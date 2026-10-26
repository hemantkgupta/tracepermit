# ADR 0078: Execution-disabled, enrolled-workload registration ingress

Status: Implemented unwired Java adapter; synthetic qualification only.

Requirements: FR-002/003/010/012; SEC-001/002/003/004/009/012;
REL-001/002/003/005/009; DATA-001; OPS-002/004; PORT-004.
No whole-requirement or production-readiness status promotion.

## Context and primary-source evidence

AgentCore workload identity, a human identity and human delegation are distinct.
Two independently valid tokens cannot establish a relationship between their
subjects, clients, audience, scopes, session and request. The merged registration,
withdrawal and target-binding contracts remain the existing boundaries:
[ADR0070](0070-durable-business-operation-registration.md),
[ADR0072](0072-durable-pending-operation-withdrawal.md) and
[ADR0075](0075-github-business-operation-binding.md).

Official public sources inspected on 25 October 2026 (Asia/Calcutta):

- AWS's [AgentCore Identity quickstart](https://github.com/aws/bedrock-agentcore-starter-toolkit/blob/f5b2ed8b40a13a22b2ec36eebf93317f0aac8ce0/documentation/docs/user-guide/identity/quickstart.md)
  describes outbound OAuth credential providers, user consent, workload callback
  configuration and authorization-URL session binding. Its diagnostic sample's
  unverified JWT decoding is not a resource-server verifier.
- AWS's [IAM JWT quickstart](https://github.com/aws/bedrock-agentcore-starter-toolkit/blob/f5b2ed8b40a13a22b2ec36eebf93317f0aac8ce0/documentation/docs/user-guide/identity/quickstart-aws-jwt.md)
  separately describes AWS-signed STS JWT federation and an explicitly configured
  audience. Such a token is not automatically this application's existing
  `at+jwt` access-token profile or a user's delegated authorization.
- Okta's [OAuth OBO guide](https://github.com/okta/okta-developer-docs/blob/44a82c04f229a4bd4c6b336bcec034c90f0f9297/packages/@okta/vuepress-site/docs/guides/set-up-token-exchange/main/index.md)
  describes authorization-server token exchange, configured service clients,
  resource audiences, policies and scopes; arbitrary token pairing is not exchange.
- Okta's [third-party AI-agent exchange guide](https://github.com/okta/okta-developer-docs/blob/44a82c04f229a4bd4c6b336bcec034c90f0f9297/packages/@okta/vuepress-site/docs/guides/ai-agent-third-party-token-exchange/main/index.md)
  describes agent registration, client authentication, user ID-token/ID-JAG
  exchange and custom authorization-server policy, with feature enablement
  prerequisites. This adapter implements none of that provider exchange.

The official documentation websites were policy-blocked (HTTP403). The sources
above were read from the providers' official public GitHub repositories through
allowed GitHub APIs; network policy and TLS verification were preserved. No
quickstart command, provider exchange or live resource call was executed.

## Decision

Add `AgentCoreRegistrationIngress` only in the remote adapter module. It is a
callable library with no listener, launcher, execution/approval/permit port or
target credential/transport dependency. It registers an initial
`evaluating/not_started/pending` operation through `BusinessOperationRepository`.
Construction of any context or enrollment does not authenticate a caller.

The operator configures one immutable enrollment per instance: exact issuer,
verified issuer-qualified subject, client, mapped tenant, local workspace and
workload requester, exact ADR0075 target/profile, and bounded retention policy.
Deployment routing to an instance is operator-owned and absent from this slice.
No body value selects an enrollment, target, scope, generation or policy release.
The proposed policy/artifact metadata grants no current policy authority.

The adapter accepts the unchanged concrete `OidcAccessTokenVerifier`, never a
caller-supplied authenticated context. Signature/key trust, issuer, audience,
JWT type, dates and provider claim mappings must pass that verifier. It then
checks exact enrollment, tenant and `operations.register` scope, and labels only
the enrolled workload as authenticated. It records a single principal hop and
no human, agent ancestry or delegation. Certificate-bound tokens are refused
because this callable profile has no verified transport-proof input.

