# ADR 0006: Append-only evidence with external integrity anchors

- **Status:** Accepted for the POC
- **Date:** 2026-10-09
- **Requirements:** FR-010, SEC-010, SEC-012, DATA-001, DATA-004

## Context

TracePermit must explain what it observed, derived, decided, authorized, and
executed. Mutable application logs cannot preserve that timeline. A signature
or hash also cannot prove that an application's claim was true, and a database
administrator who can rewrite every row can recompute an entire hash chain.

Evidence must therefore distinguish three properties:

1. **content integrity** — the recorded bytes have not changed;
2. **history integrity** — removal, insertion, or reordering is detectable; and
3. **semantic truth** — an independently trustworthy source supports the claim.

No single application-owned hash chain provides all three.

## Decision

The POC records a bounded append-only evidence stream. Each record:

- has a monotonically increasing sequence;
- links to the prior record's SHA-256 hash;
- hashes a deterministic representation of its payload and previous hash;
- correlates trace, action, evaluation, permit, and attempt identifiers when
  available;
- stores the canonical action hash and credential fingerprints; and
- excludes raw action arguments, target results, tokens, and reusable secrets.

Gateway execution paths append separate events for observation, evaluation,
authorization, dispatch, denial/non-execution, and observed outcome.
Reconciliation is a new event; it never replaces the earlier unknown event.

The verifier may accept an expected head hash. A head retained outside the
application/database authority can reveal a completely rewritten local chain.
The process-local stream does not publish or sign that head. The durable
profile creates a per-event outbox identity, and the evidence-publication implementation publishes
that identity through a leased worker and stores an independently verified
signed receipt. The configured test signer is still not a production WORM or
transparency service.

## Consequences

### Benefits

- Local edits, broken links, gaps, and reordering are detectable.
- The recorded timeline preserves what was known at each stage.
- Evidence minimizes credential and argument exposure.
- Tests explicitly demonstrate the need for an independent anchor.

### Costs and limitations

- The in-memory stream is lost on restart and is not shared across replicas.
- An attacker controlling the complete stream can build a different internally
  valid chain unless a trusted head is retained elsewhere.
- Hash validity proves byte integrity, not identity, policy, or outcome truth.
- The application is currently its own evidence producer; producer compromise
  can create false but internally valid new events.
- Retention, production access control, protected anchor-key lifecycle, and
  WORM/transparency publication remain future work. Tenant-scoped durable
  evidence/outbox state and the signed-receipt foundation are implemented in
  the Java PostgreSQL profile.

## Production direction

Use an append-only durable stream and outbox. A separately authorized
checkpoint service should verify sequence and linkage, sign a checkpoint with a
protected key, and publish it to a location neither the application nor its
database administrator can rewrite. Independent target, policy, identity, and
approval receipts should be correlated by canonical hashes when stronger
semantic evidence is required.

ADR 0019 implements the first Java/PostgreSQL version of this direction with
bounded outbox leases, an injected publisher, exact Ed25519 receipt
verification, and append-only receipt storage. It deliberately leaves the
production anchor provider and transport unselected.
