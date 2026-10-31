package io.tracepermit.auth0reviewer;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.tracepermit.identity.*;
import java.net.URI;
import java.security.KeyFactory;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.util.*;

/** Offline operator trust, never a caller identity assertion. */
record ReviewerConfiguration(URI issuer, String audience, String client, String subject,
    String tenant, String keyId, RSAPublicKey key) {
  static final URI CALLBACK = URI.create("http://127.0.0.1:41839/reviewer/callback");
  static final String TENANT_CLAIM = "https://tracepermit.org/claims/tenant_id";
  static final String ROLE_CLAIM = "https://tracepermit.org/claims/roles";
  static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder()
      .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
      .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

  static ReviewerConfiguration parse(byte[] bytes) {
    try {
      if (bytes.length == 0 || bytes.length > 16384) throw rejected();
      String decoded = java.nio.charset.StandardCharsets.UTF_8.newDecoder()
          .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
          .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
          .decode(java.nio.ByteBuffer.wrap(bytes)).toString();
      JsonNode n = JSON.readTree(decoded);
      Set<String> fields = new HashSet<>();
      n.fieldNames().forEachRemaining(fields::add);
      if (!n.isObject() || !fields.equals(Set.of("schemaVersion", "issuer", "audience",
          "clientId", "expectedSubject", "tenant", "keyId", "publicKeySpki", "callbackUri")) ||
          !"1.0".equals(text(n, "schemaVersion", 3)) ||
          !CALLBACK.toString().equals(text(n, "callbackUri", 128))) throw rejected();
      URI issuer = URI.create(text(n, "issuer", 256));
      String host = issuer.getHost();
      if (host == null || !host.matches("[a-z0-9][a-z0-9-]{0,62}\\.au\\.auth0\\.com") ||
          !issuer.toString().equals("https://" + host + "/")) throw rejected();
      String audience = text(n, "audience", 512);
      URI aud = URI.create(audience);
      if (!"https".equals(aud.getScheme()) || aud.getHost() == null || aud.getUserInfo() != null ||
          aud.getFragment() != null || aud.getQuery() != null || audience.equals(issuer + "api/v2/")) throw rejected();
      String client = text(n, "clientId", 256);
      String subject = text(n, "expectedSubject", 256);
      String tenant = text(n, "tenant", 128);
      String kid = text(n, "keyId", 256);
      if (!client.matches("[A-Za-z0-9_-]{1,256}") ||
          !subject.matches("[A-Za-z0-9_-]+[|][A-Za-z0-9._:@/+\\-=|]{1,220}") ||
          subject.endsWith("@clients") || !kid.matches("[A-Za-z0-9._-]{1,256}") ||
          !tenant.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")) throw rejected();
      String encoded = text(n, "publicKeySpki", 2048);
      byte[] der = Base64.getDecoder().decode(encoded);
      if (!Base64.getEncoder().encodeToString(der).equals(encoded)) throw rejected();
      RSAPublicKey key = (RSAPublicKey) KeyFactory.getInstance("RSA")
          .generatePublic(new X509EncodedKeySpec(der));
      if (key.getModulus().bitLength() < 2048 || key.getModulus().bitLength() > 8192) throw rejected();
      return new ReviewerConfiguration(issuer, audience, client, subject, tenant, kid, key);
    } catch (Exception rejected) { throw rejected(); }
  }

  OidcProviderConfiguration provider() {
    return new OidcProviderConfiguration(issuer, audience, issuer.resolve(".well-known/jwks.json"),
        URI.create(audience + "/.well-known/oauth-protected-resource"), List.of(issuer),
        "TracePermit reviewer", Map.of(client, "Reviewer"), TENANT_CLAIM, Map.of(tenant, tenant),
        Set.of(ConnectorScope.of("operations.decide")), ConnectorOrigin.CLI, Duration.ofSeconds(300),
        Duration.ofMinutes(1), Duration.ofSeconds(30), OidcTokenProfile.auth0(ROLE_CLAIM, "REVIEWER"));
  }

  private static String text(JsonNode n, String field, int max) {
    JsonNode v = n.path(field);
    if (!v.isTextual() || v.textValue().isBlank() || v.textValue().length() > max ||
        v.textValue().chars().anyMatch(Character::isISOControl)) throw rejected();
    return v.textValue();
  }

  static IllegalArgumentException rejected() { return new IllegalArgumentException("REVIEWER_CONFIG_INVALID"); }
  @Override public String toString() { return "ReviewerConfiguration[redacted]"; }
}
