# Cloud 1: GitHub credentials and adapter composition

Read and obey the [eight-hour shared protocol](wave2-shared-protocol.md).
The operator approved this packet; leave every PR unmerged.

## Starting point

- Environment: `tracepermit-java21-docker` (verify before editing).
- Existing draft [PR105](https://github.com/hemantkgupta/tracepermit/pull/105).
- Branch: `codex/github-app-installation-credentials`.
- Expected published head: `9405c0ee476b28322a5930a5a88a2d5032abc6b3`.
- Planning main: `0bc6b618aa9eac7df3bacf7a6d4bcafe2a824c4f`.
- The prior writer released ownership in the PR handoff; verify no newer writer
  or head supersedes it before claiming this branch. Attach PR105 to this task.

## Ordered acceptance work

1. Inspect the actual conflicts and fetch current main. Reconcile PR105 with a
   normal merge preserving parents and unrelated work, using `apply_patch` for
   manual text edits. Do not rebase or force-push. Preserve the recorded historical
   native-process cleanup failures; hosted success does not relabel a local failure.
2. Independently review the provider implementation. Add missing positive/negative
   regressions for exact App/installation/repository/permissions, expiry after
   waits and cleanup, malformed/omitted typed assurance, redirects/DNS/TLS, bounded
   response/deadline behavior, one-exchange behavior and fixed secret-safe errors.
3. Add a narrow deterministic composition fixture/factory proving the concrete
   provider can serve the existing draft-PR adapter without changing permit
   verification, target preflight, allowlist or unknown-outcome safeguards. Use
   fake transports only. Never wire a shipped launcher or business-operation
   executor, invent Mac authority interfaces or label a process-local ledger as
   durable authority. A separate follow-up draft PR is allowed only after a clean
   pushed PR105 checkpoint and explicit ownership record.
4. Run relevant focused/full gates, preserve failure/skip/cache facts and obtain
   exact-final-head CI. Publish the standard handoff and release writer ownership.

Exclusive runtime scope is `github.credentials` plus new GitHub composition
files/tests in `adapters-remote`. Do not edit application authority, persistence,
identity, ingress, existing target/permit semantics or CI. Keep ADR0076; ADR0079 is
reserved if a new composition decision is needed. Scope shared docs narrowly.
No live App credential/key installation, token minting or GitHub target call.
