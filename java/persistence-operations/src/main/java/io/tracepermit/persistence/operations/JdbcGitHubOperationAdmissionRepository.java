package io.tracepermit.persistence.operations;

import static io.tracepermit.application.BusinessOperationStorageException.Code.*;
import static io.tracepermit.persistence.operations.JdbcBusinessOperationRepository.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.tracepermit.adapters.remote.github.GitHubBusinessOperationBinding;
import io.tracepermit.application.BusinessOperationAuthorityProfile;
import io.tracepermit.application.BusinessOperationExecution;
import io.tracepermit.application.BusinessOperationStorageException;
import io.tracepermit.application.BusinessOperationTransitionPlanner;
import io.tracepermit.contracts.CanonicalJson;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import javax.sql.DataSource;

/** Opt-in V6 atomic single-use admission only. No sender, target or dispatch capability exists. */
public final class JdbcGitHubOperationAdmissionRepository {
  private final DataSource source;
  private final BusinessOperationAuthorityProfile profile;
  private final GitHubBusinessOperationBinding.ExpectedProfile target;
  private final OperationPermitCodec codec;

  public JdbcGitHubOperationAdmissionRepository(DataSource source, BusinessOperationAuthorityProfile profile,
      GitHubBusinessOperationBinding.ExpectedProfile target) {
    if (source == null || profile == null || target == null) throw error(OPERATION_COMMAND_INVALID);
    this.source = source; this.profile = profile; this.target = target;
    this.codec = new OperationPermitCodec(profile.admissionSettings(), null);
    OperationDatabase.validateAdmissionExisting(source);
  }

  /** Sensitive serialized value is verified before lock acquisition, and never stored or returned. */
  public JsonNode admit(BusinessOperationExecution command, String serializedPermit) {
    if (command == null) throw error(OPERATION_COMMAND_INVALID);
    JsonNode claims = codec.verify(serializedPermit).claims();
    if (!profile.scope().equals(command.scope()) || !command.operationId().equals(claims.path("operationId").asText())) {
      throw error(OPERATION_PERMIT_INVALID);
    }
    var admission = OperationAdmissionJournal.create(claims, OperationPermitCodec.fingerprint(serializedPermit), command.actor());
    JsonNode result;
    boolean commitAttempted = false;
    try (Connection connection = source.getConnection()) {
      var engine = OperationDatabase.inspectWriter(connection);
      connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
      connection.setAutoCommit(false);
      try {
        OperationAuthorityControl.requireCurrent(connection, profile);
        OperationDatabase.requireAdmissionVersion(connection);
        JsonNode initial = read(connection, command.scope(), "operation_id", command.operationId())
            .orElseThrow(() -> error(OPERATION_NOT_FOUND));
        JsonNode approved = JdbcBusinessOperationLifecycleRepository.current(connection, command.scope(), command.operationId(), initial, true);
        requireCurrent(connection, initial, approved, command, claims,
            JdbcBusinessOperationLifecycleRepository.storeTime(connection, engine));
        // Permit/attempt UNIQUE and head FK can wait even after the explicit authority locks.
        // This row is provisional until every later recheck/write and acknowledged commit.
        OperationAdmissionJournal.insert(connection, admission);
        Instant at = JdbcBusinessOperationLifecycleRepository.storeTime(connection, engine);
        requireCurrent(connection, initial, approved, command, claims, at);
        var planner = new BusinessOperationTransitionPlanner();
        result = planner.propose(command.scope(), approved, command.expected(),
            BusinessOperationTransitionPlanner.Dimension.EXECUTION, "claimed", OperationAdmissionJournal.REASON, at).snapshot();
        JsonNode actor = OperationAdmissionJournal.actor(admission);
        append(connection, command.operationId(), approved, result, actor);
        OperationAdmissionJournal.insertOutbox(connection, OperationAdmissionJournal.outbox(admission, approved, result, actor));
        // Cover ALL implicit UNIQUE/FK waits, including facts/outbox, not merely the first INSERT.
        // These immutable writes are rolled back if either signed or fresh authority has expired.
        Instant finalTime = JdbcBusinessOperationLifecycleRepository.storeTime(connection, engine);
        if (finalTime.isBefore(at)) throw error(OPERATION_CLOCK_REGRESSION);
        requireCurrent(connection, initial, approved, command, claims, finalTime);
        commitAttempted = true;
        connection.commit();
      } catch (SQLException | RuntimeException failure) {
        try { connection.rollback(); }
        catch (SQLException | RuntimeException cleanup) {
          throw error(commitAttempted ? OPERATION_COMMIT_UNKNOWN : OPERATION_STORAGE_UNAVAILABLE);
        }
        if (commitAttempted) throw error(OPERATION_COMMIT_UNKNOWN);
        if (failure instanceof BusinessOperationStorageException fixed) throw error(fixed.code());
        // No generic SQL duplicate becomes replay success. The complete transaction rolls back.
        throw error(OPERATION_STORAGE_UNAVAILABLE);
      }
    } catch (BusinessOperationStorageException fixed) { throw error(fixed.code()); }
    catch (SQLException | RuntimeException failure) {
      throw error(commitAttempted ? OPERATION_COMMIT_UNKNOWN : OPERATION_STORAGE_UNAVAILABLE);
    }
    return result.deepCopy(); // History only, after commit AND cleanup acknowledgement; not dispatch.
  }

