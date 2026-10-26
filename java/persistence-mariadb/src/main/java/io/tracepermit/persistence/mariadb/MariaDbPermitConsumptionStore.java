package io.tracepermit.persistence.mariadb;

import io.tracepermit.execution.TenantPartition;
import io.tracepermit.security.PermitConsumptionStore;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.regex.Pattern;
import javax.sql.DataSource;

/** MariaDB-backed standalone, tenant-bound, single-use execution permit consumption. */
public final class MariaDbPermitConsumptionStore implements PermitConsumptionStore {
  public static final TenantPartition DEFAULT_TENANT = new TenantPartition("default");

  private static final int DUPLICATE_KEY = 1062;
  private static final Pattern PERMIT_PATTERN =
      Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._:@/+\\-]{0,255}$");
  private static final Pattern HASH_PATTERN = Pattern.compile("^sha256:[a-f0-9]{64}$");

  private final DataSource dataSource;
  private final TenantPartition defaultTenant;

  /** Validate-only runtime path; database creation and migration are always explicit. */
  public MariaDbPermitConsumptionStore(DataSource dataSource) {
    this(dataSource, DEFAULT_TENANT);
  }

  /** Validate-only runtime path; database creation and migration are always explicit. */
  public MariaDbPermitConsumptionStore(DataSource dataSource, TenantPartition defaultTenant) {
    this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    this.defaultTenant = Objects.requireNonNull(defaultTenant, "defaultTenant");
    MariaDbDatabaseLifecycle.validateExisting(dataSource);
  }

  public MariaDbPermitConsumptionStore(DataSource dataSource, String defaultTenantId) {
    this(dataSource, new TenantPartition(defaultTenantId));
  }

  public static MariaDbPermitConsumptionStore openExisting(DataSource dataSource) {
    return new MariaDbPermitConsumptionStore(dataSource);
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
    requireHash(actionHash);

    String sql = """
        INSERT INTO tp_consumed_execution_permits
          (tenant_id, permit_id, expires_at, action_hash, claimed_at)
        VALUES (?, ?, ?, ?, ?)
        """;
    try {
      return inTransaction("Failed to record consumed execution permit", connection -> {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
          statement.setString(1, tenant.value());
          statement.setString(2, permitId);
          statement.setObject(3, LocalDateTime.ofInstant(expiresAt, ZoneOffset.UTC));
          statement.setString(4, actionHash);
          statement.setObject(5, LocalDateTime.ofInstant(now, ZoneOffset.UTC));
          return statement.executeUpdate() == 1;
        }
      });
    } catch (MariaDbStateException failure) {
      if (isDuplicateKey(failure)) {
        return false;
      }
      throw failure;
    }
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
    } catch (SQLException failure) {
      throw new MariaDbStateException("Failed to query consumed execution permit", failure);
    }
  }

  public TenantPartition defaultTenant() {
    return defaultTenant;
  }

  private static void requirePermitId(String value) {
    if (value.isEmpty() || value.length() > 256 || !PERMIT_PATTERN.matcher(value).matches()) {
      throw new IllegalArgumentException("The permit ID is invalid.");
    }
  }

  private static void requireHash(String value) {
    if (value == null || !HASH_PATTERN.matcher(value).matches()) {
      throw new IllegalArgumentException("The action hash is invalid.");
    }
  }

  private static boolean isDuplicateKey(Throwable failure) {
    for (Throwable current = failure; current != null; current = current.getCause()) {
      if (current instanceof SQLException sqlFailure) {
        for (SQLException candidate = sqlFailure; candidate != null; candidate = candidate.getNextException()) {
          if (candidate.getErrorCode() == DUPLICATE_KEY) {
            return true;
          }
        }
      }
    }
    return false;
  }

  /** Owns a fresh/exclusive DataSource lease and never joins a caller transaction. */
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
        throw failure;
      }
    } catch (SQLException failure) {
      throw new MariaDbStateException(message, failure);
    }
  }

  @FunctionalInterface
  private interface SqlWork<T> {
    T run(Connection connection) throws SQLException;
  }
}
