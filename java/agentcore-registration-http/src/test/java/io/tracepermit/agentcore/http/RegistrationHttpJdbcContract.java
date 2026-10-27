package io.tracepermit.agentcore.http;

import static io.tracepermit.agentcore.http.HttpRegistrationTestSupport.*;
import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import io.tracepermit.contracts.CanonicalJson;
import io.tracepermit.contracts.JsonSupport;
import io.tracepermit.persistence.operations.JdbcBusinessOperationRepository;
import io.tracepermit.persistence.operations.OperationDatabase;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.containers.JdbcDatabaseContainer;

/** Actual signed HTTP/JWKS/ingress/JDBC, with explicit fixture-admin DDL and restricted runtime. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class RegistrationHttpJdbcContract {
  private static final String USER = "tp_http_registration";
  private static final List<String> TABLES = List.of("tp_business_operations", "tp_business_operation_facts", "tp_operation_lifecycle_heads", "tp_operation_lifecycle_facts");
  private JdbcDatabaseContainer<?> database;
  private HttpJdbcProbeSource admin;
  private HttpJdbcProbeSource runtime;
  private HttpRegistrationTestSupport identity;
  private AgentCoreRegistrationHttpServer server;

  abstract JdbcDatabaseContainer<?> container();
  abstract String version();

  @BeforeAll void install() {
    database = container().withPassword(UUID.randomUUID().toString());
    database.start(); // Required exact fixture, no availability skip or substitute image.
    admin = new HttpJdbcProbeSource(database.getJdbcUrl(), database.getUsername(), database.getPassword());
    OperationDatabase.initializeEmpty(admin);
    String password = UUID.randomUUID().toString();
    var owner = postgres() ? admin : new HttpJdbcProbeSource(database.getJdbcUrl(), "root", database.getPassword());
    try (Connection connection = owner.getConnection(); var statement = connection.createStatement()) {
      statement.setQueryTimeout(5);
      if (postgres()) {
        statement.execute("CREATE ROLE " + USER + " LOGIN PASSWORD '" + password + "'");
        statement.execute("GRANT USAGE ON SCHEMA public TO " + USER);
        for (String table : TABLES) statement.execute("GRANT SELECT" + (table.equals("tp_operation_lifecycle_facts") ? "" : ", INSERT") + " ON " + table + " TO " + USER);
        statement.execute("GRANT SELECT ON tp_operation_schema_history TO " + USER);
      } else {
        statement.execute("CREATE USER '" + USER + "'@'%' IDENTIFIED BY '" + password + "'");
        for (String table : TABLES) statement.execute("GRANT SELECT" + (table.equals("tp_operation_lifecycle_facts") ? "" : ", INSERT") + " ON `" + database.getDatabaseName() + "`." + table + " TO '" + USER + "'@'%'");
        statement.execute("GRANT SELECT ON `" + database.getDatabaseName() + "`.tp_operation_schema_history TO '" + USER + "'@'%'");
      }
      runtime = new HttpJdbcProbeSource(database.getJdbcUrl(), USER, password);
      assertThat(connection.getMetaData().getDatabaseProductVersion()).matches(java.util.regex.Pattern.quote(version()) + "(?:[^0-9.].*)?");
    } catch (SQLException failure) { throw fixtureFailure(); }
  }

  @BeforeEach void start() {
    for (String table : List.of("tp_operation_lifecycle_facts", "tp_operation_lifecycle_heads", "tp_business_operation_facts", "tp_business_operations")) execute("DELETE FROM " + table);
    runtime.resetProbe();
    var repository = new JdbcBusinessOperationRepository(runtime);
    runtime.resetProbe();
    identity = new HttpRegistrationTestSupport();
    server = new AgentCoreRegistrationHttpServer(identity.ingress(repository), 0, AgentCoreRegistrationHttpServer.Limits.defaults());
  }
  @AfterEach void closeServer() {
    if (server != null) server.close();
    if (identity != null) identity.close();
  }
  @AfterAll void stop() { if (database != null) database.stop(); }

  @Test void signedHttpPersistsOnlyInitialMetadataAndFreshTokenRetriesReadTheExactOriginal() throws Exception {
    String authorization = token();
    var response = send(server.port(), authorization, body());
    assertThat(response.status()).isEqualTo(201);
    assertThat(JsonSupport.MAPPER.readTree(response.body()).size()).isEqualTo(3);
    String id = JsonSupport.MAPPER.readTree(response.body()).path("operationId").textValue();
    String original = stored();
    assertPersisted(id);
    var replay = send(server.port(), authorization, body());
    assertThat(replay.status()).isEqualTo(409);
    assertThat(runtime.opens.get()).isEqualTo(1);
    var retried = send(server.port(), token(), body());
    assertThat(retried.status()).isEqualTo(200);
    assertThat(JsonSupport.MAPPER.readTree(retried.body()).path("operationId").textValue()).isEqualTo(id);
    assertThat(stored()).isEqualTo(original);
    assertThat(runtime.actualCommits.get()).isEqualTo(1);
    assertThat(identity.jwksFetches.get()).isEqualTo(1);
    assertThat(response.body()).doesNotContain(authorization, "sha256:", "canonicalAction", "trustedContext", "Fingerprint");
    assertThat(response.headers()).contains("Cache-Control: no-store").doesNotContain("Access-Control", "Location:");
    server.close();
    var reconstructedSource = runtime.reconstructed();
    var reconstructed = new JdbcBusinessOperationRepository(reconstructedSource);
    reconstructedSource.resetProbe();
    server = new AgentCoreRegistrationHttpServer(identity.ingress(reconstructed), 0, AgentCoreRegistrationHttpServer.Limits.defaults());
    assertThat(send(server.port(), token(), body()).status()).isEqualTo(200);
    assertThat(stored()).isEqualTo(original);
    assertThat(reconstructedSource.actualCommits.get()).isZero();
    assertPersisted(id);
  }

  @ParameterizedTest @ValueSource(strings = {"title", "body"})
  void changedContentConflictsWithoutReplacingOriginalAndItsTokenStaysConsumed(String field) throws Exception {
    assertThat(send(server.port(), token(), body()).status()).isEqualTo(201);
    String before = stored();
    String authorization = token();
    var conflict = send(server.port(), authorization, body().put(field, "Synthetic changed content"));
    assertThat(conflict.status()).isEqualTo(409);
    assertThat(conflict.body()).isEqualTo("{\"error\":\"AGENTCORE_REQUEST_KEY_CONFLICT\"}");
    int opens = runtime.opens.get();
    assertThat(send(server.port(), authorization, body()).body()).isEqualTo("{\"error\":\"AGENTCORE_REGISTRATION_REJECTED\"}");
    assertThat(runtime.opens.get()).isEqualTo(opens);
    assertThat(stored()).isEqualTo(before);
    assertCounts(1, 3, 1, 0);
  }

  @ParameterizedTest @ValueSource(strings = {"issuer", "audience", "subject", "client", "tenant", "scope", "expired", "sender-bound"})
  void rejectedSignedIdentityHasNoJdbcAttempt(String change) throws Exception {
    String authorization = token(claims -> {
      switch (change) {
        case "issuer" -> claims.put("iss", "https://other.example.invalid");
        case "audience" -> claims.put("aud", "https://other-resource.example.invalid");
        case "subject" -> claims.put("sub", "workload:unenrolled");
        case "client" -> claims.put("client_id", "other-client");
        case "tenant" -> claims.put("org_id", "other-org");
        case "scope" -> claims.put("scope", "operations.other");
        case "expired" -> claims.put("exp", NOW.getEpochSecond());
        case "sender-bound" -> claims.putObject("cnf").put("x5t#S256", java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]));
        default -> throw new AssertionError("UNKNOWN_SYNTHETIC_CASE");
      }
    });
    assertThat(send(server.port(), authorization, body()).status()).isEqualTo(401);
    assertNoInsert();
  }

  @ParameterizedTest @ValueSource(strings = {"tenantId", "workspaceId", "requesterPrincipalId", "policyRelease", "generation", "authenticated", "approved", "verified", "target"})
  void bodyCannotChooseScopeOrAuthorityBeforeDurableInsertion(String field) throws Exception {
    var response = send(server.port(), token(), body().put(field, CANARY));
    assertThat(response.status()).isEqualTo(400);
    assertThat(response.body()).doesNotContain(CANARY);
    assertNoInsert();
    assertThat(identity.verifications.get()).isZero();
  }

  @ParameterizedTest @ValueSource(strings = {"duplicate", "cookie", "forwarded", "human"})
  void untrustedHttpIdentityCompositionStopsBeforeJdbc(String kind) throws Exception {
    byte[] body = bytes(body());
    var fields = new java.util.ArrayList<>(List.of("Host: 127.0.0.1:" + server.port(), "Authorization: " + token(), "Content-Type: application/json", "Content-Length: " + body.length));
    fields.add(switch (kind) {
      case "duplicate" -> "aUtHoRiZaTiOn: " + token();
      case "cookie" -> "Cookie: session=" + CANARY;
      case "forwarded" -> "Forwarded: for=127.0.0.1";
      case "human" -> "Human-Authorization: " + token(claims -> claims.put("sub", "human:synthetic"));
      default -> throw new AssertionError("UNKNOWN_SYNTHETIC_CASE");
    });
    var result = send(server.port(), request(server.port(), "POST", AgentCoreRegistrationHttpServer.ROUTE, fields, body));
    assertThat(result.status()).isEqualTo(400);
    if (kind.equals("human")) assertThat(result.body()).contains("AGENTCORE_DELEGATION_CONTRACT_REQUIRED");
    assertNoInsert(); assertThat(identity.verifications.get()).isZero();
  }

  @ParameterizedTest
  @EnumSource(value = HttpJdbcProbeSource.Fault.class, names = {"SECOND_FACT", "AFTER_COMMIT", "CLOSE_AFTER_COMMIT"})
  void actualJdbcFailuresAndAcknowledgementLossStayFixedUncertainAndNeverAutomaticallyRetry(HttpJdbcProbeSource.Fault fault) throws Exception {
    runtime.fault = fault;
    String authorization = token();
    boolean committed = fault != HttpJdbcProbeSource.Fault.SECOND_FACT;
    var response = send(server.port(), authorization, body().put("title", CANARY));
    assertThat(response.status()).isEqualTo(503);
    assertThat(response.body()).isEqualTo(committed ? "{\"error\":\"AGENTCORE_REGISTRATION_UNKNOWN\"}" : "{\"error\":\"AGENTCORE_STORAGE_UNAVAILABLE\"}");
    assertThat(response.body()).doesNotContain(CANARY, authorization, HttpJdbcProbeSource.CANARY, "created", "sha256:", "snapshot");
    assertThat(runtime.opens.get()).isEqualTo(1);
    assertCounts(committed ? 1 : 0, committed ? 3 : 0, committed ? 1 : 0, 0);
    String before = committed ? stored() : null;
    runtime.fault = HttpJdbcProbeSource.Fault.NONE;
    assertThat(send(server.port(), authorization, body()).status()).isEqualTo(409);
    assertThat(runtime.opens.get()).isEqualTo(1);
    var retry = send(server.port(), token(), body().put("title", CANARY));
    assertThat(retry.status()).isEqualTo(committed ? 200 : 201);
    assertThat(JsonSupport.MAPPER.readTree(retry.body()).path("executionEnabled").booleanValue()).isFalse();
    if (committed) assertThat(stored()).isEqualTo(before);
    assertThat(runtime.actualCommits.get()).isEqualTo(1);
    assertPersisted(JsonSupport.MAPPER.readTree(retry.body()).path("operationId").textValue());
  }

  private void assertNoInsert() {
    assertThat(runtime.opens.get()).isZero(); assertThat(runtime.insertAttempts.get()).isZero(); assertCounts(0, 0, 0, 0);
  }
  private void assertPersisted(String id) throws Exception {
    assertCounts(1, 3, 1, 0);
    JsonNode original = JsonSupport.MAPPER.readTree(stored());
    assertThat(original.path("operationId").textValue()).isEqualTo(id);
    assertThat(original.path("trustedContext")).isEqualTo(SCOPE.trustedContext());
    assertThat(original.path("authorization").path("status").textValue()).isEqualTo("evaluating");
    assertThat(original.path("execution").path("status").textValue()).isEqualTo("not_started");
    assertThat(original.path("evidence").path("status").textValue()).isEqualTo("pending");
    try (Connection c = admin.getConnection(); var query = c.createStatement()) {
      query.setQueryTimeout(5);
      try (var rows = query.executeQuery("SELECT snapshot_json, snapshot_hash FROM tp_operation_lifecycle_heads")) {
        assertThat(rows.next()).isTrue();
        assertThat(CanonicalJson.canonicalize(JsonSupport.MAPPER.readTree(rows.getString(1)))).isEqualTo(CanonicalJson.canonicalize(original));
        assertThat(rows.getString(2)).isEqualTo(CanonicalJson.sha256(original));
      }
      try (var rows = query.executeQuery("SELECT fact_json FROM tp_business_operation_facts ORDER BY sequence_number")) {
        int i = 0;
        while (rows.next()) assertThat(CanonicalJson.canonicalize(JsonSupport.MAPPER.readTree(rows.getString(1))))
            .isEqualTo(CanonicalJson.canonicalize(original.path("transitions").get(i++)));
        assertThat(i).isEqualTo(3);
      }
    } catch (SQLException failure) { throw fixtureFailure(); }
  }
  private String stored() {
    try (Connection c = admin.getConnection(); var query = c.createStatement()) {
      query.setQueryTimeout(5);
      try (var rows = query.executeQuery("SELECT snapshot_json FROM tp_business_operations")) { assertThat(rows.next()).isTrue(); return rows.getString(1); }
    } catch (SQLException failure) { throw fixtureFailure(); }
  }
  private void assertCounts(int originals, int facts, int heads, int journal) {
    int[] counts = {originals, facts, heads, journal};
    try (Connection c = admin.getConnection(); var query = c.createStatement()) {
      query.setQueryTimeout(5);
      for (int i = 0; i < TABLES.size(); i++) try (var rows = query.executeQuery("SELECT COUNT(*) FROM " + TABLES.get(i))) {
        assertThat(rows.next()).isTrue(); assertThat(rows.getInt(1)).isEqualTo(counts[i]);
      }
    } catch (SQLException failure) { throw fixtureFailure(); }
  }
  private void execute(String sql) {
    try (Connection c = admin.getConnection(); var statement = c.createStatement()) { statement.setQueryTimeout(5); statement.execute(sql); }
    catch (SQLException failure) { throw fixtureFailure(); }
  }
  private boolean postgres() { return database.getJdbcUrl().startsWith("jdbc:postgresql:"); }
  private static AssertionError fixtureFailure() { return new AssertionError("SYNTHETIC_HTTP_JDBC_FIXTURE_FAILED"); }
}
