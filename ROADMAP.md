# MineCICD – Roadmap and Potential Additions

This document collects feature ideas and enhancements discussed by users and maintainers. It is a living list meant to guide discussion and prioritization. Items are grouped by area and include a short rationale and feasibility note.

Note: Items marked (idea) are proposals; they may change or be rejected after community discussion.

## 1. Git Workflow & Repository Management
- Multi‑repo pull/merge (idea): Add a safe, read‑only command like `minecicd merge <remote> <branch> [ours|theirs]` to fetch and locally merge other repos/branches for server‑side composition. Rationale: compose core + gamemode configs. Feasibility: medium/high complexity; careful conflict handling and clear “never push merged externals” guarantees required.
- Improve initial branch handling: Avoid creating/pushing a default `master` branch when a different branch is configured on first init. Rationale: reduce noise on fresh repos. Feasibility: low.
- Branch listing + info: `minecicd branches` to show local/remote branches, current branch, ahead/behind counts. Rationale: better visibility. Feasibility: low.
- Protected branch safety: Optional setting to block `push` on protected branches or require `--force` flag. Feasibility: low/medium.

## 2. Secrets & Configuration
- Secrets validation command: `minecicd secrets validate` to check placeholders exist in target files and report missing keys. Feasibility: low.
- Secrets dry‑run: `minecicd secrets preview <file>` to show a diff of applied secrets without modifying the worktree. Feasibility: low/medium.
- Template helpers: Optional `${ENV:VAR}` or `${RANDOM_PORT}` helper tokens in secrets.yml (resolved locally, not committed). Feasibility: medium.
- Auto‑repair filters: On startup/pull, verify `.gitattributes` and `.git/config` contain expected filters; self‑heal if not. Feasibility: low (partly implemented already).

## 3. CI/Webhook & Automation
- Webhook test command: `minecicd webhook test` to simulate a push event (no network). Feasibility: low.
- Scheduled pull: Cron‑like schedule in config to periodically `pull` (with optional `dry-run`). Feasibility: low/medium.
- Rate limiting & retry: Backoff strategy on pull/push failures; configurable limits. Feasibility: low.

## 4. Safety, Recovery & Backups
- One‑shot backup: `minecicd backup <name>` to zip tracked files (and optionally selected folders) before risky operations. Feasibility: low/medium.
- Doctor/Diagnostics: `minecicd doctor` to check environment (Java, OS, webhooks port, credentials, repo state, filters). Feasibility: low.
- Safer resets: `minecicd reset --confirm <commit>` guard or interactive confirmation for destructive operations (configurable). Feasibility: low.

## 5. UX, Commands & Feedback
- Dry‑run mode: `minecicd pull --dry-run` and `minecicd push --dry-run` to show planned actions and diffs without writing. Feasibility: medium.
- Incremental diff paging: Allow `minecicd diff remote 2` for paged output; filter by path prefix. Feasibility: low.
- Better bossbar/messages toggles: Per‑action verbosity level; configurable durations per action. Feasibility: low.

## 6. Experimental Jar Handling
- Safer hot‑reload integration: Improve detection of plugin names; fallback to `plugins/<plugin>/plugin.yml` parsing. Feasibility: medium.
- Staging step: Queue jar changes and apply on confirm/restart, to avoid mid‑tick reloads. Feasibility: medium.

## 7. Windows/Linux Parity & Ops
- Portable binary checks: Improve detection of `sed`/tools on various distros; cache capability checks. Feasibility: low.
- Path normalization audit: Ensure all path joins and comparisons are OS‑neutral. Feasibility: low.

## 8. Observability
- Optional metrics: Count of pulls/pushes, failures, durations (exposed via simple `/minecicd metrics`). Feasibility: low.
- Log redaction: Ensure secrets are never printed; add explicit redaction filters. Feasibility: low.

## 9. Documentation
- Admin playbooks: Common workflows (dev → staging → prod), rollback procedures, secret patterns. Feasibility: low.
- Troubleshooting matrix: Map common errors/symptoms to fixes (e.g., secrets not applying, webhook 403, etc.). Feasibility: low.

---
Contributions welcome! If you plan to work on any of these, please open an issue first to coordinate design and scope.
