# ADR0091: closed durable AgentCore registration HTTP composition

- Status: implemented candidate; synthetic three-engine qualification
- Date: 2026-10-27
- Requirements: FR-002/003/008/010/011/012; SEC-001/002/003/004/006/009/012;
  REL-001/002/003/004/005/009; DATA-001; OPS-002/004; PORT-004

## Context

[ADR0082](0082-loopback-agentcore-registration-http.md) introduced the bounded
numeric-loopback HTTP transport around a trusted `AgentCoreRegistrationIngress`.
[ADR0086](0086-durable-agentcore-registration-composition.md) introduced the
closed concrete composition of the access-token verifier, exact workload
enrollment, shared JDBC replay store, durable operation repository and ingress.
Both were deliberately separate. The HTTP candidate's real JDBC qualification
used process-local replay, while the durable composition had no listener.

The missing supported path is one trusted-operator construction that joins those
existing concrete components without exposing a public callback or supplier that
could manufacture identity, replay, repository or authority decisions. This is a
composition change, not a new authentication, canonicalization, replay,
idempotency, transport or storage contract.

## Decision

Add `DurableAgentCoreRegistrationHttpServer` inside
`agentcore-durable-registration`. Its only public construction takes the existing
concrete verifier, exact enrollment, clock, separate initialized replay and
operation datasources, agreed replay capacity, finite replay DML budget, requested
port and the existing trusted HTTP `Limits` value.

Construction first calls `DurableAgentCoreRegistration.create`, which remains the
only supported path to `JdbcWorkloadIdentityReplayStore`,
`JdbcBusinessOperationRepository` and `AgentCoreRegistrationIngress`. A
package-private accessor passes that exact ingress to the unchanged
`AgentCoreRegistrationHttpServer`. The wrapper exposes only `create`, `port` and
`close`. It has no public ingress/repository/replay supplier, generic registration
callback, in-memory fallback, launcher, service registration or default wiring.
Caller-owned datasources remain caller-owned.

The underlying server still binds only numeric IPv4 `127.0.0.1`, owns all HTTP
framing, header, route, admission, deadline, cancellation and response rules, and
returns only `created`, `operationId` and `executionEnabled:false`. The durable
factory still owns validate-only startup, strict token verification, exact
enrollment, shared replay consumption and operation registration. No request
field can select a verifier, tenant, role, enrollment, target profile, replay
store, repository, policy or authority state.

## Persistence, idempotency and uncertainty

The verified credential fingerprint commits in the replay database before the
separate operation-registration transaction begins. The stores are intentionally
not a distributed transaction.

- Replay refusal, saturation, capacity disagreement, unavailable schema, write
  failure or acknowledgement uncertainty prevents registration. A replay row can
  be durable even when its acknowledgement is unknown. The server never retries.
- The same verified credential cannot register again across reconstructed
  servers or terminated/restarted JVMs sharing the stores.
- Fresh valid credentials and the same scoped request key plus semantic content
  return the immutable original operation. Changed semantic content conflicts;
  changed proposed policy/artifact metadata does not overwrite the original.
- A known registration rollback can leave the credential spent and no operation.
  Commit or cleanup acknowledgement uncertainty remains
  `AGENTCORE_REGISTRATION_UNKNOWN` even when an independent observer sees the
  complete durable record. Recovery requires an explicit fresh credential and
  same key, and returns metadata only.

Transport timeout or connection loss is not a rollback receipt. A dispatched
JDBC call that ignores interruption retains its worker/admission reservation until
it actually returns. It can later commit replay and registration metadata after
the client connection has closed. Queued replacement work must not start early,
and the server never converts timeout into a second attempt.

## Authority boundary

Only workload registration metadata is created. Initial state remains
`evaluating/not_started/pending`, and every receipt remains execution-disabled.
Human authorization and independently valid workload/human token pairs remain
refused because no verified provider exchange/grant contract exists. No approval,
permit, admission, attempt, outbox, target credential, sender or execution port is
introduced. No live AWS AgentCore, Okta, GitHub or destructive target is contacted.

## Qualification and limitations

The [qualification runbook](../runbooks/durable-agentcore-registration-http.md)
covers actual numeric-loopback HTTP, cached synthetic JWKS parsing, signed
workload tokens, restricted JDBC grants and both durable stores on exact
PostgreSQL16.4, MariaDB11.4.13 and MariaDB10.6.25 images. It includes independent
server races, terminated child-JVM restart, schema/capacity/authority negatives,
replay and registration write/commit/close faults, acknowledgement recovery and
an interruption-ignoring dependency control.

Synthetic local keys and loopback databases do not qualify provider token
compatibility, TLS/private edge deployment, role revocation, database
failover/restore fencing, production load, human delegation or target execution.
JDBC query timeouts are not total connection/commit/cleanup deadlines. Numeric
loopback is not local-process isolation. Rollback removes this unused wrapper and
its dependency edge; existing replay and operation records remain for explicit
operator review.
