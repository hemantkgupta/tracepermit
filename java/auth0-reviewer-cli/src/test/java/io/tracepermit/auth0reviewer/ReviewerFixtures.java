package io.tracepermit.auth0reviewer;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.security.*;
import java.time.*;
import java.util.*;

final class ReviewerFixtures {
  static final Instant NOW = Instant.parse("2030-01-01T00:00:30Z");
  static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
  static final KeyPair KEY = key();
  static final String ISSUER = "https://synthetic.au.auth0.com/";
  static ObjectNode json() {
    return ReviewerConfiguration.JSON.createObjectNode().put("schemaVersion", "1.0")
        .put("issuer", ISSUER).put("audience", "https://demo.example.invalid/api")
        .put("clientId", "synthetic-reviewer").put("expectedSubject", "auth0|synthetic-human")
        .put("tenant", "synthetic-demo").put("keyId", "synthetic-key")
        .put("publicKeySpki", Base64.getEncoder().encodeToString(KEY.getPublic().getEncoded()))
        .put("callbackUri", ReviewerConfiguration.CALLBACK.toString());
  }
  static ReviewerConfiguration config() { return ReviewerConfiguration.parse(bytes(json())); }
  static byte[] bytes(ObjectNode n) {
    try { return ReviewerConfiguration.JSON.writeValueAsBytes(n); }
    catch (Exception failure) { throw new AssertionError(failure); }
  }
  static ObjectNode claims() {
    return ReviewerConfiguration.JSON.createObjectNode().put("iss", ISSUER)
        .put("aud", "https://demo.example.invalid/api").put("client_id", "synthetic-reviewer")
        .put("sub", "auth0|synthetic-human").put(ReviewerConfiguration.TENANT_CLAIM, "synthetic-demo")
        .put("jti", "synthetic-jti").put("scope", "operations.decide")
        .put("iat", NOW.getEpochSecond()).put("exp", NOW.plusSeconds(300).getEpochSecond());
  }
  static String response(java.util.function.Consumer<ObjectNode> mutation) {
    ObjectNode claims = claims(); claims.putArray(ReviewerConfiguration.ROLE_CLAIM).add("REVIEWER");
    mutation.accept(claims);
    try {
      ObjectNode header = ReviewerConfiguration.JSON.createObjectNode().put("typ", "at+jwt")
          .put("alg", "RS256").put("kid", "synthetic-key");
      var base = Base64.getUrlEncoder().withoutPadding();
      String payload = base.encodeToString(bytes(header)) + "." + base.encodeToString(bytes(claims));
      Signature signature = Signature.getInstance("SHA256withRSA"); signature.initSign(KEY.getPrivate());
      signature.update(payload.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
      String token = payload + "." + base.encodeToString(signature.sign());
      return ReviewerConfiguration.JSON.createObjectNode().put("access_token", token)
          .put("token_type", "Bearer").put("expires_in", 300).put("scope", "operations.decide").toString();
    } catch (Exception failure) { throw new AssertionError(failure); }
  }
  private static KeyPair key() {
    try { KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA"); generator.initialize(2048); return generator.generateKeyPair(); }
    catch (Exception failure) { throw new AssertionError(failure); }
  }
  private ReviewerFixtures() {}
}
