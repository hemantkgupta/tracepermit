# P1 EKS rehearsal readiness packet

Status: **implemented documentation packet; new manual validation UNRUN**.
This packet prepares a human-operated synthetic campaign. It grants no cluster,
identity, provider, administration or target permission. It introduces no new
Java behavior, credential source, authenticated ingress or quarantine adapter.

The source dependency is draft [PR144](https://github.com/hemantkgupta/tracepermit/pull/144)
head `01c4cc625c8e7b8620d85d8a6ffd81c7eb2b1579`, tree
`14069d031fd96d868fdd9ac98c6a0e071461f883`, over accepted PR143 main
`7c33ea1ad1c5ef9dae07df235f28db14a1ea2c14`, tree
`236c8805c91c3ba686f98a83443da06cd54c6a0e`. That PR's human-run54-case
and repository results are dependency receipts, not validation of this later
documentation tree. PR144 remains subject to its own review and merge decision.
The integration owner must pin the actual accepted release before a cluster run.

## What is prepared

The private human-run packet stage gathers the exact source-free EKS archive,
its installed distribution, the [input sheet](eks-sandbox-inputs.template.md),
the unchanged acceptance plan/snapshot examples, a blank
[campaign record](../../deploy/sandbox/eks/readiness/campaign.example.json) and
the [28-control worksheet](../../deploy/sandbox/eks/readiness/control-ledger.csv).
All supplied templates remain unselected or NOT_RUN. File digests are local
bookkeeping, not evidence of execution, operator identity or approval. Cloud2's
strict artifact verifier remains a distinct lane; this packet stage is not a
replacement parser or attestor.

The packet carries a single fixed P1 command/plan per reviewed suspended Job.
Separate lifecycle steps and test scenarios need their own bound plans and
observations. Do not substitute an arbitrary executable, script or multi-command
entrypoint to make the organization launcher act as a service. Registration
remains execution-disabled. P1 uses synthetic identities/material and the
deterministic synthetic target; it does not qualify a live Okta tenant, an agent
entry point or GitHub dispatch.

## Stage ledger and release gates

| Stage                           | Required inputs and human action                                                                                                           | Completion evidence                                                                                                                                      | Block/stop condition                                                                                                                  |
| ------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------ | -------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------- |
| R0: ownership/release           | Named pilot/platform/security/database/identity/audit owners; accepted source and exact archive/image pins; workload and time window       | Private campaign record and release receipt, each reviewed by named owners                                                                               | Pending PR dependency, unknown owner, missing artifact identity, unqualified image or scope drift                                     |
| R1: local offline packet        | Human runs focused build/tests, packet assembly and repository checks separately                                                           | Exact source manifest, fresh XML, archive before/after identity, installed-file inventory, numeric exits, complete coverage                              | First failure; stale or source-mismatched prerequisite; no install/download/retry fallback                                            |
| R2: org-input review            | Complete the private input sheet and campaign record; review every selecting policy, admission/RBAC/identity route, storage and audit path | Private approved topology/scope references and explicitly bounded synthetic scenario set                                                                 | Placeholder/default used as an org selection; secret supplied in the sheet; unsupported topology                                      |
| R3: administration contract     | Org identifies the externally authenticated operator and approved exact-profile quarantine procedure                                       | Named operator/access/custody references; expected profile/generation; acknowledgement and independent readback procedure; uncertain-completion handling | No approved procedure, runtime DB identity reused as administrator, arbitrary SQL, unqualified new installed adapter                  |
| R4: explicit rehearsal decision | Pilot/platform/security owners review R0-R3 and authorize one exact synthetic cluster campaign                                             | Human disposition bound to source/artifacts/topology/inputs/workload/window                                                                              | This packet, a merge, a reported pass or a configuration file is treated as permission                                                |
| R5: actual containment/input    | Org operator executes its separately reviewed positive/negative controls on the exact topology                                             | The first24 worksheet controls, independently retained redacted evidence and actual observations                                                         | Provenance positive absent before private frames; forbidden access; audit/custody unknown; startup/outage escape; unexpected workload |
| R6: synthetic lifecycle/stop    | Explicit reviewed scenarios, then stop new submissions, quarantine, fresh denial, read-only reconciliation, cleanup and audit accounting   | Last4 worksheet controls plus exact started/terminal/unknown accounting and independent reviewer                                                         | Started/unknown work resent; uncertainty treated as absence; evidence deleted; cleanup mistaken for cancellation                      |
| R7: acceptance/disposition      | Named owners review actual evidence, failures, unknowns and residual risks                                                                 | Explicit human acceptance or rejection for the exact P1 scope only                                                                                       | Any required gate absent; proposed exception lacks its own exact human disposition                                                    |

R4-R7 are not executable stages in the local runner. No cluster action is
authorized by R0-R3 preparation. The actual selected EKS/CNI/platform versions,
native-sidecar/PVC support, routing and audit behavior require an org-owned
compatibility review. Existing configuration grammar is not that review.

## Evidence capture contract

Use the exact control IDs in the worksheet and existing acceptance catalog.
For every positive, denial and uncertainty case, record the expected result,
actual classified result, reviewed source/archive/image/input fingerprints,
case start/end and actual observation time, fixed workload, failure/skip/cache
limits, observer identity reference and owned-cleanup result. Bind its private
receipt digest to the corresponding snapshot record. One unexplained process
failure or an unreachable destination is not a demonstrated policy denial.

The worksheet deliberately contains no PASS_REPORTED rows. Only the org observer
may record its actual result; an independent reviewer must assess the underlying
receipt. A receipt hash does not establish truth or independent custody. Preserve
failed receipts before any repair; do not replace them with a later pass.

Keep the completed campaign, inputs, snapshot, manifests and logs outside Git in
owner-only locations. Capture bounded classifications, fingerprints and counts;
never bearer/key/password values, kubeconfig, full environment/process arguments,
raw provider payloads, credential endpoint response bodies or audit streams.
Agree the actual attach/stream/audit custody path before private frames. Local
Docker or anonymous-descriptor success does not qualify that EKS path.

Snapshot capturedAt and stop requestedAt must remain within the plan's explicit
60..300second age bound when assessed. Control observedAt values must be their
actual observations inside evidenceNotBefore..capturedAt and an at-most24hour
campaign. Do not regenerate timestamps to refresh old observations. A later
assessment may need a fresh observation; no automatic retest is supplied.

The closed snapshot is a normalized operator report, never a token, raw API
object, authenticated collector output or approval. The installed kit returns
only REVIEW_REQUIRED or BLOCKED. A complete reported worksheet still grants no
execution, retry, cluster acceptance or guarantee against a late effect.

## Stop decision that remains open

The human has selected the org-approved authenticated admin procedure route.
The [procedure contract](../design/eks-p1-quarantine-procedure-contract.md)
specifies the remaining actual procedure/custody/route/lifetime inputs and 20
planned cases. This route choice does not qualify an unidentified org tool,
add an installed command or approve an actual stop drill.

`OperationAuthorityAdministration.quarantine` accepts a separately privileged
DataSource and an exact expected profile. It is a library operation; it does not
authenticate an org operator or supply/install that connection. Runtime users
must retain restricted grants. Its acknowledged COMMIT plus cleanup must be
distinguished from commit uncertainty. No blind repeat or replacement authority
may be inferred from an uncertain administration outcome.

The org may propose an already approved externally authenticated administration
procedure. If it instead needs a new installed adapter or new custody mechanism,
return that exact trust contract for human review before implementation. This
packet selects neither option and contains no administration/SQL command.

Stop new submissions first; quarantine the exact profile/generation through the
approved procedure; observe fresh admission refusal; classify every started
attempt; reconcile read-only; observe owned process/database quiescence; retain
independent audit and human disposition. Account for at most one started P1
attempt as terminal or unknown. Killing a Pod, ending a session, observing an
expired lease or deleting a Job does not establish rollback or absence of a
late effect. Do not delete the PVC or evidence as a stop action. No automatic
restart/resend/reconstructed permit/lease is permitted after start/uncertainty.

## Ownership and future stages

Local Mac owns this readiness documentation, private manual runner and release
assembly. Air retains organization-module/image qualification. Cloud1 retains
its COMMIT-loss tests and engine-budget proposal. Cloud2 retains strict archive
compatibility. Shared integration is performed once after ownership release;
no unpublished source, private helper or validation result is transferred or
silently substituted. Worker status is established only by supplied receipts.

P1 is PostgreSQL16.4-only. MariaDB11.4 remains a separate supported qualification
obligation, with no reuse of a provisional PostgreSQL gate budget. MariaDB10.x
integration remains retired. No skipped case or selector silently narrows the
repository's active qualification policy.

P2 requires the exact agent/client and a separately reviewed authenticated
ingress/sender/replay/revocation contract. P3 requires live non-production Okta
and GitHub target/custody/route approval, pre-existing reviewed refs, draft-only
binding and consequence review. Filling those future input-sheet rows or
passing P1 does not approve P2/P3.

Requirements partially addressed by this operational preparation: FR-008/009/012/015;
SEC-003/006/007/008/012/019; REL-002/005/008; OPS-005. No whole requirement status
changes. The [manual runbook](../runbooks/eks-p1-rehearsal-readiness.md) defines
the local validation stages; all new stages are UNRUN.
