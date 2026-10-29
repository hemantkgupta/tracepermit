# Review and qualify the org P1 quarantine procedure

Status: **contract prepared; procedure selection and all new acceptance UNRUN**.
The human chose the [org-owned authenticated procedure route](../design/eks-p1-quarantine-procedure-contract.md).
There is no installed admin command, implementation or deployment action in
this packet. Fill the [blank private record](../../deploy/sandbox/eks/readiness/quarantine-procedure.example.json)
outside Git; retain non-secret approval/custody/target references only.

## Source facts, not new validation

At accepted PR146/147 main `d4faf07`, the guarded administration helper conditions the update
on scope/profile/generation/ready and waits for COMMIT+cleanup. Its source
accepts no actor/deadline, and shared statements use a five-second query timeout. Existing
OperationAuthorityControlContract cases cover quarantined-generation refusal,
runtime grant denial and a quarantine blocked behind a real control lock.
Its acknowledgement-loss cases shown in source exercise stageNew/activate;
they are not fresh quarantine-specific transport-loss proof. No tests were run
by this contract writer. Source review does not qualify the actual org tool,
identity mapping, credential lifetime, route, host custody or cleanup.

## Manual qualification matrix

All rows below are **PLANNED / UNRUN**. Use only separately approved disposable
synthetic fixtures and actual org procedure/artifact identity. Keep the existing
statement/runtime deadlines, workload, grants, cleanup and coverage unchanged.
Each row needs its own expected/observed outcome, exact source/artifact/target
binding, bounded timing/counters, original failure evidence and independent
review. Do not use a successful receipt from another source/topology.

| Case | Positive/control and required observation                                                             | Failure/uncertainty boundary                                                                                                                            |
| ---- | ----------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Q01  | Approved named operator, trusted full profile and exact ready writer; helper acknowledges quarantine  | Scope/profile/generation unchanged; only intended control state changes; no business facts/journals rewritten                                           |
| Q02  | Verified but unenrolled/wrong-scope operator                                                          | Refuse before acquiring administrative application access; no mutation                                                                                  |
| Q03  | Body flags, requester/reviewer identity or caller-selected issuer/key/policy                          | No administrative authority borrowed; approved independent root required                                                                                |
| Q04  | Agent/runtime attempts access to admin credential, process, files, logs or channels                   | No reusable credential or management access; synthetic-material leakage controls only                                                                   |
| Q05  | Runtime DB identity calls forbidden administration                                                    | Actual ACL denial with otherwise-valid values; not duplicate-key/stale-state masking                                                                    |
| Q06  | Wrong writer/clone/replica/PVC/context with otherwise matching profile                                | Org route/binding check refuses; helper metadata inspection alone is insufficient                                                                       |
| Q07  | Wrong/stale profile, scope or generation on the intended writer                                       | Refuse exact mismatch; no substitute profile, generation change or repair                                                                               |
| Q08  | Already quarantined or replaced row                                                                   | Record stale/observed state separately; no idempotent acknowledgement or reactivation                                                                   |
| Q09  | Two authorized quarantine attempts contend                                                            | Exact conditional transition; acknowledge only actual successful call; stale/unknown results preserved; no retry                                        |
| Q10  | Approved control holder blocks quarantine then releases                                               | Observe actual wait/order and existing timeout; no claim of instant stop before acknowledgement                                                         |
| Q11  | Operator authorization expiry/revocation during a real wait                                           | Demonstrate approved org session semantics/enforcement point; pre-call verification alone is not proof                                                  |
| Q12  | Real COMMIT acknowledgement lost after a control write                                                | UNKNOWN even if independent readback observes quarantined; no inferred rollback or automatic retry                                                      |
| Q13  | Connection cleanup acknowledgement lost                                                               | UNKNOWN; actual session/owned cleanup observed separately; no success from COMMIT alone                                                                 |
| Q14  | Procedure interruption/deadline/process or output loss                                                | Unknown completion/custody and incident procedure; finite owned cleanup; no unrelated process action                                                    |
| Q15  | Independent readback observes ready/quarantined/changed/unavailable after uncertainty                 | Observation stays distinct from this call's acknowledgement and from absence of a late write                                                            |
| Q16  | Legitimate unspent permit prepared while ready, then real admission-only probe after exact quarantine | Valid original deadlines, independent exact quarantined readback and unchanged per-operation facts/outbox; no delivery/target/lease/dispatch capability |
| Q17  | Probe unexpectedly admitted because stop is unestablished                                             | Record failed stop, preserve state/evidence and terminate probe; never continue to dispatch                                                             |
| Q18  | One business attempt already started or unknown when submissions close                                | Exact terminal-or-unknown accounting, read-only reconciliation, no resend or cancellation guarantee                                                     |
| Q19  | Canonical sha256-prefixed profile and bare-digest worksheet projection                                | Preserve both representations and exact correspondence; reject wrong algorithm/rehash/coercion/out-of-range generation                                  |
| Q20  | Evidence/audit/cleanup failure and missing org references                                             | Retain failures and incident owner; no acceptance inferred from a completed checklist or process exit                                                   |

