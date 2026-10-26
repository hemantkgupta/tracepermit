# GLM goal: close two residual merged PR65 harness defects

Repository: https://github.com/hemantkgupta/tracepermit
New branch: `codex/qualification-provenance-cleanup-repair`.
PR65 is MERGED. Do not reopen/write its retired producer branch or duplicate the
new approval export. One writer: GLM after founder forwarding. Fresh isolated
checkout; inspect/report branch/HEAD/main/status/divergence and preserve all files.

Read AGENTS.md, specs README and the endpoint-distribution qualification tests.
The reviewed merged baseline is b38e9289d791ffabc1e2142357fc8366301ee201.
Inspect newer main before starting; do not silently overwrite a concurrent repair.
Exclusive scope: `java/adapters-http/build.gradle.kts`,
`scripts/endpoint-connector-distribution.test.mjs`, NEW focused helper/regression
tests as needed, and a qualification-only runbook. No Java/connector runtime,
approval/export/UI/identity/permit/policy/schema/migration/dependency/security-rule
changes. No shared traceability/ADR/site/Notion edits; integrator owns those.

1. ACTUAL Gradle provenance path: current `gitHeadProvider` uses
   providers.exec with isIgnoreExitValue=true and forwards nonblank stdout as the
   expected commit. Installed Java tests then trust that configured value, so the
   separately tested bounded resolver is bypassed. A private Git wrapper returning
   a valid OLD SHA then exiting73 caused an actual strict installed test against a
   stale archive to PASS; with normal Git the same stale archive correctly FAILED.
   Fix at the real Gradle/provider boundary. Nonzero/missing/malformed/ambiguous Git
   result or finite deadline must fail closed, never authorize stale provenance.
   Test the actual Gradle→test wiring with synthetic wrapper/repository, not only
   a new unused resolver helper. Retain explicit operator-provided exact-head
   strict-mode checks and current archive inputs/cache semantics. No new package
   release claim merely because helper unit tests pass. Do not run an unbounded
   stalled-Git probe; bound both parent and child and clean only owned processes.
2. Cleanup diagnostics: current gracefulTerminate embeds termError.message and
   killError.message in a returned cleanup exception. A synthetic kill exception
   can leak an opaque sensitive canary with or without a primary failure. Replace
   raw messages/causes with fixed safe phase categories, preserve the original
   failure/nonzero result, prove whether shutdown is confirmed, remove listeners,
   and never silently accept an unconfirmed child exit. Test both primary-present
   and primary-absent paths plus cooperative success. Label synthetic no-exit/
   throwing-kill models honestly; do not claim OS unkillability qualification.

These are assurance/test-harness defects, NOT a demonstrated runtime authorization
bypass. Do not weaken strict tests or change docs to claim the defects impossible.
Use synthetic values only, bounded buffers/waits and owned disposable fixtures.
No Docker restart, arbitrary port/process/container kill, broad delete, real
credentials/targets, public listener/tunnel or external service changes.

Run focused sensitivity/positive controls, affected installed/native/packaging
checks with exact current clean archive as needed, required check/format and
links once at final coherent head. Clearly distinguish cached/skipped results and
intentional red controls. After two unsuccessful repairs return diagnosis instead
of expanding scope. Open ONE draft PR, leave UNMERGED, return exact-head CI and
the audit-ready plan's exact TRACEPERMIT HANDOFF; commit/push and explicitly stop
editing. No reset/rebase/force-push/merge without founder approval. The integrator
will review the actual integration path independently.
