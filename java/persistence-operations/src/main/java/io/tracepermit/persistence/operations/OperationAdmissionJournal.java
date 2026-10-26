package io.tracepermit.persistence.operations;

import static io.tracepermit.application.BusinessOperationStorageException.Code.OPERATION_STORAGE_CORRUPT;
import static io.tracepermit.persistence.operations.JdbcBusinessOperationRepository.prepare;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.tracepermit.application.BusinessOperationScope;
import io.tracepermit.application.BusinessOperationStorageException;
import io.tracepermit.application.BusinessOperationTransitionPlanner;
import io.tracepermit.contracts.CanonicalJson;
import io.tracepermit.contracts.JsonSupport;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

/** Closed immutable admission metadata. Neither a retained signature nor dispatch permission. */
final class OperationAdmissionJournal {
  static final String FORMAT = "tracepermit-operation-admission-journal-v1";
  static final String REASON = "OPERATION_EXECUTION_CLAIMED";
  record Admission(ObjectNode json, String hash) {
    Admission { json = json.deepCopy(); }
    @Override public ObjectNode json() { return json.deepCopy(); }
    @Override public String toString() { return "StoredOperationAdmission[redacted; not dispatch]"; }
  }
  private OperationAdmissionJournal() {}

  static Admission create(JsonNode claims, String fingerprint, ObjectNode identity) {
    ObjectNode value = JsonSupport.MAPPER.createObjectNode().put("format", "tracepermit-operation-admission-v1")
        .put("operationId", claims.path("operationId").asText()).put("scopeHash", claims.path("scopeHash").asText())
        .put("permitFingerprint", fingerprint);
    value.set("claims", claims.deepCopy()); value.set("identity", identity.deepCopy());
    return checked(value, CanonicalJson.sha256(value));
  }

  static Optional<Admission> read(Connection connection, String id) throws SQLException {
    if (!OperationDatabase.hasAdmissions(connection)) return Optional.empty();
    OperationDatabase.requireAdmissionVersion(connection);
    try (var statement = prepare(connection, "SELECT permit_id, attempt_id, admission_hash, admission_json FROM tp_operation_admissions WHERE operation_id = ?")) {
      statement.setString(1, id);
      try (var rows = statement.executeQuery()) {
        if (!rows.next()) return Optional.empty();
        var result = checked(StoredOperationJson.read(rows.getString(4), 16384), rows.getString(3));
        if (!id.equals(result.json().path("operationId").asText())
            || !rows.getString(1).equals(result.json().path("claims").path("permitId").asText())
            || !rows.getString(2).equals(result.json().path("claims").path("attemptId").asText()) || rows.next()) throw corrupt();
        return Optional.of(result);
      }
    }
  }

  private static Admission checked(JsonNode value, String hash) {
    try {
      fields(value, Set.of("format", "operationId", "scopeHash", "permitFingerprint", "claims", "identity"));
      JsonNode claims = OperationPermitClaims.historical(value.path("claims"));
      if (!"tracepermit-operation-admission-v1".equals(value.path("format").asText())
          || !hash.equals(CanonicalJson.sha256(value))
          || !value.path("operationId").equals(claims.path("operationId"))
          || !value.path("scopeHash").equals(claims.path("scopeHash"))
          || !value.path("permitFingerprint").isTextual()
          || !value.path("permitFingerprint").asText().matches("sha256:[a-f0-9]{64}")) throw corrupt();
      return new Admission((ObjectNode) value, hash);
    } catch (RuntimeException rejected) { throw corrupt(); }
  }

  static void insert(Connection connection, Admission admission) throws SQLException {
    JsonNode claims = admission.json().path("claims");
    try (var statement = prepare(connection, """
        INSERT INTO tp_operation_admissions (operation_id, permit_id, attempt_id, admission_hash, admission_json)
        VALUES (?, ?, ?, ?, ?)
        """)) {
      statement.setString(1, admission.json().path("operationId").asText());
      statement.setString(2, claims.path("permitId").asText()); statement.setString(3, claims.path("attemptId").asText());
      statement.setString(4, admission.hash()); statement.setString(5, CanonicalJson.canonicalize(admission.json()));
      if (statement.executeUpdate() != 1) throw corrupt();
    }
  }