  private void requireCurrent(Connection connection, JsonNode initial, JsonNode approved,
      BusinessOperationExecution command, JsonNode signed, Instant now) throws SQLException {
    if (now.isBefore(OperationReviewJournal.time(signed.path("issuedAt")))) throw error(OPERATION_CLOCK_REGRESSION);
    if (!OperationReviewJournal.time(signed.path("expiresAt")).isAfter(now)) throw error(OPERATION_PERMIT_EXPIRED);
    JsonNode actual = OperationPermitClaims.capture(connection, initial, approved, profile, target, command, now);
    if (!CanonicalJson.canonicalize(stable(actual)).equals(CanonicalJson.canonicalize(stable(signed)))) {
      throw error(OPERATION_PERMIT_INVALID);
    }
  }

  private static ObjectNode stable(JsonNode claims) {
    ObjectNode result = claims.deepCopy();
    result.remove(List.of("permitId", "attemptId", "issuedAt", "expiresAt", "executeIdentity"));
    ((ObjectNode) result.path("identityBounds")).remove("ownerExpiresAt");
    return result;
  }

  private static void append(Connection connection, String id, JsonNode before, JsonNode result, JsonNode actor) throws SQLException {
    JsonNode fact = result.path("transitions").get(result.path("transitions").size() - 1);
    try (var statement = prepare(connection, """
        INSERT INTO tp_operation_lifecycle_facts
          (operation_id, sequence_number, previous_snapshot_hash, result_snapshot_hash, fact_json, actor_hash, actor_json)
          VALUES (?, ?, ?, ?, ?, ?, ?)
        """)) {
      statement.setString(1, id); statement.setInt(2, fact.path("sequence").asInt());
      statement.setString(3, CanonicalJson.sha256(before)); statement.setString(4, CanonicalJson.sha256(result));
      statement.setString(5, CanonicalJson.canonicalize(fact)); statement.setString(6, CanonicalJson.sha256(actor));
      statement.setString(7, CanonicalJson.canonicalize(actor));
      if (statement.executeUpdate() != 1) throw error(OPERATION_STORAGE_CORRUPT);
    }
    try (var statement = prepare(connection,
        "UPDATE tp_operation_lifecycle_heads SET snapshot_hash = ?, snapshot_json = ? WHERE operation_id = ? AND snapshot_hash = ?")) {
      statement.setString(1, CanonicalJson.sha256(result)); statement.setString(2, CanonicalJson.canonicalize(result));
      statement.setString(3, id); statement.setString(4, CanonicalJson.sha256(before));
      if (statement.executeUpdate() != 1) throw error(OPERATION_STATE_STALE);
    }
  }
  private static BusinessOperationStorageException error(BusinessOperationStorageException.Code code) { return new BusinessOperationStorageException(code); }
  @Override public String toString() { return "JdbcGitHubOperationAdmissionRepository[redacted; no target or dispatch]"; }
}
