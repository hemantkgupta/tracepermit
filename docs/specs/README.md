# TracePermit specifications

These documents describe the Java/React POC and distinguish implemented
controls from target enterprise requirements. Source and tests determine what
actually runs; a target requirement is not an implementation claim.

| Document                                               | Purpose                                         |
| ------------------------------------------------------ | ----------------------------------------------- |
| [Governance](00-specification-governance.md)           | Requirement language and change rules           |
| [Product requirements](01-product-requirements.md)     | Users, scope, and intended outcomes             |
| [System requirements](02-system-requirements.md)       | Functional, security, and operational IDs       |
| [Current-state baseline](03-current-state-baseline.md) | Implemented, qualified, and future capabilities |
| [High-level design](04-high-level-design.md)           | Components and trust boundaries                 |
| [Domain model](05-domain-and-data-model.md)            | Actions, permits, approvals, and evidence       |
| [Java low-level design](07-low-level-design-java.md)   | Module ownership and enforcement flow           |
| [Interface contracts](08-interface-contracts.md)       | MCP, HTTP, schema, and error boundaries         |
| [Sequence flows](09-sequence-flows.md)                 | Allow, deny, approval, replay, and recovery     |
| [Threat model](10-security-threat-model.md)            | Threats, controls, and residual risks           |
| [Quality and acceptance](11-quality-and-acceptance.md) | Tests and release gates                         |
| [Traceability matrix](12-traceability-matrix.md)       | Requirement-to-code/test status                 |

For a security-boundary change, read the affected specification and
[architecture decision](../architecture/), cite the requirement IDs, update
the traceability matrix, and add positive and negative tests. The
[simulator scenarios](../demo-scenarios/README.md) show qualified POC paths.
No demo path may contact a real destructive target.
