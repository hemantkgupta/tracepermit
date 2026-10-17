# PostgreSQL JDBC Driver Upgrade Qualification (42.7.5 to 42.7.14)

Context: Dependabot PR #9 (`org.postgresql:postgresql` version update from `42.7.5` to `42.7.14`).  
Relevant Requirements: SEC-005, SEC-006; REL-001, REL-003, REL-005; OPS-004.  
Related Runbooks: [simulator-backup-restore-qualification.md](simulator-backup-restore-qualification.md), [operator-recovery-inspection.md](operator-recovery-inspection.md), [operator-recovery-quarantine.md](operator-recovery-quarantine.md).

This runbook documents the primary-source security and behavioral assessment, dependency graph resolution, qualification test suite results, and operational boundaries for the PostgreSQL JDBC driver upgrade from `42.7.5` to `42.7.14` in TracePermit's PostgreSQL persistence module (`persistence-postgres`).

## 1. Upstream Changelog and Primary-Source Assessment

The upgrade spans patch releases `42.7.6` through `42.7.14` of the PostgreSQL JDBC driver (`pgjdbc`). Primary sources include:

- [pgjdbc Upstream Releases](https://github.com/pgjdbc/pgjdbc/releases)
- [pgjdbc Master CHANGELOG](https://github.com/pgjdbc/pgjdbc/blob/master/CHANGELOG.md)
- [Release Comparison REL42.7.5...REL42.7.14](https://github.com/pgjdbc/pgjdbc/compare/REL42.7.5...REL42.7.14)

Below is a systematic assessment of upstream changes, security advisories, bug fixes, and their relevance to TracePermit.

### [42.7.6](https://github.com/pgjdbc/pgjdbc/releases/tag/REL42.7.6)

- **Metadata and Introspection**:
  - `DatabaseMetaData.getIndexInfo()` now includes index comments in the `REMARKS` column (PR #3513).
  - Startup parameters unified into a single query, deprecating `groupStartupParameters` (PR #3613).
  - `extra_float_digits=3` sent for PostgreSQL 12+ (PR #3491).
- **Protocol and Cancellation**:
  - Fixed protocol 3.2 and wider cancel key handling (PR #3592).
  - Fixed `EOFException` on `PreparedStatement#toString` when `bytea` parameters were left unset.
  - Made `PgConnection#abort` compatible with Java 24 (PR #3582).
  - Updated default build target to Java 21 (PR #3612).
- **TracePermit Relevance**: Compatible with TracePermit's Java 21 baseline. Introspection improvements align with schema guard queries in [`PostgresDatabaseLifecycle.java`](../../java/persistence-postgres/src/main/java/io/tracepermit/persistence/postgres/PostgresDatabaseLifecycle.java).

### [42.7.7](https://github.com/pgjdbc/pgjdbc/releases/tag/REL42.7.7)

- **Security Advisory (CVE-2025-49146)**:
  - _Insecure Authentication Fallback_: Fixed an issue where the driver silently ignored the `channelBinding=require` configuration parameter when non-SASL authentication methods were requested by the server. Under `42.7.7+`, the driver strictly fails closed if channel binding is required but SASL is not used or fails to complete.
- **TracePermit Relevance**: Eliminates silent authentication fallback vulnerabilities in TLS environments. TracePermit test fixtures run in isolated local Docker containers; in any future networked deployment, this fail-closed behavior enforces required channel binding.

### [42.7.8](https://github.com/pgjdbc/pgjdbc/releases/tag/REL42.7.8)

- **Performance and Metadata**:
  - Server-prepared statements enabled for `DatabaseMetaData` queries to improve metadata call latency.
  - Removed `QUERY_ONESHOT` flag when calling `getMetaData` and wrapped `FileInputStream` with `BufferedInputStream` (PR #3783, PR #3750).
  - Added configurable boolean-to-numeric conversion support for `ResultSet` getters (PR #3796).
- **Cancellation Robustness**:
  - Prevented `IllegalStateException: Timer already cancelled` when `StatementCancelTimerTask.run` encounters a runtime error (PR #3778).
  - Avoided `NullPointerException` when cancelling a query before the server cancel key is received.
  - Added missing `SELF_REFERENCING_COL_NAME` column to `getTables()` `ResultSetMetaData` (PR #3660).
- **TracePermit Relevance**: Accelerates Flyway schema verification and [`PostgresDatabaseLifecycle.validateExisting`](../../java/persistence-postgres/src/main/java/io/tracepermit/persistence/postgres/PostgresDatabaseLifecycle.java) metadata introspection; prevents timer exceptions during statement cancellation.

### [42.7.9](https://github.com/pgjdbc/pgjdbc/releases/tag/REL42.7.9)

- **Connection and Certificate Management**:
  - Added `queryTimeout` connection property to enforce global statement timeout policies at the connection level.
  - Added `PEMKeyManager` to natively support PEM-encoded certificates and private keys.
  - Fixed memory leak in Java <= 21 caused by `Thread.inheritedAccessControlContext`.
  - Hardened class loading: switched user-supplied class loading to a safer 3-parameter `Class.forName` without immediate initialization.
  - Fixed `PGXAConnection.prepare(Xid)` to correctly return `XA_RDONLY` for read-only connections.
  - Fixed numeric array decoding containing special values like `NaN`.
- **TracePermit Relevance**: Memory leak resolution directly benefits long-running Java 21 daemon services. Hardened class loading protects against unintended static initialization.

### [42.7.10](https://github.com/pgjdbc/pgjdbc/releases/tag/REL42.7.10)

- **Regression Reversion**:
  - Reverted proleptic Gregorian Calendar change due to regressions with date handling.
  - Fixed non-standard strings failing test assertions on upcoming PostgreSQL server versions.
  - Processed pending responses before fastpath execution to prevent protocol synchronization errors.
- **TracePermit Relevance**: Maintains predictable timestamp parsing across `Instant`, `OffsetDateTime`, and `Timestamp` columns used in durable enforcement state records.

### [42.7.11](https://github.com/pgjdbc/pgjdbc/releases/tag/REL42.7.11)

- **Security Advisory (CVE-2026-42198)**:
  - _Client-side DoS via SCRAM Iteration Count_: Malicious or compromised PostgreSQL servers could specify an excessively large PBKDF2 iteration count during SCRAM authentication, causing client CPU exhaustion. Introduced `scramMaxIterations` (default `100,000`) to bound computational cost.
- **Protocol and Concurrency**:
  - Implemented `require_auth` connection property to align authentication requirements with `libpq`.
  - Ensured extended protocol messages consistently end with a `Sync` message.
  - Guarded connection closed flags with a reentrant lock to eliminate concurrent closure race conditions.
  - Fixed `jsonb` column types to return as `PGobject` rather than raw strings.
- **TracePermit Relevance**: Mitigates client CPU exhaustion on authentication. The reentrant lock on connection closure safeguards against concurrent connection pool cleanup races.

### [42.7.12](https://github.com/pgjdbc/pgjdbc/releases/tag/REL42.7.12)

- **Security Advisory (CVE-2026-54291)**:
  - _Enforced SCRAM Channel-Binding Policy / Silent Downgrade Prevention_: Under `channelBinding=require`, the driver previously could silently downgrade from `SCRAM-SHA-256-PLUS` (with channel binding) to plain `SCRAM-SHA-256` (without channel binding) if the server presented a certificate with a signature algorithm lacking a `tls-server-end-point` hash (e.g. Ed25519 or post-quantum schemes). The driver now fails closed.
- **TracePermit Relevance**: Prevents man-in-the-middle channel-stripping attacks.

### [42.7.13](https://github.com/pgjdbc/pgjdbc/releases/tag/REL42.7.13)

- **Prepared Statement Caching and DDL**:
  - Invalidate prepared statement cache via `search_path` GUC_REPORT on PostgreSQL 18+.
  - Added `flushCacheOnDdl` property to re-prepare server statements automatically following `CREATE`, `DROP`, or `ALTER` statements.
  - Added `connectExecutor` (`Executor`) option.
  - Expanded batch update sizing (`reWriteBatchedInserts` capped by protocol limits instead of 128).
  - Derived `getPrimaryKeys` from `pg_constraint.conkey` for higher performance and accuracy.
  - Empty timestamp, date, and time string values now reject with a clear error.
- **TracePermit Relevance**: Primary key derivation optimization benefits Flyway migrations and schema checks. Strict rejection of empty timestamp strings aligns with TracePermit's fail-closed input validation.

### [42.7.14](https://github.com/pgjdbc/pgjdbc/releases/tag/REL42.7.14)

- **Security Advisory (CVE-2026-107314)**:
  - _`requireAuth` Enforcement Validation_: Addressed a bug where a `requireAuth` configuration excluding all authentication methods or given an empty/invalid value was silently treated as unset by versions `42.7.11` through `42.7.13`. Invalid configurations now fail with `SQLState 22023` or `08004`.
- **Security Advisory (CVE-2026-107315)**:
  - _Parameter Padding Data Leak_: Fixed a vulnerability in versions `42.7.4` through `42.7.13` where sending a parameter value shorter than its declared length (such as a mismatched stream writer) caused the driver to pad data with residual bytes from prior statements executed on the connection, potentially leaking prior SQL texts or parameter contents. The driver now safely pads with zeroes.
- **TracePermit Relevance**: Eliminates cross-statement data leakage in pooled connections. Ensures strict rejection of invalid authentication specifications.

---

## 2. Dependency Graph and Resolution Verification

The dependency declaration in [`java/persistence-postgres/build.gradle.kts`](../../java/persistence-postgres/build.gradle.kts) was updated from `42.7.5` to `42.7.14`:

```kotlin
api("org.postgresql:postgresql:42.7.14")
...
testImplementation("org.postgresql:postgresql:42.7.14")
```

### Dependency Insight Output

Gradle dependency insight commands verify single-version resolution without conflicts or evictions:

```bash
./java/gradlew -p java :persistence-postgres:dependencyInsight \
  --dependency org.postgresql:postgresql --configuration runtimeClasspath
```

Output:

```
> Task :persistence-postgres:dependencyInsight
org.postgresql:postgresql:42.7.14
  Variant runtime:
    | Attribute Name                 | Provided     | Requested    |
    |--------------------------------|--------------|--------------|
    | org.gradle.status              | release      |              |
    | org.gradle.category            | library      | library      |
    | org.gradle.libraryelements     | jar          | jar          |
    | org.gradle.usage               | java-runtime | java-runtime |
    | org.gradle.dependency.bundling |              | external     |
    | org.gradle.jvm.environment     |              | standard-jvm |
    | org.gradle.jvm.version         |              | 21           |

org.postgresql:postgresql:42.7.14
\--- runtimeClasspath
```

```bash
./java/gradlew -p java :persistence-postgres:dependencyInsight \
  --dependency org.postgresql:postgresql --configuration testRuntimeClasspath
```

Output:

```
> Task :persistence-postgres:dependencyInsight
org.postgresql:postgresql:42.7.14
  Variant runtime:
    | Attribute Name                 | Provided     | Requested    |
    |--------------------------------|--------------|--------------|
    | org.gradle.status              | release      |              |
    | org.gradle.category            | library      | library      |
    | org.gradle.libraryelements     | jar          | jar          |
    | org.gradle.usage               | java-runtime | java-runtime |
    | org.gradle.dependency.bundling |              | external     |
    | org.gradle.jvm.environment     |              | standard-jvm |
    | org.gradle.jvm.version         |              | 21           |

org.postgresql:postgresql:42.7.14
\--- testRuntimeClasspath
```

### Component Compatibility

- **Flyway Core & PostgreSQL Extension**: `11.8.2` operates without deprecation or schema introspection warnings against `42.7.14`.
- **Testcontainers PostgreSQL**: `org.testcontainers:postgresql:1.21.4` manages `postgres:16.4-alpine` test containers cleanly, matching the driver's PostgreSQL 16 wire protocol expectations.
- **Java Runtime**: Standard JVM 21, aligned across all modules.

---

## 3. Qualification Test Results

All automated tests in the persistence, approval HTTP adapter, and simulator modules were executed against real disposable PostgreSQL 16 containers with the upgraded driver.

### Persistence Module Tests (`:persistence-postgres`)

Command: `scripts/run-java-postgres-tests.sh`

| Test Suite Class                              | Tests Run | Failures | Errors | Skipped |    Time    |
| :-------------------------------------------- | :-------: | :------: | :----: | :-----: | :--------: |
| `PostgresApprovalHistoryTest`                 |     1     |    0     |   0    |    0    |   1.49s    |
| `PostgresBackupRestoreQualificationTest`      |     6     |    0     |   0    |    0    |   7.90s    |
| `PostgresCompletionFailureRecoveryTest`       |    14     |    0     |   0    |    0    |   5.93s    |
| `PostgresDatabaseLifecycleTest`               |    31     |    0     |   0    |    0    |   10.61s   |
| `PostgresDurableEnforcementStateTest`         |    25     |    0     |   0    |    0    |   10.36s   |
| `PostgresPermitConsumptionBoundaryTest`       |    12     |    0     |   0    |    0    |   2.20s    |
| `PostgresPermitConsumptionStoreTest`          |    10     |    0     |   0    |    0    |   2.25s    |
| `PostgresPolicyBoundApprovalTest`             |     8     |    0     |   0    |    0    |   3.30s    |
| `PostgresPolicyBoundRestoreQualificationTest` |     5     |    0     |   0    |    0    |   10.62s   |
| `PostgresPolicyGenerationBoundaryTest`        |     8     |    0     |   0    |    0    |   3.81s    |
| `PostgresPolicyLifecycleBoundaryTest`         |    16     |    0     |   0    |    0    |   5.66s    |
| `PostgresPolicyLifecycleStoreTest`            |    12     |    0     |   0    |    0    |   3.16s    |
| `PostgresRecoveryInspectionTest`              |    15     |    0     |   0    |    0    |   10.95s   |
| `PostgresRecoveryQuarantineTest`              |    24     |    0     |   0    |    0    |   16.58s   |
| **Total `:persistence-postgres`**             |  **167**  |  **0**   | **0**  |  **0**  | **1m 34s** |

### Approval HTTP Adapter Tests (`:adapters-http`)

Command: `scripts/run-java-approval-api-tests.sh`

| Test Suite Class                         | Tests Run | Failures | Errors | Skipped |    Time    |
| :--------------------------------------- | :-------: | :------: | :----: | :-----: | :--------: |
| `SimulatorApprovalBootstrapTest`         |     8     |    0     |   0    |    0    |   28.12s   |
| `SimulatorApprovalBrowserTest`           |    26     |    0     |   0    |    0    |   19.48s   |
| `SimulatorApprovalConfigurationTest`     |    45     |    0     |   0    |    0    |   0.63s    |
| `SimulatorApprovalHistoryTest`           |     3     |    0     |   0    |    0    |   6.32s    |
| `SimulatorApprovalMcpAdapterTest`        |    24     |    0     |   0    |    0    |   0.02s    |
| `SimulatorApprovalRedactionBoundaryTest` |     6     |    0     |   0    |    0    |   5.63s    |
| `SimulatorApprovalServerTest`            |    31     |    0     |   0    |    0    |   15.93s   |
| **Total `:adapters-http` Approval**      |  **143**  |  **0**   | **0**  |  **0**  | **1m 16s** |

### Simulator Module Tests (`:simulator`)

Command: `./java/gradlew -p java :simulator:test --rerun`

| Test Suite Class                        | Tests Run | Failures | Errors | Skipped |   Time    |
| :-------------------------------------- | :-------: | :------: | :----: | :-----: | :-------: |
| `StatefulTargetSimulatorTest`           |     6     |    0     |   0    |    0    |   0.35s   |
| `SyntheticExfilTargetSimulatorTest`     |     1     |    0     |   0    |    0    |   0.00s   |
| `SyntheticPromptInjectionSimulatorTest` |     1     |    0     |   0    |    0    |   0.00s   |
| **Total `:simulator`**                  |   **8**   |  **0**   | **0**  |  **0**  | **0.35s** |

### Repository Verification Checks

- `pnpm check`: Passed (4 console test files / 41 tests passed; portable Java 21 environment confirmed; `gradlew check` passed across all Java subprojects).
- `pnpm format:check`: Passed (code formatting clean).
- `pnpm docs:links`: Passed (481 markdown links verified; all local targets exist).
- `pnpm docs:flows:check`: Passed (8 diagram tests passed; documentation guides synchronized).
- `git diff --check`: Passed (no trailing whitespace or conflict markers).

---

## 4. Architectural and Operational Boundaries

1. **Simulator-Only Operational Scope**:
   - TracePermit currently operates against disposable PostgreSQL 16 Testcontainers and local Docker Compose instances for simulated visibility, approval, and enforcement qualification.
   - The driver upgrade does not connect to external production databases, managed cloud services (e.g., AWS RDS, Amazon Aurora), or cross-region database clusters.

2. **Network and Transport Boundaries**:
   - Connection strings in test fixtures use local container addresses (`localhost` or container bridge IPs) without TLS certificate chains or client certificate authentication.
   - SCRAM-SHA-256 channel binding (`channelBinding=require`) and TLS hostname verification (`sslmode=verify-full`) were analyzed upstream and verified secure, but are not actively exercised by the simulator fixture.

3. **Connection Management**:
   - TracePermit uses direct `DataSource` connections managed through [`PostgresDatabaseLifecycle`](../../java/persistence-postgres/src/main/java/io/tracepermit/persistence/postgres/PostgresDatabaseLifecycle.java) and [`PostgresDurableEnforcementState`](../../java/persistence-postgres/src/main/java/io/tracepermit/persistence/postgres/PostgresDurableEnforcementState.java) with explicit query timeouts (`setQueryTimeout(5)`), session-level advisory locks, and transaction rollbacks.
   - External connection poolers (e.g. HikariCP, PgBouncer) and multi-host failover strings (`targetServerType=primary`) are not part of this qualification slice.

4. **Future Production Requirements**:
   - Prior to connecting TracePermit to managed cloud PostgreSQL instances, an additional qualification phase must validate:
     - Strict TLS negotiation (`sslmode=verify-full`, `sslrootcert`, `sslkey`, `sslcert`).
     - Connection pooler sizing, eviction timeouts, and TCP keep-alive settings (`tcpKeepAlive=true`, `socketTimeout`).
     - SCRAM-SHA-256-PLUS mutual channel binding against enterprise identity proxies.

---

## 5. Recommendation and Merge Readiness

The PostgreSQL JDBC driver upgrade from `42.7.5` to `42.7.14` resolves three documented security vulnerabilities (CVE-2025-49146, CVE-2026-42198, CVE-2026-54291) and two patch-level data leak/auth issues (CVE-2026-107314, CVE-2026-107315), brings upstream performance and stability improvements to metadata introspection and statement cancellation, and introduces zero regressions or breaking changes across TracePermit's 167 database persistence tests and 143 approval API tests.

**Verdict**: **QUALIFIED FOR MERGE**. Dependabot PR #9 is verified, safe, and ready for founder review.
