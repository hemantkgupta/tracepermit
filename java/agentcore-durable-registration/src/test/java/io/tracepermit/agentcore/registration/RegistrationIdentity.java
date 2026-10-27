package io.tracepermit.agentcore.registration;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.tracepermit.adapters.remote.agentcore.AgentCoreRegistrationIngress;
import io.tracepermit.adapters.remote.github.GitHubBusinessOperationBinding;
import io.tracepermit.adapters.remote.github.GitHubPilotTargetBinding;
import io.tracepermit.application.BusinessOperationScope;
import io.tracepermit.contracts.JsonSupport;
import io.tracepermit.identity.CachingOidcJwkResolver;
import io.tracepermit.identity.ConnectorOrigin;
import io.tracepermit.identity.ConnectorScope;
import io.tracepermit.identity.OidcAccessTokenVerifier;
import io.tracepermit.identity.OidcProviderConfiguration;
import java.math.BigInteger;
import java.net.URI;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/** Local signing material only: not AWS/Okta keys or interoperability evidence. */
final class RegistrationIdentity {
  static final Instant NOW = Instant.parse("2030-01-01T00:00:30Z");
  static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
  static final String ISSUER = "https://identity.example.invalid";
  static final String AUDIENCE = "https://registration.example.invalid";
  static final BusinessOperationScope SCOPE =
      new BusinessOperationScope("synthetic-tenant", "synthetic-workspace", "workload:synthetic-agent");
  private static final KeyPair KEY = keyPair();

  private RegistrationIdentity() {}

  static byte[] publicKey() { return KEY.getPublic().getEncoded(); }

  static OidcAccessTokenVerifier verifier() { return verifier((RSAPublicKey) KEY.getPublic()); }

  static OidcAccessTokenVerifier verifier(RSAPublicKey key) {
    return new OidcAccessTokenVerifier(
        issuer -> ISSUER.equals(issuer) ? Optional.of(provider()) : Optional.empty(),
        (config, keyId, algorithm, now) -> {
          if (!"synthetic-key".equals(keyId) || !"RS256".equals(algorithm)) {
            throw new IllegalStateException("SYNTHETIC_UNTRUSTED_KEY");
          }
          return key;
        }, CLOCK);
  }

  private static OidcProviderConfiguration provider() {
    return new OidcProviderConfiguration(URI.create(ISSUER), AUDIENCE,
        URI.create(ISSUER + "/jwks"), URI.create(AUDIENCE + "/metadata"), List.of(URI.create(ISSUER)),
        "Synthetic JDBC registration", Map.of("synthetic-client", "Synthetic client", "other-client", "Other client"),
        "org_id", Map.of("synthetic-org", SCOPE.tenantId(), "other-org", "other-tenant"),
        Set.of(AgentCoreRegistrationIngress.REQUIRED_SCOPE, ConnectorScope.of("operations.other")),
        ConnectorOrigin.CLI, Duration.ofMinutes(5), Duration.ofMinutes(1), Duration.ofSeconds(30));
  }

  static JwksFixture jwksFixture() { return new JwksFixture(); }

  /** Actual cached JWKS parsing over a local synthetic document; no provider network is contacted. */
  static final class JwksFixture implements AutoCloseable {
    final AtomicInteger fetches = new AtomicInteger();
    private final CachingOidcJwkResolver keys = new CachingOidcJwkResolver(uri -> {
      if (!URI.create(ISSUER + "/jwks").equals(uri)) {
        throw new IllegalStateException("SYNTHETIC_UNTRUSTED_JWKS_URI");
      }
      fetches.incrementAndGet();
      RSAPublicKey key = (RSAPublicKey) KEY.getPublic();
      ObjectNode jwk = JsonSupport.MAPPER.createObjectNode()
          .put("kty", "RSA").put("kid", "synthetic-key").put("alg", "RS256").put("use", "sig")
          .put("n", unsigned(key.getModulus())).put("e", unsigned(key.getPublicExponent()));
      return JsonSupport.MAPPER.createObjectNode()
          .set("keys", JsonSupport.MAPPER.createArrayNode().add(jwk)).toString();
    });

