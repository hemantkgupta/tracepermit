package io.tracepermit.persistence.postgres;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.tracepermit.application.ExecutionResult;
import io.tracepermit.application.SecureExecutionService;
import io.tracepermit.application.SecurityReadiness;
import io.tracepermit.contracts.CanonicalJson;
import io.tracepermit.contracts.ContractViolation;
import io.tracepermit.contracts.JsonSupport;
import io.tracepermit.execution.InMemoryEvidenceLedger;
import io.tracepermit.execution.InMemoryExecutionLedger;
import io.tracepermit.policy.ClassificationContext;
import io.tracepermit.policy.PolicyConcurrencyException;
import io.tracepermit.policy.PolicyDecision;
import io.tracepermit.policy.PolicyEvaluator;
import io.tracepermit.policy.PolicyLifecycleService;
import io.tracepermit.policy.PolicyState;
import io.tracepermit.policy.PolicyTransitionRecord;
import io.tracepermit.policy.PolicyValidator;
import io.tracepermit.policy.PolicyVersionRecord;
import io.tracepermit.policy.RollbackRecord;
import io.tracepermit.security.ExecutionPermitAuthority;
import io.tracepermit.security.InMemoryPermitConsumptionStore;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

import javax.sql.DataSource;
import java.math.BigInteger;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

final class PostgresPolicyLifecycleStoreTest {

  private static final Path ROOT = Path.of(System.getProperty("tracepermit.repositoryRoot"));
  private static final Instant NOW = Instant.parse("2026-10-16T12:00:00Z");
  private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16.4-alpine");
  private static final AtomicLong ID_COUNTER = new AtomicLong();

  private DataSource dataSource;
  private PostgresPolicyLifecycleStore store;

  @BeforeAll
  static void startPostgres() {
    POSTGRES.start();
  }

  @AfterAll
  static void stopPostgres() {
    POSTGRES.stop();
  }

