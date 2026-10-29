# ADR0099: offline EKS preflight and emergency-stop acceptance kit

- Status: implemented; compilation and manual validation pending
- Date: 2026-10-29
- Requirements: partial FR-008/009/012/015; SEC-003/006/007/008/012/019; REL-002/005/008; OPS-005

## Decision

Extend `eks-sandbox-bundle` with a separate installed
`tracepermit-eks-acceptance` launcher. It prepares a suspended organization
rehearsal, emits a finite manual acceptance/stop catalog and assesses private,
normalized operator observations offline. No AWS/Kubernetes SDK, kubeconfig,
process execution, listener, deployment, authentication or administrative
database adapter is added. Accepted main is PR143 merge
`7c33ea1ad1c5ef9dae07df235f28db14a1ea2c14`.

The versioned profile is `tracepermit-eks-organization-acceptance-v1`.
The organization operator profile remains
`tracepermit-installed-github-org-synthetic-operator-v1`. A plan pins the
existing closed EKS configuration, explicit organization image/archive/command,
context fingerprint, cluster UID, authority fingerprint and generation.
Current snapshot/stop freshness is explicitly60–300seconds. Control receipts
must fall inside a plan-pinned rehearsal window of at most24hours, independent of token,
approval, permit, lease and process deadlines. Those existing bounds do not
change. Missing values are not inferred from the current machine or cluster.

## Suspended organization variant

The existing renderer and template are unchanged. The new preparation command
uses their fixed P1 resources, adds an acceptance-plan fingerprint, and replaces
only the trusted Job's image/entrypoint/argument with the exact organization
operator at `/opt/organization/bin/tracepermit-synthetic-organization-operator`.
Both Jobs remain suspended, single-completion, non-restarting and zero-backoff;
the native loopback PostgreSQL16.4 sidecar, preinitialized PVC binding, no-token
service accounts, restricted security and network declarations are retained.
The old EKS image remains the separate agent/probe image. Organization and probe
archive identities are recorded separately. No generic entrypoint/command or
real-target adapter can be selected.

The plan's legacy bundle seed must use `check-config`; the separate explicit
organization command may be check-config/register/inspect/evaluate/review/
deliver-synthetic/reconcile. Private operator frames are supplied only under a
later approved transport/custody procedure. Preparing YAML/JSON never supplies
those frames or authorizes a Job to run. Organization-image qualification is
separate work; an older lifecycle image is not an organization-image substitute.

## Claims and evidence boundary

The kit reads a closed, bounded normalized snapshot, not raw kubeconfig, full
Pod dumps, JWTs, secrets or provider credentials. It compares the supplied
posture with pinned expectations, requires exact plan/context/cluster bindings,
tracks28 named controls, and preserves missing/unknown/failure/stale states.
Every claimed pass/failure requires an evidence fingerprint and observation
time. Unknown fields, noncanonical scalar types, duplicate JSON members and
trailing data are refused. The kit does not open the referenced evidence or
independently verify the reporting operator, test execution or observation truth.

An assessment is always `REVIEW_REQUIRED` or `BLOCKED`. Exit0 means a review
artifact was produced, never acceptance. Execution, automatic retry, cluster
acceptance and absence-of-late-effect flags are always false. A complete set
of invented pass records cannot grant authority. Cloud2's separate artifact/
evidence verifier remains the owner of generic archive/integrity/completeness
checks; this kit contains deployment-specific comparisons and drill ordering.

## Emergency-stop boundary

The catalog orders closure of new submissions, a separately authorized exact
profile quarantine, fresh admission-denial evidence, classification of every
started attempt, read-only reconciliation, owned cleanup/quiescence and audit
accounting. The accepted administration library does not authenticate an
operator or provide an installed administrative workflow. This kit does not
bridge that gap; a reviewed organization-owned administration procedure is a
prerequisite for the actual drill.

A stop record binds profile/generation/time, strict boolean observations and a
single-attempt terminal/unknown accounting union. Unknown remains visible even
when cleanup was reported. No rollback, resend, reconstructed permit, kill-based
effect cancellation, dead-worker target count or exactly-once claim follows.
The kit never suspends/deletes a live Job, Pod, PVC, database or evidence record.

## Manual qualification

Add default offline unit/CLI/source-free archive tests and retain all existing
module tests. Human-run focused qualification precedes unchanged repository,
formatting, docs/helper/13-flow/drift/diff and94% LINE/75% BRANCH gates. No
validation, syntax check, image/container/cluster action, CI or worker message
was executed by the writer. Inputs/templates and commands are in the
[runbook](../runbooks/eks-rehearsal-acceptance-kit.md). Actual cluster/private
transport, policy, stop and provider gates remain unrun and require their own
scope, inputs, owners and approval.

Primary deployment references, reviewed2026-10-29:
[Pod Security Standards](https://kubernetes.io/docs/concepts/security/pod-security-standards/),
[NetworkPolicy](https://kubernetes.io/docs/concepts/services-networking/network-policies/),
[EKS policy startup mode](https://docs.aws.amazon.com/eks/latest/userguide/cni-network-policy-configure.html),
and [Kubernetes audit levels](https://kubernetes.io/docs/tasks/debug/debug-cluster/audit/).
Their availability is not proof that a selected cluster implements the controls.