Any second authorization value, including an empty value, returns
`AGENTCORE_DELEGATION_CONTRACT_REQUIRED` before verification or persistence.
**Missing contract:** a provider-specific, cryptographically verified grant or
exchange artifact binding the enrolled workload/client to the human subject,
intended resource audience, tenant, narrowed scopes/resources, lifetime and
request/session, with replay and revocation semantics. Neither independent
verification of two tokens nor an asserted user/session ID fills this contract.
This slice cannot accept human/OBO composition; the missing live contract is an
explicit gate, not an inferred delegation rule.

The only body schema is closed `1.0`: `schemaVersion`, `clientRequestKey`,
`title`, and optional nonblank `body`. The adapter derives all trusted canonical
identity, target, IDs, timestamps and lifecycle data itself. There is a 32KiB
byte ceiling checked before cloning/decoding, strict UTF-8, duplicate detection,
trailing-token rejection, depth4/name64/number32/string16384 parser limits,
ASCII request-key length128, title length256 and body length16384. Lone Unicode
surrogates are refused. Unsupported fields and schemas fail before key resolution.
This is a deliberately narrower ingress schema; canonical v1 is unchanged.

The scoped request-key hash uses canonical domain-separated material containing
this ingress profile, the trusted scope fingerprint and the raw client key.
ADR0075 revalidates the generated operation against the exact operator target,
profile, resource/precondition, policy artifacts and generation before insertion.
The repository owns durable idempotency: same semantic content returns the
original registration; changed content conflicts, including changed target
resource/precondition or generation. New IDs, verification evidence and proposed
release metadata cannot overwrite the first registration.

An injected existing `WorkloadIdentityReplayStore` consumes the verified token's
credential fingerprint under its tenant/issuer namespace immediately before
registration. This is single use of an exact token, not a provider `jti`
namespace: the existing verifier deliberately exposes no token ID. A freshly
issued signed token with different bytes is distinct even if an issuer reuses
`jti`. Use a shared fail-closed implementation for multi-instance deployments;
the tested bounded in-memory store is process-local and loses replay state on
restart. Never infer durable provider replay protection from this test fixture.
The token must remain valid at consumption. Metadata registration conveys no
authority after expiry, including while a repository call waits.

A failed/uncertain repository attempt burns that token; an explicit registration
retry needs a fresh verified token and the same scoped key. No automatic retry
occurs. Commit uncertainty remains `AGENTCORE_REGISTRATION_UNKNOWN`. Repository
key conflict has its own fixed code; all other storage exceptions become a fixed
unavailable code. Errors retain no cause, suppressed diagnostics, token or body.
Receipts contain only creation status, the repository-returned operation ID and
`executionEnabled=false`; arbitrary stored snapshots are not public responses.

## Qualification and limits

[The runbook](../runbooks/agentcore-trusted-ingress.md) records test evidence and
commands. Local RSA/JWT fixtures prove only this closed adapter's behavior;
verification-key resolution is synthetic. The recording repository checks the
port boundary and error mapping, and does not establish database durability.
The unchanged three-engine repository suite independently qualifies its real
fixtures. No composed ingress-to-JDBC fixture is introduced by this slice.

Live AgentCore/Okta claim compatibility is unqualified. In particular, the
existing strict `at+jwt`/RS256 profile, HTTPS audience, `client_id`, tenant mapping
and scope shape may differ from either provider's actual tokens; existing
identity behavior is unchanged. No AWS STS verifier, token exchange, dynamic
trust discovery or account provisioning is added.

No public remote boundary, sender proof, durable replay deployment, authority
transaction, withdrawal API, execution, target credential acquisition, GitHub
call, external evidence publication, RDS/EKS integration or production readiness
is claimed. The callable work is bounded by parser/token/store limits; key
resolution and repository connection/deadline configuration remain operator
responsibilities. Rollback removes the unused ingress adapter, retaining existing
operation records and repository semantics for explicit operator review.
