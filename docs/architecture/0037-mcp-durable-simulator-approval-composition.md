# ADR 0037: MCP and browser share durable simulator approvals

Status: Accepted for the local simulator profile

Requirements: FR-002/003/004/007/008/009/012/014; SEC-001/002/004/005/006/009/012;
REL-001/002/004/005; DATA-001.

## Context

The bearer approval API and optional browser inbox already authenticate callers,
enforce operator membership and roles, persist an immutable canonical action and
require independent review before a PostgreSQL single-use execution claim.
The separate hosted OAuth MCP demo does not share this durable approval workflow.
Coding-agent clients need a bounded request/status/resume protocol without
keeping a tool call open while a human reviews it.

## Decision

The existing numeric-loopback `SimulatorApprovalServer` additionally exposes a
stateless JSON-response MCP `POST /mcp`. It authenticates the same strict RS256
access-token profile and current enrollment **before reading the body**. Host,
query, body-size, duplicate-key, UTF-8, Origin, Cookie and sender-proof boundaries
remain enforced. Source-attestation headers are rejected, not silently trusted.
This is a distinct bearer-only local profile, not the source-attested CLI profile.

`ApprovalMcpAdapter` owns only protocol validation, the fixed three-tool catalog,
safe response/error framing and scope-filtered discovery. The server routes
request/resume into **the same methods** used by the bearer/browser approval API.
`HumanApprovalService` and `PostgresDurableEnforcementState` remain the sole
approval/permit/claim implementation. No table, migration or independent approval
store is introduced. The fixed local policy advances to version 2 to explicitly
review the known simulated mutation from `api` or `cli`; all other actions block.
Existing version-1 approvals fail their policy binding on resume after upgrade.

Tools:

- `tracepermit_request_simulated_change`: strict `sg-demo`, integer port 1–65535,
  one of three documentation CIDRs. REQUESTER + `approvals.request`. Returns a
  durable pending approval and `not_executed`, never an execution permit.
- `tracepermit_get_approval`: approval ID only, owned approval only.
  REQUESTER + `approvals.read`. Reads current durable status; no polling loop.
- `tracepermit_resume_approval`: approval ID plus request hash only.
  REQUESTER + `approvals.execute`. Loads the immutable action, not new arguments.

There is **no MCP approval/deny tool**, even for a combined-role member. A distinct
enrolled human reviews through the existing authenticated API/browser boundary.
Browser cookies are never MCP credentials. Current reviewer enrollment, exact
binding, expiry and current policy are rechecked before durable claim/dispatch.
MCP snapshots additionally bind resumption to the verified original client ID
and configured origin, on both MCP and HTTP execution routes. Credential renewal
for that principal/client is allowed; a credential fingerprint is evidence, not
the permanent identity key. Client metadata/actor assurance remains asserted:
there is no proof of a Codex executable, device, human trigger or provider plan.

## Failure semantics

The listener supports initialize, initialized notification (202), ping,
server discovery, tools/list and tools/call. Calls return JSON, not SSE, and use
no session ID. GET/DELETE, batches, cursors and unknown protocol versions/tools
are not supported. Authentication failures remain HTTP failures. Valid tool
calls with authorization/state failures return `isError=true` with a bounded
code, not an invented execution outcome. Tool annotations are hints only.

Approval consumption and execution claim commit before the simulator effect.
Completion commits separately: this is **at most one dispatch per approval**,
not a distributed exactly-once target guarantee. If completion is lost, durable
state can remain consumed/executing; status is not proof of success and a new
attempt must not be issued automatically. Resume replay cannot invoke again.
Repeated request calls can create distinct pending approvals; MCP request IDs do
not deduplicate business operations. Do not retry request or resume blindly.

## Qualification and limits

Codex is the first client candidate. Official Codex configuration supports HTTP
MCP bearer tokens via an environment-variable reference; do not save usable tokens
in config or launch arguments. The test-classpath-only qualification launcher
provides synthetic short-lived requester credentials in an owner-only temporary
file, an account-picker IdP and disposable PostgreSQL. It cannot be selected from
normal operator configuration. Browser review and native client qualification
are separate from protocol tests and must be recorded accurately.

No external IdP registration, public TLS/tunnel, production session/key custody,
shared abuse control, real target credential or host-wide anti-bypass is added.
The local HTTP browser profile must not be proxy/tunnel exposed. Same-UID host
compromise, leaked bearer credentials and approval flooding remain limitations.
See the [runbook](../runbooks/codex-durable-approvals.md).
