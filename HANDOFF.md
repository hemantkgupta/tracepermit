# TracePermit implementation handoff

GitHub is authoritative. Credentials, private customer/provider planning and local
operator values remain outside Git. Historical receipts are not fresh results.

TRACEPERMIT HANDOFF

- Task: Finish PR103 CI scheduling optimization against current main, preserving every required gate and leaving the PR unmerged.
- Repository: https://github.com/hemantkgupta/tracepermit; Mac /Users/hemantkgupta/code-all/tracepermit-parallel-ci.
- Branch: codex/parallel-ci-verification.
- Base commit: 3eadd8220cd0a78dc07d9830d3f0959515cddf16 (main, merged94/100/102).
- Latest pushed commit: preceding producer checkpoint365d5dd1c54e3eab2155537f2ffe3d86357fbe46; this normal reconciliation checkpoint follows. Resolve the final SHA from PR103 and the final GitHub handoff.
- PR URL: https://github.com/hemantkgupta/tracepermit/pull/103 (open/unmerged; existing draft/review-ready state unchanged).
- Branch owner now: Mac coordinator sole writer under the human assignment, after producer completion/idle and clean exact-head verification. Final GitHub handoff must release writing.
- Working tree status: clean365d5dd with upstream0/0 before normal merge of current main; workflow and quality-spec conflicts resolved preserving both changes. No reset/discard/rebase or other-checkout edits.
- Completed: retained six independent clean-runner jobs and final fail-closed verify; required core now contains the aggregate suite and both forced PostgreSQL/MariaDB gates. Preserved runtime, action pins, permissions, triggers, database workload/coverage and independent browser/registration jobs. Added current-main command-inventory and MariaDB-ordering regressions plus unknown-result cases. Updated README, quality specification, traceability and handoff.
- Remaining: finish local full checks and exact-head hosted qualification/timing; normal commit/push first after focused/lint/docs checks. Both local and hosted gates must pass before final completion. No guaranteed speed or production claim.
- Files materially changed: workflow and quality-spec conflict resolution, workflow regressions, README clarification, traceability and this handoff. Incoming main code is unchanged.
- Tests passed: fresh 37/37 workflow cases; actionlint 1.7.12; frozen install; format; 1,067 documentation links; 13 synchronized flow tests/drift; diff checks. Applying the new suite to frozen 365d5dd gives exactly two expected failures for the newly required MariaDB gate/inventory (35 pass), then all 37 pass after reconciliation. Independent current-main comparison retains 29 distinct verification commands/31 occurrences, adding only the workflow regression step; triggers, permissions and action pins match, browser/registration job bodies are unchanged. Initial producer all-six-job/final-verify run 8m39s is historical; not qualification of this head.
- Tests failed/not run: expected frozen negative-control failures only; fresh broad local and hosted checks are pending. No live or production qualification.
- Exact next command: pnpm check
- Required environment: Java21, Node22+, pnpm11.25.0, Docker/disposable fixtures, actionlint and GitHub access. One Gradle worker for local broad checks.
- Local-only/manual steps: frozen counterexample fixture and logs stay outside Git under /tmp/tracepermit-pr103-qualification.8dRhmM; source and final sanitized handoff are transferable without these files. No private agentcore-work files read/published.
- Security constraints: no missing/skipped/allowed-failure gate, lower coverage, reduced workload, runtime or branch-protection change, artifact trust shortcut, privileged token permissions, live action, public ingress/tunnel, paid fallback, Docker restart, force-push or PR merge.
- Known limitations: this changes scheduling only. Both PostgreSQL passes and forced MariaDB remain required; runner minutes may rise. One historical faster run is not a guaranteed speedup. Existing installation/live-provider/production limitations remain unchanged.
- Safe for another environment to continue: read-only review only until final GitHub handoff releases the Mac at a verified clean pushed SHA.

## Preserved prior evidence

