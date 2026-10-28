package io.tracepermit.persistence.operations;

import static io.tracepermit.application.BusinessOperationStorageException.Code.OPERATION_STORAGE_CORRUPT;
import static io.tracepermit.persistence.operations.JdbcBusinessOperationRepository.prepare;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.tracepermit.adapters.remote.github.BoundGitHubOperation;
import io.tracepermit.application.BusinessOperationAuthorityProfile;
import io.tracepermit.application.BusinessOperationEvaluation;
import io.tracepermit.application.BusinessOperationReview;
import io.tracepermit.application.BusinessOperationScope;
import io.tracepermit.application.BusinessOperationStorageException;
import io.tracepermit.application.BusinessOperationTransitionPlanner;
import io.tracepermit.application.TrustedOperationGroupMembership;
import io.tracepermit.contracts.CanonicalJson;
import io.tracepermit.contracts.JsonSupport;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

/** Closed V4 metadata/replay vocabulary. Integrity data, not an approval or dispatch capability. */
final class OperationReviewJournal {
  static final String FORMAT = "tracepermit-operation-review-journal-v1";
  static final String APPROVED = "OPERATION_REVIEW_APPROVED";
  static final String DENIED = "OPERATION_REVIEW_DENIED";
  private static final Set<String> BINDING_FIELDS = Set.of("format", "operationId", "scopeHash",
      "evaluatedSnapshotHash", "authorizationVersion", "profileHash", "settingsHash", "groupHash",
      "membershipSourceFingerprint", "lifetimeSeconds", "lifetimeNanos", "evaluatedAt", "outcome",
      "reasonCode", "targetProfileHash", "bindingDigest");

  record Binding(ObjectNode json, String hash) {
    Binding { json = json.deepCopy(); }
    @Override public ObjectNode json() { return json.deepCopy(); }
    @Override public String toString() { return "OperationReviewBinding[redacted; not authority]"; }
  }

  private OperationReviewJournal() {}

  static String groupHash(String group) {
    return CanonicalJson.sha256(JsonSupport.MAPPER.createObjectNode()
        .put("format", "tracepermit-operation-review-group-v1").put("group", group));
  }

  static Binding create(BusinessOperationScope scope, JsonNode before,
      BusinessOperationAuthorityProfile profile, BoundGitHubOperation target,
      String outcome, String reason, Instant now) {
    var settings = profile.reviewSettings();
    ObjectNode json = JsonSupport.MAPPER.createObjectNode()
        .put("format", "tracepermit-operation-review-binding-v1")
        .put("operationId", before.path("operationId").asText()).put("scopeHash", scope.fingerprint())
        .put("evaluatedSnapshotHash", CanonicalJson.sha256(before))
        .put("authorizationVersion", before.path("authorization").path("version").asLong() + 1)
        .put("profileHash", profile.fingerprint()).put("settingsHash", CanonicalJson.sha256(settings.pins()))
        .put("groupHash", groupHash(settings.reviewerGroupId()))
        .put("membershipSourceFingerprint", settings.membershipSourceFingerprint())
        .put("lifetimeSeconds", settings.approvalLifetime().getSeconds())
        .put("lifetimeNanos", settings.approvalLifetime().getNano())
        .put("evaluatedAt", now.toString()).put("outcome", outcome).put("reasonCode", reason)
        .put("targetProfileHash", target.profileFingerprint()).put("bindingDigest", target.bindingDigest());
    if ("require_approval".equals(outcome)) json.put("approvalDeadline", settings.deadlineFromWriterTime(now).toString());
    return checked(json, CanonicalJson.sha256(json));
  }

  static void insert(Connection connection, Binding binding) throws SQLException {
    try (var statement = prepare(connection, """
        INSERT INTO tp_operation_review_bindings (operation_id, binding_hash, binding_json) VALUES (?, ?, ?)
        """)) {
      statement.setString(1, binding.json().path("operationId").asText());
      statement.setString(2, binding.hash());
      statement.setString(3, CanonicalJson.canonicalize(binding.json()));
      if (statement.executeUpdate() != 1) throw corrupt();
    }
  }

