# Durable AgentCore registration HTTP qualification

Status: implemented candidate with local synthetic three-engine evidence. Exact
pushed-head CI belongs to the draft PR handoff.

Requirements: FR-002/003/008/010/011/012;
SEC-001/002/003/004/006/009/012; REL-001/002/003/004/005/009; DATA-001;
OPS-002/004; PORT-004. Partial evidence only; no whole-requirement promotion.

## Supported operator construction

Use `DurableAgentCoreRegistrationHttpServer.create` with:

- the concrete `OidcAccessTokenVerifier` and one exact trusted workload
  enrollment;
- a trusted clock;
- separate already initialized replay and operation datasources;
- the persisted replay capacity and a finite replay DML budget;
- port zero or a trusted local port; and
- the existing bounded `AgentCoreRegistrationHttpServer.Limits` value.

The factory validates existing stores only, constructs the existing durable
registration composition, and starts the existing numeric `127.0.0.1` server.
It does not initialize or repair databases. Datasources remain caller-owned.
There is no executable, service loader, default port, public bind, fallback store,
verification override, repository/replay callback or request-supplied trusted
configuration.

Send the unchanged closed HTTP1.1 request to
`POST /v1/agentcore/registrations`. The body contains only `schemaVersion:1.0`,
`clientRequestKey`, `title` and optional nonblank `body`. The response contains
only `created`, `operationId` and `executionEnabled:false`, or one fixed error.
Never place bearer values, private keys or database credentials in argv, files,
logs, evidence or handoffs.

## Durable behavior and recovery

Replay commits and acknowledges cleanup before operation registration starts.
The same verified credential is single-use across server reconstruction and JVM
restart. Fresh credentials with the same scoped key and semantic content return
the immutable original. Changed semantic content conflicts. Proposed policy or
artifact metadata never overwrites the original.

Replay saturation, capacity disagreement, unready schema, permission denial and
write/commit/close uncertainty cannot reach registration. Registration rollback
or uncertainty leaves the credential spent. Unknown registration acknowledgement
stays `AGENTCORE_REGISTRATION_UNKNOWN`, even if independent readback sees a
complete record. Do not retry automatically. An operator may explicitly submit a
fresh valid credential and the same key to recover metadata only.

A transport deadline closes the connection and requests interruption; it does
not certify rollback. Running work retains capacity until actual return. A trusted
dependency that ignores interruption can finish metadata after the client times
out. Do not start a replacement or infer absence from timeout/reset.

## Synthetic qualification matrix

Each exact engine contract uses one owned loopback-only container with separate
replay and operation catalogs, fixture-admin initialization, and restricted
runtime users. Tests use runtime-generated RSA material, an actual cached JWKS
document parser, signed access tokens and raw numeric-loopback HTTP sockets.

| Evidence                 | Assertions                                                                                                                                                             |
| ------------------------ | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Positive registration    | One replay claim/counter, one immutable operation, exactly three initial facts and one head; no lifecycle journal; minimal execution-disabled receipt                  |
| Independent servers      | One winner for one credential; fresh credentials return the same operation; semantic conflict does not overwrite; policy/artifact-only proposal returns original       |
| Process restart          | Two terminated child JVMs construct real HTTP servers against shared stores; the second rejects the spent credential; fresh-token metadata recovery returns existing   |
| Negative authority       | Invalid signature/issuer scope/expiry/enrollment, unknown identity/role/authenticated/verified/delegated body fields and paired human token stop before durable writes |
| Readiness/capacity       | Capacity mismatch, malformed existing schema and saturation fail closed without repair or fallback                                                                     |
| Replay uncertainty       | Real forwarded JDBC first-write, commit and close faults never reach registration; committed replay remains spent                                                      |
| Registration uncertainty | Real first-write rollback and commit/close acknowledgement loss preserve spent identity; observer-visible commits remain unknown; fresh-token recovery is explicit     |
| Timeout/cancellation     | An interruption-ignoring real JDBC stage retains the only worker; queued work expires without starting; the first call may complete metadata after transport timeout   |

The first complete development contract passed 36 cases across the three engines,
zero failures/errors/skips, with all 25 Gradle tasks executed and both caches
disabled. The final complete focused invocation passed 164 HTTP plus 141 durable
cases (305 total), zero failures/errors/skips, with all 27 tasks executed and both
caches disabled in 3m32s wall time. The durable total contains 36 new composition
cases and 105 unchanged direct-composition cases.

The single complete repository `pnpm check` passed the console build/typecheck
and 124 tests, 152 Java-environment scenarios, and 3,458 Java tests across 206
suites with zero failures/errors and one existing opt-in absent-archive
native-PTY skip. Gradle finished in 26m19s with 45 executed, 20 from cache and 27
up-to-date tasks; the immediately preceding fresh HTTP/durable outputs were reused.
Aggregate coverage was 94.5667% LINE and 78.0458% BRANCH against unchanged
94%/75% floors. Final exact-pushed-head CI remains a separate PR gate.

## Commands and bounds

Reuse the prepared Java21, Gradle8.14.3, Docker29.9.2 and exact native-arm64
database images. Do not install/download dependencies, restart/prune Docker or
stop unrelated workers. Run one heavy writer at a time with maximum one Gradle
worker; do not reduce test concurrency, timeouts, assertions or coverage floors.

```bash
export TRACEPERMIT_JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
export TRACEPERMIT_GRADLE_USER_HOME=/private/tmp/tracepermit-m2-pr113-qualification/.cache/gradle
caffeinate -i java/gradlew -p java :agentcore-durable-registration:test \
  --rerun-tasks --no-build-cache --no-configuration-cache --no-daemon \
  --max-workers=1
pnpm check
pnpm format:check
pnpm docs:links
pnpm docs:flows:check
git diff --check
```

Fixtures use finite container startup, JDBC connect/socket/query/lock, race,
child input/output/exit/cleanup and test-method bounds. Tests stop only their own
servers, child processes, executors and containers. Live provider, public edge,
human OBO, approval/admission, target execution, restore/failover and production
readiness remain unqualified.
