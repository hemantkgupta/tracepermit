package io.tracepermit.persistence.operations;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import io.tracepermit.adapters.remote.github.GitHubBusinessOperationBinding;
import io.tracepermit.adapters.remote.github.GitHubPilotTargetBinding;
import io.tracepermit.application.*;
import io.tracepermit.contracts.BusinessOperationValidator;
import io.tracepermit.contracts.CanonicalJson;
import io.tracepermit.contracts.JsonSupport;
import io.tracepermit.execution.TenantPartition;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import org.testcontainers.containers.PostgreSQLContainer;

/** Disposable PostgreSQL admission-only fixture; no delivery repository or target is constructed. */
final class QuarantineAdmissionFixture implements AutoCloseable {
  static final String HASH = "sha256:" + "a".repeat(64);
  static final BusinessOperationScope SCOPE = new BusinessOperationScope("synthetic-tenant", "synthetic-github-sandbox", "principal:synthetic-requester");
  private static final BusinessOperationTransitionPlanner PLANNER = new BusinessOperationTransitionPlanner();
  static final List<String> DATA_TABLES = List.of("tp_operation_schema_history", "tp_business_operations", "tp_business_operation_facts",
      "tp_operation_lifecycle_heads", "tp_operation_lifecycle_facts", "tp_operation_review_bindings", "tp_operation_admissions",
      "tp_operation_admission_outbox", "tp_operation_delivery_events");
  private final PostgreSQLContainer<?> database = new PostgreSQLContainer<>("postgres:16.4-alpine").withPassword(UUID.randomUUID().toString());
  QuarantineAdmissionSource owner;
  QuarantineAdmissionSource runtime;
  QuarantineAdmissionSource observer;
  KeyPair keys;
  BusinessOperationAuthorityProfile profile;
  GitHubBusinessOperationBinding.ExpectedProfile target;
  JsonNode initial;
  ObjectNode policy;

  void start() {
    database.withCreateContainerCmdModifier(command -> command.getHostConfig().withPortBindings(database.getExposedPorts().stream()
        .map(port -> new PortBinding(Ports.Binding.bindIp("127.0.0.1"), new ExposedPort(port))).toArray(PortBinding[]::new)));
    database.start();
    assertThat(database.getContainerInfo().getNetworkSettings().getPorts().getBindings().values()).allSatisfy(values ->
        assertThat(values).hasSize(1).allSatisfy(value -> assertThat(value.getHostIp()).isEqualTo("127.0.0.1")));
    owner = source(database.getUsername(), database.getPassword());
    OperationDatabase.initializeDeliveryEmpty(owner);
    String runtimePassword = UUID.randomUUID().toString();
    String observerPassword = UUID.randomUUID().toString();
    try (var connection = owner.getConnection(); var statement = connection.createStatement()) {
      statement.setQueryTimeout(5);
      statement.execute("CREATE USER tp_quarantine_runtime PASSWORD '" + runtimePassword + "'");
      statement.execute("CREATE USER tp_quarantine_observer PASSWORD '" + observerPassword + "'");
      for (String user : List.of("tp_quarantine_runtime", "tp_quarantine_observer")) {
        statement.execute("GRANT USAGE ON SCHEMA public TO " + user);
        for (String table : DATA_TABLES) statement.execute("GRANT SELECT ON " + table + " TO " + user);
        statement.execute("GRANT SELECT ON tp_operation_authority_controls TO " + user);
      }
      for (String table : List.of("tp_operation_lifecycle_facts", "tp_operation_review_bindings", "tp_operation_admissions", "tp_operation_admission_outbox")) {
        statement.execute("GRANT INSERT ON " + table + " TO tp_quarantine_runtime");
      }
      statement.execute("GRANT UPDATE(snapshot_hash, snapshot_json) ON tp_operation_lifecycle_heads TO tp_quarantine_runtime");
      statement.execute("GRANT UPDATE(lock_marker) ON tp_operation_authority_controls TO tp_quarantine_runtime");
    } catch (SQLException failure) { throw new AssertionError("SYNTHETIC_ROLE_SETUP_FAILED"); }
    runtime = source("tp_quarantine_runtime", runtimePassword);
    observer = source("tp_quarantine_observer", observerPassword);
    try (var connection = observer.getConnection(); var statement = connection.createStatement()) {
      statement.setQueryTimeout(5);
      try (var rows = statement.executeQuery("SHOW server_version_num")) {
        assertThat(rows.next()).isTrue(); assertThat(rows.getInt(1)).isEqualTo(160004);
      }
    } catch (SQLException failure) { throw new AssertionError("SYNTHETIC_POSTGRES_IDENTITY_UNESTABLISHED"); }
  }

  private QuarantineAdmissionSource source(String user, String password) {
    return new QuarantineAdmissionSource(database.getJdbcUrl(), user, password);
  }

