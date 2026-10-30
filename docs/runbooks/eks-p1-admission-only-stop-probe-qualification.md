# Admission-only stop probe — planned qualification

Status: **DRAFT; no probe implementation or qualification executed**.
This is the manual acceptance plan for the
[proposed contract](../design/eks-p1-admission-only-stop-probe-contract.md).
The existing [org quarantine procedure](eks-p1-quarantine-procedure-review.md)
and its operator/session/custody/route approvals remain separate. PR149's 19
library cases are historical component evidence, not installed probe results.

## Draft-document validation

The local writer supplies source-pinned Bash focused and repo stages after a
source-only checkpoint. Focused runs formatting, documentation link helpers,
13 flow/drift checks and the scoped diff. Repo later runs literal pnpm check,
pnpm format:check, links/flows/diff and unchanged 94% LINE / 75% BRANCH gates. No new
JUnit case is created for a documentation-only proposal. These stages cannot
accept the product design or qualify any scenario below. Human execution only;
new private stdout/stderr logs and numeric exits; first failure stops the run.
Reused/up-to-date/cache outputs stay labelled; preserve all failed attempts.

The new documents and blank worksheet are not a reviewed implementation or org
input. Keep actual org/private selections outside Git. The shared traceability,
HANDOFF and ADR proposal stays unapplied until its integration window is free.

## Proposed future stage order

Every implementation stage requires the actual reviewed source tree, explicit
runtime/cache/image/artifact identities, source/auxiliary hashes and before/after
capture. Exact commands/selectors/counts must be derived from the implemented
source; these named scenarios are not claimed JUnit counts or current commands.
All stages are **PLANNED / UNRUN**.

1. Contract choices and minimal config/key reuse/packaging review; allocate the
   org/EKS source window. No parser copying, general callbacks or extra authority.
2. Human focused framing/provenance/identity/state/receipt tests and ownership
   cleanup controls. No global permissive test mode or deadline relaxation.
3. Installed extracted archive outside the checkout with Java-only PATH,
   source-bound distribution inventory and Java8 early refusal/Java21 execution.
4. Disposable PostgreSQL16.4 admission-only runtime and independent observer
   cases, with exact signed identities/approval/profile pins and fixed workload.
5. Probe-specific process/transport/timeout/UNKNOWN/cleanup qualification.
   Existing Cloud1 wire-loss helpers remain owned/unpublished; do not import
   or recreate them. JDBC faults must be labelled narrower than wire-level loss.
6. Configuration-cache create/reuse for affected producers; unchanged strict
   USTAR compatibility, original org/lifecycle command regression and full
   repository coverage/docs gates. No skipped new cases or automatic retries.
7. Separately review the actual probe image and closed EKS Job binding,
   ownership/RBAC/topology/input/audit custody and an explicit P1 disposition.
   No cluster action or actual org administration follows automatically.

The probe's runtime role must have no delivery-event INSERT or control-column
mutation privilege; the observer remains SELECT-only. Physical permission
checks need valid otherwise-successful values. A database error, hidden metadata
or duplicate/stale input must not stand in for an actual ACL denial.

## Planned scenario catalog

