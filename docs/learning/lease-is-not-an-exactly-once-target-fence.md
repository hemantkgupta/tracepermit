# A lease is not an exactly-once target fence

[Closed V7 delivery](../runbooks/closed-synthetic-operation-delivery.md) separates
admission, acknowledged dispatch start and the deterministic target observation.
None can be replaced by a public `approved` or `verified` label.

A writer-time lease is capped at30 seconds and every original authority deadline.
It cannot renew a permit or approval. A later fence can invalidate an expired
pre-start claim; fresh checks after waits/writes prevent an early clock sample
from authorizing a late successful insert.

After dispatch_started, recovery never resends. Crashes may leave zero calls or
an unknown result. A paused worker may resume after lease expiry: the database
cannot atomically fence its next target instruction. Only deterministic
synthetic targets are allowed; no exactly-once external effect is claimed.

Read-only reconciliation creates no capability and changes no history. Lost
synthetic memory stays unknown, never permission to retry. [ADR0090](../architecture/0090-closed-durable-synthetic-delivery.md)
records this boundary and its separate qualification gates.

Coherent hashes establish linkage, not every field's JSON type. The V7 historical
reader must also require a String principal before comparing it with the exact
owner. An administrative fixture can otherwise replace String123 with number123
and recompute all links while preserving asText equality. The bounded diagnostic
demonstrated that metadata gap, not a runtime-grant or execution-authority bypass.
Regression controls keep valid String123/Stringtrue owners readable and refuse
numeric/boolean lease/start identities even with coherent hashes. Runtime has no
history-rewrite grant, and reconciliation still cannot dispatch or create authority.
