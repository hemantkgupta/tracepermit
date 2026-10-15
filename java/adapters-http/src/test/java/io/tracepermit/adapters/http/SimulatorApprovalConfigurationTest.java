package io.tracepermit.adapters.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyPairGenerator;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

final class SimulatorApprovalConfigurationTest {
  @TempDir Path directory;
  private SimulatorApprovalFixture fixture;

  @BeforeEach void setup() throws Exception { fixture = new SimulatorApprovalFixture(directory); }

  @Test void optionalBrowserConfigurationIsExplicitPrivateAndValidatedOffline() throws Exception {
    Files.createDirectories(directory.resolve("console"));
    Files.writeString(directory.resolve("console/index.html"), "<html></html>");
    fixture.write("client.secret", "synthetic-browser-client-secret\n".getBytes(StandardCharsets.UTF_8));
    var web = fixture.json.putObject("browser").put("authorizationUri", "https://idp.example.invalid/authorize")
        .put("tokenUri", "https://idp.example.invalid/token").put("clientSecretFile", "client.secret")
        .put("consoleDirectory", "console");
    var config = fixture.load();
    assertThat(config.browser().consoleDirectory).isEqualTo(directory.resolve("console").toRealPath());
    assertThat(config.browser().toString()).doesNotContain("synthetic", "secret");
    web.put("tokenUri", "http://127.0.0.1/token"); fixture.save(); rejected();
    web.put("tokenUri", "https://idp.example.invalid/token").put("consoleDirectory", "missing"); fixture.save(); rejected();
    web.put("consoleDirectory", "console").put("unknown", true); fixture.save(); rejected();
    web.remove("unknown"); fixture.write("client.secret", "short".getBytes(StandardCharsets.UTF_8)); fixture.save(); rejected();
  }

  @Test void explicitConfigurationLoadsWithoutDatabaseOrNetworkAndPreservesKeys() throws Exception {
    var first = fixture.load();
    var second = fixture.load();
    assertThat(first.port()).isEqualTo(18085);
    assertThat(first.provider().tenantId("alpha")).isEqualTo("simulated-alpha");
    assertThat(first.keyId()).isEqualTo("local-simulator-1");
    assertThat(first.permitKey().getPrivate().getEncoded()).isEqualTo(second.permitKey().getPrivate().getEncoded());
    assertThat(first.permitKey().getPublic().getEncoded()).isEqualTo(fixture.key.getPublic().getEncoded());
    assertThat(first.toString()).doesNotContain("alice", "synthetic", "approval-client");
    fixture.json.withObject("/permit").put("publicKeyFile", directory.resolve("permit.spki").toString());
    fixture.write("database.secret", "synthetic-local-test-password".getBytes(StandardCharsets.UTF_8));
    assertThat(fixture.load()).isNotNull();
  }

  static Stream<Consumer<ObjectNode>> invalidConfigurations() {
    return Stream.of(
        j -> j.put("unknown", "secret-must-not-leak"),
        j -> j.remove("oidc"), j -> j.put("schemaVersion", "2.0"),
        j -> j.put("port", 0), j -> j.put("port", 65536), j -> j.put("port", 1.5), j -> j.put("port", "8085"),
        j -> j.withObject("/postgres").put("host", "db.internal"),
        j -> j.withObject("/postgres").put("password", "secret-must-not-leak"),
        j -> j.withObject("/postgres").put("database", "db?options=unsafe"),
        j -> j.withObject("/oidc").put("issuer", "http://idp.example.invalid"),
        j -> j.withObject("/oidc").put("jwksUri", "https://user:secret@idp.example.invalid/jwks"),
        j -> j.withObject("/oidc").put("jwksUri", "https://127.0.0.1/jwks"),
        j -> j.withObject("/oidc").put("jwksUri", "https://idp.example.invalid"),
        j -> j.withObject("/oidc").put("clientId", "bad client"),
        j -> j.withObject("/oidc").put("tenantValue", "bad|tenant"),
        j -> j.putArray("memberships"),
        j -> j.set("memberships", j.path("memberships").get(0)),
        j -> ((ObjectNode) j.withArray("memberships").get(0)).put("human", "true"),
        j -> ((ObjectNode) j.withArray("memberships").get(1)).put("human", false),
        j -> ((ObjectNode) j.withArray("memberships").get(1)).put("subject", "alice"),
        j -> ((ObjectNode) j.withArray("memberships").get(1)).put("subject", "bad#subject"),
        j -> ((ObjectNode) j.withArray("memberships").get(0)).putArray("roles").add("ADMIN"),
        j -> ((ObjectNode) j.withArray("memberships").get(0)).putArray("roles").add("REQUESTER").add("REQUESTER"),
        j -> ((ObjectNode) j.withArray("memberships").get(0)).putArray("roles").add(1),
        j -> ((ObjectNode) j.withArray("memberships").get(1)).putArray("roles").add("VIEWER"),
        j -> ((ObjectNode) j.withArray("memberships").get(0)).putArray("roles").add("VIEWER"),
        j -> j.withArray("memberships").add(j.path("memberships").get(0).deepCopy()),
        j -> { for (int n = 0; n < 1000; n++) j.withArray("memberships").add(j.path("memberships").get(0).deepCopy()); },
        j -> j.withObject("/permit").put("keyId", "bad\nidentifier"),
        j -> j.withObject("/permit").put("keyId", "x".repeat(129)),
        j -> j.withObject("/permit").put("keyId", " "),
        j -> j.withObject("/permit").put("privateKeyFile", "missing.pk8"));
  }

