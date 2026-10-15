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
qualification uses a synthetic IdP and safe simulator, not a live browser
login. A separate `pnpm dev:approvals` entry point accepts explicit private
operator configuration; `--check` validates files and trust configuration
offline, not IdP or database readiness. It is not started by existing demo launchers. See the
[API qualification runbook](https://github.com/hemantkgupta/tracepermit/blob/main/docs/runbooks/authenticated-approval-api-foundation.md).
The default demo console remains read-only. An optional same-origin Java
browser adapter now provides OIDC code/PKCE sign-in and a role-scoped React
approval inbox over the fixed simulator. Tokens stay server-side; all API
role, scope, ownership and exact-request checks still apply. Run
`pnpm demo:approvals:browser` for the test-only synthetic IdP/disposable DB
qualification. It is not a public browser deployment or live external-IdP
qualification. See the
[browser runbook](https://github.com/hemantkgupta/tracepermit/blob/main/docs/runbooks/browser-approval-inbox.md).

The separate Codex MCP composition exposes only request, status and explicit
resume tools over that same durable service. It cannot approve its own request.
Native app-server MCP calls and a synthetic browser review have been qualified.
The separate [model-driven record](https://github.com/hemantkgupta/tracepermit/blob/main/docs/demo-scenarios/evidence/codex-model-driven-approval-2026-10-15.md)
qualifies Codex 0.162.1/`gpt-5.6-sol` request/review/resume, replay and expiry
rejection; explicit reviewer denial remains a manual gate. This is one bearer-only
local profile, not arbitrary prompt/client compatibility, endpoint source
attestation or hosted OAuth.
See [Flow 3](core-flows.md#flow-3-human-review-then-explicit-resume) and the
[Codex runbook](https://github.com/hemantkgupta/tracepermit/blob/main/docs/runbooks/codex-durable-approvals.md).

[Scenario 3](https://github.com/hemantkgupta/tracepermit/blob/main/docs/demo-scenarios/03-request-bound-human-approval.md)
and [Scenario 8](https://github.com/hemantkgupta/tracepermit/blob/main/docs/demo-scenarios/08-production-deletion-request-bound-approval.md)
exercise the approval boundary with safe simulators and disposable state.
