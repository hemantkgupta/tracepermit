# ADR0094: offline, suspended EKS synthetic rehearsal bundle

- Status: implemented candidate; manual validation and cluster qualification pending
- Date: 2026-10-28
- Requirements: SEC-003/007/008/012; REL-002/005; OPS-005; partial evidence only

## Decision

Implement `eks-sandbox-bundle`, a separate Java21 renderer/checker over a fixed
Kubernetes JSON List. It does not load kubeconfig, create a cloud client, execute
a process, deploy, log in or fetch a provider credential. A separate explicitly
invoked TCP probe is packaged alongside it; rendering never calls that probe.
Existing lifecycle runtime, identity, protocols, SQL, grants and defaults remain
unchanged. This implements the preparation slice of
[ADR0056](0056-enterprise-credentials-and-egress-boundary.md), not its acceptance.

Configuration is an exact bounded string-only object with a versioned synthetic
profile. It pins cluster/account/region, Kubernetes minor, Linux EC2/IPv4/VPC-CNI
strict-mode declarations, separate namespaces/node pools, immutable images,
preinitialized database claim and UID, source/archive identity, command and
numeric probe destinations. No provider credentials or body authority fields
are accepted. The blank example deliberately refuses rendering.

The output contains two restricted namespaces, distinct non-automount service
accounts, resource limits, default-deny ingress/egress and one agent egress rule
to an explicitly selected non-sensitive canary Pod IPv4 on TCP18080. No Role,
RoleBinding, Secret, public Service, ingress or PVC is created. Resource names
do not establish custody or deny external cluster-wide RBAC bindings.

Both Jobs are suspended and have zero application retries. Pod networking,
host PID/IPC, privilege escalation and shared process namespace are disabled;
capabilities are dropped, seccomp is RuntimeDefault and application roots are
read-only. Administrator-owned NodeRestriction labels and exact taints separate
trusted/untrusted node pools. Actual admission/controller support must be
qualified; these are declarations, not an attestation of the cluster.

The trusted Job runs one explicit unchanged lifecycle CLI command with real
stdin/EOF and no TTY. PostgreSQL16.4-alpine runs as UID70 on numeric loopback
from an operator-preinitialized, dedicated ReadWriteOncePod claim. It starts
`postgres` directly, never `initdb`, performs no upgrade/grant initialization
and shares neither its data mount nor temporary volume with the lifecycle
container. V7 runtime and current authority are validated by the existing CLI.
The PVC UID is a bound expected value that still needs actual operator checking.

Use a Kubernetes native init sidecar for PostgreSQL, with its own restart policy
and startup probe, so the database can terminate after the main Job completes.
Require Kubernetes1.33–1.36 for this first closed profile. Its support and Job
behavior follow the [native-sidecar documentation](https://kubernetes.io/docs/concepts/workloads/pods/sidecar-containers/).
The database's restart policy does not authorize lifecycle reexecution. The
lifecycle Pod policy is Never, Job backoff is0 and existing CLI60s/5s/1s bounds
remain unchanged inside a120s outer Job deadline. A database restart/failure can
still leave an unknown result; generation/restore/failover are not qualified.

The probe makes five bounded numeric TCP connections, without DNS, HTTP or
credential reads: positive canary, API, protected target and two metadata
endpoints. A missing positive control is inconclusive. An observed connection
to a forbidden endpoint fails. All-denied observations alone are not a network
acceptance result: an independently reachable forbidden peer and actual topology
evidence are required to distinguish policy denial from an unavailable route.
IPv6, alternate interfaces, L7 routing, DNS exfiltration and hostile host/kernel
administrators are outside this initial profile and must not be inferred.

## Integrity, privacy and limits

Render/check require owner-only regular non-symlink files; render creates a new
0600 output inside a0700 directory, without overwriting. Output includes exact
non-secret deployment metadata and stays private. Errors and probe receipts are
fixed/redacted. The checker requires exact semantic equality with the fixed
manifest contract; it is not a general Kubernetes validator. Independent tests
inspect security fields and mutate critical policy/workload resources.

The Docker recipe uses digest-pinned, pre-cached Java21 base plus checksum-bound
archives, with no package installation or network build step. Its image has no
cloud/provider credential. A human still must build/inspect the actual image,
pin its digest and preload it on the selected nodes; `imagePullPolicy: Never`
prevents silent runtime downloads. Image version/content is not established by
an operator's text field or a source label.

No side-effect workflow is automatically chained from registration or a Job.
The renderer grants no execution authority. Human private-frame delivery into
the actual container and protection against audit/transport disclosure remain
deployment gates; stdin flags alone do not prove descriptor provenance or key
custody. Cluster access, data initialization, credentials and real GitHub/Okta
integration require their own owner-approved work.

Tests, archive checks and manual stages are described in the
[runbook](../runbooks/eks-synthetic-sandbox-bundle.md). No tests, builds, cluster
probes or CI actions have been executed by the implementation writer.
