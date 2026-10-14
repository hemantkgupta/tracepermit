---
layout: home

hero:
  name: "TracePermit"
  text: "Agentic Visibility & Control"
  tagline: "A Java proof of concept that attributes and evaluates AI-agent tool actions before safe, simulated execution."
  image:
    src: /logo.svg
    alt: TracePermit Logo
  actions:
    - theme: brand
      text: Run the local demo
      link: /guide/getting-started
    - theme: alt
      text: Explore the architecture
      link: /guide/architecture
    - theme: alt
      text: View source on GitHub
      link: https://github.com/hemantkgupta/tracepermit

features:
  - icon: 🛡️
    title: Pre-execution mediation
    details: Supported simulator actions cross authentication, normalization, intent and risk classification, policy, and permit checks before dispatch.
  - icon: 👤
    title: Identity with provenance
    details: Separate a verified human or workload subject from the agent actor and the evidence available at each origin boundary.
  - icon: 📜
    title: Request-bound decisions
    details: Bind decisions and approvals to the exact normalized action; reject changed requests and reused permits.
  - icon: 🔍
    title: Endpoint attestation
    details: The Java stdio connector observes local process facts and signs a request-bound proof in the qualified CLI profile.
  - icon: 🌐
    title: Hosted-client path
    details: One ChatGPT + Auth0 + private-tunnel composition was qualified with OAuth attribution and simulated allow/block actions. Other clients remain to be qualified.
  - icon: 🧪
    title: Safe evidence
    details: Stateful AWS and Jira simulators, bounded evidence, and a read-only React console support repeatable demos without real target credentials.
---

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
