package io.tracepermit.adapters.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.tracepermit.application.ExecutionResult;
import io.tracepermit.application.SecurityReadiness;
import io.tracepermit.application.TenantActionEnforcer;
import io.tracepermit.application.TrustedTenantContext;
import io.tracepermit.contracts.JsonSupport;
import io.tracepermit.execution.TenantPartition;
import io.tracepermit.simulator.StatefulTargetSimulator;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

final class DevelopmentBackendMainTest {
  private static final Path ROOT = Path.of(System.getProperty("tracepermit.repositoryRoot"));
  private static final Instant NOW = Instant.parse("2026-10-10T10:15:30Z");
  private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

  @Test
  void createsMemoryEnforcerByDefault() {
    TenantActionEnforcer enforcer =
        DevelopmentBackendMain.createEnforcer(
            Map.of(),
            ROOT,
            new StatefulTargetSimulator(),
            SecurityReadiness.allReady(NOW),
            CLOCK);
    assertThat(enforcer).isNotNull();

    JsonNode event = testEvent();
    ExecutionResult result =
        enforcer.execute(new TrustedTenantContext(new TenantPartition("acme")), event);
    assertThat(result.outcome()).isEqualTo("succeeded");
  }

  @Test
  void createsPostgresEnforcerWhenRequested() {
    // When postgres is configured but points to non-existent connection, migration attempt fails
    assertThatThrownBy(
            () ->
                DevelopmentBackendMain.createEnforcer(
                    Map.of(
                        "TRACEPERMIT_STORAGE",
                        "postgres",
                        "TRACEPERMIT_POSTGRES_JDBC_URL",
                        "jdbc:postgresql://127.0.0.1:54321/nonexistent"),
                    ROOT,
                    new StatefulTargetSimulator(),
                    SecurityReadiness.allReady(NOW),
                    CLOCK))
        .isNotNull();
    assertThatThrownBy(
            () ->
                DevelopmentBackendMain.createEnforcer(
                    Map.of("TRACEPERMIT_STORAGE", "postgres"),
                    ROOT,
                    new StatefulTargetSimulator(),
                    SecurityReadiness.allReady(NOW),
                    CLOCK))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                DevelopmentBackendMain.createEnforcer(
                    Map.of("TRACEPERMIT_STORAGE", "unknown"),
                    ROOT,
                    new StatefulTargetSimulator(),
                    SecurityReadiness.allReady(NOW),
                    CLOCK))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void postgresModeMigratesAndExecutesOnlyTheConfiguredSimulatedRead() {
    try (PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16.4-alpine")) {
      postgres.start();
      StatefulTargetSimulator simulator = new StatefulTargetSimulator();
      TenantActionEnforcer enforcer =
          DevelopmentBackendMain.createEnforcer(
              Map.of(
                  "TRACEPERMIT_STORAGE", "postgres",
                  "TRACEPERMIT_POSTGRES_JDBC_URL", postgres.getJdbcUrl(),
                  "TRACEPERMIT_POSTGRES_USER", postgres.getUsername(),
                  "TRACEPERMIT_POSTGRES_PASSWORD", postgres.getPassword()),
              ROOT,
              simulator,
              SecurityReadiness.allReady(NOW),
              CLOCK);

      ExecutionResult result =
          enforcer.execute(new TrustedTenantContext(new TenantPartition("acme")), testEvent());
      assertThat(result.outcome()).isEqualTo("succeeded");
      assertThat(result.result().path("mode").asText()).isEqualTo("simulated");

      JsonNode before = simulator.snapshot();
      ExecutionResult destructive =
          enforcer.execute(
              new TrustedTenantContext(new TenantPartition("acme")),
              JsonSupport.read(ROOT.resolve("fixtures/chatgpt-aws-terminate-simulated.json")));
      assertThat(destructive.outcome()).isEqualTo("block");
      assertThat(simulator.snapshot()).isEqualTo(before);
    }
  }

