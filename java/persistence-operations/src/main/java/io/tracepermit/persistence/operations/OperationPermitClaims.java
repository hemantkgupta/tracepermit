package io.tracepermit.persistence.operations;

import static io.tracepermit.application.BusinessOperationStorageException.Code.*;
import static io.tracepermit.persistence.operations.JdbcBusinessOperationRepository.prepare;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.tracepermit.adapters.remote.github.GitHubBusinessOperationBinding;
import io.tracepermit.application.*;
import io.tracepermit.contracts.CanonicalJson;
import io.tracepermit.contracts.JsonSupport;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** One internal claims recipe, called only after the closed owner has loaded actual JDBC state. */
final class OperationPermitClaims {
  static final String FORMAT = "tracepermit-business-operation-permit-v1";
  private static final Set<String> HASHES = Set.of("scopeHash", "profileHash", "admissionSettingsHash",
      "targetProfileHash", "targetBindingDigest", "canonicalActionHash", "semanticPayloadDigest",
      "policyReleaseHash", "artifactDigestsHash", "resourceBindingHash", "preconditionBindingHash",
      "targetIdempotencyKeyHash", "reviewBindingHash", "evaluationFactHash", "evaluationActorHash",
      "approvalFactHash", "approvalActorHash", "publicKeyFingerprint");
  private static final Set<String> STATE = Set.of("operationId", "recoveryGeneration", "lastSequence",
      "authorizationVersion", "executionVersion", "evidenceVersion", "snapshotHash");
  private static final Set<String> BOUNDS = Set.of("evaluatorExpiresAt", "reviewerExpiresAt", "membershipExpiresAt",
      "ownerExpiresAt", "approvalDeadline");

  private OperationPermitClaims() {}

