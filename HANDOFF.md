# TracePermit implementation handoff

GitHub is authoritative. Credentials, private customer/provider planning and local
operator values remain outside Git. Historical receipts are not fresh results.

TRACEPERMIT HANDOFF

- Task: Reconcile PR101 durable pending-operation withdrawal with merged main, preserving its internal-only authority limits and all incoming qualification gates. Leave the PR unmerged.
- Repository: https://github.com/hemantkgupta/tracepermit; Mac checkout `/Users/hemantkgupta/code-all/tracepermit-operation-lifecycle`.
- Branch: codex/durable-operation-lifecycle.
- Base commit: pinned main `f23dc22f056058d7977715e0878c284728d10d17` (merged PR94/100/102/103); original PR101 work started at `2883eb6dc9c40f88c231ddc6cec7df5f016b2add`.
- Latest pushed commit: normal merge checkpoint `3a0a380153176c20d42461276fd483bcb3b7a01a`, followed by a documentation-only results checkpoint. Resolve the exact final pushed head from PR101 and its GitHub handoff before transfer; the previous published head was `d35e6c285440428513c8d4864b661e71d66633cd`.
- PR URL: https://github.com/hemantkgupta/tracepermit/pull/101 — draft, open, unmerged.
- Branch owner now: released to user/review owner after the final normal push; the Mac coordinator stops editing this branch. The separate binding bridge remains Mac-owned on its own branch.
- Working tree status: clean and synced at the final push. A normal merge preserves both parents; only three documentation conflicts needed manual resolution. No reset, discard, rebase, force-push or other-checkout edits.
- Completed:
  - Prior PR101 checkpoint: pending-only withdrawal; locked expected-state and trusted owner/role/scope/deadline checks using database time; coherent current reads; no generic desired-state/proposal write.
  - Separate current heads/hash-linked actor journal; atomic head/fact commit and explicit commit uncertainty; original snapshot/three facts and original-return retries preserved.
  - Explicit additive V1→V2 upgrade, validate-only runtime and column-scoped head update grants. Database-review guidance informed short single-row transactions and least privilege.
  - Fixed exceptions cannot retain JDBC causes or suppressed close diagnostics. Package-local strict stored-JSON decoding rejects duplicate/nested keys, trailing data, malformed/null/empty/oversized values before hash/semantic checks or populated upgrade DDL. Global JSON/canonical behavior, valid-value hashes, V1 migration bytes, locks and grants are unchanged by that repair.
  - ADR0072, runbooks, learning/specifications/traceability and public parallel packets describe the internal library and its remaining integration boundary.
  - Incoming main contains merged GitHub preflight/typed-confirmation repairs, standalone MariaDB permit consumption, bounded listener dispatch and parallel fail-closed CI. Their original evidence is linked below; this reconciliation does not requalify them by inheritance.
- Remaining: exact-final-head hosted checks and independent/operator review; leave PR101 draft and unmerged. Operation-to-GitHub binding and executor composition remain separate. Local passing checks do not qualify RDS, failover, live identity, targets or production deployment.
- Files materially changed: prior PR101 application lifecycle command/port/exception/tests; JDBC registration/database/lifecycle journal and strict reader; additive PostgreSQL/MariaDB V2 migrations/shared tests; ADR0072, runbooks, learning, specs and packets. Manual reconciliation is limited to HANDOFF, the specification index and traceability; incoming main code is preserved.
- Tests passed: fresh forced `pnpm test:java-operations --rerun --no-build-cache --max-workers=1`: 245 cases, comprising 78 each on PostgreSQL16.4/MariaDB11.4.13/MariaDB10.6.25 plus 11 strict-reader cases, zero failures/errors/skips. Then one Java21/one-worker `pnpm check` passed: 124 React tests and 1,723 Java cases recorded across 144 suites, zero failures/errors, one existing installed-archive/native-PTY skip. Gradle completed in 10m44s: 49 tasks executed, 2 from cache, 14 up-to-date; the freshly forced operation result was reused. Aggregate coverage 94.37% line/78.16% branch, unchanged thresholds. Frozen install, formatting, 1,081 documentation links/3 tests, 13 flow tests/drift and diff checks passed. Final results-only document edits are checked separately and do not change runtime bytes.
- Tests failed/not run: no failure in this reconciliation's focused or broad run. Exact-final-head hosted checks remain pending at the documentation checkpoint. Historical negative controls and failures remain evidence, not current passes: two removed-deadline failures; three pre-repair close-diagnostic failures; ten ambiguous-JSON failures; the initial MissingNode rejection repair; an unsuitable-JDK probe; older host-load contention and pre-PR102 latency failures. The existing native-PTY test skipped because its installed archive was absent, not merely because this was noninteractive. No live IdP/GitHub/RDS/AgentCore/S3/EKS or production qualification.
- Exact next command: gh pr checks 101 --repo hemantkgupta/tracepermit
- Required environment: Java21 through scripts/configure-java-environment.sh (set TRACEPERMIT_JAVA_HOME privately if discovery fails); Node.js22+, pnpm11.25.0; Docker. Bound Gradle workers on a shared Mac without weakening internal concurrency, deadlines or assertions.
- Local-only/manual steps: ignored build/test reports, caches and CLI helpers stay local. Obtain fresh frozen-head independent review after reconciliation. Stop all old/new writers and verify backup before any separately approved real-store upgrade; MariaDB partial DDL requires operator recovery.
- Security constraints: no public route, target/permit/approval/publication call, private customer file access, sensitive output, billed API fallback, shared writer or automatic merge. No missing/skipped gate, lower coverage, reduced workload, artifact-trust shortcut or branch-protection change. A future trusted adapter must verify enrollment and derive workspace scope; constructor flags/state tokens are not identity proofs.
- Known limitations: closed initial-evaluating→cancelled composition only; no generic trusted lifecycle service, instant revocation, restored-backup fence, signed journal/DBA attestation, encryption/redaction, retention, live deployment or production readiness. Durable operation-to-GitHub authority integration remains separate. CI scheduling is not a guaranteed speedup and may increase runner minutes.
- Safe for another environment to continue: yes, after fetching/verifying the final GitHub head, a clean tree, this handoff and the explicit writer release. No concurrent writer or automatic PR merge is authorized.

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

These historical receipts retain their original scope; fresh merged-head PR101
local checks are recorded above, with exact-final-head hosted checks separate.
