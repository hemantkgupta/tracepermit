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
2. **An Allowlisted Transformer is Invoked:** A reviewed, deterministic transformer clamps or cleanses the arguments (e.g., reducing a requested log window from 1,000 down to 100).
3. **A New Canonical Action is Created:** The sanitized arguments receive a
   distinct event ID and a new SHA-256 canonical action hash.
4. **Recursive Re-Evaluation:** The derived action is passed back through the policy evaluator. Only if the derived action evaluates to `ALLOW` is an execution permit minted.
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