Q16 must use an actual admission-only artifact/procedure approved by the org;
it cannot call deliver-synthetic as a denial probe. A successful preflight
guard or a rejected invalid permit does not substitute for admission evidence.
The existing issuer itself requires ready authority: prepare the permit before
quarantine, retain it only in memory, then invoke admission with otherwise-valid
prerequisites after the stop. An issuer-only rejection does not satisfy Q16.
Use a distinct qualified positive fixture to establish prerequisites. Missing
or staged controls can also return OPERATION_AUTHORITY_NOT_READY, so preserve
exact readback and exclude those alternative causes. Expiry during the admin
wait is inconclusive; no extended lifetime or automatic repeat. Unexpected or
uncertain admission must never continue to delivery.
Q12 needs real transport/observer evidence if claimed as wire-level loss;
throwing after a successful JDBC call is a narrower synthetic fault receipt.
No private Cloud 1 gate/helper is imported by this packet.

## Human command preparation

For this documentation-only branch the local writer supplies separate exact
source-pinned Bash **focused** and **repo** commands. Focused runs formatting,
documentation link helpers and 13 flow/drift checks. Repo later runs unchanged
pnpm check, pnpm format:check, links/flows/diff and 94% line / 75% branch aggregate gates.
These validate the documentation/source tree, not Q01-Q20 or an org procedure.
The earlier `23d438d` documentation pass remains a separate receipt. The new
accepted-base merge and probe-ordering text are implemented with validation
pending until the human supplies results for the refreshed commit/tree.
All commands capture fresh private stdout/stderr and numeric exits, stop on
failure and do not start another stage. The writer does not execute them.

No actual org/admin execution command can be written safely until the named
procedure/version, authenticated identity mapping, custody/route, exact writer/
profile, approval and finite deadline/cleanup semantics are supplied privately.
Then the human reviews each bounded command/log contract before running it.
Do not guess a kube-context, SQL shell, credential path, token flow or timeout.
No auth tool installation, network-policy repair, automatic retry, provider
action, CI action or worker message follows from the contract.

## Stop record and publication

Keep the org procedure record distinct from the existing closed EKS snapshot.
Project only actual observations using the documented fingerprint representation
bridge; never pass the entire record or approval fields as application authority.
Capture actual timestamps, preserve unknowns and meet the existing 60–300 second
snapshot/stop freshness and at-most-24-hour campaign window. Do not rewrite time
to freshen old evidence. Evidence remains private and independently retained.

Return a full 19-field handoff with source/trees/parents, the actual procedure
and trust/custody/route references, local versus org case results, fresh/cache/
skip limits, failures, readback/cleanup observations and ownership. Stop if any
required boundary is unestablished. No publication, activation, profile recovery,
live provider or deployment is authorized automatically.
