package io.tracepermit.persistence.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tracepermit.execution.ApprovalBinding;
import io.tracepermit.execution.ApprovalDecision;
import io.tracepermit.execution.ApprovalExecutionClaim;
import io.tracepermit.execution.DurableExecutionClaim;
import io.tracepermit.execution.TenantPartition;
import io.tracepermit.contracts.CanonicalJson;
import io.tracepermit.contracts.JsonSupport;
import io.tracepermit.application.DurableExecutionService;
import io.tracepermit.application.HumanApprovalService;
import io.tracepermit.application.SecurityReadiness;
import io.tracepermit.application.SimulatedTarget;
import io.tracepermit.application.TrustedReviewerContext;
import io.tracepermit.application.TrustedTenantContext;
import io.tracepermit.policy.PolicyEvaluator;
import io.tracepermit.security.ExecutionPermitAuthority;
import io.tracepermit.security.InMemoryPermitConsumptionStore;
import java.nio.file.Path;
import java.util.Set;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

/** Fresh generated databases only; quarantine is never reset to make another test pass. */
final class PostgresRecoveryQuarantineTest {
  private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.4-alpine");
  private static final Instant NOW = Instant.parse("2026-10-17T12:00:00Z");
  private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
  private static final TenantPartition TENANT = new TenantPartition("synthetic");
  private PGSimpleDataSource source;
  private PostgresDurableEnforcementState state;

  @BeforeAll static void start() { POSTGRES.start(); }
  @AfterAll static void stop() { POSTGRES.stop(); }

  @BeforeEach void fresh() throws Exception {
    String name = "recovery_" + UUID.randomUUID().toString().replace("-", "");
    try (var c = configured(POSTGRES.getDatabaseName()).getConnection(); var sql = c.createStatement()) {
      sql.execute("CREATE DATABASE " + name + " TEMPLATE template0");
    }
    source = configured(name);
    PostgresDatabaseLifecycle.initializeEmpty(source);
    state = repository(source);
  }

  @Test void operatorDeclarationIsDurableIdempotentAndNotAnAuthorityReset() {
    assertThat(PostgresRecoveryQuarantine.status(source)).isEqualTo(
        new PostgresRecoveryQuarantine.Status("ready", null, null));
    var first = PostgresRecoveryQuarantine.quarantine(source, NOW);
    assertThat(first.mode()).isEqualTo("quarantined");
    assertThat(first.recoveryId()).isNotNull();
    assertThat(first.quarantinedAt()).isEqualTo(NOW);
    assertThat(PostgresRecoveryQuarantine.quarantine(source, NOW.plusSeconds(60))).isEqualTo(first);
    repository(source); // strict opening for inspection remains possible, unlike effectful Main startup
    assertThat(PostgresRecoveryQuarantine.status(source)).isEqualTo(first);
  }

  @ParameterizedTest @ValueSource(strings = {"synthetic", "another-tenant"})
  void existingReplicasAndAllTenantsCannotCreateClaimsAfterDeclaration(String tenant) throws Exception {
    var other = repository(source);
    PostgresRecoveryQuarantine.quarantine(source, NOW);
    assertThatThrownBy(() -> other.claim(claim(new TenantPartition(tenant), "new")))
        .hasMessage("DATABASE_RECOVERY_QUARANTINED");
    assertThat(count("tp_consumed_execution_permits")).isZero();
    assertThat(count("tp_execution_attempts")).isZero();
    assertThat(count("tp_evidence_events")).isZero();
  }

