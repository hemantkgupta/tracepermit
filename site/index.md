---
layout: home

hero:
  name: "TracePermit"
  text: "Request-bound approvals for coding agents"
  tagline: "Authenticate a tool request, independently review its exact action, and explicitly resume a safe simulator. Open-source Java/React evaluation preview—not a production gateway."
  actions:
    - theme: brand
      text: Explore the approval workflow
      link: /guide/working-today
    - theme: alt
      text: Try on a clean Mac
      link: /guide/fresh-macbook-evaluation
    - theme: alt
      text: View source on GitHub
      link: https://github.com/hemantkgupta/tracepermit

features:
  - icon: 🛡️
    title: Authenticate before action
    details: Supported requests cross identity, enrollment, scope and policy checks before any simulator dispatch. Client-supplied claims do not create authority.
  - icon: 👤
    title: Independent human review
    details: A distinct authorized reviewer inspects the exact target, arguments, request hash and expiry. Approval is not execution.
  - icon: 📜
    title: Single-use authority
    details: The original requester explicitly resumes. PostgreSQL consumes the exact approval and claims execution before the simulated effect; changed or consumed authority is refused.
  - icon: 🔍
    title: Role-scoped approval history
    details: A separate authenticated React inbox supports requester, reviewer and viewer flows, with bounded filtering and pagination. Provider tokens stay server-side.
  - icon: 🌐
    title: Buildable local package
    details: Package the Java service and built inbox without source build tools on the runtime host. Java 21, local PostgreSQL and operator-owned OIDC configuration are still required.
  - icon: 🧪
    title: Failure-aware controls
    details: Replay and expiry checks, bounded identity/admission resources, uncertain-outcome recording and declared recovery containment have targeted simulator tests.
---

## One exact request, one explicit decision

<div class="approval-preview" aria-label="Approval workflow states">
  <div><strong>1 · Request</strong><span>Pending · not executed</span></div>
  <div><strong>2 · Independent review</strong><span>Approved · still not executed</span></div>
  <div><strong>3 · Explicit resume</strong><span>Claim authority · invoke simulator</span></div>
  <div><strong>4 · Inspect</strong><span>Durable status · recorded outcome</span></div>
</div>

Explore [what works today](/guide/working-today), the
[twelve action and operator flows](/guide/core-flows), and the
[fresh Mac evaluation](/guide/fresh-macbook-evaluation). The named Codex approval
rehearsal and automated browser/PostgreSQL tests are evidence for their stated
profiles, not a promise of every client, IdP or deployment.

## The enforcement boundary

![TracePermit Architecture Overview](/images/tracepermit-architecture.svg)

The model proposes a tool call; TracePermit governs the call at the controlled
MCP boundary. It does not see a hosted model's private reasoning or all of its
network traffic. The current POC has no real AWS or Jira executor and is **not
a production gateway**.

## Runtime visibility and evidence

TracePermit projects evaluated actions, hash-linked local evidence, causal lineage, and deterministic policy decisions into a live read-only console. An independently signed external-receipt foundation is separately tested, not configured in the local demo:

![TracePermit Console Preview](/images/tracepermit-console-concept.png)

## Start with the reproducible local demo

Use the [source quickstart](/guide/getting-started) for Java 21, Node.js 22,
pnpm, and the numbered simulator scenarios. The [deployment status
page](/guide/deployment) distinguishes the local POC from a future production
topology.

TracePermit is released under the [Apache License 2.0](https://github.com/hemantkgupta/tracepermit/blob/main/LICENSE).
