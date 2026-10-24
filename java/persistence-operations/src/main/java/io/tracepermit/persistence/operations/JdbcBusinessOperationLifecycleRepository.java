package io.tracepermit.persistence.operations;

import static io.tracepermit.application.BusinessOperationStorageException.Code.*;
import static io.tracepermit.persistence.operations.JdbcBusinessOperationRepository.*;

import com.fasterxml.jackson.databind.JsonNode;
import io.tracepermit.application.BusinessOperationLifecycleRepository;
import io.tracepermit.application.BusinessOperationScope;
import io.tracepermit.application.BusinessOperationStorageException;
import io.tracepermit.application.BusinessOperationTransitionPlanner;
import io.tracepermit.application.BusinessOperationWithdrawal;
import io.tracepermit.contracts.BusinessOperationValidator;
import io.tracepermit.contracts.CanonicalJson;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Calendar;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TimeZone;
import javax.sql.DataSource;

/**
 * Durable closed pending-withdrawal command. No generic proposal write, permit, policy, transport,
 * external callback, signing or target port exists here. The database is a trusted integrity boundary,
 * not a cryptographic attestation against an administrator who can rewrite rows and hashes.
 */
public final class JdbcBusinessOperationLifecycleRepository implements BusinessOperationLifecycleRepository {
  private final DataSource source;
  private final BusinessOperationTransitionPlanner planner = new BusinessOperationTransitionPlanner();

  public JdbcBusinessOperationLifecycleRepository(DataSource source) {
    this.source = Objects.requireNonNull(source);
    OperationDatabase.validateExisting(source);
  }

  @Override
  public Optional<JsonNode> findCurrent(BusinessOperationScope scope, String operationId) {
    Objects.requireNonNull(scope);
    String id = normalizeId(operationId);
    Optional<JsonNode> result;
    try (Connection connection = source.getConnection()) {
      OperationDatabase.inspectWriter(connection);
      // Immutable registration, head and journal are read from one coherent MVCC snapshot.
      connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
      connection.setAutoCommit(false);
      try {
        Optional<JsonNode> initial = read(connection, scope, "operation_id", id);
        result = initial.isEmpty() ? Optional.empty()
            : Optional.of(current(connection, scope, id, initial.orElseThrow(), false));
      } finally {
        connection.rollback();
      }
    } catch (BusinessOperationStorageException failure) {
      throw failure;
    } catch (SQLException failure) {
      throw error(OPERATION_STORAGE_UNAVAILABLE);
    }
    return result;
  }

  @Override
  public JsonNode withdraw(BusinessOperationWithdrawal command) {
    if (command == null) throw error(OPERATION_COMMAND_INVALID);
    JsonNode result;
    boolean commitAttempted = false;
    try (Connection connection = source.getConnection()) {
      OperationDatabase.Engine engine = OperationDatabase.inspectWriter(connection);
      connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
      connection.setAutoCommit(false);
      try {
        BusinessOperationScope scope = command.scope();
        String id = command.operationId();
        // Scope is checked before locking, against immutable registration, never from a body.
        JsonNode initial = read(connection, scope, "operation_id", id)
            .orElseThrow(() -> error(OPERATION_NOT_FOUND));
        JsonNode before = current(connection, scope, id, initial, true);
        Instant now = storeTime(connection, engine);
        command.requireAuthorizedAt(now);
        if (!planner.observe(scope, before).equals(command.expected())) throw error(OPERATION_STATE_STALE);
        requirePending(before);
        if (now.isBefore(Instant.parse(before.path("updatedAt").asText()))) {
          throw error(OPERATION_CLOCK_REGRESSION);
        }
        result = planner.propose(scope, before, command.expected(),
            BusinessOperationTransitionPlanner.Dimension.AUTHORIZATION, "cancelled",
            BusinessOperationWithdrawal.REASON, now).snapshot();
        JsonNode fact = result.path("transitions").get(result.path("transitions").size() - 1);
        String hash = CanonicalJson.sha256(result);
        JsonNode actor = command.actor();
        try (var statement = prepare(connection, """
            INSERT INTO tp_operation_lifecycle_facts
              (operation_id, sequence_number, previous_snapshot_hash, result_snapshot_hash,
               fact_json, actor_hash, actor_json) VALUES (?, ?, ?, ?, ?, ?, ?)
            """)) {
          statement.setString(1, id);
          statement.setInt(2, fact.path("sequence").asInt());
          statement.setString(3, command.expected().snapshotHash());
          statement.setString(4, hash);
          statement.setString(5, CanonicalJson.canonicalize(fact));
          statement.setString(6, CanonicalJson.sha256(actor));
          statement.setString(7, CanonicalJson.canonicalize(actor));
          statement.executeUpdate();
        }
        try (var statement = prepare(connection, """
            UPDATE tp_operation_lifecycle_heads SET snapshot_hash = ?, snapshot_json = ?
             WHERE operation_id = ? AND snapshot_hash = ?
            """)) {
          statement.setString(1, hash);
          statement.setString(2, CanonicalJson.canonicalize(result));
          statement.setString(3, id);
          statement.setString(4, command.expected().snapshotHash());
          if (statement.executeUpdate() != 1) throw error(OPERATION_STATE_STALE);
        }
        commitAttempted = true;
        connection.commit();
      } catch (SQLException | RuntimeException failure) {
        try {
          connection.rollback();
        } catch (SQLException rollbackFailure) {
          throw error(commitAttempted ? OPERATION_COMMIT_UNKNOWN : OPERATION_STORAGE_UNAVAILABLE);
        }
        throw failure;
      }
    } catch (BusinessOperationStorageException failure) {
      throw failure;
    } catch (SQLException failure) {
      throw error(commitAttempted ? OPERATION_COMMIT_UNKNOWN : OPERATION_STORAGE_UNAVAILABLE);
    }
    // Never report success until commit AND connection cleanup acknowledged.
    return result.deepCopy();
  }

