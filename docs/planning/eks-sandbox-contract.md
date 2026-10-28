# EKS organization sandbox contract

Status: **draft prepared; validation, deployment inputs and acceptance pending**.
Prepared 2026-10-27 for the human-selected EKS / Okta / GitHub draft-PR pilot.
This document authorizes planning only. No cluster, IdP, credential, provider or
target action has been performed or authorized by preparing it.

## Implementation update — 2026-10-28

The human authorized preparation of the local EKS bundle while retaining manual
validation and prohibiting deployment or Air messaging. Implementation now starts
from accepted PR130 merge `35a791ffeba1a6b0b10e3e072e97f1085d80eed3`, tree
`7613a913d86b339b7e57cb4f3785c799f1c6259e`, ordered parents
`f5da638759226360a6633eebe6cf37f3e18e40ac` and
`070cc28f3662b272d6747a6dfd407c3c78d7af3a`. Earlier source/PR receipts below
are historical, not current unmerged status.

[ADR0097](../architecture/0097-offline-eks-sandbox-bundle.md) and its
[runbook](../runbooks/eks-synthetic-sandbox-bundle.md) record the candidate
renderer/checker, suspended manifests, native loopback PostgreSQL sidecar,
separately invoked probe and validation-pending limits. The first closed profile
requires an operator-preinitialized PostgreSQL16.4-alpine ReadWriteOncePod claim
and UID rather than silently creating a database or selecting remote storage.
All org inputs, actual image/cluster/transport checks and deployment gates remain
pending. No tests or cluster/provider actions were run by the writer.

## 1. Source and ownership

The source baseline is accepted main
`f5da638759226360a6633eebe6cf37f3e18e40ac`, tree
`c627f9967395b848382568dd8d7cb46bfd087f5f`, ordered parents
`3c89887f8e2398eab557091d27d97e9aa238035c` and
`f04e3b6005ed579b0bcdbb409ab30583750c347c`. GitHub reports PR129 merged;
the fetched Git object agrees. This was a source read, not a new CI check.