  void reset() throws Exception {
    owner.resetObservations(); runtime.resetObservations(); observer.resetObservations();
    for (String table : List.of("tp_operation_delivery_events", "tp_operation_admission_outbox", "tp_operation_admissions", "tp_operation_review_bindings",
        "tp_operation_lifecycle_facts", "tp_operation_lifecycle_heads", "tp_business_operation_facts", "tp_business_operations", "tp_operation_authority_controls")) sql("DELETE FROM " + table);
    keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    policy = (ObjectNode) JsonSupport.MAPPER.readTree("""
        {"schemaVersion":"1.0","id":"synthetic-github-draft-pr","version":1,"name":"Synthetic",
         "description":"No target authority","enabled":true,"defaultDecision":{"outcome":"block","reasonCode":"SYNTHETIC_DENY"},
         "rules":[{"id":"exact-draft-pr","priority":1,"description":"Synthetic","match":{"toolNames":["github.pull_request.create"]},
         "decision":{"outcome":"require_approval","reasonCode":"SYNTHETIC_REVIEW","approvalGroup":"synthetic-reviewers"}}]}
        """);
    target = new GitHubBusinessOperationBinding.ExpectedProfile("github-draft-pr-v1", "test", 1,
        new GitHubBusinessOperationBinding.PolicyRelease("synthetic-github-draft-pr", 1, CanonicalJson.sha256(policy)),
        Map.of("policy_bundle", HASH, "adapter_release", HASH),
        new GitHubPilotTargetBinding(71, 101, 201, "tracepermit/agentcore-pr-pilot", "synthetic-change", "main", "a".repeat(40), "b".repeat(40)));
    initial = operation();
    profile = profile(SCOPE, 1, policy);
    OperationAuthorityAdministration.stageNew(owner, profile);
    OperationAuthorityAdministration.activateStaged(owner, profile);
    new JdbcBusinessOperationRepository(owner).createOrGet(SCOPE, initial);
    owner.resetObservations(); runtime.resetObservations(); observer.resetObservations();
  }

  BusinessOperationAuthorityProfile profile(BusinessOperationScope scope, long generation, JsonNode operatorPolicy) {
    return new BusinessOperationAuthorityProfile(scope, target.adapterId(), generation, operatorPolicy,
        CanonicalJson.sha256(initial.path("artifactDigests")), initial.path("resourceBindingHash").asText(),
        new BusinessOperationReviewSettings("synthetic-reviewers", HASH, Duration.ofMinutes(2)),
        new BusinessOperationAdmissionSettings("https://synthetic-issuer.invalid", "synthetic-key", "synthetic-admission", keys.getPublic(), Duration.ofSeconds(60)));
  }

  JsonNode approved() {
    var writer = new JdbcGitHubOperationReviewRepository(runtime, profile, target);
    var identity = requester(Set.of("operations.evaluate"), fresh());
    JsonNode pending = writer.evaluate(new BusinessOperationEvaluation(SCOPE, id(), PLANNER.observe(SCOPE, initial), new TrustedOperationEvaluatorContext(identity, SCOPE.workspaceId())));
    var reviewer = new TrustedReviewerContext(new TenantPartition(SCOPE.tenantId()), "principal:reviewer", "human", true,
        Set.of("operation_reviewer"), Set.of("operations.decide"), "synthetic-test", HASH);
    return writer.review(new BusinessOperationReview(SCOPE, id(), PLANNER.observe(SCOPE, pending),
        new TrustedOperationReviewerContext(reviewer, SCOPE.workspaceId(), fresh()), BusinessOperationReview.Decision.APPROVE),
        new TrustedOperationGroupMembership(SCOPE.tenantId(), SCOPE.workspaceId(), "principal:reviewer", "synthetic-reviewers", HASH,
            Instant.now().minusSeconds(1), fresh().minusSeconds(30)));
  }

  JdbcGitHubOperationAdmissionRepository admission() { return new JdbcGitHubOperationAdmissionRepository(runtime, profile, target); }
  JdbcGitHubOperationPermitIssuer issuer() { return new JdbcGitHubOperationPermitIssuer(runtime, profile, target, keys.getPrivate()); }
  BusinessOperationExecution execution(JsonNode approved, Instant expiry) {
    return new BusinessOperationExecution(SCOPE, id(), PLANNER.observe(SCOPE, approved), requester(Set.of("operations.execute"), expiry), SCOPE.workspaceId());
  }
  private static TrustedRequesterContext requester(Set<String> scopes, Instant expiry) {
    return new TrustedRequesterContext(new TenantPartition(SCOPE.tenantId()), SCOPE.requesterPrincipalId(), true,
        Set.of("operation_requester"), scopes, "synthetic-test", HASH, expiry);
  }
  static Instant fresh() { return Instant.now().plusSeconds(180); }
  String id() { return initial.path("operationId").asText(); }

