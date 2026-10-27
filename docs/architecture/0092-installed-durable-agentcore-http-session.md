# ADR0092: installed bounded durable AgentCore HTTP session

- Status: implemented candidate; focused qualification passed, aggregate failures deferred in issue126
- Date: 2026-10-27
- Requirements: FR-002/003/008/010/011/012; SEC-001/002/003/004/006/009/012;
  REL-001/002/003/004/005/009; DATA-001; OPS-002/004; PORT-004

## Context

[ADR0091](0091-durable-agentcore-registration-http-composition.md) joins the
existing numeric-loopback HTTP transport to the closed durable registration
factory, but deliberately provides no executable or default service wiring.
[ADR0089](0089-source-free-synthetic-registration-cli.md) provides a hardened
installed command for one offline configuration check or one registration, but
deliberately provides no listener. A local operator therefore cannot exercise
the qualified durable HTTP composition from a source-free archive without
building new application code.

The missing slice is a bounded, explicitly requested local session. It must not
turn listener lifetime into a registration acknowledgement, accept runtime
authority from the request, weaken the existing HTTP limits, or create a
long-running default service.

## Decision

Add the separate Java21 distribution `agentcore-registration-http-cli` with two
commands: `check-session` and `serve-bounded`. Its Java8-bytecode bootstrap
rejects unsupported runtimes before loading Java21 implementation classes. The
launcher and its single worker independently verify that stdin is a real
anonymous pipe. The parent clears the worker environment and owns its lifecycle.

Input is exactly two big-endian length-prefixed frames followed by EOF:

1. the unchanged closed operator configuration from
   `agentcore-registration-cli`; and
2. a strict session object containing only `schemaVersion:1.0`, profile
   `tracepermit-local-synthetic-registration-http-session-v1`, an explicit
   `requestedPort`, and explicit `sessionMillis`.

All framing, schemas and EOF validate before datasource construction, network
access or listener creation. `check-session` stops there and is offline.
`serve-bounded` accepts port zero or `1024..65535`, binds only the existing
numeric IPv4 `127.0.0.1` server, and accepts a duration of `1000..30000`
milliseconds. It constructs only `DurableAgentCoreRegistrationHttpServer` with
the existing `AgentCoreRegistrationHttpServer.Limits.defaults()`; request data
cannot select the address, worker count, pending count or transport deadlines.

Reuse crosses a narrow `InstalledHttpRuntime` facade in the existing CLI module.
That facade owns the unchanged operator parser, concrete verifier, loopback JDBC
datasources and durable HTTP factory. Its validated plan is one-shot and exposes
only session duration, the bound port and close. The only visibility change is
package access for the existing bounded frame reader. Existing CLI commands,
receipts and supervisor remain unchanged.

## Lifecycle receipts and containment

The worker emits at most two strict JSON receipts, each inside a four-byte
length-prefixed frame no larger than 1024 bytes. Every receipt has exactly
`schemaVersion`, `profile`, `phase`, `outcome`, nullable `host`/`port`,
`executionEnabled:false` and `rollbackConfirmed:false`.

`serve-bounded` emits `READY/LISTENING` only after the real listener binds, then
`FINAL/SESSION_ENDED` only after the requested duration and server close.
Failures use a fixed final outcome. `check-session` emits only
`FINAL/CONFIG_VALID`. Exit zero is limited to `CONFIG_VALID` and
`SESSION_ENDED`; usage exits64 and every other result exits1.

The parent requires ready within20 seconds, total completion within60 seconds,
each public receipt write within1 second, and owned cleanup within a separate5
second reserve. Cleanup first allows1.5 seconds for graceful termination and
then forces the owned process if necessary. Stdout must contain exactly the
expected one or two frames and stderr must be empty. Partial, extra, malformed,
late or exit-mismatched worker output becomes fixed failure. No worker is reused
and there is no retry or substitute implementation.

## Security and uncertainty boundary

The HTTP route and its registration receipt remain unchanged. Durable replay
still commits before the separate operation-registration transaction. A client
disconnect, session expiry, worker termination or final lifecycle receipt does
not prove registration rollback. `SESSION_ENDED` says only that the owned
listener closed after its bounded window; it is not a drain certificate and does
not change the result of an already dispatched JDBC operation.

This distribution adds no live provider discovery, public edge, TLS terminator,
human/OBO exchange, approval, permit, admission, target credential or execution
path. It never initializes, migrates, repairs or adopts a database. The stores,
synthetic trust configuration and requested session remain operator-owned.

## Qualification and limitations

The [installed-session runbook](../runbooks/installed-durable-agentcore-http.md)
defines source-free archive, anonymous-pipe, supervisor, default4+8 transport
capacity, real forwarded-JDBC acknowledgement loss and four-process shared-store
checks on PostgreSQL16.4, MariaDB11.4.13 and MariaDB10.6.25. These are synthetic
local controls, not production deployment qualification.

Numeric loopback is not local-process isolation. Session shutdown is bounded by
the existing server close behavior and cannot recall a JDBC operation that has
already committed or ignores interruption. Database failover/restore fencing,
live identity-provider compatibility, revocation, public ingress, production
load and target execution remain unqualified.