    OidcAccessTokenVerifier verifier() {
      return new OidcAccessTokenVerifier(
          issuer -> ISSUER.equals(issuer) ? Optional.of(provider()) : Optional.empty(),
          (configuration, keyId, algorithm, now) -> keys.resolve(configuration, keyId, algorithm, now),
          CLOCK);
    }

    @Override public void close() { keys.close(); }
  }

  static AgentCoreRegistrationIngress.Enrollment enrollment() {
    var target = new GitHubPilotTargetBinding(71, 101, 201, "tracepermit/agentcore-pr-pilot",
        "synthetic-change", "main", "a".repeat(40), "b".repeat(40));
    var profile = new GitHubBusinessOperationBinding.ExpectedProfile("github-draft-pr-v1", "test", 1,
        new GitHubBusinessOperationBinding.PolicyRelease("synthetic-policy", 1, "sha256:" + "5".repeat(64)),
        Map.of("adapter_release", "sha256:" + "7".repeat(64)), target);
    return enrollment(SCOPE, profile);
  }

  static AgentCoreRegistrationIngress.Enrollment enrollment(BusinessOperationScope scope,
      GitHubBusinessOperationBinding.ExpectedProfile profile) {
    return new AgentCoreRegistrationIngress.Enrollment(ISSUER, ISSUER + "#workload:synthetic",
        "synthetic-client", scope, profile, "synthetic-retention", Duration.ofDays(30));
  }

  static ObjectNode request() {
    return JsonSupport.MAPPER.createObjectNode().put("schemaVersion", "1.0")
        .put("clientRequestKey", "synthetic-key-1").put("title", "Synthetic JDBC change");
  }

  static byte[] bytes(ObjectNode request) {
    return request.toString().getBytes(StandardCharsets.UTF_8);
  }

  static String token() {
    return token(claims -> {});
  }

  static String token(Consumer<ObjectNode> mutation) {
    ObjectNode header = JsonSupport.MAPPER.createObjectNode()
        .put("alg", "RS256").put("typ", "at+jwt").put("kid", "synthetic-key");
    ObjectNode claims = JsonSupport.MAPPER.createObjectNode().put("iss", ISSUER).put("aud", AUDIENCE)
        .put("sub", "workload:synthetic").put("client_id", "synthetic-client").put("org_id", "synthetic-org")
        .put("scope", "operations.register").put("iat", NOW.minusSeconds(30).getEpochSecond())
        .put("exp", NOW.plusSeconds(270).getEpochSecond()).put("jti", UUID.randomUUID().toString());
    mutation.accept(claims);
    var encoder = Base64.getUrlEncoder().withoutPadding();
    String input = encoder.encodeToString(header.toString().getBytes(StandardCharsets.UTF_8)) + "."
        + encoder.encodeToString(claims.toString().getBytes(StandardCharsets.UTF_8));
    try {
      Signature signer = Signature.getInstance("SHA256withRSA");
      signer.initSign(KEY.getPrivate());
      signer.update(input.getBytes(StandardCharsets.US_ASCII));
      return "Bearer " + input + "." + encoder.encodeToString(signer.sign());
    } catch (java.security.GeneralSecurityException failure) {
      throw new AssertionError("SYNTHETIC_SIGNING_FAILED");
    }
  }

  private static KeyPair keyPair() {
    try {
      var generator = KeyPairGenerator.getInstance("RSA");
      generator.initialize(2048);
      return generator.generateKeyPair();
    } catch (java.security.GeneralSecurityException failure) {
      throw new ExceptionInInitializerError("SYNTHETIC_KEY_GENERATION_FAILED");
    }
  }

  private static String unsigned(BigInteger value) {
    byte[] bytes = value.toByteArray();
    return Base64.getUrlEncoder().withoutPadding()
        .encodeToString(bytes[0] == 0 ? Arrays.copyOfRange(bytes, 1, bytes.length) : bytes);
  }
}
