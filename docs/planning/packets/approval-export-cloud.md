# Cloud goal: offline Java approval review checker

Repository: https://github.com/hemantkgupta/tracepermit
New branch: `codex/approval-review-offline-checker`, sole writer cloud after launch.
Do NOT modify `codex/auth0-native-oidc-qualification`; it is not this assignment.

Use an isolated checkout. First inspect/report branch, HEAD, main, status,
divergence and sensitive/local-only work; preserve all existing changes. Read
AGENTS.md, specs README, ADR0064, the closed export schema, synthetic golden
vector, export runbook and audit-ready milestone plan from the founder-supplied
exact published checkpoint. If not merged, read the pinned contract with Git;
do not import/cherry-pick/merge the unmerged backend or edit its paths.

Exclusive edits: NEW `java/review-verifier` module (main/tests/build), one module
entry in `java/settings.gradle.kts`, and its NEW offline-checker runbook. Reuse
Java21, existing Jackson/contracts canonicalization dependencies. No new remote
dependency/version, Node/Python backend, database, browser, Auth0, policy or target.
Do not edit frozen contracts/vector/ADR/shared specs/CI/package scripts/site/Notion.

Implement a standalone Java CLI accepting exactly one local regular JSON file
with finite 32,768-byte input and strict UTF-8 decoding. No URL, stdin waiting,
directory traversal scanner, network, environment credentials or effectful call.
Reject symlinks/non-regular files as unsupported and bound actual reads, not just
the initial stat. Do not print file path, content, identifiers, exception/cause or
raw parser text. Fixed safe result categories and nonzero exit on failure.

Verify the exact CLOSED v1 schema, types, identifiers, statuses, timestamp forms,
all six false assurance flags, simulator mode and existing v1 canonical profile.
Reject unknown versions/fields, duplicate keys, trailing JSON, malformed UTF-8,
excessive nesting/string length/numbers, oversized input and checksum mismatch.
Compute CanonicalJson.sha256(payload), not a different canonicalization or file
byte hash. Preserve the existing v1 algorithm. PolicyVersion is a positive signed
32-bit integer; no float/overflow coercion. Use the published golden vector and
test harmless object-key/whitespace permutations plus changed payload/hash.

Success wording must explicitly say STRUCTURE_AND_CHECKSUM_VALID / UNSIGNED /
SIMULATOR_ONLY / NOT_AUTHORITY (fixed wording may be combined on a line). A matching
unkeyed checksum proves neither authorship, original request binding, complete
history, transactional snapshot nor real target effect. A deliberate payload+
checksum rewrite remains untrusted even when this mechanical check passes; test
and explain this boundary rather than claiming attack resistance. Reject a true
assurance flag even if its checksum is correctly recomputed.

Expose a documented source-free installDist Java launcher through Gradle's
application plugin, with no test classes/credentials and no Node/pnpm/Gradle
runtime prerequisite after build. Test both main entry and the actual generated
launcher with an existing Java21 PATH, private temp paths with spaces, positive
and malformed files, fixed diagnostics/exit codes. No automatic release publishing.

Run focused module tests, check/format/links; state build-cache/skips and anything
not exercised. Open one draft PR and leave it UNMERGED. Return the plan's exact
TRACEPERMIT HANDOFF, commit/push a coherent checkpoint, explicitly stop editing
and wait. No reset/rebase/clean/force-push/merge or provider/hosting permission
change. No sensitive raw values in Git, output, PR or handoff. Final backend→UI→
checker integration belongs to the Mac after reviewed merges.
