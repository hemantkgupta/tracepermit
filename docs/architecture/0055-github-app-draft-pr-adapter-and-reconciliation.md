# ADR 0055: GitHub App draft pull request adapter and external reconciliation

- Status: Accepted
- Date: 2026-10-23
- Affected Requirements: FR-001, FR-002, FR-008, FR-009, FR-012, FR-015, SEC-001, SEC-002, SEC-003, SEC-012, SEC-015, SEC-019

## Context

TracePermit's first AgentCore pilot operation governs the automated creation of draft pull requests in one private sandbox repository (`tracepermit/agentcore-pr-pilot`). Unlike internal simulated targets, GitHub.com is an external third-party provider reached over HTTPS.

This introduces two fundamental architectural challenges:

1. **Lack of native idempotency**: GitHub's REST API endpoint `POST /repos/{owner}/{repo}/pulls` does not accept a client-specified idempotency key. A network disconnect, socket timeout, or 5xx server error leaves the client in an ambiguous state: the pull request may have been created on GitHub, or the request may have failed before execution. Re-executing the POST automatically risks duplicate pull request creation attempts or conflicting state, violating ADR 0005 and ADR 0020.
2. **Strict boundary and credential safety**: As defined in `AGENTS.md` and `SEC-001`/`SEC-003`, user requests must not self-assert authenticated or verified claims, raw bearer tokens or private keys must never enter source control, logs, or evidence records, and effectful operations must fail closed against unknown schemas, repositories, branches, or verbs.

## Decision

TracePermit introduces a dedicated Java adapter foundation in `adapters-remote`: `GitHubDraftPullRequestAdapter`, paired with a read-only reconciliation port: `GitHubPullRequestOutcomeResolver`.

### 1. Pilot Boundary and Policy Enforcement

- **Operation**: `github.pull_request.create` only. Any other operation (merges, deletions, issues, repo settings) fails closed.
- **Target Destination**: Strictly HTTPS `api.github.com` (configurable to deterministic test servers in qualification).
- **Repository Allowlist**: Strictly `tracepermit/agentcore-pr-pilot`. Any other repository is denied immediately (`REPOSITORY_NOT_ALLOWLISTED`).
- **Base Branch Allowlist**: Strictly `main`. Any other target branch is denied immediately (`BASE_BRANCH_NOT_ALLOWLISTED`).
- **Draft Invariant**: `draft` must be explicitly present and boolean `true`. Non-draft PR creation is prohibited (`DRAFT_REQUIRED_TRUE`).
- **Scope Limit**: The agent prepares and pushes branches independently; TracePermit governs only the policy admission, permit consumption, and dispatch of the draft pull request creation.
- **Fail-Closed Arguments & Targets**: Arguments are restricted to `title`, `head`, `base`, `draft`, `body`, and `repository`. Target fields are restricted to `system`, `environment`, `accountId`, and `resourceType`. Unknown fields fail closed.
- **Self-Assertion Prevention (SEC-001)**: Body arguments containing `authenticated`, `delegated`, or `verified` claims are rejected immediately (`SELF_ASSERTION_FORBIDDEN`).

### 2. Authentication and Secret Redaction (SEC-003, SEC-012)

- Authentication uses short-lived GitHub App installation tokens derived from a trusted `GitHubAppCredentialProvider`.
- Callers and request bodies never supply or claim tokens.
- Credentials in memory mask raw tokens in `toString()`.
- Audit evidence records store only the stable `installationId`, `repository`, and cryptographic `credentialFingerprint` (`sha256:...`).
- Error messages and exception causes redact all token patterns and authorization headers.

### 3. Network and Transport Safety (SEC-015)

- Transport uses a pinned OkHttpClient configured with:
  - `proxy(Proxy.NO_PROXY)`: No ambient HTTP/HTTPS proxies.
  - `followRedirects(false)` & `followSslRedirects(false)`: Redirects fail closed (`UNEXPECTED_REDIRECT`).
  - `retryOnConnectionFailure(false)`: No hidden transport retries.
  - Bounded connect timeout (5s) and call timeout (10s).
  - Maximum response size limit (64 KB / 65,536 bytes) to prevent memory exhaustion attacks.

### 4. External Idempotency and Reconciliation Contract (FR-015, SEC-019, ADR 0005, ADR 0020)

- **Stable Operation Key**:
  Because GitHub PR creation lacks a native idempotency key, TracePermit derives an explicit deterministic operation key:
  $$\text{operationKey} = \text{sha256}("github.pull\_request.create:" + \text{installationId} + ":" + \text{repository} + ":" + \text{head} + ":" + \text{base})$$
  Because GitHub enforces that only one open pull request may exist for a given `head` and `base` branch within a repository, this tuple uniquely identifies the external logical effect.
- **Ledger Claim**:
  Before network dispatch, the adapter claims the operation key in `InMemoryExecutionLedger` (or durable ledger). Concurrent duplicates are blocked (`CONCURRENT_EXECUTION_IN_PROGRESS`). Subsequent calls after success return the stored result without calling GitHub.
- **Ambiguous Outcome Handling**:
  If a network timeout (`SocketTimeoutException`), transport error, or 5xx server response occurs, the adapter records the attempt status as `UNKNOWN` with an explicit reason code (`OUTCOME_UNCERTAIN_TIMEOUT`, `OUTCOME_UNCERTAIN_IO_ERROR`). The adapter **never retries the POST**.
- **Read-Only Reconciliation Lookup**:
  `GitHubPullRequestOutcomeResolver` implements `TargetOutcomeResolver`. When executed by `ExecutionReconciliationWorker`, it performs a read-only `GET /repos/{owner}/{repo}/pulls?head={owner}:{head}&base={base}&state=all`:
  - If a pull request matching `head` and `base` is returned: outcome is confirmed as `succeeded` (`PR_CONFIRMED_SUCCESS`).
  - If an empty array is returned: outcome is confirmed as `failed` (`PR_CONFIRMED_ABSENT`).
  - If the lookup times out or encounters a server error: outcome remains `unknown` (`TARGET_LOOKUP_FAILED`) and reconciliation is deferred.

### 5. Simulator vs. Live Qualification Boundary (FR-012)

- `StatefulTargetSimulator` implements in-memory stateful PR creation for simulated targets (`accountId: simulated-tracepermit`).
- `GitHubDraftPullRequestAdapter` is qualified against deterministic `MockWebServer` fake servers in automated test suites.
- Live GitHub API execution against the private repository `tracepermit/agentcore-pr-pilot` is reserved for explicit operator qualification and is distinguished by `mode: "pilot"`.

## Consequences

- Automated retries will never re-execute a PR creation request without read-only reconciliation.
- External uncertainty is visible, durable, and verifiable.
- Pilot scope is strictly bounded to one allowlisted private sandbox repository and draft PRs.
- No bearer tokens or private keys can leak into evidence, logs, or source control.
