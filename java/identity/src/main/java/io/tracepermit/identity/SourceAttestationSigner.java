package io.tracepermit.identity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.tracepermit.contracts.CanonicalJson;
import io.tracepermit.contracts.JsonSupport;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.PrivateKey;
import java.security.Signature;
import java.util.Base64;
import java.util.Objects;

public final class SourceAttestationSigner {
  private final PrivateKey privateKey;

  @FunctionalInterface
  public interface SigningOperation {
    byte[] sign(byte[] signingInput);
  }

  public SourceAttestationSigner(PrivateKey privateKey) {
    this.privateKey = Objects.requireNonNull(privateKey);
    if (!"EdDSA".equals(privateKey.getAlgorithm())) {
      throw new IllegalArgumentException("Source attestations require an Ed25519 private key.");
    }
  }

  public String sign(JsonNode inputClaims) {
    return sign(inputClaims, this::signBytes);
  }

  public static String sign(JsonNode inputClaims, SigningOperation signingOperation) {
    Objects.requireNonNull(signingOperation);
    JsonNode claims = SourceAttestationVerifier.validateClaimsForSigning(inputClaims);
    ObjectNode header = JsonSupport.MAPPER.createObjectNode();
    header.put("alg", "EdDSA");
    header.put("kid", claims.path("keyId").asText());
    header.put("typ", "tracepermit-source-attestation+jws");
    String signingInput = encode(header) + "." + encode(claims);
    byte[] signature = Objects.requireNonNull(
        signingOperation.sign(signingInput.getBytes(StandardCharsets.UTF_8)));
    if (signature.length != 64) {
      throw new IllegalArgumentException("Source-attestation signature must be 64 bytes.");
    }
    return signingInput + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(signature);
  }

  private static String encode(JsonNode value) {
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(CanonicalJson.canonicalize(value).getBytes(StandardCharsets.UTF_8));
  }

  private byte[] signBytes(byte[] input) {
    try {
      Signature signer = Signature.getInstance("Ed25519");
      signer.initSign(privateKey);
      signer.update(input);
      return signer.sign();
    } catch (GeneralSecurityException exception) {
      throw new IllegalStateException("Ed25519 signing is unavailable.", exception);
    }
  }
}
