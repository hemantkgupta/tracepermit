package io.tracepermit.adapters.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.tracepermit.contracts.CanonicalJson;
import io.tracepermit.contracts.JsonSupport;
import io.tracepermit.execution.TenantPartition;
import io.tracepermit.identity.AuthenticationException;
import io.tracepermit.identity.ConnectorOrigin;
import io.tracepermit.identity.ConnectorScope;
import io.tracepermit.identity.OidcAccessTokenVerifier;
import io.tracepermit.identity.OidcProviderConfiguration;
import io.tracepermit.identity.StaticOidcProviderRegistry;
import io.tracepermit.persistence.postgres.PostgresDurableEnforcementState;
import io.tracepermit.persistence.postgres.PostgresPermitConsumptionStore;
import io.tracepermit.persistence.postgres.PostgresPolicyLifecycleStore;
import io.tracepermit.security.ExecutionPermitAuthority;
import io.tracepermit.simulator.StatefulTargetSimulator;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

/** Actual HTTP + signature verification + PostgreSQL, with no live IdP or real target. */
final class SimulatorApprovalServerTest {
  private static final Path ROOT = Path.of(System.getProperty("tracepermit.repositoryRoot"));
  private static final String ISSUER = "https://idp.example.invalid";
  private static final String AUDIENCE = "https://approval.example.invalid";
  private static final String SCOPES = "approvals.read approvals.request approvals.decide approvals.execute";
  private static final String ACTION = "{\"groupId\":\"sg-demo\",\"port\":443,\"cidr\":\"192.0.2.0/24\"}";
  private static final TenantPartition TENANT = new TenantPartition("simulated-alpha");
  private static final AtomicInteger SCHEMA = new AtomicInteger();
  private static PostgreSQLContainer<?> postgres;
  private static KeyPair rsa;
  private static KeyPair permitKey;
  private static HttpClient client;
  private final MutableClock clock = new MutableClock();
  private PGSimpleDataSource dataSource;
  private ApprovalMemberships memberships;
  private PostgresDurableEnforcementState state;
  private StatefulTargetSimulator simulator;
  private SimulatorApprovalServer server;
  private OidcAccessTokenVerifier verifier;
  private JsonNode policy;

  @BeforeAll static void prerequisites() throws Exception {
    postgres = new PostgreSQLContainer<>("postgres:16.4-alpine");
    postgres.start();
    var generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(2048);
    rsa = generator.generateKeyPair();
    permitKey = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
  }

  @AfterAll static void cleanup() {
    if (client != null) client.close();
    if (postgres != null) postgres.stop();
  }

  @BeforeEach void start() throws Exception {
    dataSource = new PGSimpleDataSource();
    dataSource.setUrl(postgres.getJdbcUrl());
    dataSource.setUser(postgres.getUsername());
    dataSource.setPassword(postgres.getPassword());
    String schema = "approval_case_" + SCHEMA.incrementAndGet();
    try (var connection = dataSource.getConnection(); var sql = connection.createStatement()) {
      sql.execute("CREATE SCHEMA " + schema);
    }
    dataSource.setCurrentSchema(schema);
    memberships = new ApprovalMemberships(entries());
    simulator = new StatefulTargetSimulator();
    policy = JsonSupport.read(ROOT.resolve("policies/aws-web-readonly.json"));
    Set<ConnectorScope> scopes = Set.of(SCOPES.split(" ")).stream()
        .map(ConnectorScope::of).collect(java.util.stream.Collectors.toUnmodifiableSet());
    var provider = new OidcProviderConfiguration(URI.create(ISSUER), AUDIENCE,
        URI.create(ISSUER + "/jwks"), URI.create(AUDIENCE + "/metadata"),
        List.of(URI.create(ISSUER)), "Simulator approvals",
        Map.of("approval-client", "Approval API", "alternate-client", "Alternate API"), "org_id",
        Map.of("alpha", TENANT.value(), "beta", "simulated-beta"), scopes,
        ConnectorOrigin.CLI, Duration.ofMinutes(15), Duration.ofMinutes(1), Duration.ofSeconds(5));
    verifier = new OidcAccessTokenVerifier(new StaticOidcProviderRegistry(List.of(provider)),
        (p, keyId, algorithm, now) -> {
          if (!"fixture-key".equals(keyId)) throw new AuthenticationException("INVALID_KEY", "Invalid key.");
          return (RSAPublicKey) rsa.getPublic();
        }, clock);
    restart();
  }

