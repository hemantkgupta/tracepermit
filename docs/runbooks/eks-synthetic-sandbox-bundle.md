# Offline EKS synthetic sandbox bundle

Status: **implemented; manual validation pending; not deployed or cluster-qualified**.
Requirements: SEC-003/007/008/012; REL-002/005; OPS-005, partial preparation only.
See [ADR0094](../architecture/0094-offline-eks-sandbox-bundle.md) and the
[organization acceptance plan](org-sandbox-acceptance-plan.md).

## What is included

`java/eks-sandbox-bundle` provides a Java21 source-free distribution named
`tracepermit-eks-sandbox`. It renders or checks a fixed13-resource Kubernetes
JSON List using [the versioned template](../../deploy/sandbox/eks/bundle.template.json).
There is no apply/deploy mode and no AWS/Kubernetes/provider client. A separate
`EksNetworkProbe` main is used only when an explicitly approved probe Job runs.

The List includes two namespaces with version-pinned Restricted Pod Security
labels; two non-automount service accounts; two quotas/limits; two default-deny
network policies; one exact positive-control rule; and two suspended Jobs.
No roles/bindings, credentials, public services, ingress or storage are created.
Other org policies and admission mutations may expand the actual effective
behavior and must be reviewed separately.

## Supported first profile and required inputs

Copy [the blank example](../../deploy/sandbox/eks/operator.example.json) outside
Git into an owner-only file. Placeholder values are invalid. The exact keys are:

```text
schemaVersion profile stage accountId region clusterName clusterArn
kubernetesMinor compute architecture ipFamily cni cniVersion policyMode
agentNamespace trustedNamespace agentNodePool trustedNodePool
runtimeImage databaseImage databasePvc databasePvcUid sourceCommit archiveSha256
rehearsalId command controlIPv4 kubernetesApiIPv4 protectedTargetIPv4
```

All values are strings. Fixed selections are schema1.0,
`tracepermit-eks-synthetic-sandbox-v1`, `P1_SYNTHETIC_ONLY`, `ec2-linux`, `IPv4`,
`amazon-vpc-cni` and `strict`. Declare a supported Kubernetes minor1.33–1.36 and
exact CNI add-on version; do not use `latest`. Select explicit `amd64` or `arm64`
architecture matching both nodes and image artifacts. Account/region/name must match the
exact AWS cluster ARN. Separate namespace names begin `tp-sbx-`; node pool values
are distinct. Protected placement uses
`tracepermit.node-restriction.kubernetes.io/pool` and exact
`tracepermit.dev/pool` NoSchedule taints. The platform owner must verify actual
Node authorizer/NodeRestriction enforcement and the selected compute/CNI support.

Both images require exact SHA256 digest references. `runtimeImage` follows
[the combined-image recipe](../../deploy/sandbox/eks/Dockerfile); the database
image must be a qualified PostgreSQL16.4-alpine image with UID70 and the
documented binaries. Image text/digests are operator assertions until independently
bound to actual built/inspected artifacts. No image is pulled by the workloads.

The dedicated pre-existing database PVC must be operator-preinitialized,
ReadWriteOncePod, owned by UID70, and contain `pgdata` with the accepted V7 schema,
restricted runtime identity, SCRAM authentication and current profile/generation.
Record/check its exact UID, namespace, CSI/access mode and recovery history before
using it. The bundle's annotation pins the expected UID but cannot check the live
claim. It does not create the PVC, initialize/upgrade PostgreSQL, select a storage
class, load passwords, grant authority or permit concurrent database writers.
ReadWriteOnce alone is insufficient because it can admit multiple same-node Pods.

Commands are the unchanged lifecycle set: check-config, inspect, evaluate,
review, deliver-synthetic, reconcile. Each Job represents one explicit invocation,
not an automatic multi-command scenario. Never put lifecycle configuration,
bearers, passwords, keys or permits into manifest environment, args, ConfigMaps,
Secrets, logs or files. Supply exact frames privately through the already-approved
input interface only after the operator's actual container transport/custody has
been qualified. `stdin:true`, `stdinOnce:true`, `tty:false` are declarations,
not proof of an anonymous descriptor or a protected remote input path.

The positive-control address is an independently running non-sensitive canary
**Pod IPv4**, on TCP18080, outside these namespaces. It must be private and
different from the API and protected-target IPs. Do not substitute a Service IP
and assume the CNI's pre/post-DNAT policy behavior is equivalent. API and target
IPs must be independently observed reachable from an approved positive-control
peer. There is no DNS egress in this profile; numeric probe inputs avoid DNS
failure being mislabeled as denial. IPv6/dual-stack/additional interfaces need
a separately reviewed profile, rather than silently dropping those probes.

## Offline render and check — never deployment

After human-run validation and packaging, extract the archive outside the
checkout. Use Java21 and an owner-only directory. The operator first completes
the private configuration; the shipped blank example deliberately fails.

