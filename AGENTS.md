# Backlog adapter contributor instructions

Public Java 25 / Spring Boot 4.0.9 adapter. Read CONTRIBUTING.md when present.
Use only public SDK contracts and synthetic fixtures; never import private core.
Keep Backlog parsing and mapping here. Host owns Git/auth/access. No full ticket
contents in observations or logs. Use isolated task directories/worktrees, preserve
other tasks, run Maven verify for code changes, and report actual verification.
Do not commit, publish or create a remote repository without authorization.

Before merging a PR, run `./scripts/verify-local.sh` on its final commit and
report the actual results. All verification belongs locally; GitHub Actions is
reserved for package releases. Keep every removed hosted check in the local
verification entrypoint. Do not skip tests to obtain a successful verification.
