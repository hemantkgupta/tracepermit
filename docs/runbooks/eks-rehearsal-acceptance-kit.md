# Manual EKS preflight, containment and emergency-stop kit

Status: **implemented; compilation and all new validation pending**.
See [ADR0099](../architecture/0099-offline-eks-acceptance-kit.md),
the [organization acceptance plan](org-sandbox-acceptance-plan.md) and
the [private input sheet](../planning/eks-sandbox-inputs.template.md).
There is no cluster, provider or administrative execution mode.

## Inputs and offline commands

The source-free EKS distribution additionally installs
`bin/tracepermit-eks-acceptance`. It supports exactly:

```text
tracepermit-eks-acceptance prepare PRIVATE_PLAN NEW_PRIVATE_MANIFEST
tracepermit-eks-acceptance catalog PRIVATE_PLAN NEW_PRIVATE_CATALOG
tracepermit-eks-acceptance assess PRIVATE_PLAN PRIVATE_SNAPSHOT NEW_PRIVATE_RESULT
```

These are interface forms, not commands to run against an organization now.
The coordinator supplies checkout/commit/runtime-pinned Bash validation commands
with fresh private logs and numeric exits. The writer does not execute them.
No `kubectl`, AWS CLI, Docker or CI command is invoked by the installed kit.

Copy [the plan template](../../deploy/sandbox/eks/acceptance-plan.example.json)
and [snapshot template](../../deploy/sandbox/eks/acceptance-snapshot.example.json)
outside Git into a0700 owner-only directory. Input files must be0600, regular,
and free of symlink substitutions; output files are newly created0600 and never
overwrite an existing path. Templates intentionally fail until required values
are selected. Never put real org identifiers in Git/public logs. No kubeconfig,
bearer, private key, password, credential URL, full Pod environment or raw audit
payload is an input to this kit.

The plan is a closed object with schemaVersion/profile, bundle,
contextFingerprint, clusterUid, operatorProfile, organizationImage,
organizationArchiveSha256, organizationCommand, authorityProfileFingerprint,
recoveryGeneration, maxObservationAgeSeconds and evidenceNotBefore. Bundle is the unchanged closed
EKS configuration; its command is the canonical `check-config` seed. The new
organizationCommand explicitly selects one of the seven accepted organization
commands. Digest-pinned organization and probe images are distinct. The trusted
entrypoint is fixed under `/opt/organization`; the probe remains under
`/opt/sandbox`. The organization image must actually contain its qualified
archive at that location. No URL/image substitution enables live execution.

`prepare` retains all13 fixed resources and both suspended Jobs. It does not
install the image, initialize the PVC/database, provide frames or unsuspend work.
`catalog` emits28 required controls and seven ordered stop steps, with no shell
administration commands. Exact organization inputs and actual execution approval
are still required before any human cluster action.

## Supplied snapshot contract

The snapshot has exactly schemaVersion/profile, planFingerprint,
contextFingerprint, clusterUid, capturedAt, posture, controls and stop. It is an
operator-normalized projection prepared under the private capture procedure,
not an automatic or authenticated collector. Do not feed arbitrary full API
objects into it. Capture/normalization and observation truth need independent
human review; no API/RBAC credential is acquired here.

Posture keys are the pinned versions/topology/namespaces/node pools/images/PVC,
organizationLauncher, manifestSha256, podSecurityEnforce/podSecurityVersion,
policyEngineEnabled, unexpectedSelectingPolicies, agentTokenAutomount,
trustedTokenAutomount, agentAwsRoleMappings/trustedAwsRoleMappings,
agentApiPermissions/trustedApiPermissions, hostNamespaceOrSocketAccess,
allContainerKindsInspected, runAsNonRoot, readOnlyRootFilesystem,
allowPrivilegeEscalation, addedCapabilities/dropAllCapabilities,
seccompRuntimeDefault, applicationRestartPolicy, jobBackoffLimit,
jobParallelism/jobCompletions and databaseAccessMode. Missing/null posture
values remain `NOT_SUPPLIED`; an observed contradiction or scalar coercion is
blocking. A matching manifest fingerprint is not kernel/network enforcement.

