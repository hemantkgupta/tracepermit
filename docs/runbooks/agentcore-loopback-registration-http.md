# Loopback registration HTTP qualification

This is the explicitly constructed, execution-disabled transport in
[ADR0082](../architecture/0082-loopback-agentcore-registration-http.md), stacked on
read-only PR111 at `317a1be331b46292699a1e5bbf2e824537a27eb8`. No executable/default
wiring, existing launcher or production route is added.

## Operator construction and protocol

Construct the existing verifier, exact enrollment, repository and replay port in
trusted operator code, then pass the real `AgentCoreRegistrationIngress` to
`AgentCoreRegistrationHttpServer` with a port and trusted `Limits`. Address is
always numeric127.0.0.1; tests use ephemeral ports. Do not deserialize any of those
configuration objects from requests. This task does not implement an operator
launcher, Gemini's durable replay, Cloud1's pilot composition or Mac authority.

Send one HTTP1.1 POST to `/v1/agentcore/registrations`, exact numeric Host including
port, one Authorization header, JSON Content-Type and one canonical Content-Length.
Only the existing ingress schema1.0 body is accepted: key, title and optional
body. Maximum framed body is32768 UTF-8 bytes. No extra identity/cookie/forwarded
headers, query routing, compression, chunking, upgrade, CORS or implicit OBO.
Do not put bearer values in command lines, logs, examples, tracked files or receipts.
The library has no CLI that requests or prints credentials.

Created201 and existing200 contain only creation status, operation UUID and
`executionEnabled:false`. Fixed errors never contain snapshots, fingerprints,
caller values or raw exceptions. Conflict409 is not permission to overwrite a
key. Registration unknown503 and transport loss never prove absence: only an
explicit same-key request with fresh verified identity may resolve metadata.
No response authorizes a permit, admission, dispatch, target credential or effect.

## Deadline and close interpretation

Default4 workers/8 pending jobs/12 total reservations, headers2s/body3s/overall5s,
close1s; all limits are trusted and finite. The NIO loop closes slow headers,
partial/trickling bodies, queued expirations and slow responses at their absolute
monotonic deadlines. Overload is rejected before parsing/authentication, with a
best-effort fixed503 or connection close/reset. Timeout/reset is not an HTTP
success or a database rollback receipt.

A timed-out running dependency retains capacity until it actually returns.
Pending cancellation does not later authenticate/register when workers free.
Close waits only within its budget and preserves interruption; it does not claim
an uncooperative JDBC/identity call stopped or that a registration was rolled back.
A metadata commit can become visible after client timeout. Operator connection,
query and key-resolution bounds still matter. There is no automatic retry.

## Synthetic evidence and commands

Locally generated RSA signing material and a synthetic JWKS document feed the
actual cached JWK resolver and access-token verifier; no issuer/JWKS URL is fetched
from a network. Real signed HTTP-to-JDBC tests run exact PostgreSQL16.4,
MariaDB11.4.13 and MariaDB10.6.25 with explicit fixture-admin initialization and
separate SELECT/INSERT runtime users, no UPDATE/DELETE/DDL or journal INSERT.
Direct reads check original+three facts+initial head and zero lifecycle journal.
Commit/close acknowledgement-loss injection forwards real JDBC operations;
fresh-token same-key recovery retains the original, never dispatches.

Initial full module run:139 cases (58 transport+27 per engine), zero failures,
errors or skips,20/20 tasks executed in43s with caches disabled and fresh XML.
Expanded transport suite:76 passed, zero failures/errors/skips,2 executed/18
up-to-date tasks in14s. Final pristine/guard/full/hosted receipts are recorded in
[PR115](https://github.com/hemantkgupta/tracepermit/pull/115), not inferred from
PR109/111 or another machine's results.

Retained setup failures: initial compile rejected a nonserializable exception
field under unchanged `-Werror`; the exception now stores only status/code.
Initial transport run57/58 passed; overload reset reached a helper that expected
EOF. The helper now accepts actual reset/close as pre-parse refusal, never treats
a timeout as refusal, and uses response framing rather than assuming EOF.
Initial guard harness omitted tini's `--`, so no test ran; an XML freshness check
now rejects such stale output. These are not successful control receipts.

Use the prepared environment and saved Java selector; keep Docker/proxy/CA
settings unchanged and never overlap Gradle commands sharing reports/coverage:

```bash
export TRACEPERMIT_JAVA_HOME=/workspace/.local/jdk-21
export XDG_DATA_HOME=/workspace/.local/share XDG_CACHE_HOME=/workspace/.cache
source scripts/configure-java-environment.sh
/usr/local/bin/docker-init -s -- java/gradlew -p java :agentcore-registration-http:test --no-daemon --max-workers=2 --no-build-cache --no-configuration-cache --rerun-tasks
/usr/local/bin/docker-init -s -- java/gradlew -p java :adapters-remote:test --tests '*agentcore.jdbc.*RegistrationTest' --no-daemon --max-workers=2 --no-build-cache --no-configuration-cache --rerun-tasks
/usr/local/bin/docker-init -s -- pnpm check
pnpm format:check
pnpm docs:links
pnpm docs:flows:check
```

For full check preserve existing `GRADLE_OPTS` and append the command-scoped
`-Dorg.gradle.workers.max=2`. Do not reduce test workloads, internal concurrency,
deadlines, assertions or coverage. Tests stop only their own fixtures; never
restart/prune Docker or clean unrelated resources.

Remaining operator gates: private TLS edge, actual provider token compatibility,
verifiable OBO, shared durable replay, role revocation, current authority and a
separately reviewed executor. Numeric loopback is not process isolation. No live
provider/target/customer operation or production-readiness claim is qualified.

Final pristine module:157/157 cases (76 transport+27 per engine), zero failures,
errors or skips;21/21 tasks executed in40s with build/configuration caches
disabled, fresh XML copied before subsequent commands. New module coverage was
95.70% line/77.18% branch. The unchanged PR111 composition suite also passed
174/174 with20/20 tasks executed in49s, caches disabled and fresh XML copied.

Deliberate controls on owned source: removing duplicate-header rejection made
4/23 selected cases fail; removing the transport byte ceiling made1/1 fail;
removing absolute expiry made1/1 fail. Source was restored by hash in `finally`
after every control. The final pristine run restored all compiled/coverage
outputs and passed. These intentional red controls are not current failed gates;
no weakened source, workload, timeout, assertion or threshold is retained.
Final serialized full-check and exact-head hosted receipts belong to the PR
handoff. Tests and controls are not run concurrently against shared outputs.

Accepted-channel ownership repair: configuration could fail before an accepted
socket entered the shutdown tracking set. Provisional ownership now closes failed
nonblocking/socket-option setup and every untracked accept exit, returning any
reserved capacity. Seven deterministic fake-channel cases cover checked,
unchecked and error failures at both setup steps, plus successful transfer to
caller ownership. Removing the setup cleanup deliberately failed6/7 cases; source
was restored by hash before a new pristine run. That run passed164/164 cases
(the previous157 plus7 setup cases), zero failures/errors/skips,21/21 tasks
executed in42s with both caches disabled and fresh XML archived. Module coverage
was96.17% line/77.63% branch. Prior157-case and hosted receipts apply to the prior
head; the final handoff identifies the repaired head and its own hosted result.