  static Optional<Binding> read(Connection connection, String operationId) throws SQLException {
    if (!OperationDatabase.hasReviewBindings(connection)) return Optional.empty();
    OperationDatabase.requireReviewVersion(connection);
    try (var statement = prepare(connection,
        "SELECT binding_hash, binding_json FROM tp_operation_review_bindings WHERE operation_id = ?")) {
      statement.setString(1, operationId);
      try (var rows = statement.executeQuery()) {
        if (!rows.next()) return Optional.empty();
        JsonNode json = StoredOperationJson.read(rows.getString(2), 8192);
        if (!json.isObject()) throw corrupt();
        Binding binding = checked((ObjectNode) json, rows.getString(1));
        if (!operationId.equals(json.path("operationId").asText()) || rows.next()) throw corrupt();
        return Optional.of(binding);
      }
    }
  }

  private static Binding checked(ObjectNode json, String hash) {
    try {
      Set<String> fields = new HashSet<>(BINDING_FIELDS);
      if ("require_approval".equals(json.path("outcome").asText())) fields.add("approvalDeadline");
      requireFields(json, fields);
      if (!"tracepermit-operation-review-binding-v1".equals(json.path("format").asText())
          || !JdbcBusinessOperationRepository.normalizeId(json.path("operationId").asText()).equals(json.path("operationId").asText())
          || !hash.equals(CanonicalJson.sha256(json))) throw corrupt();
      for (String key : Set.of("scopeHash", "evaluatedSnapshotHash", "profileHash", "settingsHash", "groupHash",
          "membershipSourceFingerprint", "targetProfileHash", "bindingDigest")) requireHash(json.get(key));
      if (!json.path("authorizationVersion").isIntegralNumber() || !json.path("authorizationVersion").canConvertToLong()
          || json.path("authorizationVersion").asLong() != 2
          || !json.path("lifetimeSeconds").isIntegralNumber() || !json.path("lifetimeSeconds").canConvertToLong()
          || !json.path("lifetimeNanos").isIntegralNumber() || !json.path("lifetimeNanos").canConvertToLong()) throw corrupt();
      long seconds = json.path("lifetimeSeconds").asLong();
      long nanos = json.path("lifetimeNanos").asLong();
      if (seconds < 0 || seconds > 300 || nanos < 0 || nanos > 999_999_999) throw corrupt();
      Duration lifetime = Duration.ofSeconds(seconds, nanos);
      if (lifetime.isZero() || lifetime.compareTo(Duration.ofMinutes(5)) > 0) throw corrupt();
      Instant at = time(json.path("evaluatedAt"));
      if (!Set.of("block", "require_approval").contains(json.path("outcome").asText())
          || !json.path("reasonCode").isTextual() || !json.path("reasonCode").asText().matches("[A-Z][A-Z0-9_]{0,127}")) throw corrupt();
      if (json.has("approvalDeadline") && !at.plus(lifetime).equals(time(json.get("approvalDeadline")))) throw corrupt();
      return new Binding(json, hash);
    } catch (RuntimeException failure) {
      throw corrupt();
    }
  }

  static ObjectNode evaluationActor(Binding binding, BusinessOperationEvaluation command) {
    return envelope(binding, "evaluation").set("identity", command.actor());
  }

  static ObjectNode reviewActor(Binding binding, BusinessOperationReview command,
      TrustedOperationGroupMembership membership) {
    ObjectNode actor = envelope(binding, "review");
    actor.set("identity", command.actor());
    actor.put("groupHash", groupHash(membership.groupId()));
    actor.put("membershipSourceFingerprint", membership.sourceFingerprint());
    actor.put("membershipObservedAt", membership.observedAt().toString());
    actor.put("membershipExpiresAt", membership.expiresAt().toString());
    actor.put("decision", command.decision().name());
    return actor;
  }

  private static ObjectNode envelope(Binding binding, String kind) {
    return JsonSupport.MAPPER.createObjectNode().put("journalFormat", FORMAT).put("kind", kind)
        .put("bindingHash", binding.hash());
  }

