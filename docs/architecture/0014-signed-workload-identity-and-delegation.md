# ADR 0014: Signed workload identity and monotonic delegation

**Status:** Accepted and implemented as a Java in-process foundation
**Date:** 2026-10-09

## Context

A scheduled action has no live human session. `createdBy`, a schedule ID, a
client-provided `subject`, and a structurally valid lineage graph explain
provenance, but none proves that a scheduler authenticated a workload or that
the workload delegated bounded authority to an agent or tool. Treating those
fields as authority would let the request mint its own principal and grant.

## Decision

TracePermit uses two strict compact Ed25519 credentials over
`tracepermit-canonical-json-v1`:

- `tracepermit-workload-identity+jws` binds scheduler issuer/key, tenant,
  workload subject, scheduler/schedule/run, event, trace, gateway audience,
  canonical action hash, token ID, issue time, and expiry;
- `tracepermit-delegation-grant+jws` binds issuer/key, tenant, token and parent
  token/hash, actor, subject, audience, scopes, resources, purpose, trace, issue
  time, and expiry.

Public keys come only from an operator-controlled registry keyed by issuer and
key ID. Each entry also binds tenant, signing principal, permitted usage, key
state, and an Ed25519 public key. The request cannot provide a key, trust state,
verification result, tenant, or required authority mapping.

The scheduled adapter verifies the workload credential and the complete
delegation chain before constructing the canonical action. The chain must have
one root, no branches, duplicate IDs, cycles, or disconnected grants. The root
actor is the authenticated workload. Every child actor is its parent subject,
its parent hash covers the exact compact parent credential, and its scope,
resource, audience, and lifetime never broaden the parent. The leaf must contain
the adapter-mapped exact scope and resource.

Workload credentials live for at most 300 seconds and are atomically consumed
once. Delegations live for at most 900 seconds and the chain has at most eight
hops. Revoked credentials/keys and unavailable revocation state fail closed.
Unknown format versions, types, algorithms, issuers, keys, audiences, fields,
and trust states also fail closed. A trusted action mapping may explicitly allow
direct workload execution; otherwise a complete delegation is mandatory.

Only non-secret fingerprints and verified grant projections enter the canonical
action. Compact credentials, signatures, private keys, and raw trust-registry
material do not.

## Consequences

- `createdBy` remains provenance and cannot authorize a scheduled run.
- A scheduler can authenticate a workload without granting it unlimited target
  authority.
- Each delegating signer is bound to the grant actor, preventing a valid but
  unrelated key from signing on another principal's behalf.
- Action, tenant, schedule, run, event, trace, audience, scope, resource,
  parent, time, revocation, and replay substitutions fail before policy or
  dispatch.
- Language-neutral schemas and a public-key-only golden vector preserve the
  signed contract; Java owns the verifier and trusted adapter.

## Rejected alternatives

- Trust scheduler body fields or `verification=verified`: self-assertion is not
  authentication.
- Let each request carry its verification key: an attacker could sign its own
  authority.
- Authenticate only the scheduler connection: that does not bind the exact run
  and action or prove downstream delegation.
- Validate only the leaf grant: omitted or widened parents would be invisible.
- Store compact credentials for audit: fingerprints and bounded claims are
  sufficient evidence and avoid retaining reusable bearer material.

## Limitations

This is an in-process Java foundation with in-memory replay and revocation
adapters. It has no network route, real scheduler, durable registry, distributed
replay store, production key rotation service, HSM/KMS signer, real AWS/Jira
credential, or remote dispatch. Existing web connector trust semantics are
unchanged.
