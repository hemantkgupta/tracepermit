package io.tracepermit.agentcore.registration;

import static io.tracepermit.agentcore.registration.RegistrationIdentity.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import io.tracepermit.adapters.remote.agentcore.AgentCoreRegistrationIngress;
import io.tracepermit.adapters.remote.github.GitHubBusinessOperationBinding;
import io.tracepermit.contracts.BusinessOperationValidator;
import io.tracepermit.contracts.CanonicalJson;
import io.tracepermit.contracts.JsonSupport;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Actual signature verification, replay JDBC and registration JDBC through the supported factory. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Timeout(180)
abstract class DurableRegistrationContract {
  private RegistrationDatabases db;
  private DurableAgentCoreRegistration registration;
  abstract String image();

  @BeforeAll void start() { db = new RegistrationDatabases(image()); }
  @AfterAll void stop() { if (db != null) db.close(); }
  @BeforeEach void reset() {
    db.reset(); registration = create(); db.replay.reset(); db.operations.reset();
  }
  @AfterEach void allConnectionsClosedAndNoExecutionTables() {
    assertThat(db.replay.poolCloses.get()).isZero(); assertThat(db.operations.poolCloses.get()).isZero();
    assertThat(db.replay.opens.get()).isEqualTo(db.replay.closes.get());
    assertThat(db.operations.opens.get()).isEqualTo(db.operations.closes.get());
    try (var connection = db.operationAdmin.getConnection();
         var rows = connection.getMetaData().getTables(connection.getCatalog(), db.postgres ? "public" : null,
             "%", new String[] {"TABLE", "VIEW"})) {
      Set<String> names = new java.util.TreeSet<>();
      while (rows.next()) names.add(rows.getString("TABLE_NAME"));
      assertThat(names).containsExactlyInAnyOrder("tp_operation_schema_history", "tp_business_operations",
          "tp_business_operation_facts", "tp_operation_lifecycle_heads", "tp_operation_lifecycle_facts");
    } catch (SQLException failure) { throw new AssertionError("SYNTHETIC_INVENTORY_FAILED"); }
    assertThat(count(db.operationAdmin, "tp_operation_lifecycle_facts")).isZero();
  }

  private DurableAgentCoreRegistration create() {
    return DurableAgentCoreRegistration.create(verifier(), enrollment(), CLOCK,
        db.replay, db.operations, 128, Duration.ofSeconds(2));
  }

  @Test void publicSurfaceHasNoRepositoryReplayOrExecutionOverride() {
    assertThat(DurableAgentCoreRegistration.class.getConstructors()).isEmpty();
    assertThat(java.util.Arrays.stream(DurableAgentCoreRegistration.class.getDeclaredMethods())
        .filter(method -> java.lang.reflect.Modifier.isPublic(method.getModifiers()))
        .map(java.lang.reflect.Method::getName))
        .containsExactlyInAnyOrder("create", "organization", "register");
    assertThat(java.util.Arrays.stream(AgentCoreRegistrationIngress.Receipt.class.getRecordComponents())
        .map(java.lang.reflect.RecordComponent::getName)).containsExactly("created", "operationId");
    counts(0, 0);
  }

