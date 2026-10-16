# ADR 0039 — Policy lifecycle staging, rollback, and simulation

- Status: implemented in-memory and PostgreSQL durable foundations; operational control plane pending
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
   a monitor in the in-memory store and a transaction-scoped advisory lock plus
   row-level locking in PostgreSQL. The advisory lock is taken before reading
   the active pointer, including when no row exists on first activation.
   Activation deprecates the current version, creates an ACTIVE record,
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
7. **Durable PostgreSQL persistence foundation (`PolicyLifecycleStore`):**
   `java/policy` introduces the `PolicyLifecycleStore` SPI, with `InMemoryPolicyLifecycleStore`
   and `PostgresPolicyLifecycleStore` in `java/persistence-postgres`.
   - **Flyway V7 migration:** creates `tp_policy_versions`, `tp_active_policies`,
     and `tp_policy_lifecycle_transitions`, bumping the enforcement schema guard to version 7.
   - **Database immutability triggers:** `tp_guard_policy_version_mutation()` rejects
     deletion and changes to revision identity, name/description, content hash,
     document and staged time. Lifecycle state/timestamps remain mutable. The
     `tp_reject_append_only_mutation()` rejects modification or deletion of lifecycle transition history.
   - **Optimistic concurrency & atomic transitions:** `tp_active_policies` maintains a monotonic
     `activation_sequence` and current `active_version`. Activations and rollbacks support an optional
     `expectedActiveVersion` precondition; conflicting transitions throw `PolicyConcurrencyException`.
     Active pointer updates and append-only transition logs commit atomically.
     `pg_advisory_xact_lock` serializes the workspace/policy namespace before
     `SELECT ... FOR UPDATE`; hash collisions can only add contention, not
     change workspace filtering. Advisory lock acquisition has a five-second
     query timeout. Each write owns a fresh/exclusive DataSource lease and
     READ COMMITTED transaction, commits before returning, and restores the
     original isolation/auto-commit modes after success. Caller-owned or
     thread-bound transactions are unsupported. SQL and unchecked failures
     explicitly roll back; rollback failures are suppressed on the original
     failure, with no failure-path switch to auto-commit. Commit acknowledgement,
     mode-restoration or connection-close failure never reports success and
     never automatically retries. The transaction may already be durable:
     inspect active state/history through a fresh connection before proceeding.
     Idempotent activation queries within the same lease, not a nested one.
     Restaging uses conflict-safe insertion and checks the committed content hash.
     Reactivation clears obsolete deprecated/rolled-back timestamps consistently
     in both returned and persisted revision state.
   - **Rollback semantics:** automatic rollback queries the transition log to identify the
     immediate predecessor activation, enabling repeated rollbacks to cleanly toggle between
     versions. Explicit target rollback validates existence and precondition before atomic transition.
   - **Workspace namespacing:** all durable storage records are strictly scoped by `workspace_id`,
     supplied by a trusted caller. Database unavailability fails closed without in-memory fallback.

## Interpretation and limits

- In-memory lifecycle state is process-local and lost on restart; `PostgresPolicyLifecycleStore`
  persists revisions, active state and transition history across restarts.
- Caller-supplied actor identifiers (`actorId`) and reason strings are bounded tracking
  metadata, not authenticated operator provenance. Authentication must occur in an
  edge adapter before passing trusted caller identity.
- Database triggers reject ordinary application updates and deletes on revisions and
  append-only transition history. This does not claim cryptographic tamper-proofing
  against a database administrator.
- Durable policy activation integration with pending-approval stale-policy checks is
  explicitly deferred; pending human approvals continue to snapshot their evaluation-time
  policy version.
- Authenticated HTTP/MCP administration endpoints, React console policy editors,
  and distributed multi-region policy synchronization remain deferred target work.
- Calls are trusted in-process Java APIs, not policy-changing agent tools.
  A null expected version is an unconditional serialized transition; a non-null
  expected version compares only the current version. It is not an ABA-resistant
  generation token: v1 to v2 to v1 can again satisfy expected v1. The monotonic
  sequence is history metadata, not a caller-supplied concurrency precondition.
  Workspace query filters are application-level isolation, not PostgreSQL RLS
  or authorization of arbitrary caller-supplied workspace names.
  Defensive copies are not a sandbox against malicious JVM code, reflection,
  memory access or a compromised operator.
- Simulation explains policy matching for a canonical vector; it does not
  predict target failures or prove runtime latency.

## Verification

- `PolicyTemplateQualificationTest`: two opt-in candidates, 19 cases/38
  evaluations, exact classification/decision metadata, isolated scope and
  priority mutations, input preservation and a forbidden-catalog port.
  Asserted/fabricated canonical identity and malformed raw tool arguments are
  explicitly characterized as non-authentication/non-schema-validation
  boundaries. No execution authority or default runtime behavior changes.
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
- `PostgresPolicyLifecycleStoreTest` (12 integration tests on real disposable PostgreSQL):
  - `stageAndQuerySurvivesRepositoryRecreation`: staged revisions survive store recreation;
  - `activeSelectionAndRollbackHistorySurviveRecreation`: active selection and rollback history survive store recreation;
  - `sameVersionDifferentContentRejectionFailsClosed`: identical restage succeeds idempotently; differing content fails closed;
  - `inputAndReturnedDocumentMutationCannotAlterStoredPolicyOrHash`: defensive copying and DB triggers prevent alteration of stored policy or canonical hash;
  - `numericOverflowAndPriorityBoundariesRemainCorrect`: Java `int` range 1..2147483647 and signed priorities -2147483648..2147483647 are preserved without truncation;
  - `workspaceIsolationForReadsAndLifecycleMutations`: separate workspaces cannot observe or mutate each other's policies or active state;
  - `competingTransitionsObeyOptimisticConcurrencySemantics`: 32 concurrent threads competing with `expectedActiveVersion` preconditions result in exactly 1 success and 31 `PolicyConcurrencyException` rejections;
  - `failedTransactionsLeaveNeitherPartialStateNorOrphanHistory`: simulated DB trigger failure inside an activation rolls back both active state and transition history atomically;
  - `databaseUnavailabilityFailsClosedWithoutSilentInMemoryFallback`: initial store startup and runtime connection failure fail closed with `PostgresStateException` with zero silent fallback to in-memory state;
  - `existingInMemoryLifecycleAndSimulatorBehaviorRemainCompatible`: `PolicyLifecycleService` with `PostgresPolicyLifecycleStore` composes end-to-end with `SecureExecutionService`, correctly honoring allow-block-rollback transitions;
  - `repeatedRollbackTogglesBetweenVersions`: repeated rollback alternates between active versions by querying predecessor transitions;
  - `explicitTargetRollbackRespectsPreconditionAndTarget`: explicit rollback restores specified target version and validates preconditions.

- `PostgresPolicyLifecycleBoundaryTest`: first-activation interleaving with one
  active revision and connected history; unchecked activation/rollback failure
  with a commit-on-release connection; single-lease idempotence; persisted/returned
  metadata agreement; manual transactions; failed/lost commits and close failures;
  concurrent restaging, workspace mutations, SQL immutability and V6-to-V7 upgrade
  preserving an existing permit claim. Seven of the initial eight assertions
  failed against the submitted implementation before the fixes. The complete
  regression class adds 16 real-PostgreSQL tests. A separate failing probe first
  reproduced acceptance of an incompatible schema guard; startup now requires
  the exact V7 contract and rejects unknown/missing guard state.

See the [durable lifecycle runbook](../runbooks/durable-policy-lifecycle.md)
for schema upgrade, fault interpretation and reproducible local verification.
