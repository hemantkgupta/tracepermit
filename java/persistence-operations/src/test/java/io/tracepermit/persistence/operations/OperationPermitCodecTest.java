package io.tracepermit.persistence.operations;

import static io.tracepermit.application.BusinessOperationStorageException.Code.OPERATION_PERMIT_INVALID;
import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.tracepermit.application.BusinessOperationAdmissionSettings;
import io.tracepermit.application.BusinessOperationStorageException;
import io.tracepermit.contracts.CanonicalJson;
import io.tracepermit.contracts.JsonSupport;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/** Generated keys and synthetic claims only. No JDBC, clock, network or execution port. */
final class OperationPermitCodecTest {
  private static final String HASH = "sha256:" + "a".repeat(64);
  private static final String OPERATION = "00000000-0000-4000-8000-000000000003";

  @Test
  void newClosedWireUsesExactIndependentHeaderAndActualEd25519Signature() throws Exception {
    KeyPair key = keys();
    var settings = settings(key);
    var codec = new OperationPermitCodec(settings, key.getPrivate());
    var claims = claims(settings);
    String token = codec.sign(claims);
    String[] parts = token.split("\\.");
    String header = "{\"alg\":\"EdDSA\",\"kid\":\"synthetic-key\",\"typ\":\"tracepermit-business-operation-permit-v1+jws\"}";
    assertThat(parts[0].equals(encode(header.getBytes(StandardCharsets.US_ASCII)))).isTrue();
    Signature independent = Signature.getInstance("Ed25519"); independent.initVerify(key.getPublic());
    independent.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
    assertThat(independent.verify(Base64.getUrlDecoder().decode(parts[2]))).isTrue();
    var checked = new OperationPermitCodec(settings, null).verify(token);
    assertThat(CanonicalJson.sha256(checked.claims())).isEqualTo(CanonicalJson.sha256(claims));
    checked.claims().put("issuer", "caller-mutated");
    assertThat(CanonicalJson.sha256(checked.claims())).isEqualTo(CanonicalJson.sha256(claims));
    assertThat(checked.toString()).doesNotContain("synthetic-owner", HASH, "2030");
    assertThat(OperationPermitCodec.Checked.class.getConstructors()).isEmpty();
    invalid(() -> new OperationPermitCodec(settings, null).sign(claims));
  }

  @Test
  void keyPairMismatchAndDifferentKeyMaterialUnderSameKeyLabelFailClosed() throws Exception {
    var first = keys(); var second = keys();
    var settings = settings(first);
    invalid(() -> new OperationPermitCodec(settings, second.getPrivate()));
    var wrongCurve = KeyPairGenerator.getInstance("Ed448").generateKeyPair();
    invalid(() -> new OperationPermitCodec(settings, wrongCurve.getPrivate()));
    String token = new OperationPermitCodec(settings, first.getPrivate()).sign(claims(settings));
    invalid(() -> new OperationPermitCodec(settings(second), null).verify(token));
    invalid(() -> new OperationPermitCodec(null, first.getPrivate()));
  }

  @Test
  void unknownMissingDuplicateTrailingAndNonCanonicalJsonAreRefusedEvenWithValidSignature() throws Exception {
    var key = keys(); var settings = settings(key); var verifier = new OperationPermitCodec(settings, null);
    String json = CanonicalJson.canonicalize(claims(settings));
    for (String malformed : List.of(
        json.substring(0, json.length() - 1) + ",\"private-canary\":\"not-a-claim\"}",
        json.substring(0, json.length() - 1) + ",\"maxUses\":1}",
        json + " {}", " " + json, "null", "[]", "\"private-canary\"")) {
      invalid(() -> verifier.verify(raw(key, header(), malformed.getBytes(StandardCharsets.UTF_8))));
    }
    var missing = claims(settings); missing.remove("evaluationActorHash");
    invalid(() -> verifier.verify(raw(key, header(), CanonicalJson.canonicalize(missing).getBytes(StandardCharsets.UTF_8))));
    // Frozen canonical JSON renders an integral floating value as 5; put the non-canonical
    // numeric spelling on the actual wire rather than accidentally canonicalizing it away.
    invalid(() -> verifier.verify(raw(key, header(), json.replace("\"lastSequence\":5", "\"lastSequence\":5.0").getBytes(StandardCharsets.UTF_8))));
    invalid(() -> verifier.verify(raw(key, header(), new byte[] {(byte) 0xc3, 0x28})));
  }

