# TracePermit specifications

[ADR0094](../architecture/0094-offline-eks-sandbox-bundle.md) adds an offline
EKS synthetic rehearsal renderer/checker and a separately invoked bounded TCP
probe. Its [runbook](../runbooks/eks-synthetic-sandbox-bundle.md) distinguishes
suspended restricted manifests, operator-preinitialized loopback storage and
manual qualification from actual cluster isolation. Validation is pending;
no deployment or provider access is authorized.

These documents describe the Java/React POC and distinguish implemented
controls from target enterprise requirements. Source and tests determine what
actually runs; a target requirement is not an implementation claim.

[ADR0093](../architecture/0093-source-free-operation-lifecycle.md) adds a
separate source-free Java21 command that composes the closed V7 operation
lifecycle with real offline OIDC verification, independent human review,
pinned Ed25519 permit signing and only a deterministic synthetic target. Its
[runbook](../runbooks/source-free-operation-lifecycle.md) defines exact private
frames, fixed receipts, three-engine evidence and remaining gates. Success is
still `executionEnabled:false`; uncertainty and reconciliation never authorize
automatic target retry.

[ADR0090](../architecture/0090-closed-durable-synthetic-delivery.md) reserves
Track A's opt-in V7 closed synthetic delivery and read-only reconciliation.
Its [runbook](../runbooks/closed-synthetic-operation-delivery.md) separates capped
writer-time leases, acknowledged start, observations and no-resend recovery.
Implementation/qualification are in progress; no endpoint/default/live target
is enabled. The [learning note](../learning/lease-is-not-an-exactly-once-target-fence.md)
explains why a lease cannot atomically fence a paused target call.
[ADR0092](../architecture/0092-installed-durable-agentcore-http-session.md)
adds a separate source-free Java21 command that starts the existing closed
durable registration HTTP composition only for an explicit bounded session. Its
[runbook](../runbooks/installed-durable-agentcore-http.md) defines the exact
two-frame anonymous-pipe input, lifecycle-only receipts and installed
three-engine evidence. Session end is not registration rollback, approval,
admission or execution authority.

[ADR0091](../architecture/0091-durable-agentcore-registration-http-composition.md)
adds the closed trusted-operator composition of the existing numeric-loopback
HTTP server and existing durable AgentCore registration factory. Its
[runbook](../runbooks/durable-agentcore-registration-http.md) covers actual
HTTP/JWKS/signed-token/shared-replay/durable-registration evidence across the
three exact database engines. It remains execution-disabled, synthetic and
unwired; no live provider, human delegation, approval, admission or target is
enabled.

[ADR0089](../architecture/0089-source-free-synthetic-registration-cli.md) adds
the independent source-free local synthetic registration command. Its
[runbook](../runbooks/source-free-synthetic-registration-cli.md) defines closed
private framing, offline public trust, loopback-only stores and qualification
status. All receipts remain execution-disabled; no live provider or target
authority is added.

[ADR0067](../architecture/0067-bounded-okta-oidc-qualification.md) and the
[Okta operator runbook](../runbooks/okta-oidc-qualification.md) define the
schema1.2 public/native custom-server foundation. Generated-signature tests
qualify the bounded implementation, not a live tenant or EKS deployment.

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

[ADR0085](../architecture/0085-closed-durable-operation-review.md) adds the opt-in
V4 closed JDBC evaluation/review/pending-withdrawal transaction and a versioned
binding/journal reader. Its [qualification](../runbooks/durable-operation-review.md)
is separate from earlier green checkpoints. No endpoint, permit, admission,
attempt/outbox, provider or target is enabled.

[ADR0087](../architecture/0087-fully-bound-operation-permit-and-admission.md)
records the approved strict identity/signature/lifetime choices and opt-in
closed JDBC permit-issuance slice. Its [issuance qualification](../runbooks/closed-operation-permit-issuance.md)
is separate from V4 review: actual approved state is loaded and the read
connection closes before signing. The separate opt-in V6
[closed admission library](../runbooks/closed-operation-admission.md) adds one
atomic consumption/initial-attempt/fact/head/outbox transaction and its closed
reader, with scoped three-engine local qualification. Default V2/V1–V4 SQL
are unchanged. No sender, endpoint or target is enabled.

