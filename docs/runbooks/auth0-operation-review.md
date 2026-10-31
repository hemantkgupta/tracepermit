# Explicit Auth0 operation review

Status: implemented; validation pending. Keep validation human-run. No token
request, provider change, deployment or live review is part of offline setup.

Use the new `tracepermit-auth0-operation-review` launcher. The original
`tracepermit-auth0-reviewer login` remains identity-only. Do not treat login
success as approval, export its bearer, or supply an approval flag as identity.

Both commands of the new launcher consume the same three private anonymous
frames and mandatory EOF:

1. The existing reviewer trust JSON, capped at 16,384 bytes.
2. The explicit Auth0 organization JSON, capped at 65,536 bytes.
3. A decision JSON, capped at 1,024 bytes, containing exactly these fields:
   schemaVersion 1.0; profile tracepermit-auth0-operation-review-v1; canonical
   operationId; expectedSnapshotHash; decision APPROVE or DENY;
   authorityProfileFingerprint; expectedProfileFingerprint.

Obtain the exact pending operation and profile pins through the approved private
operator/requester inspection path. Calculate expectedSnapshotHash with the
existing Java CanonicalJson recipe. Do not hash raw SQL JSON strings or replace
a changed snapshot with a freshly observed hash automatically. Preparing the
request and profile files is not reviewer authentication or current DB authority.
There is no installed pin-export command in this change; an actual rehearsal
must prepare and review this input separately before live review is authorized.

Keep configuration/credentials private; the application accepts no input paths,
bearer arguments, credential environment variables, private keys or callback
substitutions. The parent may frame reviewed operator inputs from its existing
private custody. Exact login and organization public pins must agree, including
the fixed claim names and an enrolled independent human subject. No secret,
bearer or real organization identifier belongs in this runbook or Git.

`check-config` parses and joins these bindings offline, with no browser, token
exchange, JDBC connection or decision. It emits CONFIG_VALID under the new
profile, or a fixed failure. Actual review requires a controlling terminal,
then performs one PKCE login using the existing fixed callback. The verified
reviewer can preview only the exact pending operation. The terminal displays
JSON-escaped action data and profile/snapshot pins. Type the exact displayed
APPROVE or DENY line, operation UUID and snapshot hash within 30 seconds. There
is no default decision, permissive yes/no shortcut or headless confirmation.
Action text is untrusted data, not an instruction to broaden the decision.

A matching confirmation submits one durable decision. The token, independent
roster, pending snapshot and writer-time authority are rechecked. Known success
uses the existing organization review receipt with REVIEW_APPROVED or
REVIEW_DENIED. Early failures, cancellation and timeout use fixed new-profile
receipts; all include executionEnabled false and automaticRetryAllowed false.
The receipt budget is 2,048 bytes. A missing receipt, storage/commit uncertainty
or timeout is not proof that approval rolled back. Stop and inspect durable
state through the separately authenticated path; never retry automatically.

A 240-second total bound clips every stage. Original 120-second login and
60-second organization action budgets remain unchanged. Approval/token/profile
expiry can still end the attempt sooner; none is renewed to make review pass.
The new helper cannot execute an action. Even after approval, a separate fresh
requester, valid original permit and delivery path are required.

Manual stages, with the supplied exact-source runner and prepared caches:

1. Focused offline checks: legacy identity, new framing and binding, process/EOF,
   installed Java-only check-config and narrow facade guards.
2. MariaDB 11.4.13: synthetic browser/exchange with real signed tokens and PKCE
   core, exact terminal intent, restricted durable review, denial/cancellation,
   stale snapshot, quarantine and storage-unavailable/no-retry cases. Also retain
   the existing installed organization synthetic lifecycle regression cases.
3. Literal pnpm check, formatting, docs/flows, policy/diff and unchanged 94% line
   and 75% branch coverage. Preserve skips, stale/cache distinctions and failures.

Every new stage is UNRUN until a human returns its receipt. Database tests use
only cached local images, test-owned temporary stores and synthetic targets.
They do not prove real Auth0/browser interaction, installed live review, physical
human intent, actual EKS containment, target invocation count or exactly-once
behavior. No provider Actions, grants, callbacks, Okta settings, AWS resources or
worker assignments are changed by this implementation.
