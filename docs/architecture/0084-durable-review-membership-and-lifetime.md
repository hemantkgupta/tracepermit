# ADR0084: explicit durable-review membership and lifetime

Status: human-approved design; the durable evaluation/review transaction is
**not implemented** by this decision. Existing review constructors and V3 control
lookups remain data/prerequisites, not committed approval or admission.

Requirements: FR-003/007/008/012; SEC-001/002/003/005/009/012;
REL-001/002/004/005; OPS-002/004. No whole-requirement completion is claimed.

## Approved boundary

The operator approved this strict contract on2026-10-25:

- Mandatory exact, independently verified reviewer-group membership. Human
  identity, tenant/workspace, operation reviewer role, decision scope and
  non-self-review checks still apply to both approval and denial. A role is not
  a substitute for the selected group. Missing/unknown group fails closed.
- An explicitly configured positive approval lifetime capped at five minutes,
  with **no default**. The pending deadline is derived by the transaction owner
  from fresh writer time after all authority locks, not supplied in a command.
  Equality with any applicable deadline is expired.
- Separate fresh authority to initiate evaluation, not reused cancellation
  permission, an old canonical event's identity label or a public verified flag.
- These rules/configuration must be content-pinned to the current operator
  profile/policy and recovery generation. Changing them invalidates old pins;
  an unrelated configuration object is not implicitly covered by a v1 hash.
- Missing, stale or expired identity, membership, settings or current-control
  facts block. This decision enables no live Okta, GitHub, AWS or customer call.

Membership facts must bind exact principal, tenant/workspace and selected group
to independently derived provenance/configuration and a bounded expiry. Any
internal Java carrier is trusted-adapter data, not token verification. Never
deserialize authority facts from a public body, or perform IdP/network work
under database locks. No instantaneous membership-revocation claim follows.

## Composition prerequisites

The [independent design critique](https://github.com/hemantkgupta/tracepermit/pull/108#issuecomment-6394413528)
maps the actual canonical transition graph and reader compatibility. Implement
only evaluating→denied/awaiting_approval and pending→approved/denied through
closed commands. Expired review rejects without a decision fact; do not invent
evaluating→expired or allow post-approval withdrawal.

Use one READ COMMITTED writer transaction: current-control lock first, then
operation and any later authority rows, then fresh writer time and repeated
whole-state/group/deadline/current-pin checks. Store the immutable pending
binding and closed journal facts with the conditional head update atomically.
The current replay verifier accepts withdrawal facts only: extending its closed
versioned actor/fact vocabulary and schema compatibility is necessary, not an
arbitrary proposal-write API. Preserve original registration and V1/V2 bytes.

Reuse the exact strict GitHub target join on the actually loaded operation
without copying its hash recipe, trusting an external bound flag or introducing
an application-to-adapter cycle. Offline intended-target pins do not attest
current provider state. Send-time provider/preflight validation is separate,
outside locks, and still cannot provide atomic ref compare-and-swap or fence a
paused sender.

Return metadata only after acknowledged commit and cleanup. Unknown outcomes
never trigger an automatic decision retry or dispatch. This decision supplies
no permit, admission, attempt, outbox, execution capability, restore fence or
production qualification; those remain separate future gates.
