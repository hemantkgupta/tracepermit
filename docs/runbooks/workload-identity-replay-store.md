# Workload-Identity Replay Store Runbook

## Overview

The durable workload-identity replay store (`io.tracepermit.persistence.identity.JdbcWorkloadIdentityReplayStore`, [ADR 0080](../architecture/0080-durable-workload-identity-replay.md)) provides atomic single-use tracking for verified workload credentials (such as AgentCore ingress token fingerprints) across restarts and multi-instance deployments.

Supported production database engines:

- **PostgreSQL**: `16.4`
- **MariaDB**: `11.4.13` (primary) and `10.6.25` (compatibility) with InnoDB

---

## Architecture and Database Objects

The store maintains a dedicated, self-contained schema consisting of three exact tables:

| Table                               | Engine (PG / MariaDB) | Description                                                                                                                          |
| :---------------------------------- | :-------------------- | :----------------------------------------------------------------------------------------------------------------------------------- |
| `tp_identity_replay_schema_history` | Relational            | Flyway migration version history.                                                                                                    |
| `tp_workload_replay_claims`         | Relational / InnoDB   | Stores hashed claim records (`claim_hash`, `tenant_hash`, `token_fingerprint`, `expires_at`, `claimed_at`).                          |
| `tp_workload_replay_state`          | Relational / InnoDB   | Singleton row (`singleton = 1`) tracking atomic claim counts (`total_claims`) and agreed operator-owned capacity limit (`capacity`). |

> [!IMPORTANT]
> **Credential Privacy Boundary**: The database stores **only** cryptographic SHA-256 digests (`sha256:...`) and UTC timestamps. Raw bearer tokens, authorization headers, private keys, and user claims are never written to disk or logs.
> All runtime exceptions discard causes and suppressed exceptions to prevent credentials, URLs, or database diagnostics from escaping.

---

## Database User Role Separation

### 1. Migration Owner Role

Used exclusively during offline bootstrap or schema initialization. Requires DDL privileges:

- PostgreSQL: `CREATE, USAGE ON SCHEMA public;`
- MariaDB: `ALL PRIVILEGES ON <database_name>.*;`

### 2. Runtime Application Role

Used by operational ingress services. Must have strictly bounded DML privileges:

#### PostgreSQL Configuration

```sql
CREATE ROLE tp_identity_runtime LOGIN PASSWORD '<secure_password>';
GRANT USAGE ON SCHEMA public TO tp_identity_runtime;
GRANT SELECT, INSERT ON tp_workload_replay_claims TO tp_identity_runtime;
GRANT SELECT, UPDATE ON tp_workload_replay_state TO tp_identity_runtime;
GRANT SELECT ON tp_identity_replay_schema_history TO tp_identity_runtime;
```

#### MariaDB Configuration

```sql
CREATE USER 'tp_identity_runtime'@'%' IDENTIFIED BY '<secure_password>';
GRANT SELECT, INSERT ON `<database_name>`.tp_workload_replay_claims TO 'tp_identity_runtime'@'%';
GRANT SELECT, UPDATE ON `<database_name>`.tp_workload_replay_state TO 'tp_identity_runtime'@'%';
GRANT SELECT ON `<database_name>`.tp_identity_replay_schema_history TO 'tp_identity_runtime'@'%';
```

---

## Operational Procedures

### Initial Installation (Empty Schema Only)

To initialize a fresh, dedicated database schema:

1. Ensure the target database exists and contains zero tables, views, procedures, or triggers.
2. Execute the initialization routine using migration owner credentials with the agreed operator capacity (default `10,000`):
   ```java
   WorkloadIdentityReplayDatabase.initializeEmpty(migrationDataSource, capacity);
   ```
3. Initialization will run Flyway migrations and validate the schema, creating version 2 and setting initial state `total_claims = 0` and `capacity = <capacity>`.
4. If any existing objects or tables are present, `initializeEmpty` will fail closed with `REPLAY_SCHEMA_NOT_READY`.

### Upgrading an Existing V1 Database

To upgrade an existing V1 schema to V2:

1. Ensure runtime writers are paused.
2. Run explicit upgrade using migration owner credentials:
   ```java
   WorkloadIdentityReplayDatabase.upgradeFromV1(migrationDataSource, capacity);
   ```
3. The upgrade applies `V2__workload_replay_agreed_capacity.sql`, populates the operator-agreed capacity, and verifies complete physical DDL integrity.

### Runtime Validation

Ingress services constructing `JdbcWorkloadIdentityReplayStore` automatically validate the schema and agreed capacity without running DDL:

```java
WorkloadIdentityReplayStore replayStore = new JdbcWorkloadIdentityReplayStore(runtimeDataSource, capacity);
```

Runtime validation asserts:

- Exact table whitelist (`tp_identity_replay_schema_history`, `tp_workload_replay_claims`, `tp_workload_replay_state`).
- Engine version matches supported PostgreSQL or MariaDB profiles.
- Writable endpoint (fails closed on replicas or read-only connections).
- Zero triggers, routines, or events.
- Physical column definitions, types, nullability, and collations (`"C"` on PG, `ascii_bin` on MariaDB).
- Primary key constraints on all tables.
- Persisted capacity in `tp_workload_replay_state` exactly matches local configured capacity (mismatch throws `REPLAY_CAPACITY_MISMATCH`).

---

## Capacity Monitoring & Saturation Alerts

The store enforces finite capacity (`DEFAULT_CAPACITY = 10,000` or operator-configured). When `total_claims >= capacity`, subsequent fresh token submissions fail closed (`return false`).

### Monitoring Query

Operators should poll the claim count metric periodically:

```sql
SELECT total_claims, capacity FROM tp_workload_replay_state WHERE singleton = 1;
```

### Alert Thresholds

- **Warning**: `total_claims >= capacity * 0.80` (80% full).
- **Critical**: `total_claims >= capacity * 0.95` (95% full).
- **Action Required**: Plan maintenance or resize capacity ceiling.

---

## Troubleshooting & Incident Handling

| Exception Code                     | Probable Cause                                                                           | Action                                                                    |
| :--------------------------------- | :--------------------------------------------------------------------------------------- | :------------------------------------------------------------------------ |
| `REPLAY_STORAGE_READ_ONLY`         | Node connected to read-only replica or RDS failover replica.                             | Point connection pool to authoritative writer endpoint.                   |
| `REPLAY_SCHEMA_NOT_READY`          | Missing tables, dropped columns (e.g. `expires_at`), trigger detected, or missing state. | Run schema validation inspection; ensure all physical columns are intact. |
| `REPLAY_CAPACITY_MISMATCH`         | Local client configured capacity differs from persisted database capacity.               | Ensure all ingress nodes configure the identical agreed capacity.         |
| `REPLAY_STORAGE_PERMISSION_DENIED` | Runtime user lacks `INSERT` or `UPDATE` privileges.                                      | Verify runtime role grants against permissions guide above.               |
| `REPLAY_COMMIT_UNKNOWN`            | Network timeout, lock wait past expiry, or socket reset during transaction commit/close. | Check database host connectivity; do not assume operation succeeded.      |
| `REPLAY_STORAGE_UNAVAILABLE`       | Database unreachable or connection pool exhausted.                                       | Verify database health, host resources, and connection pool sizing.       |
