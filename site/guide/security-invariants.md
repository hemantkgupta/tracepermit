---
title: The Security Invariant
description: The formal security boundaries and guarantees of TracePermit.
---

# The Security Invariant

TracePermit is constructed around a central, non-negotiable security invariant:

> **Every supported simulated side effect must pass through a controlled enforcement point. The gateway records what it observed, derives intent and risk itself, evaluates policy, and only then releases the action to a safe simulator adapter.**

![TracePermit Enforcement Flow Recap](/images/flow-recap.png)

---

## The Ten Core Security Axioms

### 1. Evidence Before Claims

Never accept `authenticated`, `delegated`, or `verified` merely because a request body or client header says so. A trusted adapter derives those fields from verified credentials or transport evidence. The development endpoint connector adds observed process facts with explicitly limited assurance; it is not kernel-backed managed-device attestation.

### 2. Action Before Prompt

Prompt injection can easily trick a large language model into believing it is acting benevolently. TracePermit evaluates the **structured, normalized action about to execute** (`tool`, `arguments`, `target`, `resource`), regardless of the natural-language prompt that triggered it.

### 3. Complete Mediation

Within the supported POC paths, no side-effecting simulator tool has an
unmediated fast path. This does not establish control over arbitrary host
processes, all third-party tools, or direct access to real enterprise targets.

### 4. Least Authority

Execution permits live at most 60 seconds and bind the exact canonical
operation. Delegation credentials have independently bounded lifetimes and
scopes. No real downstream target credential is configured in the POC.

### 5. Causal Lineage $\neq$ Authority Delegation

If Agent A calls Agent B, Agent B does **not** automatically inherit Agent A's privileges. Cryptographic delegations must be explicitly signed, parent-hash linked, and strictly monotonically narrowed (cannot broaden scope, target resources, or lifetime).

### 6. Single-Use Execution Permits (Anti-TOCTOU)

To prevent Time-of-Check to Time-of-Use (TOCTOU) race conditions, policy evaluation does not directly dispatch the tool. Instead, it mints a cryptographic Execution Permit (JWS) bound to:
$$\text{Permit} \longleftrightarrow \{\text{Subject}, \text{CanonicalActionHash}, \text{Resource}, \text{PolicyVersion}\}$$
The downstream adapter verifies and claims the permit before invoking a simulator. The local store is single-use within one process; the PostgreSQL profile provides tested cross-process atomic claims. A modified action hash is rejected.

### 7. Dual Control for Destructive Operations

When policy selects human approval for a sensitive simulated action, the durable approval flow requires distinct reviewer and requester identities:
$$\text{Requester} \neq \text{Approver}$$
Self-approval is rejected fail-closed.

### 8. Fail Closed

Missing, malformed, expired, or unknown credentials, tools, or policy versions never receive a permit or dispatch. Depending on the boundary, rejection is an HTTP/MCP authentication or validation error, or an explicit policy `block` decision.

### 9. Simulator First

New tool adapters and dangerous capabilities must first be verified against
deterministic, stateful simulators. The current POC grants no real cloud
credential.

### 10. Audit Honesty

An ambiguous result is not proof of failure and must not trigger blind
re-execution. A completion-write failure can leave consumed authority and an
`executing` attempt. The separately tested reconciliation worker handles persisted
`UNKNOWN` attempts by read-only lookup and appends a terminal result only when
confirmed. No automatic executing-to-unknown sweeper or worker is wired into the
browser approval launcher. See [Flow 9](core-flows.md#flow-9-uncertainty-is-not-permission-to-retry).
