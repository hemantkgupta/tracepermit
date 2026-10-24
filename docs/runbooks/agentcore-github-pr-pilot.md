# Operator Runbook: AgentCore GitHub Draft Pull Request Pilot

Current synthetic target-validation contract:
[ADR0073](../architecture/0073-github-pilot-target-preflight.md) and the
[dedicated preflight runbook](github-pilot-target-preflight.md) supersede the
original permissive confirmation/absence path. No live GitHub access, credential
installation or production execution is authorized or qualified by this guide.

## 1. Overview and Pilot Boundary

This runbook documents the operational procedures, trust boundaries, and reconciliation lifecycle for TracePermit's first AgentCore pilot operation: `github.pull_request.create`.

| Attribute                   | Bounded Value                                                                         |
| :-------------------------- | :------------------------------------------------------------------------------------ |
| **Operation**               | `github.pull_request.create`                                                          |
| **Target Host**             | Strictly HTTPS `api.github.com` (no GitHub Enterprise Server or arbitrary host)       |
| **Allowlisted Repository**  | `tracepermit/agentcore-pr-pilot` (private sandbox)                                    |
| **Allowlisted Base Branch** | `main`                                                                                |
| **Draft Requirement**       | `draft: true` (mandatory)                                                             |
| **Prohibited Capabilities** | Merges, branch creation/deletion, issues, settings, workflows, admin                  |
| **Authentication**          | GitHub App installation token via trusted provider; zero secrets in Git/logs/evidence |
| **Evidence Policy**         | Credential fingerprints (`sha256:...`) and installation IDs only                      |

### Affected Requirement IDs

`FR-001`, `FR-002`, `FR-008`, `FR-009`, `FR-012`, `FR-015`, `SEC-001`, `SEC-002`, `SEC-003`, `SEC-012`, `SEC-015`, `SEC-019`.

---

## 2. Security Boundaries and Invariants

1. **Self-Assertion Prevention (`SEC-001`)**:
   Incoming tool calls cannot declare `authenticated`, `delegated`, or `verified` state. The adapter derives installation credentials through a trusted provider port.
2. **Fail-Closed Arguments (`SEC-002`)**:
   Unknown fields in `arguments` or `target` are rejected immediately with `UNSUPPORTED_ARGUMENT` or `UNSUPPORTED_TARGET_FIELD`.
3. **Draft-Only Pull Requests**:
   The pilot strictly limits agents to creating draft PRs. Requests with `draft: false` or missing draft flags are denied (`DRAFT_REQUIRED_TRUE`).
4. **Network and Transport Boundary (`SEC-015`)**:
   - `Proxy.NO_PROXY` enforced.
   - HTTP and HTTPS redirects rejected (`UNEXPECTED_REDIRECT`).
   - Transport retries disabled (`retryOnConnectionFailure(false)`).
   - Bounded timeouts (5s connect, 10s call).
   - Maximum decoded response size bounded to 65,536 bytes while reading POST confirmation and GET lookup, including chunked responses without `Content-Length`. The reader materializes at most one overflow byte beyond the limit; responses close on rejection.
5. **Secret Redaction (`SEC-003`, `SEC-012`)**:
   Raw tokens are masked in `GitHubAppCredentials.toString()` and stripped from all error messages and exception causes. Evidence records contain only `sha256:...` fingerprints.

---

## 3. Idempotency and Reconciliation Lifecycle (`ADR 0069`)

GitHub's REST API does not support client-specified idempotency keys. To prevent duplicate pull request creation:

```
[Agent Action]
      │
      ▼
[Validate: repo, base=main, draft=true]
      │
      ▼
[Compute Stable Operation Key] ───► sha256(installId + ":" + repo + ":" + head + ":" + base)
      │
      ▼
[Claim Ledger Attempt: executing]
      │
      ├── (201 + exact typed identity/content) ──► [succeeded]
      ├── (Oversized 201 body) ───────► [unknown] (RESPONSE_TOO_LARGE)
      │
      ├── (Timeout / Network Drop) ──► [unknown] (OUTCOME_UNCERTAIN_TIMEOUT)
      │                                       │
      │                                       ▼
      │                            [Reconciliation Worker]
      │                                       │
      │                                       ▼
      │                            [Read-only GET /repos/.../pulls]
      │                                       ├── (One exact complete open draft) ──► [succeeded observation]
      │                                       ├── (Empty/duplicate/paginated) ──► [unknown]
      │                                       └── (Lookup unavailable) ──► [unknown]
      │
      └── (401/403/404/422 refusal) ──► [failed]; other/redirect ──► [unknown]
```

1. **Stable Operation Key**:
   Derived from `sha256("github.pull_request.create:" + installationId + ":" + repository + ":" + head + ":" + base)`.
2. **Concurrent Duplicate Prevention**:
   A concurrent attempt while an operation is `executing` is rejected with `CONCURRENT_EXECUTION_IN_PROGRESS`.
3. **Idempotent Replay**:
   A subsequent valid permit for an already `succeeded` operation returns the cached result without contacting GitHub.
4. **Reconciliation by Read-Only Lookup**:
   A caller may supply `GitHubPullRequestOutcomeResolver.lookup()` to the reconciliation worker port. It performs a `GET /repos/{owner}/{repo}/pulls?head={owner}:{head}&base={base}&state=all`. The original POST is never retried automatically.

An explicit operator target record and current request-bound permit are required.
GET installation access, immutable repository/owner and exact ref/SHA preflight
must pass before POST or lookup. Ref state can race after a read; no native CAS
or exactly-once property is claimed. A positive lookup observes the desired
logical effect, not which request created it. Empty lists remain unknown.

Lookup authentication rejection (401/403), repository invisibility (404), redirects,
oversized bodies and transport/server failures do not prove the original creation
failed. They return `unknown` with the existing fixed reason code and no confirmed
result. Correct the lookup's availability without submitting another creation.

The adapter foundation uses a process-local ledger and registered lookup metadata.
It does not itself wire a durable worker, protect across restart/replicas, or qualify
pagination/eventual visibility as authoritative absence. See
[ADR 0069](../architecture/0069-github-app-draft-pr-adapter-and-reconciliation.md).

---

## 4. Qualification and Verification

Automated tests qualify the adapter against a deterministic fake HTTP server (`MockWebServer`):

```bash
bash -c 'source scripts/configure-java-environment.sh && java/gradlew -p java :adapters-remote:test --tests "*github.*Test"'
```

To run full project verification:

```bash
pnpm check
pnpm format:check
pnpm docs:links
pnpm docs:flows:check
git diff --check
```

### Stop Conditions and Anomaly Response

- **Unknown Repository Rejection**: If an agent requests PR creation on an unapproved repository, the adapter fails closed immediately. Verify that repository routing is intact.
- **Persistent UNKNOWN State**: If a pull request remains `unknown` after multiple reconciliation attempts, check GitHub API status and ensure read permissions on pull requests are granted to the GitHub App installation.
