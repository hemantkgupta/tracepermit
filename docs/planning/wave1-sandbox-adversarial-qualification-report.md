# Wave 1 Customer-Sandbox Foundations: Independent Adversarial Qualification Report

## Executive Summary & Baseline

- **Qualification Scope**: Independent qualification of customer-sandbox foundations, operation registration and withdrawal lifecycle, exact GitHub target binding, target preflight, MariaDB authority transaction design, and GitHub App installation credential provider.
- **Repository**: `https://github.com/hemantkgupta/tracepermit`
- **Frozen Baseline**: Commit `0bc6b618aa9eac7df3bacf7a6d4bcafe2a824c4f` (`origin/main`)
- **Audit Branch**: `codex/sandbox-adversarial-qualification`
- **Audit Date**: 2026-10-25
- **Evaluator**: Independent qualification lane (Antigravity subscription-backed, zero billed API fallback)
- **External Dependency Reviews**:
  - **PR #101** (commit `6b7de90` / ADR 0072): `JdbcBusinessOperationLifecycleRepository` (merged into baseline `0bc6b618aa9eac7df3bacf7a6d4bcafe2a824c4f`).
  - **PR #104** (commit `1650c4b` / ADR 0075): `GitHubBusinessOperationBinding` (merged into baseline `0bc6b618aa9eac7df3bacf7a6d4bcafe2a824c4f`).
  - **PR #105** (head `9405c0ee476b28322a5930a5a88a2d5032abc6b3` / ADR 0076): `GitHubInstallationCredentialProvider` (`codex/github-app-installation-credentials`).
  - **PR #107** (head `ddaefdbe573d9083d3d16a80a31aca9e7e96a584` / ADR 0077): `docs/design/mariadb-authority-transaction.md` (`codex/mariadb-authority-transaction-design`).

> [!CAUTION]
> **Production Boundary Disclaimer**: Static code inspections, schema verifications, in-memory simulations, and mock-transport suites (`MockWebServer`) do **NOT** constitute live target, customer-network, or production qualification. No live GitHub API calls, AWS infrastructure, or customer pilot sandboxes were accessed. Real target pilot operations require isolated, operator-supervised sandbox testing under production network conditions.

---

## Phase A: Adversarial Boundary Analysis

Ten core adversarial threat vectors were evaluated across the integration perimeter:

### 1. Scope and Rehashed Request Substitution

- **Threat**: An attacker or rogue workload submits a registration or execution permit using altered action arguments (e.g. changing PR title, base branch, or target repository) while re-using an existing client request key or hash, attempting to execute an unauthorized payload under an approved key.
- **Enforcement**:
  - `GitHubBusinessOperationBinding` computes an immutable `operationSnapshotHash` incorporating exact canonical target parameters (owner, repository, base, head, title, draft status).
  - Any parameter variation yields a disjoint `operationSnapshotHash`.
  - Re-submitting an altered operation under an existing client request key triggers `OPERATION_REQUEST_KEY_CONFLICT`.
  - Submitting an execution permit with a mismatched snapshot hash triggers `ExecutionPermitException: permit_request_hash_mismatch`.

### 2. Caller-Authored Approval and State Labels

- **Threat**: The caller injects synthetic approval state (`"state": "approved"`, `"evaluation": "permitted"`, `"approvedBy": "operator@domain.com"`) into the submitted request body or operation document.
- **Enforcement**:
  - Stored operation documents in `JdbcBusinessOperationLifecycleRepository` treat all payload fields as untrusted client data.
  - Lifecycle state transitions are driven strictly by trusted adapter contexts and verified database rows (`storeTime`, row versioning, and explicit state transitions).
  - Preflight and binding logic in `GitHubBusinessOperationBinding` ignores caller-provided state fields and validates only the raw schema and target profile.

### 3. Stale Recovery Generation, Policy Version, and Snapshot Mismatch

- **Threat**: An operator rolls back or invalidates a policy generation, but a pending operation attempt claims validity against an older generation or a stale snapshot hash.
- **Enforcement**:
  - `BusinessOperationTransitionPlanner` produces an explicit `StateToken` containing `recoveryGeneration`, `lastSequence`, and `snapshotHash`.
  - Transition operations (`withdraw`, `claim`, `execute`) require the token to match current database state under row lock (`FOR UPDATE`).
  - Any generation disparity or hash divergence immediately aborts the transaction with `OPERATION_STATE_STALE` without writing partial state.

