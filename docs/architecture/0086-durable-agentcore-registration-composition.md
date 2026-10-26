# ADR0086: execution-disabled durable AgentCore registration composition

Status: implemented callable Java composition; synthetic three-engine qualification.

Requirements: FR-003/008/011/012; SEC-001/003/006/009/012;
REL-001/002/004; OPS-002/004. Evidence is partial; no whole-ID completion.

## Context

[ADR0078](0078-agentcore-execution-disabled-registration.md) introduced the strict
enrolled-workload ingress. PR111 qualified that ingress with real JDBC registration
but process-local replay. [ADR0080](0080-durable-workload-identity-replay.md) and
PR118 qualified shared replay separately. The missing foundation is a supported
composition of the concrete verifier, ingress and both durable stores.

This branch starts at frozen PR118 `b7d5c1a336bc4a456fa179e9d1ed7c0f63a6d758`.
Integration merge `eb0fdb30a163e4aed36adf54f207b0c939e891ab` adds frozen PR111
`317a1be331b46292699a1e5bbf2e824537a27eb8` as its second parent. Reconciliation
retained both additive specification entries. Producer runtime bytes were not
rewritten, and no GitHub PR or main branch was merged.

## Decision

Add the closed `agentcore-durable-registration` Java library and
`DurableAgentCoreRegistration.create`. The operator supplies the concrete
`OidcAccessTokenVerifier`, one trusted enrollment, clock, two dedicated initialized
datasources, explicit agreed replay capacity and positive finite DML budget.
The factory constructs `JdbcWorkloadIdentityReplayStore`,
`JdbcBusinessOperationRepository` and `AgentCoreRegistrationIngress` internally.
There is no supported replay/repository supplier override, in-memory fallback,
body-verified flag, listener, executable launcher, target or credential client.

Construction validates the existing dedicated stores. It never initializes,
migrates, repairs or combines their inventories. Startup failures, including
unchecked datasource failures, collapse to `DURABLE_REGISTRATION_NOT_READY` with
no cause, suppressed diagnostics or private configuration. Datasources/pools
remain caller-owned. The library creates no background cleanup workers and has
no pool-close lifecycle.

The existing ingress performs bounded strict body parsing, actual token signature
and claim verification, exact operator enrollment and target/profile binding.
Unknown body authority fields remain invalid. The verified credential fingerprint
is consumed under the existing tenant/issuer namespace before registration.
The only result is the existing minimal receipt: `created`, `operationId` and
`executionEnabled=false`. No stored body, credential, approval or permit is returned.

## Four distinct boundaries

1. **Single-use workload identity** records consumption of one exact verified
   credential fingerprint. It is not a provider-jti, human grant or instantaneous
   revocation contract.
2. **Request-key idempotency** selects the original immutable operation within
   its trusted scope. Different valid tokens can return the same original; changed
   semantic content conflicts. New policy/artifact proposals never overwrite it.
3. **Human delegation** needs a separately verified provider grant/exchange
   contract. A second authorization value is always refused, even if both tokens
   are individually valid. Workload-only identity does not establish human OBO.
4. **Authority/admission** is absent. A registration snapshot or receipt cannot
   approve, admit, mint a permit, dispatch, acquire target credentials or execute.

## Two separate commits and uncertainty

The replay transaction commits and acknowledges cleanup first. Only confirmed
replay consumption permits the separate operation registration call. These two
dedicated stores are not a distributed transaction and have no all-or-nothing
atomicity claim.

- Replay refusal, capacity disagreement, unready schema or acknowledgement
  uncertainty prevents registration. A committed replay row may exist even when
  its acknowledgement is unknown; the ingress returns a fixed rejection.
- Once replay is confirmed, a known registration rollback leaves the token spent
  and no partial operation, initial facts or head. Registration commit/cleanup
  uncertainty remains `AGENTCORE_REGISTRATION_UNKNOWN`, even when an independent
  observer sees a complete durable registration.
- The same token cannot retry registration under another request key. Recovery
  requires an explicitly supplied new verified token. The existing repository
  may then return the original by scoped request-key idempotency; that metadata
  lookup grants no permission to repeat a side effect.
- No automatic retry follows either uncertainty. A later metadata-only receipt
  does not revive authority if identity expires during registration storage work.

## Qualification and limits

The [qualification runbook](../runbooks/agentcore-durable-registration-qualification.md)
maps real signed-token, shared replay and registration tests to requirements on
PostgreSQL16.4, MariaDB11.4.13 and MariaDB10.6.25. It distinguishes genuine child-JVM
restart proof from object reconstruction, and includes a deliberately non-durable
test control that fails the persistence oracle. New test ports bind only numeric
127.0.0.1; signing keys and all targets are synthetic.

Finite datasource connection/socket/lock bounds and bounded child/test/cleanup
work are fixture/deployment responsibilities. JDBC statement timeouts are not
total connection, commit or cleanup deadlines. Replay acknowledgement after
expiry fails conservatively; it cannot make an indefinitely stalled driver return.

This is not live AWS AgentCore/Okta interoperability, human OBO, approval/admission,
target execution, older-backup/restore/failover fencing, instant revocation,
production load qualification or production certification. The unchanged stores'
schema checks remain partial physical validation, not DBA-resistant attestation.
Rollback removes this unused composition, preserving existing durable records
and the two stores' explicit operator lifecycle procedures.
