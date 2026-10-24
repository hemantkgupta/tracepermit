# ADR 0070: Durable business-operation registration on MariaDB and PostgreSQL

Status: Implemented isolated registration library; runtime wiring and
authority-bearing lifecycle transitions remain pending.

Requirements: FR-002/010/012; SEC-001/002/003/009/012;
REL-001/002/003/004/005/009; DATA-001/002/004; OPS-002/004;
PORT-001/002/003.

## Context

[ADR 0068](0068-versioned-business-operation-contract.md) freezes the operation
identity and snapshot contract. A document validator cannot decide which of two
concurrent submissions owns a client request key, nor prove that a snapshot and
its initial lifecycle facts survive a process restart together. A durable
registration boundary is needed before an operations API or AgentCore transport
depends on those properties.

The existing PostgreSQL approval/execution schema and the separately reviewed
MariaDB permit-consumption foundation have different responsibilities. Extending
either implicitly would conflate registration with execution authority.

## Decision

Add an application `BusinessOperationRepository` port and an isolated
`persistence-operations` JDBC implementation. Qualify the same contract tests on
PostgreSQL 16.4, MariaDB 11.4.13 and transitional MariaDB 10.6.25. Select the dialect
from JDBC/server metadata, require a writer connection, and refuse other engine
versions. MariaDB requires InnoDB, flush-at-commit durability and synchronous
binlog flush when binary logging is enabled. This does not qualify replication
or RDS failover.

The scope is the exact trusted tenant, workspace and requester, supplied separately
from the document. Store its canonical fingerprint as an indexed partition key.
The client request-key fingerprint is unique within that scope. A plain insert
lets the database arbitrate concurrent claims; no pre-insert existence check,
`INSERT IGNORE`, upsert, automatic retry or process-local lock determines the winner.
The scoped request key is the primary key and the operation ID has a separate
unique constraint. InnoDB contenders for the same request therefore encounter
the same primary record before distinct operation-ID secondary entries. Repeated
race tests retain exactly one created record and the full conflict assertions.

Only the initial `evaluating` / `not_started` / `pending` aggregate is accepted.
Insert the immutable snapshot and its three initial facts in one transaction.
No method advances approval, execution or evidence delivery. Registration conveys
no approval, consumed permit, execution attempt or target call.

After a duplicate-key insert, confirm rollback before a fresh read. Return the
original record only when adapter, semantic payload digest, resource/precondition
binding and recovery generation agree. Changed content yields
`OPERATION_REQUEST_KEY_CONFLICT`; an operation-ID collision without that scoped key
yields `OPERATION_ID_CONFLICT`. Only PostgreSQL SQLSTATE 23505 or MariaDB vendor
1062 / SQLSTATE 23000 qualifies for duplicate handling. Other integrity, timeout,
rollback or connection failures cannot become an existing-record success.

Transport/event IDs, proposed operation ID, timestamps, policy release, artifact
digests and retention proposals can differ on a retry. They never overwrite the
first stored values. Reads return that original snapshot. Later policy/authority
checks must use current verified context and the stored exact action; registration
does not revive expired authority. No pruning or retention expiry frees a key.

Return `created` only after commit and connection cleanup. A commit exception,
including lost acknowledgement, yields `OPERATION_COMMIT_UNKNOWN`; rollback cannot
prove whether an attempted commit reached durable storage. A later explicit call
with the same scoped key may resolve registration uncertainty by reading the
original. This is not permission to repeat an external side effect.

Reads recompute the snapshot fingerprint and binding, revalidate the contract and
scope, and compare all three stored facts. Reject inconsistent data. These hashes
detect accidental/partial mutation; they provide neither confidentiality nor
independent authenticity against a database administrator who can rewrite hashes.
Snapshots are bounded to 256 KiB of UTF-8 in Java and by database constraints.

Installation explicitly initializes an empty dedicated operation schema/database.
Runtime construction validates migration history, object inventory, unique indexes,
foreign key and MariaDB storage engines without migration or repair. Runtime users
need SELECT/INSERT on the two operation tables and SELECT on migration history;
tests prove that these grants suffice and do not allow UPDATE/DELETE. SQL and
connection exceptions become fixed diagnostic codes. Migration logging is disabled
for this component to avoid exposing connection identifiers.

## Consequences and limits

The shared test suite covers simultaneous identical and conflicting payloads,
scope isolation, restarts, rollback of partially inserted facts, commit ambiguity,
corruption, missing uniqueness, writer/version refusal and restricted runtime grants.
An additional CI job runs all three engines with no Docker-availability skip.

The module uses a separate dedicated database (PostgreSQL public schema or a
MariaDB non-system database). It does not adopt existing enforcement tables,
provide schema upgrades, authenticate a caller, derive the raw client key's scoped
fingerprint, encrypt snapshots, redact arbitrary tool arguments, enforce retention,
protect against database-admin mutation or detect restored older backups. Pool size,
connection acquisition/network timeouts, TLS and writer endpoint selection remain
operator/integration responsibilities. Each SQL statement has a five-second query
timeout; that is not an end-to-end network deadline.

There is no operations API, AgentCore transport, lifecycle transition command,
MariaDB approval/evidence parity, GitHub dispatch, S3 publication, live RDS test,
EKS deployment or production-readiness claim in this slice. Existing simulator
launchers and databases retain their prior behavior.

Rollback removes the unused port/library and CI gate. Never drop a populated
operation database as an application rollback step; retain it for explicit future
migration and evidence review.
