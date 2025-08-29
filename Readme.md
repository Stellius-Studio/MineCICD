<hr style="border: 1px solid green;">
<h2 style="color:green">MineCICD 2.0 has been released!</h2>
<h3 style="color:green">Version 2.0 is a complete rewrite of the plugin, focussed on:</h3>
<li style="color:green">Improving performance of all operations</li>
<li style="color:green">Expanding compatibility to MS Windows and across versions</li>
<li style="color:green">Fixing bugs and making operations more reliable</li>
<li style="color:green">Adding new, suggested features</li>
<h3 style="color:green">Version 2.0 Changelog:</h3>
<li style="color:green">Git repository root is now the same as the server root</li>
<li style="color:green">Compatibility for Microsoft Windows added (no more file path issues)</li>
<li style="color:green">Removed whitelisting / blacklisting in the config, now done with .gitignore file</li>
<li style="color:green">Fixed most issues listed on GitHub</li>
<hr style="border: 1px solid green;">
<h2 style="color:red">Updating from MineCICD 1.* to 2.0 will reset the config! See Migration steps below</h2>

# MineCICD 2.3.2
#### Minecraft Versions [Paper / Spigot] 1.8.* - 1.21.*
## Continuous Integration, Continuous Delivery - Now for Minecraft Servers

### What is MineCICD?
MineCICD is a tool used for Minecraft Server and Network development, which can massively speed up all
setup- and configuration processes. It tracks all changes with Version Control System Integration, allows for
fast and safe reverts, rollbacks, insight into changes / bug tracking and much more.

Developers can use their personal IDE to edit files on their own Machine, push changes to the Repository, which
automatically applies the changes on the server and performs arbitrarily defined actions / commands / scripts,
with support for server shell commands.

Networks may make use of MineCICD to manage multiple servers, plugins, and configurations across all servers
simultaneously, with the ability to track changes and apply them to all servers at once!

