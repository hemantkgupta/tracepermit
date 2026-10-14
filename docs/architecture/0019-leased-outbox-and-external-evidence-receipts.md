# ADR 0019: Publish evidence through leased outbox claims and signed receipts

**Status:** Accepted

## Context

A local hash chain can expose accidental mutation inside one evidence stream,
but a compromised database/application boundary can rewrite both events and
chain heads. Evidence therefore needs a checkpoint held by a different trust
boundary. Calling that boundary inside the execution transaction would couple
target availability to an external audit service and hold database locks over
network I/O.

## Decision

Use the transactional outbox already created with each evidence event. Workers
claim rows with bounded PostgreSQL leases, publish outside a transaction, and
store an immutable receipt only after verifying an Ed25519 signature from an
operator-configured external public key. The signed format binds tenant,
outbox, stream, sequence, evidence event, payload hash, provider, key, anchor
ID, time, and algorithm.

Delivery is at-least-once. The stable outbox ID is the external idempotency key.
Unknown or mismatched trust inputs fail closed for publication, while execution
continues to depend on durable local evidence creation rather than live anchor
availability.

## Consequences

- execution transactions remain short;
- concurrent workers cannot actively own the same row;
- a crash is recoverable after lease expiry;
- receipts are externally verifiable and tenant-scoped;
- the external sink can observe bounded identifiers and hashes but no raw
  action or credential; and
- exactly-once publication is not claimed without sink-side idempotency.

This does not make evidence truthful and does not configure a production
anchor, key, network route, or credential.