  record Control(String scope, String profile, long generation, String state, int marker) {}
  Control control() throws SQLException {
    try (var connection = observer.getConnection(); var statement = connection.prepareStatement("SELECT scope_hash, profile_hash, recovery_generation, control_state, lock_marker FROM tp_operation_authority_controls")) {
      statement.setQueryTimeout(5);
      try (var rows = statement.executeQuery()) {
        if (!rows.next()) return null;
        var result = new Control(rows.getString(1), rows.getString(2), rows.getLong(3), rows.getString(4), rows.getInt(5));
        assertThat(rows.next()).isFalse();
        return result;
      }
    }
  }

  /** Hash/count every row through the independent SELECT-only connection, excluding controls. */
  Map<String, List<String>> data() throws SQLException {
    Map<String, List<String>> result = new TreeMap<>();
    try (var connection = observer.getConnection(); var statement = connection.createStatement()) {
      statement.setQueryTimeout(5);
      for (String table : DATA_TABLES) {
        List<String> hashes = new ArrayList<>();
        try (var rows = statement.executeQuery("SELECT * FROM " + table)) {
          int columns = rows.getMetaData().getColumnCount();
          while (rows.next()) {
            ArrayNode row = JsonSupport.MAPPER.createArrayNode();
            for (int index = 1; index <= columns; index++) row.add(rows.getString(index));
            hashes.add(CanonicalJson.sha256(row));
          }
        }
        Collections.sort(hashes);
        result.put(table, List.copyOf(hashes));
      }
    }
    return Map.copyOf(result);
  }

  JsonNode storedHead() throws Exception {
    try (var connection = observer.getConnection(); var statement = connection.prepareStatement("SELECT snapshot_json FROM tp_operation_lifecycle_heads WHERE operation_id = ?")) {
      statement.setQueryTimeout(5); statement.setString(1, id());
      try (var rows = statement.executeQuery()) {
        assertThat(rows.next()).isTrue();
        JsonNode result = JsonSupport.MAPPER.readTree(rows.getString(1));
        assertThat(rows.next()).isFalse();
        return result;
      }
    }
  }

  Instant storeNow() throws SQLException {
    try (var connection = observer.getConnection(); var statement = connection.createStatement()) {
      statement.setQueryTimeout(5);
      try (var rows = statement.executeQuery("SELECT CURRENT_TIMESTAMP")) {
        assertThat(rows.next()).isTrue();
        return rows.getTimestamp(1).toInstant();
      }
    }
  }

  void sql(String sql) throws SQLException {
    try (var connection = owner.getConnection(); var statement = connection.createStatement()) {
      statement.setQueryTimeout(5); statement.execute(sql);
    }
  }

  void assertQuarantined(Control before) throws SQLException {
    assertThat(control()).isEqualTo(new Control(before.scope(), before.profile(), before.generation(), "quarantined", before.marker()));
  }

  void assertNoOwnedConnections() {
    assertThat(owner.open.get()).isZero(); assertThat(runtime.open.get()).isZero(); assertThat(observer.open.get()).isZero();
  }

  @Override public void close() { database.stop(); }

  private ObjectNode operation() {
    ObjectNode value = (ObjectNode) JsonSupport.read(Path.of(System.getProperty("tracepermit.repositoryRoot")).resolve("contracts/vectors/business-operation-v1.json")).path("operation").deepCopy();
    value.put("operationId", UUID.randomUUID().toString()); ((ArrayNode) value.path("transitions")).remove(3);
    ((ObjectNode) value.path("authorization")).put("status", "evaluating").put("version", 1).put("updatedAt", value.path("createdAt").asText());
    value.put("updatedAt", value.path("transitions").get(2).path("at").asText());
    ObjectNode action = (ObjectNode) value.path("canonicalAction").path("action"); ObjectNode arguments = (ObjectNode) action.path("arguments");
    arguments.remove("owner"); arguments.put("repository", target.targetBinding().repository()).put("head", target.targetBinding().head()).put("base", "main");
    ((ObjectNode) action.path("target")).put("accountId", target.targetBinding().repository()).put("resourceType", "pull_request").put("environment", target.environment());
    value.set("resourceBinding", target.resourceBinding()); value.putObject("policyRelease").put("policyId", target.policyRelease().policyId()).put("version", 1).put("contentHash", target.policyRelease().contentHash());
    var artifacts = value.putArray("artifactDigests"); new TreeMap<>(target.artifactDigests()).forEach((kind, hash) -> artifacts.addObject().put("kind", kind).put("digest", hash));
    value.put("canonicalActionHash", CanonicalJson.sha256(value.path("canonicalAction"))).put("semanticPayloadDigest", CanonicalJson.sha256(action)).put("resourceBindingHash", CanonicalJson.sha256(value.path("resourceBinding")));
    value.put("targetIdempotencyKeyHash", BusinessOperationValidator.targetIdempotencyKeyHash(value.path("operationId").asText(), target.adapterId(), 1));
    return (ObjectNode) BusinessOperationValidator.validate(value);
  }
}