  @BeforeEach
  void resetDatabase() throws SQLException {
    dataSource = configuredDataSource();
    store = new PostgresPolicyLifecycleStore(dataSource, CLOCK, () -> "plt-" + ID_COUNTER.incrementAndGet());
    try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
      statement.execute("""
          TRUNCATE tp_policy_lifecycle_transitions, tp_active_policies, tp_policy_versions
          """);
    }
  }

  @Test
  void stageAndQuerySurvivesRepositoryRecreation() {
    ObjectNode candidate = samplePolicy("pol-persist", 1);
    PolicyVersionRecord staged = store.stage("ws-recreation", candidate, NOW);
    assertThat(staged.state()).isEqualTo(PolicyState.STAGED);
    assertThat(staged.version()).isOne();

    // Recreate repository from raw data source
    PostgresPolicyLifecycleStore recreated = new PostgresPolicyLifecycleStore(
        dataSource, CLOCK, () -> "plt-" + ID_COUNTER.incrementAndGet());

    Optional<PolicyVersionRecord> fetched = recreated.getVersion("ws-recreation", "pol-persist", 1);
    assertThat(fetched).isPresent();
    PolicyVersionRecord record = fetched.get();
    assertThat(record.policyId()).isEqualTo("pol-persist");
    assertThat(record.version()).isOne();
    assertThat(record.name()).isEqualTo(candidate.path("name").asText());
    assertThat(record.description()).isEqualTo(candidate.path("description").asText());
    assertThat(record.contentHash()).isEqualTo(CanonicalJson.sha256(candidate));
    assertThat(record.state()).isEqualTo(PolicyState.STAGED);
    assertThat(record.stagedAt()).isEqualTo(NOW);
    assertThat(record.document()).isEqualTo(candidate);
  }

  @Test
  void activeSelectionAndRollbackHistorySurviveRecreation() {
    ObjectNode v1 = samplePolicy("pol-lifecycle", 1);
    ObjectNode v2 = samplePolicy("pol-lifecycle", 2);
    v2.put("description", "Updated v2 policy description");

    store.stage("ws-audit", v1, NOW);
    store.stage("ws-audit", v2, NOW.plusSeconds(10));

    PolicyVersionRecord act1 = store.activate("ws-audit", "pol-lifecycle", 1, null, NOW.plusSeconds(20), "actor-alice", "initial rollout");
    assertThat(act1.isActive()).isTrue();

    PolicyVersionRecord act2 = store.activate("ws-audit", "pol-lifecycle", 2, 1, NOW.plusSeconds(30), "actor-bob", "upgrade rollout");
    assertThat(act2.isActive()).isTrue();

    RollbackRecord rollback = store.rollback("ws-audit", "pol-lifecycle", 2, null, NOW.plusSeconds(40), "actor-charlie", "production anomaly");
    assertThat(rollback.fromVersion()).isEqualTo(2);
    assertThat(rollback.toVersion()).isOne();
    assertThat(rollback.reason()).isEqualTo("production anomaly");
    assertThat(rollback.actorId()).isEqualTo("actor-charlie");

    // Recreate store instance
    PostgresPolicyLifecycleStore fresh = new PostgresPolicyLifecycleStore(
        dataSource, CLOCK, () -> "plt-" + ID_COUNTER.incrementAndGet());

    Optional<PolicyVersionRecord> currentActive = fresh.activeVersion("ws-audit", "pol-lifecycle");
    assertThat(currentActive).isPresent();
    assertThat(currentActive.get().version()).isOne();
    assertThat(currentActive.get().isActive()).isTrue();

    PolicyVersionRecord v2Record = fresh.getVersion("ws-audit", "pol-lifecycle", 2).orElseThrow();
    assertThat(v2Record.isRolledBack()).isTrue();
    assertThat(v2Record.rollbackTargetVersion()).isOne();
    assertThat(v2Record.rolledBackAt()).isEqualTo(NOW.plusSeconds(40));

    List<RollbackRecord> rollbacks = fresh.rollbackHistory("ws-audit", "pol-lifecycle");
    assertThat(rollbacks).hasSize(1);
    RollbackRecord storedRollback = rollbacks.getFirst();
    assertThat(storedRollback.workspaceId()).isEqualTo("ws-audit");
    assertThat(storedRollback.policyId()).isEqualTo("pol-lifecycle");
    assertThat(storedRollback.fromVersion()).isEqualTo(2);
    assertThat(storedRollback.toVersion()).isOne();
    assertThat(storedRollback.reason()).isEqualTo("production anomaly");

    List<PolicyTransitionRecord> transitions = fresh.activationHistory("ws-audit", "pol-lifecycle");
    assertThat(transitions).hasSize(3);
    assertThat(transitions.get(0).transitionType()).isEqualTo("activate");
    assertThat(transitions.get(0).toVersion()).isOne();
    assertThat(transitions.get(1).transitionType()).isEqualTo("activate");
    assertThat(transitions.get(1).fromVersion()).isOne();
    assertThat(transitions.get(1).toVersion()).isEqualTo(2);
    assertThat(transitions.get(2).transitionType()).isEqualTo("rollback");
    assertThat(transitions.get(2).fromVersion()).isEqualTo(2);
    assertThat(transitions.get(2).toVersion()).isOne();
  }

  @Test
  void sameVersionDifferentContentRejectionFailsClosed() {
    ObjectNode candidate = samplePolicy("pol-immutable", 1);
    PolicyVersionRecord staged = store.stage("ws-immutable", candidate, NOW);

    // Identical restaging is idempotent and returns existing record
    PolicyVersionRecord restaged = store.stage("ws-immutable", candidate, NOW.plusSeconds(10));
    assertThat(restaged.contentHash()).isEqualTo(staged.contentHash());
    assertThat(restaged.version()).isEqualTo(staged.version());

    // Differing content under existing version identity must fail closed
    ObjectNode modified = candidate.deepCopy();
    modified.put("description", "A completely different description");
    assertThatThrownBy(() -> store.stage("ws-immutable", modified, NOW.plusSeconds(20)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("POLICY_VERSION_IMMUTABLE");

    // Verify stored record retains original hash and description
    PolicyVersionRecord retrieved = store.getVersion("ws-immutable", "pol-immutable", 1).orElseThrow();
    assertThat(retrieved.contentHash()).isEqualTo(staged.contentHash());
    assertThat(retrieved.description()).isEqualTo(candidate.path("description").asText());
  }

  @Test
  void inputAndReturnedDocumentMutationCannotAlterStoredPolicyOrHash() throws SQLException {
    ObjectNode candidate = samplePolicy("pol-defensive", 1);
    JsonNode expected = candidate.deepCopy();

    PolicyVersionRecord staged = store.stage("ws-defensive", candidate, NOW);

    // Mutate caller's local input after stage
    candidate.put("name", "MALICIOUS_MUTATION");
    ((ObjectNode) candidate.path("defaultDecision")).put("outcome", "allow");

    // Mutate returned document from staged
    ((ObjectNode) staged.document()).put("name", "MALICIOUS_MUTATION_2");

    PolicyVersionRecord query1 = store.getVersion("ws-defensive", "pol-defensive", 1).orElseThrow();
    assertThat(query1.document()).isEqualTo(expected);
    assertThat(query1.contentHash()).isEqualTo(CanonicalJson.sha256(expected));

    // Mutate query1 document
    ((ObjectNode) query1.document()).put("name", "MALICIOUS_MUTATION_3");

    store.activate("ws-defensive", "pol-defensive", 1, null, NOW, "actor", "activation");
    PolicyVersionRecord active = store.activeVersion("ws-defensive", "pol-defensive").orElseThrow();
    assertThat(active.document()).isEqualTo(expected);
    assertThat(active.contentHash()).isEqualTo(CanonicalJson.sha256(expected));

    // Direct SQL update attempt on document or content_hash must be blocked by database trigger
    try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
      assertThatThrownBy(() -> stmt.executeUpdate("""
          UPDATE tp_policy_versions
          SET document = '{"malicious": true}'::jsonb
          WHERE workspace_id = 'ws-defensive' AND policy_id = 'pol-defensive' AND version = 1
          """))
          .isInstanceOf(SQLException.class)
          .hasMessageContaining("tp_policy_versions core immutable fields cannot be mutated");

      assertThatThrownBy(() -> stmt.executeUpdate("""
          DELETE FROM tp_policy_versions
          WHERE workspace_id = 'ws-defensive' AND policy_id = 'pol-defensive' AND version = 1
          """))
          .isInstanceOf(SQLException.class)
          .hasMessageContaining("tp_policy_versions rows cannot be deleted");
    }
  }

  @Test
  void numericOverflowAndPriorityBoundariesRemainCorrect() {
    // Maximum supported integer version (Integer.MAX_VALUE = 2147483647)
    ObjectNode maxVersionPolicy = samplePolicy("pol-max-ver", Integer.MAX_VALUE);
    PolicyVersionRecord stagedMax = store.stage("ws-numeric", maxVersionPolicy, NOW);
    PolicyVersionRecord activeMax = store.activate("ws-numeric", "pol-max-ver", Integer.MAX_VALUE, null, NOW, null, "activate max version");
    assertThat(activeMax.version()).isEqualTo(Integer.MAX_VALUE);
    assertThat(activeMax.document().path("version").asInt()).isEqualTo(Integer.MAX_VALUE);

    // Invalid versions fail before DB insert
    for (BigInteger invalidVer : List.of(
        BigInteger.ZERO,
        BigInteger.valueOf(-1),
        BigInteger.valueOf(Integer.MAX_VALUE).add(BigInteger.ONE),
        new BigInteger("9007199254740992"),
        new BigInteger("18446744073709551617"))) {
      ObjectNode candidate = samplePolicy("pol-invalid", 1);
      candidate.put("version", invalidVer);
      assertThatThrownBy(() -> store.stage("ws-numeric", candidate, NOW))
          .isInstanceOf(ContractViolation.class);
    }

    // Signed priority boundaries (-2147483648..2147483647) remain valid and preserve ordering
    ObjectNode priorityPolicy = samplePolicy("pol-priorities", 1);
    ArrayNode rules = priorityPolicy.putArray("rules");
    addRule(rules, "rule-min", Integer.MIN_VALUE, "allow");
    addRule(rules, "rule-neg", -100, "allow");
    addRule(rules, "rule-zero", 0, "allow");
    addRule(rules, "rule-pos", 100, "allow");
    addRule(rules, "rule-max", Integer.MAX_VALUE, "block");

    PolicyVersionRecord stagedPriority = store.stage("ws-numeric", priorityPolicy, NOW);
    assertThat(stagedPriority.document().path("rules")).hasSize(5);

    // Verify evaluator obeys exact signed priority ordering
    JsonNode action = JsonSupport.read(ROOT.resolve("fixtures/chatgpt-aws-read.json"));
    PolicyEvaluator evaluator = new PolicyEvaluator();
    PolicyDecision decision = evaluator.evaluate(action, stagedPriority.document()).decision();
    assertThat(decision.outcome()).isEqualTo("block");
    assertThat(decision.matchedRuleId()).isEqualTo("rule-max");

    // Out-of-range priority fails closed
    for (BigInteger invalidPriority : List.of(
        BigInteger.valueOf(Integer.MIN_VALUE).subtract(BigInteger.ONE),
        BigInteger.valueOf(Integer.MAX_VALUE).add(BigInteger.ONE),
        new BigInteger("9007199254740992"))) {
      ObjectNode invalidCandidate = samplePolicy("pol-bad-pri", 1);
      ((ObjectNode) invalidCandidate.path("rules").get(0)).put("priority", invalidPriority);
      assertThatThrownBy(() -> store.stage("ws-numeric", invalidCandidate, NOW))
          .isInstanceOf(ContractViolation.class);
    }
  }

  @Test
  void workspaceIsolationForReadsAndLifecycleMutations() {
    ObjectNode policyAlpha = samplePolicy("pol-shared", 1);
    policyAlpha.put("description", "Alpha workspace policy");

    ObjectNode policyBravo = samplePolicy("pol-shared", 1);
    policyBravo.put("description", "Bravo workspace policy");

    store.stage("workspace-alpha", policyAlpha, NOW);
    store.stage("workspace-bravo", policyBravo, NOW);

    store.activate("workspace-alpha", "pol-shared", 1, null, NOW, "actor", "alpha activation");

    // Alpha is active; Bravo has no active version
    assertThat(store.activeVersion("workspace-alpha", "pol-shared")).isPresent();
    assertThat(store.activeVersion("workspace-bravo", "pol-shared")).isEmpty();

    // Versions list is strictly isolated
    List<PolicyVersionRecord> alphaVersions = store.listVersions("workspace-alpha", "pol-shared");
    List<PolicyVersionRecord> bravoVersions = store.listVersions("workspace-bravo", "pol-shared");
    assertThat(alphaVersions).hasSize(1);
    assertThat(bravoVersions).hasSize(1);
    assertThat(alphaVersions.getFirst().description()).isEqualTo("Alpha workspace policy");
    assertThat(bravoVersions.getFirst().description()).isEqualTo("Bravo workspace policy");

    // Policy ID listing is isolated
    store.stage("workspace-alpha", samplePolicy("pol-alpha-only", 1), NOW);
    assertThat(store.listPolicyIds("workspace-alpha")).containsExactlyInAnyOrder("pol-shared", "pol-alpha-only");
    assertThat(store.listPolicyIds("workspace-bravo")).containsExactly("pol-shared");
  }

  @Test
  void competingTransitionsObeyOptimisticConcurrencySemantics() throws Exception {
    ObjectNode v1 = samplePolicy("pol-concurrency", 1);
    ObjectNode v2 = samplePolicy("pol-concurrency", 2);
    ObjectNode v3 = samplePolicy("pol-concurrency", 3);

    store.stage("ws-concurrency", v1, NOW);
    store.stage("ws-concurrency", v2, NOW);
    store.stage("ws-concurrency", v3, NOW);

    store.activate("ws-concurrency", "pol-concurrency", 1, null, NOW, "initial", "activate v1");

    // 32 threads competing to activate with precondition expectedActiveVersion = 1
    CountDownLatch start = new CountDownLatch(1);
    AtomicInteger successes = new AtomicInteger();
    AtomicInteger conflicts = new AtomicInteger();

    List<PostgresPolicyLifecycleStore> replicas = List.of(
        new PostgresPolicyLifecycleStore(dataSource, CLOCK, () -> "plt-" + ID_COUNTER.incrementAndGet()),
        new PostgresPolicyLifecycleStore(dataSource, CLOCK, () -> "plt-" + ID_COUNTER.incrementAndGet()),
        new PostgresPolicyLifecycleStore(dataSource, CLOCK, () -> "plt-" + ID_COUNTER.incrementAndGet()),
        new PostgresPolicyLifecycleStore(dataSource, CLOCK, () -> "plt-" + ID_COUNTER.incrementAndGet()));

    List<Callable<Void>> tasks = java.util.stream.IntStream.range(0, 32).mapToObj(index -> (Callable<Void>) () -> {
      start.await();
      int targetVer = (index % 2 == 0) ? 2 : 3;
      try {
        replicas.get(index % replicas.size()).activate(
            "ws-concurrency", "pol-concurrency", targetVer, 1, NOW.plusSeconds(index + 1), "actor-" + index, "competing update");
        successes.incrementAndGet();
      } catch (PolicyConcurrencyException expected) {
        conflicts.incrementAndGet();
      }
      return null;
    }).toList();

    try (var executor = Executors.newFixedThreadPool(16)) {
      var futures = tasks.stream().map(executor::submit).toList();
      start.countDown();
      for (var f : futures) f.get();
    }

    // Exactly one winner, 31 conflicts
    assertThat(successes.get()).isOne();
    assertThat(conflicts.get()).isEqualTo(31);

    // Active version is updated to the winner's version (either 2 or 3)
    PolicyVersionRecord active = store.activeVersion("ws-concurrency", "pol-concurrency").orElseThrow();
    assertThat(active.version()).isIn(2, 3);
  }

  @Test
  void failedTransactionsLeaveNeitherPartialStateNorOrphanHistory() throws SQLException {
    ObjectNode v1 = samplePolicy("pol-fail-atomicity", 1);
    ObjectNode v2 = samplePolicy("pol-fail-atomicity", 2);

    store.stage("ws-atomicity", v1, NOW);
    store.stage("ws-atomicity", v2, NOW);

    store.activate("ws-atomicity", "pol-fail-atomicity", 1, null, NOW, "init", "initial");

    // Install a temporary trigger on tp_policy_lifecycle_transitions that simulates failure during transition log insertion
    try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
      stmt.execute("""
          CREATE OR REPLACE FUNCTION tp_test_fail_transition()
          RETURNS TRIGGER AS $$
          BEGIN
            IF NEW.to_version = 2 THEN
              RAISE EXCEPTION 'SIMULATED_TRANSITION_FAILURE';
            END IF;
            RETURN NEW;
          END;
          $$ LANGUAGE plpgsql;

          CREATE TRIGGER tp_test_fail_transition_trigger
          BEFORE INSERT ON tp_policy_lifecycle_transitions
          FOR EACH ROW EXECUTE FUNCTION tp_test_fail_transition();
          """);
    }

    // Attempt activation of v2 -> must fail
    assertThatThrownBy(() -> store.activate(
        "ws-atomicity", "pol-fail-atomicity", 2, 1, NOW.plusSeconds(5), "actor", "failing activation"))
        .isInstanceOf(PostgresStateException.class)
        .hasCauseInstanceOf(SQLException.class);

    // Remove the trigger
    try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
      stmt.execute("DROP TRIGGER IF EXISTS tp_test_fail_transition_trigger ON tp_policy_lifecycle_transitions");
      stmt.execute("DROP FUNCTION IF EXISTS tp_test_fail_transition()");
    }

    // Verify atomic rollback: active policy remains 1, v2 remains staged (not active), and no orphan transition row exists
    PolicyVersionRecord active = store.activeVersion("ws-atomicity", "pol-fail-atomicity").orElseThrow();
    assertThat(active.version()).isOne();
    assertThat(active.isActive()).isTrue();

    PolicyVersionRecord v2Record = store.getVersion("ws-atomicity", "pol-fail-atomicity", 2).orElseThrow();
    assertThat(v2Record.isStaged()).isTrue();

    List<PolicyTransitionRecord> history = store.activationHistory("ws-atomicity", "pol-fail-atomicity");
    assertThat(history).hasSize(1);
    assertThat(history.getFirst().toVersion()).isOne();
  }

  @Test
  void databaseUnavailabilityFailsClosedWithoutSilentInMemoryFallback() {
    PGSimpleDataSource deadSource = new PGSimpleDataSource();
    deadSource.setUrl("jdbc:postgresql://127.0.0.1:54329/nonexistent_db");
    deadSource.setUser("invalid");
    deadSource.setPassword("invalid");

    // Initial creation against unavailable DB fails closed
    assertThatThrownBy(() -> new PostgresPolicyLifecycleStore(deadSource, CLOCK, () -> "plt-broken"))
        .isInstanceOf(PostgresStateException.class);

    // Existing store when database connection fails at runtime fails closed
    FailingDataSource failingDataSource = new FailingDataSource(dataSource);
    PostgresPolicyLifecycleStore runtimeStore = new PostgresPolicyLifecycleStore(
        failingDataSource, CLOCK, () -> "plt-failing");
    failingDataSource.setFailing(true);

    ObjectNode candidate = samplePolicy("pol-dead", 1);
    assertThatThrownBy(() -> runtimeStore.stage("ws-dead", candidate, NOW))
        .isInstanceOf(PostgresStateException.class);
    assertThatThrownBy(() -> runtimeStore.activate("ws-dead", "pol-dead", 1, null, NOW, "actor", "reason"))
        .isInstanceOf(PostgresStateException.class);
    assertThatThrownBy(() -> runtimeStore.rollback("ws-dead", "pol-dead", null, null, NOW, "actor", "reason"))
        .isInstanceOf(PostgresStateException.class);
    assertThatThrownBy(() -> runtimeStore.activeVersion("ws-dead", "pol-dead"))
        .isInstanceOf(PostgresStateException.class);
    assertThatThrownBy(() -> runtimeStore.activationHistory("ws-dead", "pol-dead"))
        .isInstanceOf(PostgresStateException.class);
    assertThatThrownBy(() -> runtimeStore.listVersions("ws-dead", "pol-dead"))
        .isInstanceOf(PostgresStateException.class);
    assertThatThrownBy(() -> runtimeStore.rollbackHistory("ws-dead", "pol-dead"))
        .isInstanceOf(PostgresStateException.class);
    assertThatThrownBy(() -> runtimeStore.listPolicyIds("ws-dead"))
        .isInstanceOf(PostgresStateException.class);
  }

  @Test
  void existingInMemoryLifecycleAndSimulatorBehaviorRemainCompatible() {
    PolicyLifecycleService inMemoryService = new PolicyLifecycleService();
    ObjectNode candidate = samplePolicy("pol-compat", 1);
    inMemoryService.stage(candidate, NOW);
    inMemoryService.activate("pol-compat", 1, NOW);

    PolicyLifecycleService postgresService = new PolicyLifecycleService(store);
    postgresService.stage(candidate, NOW);
    postgresService.activate("pol-compat", 1, NOW);

    // End-to-end integration with SecureExecutionService
    String policyId = "pol-compat";
    ObjectNode v1 = samplePolicy(policyId, 1);
    ObjectNode v2 = v1.deepCopy();
    v2.put("version", 2);
    ArrayNode rules = (ArrayNode) v2.path("rules");
    rules.remove(2); // removes allow-cloudwatch-read
    ((ObjectNode) v2.path("defaultDecision")).put("outcome", "block");

    postgresService.stage(v1, NOW);
    postgresService.stage(v2, NOW.plusSeconds(5));
    postgresService.activate(policyId, 1, NOW.plusSeconds(10));

    AtomicInteger dispatches = new AtomicInteger();
    AtomicInteger identifiers = new AtomicInteger();
    InMemoryEvidenceLedger evidence = new InMemoryEvidenceLedger(
        "urn:tracepermit:lifecycle-test",
        CLOCK,
        () -> "evidence-" + identifiers.incrementAndGet());
    SecureExecutionService execService = new SecureExecutionService(
        postgresService,
        policyId,
        new ExecutionPermitAuthority(
            ExecutionPermitAuthority.DEFAULT_ISSUER,
            "test-lifecycle-key",
            new byte[32],
            30,
            CLOCK,
            () -> "permit-" + identifiers.incrementAndGet(),
            new InMemoryPermitConsumptionStore()),
        new InMemoryExecutionLedger(() -> "attempt-" + identifiers.incrementAndGet()),
        evidence,
        action -> {
          dispatches.incrementAndGet();
          return JsonSupport.MAPPER.createObjectNode().put("status", "simulated");
        },
        () -> SecurityReadiness.allReady(NOW),
        CLOCK,
        "urn:tracepermit:lifecycle-test-simulator");

    JsonNode readAction = JsonSupport.read(ROOT.resolve("fixtures/chatgpt-aws-read.json"));

    // v1 is active: allows CloudWatch read
    ExecutionResult res1 = execService.execute(readAction);
    assertThat(res1.outcome()).isEqualTo("succeeded");
    assertThat(dispatches.get()).isOne();

    // Activate v2: blocks CloudWatch read
    postgresService.activate(policyId, 2, NOW.plusSeconds(20));
    ExecutionResult res2 = execService.execute(readAction);
    assertThat(res2.outcome()).isEqualTo("block");
    assertThat(dispatches.get()).isOne(); // no additional dispatch

    // Rollback to v1: allows CloudWatch read again for distinct event
    postgresService.rollback(policyId, NOW.plusSeconds(30), "restore read access");
    ObjectNode distinctReadAction = ((ObjectNode) readAction).deepCopy();
    distinctReadAction.put("eventId", "018f47a2-f5ce-7a4b-8f54-80e5d3eaf092");
    ExecutionResult res3 = execService.execute(distinctReadAction);
    assertThat(res3.outcome()).isEqualTo("succeeded");
    assertThat(dispatches.get()).isEqualTo(2);
  }

  @Test
  void repeatedRollbackTogglesBetweenVersions() {
    ObjectNode v1 = samplePolicy("pol-toggle", 1);
    ObjectNode v2 = samplePolicy("pol-toggle", 2);

    store.stage("ws-toggle", v1, NOW);
    store.stage("ws-toggle", v2, NOW);

    store.activate("ws-toggle", "pol-toggle", 1, null, NOW, "init", "activate 1");
    store.activate("ws-toggle", "pol-toggle", 2, null, NOW.plusSeconds(1), "upgrade", "activate 2");

    // Rollback 1: v2 -> v1
    RollbackRecord rb1 = store.rollback("ws-toggle", "pol-toggle", null, null, NOW.plusSeconds(2), "actor", "rb 1");
    assertThat(rb1.fromVersion()).isEqualTo(2);
    assertThat(rb1.toVersion()).isOne();
    assertThat(store.activeVersion("ws-toggle", "pol-toggle").orElseThrow().version()).isOne();

    // Rollback 2: v1 -> v2 (repeated rollback restores preceding active version)
    RollbackRecord rb2 = store.rollback("ws-toggle", "pol-toggle", null, null, NOW.plusSeconds(3), "actor", "rb 2");
    assertThat(rb2.fromVersion()).isOne();
    assertThat(rb2.toVersion()).isEqualTo(2);
    assertThat(store.activeVersion("ws-toggle", "pol-toggle").orElseThrow().version()).isEqualTo(2);

    // Rollback 3: v2 -> v1
    RollbackRecord rb3 = store.rollback("ws-toggle", "pol-toggle", null, null, NOW.plusSeconds(4), "actor", "rb 3");
    assertThat(rb3.fromVersion()).isEqualTo(2);
    assertThat(rb3.toVersion()).isOne();
    assertThat(store.activeVersion("ws-toggle", "pol-toggle").orElseThrow().version()).isOne();
  }

  @Test
  void explicitTargetRollbackRespectsPreconditionAndTarget() {
    ObjectNode v1 = samplePolicy("pol-explicit-rb", 1);
    ObjectNode v2 = samplePolicy("pol-explicit-rb", 2);
    ObjectNode v3 = samplePolicy("pol-explicit-rb", 3);

    store.stage("ws-explicit", v1, NOW);
    store.stage("ws-explicit", v2, NOW);
    store.stage("ws-explicit", v3, NOW);

    store.activate("ws-explicit", "pol-explicit-rb", 1, null, NOW, "init", "v1");
    store.activate("ws-explicit", "pol-explicit-rb", 2, null, NOW.plusSeconds(1), "init", "v2");
    store.activate("ws-explicit", "pol-explicit-rb", 3, null, NOW.plusSeconds(2), "init", "v3");

    // Mismatched expectedActiveVersion precondition fails
    assertThatThrownBy(() -> store.rollback("ws-explicit", "pol-explicit-rb", 2, 1, NOW.plusSeconds(3), "actor", "mismatch"))
        .isInstanceOf(PolicyConcurrencyException.class)
        .hasMessageContaining("POLICY_CONCURRENCY_CONFLICT");

    // Direct rollback from v3 to v1
    RollbackRecord rb = store.rollback("ws-explicit", "pol-explicit-rb", 3, 1, NOW.plusSeconds(4), "actor", "jump back to v1");
    assertThat(rb.fromVersion()).isEqualTo(3);
    assertThat(rb.toVersion()).isOne();
    assertThat(store.activeVersion("ws-explicit", "pol-explicit-rb").orElseThrow().version()).isOne();
  }

  private static ObjectNode samplePolicy(String id, int version) {
    ObjectNode policy = (ObjectNode) JsonSupport.read(ROOT.resolve("policies/aws-web-readonly.json"));
    policy.put("id", id);
    policy.put("version", version);
    return policy;
  }

  private static void addRule(ArrayNode rules, String id, int priority, String outcome) {
    ObjectNode rule = rules.addObject()
        .put("id", id)
        .put("priority", priority)
        .put("description", "Synthetic priority test rule");
    rule.putObject("match");
    rule.putObject("decision").put("outcome", outcome).put("reasonCode", "TEST_" + id);
  }

  private static PGSimpleDataSource configuredDataSource() {
    PGSimpleDataSource source = new PGSimpleDataSource();
    source.setUrl(POSTGRES.getJdbcUrl());
    source.setUser(POSTGRES.getUsername());
    source.setPassword(POSTGRES.getPassword());
    return source;
  }

  private static final class FailingDataSource implements DataSource {
    private final DataSource delegate;
    private volatile boolean failing;

    FailingDataSource(DataSource delegate) {
      this.delegate = delegate;
    }

    void setFailing(boolean failing) {
      this.failing = failing;
    }

    @Override
    public Connection getConnection() throws SQLException {
      if (failing) {
        throw new SQLException("Connection failed: database unavailable");
      }
      return delegate.getConnection();
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
      if (failing) {
        throw new SQLException("Connection failed: database unavailable");
      }
      return delegate.getConnection(username, password);
    }

    @Override
    public java.io.PrintWriter getLogWriter() throws SQLException {
      return delegate.getLogWriter();
    }

    @Override
    public void setLogWriter(java.io.PrintWriter out) throws SQLException {
      delegate.setLogWriter(out);
    }

    @Override
    public void setLoginTimeout(int seconds) throws SQLException {
      delegate.setLoginTimeout(seconds);
    }

    @Override
    public int getLoginTimeout() throws SQLException {
      return delegate.getLoginTimeout();
    }

    @Override
    public java.util.logging.Logger getParentLogger() throws java.sql.SQLFeatureNotSupportedException {
      return delegate.getParentLogger();
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
      return delegate.unwrap(iface);
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) throws SQLException {
      return delegate.isWrapperFor(iface);
    }
  }
}
