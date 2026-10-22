# Gemini goal: explicit approval review download in React

Repository: https://github.com/hemantkgupta/tracepermit
Branch: `codex/approval-review-download-ui`, sole writer Gemini after founder starts it.
Use a new isolated clone/worktree; do not modify shared, Mac, GLM or cloud checkouts.

First report path/branch/HEAD/origin-main/status/divergence and any local-only or
sensitive work. Read AGENTS.md, specs README, ADR0064, the export schema/runbook
and audit-ready workspace plan from the exact published integration checkpoint
supplied by the founder. If not on main yet, read that pinned commit with Git;
do not copy its backend implementation or merge/cherry-pick an unmerged branch.
Read applicable React and frontend-testing skills. No new dependencies.

Exclusive edits: `apps/console/src/approval-export.ts`, a new export test file,
`ApprovalInbox.tsx`, its directly needed tests, a new
`scripts/approval-export-browser.test.mjs` and a UI-only runbook. Preserve existing
history/withdrawal/CSRF/role controls. No Java, schema, ADR/shared specs, package
scripts, locks, CI, Auth0, site or Notion changes. Report conflicts before writing.

Implement an explicit "Download review JSON" control for the selected request.
Explain before download: simulator-only, metadata-only, unsigned, incomplete,
not execution authority. It is an export permission inherited from current read
visibility, not a reviewer/execute grant. Fetch only on user gesture from the
same-origin browser export route using the existing cookie session; never bearer
tokens/local storage. No download on mount, refresh, selection, failed request or
mutation. Do not automatically retry. Disable during invalid/loading/expired
authority; server remains final. Use existing fixed-error UI, not raw response text.

Bound body streaming to 32,768 UTF-8 bytes and a finite abort deadline. Strictly
validate closed shape/version/kind/profile, all false assurance flags, simulator
mode, types/IDs/statuses, and selected request ID/hash before creating a JSON Blob.
Do not claim browser-side cryptographic verification if not implemented. Capture
identity/session/selection generation when clicked; logout, session expiry,
changed authority, request selection/hash or later responses must invalidate an
in-flight download even if abort is ignored. Make download a single explicit
successful action with safe fixed filename derived from validated request ID;
revoke object URLs promptly/finally. Do not save HTML, server errors, raw tokens,
unexpected fields or an action snapshot to disk. Downloads remain sensitive metadata.

Focused tests must include positive valid export and negative wrong hash/ID/mode/
assurance/version/field, duplicate clicks, oversized/invalid response, HTTP401/403/
404/503, slow/late body after logout/selection, and object-URL cleanup. Include
mounted real-browser download/save verification against the actual Java route
once the backend has merged. Reuse the existing bounded disposable fixture and
Playwright only; no personal profile or real IdP. If backend is not on main,
complete mocked/component tests and explicitly leave integrated download pending.
Do not replace that missing integration with a passing mock-only claim.

Run focused tests, `pnpm check`, `pnpm format:check`, docs links and affected
browser tests. Record exact head/check counts/failures/skips. Open one draft PR,
leave UNMERGED, use the plan's exact TRACEPERMIT HANDOFF, then explicitly stop
editing. No reset/rebase/force-push/merge, public listener, tunnel, real targets,
provider changes, new scope/role or expanded assurance claim. If a shared contract
needs adjustment, report it to the founder/integrator first. Do not message other
Codex chats without the human's authorization.