  @ParameterizedTest @MethodSource("invalidConfigurations")
  void unknownMalformedAndUnusableTrustConfigurationFailsClosed(Consumer<ObjectNode> mutate) throws Exception {
    mutate.accept(fixture.json);
    fixture.save();
    rejected();
  }

  @ParameterizedTest @ValueSource(strings = {"null", "[]", "", "{} {}", "{\"schemaVersion\":\"1.0\",\"schemaVersion\":\"1.0\"}"})
  void strictJsonRejectsEmptyScalarDuplicateAndTrailingInput(String value) throws Exception {
    fixture.write("operator.json", value.getBytes(StandardCharsets.UTF_8));
    rejected();
  }

  @Test void fileBoundsEncodingPermissionsAndSymlinksAreRejected() throws Exception {
    fixture.write("operator.json", new byte[] {(byte) 0xc3, (byte) 0x28}); rejected();
    fixture.write("operator.json", new byte[65_537]); rejected();
    fixture.save();
    Files.setPosixFilePermissions(fixture.config, PosixFilePermissions.fromString("rw-r--r--")); rejected();
    Files.setPosixFilePermissions(fixture.config, PosixFilePermissions.fromString("rw-------"));
    Files.setPosixFilePermissions(directory.resolve("permit.pk8"), PosixFilePermissions.fromString("rw-rw----")); rejected();
    Files.setPosixFilePermissions(directory.resolve("permit.pk8"), PosixFilePermissions.fromString("rw-------"));
    Path link = directory.resolve("key-link");
    Files.createSymbolicLink(link, directory.resolve("permit.pk8"));
    fixture.json.withObject("/permit").put("privateKeyFile", "key-link"); fixture.save(); rejected();
    fixture.json.withObject("/permit").put("privateKeyFile", "."); fixture.save(); rejected();
    Files.createSymbolicLink(directory.resolve("config-link"), fixture.config);
    assertThatThrownBy(() -> SimulatorApprovalConfiguration.load(directory.resolve("config-link")))
        .hasMessage("INVALID_APPROVAL_CONFIGURATION");
  }

  @Test void emptyOversizedWeakOrNonUtf8SecretAndWrongKeysAreRejected() throws Exception {
    for (byte[] secret : new byte[][] {new byte[0], new byte[1025], "short".getBytes(StandardCharsets.UTF_8),
        "                ".getBytes(StandardCharsets.UTF_8), "long-but\ninvalid-secret".getBytes(StandardCharsets.UTF_8),
        new byte[] {(byte) 0xff}}) {
      fixture.write("database.secret", secret); rejected();
    }
    fixture.write("database.secret", "synthetic-local-test-password".getBytes(StandardCharsets.UTF_8));
    fixture.write("permit.spki", KeyPairGenerator.getInstance("Ed25519").generateKeyPair().getPublic().getEncoded()); rejected();
    fixture.write("permit.spki", fixture.key.getPublic().getEncoded());
    fixture.write("permit.pk8", KeyPairGenerator.getInstance("RSA").generateKeyPair().getPrivate().getEncoded()); rejected();
  }

