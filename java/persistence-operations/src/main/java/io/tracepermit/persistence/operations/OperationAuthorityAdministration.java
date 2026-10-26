package io.tracepermit.persistence.operations;

import static io.tracepermit.application.BusinessOperationStorageException.Code.*;
import static io.tracepermit.persistence.operations.JdbcBusinessOperationRepository.prepare;

import io.tracepermit.application.BusinessOperationAuthorityProfile;
import io.tracepermit.application.BusinessOperationStorageException;
import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;

/**
 * Explicit operator-only control changes on a separately privileged connection, never a runtime
 * route or request body. Runtime users get SELECT plus UPDATE(lock_marker) for PostgreSQL row-lock
 * privilege, never mutation of control authority columns, INSERT/DELETE/DDL. The marker is CHECK(0).
 * This does not verify operator identity, prevent an older-backup restore or fence a paused sender.
 */
public final class OperationAuthorityAdministration {
  private OperationAuthorityAdministration() {}

  public static void stageNew(DataSource administrator, BusinessOperationAuthorityProfile profile) {
    change(administrator, profile, null, "stage");
  }

  public static void activateStaged(DataSource administrator, BusinessOperationAuthorityProfile profile) {
    change(administrator, profile, null, "activate");
  }

  public static void quarantine(DataSource administrator, BusinessOperationAuthorityProfile profile) {
    change(administrator, profile, null, "quarantine");
  }

  /** Any replacement, including a policy-only change, requires a strictly newer recovery generation. */
  public static void stageReplacement(DataSource administrator,
      BusinessOperationAuthorityProfile previous, BusinessOperationAuthorityProfile next) {
    if (previous == null || next == null || !previous.scope().equals(next.scope())
        || next.recoveryGeneration() <= previous.recoveryGeneration()) {
      throw error(OPERATION_COMMAND_INVALID);
    }
    change(administrator, previous, next, "replace");
  }

  private static void change(DataSource source, BusinessOperationAuthorityProfile expected,
      BusinessOperationAuthorityProfile next, String kind) {
    if (source == null || expected == null) throw error(OPERATION_COMMAND_INVALID);
    OperationDatabase.validateAuthorityExisting(source);
    boolean commitAttempted = false;
    try (Connection connection = source.getConnection()) {
      OperationDatabase.inspectWriter(connection);
      connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
      connection.setAutoCommit(false);
      try {
        String sql = switch (kind) {
          case "stage" -> """
              INSERT INTO tp_operation_authority_controls
                (scope_hash, profile_hash, recovery_generation, control_state) VALUES (?, ?, ?, 'staged')
              """;
          case "activate" -> """
              UPDATE tp_operation_authority_controls SET control_state = 'ready'
                WHERE scope_hash = ? AND profile_hash = ? AND recovery_generation = ? AND control_state = 'staged'
              """;
          case "quarantine" -> """
              UPDATE tp_operation_authority_controls SET control_state = 'quarantined'
                WHERE scope_hash = ? AND profile_hash = ? AND recovery_generation = ? AND control_state = 'ready'
              """;
          case "replace" -> """
              UPDATE tp_operation_authority_controls
                SET profile_hash = ?, recovery_generation = ?, control_state = 'staged'
                WHERE scope_hash = ? AND profile_hash = ? AND recovery_generation = ?
              """;
          default -> throw error(OPERATION_COMMAND_INVALID);
        };
        try (var statement = prepare(connection, sql)) {
          int offset = 0;
          if (next != null) {
            statement.setString(1, next.fingerprint());
            statement.setLong(2, next.recoveryGeneration());
            offset = 2;
          }
          statement.setString(offset + 1, expected.scope().fingerprint());
          statement.setString(offset + 2, expected.fingerprint());
          statement.setLong(offset + 3, expected.recoveryGeneration());
          if (statement.executeUpdate() != 1) throw error(OPERATION_STATE_STALE);
        }
        commitAttempted = true;
        connection.commit();
      } catch (SQLException | RuntimeException failure) {
        try {
          connection.rollback();
        } catch (SQLException | RuntimeException rollbackFailure) {
          throw error(commitAttempted ? OPERATION_COMMIT_UNKNOWN : OPERATION_STORAGE_UNAVAILABLE);
        }
        throw failure;
      }
    } catch (SQLException | RuntimeException failure) {
      if (commitAttempted) throw error(OPERATION_COMMIT_UNKNOWN);
      if (failure instanceof BusinessOperationStorageException rejected) throw error(rejected.code());
      throw error(OPERATION_STORAGE_UNAVAILABLE);
    }
    // Never report activation success before both COMMIT and connection cleanup acknowledge it.
  }

  private static BusinessOperationStorageException error(BusinessOperationStorageException.Code code) {
    return new BusinessOperationStorageException(code);
  }
}
