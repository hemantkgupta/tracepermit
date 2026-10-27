# Source-free local synthetic registration CLI

Status: local three-engine and repository qualification complete. This is not a
release or live-provider qualification.

This is partial FR-003/008/011/012, SEC-001/003/006/009/012, REL-001/002/004 and
OPS-002/004 evidence. [ADR0089](../architecture/0089-source-free-synthetic-registration-cli.md)
defines the trust/process boundary. The frozen producer is PR120 at
`f655db740f90d008f2d8237e0a3fe40baa82bc37`.

## Installed use

Unpack `tracepermit-synthetic-registration-0.1.0-SNAPSHOT.tar` outside a checkout.
Set `JAVA_HOME` to an existing Java 21 runtime. Start only one of these commands
from the unpacked `bin` directory, with an anonymous stdin pipe supplied by a
trusted local operator process:

```text
tracepermit-synthetic-registration check-config
tracepermit-synthetic-registration register-once
```

No secret belongs on these command lines or in environment variables. There are
no URL, file, profile-default, trust-boolean or target-execution switches. The
operator process must construct the private frames directly in memory, close its
pipe after the last frame, read the bounded receipt and check the exit status.
It must not persist/echo the frames. A shell redirect from a regular file is
refused. Source/build tools and test classes are not runtime prerequisites.

Each frame is a 4 byte signed big-endian positive byte length followed by exactly
that many bytes. `check-config` takes one UTF8 operator JSON frame and EOF.
`register-once` takes that operator frame, a UTF8 Authorization value frame
(including `Bearer `), the UTF8 request JSON frame, then EOF. Newlines have no
framing meaning. Bounds are 65536/16391/32768 bytes respectively. Extra frames,
missing EOF, duplicate/unknown fields, invalid UTF8, JSON suffixes and incomplete
length/payload data are refused before JDBC effects.

The operator JSON object requires exactly these fields:

| Field                  | Closed value                                         |
| ---------------------- | ---------------------------------------------------- |
| `schemaVersion`        | `1.0`                                                |
| `profile`              | `tracepermit-local-synthetic-registration-v1`        |
| `provider`             | Explicit public trust policy described below         |
| `enrollment`           | Exact operator workload enrollment described below   |
| `target`               | Strict existing GitHub draft profile described below |
| `replay`, `operations` | Distinct initialized dedicated local databases       |
| `replayCapacity`       | Agreed persisted capacity, integer 1..1000000        |
| `replayDmlMillis`      | Explicit finite DML budget, integer 1..10000         |

`provider` requires `issuer`, `audience`, `keyId`, `algorithm`=`RS256`,
`jwtType`=`at+jwt`, `publicKeySpki` (canonical Base64 DER RSA public SPKI,
2048..4096 bits), `tenantClaim`, `tenantValue`, mapped `tenantId`, exact `clientId`,
`maximumTokenLifetimeSeconds`, `keyCacheSeconds`, `refreshCooldownSeconds`,
`jwksUri`, `metadataUri`, `origin`=`CLI`, and distinct `supportedScopes` including
`operations.register`. The three durations are 1..900 seconds with the existing
provider relationships enforced. HTTPS provider/audience metadata remains offline;
the URI fields do not enable discovery, HTTP or remote key retrieval. The
authorization-server list is the explicitly supplied issuer; there is one client
and one tenant mapping. No private key or arbitrary JWT algorithm is accepted.

`enrollment` requires `verifiedSubjectId` (the exact issuer-qualified workload),
`tenantId`, `workspaceId`, `requesterId`, `clientId`, `retentionPolicyId` and
`retentionSeconds` (1 second..365 days). Tenant/client must match the provider policy.
These are local operator assertions checked against the real verified identity,
not fields a remote request may select.

`target` requires `adapterId`, `environment`, `recoveryGeneration`, `policyId`,
`policyVersion`, `policyContentHash`, `artifactDigests`, `installationId`,
`repositoryId`, `ownerId`, `repository`, `head`, `base`, `headSha`, `baseSha`.
The existing strict binding requires `github-draft-pr-v1`, dev/test/staging,
positive safe integers, SHA256 digests,40 hex commit SHAs, repository
`tracepermit/agentcore-pr-pilot`, base `main`, and a distinct valid head ref.
An artifact map has1..16 entries. No target call occurs.

Each database requires exactly `engine`, `host`, `port`, `catalog`, `user`,
`password`. Engine is `POSTGRESQL_16_4`, `MARIADB_11_4_13`, or `MARIADB_10_6_25`;
host is literally `127.0.0.1`; port is 1..65535; catalog/user match
`[A-Za-z][A-Za-z0-9_]{0,62}`; password is a nonblank bounded string without controls.
Different credentials do not make one database into two dedicated stores.
Runtime sets fixed connect 5s/socket 10s/query 5s/lock 5s limits internally; replay
DML also uses the supplied finite budget and token expiry. These do not enlarge
the 60s command budget. Owner initialization and minimum runtime grants remain
the existing producer deployment prerequisite; this command provides no DDL.

