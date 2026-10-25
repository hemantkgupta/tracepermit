# Gemini goal: durable shared workload-credential replay store

Repository: <https://github.com/hemantkgupta/tracepermit>.
Frozen starting main: `0bc6b618aa9eac7df3bacf7a6d4bcafe2a824c4f`.
Time allocation: up to eight productive hours, then a coherent handoff. This is
an isolated **implementation** task, not another broad review-only audit.
All PRs remain draft/open/unmerged. No paid API fallback or live target/provider.

## Goal and ownership

Implement and qualify a standalone Java JDBC implementation of the existing
`io.tracepermit.identity.WorkloadIdentityReplayStore` port, shared across process
instances and durable across store reconstruction. Current ingress uses an
in-memory replay store: this is a real remaining foundation gap.

Use a new isolated checkout/branch `codex/durable-workload-identity-replay` from
the exact frozen main above. Verify GitHub/main and report differences; do not
silently adopt later incompatible work. Exclude `agentcore-work/**` before any
checkout/materialization. Never read, stage or publish private customer files.
Verify path/origin/branch/HEAD/upstream/dirty state and claim one-writer ownership
in a draft PR before implementation. Preserve unexpected changes and stop if
ownership is ambiguous. No reset/clean/rebase/force push or PR merge.

Own a new `java/persistence-identity/` module, new standalone migrations/tests,
its scoped ADR/runbook/learning/specification evidence and the single necessary
`java/settings.gradle.kts` module entry. Reserve ADR0080 for this task; first
check whether that number is already allocated and stop on a collision. Do not
edit existing identity verifiers, in-memory stores, ingress, GitHub credentials,
canonical contracts, application authority, existing persistence modules,
existing migration bytes, launchers or CI. Shared docs receive narrow additions.

Mac owns PR108/application/durable operation authority/ADR0077. Cloud work is
PR105 credentials, PR109 execution-disabled ingress, stacked PR111 ingress/JDBC
tests and stacked PR112 evidence repair. Those paths/branches stay untouched.
This new replay library must not be mistaken for Mac's execution-permit admission.

## Read before coding

Read AGENTS.md, `docs/specs/README.md`, affected security/reliability requirements,
`WorkloadIdentityReplayStore`, its in-memory implementation/tests, existing exact
engine validation and registration/withdrawal transaction tests. Read PR109 at
`bc2486a18558dc14998db840187024fd2a2faac9` only as an integration consumer: it uses
the verified credential fingerprint as `tokenId`, not a provider `jti`. Do not
change that distinction or claim OBO/provider interoperability. Review the
existing MariaDB permit-store foundation for lessons, not authority to copy
raw diagnostic retention or couple schemas. PR107 is design/proposal only.

## Required coherent implementation

1. Preserve the existing port signature:
   `boolean consume(String tenantId, String issuer, String tokenId, Instant expiresAt, Instant now)`.
   Its inputs come from a trusted verifier/adapter, never a public body. This
   store does not authenticate, verify delegation, approve or execute anything.
   Document any narrower bounded input profile explicitly; do not normalize,
   trim or case-fold identities silently.
2. Namespace claims by exact tenant + issuer + token identity. Use a versioned,
   collision-safe, length-delimited/domain-separated hash recipe, not delimiter
   concatenation or PADSPACE text equality. Store bounded fingerprints and
   required timestamps, not raw credentials/headers/key material. Reject malformed
   Unicode and unsupported/boundary inputs without echoing their values.
3. Two independent store instances/connections racing the same key must have
   exactly one acknowledged winner. Plain unique insertion/explicit transactions
   are preferable to read-then-write or broad ignore/upsert. Recognize only the
   expected duplicate-key condition as replay; SQL/permission/schema/connection
   faults are fixed fail-closed errors, never successful consumption.
4. A duplicate must never refresh expiry or overwrite the original claim.
   Expired/equal-deadline submissions fail closed and write nothing. Preserve
   the trusted `now` boundary; do not silently replace it with an earlier database
   clock and thereby extend a verifier deadline. If database time is added, sample
   fresh time after applicable locks and state the conservative clock contract.
   No implicit clock skew allowance, fake time or datetime widening is acceptable.
