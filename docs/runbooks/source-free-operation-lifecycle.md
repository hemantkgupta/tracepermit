# Source-free closed operation lifecycle qualification

Status: implemented candidate with passing fresh three-engine installed evidence
and unchanged aggregate repository coverage gates.

Requirements: FR-003/008/011/012; SEC-001/003/006/009/012;
REL-001/002/004; OPS-002/004. This is partial evidence only.

## Installed contract

Build `:agentcore-operation-lifecycle-cli:distTar`, extract the archive outside
every checkout, and invoke
`bin/tracepermit-synthetic-operation-lifecycle` on Java 21. The supported commands
are exactly:

- `check-config`
- `inspect`
- `evaluate`
- `review`
- `deliver-synthetic`
- `reconcile`

Standard input must be a real inherited anonymous pipe. Send big-endian
length-prefixed frames and close it:

| Command             | Exact frames, in order                                              |
| ------------------- | ------------------------------------------------------------------- |
| `check-config`      | operator configuration                                              |
| `inspect`           | configuration, requester bearer, command                            |
| `evaluate`          | configuration, requester bearer, command                            |
| `review`            | configuration, reviewer bearer, command                             |
| `deliver-synthetic` | configuration, requester bearer, Ed25519 PKCS8 private key, command |
| `reconcile`         | configuration, requester bearer, command                            |

The respective limits are 65,536 bytes for configuration, 16,384 for a bearer,
128 for the private key and 1,024 for the command. Extra/truncated/empty/
oversized frames or any byte after the expected frames refuse before parsing,
authentication, JDBC, signing or synthetic delivery. Never place bearers,
private keys, passwords, permits or raw JDBC URLs in argv, environment, files,
logs, Page/PR text or test receipts.

Configuration is a closed
`tracepermit-local-synthetic-operation-lifecycle-v1` object. It pins the exact
numeric-loopback V7 database, scope, strict GitHub target/profile, current
authority, permit public key and lifetimes, requester/reviewer RS256 public
keys/providers, exact requester enrollment and independent human roster.
`check-config` is offline. It does not open JDBC, resolve DNS, sign or dispatch.

Every non-check command object contains exactly `schemaVersion:1.0`, the same
profile and one canonical UUID `operationId`. Review additionally requires
`decision` equal to `APPROVE` or `DENY`. Delivery additionally requires
`syntheticOutcome` equal to `CONFIRMED_SUCCESS`, `CONFIRMED_REJECTION` or
`UNKNOWN`. Body fields such as `authenticated`, `verified`, `delegated`, role,
authority, permit or target are not accepted.

## Receipt and operator interpretation

The command emits one newline-terminated JSON object no larger than 2,048 bytes
and nothing on stderr. Its exact root fields are `schemaVersion`, `profile`,
`command`, `outcome`, nullable `operationId`, `authorizationStatus`,
`executionStatus`, `dispatchStarted`, `authorityProfileFingerprint`,
`expectedProfileFingerprint`, plus `executionEnabled:false` and
`automaticRetryAllowed:false`.

Only fixed successful lifecycle outcomes exit zero. Usage exits 64; every other
failure exits 1. Treat missing, extra, malformed, late, exit-mismatched, `UNKNOWN`
or supervisor-killed output as failure. The launcher permits at most 60 seconds,
reserves 5 seconds for owned cleanup and permits 1 second for the final public
receipt write.

`DELIVERY_SUCCEEDED` means the fixed in-memory synthetic target returned its
configured result after durable start. It is not general execution enablement,
a production target receipt or permission to repeat the action. A failed command,
worker death or ambiguous database acknowledgement never authorizes an automatic
restart, new permit, new attempt or resend. Use `reconcile` only with a freshly
verified owner token; it performs a read-only observation.

## Focused qualification matrix

Tests use runtime-generated RSA and Ed25519 keys, real signed OIDC access tokens,
restricted database principals, exact V7 stores and only synthetic targets.

| Evidence               | Assertion                                                                                                                                      |
| ---------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------- |
| Source-free archive    | Extracted outside every checkout; executable Java 8 bootstrap/Java 21 runtime; no source, tests, build metadata or repository path in archives |
| Private input          | Real anonymous pipe, exact frames/EOF, strict UTF-8/JSON, no body authority, corrupt signature/wrong private key/trailing byte before effects  |
| Identity and authority | Real RS256 verification, exact enrollment/scopes, independent human roster/fingerprint, pinned Ed25519 signing key and current V7 authority    |
| Lifecycle and restart  | Separate installed processes inspect, evaluate, approve, admit, dispatch and reconcile one operation with exact durable counts                 |
| Result uncertainty     | Confirmed rejection and unknown stay durable; repeat delivery refuses; reconciliation observes without retry                                   |
| Contention             | Eight installed delivery contenders create one admission and at most one synthetic dispatch; no automatic retry                                |
| Supervisor             | Worker death before input returns fixed unknown with empty stderr and no effect; malformed/hostile child output fails closed                   |
| Three exact engines    | Equivalent installed lifecycle cases on PostgreSQL 16.4, MariaDB 11.4.13 and MariaDB 10.6.25                                                   |
| Coverage mirror        | PostgreSQL in-process execution of the identical closed worker makes deliberately halted installed paths measurable without weakening them     |

The final fresh module gate passed 25 tests with zero failures, errors or skips:
12 installed lifecycle cases across the three exact engines, six direct
PostgreSQL cases and seven archive/protocol/supervisor/launch boundary cases.
The unchanged aggregate gate then passed at 17,860/18,999 lines (94.0049%) and
8,520/11,057 branches (77.0553%). The earlier broad `pnpm check` completed the
console and Java test bodies but stopped at the pre-repair 93.6681% line result;
the bounded module refresh and aggregate verification above are the subsequent
repair evidence. A third broad run was not substituted for the recorded two-run
qualification limit.

The underlying V7 delivery repository retains its separate actual transaction,
commit-loss, crash, lease/fence and no-resend qualification. This installed
composition currently adds worker-death, durable unknown and concurrency evidence;
it does not claim a new installed transport-level commit-acknowledgement-loss
injection. Preserve that distinction in handoffs and do not relabel dependency
evidence as a new installed-process test.

## Exact local commands

Reuse the prepared JDK 21, pnpm 11.25 and Docker setup. Do not install/download,
restart/prune Docker, stop unrelated processes or overlap a second heavy writer.

```bash
export JENV_VERSION=21
export GRADLE_USER_HOME=/private/tmp/tracepermit-track-c-impl.VARxJg/.cache/gradle
caffeinate -i /opt/homebrew/bin/jenv exec java/gradlew -p java \
  :agentcore-operation-lifecycle-cli:test \
  --offline --rerun-tasks --no-build-cache --no-configuration-cache \
  --no-daemon --max-workers=1
pnpm check
pnpm format:check
pnpm docs:links
pnpm docs:flows:check
git diff --check
```

Preserve every failure or inconclusive attempt. Do not reduce concurrency,
deadlines, assertions, strict schemas, restricted grants or the aggregate
94% line/75% branch floors to make a run pass.

## Limits

This is a local source-free synthetic operator tool, not live provider or
downstream interoperability. It installs no listener, migration, background
worker, plugin or default runtime. It does not acquire a target credential or
contact GitHub, AWS, Jira or AgentCore. It does not certify database failover,
restore fencing, instantaneous revocation, target-side exactly-once effects,
credential custody, public ingress, production load or production readiness.