  private void restart() throws Exception {
    if (server != null) server.close();
    state = new PostgresDurableEnforcementState(dataSource, clock, () -> UUID.randomUUID().toString());
    var permits = new ExecutionPermitAuthority(ExecutionPermitAuthority.DEFAULT_ISSUER,
        "fixture-ed25519", permitKey, 30, clock, () -> UUID.randomUUID().toString(),
        new PostgresPermitConsumptionStore(dataSource));
    server = SimulatorApprovalServer.start(new InetSocketAddress("127.0.0.1", 0), verifier,
        memberships, state, policy, permits, simulator, clock);
  }

  @AfterEach void stop() { if (server != null) server.close(); server = null; }

  @Test void authenticatedApprovalSurvivesRestartAndExecutesExactlyOnce() throws Exception {
    String bearer = token("alice");
    JsonNode pending = create(bearer);
    assertThat(pending.path("status").asText()).isEqualTo("pending");
    assertThat(pending.path("executionStatus").asText()).isEqualTo("not_executed");
    assertThat(pending.toString()).doesNotContain(bearer, "credentialFingerprint", ISSUER + "#alice");
    assertThat(rules()).isZero();
    String path = path(pending);
    restart();
    assertThat(json(call("GET", path, bearer, null)).path("requestHash")).isEqualTo(pending.path("requestHash"));
    assertThat(json(call("GET", "/api/v1/approvals", bearer, null)).path("items").size()).isEqualTo(1);
    assertStatus(call("POST", path + "/execute", bearer, hash(pending)), 409);
    assertStatus(call("POST", path + "/decision", token("alice"), decision(pending, "approve")), 403);
    assertStatus(call("POST", path + "/decision", token("bob"), decision(pending, "approve")), 200);
    assertStatus(call("POST", path + "/decision", token("bob"), decision(pending, "deny")), 409);
    assertStatus(call("POST", path + "/execute", token("bob"), hash(pending)), 403);
    assertStatus(call("POST", path + "/execute", bearer, "{\"requestHash\":\"changed\"}"), 409);
    assertThat(rules()).isZero();
    JsonNode executed = json(call("POST", path + "/execute", bearer, hash(pending)));
    assertThat(executed.path("executionStatus").asText()).isEqualTo("succeeded");
    assertThat(executed.path("status").asText()).isEqualTo("consumed");
    assertThat(rules()).isEqualTo(1);
    restart();
    assertStatus(call("POST", path + "/execute", bearer, hash(pending)), 409);
    assertThat(json(call("GET", path, bearer, null)).path("executionStatus").asText()).isEqualTo("succeeded");
    String evidence = state.readEvidence(TENANT).toString();
    assertThat(evidence).doesNotContain(bearer, "192.0.2.0", "privateKey");
    assertThat(state.readEvidence(TENANT)).hasSizeGreaterThanOrEqualTo(5);
  }

  @Test void requesterScopeIsOwnOnlyAndTenantCannotBeSelectedInBody() throws Exception {
    JsonNode pending = create(token("alice"));
    assertStatus(call("GET", path(pending), token("charlie"), null), 404);
    assertStatus(call("GET", path(pending), token("beta", c -> c.put("org_id", "beta")), null), 404);
    assertThat(json(call("GET", "/api/v1/approvals", token("charlie"), null)).path("items").isEmpty()).isTrue();
    assertStatus(call("POST", "/api/v1/approvals", token("alice"), ACTION.replace("443", "443,\"tenant\":\"beta\"")), 400);
  }

  @Test void serviceIsNotAReviewerAndTokenRoleClaimsDoNotEnrollAnyone() throws Exception {
    assertStatus(call("GET", "/api/v1/approvals", token("unknown", c -> c.put("role", "admin")), null), 403);
    JsonNode pending = create(token("alice"));
    assertStatus(call("POST", path(pending) + "/decision", token("service", c -> c.put("role", "approval_reviewer")), decision(pending, "approve")), 403);
    assertStatus(call("POST", path(pending) + "/decision", token("alice", c -> c.put("client_id", "alternate-client")), decision(pending, "approve")), 403);
    assertThat(rules()).isZero();
    JsonNode workloadRequest = create(token("service"));
    assertThat(state.findAction(TENANT, workloadRequest.path("approvalRequestId").asText())
        .path("identity").path("subject").path("type").asText()).isEqualTo("workload");
  }