The untrusted request is the unchanged producer schema: required `schemaVersion`
\=`1.0`, `clientRequestKey`, `title`; optional nonblank `body`. The key is at most
128 ASCII characters matching `[A-Za-z0-9][A-Za-z0-9._:-]{0,127}`, title at most 256
characters and body at most 16384. No identity, trusted context, target, human
token, verification flag or delegation field is accepted.

## Receipts and recovery

The closed JSON receipt contains `schemaVersion`, `profile`, `outcome`,
`created`, `operationId`, `executionEnabled`. Execution is always false.
Only `CONFIG_VALID`, `CREATED` and `EXISTING` have zero exit status. Registration
success has a boolean `created` and the original operation ID; other outcomes
have null creation/ID fields. Receipt decoding strictly requires textual JSON AST
nodes for `schemaVersion`, `profile`, and `outcome`, rejecting numeric coercion
(such as numeric `1.0` or numeric outcomes) and non-string types as `UNKNOWN`.
Fixed failures are `USAGE`, `INPUT_INVALID`, `CONFIG_INVALID`, `NOT_READY`,
`REJECTED`, `UNKNOWN`. Raw exceptions never appear.

`UNKNOWN` is nonzero even if an independent observer finds committed data.
The producer's replay refusal code also covers uncertainty, so the CLI reports
it conservatively as unknown rather than promising rollback. Neither readback
nor a successful later lookup recreates a lost acknowledgement. Never retry
automatically. A separately supplied fresh verified token and the same scoped
request key may explicitly recover the immutable original metadata. Changed
content conflicts; a spent token cannot bypass replay by changing the key.
Replay and operation commits are separate, with no distributed atomicity claim.

The supervisor reserves 1s for sending the tiny receipt inside a 60s total command
budget and adds a separate 5s cleanup reserve. Slow/open stdin, blocked output,
worker exit and pressure remain bounded. Cleanup operates against a single
monotonic cleanup deadline: initial and arriving interrupts are collected and
preserved, forced worker exit is awaited and observed within the remaining
cleanup reserve, and owned pipe closer and output drainer threads are joined.
Unobserved exit or interrupt during cleanup reports `UNKNOWN`. Child termination
precedes pipe close; the process only terminates its own worker. Host suspend,
kernel stalls, process creation before handle ownership, SIGKILL and same-UID/root
interference remain deployment limitations. Keep the lid open and the host awake
for qualification.

## Qualification commands and evidence

Reuse the existing toolchain and pinned native-arm64 images. Select Java/javac21
process-locally using `TRACEPERMIT_JAVA_HOME`, the established Gradle cache and
existing Docker socket configuration. Do not install, restart, prune or rebuild
the environment. Run one heavy Gradle/coverage writer. For both 32-process races
on each engine, all 32 installed supervisor process handles are created and retained
first, all 32 live owned worker processes are positively observed blocked before
command by withheld protocol input, and work is released together with pre-release
and failure cleanup:

```bash
caffeinate -i java/gradlew -p java :agentcore-registration-cli:check \
  --rerun-tasks --no-build-cache --no-configuration-cache --no-daemon --max-workers=1
pnpm check
pnpm format:check
pnpm docs:links
pnpm docs:flows:check
git diff --check
```

`checkAll` includes every subproject's `check`, including this new module. The
aggregate 94% line/75% branch floors are unchanged. Freshness, counts, failures and
skips are recorded below. The archive test writes
`java/agentcore-registration-cli/build/distributions/cli-runtime-inventory.txt`
with its SHA256, actual Java version and sorted runtime inventory. Public-key
pins and locally generated tokens are synthetic compatibility evidence only.

| Gating evidence                                  | Boundary                                                                                                                                                                                                                                                                                                                                |
| ------------------------------------------------ | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Three `InstalledRegistrationContract` subclasses | Exact PostgreSQL16.4, MariaDB11.4.13 and10.6.25; archive unpacked outside every checkout with Java-only runtime PATH                                                                                                                                                                                                                    |
| Restart, replay, original-return, conflict       | Fresh processes, immutable operation/facts/head, spent tokens, exact counts and no authority effects                                                                                                                                                                                                                                    |
| Both 32-process races on each engine             | 32 supervisor handles retained first, 32 live owned workers blocked on withheld input positively observed, simultaneous release; one acknowledged creation for a shared token or 32 distinct tokens/one key; 31 original returns for the latter                                                                                         |
| Signature/enrollment/body/human negatives        | Real locally generated signatures; no request-derived authority or unauthorized writes                                                                                                                                                                                                                                                  |
| Schema/capacity/grants controls                  | Empty and malformed existing schemas, mismatch/saturation, actual privilege denials and valid admin positives; no repair/fallback                                                                                                                                                                                                       |
| Real `CommitGate` TCP fixtures                   | Forward real COMMIT, hold reply, independently observe durability, lose JDBC reply/kill worker/deny readback; unknown never OK                                                                                                                                                                                                          |
| `BoundaryTest`                                   | Closed nested schemas, strict AST string types for schemaVersion/profile/outcome with single-property negative vectors, malformed framing/UTF8/Unicode/JSON, offline DNS/network/JDBC guard                                                                                                                                             |
| `SupervisorTest`, `ArchiveBoundaryTest`          | Interrupted cleanup with live worker and active I/O awaiting forced exit within monotonic cleanup reserve, positive stalled children, blocked reads/writes, pressure, exit/interrupt, actual installed open-input watchdog, anonymous pipe provenance enforcement vs linked/unlinked named FIFOs, owned child/thread/connection cleanup |

