package io.tracepermit.persistence.operations;

import static io.tracepermit.application.BusinessOperationStorageException.Code.OPERATION_COMMAND_INVALID;
import static io.tracepermit.application.BusinessOperationStorageException.Code.OPERATION_NOT_FOUND;
import static io.tracepermit.application.BusinessOperationStorageException.Code.OPERATION_STORAGE_UNAVAILABLE;
import static io.tracepermit.persistence.operations.JdbcBusinessOperationRepository.read;

import com.fasterxml.jackson.databind.JsonNode;
import io.tracepermit.application.BusinessOperationAuthorityProfile;
import io.tracepermit.application.BusinessOperationScope;
import io.tracepermit.application.BusinessOperationStorageException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;
import javax.sql.DataSource;

/** Read-only current-authority inspection for the closed installed lifecycle command. */
public final class JdbcGitHubOperationInspector {
  private final DataSource source;
  private final BusinessOperationAuthorityProfile profile;

  public JdbcGitHubOperationInspector(
      DataSource source, BusinessOperationAuthorityProfile profile) {
    if (source == null || profile == null) throw error(OPERATION_COMMAND_INVALID);
    this.source = source;
    this.profile = profile;
    OperationDatabase.validateDeliveryExisting(source);
  }

  public Inspection inspect(BusinessOperationScope scope, String operationId) {
    if (!profile.scope().equals(scope)) throw error(OPERATION_COMMAND_INVALID);
    String id = id(operationId);
    try (Connection connection = source.getConnection()) {
      OperationDatabase.inspectWriter(connection);
      connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
      connection.setAutoCommit(false);
      try {
        OperationAuthorityControl.requireCurrent(connection, profile);
        OperationDatabase.requireDeliveryVersion(connection);
        JsonNode initial =
            read(connection, scope, "operation_id", id)
                .orElseThrow(() -> error(OPERATION_NOT_FOUND));
        JsonNode current =
            JdbcBusinessOperationLifecycleRepository.current(
                connection, scope, id, initial, false);
        boolean started =
            OperationDeliveryJournal.read(connection, id).stream()
                .anyMatch(event -> event.kind().equals("dispatch_started"));
        connection.rollback();
        return new Inspection(current, started);
      } catch (SQLException | RuntimeException failure) {
        try {
          connection.rollback();
        } catch (SQLException | RuntimeException ignored) {
          throw error(OPERATION_STORAGE_UNAVAILABLE);
        }
        if (failure instanceof BusinessOperationStorageException fixed) {
          throw error(fixed.code());
        }
        throw error(OPERATION_STORAGE_UNAVAILABLE);
      }
    } catch (BusinessOperationStorageException fixed) {
      throw error(fixed.code());
    } catch (SQLException | RuntimeException failure) {
      throw error(OPERATION_STORAGE_UNAVAILABLE);
    }
  }

  public record Inspection(JsonNode operation, boolean dispatchStarted) {
    public Inspection {
      operation = operation.deepCopy();
    }

    @Override
    public JsonNode operation() {
      return operation.deepCopy();
    }
  }

  private static String id(String value) {
    try {
      String normalized = UUID.fromString(value).toString();
      if (!normalized.equals(value)) throw new IllegalArgumentException();
      return normalized;
    } catch (RuntimeException invalid) {
      throw error(OPERATION_COMMAND_INVALID);
    }
  }

  private static BusinessOperationStorageException error(
      BusinessOperationStorageException.Code code) {
    return new BusinessOperationStorageException(code);
  }
}
