# ADR0094: retire MariaDB 10.x automated qualification

Status: implemented; validation pending. Requirements: FR-008; SEC-005/006/009;
REL-001/004/009; DATA-001; OPS-002/004. No whole-requirement promotion.

## Decision

The owner no longer requires MariaDB 10.x integration qualification. Default
`pnpm check`, Java `checkAll`, focused Gradle test tasks and CI therefore exclude
MariaDB 10.x engine-specific test classes across all modules. The standalone
permit-consumption and replay-repair parameterized fixtures use PostgreSQL and/or
MariaDB 11.4.13 as appropriate, without starting a 10.x container. The old
10.6.25-only classes are retained as historical source, not as active tests.

PostgreSQL 16.4 and MariaDB 11.4.13 retain their full existing cases, including
cross-process, expiry, contention, uncertainty, fail-safe and cleanup checks.
No assertion, test timeout, application deadline, admission rule or coverage
threshold is relaxed. The CI matrix drops only the 10.6 operation lane; the
complete production-class coverage denominator and 94% line / 75% branch floors
remain. Five source/run-bound JaCoCo manifests are required. Missing, stale,
corrupt, extra or incomplete evidence must fail the coverage gate.

The existing Java runtime still recognizes the historical 10.6.25 compatibility
profile. That behavior and its lightweight unit assertions are not changed by
this CI optimization, but no fresh 10.x integration qualification is claimed.
Historical reports, successful and unsuccessful, remain dated evidence for their
own source trees only. If 10.x support is requested later, restoring and running
its isolated engine qualification requires a separately reviewed change.

The integration-test source inventory checks direct 10.x image literals against
retired test class names and guards against accidentally excluding a non-10.x
test. It does not prove that dynamically assembled image names are absent.
Each retained 10.6 fixture must have an 11.4 counterpart. CI's existing selector,
the required `verify` gate, branch permissions, PostgreSQL and 11.4 safeguards
remain unchanged. This change is limited to synthetic fixtures and qualification;
it introduces no live target, authority or real credential.

## Validation and limits

The Java suite previously hit its reported 30-minute outer limit, and the
handoff records a stopped MariaDB 10.6 case under its unchanged test deadline.
The expected saving is the removed 10.x work; the exact duration and whether the
remaining suite completes within any external limit must be measured by the
human on this exact tree. Do not infer a pass from historical reports. The human
must run the focused scope, PostgreSQL/11.4 cases, `pnpm check` and
`pnpm format:check` under the manual validation workflow before any green claim.