### 4. Expiry After Lock Waits Evaluated at Store Time

- **Threat**: A client submits a request with 1 second remaining before token expiry; database lock contention delays processing by 2 seconds. The server attempts to commit the operation using the submission timestamp rather than the lock-acquisition timestamp.
- **Enforcement**:
  - `BusinessOperationWithdrawal.requireAuthorizedAt(storeTime)` strictly re-samples the authoritative database clock (`storeTime`) **after** acquiring the row lock.
  - If `storeTime.isAfter(authorityExpiresAt)` or `storeTime.equals(authorityExpiresAt)`, the transaction fails closed with `OPERATION_AUTHENTICATION_EXPIRED`.
  - Exact boundary equality (`storeTime.equals(authorityExpiresAt)`) is treated as expired, preventing zero-second race windows.

### 5. Duplicate Admission, Single-Use Permits, and Replay Rejection

- **Threat**: A valid execution permit is replayed against the target adapter to trigger a second pull request creation.
- **Enforcement**:
  - `ExecutionPermitAuthority` and `InMemoryPermitConsumptionStore` enforce single-use consumption. A consumed permit token cannot be consumed again (`ExecutionPermitException: The single-use execution permit has already been consumed`).
  - When replaying with a freshly issued permit for the same idempotent action, `InMemoryExecutionLedger` matches the idempotency key and returns the cached outcome with zero network calls dispatched.

### 6. Partial Transactional Failure and Malformed Confirmations

- **Threat**: Target returns truncated, unparseable, or schema-violating confirmation payloads during PR creation, or network disconnection occurs during response parsing.
- **Enforcement**:
  - `GitHubDraftPullRequestAdapter` parses target responses through strict Jackson mapping requiring non-null PR number, HTML URL, and node ID.
  - Any truncated or malformed payload fails closed, transition to `FAILED` with code `TARGET_PREFLIGHT_OR_IDENTITY_REJECTED` or `INVALID_TARGET_CONFIRMATION`, and records diagnostic evidence without publishing an execution success.

### 7. Changed Remote Ref or Commit SHA Preflight Invalidation

- **Threat**: A branch ref (`refs/heads/feature-branch`) is updated or deleted on the remote repository between the time the operation is approved and the time execution is attempted.
- **Enforcement**:
  - `GitHubPilotTargetPreflight` resolves head ref existence and expected commit SHA prior to dispatch.
  - If the commit SHA changed or the ref does not exist, preflight aborts with `TARGET_PREFLIGHT_OR_IDENTITY_REJECTED` and prevents dispatch.

### 8. Uncertain Target Outcome Fencing (Reconcile by Lookup, Never Re-POST)

- **Threat**: Target server returns HTTP 500 or network connection resets after receiving the POST request. A retry attempt issues a duplicate POST, creating duplicate PRs.
- **Enforcement**:
  - On timeout or HTTP 500, `GitHubDraftPullRequestAdapter` records the attempt as `dispatched = true` and `state = unknown` (`EXECUTION_OUTCOME_UNKNOWN`).
  - Subsequent execution calls for that attempt are blocked immediately with zero network requests.
  - `GitHubPullRequestOutcomeResolver.lookup()` inspects open pull requests. If zero or multiple matches are found, it returns `unknown("LOOKUP_AMBIGUOUS_OR_ABSENT")`, refusing to assume absence and strictly forbidding a second POST.

### 9. Unrelated Workload Identity and Audience Mismatch

- **Threat**: A valid permit issued for workload `service-a` in tenant `tenant-1` is presented by workload `service-b` or under tenant `tenant-2`.
- **Enforcement**:
  - Permit tokens encode audience (`aud`), tenant (`tenantId`), and authorized executor principal.
  - Target adapter verifies caller context against permit claims. Any audience or executor mismatch throws `ExecutionPermitException: permit_audience_mismatch` or `permit_executor_mismatch`.

### 10. Credential and Sensitive Argument Redaction in Evidence and Diagnostics