## Installation & Setup
1. Download the plugin from the latest release on GitHub (https://github.com/Konstanius/MineCICD/releases)
2. Add the plugin into your plugins folder
3. Restart the Minecraft server
4. Create a Git repository (For example on GitHub at https://github.com/new or similar)
5. Link the repository and your access credentials in the config
    - For GitHub, you must use a Personal Access Token
      - Get the token from https://github.com/settings/tokens
      - It must have full repo permissions
      - Set it in the config.yml under both `git.user` and `git.pass`
    - Other Git providers may require different credentials
      - Set them in the config.yml under `git.user` and `git.pass` accordingly
6. Reload the config with `/minecicd reload`
7. Load the repository with `/minecicd pull`

Note: On first init, MineCICD will check out or create the configured branch (config key `git.branch`) before the initial commit/push, avoiding accidental creation of a default `master` branch.

### Now you are all set! - Time to start syncing
- Add files to Git Tracking with `/minecicd add <file>`
- Load changes made to the repository with `/minecicd pull`
- (Optionally) push changes from your server to the repository with `/minecicd push <commit message>`

### Which files should be tracked?
- All files that are part of the server setup and configuration should be tracked
- This includes plugins, server configurations, scripts, and other files that are part of the server setup
- **You should NOT track player data, world data, or other files that are generated and updated dynamically**
   - **Tracking world or player data will inevitably lead to conflicts and issues**

### Protected branches (optional)
You can protect critical branches from accidental pushes. Configure in `config.yml`:
```yaml
git:
  protected-push-mode: "off"        # off | require-force | block
  protected-branches: "main, production"  # Comma-separated list
```
- off: No special handling.
- require-force: Pushes to any protected branch require using `minecicd push force <message>`.
- block: Pushes to protected branches are blocked.

Tip: Use `/minecicd branches` to see your current branch and ahead/behind counts.

### UX improvements
- Dry-run: `/minecicd pull --dry-run`, `/minecicd push --dry-run`
- Diff paging/filter: `/minecicd diff remote [page] [pathPrefix]`
- Bossbar per-action: set `bossbar.enabled.<action>` and `bossbar.duration.<action>` in config (falls back to global).

### Instant commit propagation setup
1. Create a webhook for your repository (Repo settings -> Webhooks -> Add webhook)
    - Payload URL: `http://<your server ip>:8080/minecicd` (Port / Path configurable in `config.yml`)
    - Content type: `application/json`
    - Set it to trigger only for `The push event`
2. You're all set! Now you can also use commit actions

Tip: You can simulate a webhook push without any network by running `/minecicd webhook test`. This will fetch, apply changes (if any), parse CICD commit directives, and run allowed actions, just like a real webhook.

### Commit Actions
Commit Actions are actions that are performed when a commit is pushed to the repository.<br>
They can range from restarting / reloading the server or individual plugins to executing game commands or
defined scripts containing game commands or shell commands.<br>
You can define commit actions as follows:
```yaml
# Execution order is top to bottom for commands, scripts and restart / reload
# Only one of restart / global-reload / reload (plugin(s)) is performed.
<any other commit message>
CICD restart (Will only stop the server, starting relies on your restart script / server host)
CICD global-reload (Reload the entire server using the reload command)
CICD reload <plugin-name> (Multiple plugins via separate lines can be specified (Requires PlugManX))
CICD run <command> (Multiple commands via separate lines can be specified)
CICD script <script-name> (Multiple scripts via separate lines can be specified)
<...>
```

### Secrets
Secrets are a way of storing sensitive information, such as passwords or API keys, in a dedicated, untracked file.<br>
They are defined in the `/secrets.yml` file, following the format below:
```yaml
# secrets.yml
# For each file, create an index here
# (It is not used for anything, only to uniquely identify each secrets config block)
1:
   # Each block needs to have its file path specified
   file: "plugins/example-plugin-1/config.yml"
   # Add secrets for each block
   # the key (before the ":" will be the value that replaces the secret)
   # the value (after the ":") will be the actual secret
   database_password: "${ENV:DB_PASS}"
   database_username: "username"
2:
   file: "plugins/example-plugin-2/config.yml"
   random_free_port: "${RANDOM_PORT}"
   license_key: "license_key"
```
Helper tokens resolved locally (never committed):
- ${ENV:VAR} – replaced with the environment variable VAR (empty if missing)
- ${RANDOM_PORT} – replaced with an available random TCP port on this machine

After modifying this file, make sure to reload the plugin with `/minecicd reload` to apply the changes.<br>
These secrets will never be visible in the repository and only exist in the local server files.<br>
Since Windows does not come with the `sed` command, MineCICD ships with a custom implementation for Windows: `plugins/MineCICD/tools/windows-replace.exe`.<br>
If your Linux installation does not have `sed`, MineCICD will use another custom implementation: `plugins/MineCICD/tools/linux-replace.exe`.<br>

Secrets commands:
- `/minecicd secrets validate` – checks that each configured placeholder (like `{{database_password}}`) exists in its target file; reports missing ones and missing files.
- `/minecicd secrets preview <file>` – shows a diff of what would change if secrets were applied to the specified file; does not modify the worktree.

### Scripts
Scripts are a way of storing procedures of Minecraft commands and system shell commands.<br>
They are defined in the `plugins/MineCICD/scripts` directory as `<script_name>.sh` files.<br>
For a detailed description about the syntax, see the `plugins/MineCICD/scripts/example_script.sh` file.

### Version Tracking for jar files
Tracking plugin jarfiles with Git is currently only experimentally supported and can cause issues.<br>
This is due to issues with File Locks and the way Java handles jar files, but is being worked on.<br>
This feature is disabled by default, to enable it, do the following:
- Remove the `*.jar` line from the `.gitignore` file in the server root
- Install PlugManX on your server (https://www.spigotmc.org/resources/plugmanx.88135/)
- Enable the `experimental-jar-loading` option in the config.yml and reload MineCICD (`/minecicd reload`)

This will unload and load plugins if their jarfiles change, are removed. or new ones are added.

Safer hot-reload and staging:
- Plugin name detection reads plugin.yml from jar files for accurate names; falls back to filename/path heuristics.
- Optional staging: set `experimental-jar-staging: true` in config to queue jar unload/load operations instead of running mid-tick.
  - Review queued operations with `/minecicd jars list`, apply them with `/minecicd jars apply`, or clear with `/minecicd jars clear`. 

### Merging external branches (safe composition)
Use `/minecicd merge <remote|url> <branch> [ours|theirs]` to fetch and merge another repository/branch into your working tree without auto-commit.
- Remote can be an existing remote name (e.g., `origin`) or a full Git URL.
- Preference `ours|theirs` optionally biases conflict resolution.
- After a successful merge, MineCICD writes a local marker file to prevent accidental pushes. To push deliberately after reviewing, use `minecicd push force <message>`.

### Automation, Metrics & Retry
Configure periodic pulls and retry/backoff in config.yml:

- automation.pull.enabled: true|false
- automation.pull.cron: "*/N * * * *" (supported pattern: every N minutes)
- automation.pull.dry-run: true|false (fetch+report only)
- git.retry.max-attempts / base-delay-ms / max-delay-ms: retry settings used by scheduled/webhook pulls and by push command.

Metrics:
- View pull/push counts, failures, and average durations with `/minecicd metrics`.

Logging & Security:
- Logs are automatically redacted to mask secrets and credentials (git.pass and values from secrets.yml).

### Safety, Recovery & Backups
- Create a one-shot backup of tracked files (and optional extra paths):
  - `/minecicd backup <name> [extra path ...]`
  - Backups are saved as timestamped zip files under `plugins/MineCICD/backups/`.
- Diagnostics report:
  - `/minecicd doctor` shows environment, webhook status, credentials presence, repository state (ahead/behind), and secrets filters status.
- Safer resets:
  - By default resets require an explicit confirmation flag. Configure via `git.reset.require-confirm` in config.yml.

### Safety, Recovery & Backups
- Create a one-shot backup of tracked files (and optional extra paths):
  - `/minecicd backup <name> [extra path ...]`
  - Backups are saved as timestamped zip files under `plugins/MineCICD/backups/`.
- Diagnostics report:
  - `/minecicd doctor` shows environment, webhook status, credentials presence, repository state (ahead/behind), and secrets filters status.
- Safer resets:
  - By default resets require an explicit confirmation flag. Configure via `git.reset.require-confirm` in config.yml.

### Admin Playbooks
Below are proven, low-risk operational flows you can copy for your teams.

- Dev → Staging → Prod via branches
  1. Developers work on feature branches and open PRs to `dev`.
  2. Staging server tracks `dev` branch (config `git.branch: dev`). Enable webhook and scripts/commands as needed.
  3. When stable, merge `dev` → `main` (production). Production server tracks `main`.
  4. Use `/minecicd branches` on servers to verify branch and ahead/behind.
  5. For multi-repo composition, use safe merges: `/minecicd merge <remote|url> <branch> [ours|theirs]`, then review and `push force` if intentional.

- Safe releases with backups and dry-runs
  1. Before pushing or pulling a risky change, create a backup: `/minecicd backup pre-release-<ticket>`.
  2. Preview changes:
     - Remote: `/minecicd pull --dry-run`
     - Local: `/minecicd push --dry-run`
  3. Apply: `/minecicd pull` (or push) and monitor `/minecicd metrics`.

- Rollback procedures
  - Roll back to a specific commit (confirmation required by default):
    - `/minecicd reset --confirm <commit|link>`
  - Roll back to time (last commit before timestamp):
    - `/minecicd rollback <dd.MM.yyyy HH:mm:ss>`
  - Revert a specific commit (creates a new revert commit and pushes it):
    - `/minecicd revert <commit|link>`
  - Use backups to restore non-git or staged data:
    - Locate the ZIP in `plugins/MineCICD/backups/` and restore files as needed.

- Secret patterns (best practices)
  - Keep all sensitive values in `secrets.yml`; reference placeholders like `{{database_password}}` in config files.
  - Prefer helper tokens for portability:
    - `${ENV:VAR}` reads environment variables for different environments (dev/stage/prod).
    - `${RANDOM_PORT}` auto-allocates a free port for ephemeral test servers.
  - Validate and preview before rollout:
    - `/minecicd secrets validate`
    - `/minecicd secrets preview <file>`
  - Avoid committing secret values: MineCICD uses Git filters to smudge/clean.

### Troubleshooting
Use the matrix below to diagnose common problems.

- Secrets not applying to files
  - Run `/minecicd secrets validate` to find missing `{{placeholder}}` or files.
  - Ensure `.gitattributes` and git filters are present: `/minecicd doctor`.
  - Reload plugin to reapply filters: `/minecicd reload`.
  - On fresh clones, a pull with filters will smudge placeholders; ensure `secrets.yml` is present and valid.

- Webhook returns 403/404 or no events arrive
  - Check webhooks config: port/path; verify MineCICD is listening with `/minecicd doctor`.
  - Confirm firewall/ingress allows inbound traffic to the configured port.
  - Ensure repo webhook is set to the correct URL and branch. Use `/minecicd webhook test` to simulate locally.

- Push blocked: “protected branch” or “merged externals”
  - Protected branches: either use `minecicd push force <msg>` if mode is `require-force` or change `git.protected-push-mode`.
  - External merge marker: a local marker prevents accidental push after `/minecicd merge`. If you intend to publish, use `push force`.

- sed not found on Linux, or replacements not happening
  - MineCICD auto-detects `sed`. If unavailable, it falls back to `plugins/MineCICD/tools/linux-replace.exe`.
  - Run `/minecicd doctor` to check filters; reloading (`/minecicd reload`) reinstalls tools if missing.

- Windows path or newline issues
  - Path normalization is handled internally. Always use forward slashes in repo paths where possible.
  - If diffs look odd, try `/minecicd diff local` and `/minecicd diff remote` with a path prefix filter.

- Authentication failures (pull/push)
  - Ensure `git.user` and `git.pass` (token) are set; `/minecicd doctor` will say if credentials are missing.
  - For GitHub, use a Personal Access Token with repo scope; set token as both user and pass if needed.

- Repo not initialized / commands say repo missing
  - Run `/minecicd pull` once after configuring the repository; this bootstraps `.git` and initial files.
  - If broken state, consider `minecicd resolve repo-reset` and then `/minecicd pull` again (be cautious!).

## Commands
- `minecicd branch <name>` - Switches the active branch tracked by this server.
- `minecicd pull` - Pulls the latest changes from the remote or sets up the local repository if run for the first time.
- `minecicd pull --dry-run` - Shows planned remote changes without applying them.
- `minecicd push <commit message>` or `minecicd push force <commit message>` - Pushes local changes to the remote. Use `force` only if required by protected-branch settings or after a deliberate external merge.
- `minecicd push --dry-run` - Shows local uncommitted changes that would be pushed.
- `minecicd branches` - Lists local branches, marks the current one, and shows ahead/behind counts vs `origin`.
- `minecicd merge <remote|url> <branch> [ours|theirs]` - Safely fetches and merges an external branch into the working tree without auto-commit. A safety marker prevents accidental push unless you use `push force`.
- `minecicd secrets validate` - Validates that configured placeholders exist in their target files.
- `minecicd secrets preview <file>` - Shows a diff of applying secrets to a file (no changes written).
- `minecicd webhook test` - Simulates a webhook push event locally (no network).
- `minecicd add <file / 'directory/'>` - Adds a file or directory to the repository.
- `minecicd remove <file / 'directory/'>` - Removes a file or directory from the repository.
- `minecicd reset --confirm <commit hash / link>` - Hard resets the current branch to the specified commit. Confirmation flag required by default (configurable via `git.reset.require-confirm`). (Commits will not be reverted)
- `minecicd rollback <dd.MM.yyyy HH:mm:ss>` - Hard resets the current branch to the latest commit before the specified date. (Commits will not be reverted)
- `minecicd revert <commit hash / link>` - Attempts to revert a specific commits changes.
- `minecicd script <script name>` - Runs a script from the scripts folder.
- `minecicd log <page / commit hash / link>` - Shows the commits made on the current branch.
- `minecicd status` - Shows the current status of the plugin, repository, Webhook listener, and changes.
- `minecicd resolve <merge-abort / repo-reset / reset-local-changes>` - Resolves conflicts by either aborting the merge, resetting the repository, or removing local changes.
- `minecicd diff <local|remote> [page] [pathPrefix]` - Shows local or remote changes with optional pagination and path filtering.
- `minecicd reload` - Reloads the plugin configuration and webhook webserver.
- `minecicd help` - Shows this help message.

## Roadmap & Ideas
See ROADMAP.md for proposed features and enhancements that are open for community feedback and contributions.

## Permissions
- `minecicd.<subcommand>` - Allows the user to use the subcommand
- `minecicd.notify` - Allows the user to receive notifications from actions performed by MineCICD

## Migrating from MineCICD 1.* to 2.0
1. Push any changes made on your server to the repository!
2. Install the new version of MineCICD
3. Copy the existing token from the old config and save them somewhere
4. The entire plugin directory of MineCICD will reset (No server files are affected)
5. Set up the new config with the token, repository url, branch name and other settings
6. Run `/minecicd pull` to clone the repository
  - MineCICD should automatically detect which files were added to the repository
  - If not, manually add them with `/minecicd add <file / 'directory/'>`
