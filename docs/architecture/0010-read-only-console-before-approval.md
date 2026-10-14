# ADR 0010: Keep the initial console read-only

- **Status:** Accepted
- **Date:** 2026-10-09
- **Requirements:** FR-010, FR-014, SEC-008, SEC-012, OPS-001, OPS-002

## Context

The saved console concept contains prominent `Deny` and `Approve and execute`
buttons. Implementing those buttons before the approval security model exists
would create an attractive but false authority boundary. The current Java API
has no remote administrator identity, tenant roles, CSRF-safe approval
transport, or authenticated mutation API. The request-bound approval service provides durable
request binding, reviewer authorization at the application boundary,
single-use consumption, and mutation evidence, but it deliberately does not
weaken this console boundary.

The product still benefits from an early console because visibility can test
the action projection, operator information hierarchy, causal lineage, policy
explanation, API shape, and responsive design.

## Decision

Implement the first React/Vite console as a read-only consumer of the Java
loopback API. It may search, filter, and select projected action records. It
must not send any mutation request.

Render the concept's approval controls only as disabled design context and
place an explicit read-only notice beside them. Mark all data as simulated and
use the API's real demo counts rather than presentation-only production values.

Keep console state derived from a secret-minimized read model. Do not expose the
canonical command body merely because a UI could hide fields visually.

## Consequences

### Benefits

- UI and API contracts can evolve without manufacturing unsafe approval
  semantics.
- The operator experience is tested against actual Java policy projections.
- The UI cannot bypass policy or simulator controls because it has no write
  route.
- Safety limitations are visible in the product, not only in documentation.

### Costs and limitations

- The initial console cannot approve, deny, execute, reconcile, or edit policy.
- Overview, Approvals, and Integrations remain visual scaffolding; Evidence,
  Lineage, and Policies are read-only query views.
- Mobile hides the detail panel until a proper accessible drawer is designed.
- The API remains loopback-only and unauthenticated; the console is not a
  remotely deployable control plane.

## Next decision point

Before enabling approval controls, add a production reviewer authenticator and
tenant-role provider, authenticated mutation contract, session and CSRF
protection, idempotent client request semantics, rate/abuse controls, and
operator-facing mutation evidence. The exact request binding, expiry,
single-use database consumption, and core evidence behavior now exist behind
the Java application port. UI enablement remains the last step, not the first.

## Additional read-only views

The later Evidence, Lineage, and Policies navigation items are now implemented
as dedicated read-only views under the same boundary. They query bounded
server-owned projections and add no approval, execution, policy-write, or
evidence-mutation route. “Integrity valid” remains distinct from external
anchoring and truth; a lineage delegation label remains a structurally
validated claim rather than production cryptographic authority.
