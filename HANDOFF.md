# TracePermit implementation handoff

GitHub is authoritative. Credentials, private customer/provider planning and local
operator values remain outside Git. Historical receipts are not fresh results.

TRACEPERMIT HANDOFF

- Task: Reconcile PR101 durable pending-operation withdrawal with merged main, preserving its internal-only authority limits and all incoming qualification gates. Leave the PR unmerged.
- Repository: https://github.com/hemantkgupta/tracepermit; Mac checkout `/Users/hemantkgupta/code-all/tracepermit-operation-lifecycle`.
- Branch: codex/durable-operation-lifecycle.
- Base commit: pinned main `f23dc22f056058d7977715e0878c284728d10d17` (merged PR94/100/102/103); original PR101 work started at `2883eb6dc9c40f88c231ddc6cec7df5f016b2add`.
- Latest pushed commit: `d35e6c285440428513c8d4864b661e71d66633cd`; the current normal merge is not yet committed or pushed. Verify the final PR head and GitHub handoff before transfer.
- PR URL: https://github.com/hemantkgupta/tracepermit/pull/101 — draft, open, unmerged.
- Branch owner now: Mac coordinator sole writer after clean exact-head verification and explicit ownership claim. Read-only review does not grant concurrent writing ownership.
- Working tree status: normal merge of pinned main into clean d35e6c2 in progress. Documentation conflicts are being reconciled without reset, discard, rebase, force-push or other-checkout edits.
- Completed:
  - Prior PR101 checkpoint: pending-only withdrawal; locked expected-state and trusted owner/role/scope/deadline checks using database time; coherent current reads; no generic desired-state/proposal write.
  - Separate current heads/hash-linked actor journal; atomic head/fact commit and explicit commit uncertainty; original snapshot/three facts and original-return retries preserved.
  - Explicit additive V1→V2 upgrade, validate-only runtime and column-scoped head update grants. Database-review guidance informed short single-row transactions and least privilege.
  - Fixed exceptions cannot retain JDBC causes or suppressed close diagnostics. Package-local strict stored-JSON decoding rejects duplicate/nested keys, trailing data, malformed/null/empty/oversized values before hash/semantic checks or populated upgrade DDL. Global JSON/canonical behavior, valid-value hashes, V1 migration bytes, locks and grants are unchanged by that repair.
  - ADR0072, runbooks, learning/specifications/traceability and public parallel packets describe the internal library and its remaining integration boundary.
  - Incoming main contains merged GitHub preflight/typed-confirmation repairs, standalone MariaDB permit consumption, bounded listener dispatch and parallel fail-closed CI. Their original evidence is linked below; this reconciliation does not requalify them by inheritance.
