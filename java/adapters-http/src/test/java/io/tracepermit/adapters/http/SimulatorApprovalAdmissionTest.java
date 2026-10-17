package io.tracepermit.adapters.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import io.tracepermit.security.ExecutionPermitAuthority;
import io.tracepermit.simulator.StatefulTargetSimulator;
import java.io.IOException;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Bounded admission qualification for the simulator approval foundation.
 * Saturation must reject before authentication, body parsing, database work,
 * or target dispatch; capacity must be released exactly once on every failure
 * path; close must return within a documented bound even with a blocked
 * admitted handler. Rejections are bounded local resource control on the
 * loopback foundation, not public abuse prevention, and they never authorize
 * an effect or carry credentials.
 */
final class SimulatorApprovalAdmissionTest {
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
  private final AtomicInteger parkCalls = new AtomicInteger();
  private final AtomicInteger normalCalls = new AtomicInteger();
  private final AtomicReference<CountDownLatch> parkSignal = new AtomicReference<>();
  private final AtomicReference<CountDownLatch> parkGate = new AtomicReference<>();
  private volatile Thread parkedLane;
  private CountingDataSource dataSource;
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
    dataSource = new CountingDataSource();
    dataSource.setUrl(postgres.getJdbcUrl());
    dataSource.setUser(postgres.getUsername());
    dataSource.setPassword(postgres.getPassword());
    String schema = "approval_admission_" + SCHEMA.incrementAndGet();
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
          if ("park-key".equals(keyId)) {
            parkCalls.incrementAndGet();
            parkedLane = Thread.currentThread();
            CountDownLatch signal = parkSignal.get();
            if (signal != null) {
              signal.countDown();
              try {
                parkGate.get().await();
              } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AuthenticationException("ADMISSION_SHUTDOWN", "Interrupted while parked.");
              }
            }
          } else normalCalls.incrementAndGet();
          if ("park-key".equals(keyId) || "fixture-key".equals(keyId)) return (RSAPublicKey) rsa.getPublic();
          throw new AuthenticationException("INVALID_KEY", "Invalid key.");
        }, clock);
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

  private void restart(int activeLanes, int pendingBound) throws Exception {
    if (server != null) server.close();
    state = new PostgresDurableEnforcementState(dataSource, clock, () -> UUID.randomUUID().toString());
    var permits = new ExecutionPermitAuthority(ExecutionPermitAuthority.DEFAULT_ISSUER,
        "fixture-ed25519", permitKey, 30, clock, () -> UUID.randomUUID().toString(),
        new PostgresPermitConsumptionStore(dataSource));
    server = SimulatorApprovalServer.start(new InetSocketAddress("127.0.0.1", 0), verifier,
        memberships, state, policy, permits, simulator, clock, activeLanes, pendingBound);
  }

  @AfterEach void stop() { if (server != null) server.close(); server = null; }

  @Test void saturatedAdmissionQueuesBoundedAndRejectsOverflowWithoutExpensiveWork() throws Exception {
    restart(2, 2);
    parkSignal.set(new CountDownLatch(2));
    parkGate.set(new CountDownLatch(1));
    int baselineConnections = dataSource.connections.get();
    var parkedFirst = CompletableFuture.supplyAsync(() -> {
      try { return call("POST", "/api/v1/approvals", token("alice", "park-key"), ACTION); }
      catch (Exception failure) { throw new IllegalStateException(failure); }
    });
    var parkedSecond = CompletableFuture.supplyAsync(() -> {
      try { return call("POST", "/api/v1/approvals", token("bob", "park-key"), ACTION); }
      catch (Exception failure) { throw new IllegalStateException(failure); }
    });
    assertThat(parkSignal.get().await(15, TimeUnit.SECONDS))
        .as("both requests must park inside authentication while holding both lanes").isTrue();
    assertThat(dataSource.connections.get())
        .as("parked requests have not reached the database yet").isEqualTo(baselineConnections);
    assertThat(parkCalls.get()).isEqualTo(2);
    assertThat(normalCalls.get()).isZero();

    // Five more exchanges arrive while both lanes stay parked. Exactly the pending
    // bound (2) waits in the bounded queue; the other 3 are rejected by the executor
    // before any handler runs, which the JDK server surfaces as a closed connection.
    var overflow = new ArrayList<CompletableFuture<Object>>();
    for (int i = 0; i < 5; i++) {
      overflow.add(CompletableFuture.supplyAsync(() -> {
        try { return call("POST", "/api/v1/approvals", token("alice"), ACTION); }
        catch (Exception failure) { return failure; }
      }));
    }
    int rejected = 0;
    int queued = 0;
    for (var offer : overflow) {
      try {
        assertThat(offer.get(5, TimeUnit.SECONDS))
            .as("a rejected offer must fail its connection without being served")
            .isInstanceOf(IOException.class);
        rejected++;
      } catch (TimeoutException waiting) {
        queued++;
      }
    }
    assertThat(rejected)
        .as("exactly the offers beyond lanes plus pending bound are rejected").isEqualTo(3);
    assertThat(queued).as("exactly the pending bound of exchanges wait in the queue").isEqualTo(2);
    assertThat(parkCalls.get())
        .as("queued exchanges must not start authentication while parked").isEqualTo(2);
    assertThat(normalCalls.get())
        .as("neither queued nor rejected exchanges may reach key resolution").isZero();
    assertThat(dataSource.connections.get())
        .as("no offer may touch the database while the foundation is saturated")
        .isEqualTo(baselineConnections);
    assertThat(rules()).isZero();

    parkGate.get().countDown();
    assertStatus(parkedFirst.get(15, TimeUnit.SECONDS), 201);
    assertStatus(parkedSecond.get(15, TimeUnit.SECONDS), 201);
    int served = 0;
    for (var offer : overflow) {
      if (!(offer.get(15, TimeUnit.SECONDS) instanceof Exception)) served++;
    }
    assertThat(served).as("the queued exchanges are served once a lane frees").isEqualTo(2);
    assertThat(dataSource.connections.get())
        .as("served exchanges perform their database work after admission")
        .isGreaterThan(baselineConnections);
    assertThat(rules()).isZero();
  }

  @Test void dispatchPoolHoldsExactLaneAndQueueBounds() throws Exception {
    ApprovalAdmissionExecutors.validate(1, 1);
    ApprovalAdmissionExecutors.validate(64, 1024);
    assertThatThrownBy(() -> ApprovalAdmissionExecutors.validate(0, 4)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> ApprovalAdmissionExecutors.validate(65, 4)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> ApprovalAdmissionExecutors.validate(1, 0)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> ApprovalAdmissionExecutors.validate(1, 1025)).isInstanceOf(IllegalArgumentException.class);

    ExecutorService pool = ApprovalAdmissionExecutors.newDispatchPool(1, 2);
    var release = new CountDownLatch(1);
    var firstStarted = new CountDownLatch(1);
    var laneSeen = new AtomicReference<Thread>();
    AtomicInteger completed = new AtomicInteger();
    Runnable parkedTask = () -> {
      try {
        release.await();
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
      }
      completed.incrementAndGet();
    };
    pool.submit(() -> {
      laneSeen.set(Thread.currentThread());
      firstStarted.countDown();
      parkedTask.run();
    });
    assertThat(firstStarted.await(15, TimeUnit.SECONDS)).isTrue();
    pool.submit(parkedTask);
    pool.submit(parkedTask);
    assertThat(((ThreadPoolExecutor) pool).getQueue().size())
        .as("exactly the pending bound of tasks is queued").isEqualTo(2);
    assertThatThrownBy(() -> pool.submit(() -> {}))
        .as("an offer beyond the pending bound must be rejected before any handler runs")
        .isInstanceOf(RejectedExecutionException.class);
    assertThat(((ThreadPoolExecutor) pool).getMaximumPoolSize())
        .as("active work is exactly the lane count").isEqualTo(1);
    assertThat(((ThreadPoolExecutor) pool).getActiveCount()).isEqualTo(1);
    assertThat(laneSeen.get().isDaemon()).as("dispatch lanes must be daemon threads").isTrue();
    assertThat(laneSeen.get().getName()).startsWith("approval-dispatch-");
    release.countDown();
    pool.shutdown();
    assertThat(pool.awaitTermination(15, TimeUnit.SECONDS)).isTrue();
    assertThat(completed.get()).as("every accepted task completed exactly once").isEqualTo(3);
  }

  @Test void everyFailurePathReturnsTheLaneToService() throws Exception {
    restart(1, 4);
    assertStatus(call("POST", "/api/v1/approvals", null, ACTION), 401);
    assertStatus(post(token("alice")), 201);
    assertStatus(call("POST", "/api/v1/approvals", "garbage", ACTION), 401);
    assertStatus(post(token("alice")), 201);
    assertStatus(call("POST", "/api/v1/approvals", token("alice"), "not-json"), 400);
    assertStatus(post(token("alice")), 201);
    assertStatus(call("POST", "/api/v1/approvals", token("alice"), ACTION, "Origin", "https://evil.invalid"), 403);
    assertStatus(post(token("alice")), 201);
    assertStatus(call("GET", "/no-route", token("alice"), null), 404);
    assertStatus(post(token("alice")), 201);
    assertStatus(call("PUT", "/api/v1/approvals", token("alice"), ACTION), 405);
    assertStatus(post(token("alice")), 201);
    assertStatus(call("POST", "/api/v1/approvals", token("alice"), "x".repeat(65537)), 413);
    assertStatus(post(token("alice")), 201);

    dataSource.setPortNumbers(new int[] {1});
    dataSource.setConnectTimeout(1);
    assertStatus(call("POST", "/api/v1/approvals", token("alice"), ACTION), 503);
    dataSource.setPortNumbers(new int[] {postgres.getFirstMappedPort()});
    dataSource.setConnectTimeout(5);
    assertStatus(post(token("alice")), 201);
    assertThat(rules()).isZero();
  }

  @Test void repeatedCyclesDoNotLeakAdmissionSlots() throws Exception {
    restart(1, 2);
    for (int cycle = 0; cycle < 25; cycle++) {
      JsonNode pending = create(token("alice"));
      assertThat(pending.path("status").asText()).isEqualTo("pending");
      if (cycle % 5 == 4) {
        assertStatus(call("POST", path(pending) + "/decision", token("bob"), decision(pending, "deny")), 200);
      }
    }
    assertThat(json(call("GET", "/api/v1/approvals?limit=50", token("alice"), null)).path("items").size())
        .isEqualTo(25);
    assertThat(rules()).isZero();
  }

  @Test void closeIsBoundedEvenWithABlockedAdmittedHandler() throws Exception {
    restart(1, 4);
    parkSignal.set(new CountDownLatch(1));
    parkGate.set(new CountDownLatch(1));
    var parked = CompletableFuture.supplyAsync(() -> {
      try { return call("POST", "/api/v1/approvals", token("alice", "park-key"), ACTION); }
      catch (Exception failure) { throw new IllegalStateException(failure); }
    });
    assertThat(parkSignal.get().await(15, TimeUnit.SECONDS)).isTrue();
    Thread lane = parkedLane;
    assertThat(lane).isNotNull();

    long startedAt = System.nanoTime();
    server.close();
    long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000;
    assertThat(elapsedMillis)
        .as("close must return within the documented bound even while a handler is blocked")
        .isLessThan(10_000);
    server.close();

    parkGate.get().countDown();
    lane.join(15_000);
    assertThat(lane.isAlive())
        .as("the interrupted handler thread must unwind after close returns").isFalse();
    assertThat(lane.isDaemon()).isTrue();
    // Close tears down the in-flight connection: the parked client observes connection
    // teardown (never a fabricated status), and its call completes instead of hanging.
    assertThatThrownBy(() -> parked.get(15, TimeUnit.SECONDS))
        .as("the parked client call must complete with connection teardown, not hang")
        .isInstanceOf(ExecutionException.class);
    assertThat(rules()).isZero();
  }

  @Test void concurrentCloseAndSubmissionsStayBounded() throws Exception {
    restart(2, 2);
    var requests = new ArrayList<CompletableFuture<Object>>();
    for (int i = 0; i < 8; i++) {
      requests.add(CompletableFuture.supplyAsync(() -> {
        try {
          return call("POST", "/api/v1/approvals", token("alice"), ACTION);
        } catch (Exception refused) {
          return refused;
        }
      }));
    }
    server.close();
    for (var request : requests) {
      assertThat(request.get(20, TimeUnit.SECONDS))
          .as("every concurrent submission completes instead of hanging across close")
          .isNotNull();
    }
    assertThatThrownBy(() -> call("POST", "/api/v1/approvals", token("alice"), ACTION))
        .as("a closed foundation must refuse new connections instead of accepting work")
        .isInstanceOf(IOException.class);
  }

  @Test void defaultCapacityPreservesNormalReviewExecuteAndReplayFlows() throws Exception {
    restart();

    String bearer = token("alice");
    JsonNode pending = create(bearer);
    String path = path(pending);
    assertStatus(call("POST", path + "/decision", token("bob"), decision(pending, "approve")), 200);
    JsonNode executed = json(call("POST", path + "/execute", bearer, hash(pending)));
    assertThat(executed.path("executionStatus").asText()).isEqualTo("succeeded");
    HttpResponse<String> replay = call("POST", path + "/execute", bearer, hash(pending));
    assertStatus(replay, 409);
    assertThat(json(replay).path("error").path("code").asText()).isEqualTo("APPROVAL_NOT_EXECUTABLE");
    assertThat(json(call("GET", "/api/v1/approvals?limit=50", bearer, null)).path("items").size()).isEqualTo(1);
    assertThat(rules()).isEqualTo(1);
  }

  @Test void invalidCapacityArgumentsAreRejectedBeforeAnyServerStarts() {
    assertThatThrownBy(() -> SimulatorApprovalServer.start(new InetSocketAddress("127.0.0.1", 0),
        null, null, null, policy, null, null, clock, 0, 4))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> SimulatorApprovalServer.start(new InetSocketAddress("127.0.0.1", 0),
        null, null, null, policy, null, null, clock, 65, 4))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> SimulatorApprovalServer.start(new InetSocketAddress("127.0.0.1", 0),
        null, null, null, policy, null, null, clock, 1, 0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> SimulatorApprovalServer.start(new InetSocketAddress("127.0.0.1", 0),
        null, null, null, policy, null, null, clock, 1, 1025))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(server).isNull();
  }

  private int rules() { return simulator.snapshot().path("aws").path("securityGroups").path("sg-demo").size(); }
  private JsonNode create(String bearer) throws Exception {
    return json(assertStatus(post(bearer), 201));
  }
  private HttpResponse<String> post(String bearer) throws Exception {
    return call("POST", "/api/v1/approvals", bearer, ACTION);
  }
  private static String path(JsonNode pending) { return "/api/v1/approvals/" + pending.path("approvalRequestId").asText(); }
  private static String hash(JsonNode pending) { return "{\"requestHash\":\"" + pending.path("requestHash").asText() + "\"}"; }
  private static String decision(JsonNode pending, String decision) {
    return hash(pending).replace("}", ",\"decision\":\"" + decision + "\"}");
  }
  private static JsonNode json(HttpResponse<String> response) throws Exception { return JsonSupport.MAPPER.readTree(response.body()); }
  private static HttpResponse<String> assertStatus(HttpResponse<String> response, int status) {
    assertThat(response.statusCode()).describedAs(response.body()).isEqualTo(status);
    return response;
  }
  private HttpResponse<String> call(String method, String path, String token, String body, String... headers) throws Exception {
    var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path))
        .timeout(Duration.ofSeconds(15));
    if (token != null) request.header("Authorization", "Bearer " + token);
    if (body != null) request.header("Content-Type", "application/json");
    for (int i = 0; i < headers.length; i += 2) request.header(headers[i], headers[i + 1]);
    request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
    return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
  }
  private String token(String subject) throws Exception { return token(subject, "fixture-key", c -> {}); }
  private String token(String subject, String keyId) throws Exception { return token(subject, keyId, c -> {}); }
  private String token(String subject, Consumer<ObjectNode> mutate) throws Exception { return token(subject, "fixture-key", mutate); }
  private String token(String subject, String keyId, Consumer<ObjectNode> mutate) throws Exception {
    ObjectNode header = JsonSupport.MAPPER.createObjectNode().put("alg", "RS256").put("typ", "at+jwt").put("kid", keyId);
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
    return List.of(
        entry("alice", true, Set.of(ApprovalMemberships.Role.REQUESTER, ApprovalMemberships.Role.REVIEWER)),
        entry("bob", true, Set.of(ApprovalMemberships.Role.REVIEWER, ApprovalMemberships.Role.REQUESTER)));
  }
  private static ApprovalMemberships.Entry entry(String subject, boolean human, Set<ApprovalMemberships.Role> roles) {
    return new ApprovalMemberships.Entry(ISSUER + "#" + subject, "approval-client", TENANT, human, roles);
  }

  /** Counts physical connection attempts so rejected requests can prove zero database activity. */
  private static final class CountingDataSource extends PGSimpleDataSource {
    private static final long serialVersionUID = 1L;
    final AtomicInteger connections = new AtomicInteger();
    @Override public java.sql.Connection getConnection() throws java.sql.SQLException {
      connections.incrementAndGet();
      return super.getConnection();
    }
    @Override public java.sql.Connection getConnection(String user, String password) throws java.sql.SQLException {
      connections.incrementAndGet();
      return super.getConnection(user, password);
    }
  }

  private static final class MutableClock extends Clock {
    private volatile Instant now = Instant.parse("2030-01-01T00:00:00Z");
    @Override public ZoneId getZone() { return ZoneOffset.UTC; }
    @Override public Clock withZone(ZoneId zone) { return this; }
    @Override public Instant instant() { return now; }
  }
}