  @Test void signedRegistrationReconstructionFreshRetryAndImmutablePolicy() {
    String authorization = token();
    var first = registration.register(authorization, null, bytes(request()));
    assertThat(first.created()).isTrue(); assertThat(first.executionEnabled()).isFalse();
    counts(1, 1); JsonNode original = original(); String hash = CanonicalJson.sha256(original);
    assertThat(BusinessOperationValidator.validate(original).isObject()).isTrue();
    assertThat(original.path("canonicalAction").path("identity").path("subject").path("type").asText()).isEqualTo("workload");
    assertThat(original.path("canonicalAction").path("lineage").path("delegations").size()).isZero();
    assertThat(original.toString().contains(authorization)).isFalse();
    assertThat(original.path("canonicalAction").path("identity").path("evidence").get(0)
        .path("credentialFingerprint").asText()).matches("sha256:[a-f0-9]{64}");

    var fresh = DurableAgentCoreRegistration.create(verifier(), enrollment(), CLOCK,
        db.replay.fresh(), db.operations.fresh(), 128, Duration.ofSeconds(2));
    rejected(() -> fresh.register(authorization, null, bytes(request())), "AGENTCORE_REGISTRATION_REJECTED");
    counts(1, 1);
    var retry = fresh.register(token(), null, bytes(request()));
    assertThat(retry.created()).isFalse(); assertThat(retry.operationId()).isEqualTo(first.operationId());

    var profile = enrollment().targetProfile();
    var changed = new GitHubBusinessOperationBinding.ExpectedProfile(profile.adapterId(), profile.environment(),
        profile.recoveryGeneration(), new GitHubBusinessOperationBinding.PolicyRelease("synthetic-new-policy", 2,
            "sha256:" + "8".repeat(64)), java.util.Map.of("adapter_release", "sha256:" + "9".repeat(64)), profile.targetBinding());
    var changedFactory = DurableAgentCoreRegistration.create(verifier(), enrollment(SCOPE, changed), CLOCK,
        db.replay.fresh(), db.operations.fresh(), 128, Duration.ofSeconds(2));
    assertThat(changedFactory.register(token(), null, bytes(request())).operationId()).isEqualTo(first.operationId());
    assertThat(CanonicalJson.sha256(original())).isEqualTo(hash); counts(3, 1);
    assertStoredFactsAndHead(original);
  }

  @Test void changedContentSpendsNewTokenAndChangingRequestKeyCannotReuseIt() {
    registration.register(token(), null, bytes(request()));
    String conflicting = token();
    rejected(() -> registration.register(conflicting, null, bytes(request().put("title", "Synthetic changed"))),
        "AGENTCORE_REQUEST_KEY_CONFLICT");
    int calls = db.operations.opens.get();
    rejected(() -> registration.register(conflicting, null, bytes(request().put("clientRequestKey", "another-key"))),
        "AGENTCORE_REGISTRATION_REJECTED");
    assertThat(db.operations.opens.get()).isEqualTo(calls); counts(2, 1);
  }

  @Test void thirtyTwoIndependentInstancesContendForOneToken() throws Exception {
    List<DurableAgentCoreRegistration> instances = new ArrayList<>();
    for (int i = 0; i < 32; i++) instances.add(create());
    db.replay.reset(); db.operations.reset();
    String authorization = token(); AtomicInteger created = new AtomicInteger(), refused = new AtomicInteger();
    race(i -> {
      try {
        var receipt = instances.get(i).register(authorization, null, bytes(request()));
        assertThat(receipt.executionEnabled()).isFalse(); if (receipt.created()) created.incrementAndGet();
      } catch (AgentCoreRegistrationIngress.IngressException failure) {
        assertThat(failure.code()).isEqualTo("AGENTCORE_REGISTRATION_REJECTED"); refused.incrementAndGet();
      }
    });
    assertThat(created.get()).isEqualTo(1); assertThat(refused.get()).isEqualTo(31);
    assertThat(db.operations.commits.get()).isEqualTo(1); counts(1, 1);
  }

  @Test void thirtyTwoVerifiedTokensContendForOneScopedRequestKey() throws Exception {
    List<DurableAgentCoreRegistration> instances = new ArrayList<>(); List<String> tokens = new ArrayList<>();
    for (int i = 0; i < 32; i++) { instances.add(create()); tokens.add(token()); }
    db.replay.reset(); db.operations.reset();
    AtomicInteger created = new AtomicInteger(), existing = new AtomicInteger();
    Set<String> ids = java.util.concurrent.ConcurrentHashMap.newKeySet();
    race(i -> {
      var receipt = instances.get(i).register(tokens.get(i), null, bytes(request()));
      assertThat(receipt.executionEnabled()).isFalse(); ids.add(receipt.operationId());
      if (receipt.created()) created.incrementAndGet(); else existing.incrementAndGet();
    });
    assertThat(created.get()).isEqualTo(1); assertThat(existing.get()).isEqualTo(31);
    assertThat(ids).hasSize(1); counts(32, 1);
  }

