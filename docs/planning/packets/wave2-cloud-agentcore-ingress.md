# Cloud 2: trusted, execution-disabled AgentCore ingress

Read and obey the [eight-hour shared protocol](wave2-shared-protocol.md).
The operator approved this packet; leave every PR unmerged.

## Starting point

- Environment: `tracepermit-java21-docker` (verify before editing).
- New isolated branch: `codex/agentcore-trusted-ingress`.
- Planning main: `0bc6b618aa9eac7df3bacf7a6d4bcafe2a824c4f`.
- Inspect current GitHub state and branch/path ownership before claiming a lane.
  Do not edit PR105,95,106,107 or the Mac authority branch.

## Ordered acceptance work

1. Read public specifications and merged operation registration, withdrawal and
   binding contracts. Verify current AgentCore/Okta identity facts in official
   primary documentation. Separate documented behavior, assumptions, synthetic
   fixtures and missing live prerequisites. Do not read private planning files.
2. Implement a bounded Java adapter that registers a scoped operation through the
   existing `BusinessOperationRepository`, with execution disabled. A trusted
   adapter derives tenant/workspace/requester after verification and explicit
   operator enrollment. Never accept body-supplied authenticated/delegated/approved
   flags, trusted scope, current policy authority or arbitrary target routing.
3. Preserve exact operator target/profile binding and scoped idempotency semantics;
   reject unsupported fields, changed-content retries, ambiguous/oversized JSON
   and unsupported schemas. Bound work and return fixed, secret-safe errors.
4. Workload identity and human/OBO authority are distinct. Two unrelated valid
   tokens are not delegation. Require a documented verifiable relationship or
   reject that composition and name the exact missing contract. An injected
   context or synthetic JWT is not AgentCore/Okta interoperability evidence.
5. Test positive controls and wrong issuer/audience, expired/replayed identity,
   unrelated token pairs, tenant/workspace substitution, unenrolled subjects,
   changed-content retries, parser bounds and zero execution/credential calls.
6. Run proportionate focused/full gates and exact-head CI; deliver a pushed draft
   PR, the standard handoff, honest limits and explicit ownership release.

Exclusive scope is new AgentCore ingress classes/tests and scoped docs; reserve
ADR0078. Do not change existing identity verifier behavior, canonical v1,
application authority interfaces, persistence, GitHub adapters, shipped launchers
or CI. Reuse existing ports. A missing shared interface is a named dependency,
not permission to edit Mac paths. Do not start a public listener or wire execution.
