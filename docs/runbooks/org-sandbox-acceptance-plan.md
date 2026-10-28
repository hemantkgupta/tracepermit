# Organization sandbox acceptance plan

Status: **proposed acceptance contract; no deployment or real-target authorization**.
Prepared 2026-10-27 and updated 2026-10-28 against accepted main
`dfb7b3b11ed7af3b4d0230fae9adf6e514289ba5`. This planning refresh records PR133's
verified-subject independence repair and PR134's retirement of MariaDB10.x
integration qualification; it is not fresh deployment or final-composition
qualification. PostgreSQL16.4 and MariaDB11.4.13 remain the active engines.

This plan defines the evidence needed to move the installed synthetic pilot into
one organization-owned non-production sandbox. It does not select an organization,
create infrastructure, install credentials, enable an adapter, or authorize an
external effect. Organization-specific values remain unselected until the named
owners approve the deployment manifest.

The human selected **EKS, Okta and GitHub draft PR** for the next pilot direction.
These platform/provider/operation choices are recorded; the exact cluster,
authorization server/app, GitHub installation/repository/refs, custody and routes
still need the deployment-specific contract and approval below.

The controlling design is [ADR0056](../architecture/0056-enterprise-credentials-and-egress-boundary.md).
Its proposed isolation, credential custody and egress controls are separate from
the existing Java authorization code. The [current capability baseline](../specs/03-current-state-baseline.md)
and [real-target release gates](../specs/10-security-threat-model.md#7-real-target-release-gates)
remain binding. This plan changes no requirement status.

## 1. Current evidence and open gates

| Area                                                                       | Actual evidence                                                                                                                                    | Still required                                                                          |
| -------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------- |
| Registration, review, permit/admission and synthetic delivery              | Accepted library slices through PR128; registration remains execution-disabled                                                                     | A reviewed installed composition; acceptance in the selected deployment                 |
| Permanent component lifecycle contract                                     | Mac Air reports 15/15 on PostgreSQL16.4, MariaDB11.4.13 and MariaDB10.6.25 after refreshing accepted main                                          | Publication and final-composition broad qualification; this is not installed E evidence |
| Track E installed lifecycle operator                                       | PR130 is merged at accepted main `35a791ffeba1a6b0b10e3e072e97f1085d80eed3`; its retained qualification evidence and exceptions remain controlling | Organization-specific composition and fresh exact-candidate qualification               |
| Reliability                                                                | [Issue126](https://github.com/hemantkgupta/tracepermit/issues/126) preserves failures; local process-harness repairs are a separate workstream     | Passing unchanged tests and complete aggregate coverage on the release candidate        |
| Org identity, protected credentials, enforced routing and audit deployment | Deployment requirements/design only                                                                                                                | The exact organization-owned composition and negative-control evidence below            |
| Non-production provider action                                             | Existing narrow adapter foundations are synthetically qualified only                                                                               | Separate adapter/topology review and explicit live non-production approval              |

The [offline EKS bundle](eks-synthetic-sandbox-bundle.md) and
[ADR0097](../architecture/0097-offline-eks-sandbox-bundle.md) prepare suspended
synthetic workloads and explicit operator inputs only. Its initial human-run
feature results do not qualify the refreshed composition, image, private input
transport, PVC, CNI/RBAC/admission or cluster isolation. Those gates remain open.
The organization-profile installed operator is separate work and is not enabled
by this bundle or acceptance-plan refresh.

PR128's accepted qualification exception does not waive any sandbox gate or
future PR. Track E's three contract decisions are already approved; they do not
authorize an organization deployment or real provider credentials. No passing
component test, compilation, packaging result or merged PR is a sandbox readiness
certificate. The remaining failures and missing coverage are recorded in the
[merge receipt](https://github.com/hemantkgupta/tracepermit/issues/126#issuecomment-6445837272).

## 2. Minimum pilot scope

The recommended first deployment has one isolated agent environment, one tenant
and workspace, one explicit requester enrollment, an independent reviewer group,
one pinned authority profile, one durable writer topology, and one allowlisted
non-production operation. Start with the complete synthetic flow. A separately
approved real-target stage may follow; it is never enabled by changing a URL in
the synthetic profile.

The selected operation is the existing [GitHub draft-PR pilot](agentcore-github-pr-pilot.md).
No organization resource is selected or authorized by that operation choice.
Review exact immutable repository identity, refs, base and draft-only binding;
exclude merge, administration and unrelated repositories. The synthetic fixture
repository named in that guide must not be mistaken for the organization's
approved repository.

## 3. Deployment manifest decisions

Keep identifiers and public fingerprints in a restricted operator manifest.
Never collect secrets in this document, Git, Pages, issue comments or evidence.

| Decision                        | Required value / review                                                                                                                               | Accountable owner               | Current state                                                   |
| ------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------- | --------------------------------------------------------------- |
| Sandbox location and isolation  | EKS cluster/account/region/namespace/CNI and pod topology; separate agent and trusted executor privileges; administrator assumptions                  | Org platform/security owner     | EKS selected; exact deployment pending                          |
| Client and entry point          | Exact installed agent/client version and CLI/MCP/HTTP transport; same-host versus remote trust boundary, enrollment and failure behavior              | Org platform/identity owners    | Unselected                                                      |
| Identity provider               | Okta authorization-server issuer, audience, tenant, client/token profile and public-key/enrollment/revocation process                                 | Org identity owner              | Okta selected; exact tenant/apps pending                        |
| Actors                          | Requester enrollment, independent human reviewer roster/group and operator duties; exact scope/workspace                                              | Org security/application owners | Unselected                                                      |
| Authority                       | Reviewed policy/artifact/action/target/precondition bindings, profile fingerprint and recovery generation                                             | Policy owner                    | Unselected                                                      |
| Stores                          | Exact supported engine/image, restricted identities, capacity, stopped-writer initialization/upgrade/restore procedure                                | Database owner                  | Unselected                                                      |
| Network                         | Allowed gateway/model-provider/dependency routes; explicit deny coverage and target-side identity enforcement                                         | Org network/platform owner      | Unselected                                                      |
| Key/provider credential custody | Protected source/signing custody and executor-only provider identity, mapping, rotation/revocation; no agent-readable reusable credentials            | Org security/target owner       | Unselected                                                      |
| Target                          | GitHub draft-PR-only operation; exact installation, immutable non-production repository identity, pre-existing head/base refs and minimum permissions | Target owner                    | GitHub draft PR selected; exact installation/repository pending |
| Evidence and operations         | Restricted audit destination/retention, monitoring, operator access, incident and emergency-stop owners                                               | Org operations/security owner   | Unselected                                                      |
| Rehearsal and acceptance        | Fixed workload, time budgets, artifact identity, evidence ledger and human go/no-go signers                                                           | Pilot owner                     | Unselected                                                      |

Provider-specific permissions and deployment controls must be reviewed for the
chosen target/platform before credentials or infrastructure are introduced.
EKS is the selected platform. This plan selects no CNI, broker or secret-store
product and grants no cluster/deployment access.

### EKS / Okta / GitHub contract to prepare next

Two non-overlapping follow-up lanes can prepare the concrete contract after the
current implementations are integrated:

- **Local Mac — EKS containment and qualification:** define the agent/executor
  pod and service-account split, protected configuration, database connectivity,
  enforced egress/metadata/host-socket denial, audit and emergency-stop drill.
  Pin the actual CNI and routing mechanism; a NetworkPolicy file alone is not
  proof of target bypass resistance. Manifests and synthetic tests precede any
  operator-approved cluster action.
- **Mac Air — Okta/GitHub integration contract:** map real verified Okta claims
  to exact requester/reviewer enrollment, profile-bound membership and revocation;
  join the accepted lifecycle to the existing fixed GitHub draft-PR adapter and
  read-only reconciliation. Review executor-only App installation credentials,
  exact repository/ref preflight, no automatic POST retry and uncertainty limits.
  The [library-only implementation candidate](../design/okta-github-org-sandbox-composition.md)
  now pins the Java authority/profile seams and stops before the unresolved
  custody, ingress, organization manifest and live-target boundaries.

The Track E per-delivery Ed25519 permit-signing key is distinct from the GitHub
App provider credential. The approved E pipe contract does not approve a new
persistent key store, broker, provider-key frame, public endpoint or arbitrary
credential mapping. Propose any such new custody/transport boundary explicitly
before implementation. The application must retain its closed synthetic profile
until a separately reviewed real non-production composition is approved.

This is the next-work plan, not dispatch of new workers or permission to access
EKS, Okta administration, a GitHub installation or any real credential/resource.

## 4. Ordered acceptance gates

Every gate records candidate commit/tree/ordered parents, artifact SHA256 and
inventory, runtime/image identity, exact inputs as public fingerprints, expected
and observed outcomes, target invocation counters, failures/skips/cache limits,
and cleanup/ownership. Keep original failed evidence when a repair is made.

### G0 — Scope and ownership freeze

- Approve the manifest above and a reviewed threat model for the actual topology.
- Verify one writer per implementation branch and explicit runtime/database
  ownership. No worker edits another owner's unpublished source.
- Record whether this stage is synthetic-only or the separately approved
  non-production adapter stage. Default is synthetic-only.
- Map the planned evidence to FR-001/008/009/012/015, SEC-001/002/003/006/007/008/012/019,
  REL-002/005/008 and OPS-005. Partial POC evidence does not promote a requirement
  to organization-wide acceptance.

### G1 — Reproducible installed release

- Complete Track E's approved tests with its preserved permanent lifecycle
  contract, then unchanged `pnpm check`, `pnpm format:check`, documentation/helper
  and 13-flow/drift checks on the exact final candidate.
- Establish complete Java aggregate coverage at least94% LINE and75% BRANCH.
  A skipped coverage job or a subset of execution data does not satisfy this gate.
- Every new case must execute; preserve any inherited opt-in skip explicitly.
  Resolve issue126 failures through evidence, without disabling tests, weakening
  assertions, extending deadlines or reducing workload/concurrency/grants/cleanup.
- Qualify a clean extracted source-free archive outside source checkouts using
  only the prepared supported Java runtime, including early unsupported-Java
  behavior, offline validation and independent installed processes.
- Verify the actual publication head, hosted checks and deployment artifact
  match. A same-tree merge does not convert failed checks to passing ones.

### G2 — Identity and exact authority

- Qualify the selected client/entry point. A remote hop additionally requires a
  reviewed server/TLS identity, client authentication, audience/sender constraint,
  enrollment, shared replay/abuse controls, revocation and outage behavior. Merely
  replacing a loopback URL or adding TLS is not this qualification. The installed
  operator CLI alone does not qualify an arbitrary agent/client integration.
- Verify real trusted tokens against the exact approved issuer/audience/tenant,
  enrollment and public pins. Reject wrong/expired tokens, client/tenant swaps,
  body-supplied identities, roles, membership or `verified` flags before effects.
- Exercise the approved offline reviewer roster, canonical membership-source
  fingerprint, role/scope/workspace/group binding and independent principal.
  Expiry is capped by the token and approved five-minute membership limit.
- Require fresh verified exact-owner authority for inspection/reconciliation too.
  Registration, an operator configuration, anonymous-pipe provenance and a causal
  parent are not execution authority.
- Validate the per-delivery signing key against its public pin inside the worker;
  do not persist/export raw permits or keys. Qualify key/profile/generation,
  policy/artifact/action/target/precondition substitutions and stale authority.
- Preserve exact private frames and EOF before effects, parser limits, receipt
  schemas, exit semantics and the existing parent/cleanup/permit/lease bounds.

### G3 — Credential isolation and bypass resistance

- Demonstrate the agent cannot read trusted-executor/source/provider secrets
  through inherited environment, files/mounts, process inspection, tools, logs,
  results, error responses or evidence. Same-user development key files and
  anonymous pipes alone are not a protected custody boundary.
- Prove a valid declared gateway path works, while direct target access fails
  across applicable IPv4/IPv6, DNS, redirects, proxy settings, metadata/ambient
  identity endpoints, loopback/host services and administrative sockets/mounts.
- Explicitly allow required model-provider/dependency traffic without creating a
  generic proxy or another route to the protected target.
- Test target-side identity restrictions as well as network controls; only the
  intended executor identity may operate the allowlisted resource. The agent
  must not be able to alter network/credential policy or impersonate the executor.
- Use positive controls and target counters. Missing credentials or a disconnected
  network do not prove the intended enforcement boundary works.

### G4 — Durable concurrency, crashes and uncertainty

- Use actual PostgreSQL16.4/MariaDB11.4.13 qualification for the supported
  composition under [ADR0094](../architecture/0094-mariadb-10-qualification-retirement.md),
  then pin the chosen deployment engine and restricted
  runtime/operator grants. Runtime performs no DDL or privilege expansion.
  Historical MariaDB10.x receipts do not qualify this composition; fresh 10.x
  integration is retired and requires a separately reviewed restoration.
- Exercise multi-process contention with the existing full contender/workload
  controls. Prove authority is shared and one-use, rather than process-local.
- Inject failures before claim acknowledgement, around acknowledged
  `dispatch_started` commit/cleanup, during target response loss and during
  completion acknowledgement. Keep success, rejection and unknown distinct.
- Crash/restart parent and worker, hold real commit replies, expire authority
  during waits, and exercise stale owner/fence/generation controls. Unknown
  completion never restores authority or permits resend/new attempt after start.
- Recovery/reconciliation uses read-only observations; absence is not proof of
  no late effect. A DB lease cannot atomically fence a paused worker just before
  an external call. Do not claim external exactly-once or failover qualification.
- Qualify stopped-writer upgrade/restore and fresh generation procedures; no
  implicit schema upgrade, partial-upgrade acceptance or old-snapshot authority
  revival. Keep V1–V7 migration/default bytes unchanged unless separately reviewed.

### G5 — Operations and evidence

- Review redaction across stdout/stderr, process arguments, logs, traces,
  database projections and exports. Store credential fingerprints only.
- Configure independent evidence custody/retention appropriate to the org;
  a local hash chain is not an independently retained or tamper-proof audit sink.
- Exercise admission shutdown, provider-identity revocation and route denial
  under an approved emergency-stop drill. Already-started/in-flight effects may
  remain unknown; stop is not rollback or proof of target absence.
- Verify bounded cleanup and resource ownership after failed starts, process I/O
  pressure, interruption and forced termination. Observe actual exit and port
  release; never kill unrelated workers or repair the host to hide a failure.
- Record the measured small-pilot workload and operational response/restore
  procedures. Do not infer production SLOs or broad enterprise readiness.

### G6 — User acceptance and go/no-go

Run one recorded scenario set: offline check, authenticated registration,
inspection, policy deny, pending approval with zero target effects, refusal of
self-review, valid independent review, explicit permitted synthetic delivery,
duplicate/spent-authority refusal, known rejection, unknown completion and
read-only reconciliation without resend. Include all G2/G3 negative controls.

The pilot owner, security owner, platform operator and target owner review the
complete evidence ledger and unresolved risks. All required gates must be met
for the exact candidate/topology. Any proposed exception requires a new explicit
human disposition with its exact limits; PR127/128 exceptions do not apply.
Real non-production target access requires its own final approval. Expansion to
another operation, target, tenant or topology repeats the affected gates.

Perform that rehearsal through the actual selected installed client and deployed
entry point with the approved identity profile. Record client/backend versions
and the exact route; a Java test harness or operator-only demonstration cannot
stand in for the organization's agent integration.

## 5. Implementation and review ownership

| Lane                                     | Owner                      | Exclusive scope / handoff                                                                              |
| ---------------------------------------- | -------------------------- | ------------------------------------------------------------------------------------------------------ |
| Track E and permanent lifecycle contract | Mac Air                    | New installed module, narrow approved reuse facades, four D contract files and E documentation         |
| Issue126 reliability                     | Local Mac                  | Existing process-test harnesses and new harness regressions; no production/CI/schema/authority changes |
| This acceptance contract                 | Local Mac                  | This new plan and its evidence/decision ledger; no deployment writes                                   |
| Shared specification/HANDOFF entries     | Coordinated at integration | Additive lane-specific entries with exact preservation of the other writer's sections                  |
| Org deployment and target activation     | Future named org owners    | Starts only after manifest/threat-model and deployment-specific approvals                              |

Cloud implementation lanes remain released. Draft PR review, exact-head CI and
resource release are required handoffs; a future PR is not automatically merged.

## 6. Acceptance record template

For each gate record: gate ID; candidate/artifact/image identity; environment and
scope fingerprint; owner; named case and positive/negative controls; expected
target counts; actual result and timing; fresh/cached/skipped limits; preserved
failure reference; cleanup observation; independent reviewer; disposition.
Record only public/approved fingerprints, never a token, key, credential, private
configuration value or raw worker command. Until the ledger is complete, label
this plan **unaccepted** and the deployment **not qualified**.
