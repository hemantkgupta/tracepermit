# ADR 0068: Versioned business-operation contract foundation

Status: Implemented contract foundation; durable repository, API, and target
execution remain pending.

Requirements: FR-002/007/010/012/015; SEC-001/002/003/005/009/010/019;
REL-001/002/003/004/005/008/009; DATA-001/002/004; OPS-002/004;
PORT-001/002/003.

## Context

The canonical action records one observed request. It is intentionally tied to
an event and transport lineage. An enterprise client may repeat the same
business request in another transport session with another event ID. Reusing
the event ID or a model/tool-use ID as the durable business identity would make
request conflict handling, recovery, and target reconciliation ambiguous.

The existing durable simulator records approvals and execution attempts, but it
does not persist a general business-operation aggregate containing the immutable
canonical action. Database-neutral operation semantics must be fixed before a
MariaDB repository, public API, AgentCore adapter, or real target adapter is
allowed to invent its own states.

## Decision

Add strict language-neutral `business-operation-v1` schema and vector artifacts
plus `BusinessOperationValidator` in the Java `contracts` module. The aggregate
has a server-generated operation UUID and trusted tenant, workspace, requester,
and adapter identifiers. Those trusted-context fields are internal adapter
output, never fields accepted from an unauthenticated public request body.

Keep these identities separate and explicit:

- the transport request ID and canonical event ID inside the immutable action;
- the server operation ID;
- a fingerprint of the client request key;
- the full canonical action hash;
- a semantic payload digest over the canonical `action` object;
- a resource-and-precondition binding hash; and
- a target idempotency digest derived from operation ID, adapter ID, and
  recovery generation.

Bind the exact policy release and relevant artifact digests. Require an explicit
retention-policy identifier and future `retainUntil` value rather than treating
unspecified retention as safe. No credential, token, target secret, or raw
client request key belongs in the aggregate.

Represent authorization, execution, and evidence delivery as independent
snapshot dimensions backed by one globally ordered append-only transition
history. Each dimension begins in one exact initial state, advances only over
an allowlisted edge, and has a contiguous optimistic version. Snapshot status,
version, and time must equal the final transition for that dimension. The first
transition equals `createdAt`; the final transition equals `updatedAt`.

The Java validator requires the embedded action to already equal its normalized
form, recomputes every derivable digest, validates state graphs and time/version
ordering, rejects unknown schema values and fields, and returns a defensive
copy. The language-neutral vector is a deterministic simulated draft pull
request in a synthetic repository; it performs no network or target action.

## Consequences and limits

The contract prevents later adapters from conflating transport, event,
operation, semantic, resource, and target identities. It gives PostgreSQL and
MariaDB implementations the same state vocabulary and supplies adversarial
tests for mutation, relabeling, unknown fields, cross-dimension states, skipped
versions, reordered transitions, stale generations, and unbound preconditions.

This slice does **not** implement request-key lookup or conflict semantics,
database tables or constraints, snapshot encryption/access control, retention
deletion, HTTP endpoints, AgentCore ingestion, approval, permit issuance,
execution, reconciliation, S3 publication, or a real GitHub call. A single
document validator cannot prove atomic insert-or-return behavior across two
requests. Those properties require a durable repository and database tests.

The frozen canonical JSON v1 numeric behavior is unchanged. Business adapters
that need exact large integers or decimal money must use string/decimal domain
contracts or a future versioned canonicalization profile; they must not silently
reinterpret v1 numbers.

Rollback removes the new schema, vector, validator, tests, and documentation.
It changes no current approval, execution, or target behavior.
