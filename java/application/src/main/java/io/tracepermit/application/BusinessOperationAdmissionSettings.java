package io.tracepermit.application;

import static io.tracepermit.application.BusinessOperationStorageException.Code.OPERATION_PROFILE_INVALID;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.tracepermit.contracts.CanonicalJson;
import io.tracepermit.contracts.JsonSupport;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.interfaces.EdECPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;

/** Explicit operator pins for the new operation permit, never request-derived configuration. */
public final class BusinessOperationAdmissionSettings {
  private final String issuer;
  private final String keyId;
  private final String audience;
  private final PublicKey publicKey;
  private final String publicKeyFingerprint;
  private final Duration lifetime;

  public BusinessOperationAdmissionSettings(String issuer, String keyId, String audience,
      PublicKey publicKey, Duration lifetime) {
    try {
      if (!identifier(issuer, 512) || !identifier(keyId, 128) || !identifier(audience, 256)
          || publicKey == null || lifetime == null || lifetime.isZero() || lifetime.isNegative()
          || lifetime.compareTo(Duration.ofSeconds(60)) > 0) throw invalid();
      // Decode our own immutable standard key, rather than retaining a mutable caller wrapper.
      byte[] encoded = publicKey.getEncoded();
      if (encoded == null || encoded.length != 44) throw invalid();
      PublicKey decoded = KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(encoded));
      if (!(decoded instanceof EdECPublicKey ed) || !"Ed25519".equals(ed.getParams().getName())
          || !Arrays.equals(encoded, decoded.getEncoded())) throw invalid();
      this.issuer = issuer;
      this.keyId = keyId;
      this.audience = audience;
      this.publicKey = decoded;
      this.lifetime = lifetime;
      this.publicKeyFingerprint = CanonicalJson.sha256(JsonSupport.MAPPER.createObjectNode()
          .put("format", "tracepermit-operation-permit-public-key-v1")
          .put("spki", Base64.getUrlEncoder().withoutPadding().encodeToString(encoded)));
    } catch (Exception failure) {
      throw invalid();
    }
  }

  public String issuer() { return issuer; }
  public String keyId() { return keyId; }
  public String audience() { return audience; }
  public PublicKey publicKey() { return publicKey; }
  public String publicKeyFingerprint() { return publicKeyFingerprint; }
  public Duration lifetime() { return lifetime; }

  public ObjectNode pins() {
    return JsonSupport.MAPPER.createObjectNode()
        .put("format", "tracepermit-operation-admission-settings-v1")
        .put("issuer", issuer).put("keyId", keyId).put("audience", audience)
        .put("publicKeyFingerprint", publicKeyFingerprint)
        .put("lifetimeSeconds", lifetime.getSeconds()).put("lifetimeNanos", lifetime.getNano());
  }

  private static boolean identifier(String value, int maximum) {
    return value != null && value.length() <= maximum
        && value.matches("[A-Za-z0-9][A-Za-z0-9._:@/+\\-]*");
  }

  private static BusinessOperationStorageException invalid() {
    return new BusinessOperationStorageException(OPERATION_PROFILE_INVALID);
  }

  @Override public String toString() {
    return "BusinessOperationAdmissionSettings[redacted; operator pins, not authority]";
  }
}
