# TracePermit implementation handoff

GitHub is authoritative. No private customer inputs or credentials are included.

TRACEPERMIT HANDOFF

- Task: Durable pending-operation withdrawal, immutable journal and bounded overnight parallel work.
- Repository: https://github.com/hemantkgupta/tracepermit; Mac checkout `/Users/hemantkgupta/code-all/tracepermit-enterprise-operation`.
- Branch: codex/durable-operation-lifecycle
- Base commit: 2883eb6dc9c40f88c231ddc6cec7df5f016b2add (merged PR #99).
- Latest pushed commit: runtime checkpoint `5ec343be33903884029a2a24a297ce54e0864539`; a documentation-only handoff commit follows. Resolve/verify the exact PR head before taking ownership.
- PR URL: https://github.com/hemantkgupta/tracepermit/pull/101 — draft, open, unmerged.
- Branch owner now: unassigned after handoff push; this Mac thread stops editing after that push. CI monitoring/review does not grant concurrent writing ownership.
- Working tree status: clean and synced after handoff push; no merge/rebase/force-push or existing-change cleanup.
- Completed:
  - Closed pending-only withdrawal; locked expected-state and trusted owner/role/scope/deadline checks using database time; coherent current reads; no generic desired-state/proposal write.
  - Separate current heads/hash-linked actor journal; atomic head/fact commit and explicit commit uncertainty; original snapshot/three facts and original-return retries preserved.
  - Explicit additive V1→V2 upgrade, validate-only runtime and column-scoped head update grants. Database-review guidance informed short single-row transactions and least privilege.
  - Fixed exceptions cannot retain JDBC causes or suppressed close diagnostics. Package-local strict stored-JSON decoding rejects duplicate/nested keys, trailing data, malformed/null/empty/oversized values before hash/semantic checks or populated upgrade DDL. Global JSON/canonical behavior, valid-value hashes, V1 migration bytes, locks and grants are unchanged by that repair.
  - ADR0072, runbooks, learning/specifications/traceability and public parallel packets updated.
  - Cloud produced separate draft PR #100, then repaired independently reproduced large-ID binding/cache aliasing at c8af3f7d6576d8c89c08430c0d9377c280a9caf2. Gemini's older-head audit was received; local saved reports confirm 271 total remote cases (189 GitHub-related subset) and 195 database cases at the earlier SHAs, not later repairs.
- Remaining: Fresh exact-head hosted CI and review of all final repairs. Previous af723ae hosted verify failed the unchanged latency harness: concurrency20 warmup request IO failure, consequent downstream failures and 19/60 deltas; browser/operation-repository/Pages passed. Separate cloud diagnostics/qualification-barrier work owns that failure investigation. Do not declare merge readiness from a single green rerun or this library's local results.
- Files materially changed: application lifecycle command/port/exception/tests; JDBC registration/database/lifecycle journal and strict reader; additive PostgreSQL/MariaDB V2 migrations/shared tests; ADR0072, runbooks, learning, specs, plan/packets and HANDOFF.
- Tests passed: final Java21/one-worker `pnpm check`: 124 React tests; 1,498 Java cases recorded, zero failures/errors and one existing installed-archive/native-PTY skip; 94.39% line /77.78% branch with unchanged gates. Operations module: 245 cases, including 234 database cases (78 each on PostgreSQL16.4 and MariaDB11.4.13/10.6.25) and 11 strict-reader cases, no failures/errors/skips. Unchanged Gradle task outputs were reused; changed persistence tests reran. Formatting, 1,030 links, 13 flow checks and diff checks passed.
- Tests failed/not run: two deliberate removed-deadline failures; three pre-repair close-diagnostic failures; ten Java21/PostgreSQL ambiguous-JSON regressions failed before repair. First strict-reader full run caught empty input decoding to a MissingNode; an explicit rejection fixed it before the final passing gate. Initial probe launch selected an unsuitable JDK; explicit Java21 rerun reproduced the ten intended failures. Earlier broad-run policy contention timeout under severe host load and hosted latency failures remain disclosed, not passes. No live IdP/GitHub/RDS/AgentCore/S3/EKS or production qualification.
- Exact next command: gh pr checks 101 --repo hemantkgupta/tracepermit
- Required environment: Java21 through scripts/configure-java-environment.sh (set TRACEPERMIT_JAVA_HOME privately if discovery fails); Node.js22+, pnpm11.25.0; Docker. Bound Gradle workers on a shared Mac without weakening internal concurrency, deadlines or assertions.
- Local-only/manual steps: ignored build/test reports, caches and CLI helpers stay local. Gemini needs a fresh frozen-head read-only review for the latest repairs. Stop all old/new writers and verify backup before any separately approved real-store upgrade; MariaDB partial DDL requires operator recovery.
- Security constraints: no public route, target/permit/approval/publication call, private customer file access, sensitive output, billed API fallback, shared writer or automatic merge. A future trusted adapter must verify enrollment and derive workspace scope; constructor flags/state tokens are not identity proofs.
- Known limitations: closed initial-evaluating→cancelled composition only; no generic trusted lifecycle service, instant revocation, restored-backup fence, signed journal/DBA attestation, encryption/redaction, retention, live deployment or production readiness. Durable operation-to-GitHub authority integration remains separate.
- Safe for another environment to continue: yes, after fetching/verifying the exact PR head, clean tree, reading this handoff and explicitly taking exclusive ownership. Read-only audits may run concurrently.