  @Test void membershipRemovalAndNarrowedScopeApplyOnEveryRequest() throws Exception {
    JsonNode pending = create(token("alice"));
    assertStatus(call("POST", path(pending) + "/decision", token("bob", c -> c.put("scope", "approvals.read")), decision(pending, "approve")), 403);
    assertStatus(call("GET", path(pending), token("bob", c -> c.put("scope", "approvals.decide")), null), 403);
    memberships.replace(entries().stream().filter(e -> !e.verifiedSubjectId().equals(ISSUER + "#bob")).toList());
    assertStatus(call("POST", path(pending) + "/decision", token("bob"), decision(pending, "approve")), 403);
    assertThat(rules()).isZero();
  }

  @Test void denialAndExpiryNeverDispatch() throws Exception {
    JsonNode denied = create(token("alice"));
    assertStatus(call("POST", path(denied) + "/decision", token("bob"), decision(denied, "deny")), 200);
    assertStatus(call("POST", path(denied) + "/execute", token("alice"), hash(denied)), 409);
    JsonNode pending = create(token("alice"));
    clock.advance(301);
    assertThat(json(call("GET", path(pending), token("alice"), null)).path("expired").asBoolean()).isTrue();
    assertStatus(call("POST", path(pending) + "/decision", token("bob"), decision(pending, "approve")), 409);
    assertThat(json(call("GET", path(pending), token("alice"), null)).path("status").asText()).isEqualTo("expired");
    assertThat(rules()).isZero();
  }

  @Test void reviewerRemovalAfterApprovalPreventsExecution() throws Exception {
    JsonNode pending = create(token("alice"));
    assertStatus(call("POST", path(pending) + "/decision", token("bob"), decision(pending, "approve")), 200);
    memberships.replace(entries().stream().filter(e -> !e.verifiedSubjectId().equals(ISSUER + "#bob")).toList());
    assertStatus(call("POST", path(pending) + "/execute", token("alice"), hash(pending)), 403);
    assertThat(rules()).isZero();
  }

  @Test void policyChangeInvalidatesPreviouslyApprovedAction() throws Exception {
    JsonNode pending = create(token("alice"));
    assertStatus(call("POST", path(pending) + "/decision", token("bob"), decision(pending, "approve")), 200);
    ((ObjectNode) policy).put("version", 99);
    restart();
    assertStatus(call("POST", path(pending) + "/execute", token("alice"), hash(pending)), 409);
    assertThat(rules()).isZero();
  }

  @Test void subMicrosecondClockCannotBreakBindingAfterDatabaseRoundTrip() throws Exception {
    clock.now = clock.now.with(java.time.temporal.ChronoField.NANO_OF_SECOND, 123456789);
    JsonNode pending = create(token("alice"));
    restart();
    assertStatus(call("POST", path(pending) + "/decision", token("bob"), decision(pending, "approve")), 200);
    assertStatus(call("POST", path(pending) + "/execute", token("alice"), hash(pending)), 200);
    assertThat(rules()).isEqualTo(1);
  }

