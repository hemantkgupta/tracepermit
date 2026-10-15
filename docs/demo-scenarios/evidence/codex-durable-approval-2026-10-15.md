# Codex native MCP to durable simulator approval qualification

Date: 2026-10-15. Scope: local Java/React, synthetic identities and disposable
PostgreSQL 16.4. No real cloud service, personal login, external IdP registration,
public tunnel, model inference or target credential was used.

Later evidence: the separate [model-driven rehearsal](codex-model-driven-approval-2026-10-15.md)
qualifies a named Codex 0.162.1/`gpt-5.6-sol` prompt path. The observations below
remain the original protocol-only record; they must not be relabeled as model calls.

## Evidence levels

| Layer               | Result                                                                                                               | What this proves                                                                                                                                                                               |
| ------------------- | -------------------------------------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Protocol            | Java strict-envelope/schema/error tests passed                                                                       | The named protocol cases, not native client compatibility                                                                                                                                      |
| Durable composition | Actual HTTP/signatures/PostgreSQL MCP → browser decision → explicit resume; restart/concurrency/failure tests passed | The tested application/state boundaries                                                                                                                                                        |
| Native client       | Installed `codex-cli 0.142.4`, app-server stdio client and real HTTP MCP transport                                   | Native initialize, three-tool catalog and actual request/status/resume calls, without LLM inference                                                                                            |
| Rendered browser    | Chrome extension, desktop 1488×956                                                                                   | Existing reviewer UI rendered the MCP-created pending approval, exact-hash confirmation, approved/not-executed and consumed/succeeded states; no captured warnings/errors or framework overlay |
| Model-driven UX     | Not exercised in this qualification                                                                                  | User prompt/tool-selection rehearsal remains separate                                                                                                                                          |

The native test used only invocation-scoped MCP configuration, an environment
reference to a short-lived synthetic Alice bearer, and an ephemeral app-server
session. It did not change the global Codex MCP configuration or start a model
turn. The test driver called `initialize`, `mcpServerStatus/list`, ephemeral
`thread/start` and `mcpServer/tool/call`; the installed CLI generated the local
protocol schema used by that driver. No reusable credential was printed or added
to the repository. This is one named local client/profile, not broad compatibility.

## Observed lifecycle

Synthetic approval ID: `c24d2efb-7f69-4cc6-b45c-ec82e55d9149`.
Request hash: `sha256:4c373ad2aadad911a33a4964c10c425ac8a748dc3d3ff841fbc164c1cf0f6f07`.
These are non-secret fixture identifiers, not credentials.

1. Codex native inventory returned TracePermit server version 0.1.0,
   `authStatus=bearerToken`, exactly the three approval tools and no review tool.
2. `tracepermit_request_simulated_change` with `sg-demo`, port 443 and
   `192.0.2.0/24` returned `isError=false`, `pending`, `not_executed`, local policy
   `pol-local-simulator-approval` v2, and canonical action protocol `mcp`.
3. Chrome signed in as synthetic Bob (REVIEWER). The inbox displayed the same
   ID/hash and exact arguments. **Approve exact action → Confirm approve**
   produced `approved`, still `not_executed`.
4. Native Codex `tracepermit_get_approval` independently returned the same
   approved/not-executed state and a distinct reviewer principal.
5. Native Codex `tracepermit_resume_approval` with the original ID/hash returned
   `consumed` / `succeeded`. Chrome Refresh displayed the same durable outcome.
6. A second native resume returned `isError=true`,
   `errorCode=APPROVAL_NOT_EXECUTABLE`, not another effect or success.
7. The qualification launcher was stopped normally. Its disposable database
   and generated keys/token file were removed. Screenshots contain only
   synthetic identity/action data and were retained outside the source tree.

## Automated verification

- `pnpm check`: 443 Java tests and 12 React tests, zero failures/errors/skips.
  Aggregate coverage 94.83% line / 77.92% branch, unchanged 94% / 75% gates.
- `pnpm format:check`, `pnpm docs:links`, frozen pnpm installation passed.
- Existing request-bound approval and production-deletion simulator scenarios
  passed; `pnpm demo:verify` passed all 15 checks.
- Frozen site installation reported zero vulnerabilities; site build passed.

Important negatives include no reviewer tool, scope-filtered catalog plus
independent invocation authorization, wrong owner/tenant/client, alternate HTTP
execution binding, changed hash/arguments, self-review, expiry, reviewer removal,
stale policy, unavailable DB, eight concurrent resumes, lost completion and
restart without redispatch. Existing application/PG tests establish atomic
single-use claims; a simulator's deduplicated resource state is not alone proof
of invocation count.

## Reproduce and limits

Use the [Codex approval runbook](../../runbooks/codex-durable-approvals.md) and
`pnpm demo:approvals:codex`. Native protocol/API invocation is not a claim that a
model selected the right tool from a prompt. The manual prompts in the runbook
remain the next UX rehearsal. No managed endpoint identity, personal/enterprise
OpenAI account attribution, public browser IdP profile, real target adapter or
host-wide anti-bypass is qualified. This is at-most-one dispatch per approval,
not exactly-once external effects or deduplicated approval creation.