  @Test void actualTwoJvmRestartRejectsSpentTokenAndNonDurableControlFailsTheOracle() throws Exception {
    String authorization = token();
    assertThat(RegistrationChild.call(db, authorization, bytes(request()), false)).isEqualTo("CREATED");
    assertThat(RegistrationChild.call(db, authorization, bytes(request()), false)).isEqualTo("REPLAY_REJECTED");
    counts(1, 1);
    String controlToken = token(); byte[] controlBody = bytes(request().put("clientRequestKey", "control-only"));
    assertThat(RegistrationChild.call(db, controlToken, controlBody, true)).isEqualTo("CREATED");
    String controlResult = RegistrationChild.call(db, controlToken, controlBody, true);
    assertThat(controlResult).isEqualTo("EXISTING");
    assertThatThrownBy(() -> assertThat(controlResult).isEqualTo("REPLAY_REJECTED"))
        .as("same persistence oracle distinguishes deliberately process-local replay")
        .isInstanceOf(AssertionError.class);
    counts(1, 2);
  }

  @Test void saturationRefusesBeforeRegistrationWithoutEviction() {
    RegistrationDatabases.execute(db.replayAdmin, "UPDATE tp_workload_replay_state SET capacity=1 WHERE singleton=1");
    var bounded = DurableAgentCoreRegistration.create(verifier(), enrollment(), CLOCK, db.replay, db.operations,
        1, Duration.ofSeconds(2)); db.operations.reset();
    bounded.register(token(), null, bytes(request())); int calls = db.operations.opens.get();
    rejected(() -> bounded.register(token(), null, bytes(request().put("clientRequestKey", "overflow"))),
        "AGENTCORE_REGISTRATION_REJECTED");
    assertThat(db.operations.opens.get()).isEqualTo(calls); counts(1, 1);
  }

  @ParameterizedTest @ValueSource(strings = {"capacity", "replay-schema", "operation-schema", "unchecked", "zero-budget", "null-verifier"})
  void startupIsValidateOnlyFixedAndHasNoFallback(String kind) {
    String table = kind.equals("replay-schema") ? "tp_workload_replay_claims" : "tp_business_operation_facts";
    var source = kind.equals("replay-schema") ? db.replayAdmin : db.operationAdmin;
    boolean rename = kind.endsWith("-schema");
    if (rename) RegistrationDatabases.execute(source, "ALTER TABLE " + table + " RENAME TO tp_unready_fixture");
    if (kind.equals("unchecked")) db.replay.fault = BoundedJdbcSource.Fault.UNCHECKED_BORROW;
    try {
      assertThatThrownBy(() -> DurableAgentCoreRegistration.create(kind.equals("null-verifier") ? null : verifier(),
          enrollment(), CLOCK, db.replay, db.operations, kind.equals("capacity") ? 129 : 128,
          kind.equals("zero-budget") ? Duration.ZERO : Duration.ofSeconds(2)))
          .isInstanceOf(DurableAgentCoreRegistration.InitializationException.class)
          .hasMessage("DURABLE_REGISTRATION_NOT_READY").hasNoCause().satisfies(failure -> {
            failure.addSuppressed(new IllegalStateException(BoundedJdbcSource.CANARY));
            failure.initCause(new IllegalStateException(BoundedJdbcSource.CANARY));
            assertThat(failure.getCause()).isNull(); assertThat(failure.getSuppressed()).isEmpty();
          });
      assertThat(db.replay.writes.get()).isZero(); assertThat(db.operations.writes.get()).isZero();
    } finally {
      db.replay.fault = BoundedJdbcSource.Fault.NONE;
      if (rename) RegistrationDatabases.execute(source, "ALTER TABLE tp_unready_fixture RENAME TO " + table);
    }
    counts(0, 0);
  }

