# ADR 0072: durable pending-operation withdrawal without target authority

Status: implemented internal library; integration and independent review pending.

Requirements: FR-003/007/009/010/012; SEC-002/003/005/006/009/012;
REL-001/002/004/005; DATA-001; OPS-002/004.

## Context and decision

ADR 0070 freezes immutable initial registration and original-return scoped-key
retry semantics. ADR 0071 produces uncommitted candidates, not an atomic winner
or authority. Updating the original registration or accepting arbitrary proposed
`approved`/`claimed`/`succeeded` JSON would break those boundaries. V1 migration
bytes, checksums and its three-fact constraint remain unchanged.

Add an internal `BusinessOperationLifecycleRepository` with only `findCurrent`
and `withdraw(BusinessOperationWithdrawal)`. There is no generic proposal write,
desired state/reason input, callback, caller clock, HTTP route or target port.
The command requires existing trusted requester context, exact tenant/workspace/
owner scope and an expected whole-state token. Construction is not authentication:
a future adapter must verify token/transport and operator enrollment, derive
workspace scope and supply trusted facts. No public body deserializes this class.

The requester must be authenticated, match tenant and owner, hold the explicit
`operation_requester` role and `operations.cancel` scope, and have unexpired
authority. Repeat those checks **after** the row lock, using PostgreSQL
`clock_timestamp()` or MariaDB `UTC_TIMESTAMP(6)`. Compare every expected-token
component and canonical snapshot hash. Refuse clock regression, not manufacture
a newer timestamp. Role snapshots remain valid until their bounded deadline;
instant membership revocation is not established.

Only evaluating/awaiting-approval authorization with not-started execution can
be withdrawn. The currently reachable write path is initial `evaluating` →
`cancelled`: no command to enter awaiting approval is added. Preserve all other
dimensions, immutable fields and history. A stale duplicate fails; a fresh token
on cancelled work also fails. Withdrawal grants no authority and is independent
of a permissive current policy. It cannot stop or reverse admitted/executing work.

## Additive V2 storage and consistency

Keep `tp_business_operations` and `tp_business_operation_facts` untouched. V2 adds
`tp_operation_lifecycle_heads` (snapshot/hash and per-operation lock anchor) and
`tp_operation_lifecycle_facts` (sequence, predecessor/result hashes, exact fact,
actor metadata/hash). The actor record contains scope fingerprint, owner
principal, authentication method, credential **fingerprint** and deadline, never
a credential. This journal is not signed evidence or an S3 publication receipt.

Registration inserts its head atomically with the original snapshot/three facts.
Scoped-key retries and registration `find` always return the **initial** record,
even after withdrawal; use `findCurrent` to observe progress.

Withdrawal reads scoped immutable registration, locks the head `FOR UPDATE`,
validates the current chain, samples database time, checks authority/token/state,
then appends a fact and updates the head in one READ COMMITTED transaction. The
update also predicates on the expected hash. No external callback/network/IdP or
target call occurs under the lock. Every SQL statement has a five-second query
timeout, not a whole-operation/network deadline; pool/socket limits remain
operator responsibilities.

`findCurrent` uses one REPEATABLE READ snapshot across registration, head and
facts. Replay the closed withdrawal journal from the immutable initial snapshot,
checking contiguous sequence, predecessor/result hashes, exact fact, actor
binding/deadline and canonical head equality. A read cannot combine an old head
with a new fact. Canonical comparison handles Jackson integer node-width changes
across serialization without changing the frozen v1 profile or its numeric
limits. Hashes provide neither confidentiality nor authenticity against a DBA
who can rewrite the data and hashes coherently.

Partial writes roll back together. Commit/post-commit cleanup acknowledgement
loss is `OPERATION_COMMIT_UNKNOWN`, regardless of actual durability. No automatic
retry or false success occurs. A separate explicit lookup can inform an operator;
it never redispatches a target.

Fixed-code storage exceptions explicitly disable cause initialization and
suppression: otherwise try-with-resources can retain a raw JDBC close diagnostic
on a rejected-command or corrupt-read exception. Independent review identified
this combined-failure case; three regressions failed before the repair and are
included in the shared engine suite. This does not convert cleanup failure after
commit into success; that still reports commit unknown.

## Migration and privilege boundary

Runtime validates exact V2 inventory/history/keys/FKs and supported writer/engine;
it never initializes, migrates, repairs or adopts. Explicit `initializeEmpty`
creates a new dedicated store. `upgradeFromV1` validates the exact populated V1
predecessor before DDL, creates additive tables and copies heads without rewriting
V1 rows. Partial/extra objects, checksum differences, corrupt predecessors and
repeated upgrades are refused.

Stop all old/new operation writers and verify a backup before upgrading. This is
an **operator precondition**, not automatic discovery/fencing of existing old
processes. Old V1 runtime validation does not accept V2. MariaDB DDL can implicitly
commit; interruption/partial DDL requires operator investigation/recovery, never
automatic repair/resume, checksum rewriting or dropping a populated store.

Runtime needs SELECT/INSERT on the four data tables, SELECT on history, and only
column-scoped UPDATE `(snapshot_hash, snapshot_json)` on heads. PostgreSQL also
needs USAGE on public. No UPDATE of initial registrations/facts or head IDs,
DELETE, DDL, administrative grant or migration ownership is needed.

## Verification and remaining gates

The shared contract runs on PostgreSQL 16.4 and MariaDB 11.4.13/10.6.25: original
retries, durable restart, concurrent winner/stale losers, coherent in-flight
reads, real row-lock deadline expiry, partial-write rollback, lost commit/cleanup
acknowledgement, chain/actor/head corruption, migration refusal/preservation and
restricted grants. Application tests cover owner/tenant/role/scope/flag/deadline/
ID/token rejection. All fixtures are synthetic/disposable.

Not implemented: a generic trusted lifecycle service, policy/approval decisions,
global recovery fencing or backup resurrection protection, claims/permits,
execution, signed evidence/S3, API/AgentCore wiring, encryption/redaction,
retention, live RDS TLS/failover, EKS or production qualification. Tests for partial
MariaDB inventory refusal are not a complete crash/recovery certification.