  static ObjectNode actor(Admission admission) {
    return JsonSupport.MAPPER.createObjectNode().put("journalFormat", FORMAT).put("kind", "admission")
        .put("admissionHash", admission.hash());
  }

  static ObjectNode outbox(Admission admission, JsonNode before, JsonNode result, JsonNode actor) {
    JsonNode fact = result.path("transitions").get(result.path("transitions").size() - 1);
    return JsonSupport.MAPPER.createObjectNode().put("format", "tracepermit-operation-admission-outbox-v1")
        .put("operationId", admission.json().path("operationId").asText())
        .put("scopeHash", admission.json().path("scopeHash").asText()).put("admissionHash", admission.hash())
        .put("attemptId", admission.json().path("claims").path("attemptId").asText())
        .put("sequence", fact.path("sequence").asLong()).put("factHash", CanonicalJson.sha256(fact))
        .put("actorHash", CanonicalJson.sha256(actor)).put("previousSnapshotHash", CanonicalJson.sha256(before))
        .put("resultSnapshotHash", CanonicalJson.sha256(result)).put("at", fact.path("at").asText())
        .put("event", "execution_claimed");
  }

  static void insertOutbox(Connection connection, ObjectNode event) throws SQLException {
    try (var statement = prepare(connection,
        "INSERT INTO tp_operation_admission_outbox (operation_id, outbox_hash, outbox_json) VALUES (?, ?, ?)")) {
      statement.setString(1, event.path("operationId").asText()); statement.setString(2, CanonicalJson.sha256(event));
      statement.setString(3, CanonicalJson.canonicalize(event));
      if (statement.executeUpdate() != 1) throw corrupt();
    }
  }

  static JsonNode replay(Connection connection, BusinessOperationScope scope, JsonNode before,
      JsonNode fact, JsonNode actor, Admission admission, OperationReviewJournal.Binding review) throws SQLException {
    try {
      fields(actor, Set.of("journalFormat", "kind", "admissionHash"));
      if (admission == null || review == null || !FORMAT.equals(actor.path("journalFormat").asText())
          || !"admission".equals(actor.path("kind").asText()) || !admission.hash().equals(actor.path("admissionHash").asText())
          || !"execution".equals(fact.path("dimension").asText()) || !"claimed".equals(fact.path("to").asText())
          || !REASON.equals(fact.path("reasonCode").asText())) throw corrupt();
      JsonNode data = admission.json(); JsonNode claims = data.path("claims");
      var planner = new BusinessOperationTransitionPlanner();
      var state = planner.observe(scope, before);
      JsonNode signedState = claims.path("state");
      if (!scope.fingerprint().equals(data.path("scopeHash").asText())
          || !state.operationId().equals(data.path("operationId").asText())
          || !scope.requesterPrincipalId().equals(claims.path("ownerPrincipalId").asText())
          || !state.snapshotHash().equals(signedState.path("snapshotHash").asText())
          || state.recoveryGeneration() != signedState.path("recoveryGeneration").asLong()
          || state.lastSequence() != 5 || state.authorizationVersion() != 3 || state.executionVersion() != 1 || state.evidenceVersion() != 1
          || !"approved".equals(before.path("authorization").path("status").asText())
          || !"not_started".equals(before.path("execution").path("status").asText())
          || !review.hash().equals(claims.path("reviewBindingHash").asText())
          || !review.json().path("profileHash").equals(claims.path("profileHash"))
          || !review.json().path("targetProfileHash").equals(claims.path("targetProfileHash"))
          || !review.json().path("approvalDeadline").equals(claims.path("identityBounds").path("approvalDeadline"))) throw corrupt();
      for (String field : Set.of("canonicalActionHash", "semanticPayloadDigest", "resourceBindingHash")) {
        if (!before.path(field).equals(claims.path(field))) throw corrupt();
      }
      for (String field : Set.of("policyRelease", "artifactDigests")) {
        if (!CanonicalJson.sha256(before.path(field)).equals(claims.path(field + "Hash").asText())) throw corrupt();
      }
      if (!CanonicalJson.sha256(before.path("resourceBinding").path("precondition")).equals(claims.path("preconditionBindingHash").asText())) throw corrupt();
      requireReviewAnchors(connection, state.operationId(), claims);
      Instant at = OperationReviewJournal.time(fact.path("at"));
      if (at.isBefore(OperationReviewJournal.time(claims.path("issuedAt")))
          || !OperationReviewJournal.time(claims.path("expiresAt")).isAfter(at)) throw corrupt();
      identity(data.path("identity"), scope, at);
      JsonNode result = planner.propose(scope, before, state, BusinessOperationTransitionPlanner.Dimension.EXECUTION,
          "claimed", REASON, at).snapshot();
      ObjectNode expected = outbox(admission, before, result, actor);
      try (var statement = prepare(connection, "SELECT outbox_hash, outbox_json FROM tp_operation_admission_outbox WHERE operation_id = ?")) {
        statement.setString(1, state.operationId());
        try (var rows = statement.executeQuery()) {
          if (!rows.next() || !CanonicalJson.sha256(expected).equals(rows.getString(1))
              || !CanonicalJson.canonicalize(expected).equals(CanonicalJson.canonicalize(StoredOperationJson.read(rows.getString(2), 4096)))
              || rows.next()) throw corrupt();
        }
      }
      return result;
    } catch (RuntimeException rejected) { throw corrupt(); }
  }

