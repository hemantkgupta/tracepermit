# ADR 0039 — Policy lifecycle staging, rollback, and simulation

- Status: implemented
- Date: 2026-10-16
- Requirements: FR-013; REL-006; SEC-002; OPS-002

## Context

TracePermit policies govern all agentic actions and determine whether actions are
allowed, blocked, sanitized, or held for human approval. Previously, policy evaluation
was bound to static JSON instances at service construction. Operators had no
standardized runtime mechanism to validate candidate policies against the checked-in
schema, stage candidates safely, simulate their behavioral impact against canonical
actions without side effects, atomically activate new versions without downtime,
or roll back to previous active versions when regressions are detected.

## Decision

Add policy lifecycle management directly in the `java/policy` module and integrate
dynamic evaluation into `SecureExecutionService`:

1. **Strict validation (`PolicyValidator`):** Validates candidate policies against
   `contracts/json-schema/policy-v1.schema.json` and system invariants. Rejects
   unknown outcomes, duplicate rule IDs, negative or non-integral priorities,
   and unpermitted match criteria with `ContractViolation` before candidate
   ingestion (`SEC-002`).

2. **Immutable version staging (`PolicyVersionRecord`):** Candidate policies are
   staged with explicit `1.0` schema version, positive integer version, and
   canonical SHA-256 content hash (`CanonicalJson.sha256`). Re-staging identical
   content is idempotent; attempting to alter content for an existing version
   fails closed (`POLICY_VERSION_IMMUTABLE`).

3. **Isolated dry-run simulation (`simulate`):** Evaluates canonical action
   events against candidate or staged policy documents with optional sequence
   context. Simulation returns a `PolicySimulationResult` indicating rule match,
   decision outcome, and reason code. Invariant: simulation never issues execution
   permits, never mutates evidence ledgers, and never dispatches to simulators.

4. **Atomic activation and rollback (`PolicyLifecycleService`):**
   - `activate(policyId, version, now)` atomically transitions a staged version
     to `ACTIVE`, deprecating the currently active version and updating chronological
     activation history.
   - `rollback(policyId, now, reason)` restores the immediately preceding active
     version, transitions the current version to `ROLLED_BACK`, and appends an
     immutable `RollbackRecord` capturing `fromVersion`, `toVersion`, `rolledBackAt`,
     and operator reason (`REL-006`, `OPS-002`).
   - Active version lookups use atomic references, guaranteeing lock-free,
     sub-millisecond evaluation throughput under concurrent execution.

5. **Runtime enforcement integration:** `SecureExecutionService` supports
   dynamic policy resolution via `PolicyLifecycleService`, ensuring subsequent
   enforcement actions immediately reflect activated or rolled-back versions,
   while preserving idempotent replays of previously executed client event IDs.

## Interpretation and limits

- Staging and rollback provenance is tracked in the Java lifecycle catalog;
  distributed multi-cluster replication across distinct regions remains target work.
- Rollback requires an established activation history with at least one previous
  active version; rolling back an initial single version fails closed.
- Simulation verifies policy rule matching for a given action vector; it does not
  execute simulated targets or predict downstream target exceptions.

## Verification

- `PolicyValidatorTest`: validates repository policies and asserts fail-closed
  rejection of invalid schemas, duplicate IDs, unknown outcomes, and bad enums.
- `PolicyLifecycleServiceTest`: verifies staging idempotency, immutability guards,
  atomic promotion, linearizable rollback provenance, and multi-threaded evaluation
  safety during active rollout cycles.
- `SecureExecutionServiceLifecycleTest`: proves end-to-end enforcement dynamically
  shifts from allow to block upon activation and cleanly reverts upon rollback.
