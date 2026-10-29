# PostgreSQL quarantine and admission component qualification

Status: **implemented; manual validation pending**. All 19 new executions are
UNRUN. The baseline is accepted PR148 main
`a769ecc3087348c400de0fdde5c2a60a3a5c319f`. This test-only slice uses the existing
V7 PostgreSQL 16.4 fixture and the unchanged administration, authority, review,
permit and admission libraries. It adds no production API, installed probe,
identity adapter, schema, grant migration, provider or deployment command.
[ADR0083](../architecture/0083-writer-locked-operation-authority-control.md) and
[ADR0087](../architecture/0087-fully-bound-operation-permit-and-admission.md)
remain the applicable boundaries; no new trust choice is introduced.

Partial FR-008/009/012/015, SEC-003/006/009/012/019, REL-002/005/008 and OPS-005
component responsibilities are exercised below. No whole requirement is promoted.
The [org procedure](../design/eks-p1-quarantine-procedure-contract.md) and its
[Q01–Q20 acceptance matrix](eks-p1-quarantine-procedure-review.md) remain separate
and UNRUN, even if every component test passes.

## Fixture and observation boundaries

`PostgresQuarantineAdmissionTest` uses four new test-only sources in
`java/persistence-operations`. It creates a disposable loopback-bound
`postgres:16.4-alpine` container and checks the actual server version number 160004. Its generated credentials exist only in the fixture. Its runtime role
gets the existing admission grants, including PostgreSQL UPDATE(lock_marker)
for row locking, but cannot update authority columns or insert delivery events.
A separate SELECT-only role observes controls and hashes/counts all non-control
rows. The fixture administrator uses a separate connection to observe an actual
PostgreSQL lock wait; that privilege is not granted to the read-only observer.

Fixture setup deliberately starts at V7 so an empty delivery journal is an
actual observation. No delivery repository, lease, dispatcher or synthetic target
is constructed. A claimed admission in a disposable test ends there. Empty
journals and the absence of a target object are component limits, not a proof of
external target invocation counts, cancellation, fencing or exactly-once effects.
No existing test, production file, build, CI, SQL migration or org grant changes.

The stop probe prepares a real approval and signed, unspent permit **before**
quarantine. It constructs the admission repository before stopping, observes the
exact ready-to-quarantined row change, confirms the original permit has not
expired using database time, then calls the real admission method. It checks
OPERATION_AUTHORITY_NOT_READY with no admission insert and no changed business,
review, head, fact, outbox or delivery rows. Scope/profile/generation/lock marker
stay fixed. Expiry is a test failure requiring diagnosis, never a reason to
extend validity or rerun automatically.

The fixture data and trusted synthetic contexts follow the existing admission
contract. They do not authenticate a real operator or organization identity.
The authority helper still takes a privileged DataSource, not an authenticated
human. Session revocation/expiry through a wait and wrong-writer/clone/PVC
binding require the actual approved org procedure.

## New cases — all UNRUN

