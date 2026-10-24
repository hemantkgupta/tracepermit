# Qualify durable business-operation registration

This developer gate runs the same registration contract on PostgreSQL 16.4,
MariaDB 11.4.13 and transitional MariaDB 10.6.25. Containers contain synthetic
data and generated credentials. The library is not connected to shipped
simulator launchers, an operations API, AgentCore or a real target.

## Run the gate

Use Java 21, Node.js 22+, pnpm 11.25.0 and a healthy Docker engine:

```bash
corepack enable
pnpm install --frozen-lockfile
pnpm test:java-operations --rerun-tasks
pnpm check
pnpm format:check
```

The focused gate must execute every engine suite with zero failures or skips.
It creates disposable containers, bounds concurrent task waits/cleanup and removes
the containers afterward. No cloud account, GitHub App, identity provider, customer
database or reusable credential is required. Do not substitute a production
database URL for these fixtures.

## Library boundary

`BusinessOperationScope` must come from authenticated adapter context. Constructing
the record itself does not authenticate or authorize anything. The document must
contain that exact context and the three initial facts. The repository validates
internally and returns defensive copies.

An operator calls `OperationDatabase.initializeEmpty(dataSource)` explicitly once
against an empty dedicated operation database. `new JdbcBusinessOperationRepository`
only validates an existing installation. Never point it at the current simulator
approval database or the separate MariaDB permit-consumption database: their
inventories are intentionally incompatible.

Configure a pool against the writer endpoint. Grant the runtime user SELECT and
INSERT on `tp_business_operations` and `tp_business_operation_facts`, plus SELECT on
`tp_operation_schema_history`. PostgreSQL also requires USAGE on the public schema.
Migration ownership stays with a separate operator. Runtime needs no UPDATE,
DELETE, DDL or administrative grant. Live RDS/TLS/replication qualification and
deployment-ready provisioning remain future work.

## Interpret the result

| Result                                                                                        | Meaning and next step                                                                                                                                                                 |
| --------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `created = true`                                                                              | The new snapshot and three initial facts committed. Continue to independent policy evaluation; no authority is implied.                                                               |
| `created = false`                                                                             | The scoped key and request binding already exist. Use the returned original snapshot and ID.                                                                                          |
| `OPERATION_REQUEST_KEY_CONFLICT`                                                              | The key was reused with a changed semantic action, adapter, resource/precondition or recovery generation. Refuse the changed request.                                                 |
| `OPERATION_ID_CONFLICT`                                                                       | The proposed operation ID collides without a matching scoped key. Refuse it.                                                                                                          |
| `OPERATION_COMMIT_UNKNOWN`                                                                    | Commit or subsequent cleanup failed. No success was returned. A later explicit same-key registration/lookup may resolve storage uncertainty; this library retries no external action. |
| `OPERATION_STORAGE_UNAVAILABLE`                                                               | A storage, rollback or SQL dependency failed. Keep the request unevaluated and investigate.                                                                                           |
| `OPERATION_STORAGE_CORRUPT`                                                                   | Snapshot, scope, binding or initial facts disagree. Stop use and investigate privately.                                                                                               |
| `OPERATION_SCHEMA_NOT_READY` / `OPERATION_DATABASE_UNSUPPORTED` / `OPERATION_WRITER_REQUIRED` | Correct the installation or writer configuration before continuing.                                                                                                                   |

The scope fingerprint covers tenant, workspace and requester with case-sensitive
canonical JSON. The request binding covers `adapterId`, `semanticPayloadDigest`,
`resourceBindingHash` and `recoveryGeneration`. Transport/event IDs can differ.
Original policy/artifact/retention values remain unchanged even if a later proposed
document supplies different ones. Retention expiry does not delete a record or
make its key reusable.

`find(scope, operationId)` returns empty for another scope, even when the ID exists.
There is no unscoped lookup or list API. Authorized reads, request-key generation
and credential validity must be enforced by a future trusted service adapter.

## Evidence and limits

Record only source commit, engine image/version, fixed test counts and pass/fail.
Keep JDBC URLs, passwords, snapshots, arguments and real identifiers out of handoffs
and diagnostics. The database stores the exact canonical action; real requests
require operator-controlled access, encryption and an argument-sanitization policy.

The gate demonstrates registration atomicity and scoped deduplication. It does not
qualify permit consumption, approval/execution transitions, external evidence,
backup resurrection, RDS failover, production throughput or exactly-once GitHub
effects. See [ADR 0070](../architecture/0070-durable-business-operation-registration.md).
