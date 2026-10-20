package io.tracepermit.executor.simulator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.tracepermit.contracts.JsonSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyPair;
import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VerifierOnlyExecutorConfigurationTest {
  @TempDir Path directory;

  @Test
  void loadsOnlyPublicVerificationTrustAndLoopbackPersistence() throws IOException {
    KeyPair keys = ExecutorTestFixtures.keyPair();
    Path config = validConfiguration(keys);

    VerifierOnlyExecutorConfiguration loaded = VerifierOnlyExecutorConfiguration.load(config);

    assertThat(loaded.issuer()).isEqualTo(ExecutorTestFixtures.ISSUER);
    assertThat(loaded.audience()).isEqualTo(ExecutorTestFixtures.AUDIENCE);
    assertThat(loaded.keyId()).isEqualTo(ExecutorTestFixtures.KEY_ID);
    assertThat(loaded.publicKey()).isEqualTo(keys.getPublic());
    assertThat(loaded.routes()).containsExactly(ExecutorTestFixtures.route());
    assertThat(loaded.keyFingerprint()).matches("sha256:[a-f0-9]{64}");
    assertThat(loaded.database().getServerNames()).containsExactly("127.0.0.1");
    assertThat(loaded.database().getSslMode()).isEqualTo("disable");
  }

  @Test
  void rejectsUnknownVersionsFieldsAndSelfSuppliedSigningMaterial() throws IOException {
    for (Consumer<ObjectNode> mutation : List.<Consumer<ObjectNode>>of(
        root -> root.put("schemaVersion", "2.0"),
        root -> root.put("verificationKey", "request-owned"),
        root -> ((ObjectNode) root.path("permit")).put("privateKeyFile", "private.pk8"),
        root -> ((ObjectNode) root.path("permit")).put("algorithm", "HS256"),
        root -> ((ObjectNode) root.path("postgres")).put("host", "db.example.com"),
        root -> ((ObjectNode) root.path("permit")).put("issuer", "http://issuer.invalid"),
        root -> ((ObjectNode) root.path("permit")).put("audience", "relative-audience"),
        root -> ((ObjectNode) root.path("adapter")).putArray("routes"),
        root -> ((ObjectNode) root.path("adapter").path("routes").get(0))
            .put("targetAccountId", "bad/account"))) {
      Path config = validConfiguration(ExecutorTestFixtures.keyPair());
      ObjectNode json = read(config);
      mutation.accept(json);
      writePrivate(config, JsonSupport.MAPPER.writeValueAsBytes(json));
      assertThatThrownBy(() -> VerifierOnlyExecutorConfiguration.load(config))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage("INVALID_EXECUTOR_CONFIGURATION");
    }
  }

  @Test
  void rejectsDuplicateRoutesKeysTrailingJsonAndMalformedFiles() throws IOException {
    Path config = validConfiguration(ExecutorTestFixtures.keyPair());
    ObjectNode duplicateRoute = read(config);
    JsonNode route = duplicateRoute.path("adapter").path("routes").get(0);
    ((com.fasterxml.jackson.databind.node.ArrayNode) duplicateRoute.path("adapter").path("routes"))
        .add(route.deepCopy());
    writePrivate(config, JsonSupport.MAPPER.writeValueAsBytes(duplicateRoute));
    assertInvalid(config);

    config = validConfiguration(ExecutorTestFixtures.keyPair());
    writePrivate(config, "{\"schemaVersion\":\"1.0\",\"schemaVersion\":\"1.0\"}"
        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
    assertInvalid(config);

    config = validConfiguration(ExecutorTestFixtures.keyPair());
    byte[] valid = Files.readAllBytes(config);
    writePrivate(config, (new String(valid, java.nio.charset.StandardCharsets.UTF_8) + " {}").getBytes(
        java.nio.charset.StandardCharsets.UTF_8));
    assertInvalid(config);

    config = validConfiguration(ExecutorTestFixtures.keyPair());
    writePrivate(directory.resolve("permit.spki"), new byte[] {1, 2, 3});
    assertInvalid(config);
  }

  @Test
  void rejectsUnsafeReferencesPermissionsSymlinksSecretsAndOversize() throws IOException {
    Path config = validConfiguration(ExecutorTestFixtures.keyPair());
    ObjectNode escaped = read(config);
    ((ObjectNode) escaped.path("postgres")).put("passwordFile", "../outside.secret");
    writePrivate(config, JsonSupport.MAPPER.writeValueAsBytes(escaped));
    assertInvalid(config);

    config = validConfiguration(ExecutorTestFixtures.keyPair());
    Files.setPosixFilePermissions(config, PosixFilePermissions.fromString("rw-r--r--"));
    assertInvalid(config);

    config = validConfiguration(ExecutorTestFixtures.keyPair());
    Path password = directory.resolve("database.secret");
    Files.setPosixFilePermissions(password, PosixFilePermissions.fromString("rw-r--r--"));
    assertInvalid(config);

    config = validConfiguration(ExecutorTestFixtures.keyPair());
    writePrivate(directory.resolve("database.secret"), "short".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    assertInvalid(config);

    config = validConfiguration(ExecutorTestFixtures.keyPair());
    Path realKey = directory.resolve("permit.spki");
    Path link = directory.resolve("linked.spki");
    Files.createSymbolicLink(link, realKey.getFileName());
    ObjectNode linked = read(config);
    ((ObjectNode) linked.path("permit")).put("publicKeyFile", "linked.spki");
    writePrivate(config, JsonSupport.MAPPER.writeValueAsBytes(linked));
    assertInvalid(config);

    config = directory.resolve("oversize.json");
    writePrivate(config, new byte[65_537]);
    assertInvalid(config);
  }

  @Test
  void rejectsInvalidRouteConstruction() {
    assertThatThrownBy(() -> new ExecutorRoute("", "aws", "prod", "simulated-123"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ExecutorRoute("tool", null, "prod", "simulated-123"))
        .isInstanceOf(NullPointerException.class);
  }

  private Path validConfiguration(KeyPair keys) throws IOException {
    writePrivate(directory.resolve("database.secret"),
        "runtime-generated-test-password\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    writePrivate(directory.resolve("permit.spki"), keys.getPublic().getEncoded());
    ObjectNode root = JsonSupport.MAPPER.createObjectNode();
    root.put("schemaVersion", "1.0");
    ObjectNode postgres = root.putObject("postgres");
    postgres.put("host", "127.0.0.1");
    postgres.put("port", 5432);
    postgres.put("database", "tracepermit");
    postgres.put("user", "tracepermit");
    postgres.put("passwordFile", "database.secret");
    ObjectNode permit = root.putObject("permit");
    permit.put("issuer", ExecutorTestFixtures.ISSUER);
    permit.put("audience", ExecutorTestFixtures.AUDIENCE);
    permit.put("keyId", ExecutorTestFixtures.KEY_ID);
    permit.put("publicKeyFile", "permit.spki");
    ObjectNode route = root.putObject("adapter").putArray("routes").addObject();
    route.put("toolName", "aws.ec2.update_security_group");
    route.put("targetSystem", "aws");
    route.put("targetEnvironment", "prod");
    route.put("targetAccountId", "simulated-123");
    Path config = directory.resolve("executor.json");
    writePrivate(config, JsonSupport.MAPPER.writeValueAsBytes(root));
    return config;
  }

  private static ObjectNode read(Path path) throws IOException {
    return (ObjectNode) JsonSupport.MAPPER.readTree(Files.readAllBytes(path));
  }

  private static void writePrivate(Path path, byte[] contents) throws IOException {
    Files.write(path, contents);
    Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"));
  }

  private static void assertInvalid(Path path) {
    assertThatThrownBy(() -> VerifierOnlyExecutorConfiguration.load(path))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("INVALID_EXECUTOR_CONFIGURATION");
  }
}
