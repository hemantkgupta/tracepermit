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

## From tool proposal to controlled execution

The model proposes an action; it does not grant authority. This overview shows
the control points across supported profiles—not one launcher that combines
every identity, approval, and recovery feature.

<div class="intro-visual" aria-hidden="true">
  <img src="/images/intro-request-flow.svg" alt="" width="880" height="1220" />
</div>

[Open the request-flow SVG at full size](/images/intro-request-flow.svg)

<ol class="intro-text" aria-label="Request flow text version">
  <li><strong>Propose and verify.</strong> A supported client submits a structured tool call. The Java gateway verifies the configured identity and, where required, request-bound source or workload proofs. Local endpoint, hosted OAuth, and signed-workload profiles are distinct.</li>
  <li><strong>Normalize and evaluate.</strong> Java creates the canonical action and evaluates its arguments, target, policy version, and server-owned sequence context.</li>
  <li><strong>Follow the decision.</strong> Block means no dispatch. Sanitize creates a narrower action for full re-evaluation. Require approval means pending, not executed. In the durable approval profile, an independent reviewer approves an exact request; the original requester must explicitly resume it before expiry, with current policy checked again.</li>
  <li><strong>Verify before effect.</strong> An effective allow can produce a request-bound execution permit. The execution boundary checks its signature, audience, hash and time, and claims one-use authority before simulator dispatch. The PostgreSQL profile atomically claims durable approval, permit and attempt state; the default local path uses process-local state.</li>
  <li><strong>Record what is known.</strong> Bounded, correlated evidence distinguishes policy decisions from execution outcomes. An unknown outcome is not success and does not authorize a retry. Lookup-only reconciliation is a separately tested durable foundation, not an automatically running recovery service.</li>
</ol>

::: warning Simulator-only, not a universal interceptor
Current executable targets are simulators. The local endpoint connector connects
to a backend on the same laptop; the website is documentation, not a hosted
backend. Real enterprise deployment additionally needs controlled target
credentials and anti-bypass egress. A source proof is not action authorization.
:::

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

## Core Pillars of TracePermit

<div class="intro-visual" aria-hidden="true">
  <img src="/images/intro-core-pillars.svg" alt="" width="880" height="1000" loading="lazy" />
</div>

[Open the core-pillars SVG at full size](/images/intro-core-pillars.svg)

<ol class="intro-text" aria-label="Core pillars text version">
  <li><strong>Complete mediation within supported paths.</strong> Every simulated side effect crosses an enforcement point. This does not intercept arbitrary tools or prevent whole-host credential bypass.</li>
  <li><strong>Canonical actions.</strong> Java normalizes a versioned ActionEnvelope using <code>tracepermit-canonical-json-v1</code> and SHA-256. Policy and permits bind to the exact structured action, not model assurances.</li>
  <li><strong>Request-bound execution permits.</strong> Effective allow is distinct from pending approval. The execution boundary verifies a short-lived signed permit and claims one-use authority before effect. Durability depends on the selected store/profile.</li>
  <li><strong>Development endpoint source evidence.</strong> The local Java connector observes process and terminal facts and signs a request-bound Ed25519 source proof. It is not managed-device or hardware-backed attestation, or proof of human approval.</li>
  <li><strong>Safe simulation and honest outcomes.</strong> AWS/Jira targets are simulated. Durable unknown-outcome handling and lookup-only reconciliation have separate tests; there is no automatic worker in the browser launcher or exactly-once external-effect guarantee.</li>
</ol>

For profile-specific wiring and limits, see the
[end-to-end architecture](/guide/architecture),
[local endpoint connector](/guide/endpoint-connector), and
[developer flow guide](/guide/core-flows).

## Next Steps

- **[What works today](/guide/working-today):** Choose a qualified simulator
  profile and understand its limits.
- **[Fresh MacBook evaluation](/guide/fresh-macbook-evaluation):** Follow the
  clean-machine source walkthrough or the separate operator-package path.
- **[Local POC quickstart](/guide/getting-started):** Build and run the Java
  gateway, React console, and simulator scenarios from source.
- **[The Security Invariant](/guide/security-invariants):** Deep dive into the formal trust guarantees.
- **[End-to-End Architecture](/guide/architecture):** Explore the complete pipeline from ingress to execution.
