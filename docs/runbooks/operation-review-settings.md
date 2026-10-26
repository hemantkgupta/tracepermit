# Qualifying explicit operation-review inputs and settings

Scope: [ADR0084](../architecture/0084-durable-review-membership-and-lifetime.md)
input/settings prerequisites only. Requirements: FR-003/007/008/012;
SEC-001/002/003/005/009/012; REL-001/002/004/005; OPS-002/004.
No whole-requirement or production-readiness promotion.

## Contract

- Operator settings have no group, enrollment-source or lifetime default.
  Lifetime must be positive and at most five minutes; seconds and nanoseconds
  remain exact. Missing values and malformed/unbounded identifiers reject.
- The explicit profile v2 includes these settings in its hash. The legacy v1
  recipe remains unchanged; the new group/evaluation checks refuse v1 settings.
  A `require_approval` policy result must select the configured exact group.
- Both approve and deny require the independently authenticated human reviewer,
  matching tenant/workspace, reviewer role and decision scope, non-self-review,
  and exact independently verified principal/group/enrollment provenance.
  Membership cannot outlive authentication, start in the future or be expired.
  Equality is expired. Internal carriers are trusted-adapter data, not proof.
- Evaluation independently requires `operation_requester`/`operations.evaluate`
  for the exact requester and workspace. Cancellation scope is insufficient.
  Authentication and generation are checked again at supplied writer time.
- Existing V3 control pins bind the settings through the profile hash. A changed
  group/source/lifetime cannot pass current control; replacements require a
  greater generation and explicit activation. Runtime grants do not change.

Neither a supplied instant nor an expected state token proves writer time or
current database state. The future transaction owner must load/lock all current
authority and then sample database time. No network lookup belongs under locks.

## Reproduction

Use a verified existing Java 21 **JDK**, with both `java` and `javac` major 21,
Node 22+, pnpm 11.25.0 and Docker. Set `TRACEPERMIT_JAVA_HOME` to that JDK when
platform discovery selects another installed version. No machine-wide install
or gate change is required. Run from the repository root:

```sh
pnpm java:env-check
bash -c 'set -euo pipefail; source scripts/configure-java-environment.sh; exec java/gradlew -p java :application:test --tests "*BusinessOperationReviewSettingsTest" --tests "*BusinessOperationAuthorityProfileTest" --tests "*BusinessOperationReviewTest" --no-daemon --rerun-tasks --no-build-cache --no-configuration-cache --max-workers=1 --console=plain'
pnpm test:java-operations --rerun-tasks --no-build-cache --no-configuration-cache --max-workers=1 --console=plain
pnpm check
pnpm format:check
pnpm docs:links
pnpm docs:flows:check
git diff --check
```

`--max-workers=1` limits Gradle task scheduling, not the test's internal races,
deadlines or concurrency. No test or coverage gate is disabled.

`BusinessOperationReviewSettingsTest` independently reconstructs the v1 hash,
changes each v2 settings pin, checks exact policy group, exercises both review
decisions and membership/time substitutions, and separates evaluation permission
from cancellation. The shared control test runs on PostgreSQL 16.4 and MariaDB
11.4.13/10.6.25: changed pins fail, same-generation replacement fails, new staged
settings remain inactive until explicit activation, and all operation/journal
tables remain empty. It does not model a complete reviewer transaction.

## Qualification history and limits

An initial local command used a nested login shell that selected the wrong Java
installation. Although its 26 selected application cases passed, the command
failed the unchanged deprecation-as-error build gate and is **not** a qualified
run. Explicit selection of the already installed compiler JDK 21 followed by
strict shell setup produced a fresh successful 26-case run, 11 tasks executed.

The first new three-engine case failed all three fixtures because it expected
`OPERATION_AUTHORITY_NOT_READY` for mismatching pins. Source/XML established
the existing correct result `OPERATION_PROFILE_MISMATCH`; only new test
expectations were corrected. Matching but staged pins still require
`OPERATION_AUTHORITY_NOT_READY`. The corrected three cases passed freshly.

Omitting the new v2 settings pins and exact membership-source guard made both
selected distinguishing cases fail. Both production files were restored
byte-identically; the26 application cases then passed freshly in7s with11 tasks
executed. The complete forced/cache-disabled operation suite passed313 cases
across8 suites, zero failures/errors/skips,14 tasks executed in2m29. Full
`pnpm check` passed124 React and1919 recorded Java cases/152 suites, zero
failures/errors and1 existing absent-archive/native-PTY skip. Its Gradle gate
executed10 tasks with55 up-to-date in3m7, so not all tests were fresh. Aggregate
coverage94.5760% line/78.9952% branch passed unchanged94/75 thresholds. See
[HANDOFF](../../HANDOFF.md) and the exact-head PR receipt for publication status.
Deliberate local mutations must always be restored before qualification or
publication.

The atomic pending-binding/deadline writer, versioned decision journal and
reader compatibility, strict loaded GitHub target join, approval transaction,
fully bound permit, single-use admission, attempt/evidence/outbox and synthetic
dispatch remain unimplemented. The existing review actor and migration bytes
are unchanged. No IdP enrollment verification, endpoint, credential minting,
live target call, immediate revocation or paused-sender/restore fence is enabled.
