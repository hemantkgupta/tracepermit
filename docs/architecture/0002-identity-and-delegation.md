# ADR 0002 — Separate identity, causal lineage, and delegated authority

- Status: accepted; structural model and Java signed-chain foundation implemented
- Date: 2026-10-09

## Context

An agent action can involve a human subject, a workload acting for that subject, an external orchestrator, one or more sub-agents, a tool server, and a target credential. A single `userId` cannot represent these relationships or their different assurance levels.

Causal process lineage and delegated authority are also different claims. Observing that ChatGPT called an MCP tool establishes a causal hop; it does not prove that ChatGPT received a cryptographically bounded delegation token.

## Decision

The canonical action separates:

- `identity.subject`: the security subject on whose behalf policy is evaluated;
- `identity.actor`: the immediate service, orchestrator, or agent acting for the subject;
- `identity.createdBy`: historical ownership for scheduled workloads, not the current executor;
- `identity.evidence`: fingerprints and verification metadata, never reusable bearer credentials;
- `lineage.hops`: causal observations with explicit assurance;
- `lineage.delegations`: verified, scoped authority grants linked by parent token ID.

The validator rejects dangling or cyclic causal graphs, expired grants, unverified grants, scope or resource amplification, child grants that outlive their parent, and delegated hops without a known authority grant.

## Trust limitation

Schema validation alone proves structural consistency, not cryptographic
authenticity. The signed-workload adapter
verifies Ed25519 workload/delegation credentials against operator-owned trust,
revocation, and replay inputs before constructing canonical identity and
lineage. The generic `/v1/evaluate` and `/v1/execute` payload remains a trusted
internal contract; an arbitrary external caller must never self-assert
`verification: verified`.

## Consequences

- Human, service, and scheduled actions remain distinguishable.
- A job creator is not mislabeled as the person who actively ran a scheduled execution.
- Causal information from an unmodified web client can be retained without overstating it as delegated authority.
- Policy can use verified subject/actor context while preserving lower-assurance client metadata for investigation.
- Signed scheduler/delegation credentials reuse the same parent-child
  constraints; see ADR 0014.