  static JsonNode replay(BusinessOperationScope scope, JsonNode before, JsonNode fact, JsonNode actor, Binding binding) {
    try {
      if (binding == null || !FORMAT.equals(actor.path("journalFormat").asText())
          || !binding.hash().equals(actor.path("bindingHash").asText())
          || !scope.fingerprint().equals(binding.json().path("scopeHash").asText())
          || !before.path("operationId").asText().equals(binding.json().path("operationId").asText())
          || !"authorization".equals(fact.path("dimension").asText())
          || !"not_started".equals(before.path("execution").path("status").asText())) throw corrupt();
      Instant at = time(fact.path("at"));
      String kind = actor.path("kind").asText();
      String next;
      String reason;
      if ("evaluation".equals(kind)) {
        requireFields(actor, Set.of("journalFormat", "kind", "bindingHash", "identity"));
        requireIdentity(actor.path("identity"), scope, at, false);
        if (!"evaluating".equals(before.path("authorization").path("status").asText())
            || before.path("transitions").size() != 3
            || !CanonicalJson.sha256(before).equals(binding.json().path("evaluatedSnapshotHash").asText())
            || !at.equals(time(binding.json().path("evaluatedAt")))) throw corrupt();
        next = "block".equals(binding.json().path("outcome").asText()) ? "denied" : "awaiting_approval";
        reason = binding.json().path("reasonCode").asText();
      } else if ("review".equals(kind)) {
        requireFields(actor, Set.of("journalFormat", "kind", "bindingHash", "identity", "groupHash",
            "membershipSourceFingerprint", "membershipObservedAt", "membershipExpiresAt", "decision"));
        requireIdentity(actor.path("identity"), scope, at, true);
        if (!"awaiting_approval".equals(before.path("authorization").path("status").asText())
            || before.path("authorization").path("version").asLong() != 2
            || !"require_approval".equals(binding.json().path("outcome").asText())
            || !time(binding.json().path("approvalDeadline")).isAfter(at)
            || !binding.json().path("groupHash").equals(actor.path("groupHash"))
            || !binding.json().path("membershipSourceFingerprint").equals(actor.path("membershipSourceFingerprint"))) throw corrupt();
        Instant membershipExpiry = time(actor.path("membershipExpiresAt"));
        if (time(actor.path("membershipObservedAt")).isAfter(at) || !membershipExpiry.isAfter(at)
            || membershipExpiry.isAfter(time(actor.path("identity").path("authorityExpiresAt")))) throw corrupt();
        String decision = actor.path("decision").asText();
        if (!Set.of("APPROVE", "DENY").contains(decision)) throw corrupt();
        next = "APPROVE".equals(decision) ? "approved" : "denied";
        reason = "APPROVE".equals(decision) ? APPROVED : DENIED;
      } else throw corrupt();
      if (!next.equals(fact.path("to").asText()) || !reason.equals(fact.path("reasonCode").asText())) throw corrupt();
      var planner = new BusinessOperationTransitionPlanner();
      return planner.propose(scope, before, planner.observe(scope, before),
          BusinessOperationTransitionPlanner.Dimension.AUTHORIZATION, next, reason, at).snapshot();
    } catch (RuntimeException failure) {
      throw corrupt();
    }
  }

  private static void requireIdentity(JsonNode actor, BusinessOperationScope scope, Instant at, boolean human) {
    Set<String> fields = new HashSet<>(Set.of("scopeHash", "principalId", "authenticationMethod", "credentialFingerprint", "authorityExpiresAt"));
    if (human) fields.add("principalType");
    requireFields(actor, fields);
    if (!scope.fingerprint().equals(actor.path("scopeHash").asText())
        || !actor.path("principalId").isTextual() || !actor.path("principalId").asText().matches("[A-Za-z0-9][A-Za-z0-9._:@/+\\-]{0,255}")
        || (human ? scope.requesterPrincipalId().equals(actor.path("principalId").asText())
            || !"human".equals(actor.path("principalType").asText()) : !scope.requesterPrincipalId().equals(actor.path("principalId").asText()))
        || !actor.path("authenticationMethod").isTextual()
        || !actor.path("authenticationMethod").asText().matches("[A-Za-z0-9][A-Za-z0-9._:@/+\\-]{0,127}")
        || !time(actor.path("authorityExpiresAt")).isAfter(at)) throw corrupt();
    requireHash(actor.path("credentialFingerprint"));
  }

  private static void requireFields(JsonNode json, Set<String> expected) {
    Set<String> fields = new HashSet<>();
    json.fieldNames().forEachRemaining(fields::add);
    if (!json.isObject() || !fields.equals(expected)) throw corrupt();
  }

  private static void requireHash(JsonNode node) {
    if (node == null || !node.isTextual() || !node.asText().matches("sha256:[a-f0-9]{64}")) throw corrupt();
  }

  static Instant time(JsonNode node) {
    try {
      if (node == null || !node.isTextual()) throw corrupt();
      Instant value = Instant.parse(node.asText());
      if (!value.toString().equals(node.asText())) throw corrupt();
      return value;
    } catch (RuntimeException failure) {
      throw corrupt();
    }
  }

  private static BusinessOperationStorageException corrupt() {
    return new BusinessOperationStorageException(OPERATION_STORAGE_CORRUPT);
  }
}
