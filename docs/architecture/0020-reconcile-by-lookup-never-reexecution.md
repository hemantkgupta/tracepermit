# ADR 0020: Reconcile unknown outcomes by lookup, never re-execution

**Status:** Accepted

## Context

After dispatch, a timeout or lost response can leave TracePermit unable to
prove whether the target effect occurred. Treating this as failure may permit a
retry that duplicates a non-idempotent effect. Treating it as success invents
evidence. Retrying the original action from a background worker is therefore
unsafe.

## Decision

Persist an unknown-outcome queue entry in the same transaction as the unknown
transition and evidence. A leased worker may only call an injected read-only
resolver keyed by the existing tenant, adapter, and stable idempotency hash.
Only a confirmed result appends a terminal transition and reconciliation event.
Unknown or unavailable lookups schedule another read; they do not invoke the
effect and do not change execution status.

Queue workers lock the queue before the attempt. Manual reconciliation uses
the same order. External lookup occurs without an open database transaction.

## Consequences

- uncertain outcomes cannot trigger hidden re-execution;
- history preserves what was known at each time;
- workers recover through lease expiry and durable retry scheduling;
- a target adapter must expose a trustworthy lookup or the attempt remains
  unknown; and
- the POC does not claim that every target can be reconciled.
