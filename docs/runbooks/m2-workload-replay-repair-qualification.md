# Apple Silicon PR113 replay-repair qualification

Date: 2026-10-25 (Asia/Kolkata). This record independently qualifies the bounded
repair in [PR113](https://github.com/hemantkgupta/tracepermit/pull/113) at frozen
commit `996b7d1dc135f5c0e922007975f7d36870c128dc`, compared with reviewed commit
`72e1fca62e9d616b9da14e31bc666255cfb1105b`. The PR base was
`0bc6b618aa9eac7df3bacf7a6d4bcafe2a824c4f`. The producer branch remains owned by
its producer; this qualification uses `codex/m2-replay-repair-qualification`.

Requirements referenced: FR-008/011, SEC-006, REL-001/004 and OPS-004. These are
partial evidence pointers, with no promotion of whole-requirement status.
No production code, migrations, existing tests, dependency versions, CI,
thresholds, or global specification indexes were changed. The only additions
are this record and `ReplayRepairAcknowledgementTest` in the existing
`persistence-identity` test sources.

## Environment and isolation

- Apple Silicon host: `arm64`, 16 GiB RAM, approximately 29 GiB free before the
  database runs. Docker engine: Linux `aarch64` / `arm64`.
- Java and javac: Homebrew OpenJDK `21.0.13.0.2`, registered with jEnv `0.6.0`.
  The checked-in `.java-version` pin was preserved; the run selected this
  existing Java 21 JDK explicitly through `TRACEPERMIT_JAVA_HOME`.
- Node `22.23.4`, pnpm `11.25.0`, Gradle wrapper `8.14.3`, Docker Desktop
  `4.96.3 (244472)`, Docker engine/client `29.9.2`.
- The original checkout remained clean on `main` with HEAD/local `origin/main`
  at the PR base and zero upstream divergence. A separate detached sparse clone
  was populated at the frozen repair only after excluding `agentcore-work/`.
  That directory was absent throughout. No private planning, credentials,
  dependency-directory symlinks or another environment's local receipts were
  copied into the checkout.
- Repository dependencies were installed using the frozen lockfile. The exact
  Gradle distribution was downloaded into the wrapper cache after Java's initial
  download timed out; SHA-256 matched the checked-in value
  `bd71102213493060956ec229d946beee57158dbd89d0e62b91bca0fa2c5f3531`.
- GitHub CLI was installed but had no CLI login. Repository reads/publication
  can use the connected GitHub account without exporting credentials.
- Docker startup required user completion before qualification resumed. No
  database tests are attributed to the earlier period when Docker was unavailable.
- One Gradle/test/coverage writer ran at a time, with one Gradle worker. Original
  internal test concurrency, including the 16-store races, was preserved.
  Only disposable Testcontainers resources created by these runs were stopped.

All three exact images ran natively as `arm64`; no engine substitution or
architecture emulation was used. Observed image digests:

| Image                  | Digest                                                                    |
| ---------------------- | ------------------------------------------------------------------------- |
| `postgres:16.4-alpine` | `sha256:5660c2cbfea50c7a9127d17dc4e48543eedd3d7a41a595a2dfa572471e37e64c` |
| `mariadb:11.4.13`      | `sha256:250e1609e93c0e6aecf64bfbab2d84d5e89d2481540fe77447ff6c855c3b1873` |
| `mariadb:10.6.25`      | `sha256:6055d4ee6a6045626442cafe4feaf7419e878c65baf3af658265e24114af1cd7` |

## Repair evidence

The original findings are in the
[published review](https://github.com/hemantkgupta/tracepermit/pull/113#issuecomment-6394443819).
The actual repair diff, ADR0080, the replay-store runbook, learning note,
interface specification and implementation/tests were inspected.

| Original finding                                        | Implemented control and fresh evidence                                                                                                                                                                                                                                                                                                                      | Qualification limit                                                                                                                                                                                                                |
| ------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Private causes/suppressed cleanup diagnostics           | `WorkloadIdentityReplayException` disables suppression, stores a null cause and prevents later cause attachment. The unit test attempts both attachments; `combinedErrorAndCleanupFailureNeverLeaksSuppressedOrCanary` exercises combined synthetic DML/close failures on each engine.                                                                      | Fixed replay exceptions are covered; this is not a claim that arbitrary JDBC, datasource or application logging is sanitized.                                                                                                      |
| Unbounded DML and acknowledgement after expiry          | Both claim INSERT and counter UPDATE receive finite JDBC query timeouts. Trusted caller `now` plus monotonic elapsed time is rechecked after borrowing, DML, commit and cleanup. The existing real counter-lock test rolls back to zero writes after expiry. Added tests observe actual statement timeouts and successful JDBC calls delayed across expiry. | Whole-second statement timeouts are not a total wall-clock deadline. Pool acquisition, metadata/driver work, transaction setup, commit, rollback and cleanup need finite datasource/deployment bounds.                             |
| Different instances silently adopt different capacities | V2 persists capacity. Construction validates local agreement, and the counter UPDATE includes the persisted/local capacity match. Existing tests reject conflicting reconstruction and retain exactly one final-slot winner among 16 stores with distinct tokens.                                                                                           | Requires a trusted operator/database. This does not fence privileged resizing, database restore, failover or backup rollback.                                                                                                      |
| Validate-only startup accepts a missing physical column | The validator inspects actual column names, nullability, selected types/widths/collations and primary keys. Existing tests drop `expires_at` while retaining migration history and require both validate-only startup and new construction to reject it on every engine.                                                                                    | This is partial physical validation, not complete schema attestation. State-column type checks, every CHECK constraint/index definition, timestamp precision and all semantic DDL equivalences are not established by these tests. |

The V1 migration files remain byte-for-byte unchanged by the repair; the diff
adds explicit V2 capacity migrations and an upgrade path. Existing test evidence
also selects AFTER_COMMIT, checks rollback after a real successful INSERT followed
by a counter failure, and exercises restricted runtime grants. These are producer
tests executed independently here, rather than independently authored replacements.

## Additional regression gap

`ReplayRepairAcknowledgementTest` adds four parameterized cases on each exact
database profile (12 invocations):

1. A real commit succeeds before its return is delayed past expiry. The store
   must raise `REPLAY_COMMIT_UNKNOWN`, close the connection, retain exactly one
   durable claim/counter increment and reject replay.
2. A real committed connection successfully closes before its return is delayed
   past expiry. The same conservative acknowledgement and retained-claim
   assertions apply.
3. A successfully borrowed connection is returned after expiry. Consumption must
   return false, close the connection, execute no DML and write nothing. A fresh
   valid offer can subsequently consume that token.
4. Both real DML statements expose a two-second configured timeout for a long
   validity window, and a one-second timeout when remaining validity is shorter
   than the default DML budget. Successful commit/cleanup remain positive controls.

The delay helper wraps real JDBC connections/statements, never fabricates a
commit and never injects a commit/close exception in these cases. For the expiry
tests it asserts the selected stage was reached while the token was still valid,
then delays its return until after the unchanged deadline. Committed state is
read through a separate datasource/store. Synthetic credentials are created
in memory, and each fixture owns its disposable database.

These cases fill an acknowledgement-timing gap. Existing exception faults,
capacity races and missing-column cases were left intact.

## Commands and results

Use an installed Java 21 JDK and native Docker. For a Docker Desktop setup
without a host `/var/run/docker.sock`, the test process can use:

```sh
export TRACEPERMIT_JAVA_HOME=/path/to/existing/java-21-jdk
export DOCKER_HOST="unix://$HOME/.docker/run/docker.sock"
export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock
export GRADLE_OPTS="${GRADLE_OPTS:-} -Dorg.gradle.workers.max=1"
bash scripts/test-java-environment.sh
corepack enable
pnpm install --frozen-lockfile
bash -c 'source scripts/configure-java-environment.sh &&
  java/gradlew -p java :persistence-identity:test --no-daemon --max-workers=1 \
    --rerun-tasks --no-build-cache --no-configuration-cache'
pnpm check
pnpm format:check
pnpm docs:links
pnpm docs:flows:check
git diff --check
```

The Page's wrapped module command was resolved to `:persistence-identity:test`;
the format/docs/flows gates use the repository's actual check script names.

| Run                                                      | Result                                                                                                                                                                                                                                                                                                  |
| -------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Frozen repair, complete fresh persistence-identity suite | PASS: 25 tests each PostgreSQL/MariaDB11.4/MariaDB10.6 plus 10 unit tests; 85 total, zero failures/errors/skips. All 8 actionable tasks executed with build/configuration caches disabled; 1m29s including first-use setup.                                                                             |
| Added regression class, fresh targeted run               | PASS: 12 invocations, zero failures/errors/skips. All 8 actionable tasks executed with both caches disabled; 27s.                                                                                                                                                                                       |
| `pnpm check`                                             | PASS: console build/typecheck and 124 tests; 1,922 Java cases, zero failures/errors and one existing opt-in skip. Coverage LINE 94.074%, BRANCH 77.970%, above unchanged 94%/75% gates. Java checkAll finished in 8m46s; 69 actionable tasks, 62 executed and 7 up-to-date; configuration cache stored. |
| Formatting, links, flows and diff whitespace             | PASS: pnpm format:check; pnpm docs:links (1,120 local links); pnpm docs:flows:check; git diff --check, including both additions.                                                                                                                                                                        |

Saved local receipts distinguish the frozen 85-case run from the targeted
12-case run. The full check executed all 97 replay tests together with zero
failures/errors/skips, while reusing some compiled artifacts as stated above.
The one full-check skip was
`JavaBackendEndpointConnectorE2eTest.installedArchiveConnectorAgainstRealBackendWithNativePty`,
an existing opt-in installed-archive/native-PTY qualification case. It was not
enabled or represented as passing. No hosted check is represented as a local
execution, and no historical receipt is represented as a fresh result.

## Scope of the conclusion

This evidence supports the four bounded repairs and the added successful-call
expiry regressions. It does not establish production readiness, complete schema
attestation, crash/restore/failover fencing, all distributed schedules, OBO,
authentication, business-operation admission, execution permits or target-side
exactly-once effects. The library remains standalone and unwired.

Datasource deadlines remain deployment prerequisites. JDBC whole-second timeout
granularity and driver enforcement are distinct from rejecting authority after
an expired call eventually returns. Acknowledgement uncertainty can coexist with
a committed claim; callers must not treat it as permission to repeat a side effect.

The existing producer runbook mentions a default capacity of 65,536, whereas the
frozen Java constant and V2 migration default are 10,000. This qualification uses
the actual constant and leaves correction of producer documentation to its owner.
No AWS, IdP, customer system, live target repository, public listener or tunnel
was used. A passing suite is not a blanket security certification.
