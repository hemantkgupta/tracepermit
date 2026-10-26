---
title: Request-bound human approval
description: Durable dual-control approval for a fixed simulated action.
---

# Request-bound human approval

The Java durable profile demonstrates human-in-the-loop approval for an exact
simulated request. Its trusted reviewer context requires an authenticated,
authorized human in the same tenant. Self-approval is rejected. Approval
binds the subject, actor/lineage, event, action hash, policy, tool, resource,
and expiry; PostgreSQL atomically consumes it with the execution claim before
simulator dispatch.

```text
Agent proposes an allowlisted simulated change
          ↓
Policy requires approval → pending durable request
          ↓
Separate trusted reviewer approves exact request
          ↓
Approved / not executed → original requester explicitly resumes
          ↓
Atomic approval + permit claim → simulator execution
```

## Atomic claim and approval boundary

![TracePermit Claim Boundary](/images/03-claim-boundary.png)

The default POC does **not** expose a public approval API, editable console
control, or Slack/Teams notification workflow. A separate loopback-only Java
API foundation verifies access tokens plus operator-owned reviewer membership
and persists exact action snapshots with pending approvals. Its automated
qualification uses a synthetic IdP and safe simulator, including rendered browser
sign-in. A separate named Auth0/native composition was live-qualified on
2026-10-22; neither result is general provider compatibility. A separate
`pnpm dev:approvals` entry point accepts explicit private
operator configuration; `--check` validates files and trust configuration
offline, not IdP or database readiness. It is not started by existing demo launchers. See the
[API qualification runbook](https://github.com/hemantkgupta/tracepermit/blob/main/docs/runbooks/authenticated-approval-api-foundation.md).
The default demo console remains read-only. An optional same-origin Java
browser adapter now provides OIDC code/PKCE sign-in and a role-scoped React
approval inbox over the fixed simulator. Tokens stay server-side; all API
role, scope, ownership and exact-request checks still apply. Run
`pnpm demo:approvals:browser` for the test-only synthetic IdP/disposable DB
qualification. It is not a public browser deployment. See the
[browser runbook](https://github.com/hemantkgupta/tracepermit/blob/main/docs/runbooks/browser-approval-inbox.md).

The separate Codex MCP composition exposes only request, status and explicit
resume tools over that same durable service. It cannot approve its own request.
Native app-server MCP calls and a synthetic browser review have been qualified.
The separate [model-driven record](https://github.com/hemantkgupta/tracepermit/blob/main/docs/demo-scenarios/evidence/codex-model-driven-approval-2026-10-15.md)
qualifies Codex 0.162.1/`gpt-5.6-sol` request/review/resume, replay, expiry and
explicit reviewer-denial rejection. This is one bearer-only
local profile, not arbitrary prompt/client compatibility, endpoint source
attestation or hosted OAuth.
See [Flow 3](core-flows.md#flow-3-human-review-then-explicit-resume) and the
[Codex runbook](https://github.com/hemantkgupta/tracepermit/blob/main/docs/runbooks/codex-durable-approvals.md).

## Inspect history without granting authority

The inbox supports exact status/tool/request-ID filters and explicit older pages.
Each read rechecks current token, enrollment, role, scopes and tenant/owner context.
The cursor is an unsigned page position, never authorization; React bounds retained
rows, validates responses and renders untrusted action data as text. This is not a
complete audit export, retention system or policy-admin console.

See [Flow 10](core-flows.md#flow-10-browser-sign-in-and-role-scoped-history) and
the [history API contract](https://github.com/hemantkgupta/tracepermit/blob/main/docs/runbooks/approval-history-api.md).
For a step-by-step second-machine test, use the
[fresh Mac walkthrough](/guide/fresh-macbook-evaluation).

## Withdraw your pending request

The optional local inbox lets an eligible requester withdraw their own live
pending proposal. It requires current requester enrollment and an explicit
`approvals.cancel` grant; reviewer or viewer access alone is insufficient.

Select **Withdraw request**, inspect the exact ID/hash and then choose
**Confirm withdrawal**. **Back** only closes the confirmation. Java rechecks
ownership, scope, state and expiry before committing `cancelled` with
`not_executed`; no execution authority is created. The cancelled history filter
helps find the terminal request. A pending-only filter will no longer include it.

This is **not an emergency stop**: an approved or already admitted action cannot
be withdrawn by this flow. If review wins first, withdrawal conflicts. If the
response is lost, the UI does not claim success or retry automatically; inspect
current server state before taking further action.

Built-source synthetic-browser tests cover this path. A named source-free
same-Mac Auth0 profile exercised owner withdrawal on 2026-10-21, while the
2026-10-22 installed release-candidate rerun covered request, distinct review,
execution and restart durability. Other providers, public ingress and real
targets remain separate gates. See the
[sanitized installed record](https://github.com/hemantkgupta/tracepermit/blob/main/docs/demo-scenarios/evidence/installed-auth0-approval-2026-10-22.md).
See the [withdrawal walkthrough](https://github.com/hemantkgupta/tracepermit/blob/main/docs/runbooks/pending-approval-withdrawal.md).

## Expiry while reviewing

Loaded pending/approved requests update their local expiry labels without a
refresh. When an approval lifetime elapses, review/withdraw/execute controls and an open
confirmation become unavailable; stored status remains visible rather than being
rewritten. The confirmation handler also rechecks eligibility immediately before
POST. No timer approves, executes or retries anything. Browser time is advisory
and can be skewed or throttled in background tabs; Java remains authoritative.
Use **Refresh** to inspect current server state before continuing.

[Scenario 3](https://github.com/hemantkgupta/tracepermit/blob/main/docs/demo-scenarios/03-request-bound-human-approval.md)
and [Scenario 8](https://github.com/hemantkgupta/tracepermit/blob/main/docs/demo-scenarios/08-production-deletion-request-bound-approval.md)
exercise the approval boundary with safe simulators and disposable state.
