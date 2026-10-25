# AgentCore ingress and JDBC registration qualification

Cloud2 claims sole-writer ownership of `codex/agentcore-jdbc-registration-qualification`
until a final pushed TRACEPERMIT HANDOFF releases it. This draft is stacked on
read-only PR109 at `bc2486a18558dc14998db840187024fd2a2faac9`.

Scope: new test-source composition of the existing strict token verifier,
operator enrollment, AgentCore registration ingress and real JDBC repository on
PostgreSQL16.4/MariaDB11.4.13/MariaDB10.6.25, narrow test-only dependencies and
scoped evidence. No runtime, migration, authority, credential, launcher, CI or
provider behavior changes. PR109 and all other producer branches stay untouched.

Startup: prepared tracepermit-java21-docker verified, clean exact starting head,
restricted networking and private subtree exclusion retained; two preflights and
nine tooling tests passed. At the initial ownership checkpoint no new
application/database qualification had run; later results follow below.
Existing environment/producer receipts remain historical.

Contract limit from ADR0070/0078: same-key changed target/resource or generation
conflicts; policy/artifact/retention release proposals may differ and return the
original registration. A broader all-profile-change conflict is unsupported.
Tests must preserve this distinction. Registration remains metadata only;
provider interoperability, shared durable replay and role revocation remain
unqualified. No live provider/target action or automatic merge is authorized.

## Fixture and assertions

The new `agentcore.jdbc` test-source contract composes the unchanged
`OidcAccessTokenVerifier`, exact operator `Enrollment`,
`AgentCoreRegistrationIngress` and `JdbcBusinessOperationRepository`. Each
concrete class starts one required exact-version disposable Testcontainers
instance. There is no Docker availability skip or alternate version. Local
RSA keys are generated in memory; issuer/audience/tenant values are synthetic.
No provider exchange, live key fetch or external target is constructed.

Fixture admin explicitly calls `OperationDatabase.initializeEmpty` once and
provisions a separate runtime user. Runtime receives SELECT on the four data
tables and schema history, INSERT only on the original/facts/head tables and
schema USAGE where required. It has no journal INSERT, UPDATE, DELETE or DDL
permission. Normal repository construction validates existing schema. The
composition requires the engine's permission-denied category (not an arbitrary
SQL/foreign-key failure) for runtime create/alter, rewrite/delete, head update and
journal INSERT attempts; exact table inventory contains no authority/permit/
attempt/target objects. The ingress constructor has no such port to call.

Positive controls inspect the database directly: one original snapshot, all
three exact initial facts, matching initial head/hash and zero lifecycle-journal
facts. Authorization remains `evaluating`, execution `not_started`, evidence
`pending` and every receipt has `executionEnabled=false`. The scoped-key golden
was independently calculated with Python hashlib over sorted compact UTF-8 JSON.
Fresh tokens with the same key preserve the original operation identity/content
through a reconstructed restricted-user repository. Exact-token16-way contention
reaches JDBC once via shared **process-local** replay;16 fresh tokens have one
durable original and a single registration commit.

Wrong issuer/audience/subject/client/tenant/scope/time/enrollment, untrusted
workspace/authority/routing fields, parser failures and all16 tested combinations
of workload/human/invalid/missing first authorization with non-null second
values stop before JDBC opens or insert attempts. Both unrelated positive tokens
independently pass the real verifier; their composition still fails. This is
refusal evidence, not an implementation of OBO.

`RegistrationJdbcProbeSource` forwards to real JDBC connections and records the
registration phase separately from schema-validation transactions. Injected SQL
exceptions occur after actual uncommitted second-fact/head writes; real rollback
must remove the original, every fact and head. A separate real database privilege
revocation makes the driver reject fact insertion after the original insert.
Commit probes fail before actual commit, after actual commit or after actual
post-commit connection close. A combined write/close canary verifies safe errors
without retained causes/suppressed diagnostics. These are explicit JDBC-boundary
fault injections, not process-crash/network-failover certification or a mocked
repository.