  private static void requireReviewAnchors(Connection connection, String id, JsonNode claims) throws SQLException {
    try (var statement = prepare(connection,
        "SELECT sequence_number, fact_json, actor_json FROM tp_operation_lifecycle_facts WHERE operation_id = ? AND sequence_number IN (4, 5) ORDER BY sequence_number")) {
      statement.setString(1, id);
      try (var rows = statement.executeQuery()) {
        for (int sequence : new int[] {4, 5}) {
          if (!rows.next() || rows.getInt(1) != sequence) throw corrupt();
          String prefix = sequence == 4 ? "evaluation" : "approval";
          JsonNode actor = StoredOperationJson.read(rows.getString(3), 4096);
          if (!CanonicalJson.sha256(StoredOperationJson.read(rows.getString(2), 4096)).equals(claims.path(prefix + "FactHash").asText())
              || !CanonicalJson.sha256(actor).equals(claims.path(prefix + "ActorHash").asText())) throw corrupt();
          JsonNode bounds = claims.path("identityBounds");
          if (!actor.path("identity").path("authorityExpiresAt").equals(bounds.path(sequence == 4 ? "evaluatorExpiresAt" : "reviewerExpiresAt"))
              || (sequence == 5 && (!"APPROVE".equals(actor.path("decision").asText())
                  || !actor.path("membershipExpiresAt").equals(bounds.path("membershipExpiresAt"))))) throw corrupt();
        }
        if (rows.next()) throw corrupt();
      }
    }
  }

  private static void identity(JsonNode identity, BusinessOperationScope scope, Instant at) {
    fields(identity, Set.of("scopeHash", "principalId", "authenticationMethod", "credentialFingerprint", "authorityExpiresAt"));
    if (!scope.fingerprint().equals(identity.path("scopeHash").asText())
        || !scope.requesterPrincipalId().equals(identity.path("principalId").asText())
        || !identity.path("authenticationMethod").isTextual()
        || !identity.path("authenticationMethod").asText().matches("[A-Za-z0-9][A-Za-z0-9._:@/+\\-]{0,127}")
        || !identity.path("credentialFingerprint").isTextual()
        || !identity.path("credentialFingerprint").asText().matches("sha256:[a-f0-9]{64}")
        || !OperationReviewJournal.time(identity.path("authorityExpiresAt")).isAfter(at)) throw corrupt();
  }

  static void requireNoOutbox(Connection connection, String id) throws SQLException {
    if (!OperationDatabase.hasAdmissions(connection)) return;
    OperationDatabase.requireAdmissionVersion(connection);
    try (var statement = prepare(connection, "SELECT operation_id FROM tp_operation_admission_outbox WHERE operation_id = ?")) {
      statement.setString(1, id); try (var rows = statement.executeQuery()) { if (rows.next()) throw corrupt(); }
    }
  }
  private static void fields(JsonNode value, Set<String> expected) {
    Set<String> fields = new HashSet<>(); value.fieldNames().forEachRemaining(fields::add);
    if (!value.isObject() || !fields.equals(expected)) throw corrupt();
  }
  private static BusinessOperationStorageException corrupt() { return new BusinessOperationStorageException(OPERATION_STORAGE_CORRUPT); }
}
