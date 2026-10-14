# ADR 0005 — Track logical execution and preserve uncertain outcomes

- Status: accepted for POC
- Date: 2026-10-08

## Context

A single-use permit prevents replay of one capability, but a caller could
obtain another permit for the same canonical event. TracePermit also needs to
prevent duplicate business effects and represent the crash window where a
target may have acted but its response was lost.

## Decision

The POC adds a process-local execution ledger keyed by a SHA-256 idempotency key
derived from the event ID and canonical action hash. After executor-side permit
verification and consumption, the executor atomically begins one logical
attempt before calling the simulator.

The attempt has an append-only transition history:

```text
executing -> succeeded
executing -> failed
executing -> unknown -> succeeded | failed
```

Behavior for a later valid permit with the same idempotency key is:

- `succeeded`: return the stored result without invoking the simulator again;
- `executing`: reject as already in progress;
- `failed`: do not retry automatically; and
- `unknown`: block until reconciliation confirms an outcome.

Generic target exceptions are classified as `unknown` because the executor
cannot prove that no effect occurred. Only an explicit simulator error may
declare a confirmed failure.

## Trust limitation

The ledger is in memory and protects one running process only. It is lost on
restart and does not coordinate replicas. The current MCP adapter generates a
new event for a new tool call, so transport retries are not deduplicated unless
they are correlated to the same canonical event by an upstream workflow.

A production design needs a tenant-bound client/idempotency key, an atomic
durable claim, execution attempts in persistent storage, target-side
idempotency or lookup, and a reconciliation worker.

## Consequences

- Permit replay and logical-operation duplication are treated separately.
- A successful logical action can return its stored result under a later valid
  permit without repeating the effect.
- Concurrent duplicates cannot both call the simulator in one process.
- Unknown outcomes remain blocked and visible until explicitly reconciled.
- State transitions preserve the earlier uncertainty rather than overwriting
  it.
