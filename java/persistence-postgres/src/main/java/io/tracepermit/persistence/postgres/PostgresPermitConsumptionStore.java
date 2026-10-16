package io.tracepermit.persistence.postgres;

import io.tracepermit.execution.TenantPartition;
import io.tracepermit.security.PermitConsumptionStore;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * PostgreSQL-backed durable execution permit consumption store (SEC-006, REL-004, FR-008).
 *
 * <p>Enforces atomic single-use permit consumption across processes, restarts, and concurrent
 * dispatch attempts by persisting claims in the {@code tp_consumed_execution_permits} table
 * with conflict rejection. Live claims require a verified canonical action hash.
 * Each write owns and commits a fresh/exclusive DataSource lease before success;
 * caller-owned/thread-bound transactions are unsupported. This is not the
 * composed atomic execution/evidence claim used by durable application services.
 */
public final class PostgresPermitConsumptionStore implements PermitConsumptionStore {
  public static final TenantPartition DEFAULT_TENANT = new TenantPartition("default");

  private static final Pattern PERMIT_PATTERN =
      Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._:@/+\\-]{0,255}$");
  private static final Pattern HASH_PATTERN =
      Pattern.compile("^sha256:[a-f0-9]{64}$");

  private final DataSource dataSource;
  private final TenantPartition defaultTenant;

  public PostgresPermitConsumptionStore(DataSource dataSource) {
    this(dataSource, DEFAULT_TENANT);
  }

  public PostgresPermitConsumptionStore(DataSource dataSource, TenantPartition defaultTenant) {
    this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    this.defaultTenant = Objects.requireNonNull(defaultTenant, "defaultTenant");
    PostgresDurableEnforcementState.migrate(
        dataSource, PostgresDurableEnforcementState.MIGRATION_LOCATION);
  }

  public PostgresPermitConsumptionStore(DataSource dataSource, String defaultTenantId) {
    this(dataSource, new TenantPartition(defaultTenantId));
  }

  @Override
  public boolean consume(String permitId, Instant expiresAt, Instant now) {
    return consume(defaultTenant, permitId, expiresAt, null, now);
  }

  @Override
  public boolean consume(String permitId, Instant expiresAt, String actionHash, Instant now) {
    return consume(defaultTenant, permitId, expiresAt, actionHash, now);
  }

  @Override
  public boolean consume(
      String tenantId, String permitId, Instant expiresAt, String actionHash, Instant now) {
    return consume(new TenantPartition(tenantId), permitId, expiresAt, actionHash, now);
  }

  public boolean consume(TenantPartition tenant, String permitId, Instant expiresAt, Instant now) {
    return consume(tenant, permitId, expiresAt, null, now);
  }

  public boolean consume(
      TenantPartition tenant,
      String permitId,
      Instant expiresAt,
      String actionHash,
      Instant now) {
    Objects.requireNonNull(tenant, "tenant");
    Objects.requireNonNull(permitId, "permitId");
    Objects.requireNonNull(expiresAt, "expiresAt");
    Objects.requireNonNull(now, "now");
    requirePermitId(permitId);

    if (!expiresAt.isAfter(now)) {
      return false;
    }

    requireHash(actionHash, "action hash");

    String sql = """
        INSERT INTO tp_consumed_execution_permits
          (tenant_id, permit_id, expires_at, action_hash, claimed_at)
        VALUES (?, ?, ?, ?, ?)
        ON CONFLICT (tenant_id, permit_id) DO NOTHING
        """;
    return inTransaction("Failed to record consumed execution permit", connection -> {
      try (PreparedStatement statement = connection.prepareStatement(sql)) {
        statement.setString(1, tenant.value());
        statement.setString(2, permitId);
        statement.setObject(3, OffsetDateTime.ofInstant(expiresAt, ZoneOffset.UTC));
        statement.setString(4, actionHash);
        statement.setObject(5, OffsetDateTime.ofInstant(now, ZoneOffset.UTC));
        return statement.executeUpdate() == 1;
      }
    });
  }

  public boolean isConsumed(String permitId) {
    return isConsumed(defaultTenant, permitId);
  }

  public boolean isConsumed(String tenantId, String permitId) {
    return isConsumed(new TenantPartition(tenantId), permitId);
  }

  public boolean isConsumed(TenantPartition tenant, String permitId) {
    Objects.requireNonNull(tenant, "tenant");
    Objects.requireNonNull(permitId, "permitId");
    requirePermitId(permitId);

    String sql = """
        SELECT 1
          FROM tp_consumed_execution_permits
         WHERE tenant_id = ? AND permit_id = ?
        """;
    try (Connection connection = dataSource.getConnection();
         PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, tenant.value());
      statement.setString(2, permitId);
      try (ResultSet results = statement.executeQuery()) {
        return results.next();
      }
    } catch (SQLException exception) {
      throw new PostgresStateException("Failed to query consumed execution permit", exception);
    }
  }

  public int pruneExpiredUnreferenced(Instant now) {
    return pruneExpiredUnreferenced(defaultTenant, now);
  }

  public int pruneExpiredUnreferenced(String tenantId, Instant now) {
    return pruneExpiredUnreferenced(new TenantPartition(tenantId), now);
  }

  public int pruneExpiredUnreferenced(TenantPartition tenant, Instant now) {
    Objects.requireNonNull(tenant, "tenant");
    Objects.requireNonNull(now, "now");

    String sql = """
        DELETE FROM tp_consumed_execution_permits p
         WHERE p.tenant_id = ?
           AND p.expires_at <= ?
           AND NOT EXISTS (
             SELECT 1 FROM tp_execution_attempts a
              WHERE a.tenant_id = p.tenant_id AND a.permit_id = p.permit_id
           )
           AND NOT EXISTS (
             SELECT 1 FROM tp_approval_requests r
              WHERE r.tenant_id = p.tenant_id AND r.consumed_permit_id = p.permit_id
           )
        """;
    return inTransaction("Failed to prune expired unreferenced permits", connection -> {
      try (PreparedStatement statement = connection.prepareStatement(sql)) {
        statement.setString(1, tenant.value());
        statement.setObject(2, OffsetDateTime.ofInstant(now, ZoneOffset.UTC));
        return statement.executeUpdate();
      }
    });
  }

  public TenantPartition defaultTenant() {
    return defaultTenant;
  }

  private static void requirePermitId(String value) {
    if (value.isEmpty() || value.length() > 256 || !PERMIT_PATTERN.matcher(value).matches()) {
      throw new IllegalArgumentException("The permit ID is invalid.");
    }
  }

  private static void requireHash(String value, String label) {
    if (value == null || !HASH_PATTERN.matcher(value).matches()) {
      throw new IllegalArgumentException("The " + label + " is invalid.");
    }
  }

  /** Owns a fresh/exclusive DataSource lease; never joins a caller's transaction. */
  private <T> T inTransaction(String message, SqlWork<T> work) {
    try (Connection connection = dataSource.getConnection()) {
      boolean originalAutoCommit = connection.getAutoCommit();
      connection.setAutoCommit(false);
      try {
        T result = work.run(connection);
        connection.commit();
        connection.setAutoCommit(originalAutoCommit);
        return result;
      } catch (RuntimeException | SQLException failure) {
        try {
          connection.rollback();
        } catch (SQLException rollbackFailure) {
          failure.addSuppressed(rollbackFailure);
        }
        // Never enable auto-commit after an unsuccessful rollback: that can commit pending work.
        throw failure;
      }
    } catch (SQLException exception) {
      throw new PostgresStateException(message, exception);
    }
  }

  @FunctionalInterface
  private interface SqlWork<T> {
    T run(Connection connection) throws SQLException;
  }
}