  @Test void pendingReviewAndApprovedConsumptionAreFrozenWithoutPartialChanges() throws Exception {
    var pending = binding("pending"); var approved = binding("approved");
    state.createPending(pending); state.createPending(approved); state.decide(decision(approved));
    long evidence = count("tp_evidence_events");
    long transitions = count("tp_approval_transitions");
    PostgresRecoveryQuarantine.quarantine(source, NOW);
    assertThatThrownBy(() -> state.createPending(binding("new"))).hasMessage("DATABASE_RECOVERY_QUARANTINED");
    assertThatThrownBy(() -> state.decide(decision(pending))).hasMessage("DATABASE_RECOVERY_QUARANTINED");
    assertThatThrownBy(() -> state.consumeAndClaim(approvalClaim(approved))).hasMessage("DATABASE_RECOVERY_QUARANTINED");
    assertThat(state.find(TENANT, "pending").status()).isEqualTo("pending");
    assertThat(state.find(TENANT, "approved").status()).isEqualTo("approved");
    assertThat(count("tp_approval_transitions")).isEqualTo(transitions);
    assertThat(count("tp_evidence_events")).isEqualTo(evidence);
    assertThat(count("tp_execution_attempts")).isZero();
    assertThat(count("tp_consumed_execution_permits")).isZero();
  }

  @Test void standalonePermitConsumptionAlsoFailsClosed() throws Exception {
    var store = PostgresPermitConsumptionStore.openExisting(source);
    assertThat(store.consume(TENANT, "before", NOW.plusSeconds(30), hash('a'), NOW)).isTrue();
    PostgresRecoveryQuarantine.quarantine(source, NOW);
    assertThatThrownBy(() -> store.consume(TENANT, "after", NOW.plusSeconds(30), hash('a'), NOW))
        .hasMessage("DATABASE_RECOVERY_QUARANTINED");
    assertThat(store.isConsumed(TENANT, "before")).isTrue();
    assertThat(store.isConsumed(TENANT, "after")).isFalse();
    assertThat(count("tp_consumed_execution_permits")).isEqualTo(1);
  }

  @ParameterizedTest @ValueSource(strings = {"succeeded", "failed", "unknown"})
  void admittedWorkMayRecordOutcomeAndUnknownMayReconcileWithoutNewClaim(String outcome) throws Exception {
    var attempt = state.claim(claim(TENANT, "admitted")).attempt();
    PostgresRecoveryQuarantine.quarantine(source, NOW);
    var completed = state.complete(TENANT, attempt.attemptId(), outcome, "SIMULATOR_OBSERVED", NOW.plusSeconds(1));
    assertThat(completed.status()).isEqualTo(outcome);
    if (outcome.equals("unknown")) {
      assertThat(state.reconcile(TENANT, "simulator", hash('a'), "succeeded", "LOOKUP_ONLY_CONFIRMED", NOW.plusSeconds(2)).status())
          .isEqualTo("succeeded");
    }
    assertThat(count("tp_execution_attempts")).isEqualTo(1);
    assertThat(count("tp_consumed_execution_permits")).isEqualTo(1);
    assertThat(count("tp_evidence_events")).isEqualTo(outcome.equals("unknown") ? 3 : 2);
    assertThat(count("tp_evidence_outbox")).isEqualTo(count("tp_evidence_events"));
  }

  @Test void runtimeWithBroadDmlCannotDeclareOrReverseQuarantine() throws Exception {
    String role = "runtime_" + UUID.randomUUID().toString().replace("-", "");
    sql("CREATE ROLE " + role + " LOGIN PASSWORD 'synthetic-only'; GRANT USAGE ON SCHEMA public TO " + role
        + "; GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO " + role);
    var runtime = configured(source.getDatabaseName()); runtime.setUser(role); runtime.setPassword("synthetic-only");
    assertThat(PostgresRecoveryQuarantine.status(runtime).mode()).isEqualTo("ready");
    // Required row-lock permission does not confer the operator transition.
    assertThat(repository(runtime).claim(claim(TENANT, "runtime")).started()).isTrue();
    assertThatThrownBy(() -> PostgresRecoveryQuarantine.quarantine(runtime, NOW)).hasMessage("RECOVERY_OPERATOR_REQUIRED");
    try (var c = runtime.getConnection(); var q = c.createStatement()) {
      assertThatThrownBy(() -> q.execute("UPDATE public.tp_recovery_quarantine SET mode='quarantined', recovery_id=gen_random_uuid(), quarantined_at=now()"))
          .isInstanceOf(SQLException.class).hasMessageContaining("RECOVERY_OPERATOR_REQUIRED");
    }
    PostgresRecoveryQuarantine.quarantine(source, NOW);
    assertThat(PostgresRecoveryQuarantine.status(runtime).mode()).isEqualTo("quarantined");
    assertThatThrownBy(() -> repository(runtime).claim(claim(TENANT, "after"))).hasMessage("DATABASE_RECOVERY_QUARANTINED");
  }

