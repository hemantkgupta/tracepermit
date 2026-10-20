# Pending approval cancellation — proposed product contract

**Status: Java API implemented; requester UI and installed withdrawal qualification remain.**
[ADR 0059](../architecture/0059-explicit-v9-v10-simulator-upgrade.md) implements
V10 storage/consumer compatibility and explicit V9→V10 upgrade only.
[ADR 0060](../architecture/0060-authenticated-pending-request-withdrawal.md) adds
the cancellation service and authenticated bearer/browser route with explicit
scope. No MCP tool or requester button is added. This document retains the full
product contract; use the [withdrawal runbook](../runbooks/pending-approval-withdrawal.md)
for the current API boundary and the distribution runbook for upgrade.

Requirements: FR-003/007/008/009/010; SEC-001/002/003/005/006/009/012;
REL-001/002/004/005; DATA-001; OPS-002/004; PORT-001/003.
Decision record: [ADR 0058](../architecture/0058-pending-approval-cancellation-contract.md).

## 1. Problem and current boundaries

An owner should be able to withdraw an unreviewed request instead of waiting for
expiry or asking a reviewer to deny it. Withdrawal must not grant execution
authority, impersonate an independent reviewer or promise to stop an effect.

The current [approval API](../runbooks/authenticated-approval-api-foundation.md)
supports request, read, review and explicit execution. Its stored statuses are
`pending`, `approved`, `denied`, `expired`, `consumed` and storage-compatible
`cancelled`; the cancellation route is now implemented. The browser's **Cancel** button dismisses a confirmation dialog; it does
not change durable authority. Local expiry guidance is not a server transition.

The current operator profile validates V1–V10 on startup. Its `--init-db` remains
empty-only. ADR0059 supplies the separate stopped-service V9→V10 upgrade gate
required by ADR0045; other predecessors remain unsupported. Old binaries refuse
guard10 rather than silently interpreting new statuses.

## 2. Narrow outcome

Only a verified, still-enrolled **request owner** with requester membership and
the new, explicitly granted `approvals.cancel` scope may withdraw a live
`pending` request. Human and workload requesters follow the same owner check;
cancellation does not assert human presence. The stable issuer-qualified
principal is used, not a display name, email, client-supplied actor or role claim.

The new terminal status is **`cancelled`**, with reason `REQUESTER_CANCELLED`.
No reviewer, approval decision, permit or execution attempt is fabricated.
Reviewer membership alone does not permit cancellation; even a combined
requester/reviewer can cancel only their own request. Distinct-reviewer checks
on the existing approval route remain unchanged.

This first slice refuses `approved`, `denied`, `expired` and `consumed` requests.
It does not revoke approved authority, interrupt a worker, cancel a target
operation, reconcile an unknown outcome or undo an effect. Those require a
separate revocation/dispatch design. Cancellation does not block the same owner
from explicitly proposing a genuinely new action under ordinary policy.

## 3. Authenticated HTTP contract

Bearer route: `POST /api/v1/approvals/{approvalRequestId}/cancel`.
The optional browser session adapter exposes the same operation under
`/browser/api/v1/approvals/{approvalRequestId}/cancel`, through the shared Java
service and existing origin/session/CSRF protections. The UI control remains separate.

The body contains exactly one field:

```json
{
  "requestHash": "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
}
```

Use the actual hash from an authorized read, not the illustrative value above.
No replacement arguments, caller timestamp, tenant, requester, decision,
reviewer, reason, authentication flag, token or proof is accepted in the body.
Preserve current strict JSON/media/size/query/header checks and `no-store`
responses. A scope by itself is not membership or ownership.

| Result                          | Proposed behavior                                                                                                         |
| ------------------------------- | ------------------------------------------------------------------------------------------------------------------------- |
| 200                             | Committed `cancelled` projection, original request hash and `executionStatus=not_executed`                                |
| 401                             | Missing, invalid or expired verified authentication                                                                       |
| 403                             | Missing current enrollment, requester role or `approvals.cancel`; cross-owner request already visible to an elevated role |
| 404                             | Absent or cross-tenant request; another owner's request hidden from a requester-only principal                            |
| 409 `APPROVAL_BINDING_MISMATCH` | Exact stored request hash differs                                                                                         |
| 409 `APPROVAL_NOT_CANCELLABLE`  | State is no longer pending, including a repeated cancellation                                                             |
| 409 `APPROVAL_EXPIRED`          | Pending row has reached expiry; cancellation makes no transition                                                          |
| 503                             | Persistence/readiness failure, quarantine or uncertain commit; no success assertion                                       |

