# TracePermit implementation handoff

This file records only transferable repository state. It contains no customer
planning files, database URLs, AWS identifiers, passwords, tokens, keys or
other private operator values.

TRACEPERMIT HANDOFF

- Task: Add a bounded MariaDB durable permit-consumption foundation without claiming PostgreSQL approval/evidence parity.
- Repository: https://github.com/hemantkgupta/tracepermit
- Branch: codex/mariadb-durable-state-foundation
- Base commit: 642a86b2c0128ced66b49bdce3f505516560d6d6
- Latest pushed commit: implementation commit `d795d05`; this handoff-only commit follows it
- PR URL: https://github.com/hemantkgupta/tracepermit/pull/94
- Branch owner now: unassigned after this handoff commit is pushed; the Mac thread has stopped editing the branch
- Working tree status: clean after this handoff commit and push
- Completed: added the isolated `persistence-mariadb` Java module; exact MariaDB 11.4.13 primary and 10.6.25 transitional engine profiles; explicit empty-database initialization; validate-only runtime; strict InnoDB/object/schema/Flyway checks; tenant-bound plain-insert replay claim with only vendor duplicate-key 1062 treated as replay; commit-before-success and acknowledgement-uncertainty handling; dedicated CI gate; ADR, runbook, baseline, low-level design, threat model, quality and traceability updates
- Remaining: exact-head GitHub CI and independent review; later slices must port composed approval/execution/evidence, policy, recovery, pruning and RDS deployment/failover qualification before any MariaDB runtime wiring or production-support claim
- Files materially changed: `.github/workflows/ci.yml`; `java/settings.gradle.kts`; new `java/persistence-mariadb/**`; `scripts/run-java-mariadb-tests.sh`; `package.json`; ADR 0066; MariaDB qualification runbook; affected specifications; this handoff
- Tests passed: exact implementation commit `pnpm test:java-mariadb --rerun-tasks` (14 tests, zero failures/errors/skips across both exact images); exact implementation commit `pnpm check` (124 React tests and Java `checkAll`/aggregate coverage successful; one pre-existing installed-archive connector E2E skipped because the archive was not prepared); `pnpm format:check`; `pnpm docs:links` (988 local links); `pnpm docs:flows:check`; `actionlint .github/workflows/ci.yml`; `git diff --check`
- Tests failed/not run: no failed local gate; live Amazon RDS, TLS, replication, Multi-AZ failover, backup/restore, restricted migration/runtime DB users and composed MariaDB approval/evidence behavior were not run
- Exact next command: `gh pr checks 94 --repo hemantkgupta/tracepermit --watch`
- Required environment: review needs GitHub/source; repeating the focused gate needs Java 21, Node.js 22+, Corepack/pnpm 11.25.0 and Docker capable of running the exact MariaDB 10.6.25 and 11.4.13 images
- Local-only/manual steps: the four `agentcore-work` customer planning files remain outside this worktree and ignored in the shared checkout; later RDS endpoints, account identifiers, security groups, database names/users/passwords and test evidence must remain private and sanitized
- Security constraints: module remains unwired; dedicated non-system database; InnoDB writer endpoint for authority state; unknown engine/version/schema fails closed; no `INSERT IGNORE`, automatic migration/repair/retry, replica authority reads, credential output, real target, force-push or merge
- Known limitations: standalone replay claim only; not `PostgresDurableEnforcementState` parity, RDS qualification, production deployment, exactly-once target execution, database-admin isolation or a GitHub/AgentCore integration
- Safe for another environment to continue: yes, after fetching the branch, verifying the expected commit, confirming a clean worktree and explicitly taking ownership
