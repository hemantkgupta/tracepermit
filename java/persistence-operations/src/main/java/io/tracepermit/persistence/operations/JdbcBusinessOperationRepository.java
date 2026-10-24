package io.tracepermit.persistence.operations;

import static io.tracepermit.application.BusinessOperationStorageException.Code.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.tracepermit.application.BusinessOperationRegistration;
import io.tracepermit.application.BusinessOperationRepository;
import io.tracepermit.application.BusinessOperationScope;
import io.tracepermit.application.BusinessOperationStorageException;
import io.tracepermit.contracts.BusinessOperationValidator;
import io.tracepermit.contracts.CanonicalJson;
import io.tracepermit.contracts.JsonSupport;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import javax.sql.DataSource;

/** Immutable registration store. No method issues authority, advances execution or invokes a target. */
public final class JdbcBusinessOperationRepository implements BusinessOperationRepository {
  static final int MAX_BYTES = 262_144;
  private final DataSource source;

  public JdbcBusinessOperationRepository(DataSource source) {
    this.source = Objects.requireNonNull(source);
    OperationDatabase.validateExisting(source);
  }

  @Override
  public BusinessOperationRegistration createOrGet(BusinessOperationScope scope, JsonNode input) {
    JsonNode operation = BusinessOperationValidator.validate(input);
    requireScope(scope, operation);
    requireInitial(operation);
    String snapshot = CanonicalJson.canonicalize(operation);
    if (snapshot.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) throw error(OPERATION_TOO_LARGE);
    String id = normalizeId(operation.path("operationId").asText());
    String key = operation.path("clientRequestKeyHash").asText();
    boolean commitAttempted = false;
    try (Connection connection = source.getConnection()) {
      OperationDatabase.Engine engine = OperationDatabase.inspectWriter(connection);
      connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
      connection.setAutoCommit(false);
      try {
        // A plain insert makes the DB unique constraint the sole concurrency arbiter.
        try (PreparedStatement statement = prepare(connection, """
            INSERT INTO tp_business_operations
              (operation_id, scope_hash, request_key_hash, binding_hash, snapshot_hash, snapshot_json)
            VALUES (?, ?, ?, ?, ?, ?)
            """)) {
          statement.setString(1, id);
          statement.setString(2, scope.fingerprint());
          statement.setString(3, key);
          statement.setString(4, bindingHash(operation));
          statement.setString(5, CanonicalJson.sha256(operation));
          statement.setString(6, snapshot);
          statement.executeUpdate();
        } catch (SQLException insertFailure) {
          // PostgreSQL aborts the transaction on a duplicate; read only after confirmed rollback.
          connection.rollback();
          if (!engine.duplicate(insertFailure)) throw insertFailure;
          Optional<JsonNode> existing = read(connection, scope, "request_key_hash", key);
          connection.rollback();
          if (existing.isEmpty()) throw error(OPERATION_ID_CONFLICT);
          if (!bindingHash(existing.orElseThrow()).equals(bindingHash(operation))) {
            throw error(OPERATION_REQUEST_KEY_CONFLICT);
          }
          return new BusinessOperationRegistration(false, existing.orElseThrow());
        }
        try (PreparedStatement statement = prepare(connection, """
            INSERT INTO tp_business_operation_facts (operation_id, sequence_number, fact_json)
            VALUES (?, ?, ?)
            """)) {
          for (JsonNode fact : operation.path("transitions")) {
            statement.setString(1, id);
            statement.setInt(2, fact.path("sequence").asInt());
            statement.setString(3, CanonicalJson.canonicalize(fact));
            statement.executeUpdate();
          }
        }
        try (PreparedStatement statement = prepare(connection, """
            INSERT INTO tp_operation_lifecycle_heads (operation_id, snapshot_hash, snapshot_json)
            VALUES (?, ?, ?)
            """)) {
          statement.setString(1, id);
          statement.setString(2, CanonicalJson.sha256(operation));
          statement.setString(3, snapshot);
          statement.executeUpdate();
        }
        commitAttempted = true;
        connection.commit();
      } catch (SQLException | RuntimeException failure) {
        try {
          connection.rollback();
        } catch (SQLException rollbackFailure) {
          // Never expose the JDBC exception or reinterpret failed rollback as a duplicate.
          throw error(commitAttempted ? OPERATION_COMMIT_UNKNOWN : OPERATION_STORAGE_UNAVAILABLE);
        }
        throw failure;
      }
    } catch (BusinessOperationStorageException failure) {
      throw failure;
    } catch (SQLException failure) {
      throw error(commitAttempted ? OPERATION_COMMIT_UNKNOWN : OPERATION_STORAGE_UNAVAILABLE);
    }
    // Return only after commit and connection cleanup, never from inside the write transaction.
    return new BusinessOperationRegistration(true, operation);
  }

