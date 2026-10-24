# TracePermit implementation handoff

GitHub is authoritative. Private customer/provider planning, credentials and
connection values remain outside Git. Historical failures are not successful
qualification samples.

TRACEPERMIT HANDOFF

- Task: Approved PR94 conflict reconciliation, exact-image MariaDB qualification and full gates; leave unmerged.
- Repository: https://github.com/hemantkgupta/tracepermit; Mac /Users/hemantkgupta/code-all/tracepermit-mariadb-foundation.
- Branch: codex/mariadb-durable-state-foundation
- Base commit: 592e2baefca9d6baac1567f8b29736981526a57a (main, merged100/102).
- Latest pushed commit: preceding published checkpoint 1a9df3952daf3d3a2d0c81218c7f828ea682b92d; this normal main-merge/qualification checkpoint follows. Resolve its exact SHA from PR94 and the final GitHub handoff.
- PR URL: https://github.com/hemantkgupta/tracepermit/pull/94 (open/unmerged; review-ready/draft setting unchanged).
- Branch owner now: Mac sole writer after cloud release, verified published head/clean worktree and explicit public ownership claim. Final GitHub handoff must release writing.
- Working tree status: existing clean c3c5a18 worktree fast-forwarded normally to published 1a9df39, then main merged normally and qualified. Only HANDOFF conflicted; this record retains both histories. Final handoff verifies clean local/upstream equality after normal push. No reset, discard, rebase or other checkout modification.
- Completed: imported main's reviewed typed GitHub confirmation and bounded dispatch repairs unchanged; retained MariaDB module/script and mandatory CI gate, main's operation-repository job and both specifications. Three existing schema/transaction-fault tests now exercise both exact database profiles instead of only 11.4.13; no weakened assertion or production-code change. Exact-image qualification, full local checks, formatting, workflow lint and documentation gates pass.
- Remaining: commit/normal push and exact-head hosted qualification. No prior cloud or historical pass substitutes for these new results. PR103's CI optimization remains separate; later reconciliation must retain the mandatory MariaDB permit-consumption gate, not confuse registration tests with this store's qualification.
- Files materially changed: manual HANDOFF conflict resolution; MariaDB test parameterization and matching qualification documentation/traceability. Main supplies its existing changes through normal merge; no runtime, dependency, migration-byte or frozen-contract modification.
- Tests passed: forced exact-image matrix 17/17 (14 store cases across MariaDB 10.6.25/11.4.13 plus 3 profile unit cases), zero failures/errors/skips, 33 seconds; both disposable containers removed. One pnpm check passed in 9m25s: 124 React tests; 1,584 recorded Java cases across 141 suites, zero failures/errors and one existing archive-absent skip. Unchanged valid outputs reused (65 tasks: 36 executed, 29 up-to-date); the 17-case MariaDB receipt is the preceding forced run, not a fresh second run. Coverage 94.27% line/78.01% branch passes unchanged 94%/75% gates. Frozen install, actionlint 1.7.12, runner Bash syntax, formatting, 1,064 documentation links, 13 flow tests/drift and diff checks pass.
- Tests failed/not run: no new local qualification failure. One existing installed-archive/native-PTY case skipped because its archive was absent. Cloud Java/Docker/tooling blockers and earlier hosted latency failure remain historical, not relabeled. New-head hosted checks are still pending. No live RDS/provider/target, server crash/restart, failover, external diagnostic-redaction or production qualification.
- Exact next command: gh pr checks 94 --repo hemantkgupta/tracepermit
- Required environment: Java21 via repository Bash setup, Node22+, pnpm11.25.0, Docker/disposable mariadb:10.6.25 and mariadb:11.4.13 fixtures, actionlint and GitHub access. One Gradle worker.
- Local-only/manual steps: ignored cache/build/test outputs stay local; transferable code and final sanitized results will be pushed. Four private agentcore-work files remain unread/unpublished. Other active lanes and worktrees are untouched.
- Security constraints: standalone tenant-bound single-use foundation remains unwired; exact engine/schema/checksum checks, InnoDB primary authority, explicit empty initialization, duplicate1062-only handling and commit/close uncertainty retained. No live action, credentials, public listener/tunnel, paid fallback, Docker restart, gate weakening, capacity/deadline/workload relaxation, force-push or PR merge.
- Known limitations: no PostgreSQL approval/evidence parity, MariaDB runtime/launcher wiring, RDS/TLS/failover/backup/least-privilege or production-readiness claim.
- Safe for another environment to continue: read-only review only until final GitHub handoff releases the Mac at a clean, verified pushed SHA.

## Preserved historical records

- [Published cloud checkpoint](https://github.com/hemantkgupta/tracepermit/blob/1a9df3952daf3d3a2d0c81218c7f828ea682b92d/HANDOFF.md)
  and [cloud final handoff](https://github.com/hemantkgupta/tracepermit/pull/94#issuecomment-6381103834)
  preserve the reconciliation, differing cloud metadata and missing Java/Docker
  qualification. That environment's blocked checks are not relabeled.
- [Merged PR100 reconciliation checkpoint](https://github.com/hemantkgupta/tracepermit/blob/41df5894cadd5e0662bd8f5ea43920a7e4a1b470/HANDOFF.md)
  and [final hosted results](https://github.com/hemantkgupta/tracepermit/pull/100#issuecomment-6384195456)
  retain the independent validation closure and dispatch integration evidence.
  [ADR0074](docs/architecture/0074-reserved-lane-bounded-dispatch.md) and its
  [diagnostic runbook](docs/runbooks/latency-qualification-transport-diagnostics.md)
  retain earlier dispatch failures, repairs and limitations. These older receipts
  do not qualify this new MariaDB composition.