  private JsonNode current(Connection connection, BusinessOperationScope scope,
      String id, JsonNode initial, boolean lock) throws SQLException {
    JsonNode head;
    String hash;
    try (var statement = prepare(connection, """
        SELECT snapshot_hash, snapshot_json FROM tp_operation_lifecycle_heads
         WHERE operation_id = ?
        """ + (lock ? " FOR UPDATE" : ""))) {
      statement.setString(1, id);
      try (ResultSet rows = statement.executeQuery()) {
        if (!rows.next()) throw error(OPERATION_STORAGE_CORRUPT);
        hash = rows.getString(1);
        head = StoredOperationJson.read(rows.getString(2), MAX_BYTES);
        if (rows.next()) throw error(OPERATION_STORAGE_CORRUPT);
      }
    }
    try {
      head = BusinessOperationValidator.validate(head);
      if (!hash.equals(CanonicalJson.sha256(head))) throw error(OPERATION_STORAGE_CORRUPT);
      JsonNode replay = initial;
      try (var statement = prepare(connection, """
          SELECT sequence_number, previous_snapshot_hash, result_snapshot_hash,
                 fact_json, actor_hash, actor_json FROM tp_operation_lifecycle_facts
           WHERE operation_id = ? ORDER BY sequence_number
          """)) {
        statement.setString(1, id);
        try (ResultSet rows = statement.executeQuery()) {
          while (rows.next()) {
            JsonNode fact = StoredOperationJson.read(rows.getString(4), MAX_BYTES);
            JsonNode actor = StoredOperationJson.read(rows.getString(6), 4096);
            if (rows.getInt(1) != replay.path("transitions").size() + 1
                || !CanonicalJson.sha256(replay).equals(rows.getString(2))
                || !CanonicalJson.sha256(actor).equals(rows.getString(5))
                || !"authorization".equals(fact.path("dimension").asText())
                || !"cancelled".equals(fact.path("to").asText())
                || !BusinessOperationWithdrawal.REASON.equals(fact.path("reasonCode").asText())) {
              throw error(OPERATION_STORAGE_CORRUPT);
            }
            requirePending(replay);
            Instant at = Instant.parse(fact.path("at").asText());
            requireActor(actor, scope, at);
            replay = planner.propose(scope, replay, planner.observe(scope, replay),
                BusinessOperationTransitionPlanner.Dimension.AUTHORIZATION, "cancelled",
                BusinessOperationWithdrawal.REASON, at).snapshot();
            if (!CanonicalJson.canonicalize(fact).equals(CanonicalJson.canonicalize(
                replay.path("transitions").get(replay.path("transitions").size() - 1)))
                || !CanonicalJson.sha256(replay).equals(rows.getString(3))) {
              throw error(OPERATION_STORAGE_CORRUPT);
            }
          }
        }
      }
      // JSON integer node width changes across JDBC serialization; compare the frozen canonical
      // representation, not Jackson IntNode-vs-LongNode object identity.
      if (!CanonicalJson.canonicalize(replay).equals(CanonicalJson.canonicalize(head))) {
        throw error(OPERATION_STORAGE_CORRUPT);
      }
      return head;
    } catch (RuntimeException failure) {
      throw error(OPERATION_STORAGE_CORRUPT);
    }
  }

  private static void requirePending(JsonNode current) {
    if (!Set.of("evaluating", "awaiting_approval").contains(current.path("authorization").path("status").asText())
        || !"not_started".equals(current.path("execution").path("status").asText())) {
      throw error(OPERATION_NOT_WITHDRAWABLE);
    }
  }

  private static void requireActor(JsonNode actor, BusinessOperationScope scope, Instant at) {
    Set<String> fields = new java.util.HashSet<>();
    actor.fieldNames().forEachRemaining(fields::add);
    if (!actor.isObject() || !fields.equals(Set.of("scopeHash", "principalId", "authenticationMethod",
        "credentialFingerprint", "authorityExpiresAt"))
        || !scope.fingerprint().equals(actor.path("scopeHash").asText())
        || !scope.requesterPrincipalId().equals(actor.path("principalId").asText())
        || !actor.path("authenticationMethod").isTextual()
        || !actor.path("authenticationMethod").asText().matches("[A-Za-z0-9][A-Za-z0-9._:@/+\\-]{0,127}")
        || !actor.path("credentialFingerprint").asText().matches("sha256:[a-f0-9]{64}")
        || !Instant.parse(actor.path("authorityExpiresAt").asText()).isAfter(at)) {
      throw error(OPERATION_STORAGE_CORRUPT);
    }
  }

  private static Instant storeTime(Connection connection, OperationDatabase.Engine engine) throws SQLException {
    try (var statement = prepare(connection, engine == OperationDatabase.Engine.POSTGRESQL
        ? "SELECT clock_timestamp()" : "SELECT UTC_TIMESTAMP(6)")) {
      try (ResultSet rows = statement.executeQuery()) {
        if (!rows.next()) throw error(OPERATION_STORAGE_UNAVAILABLE);
        var value = rows.getTimestamp(1, Calendar.getInstance(TimeZone.getTimeZone("UTC")));
        if (value == null || rows.next()) throw error(OPERATION_STORAGE_UNAVAILABLE);
        return value.toInstant();
      }
    }
  }

  private static BusinessOperationStorageException error(BusinessOperationStorageException.Code code) {
    return new BusinessOperationStorageException(code);
  }
}
