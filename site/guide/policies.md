---
title: Policy authoring and decisions
description: Deterministic policy over normalized actions and gateway-owned risk signals.
---

# Policy authoring and decisions

TracePermit's Java policy engine evaluates normalized tool actions, trusted
identity and origin context, typed arguments, and gateway-owned sequence
signals. It does not treat a prompt or caller-supplied "safe" flag as authority.

The four outcomes are `allow`, `block`, `sanitize`, and `require_approval`.
`sanitize` is not executable: an allowlisted transformer creates a distinct
effective action and policy must explicitly allow that new action. An approval
is bound to the exact request and requires a separate authorized reviewer in
the durable profile.

## Real POC policy excerpt

This is an excerpt from
[`policies/aws-agentic-demo.json`](https://github.com/hemantkgupta/tracepermit/blob/main/policies/aws-agentic-demo.json),
not a complete policy document:

```json
{
  "id": "block-agent-ec2-termination",
  "priority": 300,
  "match": {
    "origins": ["chatgpt-web", "claude-web", "cli"],
    "targetSystems": ["aws"],
    "actionClasses": ["destructive"],
    "toolNames": ["aws.ec2.terminate_instances"]
  },
  "decision": {
    "outcome": "block",
    "reasonCode": "AGENT_EC2_TERMINATION_BLOCKED"
  }
}
```

That rule blocks even a **simulated** termination for the listed origins. A
separate rule allows authenticated bounded log reads; sequence-risk and
oversized-result signals can instead block or sanitize. Rule ordering and
default decisions are defined in the full checked-in policy and validated
against the [policy
schema](https://github.com/hemantkgupta/tracepermit/blob/main/contracts/json-schema/policy-v1.schema.json).

Policy data does not grant target credentials. The POC's only target
executors are safe simulators.