- [Producer CI run](https://github.com/hemantkgupta/tracepermit/actions/runs/40093825438)
  passed at365d5dd before MariaDB integration, with an observed8m39s completion.
- [Merged MariaDB handoff](https://github.com/hemantkgupta/tracepermit/pull/94#issuecomment-6384587169)
  and [PR100 handoff](https://github.com/hemantkgupta/tracepermit/pull/100#issuecomment-6384195456)
  preserve their original evidence and limits. These receipts do not replace this
  reconciled workflow's exact-head qualification.

## Wave 1 credential-provider lane checkpoint — PR105 only

The earlier record is preserved from the frozen base as historical evidence; it
does not assign this branch. This scoped record follows the wave-one shared
protocol and preserves other lanes' claims. No private inputs were used.

TRACEPERMIT HANDOFF

- Task: Implement the unwired GitHub App installation credential-provider foundation; synthetic proof and bounded delivery.
- Repository: https://github.com/hemantkgupta/tracepermit
- Branch: codex/github-app-installation-credentials
- Base commit: f23dc22f056058d7977715e0878c284728d10d17 (frozen main).
- Latest pushed commit: public ownership/plan checkpoint b944f05; this tested source/evidence checkpoint follows. Resolve exact final pushed head from PR105 before taking ownership.
- PR URL: https://github.com/hemantkgupta/tracepermit/pull/105 (draft, open, unmerged).
- Branch owner now: cloud credential writer through final publication; final documentation/PR handoff explicitly releases writing. Mac operation binding/lifecycle, Gemini OIDC/MariaDB design and cloud setup lanes are untouched.
- Working tree status: scoped Java/docs checkpoint pending normal commit/push; no merge/rebase/force-push or other-checkout edits.
- Completed: immutable operator App/installation/owner/repository/minimum-permission configuration; explicit private POSIX PKCS#8 DER source; bounded RS256 JWT; fixed HTTPS/public DNS/platform TLS/no-follow-up exchange; typed installation GET and one restricted token POST; response assurance/expiry-after-cleanup; no cache; fixed cause/suppression-safe diagnostics. ADR0076, runbook, learning, scoped specifications/traceability and official-source facts recorded. Requirements: FR-001/008/009/012; SEC-001/002/003/012/015/019; REL-001/003/005/009; OPS-002/004; no whole-ID promotion.
- Remaining: independent review, exact-head hosted gates and the failed local native process-cleanup qualification. No unrelated repair/retry or merge authorized. Live response compatibility, private-key custody/installation, durable operation composition and target wiring remain separate.
- Files materially changed: seven runtime and two test files in github.credentials; ADR0076, dedicated runbook/learning/plan; appended ADR0073/preflight-runbook context, specs README/08/11/12 and this scoped handoff. Shared-doc overlap with PR95/101 is acknowledged; no other lane's claim is rewritten.
- Tests passed: frozen install;143 new focused cases;443 complete remote cases (300 retained+143 new), fresh again after guard restoration; no focused/remote failures/errors/skips. Removing two identity comparisons caused three expected failures among44 cases, then exact restoration. Build/typecheck,124 React tests and Java environment check passed; formatting, docs links,13 synchronized flow tests and whitespace passed. Final exact counts are recorded in PR105.
- Tests failed/not run: one pnpm check FAILED: HTTP428=424 passed,3 unchanged native process-tree cleanup failures,1 existing installed-archive/native-PTY skip. Failures: processTreeTrackerHandlesRootEarlyExitWithoutProcessNameKills, processTreeTrackerEscalatesToForceKillWhenTermIgnored, ptyProcessTreeCleanupTerminatesAllDescendantsWithNoLingeringProcesses; existing termination helper reported cleanup unconfirmed. Later Java/forced PostgreSQL-MariaDB/aggregate coverage gates not reached; no broad retry. Hosted exact-head, live provider/target, custody/rotation, installed/public-edge or production qualification not supplied.
- Exact next command: gh pr view 105 --repo hemantkgupta/tracepermit (review candidate and recorded gates; no live action).
- Required environment: prepared Java21.0.12.1, Node22+ (observed24.19.0), Corepack pnpm11.25.0; managed Docker28.4.0/pinned fixtures for full checks; one Gradle worker. Existing prepared Corepack cache/lane-local shims selected without installing tooling or changing network policy.
- Local-only/manual steps: official-source downloads, JUnit/log/control receipts and caches remain outside Git. No real credential installation or provider call. Runtime-generated temporary key files only; no committed private/development key.
- Security constraints: existing provider port/adapter/preflight/action/canonical/permit/authority behavior unchanged; no operation bridge, target dispatch, public endpoint/tunnel, arbitrary origin/trust, paid API, cache/refresh loop or retry. Private agentcore-work excluded before materialization and unread. No secrets/raw fragments/causes/suppression in diagnostics; no other-lane or global Docker/process changes.
- Known limitations: strict organization/one-repository/three-permission/DER-POSIX profile; GitHub optional assurance omissions fail closed, so live compatibility is unqualified. Opaque token installation provenance trusts authenticated GitHub minting contract, not independent introspection. Local key I/O and Java secret memory are not universal deadline/erasure guarantees. A failed mint/cleanup may leave an unused token until expiry. Local whole-repo validation is red; zero running test containers is not confirmation of the failed native process cleanup.
- Safe for another environment to continue: yes for read-only review; writer transfer requires final explicit release and verified exact pushed clean head.