  @Test void concurrentExecutionHasOnlyOneSuccessfulConsumer() throws Exception {
    JsonNode pending = create(token("alice"));
    assertStatus(call("POST", path(pending) + "/decision", token("bob"), decision(pending, "approve")), 200);
    var requests = new ArrayList<CompletableFuture<Integer>>();
    String bearer = token("alice");
    for (int i = 0; i < 8; i++) requests.add(CompletableFuture.supplyAsync(() -> {
      try { return call("POST", path(pending) + "/execute", bearer, hash(pending)).statusCode(); }
      catch (Exception failure) { throw new IllegalStateException(failure); }
    }));
    List<Integer> statuses = requests.stream().map(CompletableFuture::join).toList();
    assertThat(statuses).filteredOn(s -> s == 200).hasSize(1);
    assertThat(statuses).allMatch(s -> s == 200 || s == 409);
    assertThat(state.readEvidence(TENANT).stream().filter(e -> e.payload().path("kind").asText().equals("execution.claimed")).count()).isEqualTo(1);
    assertThat(rules()).isEqualTo(1);
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "{}", "[]", "null", "{} {}", "{\"groupId\":\"sg-demo\",\"groupId\":\"sg-other\",\"port\":443,\"cidr\":\"192.0.2.0/24\"}",
      "{\"groupId\":\"sg-real\",\"port\":443,\"cidr\":\"192.0.2.0/24\"}",
      "{\"groupId\":\"sg-demo\",\"port\":0,\"cidr\":\"192.0.2.0/24\"}",
      "{\"groupId\":\"sg-demo\",\"port\":65536,\"cidr\":\"192.0.2.0/24\"}",
      "{\"groupId\":\"sg-demo\",\"port\":443.0,\"cidr\":\"192.0.2.0/24\"}",
      "{\"groupId\":\"sg-demo\",\"port\":443,\"cidr\":\"0.0.0.0/0\"}",
      "{\"groupId\":\"sg-demo\",\"port\":443,\"authenticated\":true}"
  })
  void invalidOrInjectedActionsAreRejected(String body) throws Exception {
    assertStatus(call("POST", "/api/v1/approvals", token("alice"), body), 400);
    assertThat(state.readEvidence(TENANT)).isEmpty();
    assertThat(rules()).isZero();
  }

  @Test void invalidTokensAreRejectedBeforeMalformedBodyParsing() throws Exception {
    assertStatus(call("POST", "/api/v1/approvals", null, "not-json"), 401);
    assertStatus(call("POST", "/api/v1/approvals", "garbage", "not-json"), 401);
    assertStatus(call("POST", "/api/v1/approvals", token("alice", c -> c.put("aud", "https://other.invalid")), ACTION), 401);
    assertStatus(call("POST", "/api/v1/approvals", token("alice", c -> c.put("exp", clock.instant().minusSeconds(1).getEpochSecond())), ACTION), 401);
    String token = token("alice");
    char changed = token.endsWith("A") ? 'B' : 'A';
    assertStatus(call("POST", "/api/v1/approvals", token.substring(0, token.length() - 1) + changed, ACTION), 401);
    assertStatus(call("POST", "/api/v1/approvals", token("alice", c -> c.putObject("cnf").put("x5t#S256", Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]))), ACTION), 401);
    assertThat(state.readEvidence(TENANT)).isEmpty();
  }

  @Test void browserOriginsCookiesAndUnsupportedRequestsFailClosed() throws Exception {
    assertStatus(call("POST", "/api/v1/approvals", token("alice"), ACTION, "Origin", "https://evil.invalid"), 403);
    assertStatus(call("POST", "/api/v1/approvals", token("alice"), ACTION, "Cookie", "session=forged"), 403);
    assertStatus(call("POST", "/api/v1/approvals", token("alice"), ACTION, "Content-Type", "text/plain"), 415);
    assertStatus(call("GET", "/api/v1/approvals?tenant=beta", token("alice"), null), 400);
    assertStatus(call("GET", "/api/v1/%61pprovals", token("alice"), null), 400);
    assertStatus(call("GET", "/no-route", token("alice"), null), 404);
    assertStatus(call("GET", "/api/v1/approvals/not-an-id", token("alice"), null), 404);
    assertStatus(call("PUT", "/api/v1/approvals", token("alice"), ACTION), 405);
    assertStatus(call("GET", "/api/v1/approvals", token("alice"), null, "Authorization", "Bearer " + token("bob")), 401);
    assertStatus(call("POST", "/api/v1/approvals", token("alice"), "x".repeat(65537)), 413);
    assertThat(rules()).isZero();
  }

  @Test void exactReviewRejectsWrongHashAndUnknownDecisionOrCallerFields() throws Exception {
    JsonNode pending = create(token("alice"));
    assertStatus(call("POST", path(pending) + "/decision", token("bob"), "{\"decision\":\"approve\",\"requestHash\":\"wrong\"}"), 409);
    assertStatus(call("POST", path(pending) + "/decision", token("bob"), decision(pending, "allow")), 400);
    assertStatus(call("POST", path(pending) + "/execute", token("alice"), hash(pending).replace("}", ",\"port\":8443}")), 400);
    assertStatus(call("GET", path(pending) + "/unknown", token("bob"), null), 405);
    assertThat(rules()).isZero();
  }

  @Test void snapshotsAreImmutableTenantScopedAndTransactionallyBound() throws Exception {
    JsonNode pending = create(token("alice"));
    String id = pending.path("approvalRequestId").asText();
    var approval = state.find(TENANT, id);
    JsonNode event = state.findAction(TENANT, id);
    assertThat(CanonicalJson.sha256(event)).isEqualTo(approval.binding().actionHash());
    assertThat(state.findAction(new TenantPartition("simulated-beta"), id)).isNull();
    ((ObjectNode) event.path("action").path("arguments")).put("port", 8443);
    assertThatThrownBy(() -> state.createPending(approval.binding(), event)).hasMessage("APPROVAL_BINDING_MISMATCH");
    assertThatThrownBy(() -> state.listApprovals(TENANT, null, 51)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> state.listApprovals(TENANT, "invalid#subject|", 1)).isInstanceOf(IllegalArgumentException.class);
    try (var connection = dataSource.getConnection(); var sql = connection.createStatement()) {
      assertThatThrownBy(() -> sql.executeUpdate("UPDATE tp_approval_action_snapshots SET canonical_action = '{}'::jsonb"))
          .isInstanceOf(java.sql.SQLException.class);
    }
    assertThat(json(call("GET", path(pending), token("alice"), null)).path("action").path("arguments").path("port").asInt()).isEqualTo(443);
  }

  @Test void snapshotInsertFailureRollsBackPendingApprovalAndEvidence() throws Exception {
    try (var connection = dataSource.getConnection(); var sql = connection.createStatement()) {
      sql.execute("""
          CREATE FUNCTION fail_snapshot_insert() RETURNS TRIGGER AS $$
          BEGIN RAISE EXCEPTION 'synthetic snapshot outage'; END;
          $$ LANGUAGE plpgsql
          """);
      sql.execute("""
          CREATE TRIGGER fail_snapshot BEFORE INSERT ON tp_approval_action_snapshots
          FOR EACH ROW EXECUTE FUNCTION fail_snapshot_insert()
          """);
    }
    assertStatus(call("POST", "/api/v1/approvals", token("alice"), ACTION), 503);
    assertThat(state.listApprovals(TENANT, null, 50)).isEmpty();
    assertThat(state.readEvidence(TENANT)).isEmpty();
    try (var connection = dataSource.getConnection(); var sql = connection.createStatement();
        var result = sql.executeQuery("SELECT count(*) FROM tp_approval_requests")) {
      assertThat(result.next()).isTrue();
      assertThat(result.getInt(1)).isZero();
    }
    assertThat(rules()).isZero();
  }

  @Test void unavailableStateAndUnenrollableReviewersFailClosed() throws Exception {
    assertThatThrownBy(() -> new ApprovalMemberships(List.of(entry("service", false, Set.of(ApprovalMemberships.Role.REVIEWER)))))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ApprovalMemberships(List.of(entries().getFirst(), entries().getFirst())))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> SimulatorApprovalServer.start(new InetSocketAddress("0.0.0.0", 0), verifier,
        memberships, state, policy, null, simulator, clock)).isInstanceOf(IllegalArgumentException.class);
    dataSource.setPortNumbers(new int[] {1});
    dataSource.setConnectTimeout(1);
    assertStatus(call("GET", "/api/v1/approvals", token("alice"), null), 503);
    assertThat(rules()).isZero();
  }

  @Test void mcpActorTenantAndPolicyCannotBeSubstitutedThroughEitherIngress() throws Exception {
    memberships.replace(entries().stream().map(entry -> new ApprovalMemberships.Entry(
        entry.verifiedSubjectId(), entry.clientId(), entry.tenant(), entry.human(),
        java.util.stream.Stream.concat(entry.roles().stream(), java.util.stream.Stream.of(ApprovalMemberships.Role.REQUESTER))
            .collect(java.util.stream.Collectors.toUnmodifiableSet()))).toList());
    var pending = mcp(token("alice"), ApprovalMcpAdapter.REQUEST, ACTION);
    String id = pending.path("approvalRequestId").asText();
    String resume = "{\"approvalRequestId\":\"" + id + "\",\"requestHash\":\"" + pending.path("requestHash").asText() + "\"}";
    assertThat(mcp(token("beta", c -> c.put("org_id", "beta")), ApprovalMcpAdapter.STATUS,
        "{\"approvalRequestId\":\"" + id + "\"}").path("errorCode").asText()).isEqualTo("APPROVAL_NOT_FOUND");
    assertThat(mcp(token("charlie"), ApprovalMcpAdapter.STATUS,
        "{\"approvalRequestId\":\"" + id + "\"}").path("errorCode").asText()).isEqualTo("APPROVAL_NOT_FOUND");
    assertStatus(call("POST", path(pending) + "/decision", token("bob"), decision(pending, "approve")), 200);
    String alternate = token("alice", c -> c.put("client_id", "alternate-client"));
    assertThat(mcp(alternate, ApprovalMcpAdapter.RESUME, resume).path("errorCode").asText()).isEqualTo("APPROVAL_BINDING_MISMATCH");
    assertStatus(call("POST", path(pending) + "/execute", alternate, hash(pending)), 409);
    ((ObjectNode) policy).put("version", 99); restart();
    assertThat(mcp(token("alice"), ApprovalMcpAdapter.RESUME, resume).path("errorCode").asText()).isEqualTo("APPROVAL_BINDING_MISMATCH");
    assertThat(rules()).isZero();
    var api = create(token("alice"));
    String apiResume = "{\"approvalRequestId\":\"" + api.path("approvalRequestId").asText()
        + "\",\"requestHash\":\"" + api.path("requestHash").asText() + "\"}";
    assertThat(mcp(token("alice"), ApprovalMcpAdapter.RESUME, apiResume).path("errorCode").asText()).isEqualTo("APPROVAL_BINDING_MISMATCH");
  }

  @Test void mcpCompletionOutageRemainsConsumedAndCannotDispatchAgainAfterRestart() throws Exception {
    var pending = mcp(token("alice"), ApprovalMcpAdapter.REQUEST, ACTION);
    assertStatus(call("POST", path(pending) + "/decision", token("bob"), decision(pending, "approve")), 200);
    try (var connection = dataSource.getConnection(); var sql = connection.createStatement()) {
      sql.execute("""
          CREATE FUNCTION fail_completion() RETURNS TRIGGER AS $$
          BEGIN IF NEW.sequence > 1 THEN RAISE EXCEPTION 'synthetic completion outage'; END IF;
          RETURN NEW; END; $$ LANGUAGE plpgsql
          """);
      sql.execute("CREATE TRIGGER fail_completion BEFORE INSERT ON tp_execution_transitions FOR EACH ROW EXECUTE FUNCTION fail_completion()");
    }
    String args = "{\"approvalRequestId\":\"" + pending.path("approvalRequestId").asText()
        + "\",\"requestHash\":\"" + pending.path("requestHash").asText() + "\"}";
    assertThat(mcp(token("alice"), ApprovalMcpAdapter.RESUME, args).path("errorCode").asText()).isEqualTo("SECURITY_STATE_UNAVAILABLE");
    assertThat(rules()).isEqualTo(1); // effect happened, but its success was not durably recorded
    restart();
    assertThat(mcp(token("alice"), ApprovalMcpAdapter.RESUME, args).path("errorCode").asText()).isEqualTo("APPROVAL_NOT_EXECUTABLE");
    var status = mcp(token("alice"), ApprovalMcpAdapter.STATUS,
        "{\"approvalRequestId\":\"" + pending.path("approvalRequestId").asText() + "\"}");
    assertThat(status.path("status").asText()).isEqualTo("consumed");
    assertThat(status.path("executionStatus").asText()).isEqualTo("executing");
    assertThat(rules()).isEqualTo(1);
  }

  @Test void mcpDatabaseOutageNeverCreatesAuthorityOrDispatches() throws Exception {
    dataSource.setPortNumbers(new int[] {1}); dataSource.setConnectTimeout(1);
    assertThat(mcp(token("alice"), ApprovalMcpAdapter.REQUEST, ACTION).path("errorCode").asText()).isEqualTo("SECURITY_STATE_UNAVAILABLE");
    assertThat(rules()).isZero();
  }

  @Test void durablePolicyGenerationBindingRejectsStaleApprovalsAcrossActivationAndRollback() throws Exception {
    var policyStore = new PostgresPolicyLifecycleStore(dataSource, clock, () -> "plt-" + UUID.randomUUID());
    String policyId = "pol-aws-web-001";
    ObjectNode policyV1 = ((ObjectNode) JsonSupport.read(ROOT.resolve("policies/aws-web-readonly.json"))).deepCopy();
    policyV1.put("version", 1);
    policyStore.stage(TENANT.value(), policyV1, clock.instant());
    policyStore.activate(TENANT.value(), policyId, 1, null, clock.instant(), null, "initial activation");

    // Restart server in durable mode
    if (server != null) server.close();
    state = new PostgresDurableEnforcementState(dataSource, clock, () -> UUID.randomUUID().toString());
    var permits = new ExecutionPermitAuthority(ExecutionPermitAuthority.DEFAULT_ISSUER,
        "fixture-ed25519", permitKey, 30, clock, () -> UUID.randomUUID().toString(),
        new PostgresPermitConsumptionStore(dataSource));
    server = SimulatorApprovalServer.start(new InetSocketAddress("127.0.0.1", 0), verifier,
        memberships, state, policyStore, TENANT.value(), policyId, permits, simulator, clock);

    String aliceToken = token("alice");
    JsonNode pending = create(aliceToken);
    String id = pending.path("approvalRequestId").asText();
    String path = path(pending);

    JsonNode mcpPending = mcp(aliceToken, ApprovalMcpAdapter.REQUEST, ACTION);
    String mcpId = mcpPending.path("approvalRequestId").asText();
    String mcpPath = path(mcpPending);
    assertThat(rules()).isZero();

    // Bob approves both
    assertStatus(call("POST", path + "/decision", token("bob"), decision(pending, "approve")), 200);
    assertStatus(call("POST", mcpPath + "/decision", token("bob"), decision(mcpPending, "approve")), 200);

    // Operator activates v2 (generation 2)
    ObjectNode policyV2 = policyV1.deepCopy();
    policyV2.put("version", 2);
    policyStore.stage(TENANT.value(), policyV2, clock.instant().plusSeconds(1));
    policyStore.activate(TENANT.value(), policyId, 2, 1, clock.instant().plusSeconds(2), null, "v2 activation");

    // Alice attempts HTTP execution: must fail with 409 STALE_POLICY_GENERATION
    var executeResponse = call("POST", path + "/execute", aliceToken, hash(pending));
    assertStatus(executeResponse, 409);
    assertThat(json(executeResponse).path("error").path("code").asText()).isEqualTo("STALE_POLICY_GENERATION");
    assertThat(rules()).isZero();

    // Alice attempts MCP resumption: must fail with tool error STALE_POLICY_GENERATION
    String mcpArgs = "{\"approvalRequestId\":\"" + mcpId + "\",\"requestHash\":\"" + mcpPending.path("requestHash").asText() + "\"}";
    JsonNode mcpResume = mcp(aliceToken, ApprovalMcpAdapter.RESUME, mcpArgs);
    assertThat(mcpResume.path("errorCode").asText()).isEqualTo("STALE_POLICY_GENERATION");
    assertThat(rules()).isZero();

    // Rollback to v1 (generation 3)
    policyStore.rollback(TENANT.value(), policyId, null, 1, clock.instant().plusSeconds(5), null, "rollback to v1");

    // Alice attempts execution again on original HTTP approval: STILL 409 STALE_POLICY_GENERATION
    var executeResponse2 = call("POST", path + "/execute", aliceToken, hash(pending));
    assertStatus(executeResponse2, 409);
    assertThat(json(executeResponse2).path("error").path("code").asText()).isEqualTo("STALE_POLICY_GENERATION");
    assertThat(rules()).isZero();

    // MCP resume call on original MCP approval STILL returns tool error STALE_POLICY_GENERATION
    JsonNode mcpResume2 = mcp(aliceToken, ApprovalMcpAdapter.RESUME, mcpArgs);
    assertThat(mcpResume2.path("errorCode").asText()).isEqualTo("STALE_POLICY_GENERATION");
    assertThat(rules()).isZero();

    // Fresh approval created under restored v1 (generation 3) succeeds
    JsonNode freshPending = create(aliceToken);
    String freshPath = path(freshPending);
    assertStatus(call("POST", freshPath + "/decision", token("bob"), decision(freshPending, "approve")), 200);
    JsonNode freshExecuted = json(call("POST", freshPath + "/execute", aliceToken, hash(freshPending)));
    assertThat(freshExecuted.path("executionStatus").asText()).isEqualTo("succeeded");
    assertThat(freshExecuted.path("status").asText()).isEqualTo("consumed");
    assertThat(rules()).isEqualTo(1);
  }

  @Test void durablePolicyModeFailsClosedWhenActivePolicyIsUnavailable() throws Exception {
    var policyStore = new PostgresPolicyLifecycleStore(dataSource, clock, () -> "plt-" + UUID.randomUUID());
    if (server != null) server.close();
    state = new PostgresDurableEnforcementState(dataSource, clock, () -> UUID.randomUUID().toString());
    var permits = new ExecutionPermitAuthority(ExecutionPermitAuthority.DEFAULT_ISSUER,
        "fixture-ed25519", permitKey, 30, clock, () -> UUID.randomUUID().toString(),
        new PostgresPermitConsumptionStore(dataSource));
    server = SimulatorApprovalServer.start(new InetSocketAddress("127.0.0.1", 0), verifier,
        memberships, state, policyStore, TENANT.value(), "pol-nonexistent", permits, simulator, clock);

    // Requesting approval fails closed with 503 SECURITY_STATE_UNAVAILABLE
    var response = call("POST", "/api/v1/approvals", token("alice"), ACTION);
    assertStatus(response, 503);
    assertThat(json(response).path("error").path("code").asText()).isEqualTo("SECURITY_STATE_UNAVAILABLE");
    assertThat(rules()).isZero();
  }

  private JsonNode mcp(String bearer, String name, String arguments) throws Exception {
    var response = call("POST", "/mcp", bearer,
        "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"" + name + "\",\"arguments\":" + arguments + "}}");
    assertStatus(response, 200);
    return json(response).path("result").path("structuredContent");
  }

  private int rules() { return simulator.snapshot().path("aws").path("securityGroups").path("sg-demo").size(); }
  private JsonNode create(String bearer) throws Exception {
    var response = call("POST", "/api/v1/approvals", bearer, ACTION);
    assertStatus(response, 201);
    return json(response);
  }
  private static String path(JsonNode pending) { return "/api/v1/approvals/" + pending.path("approvalRequestId").asText(); }
  private static String hash(JsonNode pending) { return "{\"requestHash\":\"" + pending.path("requestHash").asText() + "\"}"; }
  private static String decision(JsonNode pending, String decision) {
    return hash(pending).replace("}", ",\"decision\":\"" + decision + "\"}");
  }
  private static JsonNode json(HttpResponse<String> response) throws Exception { return JsonSupport.MAPPER.readTree(response.body()); }
  private static void assertStatus(HttpResponse<String> response, int status) { assertThat(response.statusCode()).describedAs(response.body()).isEqualTo(status); }
  private HttpResponse<String> call(String method, String path, String token, String body, String... headers) throws Exception {
    var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path))
        .timeout(Duration.ofSeconds(15));
    if (token != null) request.header("Authorization", "Bearer " + token);
    if (body != null) request.header("Content-Type", "application/json");
    for (int i = 0; i < headers.length; i += 2) request.header(headers[i], headers[i + 1]);
    request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
    return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
  }
  private String token(String subject) throws Exception { return token(subject, c -> {}); }
  private String token(String subject, Consumer<ObjectNode> mutate) throws Exception {
    ObjectNode header = JsonSupport.MAPPER.createObjectNode().put("alg", "RS256").put("typ", "at+jwt").put("kid", "fixture-key");
    ObjectNode claims = JsonSupport.MAPPER.createObjectNode().put("iss", ISSUER).put("aud", AUDIENCE)
        .put("sub", subject).put("client_id", "approval-client").put("org_id", "alpha")
        .put("scope", SCOPES).put("iat", clock.instant().minusSeconds(10).getEpochSecond())
        .put("exp", clock.instant().plusSeconds(290).getEpochSecond()).put("jti", UUID.randomUUID().toString());
    mutate.accept(claims);
    String input = encode(header) + "." + encode(claims);
    Signature signer = Signature.getInstance("SHA256withRSA");
    signer.initSign(rsa.getPrivate());
    signer.update(input.getBytes(StandardCharsets.UTF_8));
    return input + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(signer.sign());
  }
  private static String encode(JsonNode value) throws Exception { return Base64.getUrlEncoder().withoutPadding().encodeToString(JsonSupport.MAPPER.writeValueAsBytes(value)); }
  private static List<ApprovalMemberships.Entry> entries() {
    var result = new ArrayList<>(List.of(
        entry("alice", true, Set.of(ApprovalMemberships.Role.REQUESTER, ApprovalMemberships.Role.REVIEWER)),
        entry("bob", true, Set.of(ApprovalMemberships.Role.REVIEWER, ApprovalMemberships.Role.REQUESTER)),
        entry("charlie", true, Set.of(ApprovalMemberships.Role.REQUESTER)),
        entry("service", false, Set.of(ApprovalMemberships.Role.REQUESTER))));
    result.add(new ApprovalMemberships.Entry(ISSUER + "#alice", "alternate-client", TENANT, true, Set.of(ApprovalMemberships.Role.REVIEWER)));
    result.add(new ApprovalMemberships.Entry(ISSUER + "#beta", "approval-client", new TenantPartition("simulated-beta"), true, Set.of(ApprovalMemberships.Role.REVIEWER)));
    return result;
  }
  private static ApprovalMemberships.Entry entry(String subject, boolean human, Set<ApprovalMemberships.Role> roles) {
    return new ApprovalMemberships.Entry(ISSUER + "#" + subject, "approval-client", TENANT, human, roles);
  }
  private static final class MutableClock extends Clock {
    private volatile Instant now = Instant.parse("2030-01-01T00:00:00Z");
    void advance(long seconds) { now = now.plusSeconds(seconds); }
    @Override public ZoneId getZone() { return ZoneOffset.UTC; }
    @Override public Clock withZone(ZoneId zone) { return this; }
    @Override public Instant instant() { return now; }
  }
}
