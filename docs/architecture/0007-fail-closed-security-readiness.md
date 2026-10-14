# ADR 0007: Fail closed on security dependency unavailability

- **Status:** Accepted for the POC
- **Date:** 2026-10-09
- **Requirements:** REL-001, OPS-001, SEC-011

## Context

A running process is not necessarily safe to receive effectful traffic.
Authentication, policy, permit signing, replay protection, and evidence
recording are part of the authorization path. Treating their failure as an
ordinary warning can turn a dependency outage into an authorization bypass.

Liveness and readiness answer different questions:

- **liveness:** is the process running and diagnosable?
- **readiness:** are all required controls available for safe traffic?

The executable POC also contains only development authentication,
process-local signing, replay, and evidence components. Binding it to a remote
interface must not imply that those controls are production-ready.

## Decision

TracePermit models five required security dependencies:

1. authentication;
2. policy;
3. permit signer;
4. replay store; and
5. evidence store.

`GET /health` remains a liveness endpoint. `GET /ready` returns `200` only when
every required dependency is ready and returns `503` otherwise. MCP dispatch,
the development token route, trusted evaluation, and execution fail with
`SECURITY_DEPENDENCY_UNAVAILABLE` before policy evaluation or target execution
when readiness is false.

The executable loopback profile marks its complete simulator stack ready. The
non-loopback profile deliberately marks production authentication, separated
permit signing, durable replay, and durable evidence unavailable. It can remain
alive for diagnosis but cannot accept MCP traffic until real implementations
are explicitly wired and reviewed.

Responses expose only bounded component names and states. They do not contain
credentials, endpoints, stack traces, or internal configuration values.

## Consequences

### Benefits

- Security dependency outages do not silently fail open.
- Orchestrators can distinguish process health from safe traffic readiness.
- A remote bind cannot accidentally turn development controls into a claimed
  production deployment.
- Tests verify that dependency failure occurs before authentication dispatch or
  target execution.

### Costs and limitations

- The current provider reports configured process state; it is not yet a live,
  timeout-bounded probe of durable services.
- A readiness check can become stale immediately after it is read. Individual
  enforcement components must still fail closed during their own operations.
- Returning unavailable dependency names is acceptable for the POC but may need
  a more restricted public response in some deployments.
- Live OAuth/JWKS transport, durable stores, separated signing, metrics,
  alerts, circuit breakers, and operational runbooks remain unimplemented; the
  transport-neutral Java OAuth verifier foundation exists separately.

## Production direction

Each dependency adapter should expose a bounded health signal with freshness
and timeout rules. Readiness should drive load-balancer routing, while runtime
errors retain component-specific fail-safe behavior. Alerts must distinguish
planned maintenance, transient degradation, and security-control failure.
