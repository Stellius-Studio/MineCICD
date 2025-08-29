package ml.konstanius.minecicd;

import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.SystemUtils;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.*;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;

/**
 * Functionality documentation:
 * <p>
 * - Uses Git native filters, clean and smudge<br>
 * - .gitattributes file is used to specify the filter associated with each file<br>
 * - .git/config file is used to specify the individual "minecicd-replace.exe ..." commands for each filter<br>
 * - secrets cannot contain the single quote character (" ' ")<br>
 * - each file can only have one filter, but each sed command may replace multiple placeholders<br>
 * - the name of the filter in the .git/config and .gitattributes files will be the same as the relative file path that it is applied to<br>
 * <p>
 * Each secret has the following:
 * - A unique identifier, which is the same as its {{identifier}} placeholder will be<br>
 * - A file that it is associated with<br>
 * - A secret that it will replace in the file
 */
public class GitSecret {
    // Cached sed capability detection (Linux/Unix)
    private static Boolean SED_AVAILABLE = null;
    private static long SED_LAST_CHECK_MS = 0L;
    private static boolean isSedAvailable() {
        if (SystemUtils.IS_OS_WINDOWS) return false;
        long now = System.currentTimeMillis();
        if (SED_AVAILABLE != null && (now - SED_LAST_CHECK_MS) < 5 * 60 * 1000L) { // cache 5 minutes
            return SED_AVAILABLE;
        }
        boolean available = false;
        try {
            // Try command -v
            Process p1 = new ProcessBuilder("sh", "-c", "command -v sed >/dev/null 2>&1").start();
            int e1 = p1.waitFor();
            // Try which
            int e2 = 1;
            try {
                Process p2 = new ProcessBuilder("sh", "-c", "which sed >/dev/null 2>&1").start();
                e2 = p2.waitFor();
            } catch (Exception ignored) {}
            // Try common path
            boolean pathExists = new java.io.File("/bin/sed").exists() || new java.io.File("/usr/bin/sed").exists();
            // Try executing sed --version quickly (best-effort)
            int e3 = 1;
            try {
                Process p3 = new ProcessBuilder("sh", "-c", "sed --version >/dev/null 2>&1").start();
                e3 = p3.waitFor();
            } catch (Exception ignored) {}
            available = (e1 == 0) || (e2 == 0) || (e3 == 0) || pathExists;
        } catch (Exception ignored) {
            available = false;
        }
        SED_AVAILABLE = available;
        SED_LAST_CHECK_MS = now;
        return available;
    }
    private static String resolveHelpers(String value) throws InvalidConfigurationException {
        if (value == null) return null;
        String result = value;
        // ${ENV:VAR}
        int start;
        while ((start = result.indexOf("${ENV:")) != -1) {
            int end = result.indexOf("}", start);
            if (end == -1) break;
            String token = result.substring(start, end + 1);
            String inner = result.substring(start + 6, end); // after ${ENV:
            String envVal = System.getenv(inner);
            if (envVal == null) envVal = ""; // empty if missing
            result = result.replace(token, envVal);
        }
        // ${RANDOM_PORT}
        while ((start = result.indexOf("${RANDOM_PORT}")) != -1) {
            int port = 0;
            try (ServerSocket socket = new ServerSocket(0)) {
                port = socket.getLocalPort();
            } catch (IOException ignored) {}
            result = result.replace("${RANDOM_PORT}", String.valueOf(port));
        }
        return result;
    }

    public static class ValidationIssue {
        public final String file;
        public final String message;
        public ValidationIssue(String file, String message) {
            this.file = file;
            this.message = message;
        }
        @Override public String toString() { return (file == null ? "" : (file + ": ")) + message; }
    }

    public static List<ValidationIssue> validateSecrets(HashMap<String, ArrayList<GitSecret>> secrets) {
        List<ValidationIssue> issues = new ArrayList<>();
        for (Map.Entry<String, ArrayList<GitSecret>> e : secrets.entrySet()) {
            String path = e.getKey();
            File f = new File(path);
            if (!f.exists()) {
                issues.add(new ValidationIssue(path, "Target file does not exist"));
                continue;
            }
            String content;
            try {
                content = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            } catch (IOException ex) {
                issues.add(new ValidationIssue(path, "Failed to read file: " + ex.getMessage()));
                continue;
            }
            for (GitSecret s : e.getValue()) {
                String placeholder = "{{" + s.identifier + "}}";
                if (!content.contains(placeholder)) {
                    issues.add(new ValidationIssue(path, "Missing placeholder " + placeholder));
                }
            }
        }
        return issues;
    }