Controls are a closed map keyed by the catalog. Each supplied record has exactly
status/evidenceSha256/observedAt. Status is PASS_REPORTED, FAIL_REPORTED, UNKNOWN
or NOT_RUN. Reported passes/failures require a nonzero SHA256 and UTC instant;
NOT_RUN requires null evidence/time; UNKNOWN may be unobserved or have a complete
evidence/time pair. Current snapshot/stop records must fit the explicit60–300second
freshness bound. Control receipts must be between the plan-pinned evidenceNotBefore
and capturedAt, with a rehearsal window of at most24hours. Reports cannot be
future-dated. These evidence bounds do not change any runtime authority or process TTL.

| Control group              | Human evidence still required                                                                                                                                                                               |
| -------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Version and admission      | Exact EKS/CNI/platform compatibility review, restricted positive admission and denial of weakened Pod/init/ephemeral profiles. Existing version grammar is not a compatibility certificate.                 |
| RBAC and workload identity | Scoped agent/trusted permissions and denied escalation/exec/attach/token/secret paths; absence of credentials alone is insufficient. No ambient AWS role.                                                   |
| Network                    | Positive reachable control, enforced metadata/API/provider/node/socket denies, startup/rescheduling/controller-outage observations and DNS/proxy/redirect review. Review all selecting policies.            |
| Input and image            | Exact organization archive/launcher, actual EKS descriptor positive, EOF/refusal/leak controls and independent attach/audit custody review before private frames. Local Docker proof alone is insufficient. |
| Storage and authority      | Exact preinitialized V7/PVC owner, restricted runtime grants, fresh identity, independent review and no resend after started/unknown work.                                                                  |
| Stop                       | Fresh admission denial for the pinned quarantined profile/generation, read-only reconciliation, owned cleanup and independently accounted audit.                                                            |

The required network evidence is operational: policies combine allowed traffic
across selecting policies, and the networking implementation must enforce them.
See [Kubernetes NetworkPolicy](https://kubernetes.io/docs/concepts/services-networking/network-policies/).
For the selected VPC CNI topology, review actual strict-startup settings using
[AWS's documentation](https://docs.aws.amazon.com/eks/latest/userguide/cni-network-policy-configure.html).
Do not infer DNS/TLS/path isolation or a startup-deny observation from YAML.

Audit levels can include request/response bodies, depending on policy. Pin the
actual API/audit/streaming/intermediary recording behavior with the org operator;
do not assume that attach streaming is secret custody. See
[Kubernetes auditing](https://kubernetes.io/docs/tasks/debug/debug-cluster/audit/).
No raw capture or reusable credential is requested as evidence.

## Emergency-stop record and order

First the named owner closes new submissions. A separately approved operator
quarantines the exact authority profile using the existing supported control
procedure. The administration library is not an authenticated installed admin
tool; this gap must be resolved by an approved org procedure before the drill.
Observe a fresh admission refusal, then classify every started attempt, reconcile
read-only, observe owned cleanup/quiescence and account for audit evidence.
Do not start by killing Pods or deleting the PVC/evidence. No stop action grants
retry, reconstructs a permit or proves cancellation of an already started effect.

The closed stop object contains profileFingerprint/generation/requestedAt,
submissionsClosed, authorityQuarantined, newAdmissionDenied, readOnlyReconciled,
startedAttempts/terminalAttempts/unknownAttempts, ownedCleanupObserved,
auditAccounted and automaticRetryEnabled. Boolean values must be actual JSON
booleans. The P1 record accounts for at most one started attempt, with
started=terminal+unknown and automaticRetryEnabled=false. Unknown remains
visible even if all other checks match. No target-invocation count or rollback
field is accepted.

## Results and staged manual validation

Exit0 means a preparation/assessment artifact was written. Assessment is always
`REVIEW_REQUIRED` or `BLOCKED`; blocked returns exit1. Invalid inputs return1
with a fixed refusal and no raw data; usage returns64. A fully invented set of
pass records still grants neither cluster acceptance nor execution/retry.
Outputs contain fingerprints, fixed check IDs/statuses and counts, not org
identifiers or credentials. This is domain-specific consistency assessment,
not Cloud2's generic archive/evidence verifier or an independent audit attestor.

Run the coordinator's **focused** stage first: expected54 EKS cases, including
the original18 and36 new plan/render/assessment/private-file/archive controls.
It runs no container or cluster gate. After reviewing that exact-source result,
run **repo** separately: unchanged pnpm check/format, links/helpers,13flows/drift,
diff and94% LINE/75% BRANCH aggregate coverage. Stop at first failure. No automatic
retry, CI action, deployment or cross-worker dispatch follows. All new stages
are currently unrun; prior PR142/143 receipts do not qualify this new source.
