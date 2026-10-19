package io.tracepermit.persistence.postgres;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import javax.sql.DataSource;

/** Owner-only, installation-wide counts. A snapshot is neither a health verdict nor recovery authority. */
public final class PostgresRecoveryInspection {
  public record Executions(long executing, long succeeded, long failed, long unknown) {}
  public record Approvals(long pending, long approved, long denied, long expired, long consumed, long cancelled) {}
  public record Snapshot(Instant transactionStartedAt, PostgresRecoveryQuarantine.Status recovery,
      Executions executions, Approvals approvals, long reconciliationQueued) {}

  private PostgresRecoveryInspection() {}

  /** No migrations, row locks, target lookup, mutation, identifiers or per-request payloads. */
  public static Snapshot inspect(DataSource source) {
    PostgresDatabaseLifecycle.validateExisting(source);
    try (var connection = source.getConnection()) {
      connection.setReadOnly(true);
      connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
      connection.setAutoCommit(false);
      try {
        if (!"public".equals(connection.getSchema())) throw new IllegalStateException("DATABASE_SCHEMA_NOT_READY");
        try (var query = connection.createStatement()) {
          query.setQueryTimeout(5);
          query.execute("SET LOCAL statement_timeout = '5s'; SET LOCAL lock_timeout = '1s'");
          // Full-installation counts must not become a runtime tenant-scoped API or an RLS-filtered view.
          try (var rows = query.executeQuery("""
              SELECT count(*) = 4 AND bool_and(
                pg_catalog.pg_has_role(current_user, c.relowner, 'USAGE') AND NOT c.relrowsecurity)
              FROM pg_catalog.pg_class c JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
              WHERE n.nspname = 'public' AND c.relkind = 'r' AND c.relname IN (
                'tp_recovery_quarantine', 'tp_execution_attempts', 'tp_approval_requests', 'tp_reconciliation_queue')
              """)) {
            if (!rows.next() || !rows.getBoolean(1)) throw new IllegalStateException("RECOVERY_INSPECTION_OPERATOR_REQUIRED");
          }
          // All reads share the snapshot established by the initial schema/ownership queries.
          var recovery = PostgresRecoveryQuarantine.read(connection, "");
          try (var rows = query.executeQuery("""
              SELECT transaction_timestamp(), e.*, a.*, r.queued
              FROM (SELECT
                count(*) FILTER (WHERE status = 'executing') AS executing,
                count(*) FILTER (WHERE status = 'succeeded') AS succeeded,
                count(*) FILTER (WHERE status = 'failed') AS failed,
                count(*) FILTER (WHERE status = 'unknown') AS unknown
                FROM public.tp_execution_attempts) e
              CROSS JOIN (SELECT
                count(*) FILTER (WHERE status = 'pending') AS pending,
                count(*) FILTER (WHERE status = 'approved') AS approved,
                count(*) FILTER (WHERE status = 'denied') AS denied,
                count(*) FILTER (WHERE status = 'expired') AS expired,
                count(*) FILTER (WHERE status = 'consumed') AS consumed,
                count(*) FILTER (WHERE status = 'cancelled') AS cancelled
                FROM public.tp_approval_requests) a
              CROSS JOIN (SELECT count(*) AS queued FROM public.tp_reconciliation_queue) r
              """)) {
            if (!rows.next()) throw new IllegalStateException("RECOVERY_INSPECTION_UNAVAILABLE");
            var snapshot = new Snapshot(rows.getObject(1, OffsetDateTime.class).toInstant(), recovery,
                new Executions(rows.getLong(2), rows.getLong(3), rows.getLong(4), rows.getLong(5)),
                new Approvals(rows.getLong(6), rows.getLong(7), rows.getLong(8), rows.getLong(9), rows.getLong(10), rows.getLong(11)),
                rows.getLong(12));
            if (rows.next()) throw new IllegalStateException("RECOVERY_INSPECTION_UNAVAILABLE");
            // End the read-only snapshot before returning anything; an error never yields a partial report.
            connection.rollback();
            return snapshot;
          }
        }
      } catch (RuntimeException | SQLException failure) {
        try { connection.rollback(); }
        catch (SQLException rollback) { failure.addSuppressed(rollback); }
        throw failure;
      }
    } catch (SQLException failure) {
      throw new PostgresStateException("Recovery inspection unavailable", failure);
    }
  }
}