  @Override
  public Optional<JsonNode> find(BusinessOperationScope scope, String operationId) {
    Objects.requireNonNull(scope);
    String id = normalizeId(operationId);
    try (Connection connection = source.getConnection()) {
      OperationDatabase.inspectWriter(connection);
      return read(connection, scope, "operation_id", id);
    } catch (BusinessOperationStorageException failure) {
      throw failure;
    } catch (SQLException failure) {
      throw error(OPERATION_STORAGE_UNAVAILABLE);
    }
  }

  static Optional<JsonNode> read(
      Connection connection, BusinessOperationScope scope, String field, String value)
      throws SQLException {
    // field is one of two private constant call sites, never an input or identifier from a request.
    try (PreparedStatement statement = prepare(connection, """
        SELECT operation_id, request_key_hash, binding_hash, snapshot_hash, snapshot_json
          FROM tp_business_operations WHERE scope_hash = ? AND %s = ?
        """.formatted(field))) {
      statement.setString(1, scope.fingerprint());
      statement.setString(2, value);
      try (ResultSet rows = statement.executeQuery()) {
        if (!rows.next()) return Optional.empty();
        String storedId = rows.getString(1);
        String key = rows.getString(2);
        String binding = rows.getString(3);
        String hash = rows.getString(4);
        String json = rows.getString(5);
        if (json == null || json.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
          throw error(OPERATION_STORAGE_CORRUPT);
        }
        JsonNode operation;
        try {
          operation = BusinessOperationValidator.validate(JsonSupport.MAPPER.readTree(json));
          requireScope(scope, operation);
          requireInitial(operation);
          if (!storedId.equals(normalizeId(operation.path("operationId").asText()))
              || !key.equals(operation.path("clientRequestKeyHash").asText())
              || !binding.equals(bindingHash(operation))
              || !hash.equals(CanonicalJson.sha256(operation)) || rows.next()) {
            throw error(OPERATION_STORAGE_CORRUPT);
          }
        } catch (java.io.IOException | RuntimeException failure) {
          throw error(OPERATION_STORAGE_CORRUPT);
        }
        requireFacts(connection, storedId, operation);
        return Optional.of(operation);
      }
    }
  }

  private static void requireFacts(Connection connection, String id, JsonNode operation)
      throws SQLException {
    try (PreparedStatement statement = prepare(connection, """
        SELECT sequence_number, fact_json FROM tp_business_operation_facts
         WHERE operation_id = ? ORDER BY sequence_number
        """)) {
      statement.setString(1, id);
      try (ResultSet rows = statement.executeQuery()) {
        for (JsonNode expected : operation.path("transitions")) {
          if (!rows.next() || rows.getInt(1) != expected.path("sequence").asInt()
              || !CanonicalJson.canonicalize(expected).equals(rows.getString(2))) {
            throw error(OPERATION_STORAGE_CORRUPT);
          }
        }
        if (rows.next()) throw error(OPERATION_STORAGE_CORRUPT);
      }
    }
  }

  private static String bindingHash(JsonNode operation) {
    ObjectNode binding = JsonSupport.MAPPER.createObjectNode();
    for (String field : new String[] {
        "adapterId", "semanticPayloadDigest", "resourceBindingHash", "recoveryGeneration"}) {
      binding.set(field, operation.get(field));
    }
    return CanonicalJson.sha256(binding);
  }

  private static void requireScope(BusinessOperationScope scope, JsonNode operation) {
    if (!Objects.requireNonNull(scope).trustedContext().equals(operation.path("trustedContext"))) {
      throw error(OPERATION_SCOPE_MISMATCH);
    }
  }

  private static void requireInitial(JsonNode operation) {
    if (operation.path("transitions").size() != 3
        || !"evaluating".equals(operation.path("authorization").path("status").asText())
        || !"not_started".equals(operation.path("execution").path("status").asText())
        || !"pending".equals(operation.path("evidence").path("status").asText())) {
      throw error(OPERATION_INITIAL_STATE_REQUIRED);
    }
  }

  static String normalizeId(String id) {
    try {
      String normalized = java.util.UUID.fromString(id).toString();
      if (!normalized.equals(id.toLowerCase(Locale.ROOT))) throw error(OPERATION_ID_INVALID);
      return normalized;
    } catch (IllegalArgumentException | NullPointerException failure) {
      throw error(OPERATION_ID_INVALID);
    }
  }

  static PreparedStatement prepare(Connection connection, String sql) throws SQLException {
    PreparedStatement statement = connection.prepareStatement(sql);
    statement.setQueryTimeout(5);
    return statement;
  }

  private static BusinessOperationStorageException error(BusinessOperationStorageException.Code code) {
    return new BusinessOperationStorageException(code);
  }
}