  @Test void realReplayPermissionDenialHasPositiveOwnerControlAndNoRegistration() {
    db.grant(db.replayAdmin, "INSERT", "tp_workload_replay_claims", false);
    rejected(() -> registration.register(token(), null, bytes(request())), "AGENTCORE_REGISTRATION_REJECTED");
    assertThat(db.replay.denied.get()).isEqualTo(1); assertThat(db.operations.opens.get()).isZero(); counts(0, 0);
    var owner = DurableAgentCoreRegistration.create(verifier(), enrollment(), CLOCK,
        db.replayAdmin, db.operationAdmin, 128, Duration.ofSeconds(2));
    assertThat(owner.register(token(), null, bytes(request())).created()).isTrue(); counts(1, 1);
  }

  @Test void realRegistrationPermissionFailureBurnsTokenAndFreshIdentityCanRetry() {
    db.grant(db.operationAdmin, "INSERT", "tp_business_operation_facts", false);
    String authorization = token();
    rejected(() -> registration.register(authorization, null, bytes(request())), "AGENTCORE_STORAGE_UNAVAILABLE");
    assertThat(db.operations.denied.get()).isEqualTo(1); counts(1, 0);
    var owner = DurableAgentCoreRegistration.create(verifier(), enrollment(), CLOCK,
        db.replayAdmin, db.operationAdmin, 128, Duration.ofSeconds(2));
    rejected(() -> owner.register(authorization, null, bytes(request())), "AGENTCORE_REGISTRATION_REJECTED");
    assertThat(owner.register(token(), null, bytes(request())).created()).isTrue(); counts(2, 1);
  }

  @ParameterizedTest @EnumSource(value=BoundedJdbcSource.Fault.class,
      names={"AFTER_COMMIT", "CLOSE_COMMIT", "LATE_COMMIT", "LATE_CLOSE"})
  void uncertainReplayAcknowledgementNeverReachesRegistration(BoundedJdbcSource.Fault fault) {
    db.replay.fault = fault; db.replay.deadlineNanos = System.nanoTime() + Duration.ofSeconds(2).toNanos();
    String authorization = token(claims -> claims.put("exp", NOW.plusSeconds(2).getEpochSecond()));
    rejected(() -> registration.register(authorization, null, bytes(request())), "AGENTCORE_REGISTRATION_REJECTED");
    if (fault.name().startsWith("LATE")) assertThat(db.replay.delayReached.get()).isTrue();
    assertThat(db.replay.commits.get()).isEqualTo(1); assertThat(db.operations.opens.get()).isZero(); counts(1, 0);
    db.replay.fault = BoundedJdbcSource.Fault.NONE;
    rejected(() -> registration.register(authorization, null, bytes(request())), "AGENTCORE_REGISTRATION_REJECTED");
    assertThat(db.operations.opens.get()).isZero(); counts(1, 0);
  }

  @ParameterizedTest @EnumSource(value=BoundedJdbcSource.Fault.class,
      names={"SECOND_FACT", "AFTER_COMMIT", "CLOSE_COMMIT"})
  void registrationRollbackAndAmbiguityPreserveSpentIdentity(BoundedJdbcSource.Fault fault) {
    db.operations.fault = fault; String authorization = token();
    boolean committed = fault != BoundedJdbcSource.Fault.SECOND_FACT;
    rejected(() -> registration.register(authorization, null, bytes(request())),
        committed ? "AGENTCORE_REGISTRATION_UNKNOWN" : "AGENTCORE_STORAGE_UNAVAILABLE");
    counts(1, committed ? 1 : 0);
    if (committed) assertStoredFactsAndHead(original());
    db.operations.fault = BoundedJdbcSource.Fault.NONE; int calls = db.operations.opens.get();
    rejected(() -> registration.register(authorization, null, bytes(request())), "AGENTCORE_REGISTRATION_REJECTED");
    assertThat(db.operations.opens.get()).isEqualTo(calls);
    var retry = registration.register(token(), null, bytes(request()));
    assertThat(retry.created()).isEqualTo(!committed); assertThat(retry.executionEnabled()).isFalse(); counts(2, 1);
  }

