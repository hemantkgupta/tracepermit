# ADR 0057: Bound endpoint response completion and terminal observation

- Status: Accepted for the local simulator connector
- Date: 2026-10-18
- Requirements: FR-016/017; SEC-003/011/014; REL-001/002/005/009; OPS-004

## Context

The loopback connector previously requested an `InputStream` response, then
read up to the byte cap synchronously. Receiving headers completed that HTTP
response stage; the subsequent stream read could outlive the configured request
timeout. A synthetic loopback response took 2,061 ms with a 400 ms timeout.
The macOS terminal probe had the same ordering problem: it read the child pipe
before its timed `waitFor`. A byte cap is not a time bound.

## Decision

For each HTTP exchange, use one monotonic timeout budget from asynchronous send
through complete response-body receipt. `BoundedGatewayBodySubscriber` completes
only at EOF, cancels on overflow before copying an oversized chunk, and buffers
at most 256 KiB of payload. The caller waits only for the remaining budget and
rejects late completion. Timeout or interruption cancels both the subscription
and exchange; interruption retains the caller's interrupt flag. This introduces
no application-level retry, refresh, forwarding process or new executor pool.

Preserve numeric-loopback-only routing, `NO_PROXY`, no redirects, the 64 KiB
request cap, JSON/SSE handling, and existing safe public error codes. Timeout
remains `GATEWAY_UNAVAILABLE`; overflow remains `GATEWAY_RESPONSE_TOO_LARGE`.
Timeout configuration is constrained to 1 ms through 30 seconds. The ordinary
entry point retains its 2-second connection and 10-second exchange settings.

For the fixed macOS `/bin/ps` command, read its bounded pipe on a per-probe Java
21 virtual thread and share one two-second budget between output and process
exit after process creation. Timeout, read failure, oversized output or nonzero
exit fails with `ENDPOINT_OBSERVATION_UNAVAILABLE`, never invented interactivity.
Cleanup requests forced child termination and interrupts/cancels the reader.
No unbounded join or executor close is introduced. The non-macOS observer and
the actual client PID/command selection are unchanged.

## Qualification

- `LoopbackGatewayDeadlineTest` uses owned numeric-loopback sockets, finite
  fixture waits and joins. It covers no headers, headers without body, partial
  JSON, partial error/redirect bodies, caller interruption, safe error codes,
  one observed request and peer-observed connection closure.
- `BoundedGatewayBodySubscriberTest` checks EOF-only completion, exact-cap
  multi-chunk bodies, overflow without copying the offending chunk, duplicate
  or late subscriptions, early cancellation, empty bodies and transport failure.
- `TerminalProbeDeadlineTest` uses declared synthetic process/pipe adapters for
  blocked output, pending exit, interruption, read failure, output bounds and
  valid/unknown terminal classification. Existing adapter tests still exercise
  the fixed native process probe; synthetic tests are not Linux qualification
  or a new live Codex model rehearsal.
- Existing loopback proxy/redirect, request/proof binding, process stability and
  subprocess simulator allow/block tests remain required regression gates.

The sensitivity control removes the HTTP completion wait's time bound; the
stalled-body regression must fail rather than appear green because of fixture
cleanup. Restore the bound and rerun the final tests before committing.

## Limits

This is an I/O waiting budget, not a hard real-time guarantee or a new p95 SLO.
Source executable hashing, signing, serialization, JSON parsing, process
creation and scheduler/OS pauses are not all covered by that budget. Cancellation
and child termination are best-effort OS/JDK operations, not proof that every
platform has synchronously released every resource.

A timeout after sending a request is **not** a policy block, evidence of zero
target effects, or cancellation of a committed execution. Do not automatically
retry the tool or restore single-use authority. Reconcile an uncertain effect
through the appropriate gateway state/evidence and supported lookup path; the
connector does not add an automated reconciliation worker.

This does not deliver the source-free connector archive, change the macOS-first
evaluation scope, protect same-user key custody, enable remote destinations or
connect a real cloud target. See [ADR 0032](0032-direct-loopback-endpoint-transport.md).
