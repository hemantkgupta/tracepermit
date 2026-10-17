# PostgreSQL JDBC Driver Upgrade Qualification (42.7.5 to 42.7.14)

Context: Dependabot PR #9 (`org.postgresql:postgresql` version update from `42.7.5` to `42.7.14`).  
Relevant Requirements: SEC-005, SEC-006; REL-001, REL-003, REL-005; OPS-004.  
Related Runbooks: [simulator-backup-restore-qualification.md](simulator-backup-restore-qualification.md), [operator-recovery-inspection.md](operator-recovery-inspection.md), [operator-recovery-quarantine.md](operator-recovery-quarantine.md).

This runbook documents the primary-source security and behavioral assessment, dependency graph resolution, qualification test suite results, and operational boundaries for the PostgreSQL JDBC driver upgrade from `42.7.5` to `42.7.14` in TracePermit's PostgreSQL persistence module (`persistence-postgres`).

---

## 1. Primary-Source Security Applicability and Upstream Changelog

The upgrade spans patch releases `42.7.6` through `42.7.14` of the PostgreSQL JDBC driver (`pgjdbc`). Primary upstream sources include:

- [pgjdbc Upstream Releases](https://github.com/pgjdbc/pgjdbc/releases)
- [pgjdbc Master CHANGELOG](https://github.com/pgjdbc/pgjdbc/blob/master/CHANGELOG.md)
- [Release Comparison REL42.7.5...REL42.7.14](https://github.com/pgjdbc/pgjdbc/compare/REL42.7.5...REL42.7.14)

### Security Advisories Matrix

The following matrix assesses relevant upstream security advisories across the `42.7.5` → `42.7.14` interval against TracePermit's inspected application paths:

| Advisory & Identifier                                                                                                                                              | Upstream Affected Range | First Fixed Version  | Relevance to Starting 42.7.5                          | Status in Selected 42.7.14                                                     | Inspected Application Path / Settings                                                                                                                                                                                                                                                                                                                           | Unqualified Scope / Residual Risk                                                                                                                                                                                                                                                                      |
| :----------------------------------------------------------------------------------------------------------------------------------------------------------------- | :---------------------- | :------------------- | :---------------------------------------------------- | :----------------------------------------------------------------------------- | :-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | :----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| [GHSA-hcjv-f5jh-r55v](https://github.com/pgjdbc/pgjdbc/security/advisories/GHSA-hcjv-f5jh-r55v) (Unbounded SSL cert-file parsing)                                  | `<= 42.7.14`            | None (open upstream) | Affected upstream                                     | **Unresolved upstream** (no patch released)                                    | Simulator launcher [`SimulatorApprovalConfiguration.java`](../../java/adapters-http/src/main/java/io/tracepermit/adapters/http/SimulatorApprovalConfiguration.java) enforces host `127.0.0.1`, sets `sslMode="disable"`, and accepts no client or root cert files. Test fixtures use local Docker container without TLS certs.                                  | Caller-supplied `DataSource` deployments that enable TLS or load certificate paths from external sources are **NOT QUALIFIED** and remain subject to upstream vulnerability. `loginTimeout` is a reported mitigation bounding connection setup, but is distinct from `connectTimeout`/`socketTimeout`. |
| [GHSA-f64h-wr5q-3qf3](https://github.com/pgjdbc/pgjdbc/security/advisories/GHSA-f64h-wr5q-3qf3) / CVE-2026-107315 (Parameter padding with stale buffer data)       | `42.7.4` – `42.7.13`    | `42.7.14`            | Affected upstream                                     | **Fixed upstream** (pads with zeroes)                                          | [`PostgresDurableEnforcementState.java`](../../java/persistence-postgres/src/main/java/io/tracepermit/persistence/postgres/PostgresDurableEnforcementState.java) uses standard typed `PreparedStatement` setters (`setString`, `setObject`, `setInt`, `setLong`). It does not use `ByteStreamWriter`, streaming parameter APIs, or mismatched declared lengths. | External callers supplying custom streaming writers with mismatched declared lengths on pooled connections would have been exposed on 42.7.5; fixed on 42.7.14.                                                                                                                                        |
| [GHSA-rhp9-mr79-r74h](https://github.com/pgjdbc/pgjdbc/security/advisories/GHSA-rhp9-mr79-r74h) / CVE-2026-107314 (`requireAuth` configuration exclusions ignored) | `42.7.11` – `42.7.13`   | `42.7.14`            | **Not affected** (property introduced in 42.7.11)     | **Fixed upstream** (fails with `SQLState 22023` / `08004`)                     | TracePermit does not set `requireAuth` in simulator or test configurations.                                                                                                                                                                                                                                                                                     | Direct upgrade from 42.7.5 to 42.7.14 avoids inheriting the intermediate 42.7.11–42.7.13 regression.                                                                                                                                                                                                   |
| [GHSA-j92g-9f8w-j867](https://github.com/pgjdbc/pgjdbc/security/advisories/GHSA-j92g-9f8w-j867) / CVE-2026-54291 (SCRAM channel-binding silent downgrade)          | `42.7.7` – `42.7.11`    | `42.7.12`            | **Not affected** (downgrade bug introduced in 42.7.7) | **Fixed upstream** (fails closed when cert signature lacks hash)               | Simulator approval launcher sets `sslMode="disable"`; local test containers do not configure TLS certificates.                                                                                                                                                                                                                                                  | Networked TLS deployments requiring SCRAM-SHA-256-PLUS channel binding against servers with Ed25519/quantum-safe certificates are **NOT QUALIFIED**.                                                                                                                                                   |
| [GHSA-98qh-xjc8-98pq](https://github.com/pgjdbc/pgjdbc/security/advisories/GHSA-98qh-xjc8-98pq) / CVE-2026-42198 (Client CPU DoS via SCRAM iteration count)        | `<= 42.7.10`            | `42.7.11`            | Affected upstream                                     | **Fixed upstream** (caps iterations via `scramMaxIterations`, default 100,000) | Test fixtures and simulator connect only to owned local disposable containers and loopback PostgreSQL, not untrusted remote servers.                                                                                                                                                                                                                            | Protection applies if connecting to untrusted or compromised remote PostgreSQL endpoints.                                                                                                                                                                                                              |
| CVE-2025-49146 (Insecure auth fallback under `channelBinding=require`)                                                                                             | `42.7.0` – `42.7.6`     | `42.7.7`             | Affected upstream                                     | **Fixed upstream** (fails closed on non-SASL auth)                             | Test fixtures and simulator do not configure `channelBinding=require` and disable SSL on loopback.                                                                                                                                                                                                                                                              | Networked deployments requiring channel binding over TLS are **NOT QUALIFIED**.                                                                                                                                                                                                                        |
| [GHSA-hggc-j2qh-988m](https://github.com/pgjdbc/pgjdbc/security/advisories/GHSA-hggc-j2qh-988m) / CVE-2026-107313 (GSS-encrypted large-value buffer reuse)         | `42.7.4` – `42.7.5`     | `42.7.6`             | Affected upstream                                     | **Fixed upstream** (large object GSS indexing corrected)                       | TracePermit does not configure `gssEncMode` (`gssEncMode=disable` or unconfigured) and does not use Kerberos/GSSAPI credentials.                                                                                                                                                                                                                                | GSSAPI/Kerberos encrypted connections are **NOT QUALIFIED**.                                                                                                                                                                                                                                           |

### Upstream Non-Security Changes (Relevant Excerpt)

- **[42.7.6](https://github.com/pgjdbc/pgjdbc/releases/tag/REL42.7.6)**: `DatabaseMetaData.getIndexInfo()` includes index remarks in `REMARKS` (PR #3513); unified startup parameters; fixed `EOFException` on `PreparedStatement#toString` with unset `bytea`; made `PgConnection#abort` compatible with Java 24; upstream build target updated to Java 21 (PR #3612; published driver retains multi-release Java bytecode compatibility).
- **[42.7.8](https://github.com/pgjdbc/pgjdbc/releases/tag/REL42.7.8)**: Server-prepared statements for `DatabaseMetaData` queries; removed `QUERY_ONESHOT` on `getMetaData`; added `SELF_REFERENCING_COL_NAME` to `getTables()` metadata; prevented `IllegalStateException` and NPE in query cancel timer tasks.
- **[42.7.9](https://github.com/pgjdbc/pgjdbc/releases/tag/REL42.7.9)**: Added `queryTimeout` connection property; `PEMKeyManager` for PEM certs; resolved memory leak in Java $\le$ 21 caused by `Thread.inheritedAccessControlContext`; hardened class loading via 3-parameter `Class.forName`.
- **[42.7.10](https://github.com/pgjdbc/pgjdbc/releases/tag/REL42.7.10)**: Reverted proleptic Gregorian calendar change to restore date/time parsing compatibility.
- **[42.7.13](https://github.com/pgjdbc/pgjdbc/releases/tag/REL42.7.13)**: Invalidate prepared statement cache via `search_path` GUC_REPORT for PG18+; `flushCacheOnDdl` property; `getPrimaryKeys` derived from `pg_constraint.conkey`; empty timestamp/date strings rejected with clear error.

_(Note: Upstream performance and memory hygiene enhancements are documented from release notes; no application-level performance benchmark was conducted as part of this qualification slice.)_

---

## 2. Dependency Graph and Resolution Verification

The dependency declaration in [`java/persistence-postgres/build.gradle.kts`](../../java/persistence-postgres/build.gradle.kts) was updated from `42.7.5` to `42.7.14`:

```kotlin
api("org.postgresql:postgresql:42.7.14")
...
testImplementation("org.postgresql:postgresql:42.7.14")
```

### Exact Gradle Dependency Insight Excerpts

Commands executed on the Java project root (`java/`):

1. **`persistence-postgres:dependencyInsight` (`runtimeClasspath`)**:

```
./java/gradlew -p java :persistence-postgres:dependencyInsight \
  --dependency org.postgresql:postgresql --configuration runtimeClasspath
```

Actual output excerpt:

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

2. **`persistence-postgres:dependencyInsight` (`testRuntimeClasspath`)**:

```
./java/gradlew -p java :persistence-postgres:dependencyInsight \
  --dependency org.postgresql:postgresql --configuration testRuntimeClasspath
```

Actual output excerpt:

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

3. **Downstream `:adapters-http` (`runtimeClasspath`)**:

```
./java/gradlew -p java :adapters-http:dependencyInsight \
  --dependency org.postgresql:postgresql --configuration runtimeClasspath
```

Actual output excerpt:

```
> Task :adapters-http:dependencyInsight
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
\--- project :persistence-postgres
     \--- runtimeClasspath
```

### Component Compatibility

- **Single Driver Resolution**: `org.postgresql:postgresql:42.7.14` resolves uniquely across compile, runtime, test, and downstream module configurations without evictions or conflicts.
- **Flyway Core & PostgreSQL Extension**: `11.8.2` operates without deprecation or schema introspection warnings against `42.7.14`.
- **Testcontainers PostgreSQL**: `org.testcontainers:postgresql:1.21.4` manages the `postgres:16.4-alpine` test container fixture cleanly.
- **Java Runtime**: Tested against OpenJDK 21.0.12.1 (build 21.0.12.1 Homebrew) on macOS; CI builds on GitHub Actions Ubuntu `setup-java` 21.

---

## 3. Qualification Test Results and Classification

Test runs were executed on **2026-10-17** against reconciled base `7ce8afe9df0b1447e5a16d2a8747a4547807d7f2` on branch `dependabot/gradle/java/org.postgresql-postgresql-42.7.14`.

Test suites are categorized below based on whether they execute against a real PostgreSQL 16 container fixture or run in-memory without a database.

### Real Disposable PostgreSQL 16 Integration Tests

These suites connect to a real, disposable PostgreSQL 16.4 container fixture (`postgres:16.4-alpine`) managed via Testcontainers:

#### Persistence Module (`:persistence-postgres`)

Command: `scripts/run-java-postgres-tests.sh` (or `java/gradlew -p java :persistence-postgres:test`)

| Test Suite Class                                       | Tests Run | Failures | Errors | Skipped | Test Execution Time |
| :----------------------------------------------------- | :-------: | :------: | :----: | :-----: | :-----------------: |
| `PostgresApprovalHistoryTest`                          |     1     |    0     |   0    |    0    |        1.49s        |
| `PostgresBackupRestoreQualificationTest`               |     6     |    0     |   0    |    0    |        7.90s        |
| `PostgresCompletionFailureRecoveryTest`                |    14     |    0     |   0    |    0    |        5.93s        |
| `PostgresDatabaseLifecycleTest`                        |    31     |    0     |   0    |    0    |       10.61s        |
| `PostgresDurableEnforcementStateTest`                  |    25     |    0     |   0    |    0    |       10.36s        |
| `PostgresPermitConsumptionBoundaryTest`                |    12     |    0     |   0    |    0    |        2.20s        |
| `PostgresPermitConsumptionStoreTest`                   |    10     |    0     |   0    |    0    |        2.25s        |
| `PostgresPolicyBoundApprovalTest`                      |     8     |    0     |   0    |    0    |        3.30s        |
| `PostgresPolicyBoundRestoreQualificationTest`          |     5     |    0     |   0    |    0    |       10.62s        |
| `PostgresPolicyGenerationBoundaryTest`                 |     8     |    0     |   0    |    0    |        3.81s        |
| `PostgresPolicyLifecycleBoundaryTest`                  |    16     |    0     |   0    |    0    |        5.66s        |
| `PostgresPolicyLifecycleStoreTest`                     |    12     |    0     |   0    |    0    |        3.16s        |
| `PostgresRecoveryInspectionTest`                       |    15     |    0     |   0    |    0    |       10.95s        |
| `PostgresRecoveryQuarantineTest`                       |    24     |    0     |   0    |    0    |       16.58s        |
| **Subtotal `:persistence-postgres` (Real PostgreSQL)** |  **187**  |  **0**   | **0**  |  **0**  |     **~1m 35s**     |

#### Approval HTTP Adapter PostgreSQL-Backed Suites (`:adapters-http`)

Command: `scripts/run-java-approval-api-tests.sh` (filtered subset connecting to PostgreSQL container)

| Test Suite Class                                | Tests Run | Failures | Errors | Skipped | Test Execution Time | Focus Area                                                  |
| :---------------------------------------------- | :-------: | :------: | :----: | :-----: | :-----------------: | :---------------------------------------------------------- |
| `SimulatorApprovalBootstrapTest`                |     8     |    0     |   0    |    0    |       24.56s        | Startup/shutdown lifecycle & PostgreSQL schema verification |
| `SimulatorApprovalBrowserTest`                  |    26     |    0     |   0    |    0    |       20.52s        | Browser HTTP endpoints & approval persistence against PG    |
| `SimulatorApprovalHistoryTest`                  |     3     |    0     |   0    |    0    |        6.75s        | Approval history queries & pagination against PG            |
| `SimulatorApprovalRedactionBoundaryTest`        |     6     |    0     |   0    |    0    |        6.78s        | Decision response redactions against PG approval state      |
| `SimulatorApprovalServerTest`                   |    31     |    0     |   0    |    0    |       16.23s        | Full HTTP approval server endpoints backed by PG            |
| **Subtotal `:adapters-http` (Real PostgreSQL)** |  **74**   |  **0**   | **0**  |  **0**  |     **~1m 15s**     |                                                             |

**Total Real PostgreSQL Integration Tests**: **261 distinct test cases** (187 persistence + 74 approval adapter), **0 failures**, **0 errors**, **0 skipped**.

---

### Pure In-Memory Unit, Configuration and Protocol Tests

The following suites executed during the qualification run do **not** attach to a PostgreSQL database; they validate in-memory logic, configuration constraints, or synthetic target behavior:

| Test Suite Class                        | Module           | Tests Run | Failures | Errors | Skipped | Scope / Description                                                |
| :-------------------------------------- | :--------------- | :-------: | :------: | :----: | :-----: | :----------------------------------------------------------------- |
| `SimulatorApprovalConfigurationTest`    | `:adapters-http` |    45     |    0     |   0    |    0    | Unit tests for port, host loopback, JWKS URI, and scope validation |
| `SimulatorApprovalMcpAdapterTest`       | `:adapters-http` |    24     |    0     |   0    |    0    | Unit tests for MCP JSON-RPC protocol translation and error codes   |
| `StatefulTargetSimulatorTest`           | `:simulator`     |     6     |    0     |   0    |    0    | In-memory synthetic stateful target simulator                      |
| `SyntheticExfilTargetSimulatorTest`     | `:simulator`     |     1     |    0     |   0    |    0    | In-memory synthetic exfiltration target simulator                  |
| `SyntheticPromptInjectionSimulatorTest` | `:simulator`     |     1     |    0     |   0    |    0    | In-memory synthetic prompt injection simulator                     |
| **Subtotal In-Memory Tests**            |                  |  **77**   |  **0**   | **0**  |  **0**  | Pure in-memory unit/protocol/simulator tests                       |

_(Notice: In-memory tests and PostgreSQL integration tests are tracked separately; they are not conflated into a single database test count.)_

---

### Repository Consistency and Linter Checks

Executed via local tooling and automated CI:

- `pnpm check`: Passed (4 console Vitest test files / 41 tests passed; portable Java 21 environment confirmed; `gradlew check` passed across all Java subprojects).
- `pnpm format:check`: Passed (Prettier formatting clean).
- `pnpm docs:links`: Passed (501 Markdown links verified; all local targets exist).
- `pnpm docs:flows:check`: Passed (8 diagram tests passed; documentation guides synchronized).
- `git diff --check`: Passed (no trailing whitespace or conflict markers).

---

## 4. Architectural and Operational Boundaries

1. **Simulator-Only Operational Scope**:
   - TracePermit's automated test suite validates behavior against disposable PostgreSQL 16 Testcontainers (`postgres:16.4-alpine`) and local development Docker instances.
   - This qualification does not test or certify connections to managed cloud databases (e.g., AWS RDS, Amazon Aurora PostgreSQL), multi-region replication topologies, or external connection poolers (e.g. PgBouncer, AWS RDS Proxy).

2. **Network and Transport Boundaries**:
   - In TracePermit's simulator approval launcher ([`SimulatorApprovalConfiguration.java`](../../java/adapters-http/src/main/java/io/tracepermit/adapters/http/SimulatorApprovalConfiguration.java)), the PostgreSQL host is strictly validated to `"127.0.0.1"`, `sslMode` is set to `"disable"`, and no SSL certificate paths (`sslrootcert`, `sslcert`, `sslkey`) are accepted.
   - TLS hostname validation (`sslmode=verify-full`), client certificate authentication (mTLS), and SCRAM-SHA-256-PLUS channel binding are **NOT QUALIFIED** in this repository.

3. **DataSource Library Boundary**:
   - TracePermit's core persistence module ([`PostgresDurableEnforcementState.java`](../../java/persistence-postgres/src/main/java/io/tracepermit/persistence/postgres/PostgresDurableEnforcementState.java) and [`PostgresDatabaseLifecycle.java`](../../java/persistence-postgres/src/main/java/io/tracepermit/persistence/postgres/PostgresDatabaseLifecycle.java)) accepts an arbitrary `javax.sql.DataSource` supplied by the caller.
   - While the built-in simulator launcher enforces loopback and disabled SSL, an external caller embedding TracePermit as a library may configure arbitrary JDBC parameters, remote hosts, or TLS settings. Such external configurations are outside the qualified simulator profile.

4. **Statement and Lock Deadlines**:
   - In [`PostgresDatabaseLifecycle.java`](../../java/persistence-postgres/src/main/java/io/tracepermit/persistence/postgres/PostgresDatabaseLifecycle.java), the advisory lock query explicitly sets a query timeout via `query.setQueryTimeout(5)`. Flyway migrations configure `statement_timeout = '10s'; lock_timeout = '5s'`.
   - General persistence statements in `PostgresDurableEnforcementState` do not apply a hardcoded 5-second deadline; their timeout behavior is governed by the underlying DataSource, socket timeouts (`socketTimeout=5`), or server-side GUC settings.

5. **Future Production Requirements**:
   - Transitioning TracePermit from simulator-only operation to managed cloud PostgreSQL infrastructure will require a separate qualification phase covering:
     - Strict TLS negotiation (`sslmode=verify-full`) with managed CA bundles.
     - Enterprise SCRAM-SHA-256-PLUS mutual channel binding.
     - Connection pool sizing, keep-alives (`tcpKeepAlive=true`), and pooler failover handling (e.g. HikariCP with `targetServerType=primary`).
     - Upstream resolution or explicit mitigation for certificate parsing advisory [GHSA-hcjv-f5jh-r55v](https://github.com/pgjdbc/pgjdbc/security/advisories/GHSA-hcjv-f5jh-r55v) if certificate paths are exposed.

---

## 5. Recommendation and Bounded Readiness Statement

In the tested simulator composition—comprising disposable PostgreSQL 16.4 Testcontainers and the fixed-loopback approval launcher—no failures, errors, or behavioral regressions were observed across 261 real PostgreSQL integration tests. The upgrade from `42.7.5` to `42.7.14` incorporates critical upstream security fixes (including parameter padding data leak mitigation CVE-2026-107315 and SCRAM iteration DoS mitigation CVE-2026-42198).

However, unpatched upstream advisory [GHSA-hcjv-f5jh-r55v](https://github.com/pgjdbc/pgjdbc/security/advisories/GHSA-hcjv-f5jh-r55v) affects pgJDBC `<= 42.7.14` for attacker-influenced TLS certificate paths; while mitigated in TracePermit's simulator launcher by loopback SSL-disabled settings, remote TLS deployments remain **UNQUALIFIED**.

**Verdict**: **QUALIFIED FOR MERGE WITHIN SIMULATOR COMPOSITION SCOPE**.
Dependabot PR #9 is verified within these documented boundaries. Final merge authorization remains with the repository founder, and central traceability matrix integration is maintained separately by Codex.
