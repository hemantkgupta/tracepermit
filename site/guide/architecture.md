---
title: End-to-end architecture
description: The implemented POC data flow, trust boundaries, and production limits.
---

# End-to-end architecture

TracePermit is an Agentic Visibility & Control (AVC) proof of concept. Java is
the sole supported backend; React provides a read-only console. Every
**supported simulated side effect** is evaluated before simulator execution.

## End-to-end architecture diagram

![TracePermit End-to-End Architecture Flow](/images/end-to-end-flow-diagram.png)

The model sends its proposed tool call through the client/provider integration;
TracePermit does not intercept the model's private reasoning before the model
service runs. At the controlled MCP boundary, a trusted adapter verifies
credentials and derives subject, actor, tenant, trigger, and provenance. Tool
arguments cannot declare themselves authenticated or approved.

## Data flow and lifecycle stages

![TracePermit Data Flow Lifecycle](/images/tracepermit-data-flow.svg)

## Origin-specific attribution

| Origin                    | Trusted evidence in the POC                                                                            | Limit                                                                    |
| ------------------------- | ------------------------------------------------------------------------------------------------------ | ------------------------------------------------------------------------ |
| Local stdio connector     | Independently observed process facts, signed proof bound to the MCP request and credential fingerprint | Development enrollment and replay state are not a managed fleet solution |
| Hosted ChatGPT via tunnel | Verified OAuth access token identifies an issuer-qualified human; hosted client is the actor           | Qualified for one Auth0/ChatGPT/tunnel simulator composition only        |
| Signed scheduled workload | Operator-trusted workload signature and narrowed delegation chain                                      | In-process adapter, not a production scheduler route                     |

Causal lineage answers _what led to a call_. Authority delegation answers
_what a signer authorized the next actor to do_. They are never conflated.

## Action and execution boundary

The current action contract is
[`contracts/json-schema/intercepted-action-v1.schema.json`](https://github.com/hemantkgupta/tracepermit/blob/main/contracts/json-schema/intercepted-action-v1.schema.json).
`tracepermit-canonical-json-v1` defines deterministic serialization for
hash-bound proofs; it is **not** a CloudEvents envelope. The Java gateway
classifies the normalized action and its gateway-owned sequence history,
evaluates policy, and issues a request-bound permit only for an allowed
effective action. A sanitizer creates a distinct derived action and sends it
through policy again. Approval is exact-request bound and requires an
independent authorized reviewer in the durable profile.

The durable PostgreSQL profile claims permits and records attempts before
simulator I/O. When the outcome is uncertain, read-only reconciliation can
append a confirmed result; the effect is not blindly retried. This does **not**
guarantee exactly-once effects in an independent real target.

For implementation details and explicit production gaps, read the
[high-level design](https://github.com/hemantkgupta/tracepermit/blob/main/docs/specs/04-high-level-design.md)
and [capability matrix](https://github.com/hemantkgupta/tracepermit/blob/main/docs/specs/03-current-state-baseline.md).
