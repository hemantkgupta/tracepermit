# Learning: event identity is not business-operation identity

An action event answers “what did this adapter observe in this request?” A
business operation answers “which durable intent are we governing across
retries, sessions, approvals, execution, and reconciliation?” They are related,
but interchangeable IDs make recovery unsafe.

A client or transport request ID can change when a request is retried. A model
tool-use ID can change when an agent replans. The canonical event ID identifies
one observation. The TracePermit operation ID is server generated and remains
stable for the durable business intent. The client request key is stored only
as a scoped fingerprint, while a separate semantic payload digest detects reuse
of that key for changed content. A target idempotency digest is derived from the
operation and recovery generation; it is never copied from transport metadata.

The full canonical action hash still binds identity, lineage, trigger, action,
and target. The semantic payload digest deliberately covers only the canonical
`action` object for future request-key conflict comparison. The resource binding
separately fixes the resolved resource and conditional precondition. None of
these hashes proves authentication or grants execution authority.

Authorization, execution, and evidence delivery are separate lifecycle
dimensions. “Approved” must not imply “executed”; “succeeded” must not imply
“evidence published”; and delivery uncertainty must not turn a target outcome
into a retry. Append-only transitions preserve how each projection reached its
current state.

The current foundation validates one aggregate. Durable request-key uniqueness,
same-key/same-payload return, same-key/different-payload conflict, authorized
snapshot reads, and atomic state transitions still need a repository and real
database constraints. See
[ADR 0068](../architecture/0068-versioned-business-operation-contract.md).
