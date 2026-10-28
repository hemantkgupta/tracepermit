# Rendered policy is not deployed isolation

A renderer can prove that a document matches its exact offline contract. It
cannot prove which cluster receives it, which CNI enforces it, which additional
policies allow traffic, or what admission webhooks inject into a Pod.

The EKS sandbox candidate keeps workloads suspended, requires explicit identity
and artifact metadata, gives the agent no declared credentials, and emits only
fixed restricted workloads and routes. Namespace labels, image digests and PVC
UID annotations remain declarations until the human binds them to observed
resources. A private pipe is not same-UID isolation; a Kubernetes stdin setting
does not establish private remote custody.

An unsuccessful TCP connection also does not identify the cause. A failed
positive control is inconclusive; even a successful one cannot prove a different
destination was reachable before policy blocked it. Preserve an independent
positive peer and actual topology/policy evidence. Five numeric IPv4 probes do
not qualify IPv6, DNS, redirects, layer7 routing or target-side permissions.

Native database sidecars solve a Job lifecycle problem, not application retry
authority. A database restart or lost receipt can leave durable state unknown.
The existing evaluated-before-effect, exact permit, acknowledged start and
read-only no-resend rules still decide what the application may do.

See the [runbook](../runbooks/eks-synthetic-sandbox-bundle.md) and
[ADR0097](../architecture/0097-offline-eks-sandbox-bundle.md). The implementation
and tests are a candidate awaiting manual evidence, not deployment acceptance.
