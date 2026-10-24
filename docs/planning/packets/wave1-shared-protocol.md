# Wave 1: customer-sandbox foundations

Frozen base: `f23dc22f056058d7977715e0878c284728d10d17` (merged PR103).
Repository: <https://github.com/hemantkgupta/tracepermit>.
These packets authorize isolated development and synthetic qualification, not a
live customer pilot, production deployment, automatic merge or paid API use.

## Exclusive lanes

| Lane                          | Branch                                       | Exclusive scope                                                            |
| ----------------------------- | -------------------------------------------- | -------------------------------------------------------------------------- |
| Mac coordinator               | `codex/durable-operation-lifecycle`          | Reconcile existing draft PR101; no unrelated runtime feature               |
| Mac coordinator               | `codex/github-operation-binding`             | Pure operation/target binding bridge and coordination packets; ADR0075     |
| Gemini                        | `codex/okta-oidc-qualification`              | Reconcile/qualify existing draft PR95, then release ownership              |
| Gemini, after PR95 checkpoint | `codex/mariadb-authority-transaction-design` | Design/migration/fault matrix only; no runtime implementation              |
| Cloud credential writer       | `codex/github-app-installation-credentials`  | New credential implementation/tests behind existing provider port; ADR0076 |
| Cloud setup writer            | `codex/cloud-java21-docker-qualification`    | Development setup/preflight scripts/tests/runbook only                     |
| Independent cloud auditor     | detached HEAD                                | Read-only foundation review; no writer ownership                           |

Read the matching packet before claiming a lane. GitHub is authoritative;
conversation history and another machine's uncommitted files are not inputs.
Inspect branch/head/main, status/upstream divergence, latest public handoff,
ownership and open-PR overlap before editing. Preserve all existing changes.
Publish an ownership claim in the existing PR or new draft PR when available.
If another writer owns the branch or the expected head differs, stop and report.
Use separate fresh clones/worktrees. Never reset, discard, clean, rebase,
force-push, edit another lane, or merge a PR. A normal base merge is allowed
only as explicitly described in the individual packet.

Read AGENTS.md, specs README and the affected ADR/runbooks. New backend behavior
is Java21; no TypeScript backend. Keep transport in adapters and canonical
contracts unchanged unless explicitly authorized. Coordinator reserves shared
contract changes and ADR0075; credential lane reserves ADR0076. Append only
your scoped traceability/evidence entries; do not rewrite another lane's claims.

## Security and bounded loop

- Never read, copy, materialize, stage or publish `agentcore-work/**` or the four
  private customer planning files. Check exclusion before a fresh checkout.
- Synthetic fixtures/disposable local containers only. No live GitHub App,
  Okta/Auth0, AWS/AgentCore/RDS/S3/EKS, customer or production calls. Package,
  source-control and official-documentation access are development inputs only.
- No tokens, codes, PKCE values, cookies, passwords, client secrets, private keys,
  connection strings or raw sensitive diagnostics in Git, output or handoffs.
  Native OIDC clients are public clients and have no client secret.
- No public listener/tunnel, TLS/SSRF bypass, proxy bypass workaround, arbitrary
  target host, silent retry, broadened permission, weaker gate or production claim.
- Use existing subscriptions only. No billed API/credit fallback. Gemini uses
  subscription-backed Antigravity; bounded Flash is permitted if Pro unavailable.
- Work in coherent checkpoints within an eight-hour ceiling, not eight hours of
  padding after completion. Stop after three consecutive no-progress attempts,
  quota/auth/network/policy failure or a missing trust decision. Do not retry to green.
- Preflight Node22+, pnpm11.25.0 and Java21; database lanes additionally need a
  responsive Docker daemon and the exact Testcontainers images. Java17 or a
  Docker availability skip is not qualification. Setup has its own lane.
- Prefer focused tests, then one full `pnpm check`, `pnpm format:check`, relevant
  docs/flow checks and diff checks. Preserve failure, skip and cache reuse facts.
  Bound Gradle workers, not the tests' internal concurrency or assertions. On a
  shared Mac coordinate broad suites; do not restart Docker or stop others' resources.
- Commit and push a coherent checkpoint; open/update a draft PR, record exact-head
  CI, explicitly stop editing and release ownership. Missing hosted gates stay
  pending. No automatic merge or general readiness certification.

## Required final handoff

Use exactly these fields, including failed/not-run gates and manual limitations:

```text
TRACEPERMIT HANDOFF
- Task:
- Repository:
- Branch:
- Base commit:
- Latest pushed commit:
- PR URL:
- Branch owner now:
- Working tree status:
- Completed:
- Remaining:
- Files materially changed:
- Tests passed:
- Tests failed/not run:
- Exact next command:
- Required environment:
- Local-only/manual steps:
- Security constraints:
- Known limitations:
- Safe for another environment to continue: yes/no
```

The current simulator candidate has a conditional private-evaluation GO, not a
customer-real-target GO. Fresh second-Mac execution, composed MariaDB authority,
trusted AgentCore ingress, durable dispatch/reconciliation, live sandbox and
private deployment remain separate gates. Do not promote a library to an
integrated capability.
