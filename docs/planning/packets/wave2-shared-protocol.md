# Wave 2: eight-hour worker protocol

Repository: <https://github.com/hemantkgupta/tracepermit>.
Planning main: `0bc6b618aa9eac7df3bacf7a6d4bcafe2a824c4f`.
This packet authorizes isolated synthetic development, commits/pushes and draft
PRs, never merges or live customer/provider/target actions.

## Startup and ownership

Use the published `tracepermit-java21-docker` environment, not the old environment
or a generic cloud computer. Verify its identity and prepared checkout/toolchain
before editing. If the expected environment is absent, stop; do not rebuild,
modify or republish an environment as an application task. Read its Start skill,
the cloud runtime skill, AGENTS.md, specifications README and relevant public
ADRs/runbooks. Private `agentcore-work/**` and customer planning files must remain
unread, unmaterialized, unstaged and unpublished.

GitHub is authoritative. Inspect/report repository path, origin, current branch,
HEAD/main, status/upstream divergence, latest handoff, ownership, recent tests and
local-only/sensitive data. Another chat's local work is not an input. Claim sole
writer ownership in the existing or new draft PR before implementation. Refuse
unexpected dirty work or another active writer; never reset, discard, clean,
rebase or force-push. A normal base merge is allowed only by the matching packet.

Mac owns application/multi-engine operations authority and ADR0077. Cloud 1 owns
GitHub credential/composition work, ADR0076 and optionally ADR0079. Cloud 2 owns
execution-disabled AgentCore ingress and ADR0078. Gemini owns independent tests
and audit evidence, not runtime changes. Do not invent or edit another lane's
interface. Shared documentation receives scoped evidence, not status rewrites.

## Product/security limits

- New backend behavior is Java21. Preserve canonical v1, existing ports, deny
  rules, trusted-adapter identity derivation, exact request binding and pending-only
  withdrawal. A proposal/JSON label/context constructor/hash is not authentication,
  approval, a permit or a dispatch claim.
- Synthetic fixtures/disposable databases only. No live target, GitHub App
  installation, Okta/Auth0/AgentCore/AWS/RDS/S3/EKS/customer call. Source control,
  package installation and primary official documentation are development inputs.
- No secrets, placeholder values, headers, sensitive response bodies, raw SQL
  diagnostics or real identifiers in output/Git/evidence. The personal GH_TOKEN
  network secret is scoped to api.github.com; never extract it or broaden scope.
- No public listener/tunnel, TLS/SSRF/proxy bypass, broader network policy,
  credentials, paid API fallback, effectful retry or production-readiness claim.
- Use the saved Java selector and platform-managed Unix Docker. Never restart or
  prune a daemon or stop resources owned by another task.

## Bounded productive loop

Work for up to eight productive hours: inspect, reproduce, implement the smallest
coherent repair, run focused positive/negative tests, checkpoint, then take the
next in-scope acceptance item. Eight hours is a ceiling, not artificial padding
or permission for half-wired authority. Stop on a missing security decision,
quota/auth failure or three identical no-progress attempts. Do not retry to green.
At most two broad runs without a diagnosed repair. No automatic merge.

Run focused tests first, then proportionate `pnpm check`, `pnpm format:check`,
documentation links/flow checks and diff checks. Database qualification must run
the real exact fixtures without availability skips. Bound Gradle workers, not
internal test concurrency/deadlines/workloads/assertions or coverage thresholds.
Record failures, skips, cache reuse and fresh XML honestly. Hosted checks must
be tied to the exact final head, not an earlier producer/base receipt.

Commit/push coherent completed work, open/update draft PR(s), attach each created
PR to the task, and explicitly stop editing/release ownership at the handoff.
An unfinished integration remains a named gate, never a certified capability.

## Required final handoff

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
