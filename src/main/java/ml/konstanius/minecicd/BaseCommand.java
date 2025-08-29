package ml.konstanius.minecicd;

import org.bukkit.Bukkit;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.revwalk.RevCommit;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.*;

import static ml.konstanius.minecicd.Messages.*;
import static ml.konstanius.minecicd.MineCICD.busyLock;
import static ml.konstanius.minecicd.MineCICD.plugin;

public class BaseCommand implements CommandExecutor {
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String subCommand;
        if (args.length == 0) {
            subCommand = "help";
        } else {
            subCommand = args[0].toLowerCase(Locale.getDefault());
        }

        if (!sender.hasPermission("minecicd." + subCommand)) {
            sender.sendMessage(getRichMessage("no-permission", true, new HashMap<String, String>() {{
                put("label", label);
            }}));
            return true;
        }

        if (sender instanceof Player) {
            ((Player) sender).closeInventory();
        }

        if (busyLock) {
            sender.sendMessage(getRichMessage("busy", true));
            return true;
        }

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            switch (subCommand) {
                case "branch": {
                    if (args.length != 2) {
                        sender.sendMessage(getRichMessage("branch-usage", true, new HashMap<String, String>() {{
                            put("label", label);
                        }}));
                        return;
                    }

                    String newBranch = args[1];
                    try {
                        GitUtils.switchBranch(newBranch);
                    } catch (Exception e) {
                        MineCICD.logError(e);
                        sender.sendMessage(getRichMessage("branch-failed", true, new HashMap<String, String>() {{
                            put("error", e.getMessage());
                        }}));
                        return;
                    }

                    sender.sendMessage(getRichMessage("branch-success", true, new HashMap<String, String>() {{
                        put("branch", newBranch);
                    }}));
                    break;
                }
                case "add": {
                    if (args.length != 2) {
                        sender.sendMessage(getRichMessage("add-usage", true, new HashMap<String, String>() {{
                            put("label", label);
                        }}));
                        return;
                    }

                    String child = args[1];
                    File root = new File(new File(".").getAbsolutePath());
                    String[] children = child.split("[/\\\\]");

                    File current = root;
                    for (String c : children) {
                        File next = new File(current, c);
                        if (!next.exists()) {
                            sender.sendMessage(getRichMessage("add-failed", true, new HashMap<String, String>() {{
                                put("error", "Target path does not exist");
                            }}));
                            return;
                        }
                        current = next;
                    }

                    if (child.endsWith("/") || child.endsWith("\\")) {
                        if (!current.isDirectory()) {
                            sender.sendMessage(getRichMessage("add-failed", true, new HashMap<String, String>() {{
                                put("error", "Target path is not a directory");
                            }}));
                            return;
                        }
                    } else {
                        if (current.isDirectory()) {
                            sender.sendMessage(getRichMessage("add-failed", true, new HashMap<String, String>() {{
                                put("error", "Target path is a directory");
                            }}));
                            return;
                        }
                    }

                    int addedAmount;
                    try {
                        String author;
                        if (sender instanceof Player) {
                            author = sender.getName();
                        } else {
                            author = "Server Console";
                        }
                        addedAmount = GitUtils.add(current, author);
                    } catch (Exception e) {
                        MineCICD.logError(e);
                        sender.sendMessage(getRichMessage("add-failed", true, new HashMap<String, String>() {{
                            put("error", e.getMessage());
                        }}));
                        return;
                    }

                    sender.sendMessage(getRichMessage("add-success", true, new HashMap<String, String>() {{
                        put("amount", String.valueOf(addedAmount));
                    }}));
                    break;
                }
                case "remove": {
                    if (args.length != 2) {
                        sender.sendMessage(getRichMessage("remove-usage", true, new HashMap<String, String>() {{
                            put("label", label);
                        }}));
                        return;
                    }

                    String child = args[1];
                    File root = new File(".");
                    String[] children = child.split("[/\\\\]");

                    File current = root;
                    for (String c : children) {
                        File next = new File(current, c);
                        if (!next.exists()) {
                            sender.sendMessage(getRichMessage("remove-failed", true, new HashMap<String, String>() {{
                                put("error", "Target path does not exist");
                            }}));
                            return;
                        }
                        current = next;
                    }

                    if (child.endsWith("/") || child.endsWith("\\")) {
                        if (!current.isDirectory()) {
                            sender.sendMessage(getRichMessage("remove-failed", true, new HashMap<String, String>() {{
                                put("error", "Target path is not a directory");
                            }}));
                            return;
                        }
                    } else {
                        if (current.isDirectory()) {
                            sender.sendMessage(getRichMessage("remove-failed", true, new HashMap<String, String>() {{
                                put("error", "Target path is a directory");
                            }}));
                            return;
                        }
                    }

                    int removedAmount;
                    try {
                        String author;
                        if (sender instanceof Player) {
                            author = sender.getName();
                        } else {
                            author = "Server Console";
                        }
                        removedAmount = GitUtils.remove(current, author);
                    } catch (Exception e) {
                        MineCICD.logError(e);
                        sender.sendMessage(getRichMessage("remove-failed", true, new HashMap<String, String>() {{
                            put("error", e.getMessage());
                        }}));
                        return;
                    }

                    sender.sendMessage(getRichMessage("remove-success", true, new HashMap<String, String>() {{
                        put("amount", String.valueOf(removedAmount));
                    }}));
                    break;
                }
                case "pull": {
                    if (args.length < 1 || args.length > 2) {
                        sender.sendMessage(getRichMessage("pull-usage", true, new HashMap<String, String>() {{
                            put("label", label);
                        }}));
                        return;
                    }

                    // Dry-run preview
                    if (args.length == 2 && args[1].equalsIgnoreCase("--dry-run")) {
                        try {
                            List<String> lines = GitUtils.previewPullChanges();
                            sender.sendMessage("Pull dry-run: planned changes (remote ahead):");
                            if (lines.isEmpty()) sender.sendMessage(" - no changes");
                            int idx = 1;
                            for (String l : lines) sender.sendMessage(" " + (idx++) + ". " + l);
                        } catch (Exception e) {
                            MineCICD.logError(e);
                            sender.sendMessage(getRichMessage("pull-failed", true, new HashMap<String, String>() {{
                                put("error", e.getMessage());
                            }}));
                        }
                        return;
                    }

                    boolean forceOverwriteChanges = false;
                    if (args.length == 2 && args[1].equalsIgnoreCase("force")) {
                        forceOverwriteChanges = true;
                    } else if (args.length == 2) {
                        sender.sendMessage(getRichMessage("pull-usage", true, new HashMap<String, String>() {{
                            put("label", label);
                        }}));
                        return;
                    }

                    if (!GitUtils.getLocalChanges().isEmpty() && !forceOverwriteChanges) {
                        String bar = MineCICD.addBarFor("pull", getCleanMessage("bossbar-pull-aborted-changes", true), BarColor.YELLOW, BarStyle.SEGMENTED_12);
                        MineCICD.removeBarFor(bar, "pull");
                        sender.sendMessage(getRichMessage("pull-aborted", true, new HashMap<String, String>() {{
                            put("label", label);
                        }}));
                        for (String change : GitUtils.getLocalChanges()) {
                            sender.sendMessage(getRichMessage("pull-change", false, new HashMap<String, String>() {{
                                put("change", change);
                            }}));
                        }
                        return;
                    }

                    boolean pulled;
                    try {
                        pulled = GitUtils.pull();
                    } catch (Exception e) {
                        MineCICD.logError(e);
                        sender.sendMessage(getRichMessage("pull-failed", true, new HashMap<String, String>() {{
                            put("error", e.getMessage());
                        }}));
                        return;
                    }

                    if (pulled) {
                        sender.sendMessage(getRichMessage("pull-success", true));
                    } else {
                        sender.sendMessage(getRichMessage("pull-no-changes", true));
                    }
                    break;
                }
                case "push": {
                    if (args.length < 2) {
                        sender.sendMessage(getRichMessage("push-usage", true, new HashMap<String, String>() {{
                            put("label", label);
                        }}));
                        return;
                    }

                    // Dry-run preview
                    if (args[1].equalsIgnoreCase("--dry-run")) {
                        try {
                            List<String> changes = GitUtils.previewPushChanges();
                            sender.sendMessage("Push dry-run: local uncommitted changes:");
                            if (changes.isEmpty()) sender.sendMessage(" - no changes");
                            int idx = 1;
                            for (String l : changes) sender.sendMessage(" " + (idx++) + ". " + l);
                        } catch (Exception e) {
                            MineCICD.logError(e);
                            sender.sendMessage(getRichMessage("push-failed", true, new HashMap<String, String>() {{
                                put("error", e.getMessage());
                            }}));
                        }
                        return;
                    }

                    boolean force = false;
                    int msgStart = 1;
                    if (args[1].equalsIgnoreCase("force")) {
                        force = true;
                        if (args.length < 3) {
                            sender.sendMessage(getRichMessage("push-usage", true, new HashMap<String, String>() {{
                                put("label", label);
                            }}));
                            return;
                        }
                        msgStart = 2;
                    }

                    String message = String.join(" ", Arrays.copyOfRange(args, msgStart, args.length));
                    String author;
                    if (sender instanceof Player) {
                        author = sender.getName();
                    } else {
                        author = "Server Console";
                    }

                    try {
                        GitUtils.pushWithRetry(message, author, force);
                    } catch (Exception e) {
                        MineCICD.logError(e);
                        sender.sendMessage(getRichMessage("push-failed", true, new HashMap<String, String>() {{
                            put("error", e.getMessage());
                        }}));
                        return;
                    }

                    sender.sendMessage(getRichMessage("push-success", true));
                    break;
                }
                case "branches": {
                    try {
                        List<String> lines = GitUtils.getBranchesInfo();
                        if (lines.isEmpty()) {
                            sender.sendMessage("No repository initialized.");
                        } else {
                            sender.sendMessage("Branches ( * = current ):");
                            for (String l : lines) sender.sendMessage(l);
                        }
                    } catch (Exception e) {
                        MineCICD.logError(e);
                        sender.sendMessage("Failed to list branches: " + e.getMessage());
                    }
                    break;
                }
                case "ready": {
                    String target = "main";
                    if (args.length >= 2) {
                        target = args[1];
                    }
                    try {
                        String author = (sender instanceof Player) ? sender.getName() : "Server Console";
                        GitUtils.markReadyToMerge(target, author);
                        String finalTarget = target;
                        sender.sendMessage(getRichMessage("ready-success", true, new HashMap<String, String>() {{
                            put("target", finalTarget);
                        }}));
                    } catch (Exception e) {
                        MineCICD.logError(e);
                        sender.sendMessage(getRichMessage("ready-failed", true, new HashMap<String, String>() {{
                            put("error", e.getMessage());
                        }}));
                    }
                    break;
                }
                case "accept": {
                    // Manual confirmation to apply pending webhook changes
                    try {
                        if (MineCICD.getPendingUpdate() == null) {
                            sender.sendMessage(getRichMessage("accept-none", true));
                            return;
                        }
                        // Clear pending and trigger the usual webhook processing flow
                        MineCICD.clearPendingUpdate();
                        WebhookHandler.simulatePushEvent();
                        sender.sendMessage(getRichMessage("accept-success", true));
                    } catch (Exception e) {
                        MineCICD.logError(e);
                        sender.sendMessage(getRichMessage("accept-failed", true, new HashMap<String, String>() {{
                            put("error", e.getMessage());
                        }}));
                    }
                    break;
                }
                case "merge": {
                    if (args.length < 3 || args.length > 4) {
                        sender.sendMessage("Usage: /" + label + " merge <remote|url> <branch> [ours|theirs]");
                        return;
                    }
                    String remote = args[1];
                    String branch = args[2];
                    String pref = args.length == 4 ? args[3] : null;
                    try {
                        GitUtils.mergeExternal(remote, branch, pref);
                        sender.sendMessage("External merge applied to working tree. Review changes and push only if appropriate.");
                    } catch (Exception e) {
                        MineCICD.logError(e);
                        sender.sendMessage("Merge failed: " + e.getMessage());
                    }
                    break;
                }
                case "webhook": {
                    if (args.length == 2 && args[1].equalsIgnoreCase("test")) {
                        try {
                            WebhookHandler.simulatePushEvent();
                            sender.sendMessage("Webhook test triggered. Check notifications and console for results.");
                        } catch (Exception e) {
                            MineCICD.logError(e);
                            sender.sendMessage("Webhook test failed: " + e.getMessage());
                        }
                    } else {
                        sender.sendMessage("Usage: /" + label + " webhook test");
                    }
                    break;
                }
                case "secrets": {
                    if (args.length < 2) {
                        sender.sendMessage("Usage: /" + label + " secrets <validate|preview> [file]");
                        return;
                    }
                    String act = args[1].toLowerCase(Locale.getDefault());
                    if ("validate".equals(act)) {
                        try {
                            HashMap<String, ArrayList<GitSecret>> map = GitSecret.readFromSecretsStore();
                            List<GitSecret.ValidationIssue> issues = GitSecret.validateSecrets(map);
                            if (issues.isEmpty()) {
                                sender.sendMessage("Secrets validation OK: all placeholders present.");
                            } else {
                                sender.sendMessage("Secrets validation found issues:");
                                for (GitSecret.ValidationIssue vi : issues) {
                                    sender.sendMessage(" - " + vi.toString());
                                }
                            }
                        } catch (Exception e) {
                            MineCICD.logError(e);
                            sender.sendMessage("Validation failed: " + e.getMessage());
                        }
                    } else if ("preview".equals(act)) {
                        if (args.length < 3) {
                            sender.sendMessage("Usage: /" + label + " secrets preview <file>");
                            return;
                        }
                        String filePath = args[2];
                        try {
                            HashMap<String, ArrayList<GitSecret>> map = GitSecret.readFromSecretsStore();
                            String diff = GitSecret.previewSecrets(filePath, map);
                            for (String line : diff.split("\n")) {
                                sender.sendMessage(line);
                            }
                        } catch (Exception e) {
                            MineCICD.logError(e);
                            sender.sendMessage("Preview failed: " + e.getMessage());
                        }
                    } else {
                        sender.sendMessage("Usage: /" + label + " secrets <validate|preview> [file]");
                    }
                    break;
                }
                case "backup": {
                    if (args.length < 2) {
                        sender.sendMessage("Usage: /" + label + " backup <name> [extra path ...]");
                        return;
                    }
                    String name = args[1];
                    List<String> extras = new ArrayList<>();
                    if (args.length > 2) {
                        extras.addAll(Arrays.asList(Arrays.copyOfRange(args, 2, args.length)));
                    }
                    try {
                        File zip = GitUtils.createBackup(name, extras);
                        sender.sendMessage("Backup completed: " + zip.getPath());
                    } catch (Exception e) {
                        MineCICD.logError(e);
                        sender.sendMessage("Backup failed: " + e.getMessage());
                    }
                    break;
                }
                case "metrics": {
                    try {
                        long pulls = MineCICD.metrics.pulls;
                        long pullFails = MineCICD.metrics.pullFailures;
                        long pullAvg = pulls > 0 ? (MineCICD.metrics.pullDurationMsTotal / pulls) : 0;
                        long pushes = MineCICD.metrics.pushes;
                        long pushFails = MineCICD.metrics.pushFailures;
                        long pushAvg = pushes > 0 ? (MineCICD.metrics.pushDurationMsTotal / pushes) : 0;
                        sender.sendMessage("Metrics:");
                        sender.sendMessage(" - pulls: " + pulls + ", failures: " + pullFails + ", avg ms: " + pullAvg);
                        sender.sendMessage(" - pushes: " + pushes + ", failures: " + pushFails + ", avg ms: " + pushAvg);
                    } catch (Exception e) {
                        MineCICD.logError(e);
                        sender.sendMessage("Metrics failed: " + e.getMessage());
                    }
                    break;
                }
                case "doctor": {
                    try {
                        List<String> report = GitUtils.doctorReport();
                        for (String line : report) sender.sendMessage(line);
                    } catch (Exception e) {
                        MineCICD.logError(e);
                        sender.sendMessage("Doctor failed: " + e.getMessage());
                    }
                    break;
                }
                case "jars": {
                    if (args.length < 2) {
                        sender.sendMessage("Usage: /" + label + " jars <list|apply|clear>");
                        return;
                    }
                    String sub = args[1].toLowerCase(Locale.getDefault());
                    switch (sub) {
                        case "list":
                            if (MineCICD.stagedJarUnload.isEmpty() && MineCICD.stagedJarLoad.isEmpty()) {
                                sender.sendMessage("No staged jar operations.");
                            } else {
                                sender.sendMessage("Staged unloads: " + (MineCICD.stagedJarUnload.isEmpty()?"<none>":String.join(", ", MineCICD.stagedJarUnload)));
                                sender.sendMessage("Staged loads: " + (MineCICD.stagedJarLoad.isEmpty()?"<none>":String.join(", ", MineCICD.stagedJarLoad)));
                            }
                            break;
                        case "apply":
                            try { MineCICD.applyStagedJarOps(); sender.sendMessage("Applied staged jar operations."); }
                            catch (Exception e) { MineCICD.logError(e); sender.sendMessage("Apply failed: " + e.getMessage()); }
                            break;
                        case "clear":
                            MineCICD.stagedJarUnload.clear();
                            MineCICD.stagedJarLoad.clear();
                            sender.sendMessage("Cleared staged jar operations.");
                            break;
                        default:
                            sender.sendMessage("Usage: /" + label + " jars <list|apply|clear>");
                    }
                    break;
                }
                case "reset": {
                    boolean requireConfirm = true;
                    try { requireConfirm = Config.getBoolean("git.reset.require-confirm"); } catch (Exception ignored) {}
                    String commit = null;
                    boolean ok = false;
                    if (requireConfirm) {
                        // Expect: reset --confirm <commit>
                        if (args.length == 3 && "--confirm".equalsIgnoreCase(args[1])) {
                            commit = args[2];
                            ok = true;
                        } else {
                            sender.sendMessage("Usage: /" + label + " reset --confirm <commit hash|link>");
                            return;
                        }
                    } else {
                        if (args.length == 2) {
                            commit = args[1];
                            ok = true;
                        } else {
                            sender.sendMessage(getRichMessage("reset-usage", true, new HashMap<String, String>() {{
                                put("label", label);
                            }}));
                            return;
                        }
                    }

                    if (commit.startsWith("http")) {
                        commit = commit.substring(commit.lastIndexOf('/') + 1);
                    }

                    if (commit.length() != 40) {
                        sender.sendMessage(getRichMessage("reset-invalid-commit", true, new HashMap<String, String>() {{
                            put("label", label);
                        }}));
                        return;
                    }

                    try {
                        GitUtils.reset(commit);
                    } catch (Exception e) {
                        MineCICD.logError(e);
                        sender.sendMessage(getRichMessage("reset-failed", true, new HashMap<String, String>() {{
                            put("error", e.getMessage());
                        }}));
                        return;
                    }

                    sender.sendMessage(getRichMessage("reset-success", true));
                    break;
                }
                case "revert": {
                    if (args.length != 2) {
                        sender.sendMessage(getRichMessage("revert-usage", true, new HashMap<String, String>() {{
                            put("label", label);
                        }}));
                        return;
                    }

                    String commit = args[1];
                    if (commit.startsWith("http")) {
                        // https://github.com/Konstanius/MineCICD-Server/commit/58f873575ff6126fd2270d594882ba0a2b1545b9
                        commit = commit.substring(commit.lastIndexOf('/') + 1);
                    }

                    if (commit.length() != 40) {
                        sender.sendMessage(getRichMessage("revert-invalid-commit", true, new HashMap<String, String>() {{
                            put("label", label);
                        }}));
                        return;
                    }

                    try {
                        GitUtils.revert(commit);
                    } catch (Exception e) {
                        MineCICD.logError(e);
                        sender.sendMessage(getRichMessage("revert-failed", true, new HashMap<String, String>() {{
                            put("error", e.getMessage());
                        }}));
                        return;
                    }

                    sender.sendMessage(getRichMessage("revert-success", true));
                    break;
                }
                case "rollback": {
                    if (args.length != 3) {
                        sender.sendMessage(getRichMessage("rollback-usage", true, new HashMap<String, String>() {{
                            put("label", label);
                        }}));
                        return;
                    }

                    String date = args[1];
                    String time = args[2];
                    String dateTime = date + " " + time;
                    Calendar calendar = Calendar.getInstance();
                    calendar.setLenient(false);
                    try {
                        calendar.setTime(new SimpleDateFormat("dd.MM.yyyy HH:mm:ss").parse(dateTime));
                    } catch (Exception e) {
                        MineCICD.logError(e);
                        sender.sendMessage(getRichMessage("rollback-invalid-date", true, new HashMap<String, String>() {{
                            put("label", label);
                        }}));
                        return;
                    }

                    if (calendar.after(Calendar.getInstance())) {
                        sender.sendMessage(getRichMessage("rollback-future-date", true, new HashMap<String, String>() {{
                            put("label", label);
                        }}));
                        return;
                    }

                    try {
                        GitUtils.rollback(calendar);
                    } catch (Exception e) {
                        MineCICD.logError(e);
                        sender.sendMessage(getRichMessage("rollback-failed", true, new HashMap<String, String>() {{
                            put("error", e.getMessage());
                        }}));
                        return;
                    }

                    sender.sendMessage(getRichMessage("rollback-success", true));
                    break;
                }
                case "log": {
                    if (args.length != 2) {
                        sender.sendMessage(getRichMessage("log-usage", true, new HashMap<String, String>() {{
                            put("label", label);
                        }}));
                        return;
                    }

                    String arg = args[1];
                    int page;
                    try {
                        page = Integer.parseInt(arg);
                        try (Git git = Git.open(new File("."))) {
                            Iterable<RevCommit> allCommits = git.log().call();
                            List<RevCommit> list = new ArrayList<>();
                            for (RevCommit commit : allCommits) {
                                list.add(commit);
                            }

                            int pages = (int) Math.ceil((double) list.size() / 10);
                            if (page > pages) {
                                throw new IllegalArgumentException("Page number is too high");
                            }

                            List<RevCommit> returnable = new ArrayList<>();
                            for (int i = (page - 1) * 10; i < page * 10 && i < list.size(); i++) {
                                returnable.add(list.get(i));
                            }

                            StringBuilder messageBuilder = new StringBuilder();
                            messageBuilder.append(getMessage("log-list-header", false, new HashMap<String, String>() {{
                                put("page", String.valueOf(page));
                                put("maxPage", String.valueOf(pages));
                            }}));
                            messageBuilder.append("\n");

                            for (RevCommit commit : returnable) {
                                PersonIdent authorIdent = commit.getAuthorIdent();
                                String author = authorIdent.getName();
                                Date date = authorIdent.getWhen();
                                String message = commit.getFullMessage();

                                message = message.replaceAll("\n", "  ");

                                if (message.length() > 40) {
                                    message = message.substring(0, 40) + "...";
                                }
                                String finalMessage = message.trim();
                                messageBuilder.append(getMessage("log-list-line", false, new HashMap<String, String>() {{
                                    put("revision", commit.getName().substring(0, 7));
                                    put("author", author);
                                    put("date", new java.text.SimpleDateFormat("dd.MM.yyyy HH:mm:ss").format(date));
                                    put("message", finalMessage);
                                }}));
                                messageBuilder.append("\n");
                            }

                            messageBuilder.append(getMessage("log-list-end", false));
                            sender.sendMessage(Messages.messageToComponent(messageBuilder.toString()));
                        } catch (Exception e) {
                            MineCICD.logError(e);
                            sender.sendMessage(getRichMessage("log-failed", true, new HashMap<String, String>() {{
                                put("error", e.getMessage());
                            }}));
                            return;
                        }
                    } catch (NumberFormatException ignored) {
                        try {
                            if (arg.startsWith("http")) {
                                arg = arg.substring(arg.lastIndexOf('/') + 1);
                            }

                            if (arg.length() != 40) {
                                sender.sendMessage(getRichMessage("log-invalid-commit", true, new HashMap<String, String>() {{
                                    put("label", label);
                                }}));
                                return;
                            }

                            RevCommit commit = GitUtils.getCommit(arg);

                            try (Git git = Git.open(new File("."))) {
                                PersonIdent authorIdent = commit.getAuthorIdent();
                                String author = authorIdent.getName();
                                Date date = authorIdent.getWhen();
                                String message = commit.getFullMessage();
                                List<DiffEntry> diffs = GitUtils.getChangesBetween(git, commit, commit.getParent(0));

                                StringBuilder changesBuilder = new StringBuilder();
                                for (DiffEntry diff : diffs) {
                                    DiffEntry.ChangeType type = diff.getChangeType();
                                    String path = diff.getNewPath();
                                    switch (type) {
                                        case ADD:
                                            changesBuilder.append("&a+ ").append(path).append("\n");
                                            break;
                                        case DELETE:
                                            changesBuilder.append("&c- ").append(path).append("\n");
                                            break;
                                        case MODIFY:
                                            changesBuilder.append("&b# ").append(path).append("\n");
                                            break;
                                        case COPY:
                                        case RENAME:
                                            break;
                                    }
                                }
                                String changes = changesBuilder.toString();
                                if (changes.isEmpty()) {
                                    changes = "&7No changes";
                                } else {
                                    changes = changes.substring(0, changes.length() - 1);
                                }

                                if (message.endsWith("\n")) {
                                    message = message.substring(0, message.length() - 1);
                                }

                                String finalCommitMsg = message;
                                String finalChanges = changes;
                                String rawMsg = Messages.getMessage("log-single-commit", false, new HashMap<String, String>() {{
                                    put("revision", commit.getName());
                                    put("author", author);
                                    put("date", new java.text.SimpleDateFormat("dd.MM.yyyy HH:mm:ss").format(date));
                                    put("message", finalCommitMsg);
                                    put("changes", finalChanges);
                                }});
                                sender.sendMessage(Messages.messageToComponent(rawMsg));
                            }
                        } catch (Exception e) {
                            MineCICD.logError(e);
                            sender.sendMessage(getRichMessage("log-failed", true, new HashMap<String, String>() {{
                                put("error", e.getMessage());
                            }}));
                            return;
                        }
                    }
                    break;
                }
                case "reload": {
                    if (args.length != 1) {
                        sender.sendMessage(getRichMessage("reload-usage", true, new HashMap<String, String>() {{
                            put("label", label);
                        }}));
                        return;
                    }

                    try {
                        MineCICD.reload();
                    } catch (Exception e) {
                        MineCICD.logError(e);
                        sender.sendMessage(getRichMessage("reload-failed", true, new HashMap<String, String>() {{
                            put("error", e.getMessage());
                        }}));
                        return;
                    }
                    sender.sendMessage(getRichMessage("reload-success", true));

                    String bar = MineCICD.addBar(getCleanMessage("bossbar-reloaded", true), BarColor.GREEN, BarStyle.SOLID);
                    MineCICD.removeBar(bar, Config.getInt("bossbar.duration"));
                    break;
                }
                case "diff": {
                    if (args.length < 2 || args.length > 4) {
                        sender.sendMessage(getRichMessage("diff-usage", true, new HashMap<String, String>() {{
                            put("label", label);
                        }}));
                        return;
                    }

                    boolean isLocal = args[1].equalsIgnoreCase("local");
                    boolean isRemote = args[1].equalsIgnoreCase("remote");
                    if (!isLocal && !isRemote) {
                        sender.sendMessage(getRichMessage("diff-usage", true, new HashMap<String, String>() {{
                            put("label", label);
                        }}));
                        return;
                    }

                    int page = 1; int pageSize = 20; String prefix = null;
                    if (args.length >= 3) {
                        try { page = Integer.parseInt(args[2]); } catch (NumberFormatException nf) { prefix = args[2]; }
                    }
                    if (args.length == 4) prefix = args[3];
                    if (page < 1) page = 1;

                    List<String> changes;
                    try (Git git = Git.open(new File("."))) {
                        if (isLocal) {
                            Set<String> localChanges = GitUtils.getLocalChanges();
                            changes = new ArrayList<>(localChanges);
                            Collections.sort(changes);
                        } else {
                            List<DiffEntry> remoteChanges = GitUtils.getRemoteChanges(git);
                            changes = new ArrayList<>();
                            for (DiffEntry entry : remoteChanges) {
                                String sym = "#";
                                switch (entry.getChangeType()) {
                                    case ADD: sym = "+"; break;
                                    case DELETE: sym = "-"; break;
                                    case MODIFY: sym = "#"; break;
                                    default: break;
                                }
                                changes.add(sym + " " + entry.getNewPath());
                            }
                        }
                    } catch (Exception e) {
                        MineCICD.logError(e);
                        sender.sendMessage(getRichMessage("diff-failed", true, new HashMap<String, String>() {{
                            put("error", e.getMessage());
                        }}));
                        return;
                    }

                    if (prefix != null && !prefix.trim().isEmpty()) {
                        String pre = prefix.replace('\\', '/');
                        List<String> filtered = new ArrayList<>();
                        for (String c : changes) {
                            String path = c.startsWith("+") || c.startsWith("-") || c.startsWith("#") ? c.substring(2) : c;
                            String norm = path.replace('\\', '/');
                            if (norm.startsWith(pre)) filtered.add(c);
                        }
                        changes = filtered;
                    }

                    int total = changes.size();
                    int pages = (int) Math.ceil(total / (double) pageSize);
                    if (pages == 0) pages = 1;
                    if (page > pages) page = pages;
                    int from = (page - 1) * pageSize;
                    int to = Math.min(from + pageSize, total);

                    sender.sendMessage((isRemote ? "Remote" : "Local") + " changes (page " + page + "/" + pages + (prefix!=null? (", filter='"+prefix+"'") : "") + "):");
                    if (total == 0) {
                        sender.sendMessage(" - no changes");
                    } else {
                        for (int i = from; i < to; i++) {
                            sender.sendMessage(" " + (i + 1) + ". " + changes.get(i));
                        }
                    }
                    break;
                }
                case "status": {
                    if (args.length != 1) {
                        sender.sendMessage(getRichMessage("status-usage", true, new HashMap<String, String>() {{
                            put("label", label);
                        }}));
                        return;
                    }

                    String remote = Config.getString("git.repo");
                    String branch = Config.getString("git.branch");

                    boolean webHookEnabled = Config.getInt("webhooks.port") != 0;
                    String serverIp;
                    try {
                        URL whatismyip = new URL("https://checkip.amazonaws.com");
                        BufferedReader in = new BufferedReader(new InputStreamReader(whatismyip.openStream()));
                        serverIp = in.readLine();
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                    String webHookAddress = "http://" + serverIp + ":" + Config.getInt("webhooks.port") + "/" + Config.getString("webhooks.path");

                    int localChanges = GitUtils.getLocalChanges().size();
                    int remoteChanges;
                    try (Git git = Git.open(new File("."))) {
                        remoteChanges = GitUtils.getRemoteChanges(git).size();
                    } catch (Exception e) {
                        MineCICD.logError(e);
                        remoteChanges = -1;
                    }

                    int finalRemoteChanges = remoteChanges;
                    sender.sendMessage(getRichMessage("status", true, new HashMap<String, String>() {{
                        put("remote", remote);
                        put("branch", branch);
                        put("webhook-status", String.valueOf(webHookEnabled));
                        put("webhook-address", webHookAddress);
                        put("local-changes", String.valueOf(localChanges));
                        put("remote-changes", finalRemoteChanges == -1 ? "N/A" : String.valueOf(finalRemoteChanges));
                    }}));
                    break;
                }
                case "help": {
                    sender.sendMessage(getRichMessage("help", false, new HashMap<String, String>() {{
                        put("label", label);
                    }}));
                    break;
                }
                case "script": {
                    if (args.length != 2) {
                        sender.sendMessage(getRichMessage("script-usage", true, new HashMap<String, String>() {{
                            put("label", label);
                        }}));
                        return;
                    }

                    String script = args[1];
                    if (script.endsWith(".sh")) {
                        script = script.substring(0, script.length() - 3);
                    }
                    try {
                        Script.run(script);
                    } catch (Exception e) {
                        MineCICD.logError(e);
                        sender.sendMessage(getRichMessage("script-failed", true, new HashMap<String, String>() {{
                            put("error", e.getMessage());
                        }}));
                        return;
                    }
                    break;
                }
                case "resolve": {
                    if (args.length != 2) {
                        sender.sendMessage(getRichMessage("resolve-usage", true, new HashMap<String, String>() {{
                            put("label", label);
                        }}));
                        return;
                    }

                    switch (args[1]) {
                        case "merge-abort": {
                            try {
                                GitUtils.mergeAbort();
                            } catch (Exception e) {
                                MineCICD.logError(e);
                                sender.sendMessage(getRichMessage("resolve-failed-merge-abort", true, new HashMap<String, String>() {{
                                    put("error", e.getMessage());
                                }}));
                                return;
                            }
                            sender.sendMessage(getRichMessage("resolve-success-merge-abort", true, new HashMap<String, String>() {{
                                put("label", label);
                            }}));
                            break;
                        }
                        case "repo-reset": {
                            try {
                                GitUtils.repoReset();
                            } catch (Exception e) {
                                MineCICD.logError(e);
                                sender.sendMessage(getRichMessage("resolve-failed-repo-reset", true, new HashMap<String, String>() {{
                                    put("error", e.getMessage());
                                }}));
                                return;
                            }
                            sender.sendMessage(getRichMessage("resolve-success-repo-reset", true, new HashMap<String, String>() {{
                                put("label", label);
                            }}));
                            break;
                        }
                        case "reset-local-changes": {
                            try (Git git = Git.open(new File("."))) {
                                RevCommit head = git.log().setMaxCount(1).call().iterator().next();
                                try {
                                    GitSecret.configureGitSecretFiltering(GitSecret.readFromSecretsStore());
                                } catch (Exception ignored) {
                                }
                                GitUtils.reset(head.getName());

                                sender.sendMessage(getRichMessage("resolve-success-reset-local-changes", true));
                            } catch (Exception e) {
                                MineCICD.logError(e);
                                sender.sendMessage(getRichMessage("resolve-failed-reset-local-changes", true, new HashMap<String, String>() {{
                                    put("error", e.getMessage());
                                }}));
                                return;
                            }
                            break;
                        }
                        default: {
                            sender.sendMessage(getRichMessage("resolve-usage", true, new HashMap<String, String>() {{
                                put("label", label);
                            }}));
                            break;
                        }
                    }
                    break;
                }
                default: {
                    sender.sendMessage(getRichMessage("invalid-subcommand", true, new HashMap<String, String>() {{
                        put("label", label);
                    }}));
                    break;
                }
            }
        });

        return true;
    }
}
