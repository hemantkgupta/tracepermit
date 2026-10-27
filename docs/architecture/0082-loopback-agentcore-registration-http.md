# ADR0082: Numeric-loopback HTTP registration transport

Status: implemented candidate; exact-head qualification receipts belong to
[PR115](https://github.com/hemantkgupta/tracepermit/pull/115). No default wiring or
public/provider deployment.

Requirements: FR-002/003/010/012; SEC-001/002/003/004/009/012;
REL-001/002/003/005/009; OPS-002/004; DATA-001; PORT-004.
No whole-requirement status promotion.

## Scope and provenance

Cloud2 owns `codex/agentcore-loopback-registration-http` until its final handoff
releases it. Base is read-only PR111 at
`317a1be331b46292699a1e5bbf2e824537a27eb8`. PR109/111, Mac authority/ADR0077,
Cloud1 pilot-operator and Gemini identity replay/ADR0080 remain separate lanes.
Only a new module, one additive settings entry and scoped documentation change.
Existing ingress, verifier, repository, migration, canonical, CI and launcher
behavior stays unchanged.

The server takes the actual final `AgentCoreRegistrationIngress` supplied by
trusted operator construction. That ingress still owns strict body parsing,
verification, exact enrollment, scope/target/profile construction, replay and
`BusinessOperationRepository` registration. No request can supply an ingress,
verifier, enrollment, repository, replay implementation, policy or generation.
No approval, permit, admission, execution, target credential or dispatch port is
accepted. Constructors and transport admission grant none of those authorities.

## Closed HTTP contract

`AgentCoreRegistrationHttpServer` is an explicitly constructed library with no
`main`, service loader, default launcher or existing-service route. It opens an
IPv4 NIO listener at hardcoded numeric `127.0.0.1`; only its port is configurable
(and may be0). DNS names, wildcard/public bind and caller-address overrides do
not exist. Numeric loopback reduces exposure, but is not local-process isolation.

Only HTTP1.1 `POST /v1/agentcore/registrations` is supported. Exact target and Host
`127.0.0.1:<actual-port>` are required; query, fragment, encoded/absolute/alternate
paths and other methods fail closed. Each connection carries one framed request
and closes after its response. There is no keepalive, pipelined second dispatch,
HTTP2 upgrade, chunked transfer, Expect/100-continue, redirect or CORS flow.

Headers are ASCII CRLF, at most24576 bytes and16 fields, request line at most512
characters, with strict token names and no folding/control/ambiguous surrounding
whitespace. Duplicate names are rejected case-insensitively, including identical
Authorization, Host and Content-Length repetitions. Exactly one nonempty
Authorization header is required; comma-combined values are refused. Cookie,
Forwarded, proxy/client-certificate/identity aliases, Origin and unknown headers
cannot supply authority and are rejected. Unsupported content encoding is
refused. Only `application/json` and `application/json; charset=utf-8` are accepted.
Content-Length is mandatory, canonical nonnegative decimal, at most32768 bytes;
there is no EOF or transfer-encoding fallback. Framing/declared size is checked
before body allocation; partial body reads never exceed that allocation. Body
UTF-8, duplicate JSON, trailing values and schema validation remain the ingress's
responsibility.

Human-Authorization and the explicitly listed human/delegation transport aliases
are passed only as the ingress's second authorization argument, which always
returns `AGENTCORE_DELEGATION_CONTRACT_REQUIRED`, even for two valid independent
tokens. They are never interpreted as OBO. Unsupported authority fields inside
JSON retain the ingress's existing `AGENTCORE_REQUEST_INVALID` behavior.
Certificate-bound access tokens remain rejected by the ingress because this
plain loopback transport supplies no verified sender proof.

## Bounds, ownership and cancellation

Java NIO nonblocking reads/writes and an owned selector loop implement deadlines;
no JDK HttpServer timeout setting is assumed. Default absolute monotonic limits
are2s for headers,3s for body receipt and5s from accept through response. The
selector polls at most20ms between bounded batches. Trickling bytes cannot reset
a phase deadline. Queueing, authentication/key resolution, repository work and
response writing remain inside the original overall transport deadline. Deadline
expiry closes transport and requests interruption; it does not try to write an
unbounded timeout response to a slow peer. Transport loss is never proof that a
dispatched registration did not commit.

Default capacity is4 fixed daemon workers and8 pending jobs. A semaphore bounds
all accepted parsing/queued/running/responding reservations to their sum12;
completion storage has the same finite bound. The JDK fixed executor can refuse
early during worker wakeup; no full-utilization or latency qualification is
claimed. Authentication/registration never runs on the selector or acceptor.
Only a finite accept batch runs per selector iteration so a hot listener cannot
starve expiry/completion checks. Capacity refusal does not read, authenticate or
register the overflow request. It writes a small best-effort fixed503 using a
nonblocking channel and closes; a reset/EOF instead of a complete error response
is a supported pre-parse refusal outcome, not an assertion of success.

An accepted channel remains provisionally owned until it is tracked for shutdown.
Nonblocking/socket-option setup closes the channel on checked, unchecked or error
failure. The accept boundary also closes every untracked channel and returns any
acquired reservation on exceptional exits before tracking; listener shutdown alone
cannot reclaim an accepted socket that was never tracked. The package-private
setup helper adds no operator/request configuration or public trust seam.

A network deadline removes pending work where the executor confirms removal;
otherwise it marks cancellation and interrupts its worker. Work checks deadline/
cancellation before invoking ingress. A running registration keeps its reservation
until its actual return/completion is consumed. Future cancellation is not used
as proof that an interruption-ignoring JDBC/identity call stopped. Timed-out work
cannot create unbounded replacement workers or queue entries.

Trusted operator limit construction allows workers1..16, pending1..64, phase and
overall bounds50ms..30s and close50ms..2s, with phases no longer than overall.
None are request-configurable. Close stops the listener, closes accepted channels,
refuses/cancels pending jobs, interrupts workers and waits only within its budget,
preserving caller interruption. Owned threads are daemon threads. Uncooperative
trusted calls may finish later with metadata; close does not report rollback,
drain completion, execution cancellation or termination of such calls. Their
capacity remains held until actual completion, and the closed server accepts no
replacement work. Required JDBC/key-resolution deployment timeouts remain an
operator gate; the transport does not invent an authority/cancellation port.

## Responses and uncertainty

A new registration returns201; an existing registration returns200. Both expose
only `created`, repository-returned UUID `operationId` and literal
`executionEnabled:false`. Errors contain one allowlisted fixed `error` code.
Responses close the connection, use JSON and `Cache-Control: no-store`. No request,
header, authorization value, URL, body, fingerprint, operation snapshot or raw
exception is logged/echoed. Request/context/job string renderings are redacted.
No server-side retry, redirect, automatic new key/token or target call exists.

Ingress request/delegation errors map to400, identity to401, replay/registration
rejection or scoped-key conflict to409, and storage unavailable/commit unknown
to503. Unsupported route/method/framing/media/length have fixed transport codes.
Unexpected trusted-port failures collapse to fixed503 without throwable logging.
Unknown commit acknowledgement remains `AGENTCORE_REGISTRATION_UNKNOWN`; it is
never translated into a false success/absence result. An explicit fresh-token
same-key call can recover only registration metadata. The operation remains
initial evaluating/not_started/pending, not approved, admitted or executable.

## Qualification and remaining gates

[The runbook](../runbooks/agentcore-loopback-registration-http.md) records focused,
real HTTP/JWKS/JDBC, guard-removal, serialized full and exact-head hosted evidence.
Memory repository unit fixtures qualify transport controls only; three-engine
cases use real `JdbcBusinessOperationRepository`, fixture-admin installation,
restricted runtime grants and real forwarded-JDBC write/commit/close fault
injection. No target/credential/permit/attempt port is present. Tests preserve
actual HTTP socket behavior, including overload resets, partial bodies and
uncooperative-call uncertainty.

Synthetic local RSA/JWKS are not AWS/Okta interoperability. This does not add a
public AgentCore deployment, TLS/private edge, OBO relationship, shared durable
replay, role revocation, durable authority transaction, approval/admission,
execution, signed evidence, live provider/target qualification or production
readiness. Loopback HTTP bearer tokens still require local credential custody.
The supported composition remains execution-disabled. Rollback removes this
unused module and its settings inclusion; preserve stored metadata and scoped
keys for explicit operator review rather than deleting records or redispatching.
