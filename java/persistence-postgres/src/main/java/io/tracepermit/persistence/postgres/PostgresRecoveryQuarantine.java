package io.tracepermit.persistence.postgres;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;

/** Explicit one-way containment, not automatic restore detection or cancellation of admitted work. */
public final class PostgresRecoveryQuarantine {
  public record Status(String mode, UUID recoveryId, Instant quarantinedAt) {}

  private PostgresRecoveryQuarantine() {}

  /** Metadata-only inspection; a quarantined database remains inspectable without migration. */
  public static Status status(DataSource source) {
    PostgresDatabaseLifecycle.validateExisting(source);
    try (var connection = source.getConnection()) {
      return read(connection, "");
    } catch (SQLException failure) {
      throw new PostgresStateException("Recovery status unavailable", failure);
    }
  }

  /** Must run with the table owner's authority. Repetition returns the original committed record. */
  public static Status quarantine(DataSource source, Instant now) {
    Objects.requireNonNull(now);
    PostgresDatabaseLifecycle.validateExisting(source);
    try (var connection = source.getConnection()) {
      boolean originalAutoCommit = connection.getAutoCommit();
      connection.setAutoCommit(false);
      try {
        try (var query = connection.createStatement()) {
          query.setQueryTimeout(5);
          try (var rows = query.executeQuery("""
              SELECT pg_catalog.pg_has_role(current_user, relowner, 'USAGE')
              FROM pg_catalog.pg_class WHERE oid = 'public.tp_recovery_quarantine'::regclass
              """)) {
            if (!rows.next() || !rows.getBoolean(1)) {
              throw new IllegalStateException("RECOVERY_OPERATOR_REQUIRED");
            }
          }
        }
        Status current = read(connection, " FOR UPDATE");
        if ("ready".equals(current.mode())) {
          UUID id = UUID.randomUUID();
          try (var update = connection.prepareStatement("""
              UPDATE public.tp_recovery_quarantine SET mode = 'quarantined', recovery_id = ?,
                quarantined_at = ? WHERE singleton = TRUE
              """)) {
            update.setQueryTimeout(5);
            update.setObject(1, id);
            update.setObject(2, OffsetDateTime.ofInstant(now, ZoneOffset.UTC));
            if (update.executeUpdate() != 1) throw new IllegalStateException("RECOVERY_STATE_UNAVAILABLE");
          }
          current = read(connection, "");
        }
        connection.commit();
        connection.setAutoCommit(originalAutoCommit);
        return current;
      } catch (RuntimeException | SQLException failure) {
        try { connection.rollback(); }
        catch (SQLException rollback) { failure.addSuppressed(rollback); }
        // Never reset auto-commit on an unsuccessful rollback or report a commit as confirmed.
        throw failure;
      }
    } catch (SQLException failure) {
      throw new PostgresStateException("Recovery quarantine unconfirmed", failure);
    }
  }

  /** First lock in every cooperating authority transaction; held until its commit/rollback. */
  static void requireReady(Connection connection) throws SQLException {
    if (connection.getAutoCommit()) throw new IllegalStateException("RECOVERY_TRANSACTION_REQUIRED");
    if (!"ready".equals(read(connection, " FOR SHARE").mode())) {
      throw new IllegalStateException("DATABASE_RECOVERY_QUARANTINED");
    }
  }

  static Status read(Connection connection, String lock) throws SQLException {
    // Legacy library fixtures can use a dedicated schema; operator startup pins public.
    String schema = connection.getSchema();
    if (schema == null || schema.isBlank()) throw new IllegalStateException("RECOVERY_STATE_UNAVAILABLE");
    try (var query = connection.createStatement()) {
      query.setQueryTimeout(5);
      try (var rows = query.executeQuery("SELECT mode, recovery_id, quarantined_at FROM \""
          + schema.replace("\"", "\"\"") + "\".tp_recovery_quarantine WHERE singleton = TRUE" + lock)) {
        if (!rows.next()) throw new IllegalStateException("RECOVERY_STATE_UNAVAILABLE");
        String mode = rows.getString(1);
        UUID id = rows.getObject(2, UUID.class);
        OffsetDateTime time = rows.getObject(3, OffsetDateTime.class);
        boolean valid = "ready".equals(mode) ? id == null && time == null
            : "quarantined".equals(mode) && id != null && time != null;
        if (!valid || rows.next()) throw new IllegalStateException("RECOVERY_STATE_UNAVAILABLE");
        return new Status(mode, id, time == null ? null : time.toInstant());
      }
    }
  }
}