  @Test
  void everyClosedBindingIntrinsicAndIdentityDeadlineIsValidatedWithoutLenientTypeCoercion() throws Exception {
    var key = keys(); var settings = settings(key); var verifier = new OperationPermitCodec(settings, null);
    List<Consumer<ObjectNode>> changes = List.of(
        n -> n.put("format", "legacy-profile"), n -> n.put("issuer", "other"), n -> n.put("audience", "other"),
        n -> n.put("publicKeyFingerprint", HASH), n -> n.put("admissionSettingsHash", HASH), n -> n.put("adapterId", "other"),
        n -> n.put("permitId", "not-an-id"), n -> n.put("attemptId", OPERATION.toUpperCase() + "x"),
        n -> n.put("operationId", "not-an-id"), n -> n.put("scopeHash", "bad"), n -> n.put("ownerPrincipalId", "private owner"),
        n -> ((ObjectNode) n.path("state")).put("operationId", "00000000-0000-4000-8000-000000000004"),
        n -> ((ObjectNode) n.path("state")).put("lastSequence", "5"),
        n -> ((ObjectNode) n.path("state")).put("authorizationVersion", 2),
        n -> ((ObjectNode) n.path("state")).put("executionVersion", 2),
        n -> ((ObjectNode) n.path("state")).put("evidenceVersion", 2),
        n -> ((ObjectNode) n.path("state")).put("recoveryGeneration", 9_007_199_254_740_992L),
        n -> n.put("maxUses", "1"), n -> n.put("maxUses", 2),
        n -> n.put("expiresAt", "2030-01-01T00:00:00Z"), n -> n.put("expiresAt", "2030-01-01T00:01:00.000000001Z"),
        n -> n.put("issuedAt", "2030-01-01T00:00:00.000Z"),
        n -> ((ObjectNode) n.path("identityBounds")).put("evaluatorExpiresAt", "2030-01-01T00:00:20Z"),
        n -> ((ObjectNode) n.path("identityBounds")).put("reviewerExpiresAt", "2030-01-01T00:00:20Z"),
        n -> ((ObjectNode) n.path("identityBounds")).put("membershipExpiresAt", "2030-01-01T00:00:20Z"),
        n -> ((ObjectNode) n.path("identityBounds")).put("approvalDeadline", "2030-01-01T00:00:20Z"),
        n -> ((ObjectNode) n.path("executeIdentity")).put("principalId", "other-owner"),
        n -> ((ObjectNode) n.path("executeIdentity")).put("scopeHash", "sha256:" + "b".repeat(64)),
        n -> ((ObjectNode) n.path("executeIdentity")).put("authorityExpiresAt", "2030-01-01T00:00:20Z"),
        n -> ((ObjectNode) n.path("executeIdentity")).put("authenticationMethod", "private method"),
        n -> ((ObjectNode) n.path("executeIdentity")).put("credentialFingerprint", "private-credential"));
    for (var change : changes) {
      var mutated = claims(settings); change.accept(mutated);
      invalid(() -> verifier.verify(raw(key, header(), CanonicalJson.canonicalize(mutated).getBytes(StandardCharsets.UTF_8))));
    }
  }

  @Test
  void algTypeKeyUrlPaddingOversizeAndSignatureChangesCannotBecomeVerifiedClaims() throws Exception {
    var key = keys(); var settings = settings(key); var verifier = new OperationPermitCodec(settings, null);
    byte[] payload = CanonicalJson.canonicalize(claims(settings)).getBytes(StandardCharsets.UTF_8);
    for (String header : List.of(header().replace("EdDSA", "none"), header().replace("EdDSA", "HS256"),
        header().replace("synthetic-key", "other-key"), header().replace(OperationPermitCodec.TYPE, "tracepermit-execution-permit+jws"),
        header().replace("}", ",\"jku\":\"https://not-called.invalid\"}"))) {
      invalid(() -> verifier.verify(raw(key, header, payload)));
    }
    String token = raw(key, header(), payload);
    String[] parts = token.split("\\.");
    for (String malformed : java.util.Arrays.asList(null, "", token + ".extra", token + "=", "x".repeat(16_385),
        parts[0] + "." + parts[1] + "=." + parts[2], parts[0] + ".." + parts[2],
        parts[0] + "." + parts[1] + "." + encode(new byte[64]),
        raw(key, header(), new byte[8193]))) {
      invalid(() -> verifier.verify(malformed));
    }
  }

