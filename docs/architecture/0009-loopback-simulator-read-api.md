# ADR 0009: Separate simulated target state from a loopback read model

- **Status:** Accepted
- **Date:** 2026-10-09
- **Requirements:** FR-012, FR-014, SEC-007, SEC-008, SEC-012, OPS-001

## Context

The Java enforcement core had pure components and a functional target
port, but no stateful target for test execution and no query surface
for the saved control-plane console concept. Adding a general control-plane
server now would imply production identity, tenancy, durability, and write
authority that do not exist yet.

The console also needs less data than the enforcement path holds. Sending raw
canonical actions would unnecessarily expose arguments, identity evidence,
credential fingerprints, delegation token hashes, and client claims.

## Decision

Add two explicit development components:

1. `simulator` implements the existing `SimulatedTarget` port with narrow AWS
   and Jira operations backed only by synchronized in-memory fake state. It has
   no target SDK, credential provider, external endpoint, or network client.
2. `adapters-http` exposes a separate secret-minimized action-feed read model.
   Its server binds only to loopback, validates Host and browser Origin, and
   accepts only `GET` plus local CORS preflight.

The HTTP adapter has no mutation route. In particular, it cannot execute an
action, approve a request, reconcile an outcome, change policy, or mutate the
simulator. The action feed is visibility evidence and never becomes execution
authority.

The development server is intentionally implemented with the JDK HTTP server
instead of adopting a production framework. Framework selection remains open
until authentication, tenancy, persistence, and deployment requirements are
ready to drive it.

## Consequences

### Benefits

- Java UI work can start against a real typed API without inventing authority.
- Stateful adapter behavior can be tested without AWS, Jira, credentials, or
  egress.
- The read model proves data minimization independently of the enforcement
  event.
- A loopback check plus Host/Origin allowlists prevent accidental remote use of
  an unauthenticated development surface.

### Costs and limitations

- State and action history disappear on restart and are not shared by replicas.
- Synchronization proves only in-process behavior.
- Demo records are deterministic projections, not a durable evidence stream.
- The API has no authentication because non-loopback binding is rejected; that
  assumption cannot survive deployment.
- The JDK server is not the selected enterprise HTTP runtime.

## Next decision point

Before adding any remote or mutating control-plane API, select the authenticated
tenant/role model, durable read store, framework, CSRF/CORS posture, pagination,
rate limits, and audit behavior. Human approval remains a distinct security
workflow and must not be implemented as a generic action-feed button.
