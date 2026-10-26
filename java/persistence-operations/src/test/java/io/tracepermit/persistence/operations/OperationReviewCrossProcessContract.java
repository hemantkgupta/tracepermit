package io.tracepermit.persistence.operations;

import static io.tracepermit.application.BusinessOperationStorageException.Code.*;
import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import io.tracepermit.adapters.remote.github.GitHubBusinessOperationBinding;
import io.tracepermit.application.*;
import io.tracepermit.contracts.CanonicalJson;
import io.tracepermit.contracts.JsonSupport;
import io.tracepermit.persistence.operations.OperationReviewChildJvm.*;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.JdbcDatabaseContainer;

/**
 * Cross-process durable qualification contract for closed GitHub operation reviews.
 *
 * <p>Exercises actual {@link JdbcGitHubOperationReviewRepository} and {@link JdbcBusinessOperationLifecycleRepository}
 * across separate OS-level JVM instances running against real PostgreSQL 16.4 and MariaDB 11.4.13/10.6.25 databases.
 * Validates process restart durability, independent contenders waiting on private barriers, historical replay at fact time,
 * restarted stale profile refusal, and closed outcome dimensions.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Timeout(180)
abstract class OperationReviewCrossProcessContract {
  private static final BusinessOperationScope SCOPE = OperationReviewChildJvm.SCOPE;
  private static final String GROUP = OperationReviewChildJvm.GROUP;
  private static final String HASH = OperationReviewChildJvm.HASH;

  private JdbcDatabaseContainer<?> database;
  private AdminSource adminSource;
  private String runtimePassword;
  private BusinessOperationAuthorityProfile profile;
  private GitHubBusinessOperationBinding.ExpectedProfile target;
  private ObjectNode initial;

  abstract JdbcDatabaseContainer<?> container();

  @BeforeAll
  void start() throws Exception {
    database = isolatedContainer();
    database.start();
    assertLoopback(database);
    adminSource = new AdminSource(database, database.getUsername(), database.getPassword());
    OperationDatabase.initializeReviewEmpty(adminSource);

    runtimePassword = UUID.randomUUID().toString();
    var owner = postgres() ? adminSource : new AdminSource(database, "root", database.getPassword());
    try (var connection = owner.getConnection(); var statement = connection.createStatement()) {
      statement.setQueryTimeout(5);
      if (postgres()) {
        statement.execute("CREATE USER tp_review_runtime PASSWORD '" + runtimePassword + "'");
        statement.execute("GRANT USAGE ON SCHEMA public TO tp_review_runtime");
        for (String table : List.of("tp_operation_schema_history", "tp_business_operations", "tp_business_operation_facts",
            "tp_operation_lifecycle_heads", "tp_operation_lifecycle_facts", "tp_operation_authority_controls", "tp_operation_review_bindings")) {
          statement.execute("GRANT SELECT ON " + table + " TO tp_review_runtime");
        }
        statement.execute("GRANT INSERT ON tp_operation_lifecycle_facts, tp_operation_review_bindings TO tp_review_runtime");
        statement.execute("GRANT UPDATE(snapshot_hash, snapshot_json) ON tp_operation_lifecycle_heads TO tp_review_runtime");
        statement.execute("GRANT UPDATE(lock_marker) ON tp_operation_authority_controls TO tp_review_runtime");
      } else {
        statement.execute("CREATE USER 'tp_review_runtime'@'%' IDENTIFIED BY '" + runtimePassword + "'");
        String prefix = "`" + database.getDatabaseName() + "`.";
        for (String table : List.of("tp_operation_schema_history", "tp_business_operations", "tp_business_operation_facts",
            "tp_operation_lifecycle_heads", "tp_operation_lifecycle_facts", "tp_operation_authority_controls", "tp_operation_review_bindings")) {
          statement.execute("GRANT SELECT ON " + prefix + table + " TO 'tp_review_runtime'@'%'");
        }
        statement.execute("GRANT INSERT ON " + prefix + "tp_operation_lifecycle_facts TO 'tp_review_runtime'@'%'");
        statement.execute("GRANT INSERT ON " + prefix + "tp_operation_review_bindings TO 'tp_review_runtime'@'%'");
        statement.execute("GRANT UPDATE(snapshot_hash, snapshot_json) ON " + prefix + "tp_operation_lifecycle_heads TO 'tp_review_runtime'@'%'");
      }
    }
  }

  @AfterAll
  void stop() {
    if (database != null) database.stop();
  }

  @BeforeEach
  void reset() throws Exception {
    for (String table : List.of("tp_operation_review_bindings", "tp_operation_lifecycle_facts", "tp_operation_lifecycle_heads",
        "tp_business_operation_facts", "tp_business_operations", "tp_operation_authority_controls")) {
      execute("DELETE FROM " + table);
    }
    configure("require_approval", Duration.ofMinutes(1));
    register();
  }

  @Test
  void testProcessRestartDurabilityEvaluationReviewAndReaderAcrossDistinctJvms() throws Exception {
    // JVM A: Evaluates initial registered operation -> awaiting_approval
    ChildResult evalResult = OperationReviewChildJvm.runChild(
        requestBuilder("EVALUATE")
            .operationJson(CanonicalJson.canonicalize(initial))
            .evaluatorExpiryIso(fresh().toString())
            .build(),
        Duration.ofSeconds(30)
    );
    assertThat(evalResult.isOk()).isTrue();
    assertThat(evalResult.resultStatus()).isEqualTo("awaiting_approval");
    JsonNode pending = evalResult.snapshot();
    String jvmA = evalResult.jvmId();

    // Verify intermediate DB state from parent
    assertThat(count("tp_operation_review_bindings")).isEqualTo(1);
    var binding = binding();
    assertThat(Instant.parse(binding.path("approvalDeadline").asText()))
        .isEqualTo(Instant.parse(binding.path("evaluatedAt").asText()).plusSeconds(60));

    // JVM B: Separate reconstructed repository & profile reviews pending -> approved
    ChildResult reviewResult = OperationReviewChildJvm.runChild(
        requestBuilder("REVIEW")
            .operationJson(CanonicalJson.canonicalize(pending))
            .decision("APPROVE")
            .reviewExpiryIso(fresh().toString())
            .build(),
        Duration.ofSeconds(30)
    );
    assertThat(reviewResult.isOk()).isTrue();
    assertThat(reviewResult.resultStatus()).isEqualTo("approved");
    JsonNode approved = reviewResult.snapshot();
    String jvmB = reviewResult.jvmId();

    // JVM C: Fresh reader reads current snapshot from DB
    ChildResult readResult = OperationReviewChildJvm.runChild(
        requestBuilder("READ_CURRENT")
            .operationId(id())
            .build(),
        Duration.ofSeconds(30)
    );
    assertThat(readResult.isOk()).isTrue();
    assertThat(readResult.resultStatus()).isEqualTo("approved");
    JsonNode currentRead = readResult.snapshot();
    String jvmC = readResult.jvmId();

    // JVM D: Fresh reader reads original registration
    ChildResult origResult = OperationReviewChildJvm.runChild(
        requestBuilder("READ_ORIGINAL")
            .operationId(id())
            .build(),
        Duration.ofSeconds(30)
    );
    assertThat(origResult.isOk()).isTrue();
    JsonNode originalRead = origResult.snapshot();
    String jvmD = origResult.jvmId();

    // Prove distinct JVM instances (no host info exposed)
    assertThat(Set.of(jvmA, jvmB, jvmC, jvmD)).hasSize(4);

    // Verify consistency: approved preserves execution not_started and evidence dimensions
    assertThat(approved.path("execution")).isEqualTo(initial.path("execution"));
    assertThat(approved.path("evidence")).isEqualTo(initial.path("evidence"));
    assertThat(CanonicalJson.canonicalize(currentRead)).isEqualTo(CanonicalJson.canonicalize(approved));
    assertThat(CanonicalJson.canonicalize(originalRead)).isEqualTo(CanonicalJson.canonicalize(initial));

    assertThat(count("tp_business_operation_facts")).isEqualTo(3);
    assertThat(count("tp_operation_lifecycle_facts")).isEqualTo(2);
    assertThat(binding()).isEqualTo(binding);
  }

  @Test
  void testIndependentContendersApproveVersusWithdrawHaveExactlyOneWinnerAndOneStaleLoser() throws Exception {
    // Prepare pending operation
    ChildResult evalResult = OperationReviewChildJvm.runChild(
        requestBuilder("EVALUATE")
            .operationJson(CanonicalJson.canonicalize(initial))
            .evaluatorExpiryIso(fresh().toString())
            .build(),
        Duration.ofSeconds(30)
    );
    assertThat(evalResult.isOk()).isTrue();
    JsonNode pending = evalResult.snapshot();
    String pendingJson = CanonicalJson.canonicalize(pending);
    var originalBinding = binding();

    // Launch two real JVM contenders synchronized by private-pipe barrier
    try (
        var contender1 = OperationReviewChildJvm.startContender(
            requestBuilder("REVIEW")
                .operationJson(pendingJson)
                .decision("APPROVE")
                .reviewExpiryIso(fresh().toString())
                .barrier(true)
                .build());
        var contender2 = OperationReviewChildJvm.startContender(
            requestBuilder("WITHDRAW")
                .operationJson(pendingJson)
                .barrier(true)
                .build())
    ) {
      // Both children have reached the barrier and printed READY
      // Release both contenders simultaneously
      contender1.signalGo();
      contender2.signalGo();

      ChildResult res1 = contender1.awaitResult(Duration.ofSeconds(30));
      ChildResult res2 = contender2.awaitResult(Duration.ofSeconds(30));

      // Both child processes must acknowledge cleanly without crash or transport loss
      assertThat(res1.acknowledged()).isTrue();
      assertThat(res2.acknowledged()).isTrue();
      assertThat(res1.exitCode()).isZero();
      assertThat(res2.exitCode()).isZero();
      assertThat(res1.jvmId()).isNotEqualTo(res2.jvmId());

      // Exactly one succeeds, the other reports closed stale-state outcome
      boolean oneWon = res1.isOk() && res2.isRefused(OPERATION_STATE_STALE);
      boolean twoWon = res2.isOk() && res1.isRefused(OPERATION_STATE_STALE);
      assertThat(oneWon ^ twoWon).withFailMessage("Race failed: res1=" + res1 + ", res2=" + res2).isTrue();

      // Database contains exactly one terminal transition and original immutable binding unchanged
      assertThat(currentStatus()).isIn("approved", "cancelled");
      assertThat(count("tp_operation_lifecycle_facts")).isEqualTo(2);
      assertThat(count("tp_operation_review_bindings")).isEqualTo(1);
      assertThat(binding()).isEqualTo(originalBinding);
    }
  }

  @Test
  void testIndependentContendersApproveVersusDenyHaveExactlyOneWinnerAndOneStaleLoser() throws Exception {
    ChildResult evalResult = OperationReviewChildJvm.runChild(
        requestBuilder("EVALUATE")
            .operationJson(CanonicalJson.canonicalize(initial))
            .evaluatorExpiryIso(fresh().toString())
            .build(),
        Duration.ofSeconds(30)
    );
    assertThat(evalResult.isOk()).isTrue();
    JsonNode pending = evalResult.snapshot();
    String pendingJson = CanonicalJson.canonicalize(pending);
    var originalBinding = binding();

    try (
        var contenderApprove = OperationReviewChildJvm.startContender(
            requestBuilder("REVIEW")
                .operationJson(pendingJson)
                .decision("APPROVE")
                .reviewExpiryIso(fresh().toString())
                .barrier(true)
                .build());
        var contenderDeny = OperationReviewChildJvm.startContender(
            requestBuilder("REVIEW")
                .operationJson(pendingJson)
                .decision("DENY")
                .reviewExpiryIso(fresh().toString())
                .barrier(true)
                .build())
    ) {
      contenderApprove.signalGo();
      contenderDeny.signalGo();

      ChildResult resApprove = contenderApprove.awaitResult(Duration.ofSeconds(30));
      ChildResult resDeny = contenderDeny.awaitResult(Duration.ofSeconds(30));

      assertThat(resApprove.acknowledged()).isTrue();
      assertThat(resDeny.acknowledged()).isTrue();
      assertThat(resApprove.exitCode()).isZero();
      assertThat(resDeny.exitCode()).isZero();
      assertThat(resApprove.jvmId()).isNotEqualTo(resDeny.jvmId());

      boolean approveWon = resApprove.isOk() && resDeny.isRefused(OPERATION_STATE_STALE);
      boolean denyWon = resDeny.isOk() && resApprove.isRefused(OPERATION_STATE_STALE);
      assertThat(approveWon ^ denyWon).withFailMessage("Race failed: resApprove=" + resApprove + ", resDeny=" + resDeny).isTrue();

      assertThat(currentStatus()).isIn("approved", "denied");
      assertThat(count("tp_operation_lifecycle_facts")).isEqualTo(2);
      assertThat(count("tp_operation_review_bindings")).isEqualTo(1);
      assertThat(binding()).isEqualTo(originalBinding);
    }
  }

  @Test
  void testHistoricalReplayReconstructsRecordAtFactTimeAndRefusesFreshAuthorityAttempts() throws Exception {
    // Configure an explicit short positive lifetime: 800ms
    execute("DELETE FROM tp_operation_authority_controls");
    ObjectNode registered = initial;
    configure("require_approval", Duration.ofMillis(800));
    initial = registered;

    // JVM A: evaluate
    ChildResult evalResult = OperationReviewChildJvm.runChild(
        requestBuilder("EVALUATE")
            .operationJson(CanonicalJson.canonicalize(initial))
            .evaluatorExpiryIso(fresh().toString())
            .lifetimeMillis(800)
            .build(),
        Duration.ofSeconds(30)
    );
    assertThat(evalResult.isOk()).isTrue();
    JsonNode pending = evalResult.snapshot();
    Instant deadline = Instant.parse(binding().path("approvalDeadline").asText());

    // Await actual database deadline expiry
    awaitExpiry(deadline);
    assertThat(storeNow()).isAfter(deadline);

    // JVM B (fresh JVM): reads current state after expiry has passed
    // Reconstructs pending snapshot at historical fact time without treating history as expired
    ChildResult readResult = OperationReviewChildJvm.runChild(
        requestBuilder("READ_CURRENT")
            .operationId(id())
            .build(),
        Duration.ofSeconds(30)
    );
    assertThat(readResult.isOk()).isTrue();
    assertThat(readResult.resultStatus()).isEqualTo("awaiting_approval");
    assertThat(CanonicalJson.canonicalize(readResult.snapshot()))
        .isEqualTo(CanonicalJson.canonicalize(pending));

    // JVM C: attempts a new review command on the expired record -> refuses with OPERATION_APPROVAL_EXPIRED
    ChildResult staleReviewResult = OperationReviewChildJvm.runChild(
        requestBuilder("REVIEW")
            .operationJson(CanonicalJson.canonicalize(pending))
            .decision("APPROVE")
            .reviewExpiryIso(fresh().toString())
            .lifetimeMillis(800)
            .build(),
        Duration.ofSeconds(30)
    );
    assertThat(staleReviewResult.isRefused(OPERATION_APPROVAL_EXPIRED)).isTrue();

    // JVM D: attempts a fresh evaluate command on awaiting_approval record -> refuses with OPERATION_NOT_REVIEWABLE
    ChildResult staleEvalResult = OperationReviewChildJvm.runChild(
        requestBuilder("EVALUATE")
            .operationJson(CanonicalJson.canonicalize(pending))
            .evaluatorExpiryIso(fresh().toString())
            .lifetimeMillis(800)
            .build(),
        Duration.ofSeconds(30)
    );
    assertThat(staleEvalResult.isRefused(OPERATION_NOT_REVIEWABLE)).isTrue();

    // JVM E: attempts a fresh evaluate command with stale initial snapshot -> refuses with OPERATION_STATE_STALE
    ChildResult staleInitialEval = OperationReviewChildJvm.runChild(
        requestBuilder("EVALUATE")
            .operationJson(CanonicalJson.canonicalize(initial))
            .evaluatorExpiryIso(fresh().toString())
            .lifetimeMillis(800)
            .build(),
        Duration.ofSeconds(30)
    );
    assertThat(staleInitialEval.isRefused(OPERATION_STATE_STALE)).isTrue();

    // Database retained unchanged
    assertThat(count("tp_operation_lifecycle_facts")).isEqualTo(1);
    assertThat(count("tp_operation_review_bindings")).isEqualTo(1);
  }

  @Test
  void testRestartedStaleProfileRefusesCommandWhenQuarantinedOrNewGenerationActivated() throws Exception {
    ChildResult evalResult = OperationReviewChildJvm.runChild(
        requestBuilder("EVALUATE")
            .operationJson(CanonicalJson.canonicalize(initial))
            .evaluatorExpiryIso(fresh().toString())
            .build(),
        Duration.ofSeconds(30)
    );
    assertThat(evalResult.isOk()).isTrue();
    JsonNode pending = evalResult.snapshot();
    var originalBinding = binding();

    // Step 1: Operator quarantines control row using OperationAuthorityAdministration
    OperationAuthorityAdministration.quarantine(adminSource, profile);

    // JVM B with stale active expectation attempts review -> refuses OPERATION_AUTHORITY_NOT_READY
    ChildResult quarantinedReviewResult = OperationReviewChildJvm.runChild(
        requestBuilder("REVIEW")
            .operationJson(CanonicalJson.canonicalize(pending))
            .decision("APPROVE")
            .reviewExpiryIso(fresh().toString())
            .build(),
        Duration.ofSeconds(30)
    );
    assertThat(quarantinedReviewResult.isRefused(OPERATION_AUTHORITY_NOT_READY)).isTrue();
    assertThat(currentStatus()).isEqualTo("awaiting_approval");
    assertThat(count("tp_operation_lifecycle_facts")).isEqualTo(1);
    assertThat(binding()).isEqualTo(originalBinding);

    // Step 2: Operator stages replacement with recovery generation = 2
    BusinessOperationAuthorityProfile nextProfile = new BusinessOperationAuthorityProfile(
        SCOPE, target.adapterId(), 2L, OperationReviewChildJvm.createPolicy("require_approval"),
        CanonicalJson.sha256(initial.path("artifactDigests")),
        initial.path("resourceBindingHash").asText(),
        profile.reviewSettings()
    );
    OperationAuthorityAdministration.stageReplacement(adminSource, profile, nextProfile);

    // Prove staged replacement remains inactive before explicit activation:
    // JVM C with nextProfile (generation 2) attempts review -> refuses OPERATION_AUTHORITY_NOT_READY
    ChildResult stagedReviewResult = OperationReviewChildJvm.runChild(
        requestBuilder("REVIEW")
            .operationJson(CanonicalJson.canonicalize(pending))
            .decision("APPROVE")
            .reviewExpiryIso(fresh().toString())
            .recoveryGeneration(2L)
            .build(),
        Duration.ofSeconds(30)
    );
    assertThat(stagedReviewResult.isRefused(OPERATION_AUTHORITY_NOT_READY)).isTrue();

    // Step 3: Explicitly activate the staged profile
    OperationAuthorityAdministration.activateStaged(adminSource, nextProfile);

    // JVM D with old generation 1 attempts review -> refuses OPERATION_PROFILE_MISMATCH at control check
    ChildResult mismatchReviewResult = OperationReviewChildJvm.runChild(
        requestBuilder("REVIEW")
            .operationJson(CanonicalJson.canonicalize(pending))
            .decision("APPROVE")
            .reviewExpiryIso(fresh().toString())
            .recoveryGeneration(1L)
            .build(),
        Duration.ofSeconds(30)
    );
    assertThat(mismatchReviewResult.isRefused(OPERATION_PROFILE_MISMATCH)).isTrue();

    // JVM E with nextProfile (generation 2) attempts to review gen-1 pending operation ->
    // refuses OPERATION_PROFILE_MISMATCH because the evaluation binding is pinned to generation 1
    ChildResult gen2ReviewResult = OperationReviewChildJvm.runChild(
        requestBuilder("REVIEW")
            .operationJson(CanonicalJson.canonicalize(pending))
            .decision("APPROVE")
            .reviewExpiryIso(fresh().toString())
            .recoveryGeneration(2L)
            .build(),
        Duration.ofSeconds(30)
    );
    assertThat(gen2ReviewResult.isRefused(OPERATION_PROFILE_MISMATCH)).isTrue();

    // History and immutable bindings remain intact
    assertThat(currentStatus()).isEqualTo("awaiting_approval");
    assertThat(count("tp_operation_lifecycle_facts")).isEqualTo(1);
    assertThat(count("tp_operation_review_bindings")).isEqualTo(1);
    assertThat(binding()).isEqualTo(originalBinding);
  }

  @Test
  void testClosedOutcomesCoverPolicyBlockHumanDenialAndWithdrawalsBeforeAndAfterEvaluation() throws Exception {
    // Case 1: Policy Block
    execute("DELETE FROM tp_operation_authority_controls");
    execute("DELETE FROM tp_operation_lifecycle_heads");
    execute("DELETE FROM tp_business_operation_facts");
    execute("DELETE FROM tp_business_operations");
    configure("block", Duration.ofMinutes(1));
    register();

    ChildResult blockEval = OperationReviewChildJvm.runChild(
        requestBuilder("EVALUATE")
            .operationJson(CanonicalJson.canonicalize(initial))
            .evaluatorExpiryIso(fresh().toString())
            .policyOutcome("block")
            .build(),
        Duration.ofSeconds(30)
    );
    assertThat(blockEval.isOk()).isTrue();
    assertThat(blockEval.resultStatus()).isEqualTo("denied");
    JsonNode blocked = blockEval.snapshot();

    ChildResult blockRead = OperationReviewChildJvm.runChild(
        requestBuilder("READ_CURRENT")
            .operationId(id())
            .policyOutcome("block")
            .build(),
        Duration.ofSeconds(30)
    );
    assertThat(blockRead.isOk()).isTrue();
    assertThat(blockRead.resultStatus()).isEqualTo("denied");
    assertThat(blockRead.snapshot().path("execution")).isEqualTo(initial.path("execution"));
    assertThat(binding().has("approvalDeadline")).isFalse();

    // Blocked cannot be reviewed
    ChildResult blockReview = OperationReviewChildJvm.runChild(
        requestBuilder("REVIEW")
            .operationJson(CanonicalJson.canonicalize(blocked))
            .decision("APPROVE")
            .policyOutcome("block")
            .reviewExpiryIso(fresh().toString())
            .build(),
        Duration.ofSeconds(30)
    );
    assertThat(blockReview.isRefused(OPERATION_NOT_REVIEWABLE)).isTrue();

    // Case 2: Independent Human Denial
    reset();
    ChildResult pendingEval = OperationReviewChildJvm.runChild(
        requestBuilder("EVALUATE")
            .operationJson(CanonicalJson.canonicalize(initial))
            .evaluatorExpiryIso(fresh().toString())
            .build(),
        Duration.ofSeconds(30)
    );
    assertThat(pendingEval.isOk()).isTrue();
    JsonNode pending = pendingEval.snapshot();

    ChildResult deniedReview = OperationReviewChildJvm.runChild(
        requestBuilder("REVIEW")
            .operationJson(CanonicalJson.canonicalize(pending))
            .decision("DENY")
            .reviewExpiryIso(fresh().toString())
            .build(),
        Duration.ofSeconds(30)
    );
    assertThat(deniedReview.isOk()).isTrue();
    assertThat(deniedReview.resultStatus()).isEqualTo("denied");
    JsonNode denied = deniedReview.snapshot();

    ChildResult deniedRead = OperationReviewChildJvm.runChild(
        requestBuilder("READ_CURRENT").operationId(id()).build(), Duration.ofSeconds(30));
    assertThat(deniedRead.isOk()).isTrue();
    assertThat(deniedRead.resultStatus()).isEqualTo("denied");
    assertThat(deniedRead.snapshot().path("execution")).isEqualTo(initial.path("execution"));

    // Case 3: Withdrawal before evaluation
    reset();
    ChildResult withdrawBeforeEval = OperationReviewChildJvm.runChild(
        requestBuilder("WITHDRAW")
            .operationJson(CanonicalJson.canonicalize(initial))
            .build(),
        Duration.ofSeconds(30)
    );
    assertThat(withdrawBeforeEval.isOk()).isTrue();
    assertThat(withdrawBeforeEval.resultStatus()).isEqualTo("cancelled");
    assertThat(count("tp_operation_review_bindings")).isZero();
    assertThat(count("tp_operation_lifecycle_facts")).isEqualTo(1);

    ChildResult withdrawBeforeRead = OperationReviewChildJvm.runChild(
        requestBuilder("READ_CURRENT").operationId(id()).build(), Duration.ofSeconds(30));
    assertThat(withdrawBeforeRead.isOk()).isTrue();
    assertThat(withdrawBeforeRead.resultStatus()).isEqualTo("cancelled");
    assertThat(withdrawBeforeRead.snapshot().path("execution")).isEqualTo(initial.path("execution"));

    // Case 4: Withdrawal after pending evaluation
    reset();
    ChildResult pendingEval2 = OperationReviewChildJvm.runChild(
        requestBuilder("EVALUATE")
            .operationJson(CanonicalJson.canonicalize(initial))
            .evaluatorExpiryIso(fresh().toString())
            .build(),
        Duration.ofSeconds(30)
    );
    assertThat(pendingEval2.isOk()).isTrue();
    JsonNode pending2 = pendingEval2.snapshot();
    var bindingAfterEval = binding();
    assertThat(count("tp_operation_review_bindings")).isEqualTo(1);

    ChildResult withdrawAfterEval = OperationReviewChildJvm.runChild(
        requestBuilder("WITHDRAW")
            .operationJson(CanonicalJson.canonicalize(pending2))
            .build(),
        Duration.ofSeconds(30)
    );
    assertThat(withdrawAfterEval.isOk()).isTrue();
    assertThat(withdrawAfterEval.resultStatus()).isEqualTo("cancelled");
    assertThat(binding()).isEqualTo(bindingAfterEval); // binding retained!

    ChildResult reviewAfterCancel = OperationReviewChildJvm.runChild(
        requestBuilder("REVIEW")
            .operationJson(CanonicalJson.canonicalize(pending2))
            .decision("APPROVE")
            .reviewExpiryIso(fresh().toString())
            .build(),
        Duration.ofSeconds(30)
    );
    assertThat(reviewAfterCancel.isRefused(OPERATION_STATE_STALE)).isTrue();
  }

  @Test
  void testPreCommandChildTerminationProducesNoStateTransition() throws Exception {
    try (ContenderHandle handle = OperationReviewChildJvm.startContender(
        requestBuilder("EVALUATE")
            .operationJson(CanonicalJson.canonicalize(initial))
            .barrier(true)
            .build()
    )) {
      // Child is waiting at READY boundary before command execution
      // Parent terminates the child before sending GO
      handle.destroy();
      ChildResult result = handle.awaitResult(Duration.ofSeconds(10));

      assertThat(result.acknowledged()).isFalse();
      assertThat(result.status()).isEqualTo("UNACKNOWLEDGED");

      // Independent reader proves no transition occurred in the database
      ChildResult readResult = OperationReviewChildJvm.runChild(
          requestBuilder("READ_CURRENT").operationId(id()).build(), Duration.ofSeconds(30));
      assertThat(readResult.isOk()).isTrue();
      assertThat(readResult.resultStatus()).isEqualTo("evaluating");
      assertThat(count("tp_operation_lifecycle_facts")).isZero();
      assertThat(count("tp_operation_review_bindings")).isZero();
    }
  }

  @Test
  void testCommittedEvaluationWithWithheldAcknowledgementReportsUnacknowledgedAndReconcilesCleanly() throws Exception {
    try (ContenderHandle handle = OperationReviewChildJvm.startContender(
        requestBuilder("EVALUATE")
            .operationJson(CanonicalJson.canonicalize(initial))
            .evaluatorExpiryIso(fresh().toString())
            .testMode("POST_COMMIT_BARRIER")
            .build()
    )) {
      // Wait until child has committed the transaction to the database
      handle.awaitCommitted(Duration.ofSeconds(15));

      // Independent fresh reader proves the transaction committed
      ChildResult freshRead = OperationReviewChildJvm.runChild(
          requestBuilder("READ_CURRENT").operationId(id()).build(), Duration.ofSeconds(30));
      assertThat(freshRead.isOk()).isTrue();
      assertThat(freshRead.resultStatus()).isEqualTo("awaiting_approval");
      assertThat(count("tp_operation_lifecycle_facts")).isEqualTo(1);
      assertThat(count("tp_operation_review_bindings")).isEqualTo(1);

      // Terminate the child BEFORE it can emit its acknowledgement result
      handle.destroy();
      ChildResult unackResult = handle.awaitResult(Duration.ofSeconds(10));

      // Parent must report UNACKNOWLEDGED, not successful completion or a stale loser
      assertThat(unackResult.acknowledged()).isFalse();
      assertThat(unackResult.status()).isEqualTo("UNACKNOWLEDGED");

      // Fresh readback reconciles committed history without manufacturing the lost acknowledgement
      ChildResult reconciledRead = OperationReviewChildJvm.runChild(
          requestBuilder("READ_CURRENT").operationId(id()).build(), Duration.ofSeconds(30));
      assertThat(reconciledRead.isOk()).isTrue();
      assertThat(reconciledRead.resultStatus()).isEqualTo("awaiting_approval");

      // Repeating the original command with initial snapshot must not append a second transition
      ChildResult repeatEval = OperationReviewChildJvm.runChild(
          requestBuilder("EVALUATE")
              .operationJson(CanonicalJson.canonicalize(initial))
              .evaluatorExpiryIso(fresh().toString())
              .build(),
          Duration.ofSeconds(30)
      );
      assertThat(repeatEval.isRefused(OPERATION_STATE_STALE)).isTrue();
      assertThat(count("tp_operation_lifecycle_facts")).isEqualTo(1);
    }
  }

  @Test
  void testCommittedReviewWithWithheldAcknowledgementReportsUnacknowledgedAndPreventsDuplicateTransition() throws Exception {
    // JVM A: evaluate to awaiting_approval
    ChildResult evalResult = OperationReviewChildJvm.runChild(
        requestBuilder("EVALUATE")
            .operationJson(CanonicalJson.canonicalize(initial))
            .evaluatorExpiryIso(fresh().toString())
            .build(),
        Duration.ofSeconds(30)
    );
    assertThat(evalResult.isOk()).isTrue();
    JsonNode pending = evalResult.snapshot();

    // JVM B: review with withheld acknowledgement
    try (ContenderHandle handle = OperationReviewChildJvm.startContender(
        requestBuilder("REVIEW")
            .operationJson(CanonicalJson.canonicalize(pending))
            .decision("APPROVE")
            .reviewExpiryIso(fresh().toString())
            .testMode("POST_COMMIT_BARRIER")
            .build()
    )) {
      // Wait until child has committed the review transaction to the database
      handle.awaitCommitted(Duration.ofSeconds(15));

      // Independent fresh reader proves the review committed to approved
      ChildResult freshRead = OperationReviewChildJvm.runChild(
          requestBuilder("READ_CURRENT").operationId(id()).build(), Duration.ofSeconds(30));
      assertThat(freshRead.isOk()).isTrue();
      assertThat(freshRead.resultStatus()).isEqualTo("approved");
      assertThat(count("tp_operation_lifecycle_facts")).isEqualTo(2);

      // Terminate the child BEFORE it can emit its acknowledgement result
      handle.destroy();
      ChildResult unackResult = handle.awaitResult(Duration.ofSeconds(10));

      // Parent must report UNACKNOWLEDGED, not success or stale loser
      assertThat(unackResult.acknowledged()).isFalse();
      assertThat(unackResult.status()).isEqualTo("UNACKNOWLEDGED");

      // Fresh readback reconciles committed approved state
      ChildResult reconciledRead = OperationReviewChildJvm.runChild(
          requestBuilder("READ_CURRENT").operationId(id()).build(), Duration.ofSeconds(30));
      assertThat(reconciledRead.isOk()).isTrue();
      assertThat(reconciledRead.resultStatus()).isEqualTo("approved");

      // Repeating the review command with pending snapshot must refuse OPERATION_STATE_STALE
      ChildResult repeatReview = OperationReviewChildJvm.runChild(
          requestBuilder("REVIEW")
              .operationJson(CanonicalJson.canonicalize(pending))
              .decision("APPROVE")
              .reviewExpiryIso(fresh().toString())
              .build(),
          Duration.ofSeconds(30)
      );
      assertThat(repeatReview.isRefused(OPERATION_STATE_STALE)).isTrue();

      // Repeating with initial snapshot also refuses OPERATION_STATE_STALE
      ChildResult repeatInitial = OperationReviewChildJvm.runChild(
          requestBuilder("REVIEW")
              .operationJson(CanonicalJson.canonicalize(initial))
              .decision("APPROVE")
              .reviewExpiryIso(fresh().toString())
              .build(),
          Duration.ofSeconds(30)
      );
      assertThat(repeatInitial.isRefused(OPERATION_STATE_STALE)).isTrue();

      // Exactly 2 lifecycle facts (evaluation + review) and exactly 1 review binding
      assertThat(count("tp_operation_lifecycle_facts")).isEqualTo(2);
      assertThat(count("tp_operation_review_bindings")).isEqualTo(1);
    }
  }

  @Test
  void testResourceAndAcknowledgementBehaviorFailsOnChildCrashOrDeadPipe() throws Exception {
    ChildResult crashResult = OperationReviewChildJvm.runChild(
        requestBuilder("EVALUATE")
            .operationJson(CanonicalJson.canonicalize(initial))
            .simulateCrash(true)
            .build(),
        Duration.ofSeconds(10)
    );

    // Unacknowledged unexpected exit must NOT be treated as a successful command or a safe stale loser
    assertThat(crashResult.acknowledged()).isFalse();
    assertThat(crashResult.exitCode()).isEqualTo(13);
    assertThat(crashResult.status()).isEqualTo("UNACKNOWLEDGED");
    assertThat(crashResult.hasStderrCategory("CHILD_SIMULATED_CRASH")).isTrue();
  }

  // --- Fixture Helpers ---

  private ChildRequest.Builder requestBuilder(String command) {
    return ChildRequest.builder(command, database.getJdbcUrl(), "tp_review_runtime", runtimePassword);
  }

  private JdbcDatabaseContainer<?> isolatedContainer() {
    var owned = container().withPassword(UUID.randomUUID().toString());
    owned.withCreateContainerCmdModifier(command -> command.getHostConfig().withPortBindings(
        owned.getExposedPorts().stream().map(port -> new PortBinding(Ports.Binding.bindIp("127.0.0.1"),
            new ExposedPort(port))).toArray(PortBinding[]::new)));
    return owned;
  }

  private static void assertLoopback(JdbcDatabaseContainer<?> owned) {
    var bindings = owned.getContainerInfo().getNetworkSettings().getPorts().getBindings();
    assertThat(bindings).hasSize(1);
    assertThat(bindings.values().iterator().next()).hasSize(1)
        .allSatisfy(binding -> assertThat(binding.getHostIp()).isEqualTo("127.0.0.1"));
  }

  private void configure(String outcome, Duration lifetime) throws Exception {
    ObjectNode policy = OperationReviewChildJvm.createPolicy(outcome);
    target = OperationReviewChildJvm.createExpectedProfile(policy);
    Path root = Path.of(System.getProperty("tracepermit.repositoryRoot"));
    initial = OperationReviewChildJvm.createInitialOperation(target, root);
    profile = new BusinessOperationAuthorityProfile(
        SCOPE, target.adapterId(), 1, policy,
        CanonicalJson.sha256(initial.path("artifactDigests")),
        initial.path("resourceBindingHash").asText(),
        new BusinessOperationReviewSettings(GROUP, HASH, lifetime)
    );
    OperationAuthorityAdministration.stageNew(adminSource, profile);
    OperationAuthorityAdministration.activateStaged(adminSource, profile);
  }

  private void register() {
    new JdbcBusinessOperationRepository(adminSource).createOrGet(SCOPE, initial);
  }

  private String currentStatus() {
    return new JdbcBusinessOperationLifecycleRepository(adminSource)
        .findCurrent(SCOPE, id()).orElseThrow()
        .path("authorization").path("status").asText();
  }

  private String id() {
    return initial.path("operationId").asText();
  }

  private static Instant fresh() {
    return Instant.now().plusSeconds(120);
  }

  private boolean postgres() {
    return database.getJdbcUrl().startsWith("jdbc:postgresql:");
  }

  private void execute(String sql) throws SQLException {
    try (var connection = adminSource.getConnection(); var statement = connection.createStatement()) {
      statement.setQueryTimeout(5);
      statement.execute(sql);
    }
  }

  private int count(String table) throws SQLException {
    try (var connection = adminSource.getConnection(); var statement = connection.createStatement()) {
      statement.setQueryTimeout(5);
      try (var rows = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
        rows.next();
        return rows.getInt(1);
      }
    }
  }

  private JsonNode binding() throws SQLException {
    try (var connection = adminSource.getConnection(); var statement = connection.createStatement()) {
      statement.setQueryTimeout(5);
      try (var rows = statement.executeQuery("SELECT binding_json FROM tp_operation_review_bindings")) {
        assertThat(rows.next()).isTrue();
        return StoredOperationJson.read(rows.getString(1), 8192);
      }
    }
  }

  private Instant storeNow() throws SQLException {
    try (var connection = adminSource.getConnection()) {
      return JdbcBusinessOperationLifecycleRepository.storeTime(connection, OperationDatabase.inspectWriter(connection));
    }
  }

  private void awaitExpiry(Instant deadline) throws Exception {
    long bound = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (!storeNow().isAfter(deadline)) {
      assertThat(System.nanoTime()).isLessThan(bound);
      Thread.sleep(15);
    }
  }

  private static final class AdminSource implements DataSource {
    private final JdbcDatabaseContainer<?> database;
    private final String user;
    private final String password;

    AdminSource(JdbcDatabaseContainer<?> database, String user, String password) {
      this.database = database;
      this.user = user;
      this.password = password;
    }

    @Override public Connection getConnection() throws SQLException {
      return DriverManager.getConnection(database.getJdbcUrl(), user, password);
    }
    @Override public Connection getConnection(String u, String p) throws SQLException {
      throw new SQLFeatureNotSupportedException();
    }
    @Override public PrintWriter getLogWriter() { return null; }
    @Override public void setLogWriter(PrintWriter out) {}
    @Override public void setLoginTimeout(int seconds) {}
    @Override public int getLoginTimeout() { return 5; }
    @Override public Logger getParentLogger() throws SQLFeatureNotSupportedException {
      throw new SQLFeatureNotSupportedException();
    }
    @Override public <T> T unwrap(Class<T> iface) throws SQLException {
      throw new SQLFeatureNotSupportedException();
    }
    @Override public boolean isWrapperFor(Class<?> iface) { return false; }
  }
}
