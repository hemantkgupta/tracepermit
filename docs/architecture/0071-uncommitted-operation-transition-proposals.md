# ADR 0071: Uncommitted business-operation transition proposals

Status: Implemented pure Java proposal primitive; authoritative lifecycle service
and durable transition command remain pending.

Requirements: FR-010/015; SEC-002/005/009/010/012; REL-002/004/005;
DATA-004. These are partial structural checks, not whole-requirement completion.

## Context

[ADR 0068](0068-versioned-business-operation-contract.md) freezes the v1 state
graphs and immutable identity/action bindings. [ADR 0070](0070-durable-business-operation-registration.md)
registers only the initial aggregate. Before introducing a durable lifecycle
command, callers need one deterministic way to construct a candidate append
without rewriting history or confusing an updated document with authority.

## Decision

Add `BusinessOperationTransitionPlanner` to the Java application module. It has
no repository, credential, clock, network, signing, target or publication port.
The caller supplies the trusted scope separately, a current snapshot, an expected
state token, one dimension, next status, reason and timestamp. Scope construction
does not itself authenticate anybody.

The token binds operation ID, recovery generation, last global sequence, all
three dimension versions and the full canonical snapshot hash. It is optimistic
state identity, not a signed capability or secret. Every component must exactly
match the supplied, revalidated snapshot. A change in another dimension or an
immutable policy/action binding invalidates an old token. No stale proposal is
automatically rebased or retried.

Append one globally sequenced fact, increment only the chosen dimension's
version, update that dimension and aggregate time, and preserve every other
field and the entire old history prefix. Reuse the existing v1 validator's
graphs, version ordering, reason format and nondecreasing timestamps instead of
introducing another transition vocabulary. Reject current/candidate snapshots
over the registration library's 262,144-byte canonical UTF-8 cap. This is a
snapshot-size check, not a pre-allocation network-body or CPU-abuse guarantee.

The result is explicitly an **uncommitted proposal** with defensive copies and
redacted diagnostics. The helper does not authenticate, evaluate policy, verify
an approval/permit, claim work, observe a target, verify a publication receipt,
enforce retention or implement atomic compare-and-swap. A structurally valid
`approved`, `claimed`, `succeeded` or `published` candidate is not proof that any
such authority or event exists. It must never be accepted as a public command
body or passed directly to an executor as authorization.

Two callers can obtain different valid candidates from one starting token.
Only a future trusted durable transaction can choose one winner, after reloading
the current operation, checking scope/actor authority/current policy and recovery,
and comparing the expected state under its lock. Future persistence must commit
the projection and append-only fact together; this primitive does not do that.

The dimensions remain independent: cancellation of authorization does not stop
an already executing target, and an unknown execution cannot be reset to
`not_started` or re-executed. Terminal results still require independently
confirmed target observations at the future service boundary.

The current registration repository remains unchanged and rejects all progressed
proposals, including a same-key attempt to replace an existing initial snapshot.
It cannot become a lifecycle-update route through this addition.

## Verification and limits

Unit tests cover every token component, cross-dimension staleness, immutable
bindings, history prefix, wrong scope, malformed input/reason, backward/equal
time, invalid/terminal edges, unknown preservation, exact byte limits, defensive
copies and fixed diagnostics. A shared database test proves that a proposal
cannot overwrite a registration on PostgreSQL 16.4 or MariaDB 11.4.13/10.6.25.

No migration, HTTP route, policy relaxation, approval/permit change, background
worker, real target or production guarantee is added. The existing signed
canonicalization profile/schema/vector is unchanged. Rollback removes the unused
proposal library/tests; it never drops or mutates a populated database.
