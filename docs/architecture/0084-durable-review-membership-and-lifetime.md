# ADR0084: explicit durable-review membership and lifetime

Status: human-approved boundary; input/settings prerequisites implemented. The
durable evaluation/review transaction is **not implemented**. Review constructors,
group checks and V3 control lookups remain prerequisites, not committed approval
or admission.

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

## Implemented input/settings checkpoint

`BusinessOperationReviewSettings` requires exact group and enrollment-source
fingerprint plus a positive explicit lifetime through five minutes. Seconds and
nanoseconds are both pinned; there is no rounding, fallback or default. Its
deadline helper accepts only a supplied writer instant, never a caller clock.
The future transaction owner must obtain that instant after all authority locks.

The explicit seven-argument `BusinessOperationAuthorityProfile` uses a versioned
v2 fingerprint containing all review settings. The six-argument v1 hash recipe
remains unchanged for existing unwired contracts. The new gates reject v1
profiles instead of implicitly upgrading them. A v2 review-only policy must
select the configured exact group for `require_approval`. Existing V3 control
storage binds these settings via the profile hash: changed group, provenance or
even one lifetime nanosecond cannot match current pins. Replacing settings needs
a newer generation and explicit activation, not a migration or broader grant.

`BusinessOperationReview.requireGroupAuthorizedAt` repeats the existing
independent-human role/scope/tenant/workspace/non-self checks and checks exact
principal/group/provenance and membership freshness. Membership cannot extend
authentication expiry; future observations and equality with expiry reject.
`TrustedOperationGroupMembership` is an internal adapter fact carrier, not token
verification or a public body schema. The old identity-only method remains for
compatibility and is explicitly insufficient for the future durable decision.

`BusinessOperationEvaluation` separately requires `operation_requester` and
`operations.evaluate`, exact owner scope, explicit settings and fresh
authentication. Withdrawal permission is insufficient. Expected state is an
optimistic identity, not proof that the operation is still evaluating. Neither
new command writes a fact, changes a head or enables an endpoint.

See the [settings qualification runbook](../runbooks/operation-review-settings.md).
The current six-field review actor, withdrawal-only journal reader, database
migrations, runtime grants and legacy simulator composition are unchanged.

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
