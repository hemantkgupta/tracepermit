# Installed durable AgentCore HTTP session qualification

Status: implemented candidate with passing fresh three-engine focused evidence;
the first repository aggregate has two preserved failures deferred to
[issue126](https://github.com/hemantkgupta/tracepermit/issues/126). This is an
accepted-risk publication checkpoint, not passing aggregate qualification.

Requirements: FR-002/003/008/010/011/012;
SEC-001/002/003/004/006/009/012; REL-001/002/003/004/005/009; DATA-001;
OPS-002/004; PORT-004. This is partial evidence only.

## Installed contract

Build `:agentcore-registration-http-cli:distTar`, extract it outside every
checkout, and invoke `bin/tracepermit-synthetic-registration-http` on Java21.
Only `check-session` and `serve-bounded` are supported. Standard input must be a
real inherited anonymous pipe at both launcher and worker boundaries. Never put
operator configuration, tokens, keys, passwords or database URLs in argv,
environment variables, files, logs or handoffs.

Write exactly two big-endian length-prefixed frames and close stdin. The first is
the unchanged closed operator configuration documented by the
[registration CLI runbook](source-free-synthetic-registration-cli.md). The
second is:

```json
{
  "schemaVersion": "1.0",
  "profile": "tracepermit-local-synthetic-registration-http-session-v1",
  "requestedPort": 0,
  "sessionMillis": 1000
}
```

`requestedPort` is zero or `1024..65535`. `sessionMillis` is explicitly
`1000..30000`; there is no default. The listener is always numeric
`127.0.0.1`, uses the unchanged default4-worker plus8-pending HTTP capacity and
the existing route `POST /v1/agentcore/registrations`.

`check-session` validates both frames and EOF without DNS, JDBC or listener
effects. `serve-bounded` returns one framed `READY/LISTENING` receipt with the
actual port and, after close, one framed `FINAL/SESSION_ENDED` receipt. Each
receipt is at most1024 bytes and contains only lifecycle fields plus
`executionEnabled:false` and `rollbackConfirmed:false`. Treat every missing,
extra, malformed, late, nonzero or `UNKNOWN` result as failure.

## Qualification matrix

Run one heavy writer with one Gradle worker and the exact cached native-arm64
images. Tests use runtime-generated synthetic RSA material and private pipes.

| Evidence            | Required assertion                                                                                                                                     |
| ------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------ |
| Source-free archive | Java8 bootstrap entry, Java21 runtime, no source/test/build inventory, archive outside checkouts                                                       |
| Input boundary      | Parent and worker accept anonymous pipes only; files/FIFOs, missing/extra/truncated/oversized/duplicate frames refuse before effects                   |
| Lifecycle           | Ready follows bind; final follows close; exact one/two framed receipts, empty stderr, matching exit, bounded slow/broken output and cleanup            |
| Shared durability   | At least four installed launcher/worker pairs share the same two stores; same-token replay and same-key idempotency survive process boundaries         |
| Contention          | Real concurrent exact-token and distinct-token/same-key requests have one created operation with matching replay/operation counts                      |
| Capacity            | Four real JDBC commits occupy the workers, eight requests queue, the thirteenth is refused/reset, and capacity returns after release                   |
| Uncertainty         | Real replay and registration commit replies are lost after independent durability observation; no automatic retry and fresh-token recovery is explicit |
| Authority           | Invalid signed identity, body authority fields and human-token pairing refuse without enabling execution                                               |
| Three engines       | Equivalent installed checks pass on PostgreSQL16.4, MariaDB11.4.13 and MariaDB10.6.25                                                                  |

## Commands

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
export GRADLE_USER_HOME=/private/tmp/tracepermit-m2-pr113-qualification/.cache/gradle
caffeinate -i java/gradlew -p java :agentcore-registration-http-cli:check \
  --offline --rerun-tasks --no-build-cache --no-configuration-cache \
  --no-daemon --max-workers=1
pnpm check
pnpm format:check
pnpm docs:links
pnpm docs:flows:check
git diff --check
```

Preserve failed or inconclusive runs in the handoff. Do not reduce concurrency,
timeouts, assertions or coverage floors to obtain a pass. Do not restart or
prune Docker, install/download dependencies, stop unrelated processes or merge a
PR as part of qualification.

## Candidate evidence and deferred failures

The final fresh module gate passed33 tests across6 suites on PostgreSQL16.4,
MariaDB11.4.13 and MariaDB10.6.25 with zero failures/errors/skips and all34 tasks
executed. The separate existing CLI gate ran182 tests with one unchanged
MariaDB11.4.13 race failure; its single authorized unchanged focused rerun
passed, and the original failure remains part of the handoff history.

The first full `pnpm check` passed the console build, typecheck,124 console tests
and152 Java-environment shell scenarios. Its Java aggregate failed after21m01s
with3,494 tests, one existing opt-in skip, two failures and zero errors. Both
failures remain active and are tracked in
[issue126](https://github.com/hemantkgupta/tracepermit/issues/126):

- MariaDB11.4.13 produced11 rather than15 expected conflict replies during the
  exact-token/four-installed-server burst. The harness starts bounded sessions
  sequentially and the aggregate case outlived an older18-second lease.
- The PostgreSQL replay commit-loss case received a final lifecycle receipt
  before READY; the current assertion did not expose whether its fixed outcome
  was `NOT_READY`, `START_FAILED` or `UNKNOWN`.

Neither failing case was rerun, skipped or weakened. Aggregate coverage did not
run after the failure. `pnpm format:check`, `pnpm docs:links` (1,349 links),
`pnpm docs:flows:check` (13 cases) and `git diff --check` passed. The human
explicitly accepted publishing the checkpoint with this limitation for later
repair and requalification.

## Interpretation and limits

`SESSION_ENDED` confirms only bounded listener closure. It does not certify
request drain, JDBC rollback, absence of late metadata, admission or target
execution. The HTTP response remains the only registration acknowledgement, and
its existing unknown/timeout rules remain controlling.

No live provider, TLS/private edge, public ingress, human delegation, approval,
permit, admission, target credential, destructive target, failover/restore or
production-load test is part of this runbook.
