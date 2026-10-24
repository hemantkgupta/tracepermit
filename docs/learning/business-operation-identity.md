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

The validator checks one aggregate. The separate registration repository now uses
a scoped unique database key, returns the original matching retry, rejects changed
bindings and commits a snapshot plus its three initial facts atomically. A commit
exception remains unknown even if rollback later succeeds: only a later explicit
same-key read/registration can establish whether that initial record exists.

Registration grants no authority. Current identity checks, approval/permit
consumption and authority-bearing state transitions still belong to a future
service composition. See [ADR 0068](../architecture/0068-versioned-business-operation-contract.md)
and [ADR 0070](../architecture/0070-durable-business-operation-registration.md).

The [transition proposal helper](../architecture/0071-uncommitted-operation-transition-proposals.md)
can now construct a candidate append against an exact snapshot token. It preserves
all immutable bindings and rejects stale versions, scope mismatch and invalid
edges. The token is forgeable state identity, not a permit. A valid `approved` or
`claimed` document is still only data: two proposals can share one starting token,
and the current registration repository rejects both as lifecycle updates.
Future actor/policy/approval checks and an atomic durable command must precede
any authority or target effect. Cancelling authorization does not stop an
already executing target; unknown never becomes permission to try again.

The [durable withdrawal command](../architecture/0072-durable-pending-operation-withdrawal.md)
now closes one small lifecycle boundary: pending/not-started work can be cancelled
by the verified owner with explicit role/scope, unexpired authority and an exact
expected token. Those checks repeat after a database row lock using store time;
one fact and its separate current head commit together. A consistent inspection
replays the journal, while registration retries still return the immutable
initial record. This distinction matters: the initial `evaluating` snapshot is
not the current state and cannot authorize execution. The new command is not a
generic lifecycle service, body-authentication shortcut, permit or target call.

Hash checking starts with unambiguous decoding: stored operation JSON rejects
duplicate keys and trailing data before hashes are compared. A last-key-wins or
prefix parser can conceal damaged bytes without changing the parsed-value hash.
This strict reader is local to the operation store, not a change to the frozen
canonical profile or an authenticity guarantee against database administrators.