| ID  | Scenario                                     | Required observation                                                                                                             |
| --- | -------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------- |
| P01 | check-config valid / invalid                 | Closed offline parse only; zero authentication, JDBC, signing or target activity.                                                |
| P02 | Complete primary frames and EOF              | Effects start only after EOF; an appended byte or incomplete stream cannot prepare a permit.                                     |
| P03 | Java8 bootstrap                              | Fixed early refusal without private reads/effects; Java21 remains required.                                                      |
| P04 | Descriptor provenance                        | Parent and worker reject files, TTYs, sockets and named FIFOs; anonymous descriptor privacy is not caller identity.              |
| P05 | Closed frames/JSON                           | Size, depth, UTF-8, duplicate/trailing/unknown fields, numeric coercion and version/profile/UUID/hold bounds fail closed.        |
| P06 | Wrong config/profile pins                    | No substituted issuer/key/target/authority root or JDBC repair.                                                                  |
| P07 | Body identity/approval flags                 | Never accepted as authenticated/enrolled authority.                                                                              |
| P08 | Requester bindings                           | Wrong tenant/client/subject/principal/workspace or scopes cannot prepare/admit.                                                  |
| P09 | Token/key substitutions                      | RS256 trust/enrollment and original pinned Ed25519 key proof retained.                                                           |
| P10 | Approved/unstarted preparation               | One real permit in worker memory, closed read transaction before signing, one sanitized PREPARED.                                |
| P11 | Wrong private key                            | Refusal without permit/secret output; byte erasure limits honest.                                                                |
| P12 | Pending/denied/withdrawn state               | No PREPARED or issuer-only refusal misreported as admission refusal.                                                             |
| P13 | Already admitted/started/unknown             | No new attempt, replacement permit, lease, retry or delivery.                                                                    |
| P14 | Ready calibration                            | Exactly one acknowledged admission/fact/head/outbox, no delivery event/target; positive fixture distinct from stop case.         |
| P15 | Quarantine acknowledged during hold          | Original valid permit reaches actual admission, exact authority-not-ready result and independently unchanged scoped data.        |
| P16 | Missing/staged controls                      | Same refusal code explicitly distinguished from an observed exact quarantine.                                                    |
| P17 | Wrong scope/profile/generation               | No substitute pins, state repair or accepted stop.                                                                               |
| P18 | Repeated/stale/uncertain quarantine          | External acknowledgement/readback distinct; no automatic admin/probe repeat.                                                     |
| P19 | Permit/approval expiry during hold           | Failure/inconclusive stop, no extension, reissue, shortened substituted hold or test skip.                                       |
| P20 | Requester expires during hold                | Same bearer reverified; expiry does not become quarantine proof.                                                                 |
| P21 | Actual control-lock wait                     | Writer-time authority/deadline checks after waits; original SQL and parent bounds unchanged.                                     |
| P22 | PREPARED output missing/malformed/lost       | No successful stop inference; bounded forwarding, possible late admission preserved as UNKNOWN.                                  |
| P23 | Owned worker killed before attempt           | Bounded owned reaping and independent observation; no restart or absence-of-late-write claim.                                    |
| P24 | Process/receipt loss during admission        | UNKNOWN even if readback later sees claimed/refused state; no retry.                                                             |
| P25 | COMMIT/cleanup acknowledgement faults        | Exact known/unknown classifications; JDBC fault scope distinguished from real transport.                                         |
| P26 | Storage unavailable before/after preparation | Distinct from authority refusal; no successful rollback or stop inference.                                                       |
| P27 | Runtime forbidden mutations                  | Actual otherwise-valid ACL denial; control lock privilege works; no admin or delivery INSERT grant.                              |
| P28 | Independent observer                         | SELECT-only route/pins and unchanged scoped hashes/counts; no ready-guard bypass or supplied evidence promotion.                 |
| P29 | Total deadline/cleanup/late child            | Read/start/hold/JDBC/output share 60s; final send inside total, cleanup at most 5s; no widened bounds or unrelated-process kill. |
| P30 | Installed source-free artifact               | Exact archive/source inventory outside checkout; no hidden harness/JDK tools/runtime callback dependency.                        |
| P31 | Existing launcher regression                 | Original organization/lifecycle commands, profiles, frames, receipts, defaults and target behavior unchanged.                    |
| P32 | Build-only producer/cache                    | New artifact normalization and cache create/reuse; strict verifier unchanged; exact raw/final hashes retained.                   |
| P33 | Secret/output custody                        | No token/key/permit/password/body/exception/argv/env leak; at most 1,024+2,048 output bytes and no stderr.                       |
| P34 | Hold owns no JDBC resources                  | Independent actual server/session/lock observations; wrapper counters alone are insufficient.                                    |
| P35 | Quarantine late/absent, admission succeeds   | EXPECT_STOP fails as ADMITTED; exactly one admission and no subsequent lease/target call.                                        |
| P36 | Missing evidence/source or time mismatch     | No P1 acceptance from process exit, record completeness, cached results or renewed timestamps.                                   |

Unexpected admission consumes the disposable operation's permit/attempt and is
a failed stop. A new operation or mutation requires a separate human direction;
it is not an automatic retry policy. Never test this against a live provider.
Missing witnesses, expired prerequisites or ambiguous ordering stay explicit.

## Evidence and completion

For each actual case retain exact source/tree/parents, archive/image/input hashes,
fixed case/mode/workload, real stage/observation timestamps, numerical exits,
original XML/logs, classified outcomes, actual counters and owned cleanup.
Record the approved expected result separately from observed output. Capture
credentials only as fingerprints. Raw tokens/keys/permits/JDBC strings/process
environments and actual org identifiers remain outside public records/Git.

Return a full 19-field handoff including actual tested versus final-source delta,
fresh/cache/up-to-date/skip limits, failed/unrun scenarios, resource observation
limits and explicit ownership release. No current design, documentation pass,
qualified producer or verifier result establishes org execution permission.
