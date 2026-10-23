# TracePermit implementation handoff

This file records only transferable repository state. It contains no provider
identifiers, tokens, authorization codes, PKCE values, cookies, passwords,
client secrets, private keys, raw client request keys, customer identifiers or
local private paths.

TRACEPERMIT HANDOFF

- Task: Persist immutable initial business-operation registrations with scoped request-key deduplication on MariaDB and PostgreSQL.
- Repository: https://github.com/hemantkgupta/tracepermit
- Branch: codex/durable-business-operation-repository
- Base commit: 7847e890317bdcbeb3659352f3b31ba66f0eae8f
- Latest pushed commit: functional checkpoint `2c352fff944298e3eb31ca89e3e4b6c4fc387cad`; this handoff-only commit follows it
- PR URL: https://github.com/hemantkgupta/tracepermit/pull/98
- Branch owner now: unassigned after this handoff push; the Mac thread has stopped editing the branch; draft PR #98 is the coordination point
- Working tree status: clean after the final handoff commit and push
- Completed: application registration port and separate trusted scope; isolated JDBC module with explicit empty-database installation and validate-only startup; primary scoped-key arbitration; original-record retry return and changed-binding conflict; atomic initial snapshot plus three facts; commit/cleanup uncertainty; integrity-checked scoped reads; restricted runtime grants; shared engine tests and CI job; ADR 0070, runbook, learning/specification/traceability and public capability updates
- Remaining: CI on the pushed commit and independent review; leave PR #98 open and unmerged; reconcile shared Gradle/workflow/docs edits when PR #94 or #96 lands; build authority-bearing operation lifecycle/service composition before exposing an operations API or AgentCore ingress
- Files materially changed: application BusinessOperation port/scope/result/exception and scope tests; new persistence-operations module, migrations and shared tests; Gradle settings; focused test script/package command; CI workflow; ADR 0070 and repository runbook; learning/specifications 03/05/07/08/12 and index; working-today site page; this handoff
- Tests passed: shared repository gate (108 tests, zero failures/errors/skips on PostgreSQL 16.4 and MariaDB 11.4.13/10.6.25, including 20 mixed-payload races per engine); pnpm check (124 React tests; 1,302 Java tests with zero failures/errors and one existing installed-archive skip); aggregate coverage 94.23% line / 77.96% branch; formatting; documentation links (1,001); flow checks (13 tests); site build and six site-verification tests; actionlint; shell syntax; git diff --check
- Tests failed/not run: no unresolved final local failure; an observed InnoDB 40001/1213 deadlock during early mixed-payload probes was repaired by making the scoped request key primary while preserving all assertions and adding repeated probes; the initial site build needed its frozen npm dependencies installed; the installed-archive connector E2E remained skipped because its archive was not prepared; live RDS/TLS/replication/failover, AgentCore, external IdP, S3 and real GitHub target work were not run
- Exact next command: gh pr checks 98 --repo hemantkgupta/tracepermit --watch
- Required environment: Java 21, Node.js 22+, Corepack/pnpm 11.25.0, Docker for the three pinned database images; repeating site verification also requires npm --prefix site ci
- Local-only/manual steps: generated test credentials and disposable containers are not transferable artifacts; source/build caches remain ignored; the four customer planning files remain outside Git in the separate shared checkout and were not copied or committed here
- Security constraints: derive tenant/workspace/requester scope from verified adapter context; registration never grants authority or dispatches a target; runtime uses a dedicated writer database with SELECT/INSERT only; never log connection details or payloads; no credentials, customer identifiers, planning files, real target calls, force-push or merge
- Known limitations: initial registration only and unwired; no lifecycle mutation, API authentication/authorization, arbitrary-argument redaction/encryption, retention execution, recovery fencing, MariaDB approval/evidence parity, S3 publication, RDS/failover qualification, EKS or production claim
- Safe for another environment to continue: yes, after explicit branch ownership transfer
