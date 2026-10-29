# ADR0096: source-free organization-profile synthetic operator

- Status: implemented candidate; validation pending
- Date: 2026-10-28
- Requirements: FR-001/008/009/012/015; SEC-001/002/003/006/007/008/012/019;
  REL-001/002/004/005/008; OPS-002/004/005. Partial evidence only.

## Context

The accepted organization-profile libraries verify Okta requester/reviewer
authority, pin one GitHub organization draft-PR target and preserve the durable
registration/review/permit/admission/delivery chain. Their published three-engine
candidate is locally qualified, but it has no installed source-free composition.
Track E's installed lifecycle intentionally has a different profile and no
registration command. Extending that executable would change an already-approved
protocol and its hashes.

The approved new slice must remain synthetic and execution-disabled. It cannot
introduce provider credentials, remote ingress, an identity callback, a generic
target port, migration authority or retry after durable uncertainty.

## Decision

Add the independent Java 21 application
`agentcore-organization-lifecycle-cli`, launched as
`tracepermit-synthetic-organization-operator`, with profile
`tracepermit-installed-github-org-synthetic-operator-v1`. Its exact commands are
`check-config`, `register`, `inspect`, `evaluate`, `review`,
`deliver-synthetic` and `reconcile`. A Java 8 bootstrap refuses unsupported
runtimes before loading Java 21 classes. Parent and worker both require an
inherited anonymous input pipe, and the parent clears the worker environment.

The root configuration is closed over `schemaVersion`, `profile`, distinct
numeric-loopback `replay` and `operations` stores, fixed replay capacity/budget,
scope, organization target, authority, separate requester/reviewer Okta
providers, exact requester enrollment and independent reviewer memberships.
Only PostgreSQL16.4 is accepted initially. Configuration declares pins; it does
not itself establish authority.

All expected frames must arrive and EOF must be observed before parsing,
authentication, JDBC, key parsing, signing or effects. Compact JWT frames are
validated explicitly and exactly one `Bearer ` prefix is constructed at the
trusted adapter. Registration delegates to the existing organization ingress.
Lifecycle commands use `OktaOperationAuthority`, the sealed organization
profile, existing JDBC repositories and only `SyntheticGitHubDeliveryTarget`.
Delivery proves the private Ed25519 key matches the configured public key before
issuing the internal permit. Reconciliation is read-only and grants no retry.

Add only the narrow
`DurableAgentCoreRegistration.organization(...)` factory. It validates supplied
existing replay and operation stores and composes
`AgentCoreRegistrationIngress.organization(...)`; it performs no DDL, migration,
activation, grant change or repair. Existing constructors and Track E bytes are
unchanged, and no library depends back on the executable.

Receipts form one exact command/outcome union. Registration alone exposes
`created` and `operationId`; lifecycle success exposes only operation status,
dispatch-started and profile fingerprints. Failures and `check-config` expose no
operation fields. Every receipt says `executionEnabled:false` and
`automaticRetryAllowed:false`. `DELIVERY_UNKNOWN` is a known durable synthetic
outcome; generic `UNKNOWN` is uncertain command completion. Neither permits
resend.

## Consequences and limits

The candidate can be manually qualified from an extracted archive without a
source checkout or live target. The command limit is 60 seconds, with five
seconds reserved for owned cleanup and one second for its receipt. Frame limits
are 65,536 configuration, 16,384 JWT, 32,768 registration, 128 private-key,
1,024 command and 2,048 receipt bytes.

This decision does not approve GitHub App/private-key custody, Okta login or
token minting, public/remote ingress, mTLS, EKS deployment, a live GitHub call,
live reconciliation, external exactly-once delivery or production readiness.
EKS containment remains the Mac-owned lane; installed COMMIT-loss and artifact
verification remain separately owned. See the
[manual runbook](../runbooks/source-free-organization-profile-synthetic-operator.md)
and [learning note](../learning/organization-synthetic-receipt-is-not-github-delivery.md).
