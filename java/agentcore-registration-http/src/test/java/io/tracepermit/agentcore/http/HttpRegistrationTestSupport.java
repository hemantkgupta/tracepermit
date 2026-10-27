package io.tracepermit.agentcore.http;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.tracepermit.adapters.remote.agentcore.AgentCoreRegistrationIngress;
import io.tracepermit.adapters.remote.github.GitHubBusinessOperationBinding;
import io.tracepermit.adapters.remote.github.GitHubPilotTargetBinding;
import io.tracepermit.application.BusinessOperationRegistration;
import io.tracepermit.application.BusinessOperationRepository;
import io.tracepermit.application.BusinessOperationScope;
import io.tracepermit.application.BusinessOperationStorageException;
import io.tracepermit.contracts.BusinessOperationValidator;
import io.tracepermit.contracts.CanonicalJson;
import io.tracepermit.contracts.JsonSupport;
import io.tracepermit.identity.CachingOidcJwkResolver;
import io.tracepermit.identity.ConnectorOrigin;
import io.tracepermit.identity.ConnectorScope;
import io.tracepermit.identity.InMemoryWorkloadIdentityReplayStore;
import io.tracepermit.identity.OidcAccessTokenVerifier;
import io.tracepermit.identity.OidcProviderConfiguration;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Local keys/JWKS only. This never calls an issuer, provider, GitHub target or credential service. */
final class HttpRegistrationTestSupport implements AutoCloseable {
  static final String CANARY = "SYNTHETIC_SENSITIVE_HTTP_CANARY";
  static final String ISSUER = "https://identity.example.invalid";
  static final String AUDIENCE = "https://registration.example.invalid";
  static final Instant NOW = Instant.parse("2030-01-01T00:00:30Z");
  static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
  static final BusinessOperationScope SCOPE = new BusinessOperationScope("synthetic-tenant", "synthetic-workspace", "workload:synthetic-agent");
  private static final KeyPair KEY = keyPair();
  final AtomicInteger verifications = new AtomicInteger();
  final AtomicInteger jwksFetches = new AtomicInteger();
  final CachingOidcJwkResolver keys = new CachingOidcJwkResolver(uri -> {
    if (!uri.toString().equals(ISSUER + "/jwks")) throw new IllegalStateException(CANARY);
    jwksFetches.incrementAndGet();
    RSAPublicKey key = (RSAPublicKey) KEY.getPublic();
    ObjectNode jwk = JsonSupport.MAPPER.createObjectNode().put("kty", "RSA").put("kid", "synthetic-key")
        .put("alg", "RS256").put("use", "sig").put("n", unsigned(key.getModulus())).put("e", unsigned(key.getPublicExponent()));
    return JsonSupport.MAPPER.createObjectNode().set("keys", JsonSupport.MAPPER.createArrayNode().add(jwk)).toString();
  });

  AgentCoreRegistrationIngress ingress(BusinessOperationRepository repository) {
    var provider = new OidcProviderConfiguration(java.net.URI.create(ISSUER), AUDIENCE,
        java.net.URI.create(ISSUER + "/jwks"), java.net.URI.create(AUDIENCE + "/metadata"),
        List.of(java.net.URI.create(ISSUER)), "Synthetic HTTP registration",
        Map.of("synthetic-client", "Synthetic client", "other-client", "Other client"), "org_id",
        Map.of("synthetic-org", SCOPE.tenantId(), "other-org", "other-tenant"),
        Set.of(AgentCoreRegistrationIngress.REQUIRED_SCOPE, ConnectorScope.of("operations.other")),
        ConnectorOrigin.CLI, Duration.ofMinutes(5), Duration.ofMinutes(1), Duration.ofSeconds(30));
    var verifier = new OidcAccessTokenVerifier(issuer -> ISSUER.equals(issuer) ? Optional.of(provider) : Optional.empty(),
        (config, keyId, algorithm, now) -> {
          verifications.incrementAndGet(); return keys.resolve(config, keyId, algorithm, now);
        }, CLOCK);
    var target = new GitHubPilotTargetBinding(71, 101, 201, "tracepermit/agentcore-pr-pilot", "synthetic-change", "main", "a".repeat(40), "b".repeat(40));
    var profile = new GitHubBusinessOperationBinding.ExpectedProfile("github-draft-pr-v1", "test", 1,
        new GitHubBusinessOperationBinding.PolicyRelease("synthetic-policy", 1, "sha256:" + "5".repeat(64)),
        Map.of("adapter_release", "sha256:" + "7".repeat(64)), target);
    var enrollment = new AgentCoreRegistrationIngress.Enrollment(ISSUER, ISSUER + "#workload:synthetic", "synthetic-client",
        SCOPE, profile, "synthetic-retention", Duration.ofDays(30));
    return new AgentCoreRegistrationIngress(verifier, new InMemoryWorkloadIdentityReplayStore(), repository, CLOCK, enrollment);
  }

