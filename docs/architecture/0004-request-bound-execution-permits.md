# ADR 0004 — Carry a request-bound permit to the executor

- Status: accepted for POC
- Date: 2026-10-08

## Context

The gateway already validates an intercepted action, evaluates policy, and
calls the simulator only after an `allow` decision. That control flow prevents
ordinary bypass inside the gateway, but the executor receives no independently
verifiable evidence of what policy approved. A bug could mutate the action
between evaluation and execution, or reuse an earlier allow decision.

## Decision

After an `allow` decision, the gateway issues a short-lived signed execution
permit. The permit binds:

- a unique single-use permit ID;
- issuer and executor audience;
- authenticated subject, event, and evaluation IDs;
- a SHA-256 hash of the complete canonical intercepted action;
- the allowing policy ID and version;
- issue and expiry times; and
- `maxUses: 1`.

The safe simulator verifies the signature, issuer, audience, lifetime, subject,
event ID, and canonical action hash immediately before execution. It then
atomically consumes the permit ID in a process-local store. Invalid, expired,
wrong-audience, changed, or replayed permits fail closed before the simulated
operation.

The canonical hash uses recursively sorted object keys so equivalent actions
do not depend on JavaScript property insertion order.

## Trust limitation

This POC uses a random process-local HMAC key and an in-memory consumption
store. The issuer and verifier therefore remain in one process and one trust
boundary. Restarting loses replay state and invalidates outstanding permits;
multiple gateway instances would not share consumption state.

A production design should separate the policy signer from executors, prefer
asymmetric or KMS-backed signing, use a durable atomic replay store, define key
rotation and revocation, and retain only non-secret permit evidence. This
permit is an internal execution capability, not a human-approval token or a
replacement for least-privilege AWS/Jira credentials.

## Consequences

- The executor can detect action mutation after policy evaluation.
- A permit issued for one executor cannot be replayed at another executor.
- A consumed permit cannot be used twice within the running POC process.
- Policy failure, permit issuance failure, and permit verification failure all
  prevent execution.
- The AWS adapter remains a local simulator and receives no cloud credential.