For a valid, visible owner request, validate the exact hash before reporting its
state. Do not disclose another tenant's state through binding/state error detail.
The caller must not infer completion from a timeout or retry automatically.
An authorized GET may establish that the state is `cancelled`, but cannot attribute
which of two indistinguishable concurrent owner submissions committed.

No cancellation MCP tool is added in this slice. The existing three-tool
approval catalog and the separate native endpoint connector are unchanged.
An unsupported guessed tool or HTTP suffix must not dispatch or gain authority.

## 4. Atomic state transition and evidence

The persistence-neutral cancellation input carries only adapter-derived tenant,
requester principal, authentication method, credential fingerprint and verified
authentication deadline, plus validated request ID/hash. Its time and identity
are not public request fields. Authorize through a new requester-specific
context, not a `TrustedReviewerContext` with fabricated reviewer provenance.

Inside one short PostgreSQL transaction:

1. Acquire the existing recovery-ready shared guard **first**, then lock the
   exact tenant/request row, preserving the established lock order.
2. Check stored owner and request hash again under that lock. Require `pending`.
   Sample the trusted store clock after lock acquisition; reject expired request
   or authentication authority, including expiry while waiting for a lock.
3. Append exactly one `pending → cancelled` transition with requester actor
   provenance. The existing append-only transition mechanism updates the
   aggregate; do not add a direct-update escape hatch.
4. Append bounded `approval.cancelled` evidence and its outbox record in that
   same transaction. Record only the credential fingerprint, never the bearer
   token, raw canonical action, reason text, arguments or target result.
5. Commit before returning success. Never call a permit signer, execution
   ledger/claim or simulator from cancellation.

Membership removal applies to new authorization checks as it does today. The
current in-memory membership registry is not transactionally linked to PostgreSQL;
this design does not promise instantaneous revocation of already-authorized
in-flight requests. That remaining admin/revocation boundary must be explicit.

Cancellation remains subject to the current recovery-ready guard, including
the transition trigger. It is refused during quarantine, not a new quarantine
escape or emergency-stop operation. No policy evaluation is needed to withdraw
authority: a changed active policy does not prevent cancelling a still-pending,
unexpired owned row whose original hash matches. Cancellation never reactivates
an old policy or approval.

### Race outcomes

| Competing operation                                     | Required outcome                                                                            |
| ------------------------------------------------------- | ------------------------------------------------------------------------------------------- |
| Two valid cancellations                                 | One transition/evidence event commits; the other gets conflict; zero permits/claims/effects |
| Cancellation wins before reviewer decision              | Reviewer re-reads `cancelled` under the same row lock and cannot approve/deny it            |
| Reviewer approval wins first                            | Cancellation conflicts; approval remains subject to ordinary explicit execution checks      |
| Reviewer denial wins first                              | Cancellation conflicts; denial is not relabelled cancellation                               |
| Execution attempted while pending or cancelled          | Existing not-executable check rejects it; no permit/claim/effect                            |
| Request/authentication expires while cancellation waits | No cancellation transition or success; no effect                                            |
| Quarantine commits before cancellation admission        | Cancellation is refused by the existing recovery guard                                      |
| Lost commit acknowledgement                             | Return uncertainty/failure, not success; authorized lookup only, no blind retry             |

The forbidden combined outcome is successful cancellation **and** successful
approval/consumption of the same request. Linearization is the locked transition
and transaction commit, not the browser click, HTTP arrival order or a Java
pre-read. A reviewer-first outcome is not proof that an in-flight effect was
cancelled. Tests must exercise both lock schedules explicitly.

## 5. Proposed V10 schema and compatibility contract

V10 storage and its consumers are implemented in ADR0059; V1–V9 resources remain
byte-identical. The remaining authenticated transition and evidence service must
fulfil the following contract before cancellation is exposed.

The reviewed migration must add `cancelled` to aggregate/history contracts and
permit **only pending-to-cancelled** transitions. Preserve the aggregate update
guard, append-only ledger, tenant keys, recovery gates and monotonic sequence/time
checks. Add cancellation actor/authentication/fingerprint fields separately from
reviewer fields, with explicit nullability/length/format checks; forbid cancellation
metadata on non-cancellation transitions. `cancelled` has no decision ID, reviewer,
consumed permit or consumed attempt. The actor must equal the stored requester;
the transition trigger must verify that constraint, not only Java.