- Remaining: resolve and verify the normal merge, run fresh focused three-engine lifecycle qualification and proportionate full gates, then commit/push and inspect exact-head hosted checks. Independent review and operation-to-GitHub binding/executor composition remain separate. Do not declare merge readiness from historical results or a single green rerun.
- Files materially changed: prior PR101 application lifecycle command/port/exception/tests; JDBC registration/database/lifecycle journal and strict reader; additive PostgreSQL/MariaDB V2 migrations/shared tests; ADR0072, runbooks, learning, specs and packets. Manual reconciliation is limited to HANDOFF, the specification index and traceability; incoming main code is preserved.
- Tests passed: historical PR101 checkpoint only — final Java21/one-worker `pnpm check` reported 124 React tests; 1,498 Java cases recorded, zero failures/errors and one existing installed-archive/native-PTY skip; 94.39% line /77.78% branch with unchanged gates. Operations module reported 245 cases, including 234 database cases (78 each on PostgreSQL16.4 and MariaDB11.4.13/10.6.25) and 11 strict-reader cases, no failures/errors/skips. Unchanged Gradle task outputs were reused; changed persistence tests reran. Formatting, 1,030 links, 13 flow checks and diff checks passed. These are not results for the current merge.
- Tests failed/not run: fresh merged-head focused, broad and hosted qualification is pending. Historical negative controls: two removed-deadline failures; three pre-repair close-diagnostic failures; ten Java21/PostgreSQL ambiguous-JSON regressions. The first strict-reader full run caught empty input decoding to a MissingNode and was repaired before its final passing gate. An initial probe used an unsuitable JDK; explicit Java21 reproduced the ten intended failures. Older host-load policy contention and pre-PR102 latency failures remain historical failures, not current passes. No live IdP/GitHub/RDS/AgentCore/S3/EKS or production qualification.
- Exact next command: git diff --check
- Required environment: Java21 through scripts/configure-java-environment.sh (set TRACEPERMIT_JAVA_HOME privately if discovery fails); Node.js22+, pnpm11.25.0; Docker. Bound Gradle workers on a shared Mac without weakening internal concurrency, deadlines or assertions.
- Local-only/manual steps: ignored build/test reports, caches and CLI helpers stay local. Obtain fresh frozen-head independent review after reconciliation. Stop all old/new writers and verify backup before any separately approved real-store upgrade; MariaDB partial DDL requires operator recovery.
- Security constraints: no public route, target/permit/approval/publication call, private customer file access, sensitive output, billed API fallback, shared writer or automatic merge. No missing/skipped gate, lower coverage, reduced workload, artifact-trust shortcut or branch-protection change. A future trusted adapter must verify enrollment and derive workspace scope; constructor flags/state tokens are not identity proofs.
- Known limitations: closed initial-evaluating→cancelled composition only; no generic trusted lifecycle service, instant revocation, restored-backup fence, signed journal/DBA attestation, encryption/redaction, retention, live deployment or production readiness. Durable operation-to-GitHub authority integration remains separate. CI scheduling is not a guaranteed speedup and may increase runner minutes.
- Safe for another environment to continue: no for branch writing until a clean committed/pushed checkpoint and explicit owner release; read-only review may run concurrently.

## Preserved merged-main evidence

- [PR94 final handoff](https://github.com/hemantkgupta/tracepermit/pull/94#issuecomment-6384587169)
  records the standalone MariaDB matrix and full-check evidence. It does not
  qualify approval/evidence parity, live RDS, crash recovery or runtime wiring.
- [PR100 final handoff](https://github.com/hemantkgupta/tracepermit/pull/100#issuecomment-6384195456)
  records GitHub preflight, strict typed confirmations, synthetic target checks
  and their limits. Its provider port and process-local state are not a composed
  durable executor or credential-minting implementation.
- [Merged PR102](https://github.com/hemantkgupta/tracepermit/pull/102),
  [ADR0074](docs/architecture/0074-reserved-lane-bounded-dispatch.md) and the
  [qualification record](docs/runbooks/latency-qualification-transport-diagnostics.md)
  preserve bounded dispatch and latency failure/repair evidence. The eight-lane,
  sixteen-pending defaults, workload and assertions remain unchanged; this is not
  a production-load or distributed-admission claim.
- [PR103 final handoff](https://github.com/hemantkgupta/tracepermit/pull/103#issuecomment-6384860027)
  and [exact-head workflow](https://github.com/hemantkgupta/tracepermit/actions/runs/40097821467)
  supersede its pre-publication checkpoint: all six required jobs/final verify
  passed at `6df3101e9073863727691390d88ada0b62d9e181`. That complete workflow took
  10m22s, not the five-second final gate. Six independent clean-runner jobs retain
  the aggregate suite and both forced PostgreSQL/MariaDB gates; final verify
  rejects failed, cancelled, skipped, missing and unknown results. The 37 workflow
  regressions and independent inventory retained 29 distinct protected commands/
  31 occurrences, with only the workflow-regression step added. Triggers,
  permissions, action pins and browser/registration jobs were preserved.
  The prior local checkpoint reported actionlint1.7.12, frozen install,
  formatting, 1,067 documentation links, 13 synchronized flow tests/drift and
  diff checks. The frozen `365d5dd` negative control gave 35 workflow passes and
  exactly two expected failures for required MariaDB gate/inventory checks,
  then all 37 passed after reconciliation. These remain historical receipts,
  not checks of the current PR101 merge.
- [Earlier producer CI](https://github.com/hemantkgupta/tracepermit/actions/runs/40093825438)
  passed at `365d5dd` before MariaDB integration, with an observed 8m39s completion.
  It is historical only and does not qualify the reconciled PR103 or PR101 head.

These receipts retain their original scope; none substitutes for the fresh
merged-head PR101 checks now pending.
