# Operation row-lock expiry fixture qualification

Requirements: FR-003/007/012; SEC-002/009; REL-001/004; OPS-004.
This is an internal-library test repair, not a new authority path or deployment
clock/failover qualification. Production Java and migration bytes are unchanged.

## Retained failure and diagnosis limits

The first Mac PR108 `pnpm check` failed the existing PostgreSQL
`authenticationDeadlineIsRecheckedAfterAnActualHeadRowLockWait`: the withdrawal
unexpectedly succeeded (244/245 operation cases passed). Keep this failed run;
later success cannot reclassify it. The test created the deadline before fixture
connection/lock setup and released the row after waiting on the independent Mac
clock, whereas production checks writer time after locking.

A later Docker-info timestamp looked far behind the Mac; a fresh owned networkless
container disagreed with that diagnosis and matched the Mac within its one-second
resolution. A diagnostic-only run of the original PostgreSQL test then passed:
fresh JDBC writer-time offset was bounded by [-4,0]ms across the observation, and
the writer was165ms beyond the deadline at release. This does **not** reproduce
or prove the original transient clock condition. No Docker restart, time change,
timing allowance or runtime repair was performed. The proven fixture weakness is
that host time did not itself establish that the authoritative writer deadline
had elapsed, and the setup could consume the pre-lock validity window.

## Stronger fixture, unchanged limits

The shared test now acquires the real holder row lock first, samples fresh writer
time to create the same800ms fixture authority window, starts the contender and
proves it reached the lock before expiry. It then samples fresh writer time until
the same150ms post-deadline margin and proves the contender is still blocked.
Release the lock and require `OPERATION_AUTHENTICATION_EXPIRED` with no journal
fact. Equality remains expired in production; no rule or lifetime was broadened.

The5s contender signal,4s monotonic wait bound,20ms polling interval,10s result/
worker cleanup bounds and real SQL lock remain. The fixture's extra writer-time
query is bounded to1s. PostgreSQL uses `clock_timestamp()` rather than a frozen
transaction timestamp; MariaDB uses a new `UTC_TIMESTAMP(6)` statement. These
fixture timestamps are not assertions about verified provider clocks or allowed
production skew. There is no availability skip, substitute engine or fake clock.

## Executed controls

The repaired focused case passed on PostgreSQL16.4, MariaDB11.4.13 and MariaDB10.6.25.
Temporarily moving **only** production's time sample before the row lock made all
three cases fail with “Expecting code to raise a throwable.” Production was then
restored byte-for-byte. This control proves the fixture still detects the exact
pre-lock-time bypass rather than merely waiting for a green result.

After restoration, the forced complete operations suite passed245 cases:78 per
exact engine and11 strict-JSON cases, zero failures/errors/skips; all14 Gradle
tasks executed with build/configuration caches disabled, in1m34. Capture XML only
after a completed run, and serialize later focused/broad tests that share
coverage/result outputs. The initial diagnostic pass is not substituted for this
restored full-suite result. Repository-wide qualification is a separate gate.

```bash
pnpm test:java-operations --rerun-tasks --no-build-cache \
  --no-configuration-cache --max-workers=1 --console=plain
```

No live database, identity provider, target, replica, restart/failover or customer
environment was contacted. The new operation review/admission composition remains
unwired; these existing withdrawal tests do not certify it.
