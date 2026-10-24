# Gemini: finish Okta, then design the MariaDB authority transaction

Read and obey [the shared protocol](wave1-shared-protocol.md). This packet is
self-contained with that document; do not rely on this Mac chat or local files.

## Checkpoint A: existing PR95

Existing draft: <https://github.com/hemantkgupta/tracepermit/pull/95>.
Expected head: `d05e891de27ad6921ad6c3427c8c69d99b75a0b4`.
Branch: `codex/okta-oidc-qualification`.
Latest released [Gemini handoff](https://github.com/hemantkgupta/tracepermit/pull/95#issuecomment-6383933527)
supersedes the older cloud-blocked PR body. It qualified pinned main2883eb6,
not current main. PR102/94/100/103 are now merged; do not duplicate their fixes.

Verify the expected remote head and released ownership. Claim sole Gemini
ownership, normally merge frozen main `f23dc22f056058d7977715e0878c284728d10d17`
into that branch, preserve both parents and resolve the smallest conflicts with
apply_patch. Preserve main's parallel CI and forced MariaDB gate. No rebase,
force-push or import of unmerged PR101/the binding bridge. Repair runtime only
for a substantiated defect with failing-before/passing-after tests.

Preserve explicit schema1.2/Okta profile, exact issuer/client/API audience/tenant,
independently verified ID and access tokens, code+S256/public method none,
mandatory callback issuer, state/nonce/time/replay/CSRF/mixed-auth/TLS/SSRF rules.
Verified groups only narrow independent exact human enrollment; they never
provision users, roles or scopes. Existing Auth0 behavior must remain compatible.

Run the focused Okta and legacy verifier/browser/code-exchange suites through
the Java environment script, then required full/documentation gates. Update
the PR body and exact handoff to remove stale blocker/ownership claims, without
claiming live Okta/OBO compatibility. Push normally, keep draft/unmerged and
explicitly release ownership before checkpoint B. Missing CI is a pending gate.

## Checkpoint B: separate reviewable design PR

From the frozen main, use a new isolated branch
`codex/mariadb-authority-transaction-design`. Do not stack runtime changes on PR95.
Allowed new files: `docs/design/mariadb-authority-transaction.md` and
`docs/runbooks/mariadb-authority-transaction-qualification-plan.md` only. Existing
shared specifications, application interfaces, migrations and runtime are read-only.

Read ADR0066/0068/0070/0071, main's PostgreSQL approval/evidence/outbox/recovery
composition and draft PR101 at its published SHA as an explicitly unmerged
dependency. Produce a concrete proposed port/transaction/migration contract for
MariaDB11.4.13 (primary),10.6.25 (transitional) and PostgreSQL16.4 compatibility.
Document lock order, database-time rechecks, tenant/owner/policy binding, independent
approval, permit/attempt/snapshot/evidence/outbox atomicity, least-privilege grants,
explicit migration boundaries, cancellation/claim races and commit/cleanup uncertainty.

Separate transactions from external network calls; no target/IdP/S3 call under
a DB lock. Define crash schedules and stale-worker/recovery-generation fences,
and spell out the older-backup-resurrection limitation. Unknown commit/outcome
must not imply retry permission or target absence. Identify exact existing ports
to reuse and unresolved decisions rather than inventing a second lifecycle engine.
Include a requirement-to-test matrix and a sequenced implementation split that
the coordinator can approve. Static inspection is not an executed crash/failover test.

Format/link-check both new documents. Open a separate draft design PR, not a
runtime parity claim. Return the shared handoff structure for each checkpoint.