  @ParameterizedTest @ValueSource(strings = {
      "DELETE FROM public.tp_recovery_quarantine", "TRUNCATE public.tp_recovery_quarantine",
      "UPDATE public.tp_recovery_quarantine SET mode='ready', recovery_id=NULL, quarantined_at=NULL",
      "UPDATE public.tp_recovery_quarantine SET quarantined_at=now()",
      "INSERT INTO public.tp_recovery_quarantine(singleton,mode) VALUES(TRUE,'ready')"
  })
  void ordinaryOwnerDmlHasNoResumeDeleteOrOverwritePath(String change) {
    var first = PostgresRecoveryQuarantine.quarantine(source, NOW);
    assertThatThrownBy(() -> sql(change)).isInstanceOf(SQLException.class).hasMessageContaining("RECOVERY_QUARANTINE_IMMUTABLE");
    assertThat(PostgresRecoveryQuarantine.status(source)).isEqualTo(first);
  }

  @Test void rawSqlCannotBypassPermitRequestReviewOrAttemptGate() throws Exception {
    var approved = binding("approved"); state.createPending(approved); state.decide(decision(approved));
    // An unreferenced permit created before quarantine cannot authorize a new attempt afterward.
    sql("INSERT INTO tp_consumed_execution_permits VALUES('synthetic','old',now()+interval '1 hour','" + hash('a') + "',now())");
    PostgresRecoveryQuarantine.quarantine(source, NOW);
    List<String> changes = List.of(
        "INSERT INTO tp_consumed_execution_permits VALUES('synthetic','new',now()+interval '1 hour','" + hash('a') + "',now())",
        "INSERT INTO tp_approval_requests SELECT * FROM tp_approval_requests",
        "INSERT INTO tp_approval_transitions SELECT * FROM tp_approval_transitions",
        "INSERT INTO tp_execution_attempts(tenant_id) VALUES('synthetic')");
    for (String change : changes) {
      assertThatThrownBy(() -> sql(change)).isInstanceOf(SQLException.class).hasMessageContaining("DATABASE_RECOVERY_QUARANTINED");
    }
    assertThat(count("tp_consumed_execution_permits")).isEqualTo(1);
    assertThat(count("tp_execution_attempts")).isZero();
    assertThat(count("tp_approval_requests")).isEqualTo(1);
  }

  @Test void rollbackOfUncommittedDeclarationDoesNotPretendItWasConfirmed() throws Exception {
    try (var c = source.getConnection(); var q = c.createStatement()) {
      c.setAutoCommit(false);
      q.execute("UPDATE public.tp_recovery_quarantine SET mode='quarantined', recovery_id=gen_random_uuid(), quarantined_at=now()");
      c.rollback();
    }
    assertThat(PostgresRecoveryQuarantine.status(source).mode()).isEqualTo("ready");
    assertThat(state.claim(claim(TENANT, "not-quarantined")).started()).isTrue();
  }

  @Test void triggerCannotBeRedirectedToShadowReadyRowBySqlSearchPath() throws Exception {
    PostgresRecoveryQuarantine.quarantine(source, NOW);
    sql("CREATE SCHEMA shadow; CREATE TABLE shadow.tp_recovery_quarantine(singleton boolean, mode text);"
        + " INSERT INTO shadow.tp_recovery_quarantine VALUES(TRUE,'ready')");
    assertThatThrownBy(() -> sql("SET search_path TO shadow, public; INSERT INTO public.tp_consumed_execution_permits"
        + " VALUES('synthetic','shadow-bypass',now()+interval '1 hour','" + hash('a') + "',now())"))
        .isInstanceOf(SQLException.class).hasMessageContaining("DATABASE_RECOVERY_QUARANTINED");
    assertThat(count("tp_consumed_execution_permits")).isZero();
  }

