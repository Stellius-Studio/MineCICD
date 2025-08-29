package ml.konstanius.minecicd;

import org.apache.commons.io.FileUtils;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.configuration.InvalidConfigurationException;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.ResetCommand;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.NoHeadException;
import org.eclipse.jgit.api.errors.RefAlreadyExistsException;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.merge.ContentMergeStrategy;
import org.eclipse.jgit.merge.MergeStrategy;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevTree;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.URIish;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.eclipse.jgit.treewalk.AbstractTreeIterator;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.treewalk.TreeWalk;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.logging.Level;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static ml.konstanius.minecicd.Messages.getCleanMessage;
import static ml.konstanius.minecicd.MineCICD.busyLock;

public abstract class GitUtils {
    // Determine plugin name from a jar (plugin.yml) or from path/file name as fallback
    public static String detectPluginName(String pathOrFile) {
        try {
            File f = new File(pathOrFile);
            if (f.isFile() && f.getName().endsWith(".jar")) {
                try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(f)) {
                    java.util.zip.ZipEntry entry = zip.getEntry("plugin.yml");
                    if (entry != null) {
                        try (java.io.InputStream is = zip.getInputStream(entry);
                             java.io.BufferedReader br = new java.io.BufferedReader(new java.io.InputStreamReader(is, StandardCharsets.UTF_8))) {
                            String line;
                            while ((line = br.readLine()) != null) {
                                line = line.trim();
                                if (line.toLowerCase(Locale.ROOT).startsWith("name:")) {
                                    String name = line.substring(5).trim();
                                    if (name.startsWith("\"") && name.endsWith("\"")) name = name.substring(1, name.length()-1);
                                    return name;
                                }
                            }
                        }
                    }
                } catch (Exception ignored) {}
            }
            // Fallback: plugins/<plugin>/plugin.yml file
            if (pathOrFile.contains("plugins" + File.separator) && pathOrFile.endsWith("plugin.yml")) {
                try {
                    List<String> lines = Files.readAllLines(new File(pathOrFile).toPath(), StandardCharsets.UTF_8);
                    for (String line : lines) {
                        String t = line.trim();
                        if (t.toLowerCase(Locale.ROOT).startsWith("name:")) {
                            String name = t.substring(5).trim();
                            if (name.startsWith("\"") && name.endsWith("\"")) name = name.substring(1, name.length()-1);
                            return name;
                        }
                    }
                } catch (Exception ignored) {}
            }
            // Last resort: strip filename
            String fn = new File(pathOrFile).getName();
            int idx = fn.indexOf("-"); if (idx == -1) idx = fn.indexOf(" "); if (idx == -1) idx = fn.indexOf("_"); if (idx == -1) idx = fn.indexOf(".");
            if (idx != -1) fn = fn.substring(0, idx);
            return fn;
        } catch (Exception e) {
            return pathOrFile;
        }
    }

    private static void stageOrExecuteUnload(String pluginName) {
        boolean staging = false;
        try { staging = Config.getBoolean("experimental-jar-staging"); } catch (Exception ignored) {}
        if (staging) {
            MineCICD.stagedJarUnload.add(pluginName);
            return;
        }
        try {
            String name = pluginName;
            MineCICD.plugin.getServer().getScheduler().callSyncMethod(MineCICD.plugin, () -> {
                try {
                    MineCICD.plugin.getServer().dispatchCommand(MineCICD.plugin.getServer().getConsoleSender(), "plugman unload " + name);
                } catch (Exception e) {
                    MineCICD.log("Failed to unload plugin " + name, Level.SEVERE);
                    MineCICD.logError(e);
                }
                return null;
            }).get();
        } catch (Exception e) {
            MineCICD.log("Failed to unload plugin " + pluginName, Level.SEVERE);
            MineCICD.logError(e);
        }
    }
    private static void stageOrExecuteLoad(String pluginName) {
        boolean staging = false;
        try { staging = Config.getBoolean("experimental-jar-staging"); } catch (Exception ignored) {}
        if (staging) {
            MineCICD.stagedJarLoad.add(pluginName);
            return;
        }
        try {
            String name = pluginName;
            MineCICD.plugin.getServer().getScheduler().callSyncMethod(MineCICD.plugin, () -> {
                try {
                    MineCICD.plugin.getServer().dispatchCommand(MineCICD.plugin.getServer().getConsoleSender(), "plugman load " + name);
                } catch (Exception e) {
                    MineCICD.log("Failed to load plugin " + name, Level.SEVERE);
                    MineCICD.logError(e);
                }
                return null;
            }).get();
        } catch (Exception e) {
            MineCICD.log("Failed to load plugin " + pluginName, Level.SEVERE);
            MineCICD.logError(e);
        }
    }
    public static CredentialsProvider getCredentials() {
        String user = Config.getString("git.user");
        if (user.isEmpty()) {
            throw new IllegalStateException("Git user is not set");
        }

        String pass = Config.getString("git.pass");
        if (pass.isEmpty()) {
            throw new IllegalStateException("Git password is not set");
        }
        return new UsernamePasswordCredentialsProvider(user, pass);
    }

    public static void loadGitIgnore() {
        File gitIgnoreFile = new File(new File("."), ".gitignore");
        if (gitIgnoreFile.exists()) return;

        InputStreamReader reader = new InputStreamReader(Objects.requireNonNull(MineCICD.plugin.getResource(".gitignore")), StandardCharsets.UTF_8);

        Scanner scanner = new Scanner(reader);
        try {
            Files.write(gitIgnoreFile.toPath(), scanner.useDelimiter("\\A").next().getBytes());
        } catch (IOException e) {
            MineCICD.log("Failed to write .gitignore", Level.SEVERE);
            MineCICD.logError(e);
        }
    }

    public static int getIndex(List<String> list, String value) {
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).equals(value)) {
                return i;
            }
        }
        return 0;
    }

    public static void allowInGitIgnore(String path, boolean isDirectory) throws IOException {
        String gitString = path.replace("\\", "/");
        if (isDirectory) {
            gitString = gitString.replaceAll("/\\*$", "") + "/*";
        }

        File gitIgnoreFile = new File(new File("."), ".gitignore");
        if (!gitIgnoreFile.exists()) {
            throw new IllegalStateException(".gitignore does not exist");
        }

        List<String> lines = Files.readAllLines(gitIgnoreFile.toPath());

        int endIndex = getIndex(lines, "# MineCICD GITIGNORE PART END MARKER");
        int startIndex = getIndex(lines, "# MineCICD GITIGNORE PART BEGIN MARKER");
        if (endIndex == 0 || startIndex == 0) {
            throw new IllegalStateException("MineCICD PART markers not found in .gitignore");
        }

        // remove whatever excludes within the path
        String trimStart = gitString;
        if (isDirectory) {
            trimStart = trimStart.substring(0, trimStart.length() - 2);
        }
        for (int i = startIndex; i < endIndex + 1; i++) {
            if (lines.get(i).startsWith("!/" + trimStart) || lines.get(i).startsWith("/" + trimStart)) {
                lines.remove(i);
                i--;
                endIndex--;
            }
        }

        // allow this exact path
        String inclusionRule = "!/" + gitString + "*";
        lines.add(startIndex + 1, inclusionRule);

        fixAndSaveGitIgnore(lines, gitIgnoreFile);
    }

    public static void removeFromGitIgnore(String path, boolean isDirectory) throws IOException {
        String gitString = path.replace("\\", "/");
        if (isDirectory) {
            gitString = gitString.replaceAll("/\\*$", "") + "/*";
        }

        File gitIgnoreFile = new File(new File("."), ".gitignore");
        if (!gitIgnoreFile.exists()) {
            throw new IllegalStateException(".gitignore does not exist");
        }

        List<String> lines = Files.readAllLines(gitIgnoreFile.toPath());

        int endIndex = getIndex(lines, "# MineCICD GITIGNORE PART END MARKER");
        int startIndex = getIndex(lines, "# MineCICD GITIGNORE PART BEGIN MARKER");
        if (endIndex == 0 || startIndex == 0) {
            throw new IllegalStateException("MineCICD PART markers not found in .gitignore");
        }

        // remove whatever else includes within this path
        String trimStart = gitString;
        if (isDirectory) {
            trimStart = trimStart.substring(0, trimStart.length() - 2);
        }
        for (int i = startIndex; i < endIndex; i++) {
            if (lines.get(i).startsWith("!/" + trimStart) || lines.get(i).startsWith("/" + trimStart)) {
                lines.remove(i);
                i--;
                endIndex--;
            }
        }

        lines.add(endIndex, "/" + gitString);
        if (isDirectory) {
            lines.add(endIndex, "!/" + gitString + "/");
        }

        fixAndSaveGitIgnore(lines, gitIgnoreFile);
    }

    public static void fixAndSaveGitIgnore(List<String> fileLines, File gitIgnoreFile) throws IOException {
        // remove duplicates
        Set<String> set = new HashSet<>();
        ArrayList<String> newLines = new ArrayList<>();
        for (String line : fileLines) {
            if (line.isEmpty() || set.add(line)) {
                newLines.add(line);
            }
        }

        int endIndex = getIndex(newLines, "# MineCICD GITIGNORE PART END MARKER");
        int startIndex = getIndex(newLines, "# MineCICD GITIGNORE PART BEGIN MARKER");

        // sort the sublist between markers
        List<String> subListParted = newLines.subList(startIndex + 1, endIndex);
        subListParted.sort((o1, o2) -> {
            // sort, so that "a" is before "!a", but "b" is after "!a"
            boolean o1Ex = o1.startsWith("!");
            boolean o2Ex = o2.startsWith("!");

            String o1Sub = o1Ex ? o1.substring(1) : o1;
            String o2Sub = o2Ex ? o2.substring(1) : o2;

            // remove trailing *
            if (o1Sub.endsWith("*")) {
                o1Sub = o1Sub.substring(0, o1Sub.length() - 1);
            }
            if (o2Sub.endsWith("*")) {
                o2Sub = o2Sub.substring(0, o2Sub.length() - 1);
            }

            int compare = o1Sub.compareTo(o2Sub);
            if (compare == 0) {
                return Boolean.compare(o1Ex, o2Ex);
            }
            return compare;
        });

        Files.write(gitIgnoreFile.toPath(), newLines);
    }

    public static boolean activeRepoExists() {
        File repoFolder = new File(".");
        return repoFolder.exists() && new File(repoFolder, ".git").exists() && new File(repoFolder, ".gitignore").exists();
    }

    public static String getCurrentRevision() {
        if (!activeRepoExists()) {
            return "";
        }

        try (Git git = Git.open(new File("."))) {
            return git.log().setMaxCount(1).call().iterator().next().getName();
        } catch (NoHeadException ignored) {
            return "";
        } catch (Exception e) {
            MineCICD.log("Failed to get current revision", Level.SEVERE);
            MineCICD.logError(e);
            return "";
        }
    }

    public static String getLatestRemoteRevision() {
        if (!activeRepoExists()) {
            return "";
        }

        try (Git git = Git.open(new File("."))) {
            git.fetch().setCredentialsProvider(getCredentials()).call();
            return git.log().setMaxCount(1).add(git.getRepository().resolve("origin/" + Config.getString("git.branch"))).call().iterator().next().getName();
        } catch (Exception e) {
            MineCICD.log("Failed to get latest remote revision", Level.SEVERE);
            MineCICD.logError(e);
            return "";
        }
    }

    public static Set<String> getLocalChanges() {
        if (!activeRepoExists()) {
            return new HashSet<>();
        }

        try (Git git = Git.open(new File("."))) {
            git.add().addFilepattern(".").call();
            return git.status().call().getUncommittedChanges();
        } catch (Exception e) {
            MineCICD.log("Failed to check for changes", Level.SEVERE);
            MineCICD.logError(e);
            throw new IllegalStateException("Failed to check for changes");
        }
    }

    public static List<DiffEntry> getRemoteChanges(Git git) throws GitAPIException, IOException {
        boolean ownsBusy = !busyLock;
        if (ownsBusy) busyLock = true;
        try {
            git.fetch().setCredentialsProvider(getCredentials()).call();

            ObjectId oldRevId = git.getRepository().resolve("HEAD");
            ObjectId newRevId = git.getRepository().resolve("origin/" + Config.getString("git.branch"));
            return getChangesBetween(git, oldRevId, newRevId);
        } finally {
            if (ownsBusy) busyLock = false;
        }
    }

    public static List<DiffEntry> getChangesBetween(Git git, ObjectId oldRevId, ObjectId newRevId) throws IOException {
        AbstractTreeIterator oldTreeParser = prepareTreeParser(git.getRepository(), oldRevId);
        AbstractTreeIterator newTreeParser = prepareTreeParser(git.getRepository(), newRevId);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        DiffFormatter diffFormatter = new DiffFormatter(out);
        diffFormatter.setRepository(git.getRepository());

        diffFormatter.close();
        return diffFormatter.scan(oldTreeParser, newTreeParser);
    }

    public static boolean pull() throws GitAPIException, URISyntaxException, IOException, InvalidConfigurationException, InterruptedException {
        boolean ownsBusy = !busyLock;
        if (ownsBusy) busyLock = true;

        String bar = MineCICD.addBarFor("pull", getCleanMessage("bossbar-pulling", true), BarColor.BLUE, BarStyle.SOLID);

        long startNs = System.nanoTime();
        boolean success = false;
        try {
            String repo = Config.getString("git.repo");
            if (repo.isEmpty()) {
                throw new IllegalStateException("Git repository is not set");
            }

            String branch = Config.getString("git.branch");
            if (branch.isEmpty()) {
                throw new IllegalStateException("Git branch is not set");
            }

            boolean changes;
            String oldCommit = getCurrentRevision();
            if (!activeRepoExists()) {
                try (Git git = Git.init().setDirectory(new File(".")).call()) {
                    GitSecret.configureGitSecretFiltering(GitSecret.readFromSecretsStore());
                    git.remoteAdd().setName("origin").setUri(new URIish(repo)).call();
                    git.fetch().setCredentialsProvider(getCredentials()).call();

                    boolean newRepo = true;
                    if (git.branchList().call().stream().anyMatch(ref -> ref.getName().equals("refs/remotes/origin/" + branch))) {
                        // Remote branch exists: align local to it and checkout before any commits
                        git.reset().setMode(ResetCommand.ResetType.HARD).setRef("origin/" + branch).call();
                        // Ensure local branch exists
                        try {
                            git.branchCreate().setName(branch).call();
                        } catch (RefAlreadyExistsException ignored) {
                        }
                        git.checkout().setName(branch).call();
                        newRepo = false;
                    } else {
                        // Remote branch doesn't exist yet: create and checkout the configured branch locally BEFORE first commit
                        try {
                            git.branchCreate().setName(branch).call();
                        } catch (RefAlreadyExistsException ignored) {
                        }
                        git.checkout().setName(branch).call();
                    }

                    // Re-configure secrets filters after potential reset/checkout (reset may have removed .gitattributes)
                    try {
                        GitSecret.configureGitSecretFiltering(GitSecret.readFromSecretsStore());
                    } catch (Exception ignored) {
                    }

                    // Force re-checkout with filters so placeholders get smudged on initial setup
                    try {
                        git.reset().setMode(ResetCommand.ResetType.HARD).setRef("HEAD").call();
                    } catch (Exception ignored) {
                    }

                    git.add().addFilepattern(".gitignore").call();
                    // Ensure .gitattributes is tracked so smudge/clean filters persist across resets and on fresh servers
                    try {
                        git.add().addFilepattern(".gitattributes").call();
                    } catch (Exception ignored) {
                    }
                    if (!getLocalChanges().isEmpty() || newRepo) {
                        git.commit().setAuthor("MineCICD", "MineCICD").setMessage("MineCICD initial setup commit").call();
                        // Push current branch (already checked out to the configured branch)
                        git.push().setCredentialsProvider(getCredentials()).call();

                        if (Config.getBoolean("experimental-jar-loading")) {
                            File pluginsFolder = new File(new File("."), "plugins");
                            if (pluginsFolder.exists()) {
                                File[] files = pluginsFolder.listFiles();
                                if (files != null) {
                                    for (File file : files) {
                                        if (file.getName().endsWith(".jar") && !file.getName().contains("MineCICD") && !file.getName().contains("PlugMan")) {
                                            String pluginName = detectPluginName(file.getPath());
                                            stageOrExecuteUnload(pluginName);
                                        }
                                    }
                                }
                            }
                        }

                        git.pull().setStrategy(MergeStrategy.THEIRS).setCredentialsProvider(getCredentials()).setContentMergeStrategy(ContentMergeStrategy.THEIRS).call();

                        if (Config.getBoolean("experimental-jar-loading")) {
                            File pluginsFolder = new File(new File("."), "plugins");
                            if (pluginsFolder.exists()) {
                                File[] files = pluginsFolder.listFiles();
                                if (files != null) {
                                    for (File file : files) {
                                        if (file.getName().endsWith(".jar") && !file.getName().contains("MineCICD") && !file.getName().contains("PlugMan")) {
                                            String pluginName = detectPluginName(file.getPath());
                                            stageOrExecuteLoad(pluginName);
                                        }
                                    }
                                }
                            }
                        }
                    }
                    String newCommit = git.log().setMaxCount(1).call().iterator().next().getName();
                    changes = !newCommit.equals(oldCommit);
                }
            } else {
                try (Git git = Git.open(new File("."))) {
                    // Ensure secrets filters are configured prior to pulling so smudge applies during checkout/merge
                    try {
                        GitSecret.configureGitSecretFiltering(GitSecret.readFromSecretsStore());
                    } catch (Exception ignored) {
                    }
                    // fetch which files are going to be changed by pulling (where remote is ahead of local)
                    String current = getCurrentRevision();
                    String latestRemote = getLatestRemoteRevision();
                    ArrayList<String> toDisable = new ArrayList<>();
                    ArrayList<String> toEnable = new ArrayList<>();
                    if (Config.getBoolean("experimental-jar-loading")) {
                        if (!current.equals(latestRemote)) {
                            List<DiffEntry> diffs = getRemoteChanges(git);

                            for (DiffEntry diff : diffs) {
                                String path = diff.getNewPath();
                                if (!path.startsWith("plugins/")) continue;
                                if (!path.endsWith(".jar")) continue;

                                File file = new File(path);

                                if (diff.getChangeType() == DiffEntry.ChangeType.ADD || diff.getChangeType() == DiffEntry.ChangeType.MODIFY || diff.getChangeType() == DiffEntry.ChangeType.RENAME) {
                                    toEnable.add(file.getName());
                                }

                                if (diff.getChangeType() == DiffEntry.ChangeType.DELETE || diff.getChangeType() == DiffEntry.ChangeType.MODIFY || diff.getChangeType() == DiffEntry.ChangeType.RENAME) {
                                    toDisable.add(file.getName());
                                }
                            }

                            if (!toDisable.isEmpty()) {
                                // Disable these plugins
                                for (String plugin : toDisable) {
                                    String pluginName = detectPluginName(new File("plugins", plugin).getPath());
                                    stageOrExecuteUnload(pluginName);
                                }
                            }
                        }
                    }

                    git.pull().setStrategy(MergeStrategy.THEIRS).setCredentialsProvider(getCredentials()).setContentMergeStrategy(ContentMergeStrategy.THEIRS).call();
                    String newCommit = git.log().setMaxCount(1).call().iterator().next().getName();
                    changes = !newCommit.equals(oldCommit);

                    if (Config.getBoolean("experimental-jar-loading")) {
                        if (!toEnable.isEmpty()) {
                            // Enable these plugins
                            for (String plugin : toEnable) {
                                String pluginName = detectPluginName(new File("plugins", plugin).getPath());
                                stageOrExecuteLoad(pluginName);
                            }
                        }
                    }
                }
            }

            if (changes) {
                MineCICD.changeBar(bar, getCleanMessage("bossbar-pulled-changes", true), BarColor.GREEN, BarStyle.SOLID);
            } else {
                MineCICD.changeBar(bar, getCleanMessage("bossbar-pulled-no-changes", true), BarColor.GREEN, BarStyle.SOLID);
            }
            MineCICD.removeBarFor(bar, "pull");
            success = true;
            try {
                if (changes) {
                    MineCICD.queueRestartIfConfigured();
                }
            } catch (Exception ignored) {}
            return changes;
        } catch (Exception e) {
            if (!(e instanceof IllegalStateException)) {
                MineCICD.log("Failed to pull changes", Level.SEVERE);
                MineCICD.logError(e);
                MineCICD.changeBar(bar, getCleanMessage("bossbar-pull-failed", true), BarColor.RED, BarStyle.SEGMENTED_12);
                MineCICD.removeBarFor(bar, "pull");
            } else {
                MineCICD.removeBar(bar, 0);
            }
            throw e;
        } finally {
            try {
                long durMs = (System.nanoTime() - startNs) / 1_000_000L;
                MineCICD.metrics.pullDurationMsTotal += durMs;
                if (success) MineCICD.metrics.pulls++; else MineCICD.metrics.pullFailures++;
            } catch (Exception ignored) {}
            if (ownsBusy) busyLock = false;
        }
    }

    private static AbstractTreeIterator prepareTreeParser(Repository repository, ObjectId objectId) throws IOException {
        // Prepare the tree parser
        CanonicalTreeParser treeParser = new CanonicalTreeParser();
        try (org.eclipse.jgit.revwalk.RevWalk walk = new org.eclipse.jgit.revwalk.RevWalk(repository)) {
            org.eclipse.jgit.revwalk.RevCommit commit = walk.parseCommit(objectId);
            org.eclipse.jgit.revwalk.RevTree tree = walk.parseTree(commit.getTree().getId());
            try (org.eclipse.jgit.treewalk.TreeWalk treeWalk = new org.eclipse.jgit.treewalk.TreeWalk(repository)) {
                treeWalk.addTree(tree);
                treeParser.reset(treeWalk.getObjectReader(), tree);
            }
        }
        return treeParser;
    }

    public static void push(String message, String author) throws Exception {
        push(message, author, false);
    }

    private static <T> T withRetry(java.util.concurrent.Callable<T> op, String opName) throws Exception {
        int maxAttempts =  Math.max(1, Optional.ofNullable(Config.getInt("git.retry.max-attempts")).orElse(3));
        long baseDelay = Math.max(0, Optional.ofNullable(Config.getInt("git.retry.base-delay-ms")).orElse(1000));
        long maxDelay = Math.max(baseDelay, Optional.ofNullable(Config.getInt("git.retry.max-delay-ms")).orElse(10000));
        Exception last = null;
        long delay = baseDelay;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return op.call();
            } catch (Exception e) {
                last = e;
                if (attempt >= maxAttempts) break;
                try { Thread.sleep(delay); } catch (InterruptedException ie) { /* ignore */ }
                delay = Math.min(maxDelay, delay * 2);
            }
        }
        throw last != null ? last : new Exception(opName + " failed without exception");
    }

    public static List<String> previewPullChanges() throws Exception {
        if (!activeRepoExists()) {
            throw new IllegalStateException("Repository has to be pulled (cloned) before previewing pull.");
        }
        try (Git git = Git.open(new File("."))) {
            List<DiffEntry> diffs = getRemoteChanges(git);
            List<String> lines = new ArrayList<>();
            for (DiffEntry d : diffs) {
                String pfx = "# ";
                switch (d.getChangeType()) {
                    case ADD: pfx = "+ "; break;
                    case DELETE: pfx = "- "; break;
                    case MODIFY: pfx = "# "; break;
                    default: break;
                }
                lines.add(pfx + d.getNewPath());
            }
            return lines;
        }
    }

    public static List<String> previewPushChanges() throws Exception {
        if (!activeRepoExists()) {
            throw new IllegalStateException("Repository has to be pulled (cloned) before previewing push.");
        }
        try (Git git = Git.open(new File("."))) {
            git.add().addFilepattern(".").call();
            Set<String> changes = git.status().call().getUncommittedChanges();
            return new ArrayList<>(changes);
        }
    }

    public static boolean pullWithRetry() throws Exception {
        return withRetry(() -> {
            try {
                return pull();
            } catch (Exception e) {
                throw e;
            }
        }, "pull");
    }

    public static void pushWithRetry(String message, String author, boolean force) throws Exception {
        withRetry(() -> {
            push(message, author, force);
            return Boolean.TRUE;
        }, "push");
    }

    public static void push(String message, String author, boolean force) throws Exception {
        if (!activeRepoExists()) {
            throw new IllegalStateException("Repository has to be pulled (cloned) before changes can be pushed.");
        }

        boolean ownsBusy = !busyLock;
        if (ownsBusy) busyLock = true;

        String bar = MineCICD.addBarFor("push", getCleanMessage("bossbar-pushing", true), BarColor.BLUE, BarStyle.SOLID);

        long startNs = System.nanoTime();
        boolean success = false;
        try {
            // TODO check if all remote commits have been pulled first

            try (Git git = Git.open(new File("."))) {
                // Safety: block push on protected branches depending on config
                String mode = Optional.ofNullable(Config.getString("git.protected-push-mode")).orElse("off").trim().toLowerCase(Locale.ROOT);
                String protectedCsv = Optional.ofNullable(Config.getString("git.protected-branches")).orElse("");
                Set<String> protectedBranches = new HashSet<>();
                for (String b : protectedCsv.split(",")) {
                    if (!b.trim().isEmpty()) protectedBranches.add(b.trim());
                }

                String currentBranch;
                try {
                    currentBranch = git.getRepository().getBranch();
                } catch (Exception ex) {
                    currentBranch = Config.getString("git.branch");
                }

                // Merge-external safety marker
                File marker = new File(".minecicd-merged-externals");
                if (marker.exists() && !force) {
                    throw new IllegalStateException("Push blocked: repository contains merged external changes. Use 'push force <message>' if you really intend to push.");
                }

                if (protectedBranches.contains(currentBranch)) {
                    if ("block".equals(mode)) {
                        throw new IllegalStateException("Push to protected branch '" + currentBranch + "' is blocked by configuration.");
                    } else if ("require-force".equals(mode) && !force) {
                        throw new IllegalStateException("Push to protected branch '" + currentBranch + "' requires --force (use 'push force <message>').");
                    }
                }

                git.add().addFilepattern(".").call();

                boolean changes = !getLocalChanges().isEmpty();
                if (!changes) {
                    MineCICD.changeBar(bar, getCleanMessage("bossbar-push-no-changes", true), BarColor.GREEN, BarStyle.SOLID);
                    MineCICD.removeBarFor(bar, "push");
                    throw new IllegalStateException("No changes to push");
                }

                RevCommit commit = git.commit().setAll(true).setAuthor(author, author).setMessage(message).call();
                git.push().add(commit.getName()).setCredentialsProvider(getCredentials()).call();
            }

            MineCICD.changeBar(bar, getCleanMessage("bossbar-pushed", true), BarColor.GREEN, BarStyle.SOLID);
            MineCICD.removeBarFor(bar, "push");
            success = true;
        } catch (Exception e) {
            if (!(e instanceof IllegalStateException)) {
                MineCICD.log("Failed to push changes", Level.SEVERE);
                MineCICD.logError(e);
                MineCICD.changeBar(bar, getCleanMessage("bossbar-push-failed", true), BarColor.RED, BarStyle.SEGMENTED_12);
                MineCICD.removeBarFor(bar, "push");
            }
            throw e;
        } finally {
            try {
                long durMs = (System.nanoTime() - startNs) / 1_000_000L;
                MineCICD.metrics.pushDurationMsTotal += durMs;
                if (success) MineCICD.metrics.pushes++; else MineCICD.metrics.pushFailures++;
            } catch (Exception ignored) {}
            if (ownsBusy) busyLock = false;
        }
    }

    public static void markReadyToMerge(String targetBranch, String author) throws Exception {
        if (!activeRepoExists()) {
            throw new IllegalStateException("Repository has to be pulled (cloned) before marking ready.");
        }
        boolean ownsBusy = !busyLock;
        if (ownsBusy) busyLock = true;
        String bar = MineCICD.addBarFor("push", getCleanMessage("bossbar-pushing", true), BarColor.BLUE, BarStyle.SOLID);
        long startNs = System.nanoTime();
        boolean success = false;
        try (Git git = Git.open(new File("."))) {
            // Ensure marker directory exists
            File dir = new File(".minecicd");
            if (!dir.exists() && !dir.mkdirs()) {
                throw new IOException("Failed to create .minecicd directory");
            }
            File marker = new File(dir, "ready-to-merge.json");
            String branch;
            try {
                branch = git.getRepository().getBranch();
            } catch (Exception e) {
                branch = Config.getString("git.branch");
            }
            final String currentBranch = branch;
            final String target = (targetBranch == null || targetBranch.isEmpty()) ? "main" : targetBranch;
            org.json.JSONObject jo = new org.json.JSONObject();
            jo.put("branch", currentBranch);
            jo.put("target", target);
            jo.put("author", author);
            jo.put("timestamp", new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX").format(new java.util.Date()));
            String json = jo.toString(2);
            java.nio.file.Files.write(marker.toPath(), json.getBytes(java.nio.charset.StandardCharsets.UTF_8));

            git.add().addFilepattern(".minecicd/ready-to-merge.json").call();
            String msg = "CICD ready-to-merge " + (((targetBranch == null || targetBranch.isEmpty()) ? "main" : targetBranch));
            RevCommit commit = git.commit().setAll(true).setAuthor(author, author).setMessage(msg).call();
            git.push().add(commit.getName()).setCredentialsProvider(getCredentials()).call();

            MineCICD.changeBar(bar, getCleanMessage("bossbar-pushed", true), BarColor.GREEN, BarStyle.SOLID);
            MineCICD.removeBarFor(bar, "push");
            success = true;
        } catch (Exception e) {
            MineCICD.log("Failed to mark ready-to-merge", Level.SEVERE);
            MineCICD.logError(e);
            MineCICD.changeBar(bar, getCleanMessage("bossbar-push-failed", true), BarColor.RED, BarStyle.SEGMENTED_12);
            MineCICD.removeBarFor(bar, "push");
            throw e;
        } finally {
            try {
                long durMs = (System.nanoTime() - startNs) / 1_000_000L;
                MineCICD.metrics.pushDurationMsTotal += durMs;
                if (success) MineCICD.metrics.pushes++; else MineCICD.metrics.pushFailures++;
            } catch (Exception ignored) {}
            if (ownsBusy) busyLock = false;
        }
    }

    public static List<String> getIncludedFiles() throws IOException, GitAPIException {
        List<String> paths = new ArrayList<>();
        try (Git git = Git.open(new File("."))) {
            git.add().addFilepattern(".").call();
            RevWalk walk = new RevWalk(git.getRepository());
            RevCommit commit = walk.parseCommit(git.getRepository().resolve("HEAD"));
            RevTree tree = commit.getTree();

            TreeWalk treeWalk = new TreeWalk(git.getRepository());
            treeWalk.addTree(tree);
            treeWalk.setRecursive(true);
            while (treeWalk.next()) {
                String path = treeWalk.getPathString();
                if (File.separator.equals("\\")) {
                    path = path.replace("/", "\\");
                } else {
                    path = path.replace("\\", "/");
                }
                paths.add(path);
            }
        }
        return paths;
    }

    public static int add(File file, String author) throws GitAPIException, IOException {
        boolean ownsBusy = !busyLock;
        if (ownsBusy) busyLock = true;

        String bar = MineCICD.addBar(getCleanMessage("bossbar-adding", true), BarColor.BLUE, BarStyle.SOLID);

        try {
            if (!activeRepoExists()) {
                throw new IllegalStateException("Repository has to be pulled (cloned) before files can be added.");
            }

            File root = new File(".");

            int before = getIncludedFiles().size();

            String relativePath = root.toPath().toAbsolutePath().relativize(file.toPath().toAbsolutePath()).toString();
            relativePath = relativePath.replace("\\", "/");
            allowInGitIgnore(relativePath, file.isDirectory());

            try (Git git = Git.open(new File("."))) {
                git.add().addFilepattern(".").call();
                RevCommit commit = git.commit().setAuthor(author, author).setAll(true).setMessage("MineCICD added \"" + relativePath + "\"").call();
                git.push().add(commit.getName()).setCredentialsProvider(getCredentials()).call();
            }

            int after = getIncludedFiles().size();
            int added = after - before;

            MineCICD.changeBar(bar, getCleanMessage("bossbar-added", true, new HashMap<String, String>() {{
                put("amount", String.valueOf(added));
            }}), BarColor.GREEN, BarStyle.SOLID);
            MineCICD.removeBar(bar, Config.getInt("bossbar.duration"));
            return added;
        } catch (Exception e) {
            if (!(e instanceof IllegalStateException)) {
                MineCICD.log("Failed to add file(s)", Level.SEVERE);
                MineCICD.logError(e);
                MineCICD.changeBar(bar, getCleanMessage("bossbar-adding-failed", true), BarColor.RED, BarStyle.SEGMENTED_12);
                MineCICD.removeBar(bar, Config.getInt("bossbar.duration"));
            } else {
                MineCICD.removeBar(bar, 0);
            }
            throw e;
        } finally {
            if (ownsBusy) busyLock = false;
        }
    }

    public static int remove(File file, String author) throws GitAPIException, IOException {
        boolean ownsBusy = !busyLock;
        if (ownsBusy) busyLock = true;

        String bar = MineCICD.addBar(getCleanMessage("bossbar-removing", true), BarColor.BLUE, BarStyle.SOLID);

        try {
            if (!activeRepoExists()) {
                throw new IllegalStateException("Repository has to be pulled (cloned) before files can be removed.");
            }

            File root = new File(".");

            int amountBefore = getIncludedFiles().size();

            String relativePath = root.toPath().toAbsolutePath().relativize(file.toPath().toAbsolutePath()).toString();
            relativePath = relativePath.replace("\\", "/");

            int amountAfter;
            try (Git git = Git.open(new File("."))) {
                git.rm().setCached(true).addFilepattern(relativePath).call();
                removeFromGitIgnore(relativePath, file.isDirectory());
                RevCommit commit = git.commit().setAuthor(author, author).setAll(true).setMessage("MineCICD removed \"" + relativePath + "\"").call();
                git.push().add(commit.getName()).setCredentialsProvider(getCredentials()).call();
                amountAfter = getIncludedFiles().size();
            }

            int amountRemoved = amountBefore - amountAfter;

            MineCICD.changeBar(bar, getCleanMessage("bossbar-removed", true, new HashMap<String, String>() {{
                put("amount", String.valueOf(amountRemoved));
            }}), BarColor.GREEN, BarStyle.SOLID);
            MineCICD.removeBar(bar, Config.getInt("bossbar.duration"));
            return amountRemoved;
        } catch (Exception e) {
            if (!(e instanceof IllegalStateException)) {
                MineCICD.log("Failed to remove file(s)", Level.SEVERE);
                MineCICD.logError(e);
                MineCICD.changeBar(bar, getCleanMessage("bossbar-removing-failed", true), BarColor.RED, BarStyle.SEGMENTED_12);
                MineCICD.removeBar(bar, Config.getInt("bossbar.duration"));
            } else {
                MineCICD.removeBar(bar, 0);
            }
            throw e;
        } finally {
            if (ownsBusy) busyLock = false;
        }
    }

    public static void reset(String commit) throws GitAPIException, IOException {
        if (!activeRepoExists()) {
            throw new IllegalStateException("Repository has to be pulled (cloned) before it can be reset.");
        }

        boolean ownsBusy = !busyLock;
        if (ownsBusy) busyLock = true;

        String bar = MineCICD.addBar(getCleanMessage("bossbar-resetting", true), BarColor.BLUE, BarStyle.SOLID);

        try (Git git = Git.open(new File("."))) {
            git.reset().setMode(ResetCommand.ResetType.HARD).setRef(commit).call();
            MineCICD.changeBar(bar, getCleanMessage("bossbar-reset", true), BarColor.GREEN, BarStyle.SOLID);
            MineCICD.removeBar(bar, Config.getInt("bossbar.duration"));
        } catch (Exception e) {
            if (!(e instanceof IllegalStateException)) {
                MineCICD.log("Failed to reset repository", Level.SEVERE);
                MineCICD.logError(e);
                MineCICD.changeBar(bar, getCleanMessage("bossbar-reset-failed", true), BarColor.RED, BarStyle.SEGMENTED_12);
                MineCICD.removeBar(bar, Config.getInt("bossbar.duration"));
            } else {
                MineCICD.removeBar(bar, 0);
            }
            throw e;
        } finally {
            if (ownsBusy) busyLock = false;
        }
    }

    public static void revert(String commit) throws GitAPIException, IOException {
        if (!activeRepoExists()) {
            throw new IllegalStateException("Repository has to be pulled (cloned) before it can be reverted.");
        }

        boolean ownsBusy = !busyLock;
        if (ownsBusy) busyLock = true;

        String bar = MineCICD.addBar(getCleanMessage("bossbar-reverting", true), BarColor.BLUE, BarStyle.SOLID);

        try (Git git = Git.open(new File("."))) {
            ObjectId commitId = git.getRepository().resolve(commit);
            RevCommit revCommit = git.revert().include(commitId).call();
            git.push().add(revCommit.getName()).setCredentialsProvider(getCredentials()).call();
            MineCICD.changeBar(bar, getCleanMessage("bossbar-reverted", true), BarColor.GREEN, BarStyle.SOLID);
            MineCICD.removeBar(bar, Config.getInt("bossbar.duration"));
        } catch (Exception e) {
            if (!(e instanceof IllegalStateException)) {
                MineCICD.log("Failed to revert repository", Level.SEVERE);
                MineCICD.logError(e);
                MineCICD.changeBar(bar, getCleanMessage("bossbar-revert-failed", true), BarColor.RED, BarStyle.SEGMENTED_12);
                MineCICD.removeBar(bar, Config.getInt("bossbar.duration"));
            } else {
                MineCICD.removeBar(bar, 0);
            }
            throw e;
        } finally {
            if (ownsBusy) busyLock = false;
        }
    }

    public static void rollback(Calendar calendar) throws GitAPIException, IOException {
        if (!activeRepoExists()) {
            throw new IllegalStateException("Repository has to be pulled (cloned) before it can be rolled back.");
        }

        String lastCommit;
        long rollbackTime = calendar.getTimeInMillis();
        try (Git git = Git.open(new File("."))) {
            RevCommit commit = git.log().setMaxCount(1).call().iterator().next();
            PersonIdent author = commit.getAuthorIdent();
            Date commitTime = author.getWhen();
            long time = commitTime.getTime();
            if (time <= rollbackTime) {
                lastCommit = commit.getName();
            } else {
                lastCommit = null;
                while (true) {
                    if (commit.getParentCount() == 0) {
                        break;
                    }

                    commit = commit.getParent(0);
                    author = commit.getAuthorIdent();
                    commitTime = author.getWhen();
                    time = commitTime.getTime();
                    if (time <= rollbackTime) {
                        lastCommit = commit.getName();
                        break;
                    }
                }
            }
        }

        if (lastCommit == null) {
            throw new IllegalStateException("No commits found before the specified time");
        }

        reset(lastCommit);
    }

    public static void mergeAbort() throws IOException, GitAPIException {
        try (Git git = Git.open(new File("."))) {
            Repository repository = git.getRepository();
            repository.writeMergeCommitMsg(null);
            repository.writeMergeHeads(null);
            git.reset().setMode(ResetCommand.ResetType.HARD).call();
        }
        // Clear external-merge marker if present
        File marker = new File(".minecicd-merged-externals");
        if (marker.exists()) {
            try { marker.delete(); } catch (Exception ignored) {}
        }
    }

    public static void repoReset() {
        FileUtils.deleteQuietly(new File(new File("."), ".git"));
        FileUtils.deleteQuietly(new File(new File("."), ".gitignore"));
    }

    public static RevCommit getCommit(String commit) throws IOException {
        try (Git git = Git.open(new File("."))) {
            ObjectId commitId = git.getRepository().resolve(commit);
            if (commitId == null) {
                throw new IllegalArgumentException("Commit not found");
            }

            RevWalk walk = new RevWalk(git.getRepository());
            return walk.parseCommit(commitId);
        }
    }

    public static void mergeExternal(String remote, String branch, String preference) throws Exception {
        if (!activeRepoExists()) {
            throw new IllegalStateException("Repository has to be pulled (cloned) before merging externals.");
        }
        boolean ownsBusy = !busyLock;
        if (ownsBusy) busyLock = true;

        String bar = MineCICD.addBar("Merging external branch...", BarColor.BLUE, BarStyle.SOLID);
        String tempRemote = null;
        try (Git git = Git.open(new File("."))) {
            if (!getLocalChanges().isEmpty()) {
                throw new IllegalStateException("There are uncommitted changes. Push or reset local changes before merging.");
            }

            // Determine if 'remote' is an existing remote name or a URL
            boolean existing = git.remoteList().call().stream().anyMatch(r -> r.getName().equals(remote));
            String remoteName = remote;
            if (!existing) {
                // Try to treat as URL and add a temporary remote
                try {
                    new URIish(remote); // validate
                } catch (Exception e) {
                    throw new IllegalArgumentException("Remote '" + remote + "' is neither a known remote nor a valid URL");
                }
                remoteName = "external-" + System.currentTimeMillis();
                tempRemote = remoteName;
                git.remoteAdd().setName(remoteName).setUri(new URIish(remote)).call();
            }

            // Fetch the target branch
            git.fetch().setRemote(remoteName).setCredentialsProvider(getCredentials()).call();

            String remoteRef = "refs/remotes/" + remoteName + "/" + branch;
            ObjectId target = git.getRepository().resolve(remoteRef);
            if (target == null) {
                throw new IllegalArgumentException("Branch '" + branch + "' not found on remote '" + remote + "'");
            }

            MergeStrategy strategy = MergeStrategy.RECURSIVE;
            ContentMergeStrategy contentPref = null;
            if (preference != null) {
                if ("ours".equalsIgnoreCase(preference)) {
                    strategy = MergeStrategy.OURS;
                } else if ("theirs".equalsIgnoreCase(preference)) {
                    strategy = MergeStrategy.THEIRS;
                    contentPref = ContentMergeStrategy.THEIRS;
                }
            }

            // Perform a non-committing merge (keeps changes in index/worktree)
            org.eclipse.jgit.api.MergeCommand cmd = git.merge().include(target).setStrategy(strategy);
            if (contentPref != null) cmd.setContentMergeStrategy(contentPref);
            cmd.setCommit(false).call();

            // Write marker to prevent accidental push of external merges
            try { new File(".minecicd-merged-externals").createNewFile(); } catch (IOException ignored) {}

            MineCICD.changeBar(bar, "Merged external branch into working tree (not committed).", BarColor.GREEN, BarStyle.SOLID);
            MineCICD.removeBar(bar, Config.getInt("bossbar.duration"));
        } catch (Exception e) {
            if (!(e instanceof IllegalStateException)) {
                MineCICD.changeBar(bar, "External merge failed", BarColor.RED, BarStyle.SEGMENTED_12);
                MineCICD.removeBar(bar, Config.getInt("bossbar.duration"));
                MineCICD.logError(e);
            }
            throw e;
        } finally {
            // Clean up temp remote if added
            try (Git git = Git.open(new File("."))) {
                if (tempRemote != null) {
                    try {
                        org.eclipse.jgit.api.RemoteRemoveCommand rr = git.remoteRemove();
                        rr.setName(tempRemote);
                        rr.call();
                    } catch (Exception ignored) {}
                }
            } catch (Exception ignored) {}
            if (ownsBusy) busyLock = false;
        }
    }

    public static List<String> getBranchesInfo() throws Exception {
        if (!activeRepoExists()) return Collections.emptyList();
        List<String> lines = new ArrayList<>();
        try (Git git = Git.open(new File("."))) {
            // Fetch to update remotes
            try { git.fetch().setCredentialsProvider(getCredentials()).call(); } catch (Exception ignored) {}
            String current = "";
            try { current = git.getRepository().getBranch(); } catch (Exception ignored) {}

            List<org.eclipse.jgit.lib.Ref> locals = git.branchList().call();
            for (org.eclipse.jgit.lib.Ref ref : locals) {
                String name = ref.getName().replace("refs/heads/", "");
                String remoteRef = "refs/remotes/origin/" + name;
                ObjectId localId = git.getRepository().resolve(ref.getName());
                ObjectId remoteId = git.getRepository().resolve(remoteRef);
                int ahead = 0;
                int behind = 0;
                if (localId != null && remoteId != null) {
                    Iterable<RevCommit> aheadCommits = git.log().add(localId).not(remoteId).call();
                    for (RevCommit ignored : aheadCommits) ahead++;
                    Iterable<RevCommit> behindCommits = git.log().add(remoteId).not(localId).call();
                    for (RevCommit ignored : behindCommits) behind++;
                }
                String mark = name.equals(current) ? "*" : " ";
                lines.add(String.format("%s %s (ahead %d, behind %d)%s", mark, name, ahead, behind, remoteId == null ? " [no remote]" : ""));
            }
        }
        return lines;
    }

    public static void setBranchIfInited() throws IOException, GitAPIException {
        if (!activeRepoExists()) {
            return;
        }

        try (Git git = Git.open(new File("."))) {
            if (git.branchList().call().stream().noneMatch(ref -> ref.getName().equals("refs/heads/" + Config.getString("git.branch")))) {
                try {
                    git.branchCreate().setName(Config.getString("git.branch")).call();
                } catch (RefAlreadyExistsException ignored) {
                }
            }

            git.checkout().setName(Config.getString("git.branch")).call();
        }
    }

    public static void switchBranch(String newBranch) throws IOException, GitAPIException {
        if (!activeRepoExists()) {
            throw new IllegalStateException("Repository has to be pulled (cloned) before switching branches.");
        }
        if (newBranch == null || newBranch.trim().isEmpty()) {
            throw new IllegalArgumentException("Branch name must not be empty");
        }

        boolean ownsBusy = !busyLock;
        if (ownsBusy) busyLock = true;

        String bar = MineCICD.addBar(getCleanMessage("bossbar-branching", true), BarColor.BLUE, BarStyle.SOLID);

        try (Git git = Git.open(new File("."))) {
            // Ensure worktree is clean (no uncommitted changes)
            if (!getLocalChanges().isEmpty()) {
                throw new IllegalStateException("There are uncommitted changes. Push or reset local changes before switching branch.");
            }

            // Fetch remotes
            try {
                git.fetch().setCredentialsProvider(getCredentials()).call();
            } catch (Exception ignored) {
            }

            // If remote branch exists, hard reset to it after checkout
            boolean remoteExists = git.branchList().setListMode(org.eclipse.jgit.api.ListBranchCommand.ListMode.REMOTE).call()
                    .stream().anyMatch(ref -> ref.getName().equals("refs/remotes/origin/" + newBranch));

            // Create local branch if it doesn't exist
            boolean localExists = git.branchList().call().stream().anyMatch(ref -> ref.getName().equals("refs/heads/" + newBranch));
            if (!localExists) {
                try {
                    git.branchCreate().setName(newBranch).call();
                } catch (RefAlreadyExistsException ignored) {
                }
            }

            // Checkout the branch
            git.checkout().setName(newBranch).call();

            // If remote exists, align local to remote
            if (remoteExists) {
                git.reset().setMode(ResetCommand.ResetType.HARD).setRef("origin/" + newBranch).call();
            }

            // Update config
            Config.set("git.branch", newBranch);

            MineCICD.changeBar(bar, getCleanMessage("bossbar-branched", true), BarColor.GREEN, BarStyle.SOLID);
            MineCICD.removeBar(bar, Config.getInt("bossbar.duration"));
        } catch (Exception e) {
            if (!(e instanceof IllegalStateException)) {
                MineCICD.log("Failed to switch branch", Level.SEVERE);
                MineCICD.logError(e);
            }
            MineCICD.changeBar(bar, getCleanMessage("bossbar-branch-failed", true), BarColor.RED, BarStyle.SEGMENTED_12);
            MineCICD.removeBar(bar, Config.getInt("bossbar.duration"));
            throw e;
        } finally {
            if (ownsBusy) busyLock = false;
        }
    }

    public static File createBackup(String name, List<String> extraPaths) throws Exception {
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("Backup name must not be empty");
        }
        File root = new File(".").getCanonicalFile();
        File backupDir = new File(MineCICD.plugin.getDataFolder(), "backups");
        if (!backupDir.exists()) backupDir.mkdirs();
        String safeName = name.replaceAll("[^a-zA-Z0-9._-]", "_");
        String ts = new SimpleDateFormat("yyyyMMdd-HHmmss").format(new java.util.Date());
        File zipFile = new File(backupDir, ts + "-" + safeName + ".zip");

        // Build set of files to include
        Set<File> files = new LinkedHashSet<>();
        try {
            List<String> tracked = activeRepoExists() ? getIncludedFiles() : Collections.emptyList();
            for (String p : tracked) {
                File f = new File(p);
                if (f.exists()) files.add(f.getCanonicalFile());
            }
        } catch (Exception ignored) {}
        if (extraPaths != null) {
            for (String p : extraPaths) {
                if (p == null || p.trim().isEmpty()) continue;
                File f = new File(p);
                if (!f.exists()) continue;
                if (f.isDirectory()) {
                    // walk directory
                    java.util.Deque<File> dq = new java.util.ArrayDeque<>();
                    dq.add(f);
                    while (!dq.isEmpty()) {
                        File cur = dq.removeFirst();
                        File[] list = cur.listFiles();
                        if (list == null) continue;
                        for (File c : list) {
                            if (c.isDirectory()) dq.add(c); else files.add(c.getCanonicalFile());
                        }
                    }
                } else {
                    files.add(f.getCanonicalFile());
                }
            }
        }

        // Exclusions
        File gitDir = new File(root, ".git").getCanonicalFile();
        File backupsDir = backupDir.getCanonicalFile();

        try (java.io.FileOutputStream fos = new java.io.FileOutputStream(zipFile);
             ZipOutputStream zos = new ZipOutputStream(fos)) {
            for (File f : files) {
                // skip .git contents and backups directory
                if (f.getPath().startsWith(gitDir.getPath())) continue;
                if (f.getPath().startsWith(backupsDir.getPath())) continue;
                // ensure within root
                if (!f.getPath().startsWith(root.getPath())) continue;
                Path rel = root.toPath().relativize(f.toPath());
                String entryName = rel.toString().replace('\\', '/');
                ZipEntry entry = new ZipEntry(entryName);
                zos.putNextEntry(entry);
                byte[] buf = Files.readAllBytes(f.toPath());
                zos.write(buf);
                zos.closeEntry();
            }
        }
        return zipFile;
    }

    public static List<String> doctorReport() {
        List<String> lines = new ArrayList<>();
        lines.add("MineCICD Doctor Report");
        try {
            lines.add("Java: " + System.getProperty("java.version"));
            lines.add("OS: " + System.getProperty("os.name") + " " + System.getProperty("os.arch") + " " + System.getProperty("os.version"));
            // Webhooks
            int port = 0; String path = "";
            try { port = Config.getInt("webhooks.port"); } catch (Exception ignored) {}
            try { path = Config.getString("webhooks.path"); } catch (Exception ignored) {}
            String wh = port == 0 ? "disabled" : (MineCICD.webServer != null ? ("listening on :" + port + "/" + path) : "enabled in config, not listening");
            lines.add("Webhooks: " + wh);

            // Credentials
            String user = ""; String pass = "";
            try { user = Optional.ofNullable(Config.getString("git.user")).orElse(""); } catch (Exception ignored) {}
            try { pass = Optional.ofNullable(Config.getString("git.pass")).orElse(""); } catch (Exception ignored) {}
            lines.add("Git credentials: user=" + (user.isEmpty() ? "missing" : "set") + ", pass=" + (pass.isEmpty() ? "missing" : "set"));

            // Repo
            if (!activeRepoExists()) {
                lines.add("Repo: not initialized (.git/.gitignore missing in server root)");
            } else {
                try (Git git = Git.open(new File("."))) {
                    String branch = "";
                    try { branch = git.getRepository().getBranch(); } catch (Exception ignored) {}
                    lines.add("Repo: initialized, branch=" + branch);
                    try { git.fetch().setCredentialsProvider(getCredentials()).call(); lines.add("Fetch: OK"); } catch (Exception e) { lines.add("Fetch: FAILED - " + e.getMessage()); }
                    // ahead/behind vs origin
                    try {
                        ObjectId localId = git.getRepository().resolve("HEAD");
                        ObjectId remoteId = git.getRepository().resolve("refs/remotes/origin/" + branch);
                        int ahead = 0, behind = 0;
                        if (localId != null && remoteId != null) {
                            Iterable<RevCommit> aheadCommits = git.log().add(localId).not(remoteId).call();
                            for (RevCommit ignored : aheadCommits) ahead++;
                            Iterable<RevCommit> behindCommits = git.log().add(remoteId).not(localId).call();
                            for (RevCommit ignored : behindCommits) behind++;
                            lines.add("Ahead/Behind: " + ahead + "/" + behind);
                        } else {
                            lines.add("Ahead/Behind: unknown (no remote tracking)");
                        }
                    } catch (Exception e) {
                        lines.add("Ahead/Behind: FAILED - " + e.getMessage());
                    }
                }
            }

            // Secrets filters present
            try {
                File gitattributes = new File(".gitattributes");
                File gitconfig = new File(".git" + File.separator + "config");
                boolean attrs = gitattributes.exists() && Files.size(gitattributes.toPath()) > 0;
                boolean cfg = gitconfig.exists() && new String(Files.readAllBytes(gitconfig.toPath()), StandardCharsets.UTF_8).contains("[filter ");
                lines.add("Secrets filters: .gitattributes=" + (attrs ? "ok" : "missing/empty") + ", .git/config filters=" + (cfg ? "present" : "missing"));
            } catch (Exception e) {
                lines.add("Secrets filters: FAILED - " + e.getMessage());
            }
        } catch (Exception e) {
            lines.add("Doctor encountered an error: " + e.getMessage());
        }
        return lines;
    }
}
