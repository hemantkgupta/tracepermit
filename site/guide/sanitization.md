---
title: Action Sanitization & Re-Evaluation
description: How TracePermit transforms risky arguments into safe, permitted actions.
---

# Action Sanitization & Re-Evaluation

In traditional firewalls, policy evaluation is binary: either a request is allowed through, or it is dropped.

However, in autonomous agent workflows, hard failures often cause the agent to enter confused retry loops or fail entirely.

TracePermit introduces the **`SANITIZE` policy outcome**.

---

## How Sanitization Works

When a policy evaluates an action to `SANITIZE`:

1. **The Original Action is Preserved:** The original action remains immutable in the audit evidence log.
2. **An Allowlisted Transformer is Invoked:** The reviewed `bounded-log-read/v1`
   transformer reduces the valid but oversized `limit: 500` request to `limit: 100`.
3. **A New Canonical Action is Created:** The sanitized arguments receive a
   distinct event ID and a new SHA-256 canonical action hash.
4. **Bounded Re-Evaluation:** The derived action is validated and passed through
   the policy evaluator again. Only `ALLOW` permits execution; another unresolved
   decision cannot become an unbounded transformation loop.
5. **Permit Binds the Effective Action:** The downstream permit binds the derived hash—never the original oversized hash.

```
Original Action (Hash A)
       │
       ▼
Policy Decision: SANITIZE
       │
       ▼
Allowlisted Transformer (Clamps Bounds)
       │
       ▼
Derived Action (Hash B)
       │
       ▼
Policy Re-Evaluation: ALLOW
       │
       ▼
Mint Execution Permit (Bound to Hash B)
```

---

## What Cannot Be Sanitized

To prevent privilege escalation, sanitizers are strictly constrained:

- A sanitizer **cannot broaden** target resources or permissions.
- A sanitizer **cannot alter identity claims** or tenant boundaries.
- If a sanitizer fails, produces non-converging output, or is unlisted, the action defaults to `BLOCK`.

The summary links original/effective hashes and a new event identity; it does not
invent causal ancestry. Arguments outside the MCP tool schema are rejected before
sanitization. See [Flow 4](core-flows.md#flow-4-sanitize-and-re-evaluate) for the
exact fixture and qualification boundary.
