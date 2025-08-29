package ml.konstanius.minecicd;

import com.sun.net.httpserver.HttpServer;
import org.apache.commons.io.FileUtils;
import org.bukkit.Bukkit;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.diff.DiffEntry;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class MineCICD extends JavaPlugin {
    // Metrics for observability
    public static class Metrics {
        public long pulls = 0;
        public long pullFailures = 0;
        public long pullDurationMsTotal = 0;
        public long pushes = 0;
        public long pushFailures = 0;
        public long pushDurationMsTotal = 0;
    }
    public static final Metrics metrics = new Metrics();
    // Redaction
    private static final java.util.Set<String> REDACTION_TOKENS = java.util.Collections.synchronizedSet(new java.util.HashSet<>());
    public static void refreshRedactionTokens() {
        try {
            REDACTION_TOKENS.clear();
            // Git credentials
            try { String pass = Config.getString("git.pass"); if (pass != null && !pass.isEmpty()) REDACTION_TOKENS.add(pass); } catch (Exception ignored) {}
            // Secrets from secrets.yml
            try {
                java.util.HashMap<String, java.util.ArrayList<GitSecret>> map = GitSecret.readFromSecretsStore();
                for (java.util.Map.Entry<String, java.util.ArrayList<GitSecret>> e : map.entrySet()) {
                    for (GitSecret s : e.getValue()) {
                        if (s.secret != null && !s.secret.isEmpty()) REDACTION_TOKENS.add(s.secret);
                    }
                }
            } catch (Exception ignored) {}
        } catch (Exception ignored) {}
    }
    public static String redact(String s) {
        if (s == null) return null;
        String out = s;
        synchronized (REDACTION_TOKENS) {
            for (String token : REDACTION_TOKENS) {
                if (token == null || token.isEmpty()) continue;
                out = out.replace(token, "****");
            }
        }
        return out;
    }
    // Staged jar operations (experimental)
    public static final java.util.Set<String> stagedJarUnload = java.util.Collections.synchronizedSet(new java.util.LinkedHashSet<>());
    public static final java.util.Set<String> stagedJarLoad = java.util.Collections.synchronizedSet(new java.util.LinkedHashSet<>());
    private static BukkitTask automationTask;
    public static FileConfiguration config;
    public static Logger logger = Logger.getLogger("MineCICD");
    public static Plugin plugin;
    public static HttpServer webServer;
    public static HashMap<String, BossBar> busyBars = new HashMap<>();
    public static boolean busyLock = false;

    @Override
    public void onEnable() {
        plugin = this;
        saveDefaultConfig();
        config = getConfig();

        if (config.get("repository-url") != null) {
            File configFile = new File(getDataFolder(), "config.yml");
            configFile.renameTo(new File(getDataFolder(), "config_old.yml"));

            File messagesFile = new File(getDataFolder(), "messages.yml");
            messagesFile.renameTo(new File(getDataFolder(), "messages_old.yml"));

            // delete all files in the data folder except for the old config and messages files
            File[] files = getDataFolder().listFiles();
            if (files != null) {
                for (File file : files) {
                    if (!file.getName().equals("config_old.yml") && !file.getName().equals("messages_old.yml")) {
                        FileUtils.deleteQuietly(file);
                    }
                }
            }

            try {
                reload();
                Migration.migrate();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        } else {
            GitUtils.loadGitIgnore();
            Messages.loadMessages();
            Script.loadDefaultScript();

            try {
                GitSecret.configureGitSecretFiltering(GitSecret.readFromSecretsStore());
            } catch (IOException | InvalidConfigurationException | InterruptedException e) {
                throw new RuntimeException(e);
            }
            // Refresh redaction tokens at startup
            try { refreshRedactionTokens(); } catch (Exception ignored) {}

            setupWebHook();
            setupAutomation();
        }

        Objects.requireNonNull(this.getCommand("minecicd")).setExecutor(new BaseCommand());
        Objects.requireNonNull(this.getCommand("minecicd")).setTabCompleter(new BaseCommandTabCompleter());

        try (Git ignored = Git.open(new File("."))) {
        } catch (Exception ignored) {
        }
    }

    public static void setupWebHook() {
        int port = config.getInt("webhooks.port");
        String path = config.getString("webhooks.path");
        if (port != 0) {
            try {
                if (webServer != null) {
                    webServer.stop(0);
                    webServer = null;
                }

                String serverIp;
                try {
                    URL whatismyip = new URL("https://checkip.amazonaws.com");
                    BufferedReader in = new BufferedReader(new InputStreamReader(whatismyip.openStream()));
                    serverIp = in.readLine();
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }

                webServer = HttpServer.create(new InetSocketAddress(port), 0);
                webServer.createContext("/" + path, new WebhookHandler());
                webServer.setExecutor(null);
                webServer.start();

                log("MineCICD is now listening on: \"http://" + serverIp + ":" + port + "/" + path + "\"", Level.INFO);
            } catch (IOException e) {
                logError(e);
            }
        } else {
            if (webServer != null) {
                webServer.stop(0);
                webServer = null;
            }
        }
    }

    public static void reload() throws GitAPIException, IOException, InvalidConfigurationException, InterruptedException {
        plugin.saveDefaultConfig();

        for (String type : busyBars.keySet()) {
            removeBar(type, 0);
        }

        Config.reload();
        Messages.loadMessages();
        GitUtils.loadGitIgnore();
        Script.loadDefaultScript();
        GitUtils.setBranchIfInited();
        GitSecret.configureGitSecretFiltering(GitSecret.readFromSecretsStore());
        // Refresh redaction tokens on reload
        try { refreshRedactionTokens(); } catch (Exception ignored) {}
        setupWebHook();
        setupAutomation();
    }

    @Override
    public void onDisable() {
        for (String type : busyBars.keySet()) {
            removeBar(type, 0);
        }

        if (webServer != null) {
            webServer.stop(0);
            log("MineCICD stopped listening.", Level.INFO);
        }
    }

    public static void log(String l, Level level) {
        String msg = redact(l);
        logger.log(level, msg);
    }

    public static void logError(Exception e) {
        String msg = redact(e.getMessage());
        logger.log(Level.SEVERE, msg, e);
        StringBuilder stackTrace = new StringBuilder();
        for (StackTraceElement element : e.getStackTrace()) {
            stackTrace.append(element.toString()).append("\n");
        }
        logger.log(Level.SEVERE, redact(stackTrace.toString()));
    }

    public static String addBar(String title, BarColor color, BarStyle style) {
        if (!Config.getBoolean("bossbar.enabled")) return "";
        String random = String.valueOf(System.currentTimeMillis());

        MineCICD.busyBars.put(random, Bukkit.createBossBar(title, color, style));

        ArrayList<Player> players = new ArrayList<>(Bukkit.getOnlinePlayers());
        players.removeIf(player -> !player.hasPermission("minecicd.notify"));

        for (Player player : players) {
            MineCICD.busyBars.get(random).addPlayer(player);
        }

        return random;
    }

    public static void changeBar(String type, String title, BarColor color, BarStyle style) {
        if (!Config.getBoolean("bossbar.enabled")) return;
        if (!MineCICD.busyBars.containsKey(type)) return;

        MineCICD.busyBars.get(type).setTitle(title);
        MineCICD.busyBars.get(type).setColor(color);
        MineCICD.busyBars.get(type).setStyle(style);
    }

    public static void removeBar(String type, int delay) {
        if (!Config.getBoolean("bossbar.enabled")) return;
        if (!MineCICD.busyBars.containsKey(type)) return;

        if (delay > 0) {
            BossBar bar = MineCICD.busyBars.get(type);
            BukkitTask task = Bukkit.getScheduler().runTaskTimer(MineCICD.plugin, () -> {
                double currentProgress = bar.getProgress();
                currentProgress -= (1.0 / (double) delay);
                if (currentProgress < 0) {
                    currentProgress = 0;
                }
                bar.setProgress(currentProgress);
            }, 1, 1);
            Bukkit.getScheduler().runTaskLater(MineCICD.plugin, () -> {
                MineCICD.busyBars.get(type).removeAll();
                MineCICD.busyBars.remove(type);
                task.cancel();
            }, delay);
        } else {
            MineCICD.busyBars.get(type).removeAll();
            MineCICD.busyBars.remove(type);
        }
    }

    // New helpers: per-action bossbar toggles and durations
    public static boolean isBossbarEnabledFor(String action) {
        try {
            String key = "bossbar.enabled." + action;
            if (config.contains(key)) {
                return Config.getBoolean(key);
            }
        } catch (Exception ignored) {}
        return Config.getBoolean("bossbar.enabled");
    }
    public static int getBossbarDurationFor(String action) {
        try {
            String key = "bossbar.duration." + action;
            if (config.contains(key)) {
                return Config.getInt(key);
            }
        } catch (Exception ignored) {}
        return Config.getInt("bossbar.duration");
    }
    public static String addBarFor(String action, String title, BarColor color, BarStyle style) {
        if (!isBossbarEnabledFor(action)) return "";
        return addBar(title, color, style);
    }
    public static void removeBarFor(String id, String action) {
        removeBar(id, getBossbarDurationFor(action));
    }

    public static void applyStagedJarOps() {
        // Execute unloads then loads in main thread using PlugMan
        try {
            Bukkit.getScheduler().callSyncMethod(MineCICD.plugin, () -> {
                for (String p : new java.util.ArrayList<>(stagedJarUnload)) {
                    try {
                        plugin.getServer().dispatchCommand(plugin.getServer().getConsoleSender(), "plugman unload " + p);
                    } catch (Exception e) { MineCICD.log("Failed to unload plugin " + p, Level.SEVERE); MineCICD.logError(e); }
                }
                for (String p : new java.util.ArrayList<>(stagedJarLoad)) {
                    try {
                        plugin.getServer().dispatchCommand(plugin.getServer().getConsoleSender(), "plugman load " + p);
                    } catch (Exception e) { MineCICD.log("Failed to load plugin " + p, Level.SEVERE); MineCICD.logError(e); }
                }
                return null;
            }).get();
        } catch (Exception e) {
            MineCICD.logError(e);
        } finally {
            stagedJarUnload.clear();
            stagedJarLoad.clear();
        }
    }

    public static void setupAutomation() {
        // Cancel previous
        if (automationTask != null) {
            try { automationTask.cancel(); } catch (Exception ignored) {}
            automationTask = null;
        }
        boolean enabled = false;
        try { enabled = Config.getBoolean("automation.pull.enabled"); } catch (Exception ignored) {}
        if (!enabled) return;
        String cron = Config.getString("automation.pull.cron");
        boolean dryRun = Config.getBoolean("automation.pull.dry-run");
        int minutes = 0;
        if (cron != null) {
            cron = cron.trim();
            // Support pattern: "*/N * * * *"
            if (cron.matches("\\*/\\d+ \\* \\* \\* \\*")) {
                String num = cron.split(" ")[0].substring(2);
                try { minutes = Integer.parseInt(num); } catch (NumberFormatException ignored) {}
            }
        }
        if (minutes <= 0) minutes = 5; // default every 5 minutes
        long ticks = minutes * 60L * 20L;
        automationTask = Bukkit.getScheduler().runTaskTimerAsynchronously(MineCICD.plugin, () -> {
            try {
                if (dryRun) {
                    // Fetch and list remote changes vs current
                    try (Git git = Git.open(new File("."))) {
                        List<DiffEntry> diffs = GitUtils.getRemoteChanges(git);
                        if (diffs.isEmpty()) {
                            MineCICD.log("[Scheduler] Dry-run pull: no remote changes.", Level.INFO);
                        } else {
                            MineCICD.log("[Scheduler] Dry-run pull: " + diffs.size() + " change(s) available.", Level.INFO);
                        }
                    }
                } else {
                    boolean changed = GitUtils.pullWithRetry();
                    MineCICD.log("[Scheduler] Pull completed. Changes applied: " + changed, Level.INFO);
                }
            } catch (Exception e) {
                MineCICD.log("[Scheduler] Pull failed: " + e.getMessage(), Level.SEVERE);
                MineCICD.logError(e);
            }
        }, ticks, ticks);
    }
}