  @Test void checkOnlyNeverStartsDependenciesAndRunErrorsAreSecretSafe() throws Exception {
    var output = new ByteArrayOutputStream();
    var errors = new ByteArrayOutputStream();
    var starts = new AtomicInteger();
    var environment = Map.of("TRACEPERMIT_APPROVAL_CONFIG", fixture.config.toString());
    try (var out = new PrintStream(output); var err = new PrintStream(errors)) {
      assertThat(SimulatorApprovalMain.execute(new String[] {"--check"}, environment, out, err,
          config -> starts.incrementAndGet())).isZero();
      assertThat(starts).hasValue(0);
      assertThat(output.toString(StandardCharsets.UTF_8)).contains("offline only");
      assertThat(SimulatorApprovalMain.execute(new String[] {"--run"}, environment, out, err,
          config -> starts.incrementAndGet())).isZero();
      assertThat(starts).hasValue(1);
      assertThat(SimulatorApprovalMain.execute(new String[] {"--run"}, environment, out, err,
          config -> { throw new Exception("secret-must-not-leak"); })).isEqualTo(2);
      for (String[] args : new String[][] {new String[0], {"--unknown"}, {"--run", "extra"}}) {
        assertThat(SimulatorApprovalMain.execute(args, environment, out, err, config -> starts.incrementAndGet())).isEqualTo(2);
      }
      for (Map<String, String> env : java.util.List.of(Map.<String, String>of(),
          Map.of("TRACEPERMIT_APPROVAL_CONFIG", " "), Map.of("TRACEPERMIT_APPROVAL_CONFIG", "\u0000"))) {
        assertThat(SimulatorApprovalMain.execute(new String[] {"--run"}, env, out, err,
            config -> starts.incrementAndGet())).isEqualTo(2);
      }
      fixture.json.put("schemaVersion", "bad"); fixture.save();
      assertThat(SimulatorApprovalMain.execute(new String[] {"--run"}, environment, out, err,
          config -> starts.incrementAndGet())).isEqualTo(2);
    }
    assertThat(starts).hasValue(1);
    assertThat(errors.toString(StandardCharsets.UTF_8)).contains("APPROVAL_STARTUP_FAILED")
        .doesNotContain("secret-must-not-leak", directory.toString(), "Exception", "alice");
  }

  @Test void fixedVersionedPolicyRequiresReviewOnlyForTheExposedApiAndCliMutation() {
    var policy = SimulatorApprovalMain.policy();
    var root = Path.of(System.getProperty("tracepermit.repositoryRoot"));
    var action = (ObjectNode) io.tracepermit.contracts.JsonSupport.read(root.resolve("fixtures/api-service-aws-modify.json"));
    var evaluator = new io.tracepermit.policy.PolicyEvaluator();
    assertThat(evaluator.evaluate(action, policy).decision().outcome()).isEqualTo("require_approval");
    action.withObject("/action").put("toolName", "aws.ec2.delete_security_group");
    assertThat(evaluator.evaluate(action, policy).decision().outcome()).isEqualTo("block");
    action.withObject("/action").put("toolName", "aws.ec2.update_security_group");
    action.withObject("/trigger").put("origin", "cli");
    assertThat(evaluator.evaluate(action, policy).decision().outcome()).isEqualTo("require_approval");
    action.withObject("/trigger").put("origin", "chatgpt-web");
    assertThat(evaluator.evaluate(action, policy).decision().outcome()).isEqualTo("block");
    assertThat(policy.path("version").asInt()).isEqualTo(2);
    assertThat(policy.path("description").asText()).isNotBlank();
    assertThat(policy.path("rules").get(0).path("description").asText()).isNotBlank();
  }

  private void rejected() {
    assertThatThrownBy(() -> SimulatorApprovalConfiguration.load(fixture.config))
        .isExactlyInstanceOf(IllegalArgumentException.class).hasMessage("INVALID_APPROVAL_CONFIGURATION")
        .hasNoCause();
  }
}
