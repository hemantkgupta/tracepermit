# ADR 0021: Bound security telemetry and require it before remote dispatch

**Status:** Accepted

## Context

Free-form logging of remote connector requests risks copying credentials,
arguments, results, tenant identifiers, and attacker-controlled values into a
widely accessible observability system. Silently losing all telemetry while a
hardened edge continues dispatching also defeats the production readiness
contract.

## Decision

Expose a typed, low-cardinality event with an allowlisted attribute vocabulary
and no payload/message field. Project that event to OpenTelemetry metrics and
spans. The hardened remote edge must successfully record the authorization
boundary event before dispatcher invocation. If that required call fails, it
returns a bounded 503 and does not dispatch.

Reporting an event about a request that is already denied is best-effort:
export failure cannot turn the denial into an allow or replace the safer
response. Post-dispatch telemetry cannot retroactively change an outcome;
production health must instead remove an unhealthy instance from traffic.

## Consequences

- telemetry cannot accept raw security payloads through its public model;
- pre-dispatch exporter failure fails closed;
- denial semantics remain stable during telemetry outages;
- production still needs an exporter-health probe and alert delivery tests;
- high-cardinality correlation remains in redacted evidence, not metrics.
