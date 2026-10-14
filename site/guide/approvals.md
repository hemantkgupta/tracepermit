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

The POC does **not** expose an authenticated remote approval API, editable
console control, or Slack/Teams notification workflow. The React console is
read-only. [Scenario 3](https://github.com/hemantkgupta/tracepermit/blob/main/docs/demo-scenarios/03-request-bound-human-approval.md)
and [Scenario 8](https://github.com/hemantkgupta/tracepermit/blob/main/docs/demo-scenarios/08-production-deletion-request-bound-approval.md)
exercise the approval boundary with safe simulators and disposable state.
