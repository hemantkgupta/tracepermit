package io.tracepermit.agentcore.httpcli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import java.util.function.Consumer;

/** Runtime-generated synthetic identity and exact private framing. */
final class HttpCliFixtures {
  static final ObjectMapper JSON = new ObjectMapper();
  static final String ISSUER = "https://identity.example.invalid";
  static final String AUDIENCE = "https://registration.example.invalid";
  private static final KeyPair KEY = keys();

  private HttpCliFixtures() {}

  static ObjectNode config(LocalDatabases database) {
    return config(database.engine, database.port, database.user, database.password);
  }

  static ObjectNode config(String engine, int port, String user, String password) {
    var root =
        JSON.createObjectNode()
            .put("schemaVersion", "1.0")
            .put("profile", "tracepermit-local-synthetic-registration-v1")
            .put("replayCapacity", 128)
            .put("replayDmlMillis", 2_000);
    var provider =
        root.putObject("provider")
            .put("issuer", ISSUER)
            .put("audience", AUDIENCE)
            .put("keyId", "synthetic-key")
            .put("algorithm", "RS256")
            .put("jwtType", "at+jwt")
            .put("publicKeySpki", Base64.getEncoder().encodeToString(KEY.getPublic().getEncoded()))
            .put("tenantClaim", "org_id")
            .put("tenantValue", "synthetic-org")
            .put("tenantId", "synthetic-tenant")
            .put("clientId", "synthetic-client")
            .put("maximumTokenLifetimeSeconds", 300)
            .put("keyCacheSeconds", 60)
            .put("refreshCooldownSeconds", 30)
            .put("jwksUri", ISSUER + "/jwks")
            .put("metadataUri", AUDIENCE + "/metadata")
            .put("origin", "CLI");
    provider.putArray("supportedScopes").add("operations.register").add("operations.other");
    root.putObject("enrollment")
        .put("verifiedSubjectId", ISSUER + "#workload:synthetic")
        .put("tenantId", "synthetic-tenant")
        .put("workspaceId", "synthetic-workspace")
        .put("requesterId", "workload:synthetic-agent")
        .put("clientId", "synthetic-client")
        .put("retentionPolicyId", "synthetic-retention")
        .put("retentionSeconds", 86_400);
    root.putObject("target")
        .put("adapterId", "github-draft-pr-v1")
        .put("environment", "test")
        .put("recoveryGeneration", 1)
        .put("policyId", "synthetic-policy")
        .put("policyVersion", 1)
        .put("policyContentHash", "sha256:" + "5".repeat(64))
        .put("installationId", 71)
        .put("repositoryId", 101)
        .put("ownerId", 201)
        .put("repository", "tracepermit/agentcore-pr-pilot")
        .put("head", "synthetic-change")
        .put("base", "main")
        .put("headSha", "a".repeat(40))
        .put("baseSha", "b".repeat(40))
        .putObject("artifactDigests")
        .put("adapter_release", "sha256:" + "7".repeat(64));
    root.set("replay", database(engine, port, "tp_replay", user, password));
    root.set("operations", database(engine, port, "tp_operations", user, password));
    return root;
  }

  private static ObjectNode database(
      String engine, int port, String catalog, String user, String password) {
    return JSON.createObjectNode()
        .put("engine", engine)
        .put("host", "127.0.0.1")
        .put("port", port)
        .put("catalog", catalog)
        .put("user", user)
        .put("password", password);
  }

  static ObjectNode session(long millis) {
    return JSON.createObjectNode()
        .put("schemaVersion", "1.0")
        .put("profile", SessionProtocol.PROFILE)
        .put("requestedPort", 0)
        .put("sessionMillis", millis);
  }

  static ObjectNode request(String key) {
    return JSON.createObjectNode()
        .put("schemaVersion", "1.0")
        .put("clientRequestKey", key)
        .put("title", "Synthetic installed HTTP registration");
  }

  static String token() {
    return token(claims -> {});
  }

  static String token(Consumer<ObjectNode> change) {
    Instant now = Instant.now();
    var claims =
        JSON.createObjectNode()
            .put("iss", ISSUER)
            .put("aud", AUDIENCE)
            .put("sub", "workload:synthetic")
            .put("client_id", "synthetic-client")
            .put("org_id", "synthetic-org")
            .put("scope", "operations.register")
            .put("iat", now.minusSeconds(5).getEpochSecond())
            .put("exp", now.plusSeconds(270).getEpochSecond())
            .put("jti", UUID.randomUUID().toString());
    change.accept(claims);
    var header =
        JSON.createObjectNode()
            .put("alg", "RS256")
            .put("typ", "at+jwt")
            .put("kid", "synthetic-key");
    var encoder = Base64.getUrlEncoder().withoutPadding();
    String input =
        encoder.encodeToString(bytes(header)) + "." + encoder.encodeToString(bytes(claims));
    try {
      var signer = Signature.getInstance("SHA256withRSA");
      signer.initSign(KEY.getPrivate());
      signer.update(input.getBytes(StandardCharsets.US_ASCII));
      return "Bearer " + input + "." + encoder.encodeToString(signer.sign());
    } catch (Exception failure) {
      throw new AssertionError("SYNTHETIC_SIGNING_FAILED");
    }
  }

  static byte[] bytes(ObjectNode value) {
    return value.toString().getBytes(StandardCharsets.UTF_8);
  }

  static byte[] input(LocalDatabases database, long millis) {
    return frames(bytes(config(database)), bytes(session(millis)));
  }

  static byte[] frames(byte[]... values) {
    try {
      var output = new ByteArrayOutputStream();
      var stream = new DataOutputStream(output);
      for (byte[] value : values) {
        stream.writeInt(value.length);
        stream.write(value);
      }
      return output.toByteArray();
    } catch (Exception failure) {
      throw new AssertionError("SYNTHETIC_FRAMING_FAILED");
    }
  }

  private static KeyPair keys() {
    try {
      var generator = KeyPairGenerator.getInstance("RSA");
      generator.initialize(2048);
      return generator.generateKeyPair();
    } catch (Exception failure) {
      throw new AssertionError("SYNTHETIC_KEY_GENERATION_FAILED");
    }
  }
}