  @Test
  void portParsingValidations() {
    assertThat(DevelopmentBackendMain.port(Map.of(), "TRACEPERMIT_PORT", 8080)).isEqualTo(8080);
    assertThat(DevelopmentBackendMain.port(Map.of("TRACEPERMIT_PORT", "9090"), "TRACEPERMIT_PORT", 8080))
        .isEqualTo(9090);

    assertThatThrownBy(() -> DevelopmentBackendMain.port(Map.of("TRACEPERMIT_PORT", "-1"), "TRACEPERMIT_PORT", 8080))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> DevelopmentBackendMain.port(Map.of("TRACEPERMIT_PORT", "70000"), "TRACEPERMIT_PORT", 8080))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> DevelopmentBackendMain.port(Map.of("TRACEPERMIT_PORT", "abc"), "TRACEPERMIT_PORT", 8080))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void repositoryRootParsing() {
    assertThat(DevelopmentBackendMain.repositoryRoot(Map.of())).isNotNull();
    assertThat(DevelopmentBackendMain.repositoryRoot(Map.of("TRACEPERMIT_REPOSITORY_ROOT", ".")))
        .isEqualTo(Path.of(".").toAbsolutePath().normalize());
  }

  @Test
  void boundedStringValidations() {
    assertThat(DevelopmentBackendMain.bounded(Map.of("KEY", "valid-value"), "KEY", 32)).isEqualTo("valid-value");

    assertThatThrownBy(() -> DevelopmentBackendMain.bounded(Map.of(), "KEY", 32))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> DevelopmentBackendMain.bounded(Map.of("KEY", ""), "KEY", 32))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> DevelopmentBackendMain.bounded(Map.of("KEY", "too-long-string-value"), "KEY", 5))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> DevelopmentBackendMain.bounded(Map.of("KEY", "value\nwith\nnewline"), "KEY", 32))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> DevelopmentBackendMain.bounded(Map.of("KEY", "value\rwith\rcr"), "KEY", 32))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void sourceVerifierConfigurationValidations() throws Exception {
    assertThat(DevelopmentBackendMain.sourceVerifier(Map.of(), CLOCK)).isNull();

    // Incomplete configuration
    assertThatThrownBy(() -> DevelopmentBackendMain.sourceVerifier(Map.of("TRACEPERMIT_ENDPOINT_ISSUER", "iss"), CLOCK))
        .isInstanceOf(IllegalArgumentException.class);

    // Invalid public key
    assertThatThrownBy(
            () ->
                DevelopmentBackendMain.sourceVerifier(
                    Map.of(
                        "TRACEPERMIT_ENDPOINT_ISSUER", "iss",
                        "TRACEPERMIT_ENDPOINT_KEY_ID", "kid",
                        "TRACEPERMIT_ENDPOINT_DEVICE_ID", "dev",
                        "TRACEPERMIT_ENDPOINT_PUBLIC_KEY_SPKI_BASE64", "not-base-64"),
                    CLOCK))
        .isInstanceOf(IllegalArgumentException.class);

    // Valid configuration
    java.security.KeyPairGenerator gen = java.security.KeyPairGenerator.getInstance("Ed25519");
    java.security.KeyPair keyPair = gen.generateKeyPair();
    String encoded = java.util.Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded());

    assertThat(
            DevelopmentBackendMain.sourceVerifier(
                Map.of(
                    "TRACEPERMIT_ENDPOINT_ISSUER", "iss",
                    "TRACEPERMIT_ENDPOINT_KEY_ID", "kid",
                    "TRACEPERMIT_ENDPOINT_DEVICE_ID", "dev",
                    "TRACEPERMIT_ENDPOINT_PUBLIC_KEY_SPKI_BASE64", encoded),
                CLOCK))
        .isNotNull();
  }

  @Test
  void publicKeyParsingValidations() {
    assertThatThrownBy(() -> DevelopmentBackendMain.publicKey(java.util.Base64.getEncoder().encodeToString(new byte[0])))
        .isInstanceOf(IllegalArgumentException.class);

    assertThatThrownBy(() -> DevelopmentBackendMain.publicKey(java.util.Base64.getEncoder().encodeToString(new byte[]{1, 2, 3})))
        .isInstanceOf(IllegalArgumentException.class);

    assertThatThrownBy(() -> DevelopmentBackendMain.publicKey("invalid@@@"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static JsonNode testEvent() {
    JsonNode vector = JsonSupport.read(ROOT.resolve("contracts/vectors/execution-permit-v1.json"));
    return JsonSupport.read(ROOT.resolve(vector.path("fixture").asText()));
  }
}
