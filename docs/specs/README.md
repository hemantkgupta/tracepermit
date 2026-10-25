# TracePermit specifications

These documents describe the Java/React POC and distinguish implemented
controls from target enterprise requirements. Source and tests determine what
actually runs; a target requirement is not an implementation claim.

| Document                                               | Purpose                                         |
| ------------------------------------------------------ | ----------------------------------------------- |
| [Governance](00-specification-governance.md)           | Requirement language and change rules           |
| [Product requirements](01-product-requirements.md)     | Users, scope, and intended outcomes             |
| [System requirements](02-system-requirements.md)       | Functional, security, and operational IDs       |
| [Current-state baseline](03-current-state-baseline.md) | Implemented, qualified, and future capabilities |
| [High-level design](04-high-level-design.md)           | Components and trust boundaries                 |
| [Domain model](05-domain-and-data-model.md)            | Actions, permits, approvals, and evidence       |
| [Java low-level design](07-low-level-design-java.md)   | Module ownership and enforcement flow           |
| [Interface contracts](08-interface-contracts.md)       | MCP, HTTP, schema, and error boundaries         |
| [Sequence flows](09-sequence-flows.md)                 | Allow, deny, approval, replay, and recovery     |
| [Threat model](10-security-threat-model.md)            | Threats, controls, and residual risks           |
| [Quality and acceptance](11-quality-and-acceptance.md) | Tests and release gates                         |
| [Traceability matrix](12-traceability-matrix.md)       | Requirement-to-code/test status                 |

For a security-boundary change, read the affected specification and
[architecture decision](../architecture/), cite the requirement IDs, update
the traceability matrix, and add positive and negative tests. The
[simulator scenarios](../demo-scenarios/README.md) show qualified POC paths.
No demo path may contact a real destructive target.

[ADR0075](../architecture/0075-github-business-operation-binding.md) adds a pure
operation/target hash join with an explicit strict GitHub profile and independent
golden recipe. Separately supplied scope/policy/generation/target expectations
are checked without normalizing a stored action. Returned data is not approval,
a permit, an execution claim or a composed durable executor; see the
[binding runbook](../runbooks/github-business-operation-binding.md).

[ADR0073](../architecture/0073-github-pilot-target-preflight.md) and the
[target-preflight runbook](../runbooks/github-pilot-target-preflight.md) qualify
the GitHub adapter's explicit operator binding, fixed production transport,
GET preflight and conservative lookup with synthetic servers only. They do not
compose the business-operation executor or authorize a live target.

Proposed product work is separate from the current implementation index. The
[pending-request cancellation contract](../design/pending-approval-cancellation.md)
and [ADR 0058](../architecture/0058-pending-approval-cancellation-contract.md)
define the proposed withdrawal feature. [ADR 0059](../architecture/0059-explicit-v9-v10-simulator-upgrade.md)
implements its storage/consumer compatibility and explicit local upgrade
prerequisite. [ADR 0060](../architecture/0060-authenticated-pending-request-withdrawal.md)
adds the Java pending-only withdrawal API; [ADR 0061](../architecture/0061-requester-withdrawal-inbox.md)
adds explicit owner withdrawal in the local React inbox. Source-free installed
withdrawal qualification remains separate.

[ADR 0062](../architecture/0062-loopback-container-install-profile.md) and the
[container runbook](../runbooks/container-install-profile.md) define the Linux
numeric-loopback container install foundation. It is partial TP-MVP-05 evidence,
not a public-edge, external-IdP or production-readiness claim.

[ADR0063](../architecture/0063-explicit-public-browser-code-pkce.md) adds the
explicit native/public browser candidate. Synthetic tests provide the mutation
matrix; the [2026-10-22 live record](../demo-scenarios/evidence/installed-auth0-approval-2026-10-22.md)
qualifies one named Auth0/native/same-Mac composition only.

[ADR0064](../architecture/0064-unsigned-approval-review-export.md) adds the
authenticated per-request metadata export. It is unsigned and intentionally
incomplete. The console download is exercised against the real Java route by a
required browser gate, which also passes the saved file to the installed Java
offline checker. Live-provider qualification retains a separate gate in the
[delivery plan](../planning/audit-ready-approval-workspace.md).

[ADR 0066](../architecture/0066-mariadb-permit-consumption-foundation.md) adds
an unwired MariaDB standalone replay-store foundation for exact 10.6.25 and
11.4.13 versions. It does not claim approval/evidence parity, RDS qualification
or production runtime support.

[ADR0068](../architecture/0068-versioned-business-operation-contract.md) adds
the persistence-neutral business-operation schema, frozen synthetic draft-PR
vector and Java lifecycle/binding validator. It does not implement durable
request deduplication, an operations API, a database adapter, AgentCore ingress
or a real target.

[ADR0070](../architecture/0070-durable-business-operation-registration.md) adds
the isolated registration port/library and a shared MariaDB/PostgreSQL contract
suite. Scoped-key uniqueness, changed-content conflict, snapshot/initial-fact
atomicity and commit uncertainty are implemented for registration. The module
is not wired into the simulator, an operations API or a target executor.

[ADR0071](../architecture/0071-uncommitted-operation-transition-proposals.md) adds
a pure, scope/state-bound transition proposal helper. A candidate label is not
authority or a committed event; durable lifecycle service composition remains
pending.

[ADR0072](../architecture/0072-durable-pending-operation-withdrawal.md) adds the
separate durable current head/withdrawal journal and an explicit V1→V2 upgrade.
Pending-only owner withdrawal uses locked whole-state/database-time authority
checks. Initial registration/retry semantics stay unchanged. There is no generic
proposed-state write, operations API, approval/permit or target wiring.
Stored operation JSON and populated upgrade inputs reject duplicate keys and
trailing data without changing global JSON/canonical semantics.

[ADR0074](../architecture/0074-reserved-lane-bounded-dispatch.md) replaces
queue-first simulator listener dispatch with explicit lane reservations and the
same bounded pending FIFO. It retains the eight-lane/sixteen-pending default,
pre-handler refusal and bounded close; see the
[handoff explanation](../learning/bounded-dispatch-handoff.md) and
[qualification record](../runbooks/latency-qualification-transport-diagnostics.md).
This does not qualify production load or a public abuse-control boundary.

[ADR0078](../architecture/0078-agentcore-execution-disabled-registration.md) adds
an unwired, execution-disabled enrolled-workload registration adapter. Its strict
body derives no authority from client flags and rejects human/OBO token pairs.
Synthetic token/recording-port tests do not qualify live AgentCore/Okta token
compatibility, a provider delegation relationship or composed durable ingress;
see the [ingress runbook](../runbooks/agentcore-trusted-ingress.md).

[ADR0082](../architecture/0082-loopback-agentcore-registration-http.md) adds an
optional numeric-loopback HTTP registration library around the unchanged
execution-disabled ingress, with explicit NIO deadlines and bounded admission.
It has no executable/default wiring, public edge, OBO or execution capability.
The [runbook](../runbooks/agentcore-loopback-registration-http.md) separates
synthetic signed HTTP/JDBC evidence from future deployment and authority gates.