```bash
umask 077
task_private="$(mktemp -d /private/tmp/tracepermit-eks-operator.XXXXXX)"
cp deploy/sandbox/eks/operator.example.json "$task_private/operator.json"
chmod 600 "$task_private/operator.json"
# Edit only this private file with approved identifiers/digests, never secrets.
# From the extracted archive, using its actual absolute launcher path:
# bin/tracepermit-eks-sandbox render "$task_private/operator.json" "$task_private/bundle.json"
# bin/tracepermit-eks-sandbox check  "$task_private/operator.json" "$task_private/bundle.json"
```

The two launcher forms are exactly `render CONFIG OUTPUT` and `check CONFIG BUNDLE`.
Usage exits64, refusal1, successful offline render/check0. Errors print a fixed
classification without path/configuration/exception text. Render creates a new
0600 file in a0700 directory, refusing symlinks and overwrite. It may leave an
incomplete new output on an I/O failure; a refusal is not a successful artifact.
Check verifies the exact versioned contract, not a live cluster, kube-context,
identity, PVC, native-sidecar/CNI support, image contents or applied policy.

Both Jobs are suspended. There is deliberately no deployment/resume command
here. Rendering or checking a file is not approval to apply any resources.

## Process, database and uncertainty limits

The lifecycle Job is finite: backoff0, parallelism1, completions1, Pod restartNever,
outer deadline120s and termination grace10s. Its original lifecycle60s parent,
5s cleanup and1s receipt budgets are unchanged. The native database init sidecar
has a20s startup-probe window and its own restartAlways lifecycle. It binds only
127.0.0.1, has a separate temporary volume, and cannot keep a completed native
sidecar Job alive. A sidecar restart does not authorize repeating the application.
See [Kubernetes native sidecars](https://kubernetes.io/docs/concepts/workloads/pods/sidecar-containers/).

Neither controller settings nor a PVC provide target exactly-once execution.
Retain the V7 authority, durable admission/start, uncertainty and no-resend rules.
A killed process/Job or a missing receipt is not rollback or proof of no late
effect. Reconciliation is freshly authenticated and read-only. Do not retry an
unknown Job by making a new name, permit, attempt or input substitution.

## Probe observations — separately human-approved cluster stage

The agent Job runs a fixed Java TCP probe: positive canary18080, API443,
protected target443, EC2 metadata169.254.169.254:80 and EKS Pod Identity
169.254.170.23:80. Each connection has a500ms timeout. It sends no HTTP request,
reads no credential response, resolves no DNS and prints no destination details.
The fixed results are:

- `PROBE_INCONCLUSIVE_CONTROL_UNAVAILABLE`, exit2: positive control failed.
- `BYPASS_CONNECTION_OBSERVED`, exit1: a forbidden connection was possible.
- `DENIAL_OBSERVATIONS_RECORDED`, exit0: observations matched; **not** qualified isolation.

Unroutable or absent endpoints can also refuse TCP. Require independent peer
reachability, policy/counter evidence, live admitted Pod review, startup and
reschedule checks before attributing refusal to policy. Node traffic, extra
interfaces, policy union, injected identities and org-level RBAC need actual
inspection. Never claim IPv6, FQDN/TLS/path controls, model-provider routing,
hostile-tenant isolation, live GitHub/Okta or real credential custody from these
five observations. See [EKS network-policy considerations](https://docs.aws.amazon.com/eks/latest/userguide/cni-network-policy.html).

## Manual qualification

The implementation writer has run no tests, builds, image builds, cluster probes
or CI actions. Exact source/runtime/cache commands and private log/exit capture
are handed to the human for this local checkout. Run these stages separately:

1. Fresh `:eks-sandbox-bundle:test` with offline dependencies, one Gradle worker,
   caches disabled and rerun-tasks. It includes positive/negative config and
   manifest controls, private file safety/redaction, offline effect tripwires,
   local loopback probe tests and actual extracted archive invocation. No cluster
   or metadata/provider connection is made by these tests.
2. Same-source `pnpm check`, `pnpm format:check`, documentation links/helpers,
   all13flow/drift checks and diff checks. Existing94% LINE/75% BRANCH floors
   and all old tests/timeouts/grants stay active. Stop on failure.
3. After explicit image inputs, a separate human packaging stage builds both
   archives and the combined Docker image with cached digest-pinned Java21 base,
   network-disabled build and SHA256 checks. No image/registry push is implied.
4. Only after the actual org inputs, owners and deployment/transport review,
   a separately approved P1 rehearsal may run. Keep every failure and cache/skip
   limit; no automatic CI retries, deployments or ongoing polling.

The feature remains **validation pending** until actual human results are
returned. Existing passing PR130/issue126 results do not qualify this new module,
image or cluster composition. Okta/GitHub integration remains a separate lane.