Advance the database contract guard to10 and update **all** strict open paths,
history status parsers, projection validators and owner inspection counts. An
older binary must refuse startup against V10 rather than silently omit cancelled
rows. Version or extend the documented lifecycle evidence contract before
emitting the new kind; do not bypass a validator with an unknown kind/version.
The new React parser must accept terminal cancelled projections before the
capability is exposed. Merely adding a button is insufficient.

An existing cancelled transition is durable across a normal process restart.
Restoring an older backup may restore the earlier pending request; cancellation
does **not** solve rollback resurrection. [Quarantine/recovery limits](../architecture/0051-operator-declared-recovery-quarantine.md)
continue to apply; a migrated or restored ready database does not authorize resume.

## 6. Explicit upgrade prerequisite

A separate operator-only **`--upgrade-db`** now supports exactly V9→V10 under
ADR0059. It is not an HTTP/MCP operation and is never invoked by `--check`,
`--run` or `--init-db`. The requirements below remain its compatibility contract.

The first implementation must:

- Require stopped clients/services and exclusive operator ownership of the
  intended dedicated simulator database, a verified backup and matching binaries.
  A local advisory lock coordinates cooperating upgraders; it is not proof that
  every administrator/runtime has stopped. Do not use irreversible recovery
  quarantine as a temporary migration lock.
- Validate exactly successful V1–V9 history/checksums, guard9, expected tables,
  invariant triggers and ready mode. Reject future/partial/modified/unknown
  schemas, unsupported predecessors, quarantine and unresolved executing/unknown
  attempts. This explicit predecessor check is separate from strict normal-start
  validation; do not loosen that validation to accept arbitrary pending versions.
- Apply only the packaged V10 migration with finite connection/query/lock budgets
  and final V10 validation. No listener, IdP fetch, token/key enrollment, permit,
  target call, history repair, baseline, clean, deletion or automatic retry.
- Preserve all existing requests, action snapshots, policy generations, decisions,
  consumption/attempt rows, evidence, outbox and quarantine state. Exact unchanged
  row-content checks, not cardinality alone, qualify preservation in fixtures.
- Use one transactional PostgreSQL migration for this version where supported.
  On failure or uncertain commit, retain state and emit a redacted failure; the
  operator validates/inspects it while services remain stopped. Never turn a
  failed upgrade into a fresh installation or automatically restore an older DB.
- Refuse repeated/unsupported upgrades with a fixed diagnostic. V10 normal
  startup still validates without migration. Runtime DML credentials do not gain
  migration privileges. Do not create a general-purpose schema administration API.

Rollback is not an in-place binary downgrade. An old binary must refuse V10.
Restoring a pre-upgrade backup also rolls back authority and evidence and remains
a disconnected recovery exercise, not a documented safe effectful resume.

## 7. Implementation order and acceptance gate

The storage/upgrade prerequisite in step2 is implemented under ADR0059.
ADR0060 implements steps3–4. The requester UI and installed qualification in
steps5–6 remain **required future tests**, not completed acceptance evidence:

1. Review this contract and its API/errors/schema/upgrade boundaries.
2. Implement explicit V9→V10 operator upgrade, preserving old migration bytes,
   with empty/wrong/partial/future/checksum/permission/lock/failure/acknowledgement
   cases, no listener/target, unchanged existing rows and old-binary refusal.
3. Implement the domain/store/service transition with deterministic unit tests,
   actual PostgreSQL races in both schedules, duplicate/expiry/rollback/quarantine
   cases, bounded fixture cleanup and exact zero permit/claim/target counters.
   A failed evidence/outbox insert must roll back the cancellation itself.
4. Add authenticated bearer/browser routes: issuer/tenant/owner/role/scope,
   invalid/expired/revoked credentials, extra/duplicate fields, CSRF/origin, wrong
   hash, every non-pending state, concurrent review and restart. Preserve the
   ordinary allow/approve-once/block/replay regression corpus.
5. Add requester-only **Withdraw request** with explicit confirmation. Keep
   **Back** or **Dismiss** for abandoning the dialog so it cannot be confused
   with durable cancellation. Never mutate from an expiry timer, refresh, effect
   hook or retry. Test stale state/session/selection and uncertain responses in
   the mounted React inbox; no success toast without a committed projection.
6. Qualify the installed simulator backend's explicit upgrade and API/browser
   flow, history filters and fixed-schema inspection count. Update public setup
   and flow guides only after merged runtime evidence supports the commands.

Do not implement all phases in competing branches. One writer owns each
runtime/migration slice; a read-only independent reviewer may probe it. Schema
and installed-runtime compatibility gate later HTTP/UI exposure. No global
requirement, fresh-host, external IdP or production-readiness status is completed
by approving this design.