  @ParameterizedTest @ValueSource(strings={"issuer", "audience", "scope", "enrollment", "expired", "signature"})
  void realIdentityFailuresPrecedeBothDatabases(String mutation) throws Exception {
    var selectedVerifier = verifier();
    if (mutation.equals("signature")) {
      var generator = KeyPairGenerator.getInstance("RSA"); generator.initialize(2048);
      selectedVerifier = verifier((RSAPublicKey) generator.generateKeyPair().getPublic());
    }
    var selected = DurableAgentCoreRegistration.create(selectedVerifier, enrollment(), CLOCK,
        db.replay, db.operations, 128, Duration.ofSeconds(2)); db.replay.reset(); db.operations.reset();
    String authorization = token(claims -> {
      switch (mutation) {
        case "issuer" -> claims.put("iss", "https://other.example.invalid");
        case "audience" -> claims.put("aud", "https://other.example.invalid");
        case "scope" -> claims.put("scope", "operations.other");
        case "enrollment" -> claims.put("sub", "workload:other");
        case "expired" -> claims.put("exp", NOW.minusSeconds(1).getEpochSecond());
        default -> { }
      }
    });
    rejected(() -> selected.register(authorization, null, bytes(request())), "AGENTCORE_IDENTITY_REJECTED");
    assertNoCalls(); counts(0, 0);
  }

  @ParameterizedTest @ValueSource(strings={"authenticated", "verified", "delegated", "approved", "humanAuthorization"})
  void bodyCannotCreateAuthorityAndHumanTokenPairIsAlwaysRefused(String field) {
    rejected(() -> registration.register(token(), null, bytes(request().put(field, true))), "AGENTCORE_REQUEST_INVALID");
    rejected(() -> registration.register(token(), token(), bytes(request())), "AGENTCORE_DELEGATION_CONTRACT_REQUIRED");
    assertNoCalls(); counts(0, 0);
  }

  @ParameterizedTest @ValueSource(booleans={false,true})
  void realCounterLockWaitHasPositivePreExpiryControlAndExpiryRefusal(boolean expire) throws Exception {
    try (var lock = db.replayAdmin.getConnection()) {
      lock.setAutoCommit(false);
      try (var statement = lock.prepareStatement("UPDATE tp_workload_replay_state SET total_claims=total_claims WHERE singleton=1")) {
        statement.setQueryTimeout(5); statement.executeUpdate();
      }
      var entered = new CountDownLatch(1); db.replay.counterEntered = entered;
      var executor = Executors.newSingleThreadExecutor();
      String authorization = expire ? token(claims -> claims.put("exp", NOW.plusSeconds(2).getEpochSecond())) : token();
      try {
        var future = executor.submit(() -> {
          try { registration.register(authorization, null, bytes(request())); return "CREATED"; }
          catch (AgentCoreRegistrationIngress.IngressException failure) { return failure.code(); }
        });
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
        TimeUnit.MILLISECONDS.sleep(200);
        assertThat(future.isDone()).as("real UPDATE remains blocked behind the held counter lock").isFalse();
        if (expire) TimeUnit.MILLISECONDS.sleep(2000);
        lock.commit();
        assertThat(future.get(10, TimeUnit.SECONDS)).isEqualTo(expire ? "AGENTCORE_REGISTRATION_REJECTED" : "CREATED");
        counts(expire ? 0 : 1, expire ? 0 : 1);
        if (expire) assertThat(db.operations.opens.get()).isZero();
      } finally {
        lock.rollback(); executor.shutdownNow();
        assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
      }
    } catch (SQLException failure) { throw new AssertionError("SYNTHETIC_LOCK_FIXTURE_FAILED"); }
  }

