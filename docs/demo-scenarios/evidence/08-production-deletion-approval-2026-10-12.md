# Scenario 8 local qualification — 2026-10-12

**Scope:** Local Java presenter composition with runtime-generated Ed25519
development keys, a trusted development reviewer context, disposable
PostgreSQL 16, and one fixed in-memory AWS simulator. The `prod` environment
and `simulated-123` account are labels only. No real AWS credential, SDK,
endpoint, account, or enterprise reviewer login was involved.

The commands below were run against the local working tree. This is
reproducible POC qualification, not an independent production audit.

| Command                                       | Observed result                                                                                                                                                                                                           |
| --------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `pnpm demo:scenario:production-deletion:auto` | Passed; signed workload/delegation verified, explicit approval rule matched, no dispatch while pending, unauthorized review and mutation rejected, one approved in-memory deletion, replay rejected, valid evidence chain |
| `pnpm demo:scenario:approval:auto`            | Passed; existing Scenario 3 modification approval retained its pending/mutation/replay behavior and one-effect result                                                                                                     |
| `pnpm check`                                  | Passed; console build/typecheck/tests, Java tests, and aggregate checks                                                                                                                                                   |
| `pnpm test:java-postgres --rerun-tasks`       | Passed against disposable PostgreSQL                                                                                                                                                                                      |
| `pnpm format:check`                           | Passed after formatting                                                                                                                                                                                                   |

## Secret-free Scenario 8 output

```text
TracePermit Live Scenario 8 — Production deletion requires approval
====================================================================
All AWS names are simulated; no AWS SDK, endpoint, or credential is configured.
Starting disposable PostgreSQL 16 for durable approval and evidence state...

1. Verified scheduler -> critical action -> pending approval
   Subject: authenticated workload:scheduled-cleanup-demo; scheduled trigger
   Signed delegation: aws.ec2.security_group.delete on one simulated security group
   Classification: destructive / critical; target label: prod
   Policy: require_approval; durable status=pending; simulator dispatches=0
   Binding: exact action, subject, lineage, policy v1, 1-minute expiry, maxUses=1

2. Reviewer authority is independent of the agent request
   [PASS] A human without the reviewer role/scope cannot approve
   [APPROVED] Separate development reviewer; simulator dispatches still 0

3. Mutated request cannot reuse the approval
   [PASS] Changed groupId rejected before dispatch; sg-demo still exists

4. Atomic consumption -> one in-memory deletion
   [PASS] PostgreSQL consumed approval and claimed execution before dispatch
   [EXECUTED] sg-demo removed from local simulator exactly once

5. Replay and evidence integrity
   [PASS] Approval replay rejected; total simulator dispatches=1
   [PASS] Durable evidence chain valid with 5 bounded records

Qualification statement: verified origin and narrow delegation did not authorize
deletion. Only a separate review of the exact canonical action enabled one
permit-backed simulated effect. No real AWS target was contacted.

[PASS] Scenario 8 complete; disposable PostgreSQL was removed.
```

The run proves this local composition, not production reviewer authentication,
managed scheduler key protection, enterprise egress enforcement, or safe live
deletion in AWS.
