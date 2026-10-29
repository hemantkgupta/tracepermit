# A stop record is not a cancelled effect

Stopping new submissions and quarantining authority reduce future opportunity
to execute. They do not reach backward into an already acknowledged durable
start or atomically cancel a paused/external operation. Killing a process and
seeing an empty local target do not establish target-side rollback.

Account for each started attempt as confirmed terminal or unknown. Unknown is
information to preserve, not a failed attempt to repeat. Reconciliation reads
state; it does not reconstruct a permit, lease or resend authority. Audit and
cleanup are separate observations, with named owners and limits.

The [EKS kit](../runbooks/eks-rehearsal-acceptance-kit.md) checks supplied records
for contradictions and missing evidence. It cannot authenticate their author or
prove their truth. Even a complete supplied pass matrix remains human-review
material. Keep source/artifact integrity, observation freshness, operational
acceptance and application authority as separate decisions. See
[ADR0099](../architecture/0099-offline-eks-acceptance-kit.md).