  static ObjectNode capture(Connection connection, JsonNode initial, JsonNode approved,
      BusinessOperationAuthorityProfile profile, GitHubBusinessOperationBinding.ExpectedProfile target,
      BusinessOperationExecution command, Instant now) throws SQLException {
    command.requireAuthorizedAt(profile, now);
    var scope = command.scope();
    var observed = new BusinessOperationTransitionPlanner().observe(scope, approved);
    if (!observed.equals(command.expected())) throw error(OPERATION_STATE_STALE);
    if (!"approved".equals(approved.path("authorization").path("status").asText())
        || observed.authorizationVersion() != 3 || observed.lastSequence() != 5
        || observed.executionVersion() != 1 || observed.evidenceVersion() != 1
        || !"not_started".equals(approved.path("execution").path("status").asText())) throw error(OPERATION_NOT_APPROVED);
    if (now.isBefore(Instant.parse(approved.path("updatedAt").asText()))) throw error(OPERATION_CLOCK_REGRESSION);
    var binding = OperationReviewJournal.read(connection, command.operationId()).orElseThrow(() -> error(OPERATION_STORAGE_CORRUPT));
    GitHubBusinessOperationBinding.BoundOperationData actual;
    try {
      actual = GitHubBusinessOperationBinding.bind(scope, approved, target);
      var original = GitHubBusinessOperationBinding.bind(scope, initial, target);
      var policy = profile.evaluate(initial);
      profile.evaluate(approved);
      var reconstructed = OperationReviewJournal.create(scope, initial, profile, original, policy.outcome(),
          policy.reasonCode(), OperationReviewJournal.time(binding.json().path("evaluatedAt")));
      if (!"require_approval".equals(policy.outcome()) || !binding.hash().equals(reconstructed.hash())) {
        throw error(OPERATION_PROFILE_MISMATCH);
      }
    } catch (RuntimeException failure) {
      throw error(OPERATION_PROFILE_MISMATCH);
    }
    var evaluation = fact(connection, command.operationId(), 4);
    var approval = fact(connection, command.operationId(), 5);
    if (!"evaluation".equals(evaluation.actor().path("kind").asText())
        || !"review".equals(approval.actor().path("kind").asText())
        || !"APPROVE".equals(approval.actor().path("decision").asText())
        || !"approved".equals(approval.fact().path("to").asText())) throw error(OPERATION_STORAGE_CORRUPT);
    var settings = profile.admissionSettings();
    ObjectNode bounds = JsonSupport.MAPPER.createObjectNode()
        .put("evaluatorExpiresAt", evaluation.actor().path("identity").path("authorityExpiresAt").asText())
        .put("reviewerExpiresAt", approval.actor().path("identity").path("authorityExpiresAt").asText())
        .put("membershipExpiresAt", approval.actor().path("membershipExpiresAt").asText())
        .put("ownerExpiresAt", command.actor().path("authorityExpiresAt").asText())
        .put("approvalDeadline", binding.json().path("approvalDeadline").asText());
    Instant expiry = now.plus(settings.lifetime());
    for (JsonNode bound : bounds) {
      Instant value = OperationReviewJournal.time(bound);
      if (value.isBefore(expiry)) expiry = value;
    }
    if (!expiry.isAfter(now)) throw error(OPERATION_PERMIT_EXPIRED);
    ObjectNode claims = JsonSupport.MAPPER.createObjectNode().put("format", FORMAT)
        .put("issuer", settings.issuer()).put("audience", settings.audience())
        .put("publicKeyFingerprint", settings.publicKeyFingerprint())
        .put("permitId", UUID.randomUUID().toString()).put("attemptId", UUID.randomUUID().toString())
        .put("operationId", command.operationId()).put("scopeHash", scope.fingerprint())
        .put("ownerPrincipalId", scope.requesterPrincipalId()).put("adapterId", target.adapterId())
        .put("profileHash", profile.fingerprint()).put("admissionSettingsHash", CanonicalJson.sha256(settings.pins()))
        .put("targetProfileHash", actual.profileFingerprint()).put("targetBindingDigest", actual.bindingDigest())
        .put("canonicalActionHash", approved.path("canonicalActionHash").asText())
        .put("semanticPayloadDigest", approved.path("semanticPayloadDigest").asText())
        .put("policyReleaseHash", CanonicalJson.sha256(approved.path("policyRelease")))
        .put("artifactDigestsHash", CanonicalJson.sha256(approved.path("artifactDigests")))
        .put("resourceBindingHash", approved.path("resourceBindingHash").asText())
        .put("preconditionBindingHash", CanonicalJson.sha256(approved.path("resourceBinding").path("precondition")))
        .put("targetIdempotencyKeyHash", actual.targetIdempotencyKeyHash())
        .put("reviewBindingHash", binding.hash())
        .put("evaluationFactHash", CanonicalJson.sha256(evaluation.fact())).put("evaluationActorHash", CanonicalJson.sha256(evaluation.actor()))
        .put("approvalFactHash", CanonicalJson.sha256(approval.fact())).put("approvalActorHash", CanonicalJson.sha256(approval.actor()))
        .put("issuedAt", now.toString()).put("expiresAt", expiry.toString()).put("maxUses", 1);
    claims.set("state", JsonSupport.MAPPER.createObjectNode().put("operationId", observed.operationId())
        .put("recoveryGeneration", observed.recoveryGeneration()).put("lastSequence", observed.lastSequence())
        .put("authorizationVersion", observed.authorizationVersion()).put("executionVersion", observed.executionVersion())
        .put("evidenceVersion", observed.evidenceVersion()).put("snapshotHash", observed.snapshotHash()));
    claims.set("identityBounds", bounds);
    claims.set("executeIdentity", command.actor());
    return checked(claims, settings);
  }

  /** Closed syntax/intrinsic bindings only: not current DB state or permission to execute. */
  static ObjectNode checked(JsonNode claims, BusinessOperationAdmissionSettings settings) {
    ObjectNode result = historical(claims);
    Instant issue = OperationReviewJournal.time(result.path("issuedAt"));
    if (!settings.issuer().equals(result.path("issuer").asText())
        || !settings.audience().equals(result.path("audience").asText())
        || !settings.publicKeyFingerprint().equals(result.path("publicKeyFingerprint").asText())
        || !CanonicalJson.sha256(settings.pins()).equals(result.path("admissionSettingsHash").asText())
        || OperationReviewJournal.time(result.path("expiresAt")).isAfter(issue.plus(settings.lifetime()))) throw invalid();
    return result;
  }

