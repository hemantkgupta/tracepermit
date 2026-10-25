# Why a valid review command is not execution authority

An authenticated independent reviewer can express approve or deny for one exact
observed operation. That input still does not prove the operation is pending,
the approval is unexpired, policy/recovery generation is current, or a database
transaction committed. A valid expected hash is an optimistic state identity,
not a capability: any caller can calculate or copy it.

The new [closed review contracts](../architecture/0077-closed-operation-authority-contracts.md)
separate three responsibilities:

1. The trusted adapter verifies identity and enrollment and supplies a bounded
   workspace-specific context. Request fields never verify themselves.
2. The transaction owner loads and locks authoritative state, samples database
   time **after** lock acquisition, rechecks all bindings and commits approval
   with its append-only facts. This step is not yet implemented for operations.
3. Separate single-use admission must consume fully bound execution authority
   and create an attempt atomically. Even a committed approval is not execution.

A static copied policy/profile can detect an operation differing from configured
expectations. It cannot detect an unseen policy change in another process or an
older restored database. Its fingerprint is not a signature or current permit.
The review-only profile rejects `allow`/`sanitize`, so a permissive policy result
cannot accidentally bypass human review in this unfinished composition.

The current command/profile classes have no database or target port. The
[wave-two board](../planning/wave2-durable-authority-batch.md) names the remaining
durability, admission and integration gates. Existing local simulator approval
flows remain a separate qualified composition, not evidence for this new path.
