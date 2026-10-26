package io.tracepermit.adapters.remote.agentcore.jdbc;

import static io.tracepermit.adapters.remote.agentcore.jdbc.AgentCoreJdbcIdentityFixture.*;
import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.tracepermit.adapters.remote.agentcore.AgentCoreRegistrationIngress;
import io.tracepermit.adapters.remote.github.GitHubBusinessOperationBinding;
import io.tracepermit.adapters.remote.github.GitHubPilotTargetBinding;
import io.tracepermit.application.BusinessOperationRepository;
import io.tracepermit.application.BusinessOperationScope;
import io.tracepermit.contracts.BusinessOperationValidator;
import io.tracepermit.contracts.CanonicalJson;
import io.tracepermit.contracts.JsonSupport;
import io.tracepermit.identity.InMemoryWorkloadIdentityReplayStore;
import io.tracepermit.identity.OidcAccessTokenVerifier;
import io.tracepermit.identity.WorkloadIdentityReplayStore;
import io.tracepermit.persistence.operations.JdbcBusinessOperationRepository;
import io.tracepermit.persistence.operations.OperationDatabase;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.containers.JdbcDatabaseContainer;

/** Real ingress/verifier/repository composition; all identities and databases are disposable fixtures. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class AgentCoreJdbcRegistrationContract {
  private static final String RUNTIME_USER = "tp_ingress_registration";
  private static final List<String> DATA_TABLES = List.of("tp_business_operations", "tp_business_operation_facts",
      "tp_operation_lifecycle_heads", "tp_operation_lifecycle_facts");
  private JdbcDatabaseContainer<?> database;
  private RegistrationJdbcProbeSource admin;
  private RegistrationJdbcProbeSource runtime;
  private JdbcBusinessOperationRepository repository;
  private InMemoryWorkloadIdentityReplayStore replay;

  abstract JdbcDatabaseContainer<?> container();
  abstract String expectedVersion();

  @BeforeAll
  void startFixture() {
    database = container().withPassword(UUID.randomUUID().toString());
    database.start(); // Required fixture: no Docker-availability assumptions/skips.
    admin = new RegistrationJdbcProbeSource(database.getJdbcUrl(), database.getUsername(), database.getPassword());
    OperationDatabase.initializeEmpty(admin); // Explicit fixture-admin installation, never runtime DDL.
    provisionRuntime();
  }

  @AfterAll
  void stopOwnedFixture() {
    if (database != null) database.stop();
  }

  @BeforeEach
  void resetOwnedRowsAndProbe() {
    runtime.fault = RegistrationJdbcProbeSource.Fault.NONE;
    grantFactInsert(true);
    for (String table : List.of("tp_operation_lifecycle_facts", "tp_operation_lifecycle_heads",
        "tp_business_operation_facts", "tp_business_operations")) adminExecute("DELETE FROM " + table);
    repository = new JdbcBusinessOperationRepository(runtime);
    runtime.resetProbe();
    replay = new InMemoryWorkloadIdentityReplayStore();
  }

  @Test
  void registersPersistedOriginalThreeFactsAndHeadWithIndependentScopedKeyGolden() {
    var receipt = ingress().register(token(), null, bytes(request()));
    assertThat(receipt.created()).isTrue();
    assertThat(receipt.executionEnabled()).isFalse();
    JsonNode original = assertSinglePersisted(receipt.operationId());
    assertThat(original.path("clientRequestKeyHash").textValue())
        // Python hashlib over sorted compact JSON; no production builder used to calculate this value.
        .isEqualTo("sha256:c4afaa73080ce040ab21012be074552c47c5c28173420cc88bf005bafe2e207f");
    assertThat(original.path("trustedContext")).isEqualTo(SCOPE.trustedContext());
    assertThat(original.path("canonicalAction").path("identity").path("subject").path("type").textValue()).isEqualTo("workload");
    assertThat(original.path("canonicalAction").path("lineage").path("delegations")).isEmpty();
    assertThat(original.toString()).doesNotContain("synthetic-key-1");
    assertThat(runtime.opens.get()).isEqualTo(1);
    assertThat(runtime.insertAttempts.get()).isEqualTo(5);
    assertThat(runtime.actualCommits.get()).isEqualTo(1);
    assertThat(runtime.insertedTables).containsExactlyInAnyOrder("tp_business_operations", "tp_business_operation_facts", "tp_operation_lifecycle_heads");
    assertThat(GitHubBusinessOperationBinding.bind(SCOPE, original, enrollment().targetProfile()).operationId())
        .isEqualTo(receipt.operationId());
  }

  @Test
  void freshTokenRetryAndReconstructedRepositoryRetainExactOriginalIdentityAndContent() {
    var first = ingress().register(token(), null, bytes(request()));
    DbState before = state();
    JsonNode original = assertSinglePersisted(first.operationId());
    var retry = ingress().register(token(), null, bytes(request()));
    assertThat(retry.created()).isFalse();
    assertThat(retry.operationId()).isEqualTo(first.operationId());
    assertThat(retry.executionEnabled()).isFalse();
    assertThat(state()).isEqualTo(before);
    var reconstructedSource = runtime.reconstructed();
    var reconstructed = new JdbcBusinessOperationRepository(reconstructedSource);
    assertThat(reconstructedSource.insertAttempts.get()).isZero();
    // Schema validation may commit read-only transactions; measure the registration phase separately.
    reconstructedSource.resetProbe();
    assertThat(reconstructed.find(SCOPE, first.operationId())).contains(original);
    var afterRestart = ingress(enrollment(), replay, reconstructed).register(token(), null, bytes(request()));
    assertThat(afterRestart.created()).isFalse();
    assertThat(afterRestart.operationId()).isEqualTo(first.operationId());
    assertThat(state()).isEqualTo(before);
    assertThat(runtime.actualCommits.get()).isEqualTo(1);
    assertThat(reconstructedSource.actualCommits.get()).isZero();
  }

  @ParameterizedTest
  @ValueSource(strings = {"title", "bodyAdded", "bodyChanged", "bodyRemoved", "headSha", "baseSha",
      "installationId", "repositoryId", "ownerId", "head", "generation", "environment"})
  void changedSemanticTargetOrGenerationConflictsWithoutAnotherWrite(String change) {
    ObjectNode initial = request();
    if (change.equals("bodyChanged") || change.equals("bodyRemoved")) initial.put("body", "Synthetic original body");
    ingress().register(token(), null, bytes(initial));
    DbState before = state();
    ObjectNode changed = initial.deepCopy();
    switch (change) {
      case "title" -> changed.put("title", "Synthetic changed title");
      case "bodyAdded", "bodyChanged" -> changed.put("body", "Synthetic changed body");
      case "bodyRemoved" -> changed.remove("body");
      default -> { }
    }
    var base = enrollment().targetProfile();
    var target = base.targetBinding();
    var changedTarget = new GitHubPilotTargetBinding(
        change.equals("installationId") ? 72 : target.installationId(),
        change.equals("repositoryId") ? 102 : target.repositoryId(),
        change.equals("ownerId") ? 202 : target.ownerId(), target.repository(),
        change.equals("head") ? "synthetic-other" : target.head(), target.base(),
        change.equals("headSha") ? "c".repeat(40) : target.headSha(),
        change.equals("baseSha") ? "d".repeat(40) : target.baseSha());
    var changedProfile = new GitHubBusinessOperationBinding.ExpectedProfile(base.adapterId(),
        change.equals("environment") ? "staging" : base.environment(), change.equals("generation") ? 2 : 1,
        base.policyRelease(), base.artifactDigests(), changedTarget);
    String authorization = token();
    var changedIngress = ingress(enrollment(SCOPE, changedProfile), replay, repository);
    int opens = runtime.opens.get();
    rejected(() -> changedIngress.register(authorization, null, bytes(changed)), "AGENTCORE_REQUEST_KEY_CONFLICT");
    assertThat(runtime.opens.get()).isEqualTo(opens + 1);
    assertThat(state()).isEqualTo(before);
    int afterConflict = runtime.opens.get();
    rejected(() -> changedIngress.register(authorization, null, bytes(changed)), "AGENTCORE_REGISTRATION_REJECTED");
    assertThat(runtime.opens.get()).isEqualTo(afterConflict);
    assertThat(runtime.actualCommits.get()).isEqualTo(1);
  }

  @Test
  void releaseMetadataOnlyProfileChangesReturnOriginalRatherThanClaimingAnUnsupportedConflict() {
    var first = ingress().register(token(), null, bytes(request()));
    DbState before = state();
    JsonNode original = assertSinglePersisted(first.operationId());
    var base = enrollment().targetProfile();
    var changedProfile = new GitHubBusinessOperationBinding.ExpectedProfile(base.adapterId(), base.environment(), 1,
        new GitHubBusinessOperationBinding.PolicyRelease("synthetic-other-policy", 2, "sha256:" + "8".repeat(64)),
        java.util.Map.of("adapter_release", "sha256:" + "9".repeat(64)), base.targetBinding());
    assertThat(changedProfile.fingerprint()).isNotEqualTo(base.fingerprint());
    var changedEnrollment = new AgentCoreRegistrationIngress.Enrollment(ISSUER, enrollment().verifiedSubjectId(),
        enrollment().clientId(), SCOPE, changedProfile, "synthetic-other-retention", Duration.ofDays(60));
    var retry = ingress(changedEnrollment, replay, repository).register(token(), null, bytes(request()));
    assertThat(retry.created()).isFalse();
    assertThat(retry.operationId()).isEqualTo(first.operationId());
    assertThat(retry.executionEnabled()).isFalse();
    assertThat(state()).isEqualTo(before);
    assertThatThrownBy(() -> GitHubBusinessOperationBinding.bind(SCOPE, original, changedProfile))
        .isInstanceOf(GitHubBusinessOperationBinding.BindingException.class);
    // Registration success is therefore not proof of current policy/profile authority.
  }

  @ParameterizedTest
  @ValueSource(strings = {"issuer", "audience", "subject", "client", "tenant", "scope", "expired",
      "enrollmentSubject", "enrollmentClient", "enrollmentTenant"})
  void rejectedIdentityOrEnrollmentNeverOpensJdbcOrInserts(String change) {
    Consumer<ObjectNode> mutate = claims -> {
      switch (change) {
        case "issuer" -> claims.put("iss", "https://other-identity.example.invalid");
        case "audience" -> claims.put("aud", "https://other-resource.example.invalid");
        case "subject" -> claims.put("sub", "workload:unenrolled");
        case "client" -> claims.put("client_id", "other-client");
        case "tenant" -> claims.put("org_id", "other-org");
        case "scope" -> claims.put("scope", "operations.other");
        case "expired" -> claims.put("exp", NOW.getEpochSecond());
        default -> { }
      }
    };
    var base = enrollment();
    var selected = new AgentCoreRegistrationIngress.Enrollment(base.issuer(),
        change.equals("enrollmentSubject") ? ISSUER + "#workload:other" : base.verifiedSubjectId(),
        change.equals("enrollmentClient") ? "other-client" : base.clientId(),
        change.equals("enrollmentTenant") ? new BusinessOperationScope("other-tenant", SCOPE.workspaceId(), SCOPE.requesterPrincipalId()) : SCOPE,
        base.targetProfile(), base.retentionPolicyId(), base.retention());
    rejected(() -> ingress(selected, replay, repository).register(token(mutate), null, bytes(request())), "AGENTCORE_IDENTITY_REJECTED");
    assertNoJdbcOrRows();
  }

  @ParameterizedTest
  @ValueSource(strings = {"workspaceId", "tenantId", "requesterPrincipalId", "trustedContext", "authenticated",
      "delegated", "verified", "approved", "humanToken", "authorization", "target", "profile", "policyRelease", "scope"})
  void untrustedScopeAuthorityAndOboFieldsStopBeforeJdbc(String field) {
    ObjectNode malformed = request().put(field, "synthetic-untrusted");
    rejected(() -> ingress().register(token(), null, bytes(malformed)), "AGENTCORE_REQUEST_INVALID");
    assertNoJdbcOrRows();
  }

  @Test
  void allHumanAuthorizationPairsAreRefusedBeforeAnyJdbcAccess() {
    String workload = token();
    String human = token(claims -> claims.put("sub", "human:synthetic"));
    assertThat(verifier().authenticate(workload)).isNotNull();
    assertThat(verifier().authenticate(human)).isNotNull();
    for (String first : new String[] {workload, human, "invalid", null}) {
      for (String second : new String[] {human, workload, "", "invalid"}) {
        rejected(() -> ingress().register(first, second, bytes(request())), "AGENTCORE_DELEGATION_CONTRACT_REQUIRED");
      }
    }
    assertNoJdbcOrRows();
  }

  @Test
  void signedWorkspaceAliasesDoNotOverrideTheOperatorEnrollment() {
    var receipt = ingress().register(token(claims -> claims.put("workspaceId", "other-workspace")
        .put("requesterPrincipalId", "workload:other")), null, bytes(request()));
    assertThat(assertSinglePersisted(receipt.operationId()).path("trustedContext")).isEqualTo(SCOPE.trustedContext());
    // Workspace comes from operator enrollment, not an independently verified provider workspace claim.
  }

  @Test
  void explicitOperatorWorkspaceEnrollmentPartitionsKeysInsteadOfProvingProviderWorkspaceAuthority() {
    var first = ingress().register(token(), null, bytes(request()));
    var alternate = new BusinessOperationScope(SCOPE.tenantId(), "other-workspace", SCOPE.requesterPrincipalId());
    var second = ingress(enrollment(alternate, enrollment().targetProfile()), replay, repository)
        .register(token(), null, bytes(request()));
    assertThat(second.created()).isTrue();
    assertThat(second.operationId()).isNotEqualTo(first.operationId());
    assertCounts(2, 6, 2, 0);
    assertThat(repository.find(SCOPE, second.operationId())).isEmpty();
    assertThat(repository.find(alternate, first.operationId())).isEmpty();
    assertThat(repository.find(alternate, second.operationId())).isPresent();
  }

  @ParameterizedTest
  @ValueSource(strings = {"duplicate", "trailing", "schema", "oversized", "utf8"})
  void parserRefusalsStopBeforeJdbc(String change) {
    byte[] input = switch (change) {
      case "duplicate" -> "{\"schemaVersion\":\"1.0\",\"schemaVersion\":\"1.0\"}".getBytes(StandardCharsets.UTF_8);
      case "trailing" -> (request() + " {}").getBytes(StandardCharsets.UTF_8);
      case "schema" -> bytes(request().put("schemaVersion", "2.0"));
      case "oversized" -> new byte[AgentCoreRegistrationIngress.MAXIMUM_REQUEST_BYTES + 1];
      case "utf8" -> new byte[] {(byte) 0xc3, (byte) 0x28};
      default -> throw new AssertionError("UNKNOWN_SYNTHETIC_CASE");
    };
    rejected(() -> ingress().register(token(), null, input), "AGENTCORE_REQUEST_INVALID");
    assertNoJdbcOrRows();
  }

  @Test
  void sixteenExactTokenContendersReachJdbcAtMostOnceThroughSharedProcessReplay() throws Exception {
    String authorization = token();
    var accepted = new AtomicInteger();
    var refused = new AtomicInteger();
    race(16, index -> {
      try {
        assertThat(ingress().register(authorization, null, bytes(request())).created()).isTrue();
        accepted.incrementAndGet();
      } catch (AgentCoreRegistrationIngress.IngressException failure) {
        assertThat(failure.code()).isEqualTo("AGENTCORE_REGISTRATION_REJECTED");
        refused.incrementAndGet();
      }
    });
    assertThat(accepted.get()).isEqualTo(1);
    assertThat(refused.get()).isEqualTo(15);
    assertThat(runtime.opens.get()).isEqualTo(1);
    assertThat(runtime.actualCommits.get()).isEqualTo(1);
    assertCounts(1, 3, 1, 0);
  }

  @Test
  void sixteenFreshTokenContendersHaveOneDurableOriginalAndOneMetadataCommit() throws Exception {
    List<AgentCoreRegistrationIngress.Receipt> receipts = java.util.Collections.synchronizedList(new ArrayList<>());
    List<String> authorizations = java.util.stream.IntStream.range(0, 16).mapToObj(index -> token()).toList();
    race(16, index -> receipts.add(ingress().register(authorizations.get(index), null, bytes(request()))));
    assertThat(receipts).hasSize(16);
    assertThat(receipts.stream().filter(AgentCoreRegistrationIngress.Receipt::created).count()).isEqualTo(1);
    assertThat(receipts.stream().map(AgentCoreRegistrationIngress.Receipt::operationId).distinct().count()).isEqualTo(1);
    assertThat(receipts).allSatisfy(receipt -> assertThat(receipt.executionEnabled()).isFalse());
    assertThat(runtime.actualCommits.get()).isEqualTo(1);
    assertSinglePersisted(receipts.get(0).operationId());
  }

  @ParameterizedTest
  @EnumSource(value = RegistrationJdbcProbeSource.Fault.class, mode = EnumSource.Mode.EXCLUDE, names = "NONE")
  void realJdbcPartialWritesAndCommitCleanupUncertaintyConsumeTheTokenWithoutAutomaticRetry(
      RegistrationJdbcProbeSource.Fault fault) {
    runtime.fault = fault;
    String authorization = token();
    boolean committed = fault == RegistrationJdbcProbeSource.Fault.AFTER_COMMIT
        || fault == RegistrationJdbcProbeSource.Fault.CLOSE_AFTER_COMMIT;
    boolean unknown = committed || fault == RegistrationJdbcProbeSource.Fault.BEFORE_COMMIT;
    rejected(() -> ingress().register(authorization, null, bytes(request())),
        unknown ? "AGENTCORE_REGISTRATION_UNKNOWN" : "AGENTCORE_STORAGE_UNAVAILABLE");
    assertThat(runtime.opens.get()).isEqualTo(1);
    assertThat(runtime.actualCommits.get()).isEqualTo(committed ? 1 : 0);
    if (fault == RegistrationJdbcProbeSource.Fault.SECOND_FACT
        || fault == RegistrationJdbcProbeSource.Fault.SECOND_FACT_AND_CLOSE) {
      assertThat(runtime.insertAttempts.get()).isEqualTo(3);
      assertThat(runtime.rollbacks.get()).isEqualTo(1);
    } else {
      assertThat(runtime.insertAttempts.get()).isEqualTo(5);
    }
    assertCounts(committed ? 1 : 0, committed ? 3 : 0, committed ? 1 : 0, 0);
    DbState beforeRetry = state();
    runtime.fault = RegistrationJdbcProbeSource.Fault.NONE;
    rejected(() -> ingress().register(authorization, null, bytes(request())), "AGENTCORE_REGISTRATION_REJECTED");
    assertThat(runtime.opens.get()).isEqualTo(1);
    assertThat(state()).isEqualTo(beforeRetry);
    var recovered = ingress().register(token(), null, bytes(request()));
    assertThat(recovered.created()).isEqualTo(!committed);
    assertThat(recovered.executionEnabled()).isFalse();
    assertSinglePersisted(recovered.operationId());
    if (committed) assertThat(state()).isEqualTo(beforeRetry);
    assertThat(runtime.actualCommits.get()).isEqualTo(1);
  }

  @Test
  void actualDatabasePermissionFailureRollsBackSnapshotAndConsumesIdentity() {
    String authorization = token();
    grantFactInsert(false);
    try {
      rejected(() -> ingress().register(authorization, null, bytes(request())), "AGENTCORE_STORAGE_UNAVAILABLE");
      assertThat(runtime.opens.get()).isEqualTo(1);
      assertThat(runtime.insertAttempts.get()).isEqualTo(2);
      assertThat(runtime.rollbacks.get()).isEqualTo(1);
      assertCounts(0, 0, 0, 0);
    } finally {
      grantFactInsert(true);
    }
    rejected(() -> ingress().register(authorization, null, bytes(request())), "AGENTCORE_REGISTRATION_REJECTED");
    assertThat(runtime.opens.get()).isEqualTo(1);
    var created = ingress().register(token(), null, bytes(request()));
    assertThat(created.created()).isTrue();
    assertSinglePersisted(created.operationId());
  }

  @Test
  void restrictedRuntimeCannotDdlRewriteDeleteOrCreateExecutionAuthority() {
    var receipt = ingress().register(token(), null, bytes(request()));
    DbState before = state();
    for (String sql : List.of("CREATE TABLE tp_ingress_forbidden (id INTEGER)",
        "UPDATE tp_business_operations SET snapshot_json = snapshot_json",
        "DELETE FROM tp_business_operations", "UPDATE tp_operation_lifecycle_heads SET snapshot_json = snapshot_json",
        "DELETE FROM tp_business_operation_facts", "ALTER TABLE tp_business_operations ADD COLUMN forbidden INTEGER",
        "INSERT INTO tp_operation_lifecycle_facts SELECT * FROM tp_operation_lifecycle_facts WHERE 1 = 0")) {
      boolean refused = false;
      try (Connection connection = runtime.getConnection(); var statement = connection.createStatement()) {
        statement.setQueryTimeout(5);
        statement.execute(sql);
      } catch (SQLException expected) {
        // A constraint/syntax failure is not evidence of a restricted grant.
        boolean permissionDenied = postgres() ? "42501".equals(expected.getSQLState())
            : "42000".equals(expected.getSQLState())
                && (expected.getErrorCode() == 1142 || expected.getErrorCode() == 1143);
        assertThat(permissionDenied).as("Driver must report permission denial").isTrue();
        refused = true;
      }
      assertThat(refused).as("Runtime privilege denial").isTrue();
    }
    assertThat(state()).isEqualTo(before);
    assertSinglePersisted(receipt.operationId());
    assertThat(tables()).containsExactlyInAnyOrder("tp_operation_schema_history", "tp_business_operations",
        "tp_business_operation_facts", "tp_operation_lifecycle_heads", "tp_operation_lifecycle_facts");
    assertThat(AgentCoreRegistrationIngress.class.getDeclaredConstructors()).hasSize(1);
    assertThat(AgentCoreRegistrationIngress.class.getDeclaredConstructors()[0].getParameterTypes())
        .containsExactly(OidcAccessTokenVerifier.class, WorkloadIdentityReplayStore.class,
            BusinessOperationRepository.class, Clock.class, AgentCoreRegistrationIngress.Enrollment.class);
    // No authority/permit/attempt/target or credential port exists in this composition.
  }

  @Test
  void requiredExactDatabaseVersionIsObserved() {
    try (Connection connection = admin.getConnection()) {
      assertThat(connection.getMetaData().getDatabaseProductVersion())
          .matches(java.util.regex.Pattern.quote(expectedVersion()) + "(?:[^0-9.].*)?");
    } catch (SQLException failure) {
      throw fixtureFailure();
    }
  }

  private AgentCoreRegistrationIngress ingress() {
    return ingress(enrollment(), replay, repository);
  }

  private AgentCoreRegistrationIngress ingress(AgentCoreRegistrationIngress.Enrollment selected,
      WorkloadIdentityReplayStore replayStore, JdbcBusinessOperationRepository jdbc) {
    return new AgentCoreRegistrationIngress(verifier(), replayStore, jdbc, CLOCK, selected);
  }

  private void assertNoJdbcOrRows() {
    assertThat(runtime.opens.get()).isZero();
    assertThat(runtime.insertAttempts.get()).isZero();
    assertThat(runtime.commitAttempts.get()).isZero();
    assertCounts(0, 0, 0, 0);
  }

  private JsonNode assertSinglePersisted(String operationId) {
    assertCounts(1, 3, 1, 0);
    try (Connection connection = admin.getConnection(); var query = connection.createStatement()) {
      query.setQueryTimeout(5);
      JsonNode original;
      String snapshotHash;
      try (var rows = query.executeQuery("SELECT operation_id, snapshot_json, snapshot_hash FROM tp_business_operations")) {
        assertThat(rows.next()).isTrue();
        assertThat(rows.getString(1)).isEqualTo(operationId);
        original = JsonSupport.MAPPER.readTree(rows.getString(2));
        snapshotHash = rows.getString(3);
        assertThat(rows.next()).isFalse();
      }
      assertThat(BusinessOperationValidator.validate(original)).isEqualTo(original);
      assertThat(snapshotHash).isEqualTo(CanonicalJson.sha256(original));
      assertThat(original.path("authorization").path("status").textValue()).isEqualTo("evaluating");
      assertThat(original.path("execution").path("status").textValue()).isEqualTo("not_started");
      assertThat(original.path("evidence").path("status").textValue()).isEqualTo("pending");
      try (var rows = query.executeQuery("SELECT operation_id, sequence_number, fact_json FROM tp_business_operation_facts ORDER BY sequence_number")) {
        int index = 0;
        while (rows.next()) {
          assertThat(rows.getString(1)).isEqualTo(operationId);
          assertThat(rows.getInt(2)).isEqualTo(index + 1);
          assertThat(CanonicalJson.canonicalize(JsonSupport.MAPPER.readTree(rows.getString(3))))
              .isEqualTo(CanonicalJson.canonicalize(original.path("transitions").get(index++)));
        }
        assertThat(index).isEqualTo(3);
      }
      try (var rows = query.executeQuery("SELECT operation_id, snapshot_json, snapshot_hash FROM tp_operation_lifecycle_heads")) {
        assertThat(rows.next()).isTrue();
        assertThat(rows.getString(1)).isEqualTo(operationId);
        assertThat(CanonicalJson.canonicalize(JsonSupport.MAPPER.readTree(rows.getString(2))))
            .isEqualTo(CanonicalJson.canonicalize(original));
        assertThat(rows.getString(3)).isEqualTo(snapshotHash);
        assertThat(rows.next()).isFalse();
      }
      return original;
    } catch (SQLException | java.io.IOException failure) {
      throw fixtureFailure();
    }
  }

  private void assertCounts(int originals, int facts, int heads, int journal) {
    int[] expected = {originals, facts, heads, journal};
    try (Connection connection = admin.getConnection(); var query = connection.createStatement()) {
      query.setQueryTimeout(5);
      for (int i = 0; i < DATA_TABLES.size(); i++) {
        try (var rows = query.executeQuery("SELECT COUNT(*) FROM " + DATA_TABLES.get(i))) {
          assertThat(rows.next()).isTrue();
          assertThat(rows.getInt(1)).isEqualTo(expected[i]);
        }
      }
    } catch (SQLException failure) { throw fixtureFailure(); }
  }

  private record DbState(List<String> originals, List<String> facts, List<String> heads, List<String> journal) {}

  private DbState state() {
    return new DbState(storedRows("SELECT operation_id, snapshot_json, snapshot_hash FROM tp_business_operations ORDER BY operation_id", 3),
        storedRows("SELECT operation_id, fact_json FROM tp_business_operation_facts ORDER BY operation_id, sequence_number", 2),
        storedRows("SELECT operation_id, snapshot_json, snapshot_hash FROM tp_operation_lifecycle_heads ORDER BY operation_id", 3),
        storedRows("SELECT operation_id, fact_json FROM tp_operation_lifecycle_facts ORDER BY operation_id, sequence_number", 2));
  }

  private List<String> storedRows(String sql, int columns) {
    try (Connection connection = admin.getConnection(); var query = connection.createStatement()) {
      query.setQueryTimeout(5);
      List<String> result = new ArrayList<>();
      try (var rows = query.executeQuery(sql)) {
        while (rows.next()) for (int i = 1; i <= columns; i++) result.add(rows.getString(i));
      }
      return List.copyOf(result);
    } catch (SQLException failure) { throw fixtureFailure(); }
  }

  private Set<String> tables() {
    String schema = postgres() ? "public" : database.getDatabaseName();
    try (Connection connection = admin.getConnection(); var query = connection.prepareStatement(
        "SELECT table_name FROM information_schema.tables WHERE table_schema = ?")) {
      query.setQueryTimeout(5); query.setString(1, schema);
      Set<String> result = new TreeSet<>();
      try (var rows = query.executeQuery()) { while (rows.next()) result.add(rows.getString(1)); }
      return result;
    } catch (SQLException failure) { throw fixtureFailure(); }
  }

  private boolean postgres() { return database.getJdbcUrl().startsWith("jdbc:postgresql:"); }

  private RegistrationJdbcProbeSource grantOwner() {
    return postgres() ? admin : new RegistrationJdbcProbeSource(database.getJdbcUrl(), "root", database.getPassword());
  }

  private void provisionRuntime() {
    String password = UUID.randomUUID().toString();
    try (Connection connection = grantOwner().getConnection(); var statement = connection.createStatement()) {
      statement.setQueryTimeout(5);
      if (postgres()) {
        statement.execute("CREATE ROLE " + RUNTIME_USER + " LOGIN PASSWORD '" + password + "'");
        statement.execute("GRANT USAGE ON SCHEMA public TO " + RUNTIME_USER);
        for (String table : DATA_TABLES) statement.execute("GRANT SELECT"
            + (table.equals("tp_operation_lifecycle_facts") ? "" : ", INSERT") + " ON " + table + " TO " + RUNTIME_USER);
        statement.execute("GRANT SELECT ON tp_operation_schema_history TO " + RUNTIME_USER);
      } else {
        statement.execute("CREATE USER '" + RUNTIME_USER + "'@'%' IDENTIFIED BY '" + password + "'");
        for (String table : DATA_TABLES) statement.execute("GRANT SELECT"
            + (table.equals("tp_operation_lifecycle_facts") ? "" : ", INSERT") + " ON `" + database.getDatabaseName()
            + "`." + table + " TO '" + RUNTIME_USER + "'@'%'");
        statement.execute("GRANT SELECT ON `" + database.getDatabaseName() + "`.tp_operation_schema_history TO '" + RUNTIME_USER + "'@'%'");
      }
      runtime = new RegistrationJdbcProbeSource(database.getJdbcUrl(), RUNTIME_USER, password);
    } catch (SQLException failure) { throw fixtureFailure(); }
  }

  private void grantFactInsert(boolean allowed) {
    String verb = allowed ? "GRANT INSERT ON " : "REVOKE INSERT ON ";
    String preposition = allowed ? " TO " : " FROM ";
    String target = postgres() ? "tp_business_operation_facts" : "`" + database.getDatabaseName() + "`.tp_business_operation_facts";
    String user = postgres() ? RUNTIME_USER : "'" + RUNTIME_USER + "'@'%'";
    try (Connection connection = grantOwner().getConnection(); var statement = connection.createStatement()) {
      statement.setQueryTimeout(5);
      statement.execute(verb + target + preposition + user);
    } catch (SQLException failure) { throw fixtureFailure(); }
  }

  private void adminExecute(String sql) {
    try (Connection connection = admin.getConnection(); var statement = connection.createStatement()) {
      statement.setQueryTimeout(5); statement.execute(sql);
    } catch (SQLException failure) { throw fixtureFailure(); }
  }

  private static void rejected(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, String code) {
    assertThatThrownBy(call).isInstanceOf(AgentCoreRegistrationIngress.IngressException.class)
        .hasMessage(code).hasNoCause().satisfies(failure -> {
          assertThat(failure.getSuppressed()).isEmpty();
          assertThat(failure.toString()).doesNotContain(RegistrationJdbcProbeSource.CANARY);
        });
  }

  @FunctionalInterface
  private interface IndexedCall { void run(int index) throws Exception; }

  private static void race(int count, IndexedCall call) throws Exception {
    CountDownLatch ready = new CountDownLatch(count), start = new CountDownLatch(1);
    var executor = Executors.newFixedThreadPool(count);
    try {
      var futures = new ArrayList<java.util.concurrent.Future<?>>();
      for (int i = 0; i < count; i++) {
        final int index = i;
        futures.add(executor.submit(() -> {
          ready.countDown();
          if (!start.await(10, TimeUnit.SECONDS)) throw new AssertionError("SYNTHETIC_RACE_START_TIMEOUT");
          call.run(index);
          return null;
        }));
      }
      assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue(); start.countDown();
      long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
      for (var future : futures) future.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
    } finally {
      start.countDown(); executor.shutdownNow();
      assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
    }
  }

  private static AssertionError fixtureFailure() { return new AssertionError("SYNTHETIC_JDBC_FIXTURE_FAILED"); }
}