    public static String previewSecrets(String filePath, HashMap<String, ArrayList<GitSecret>> secrets) throws IOException {
        ArrayList<GitSecret> list = secrets.get(filePath);
        if (list == null || list.isEmpty()) {
            return "No secrets configured for this file.";
        }
        File f = new File(filePath);
        if (!f.exists()) return "Target file does not exist.";
        String original = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
        String modified = original;
        for (GitSecret s : list) {
            modified = modified.replace("{{" + s.identifier + "}}", s.secret);
        }
        if (original.equals(modified)) return "No changes after applying secrets.";
        // Simple line-by-line diff
        StringBuilder sb = new StringBuilder();
        sb.append("--- ").append(filePath).append(" (current)\n");
        sb.append("+++ ").append(filePath).append(" (with secrets)\n");
        String[] o = original.split("\r?\n", -1);
        String[] m = modified.split("\r?\n", -1);
        int max = Math.max(o.length, m.length);
        for (int i = 0; i < max; i++) {
            String ol = i < o.length ? o[i] : "";
            String ml = i < m.length ? m[i] : "";
            if (!Objects.equals(ol, ml)) {
                sb.append("-").append(ol).append("\n");
                sb.append("+").append(ml).append("\n");
            }
        }
        return sb.toString();
    }
    public static HashMap<String, ArrayList<GitSecret>> readFromSecretsStore() throws IOException, InvalidConfigurationException {
        File secretsFile = new File(".", "secrets.yml");
        HashMap<String, ArrayList<GitSecret>> secrets = new HashMap<>();
        if (!secretsFile.exists()) {
            Files.write(secretsFile.toPath(), (
                    "1:\n" +
                            "  file: \"plugins/example-plugin-1/config.yml\"\n" +
                            "  database_password: \"password\"\n" +
                            "  database_username: \"username\"\n" +
                            "2:\n" +
                            "  file: \"plugins/example-plugin-2/config.yml\"\n" +
                            "  license_key: \"license_key\""
            ).getBytes());
        }

        FileConfiguration secretsConfig = new YamlConfiguration();
        secretsConfig.load(secretsFile);

        for (String index : secretsConfig.getKeys(false)) {
            ArrayList<GitSecret> secretsList = new ArrayList<>();

            HashSet<String> secretsForThisFile = new HashSet<>();

            ConfigurationSection section = secretsConfig.getConfigurationSection(index);
            if (section == null) {
                throw new InvalidConfigurationException("Secrets must be in a configuration section");
            }

            String filePath = section.getString("file");
            if (filePath == null) {
                throw new InvalidConfigurationException("Every secrets block must have a file");
            }
            if (filePath.contains("'")) {
                throw new InvalidConfigurationException("Secrets file paths cannot contain the single quote character");
            }

            Set<String> keys = section.getKeys(false);

            for (String secretIdentifier : keys) {
                if (secretIdentifier.equals("file")) {
                    continue;
                }
                String secret = section.getString(secretIdentifier);
                                // Resolve helper tokens locally (not committed)
                                secret = resolveHelpers(secret);

                if (secret == null) {
                    throw new InvalidConfigurationException("Every secret must have a value");
                }
                if (secretIdentifier.contains("'") || secret.contains("'")) {
                    throw new InvalidConfigurationException("Secrets and their identifier cannot contain the single quote character");
                }
                if (secretsForThisFile.contains(secretIdentifier)) {
                    throw new InvalidConfigurationException("Secrets must have unique identifiers");
                }
                secretsForThisFile.add(secretIdentifier);

                secretsList.add(new GitSecret(secretIdentifier, filePath, secret));
            }
            secrets.put(filePath, secretsList);
        }

        return secrets;
    }