Controlling documents are the [organization acceptance plan](../runbooks/org-sandbox-acceptance-plan.md),
[ADR0056](../architecture/0056-enterprise-credentials-and-egress-boundary.md),
[real-target gates](../specs/10-security-threat-model.md#7-real-target-release-gates),
[Okta qualification](../runbooks/okta-oidc-qualification.md),
[GitHub preflight](../runbooks/github-pilot-target-preflight.md) and
[credential-provider contract](../runbooks/github-app-installation-credentials.md).
Requirements traced by this proposal: FR-001/008/009/012/015;
SEC-001/002/003/006/007/008/012/019; REL-002/005/008; OPS-005.
No requirement status is promoted and no new trust boundary is implemented.

Local Mac owns this contract, its input template and future explicitly assigned
EKS containment work. Mac Air retains Track E, its reuse facades and permanent
lifecycle tests. This task does not inspect, message, poll, interrupt or modify
Air's active run. Its final candidate, validation and ownership handoff remain a
dependency; no Track E pass or publication is inferred here.

### Track E published dependency checkpoint

The human supplied [PR130](https://github.com/hemantkgupta/tracepermit/pull/130)
and its [final handoff](https://github.com/hemantkgupta/tracepermit/pull/130#issuecomment-6449232874).
That handoff covers `5046efabe7063086eb56b49f1415d64a0c6c6c87`, tree
`f5587cf91a7dc28e2cdc38be7743de68af29252a`, sole parent
`e307769dea09198bb7aaafe56f34bf125faa1c83`. It reports release of writer
ownership, clean/synchronized heads, 25 fresh lifecycle-module tests with no
failures/errors/skips, and aggregate coverage of 94.0049% LINE / 77.0553% BRANCH.
The earlier broad check's line-coverage failure and the subsequent focused
module/aggregate repair are separate receipts; no third broad check was run.
These are producer receipts, not new validation performed for this contract.

A bounded published-source read then found the PR at
`71aeccee76a890691b5cefc1c5a3113dd163e01f`, tree
`689e21675c46d1e0256578d09a1eddbed10472e7`, ordered parents
`5046efabe7063086eb56b49f1415d64a0c6c6c87` and accepted main
`f5da638759226360a6633eebe6cf37f3e18e40ac`. This is a normal merge of main;
its published delta imports PR129's process tests and documentation. GitHub
reports open, non-draft and unmerged, differing from the supplied draft-state
receipt. No PR state or source was changed here, and no CI status was polled.
The supplied pre-integration handoff does not qualify this later composition.

The handoff also explicitly leaves installed transport-level commit-reply-loss
injection unimplemented, with underlying V7 evidence remaining dependency
evidence. Live IdP/provider interoperability, protected credential custody,
remote ingress, database failover and production load remain unqualified.
Those limits remain applicable to the planned sandbox gates below. Publication
does not accept this dependency for EKS or authorize a real target stage.

## 2. Scope and phased entry point

Every simulated effect must still be evaluated before execution. Registration
remains execution-disabled. A deployment identity, namespace, private pipe or
network route is not application execution authority.

| Stage                           | Permitted scope after its own approval                                                                            | Required boundary                                                                                                                        |
| ------------------------------- | ----------------------------------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------- |
| P0: contract                    | Review this design and complete the restricted input sheet                                                        | No deployment, login, token minting or target action                                                                                     |
| P1: synthetic EKS rehearsal     | Human-operated, finite installed lifecycle Job in a trusted namespace, plus an isolated untrusted agent probe Pod | Accepted Track E artifact; synthetic identities/material and deterministic target only                                                   |
| P2: agent integration           | One selected agent/client submitting requests through a separately reviewed trusted adapter                       | Exact transport, authentication, audience, replay, rate limits and failure semantics implemented and qualified                           |
| P3: non-production GitHub pilot | One explicitly approved repository and draft-PR operation                                                         | Okta/profile composition, provider custody, preflight, durable dispatch and read-only reconciliation accepted; final live-stage go/no-go |

**Recommended first EKS implementation is P1.** Track E's approved interface is
a one-shot Java CLI with actual anonymous-pipe input, mandatory EOF and bounded
parent/worker execution. It is not an authenticated cross-Pod service. Do not
expose its loopback listener, tunnel stdin, replace loopback with `0.0.0.0`, or
make Kubernetes `exec` the agent's application API.

P1 proves an operator-run composition and containment controls only. It cannot
complete the actual-agent acceptance gate. Select the exact client and trusted
ingress before P2 implementation; a new remote boundary needs its own reviewed
contract. No public ingress is proposed. Existing browser callback/listener
constraints are unchanged.

Track E's dedicated loopback-store constraints must also be retained. For P1,
the proposed database is a disposable, dedicated supported-engine sidecar in
the trusted Job's Pod, with runtime access only on numeric loopback. It is part
of the trusted environment, never colocated with the untrusted agent. Select
the exact image and initialization procedure first. An external RDS/JDBC URL
is not compatible merely because it is inside the VPC; any necessary parser,
TLS or database-topology extension requires separate design and qualification.

## 3. Workload, identity and custody contract

| Component                     | Proposed privileges and data                                                                                                                  | Explicit exclusions                                                                                                  |
| ----------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------- |
| Untrusted agent/probe Pod     | Separate namespace and service account; explicit application client enrollment only if P2 is later accepted                                   | No provider key/token, permit-signing key, executor DB identity, AWS role, Kubernetes token or administrative access |
| Trusted lifecycle Job         | Accepted Java archive; exact operator-owned configuration; scoped runtime DB identity; private frames supplied through the approved interface | No automatic restart/retry, provider dispatch in P1, schema mutation or generic arbitrary-code endpoint              |
| Trusted database sidecar, P1  | Dedicated disposable database and restricted runtime grants; separate stopped-writer operator initialization                                  | No production data, shared agent volume or runtime DDL/grant changes                                                 |
| Platform operator             | Named human deployment and emergency-stop role; separately controlled initialization and secret provisioning                                  | Its kubeconfig/cloud privileges never enter an application Pod or evidence                                           |
| Future provider component, P3 | Exact GitHub App installation/repository mapping under trusted custody                                                                        | No agent-readable provider credentials, model-selected credential path or automatic fallback                         |
| Audit writer                  | Independently operated restricted sink and pinned write identity                                                                              | Agent cannot read secrets from evidence, delete records or change retention                                          |

Use dedicated trusted and untrusted node groups as the initial proposal, with
administrator-controlled placement and admission rules. Node separation and
Restricted Pod Security controls reduce exposure; they are not proof against a
compromised node/kernel/control-plane administrator. If the org requires a
stronger hostile-code sandbox, pin the supported RuntimeClass/isolation
mechanism and threat model before implementation.

Pin a version-compatible Restricted Pod Security admission policy. Require
non-root execution, no privilege escalation, dropped capabilities, an approved
seccomp profile, read-only application root filesystem and bounded writable
temporary storage. Disallow host networking/PID/IPC, hostPath and container
runtime sockets, privileged containers and shared host process inspection.
Controls apply to init and ephemeral containers too. These proposals follow the
[Kubernetes Pod Security Standards](https://kubernetes.io/docs/concepts/security/pod-security-standards/)
but require actual admission-denial evidence on the chosen cluster.

The agent service account has automatic token mounting disabled and no IRSA or
EKS Pod Identity association. It cannot create or mutate workloads, identities,
secrets, namespaces, labels, routes or network policy; use `exec`, `attach`,
`portforward` or ephemeral containers against trusted workloads; mint tokens;
or impersonate/bind/escalate roles. Workload labels alone are not identity.
The trusted runtime receives no Kubernetes API permissions unless a separately
approved need exists. Keep AWS workload identity and Kubernetes RBAC separate;
[EKS IAM guidance](https://docs.aws.amazon.com/eks/latest/best-practices/identity-and-access-management.html)
describes distinct credential and cluster-access boundaries.

Three credential domains remain distinct:

1. Okta requester/reviewer access tokens establish verified application
   identities and exact enrollment, never provider access by themselves.
2. Track E's per-delivery Ed25519 key uses its approved dedicated private frame;
   it must match the profile's public pin. Raw permits stay inside the worker.
3. A GitHub App RSA signing key and installation token are provider credentials.
   E's key-frame approval does not authorize sending a GitHub key through a new
   frame, storing it in configuration, or silently adding a credential broker.

The existing GitHub provider expects a protected POSIX RSA PKCS#8 DER source
with its documented size/permission checks. A KMS handle, CSI projection,
Secrets Manager source or broker is not automatically an implemented substitute.
Specify the selected custody source, permitted trusted container, IAM policy,
delivery format, rotation/revocation and memory/logging limits before P3.
Only references and public fingerprints belong in design evidence. Never put
bearers, key bytes, kubeconfigs, installation tokens or real org identifiers in
Git or public documentation. Exact org values belong in the private input sheet.

## 4. Network and process containment

Pin the EKS version, node OS/architecture, compute mode, CNI/add-on versions,
network-policy engine and startup enforcement behavior. A YAML object is not
proof that traffic is blocked. Standard Kubernetes policies are additive;
the effective allowed traffic is the union of selecting policies. Review
existing org policies as well as the proposed ones. See
[Kubernetes NetworkPolicy](https://kubernetes.io/docs/concepts/services-networking/network-policies/).

For Amazon VPC CNI, qualify the actual documented support and all active
interfaces. Its standard policy enforcement applies to the primary `eth0`
interface; extra interfaces and IPv4 egress on IPv6 Pods need explicit review.
Do not assume Fargate, Auto Mode, Multus, Windows, dual-stack or a newly chosen
plugin has the same behavior. See
[EKS network-policy considerations](https://docs.aws.amazon.com/eks/latest/userguide/cni-network-policy.html).
The recommended first topology is Linux EC2-backed nodes, one interface and
explicitly selected IP-family support, subject to the org platform owner.

| Route or surface                                                 | Agent                                                                                      | Trusted lifecycle Job                                                            |
| ---------------------------------------------------------------- | ------------------------------------------------------------------------------------------ | -------------------------------------------------------------------------------- |
| Ingress                                                          | Denied by default; exact selected client route only after P2                               | No inbound application listener in P1                                            |
| DNS                                                              | Approved resolver only; restricted names or a reviewed DNS-exfiltration decision           | Only necessary pinned dependency resolution                                      |
| GitHub/provider endpoints                                        | Denied on IPv4, IPv6, direct IP, alternate hostname and proxy paths                        | Denied in P1; exact reviewed method/path/host route in P3                        |
| Model-provider/dependency traffic                                | Separately allowlisted only if needed; cannot be a generic proxy to protected destinations | Only documented dependencies; no unrestricted CONNECT proxy                      |
| Database                                                         | Denied                                                                                     | Numeric-loopback P1 store only; exact runtime identity                           |
| Kubernetes API, node/host services, sockets and other namespaces | Denied                                                                                     | Denied except separately justified infrastructure services                       |
| Metadata and ambient credential services                         | Denied                                                                                     | No instance-profile fallback; only explicitly approved workload credential route |
| Audit sink                                                       | No mutation or read access                                                                 | Fixed, redacted write route only if selected                                     |

The metadata probe set includes EC2 `169.254.169.254` and, if enabled,
`fd00:ec2::254`; applicable container credential endpoints; and EKS Pod Identity
`169.254.170.23` / `fd00:ec2::23`. The latter are documented in
[EKS Pod Identity](https://docs.aws.amazon.com/eks/latest/userguide/pod-identities.html).
Blocking IMDS alone does not establish absence of other credential paths.
Do not fetch or retain credential response bodies as evidence.

Record IPv4 and IPv6 behavior, policy readiness before application start,
rescheduling, DNS answers/rebinding, redirects, proxy environment variables,
service/endpoint aliases, loopback, node routes and policy-controller outage.
Pod restart must not create a temporary unrestricted interval. If exact
hostname/TLS/path restrictions need an egress gateway, select and review the
org-supported mechanism; L3/L4 NetworkPolicy alone does not implement them.
Pod labels, security groups and NAT absence are not substitutes for that proof.

Process/container privileges may not expose the trusted Job's frames, file
descriptors, memory, mounts or signing material to the agent. Finite Job settings
must preserve E's existing parent, child and cleanup budgets. The proposed
one-shot Job uses `restartPolicy: Never`, `backoffLimit: 0`, `parallelism: 1`
and `completions: 1`; exact startup/deadline/grace settings need the final
artifact and database initialization timings. These settings do not replace
durable one-use admission or establish exactly-once process execution.
No Job/controller,
SDK, proxy or human automation may retry a failed/unknown delivery. A Job TTL or
deadline is not evidence that all owned workers exited or a target call stopped.

## 5. Okta and GitHub integration requirements

Okta inputs must identify a dedicated custom authorization server and exact
issuer, audience, client, tenant and signed claim grammar. Okta distinguishes
custom resource-server tokens from org-server tokens; confirm the actual tenant
entitlement and access policy. See
[Okta authorization servers](https://developer.okta.com/docs/concepts/auth-servers/).
An ID token is not substituted for an API access token.

Preserve Track E's verified requester and independent reviewer enrollment,
operator-owned offline roster, exact active-profile membership-source
fingerprint and membership expiry capped by token expiry and five minutes.
Fresh exact-owner identity is required for inspect/reconcile too. The browser
pilot's `approvals.*` vocabulary is not automatically the lifecycle's
`operations.*` vocabulary; confirm exact claims and adapter compatibility.
No body identity, email/domain inference or parsed JWT is authority.

Select how the human obtains and privately supplies tokens to the CLI. A
browser callback, remote login, on-behalf-of exchange or new machine client is
not implied by the existing installed command. Record offline-roster refresh
ownership and the maximum stale-token/membership window. Group removal does
not inherently revoke an already issued token; define and test the actual
enrollment/profile/credential stop procedure without claiming instant revocation.

For GitHub, pin the App, installation, immutable organization/repository IDs,
exact existing head/base refs and expected commit SHAs. Retain the existing
local permission profile `metadata:read`, `contents:read`,
`pull_requests:write`, restricted to that one repository, and existing strict
installation/token assurance/preflight checks. Unsupported response shapes or
permission confirmation stop the pilot rather than weakening these checks.
The existing fixed GitHub.com API boundary is retained; a GitHub Enterprise
Server hostname is a separate compatibility and trust contract, not a URL swap.
The API supports installation token scoping and draft creation, but those facts
do not qualify this installation. See
[GitHub App token exchange](https://docs.github.com/en/rest/apps/apps#create-an-installation-access-token-for-an-app)
and [pull request API](https://docs.github.com/en/rest/pulls/pulls#create-a-pull-request).

Provider permissions are broader than this exact operation. The executor and
approved route must enforce draft-only creation and bound read-only lookup;
deny merge, ready-for-review, edits, administration, branch creation, pushes,
other repositories and arbitrary methods/paths. Pre-existing reviewed refs are
required; changing refs after preflight remains a race, not a provider CAS.
GitHub App scope alone does not express the complete action binding.

Draft creation can still trigger workflows, webhooks, bots and notifications.
The target owner must approve those consequences and show the pilot repo has
no production deployment path, production secrets or privileged workflow route.
Unknown POST completion never authorizes retry, deletion, reconstructed permit,
new attempt after dispatch start or an absence-based success/failure claim.
Reconciliation is read-only and cannot restore authority.

## 6. Human-run acceptance matrix

All rows are **planned / not executed**. Capture exact commit/tree, uncommitted
diff, image/archive digests, topology and configuration fingerprints, expected
and actual results, target counters, elapsed bounds, skips/cache limits, failure
evidence and cleanup. Use fixed classifications, never credential payloads.

| Gate / cases             | Positive control                                                                       | Negative or uncertainty controls                                                                                             | Required result                                                                                 |
| ------------------------ | -------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------- |
| C0: release and source   | Accepted Track E archive extracted outside checkout; manual required repository checks | Unsupported Java; changed artifact/profile; missing final E results                                                          | Exact source/archive identity; all required gates accounted for                                 |
| C1: admission and RBAC   | Approved restricted Job/Pod starts under intended identity                             | Privileged/host-network/hostPath/extra capabilities; token mount; agent policy mutation/exec/impersonation                   | Admission/RBAC denies before workload gains authority                                           |
| C2: network bypass       | Intended dependency/approved application route works                                   | Direct GitHub, metadata, API, host, IPv4/IPv6, alternate interfaces, redirects/proxies/DNS; startup/reschedule/outage        | Forbidden routes deny with independent observation, not merely missing credentials              |
| C3: custody              | Trusted component accesses only its approved synthetic material                        | Agent file/mount/process/env/descriptor access; token/key/log/result leaks; wrong workload identity                          | No provider/signing custody crosses into the agent                                              |
| C4: Okta and review      | Exact enrolled requester, independent reviewer and fresh owner                         | Wrong issuer/audience/client/tenant/key/expiry/scope; self-review; stale roster/profile/generation; body trust               | No unauthorized state or target effect; pending approval means zero target calls                |
| C5: durable execution    | Explicit valid synthetic delivery; observed success/rejection                          | Full existing contention/wait/fence controls; actual commit-reply loss; crash before/after start; output/cleanup uncertainty | Target counters and durable state agree within documented limits; no resend after start/unknown |
| C6: GitHub, P3 only      | One authorized exact open draft on approved pre-existing refs                          | Wrong repo/ref/SHA/permission/installation; non-draft; redirect; workflow consequences; lost POST acknowledgement            | Exact bound operation only; unknown remains unknown without automatic POST retry                |
| C7: audit and stop       | Independent redacted checkpoint and approved operator stop                             | Sink outage/tamper attempt; agent retention mutation; queued/in-flight effect; revoked enrollment/provider access            | Explicit fail behavior and measured revocation; no rollback or no-late-effect claim             |
| C8: actual client, P2/P3 | Approved agent/client through exact deployed adapter                                   | Direct alternative route or different client/audience; replay/abuse/outage                                                   | Actual integration evidence; operator CLI/harness alone does not satisfy it                     |

No new test may be skipped. Existing assertions, deadlines, concurrency/workload,
grants, cleanup and 94% LINE / 75% BRANCH aggregate thresholds remain unchanged.
Preserve historical failed results; PR127/128 exceptions waive no sandbox gate.
Use existing runbooks for initialization, stopped-writer upgrades and recovery;
runtime must perform no DDL or privilege expansion.

The human executes each validation stage and supplies its private log path,
exit status and bounded result. Stop after any failure, classify and repair
only the demonstrated defect, then supply a separate exact retry command.
No agent-run tests/builds, cluster probes, CI triggers/retries or polling are
authorized by this contract. Do not stop or start another copy of Air's run.

## 7. Information and decisions needed before implementation

Complete the [input template](eks-sandbox-inputs.template.md) privately. The
required exact values cover cluster/topology, identity, repository/refs,
custody, data/audit, client route and owners. `Unknown` blocks the affected
stage; an example value or common provider default is not a selection.

Review these material decisions together before writing deployment manifests:

1. P1 operator-only synthetic rehearsal versus a separately specified P2
   agent interface; exact client and authenticated transport for P2.
2. Actual CNI/policy startup and egress enforcement mechanism, IP families,
   node isolation and whether the chosen compute mode supports all controls.
3. P1 loopback database/image and separate operator initialization; any later
   remote database boundary remains a separate change.
4. P3 GitHub key custody/workload identity and provider composition, distinct
   from Track E's approved per-delivery Ed25519 pipe.
5. Offline identity/membership freshness, incident revocation and unknown/audit
   outage procedures, with named human owners.

The recommended P1 scope is ready for review without deploying it. Final
go/no-go belongs to the pilot, platform, security, identity and target owners
after all applicable evidence is available. No automatic transition to P3 is
allowed. The next implementation packet must pin the accepted E integration
base, exact exclusive paths, private deployment manifest fingerprint, manual
commands and log capture. This document is uncommitted planning work; it does
not claim deployment readiness or merge qualification.
