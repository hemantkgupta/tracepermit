package io.tracepermit.agentcore.lifecyclecli;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.tracepermit.contracts.CanonicalJson;
import io.tracepermit.contracts.JsonSupport;
import io.tracepermit.identity.ConnectorOrigin;
import io.tracepermit.identity.ConnectorScope;
import io.tracepermit.identity.OidcAccessTokenVerifier;
import io.tracepermit.identity.OidcProviderConfiguration;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Runtime-generated real RSA signed tokens for the installed lifecycle boundary. */
final class LifecycleTestIdentity {
  static final String TENANT = "synthetic-tenant";
  static final String WORKSPACE = "synthetic-workspace";
  static final String REQUESTER_ISSUER = "https://requester.identity.invalid";
  static final String REQUESTER_AUDIENCE = "https://lifecycle.requester.invalid";
  static final String REQUESTER_CLIENT = "synthetic-requester-client";
  static final String REQUESTER_SUBJECT = "workload:synthetic-lifecycle";
  static final String REVIEWER_ISSUER = "https://reviewer.identity.invalid";
  static final String REVIEWER_AUDIENCE = "https://lifecycle.reviewer.invalid";
  static final String REVIEWER_CLIENT = "synthetic-reviewer-client";
  static final String REVIEWER_SUBJECT = "human:synthetic-reviewer";
  static final String REGISTRATION_ISSUER = "https://registration.identity.invalid";
  static final String REGISTRATION_AUDIENCE = "https://registration.ingress.invalid";
  static final String REGISTRATION_CLIENT = "synthetic-registration-client";
  static final String REGISTRATION_SUBJECT = "workload:synthetic-registration";
  static final String TENANT_VALUE = "synthetic-organization";
  static final String KEY_ID = "synthetic-rsa-key";

  private final KeyPair requester;
  private final KeyPair reviewer;
  private final KeyPair registration;

  LifecycleTestIdentity() {
    try {
      KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
      generator.initialize(2048);
      requester = generator.generateKeyPair();
      reviewer = generator.generateKeyPair();
      registration = generator.generateKeyPair();
    } catch (Exception failure) {
      throw new AssertionError("SYNTHETIC_IDENTITY_SETUP_FAILED", failure);
    }
  }

  String requesterToken() {
    return token(
        requester,
        REQUESTER_ISSUER,
        REQUESTER_AUDIENCE,
        REQUESTER_SUBJECT,
        REQUESTER_CLIENT,
        "operations.evaluate operations.execute");
  }

  String reviewerToken() {
    return token(
        reviewer,
        REVIEWER_ISSUER,
        REVIEWER_AUDIENCE,
        REVIEWER_SUBJECT,
        REVIEWER_CLIENT,
        "operations.decide");
  }

  String registrationToken() {
    return "Bearer "
        + token(
            registration,
            REGISTRATION_ISSUER,
            REGISTRATION_AUDIENCE,
            REGISTRATION_SUBJECT,
            REGISTRATION_CLIENT,
            "operations.register");
  }

  OidcAccessTokenVerifier registrationVerifier() {
    OidcProviderConfiguration provider =
        provider(
            REGISTRATION_ISSUER,
            REGISTRATION_AUDIENCE,
            REGISTRATION_CLIENT,
            Set.of("operations.register"));
    return new OidcAccessTokenVerifier(
        issuer -> REGISTRATION_ISSUER.equals(issuer) ? Optional.of(provider) : Optional.empty(),
        (candidate, keyId, algorithm, observed) -> {
          if (candidate != provider || !KEY_ID.equals(keyId) || !"RS256".equals(algorithm)) {
            throw new IllegalStateException("SYNTHETIC_KEY_REJECTED");
          }
          return (RSAPublicKey) registration.getPublic();
        },
        Clock.systemUTC());
  }

  ObjectNode requesterProvider() {
    return providerJson(
        requester,
        REQUESTER_ISSUER,
        REQUESTER_AUDIENCE,
        REQUESTER_CLIENT,
        Set.of("operations.evaluate", "operations.execute"));
  }

