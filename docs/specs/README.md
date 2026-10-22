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

Proposed product work is separate from the current implementation index. The
[pending-request cancellation contract](../design/pending-approval-cancellation.md)
and [ADR 0058](../architecture/0058-pending-approval-cancellation-contract.md)
define the proposed withdrawal feature. [ADR 0059](../architecture/0059-explicit-v9-v10-simulator-upgrade.md)
implements its storage/consumer compatibility and explicit local upgrade
prerequisite. [ADR 0060](../architecture/0060-authenticated-pending-request-withdrawal.md)
adds the Java pending-only withdrawal API; [ADR 0061](../architecture/0061-requester-withdrawal-inbox.md)
adds explicit owner withdrawal in the local React inbox. Source-free installed
withdrawal qualification remains separate.

[ADR 0062](../architecture/0062-loopback-container-install-profile.md) and the
[container runbook](../runbooks/container-install-profile.md) define the Linux
numeric-loopback container install foundation. It is partial TP-MVP-05 evidence,
not a public-edge, external-IdP or production-readiness claim.

[ADR0063](../architecture/0063-explicit-public-browser-code-pkce.md) adds the
explicit native/public browser candidate. Synthetic tests do not close the
[pending live Auth0 gate](../runbooks/auth0-native-oidc-qualification.md).

[ADR0064](../architecture/0064-unsigned-approval-review-export.md) adds the
authenticated per-request metadata export. It is unsigned and intentionally
incomplete; console download, standalone checking and live-provider qualification
have separate gates in the [delivery plan](../planning/audit-ready-approval-workspace.md).
