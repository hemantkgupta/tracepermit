# ADR 0024: Project demo visibility only after enforcement

- **Status:** Accepted
- **Date:** 2026-10-10
- **Requirements:** FR-001, FR-010, SEC-003, SEC-008, SEC-012, REL-009,
  PERF-001, OPS-001, OPS-004

## Context

The Java control-plane console originally loaded only deterministic seed data.
Live MCP actions used the real Java enforcement path but did not appear in the
browser, which weakened the demonstration and tempted a dangerous alternative:
writing UI state before the decision was final.

The console is a read-only visibility surface, not part of the synchronous
security decision. Projection availability must never decide whether a target
operation is authorized or cause a target retry.

## Decision

The Java MCP dispatcher emits a bounded notification only after the enforcer
returns an `ExecutionResult`. The development composition installs an observer
that derives action, evidence-integrity, lineage, policy-count, and latency
views from the already-normalized event and final result.

Observer exceptions are isolated. They cannot alter the returned decision,
invoke the target, or retry the action. Requests rejected before enforcement,
including unknown tools, are not inserted into the policy-evaluation feed.
The React console polls the loopback read API every two seconds.

The one-command demo launcher generates all signing material ephemerally,
strips common real-target credentials, validates fixed loopback ports and
runtime versions, and removes its exact temporary secrets during shutdown.

## Consequences

- The browser visibly follows real web-profile and endpoint-attested CLI
  enforcement without entering the execution path.
- Console failure may create a visibility gap but cannot create authorization
  or duplicate a target effect.
- Live projection is process-local and is reset on restart; durable evidence
  remains a separate production concern.
- The displayed local latency sample is diagnostic evidence, not a production
  SLO claim.
- Boundary rejections require separate bounded security telemetry rather than
  being mislabeled as policy decisions.

## Rejected alternatives

- **Write the feed before policy evaluation:** could display actions that were
  never decisions and risks coupling visibility to authorization.
- **Make console persistence mandatory for allow:** turns a read model into an
  availability dependency without improving target safety.
- **Project unknown tools as blocked decisions:** confuses schema/registration
  rejection with semantic policy enforcement.
- **Put tokens or private keys in the demo session file:** creates avoidable
  reusable credential exposure.