  ObjectNode reviewerProvider() {
    return providerJson(
        reviewer,
        REVIEWER_ISSUER,
        REVIEWER_AUDIENCE,
        REVIEWER_CLIENT,
        Set.of("operations.decide"));
  }

  String requesterVerifiedSubject() {
    return REQUESTER_ISSUER + "#" + REQUESTER_SUBJECT;
  }

  String reviewerVerifiedSubject() {
    return REVIEWER_ISSUER + "#" + REVIEWER_SUBJECT;
  }

  String requesterPrincipal() {
    return principal(requesterVerifiedSubject());
  }

  static String principal(String verifiedSubject) {
    String hash =
        CanonicalJson.sha256(JsonSupport.MAPPER.getNodeFactory().textNode(verifiedSubject));
    return "principal:" + hash.substring("sha256:".length());
  }

  private static OidcProviderConfiguration provider(
      String issuer, String audience, String client, Set<String> scopes) {
    Set<ConnectorScope> supported =
        scopes.stream().map(ConnectorScope::of).collect(java.util.stream.Collectors.toSet());
    return new OidcProviderConfiguration(
        URI.create(issuer),
        audience,
        URI.create(issuer + "/jwks"),
        URI.create(issuer + "/metadata"),
        List.of(URI.create(issuer)),
        "Synthetic lifecycle provider",
        Map.of(client, "Synthetic lifecycle client"),
        "org_id",
        Map.of(TENANT_VALUE, TENANT),
        supported,
        ConnectorOrigin.CLI,
        Duration.ofMinutes(10),
        Duration.ofMinutes(1),
        Duration.ofSeconds(30));
  }

  private static ObjectNode providerJson(
      KeyPair key, String issuer, String audience, String client, Set<String> scopes) {
    ObjectNode value = JsonSupport.MAPPER.createObjectNode();
    value
        .put("issuer", issuer)
        .put("audience", audience)
        .put("keyId", KEY_ID)
        .put("algorithm", "RS256")
        .put("jwtType", "at+jwt")
        .put(
            "publicKeySpki",
            Base64.getEncoder().encodeToString(key.getPublic().getEncoded()))
        .put("tenantClaim", "org_id")
        .put("tenantValue", TENANT_VALUE)
        .put("tenantId", TENANT)
        .put("clientId", client)
        .put("maximumTokenLifetimeSeconds", 600)
        .put("keyCacheSeconds", 60)
        .put("refreshCooldownSeconds", 30)
        .put("jwksUri", issuer + "/jwks")
        .put("metadataUri", issuer + "/metadata")
        .put("origin", "CLI");
    scopes.stream().sorted().forEach(value.putArray("supportedScopes")::add);
    return value;
  }

  private static String token(
      KeyPair key,
      String issuer,
      String audience,
      String subject,
      String client,
      String scopes) {
    Instant now = Instant.now();
    ObjectNode header =
        JsonSupport.MAPPER
            .createObjectNode()
            .put("alg", "RS256")
            .put("typ", "at+jwt")
            .put("kid", KEY_ID);
    ObjectNode claims =
        JsonSupport.MAPPER
            .createObjectNode()
            .put("iss", issuer)
            .put("aud", audience)
            .put("sub", subject)
            .put("client_id", client)
            .put("org_id", TENANT_VALUE)
            .put("scope", scopes)
            .put("iat", now.minusSeconds(5).getEpochSecond())
            .put("exp", now.plusSeconds(300).getEpochSecond())
            .put("jti", UUID.randomUUID().toString());
    Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
    String signingInput =
        encoder.encodeToString(header.toString().getBytes(StandardCharsets.UTF_8))
            + "."
            + encoder.encodeToString(claims.toString().getBytes(StandardCharsets.UTF_8));
    try {
      Signature signer = Signature.getInstance("SHA256withRSA");
      signer.initSign(key.getPrivate());
      signer.update(signingInput.getBytes(StandardCharsets.US_ASCII));
      return signingInput + "." + encoder.encodeToString(signer.sign());
    } catch (Exception failure) {
      throw new AssertionError("SYNTHETIC_TOKEN_SIGNING_FAILED", failure);
    }
  }
}
