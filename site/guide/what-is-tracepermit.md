---
title: What is TracePermit?
description: An introduction to the simulator-only Agentic Visibility & Control POC.
---

# What is TracePermit?

**TracePermit** is an open-source **Agentic Visibility & Control (AVC)** proof of
concept. Its Java gateway attributes, classifies, authorizes, and records
supported AI-agent tool actions before safe simulator execution. Real target
adapters and production deployment are future work.

It allows organizations to answer the fundamental question:

> _"Who or what caused this action, what authority did it have, what exact operation was attempted, which policy applied, what was approved, and what outcome can be proven?"_

---

## The Problem: Why Agents Break Traditional Security

AI agents can propose multi-step tool calls and actions, including cloud
operations. The enforcement question is what executable action crosses a
controlled boundary—not whether the model's private reasoning was visible.

Existing controls do not, by themselves, provide the complete action context used here:

1. **Prompt checks are not execution authorization:** Text inspection can help detect unsafe content, but it does not replace a decision on the exact structured tool invocation and its target.
2. **IAM and AVC answer different questions:** IAM can restrict principal, action, and resource. TracePermit adds request-bound approval, sequence context, and a simulator-side execution permit; it does not replace downstream IAM.
3. **Causal Lineage is Confused with Authority:** A chain showing which agent
   called which does not by itself grant authority. The signed-workload POC
   independently verifies complete, narrowed delegation chains.
4. **Client Claims Cannot Be Trusted:** Attackers can trick an agent into injecting headers (e.g., claiming to be an admin or claiming an action was pre-approved). TracePermit operates under complete mediation: **never trust client-asserted security claims**.

---

## Core Pillars of TracePermit

```
┌─────────────────────────────────────────────────────────────────────────────┐
│ 1. Complete Mediation                                                       │
│    Every simulated side effect must pass through an enforcement point.     │
│    No downstream action executes without prior normalization & evaluation.  │
├─────────────────────────────────────────────────────────────────────────────┤
│ 2. Canonical Action Envelope & Deterministic Hashing                        │
│    Actions are normalized to `tracepermit-canonical-json-v1` and hashed.    │
│    Policies evaluate stable semantic signals rather than raw prompt text.  │
├─────────────────────────────────────────────────────────────────────────────┤
│ 3. Cryptographic Execution Permits                                          │
│    Approved actions receive a single-use JWS permit cryptographically bound │
│    to the exact action hash, subject, target, and policy version.          │
├─────────────────────────────────────────────────────────────────────────────┤
│ 4. Development Endpoint Source Evidence                                     │
│    A Java process observer checks parent stability and executable/TTY facts │
│    before a request-bound Ed25519 source proof. Not managed attestation.    │
├─────────────────────────────────────────────────────────────────────────────┤
│ 5. Safe Simulation & Reconciliation                                         │
│    Stateful AWS/Jira simulators test side-effect behavior before any real   │
│    credentials are introduced. Ambiguous outcomes enter reconciliation.     │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## How TracePermit Fits in Your Stack

In the current POC, TracePermit sits between supported client origins and
simulated AWS/Jira tools. A real enterprise deployment would additionally
need controlled target credentials and anti-bypass egress:

```
[ AI Agent / Developer CLI ]
           │
           ▼
[ Model / Orchestrator ]
           │  (Proposes structured tool call)
           ▼
┌──────────────────────────────────────┐
│       TracePermit Gateway            │
│  1. Authenticate & Verify Attestation│
│  2. Normalize to Canonical JSON      │
│  3. Evaluate Policy & Arguments      │
│  4. Mint Cryptographic Permit        │
└──────────────────────────────────────┘
           │  (Permit + Normalized Action)
           ▼
[ Safe AWS/Jira Simulator ]
```

---

## Next Steps

- **[Local POC quickstart](/guide/getting-started):** Build and run the Java
  gateway, React console, and simulator scenarios from source.
- **[The Security Invariant](/guide/security-invariants):** Deep dive into the formal trust guarantees.
- **[End-to-End Architecture](/guide/architecture):** Explore the complete pipeline from ingress to execution.