5. Return true only after commit **and connection cleanup acknowledgement**.
   Partial write/rollback failure and lost commit/close acknowledgement must not
   become success or an automatic retry. Distinguish fixed uncertainty from an
   ordinary replay refusal. Exceptions must discard raw causes and suppressed
   cleanup diagnostics. Do not introduce an effectful retry anywhere.
6. Bound resource use and work per call. Never evict an unexpired claim to accept
   more work. The smallest acceptable first profile can retain append-only claims
   with an explicit finite capacity and fail-closed saturation, leaving deletion
   and retention to a separate operator design. Do not add unsafe expiry cleanup
   or unbounded scans merely for convenience. Any counter/cap update must be
   atomic with insertion and qualified under real contention.
7. Use a dedicated standalone schema/store and explicit empty-only initialization.
   Runtime is validate-only: exact inventory/history/keys, supported version,
   writer endpoint and InnoDB where applicable; no auto-migration/adoption/repair.
   Fail closed on partial/extra/corrupt schema and read-only/replica configuration.
   Demonstrate restricted runtime privileges, separate from the migration owner.
8. Exact disposable qualification: PostgreSQL16.4, MariaDB11.4.13 and MariaDB10.6.25.
   Do not substitute versions, skip unavailable Docker or claim RDS/failover parity.
   No token verification, signing, external callback or network target call under
   database locks. No runtime wiring into ingress or simulator in this task.

Separate replay claim and later registration transactions deliberately remain
separate: a failed registration can burn a verified token, and a fresh token may
retry the same scoped registration key. This is not an atomic business-operation
transaction, execution permit, exactly-once target guarantee or backup fencing.

## Required tests and quality gates

Build one shared real-engine contract, not three drifting copies. Include first
consume/replay; independent tenant/issuer separation; delimiter, case, trailing
space and coherent hash-substitution controls; strict expiry/equality; no expiry
refresh; reconstruction; genuinely concurrent independent stores; saturation;
atomic counter/claim rollback; before/after-commit and close failures; permission
denial; duplicate versus unrelated constraint errors; malformed schema/versions;
fixed diagnostics with secret canaries and suppressed-close combination failures.
Bound every worker/wait/cleanup. Run guard-removal controls and restore before
final tests. Do not reduce concurrency, workload, deadlines or coverage gates.

Use `scripts/configure-java-environment.sh`, Java21, Node22+, pnpm11.25.0 and
`pnpm install --frozen-lockfile`. Run focused tests first, then `pnpm check`,
`pnpm format:check`, documentation links/flows and diff checks. Serialize broad
and focused Gradle runs that share JaCoCo/XML outputs; one must not overwrite
another's evidence. Force the named engine suite once and capture fresh XML,
counts, exact versions and cache facts. Hosted checks must match final pushed
HEAD. Cite affected requirement IDs and add ADR/runbook/learning/traceability.

**Local environment warning:** the coordinator observed a large Docker/Mac clock
offset in Docker-info output while diagnosing a real lock-wait test failure. A
later fresh networkless container-clock probe agreed with the Mac within its
one-second resolution; the info timestamp is not proof of current database skew
or grounds for restarting Docker. Before Docker qualification,
verify bounded daemon health and host/container time consistency. If unhealthy,
stop and ask the operator to repair Docker; do not restart/prune it yourself,
change the Mac clock, widen expiry checks or label failing tests flaky. Pure
design/unit work may proceed while the environment gate is blocked.

## Productive loop and handoff

Inspect → freeze the narrow contract → focused positive/negative tests → minimal
implementation → real-engine tests → full gates → exact-head publication. At most
two broad attempts without a diagnosed repair; stop after three identical
no-progress attempts, missing trust decision, auth/quota or environment blocker.
Eight hours is a ceiling, not padding. Prefer one complete isolated library to
half-wired application authority. No live provider/target/customer/AWS/GitHub App
calls, public listeners/tunnels, secret copying or deployment.

Commit/push coherent work, open/update the draft PR, leave it unmerged, and
explicitly stop editing/release sole-writer ownership. Return exactly:

```text
TRACEPERMIT HANDOFF
- Task:
- Repository:
- Branch:
- Base commit:
- Latest pushed commit:
- PR URL:
- Branch owner now:
- Working tree status:
- Completed:
- Remaining:
- Files materially changed:
- Tests passed:
- Tests failed/not run:
- Exact next command:
- Required environment:
- Local-only/manual steps:
- Security constraints:
- Known limitations:
- Safe for another environment to continue: yes/no
```