- **Threat**: Private keys, GitHub App installation tokens, authorization headers, or sensitive operation arguments leak into log files, exception messages, or evidence records.
- **Enforcement**:
  - `GitHubAppCredentials` and `GitHubInstallationCredentialProvider` redact installation tokens in `toString()` (`[REDACTED]`).
  - Private key files are strictly checked for POSIX permissions (`0600`/`0400`) and never output to diagnostic streams.
  - `BoundOperationData.redactedRepresentation()` strips sensitive parameter blocks, leaving only cryptographic digests and safe metadata.

---

## Phase B: Permanent Deterministic Test Suite

To ensure regression prevention across all 10 threat vectors, the deterministic qualification test suite was added to the test suite:

- **Test Class**: `io.tracepermit.adapters.remote.github.SandboxAdversarialQualificationTest`
- **Location**: `java/adapters-remote/src/test/java/io/tracepermit/adapters/remote/github/SandboxAdversarialQualificationTest.java`
- **Line Count**: 934 lines
- **Status**: 10/10 passed (0 skipped, 0 failed, 100% deterministic, zero `Thread.sleep`)

### Suite Summary

| Test Method                                                                      | Vector Covered | Expected Behavior                                                 | Actual Behavior                                                   | Result |
| :------------------------------------------------------------------------------- | :------------- | :---------------------------------------------------------------- | :---------------------------------------------------------------- | :----- |
| `scopeAndRehashedRequestSubstitutionCannotBypassExactProfileOrImpersonateTenant` | 1              | Disjoint snapshot hashes; cross-tenant substitution rejected      | Hash mismatch, `ExecutionPermitException` thrown                  | PASS   |
| `callerAuthoredApprovalOrExecutionLabelsRemainDataAndNeverConveyAuthority`       | 2              | Caller JSON labels treated as passive data; unapproved by default | Binding maintains unverified state, zero authority conveyed       | PASS   |
| `staleRecoveryGenerationPolicyVersionAndContentHashFailClosed`                   | 3              | Stale recovery generation or policy version aborts transition     | Rejection with `OPERATION_STATE_STALE`                            | PASS   |
| `authorityExpiryEvaluatedAtStoreTimeFailsClosedEvenOnExactEquality`              | 4              | Expiry evaluated against storeTime; equality treated as expired   | `OPERATION_AUTHENTICATION_EXPIRED` thrown on exact equality       | PASS   |
| `succeededAttemptReplaysCachedResultWithoutSecondNetworkDispatch`                | 5              | Consumed permit rejects reuse; fresh permit reuses cached ledger  | Single permit reuse fails closed; duplicate returns cached result | PASS   |
| `truncatedOrMalformedTargetConfirmationFailsClosedAndRecordsFailure`             | 6              | Malformed target JSON fails closed and records attempt failure    | Fails closed with target rejection error code                     | PASS   |
| `changedRemoteRefOrCommitShaBreaksPreconditionBinding`                           | 7              | Changed commit SHA fails preflight verification                   | Dispatch prevented; preflight rejected                            | PASS   |
| `uncertainTargetOutcomeBlocksSecondPostAndReconciliationNeverAssumesAbsence`     | 8              | 500 error blocks second POST; lookup never infers absence         | Dispatched blocked; `LOOKUP_AMBIGUOUS_OR_ABSENT`                  | PASS   |
| `unrelatedWorkloadOrAudienceMismatchRejectsDelegation`                           | 9              | Cross-tenant or foreign executor permit rejected                  | Rejection with `permit_audience_mismatch`                         | PASS   |
| `sensitiveArgumentsAndCredentialsNeverEscapeThroughErrorsOrStringForms`          | 10             | Credentials and private keys redacted in string and error outputs | Output contains `[REDACTED]`; zero secret leakage                 | PASS   |

---

## Phase C: Evidence Table & Severity-Ranked Findings

### Boundary Evidence Matrix