| Executions | Case                                              | Required observation                                                                                                                                                                                   |
| ---------- | ------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| 1          | Ready calibration                                 | One claimed admission/fact/head/outbox; no delivery events; ready pins unchanged.                                                                                                                      |
| 1          | Quarantine then admission with a preissued permit | Acknowledged exact stop, still-valid permit, real authority refusal and unchanged immutable row hashes/counts.                                                                                         |
| 1          | Issuer-only refusal                               | No admission called; explicitly not admission-denial proof.                                                                                                                                            |
| 2          | Quarantine COMMIT / cleanup acknowledgement loss  | UNKNOWN after the real JDBC COMMIT / close; quarantined state observed separately; one attempted mutation, no retry.                                                                                   |
| 2          | Admission COMMIT / cleanup acknowledgement loss   | UNKNOWN despite claimed state/outbox; independent read-only observation, no resend or dispatch.                                                                                                        |
| 1          | Runtime and observer ACL denial                   | Admin performs the otherwise-valid conditional UPDATE then rolls it back; both restricted roles receive actual SQLState 42501. Runtime control-lock privilege works; delivery INSERT privilege absent. |
| 1          | Real control lock wait                            | Actual pg_stat_activity Lock wait observed within the existing bound; ready before release, acknowledged quarantine afterward, real admission refusal before original permit expiry.                   |
| 3          | Wrong profile / recovery generation / scope       | OPERATION_STATE_STALE and all original control/data bytes unchanged.                                                                                                                                   |
| 2          | Missing / staged controls                         | Same admission refusal, independently distinguished from quarantined.                                                                                                                                  |
| 1          | Signed expired permit                             | OPERATION_PERMIT_EXPIRED while authority remains ready; no admission.                                                                                                                                  |
| 1          | Invalid signature                                 | OPERATION_PERMIT_INVALID while ready; not a successful stop.                                                                                                                                           |
| 1          | Repeated quarantine / activation attempt          | Stale result; no reactivation or journal rewrite.                                                                                                                                                      |
| 1          | Test DataSource unavailability                    | OPERATION_STORAGE_UNAVAILABLE while ready; explicitly not real network-loss qualification.                                                                                                             |
| 1          | Expired fresh requester                           | OPERATION_AUTHENTICATION_EXPIRED with ready authority and a still-valid original permit.                                                                                                               |

The acknowledgement faults throw **after** the delegated JDBC COMMIT or close
returns successfully. They are narrow JDBC fault injection, not wire-level
COMMIT-reply-loss evidence. Cloud1's installed process/gate qualification is
independent and is neither copied nor consumed. Connection counters count owned
Java wrapper connections, not kernel sockets or all PostgreSQL sessions. No raw
permit, key, token, generated password, exception cause or org identifier is
written to test evidence. Readback remains an observation, never a promoted
success acknowledgement after UNKNOWN.

## Human-run stages

The writer supplies a source-pinned private Bash runner on this Mac with
separate **focused** and **repo** stages. It records the absolute checkout,
branch, commit/tree, source hashes, private runner/cache hashes, actual numeric
exits and fresh log paths. The only expected uncommitted file is the unrelated
manual-validation policy in AGENTS.md; it is excluded from the feature commit.
The runner stops on failure; an older passing receipt cannot hide a newer failed
focused attempt. The repository stage requires the latest exact focused receipt.
No agent tests, builds, syntax checks, Docker probes, CI retries or deployments
were run to prepare it.

Focused selects only `io.tracepermit.persistence.operations.PostgresQuarantineAdmissionTest`
with offline Gradle 8.14.3, Java 21, one worker, no build/configuration-cache
reuse and rerun-tasks. The opt-in test-only image policy refuses any missing
PostgreSQL/helper image instead of pulling it. It is set only for the focused
JVM; other repository modules retain their existing policy. The stage verifies
fresh XML with exactly 19 passes, zero failures/errors/skips and copies the XML
into private evidence. It also runs format, documentation link helpers, all 13
flow/drift checks and source/diff guards. Offline dependency resolution does not
establish host-network isolation.

Only after reviewing that receipt, run the separately supplied repo command.
It runs literal `pnpm check`, `pnpm format:check`, documentation links, flows and
diff checks with the unchanged 94% LINE / 75% BRANCH aggregate gates. Active
PostgreSQL and MariaDB 11.4 repository tests stay enabled; this new component
slice does not claim MariaDB-specific quarantine qualification. Retired 10.x
lanes are not revived. Actual executed/up-to-date/cache/skipped outcomes remain
visible; reused outputs must not be described as fresh executions.

Return each private log directory, numeric exits, `stage-result.json` and bounded
failure summaries. Do not paste credentials or private operation data. A focused
pass is not a full repository pass, a merge disposition or org sandbox acceptance.
The org tool, operator enrollment/session semantics, private custody/writer route,
independent observer/probe artifact and live drill approvals remain outstanding.
