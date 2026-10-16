# ADR 0039 — Policy lifecycle staging, rollback, and simulation

- Status: implemented in-memory Java foundation; operational control plane pending
- Date: 2026-10-16
- Requirements: FR-013; REL-006; SEC-002; OPS-002

## Context

The Java execution service previously received a static policy document.
A trusted library caller needs to validate/stage a candidate, simulate decisions,
activate a revision and restore the preceding activation without inventing a
policy administration surface or weakening the pre-execution policy invariant.

## Decision

Add `PolicyLifecycleService` in `java/policy` and an optional dynamic-policy
constructor in `SecureExecutionService`. Default backend launchers keep their
static-policy composition; no writable HTTP/MCP endpoint or React editor is added.

1. **Private candidate validation:** `PolicyValidator` copies input before
   checking policy-schema fields/types/enums, duplicate rule IDs and supported
   match criteria. The validated snapshot is the document ingested, not a
   caller-owned tree that can change after validation.
2. **Explicit Java numeric profile:** versions must be 1..2147483647 and
   priorities -2147483648..2147483647. Negative priorities are valid. Missing,
   fractional, nonnumeric or out-of-range values fail with `ContractViolation`
   before lifecycle insertion or dry-run evaluation. Catalog keys, decisions and rule
   ordering currently use Java `int`; unsupported numbers are never truncated
   into another version or precedence. The frozen language-neutral schema has a
   wider safe-integer envelope and remains unchanged. Widening the Java profile
   needs an end-to-end compatibility decision, not a cast.
3. **Immutable document snapshots:** `PolicyVersionRecord` copies constructor
   input and every `document()` result. Records exposed by stage, activation,
   active/query/list/restage and rollback cannot reveal internal mutable JSON.
   Each content hash uses the existing canonical SHA-256 profile. Identical
   restaging is idempotent; differing content for an existing version fails
   `POLICY_VERSION_IMMUTABLE`. Caller mutation cannot alter stored hashes,
   current decisions, future staged activation or rollback content.
4. **Isolated dry-run:** `simulate` validates policy and canonical action and
   calls the existing evaluator with optional classification context. It returns
   `PolicySimulationResult`; the lifecycle service has no permit issuer,
   executor, target or evidence-ledger port. It does not stage/activate candidates
   or perform even a simulated target effect.
5. **Serialized writes and atomic active selection:** per-policy mutation uses
   a monitor. Activation deprecates the current version, creates an ACTIVE record,
   appends activation history and publishes the active reference. Rollback marks
   the current revision ROLLED_BACK, restores the preceding activation and appends
   a `RollbackRecord` with versions, time and caller-supplied reason. A missing
   active/predecessor version fails closed. Reads obtain an atomic active pointer
   without the catalog writer monitor; copying and per-service synchronized
   execution remain. No latency, throughput or end-to-end lock-free guarantee
   follows from an atomic reference.
6. **Optional execution integration:** the dynamic constructor obtains one
   defensive policy snapshot per action and reuses it during sanitization
   re-evaluation. The existing permit, single-use/idempotency and safe simulator
   checks remain in place. A rejected action still dispatches nothing.

## Interpretation and limits

- Catalog, activation history and rollback reasons are process-local memory and
  lost on restart. A reason string is not authenticated operator provenance.
- Calls are trusted in-process Java APIs, not policy-changing agent tools.
  Defensive copies are not a sandbox against malicious JVM code, reflection,
  memory access or a compromised operator.
- Durable immutable revisions/audit, tenant/role-scoped administration, actual
  launcher/API wiring, pending-approval invalidation and distributed rollout
  remain target work. FR-013/REL-006/OPS-002 are not complete operationally.
- Rollback selects the preceding activation-history entry; repeated rollback
  behavior is not a persistent deployment history/retention service.
- Simulation explains policy matching for a canonical vector; it does not
  predict target failures or prove runtime latency.

## Verification

- `PolicyValidatorTest`: supported repository policies, unknown fields/schema,
  duplicate IDs, invalid decisions and match enums.
- `PolicyNumericRangeTest`: overflow/collision rejection before catalog
  mutation, arbitrary-size integer rejection, maximum supported version and
  signed priority boundaries, including negative priorities and correct ordering.
- `PolicySnapshotIsolationTest`: mutation of caller input and all exposed
  document paths cannot alter stored hashes, active/simulated decisions,
  later activation or rollback.
- `PolicyLifecycleServiceTest`: staging, activation, missing state, rollback,
  simulation and bounded concurrency. Every reader starts and every submitted
  Future is observed so worker failures cannot be silently ignored.
- `SecureExecutionServiceLifecycleTest`: allow-to-block-to-rollback composition
  and a separate attempted-document-mutation regression with zero simulator
  dispatches. These are local synthetic tests, not production qualification.
