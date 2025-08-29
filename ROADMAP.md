# MineCICD – Roadmap and Potential Additions

This document collects feature ideas and enhancements discussed by users and maintainers. It is a living list meant to guide discussion and prioritization. Items are grouped by area and include a short rationale and feasibility note.

Note: Items marked (idea) are proposals; they may change or be rejected after community discussion.

## 1. Git Workflow & Repository Management
- [x] Multi‑repo pull/merge: Added safe, read‑only command `minecicd merge <remote|url> <branch> [ours|theirs]` that fetches and merges without auto‑commit; push is blocked by a local marker unless forced.
- [x] Improve initial branch handling: On first init we check out/create the configured branch before the initial commit/push, avoiding accidental `master` creation.
- [x] Branch listing + info: Implemented `minecicd branches` showing local branches with current marker and ahead/behind vs origin.
- [x] Protected branch safety: Configurable push protection via `git.protected-push-mode` and `git.protected-branches`; force override supported.

## 2. Secrets & Configuration
- [x] Secrets validation command: `minecicd secrets validate` checks that placeholders exist in target files and reports missing keys.
- [x] Secrets dry‑run: `minecicd secrets preview <file>` shows a diff of applied secrets without modifying the worktree.
- [x] Template helpers: `${ENV:VAR}` and `${RANDOM_PORT}` helper tokens in secrets.yml (resolved locally, not committed).
- [x] Auto‑repair filters: On startup/reload and during pull, `.gitattributes` and `.git/config` are verified and re‑applied to self‑heal as needed.

## 3. CI/Webhook & Automation
- [x] Webhook test command: `minecicd webhook test` simulates a push event locally (no network).
- [x] Scheduled pull: Cron‑like schedule in config to periodically `pull` with optional `dry-run`.
- [x] Rate limiting & retry: Exponential backoff on pull/push failures; configurable limits in config.

## 4. Safety, Recovery & Backups
- [x] One‑shot backup: `minecicd backup <name> [extra paths...]` zips tracked files (HEAD) and optional paths into plugins/MineCICD/backups/ with timestamped filename.
- [x] Doctor/Diagnostics: `minecicd doctor` prints a diagnostic report (Java, OS, webhook status, credentials presence, repo state, ahead/behind, secrets filters).
- [x] Safer resets: `minecicd reset --confirm <commit>` required by default (config: `git.reset.require-confirm`).

## 5. UX, Commands & Feedback
- [x] Dry‑run mode: `minecicd pull --dry-run` and `minecicd push --dry-run` show planned actions (file list) without writing.
- [x] Incremental diff paging: `minecicd diff <local|remote> [page] [pathPrefix]` for paged output and optional path filter.
- [x] Better bossbar/messages toggles: Per‑action verbosity and durations via `bossbar.enabled.<action>` and `bossbar.duration.<action>`.

## 6. Experimental Jar Handling
- [x] Safer hot‑reload integration: Plugin name detection reads plugin.yml from jars, with fallback to path parsing.
- [x] Staging step: Jar unload/load operations are staged when enabled (`experimental-jar-staging`); apply with `/minecicd jars apply`. 

## 7. Windows/Linux Parity & Ops
- [x] Portable binary checks: Improved detection of `sed` on various distros (`command -v`, `which`, common paths, `sed --version`) with 5‑minute cached capability checks.
- [x] Path normalization audit: Ensured OS‑neutral joins and comparisons in diffs and filters; git paths normalized to forward slashes consistently.

## 8. Observability
- [x] Optional metrics: Count of pulls/pushes, failures, and total/avg durations; exposed via `/minecicd metrics`.
- [x] Log redaction: Explicit redaction filters ensure secrets and credentials are masked in all logged messages and stack traces.

## 9. Documentation
- [x] Admin playbooks: Added common workflows (dev → staging → prod), rollback procedures, and secret patterns to Readme.md.
- [x] Troubleshooting matrix: Added a matrix mapping common errors/symptoms (e.g., secrets not applying, webhook 403) to recommended fixes in Readme.md.

---
Contributions welcome! If you plan to work on any of these, please open an issue first to coordinate design and scope.
