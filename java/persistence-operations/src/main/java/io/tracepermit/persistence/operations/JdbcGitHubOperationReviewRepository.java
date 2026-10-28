package io.tracepermit.persistence.operations;

import static io.tracepermit.application.BusinessOperationStorageException.Code.*;
import static io.tracepermit.persistence.operations.JdbcBusinessOperationRepository.*;

import com.fasterxml.jackson.databind.JsonNode;
import io.tracepermit.adapters.remote.github.BoundGitHubOperation;
import io.tracepermit.adapters.remote.github.GitHubBusinessOperationBinding;
import io.tracepermit.adapters.remote.github.GitHubOperationProfile;
import io.tracepermit.application.BusinessOperationAuthorityProfile;
import io.tracepermit.application.BusinessOperationEvaluation;
import io.tracepermit.application.BusinessOperationReview;
import io.tracepermit.application.BusinessOperationScope;
import io.tracepermit.application.BusinessOperationStorageException;
import io.tracepermit.application.BusinessOperationTransitionPlanner;
import io.tracepermit.application.BusinessOperationWithdrawal;
import io.tracepermit.application.TrustedOperationGroupMembership;
import io.tracepermit.contracts.CanonicalJson;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Set;
import javax.sql.DataSource;

/**
 * Opt-in V4 closed evaluation/review/pending-withdrawal owner. Each call owns ONE writer connection,
 * locks current control FIRST, then the operation head, and samples writer time after all waits.
 * Trusted operator profiles are constructor inputs, never derived from a stored/request snapshot.
 * No generic proposal, clock callback, signing, network, permit, admission or target port exists.
 * An approved snapshot returned here is NOT execution authority.
 */
public final class JdbcGitHubOperationReviewRepository {
  private static final BusinessOperationTransitionPlanner PLANNER = new BusinessOperationTransitionPlanner();
  private final DataSource source;
  private final BusinessOperationAuthorityProfile profile;
  private final GitHubOperationProfile target;

  /** Original Track E descriptor retained for compiled and source compatibility. */
  public JdbcGitHubOperationReviewRepository(
      DataSource source,
      BusinessOperationAuthorityProfile profile,
      GitHubBusinessOperationBinding.ExpectedProfile target) {
    this(source, profile, (GitHubOperationProfile) target);
  }

  public JdbcGitHubOperationReviewRepository(DataSource source, BusinessOperationAuthorityProfile profile,
      GitHubOperationProfile target) {
    if (source == null || profile == null || target == null) throw error(OPERATION_COMMAND_INVALID);
    profile.reviewSettings();
    this.source = source;
    this.profile = profile;
    this.target = target;
    OperationDatabase.validateReviewExisting(source);
  }

  public JsonNode evaluate(BusinessOperationEvaluation command) {
    if (command == null) throw error(OPERATION_COMMAND_INVALID);
    return change(command.scope(), command.operationId(), command.expected(), command, null, null, null);
  }

  public JsonNode review(BusinessOperationReview command, TrustedOperationGroupMembership membership) {
    if (command == null || membership == null) throw error(OPERATION_COMMAND_INVALID);
    return change(command.scope(), command.operationId(), command.expected(), null, command, membership, null);
  }

  public JsonNode withdraw(BusinessOperationWithdrawal command) {
    if (command == null) throw error(OPERATION_COMMAND_INVALID);
    return change(command.scope(), command.operationId(), command.expected(), null, null, null, command);
  }

