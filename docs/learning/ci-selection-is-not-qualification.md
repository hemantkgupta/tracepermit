# CI selection is not full qualification

CI uses an explicit path allowlist to avoid running backend tests for prose/site
or console-only changes. It reads the policy from the PR base, includes deleted
and renamed paths, and treats unknown changes as a full run. It does not ask an
LLM whether a security test is relevant. A docs-only pass is evidence for its
selected checks; it is not a new Java qualification. Pushes to `main` run all gates.

The active PostgreSQL and MariaDB 11.4 operation suites keep every
concurrency/expiry/role/rollback assertion. MariaDB 10.x integration runs are
retired; historical results are not a fresh compatibility guarantee. Core
keeps common tests and automatically includes new modules. Database results are
fresh, and coverage combines every lane over the complete production class set.
Missing or stale evidence fails rather than making the coverage percentage look
better. The final `verify` gate accepts only explicitly planned skips.
The complete 94%/75% production-class coverage floors remain unchanged.

An interrupted caller also illustrates why retrying a failed security test is
insufficient: a Future can finish before its caller checks interruption. The
pilot diagnostic now handles pre-existing cancellation before creating the
worker, and the test proves no provider starts. This repairs the race while
preserving the failure contract; it does not increase the timeout or accept a
different result. See [ADR0088](../architecture/0088-selective-ci-and-complete-coverage.md).
