package io.tracepermit.executor.simulator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.tracepermit.contracts.JsonSupport;
import io.tracepermit.security.ExecutionPermitAuthority;
import io.tracepermit.security.InMemoryPermitConsumptionStore;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;

final class ExecutorTestFixtures {
  static final Instant NOW = Instant.parse("2026-10-20T12:00:00Z");
  static final String ISSUER = "https://permits.tracepermit.invalid";
  static final String AUDIENCE = "tracepermit://simulator-executor";
  static final String KEY_ID = "permit-key-2026-10";

  private ExecutorTestFixtures() {}

  static KeyPair keyPair() {
    try {
      return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    } catch (java.security.GeneralSecurityException failure) {
      throw new IllegalStateException(failure);
    }
  }

  static JsonNode action() {
    ObjectNode root = JsonSupport.MAPPER.createObjectNode();
    root.put("schemaVersion", "1.0");
    root.put("eventId", "10000000-0000-4000-8000-000000000001");
    root.put("observedAt", NOW.toString());
    root.put("traceId", "0123456789abcdef0123456789abcdef");

    ObjectNode identity = root.putObject("identity");
    ObjectNode subject = identity.putObject("subject");
    subject.put("id", "user:alice");
    subject.put("organizationId", "tenant-acme");
    subject.put("authenticationMethod", "oauth");
    subject.put("assurance", "authenticated");
    subject.put("type", "human");
    ObjectNode evidence = identity.putArray("evidence").addObject();
    evidence.put("evidenceId", "evidence-1");
    evidence.put("subjectId", "user:alice");
    evidence.put("kind", "oauth_token");
    evidence.put("assurance", "authenticated");
    evidence.put("issuer", "https://identity.tracepermit.invalid");
    evidence.put("verifiedAt", NOW.toString());
    evidence.put("credentialFingerprint", "sha256:" + "a".repeat(64));

    ObjectNode trigger = root.putObject("trigger");
    trigger.put("kind", "human");
    trigger.put("origin", "cli");
    trigger.put("requestId", "request-1");

    ObjectNode action = root.putObject("action");
    action.put("protocol", "mcp");
    action.put("toolName", "aws.ec2.update_security_group");
    ObjectNode arguments = action.putObject("arguments");
    arguments.put("groupId", "sg-demo");
    arguments.put("port", 443);
    arguments.put("cidr", "192.0.2.0/24");
    ObjectNode target = action.putObject("target");
    target.put("system", "aws");
    target.put("environment", "prod");
    target.put("accountId", "simulated-123");
    target.put("resourceType", "security-group");

    ObjectNode lineage = root.putObject("lineage");
    lineage.put("rootPrincipalId", "user:alice");
    ObjectNode hop = lineage.putArray("hops").addObject();
    hop.put("hopId", "hop-1");
    hop.put("kind", "principal");
    hop.put("subjectId", "user:alice");
    hop.put("assurance", "authenticated");
    hop.put("source", "oauth");
    hop.put("observedAt", NOW.toString());
    lineage.putArray("delegations");
    root.putObject("clientClaims");
    return root;
  }

  static JsonNode evaluation(JsonNode action) {
    ObjectNode result = JsonSupport.MAPPER.createObjectNode();
    result.put("eventId", action.path("eventId").asText());
    result.put("evaluationId", "evaluation-1");
    ObjectNode decision = result.putObject("decision");
    decision.put("outcome", "allow");
    decision.put("policyId", "policy-simulator");
    decision.put("policyVersion", 7);
    return result;
  }

  static ExecutionPermitAuthority signer(KeyPair keys, Clock clock, AtomicInteger ids) {
    return new ExecutionPermitAuthority(
        ISSUER,
        KEY_ID,
        keys,
        60,
        clock,
        () -> "permit-" + ids.incrementAndGet(),
        new InMemoryPermitConsumptionStore());
  }

  static ExecutionPermitAuthority verifier(
      KeyPair keys, Clock clock, InMemoryPermitConsumptionStore store) {
    return new ExecutionPermitAuthority(ISSUER, KEY_ID, keys.getPublic(), 60, clock, store);
  }

  static Clock clock(Instant instant) {
    return Clock.fixed(instant, ZoneOffset.UTC);
  }

  static ExecutorRoute route() {
    return new ExecutorRoute(
        "aws.ec2.update_security_group", "aws", "prod", "simulated-123");
  }

  static Path configuration(
      Path directory, KeyPair keys, int databasePort, String databaseName, String user, String password) {
    try {
      writePrivate(directory.resolve("database.secret"),
          (password + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
      writePrivate(directory.resolve("permit.spki"), keys.getPublic().getEncoded());
      ObjectNode root = JsonSupport.MAPPER.createObjectNode();
      root.put("schemaVersion", "1.0");
      ObjectNode postgres = root.putObject("postgres");
      postgres.put("host", "127.0.0.1");
      postgres.put("port", databasePort);
      postgres.put("database", databaseName);
      postgres.put("user", user);
      postgres.put("passwordFile", "database.secret");
      ObjectNode permit = root.putObject("permit");
      permit.put("issuer", ISSUER);
      permit.put("audience", AUDIENCE);
      permit.put("keyId", KEY_ID);
      permit.put("publicKeyFile", "permit.spki");
      ObjectNode route = root.putObject("adapter").putArray("routes").addObject();
      route.put("toolName", "aws.ec2.update_security_group");
      route.put("targetSystem", "aws");
      route.put("targetEnvironment", "prod");
      route.put("targetAccountId", "simulated-123");
      Path config = directory.resolve("executor.json");
      writePrivate(config, JsonSupport.MAPPER.writeValueAsBytes(root));
      return config;
    } catch (java.io.IOException failure) {
      throw new IllegalStateException(failure);
    }
  }

  private static void writePrivate(Path path, byte[] contents) throws java.io.IOException {
    Files.write(path, contents);
    Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"));
  }
}
