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
Agent proposes fixed simulated deletion
          ↓
Policy requires approval → pending durable request
          ↓
Separate trusted reviewer approves exact request
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
login. It is not started by the existing demo launchers. See the
[API qualification runbook](https://github.com/hemantkgupta/tracepermit/blob/main/docs/runbooks/authenticated-approval-api-foundation.md).
The React console remains read-only.
[Scenario 3](https://github.com/hemantkgupta/tracepermit/blob/main/docs/demo-scenarios/03-request-bound-human-approval.md)
and [Scenario 8](https://github.com/hemantkgupta/tracepermit/blob/main/docs/demo-scenarios/08-production-deletion-request-bound-approval.md)
exercise the approval boundary with safe simulators and disposable state.
