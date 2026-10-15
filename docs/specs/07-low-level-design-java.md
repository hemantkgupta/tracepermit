# Java low-level design

**Status:** Current POC design with explicit production gaps
**Applies to:** Java 21 backend and simulator-only execution

## Optional local browser approval adapter

`ApprovalBrowserAdapter` owns OIDC code exchange, one-use browser-bound login
state, bounded in-memory sessions/CSRF, strict same-origin routing and built
React assets. `BrowserIdTokenVerifier` checks a separate RS256 ID-token profile
and matches nonce/subject/client to the independently verified API token.
`SimulatorApprovalServer` keeps bearer and cookie ingress separate and shares
the same role/scope/owner, exact snapshot and durable claim path after authentication.
`SimulatorApprovalConfiguration` opt-in browser fields are private operator
inputs; `SimulatorApprovalMain` uses DNS-pinned HTTPS token POST and never a
development issuer. The synthetic browser qualifier lives only in test sources.
No database migration or real target is introduced. See
[ADR 0036](../architecture/0036-local-browser-approval-boundary.md).

## Module ownership

| Module                 | Responsibility                                                                |
| ---------------------- | ----------------------------------------------------------------------------- |
| `contracts`            | Canonical JSON, strict action and signed-claim validation                     |
| `identity`             | Development token, source proof, workload/delegation, OAuth/OIDC verification |
| `policy`               | Deterministic classifier, sequence signals, ordered policy evaluation         |
| `security`             | Request-bound permit issue, verification, and consumption                     |
| `execution`            | Attempts, idempotency, unknown outcomes, evidence chain                       |
| `application`          | Fail-closed orchestration and trusted reviewer workflow                       |
| `simulator`            | Bounded, stateful AWS/Jira fakes; no real target SDK                          |
| `adapters-http`        | Combined loopback MCP backend and read-only projections                       |
| `adapters-remote`      | Authenticated MCP edge and SSRF-safe JWKS HTTPS                               |
| `endpoint-connector`   | MCP stdio origin observation and loopback forwarding                          |
| `persistence-postgres` | Durable claims, approvals, evidence, outbox, reconciliation                   |
| `observability-otel`   | Bounded telemetry projection                                                  |
| `conformance`          | Checked-in schema, vector, and invariant tests                                |

Transport code stays in adapters. All origins converge on the canonical
action contract before classification or policy. Application and domain code
receive trusted identity context from adapters; they do not infer it from tool
arguments. The executor has no route to a real AWS or Jira service.

## Authorization and execution

1. Authenticate transport credentials and, where required, verify source or
   workload proof before protected dispatch. Host/Origin, size, replay, and
   scope failures stop before a target-capable application call.
2. Validate the exact registered tool and arguments, derive tenant, subject,
   actor, target, and action from trusted context, and compute a versioned
   canonical hash.
3. Classify intent and severity from the action and bounded server-owned
   sequence history. Evaluate ordered policy. Unknown tools, schemas,
   identities, policies, or state fail closed.
4. For sanitize, construct a new bounded action and re-evaluate it. For
   approval, require independently authenticated reviewer context and an
   exact request binding. A request-body approval flag is never authority.
5. On allow or valid approval, issue a short-lived request-bound permit.
   The executor checks signature, audience, hash, expiry, and single-use
   claim before invoking the simulator. A denial issues no permit.
6. Record only bounded, redacted evidence and expose read-only projections
   to the React console.

## State and transaction boundaries

The default loopback demo uses process-local stores. Those stores are not
safe for multi-replica or restart-persistent enforcement. The optional
PostgreSQL composition uses short transactions to atomically claim a permit
and idempotency identity, append an execution attempt and evidence/outbox
event, and commit **before** simulator dispatch. A second transaction records
the observed outcome. External calls never run inside the claim transaction.

An uncertain attempt enters a durable queue. A leased worker may query an
outcome through a read-only resolver, then append a terminal reconciliation
only after confirmed observation; it cannot reissue the target operation.
The evidence publisher likewise uses leased outbox rows and independently
verifies signed anchor receipts before storing immutable delivery evidence.

## Cryptographic and data boundaries

The separate loopback `SimulatorApprovalServer` derives reviewer authority
from verified access tokens plus operator-owned `ApprovalMemberships`.
`DurableApprovalActionState` extends approval persistence with an atomic exact
action snapshot and tenant-scoped reads. PostgreSQL V6 implements this port;
`HumanApprovalService.requestApprovalWithSnapshot` requires it. Execution uses
the original stored canonical action rather than a new client submission.
The existing console and demo launchers do not use this foundation yet.

`SimulatorApprovalConfiguration` validates private bounded POSIX files,
explicit single-client/tenant OIDC trust, independent requester/reviewer enrollment,
numeric loopback DB configuration and matching Ed25519 keys. The separate
`SimulatorApprovalMain` runs migrations/schema verification before binding the
loopback approval service with a fixed default-block/explicit-approval policy.
Offline check mode performs no DB, DNS, IdP or listener operation. See
[ADR 0035](../architecture/0035-local-approval-operator-bootstrap.md).

Signed artifacts use named canonicalization and algorithm profiles with
allowlisted keys. An endpoint source proof binds the exact MCP method/params
and the authenticated connector credential fingerprint. Delegation grants
must narrow parent authority across scope, resource, audience, and lifetime.
Source evidence proves origin facts, not permission or safety. Evidence stores
fingerprints and bounded projections, never bearer tokens or private keys.

Development signing keys and replay stores are local POC mechanisms. Managed
endpoint enrollment, production permit key custody, independent target
credentials and egress control, shared edge state, reviewer authentication,
and a production bootstrap remain outside the implemented runtime. See the
[current-state baseline](03-current-state-baseline.md) and [threat
model](10-security-threat-model.md).
