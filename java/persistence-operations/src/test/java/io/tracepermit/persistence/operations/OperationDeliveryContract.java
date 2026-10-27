package io.tracepermit.persistence.operations;

import static io.tracepermit.application.BusinessOperationStorageException.Code.*;
import static org.assertj.core.api.Assertions.*;

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
import java.io.PrintWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.sql.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.containers.JdbcDatabaseContainer;

/** Real restricted JDBC, no network target or credential acquisition. Every target is deterministic. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Timeout(120)
abstract class OperationDeliveryContract {
  private static final String HASH = "sha256:" + "a".repeat(64);
  private static final BusinessOperationScope SCOPE = new BusinessOperationScope("synthetic-tenant", "synthetic-github-sandbox", "principal:synthetic-requester");
  private static final BusinessOperationTransitionPlanner PLANNER = new BusinessOperationTransitionPlanner();
  private JdbcDatabaseContainer<?> database;
  private Source owner, runtime;
  private KeyPair keys;
  private JsonNode operatorPolicy;
  private BusinessOperationAuthorityProfile profile;
  private GitHubBusinessOperationBinding.ExpectedProfile expected;
  private JsonNode initial, claimed;
  private String permit;
  private SyntheticGitHubDeliveryTarget target;
  private String probePhase = "none";
  private boolean probeQualified;
  private String probeRefusal = "not_observed";
  abstract JdbcDatabaseContainer<?> container();

  @BeforeAll void start() throws Exception {
    database = container().withPassword(UUID.randomUUID().toString());
    database.withCreateContainerCmdModifier(command -> command.getHostConfig().withPortBindings(database.getExposedPorts().stream()
        .map(port -> new PortBinding(Ports.Binding.bindIp("127.0.0.1"), new ExposedPort(port))).toArray(PortBinding[]::new)));
    database.start();
    assertThat(database.getContainerInfo().getNetworkSettings().getPorts().getBindings().values()).allSatisfy(values ->
        assertThat(values).hasSize(1).allSatisfy(value -> assertThat(value.getHostIp()).isEqualTo("127.0.0.1")));
    owner = new Source(database.getUsername(), database.getPassword());
    OperationDatabase.initializeDeliveryEmpty(owner);
    String password = UUID.randomUUID().toString();
    var admin = pg() ? owner : new Source("root", database.getPassword());
    try (var connection = admin.getConnection(); var statement = connection.createStatement()) {
      statement.setQueryTimeout(5);
      String user = pg() ? "tp_delivery_runtime" : "'tp_delivery_runtime'@'%'";
      statement.execute("CREATE USER " + user + (pg() ? " PASSWORD '" : " IDENTIFIED BY '") + password + "'");
      if (pg()) statement.execute("GRANT USAGE ON SCHEMA public TO " + user);
      String prefix = pg() ? "" : "`" + database.getDatabaseName() + "`.";
      for (String table : tables()) statement.execute("GRANT SELECT ON " + prefix + table + " TO " + user);
      for (String table : List.of("tp_operation_lifecycle_facts", "tp_operation_review_bindings", "tp_operation_admissions", "tp_operation_admission_outbox", "tp_operation_delivery_events")) {
        statement.execute("GRANT INSERT ON " + prefix + table + " TO " + user);
      }
      statement.execute("GRANT UPDATE(snapshot_hash, snapshot_json) ON " + prefix + "tp_operation_lifecycle_heads TO " + user);
      if (pg()) statement.execute("GRANT UPDATE(lock_marker) ON tp_operation_authority_controls TO " + user);
    }
    runtime = new Source("tp_delivery_runtime", password);
  }
  @AfterAll void stop() { if (database != null) database.stop(); }
  @BeforeEach void reset() throws Exception {
    owner.fault = "none"; runtime.fault = "none"; runtime.watch = "none"; runtime.trace.clear(); runtime.categories.clear(); runtime.waitRows = -1;
    probePhase = "none"; probeQualified = false; probeRefusal = "not_observed";
    for (String table : List.of("tp_operation_delivery_events", "tp_operation_admission_outbox", "tp_operation_admissions", "tp_operation_review_bindings",
        "tp_operation_lifecycle_facts", "tp_operation_lifecycle_heads", "tp_business_operation_facts", "tp_business_operations", "tp_operation_authority_controls")) sql("DELETE FROM " + table);
    keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    ObjectNode policy = (ObjectNode) JsonSupport.MAPPER.readTree("""
        {"schemaVersion":"1.0","id":"synthetic-github-draft-pr","version":1,"name":"Synthetic",
        "description":"Synthetic only","enabled":true,"defaultDecision":{"outcome":"block","reasonCode":"SYNTHETIC_DENY"},
        "rules":[{"id":"exact-draft-pr","priority":1,"description":"Synthetic","match":{"toolNames":["github.pull_request.create"]},
        "decision":{"outcome":"require_approval","reasonCode":"SYNTHETIC_REVIEW","approvalGroup":"synthetic-reviewers"}}]}
        """);
    operatorPolicy = policy.deepCopy();
    expected = new GitHubBusinessOperationBinding.ExpectedProfile("github-draft-pr-v1", "test", 1,
        new GitHubBusinessOperationBinding.PolicyRelease("synthetic-github-draft-pr", 1, CanonicalJson.sha256(policy)), Map.of("policy_bundle", HASH, "adapter_release", HASH),
        new GitHubPilotTargetBinding(71, 101, 201, "tracepermit/agentcore-pr-pilot", "synthetic-change", "main", "a".repeat(40), "b".repeat(40)));
    initial = operation();
    profile = new BusinessOperationAuthorityProfile(SCOPE, expected.adapterId(), 1, policy,
        CanonicalJson.sha256(initial.path("artifactDigests")), initial.path("resourceBindingHash").asText(),
        new BusinessOperationReviewSettings("synthetic-reviewers", HASH, Duration.ofMinutes(2)),
        new BusinessOperationAdmissionSettings("https://synthetic-issuer.invalid", "synthetic-key", "synthetic-admission", keys.getPublic(), Duration.ofSeconds(60)));
    OperationAuthorityAdministration.stageNew(owner, profile); OperationAuthorityAdministration.activateStaged(owner, profile);
    new JdbcBusinessOperationRepository(owner).createOrGet(SCOPE, initial);
    target = new SyntheticGitHubDeliveryTarget(expected, SyntheticGitHubDeliveryTarget.Outcome.CONFIRMED_SUCCESS);
    claimed = null; permit = null;
  }

  @AfterEach void retainSafeWaitProbe() throws Exception {
    String directory = System.getenv("TRACEPERMIT_DELIVERY_EVIDENCE_DIR");
    if (directory == null || probePhase.equals("none")) return;
    Path root = Path.of(directory); java.nio.file.Files.createDirectories(root);
    ObjectNode receipt = JsonSupport.MAPPER.createObjectNode().put("engine", database.getDockerImageName())
        .put("phase", probePhase).put("qualified", probeQualified).put("pid", runtime.pid.get()).put("waitRows", runtime.waitRows)
        .put("refusalCode", probeRefusal);
    var trace = receipt.putArray("monotonicPhaseTrace"); runtime.trace.forEach(trace::add);
    java.nio.file.Files.writeString(root.resolve("wait-" + database.getDockerImageName().replaceAll("[^A-Za-z0-9]", "_")
        + "-" + probePhase + "-" + UUID.randomUUID() + ".json"), receipt.toString());
  }

  @ParameterizedTest @ValueSource(strings = {"CONFIRMED_SUCCESS", "CONFIRMED_REJECTION", "UNKNOWN"})
  void positiveLifecycleRecordsDistinctOutcomesAndReadOnlyReconstruction(String scenario) throws Exception {
    target = new SyntheticGitHubDeliveryTarget(expected, SyntheticGitHubDeliveryTarget.Outcome.valueOf(scenario));
    admit(); var repository = repository(); var command = execution(claimed, fresh());
    var lease = repository.claim(command, permit);
    Instant claimAt = event(1).at();
    assertThat(lease.expiresAt()).isEqualTo(claimAt.plusSeconds(30)); assertThat(lease.fence()).isEqualTo(1);
    assertThat(repository.dispatch(lease, command, permit)).isEqualTo(target.observation(id()));
    JsonNode head = current();
    assertThat(head.path("execution").path("status").asText()).isEqualTo(target.observation(id()));
    assertThat(PLANNER.observe(SCOPE, head).lastSequence()).isEqualTo(8);
    assertThat(target.invocations(id())).isEqualTo(1); assertThat(count("tp_operation_delivery_events")).isEqualTo(3);
    for (int n = 0; n < 3; n++) {
      var read = repository().reconcile(SCOPE, id());
      assertThat(read.dispatchStarted()).isTrue(); assertThat(read.durableState()).isEqualTo(target.observation(id()));
      assertThat(read.syntheticInvocations()).isEqualTo(1);
    }
    assertThat(count("tp_operation_delivery_events")).isEqualTo(3);
    assertCode(() -> repository().claim(execution(head, fresh()), permit), OPERATION_DISPATCH_ALREADY_STARTED);
    assertThat(target.invocations(id())).isEqualTo(1);
    try (var connection = owner.getConnection(); var statement = connection.createStatement();
        var rows = statement.executeQuery("SELECT event_json FROM tp_operation_delivery_events")) {
      while (rows.next()) assertThat(rows.getString(1).contains(permit)).isFalse();
    }
  }

  @Test void thirtyTwoIndependentOwnersMakeOneClaimAndExactlyOneSyntheticInvocation() throws Exception {
    List<JdbcGitHubOperationDeliveryRepository> owners = new ArrayList<>();
    for (int n = 0; n < 32; n++) owners.add(repository()); // All validation precedes the signed lifetime.
    admit(); var command = execution(claimed, fresh());
    var ready = new CountDownLatch(32); var release = new CountDownLatch(1); var pool = Executors.newFixedThreadPool(32);
    try {
      List<Future<String>> futures = new ArrayList<>();
      for (var repository : owners) futures.add(pool.submit(() -> {
        ready.countDown(); assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
        try { var lease = repository.claim(command, permit); repository.dispatch(lease, command, permit); return "OK"; }
        catch (BusinessOperationStorageException fixed) {
          return fixed.code().name();
        }
      }));
      assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue(); release.countDown();
      List<String> outcomes = new ArrayList<>();
      for (var future : futures) outcomes.add(future.get(60, TimeUnit.SECONDS));
      int eventCount = count("tp_operation_delivery_events");
      String evidenceDirectory = System.getenv("TRACEPERMIT_DELIVERY_EVIDENCE_DIR");
      if (evidenceDirectory != null) {
        ObjectNode receipt = JsonSupport.MAPPER.createObjectNode().put("engine", database.getDockerImageName())
            .put("contenders", 32).put("winners", outcomes.stream().filter("OK"::equals).count())
            .put("targetInvocations", target.invocations(id())).put("eventRows", eventCount);
        ObjectNode counts = receipt.putObject("outcomeCounts");
        outcomes.stream().distinct().sorted().forEach(value -> counts.put(value, outcomes.stream().filter(value::equals).count()));
        var categories = receipt.putArray("sqlCategories"); runtime.categories.stream().sorted().forEach(categories::add);
        writeSafeReceipt("contention", receipt);
      }
      org.assertj.core.api.SoftAssertions.assertSoftly(check -> {
        check.assertThat(outcomes.stream().filter("OK"::equals).count()).as("SQL categories=%s; outcomes=%s", runtime.categories, outcomes).isEqualTo(1);
        check.assertThat(outcomes).as("SQL categories=%s", runtime.categories)
            .allSatisfy(value -> assertThat(value).isIn("OK", OPERATION_DELIVERY_LEASE_BUSY.name(), OPERATION_DISPATCH_ALREADY_STARTED.name()));
        check.assertThat(target.invocations(id())).isEqualTo(1);
        check.assertThat(eventCount).isEqualTo(3);
      });
    } finally { release.countDown(); pool.shutdownNow(); assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue(); }
    assertThat(target.invocations(id())).isEqualTo(1); assertThat(count("tp_operation_admissions")).isEqualTo(1);
    assertThat(count("tp_operation_delivery_events")).isEqualTo(3); assertThat(current().path("execution").path("status").asText()).isEqualTo("succeeded");
  }

  @Test void separatelyMintedPermitsCannotReplaceTheOneAdmittedAttempt() throws Exception {
    JsonNode approved = approved(); var issuer = issuer(); var command = execution(approved, fresh());
    List<String> permits = new ArrayList<>(); for (int n = 0; n < 4; n++) permits.add(issuer.issue(command).serialized());
    permit = permits.getFirst(); claimed = admission().admit(command, permit); var repository = repository();
    for (String other : permits.subList(1, 4)) assertCode(() -> repository.claim(execution(claimed, fresh()), other), OPERATION_PERMIT_INVALID);
    var execute = execution(claimed, fresh()); var lease = repository.claim(execute, permit);
    assertThat(repository.dispatch(lease, execute, permit)).isEqualTo("succeeded");
    assertThat(target.invocations(id())).isEqualTo(1); assertThat(count("tp_operation_admissions")).isEqualTo(1);
  }

  @Test void expiredPreDispatchLeaseRecoversWithHigherFenceAndRejectsTheOldAcknowledgement() throws Exception {
    // Independent owners validate before the signed lifetime, as in the32-contender control.
    // Neither owner has a lease/capability yet. Actual JVM restart is tested separately.
    var first = repository(); var recoveryOwner = repository();
    ObjectNode receipt = JsonSupport.MAPPER.createObjectNode().put("engine", database.getDockerImageName())
        .put("phase", "owners_prepared").put("owners", 2);
    long began = System.nanoTime();
    try {
      admit(); receipt.put("phase", "admitted");
      var command = execution(claimed, fresh()); var old = first.claim(command, permit);
      receipt.put("phase", "first_claim").put("firstClaimAt", event(1).at().toString()).put("firstLeaseExpiresAt", old.expiresAt().toString());
      awaitExpiry(old.expiresAt());
      var recovered = recoveryOwner.claim(execution(claimed, fresh()), permit);
      receipt.put("phase", "recovered").put("recoveryAt", event(2).at().toString()).put("recoveredFence", recovered.fence());
      assertThat(recovered.fence()).isEqualTo(2);
      Instant originalExpiry = OperationDeliveryJournal.time(storedAdmission().path("claims").path("expiresAt"));
      receipt.put("originalPermitExpiresAt", originalExpiry.toString());
      assertThat(recovered.expiresAt()).isBeforeOrEqualTo(originalExpiry);
      Throwable stale = catchThrowable(() -> first.dispatch(old, command, permit));
      org.assertj.core.api.SoftAssertions.assertSoftly(check -> {
        check.assertThat(stale).isInstanceOf(BusinessOperationStorageException.class);
        if (stale instanceof BusinessOperationStorageException fixed) check.assertThat(fixed.code()).isEqualTo(OPERATION_STATE_STALE);
        check.assertThat(target.invocations(id())).isZero();
      });
      receipt.put("phase", "stale_fence_refused");
      assertThat(recoveryOwner.dispatch(recovered, execution(claimed, fresh()), permit)).isEqualTo("succeeded");
      assertThat(target.invocations(id())).isEqualTo(1); assertThat(count("tp_operation_delivery_events")).isEqualTo(4); current();
      receipt.put("phase", "complete");
    } catch (BusinessOperationStorageException fixed) {
      receipt.put("refusalCode", fixed.code().name()); throw fixed;
    } finally {
      receipt.put("elapsedMillis", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - began)).put("targetInvocations", target.invocations(id()));
      writeSafeReceipt("lease-recovery", receipt);
    }
  }

  @Test void leaseIsCappedByFreshOwnerAndEqualityIsExpiredWithoutRenewingAnyOriginalBound() throws Exception {
    admit(); var repository = repository(); Instant expiry = now().plusSeconds(2);
    var lease = repository.claim(execution(claimed, expiry), permit);
    assertThat(lease.expiresAt()).isEqualTo(expiry); awaitExpiry(expiry);
    assertCode(() -> repository.dispatch(lease, execution(claimed, fresh()), permit), OPERATION_DELIVERY_LEASE_EXPIRED);
    assertThat(target.invocations(id())).isZero(); assertThat(count("tp_operation_delivery_events")).isEqualTo(1);
  }

  @ParameterizedTest @ValueSource(strings = {"before_claim", "after_claim", "before_dispatch_started", "after_dispatch_started", "after_target_before_result"})
  void deterministicCrashBoundariesNeverRedispatchAfterStarted(String phase) throws Exception {
    admit(); var repository = new JdbcGitHubOperationDeliveryRepository(runtime, profile, expected, target,
        point -> { if (point.equals(phase)) throw new Crash(); });
    var command = execution(claimed, fresh());
    assertThatThrownBy(() -> { var lease = repository.claim(command, permit); repository.dispatch(lease, command, permit); }).isInstanceOf(Crash.class);
    var restarted = repository(); var read = restarted.reconcile(SCOPE, id());
    boolean started = Set.of("after_dispatch_started", "after_target_before_result").contains(phase);
    assertThat(read.dispatchStarted()).isEqualTo(started);
    assertThat(read.syntheticInvocations()).isEqualTo(phase.equals("after_target_before_result") ? 1 : 0);
    assertThat(read.durableState()).isEqualTo(started ? "executing" : "claimed");
    if (started) assertCode(() -> restarted.claim(execution(current(), fresh()), permit), OPERATION_DISPATCH_ALREADY_STARTED);
    assertThat(restarted.reconcile(SCOPE, id())).isEqualTo(read);
  }

  @ParameterizedTest @ValueSource(strings = {"before_claim", "after_claim", "before_dispatch_started", "after_dispatch_started", "after_target_before_result"})
  void actualJvmHaltAndRestartNeverRecreatesPostStartDispatch(String phase) throws Exception {
    boolean started = Set.of("after_dispatch_started", "after_target_before_result").contains(phase);
    int callsBeforeCrash = phase.equals("after_target_before_result") ? 1 : 0;
    long crashedPid;
    try (var child = OperationDeliveryChildJvm.Managed.start(childSetup("crash", phase))) {
      crashedPid = child.pid();
      // The child is already bootstrapped before fresh approval/permit authority is issued.
      admit();
      ObjectNode request = JsonSupport.MAPPER.createObjectNode().put("permit", permit).put("identityExpiresAt", fresh().toString());
      assertThat(child.execute(request).equals("CRASH:" + phase + ":" + callsBeforeCrash)).isTrue();
      assertThat(child.finish()).isEqualTo(79); assertThat(child.alive()).isFalse();
      assertThat(child.stderrCategories()).isEmpty();
    }
    int expectedEvents = phase.equals("before_claim") ? 0 : started ? 2 : 1;
    assertThat(count("tp_operation_delivery_events")).isEqualTo(expectedEvents);
    assertThat(current().path("execution").path("status").asText()).isEqualTo(started ? "executing" : "claimed");
    long restartedPid;
    String response;
    try (var child = OperationDeliveryChildJvm.Managed.start(childSetup(started ? "attempt" : "observe", "none"))) {
      restartedPid = child.pid();
      response = child.execute(JsonSupport.MAPPER.createObjectNode().put("permit", permit).put("identityExpiresAt", fresh().toString()));
      assertThat(response.equals(started ? "REFUSED:OPERATION_DISPATCH_ALREADY_STARTED:0" : "OBSERVE:claimed:false:0")).isTrue();
      assertThat(child.finish()).isZero(); assertThat(child.alive()).isFalse(); assertThat(child.stderrCategories()).isEmpty();
    }
    assertThat(count("tp_operation_delivery_events")).isEqualTo(expectedEvents);
    assertThat(ProcessHandle.of(crashedPid).map(ProcessHandle::isAlive).orElse(false)).isFalse();
    assertThat(ProcessHandle.of(restartedPid).map(ProcessHandle::isAlive).orElse(false)).isFalse();
    ObjectNode receipt = JsonSupport.MAPPER.createObjectNode().put("engine", database.getDockerImageName()).put("phase", phase)
        .put("crashedPid", crashedPid).put("haltExit", 79).put("callsObservedBeforeHalt", callsBeforeCrash)
        .put("restartedPid", restartedPid).put("restartExit", 0).put("restartResponse", response)
        .put("restartInvocations", 0).put("eventRows", expectedEvents).put("bothChildrenReaped", true);
    writeSafeReceipt("jvm-crash-" + phase, receipt);
  }

  private ObjectNode childSetup(String mode, String phase) {
    ObjectNode setup = JsonSupport.MAPPER.createObjectNode().put("jdbcUrl", database.getJdbcUrl())
        .put("username", runtime.user).put("password", runtime.password)
        .put("publicKey", Base64.getEncoder().encodeToString(keys.getPublic().getEncoded()))
        .put("resourceBindingHash", initial.path("resourceBindingHash").asText())
        .put("artifactHash", CanonicalJson.sha256(initial.path("artifactDigests")))
        .put("profileHash", profile.fingerprint()).put("operationId", id()).put("phase", phase).put("mode", mode);
    setup.set("policy", operatorPolicy.deepCopy());
    return setup; // Private pipe frame only. NEVER log, stringify in assertions, or retain this object.
  }

  private void writeSafeReceipt(String label, ObjectNode value) throws Exception {
    String directory = System.getenv("TRACEPERMIT_DELIVERY_EVIDENCE_DIR");
    if (directory == null) return;
    Path root = Path.of(directory); java.nio.file.Files.createDirectories(root);
    java.nio.file.Files.writeString(root.resolve(label + "-" + database.getDockerImageName().replaceAll("[^A-Za-z0-9]", "_")
        + "-" + UUID.randomUUID() + ".json"), value.toString());
  }

  @Test void pauseAfterAcknowledgedStartShowsTheLeaseCannotAtomicallyFenceATargetAndNeverAllowsResend() throws Exception {
    admit(); var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
    var repository = new JdbcGitHubOperationDeliveryRepository(runtime, profile, expected, target, phase -> {
      if (phase.equals("after_dispatch_started")) {
        assertThat(runtime.open.get()).isZero(); entered.countDown();
        try { assertThat(release.await(45, TimeUnit.SECONDS)).isTrue(); }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
      }
    });
    var command = execution(claimed, fresh()); var lease = repository.claim(command, permit); var pool = Executors.newSingleThreadExecutor();
    try {
      var call = pool.submit(() -> repository.dispatch(lease, command, permit));
      assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
      var reader = repository(); assertThat(reader.reconcile(SCOPE, id()).syntheticInvocations()).isZero();
      awaitExpiry(lease.expiresAt());
      assertCode(() -> reader.claim(execution(current(), fresh()), permit), OPERATION_DISPATCH_ALREADY_STARTED);
      release.countDown(); assertThat(call.get(10, TimeUnit.SECONDS)).isEqualTo("succeeded");
      assertThat(target.invocations(id())).isEqualTo(1);
    } finally { release.countDown(); pool.shutdownNow(); assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue(); }
  }

  @ParameterizedTest @ValueSource(strings = {"control", "head", "implicit"})
  void genuineWriterWaitCannotConvertExpiredFreshAuthorityIntoALease(String phase) throws Exception {
    probePhase = phase;
    admit(); var repository = repository(); var pool = Executors.newSingleThreadExecutor();
    var acknowledgedLease = phase.equals("control") ? repository.claim(execution(claimed, fresh()), permit) : null;
    try (var holder = owner.getConnection()) {
      holder.setAutoCommit(false);
      if (phase.equals("implicit")) {
        var admission = OperationAdmissionJournal.read(holder, id()).orElseThrow();
        Instant holderAt = JdbcBusinessOperationLifecycleRepository.storeTime(holder, OperationDatabase.inspectWriter(holder));
        ObjectNode holderIdentity = execution(claimed, fresh()).actor();
        ObjectNode json = OperationDeliveryJournal.base("lease", SCOPE, admission, OperationDeliveryJournal.outboxHash(holder, id()), claimed,
            List.of(), 1, holderAt).put("leaseId", UUID.randomUUID().toString())
            .put("expiresAt", OperationDeliveryJournal.deadline(admission.json(), holderIdentity, holderAt).toString());
        json.set("identity", holderIdentity); OperationDeliveryJournal.insert(holder, OperationDeliveryJournal.create(json));
      } else {
        String sql = phase.equals("control") ? "SELECT scope_hash FROM tp_operation_authority_controls WHERE scope_hash = ? FOR UPDATE"
            : "SELECT operation_id FROM tp_operation_lifecycle_heads WHERE operation_id = ? FOR UPDATE";
        try (var statement = holder.prepareStatement(sql)) { statement.setQueryTimeout(5); statement.setString(1, phase.equals("control") ? SCOPE.fingerprint() : id()); try (var rows = statement.executeQuery()) { assertThat(rows.next()).isTrue(); } }
      }
      Instant expiry = now().plusSeconds(3);
      runtime.watch = phase.equals("implicit") ? "INSERT INTO tp_operation_delivery_events"
          : phase.equals("control") ? "FROM tp_operation_authority_controls" : "FROM tp_operation_lifecycle_heads";
      runtime.attempt = new CountDownLatch(1);
      var waiter = pool.submit(() -> phase.equals("control")
          ? repository.dispatch(acknowledgedLease, execution(claimed, expiry), permit)
          : repository.claim(execution(claimed, expiry), permit));
      try {
        assertThat(runtime.attempt.await(2, TimeUnit.SECONDS)).isTrue(); awaitDatabaseWait(runtime.pid.get(), expiry);
        assertThat(waiter.isDone()).isFalse(); awaitExpiry(expiry); holder.rollback();
        requireExpiryRefusal(waiter);
      } finally { holder.rollback(); runtime.watch = "none"; }
    } finally { pool.shutdownNow(); assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue(); }
    assertThat(count("tp_operation_delivery_events")).isEqualTo(phase.equals("control") ? 1 : 0); assertThat(target.invocations(id())).isZero();
    assertThat(current().path("execution").path("status").asText()).isEqualTo("claimed");
    probeQualified = true;
  }

  @Test void genuineStartUniqueWaitExpiresFreshAuthorityBeforeAnyTargetInvocation() throws Exception {
    probePhase = "dispatch_implicit";
    admit(); var repository = repository(); var command = execution(claimed, fresh()); var lease = repository.claim(command, permit);
    var pool = Executors.newSingleThreadExecutor();
    try (var holder = owner.getConnection()) {
      holder.setAutoCommit(false);
      var admission = OperationAdmissionJournal.read(holder, id()).orElseThrow();
      Instant at = JdbcBusinessOperationLifecycleRepository.storeTime(holder, OperationDatabase.inspectWriter(holder));
      ObjectNode json = OperationDeliveryJournal.base("dispatch_started", SCOPE, admission,
          OperationDeliveryJournal.outboxHash(holder, id()), claimed, OperationDeliveryJournal.read(holder, id()), lease.fence(), at)
          .put("leaseHash", event(1).hash());
      json.set("identity", command.actor());
      OperationDeliveryJournal.insert(holder, OperationDeliveryJournal.transition(json, SCOPE, claimed, "executing"));
      Instant expiry = now().plusSeconds(3);
      runtime.watch = "INSERT INTO tp_operation_delivery_events"; runtime.attempt = new CountDownLatch(1);
      var waiter = pool.submit(() -> repository.dispatch(lease, execution(claimed, expiry), permit));
      try {
        assertThat(runtime.attempt.await(2, TimeUnit.SECONDS)).isTrue(); awaitDatabaseWait(runtime.pid.get(), expiry);
        assertThat(waiter.isDone()).isFalse(); awaitExpiry(expiry); holder.rollback();
        requireExpiryRefusal(waiter);
      } finally { holder.rollback(); runtime.watch = "none"; }
    } finally { pool.shutdownNow(); assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue(); }
    assertThat(target.invocations(id())).isZero(); assertThat(count("tp_operation_delivery_events")).isEqualTo(1);
    assertThat(current().path("execution").path("status").asText()).isEqualTo("claimed");
    probeQualified = true;
  }

  private void requireExpiryRefusal(Future<?> waiter) throws Exception {
    Throwable failure = catchThrowable(() -> waiter.get(10, TimeUnit.SECONDS));
    if (failure instanceof ExecutionException wrapped && wrapped.getCause() instanceof BusinessOperationStorageException fixed) {
      probeRefusal = fixed.code().name();
    }
    org.assertj.core.api.SoftAssertions.assertSoftly(check -> {
      check.assertThat(failure).isInstanceOf(ExecutionException.class);
      if (failure != null) check.assertThat(failure.getCause()).isInstanceOf(BusinessOperationStorageException.class);
      check.assertThat(probeRefusal).isIn(OPERATION_PERMIT_EXPIRED.name(), OPERATION_AUTHENTICATION_EXPIRED.name(), OPERATION_DELIVERY_LEASE_EXPIRED.name());
      check.assertThat(target.invocations(id())).isZero();
    });
  }

  @Test void freshDispatchIdentityIsRecordedAndCoherentExpiredOrWrongOwnerMetadataRefusesReplay() throws Exception {
    admit();
    var normal = new JdbcGitHubOperationDeliveryRepository(runtime, profile, expected, target,
        phase -> { if (phase.equals("after_dispatch_started")) throw new Crash(); });
    var command = execution(claimed, fresh());
    var lease = normal.claim(command, permit);
    var freshIdentity = new TrustedRequesterContext(new TenantPartition(SCOPE.tenantId()), SCOPE.requesterPrincipalId(), true,
        Set.of("operation_requester"), Set.of("operations.execute"), "synthetic-fresh", "sha256:" + "c".repeat(64), fresh());
    var dispatch = new BusinessOperationExecution(SCOPE, id(), PLANNER.observe(SCOPE, claimed), freshIdentity, SCOPE.workspaceId());
    assertThatThrownBy(() -> normal.dispatch(lease, dispatch, permit)).isInstanceOf(Crash.class);
    assertThat(event(2).json().path("identity")).isEqualTo(dispatch.actor());
    assertThat(event(2).json().path("identity")).isNotEqualTo(event(1).json().path("identity"));
    assertThat(target.invocations(id())).isZero();
    assertThat(current().path("execution").path("status").asText()).isEqualTo("executing");
    var original = event(2);
    for (String field : List.of("principalId", "scopeHash", "authorityExpiresAt")) {
      ObjectNode changed = original.json(); ObjectNode actor = (ObjectNode) changed.path("identity");
      actor.put(field, field.equals("authorityExpiresAt") ? original.at().toString() : field.equals("scopeHash") ? "sha256:" + "d".repeat(64) : "principal:other");
      var altered = OperationDeliveryJournal.create(changed); replaceEvent(2, changed); replaceStartActor(altered);
      try { assertCode(this::current, OPERATION_STORAGE_CORRUPT); }
      finally { replaceEvent(2, original.json()); replaceStartActor(original); }
    }
    current(); assertThat(target.invocations(id())).isZero();
  }

  private void replaceStartActor(OperationDeliveryJournal.Event event) throws SQLException {
    JsonNode actor = OperationDeliveryJournal.actor(event);
    try (var connection = owner.getConnection(); var statement = connection.prepareStatement("UPDATE tp_operation_lifecycle_facts SET actor_json = ?, actor_hash = ? WHERE operation_id = ? AND sequence_number = 7")) {
      statement.setQueryTimeout(5); statement.setString(1, CanonicalJson.canonicalize(actor)); statement.setString(2, CanonicalJson.sha256(actor)); statement.setString(3, id()); assertThat(statement.executeUpdate()).isEqualTo(1);
    }
  }

  @ParameterizedTest @ValueSource(strings = {"event:lease", "after-event:lease", "rollback:lease", "event:dispatch_started", "after-event:dispatch_started", "fact:7", "after-fact:7", "head:executing", "after-head:executing", "event:result", "after-event:result", "fact:8", "after-fact:8", "head:succeeded", "after-head:succeeded"})
  void everyProvisionalWriteAndRollbackFaultIsAtomicAndCannotResend(String fault) throws Exception {
    admit(); var repository = repository(); var command = execution(claimed, fresh());
    if (fault.endsWith(":lease")) {
      runtime.fault = fault; try { assertCode(() -> repository.claim(command, permit), OPERATION_STORAGE_UNAVAILABLE); } finally { runtime.fault = "none"; }
      assertThat(count("tp_operation_delivery_events")).isZero(); assertThat(target.invocations(id())).isZero();
    } else {
      var lease = repository.claim(command, permit); runtime.fault = fault;
      boolean afterTarget = fault.endsWith(":result") || fault.endsWith(":8") || fault.endsWith(":succeeded");
      try { assertCode(() -> repository.dispatch(lease, command, permit), afterTarget ? OPERATION_DELIVERY_RESULT_UNKNOWN : OPERATION_STORAGE_UNAVAILABLE); } finally { runtime.fault = "none"; }
      assertThat(target.invocations(id())).isEqualTo(afterTarget ? 1 : 0);
      assertThat(count("tp_operation_delivery_events")).isEqualTo(afterTarget ? 2 : 1);
      assertThat(current().path("execution").path("status").asText()).isEqualTo(afterTarget ? "executing" : "claimed");
      if (afterTarget) assertCode(() -> repository().claim(execution(current(), fresh()), permit), OPERATION_DISPATCH_ALREADY_STARTED);
    }
  }

  @ParameterizedTest @ValueSource(strings = {"commit:lease", "after-commit:lease", "close:lease", "commit:dispatch_started", "after-commit:dispatch_started", "close:dispatch_started", "commit:result", "after-commit:result", "close:result"})
  void uncertainCommitOrCleanupNeverCreatesADispatchAcknowledgement(String fault) throws Exception {
    admit(); var repository = repository(); var command = execution(claimed, fresh());
    if (fault.endsWith(":lease")) {
      runtime.fault = fault; try { assertCode(() -> repository.claim(command, permit), OPERATION_COMMIT_UNKNOWN); } finally { runtime.fault = "none"; }
      assertThat(target.invocations(id())).isZero();
      assertThat(count("tp_operation_delivery_events")).isEqualTo(fault.startsWith("commit:") ? 0 : 1);
    } else {
      var lease = repository.claim(command, permit); runtime.fault = fault;
      boolean invoked = fault.endsWith(":result");
      try { assertCode(() -> repository.dispatch(lease, command, permit), invoked ? OPERATION_DELIVERY_RESULT_UNKNOWN : OPERATION_COMMIT_UNKNOWN); } finally { runtime.fault = "none"; }
      assertThat(target.invocations(id())).isEqualTo(invoked ? 1 : 0);
      boolean started = invoked || !fault.startsWith("commit:");
      assertThat(repository().reconcile(SCOPE, id()).dispatchStarted()).isEqualTo(started);
      if (started) assertCode(() -> repository().claim(execution(current(), fresh()), permit), OPERATION_DISPATCH_ALREADY_STARTED);
    }
    assertThat(runtime.open.get()).isZero();
  }

  @Test void malformedWrongSignedBindingsQuarantineAndStaleStateNeverDispatch() throws Exception {
    admit(); var repository = repository(); var command = execution(claimed, fresh());
    assertCode(() -> repository.claim(command, permit + "x"), OPERATION_PERMIT_INVALID);
    assertCode(() -> repository.claim(execution(initial, fresh()), permit), OPERATION_STATE_STALE);
    var codec = new OperationPermitCodec(profile.admissionSettings(), keys.getPrivate());
    JsonNode claims = codec.verify(permit).claims();
    for (String field : List.of("profileHash", "targetProfileHash", "targetBindingDigest", "canonicalActionHash", "semanticPayloadDigest", "policyReleaseHash", "artifactDigestsHash", "resourceBindingHash", "preconditionBindingHash", "targetIdempotencyKeyHash", "reviewBindingHash", "approvalFactHash", "approvalActorHash", "evaluationFactHash", "evaluationActorHash")) {
      ObjectNode changed = claims.deepCopy(); changed.put(field, "sha256:" + "c".repeat(64));
      assertCode(() -> repository.claim(command, codec.sign(changed)), OPERATION_PERMIT_INVALID);
    }
    OperationAuthorityAdministration.quarantine(owner, profile);
    assertCode(() -> repository.claim(command, permit), OPERATION_AUTHORITY_NOT_READY);
    assertThat(count("tp_operation_delivery_events")).isZero(); assertThat(target.invocations(id())).isZero();
  }

  @Test void rejectedAuthorityWithAnActualCleanupFailureKeepsOnlyTheFixedDiagnostic() throws Exception {
    admit(); var repository=repository(); OperationAuthorityAdministration.quarantine(owner,profile);
    runtime.fault="close-any";
    try {assertCode(()->repository.claim(execution(claimed,fresh()),permit),OPERATION_AUTHORITY_NOT_READY);}
    finally {runtime.fault="none";}
    assertThat(runtime.open.get()).isZero();assertThat(count("tp_operation_delivery_events")).isZero();assertThat(target.invocations(id())).isZero();
  }

  @Test void wrongConcreteTargetPreconditionsAndForeignLeaseNeverInvoke() throws Exception {
    admit(); var wrong = new GitHubBusinessOperationBinding.ExpectedProfile(expected.adapterId(), expected.environment(), 1, expected.policyRelease(), expected.artifactDigests(),
        new GitHubPilotTargetBinding(71, 102, 201, "tracepermit/agentcore-pr-pilot", "synthetic-change", "main", "a".repeat(40), "b".repeat(40)));
    target = new SyntheticGitHubDeliveryTarget(wrong, SyntheticGitHubDeliveryTarget.Outcome.CONFIRMED_SUCCESS);
    var repository = repository(); var command = execution(claimed, fresh()); var lease = repository.claim(command, permit);
    assertCode(() -> repository.dispatch(lease, command, permit), OPERATION_TARGET_PRECONDITION_FAILED);
    target = new SyntheticGitHubDeliveryTarget(expected, SyntheticGitHubDeliveryTarget.Outcome.CONFIRMED_SUCCESS);
    assertCode(() -> repository().dispatch(lease, command, permit), OPERATION_COMMAND_INVALID);
    assertThat(target.invocations(id())).isZero(); assertThat(count("tp_operation_delivery_events")).isEqualTo(1);
  }

  @Test void runtimeCannotRewriteDeleteOrDdlTheDeliveryJournalWithValidAdministrativeControls() throws Exception {
    admit(); var repository = repository(); repository.claim(execution(claimed, fresh()), permit);
    for (String sql : List.of("UPDATE tp_operation_delivery_events SET event_hash = event_hash WHERE operation_id = ?", "DELETE FROM tp_operation_delivery_events WHERE operation_id = ?")) {
      try (var connection = owner.getConnection()) {
        connection.setAutoCommit(false);
        try (var statement = connection.prepareStatement(sql)) { statement.setQueryTimeout(5); statement.setString(1, id()); assertThat(statement.executeUpdate()).isEqualTo(1); }
        finally { connection.rollback(); }
      }
      try (var connection = runtime.getConnection(); var statement = connection.prepareStatement(sql)) {
        statement.setQueryTimeout(5); statement.setString(1, id());
        assertThatThrownBy(statement::executeUpdate).isInstanceOf(SQLException.class).satisfies(error -> {
          SQLException denied = (SQLException) error; assertThat(pg() ? denied.getSQLState().equals("42501") : Set.of(1142,1143).contains(denied.getErrorCode())).isTrue();
        });
      }
    }
    String ddl = "CREATE TABLE tp_delivery_forbidden (id INTEGER)";
    try (var connection = owner.getConnection(); var statement = connection.createStatement()) { statement.setQueryTimeout(5); statement.execute(ddl); statement.execute("DROP TABLE tp_delivery_forbidden"); }
    try (var connection = runtime.getConnection(); var statement = connection.createStatement()) {
      statement.setQueryTimeout(5);
      assertThatThrownBy(() -> statement.execute(ddl)).isInstanceOfSatisfying(SQLException.class, denied ->
          assertThat(pg() ? denied.getSQLState().equals("42501") : Set.of(1044,1142).contains(denied.getErrorCode())).isTrue());
    }
    current(); assertThat(target.invocations(id())).isZero();
  }

  @Test void actualInsertGrantDenialHasAValidAdministrativeControlAndNeverCreatesALease() throws Exception {
    admit(); var repository=repository(); var command=execution(claimed,fresh());
    OperationDeliveryJournal.Event control;
    try(var connection=owner.getConnection()) {
      var admission=OperationAdmissionJournal.read(connection,id()).orElseThrow();
      Instant at=JdbcBusinessOperationLifecycleRepository.storeTime(connection,OperationDatabase.inspectWriter(connection));
      ObjectNode json=OperationDeliveryJournal.base("lease",SCOPE,admission,OperationDeliveryJournal.outboxHash(connection,id()),claimed,List.of(),1,at)
          .put("leaseId",UUID.randomUUID().toString()).put("expiresAt",OperationDeliveryJournal.deadline(admission.json(),command.actor(),at).toString());
      json.set("identity",command.actor());control=OperationDeliveryJournal.create(json);
      connection.setAutoCommit(false);
      try {OperationDeliveryJournal.insert(connection,control);}finally{connection.rollback();}
    }
    Source administrator=pg()?owner:new Source("root",database.getPassword());
    String table=pg()?"tp_operation_delivery_events":"`"+database.getDatabaseName()+"`.tp_operation_delivery_events";
    String user=pg()?"tp_delivery_runtime":"'tp_delivery_runtime'@'%'";
    try(var connection=administrator.getConnection();var statement=connection.createStatement()) {statement.setQueryTimeout(5);statement.execute("REVOKE INSERT ON "+table+" FROM "+user);}
    try {
      try(var connection=runtime.getConnection()) {
        assertThatThrownBy(()->OperationDeliveryJournal.insert(connection,control)).isInstanceOfSatisfying(SQLException.class,denied->
            assertThat(pg()?denied.getSQLState().equals("42501"):Set.of(1142,1143).contains(denied.getErrorCode())).isTrue());
      }
      assertCode(()->repository.claim(command,permit),OPERATION_STORAGE_UNAVAILABLE);
    } finally {
      try(var connection=administrator.getConnection();var statement=connection.createStatement()) {statement.setQueryTimeout(5);statement.execute("GRANT INSERT ON "+table+" TO "+user);}
    }
    assertThat(count("tp_operation_delivery_events")).isZero();assertThat(target.invocations(id())).isZero();current();
  }

  @ParameterizedTest @ValueSource(strings={"before_start","after_start"})
  void controlChangesRefuseBeforeStartAndCannotAtomicallyFenceAnAlreadyStartedSyntheticWorker(String phase) throws Exception {
    admit(); var repository=new JdbcGitHubOperationDeliveryRepository(runtime,profile,expected,target,point->{
      if(phase.equals("after_start")&&point.equals("after_dispatch_started")) {
        assertThat(runtime.open.get()).isZero();OperationAuthorityAdministration.quarantine(owner,profile);
      }
    });
    var command=execution(claimed,fresh());var lease=repository.claim(command,permit);
    if(phase.equals("before_start"))OperationAuthorityAdministration.quarantine(owner,profile);
    assertCode(()->repository.dispatch(lease,command,permit),phase.equals("before_start")?OPERATION_AUTHORITY_NOT_READY:OPERATION_DELIVERY_RESULT_UNKNOWN);
    var observation=repository().reconcile(SCOPE,id());
    assertThat(observation.dispatchStarted()).isEqualTo(phase.equals("after_start"));
    assertThat(observation.syntheticInvocations()).isEqualTo(phase.equals("after_start")?1:0);
    assertThat(observation.durableState()).isEqualTo(phase.equals("after_start")?"executing":"claimed");
    assertCode(()->repository().claim(execution(current(),fresh()),permit),OPERATION_AUTHORITY_NOT_READY);
    assertThat(repository().reconcile(SCOPE,id())).isEqualTo(observation);
  }

  @Test void closedReaderRejectsAlteredUnknownOrMissingImmutableDeliveryAnchors() throws Exception {
    admit(); var repository = repository(); var command = execution(claimed, fresh()); var lease = repository.claim(command, permit);
    for (String key : List.of("scopeHash", "admissionHash", "outboxHash", "attemptId", "admittedSnapshotHash", "previousEventHash", "fence", "expiresAt", "kind", "extra")) {
      var original = event(1); ObjectNode changed = original.json();
      if (key.equals("fence")) changed.put(key, 2);
      else if (key.equals("expiresAt")) changed.put(key, original.at().plusSeconds(31).toString());
      else changed.put(key, key.equals("kind") ? "future" : key.equals("attemptId") ? UUID.randomUUID().toString() : "sha256:" + "c".repeat(64));
      replaceEvent(1, changed);
      try { assertCode(this::current, OPERATION_STORAGE_CORRUPT); }
      finally { replaceEvent(1, original.json()); }
    }
    repository.dispatch(lease, command, permit); current();
    sql("DELETE FROM tp_operation_delivery_events WHERE event_sequence = 2");
    assertCode(this::current, OPERATION_STORAGE_CORRUPT); assertThat(target.invocations(id())).isEqualTo(1);
  }

  @Test void readOnlyReconciliationWithLostSyntheticMemoryNeverCreatesAuthorityOrASecondCall() throws Exception {
    admit(); var repository = repository(); var command = execution(claimed, fresh());
    repository.dispatch(repository.claim(command, permit), command, permit);
    int rows = count("tp_operation_delivery_events");
    target = new SyntheticGitHubDeliveryTarget(expected, SyntheticGitHubDeliveryTarget.Outcome.CONFIRMED_SUCCESS);
    var reconstructed = repository(); var report = reconstructed.reconcile(SCOPE, id());
    assertThat(report.durableState()).isEqualTo("succeeded"); assertThat(report.syntheticObservation()).isEqualTo("unknown");
    assertThat(report.syntheticInvocations()).isZero();
    assertCode(() -> reconstructed.claim(execution(current(), fresh()), permit), OPERATION_DISPATCH_ALREADY_STARTED);
    assertThat(count("tp_operation_delivery_events")).isEqualTo(rows); assertThat(target.invocations(id())).isZero();
  }

  @Test void missingExecutePermissionWrongScopeAndExpiredIdentityRefuseWithoutAnyDeliveryWrite() throws Exception {
    admit(); var repository = repository();
    for (Set<String> scopes : List.of(Set.of("operations.evaluate"), Set.of("operations.cancel"), Set.of("operations.decide"))) {
      assertCode(() -> new BusinessOperationExecution(SCOPE, id(), PLANNER.observe(SCOPE, claimed), requester(scopes, fresh()), SCOPE.workspaceId()), OPERATION_EXECUTOR_NOT_AUTHORIZED);
    }
    var wrong = new BusinessOperationScope("other-tenant", SCOPE.workspaceId(), SCOPE.requesterPrincipalId());
    assertCode(() -> new BusinessOperationExecution(wrong,id(),PLANNER.observe(SCOPE,claimed),requester(Set.of("operations.execute"),fresh()),SCOPE.workspaceId()), OPERATION_EXECUTOR_NOT_AUTHORIZED);
    assertCode(() -> repository.claim(execution(claimed, now().minusSeconds(1)), permit), OPERATION_PERMIT_EXPIRED);
    assertThat(target.invocations(id())).isZero(); assertThat(count("tp_operation_delivery_events")).isZero();
  }

  @Test void retainedOwnerRejectsUnknownFailedRepeatableAndIncompleteV7Protocols() throws Exception {
    admit(); var repository = repository();
    for (int kind=0;kind<3;kind++) {
      try (var connection=owner.getConnection();var statement=connection.prepareStatement("INSERT INTO tp_operation_schema_history (installed_rank, version, description, type, script, checksum, installed_by, execution_time, success) SELECT installed_rank + 100, ?, description, type, 'tracepermit_delivery_protocol_probe', checksum, installed_by, execution_time, ? FROM tp_operation_schema_history WHERE version = '7'")) {
        statement.setQueryTimeout(5); statement.setString(1,kind==0?"8":kind==1?"7":null);statement.setBoolean(2,kind!=1);assertThat(statement.executeUpdate()).isEqualTo(1);
      }
      try {assertCode(()->repository.claim(execution(claimed,fresh()),permit),OPERATION_SCHEMA_NOT_READY);}
      finally {sql("DELETE FROM tp_operation_schema_history WHERE script = 'tracepermit_delivery_protocol_probe'");}
    }
    sql("UPDATE tp_operation_schema_history SET success = FALSE WHERE version = '7'");
    try {assertCode(this::repository,OPERATION_SCHEMA_NOT_READY);assertCode(()->repository.claim(execution(claimed,fresh()),permit),OPERATION_SCHEMA_NOT_READY);}
    finally {sql("UPDATE tp_operation_schema_history SET success = TRUE WHERE version = '7'");}
    assertThat(count("tp_operation_delivery_events")).isZero();assertThat(target.invocations(id())).isZero();
  }

  @ParameterizedTest @ValueSource(strings = {"valid", "corrupt", "partial"})
  void stoppedPopulatedV6UpgradeValidatesHistoryBeforeDdlAndPreservesAllOldRows(String scenario) throws Exception {
    String name="tp_delivery_"+UUID.randomUUID().toString().replace("-","");
    Source administrator=pg()?owner:new Source("root",database.getPassword());
    try(var connection=administrator.getConnection();var statement=connection.createStatement()){statement.setQueryTimeout(5);statement.execute("CREATE DATABASE "+name);}
    Source extra=new Source(name,pg()?database.getUsername():"root",database.getPassword());
    try {
      OperationDatabase.initializeAdmissionEmpty(extra); OperationAuthorityAdministration.stageNew(extra,profile);OperationAuthorityAdministration.activateStaged(extra,profile);
      new JdbcBusinessOperationRepository(extra).createOrGet(SCOPE,initial);
      JsonNode approved=approved(extra);var execute=execution(approved,fresh());
      String token=new JdbcGitHubOperationPermitIssuer(extra,profile,expected,keys.getPrivate()).issue(execute).serialized();
      var oldOwner=new JdbcGitHubOperationAdmissionRepository(extra,profile,expected);
      JsonNode before=oldOwner.admit(execute,token);
      if(scenario.equals("corrupt")) {
        try(var connection=extra.getConnection();var statement=connection.prepareStatement("UPDATE tp_operation_lifecycle_heads SET snapshot_hash = ? WHERE operation_id = ?")){statement.setQueryTimeout(5);statement.setString(1,"sha256:"+"0".repeat(64));statement.setString(2,id());assertThat(statement.executeUpdate()).isEqualTo(1);}
        assertCode(()->OperationDatabase.upgradeDeliveryFromV6(extra),OPERATION_SCHEMA_NOT_READY);
        try(var connection=extra.getConnection();var tables=connection.getMetaData().getTables(connection.getCatalog(),pg()?"public":null,"tp_operation_delivery_events",new String[]{"TABLE"})){assertThat(tables.next()).isFalse();}
      } else if(scenario.equals("partial")) {
        String ddl=java.nio.file.Files.readString(Path.of(System.getProperty("tracepermit.repositoryRoot"),"java/persistence-operations/src/main/resources/db/operations/"+(pg()?"postgresql":"mariadb")+"/V7__closed_synthetic_delivery.sql"));
        try(var connection=extra.getConnection();var statement=connection.createStatement()){statement.setQueryTimeout(5);statement.execute(ddl);}
        assertCode(()->new JdbcGitHubOperationDeliveryRepository(extra,profile,expected,target),OPERATION_SCHEMA_NOT_READY);
        assertCode(()->oldOwner.admit(execute,token),OPERATION_SCHEMA_NOT_READY);
        assertCode(()->OperationDatabase.upgradeDeliveryFromV6(extra),OPERATION_SCHEMA_NOT_READY);
      } else {
        OperationDatabase.upgradeDeliveryFromV6(extra);
        JsonNode after=new JdbcBusinessOperationLifecycleRepository(extra).findCurrent(SCOPE,id()).orElseThrow();
        assertThat(CanonicalJson.canonicalize(after)).isEqualTo(CanonicalJson.canonicalize(before));
        assertThat(CanonicalJson.canonicalize(new JdbcBusinessOperationRepository(extra).find(SCOPE,id()).orElseThrow())).isEqualTo(CanonicalJson.canonicalize(initial));
        var deliver=new JdbcGitHubOperationDeliveryRepository(extra,profile,expected,target);var command=execution(before,fresh());
        assertThat(deliver.dispatch(deliver.claim(command,token),command,token)).isEqualTo("succeeded");
        assertThat(deliver.reconcile(SCOPE,id()).syntheticInvocations()).isEqualTo(1);
        assertCode(()->OperationDatabase.upgradeDeliveryFromV6(extra),OPERATION_SCHEMA_NOT_READY);
      }
    } finally {
      assertThat(extra.open.get()).isZero();
      try(var connection=administrator.getConnection();var statement=connection.createStatement()){statement.setQueryTimeout(5);statement.execute("DROP DATABASE "+name);}
    }
  }

  private void admit() { JsonNode approved = approved(); permit = issuer().issue(execution(approved, fresh())).serialized(); claimed = admission().admit(execution(approved, fresh()), permit); }
  private JdbcGitHubOperationDeliveryRepository repository() { return new JdbcGitHubOperationDeliveryRepository(runtime, profile, expected, target); }
  private JdbcGitHubOperationAdmissionRepository admission() { return new JdbcGitHubOperationAdmissionRepository(runtime, profile, expected); }
  private JdbcGitHubOperationPermitIssuer issuer() { return new JdbcGitHubOperationPermitIssuer(runtime, profile, expected, keys.getPrivate()); }
  private JsonNode approved() { return approved(runtime); }
  private JsonNode approved(DataSource source) {
    var writer = new JdbcGitHubOperationReviewRepository(source, profile, expected);
    JsonNode pending = writer.evaluate(new BusinessOperationEvaluation(SCOPE, id(), PLANNER.observe(SCOPE, initial), new TrustedOperationEvaluatorContext(requester(Set.of("operations.evaluate"), fresh()), SCOPE.workspaceId())));
    var reviewer = new TrustedReviewerContext(new TenantPartition(SCOPE.tenantId()), "principal:reviewer", "human", true,
        Set.of("operation_reviewer"), Set.of("operations.decide"), "synthetic-test", HASH);
    return writer.review(new BusinessOperationReview(SCOPE, id(), PLANNER.observe(SCOPE, pending),
        new TrustedOperationReviewerContext(reviewer, SCOPE.workspaceId(), fresh()), BusinessOperationReview.Decision.APPROVE),
        new TrustedOperationGroupMembership(SCOPE.tenantId(), SCOPE.workspaceId(), "principal:reviewer", "synthetic-reviewers", HASH, Instant.now().minusSeconds(1), fresh().minusSeconds(30)));
  }
  private BusinessOperationExecution execution(JsonNode head, Instant expiry) { return new BusinessOperationExecution(SCOPE, id(), PLANNER.observe(SCOPE, head), requester(Set.of("operations.execute"), expiry), SCOPE.workspaceId()); }
  private static TrustedRequesterContext requester(Set<String> scopes, Instant expiry) { return new TrustedRequesterContext(new TenantPartition(SCOPE.tenantId()), SCOPE.requesterPrincipalId(), true, Set.of("operation_requester"), scopes, "synthetic-test", HASH, expiry); }
  private JsonNode current() { return new JdbcBusinessOperationLifecycleRepository(runtime).findCurrent(SCOPE, id()).orElseThrow(); }
  private String id() { return initial.path("operationId").asText(); }
  private static Instant fresh() { return Instant.now().plusSeconds(180); }
  private boolean pg() { return database.getJdbcUrl().startsWith("jdbc:postgresql:"); }
  private static List<String> tables() { return List.of("tp_operation_schema_history", "tp_business_operations", "tp_business_operation_facts", "tp_operation_lifecycle_heads", "tp_operation_lifecycle_facts", "tp_operation_authority_controls", "tp_operation_review_bindings", "tp_operation_admissions", "tp_operation_admission_outbox", "tp_operation_delivery_events"); }
  private void sql(String text) throws SQLException { try (var connection = owner.getConnection(); var statement = connection.createStatement()) { statement.setQueryTimeout(5); statement.execute(text); } }
  private int count(String table) throws SQLException { try (var connection = owner.getConnection(); var statement = connection.createStatement()) { statement.setQueryTimeout(5); try (var rows = statement.executeQuery("SELECT COUNT(*) FROM " + table)) { assertThat(rows.next()).isTrue(); return rows.getInt(1); } } }
  private Instant now() throws SQLException { try (var connection = owner.getConnection()) { return JdbcBusinessOperationLifecycleRepository.storeTime(connection, OperationDatabase.inspectWriter(connection)); } }
  private void awaitExpiry(Instant expiry) throws Exception { long end = System.nanoTime()+TimeUnit.SECONDS.toNanos(40); while (now().isBefore(expiry)) { assertThat(System.nanoTime()).isLessThan(end); Thread.sleep(20); } }
  private OperationDeliveryJournal.Event event(int index) throws SQLException { try (var connection = owner.getConnection()) { return OperationDeliveryJournal.read(connection, id()).get(index-1); } }
  private JsonNode storedAdmission() throws SQLException { try (var connection = owner.getConnection()) { return OperationAdmissionJournal.read(connection, id()).orElseThrow().json(); } }
  private void replaceEvent(int sequence, JsonNode value) throws SQLException { try (var connection = owner.getConnection(); var statement = connection.prepareStatement("UPDATE tp_operation_delivery_events SET event_hash = ?, event_json = ? WHERE operation_id = ? AND event_sequence = ?")) { statement.setQueryTimeout(5); statement.setString(1, CanonicalJson.sha256(value)); statement.setString(2, CanonicalJson.canonicalize(value)); statement.setString(3,id()); statement.setInt(4,sequence); assertThat(statement.executeUpdate()).isEqualTo(1); } }
  private void awaitDatabaseWait(int pid, Instant expiry) throws Exception {
    var inspector = pg() ? owner : new Source("root", database.getPassword()); long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
    try (var connection=inspector.getConnection();var statement=connection.prepareStatement(pg()?"SELECT COUNT(*) FROM pg_stat_activity WHERE pid = ? AND wait_event_type = 'Lock'":"SELECT COUNT(*) FROM information_schema.INNODB_TRX WHERE trx_mysql_thread_id = ? AND trx_state = 'LOCK WAIT'")) {
      statement.setQueryTimeout(3);statement.setInt(1,pid);
      while(true){try(var rows=statement.executeQuery()){assertThat(rows.next()).isTrue();runtime.waitRows=rows.getInt(1);if(runtime.waitRows==1)break;}assertThat(System.nanoTime()).as("Owned wait probe trace=%s; observed wait rows=%s",runtime.trace,runtime.waitRows).isLessThan(end);Thread.sleep(10);}
    }
    assertThat(now()).isBefore(expiry);
  }
  private static void assertCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable action, BusinessOperationStorageException.Code code) {
    assertThatThrownBy(action).isInstanceOfSatisfying(BusinessOperationStorageException.class, fixed -> { assertThat(fixed.code()).isEqualTo(code);fixed.addSuppressed(new IllegalStateException("synthetic-private"));assertThat(fixed.getSuppressed()).isEmpty(); }).hasMessage(code.name()).hasNoCause();
  }
  private ObjectNode operation() {
    ObjectNode value=(ObjectNode)JsonSupport.read(Path.of(System.getProperty("tracepermit.repositoryRoot")).resolve("contracts/vectors/business-operation-v1.json")).path("operation").deepCopy();
    value.put("operationId",UUID.randomUUID().toString());((ArrayNode)value.path("transitions")).remove(3);
    ((ObjectNode)value.path("authorization")).put("status","evaluating").put("version",1).put("updatedAt",value.path("createdAt").asText());
    value.put("updatedAt",value.path("transitions").get(2).path("at").asText());
    ObjectNode action=(ObjectNode)value.path("canonicalAction").path("action");ObjectNode args=(ObjectNode)action.path("arguments");
    args.remove("owner");args.put("repository",expected.targetBinding().repository()).put("head",expected.targetBinding().head()).put("base","main");
    ((ObjectNode)action.path("target")).put("accountId",expected.targetBinding().repository()).put("resourceType","pull_request").put("environment",expected.environment());
    value.set("resourceBinding",expected.resourceBinding());value.putObject("policyRelease").put("policyId",expected.policyRelease().policyId()).put("version",1).put("contentHash",expected.policyRelease().contentHash());
    var artifacts=value.putArray("artifactDigests");new TreeMap<>(expected.artifactDigests()).forEach((kind,hash)->artifacts.addObject().put("kind",kind).put("digest",hash));
    value.put("canonicalActionHash",CanonicalJson.sha256(value.path("canonicalAction"))).put("semanticPayloadDigest",CanonicalJson.sha256(action)).put("resourceBindingHash",CanonicalJson.sha256(value.path("resourceBinding")));
    value.put("targetIdempotencyKeyHash",BusinessOperationValidator.targetIdempotencyKeyHash(value.path("operationId").asText(),expected.adapterId(),1));
    return (ObjectNode)BusinessOperationValidator.validate(value);
  }
  private static final class Crash extends RuntimeException { private static final long serialVersionUID=1L; }
  private final class Source implements DataSource {
    private final String user, password;
    private final String namespace;
    volatile String fault = "none", watch = "none";
    volatile CountDownLatch attempt;
    final ConcurrentLinkedQueue<String> trace = new ConcurrentLinkedQueue<>();
    final Set<String> categories = ConcurrentHashMap.newKeySet();
    volatile int waitRows = -1;
    final AtomicInteger pid = new AtomicInteger(), open = new AtomicInteger();

    Source(String user, String password) { this(null, user, password); }
    Source(String namespace, String user, String password) {
      this.namespace = namespace; this.user = user; this.password = password;
    }

    @Override public Connection getConnection() throws SQLException {
      String url = database.getJdbcUrl() + (database.getJdbcUrl().contains("?") ? "&" : "?")
          + (pg() ? "connectTimeout=5&socketTimeout=15" : "connectTimeout=5000&socketTimeout=15000");
      if (namespace != null) url = url.replace("/" + database.getDatabaseName(), "/" + namespace);
      if (!watch.equals("none")) trace.add("connect_begin=" + System.nanoTime());
      Connection delegate;
      try { delegate = DriverManager.getConnection(url, user, password); }
      catch (SQLException failure) { category("connect", failure, 0); throw failure; }
      if (!watch.equals("none")) trace.add("connect_ready=" + System.nanoTime());
      open.incrementAndGet();
      boolean[] committed = {false}, closed = {false}; String[] kind = {"none"};
      return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {Connection.class}, (proxy, method, args) -> {
        if (method.getName().equals("commit")) {
          if (fault.equals("commit:" + kind[0])) throw new SQLException("synthetic-private");
          delegate.commit(); committed[0] = true;
          if (fault.equals("after-commit:" + kind[0])) throw new SQLException("synthetic-private");
          return null;
        }
        if (method.getName().equals("rollback") && fault.equals("rollback:" + kind[0])) {
          delegate.rollback(); throw new SQLException("synthetic-private");
        }
        if (method.getName().equals("close")) {
          try { delegate.close(); }
          finally { if (!closed[0]) { open.decrementAndGet(); closed[0] = true; } }
          if (fault.equals("close-any") || (committed[0] && fault.equals("close:" + kind[0]))) throw new SQLException("synthetic-private");
          return null;
        }
        Object result;
        try { result = invoke(method, delegate, args); }
        catch (SQLException failure) { category("connection_" + method.getName(), failure, 0); throw failure; }
        if (method.getName().equals("prepareStatement") && args[0] instanceof String sql) {
          Map<Integer, Object> params = new HashMap<>();
          return Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {PreparedStatement.class}, (ignored, call, values) -> {
            if (call.getName().startsWith("set") && values != null && values.length >= 2 && values[0] instanceof Integer index) params.put(index, values[1]);
            boolean execute = call.getName().equals("executeUpdate") || call.getName().equals("executeQuery");
            String phase = "none";
            if (execute && sql.contains("INSERT INTO tp_operation_delivery_events")) {
              kind[0] = JsonSupport.MAPPER.readTree((String) params.get(4)).path("kind").asText();
              phase = "event:" + kind[0];
            } else if (execute && sql.contains("INSERT INTO tp_operation_lifecycle_facts")) {
              phase = "fact:" + params.get(2);
            } else if (execute && (fault.startsWith("head:") || fault.startsWith("after-head:")) && sql.contains("UPDATE tp_operation_lifecycle_heads")) {
              phase = "head:" + JsonSupport.MAPPER.readTree((String) params.get(2)).path("execution").path("status").asText();
            }
            boolean watched = execute && !watch.equals("none") && sql.contains(watch)
                && (sql.contains("FOR UPDATE") || watch.startsWith("INSERT")) && attempt != null;
            if (watched) {
              try (var query = delegate.prepareStatement(pg() ? "SELECT pg_backend_pid()" : "SELECT CONNECTION_ID()")) {
                query.setQueryTimeout(3);
                try (var rows = query.executeQuery()) { assertThat(rows.next()).isTrue(); pid.set(rows.getInt(1)); }
              }
              trace.add("wait_statement_begin_pid=" + pid.get() + ";nano=" + System.nanoTime());
              attempt.countDown();
            }
            if (!phase.equals("none") && fault.equals(phase)) throw new SQLException("synthetic-private");
            long began = System.nanoTime();
            Object answer;
            try { answer = invoke(call, result, values); }
            catch (SQLException failure) {
              String label = sql.contains("FOR UPDATE") ? sql.contains("tp_operation_authority_controls") ? sql.contains("NOWAIT") ? "control_try_lock" : "control_lock" : "head_lock"
                  : phase.equals("none") ? "read" : phase.replace(':', '_');
              category(label, failure, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - began)); throw failure;
            }
            if (watched) trace.add("wait_statement_end=" + System.nanoTime());
            if (!phase.equals("none") && (fault.equals("after-" + phase)
                || (phase.equals("event:" + kind[0]) && fault.equals("rollback:" + kind[0])))) throw new SQLException("synthetic-private");
            return answer;
          });
        }
        return result;
      });
    }
    private Object invoke(java.lang.reflect.Method method, Object target, Object[] args) throws Throwable {
      try { return method.invoke(target, args); }
      catch (InvocationTargetException failure) { throw failure.getCause(); }
    }
    private void category(String phase, SQLException failure, long elapsedMillis) {
      String state = failure.getSQLState();
      categories.add(phase + ":state=" + (state != null && state.matches("[A-Z0-9]{5}") ? state : "unclassified")
          + ";vendor=" + failure.getErrorCode() + ";elapsedMillis=" + elapsedMillis);
    }
    @Override public Connection getConnection(String user, String password) throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override public PrintWriter getLogWriter() { return null; }
    @Override public void setLogWriter(PrintWriter writer) {}
    @Override public void setLoginTimeout(int seconds) {}
    @Override public int getLoginTimeout() { return 5; }
    @Override public Logger getParentLogger() throws SQLFeatureNotSupportedException { throw new SQLFeatureNotSupportedException(); }
    @Override public <T> T unwrap(Class<T> type) throws SQLException { throw new SQLFeatureNotSupportedException(); }
    @Override public boolean isWrapperFor(Class<?> type) { return false; }
  }
}