[ADR0084](../architecture/0084-durable-review-membership-and-lifetime.md)
records the approved next design: exact independent reviewer-group enrollment,
explicit approval lifetime capped at five minutes with no default, and separate
fresh evaluation authority, all requiring current profile/generation pins.
Those input checks alone are not a durable reviewer transaction or live IdP integration.

[ADR0083](../architecture/0083-writer-locked-operation-authority-control.md)
adds opt-in V3 current profile/recovery controls, explicit inactive staging and
new-generation replacement, restricted operator/runtime grants and a guard that
retains a caller-owned writer lock. Default installation remains V2; V1/V2 bytes
are unchanged. Required physical-structure checks supplement migration history;
filtered MariaDB runtime metadata is not an absence certificate or a reason to
expand grants. Full operator deployment visibility remains a future gate.
This is not the complete approval/admission transaction or an
installed service; see [qualification and limits](../runbooks/operation-authority-control.md).

[ADR0077](../architecture/0077-closed-operation-authority-contracts.md) adds only
closed, unwired operation-review inputs and a copied/pinned static operator
policy profile. It does not implement durable approval, admission or execution;
the [learning note](../learning/closed-review-is-not-admission.md) separates these
remaining authority boundaries. Its first contract checkpoint retains V2 storage;
the later opt-in V3 control prerequisite does not complete those boundaries.

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

[ADR0080](../architecture/0080-durable-workload-identity-replay.md) adds the
standalone durable `WorkloadIdentityReplayStore` library across PostgreSQL 16.4
and MariaDB 11.4.13/10.6.25. Length-delimited domain-separated compound hashes,
bounded fingerprint-only persistence, single-winner unique insertion, fail-closed
saturation and commit/cleanup acknowledgement are qualified. The module is not
wired into ingress or application authority; see the
[runbook](../runbooks/workload-identity-replay-store.md) and
[learning note](../learning/workload-replay-is-not-admission.md).

[ADR0078](../architecture/0078-agentcore-execution-disabled-registration.md) adds
an unwired, execution-disabled enrolled-workload registration adapter. Its strict
body derives no authority from client flags and rejects human/OBO token pairs.
Synthetic token/recording-port tests do not qualify live AgentCore/Okta token
compatibility, a provider delegation relationship or composed durable ingress;
see the [ingress runbook](../runbooks/agentcore-trusted-ingress.md).

[ADR0086](../architecture/0086-durable-agentcore-registration-composition.md) and
the [durable registration runbook](../runbooks/agentcore-durable-registration-qualification.md)
describe the execution-disabled composition of the concrete verifier/ingress,
shared replay and durable registration stores. Replay commits separately before
registration; neither successful registration nor idempotent recovery grants
human delegation, admission or execution authority.

[ADR0076](../architecture/0076-github-installation-credential-provider.md) adds an
unwired Java GitHub App installation credential-provider foundation behind the
existing port. Explicit operator identity/private-key inputs, restricted exchange,
strict response assurance, expiry, transport and redaction controls are qualified
only synthetically. The [runbook](../runbooks/github-app-installation-credentials.md)
and [learning note](../learning/github-installation-credential-boundary.md) record
trust gaps and stop conditions. No live installation or target/runtime wiring.

Wave 2 review adds strict assurance/cleanup/transport regressions and a test-only
concrete-provider/adapter composition fixture under ADR0076. Existing permit,
allowlist, target preflight and unknown-outcome semantics are exercised without
composing a shipped launcher or durable operation executor.

[ADR0081](../architecture/0081-github-pilot-operator-diagnostics.md) adds a separate
Java21 operator diagnostic bundle: strict private offline input validation and
explicit existing read-only target preflight. The
[setup runbook](../runbooks/pilot-operator-distribution.md) records source-free
packaging and synthetic/POSIX qualification limits. No admission, approval,
operation permit, execution or reconciliation authority is introduced.

[ADR0082](../architecture/0082-loopback-agentcore-registration-http.md) adds an
optional numeric-loopback HTTP registration library around the unchanged
execution-disabled ingress, with explicit NIO deadlines and bounded admission.
It has no executable/default wiring, public edge, OBO or execution capability.
The [runbook](../runbooks/agentcore-loopback-registration-http.md) separates
synthetic signed HTTP/JDBC evidence from future deployment and authority gates.