| Boundary Area                       | Subsystem                                  | In-Memory / Mock Qualification | Real Docker / DB Qualification | Live Target / Cloud Qualification |
| :---------------------------------- | :----------------------------------------- | :----------------------------: | :----------------------------: | :-------------------------------: |
| Operation Registration & Withdrawal | `JdbcBusinessOperationLifecycleRepository` |             Tested             | Tested (PostgreSQL Container)  |    Untested (Customer AWS RDS)    |
| Snapshot & Profile Binding          | `GitHubBusinessOperationBinding`           |             Tested             |             Tested             | Untested (Live Enterprise Tenant) |
| Installation Credential Provider    | `GitHubInstallationCredentialProvider`     |    Tested (`MockWebServer`)    |            Untested            |  Untested (Live GitHub App API)   |
| Target Preflight & Dispatch         | `GitHubDraftPullRequestAdapter`            |    Tested (`MockWebServer`)    |            Untested            |  Untested (Live GitHub REST API)  |
| MariaDB Authority Transaction       | ADR 0077 Design Specification              |        Design Reviewed         |  Untested (Writer PR Pending)  |   Untested (AWS Aurora MariaDB)   |

### Severity-Ranked Substantiated Findings

#### 1. [Medium] Finding PR101-1: Boundary Equality in Store-Time Expiry Check

- **Component**: `BusinessOperationWithdrawal.requireAuthorizedAt(storeTime)` (`java/application`)
- **Observation**: `requireAuthorizedAt` evaluates `if (!storeTime.isBefore(authorityExpiresAt)) throw new BusinessOperationStorageException(OPERATION_AUTHENTICATION_EXPIRED)`. This means `storeTime == authorityExpiresAt` is treated as expired.
- **Risk Assessment**: Low operational risk, highly safe fail-closed behavior. Ensures that any transaction landing exactly on the expiration second is rejected rather than granted a race window.
- **Status**: Substantiated and verified by `authorityExpiryEvaluatedAtStoreTimeFailsClosedEvenOnExactEquality`.

#### 2. [Medium] Finding PR107-1: Database Time vs. Application Clock Divergence in MariaDB

- **Component**: ADR 0077 (`docs/design/mariadb-authority-transaction.md`)
- **Observation**: The proposal specifies Tier 2 lock acquisition (`FOR UPDATE`) followed by database-time verification (`NOW(6)`). If the database host clock drifts from the application server clock, authority permits could expire prematurely or linger unexpectedly.
- **Mitigation Requirement**: Deployment runbooks must mandate NTP synchronization and enforce bounded clock skew checks (< 1000ms) between application nodes and database clusters.
- **Status**: Verified in design specification; implementation must assert bounded clock divergence.

#### 3. [Low] Finding PR105-1: Single-Exchange Installation Token Rate Limit Exposure

- **Component**: `GitHubInstallationCredentialProvider` (`java/adapters-remote`)
- **Observation**: ADR 0076 deliberately excludes background token caching and renewal to eliminate cross-request credential retention. Every PR creation triggers an ephemeral token exchange.
- **Risk Assessment**: Under high transaction volume, frequent token exchanges against `https://api.github.com/app/installations/{id}/access_tokens` could encounter GitHub App rate limits (typically 5,000 requests/hour per installation).
- **Mitigation Requirement**: Acceptable for pilot sandbox volumes. Wave 2 production architectures should consider a bounded in-memory token cache scoped strictly to the short-lived token validity window (e.g. 50 minutes) without persistent storage.
- **Status**: Documented as intended design trade-off for sandbox simplicity.

#### 4. [Low] Finding PR104-1: Target Profile Base Branch Fixed to `main`

- **Component**: `GitHubDraftPullRequestProfile`
- **Observation**: The pilot target profile enforces `base == "main"` and `draft == true`. Targeting an alternate release branch (e.g. `develop` or `release/v1.0`) is rejected at binding time.
- **Risk Assessment**: Intended constraint for Wave 1 customer sandbox.
- **Status**: Verified by test suite.

---

## Conclusion & Operational Recommendations

1. **Deterministic Quality Gates**: The addition of `SandboxAdversarialQualificationTest` permanently hardens the remote adapter and operation binding against replay, identity substitution, and leak regressions.
2. **Readiness for Sandbox**: The repository foundations in `0bc6b618aa9eac7df3bacf7a6d4bcafe2a824c4f` safely enforce the central invariant: every simulated side effect is strictly evaluated before execution.
3. **Pre-Production Prerequisites**: Live customer pilots must not proceed until:
   - PR #105 (credential provider) and PR #107 (MariaDB transaction engine) are fully implemented and merged.
   - Dedicated AWS infrastructure with MariaDB/RDS and private GitHub App installations are provisioned and tested end-to-end under genuine network conditions.
