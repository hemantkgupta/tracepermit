# ADR 0047 — Striped authorization concurrency, trusted clock placement, and admission bounds

- Status: implemented in Java application and remote dispatcher
- Requirements: FR-005/006/009; SEC-002/005/006/009/010; REL-001/004/009; OPS-002

## Decision

Replace the process-wide `synchronized execute(...)` monitor in `SecureExecutionService`
with per-principal `ReentrantLock` striping (`StripedMonitors`, default 64 stripes,
power-of-two bounded). Striping is keyed strictly by principal identity
(`organizationId + "\u0000" + subject.id`), exactly matching
`InMemoryActionSequenceStore`'s principal conflict domain. Cross-tool and
cross-target actions for the same principal serialize on the same stripe lock;
hash collisions safely over-serialize distinct principals but never split one
principal's authorization window across stripes.

The trusted authorization timestamp (`clock.instant()`) is captured strictly
**inside** the stripe lock window, after acquiring the principal stripe and before
querying sequence history (`contextFor`), capturing the active policy snapshot,
evaluating policy, performing reviewed sanitization/re-evaluation, and recording
the authorized proposal. This linearizes the observation timestamp with the
principal's recorded sequence history: a read committed before a send authorization
acquires its stripe is guaranteed to have a timestamp earlier than or equal to the
send's authorization timestamp, preventing future-observation exclusion and
preserving sequence signals (`SYNTHETIC_PII_READ_THEN_EXTERNAL_SEND`).

A single active policy version snapshot (`policySupplier.get()`) is held throughout
both evaluations if reviewed sanitization occurs. Exact-action binding, single-use
permit issuance/consumption, execution idempotency via `InMemoryExecutionLedger`,
and hash-chain evidence appends remain atomic within their respective components.

Dispatcher admission control is bounded via `EnforcingMcpDispatcher`'s constructor
(1–1,024 permits, default 64). Saturated capacity immediately fails closed without
queueing, returning MCP tool error `ENFORCEMENT_CAPACITY_EXCEEDED` with
`execution.status: "not_executed"` and zero calls to the downstream enforcer or target.
Acquired capacity permits are unconditionally released in a `finally` block across
all outcomes, including `TargetExecutionException` and unexpected `RuntimeException`.

The lock ordering is:

1. Dispatcher admission semaphore (`tryAcquire` non-blocking)
2. Principal stripe lock (`ReentrantLock.lock`)
3. Sequence store history query (`contextFor`)
4. Policy snapshot and evaluation
5. Sequence store recording (`record`)
6. Principal stripe unlock (`finally { stripe.unlock(); }`)
7. Permit issuance and atomic consumption
8. Execution ledger claim (`executions.begin`)
9. Target invocation (`target.invoke`)
10. Execution ledger outcome update (`succeed` / `fail` / `markUnknown`)

## Consistency and limits

- `tryAcquire` is non-blocking admission; admitted calls can still wait on
  principal stripes, sequence stores, execution ledgers, or target locks.
- This is an admission ceiling per dispatcher instance, not a process-wide ingress
  bound or per-tenant fairness guarantee.
- Fixed lock ordering covers service-owned authorization steps; it is not a proof
  of universal deadlock freedom across arbitrary external callbacks.
- Principal-history records in `ActionSequenceStore` track authorized proposals,
  not confirmed target execution effects. Target execution occurs after stripe
  release.
- This service change applies to direct simulated execution; it does not
  automatically accelerate `HumanApprovalService` or the durable browser/MCP
  approval composition.
- Target-overlap tests prove that distinct principals no longer serialize on a
  global process monitor; they do not establish p95/throughput claims (e.g. 500 QPS),
  distributed multi-node safety, or production readiness.

## Verification

- `SecureExecutionServiceConcurrencyTest` (13 tests):
  - Temporal-order regression tests under both shipped policy and test-only
    baseline-allow/PII-sequence-deny policy, verifying that a committed read is
    never invisible to a subsequent send authorization.
  - Genuinely serialized send-before-read order verification: prior send executes,
    read executes, and subsequent send blocks with `SYNTHETIC_PII_EXFILTRATION_SEQUENCE_BLOCKED`.
  - Service lock release tests: failure injection into `contextFor` and `record`
    fails closed with zero target effects, and subsequent actions for the SAME
    principal from a DIFFERENT thread succeed.
  - Sanitizer failure lock release and recovery call.
  - Cross-tool read and send actions for the same principal share the same stripe lock.
  - Hash collisions over-serialize safely without splitting principal windows.
  - Concurrent rapid-read gate, racing identical events, evidence hash-chain validity,
    and distinct-principal target overlap.
- `StripedMonitorsTest` (6 tests):
  - Deterministic principal mapping, negative hash handling, power-of-two validation,
    reentrancy, and single-stripe over-serialization.
- `EnforcingMcpDispatcherCapacityTest` (4 tests):
  - Saturated capacity fails closed with `ENFORCEMENT_CAPACITY_EXCEEDED` and
    `not_executed`, verifying zero enforcer calls on rejection.
  - Capacity release on `TargetExecutionException` and unexpected `RuntimeException`.
  - Constructor bound validation (bounds 1 to 1,024).