  private JsonNode change(BusinessOperationScope scope, String id,
      BusinessOperationTransitionPlanner.StateToken expected, BusinessOperationEvaluation evaluation,
      BusinessOperationReview review, TrustedOperationGroupMembership membership,
      BusinessOperationWithdrawal withdrawal) {
    if (!profile.scope().equals(scope)) throw error(OPERATION_PROFILE_MISMATCH);
    JsonNode result;
    boolean commitAttempted = false;
    try (Connection connection = source.getConnection()) {
      var engine = OperationDatabase.inspectWriter(connection);
      connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
      connection.setAutoCommit(false);
      try {
        OperationAuthorityControl.requireCurrent(connection, profile);
        OperationDatabase.requireReviewVersion(connection);
        if (!OperationDatabase.hasReviewBindings(connection)) throw error(OPERATION_SCHEMA_NOT_READY);
        JsonNode initial = read(connection, scope, "operation_id", id)
            .orElseThrow(() -> error(OPERATION_NOT_FOUND));
        JsonNode before = JdbcBusinessOperationLifecycleRepository.current(connection, scope, id, initial, true);
        var binding = OperationReviewJournal.read(connection, id);
        // No authority is captured at entry or before a blocking control/head lock.
        Instant now = JdbcBusinessOperationLifecycleRepository.storeTime(connection, engine);
        if (!PLANNER.observe(scope, before).equals(expected)) throw error(OPERATION_STATE_STALE);
        // current() has validated the frozen operation-v1 timestamp grammar. Equivalent
        // fractional spellings are valid stored bytes, unlike our new journal's own encoding.
        if (now.isBefore(Instant.parse(before.path("updatedAt").asText()))) throw error(OPERATION_CLOCK_REGRESSION);
        var actualTarget = bind(scope, before);
        var policy = profile.evaluate(before);
        if (binding.isPresent()) {
          // A strict binding digest contains the snapshot hash. Validate its historical capture at
          // the immutable evaluating parent, NOT by comparing it to the later pending digest.
          var captured = binding.orElseThrow();
          var originalPolicy = profile.evaluate(initial);
          var reconstructed = OperationReviewJournal.create(scope, initial, profile, bind(scope, initial),
              originalPolicy.outcome(), originalPolicy.reasonCode(),
              OperationReviewJournal.time(captured.json().path("evaluatedAt")));
          if (!captured.hash().equals(reconstructed.hash())) throw error(OPERATION_PROFILE_MISMATCH);
        }
        String next;
        String reason;
        JsonNode actor;
        if (evaluation != null) {
          evaluation.requireAuthorizedAt(profile, now);
          if (!"evaluating".equals(before.path("authorization").path("status").asText())
              || before.path("transitions").size() != 3 || binding.isPresent()
              || !"not_started".equals(before.path("execution").path("status").asText())) {
            throw error(OPERATION_NOT_REVIEWABLE);
          }
          var captured = OperationReviewJournal.create(scope, before, profile, actualTarget,
              policy.outcome(), policy.reasonCode(), now);
          OperationReviewJournal.insert(connection, captured);
          actor = OperationReviewJournal.evaluationActor(captured, evaluation);
          next = "block".equals(policy.outcome()) ? "denied" : "awaiting_approval";
          reason = policy.reasonCode();
        } else if (review != null) {
          review.requireGroupAuthorizedAt(profile, membership, now);
          if (!"awaiting_approval".equals(before.path("authorization").path("status").asText())
              || before.path("authorization").path("version").asLong() != 2
              || !"not_started".equals(before.path("execution").path("status").asText())
              || binding.isEmpty() || !"require_approval".equals(policy.outcome())) {
            throw error(OPERATION_NOT_REVIEWABLE);
          }
          var captured = binding.orElseThrow();
          if (!OperationReviewJournal.time(captured.json().path("approvalDeadline")).isAfter(now)) {
            throw error(OPERATION_APPROVAL_EXPIRED);
          }
          next = review.decision() == BusinessOperationReview.Decision.APPROVE ? "approved" : "denied";
          reason = review.decision() == BusinessOperationReview.Decision.APPROVE
              ? OperationReviewJournal.APPROVED : OperationReviewJournal.DENIED;
          actor = OperationReviewJournal.reviewActor(captured, review, membership);
        } else {
          withdrawal.requireAuthorizedAt(now);
          if (!Set.of("evaluating", "awaiting_approval").contains(before.path("authorization").path("status").asText())
              || !"not_started".equals(before.path("execution").path("status").asText())) {
            throw error(OPERATION_NOT_WITHDRAWABLE);
          }
          next = "cancelled";
          reason = BusinessOperationWithdrawal.REASON;
          actor = withdrawal.actor();
        }
        result = PLANNER.propose(scope, before, expected,
            BusinessOperationTransitionPlanner.Dimension.AUTHORIZATION, next, reason, now).snapshot();
        append(connection, id, before, result, actor);
        commitAttempted = true;
        connection.commit();
      } catch (SQLException | RuntimeException failure) {
        try {
          connection.rollback();
        } catch (SQLException | RuntimeException cleanup) {
          throw error(commitAttempted ? OPERATION_COMMIT_UNKNOWN : OPERATION_STORAGE_UNAVAILABLE);
        }
        if (commitAttempted) throw error(OPERATION_COMMIT_UNKNOWN);
        if (failure instanceof BusinessOperationStorageException fixed) throw error(fixed.code());
        throw error(OPERATION_STORAGE_UNAVAILABLE);
      }
    } catch (BusinessOperationStorageException fixed) {
      throw error(fixed.code());
    } catch (SQLException | RuntimeException failure) {
      throw error(commitAttempted ? OPERATION_COMMIT_UNKNOWN : OPERATION_STORAGE_UNAVAILABLE);
    }
    // A successful commit followed by uncertain cleanup is never acknowledged as success.
    return result.deepCopy();
  }

  private BoundGitHubOperation bind(BusinessOperationScope scope, JsonNode actual) {
    try {
      return target.bind(scope, actual);
    } catch (RuntimeException rejected) {
      throw error(OPERATION_PROFILE_MISMATCH);
    }
  }

  private static void append(Connection connection, String id, JsonNode before, JsonNode result,
      JsonNode actor) throws SQLException {
    JsonNode fact = result.path("transitions").get(result.path("transitions").size() - 1);
    String hash = CanonicalJson.sha256(result);
    try (var statement = prepare(connection, """
        INSERT INTO tp_operation_lifecycle_facts
          (operation_id, sequence_number, previous_snapshot_hash, result_snapshot_hash,
           fact_json, actor_hash, actor_json) VALUES (?, ?, ?, ?, ?, ?, ?)
        """)) {
      statement.setString(1, id);
      statement.setInt(2, fact.path("sequence").asInt());
      statement.setString(3, CanonicalJson.sha256(before));
      statement.setString(4, hash);
      statement.setString(5, CanonicalJson.canonicalize(fact));
      statement.setString(6, CanonicalJson.sha256(actor));
      statement.setString(7, CanonicalJson.canonicalize(actor));
      if (statement.executeUpdate() != 1) throw error(OPERATION_STORAGE_CORRUPT);
    }
    try (var statement = prepare(connection, """
        UPDATE tp_operation_lifecycle_heads SET snapshot_hash = ?, snapshot_json = ?
         WHERE operation_id = ? AND snapshot_hash = ?
        """)) {
      statement.setString(1, hash);
      statement.setString(2, CanonicalJson.canonicalize(result));
      statement.setString(3, id);
      statement.setString(4, CanonicalJson.sha256(before));
      if (statement.executeUpdate() != 1) throw error(OPERATION_STATE_STALE);
    }
  }

  private static BusinessOperationStorageException error(BusinessOperationStorageException.Code code) {
    return new BusinessOperationStorageException(code);
  }
}
