package io.tracepermit.persistence.operations;

import static io.tracepermit.application.BusinessOperationStorageException.Code.*;
import static io.tracepermit.persistence.operations.JdbcBusinessOperationRepository.prepare;

import io.tracepermit.application.BusinessOperationAuthorityProfile;
import io.tracepermit.application.BusinessOperationStorageException;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * First lock in a FUTURE composed authority transaction, not a claim or an approval. Uses the
 * caller's already-open writer transaction; never opens/commits/rolls back that transaction. The
 * owner must then lock operation/approval/permit/journal rows, sample fresh writer time AFTER ALL
 * waits, and repeat every independent authority/deadline check. No returned value authorizes dispatch.
 */
public final class OperationAuthorityControl {
  private OperationAuthorityControl() {}

  public static void requireCurrent(Connection connection, BusinessOperationAuthorityProfile expected) {
    requireCurrent(connection, expected, false);
  }

  /** V7 claim contention refuses immediately; success retains the SAME caller-owned control lock. */
  static void requireCurrentForDeliveryClaim(Connection connection, BusinessOperationAuthorityProfile expected) {
    requireCurrent(connection, expected, true);
  }

  private static void requireCurrent(Connection connection, BusinessOperationAuthorityProfile expected, boolean noWait) {
    try {
      if (connection == null || expected == null || connection.getAutoCommit() || connection.isReadOnly()
          || connection.getTransactionIsolation() != Connection.TRANSACTION_READ_COMMITTED) {
        throw error(OPERATION_COMMAND_INVALID);
      }
      OperationDatabase.inspectWriter(connection);
      try (var statement = prepare(connection, """
          SELECT profile_hash, recovery_generation, control_state
            FROM tp_operation_authority_controls WHERE scope_hash = ? FOR UPDATE
          """ + (noWait ? " NOWAIT" : ""))) {
        statement.setString(1, expected.scope().fingerprint());
        try (var rows = statement.executeQuery()) {
          if (!rows.next()) throw error(OPERATION_AUTHORITY_NOT_READY);
          String hash = rows.getString(1);
          long generation = rows.getLong(2);
          String state = rows.getString(3);
          if (!expected.fingerprint().equals(hash) || expected.recoveryGeneration() != generation) {
            throw error(OPERATION_PROFILE_MISMATCH);
          }
          if (!"ready".equals(state) || rows.next()) throw error(OPERATION_AUTHORITY_NOT_READY);
        }
      }
    } catch (BusinessOperationStorageException failure) {
      throw error(failure.code());
    } catch (SQLException failure) {
      if (noWait && ("55P03".equals(failure.getSQLState())
          || ("HY000".equals(failure.getSQLState()) && failure.getErrorCode() == 1205))) {
        throw error(OPERATION_DELIVERY_LEASE_BUSY);
      }
      throw error(OPERATION_STORAGE_UNAVAILABLE);
    } catch (RuntimeException failure) {
      throw error(OPERATION_STORAGE_UNAVAILABLE);
    }
  }

  private static BusinessOperationStorageException error(BusinessOperationStorageException.Code code) {
    return new BusinessOperationStorageException(code);
  }
}
