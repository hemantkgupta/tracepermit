package io.tracepermit.agentcore.cli;

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

/** Generated in memory. No private key, token, or password is shipped in the archive. */
final class SyntheticIdentity {
  private static final KeyPair KEY = keys();
  static final String ISSUER = "https://identity.example.invalid";
  static final String AUDIENCE = "https://registration.example.invalid";

  static ObjectNode config() {
    var root = Protocol.JSON.createObjectNode().put("schemaVersion", "1.0").put("profile", Protocol.PROFILE)
        .put("replayCapacity", 128).put("replayDmlMillis", 2000);
    var p = root.putObject("provider").put("issuer", ISSUER).put("audience", AUDIENCE)
        .put("keyId", "synthetic-key").put("algorithm", "RS256").put("jwtType", "at+jwt")
        .put("publicKeySpki", Base64.getEncoder().encodeToString(KEY.getPublic().getEncoded()))
        .put("tenantClaim", "org_id").put("tenantValue", "synthetic-org").put("tenantId", "synthetic-tenant")
        .put("clientId", "synthetic-client").put("maximumTokenLifetimeSeconds", 300)
        .put("keyCacheSeconds", 60).put("refreshCooldownSeconds", 30)
        .put("jwksUri", ISSUER + "/jwks").put("metadataUri", AUDIENCE + "/metadata").put("origin", "CLI");
    p.putArray("supportedScopes").add("operations.register").add("operations.other");
    root.putObject("enrollment").put("verifiedSubjectId", ISSUER + "#workload:synthetic")
        .put("tenantId", "synthetic-tenant").put("workspaceId", "synthetic-workspace")
        .put("requesterId", "workload:synthetic-agent").put("clientId", "synthetic-client")
        .put("retentionPolicyId", "synthetic-retention").put("retentionSeconds", 86400);
    root.putObject("target").put("adapterId", "github-draft-pr-v1").put("environment", "test")
        .put("recoveryGeneration", 1).put("policyId", "synthetic-policy").put("policyVersion", 1)
        .put("policyContentHash", "sha256:" + "5".repeat(64)).put("installationId", 71)
        .put("repositoryId", 101).put("ownerId", 201).put("repository", "tracepermit/agentcore-pr-pilot")
        .put("head", "synthetic-change").put("base", "main").put("headSha", "a".repeat(40))
        .put("baseSha", "b".repeat(40)).putObject("artifactDigests").put("adapter_release", "sha256:" + "7".repeat(64));
    root.set("replay", database("tp_replay")); root.set("operations", database("tp_operations"));
    return root;
  }

  static ObjectNode database(String catalog) {
    return Protocol.JSON.createObjectNode().put("engine", "POSTGRESQL_16_4").put("host", "127.0.0.1")
        .put("port", 5432).put("catalog", catalog).put("user", "synthetic_user").put("password", UUID.randomUUID().toString());
  }

  static ObjectNode request() { return Protocol.JSON.createObjectNode().put("schemaVersion", "1.0")
      .put("clientRequestKey", "synthetic-key-1").put("title", "Synthetic installed registration"); }

  static String token() { return token(c -> { }); }
  static String token(Consumer<ObjectNode> change) {
    Instant now = Instant.now();
    var claims = Protocol.JSON.createObjectNode().put("iss", ISSUER).put("aud", AUDIENCE)
        .put("sub", "workload:synthetic").put("client_id", "synthetic-client").put("org_id", "synthetic-org")
        .put("scope", "operations.register").put("iat", now.minusSeconds(5).getEpochSecond())
        .put("exp", now.plusSeconds(270).getEpochSecond()).put("jti", UUID.randomUUID().toString());
    change.accept(claims);
    var header = Protocol.JSON.createObjectNode().put("alg", "RS256").put("typ", "at+jwt").put("kid", "synthetic-key");
    var encoder = Base64.getUrlEncoder().withoutPadding();
    String input = encoder.encodeToString(bytes(header)) + "." + encoder.encodeToString(bytes(claims));
    try {
      var signer = Signature.getInstance("SHA256withRSA"); signer.initSign(KEY.getPrivate());
      signer.update(input.getBytes(StandardCharsets.US_ASCII));
      return "Bearer " + input + "." + encoder.encodeToString(signer.sign());
    } catch (Exception failure) { throw new AssertionError("SYNTHETIC_SIGNING_FAILED"); }
  }

  static byte[] bytes(ObjectNode value) { return value.toString().getBytes(StandardCharsets.UTF_8); }
  static byte[] frames(byte[]... values) {
    try {
      var output = new ByteArrayOutputStream(); var stream = new DataOutputStream(output);
      for (byte[] value : values) { stream.writeInt(value.length); stream.write(value); }
      return output.toByteArray();
    } catch (Exception failure) { throw new AssertionError("SYNTHETIC_FRAMING_FAILED"); }
  }
  static byte[] input(ObjectNode config, String token, ObjectNode request) {
    return frames(bytes(config), token.getBytes(StandardCharsets.UTF_8), bytes(request));
  }
  private static KeyPair keys() {
    try { var generator = KeyPairGenerator.getInstance("RSA"); generator.initialize(2048); return generator.generateKeyPair(); }
    catch (Exception failure) { throw new AssertionError("SYNTHETIC_KEY_GENERATION_FAILED"); }
  }
}