### Completed local evidence

- Final fresh module run: 173 tests, zero failures/errors/skips in 6m17s. All 30
  tasks executed with `--rerun-tasks --no-build-cache
--no-configuration-cache --no-daemon --max-workers=1`; the three installed
  engine suites contain 33 tests each and retain both 32-process races.
- Required `pnpm check`: pass in 11m26s. React built, typechecked and passed 124
  tests. Java passed 2,445 tests with zero failures/errors and one existing
  opt-in native-PTY skip. `checkAll` included this module. Aggregate coverage was
  94.153% line and 77.893% branch against unchanged 94%/75% floors; 82 Gradle
  tasks were 29 executed, 24 from cache and 29 up-to-date.
- `pnpm format:check`, `pnpm docs:links` (1,160 Markdown links), `pnpm
docs:flows:check` (13 tests) and `git diff --check`: pass. The first final
  formatting check requested a runbook-only reflow; Prettier applied it and the
  repeated full formatting check passed.
- Final archive SHA256:
  `cf1aaeb5e93d9c03427ccf436aa6198ab5cb668611bc8e8a7de5f89267e72dd3`.
  The tested JVM was Java `21.0.13.0.2`. The exact runtime inventory was:

```text
bin/tracepermit-synthetic-registration
bin/tracepermit-synthetic-registration.bat
lib/adapters-remote-0.1.0-SNAPSHOT.jar
lib/agentcore-durable-registration-0.1.0-SNAPSHOT.jar
lib/agentcore-registration-cli-0.1.0-SNAPSHOT.jar
lib/annotations-13.0.jar
lib/application-0.1.0-SNAPSHOT.jar
lib/checker-qual-3.55.1.jar
lib/contracts-0.1.0-SNAPSHOT.jar
lib/execution-0.1.0-SNAPSHOT.jar
lib/flyway-core-13.10.3.jar
lib/flyway-database-cockroachdb-13.10.3.jar
lib/flyway-database-postgresql-13.10.3.jar
lib/flyway-mysql-13.10.3.jar
lib/identity-0.1.0-SNAPSHOT.jar
lib/jackson-annotations-2.22.jar
lib/jackson-core-2.18.3.jar
lib/jackson-databind-2.18.3.jar
lib/jackson-datatype-jsr310-2.18.3.jar
lib/kotlin-stdlib-2.2.21.jar
lib/mariadb-java-client-3.5.7.jar
lib/nimbus-jose-jwt-10.5.jar
lib/okhttp-jvm-5.5.0.jar
lib/okio-jvm-3.18.1.jar
lib/persistence-identity-0.1.0-SNAPSHOT.jar
lib/persistence-operations-0.1.0-SNAPSHOT.jar
lib/policy-0.1.0-SNAPSHOT.jar
lib/postgresql-42.7.14.jar
lib/security-0.1.0-SNAPSHOT.jar
```

Failure history is retained: bootstrap obsolete-option and broad AutoCloseable
compiler declarations were repaired. The first expanded 62-case focus had 3
failures: an invalid Unicode fixture was replaced during UTF8 encoding, and both
MariaDB distinct-token races lacked acknowledgements. Sequential reproduction
and a concrete producer control narrowed the latter to packaging. A real
installed worker probe showed 135 stderr bytes on a valid duplicate; disabling
only JUL did not suppress MariaDB's console fallback. The fixed driver disable
property is now set before driver initialization. The first commit-gate fixture
also matched `trx_commit` metadata before actual COMMIT; its command marker was
corrected and the repaired 6-case MariaDB focus passed. The first fresh complete
module then ran 159 cases with four child-discovery fixture failures: launcher
utilities were selected before the Java worker. Filtering by the actual worker
class repaired all four, including the real 60s stalled-input watchdog. A second
fresh complete module passed 169 cases. The final portability/launch-failure
source passed the 173-case fresh run above. Focused passes did not waive either
fresh module or repository gate.

Parent PR120 history remains separate: its first aggregate had two HTTP timeouts,
unchanged focused tests passed, and the human approved a second aggregate with
`caffeinate -i`, which passed. Sleep overlap was not proved causal. This CLI adds
no live-provider, public-listener, target or production-readiness claim.
