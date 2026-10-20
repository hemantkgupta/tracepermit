# ADR 0064: Verifier-only one-shot simulator executor foundation

- **Status:** Accepted for a simulator-only foundation
- **Date:** 2026-10-20
- **Requirements:** FR-008, FR-009, SEC-003, SEC-004, SEC-005, SEC-006,
  SEC-007, SEC-008, SEC-012, REL-001, REL-004, OPS-002, OPS-005

## Context

`ExecutionPermitAuthority` already supported Ed25519 issuance and public-key-only
verification, while the runnable control-plane compositions still issued permits
and invoked simulators in one JVM. That proves request binding but provides weak
evidence for the intended signer/executor separation. A real target is not yet
authorized: provider credential custody, managed egress and deployment isolation
remain open release gates.

The next safe increment must therefore exercise a separate executor without
adding AWS, Jira or other provider authority and without allowing the execution
request to choose its verifier trust.

## Decision

Add the `executor-simulator` Java module and a one-shot stdio main:

1. Operator-owned, owner-readable configuration supplies the exact Ed25519
   public key, issuer, audience, PostgreSQL connection and simulator route
   allowlist. The request cannot supply or replace any of them. Configuration
   rejects unknown fields, private-key fields, duplicate routes, remote database
   hosts, symlinks, unsafe permissions and path traversal.
2. The process accepts exactly one bounded JSON document containing only
   `schemaVersion`, `permit` and the canonical action. Duplicate keys, trailing
   documents, unknown fields, oversized input and a non-canonical action fail
   closed.
3. The executor constructs `ExecutionPermitAuthority` with only the enrolled
   public key. Its constructor rejects any authority that can sign. Ed25519
   algorithm, key ID, issuer, audience, lifetime, subject, event and canonical
   action hash are verified before dispatch.
4. A PostgreSQL `PostgresPermitConsumptionStore.openExisting` claim commits
   before the simulator call. Independent JVMs share the tenant/permit uniqueness
   boundary, so one concurrent request wins and restart does not restore use.
5. The operator route is checked before consumption and again immediately before
   invocation. Only `StatefulTargetSimulator` is present; the module has no AWS
   SDK, Jira SDK, provider credential or network target client.
6. Output contains bounded correlation/version fields and a canonical result
   hash, never the permit, raw action arguments or raw target result. The hash is
   an integrity/correlation value, not a confidentiality mechanism.
7. The Gradle application distribution contains runtime jars, launcher, example
   configuration, runbook and legal files but no source or signing key.

`--check` validates configuration and exposes only algorithm, key ID/fingerprint,
audience and route count. It does not connect to PostgreSQL or bind a listener.
`--execute` is one request per process and exposes no network listener.

## Transaction and failure boundary

The standalone permit claim is atomic and durable, but it is not the richer
execution-attempt/evidence transaction used by the approval service. A process
crash after claim and before or during invocation consumes authority and may
leave the outcome unknown. It never authorizes a retry. Later integration must
compose a durable attempt, evidence and lookup-only recovery contract without
pre-consuming the same permit.

Configuration failure, malformed input, trust mismatch, replay, database
unavailability and simulator validation fail closed. Public errors contain only
stable codes. An unexpected failure after target dispatch is reported as
`EXECUTION_OUTCOME_UNKNOWN`, not as proof of failure.

## Consequences and explicit limits

- A compromised executor runtime that has only this configuration cannot mint
  an Ed25519 permit from the public key.
- Separate-JVM tests and source-free packaging improve evidence for the software
  boundary, but do not prove OS-user, container, VM or hardware isolation. A
  same-user process may still read files available to that user.
- There is no provider credential to steal in this slice. SEC-007 and SEC-008
  remain partial until a qualified broker/workload identity and deployment egress
  boundary exist.
- The current control-plane launchers do not dispatch to this one-shot runtime;
  integration and durable execution/evidence composition are follow-up work.
- No real target, cloud account, enterprise credential or remote listener is
  introduced. ADR 0056 and all real-target release gates remain in force.