  static ObjectNode body() {
    return JsonSupport.MAPPER.createObjectNode().put("schemaVersion", "1.0").put("clientRequestKey", "synthetic-key-1")
        .put("title", "Synthetic HTTP registration");
  }
  static byte[] bytes(ObjectNode value) { return value.toString().getBytes(StandardCharsets.UTF_8); }
  static String token() { return token(claims -> {}); }
  static String token(Consumer<ObjectNode> mutation) {
    var header = JsonSupport.MAPPER.createObjectNode().put("alg", "RS256").put("typ", "at+jwt").put("kid", "synthetic-key");
    var claims = JsonSupport.MAPPER.createObjectNode().put("iss", ISSUER).put("aud", AUDIENCE).put("sub", "workload:synthetic")
        .put("client_id", "synthetic-client").put("org_id", "synthetic-org").put("scope", "operations.register")
        .put("iat", NOW.minusSeconds(30).getEpochSecond()).put("exp", NOW.plusSeconds(270).getEpochSecond()).put("jti", UUID.randomUUID().toString());
    mutation.accept(claims);
    var encoder = Base64.getUrlEncoder().withoutPadding();
    String input = encoder.encodeToString(header.toString().getBytes(StandardCharsets.UTF_8)) + "."
        + encoder.encodeToString(claims.toString().getBytes(StandardCharsets.UTF_8));
    try {
      var signer = Signature.getInstance("SHA256withRSA"); signer.initSign(KEY.getPrivate());
      signer.update(input.getBytes(StandardCharsets.US_ASCII));
      return "Bearer " + input + "." + encoder.encodeToString(signer.sign());
    } catch (java.security.GeneralSecurityException failure) { throw new AssertionError("SYNTHETIC_SIGNING_FAILED"); }
  }

  static Socket socket(int port) throws IOException {
    Socket socket = new Socket(InetAddress.getByAddress(new byte[] {127, 0, 0, 1}), port);
    socket.setSoTimeout(4_000);
    return socket;
  }
  static byte[] request(int port, String authorization, byte[] body) {
    return request(port, "POST", AgentCoreRegistrationHttpServer.ROUTE, List.of("Host: 127.0.0.1:" + port,
        "Authorization: " + authorization, "Content-Type: application/json", "Content-Length: " + body.length), body);
  }
  static byte[] request(int port, String method, String path, List<String> fields, byte[] body) {
    byte[] head = (method + " " + path + " HTTP/1.1\r\n" + String.join("\r\n", fields) + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII);
    byte[] result = Arrays.copyOf(head, head.length + body.length);
    System.arraycopy(body, 0, result, head.length, body.length); return result;
  }
  static Reply send(int port, String authorization, ObjectNode body) throws IOException {
    return send(port, request(port, authorization, bytes(body)));
  }
  static Reply send(int port, byte[] wire) throws IOException {
    try (Socket socket = socket(port)) {
      socket.getOutputStream().write(wire); socket.getOutputStream().flush();
      return read(socket);
    } catch (java.net.SocketException closed) {
      // Pre-parse capacity refusal can reset a connection; a timeout is NOT treated as refusal.
      return new Reply(0, "", "");
    }
  }
  static Reply read(Socket socket) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    byte[] buffer = new byte[512];
    int count;
    while ((count = socket.getInputStream().read(buffer)) >= 0) {
      if (out.size() + count > 8_192) throw new AssertionError("HTTP_RESPONSE_TEST_BOUND_EXCEEDED");
      out.write(buffer, 0, count);
      String current = out.toString(StandardCharsets.UTF_8);
      int end = current.indexOf("\r\n\r\n");
      if (end >= 0) {
        var length = java.util.regex.Pattern.compile("Content-Length: ([0-9]+)\r\n").matcher(current);
        if (length.find() && out.size() >= end + 4 + Integer.parseInt(length.group(1))) break;
      }
    }
    String raw = out.toString(StandardCharsets.UTF_8);
    if (raw.isEmpty()) return new Reply(0, "", "");
    int split = raw.indexOf("\r\n\r\n");
    assertThat(split).isGreaterThan(0);
    int status = Integer.parseInt(raw.substring(9, 12));
    return new Reply(status, raw.substring(split + 4), raw.substring(0, split));
  }
  record Reply(int status, String body, String headers) {
    @Override public String toString() { return "SyntheticHttpReply[status=" + status + "]"; }
  }
  static void await(BooleanSupplier condition) throws InterruptedException {
    long deadline = System.nanoTime() + Duration.ofSeconds(4).toNanos();
    while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(5);
    assertThat(condition.getAsBoolean()).isTrue();
  }
  static class MemoryRepository implements BusinessOperationRepository {
    final AtomicInteger calls = new AtomicInteger();
    final ConcurrentHashMap<String, JsonNode> originals = new ConcurrentHashMap<>();
    @Override public BusinessOperationRegistration createOrGet(BusinessOperationScope scope, JsonNode operation) {
      calls.incrementAndGet();
      JsonNode validated = BusinessOperationValidator.validate(operation);
      JsonNode prior = originals.putIfAbsent(operation.path("clientRequestKeyHash").textValue(), validated);
      if (prior == null) return new BusinessOperationRegistration(true, validated);
      for (String field : List.of("adapterId", "semanticPayloadDigest", "resourceBindingHash", "recoveryGeneration")) {
        if (!prior.path(field).equals(validated.path(field))) throw new BusinessOperationStorageException(BusinessOperationStorageException.Code.OPERATION_REQUEST_KEY_CONFLICT);
      }
      return new BusinessOperationRegistration(false, prior);
    }
    @Override public Optional<JsonNode> find(BusinessOperationScope scope, String id) { throw new AssertionError("UNEXPECTED_READ_PORT"); }
  }
  private static KeyPair keyPair() {
    try { var generator = KeyPairGenerator.getInstance("RSA"); generator.initialize(2048); return generator.generateKeyPair(); }
    catch (java.security.GeneralSecurityException failure) { throw new ExceptionInInitializerError("SYNTHETIC_KEY_FAILED"); }
  }
  private static String unsigned(BigInteger value) {
    byte[] bytes = value.toByteArray();
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes[0] == 0 ? Arrays.copyOfRange(bytes, 1, bytes.length) : bytes);
  }
  @Override public void close() { keys.close(); }
}