  @Test void lostDeclarationCommitAcknowledgementDoesNotClearCommittedQuarantine() {
    DataSource lostAck = (DataSource) Proxy.newProxyInstance(DataSource.class.getClassLoader(), new Class<?>[] {DataSource.class},
        (p, method, args) -> {
          try {
            Object result = method.invoke(source, args);
            if (!method.getName().equals("getConnection")) return result;
            AtomicBoolean declaring = new AtomicBoolean();
            return Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[] {Connection.class}, (proxy, operation, values) -> {
              if (operation.getName().equals("prepareStatement") && values[0] instanceof String sql
                  && sql.startsWith("UPDATE public.tp_recovery_quarantine")) declaring.set(true);
              try {
                Object value = operation.invoke(result, values);
                if (operation.getName().equals("commit") && declaring.get()) throw new SQLException("Synthetic commit acknowledgement lost");
                return value;
              } catch (InvocationTargetException failure) { throw failure.getCause(); }
            });
          } catch (InvocationTargetException failure) { throw failure.getCause(); }
        });
    assertThatThrownBy(() -> PostgresRecoveryQuarantine.quarantine(lostAck, NOW))
        .isInstanceOf(PostgresStateException.class).hasMessage("Recovery quarantine unconfirmed");
    var committed = PostgresRecoveryQuarantine.status(source);
    assertThat(committed.mode()).isEqualTo("quarantined");
    assertThat(PostgresRecoveryQuarantine.quarantine(source, NOW.plusSeconds(1))).isEqualTo(committed);
    assertThatThrownBy(() -> state.claim(claim(TENANT, "after-lost-ack"))).hasMessage("DATABASE_RECOVERY_QUARANTINED");
  }

  @ParameterizedTest @ValueSource(booleans = {false, true})
  void serviceCannotDispatchAfterQuarantineButPreviouslyAdmittedDispatchCanFinish(boolean approval) throws Exception {
    Path root = Path.of(System.getProperty("tracepermit.repositoryRoot"));
    var tenant = new TenantPartition("acme");
    var context = new TrustedTenantContext(tenant);
    var policy = JsonSupport.read(root.resolve("policies/aws-web-readonly.json"));
    var permits = new ExecutionPermitAuthority(ExecutionPermitAuthority.DEFAULT_ISSUER, "synthetic-key", new byte[32],
        30, CLOCK, () -> "permit-" + UUID.randomUUID(), new InMemoryPermitConsumptionStore());
    AtomicInteger effects = new AtomicInteger();
    SimulatedTarget target = action -> {
      effects.incrementAndGet();
      // The claim committed before dispatch. Quarantine cannot cancel it, nor prevent outcome recording.
      PostgresRecoveryQuarantine.quarantine(source, NOW);
      return JsonSupport.MAPPER.createObjectNode().put("simulated", true);
    };
    Runnable execute;
    String approvalId;
    if (approval) {
      var action = JsonSupport.read(root.resolve("fixtures/api-service-aws-modify.json"));
      var service = new HumanApprovalService(new PolicyEvaluator(), policy, permits, state, state, target,
          () -> SecurityReadiness.allReady(NOW), CLOCK, () -> "approval-" + UUID.randomUUID(),
          "urn:tracepermit:recovery-test", "simulator", 300);
      approvalId = service.requestApproval(context, action).approval().binding().approvalRequestId();
      service.review(new TrustedReviewerContext(tenant, "user:bob@demo.invalid", "human", true,
          Set.of(HumanApprovalService.REVIEWER_ROLE), Set.of(HumanApprovalService.REVIEWER_SCOPE), "synthetic_oidc", hash('e')),
          approvalId, "approve", "REVIEWED");
      execute = () -> service.executeApproved(context, approvalId, action);
    } else {
      var vector = JsonSupport.read(root.resolve("contracts/vectors/execution-permit-v1.json"));
      var action = JsonSupport.read(root.resolve(vector.path("fixture").asText()));
      var service = new DurableExecutionService(new PolicyEvaluator(), policy, permits, state, target,
          () -> SecurityReadiness.allReady(NOW), CLOCK, "urn:tracepermit:recovery-test", "simulator");
      approvalId = null;
      execute = () -> service.execute(context, action);
    }
    execute.run();
    assertThat(effects).hasValue(1);
    assertThat(count("tp_execution_attempts")).isEqualTo(1);
    try (var c = source.getConnection(); var q = c.createStatement(); var r = q.executeQuery("SELECT status FROM tp_execution_attempts")) {
      r.next(); assertThat(r.getString(1)).isEqualTo("succeeded");
    }
    // This existing service does not rely on a new startup check.
    assertThatThrownBy(execute::run).isInstanceOf(IllegalStateException.class);
    assertThat(effects).hasValue(1);
    assertThat(count("tp_execution_attempts")).isEqualTo(1);
    if (approvalId != null) assertThat(state.find(tenant, approvalId).status()).isEqualTo("consumed");
  }

  @Test void claimAlreadyWaitingForQuarantineCommitSeesNewModeNotOldReadySnapshot() throws Exception {
    var claimant = configured(source.getDatabaseName()); claimant.setApplicationName("recovery-waiting-claim");
    var replica = repository(claimant);
    var executor = Executors.newSingleThreadExecutor(runnable -> { var t = new Thread(runnable); t.setDaemon(true); return t; });
    try (var operator = source.getConnection(); var q = operator.createStatement()) {
      operator.setAutoCommit(false);
      q.execute("UPDATE public.tp_recovery_quarantine SET mode='quarantined', recovery_id=gen_random_uuid(), quarantined_at=now()");
      var claim = executor.submit(() -> replica.claim(claim(TENANT, "waiting")));
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
      boolean waiting = false;
      while (!waiting && System.nanoTime() < deadline) {
        try (var c = source.getConnection(); var inspect = c.createStatement(); var r = inspect.executeQuery(
            "SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE application_name='recovery-waiting-claim' AND wait_event_type='Lock')")) {
          r.next(); waiting = r.getBoolean(1);
        }
        if (!waiting) Thread.sleep(10);
      }
      assertThat(waiting).isTrue();
      operator.commit();
      assertThatThrownBy(() -> claim.get(5, TimeUnit.SECONDS)).hasCauseInstanceOf(IllegalStateException.class)
          .hasRootCauseMessage("DATABASE_RECOVERY_QUARANTINED");
      assertThat(count("tp_execution_attempts")).isZero();
      assertThat(count("tp_consumed_execution_permits")).isZero();
    } finally {
      executor.shutdownNow(); assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
    }
  }

  @ParameterizedTest @ValueSource(strings = {"direct", "approval", "permit"})
  void declarationWaitsForPriorClaimCommitThenRejectsLaterClaims(String kind) throws Exception {
    CountDownLatch atCommit = new CountDownLatch(1); CountDownLatch release = new CountDownLatch(1);
    AtomicBoolean armed = new AtomicBoolean();
    DataSource gated = (DataSource) Proxy.newProxyInstance(DataSource.class.getClassLoader(), new Class<?>[] {DataSource.class},
        (p, method, args) -> {
          try {
            Object result = method.invoke(source, args);
            if (!method.getName().equals("getConnection")) return result;
            return Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[] {Connection.class}, (proxy, operation, values) -> {
              if (operation.getName().equals("commit") && armed.compareAndSet(true, false)) {
                atCommit.countDown();
                if (!release.await(8, TimeUnit.SECONDS)) throw new SQLException("Synthetic commit gate timed out");
              }
              try { return operation.invoke(result, values); }
              catch (InvocationTargetException failure) { throw failure.getCause(); }
            });
          } catch (InvocationTargetException failure) { throw failure.getCause(); }
        });
    var replica = repository(gated);
    var store = PostgresPermitConsumptionStore.openExisting(gated);
    var approval = binding("race"); state.createPending(approval); state.decide(decision(approval));
    var operator = configured(source.getDatabaseName()); operator.setApplicationName("recovery-race-operator");
    var executor = Executors.newFixedThreadPool(2, runnable -> { var t = new Thread(runnable); t.setDaemon(true); return t; });
    try {
      armed.set(true);
      var claim = executor.submit(() -> switch (kind) {
        case "direct" -> replica.claim(claim(TENANT, "race")).started();
        case "approval" -> replica.consumeAndClaim(approvalClaim(approval)).started();
        default -> store.consume(TENANT, "race", NOW.plusSeconds(30), hash('a'), NOW);
      });
      assertThat(atCommit.await(5, TimeUnit.SECONDS)).isTrue();
      var quarantine = executor.submit(() -> PostgresRecoveryQuarantine.quarantine(operator, NOW));
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
      boolean waiting = false;
      while (!waiting && System.nanoTime() < deadline) {
        try (var c = source.getConnection(); var q = c.createStatement(); var r = q.executeQuery(
            "SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE application_name='recovery-race-operator' AND wait_event_type='Lock')")) {
          r.next(); waiting = r.getBoolean(1);
        }
        if (!waiting) Thread.sleep(10);
      }
      assertThat(waiting).as("quarantine must wait on the authority transaction, not pass its uncommitted claim").isTrue();
      assertThat(quarantine.isDone()).isFalse();
      release.countDown();
      assertThat(claim.get(5, TimeUnit.SECONDS)).isTrue();
      assertThat(quarantine.get(5, TimeUnit.SECONDS).mode()).isEqualTo("quarantined");
      assertThatThrownBy(() -> state.claim(claim(TENANT, "later"))).hasMessage("DATABASE_RECOVERY_QUARANTINED");
      assertThat(count("tp_consumed_execution_permits")).isEqualTo(1);
    } finally {
      release.countDown(); executor.shutdownNow();
      assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
    }
  }

  private static PostgresDurableEnforcementState repository(DataSource source) {
    return PostgresDurableEnforcementState.openExisting(source, CLOCK, () -> UUID.randomUUID().toString());
  }
  private static DurableExecutionClaim claim(TenantPartition tenant, String permit) {
    return new DurableExecutionClaim(tenant, permit, NOW.plusSeconds(30), "simulator", hash('a'), "event-" + permit,
        "trace-1", "evaluation-1", "user:alice@demo.invalid", hash('b'), "policy-1", 1, "allow", List.of(hash('c')), NOW);
  }
  private static ApprovalBinding binding(String id) {
    return new ApprovalBinding(TENANT, id, CanonicalJson.sha256(JsonSupport.MAPPER.createObjectNode().put("id", id)), hash('b'), "event-" + id, "evaluation-1", "user:alice@demo.invalid",
        hash('c'), "policy-1", 1, "require_approval", "aws.ec2.update_security_group", hash('d'), "default", NOW, NOW.plusSeconds(300), 1);
  }
  private static ApprovalDecision decision(ApprovalBinding binding) {
    return new ApprovalDecision(TENANT, binding.approvalRequestId(), "decision-" + binding.approvalRequestId(),
        "user:bob@demo.invalid", "synthetic_oidc", hash('e'), "approve", "REVIEWED", NOW);
  }
  private static ApprovalExecutionClaim approvalClaim(ApprovalBinding binding) {
    return new ApprovalExecutionClaim(binding, decision(binding).approvalDecisionId(), claim(TENANT, binding.approvalRequestId()), NOW);
  }
  private static String hash(char c) { return "sha256:" + String.valueOf(c).repeat(64); }
  private static PGSimpleDataSource configured(String name) {
    var ds = new PGSimpleDataSource(); ds.setServerNames(new String[] {"127.0.0.1"});
    ds.setPortNumbers(new int[] {POSTGRES.getMappedPort(5432)}); ds.setDatabaseName(name);
    ds.setUser(POSTGRES.getUsername()); ds.setPassword(POSTGRES.getPassword());
    ds.setCurrentSchema("public"); ds.setConnectTimeout(3); ds.setSocketTimeout(10); return ds;
  }
  private void sql(String text) throws SQLException {
    try (var c = source.getConnection(); var q = c.createStatement()) { q.setQueryTimeout(5); q.execute(text); }
  }
  private long count(String table) throws SQLException {
    try (var c = source.getConnection(); var q = c.createStatement(); var r = q.executeQuery("SELECT count(*) FROM " + table)) {
      r.next(); return r.getLong(1);
    }
  }
}