  /** Stored metadata shape/intrinsic bounds only; no signature or current authority assertion. */
  static ObjectNode historical(JsonNode claims) {
    try {
      Set<String> fields = new HashSet<>(HASHES);
      fields.addAll(Set.of("format", "issuer", "audience", "permitId", "attemptId", "operationId", "ownerPrincipalId",
          "adapterId", "state", "issuedAt", "expiresAt", "maxUses", "identityBounds", "executeIdentity"));
      fields(claims, fields);
      if (!claims.path("issuer").isTextual() || !claims.path("audience").isTextual()
          || !claims.path("issuer").asText().matches("[A-Za-z0-9][A-Za-z0-9._:@/+\\-]{0,511}")
          || claims.path("audience").asText().isBlank() || claims.path("audience").asText().length() > 256
          || !FORMAT.equals(claims.path("format").asText())
          || !GitHubBusinessOperationBinding.ADAPTER_ID.equals(claims.path("adapterId").asText())) throw invalid();
      for (String key : HASHES) hash(claims.path(key));
      for (String key : Set.of("permitId", "attemptId", "operationId")) {
        JsonNode value = claims.path(key);
        if (!value.isTextual() || !UUID.fromString(value.asText()).toString().equals(value.asText())) throw invalid();
      }
      if (!claims.path("ownerPrincipalId").isTextual()
          || !claims.path("ownerPrincipalId").asText().matches("[A-Za-z0-9][A-Za-z0-9._:@/-]{0,255}")) throw invalid();
      JsonNode state = claims.path("state");
      fields(state, STATE);
      hash(state.path("snapshotHash"));
      if (!state.path("operationId").equals(claims.path("operationId"))) throw invalid();
      positive(state.path("recoveryGeneration"));
      number(state.path("lastSequence"), 5); number(state.path("authorizationVersion"), 3);
      number(state.path("executionVersion"), 1); number(state.path("evidenceVersion"), 1); number(claims.path("maxUses"), 1);
      Instant issue = OperationReviewJournal.time(claims.path("issuedAt"));
      Instant expiry = OperationReviewJournal.time(claims.path("expiresAt"));
      if (!expiry.isAfter(issue) || expiry.isAfter(issue.plusSeconds(60))) throw invalid();
      JsonNode bounds = claims.path("identityBounds");
      fields(bounds, BOUNDS);
      for (JsonNode bound : bounds) if (expiry.isAfter(OperationReviewJournal.time(bound))) throw invalid();
      JsonNode identity = claims.path("executeIdentity");
      fields(identity, Set.of("scopeHash", "principalId", "authenticationMethod", "credentialFingerprint", "authorityExpiresAt"));
      if (!identity.path("scopeHash").equals(claims.path("scopeHash"))
          || !identity.path("principalId").equals(claims.path("ownerPrincipalId"))
          || !identity.path("authorityExpiresAt").equals(bounds.path("ownerExpiresAt"))
          || !identity.path("authenticationMethod").isTextual()
          || !identity.path("authenticationMethod").asText().matches("[A-Za-z0-9][A-Za-z0-9._:@/+\\-]{0,127}")) throw invalid();
      hash(identity.path("credentialFingerprint"));
      return (ObjectNode) claims.deepCopy();
    } catch (RuntimeException failure) {
      throw invalid();
    }
  }

  private record Fact(JsonNode fact, JsonNode actor) {}
  private static Fact fact(Connection connection, String id, int sequence) throws SQLException {
    try (var statement = prepare(connection,
        "SELECT fact_json, actor_hash, actor_json FROM tp_operation_lifecycle_facts WHERE operation_id = ? AND sequence_number = ?")) {
      statement.setString(1, id); statement.setInt(2, sequence);
      try (var rows = statement.executeQuery()) {
        if (!rows.next()) throw error(OPERATION_STORAGE_CORRUPT);
        var fact = StoredOperationJson.read(rows.getString(1), 4096);
        var actor = StoredOperationJson.read(rows.getString(3), 4096);
        if (!CanonicalJson.sha256(actor).equals(rows.getString(2)) || rows.next()) throw error(OPERATION_STORAGE_CORRUPT);
        return new Fact(fact, actor);
      }
    }
  }

  private static void fields(JsonNode node, Set<String> expected) {
    Set<String> actual = new HashSet<>(); node.fieldNames().forEachRemaining(actual::add);
    if (!node.isObject() || !expected.equals(actual)) throw invalid();
  }
  private static void hash(JsonNode node) {
    if (!node.isTextual() || !node.asText().matches("sha256:[a-f0-9]{64}")) throw invalid();
  }
  private static void positive(JsonNode node) {
    if (!node.isIntegralNumber() || !node.canConvertToLong() || node.asLong() < 1 || node.asLong() > 9_007_199_254_740_991L) throw invalid();
  }
  private static void number(JsonNode node, long expected) {
    positive(node); if (node.asLong() != expected) throw invalid();
  }
  private static BusinessOperationStorageException invalid() { return error(OPERATION_PERMIT_INVALID); }
  private static BusinessOperationStorageException error(BusinessOperationStorageException.Code code) { return new BusinessOperationStorageException(code); }
}