A consumed token remains consumed after failed/uncertain registration; replay
opens no new connection. There is no automatic retry. An explicit same-key call
with a fresh verified token either creates metadata after rollback or reads the
original after acknowledgement loss. It never calls a target, obtains a permit
or advances execution. Snapshot/fact/head state is compared before and after
conflicts and uncertainty recovery.

## Qualified contract limits

Target identity/ref/SHA, environment, semantic title/body and recovery generation
changes conflict under the same scoped key. Policy/artifact-only profile changes
and retention proposals intentionally return the original registration unchanged
under [ADR0070](../architecture/0070-durable-business-operation-registration.md).
The test also proves that the original then fails exact binding to the newly
proposed profile: successful registration is not proof of current profile/policy
authority. An all-profile-change conflict would require a separate product
contract change; this qualification does not change runtime to invent it.

Workspace is an operator enrollment mapping, not a verified provider token
workspace claim. Body-supplied workspace/tenant/requester fields are rejected;
signed workspace aliases cannot replace enrollment. Explicit alternate operator
workspace enrollment produces a separate scoped key with isolated reads, rather
than proving provider workspace authority. A compromised operator enrollment is
outside the asserted caller boundary.

Provider interoperability, a cryptographically verified workload-to-human grant,
shared durable replay, role revocation, current execution authority and a composed
executor remain unqualified. Repository reconstruction is not database crash or
failover. No approval, permit, execution attempt or target wiring is added.

## Reproduction and evidence

Use the verified prepared environment and saved Java selector; preserve proxy/CA
and managed Unix Docker settings. Bound Gradle workers, not test-internal
concurrency, deadlines or workloads:

```bash
export TRACEPERMIT_JAVA_HOME=/workspace/.local/jdk-21
export XDG_DATA_HOME=/workspace/.local/share XDG_CACHE_HOME=/workspace/.cache
source scripts/configure-java-environment.sh
/usr/local/bin/docker-init -s -- java/gradlew -p java :adapters-remote:test --tests '*agentcore.jdbc.*RegistrationTest' --no-daemon --max-workers=2 --no-build-cache --no-configuration-cache --rerun-tasks
/usr/local/bin/docker-init -s -- bash scripts/run-java-operation-tests.sh --no-daemon --max-workers=2 --no-build-cache --no-configuration-cache --rerun-tasks
pnpm check
pnpm format:check
pnpm docs:links
pnpm docs:flows:check
```

For full `pnpm check`, preserve existing `GRADLE_OPTS` and append the command-scoped
`-Dorg.gradle.workers.max=2` selector. Use `/usr/local/bin/docker-init -s` as the
subreaper. Each fixture stops only its own container; no daemon restart/prune or
unrelated cleanup is permitted.

Initial PostgreSQL qualification:56/58 passed, two test-probe failures (newline
in table-category capture and constructor schema-validation commits counted as
registration commits). Correcting the probe boundaries preserved every runtime
byte and assertion about data/registration effects. Subsequent three-engine run:
174/174 passed, zero failures/errors/skips. The final stricter exact-version run
also passed174/174 with20/20 actionable tasks executed in49s, build/configuration
caches disabled and fresh XML on all three engines. Frozen install, format,
1126 local links/three helpers and13 flow/drift checks passed. The unchanged
operation suite, full-project checks and exact-head hosted receipts are
recorded in the final [PR111](https://github.com/hemantkgupta/tracepermit/pull/111)
handoff. Earlier environment/PR109 receipts are not fresh evidence for this slice.

Final review strengthened privilege probes to require PostgreSQL42501 or MariaDB
42000 with1142/1143, including a journal INSERT with no selected rows. The final
three-engine focused run again passed174/174, zero failures/errors/skips,
20/20 tasks executed in54s with caches disabled and fresh XML.

One local full-check attempt overlapped that focused run. The narrow test task
replaced the remote module's coverage input before aggregate verification, which
then correctly failed unchanged94%/75% gates at reported87%/68%. This is a test
orchestration error, not a valid full-suite qualification receipt. Its log and
4m18s/4-executed/61-up-to-date facts are preserved. The final full check runs
alone after the focused run has finished, rebuilding complete test/coverage
inputs. No thresholds or assertions were weakened and no failing product test
was retried to green. Final full/hosted evidence is in PR111's handoff.