  @Test
  void configuredNumericOrBooleanLookingLabelsDoNotAllowWireTypeCoercion() throws Exception {
    var key = keys();
    var settings = new BusinessOperationAdmissionSettings("true", "synthetic-key", "7", key.getPublic(), Duration.ofSeconds(60));
    var verifier = new OperationPermitCodec(settings, null);
    var wrongIssuer = claims(settings); wrongIssuer.put("issuer", true);
    invalid(() -> verifier.verify(raw(key, header(), CanonicalJson.canonicalize(wrongIssuer).getBytes(StandardCharsets.UTF_8))));
    var wrongAudience = claims(settings); wrongAudience.put("audience", 7);
    invalid(() -> verifier.verify(raw(key, header(), CanonicalJson.canonicalize(wrongAudience).getBytes(StandardCharsets.UTF_8))));
  }

  private static KeyPair keys() throws Exception { return KeyPairGenerator.getInstance("Ed25519").generateKeyPair(); }
  private static BusinessOperationAdmissionSettings settings(KeyPair key) {
    return new BusinessOperationAdmissionSettings("https://issuer.invalid", "synthetic-key", "synthetic-audience", key.getPublic(), Duration.ofSeconds(60));
  }
  private static String header() {
    return "{\"alg\":\"EdDSA\",\"kid\":\"synthetic-key\",\"typ\":\"tracepermit-business-operation-permit-v1+jws\"}";
  }
  private static ObjectNode claims(BusinessOperationAdmissionSettings settings) {
    var value = JsonSupport.MAPPER.createObjectNode();
    for (String hash : List.of("scopeHash", "profileHash", "targetProfileHash", "targetBindingDigest", "canonicalActionHash",
        "semanticPayloadDigest", "policyReleaseHash", "artifactDigestsHash", "resourceBindingHash", "preconditionBindingHash",
        "targetIdempotencyKeyHash", "reviewBindingHash", "evaluationFactHash", "evaluationActorHash", "approvalFactHash", "approvalActorHash")) value.put(hash, HASH);
    value.put("format", OperationPermitClaims.FORMAT).put("issuer", settings.issuer()).put("audience", settings.audience())
        .put("publicKeyFingerprint", settings.publicKeyFingerprint()).put("admissionSettingsHash", CanonicalJson.sha256(settings.pins()))
        .put("permitId", "00000000-0000-4000-8000-000000000001").put("attemptId", "00000000-0000-4000-8000-000000000002")
        .put("operationId", OPERATION).put("ownerPrincipalId", "principal:synthetic-owner").put("adapterId", "github-draft-pr-v1")
        .put("issuedAt", "2030-01-01T00:00:00Z").put("expiresAt", "2030-01-01T00:00:30Z").put("maxUses", 1);
    value.putObject("state").put("operationId", OPERATION).put("recoveryGeneration", 1).put("lastSequence", 5)
        .put("authorizationVersion", 3).put("executionVersion", 1).put("evidenceVersion", 1).put("snapshotHash", HASH);
    var bounds = value.putObject("identityBounds");
    for (String field : List.of("evaluatorExpiresAt", "reviewerExpiresAt", "membershipExpiresAt", "ownerExpiresAt", "approvalDeadline")) {
      bounds.put(field, "2030-01-01T00:02:00Z");
    }
    value.putObject("executeIdentity").put("scopeHash", HASH).put("principalId", "principal:synthetic-owner")
        .put("authenticationMethod", "synthetic").put("credentialFingerprint", HASH).put("authorityExpiresAt", "2030-01-01T00:02:00Z");
    return value;
  }
  private static String raw(KeyPair key, String header, byte[] payload) throws Exception {
    String body = encode(header.getBytes(StandardCharsets.UTF_8)) + "." + encode(payload);
    Signature signer = Signature.getInstance("Ed25519"); signer.initSign(key.getPrivate());
    signer.update(body.getBytes(StandardCharsets.US_ASCII)); return body + "." + encode(signer.sign());
  }
  private static String encode(byte[] bytes) { return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }
  private static void invalid(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
    assertThatThrownBy(action).isInstanceOfSatisfying(BusinessOperationStorageException.class, failure -> {
      assertThat(failure.code()).isEqualTo(OPERATION_PERMIT_INVALID);
      failure.addSuppressed(new IllegalStateException("private-canary"));
      assertThat(failure.getSuppressed()).isEmpty();
    }).hasMessage(OPERATION_PERMIT_INVALID.name()).hasNoCause();
  }
}
