# ADR 0017: Remote MCP invocation crosses the complete Java enforcement path

- **Status:** Accepted; Java simulator path implemented
- **Date:** 2026-10-10
- **Requirements:** FR-001, FR-002, FR-003, FR-004, FR-005, FR-006, FR-008,
  FR-009, FR-010, FR-012, FR-014, SEC-001, SEC-002, SEC-004, SEC-005,
  SEC-006, SEC-008, SEC-012, REL-001, REL-004, OPS-002, PORT-004

## Context

The remote OAuth transport implementation authenticates a remote MCP connection before parsing and supports
handshake/discovery, but deliberately rejects `tools/call`. Wiring a call
directly from that authenticated transport to the simulator would confuse
identity with action authority and bypass classification, policy, request
binding, replay protection, and evidence.

Remote arguments are attacker-controlled even after the caller is
authenticated. A tool name, target, account, identity, approval flag, or
claimed policy result in those arguments cannot become trusted configuration.

## Decision

Add an `ActionEnforcer` application port and an `EnforcingMcpDispatcher` in the
remote adapter. The adapter validates one exact tool schema, independently
requires invocation scope, and constructs identity, target, trigger, and
lineage from verified connector context plus fixed registration. It then calls
`SecureExecutionService`; it never receives a direct simulator reference.
Only operator-mapped web origins are accepted by this composition; CLI retains
its independent endpoint source-attestation boundary.

The application revalidates the canonical action, evaluates policy, issues an
execution permit only for `allow`, verifies and consumes that permit before
dispatch, and records bounded evidence. A known destructive simulated tool
reaches policy and is blocked. An unknown tool stops at the protocol boundary.

Retain `HandshakeOnlyMcpDispatcher` as the explicit no-execution
composition. Enabling a network server therefore requires an operator to
choose and inject the enforcing composition; no deployable production main is
added implicitly.

## Consequences

- Authentication, discovery, invocation scope, policy, and execution authority
  remain separate gates.
- Arguments cannot redirect execution or self-assert identity, target,
  approval, or policy state.
- The simulator independently enforces its operation and simulated-account
  allowlists after permit verification.
- Call outcomes can be returned to the authenticated caller without copying
  arguments or target results into the evidence ledger.
- Each call currently receives a new event ID; transport retry deduplication is
  not implied by single-use permit consumption.

## Rejected alternatives

- **Call the simulator from the MCP dispatcher:** bypasses policy and the
  executor-verifiable capability boundary.
- **Treat OAuth scope as the policy decision:** scope is coarse connector
  authority and cannot bind the exact action, policy version, or target.
- **Accept a canonical action in tool arguments:** permits identity/target and
  trusted-state injection.
- **Advertise only the allowed read and omit the destructive simulation:**
  would not prove that a known effectful request reaches policy and is stopped
  before execution.
- **Wire the remote adapter to PostgreSQL in this slice:** combines transport
  and deployment/persistence decisions and still would not supply production
  certificate, tenant, or operational controls.

## Limitations

The remote runtime is not packaged for deployment. OAuth Bearer tokens are not
sender-constrained, replay/evidence are process-local in this composition, and
there is no rate limit, production tenant administration, external evidence
anchor, retry idempotency, live connector qualification, anti-bypass network
control, target credential, or real target.

ADR 0018 adds a separate hardened mTLS/rate/readiness/durable-state test
composition. It does not silently change this explicit enforcement foundation
or supply the missing production services.
