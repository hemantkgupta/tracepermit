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
    try {
      if (connection == null || expected == null || connection.getAutoCommit() || connection.isReadOnly()
          || connection.getTransactionIsolation() != Connection.TRANSACTION_READ_COMMITTED) {
        throw error(OPERATION_COMMAND_INVALID);
      }
      OperationDatabase.inspectWriter(connection);
      try (var statement = prepare(connection, """
          SELECT profile_hash, recovery_generation, control_state
            FROM tp_operation_authority_controls WHERE scope_hash = ? FOR UPDATE
          """)) {
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
    } catch (SQLException | RuntimeException failure) {
      throw error(OPERATION_STORAGE_UNAVAILABLE);
    }
  }

  private static BusinessOperationStorageException error(BusinessOperationStorageException.Code code) {
    return new BusinessOperationStorageException(code);
  }
}
