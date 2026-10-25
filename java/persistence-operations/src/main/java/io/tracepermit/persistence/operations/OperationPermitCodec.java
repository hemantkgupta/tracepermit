package io.tracepermit.persistence.operations;

import static io.tracepermit.application.BusinessOperationStorageException.Code.OPERATION_PERMIT_INVALID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.tracepermit.application.BusinessOperationAdmissionSettings;
import io.tracepermit.application.BusinessOperationStorageException;
import io.tracepermit.contracts.CanonicalJson;
import io.tracepermit.contracts.JsonSupport;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.interfaces.EdECPrivateKey;
import java.util.Base64;

/** New closed wire format; package-internal signing/verification is not an admission API. */
final class OperationPermitCodec {
  static final String TYPE = "tracepermit-business-operation-permit-v1+jws";
  private final BusinessOperationAdmissionSettings settings;
  private final PrivateKey privateKey;
  private final String encodedHeader;

  OperationPermitCodec(BusinessOperationAdmissionSettings settings, PrivateKey privateKey) {
    try {
      if (settings == null) throw invalid();
      this.settings = settings;
      this.privateKey = privateKey;
      this.encodedHeader = encode(CanonicalJson.canonicalize(JsonSupport.MAPPER.createObjectNode()
          .put("alg", "EdDSA").put("kid", settings.keyId()).put("typ", TYPE)).getBytes(StandardCharsets.UTF_8));
      if (privateKey != null) {
        if (!(privateKey instanceof EdECPrivateKey ed) || !"Ed25519".equals(ed.getParams().getName())) throw invalid();
        byte[] challenge = "tracepermit-operation-permit-key-pair-check-v1".getBytes(StandardCharsets.US_ASCII);
        Signature signer = Signature.getInstance("Ed25519"); signer.initSign(privateKey); signer.update(challenge);
        Signature verifier = Signature.getInstance("Ed25519"); verifier.initVerify(settings.publicKey()); verifier.update(challenge);
        if (!verifier.verify(signer.sign())) throw invalid();
      }
    } catch (Exception failure) { throw invalid(); }
  }

  String sign(JsonNode claims) {
    try {
      if (privateKey == null) throw invalid();
      String json = CanonicalJson.canonicalize(OperationPermitClaims.checked(claims, settings));
      if (json.getBytes(StandardCharsets.UTF_8).length > 8192) throw invalid();
      String body = encodedHeader + "." + encode(json.getBytes(StandardCharsets.UTF_8));
      Signature signer = Signature.getInstance("Ed25519"); signer.initSign(privateKey);
      signer.update(body.getBytes(StandardCharsets.US_ASCII));
      return body + "." + encode(signer.sign());
    } catch (Exception failure) { throw invalid(); }
  }

  Checked verify(String token) {
    try {
      if (token == null || token.length() > 16_384) throw invalid();
      String[] parts = token.split("\\.", -1);
      if (parts.length != 3 || !encodedHeader.equals(parts[0])) throw invalid();
      byte[] payload = decode(parts[1], 8192);
      byte[] signature = decode(parts[2], 64);
      if (signature.length != 64) throw invalid();
      Signature verifier = Signature.getInstance("Ed25519"); verifier.initVerify(settings.publicKey());
      verifier.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
      if (!verifier.verify(signature)) throw invalid();
      String json = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(payload)).toString();
      JsonNode claims = StoredOperationJson.read(json, 8192);
      if (!CanonicalJson.canonicalize(claims).equals(json)) throw invalid();
      return new Checked(OperationPermitClaims.checked(claims, settings));
    } catch (Exception failure) { throw invalid(); }
  }

  static final class Checked {
    private final ObjectNode claims;
    private Checked(ObjectNode claims) { this.claims = claims.deepCopy(); }
    ObjectNode claims() { return claims.deepCopy(); }
    @Override public String toString() { return "CheckedOperationPermit[redacted; signature only, not admission]"; }
  }

  private static byte[] decode(String part, int maximum) {
    if (part.isEmpty() || part.length() > (maximum * 4 + 2) / 3 || !part.matches("[A-Za-z0-9_-]+")) throw invalid();
    byte[] decoded = Base64.getUrlDecoder().decode(part);
    if (decoded.length > maximum || !encode(decoded).equals(part)) throw invalid();
    return decoded;
  }
  private static String encode(byte[] bytes) { return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }
  private static BusinessOperationStorageException invalid() { return new BusinessOperationStorageException(OPERATION_PERMIT_INVALID); }
}