    public static void configureGitSecretFiltering(HashMap<String, ArrayList<GitSecret>> secrets) throws IOException, InterruptedException {
        File gitConfigFile = new File(new File(".", ".git"), "config");
        if (!GitUtils.activeRepoExists()) {
            return;
        }

        File gitAttributesFile = new File(".", ".gitattributes");
        if (gitAttributesFile.exists()) {
            FileUtils.deleteQuietly(gitAttributesFile);
        }

        StringBuilder gitAttributesContent = new StringBuilder();
        for (String filePath : secrets.keySet()) {
            String normalizedPath = filePath.replace("\\", "/");
            gitAttributesContent.append(normalizedPath).append(" filter=").append(normalizedPath).append("\n");
        }
        Files.write(gitAttributesFile.toPath(), gitAttributesContent.toString().getBytes());

        ArrayList<String> gitConfigLines = new ArrayList<>(Files.readAllLines(gitConfigFile.toPath()));
        boolean inFilterSection = false;
        for (int i = 0; i < gitConfigLines.size(); i++) {
            String line = gitConfigLines.get(i);
            if (line.contains("[filter")) {
                inFilterSection = true;
            } else if (inFilterSection && line.contains("[")) {
                inFilterSection = false;
            }
            if (inFilterSection) {
                gitConfigLines.remove(i);
                i--;
            }
        }

        // Check if sed is installed (cached capability check)
        boolean sedInstalled = isSedAvailable();

        for (String filePath : secrets.keySet()) {
            if (secrets.get(filePath).isEmpty()) {
                continue;
            }

            String normalizedPath = filePath.replace("\\", "/");
            gitConfigLines.add("[filter \"" + normalizedPath + "\"]");

            StringBuilder cleanCommand = new StringBuilder("\tclean = ");
            StringBuilder smudgeCommand = new StringBuilder("\tsmudge = ");
            if (SystemUtils.IS_OS_WINDOWS) {
                cleanCommand.append(".\\\\plugins\\\\MineCICD\\\\tools\\\\windows-replace.exe");
                smudgeCommand.append(".\\\\plugins\\\\MineCICD\\\\tools\\\\windows-replace.exe");
            } else {
                if (sedInstalled) {
                    cleanCommand.append(" sed");
                    smudgeCommand.append(" sed");
                } else {
                    cleanCommand.append(" ./plugins/MineCICD/tools/linux-replace.exe");
                    smudgeCommand.append(" ./plugins/MineCICD/tools/linux-replace.exe");
                }
            }

            if (!sedInstalled) {
                for (GitSecret secret : secrets.get(filePath)) {
                    String base64Secret = Base64.getEncoder().encodeToString(secret.secret.getBytes());
                    String base64Identifier = Base64.getEncoder().encodeToString(("{{" + secret.identifier + "}}").getBytes());

                    cleanCommand.append(" ").append(base64Secret).append(" ").append(base64Identifier);
                    smudgeCommand.append(" ").append(base64Identifier).append(" ").append(base64Secret);
                }
            } else {
                for (GitSecret secret : secrets.get(filePath)) {
                    cleanCommand.append(" -e 's/").append(secret.secret).append("/{{").append(secret.identifier).append("}}/g'");
                    smudgeCommand.append(" -e 's/{{").append(secret.identifier).append("}}/").append(secret.secret).append("/g'");
                }
            }

            gitConfigLines.add(cleanCommand.toString());
            gitConfigLines.add(smudgeCommand.toString());
        }

        gitConfigLines.add("");

        Files.write(gitConfigFile.toPath(), gitConfigLines);

        if (!SystemUtils.IS_OS_WINDOWS && sedInstalled) {
            return;
        }

        // load the appropriate replace executable into the MineCICD/tools directory
        File dataDir = MineCICD.plugin.getDataFolder();
        File toolsDir = new File(dataDir, "tools");
        if (!toolsDir.exists()) {
            toolsDir.mkdirs();
        }

        File replaceExecutable = new File(toolsDir, SystemUtils.IS_OS_WINDOWS ? "windows-replace.exe" : "linux-replace.exe");
        if (replaceExecutable.exists()) {
            return;
        }

        InputStream is = MineCICD.plugin.getResource((SystemUtils.IS_OS_WINDOWS ? "windows-replace.exe" : "linux-replace.exe"));
        if (is == null) {
            throw new IOException("Could not load the MineCICD replace executable");
        }

        Files.copy(is, replaceExecutable.toPath());
    }

    public GitSecret(String identifier, String file, String secret) {
        this.identifier = identifier;
        this.file = file;
        this.secret = secret;
    }

    public String identifier;
    public String file;
    public String secret;
}