  private void assertNoCalls() {
    assertThat(db.replay.opens.get()).isZero(); assertThat(db.operations.opens.get()).isZero();
  }
  private static long count(BoundedJdbcSource source, String table) { return RegistrationDatabases.count(source, table); }
  private void counts(long replay, long operations) {
    assertThat(count(db.replayAdmin, "tp_workload_replay_claims")).isEqualTo(replay);
    try (var c = db.replayAdmin.getConnection(); var s = c.createStatement()) {
      s.setQueryTimeout(5);
      try (var r = s.executeQuery("SELECT total_claims FROM tp_workload_replay_state WHERE singleton=1")) {
        assertThat(r.next()).isTrue(); assertThat(r.getLong(1)).isEqualTo(replay);
      }
    } catch (SQLException failure) { throw new AssertionError("SYNTHETIC_COUNTER_READ_FAILED"); }
    assertThat(count(db.operationAdmin, "tp_business_operations")).isEqualTo(operations);
    assertThat(count(db.operationAdmin, "tp_business_operation_facts")).isEqualTo(operations * 3);
    assertThat(count(db.operationAdmin, "tp_operation_lifecycle_heads")).isEqualTo(operations);
    assertThat(count(db.operationAdmin, "tp_operation_lifecycle_facts")).isZero();
  }
  private JsonNode original() {
    try (var c = db.operationAdmin.getConnection(); var s = c.createStatement()) {
      s.setQueryTimeout(5);
      try (var r = s.executeQuery("SELECT snapshot_json FROM tp_business_operations")) {
        assertThat(r.next()).isTrue(); return JsonSupport.MAPPER.readTree(r.getString(1));
      }
    } catch (Exception failure) { throw new AssertionError("SYNTHETIC_SNAPSHOT_READ_FAILED"); }
  }
  private void assertStoredFactsAndHead(JsonNode original) {
    try (var c = db.operationAdmin.getConnection(); var s = c.createStatement()) {
      s.setQueryTimeout(5);
      try (var r = s.executeQuery("SELECT sequence_number, fact_json FROM tp_business_operation_facts ORDER BY sequence_number")) {
        int i=0;
        while (r.next()) {
          assertThat(r.getInt(1)).isEqualTo(i+1);
          assertThat(CanonicalJson.sha256(JsonSupport.MAPPER.readTree(r.getString(2))))
              .isEqualTo(CanonicalJson.sha256(original.path("transitions").get(i++)));
        }
        assertThat(i).isEqualTo(3);
      }
      try (var r = s.executeQuery("SELECT snapshot_hash, snapshot_json FROM tp_operation_lifecycle_heads")) {
        assertThat(r.next()).isTrue(); assertThat(r.getString(1)).isEqualTo(CanonicalJson.sha256(original));
        assertThat(CanonicalJson.sha256(JsonSupport.MAPPER.readTree(r.getString(2)))).isEqualTo(CanonicalJson.sha256(original));
      }
    } catch (Exception failure) { throw new AssertionError("SYNTHETIC_FACT_HEAD_READ_FAILED"); }
  }
  static void rejected(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, String code) {
    assertThatThrownBy(call).isInstanceOf(AgentCoreRegistrationIngress.IngressException.class)
        .hasMessage(code).hasNoCause().satisfies(error -> assertThat(error.getSuppressed()).isEmpty());
  }
  @FunctionalInterface private interface Contender { void run(int i) throws Exception; }
  private static void race(Contender call) throws Exception {
    var executor = Executors.newFixedThreadPool(32);
    var ready = new CountDownLatch(32); var start = new CountDownLatch(1);
    try {
      var futures = new ArrayList<java.util.concurrent.Future<?>>();
      for (int i=0;i<32;i++) {
        final int index=i;
        futures.add(executor.submit(() -> { ready.countDown();
          if (!start.await(10,TimeUnit.SECONDS)) throw new AssertionError("SYNTHETIC_START_TIMEOUT");
          call.run(index); return null; }));
      }
      assertThat(ready.await(10,TimeUnit.SECONDS)).isTrue(); start.countDown();
      long deadline=System.nanoTime()+Duration.ofSeconds(30).toNanos();
      for(var future:futures) future.get(Math.max(1,deadline-System.nanoTime()),TimeUnit.NANOSECONDS);
    } finally { start.countDown(); executor.shutdownNow(); assertThat(executor.awaitTermination(10,TimeUnit.SECONDS)).isTrue(); }
  }
}
