package io.tracepermit.identity;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.tracepermit.contracts.CanonicalJson;
import io.tracepermit.contracts.JsonSupport;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.SignatureException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

public final class SourceAttestationVerifier {
  public static final String DEFAULT_AUDIENCE = "tracepermit-gateway";
  public static final int MAX_LIFETIME_SECONDS = 60;
  public static final int FUTURE_SKEW_SECONDS = 5;

  private static final int MAX_COMPACT_LENGTH = 16 * 1024;
  private static final Pattern HASH = Pattern.compile("^sha256:[a-f0-9]{64}$");
  private static final Pattern UUID =
      Pattern.compile(
          "^([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-8][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}|00000000-0000-0000-0000-000000000000|ffffffff-ffff-ffff-ffff-ffffffffffff)$");
  private static final Set<String> HEADER_FIELDS = Set.of("alg", "typ", "kid");
  private static final Set<String> CLAIM_FIELDS =
      Set.of(
          "version",
          "type",
          "canonicalizationProfile",
          "requestBindingProfile",
          "issuer",
          "keyId",
          "audience",
          "tokenId",
          "issuedAt",
          "expiresAt",
          "connectorCredentialFingerprint",
          "requestHash",
          "device",
          "process",
          "triggerEvidence");
  private static final Set<String> DEVICE_FIELDS = Set.of("deviceId", "platform", "assurance");
  private static final Set<String> PROCESS_FIELDS =
      Set.of("executableName", "executableHash", "parentExecutableName", "startedAt");
  private static final Set<String> TRIGGER_FIELDS = Set.of("requestedMode", "terminalAttached");
  private static final ObjectMapper STRICT_MAPPER =
      new ObjectMapper(
              JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

  private final EndpointKeyRegistry keyRegistry;
  private final SourceAttestationReplayStore replayStore;
  private final Clock clock;
  private final String audience;

  public SourceAttestationVerifier(
      EndpointKeyRegistry keyRegistry, SourceAttestationReplayStore replayStore, Clock clock) {
    this(keyRegistry, replayStore, clock, DEFAULT_AUDIENCE);
  }

  public SourceAttestationVerifier(
      EndpointKeyRegistry keyRegistry,
      SourceAttestationReplayStore replayStore,
      Clock clock,
      String audience) {
    this.keyRegistry = Objects.requireNonNull(keyRegistry);
    this.replayStore = Objects.requireNonNull(replayStore);
    this.clock = Objects.requireNonNull(clock);
    this.audience = nonEmpty(audience);
  }

  public VerifiedSourceEvidence verify(
      String compactAttestation, JsonNode request, String connectorCredentialFingerprint) {
    if (compactAttestation == null
        || compactAttestation.isEmpty()
        || compactAttestation.length() > MAX_COMPACT_LENGTH) {
      throw malformed();
    }
    String[] segments = compactAttestation.split("\\.", -1);
    if (segments.length != 3
        || segments[0].isEmpty()
        || segments[1].isEmpty()
        || segments[2].isEmpty()) {
      throw malformed();
    }

    JsonNode header = parseCanonicalJson(segments[0]);
    JsonNode payload = parseCanonicalJson(segments[1]);
    requireExactFields(header, HEADER_FIELDS);
    if (!"EdDSA".equals(requiredText(header, "alg"))
        || !"tracepermit-source-attestation+jws".equals(requiredText(header, "typ"))) {
      throw invalid("The source attestation header is invalid.");
    }
    ParsedClaims claims = parseClaims(payload);
    if (!requiredText(header, "kid").equals(claims.keyId())) {
      throw invalid("The source attestation key is invalid.");
    }

    EndpointKeyRegistry.EnrolledEndpointKey enrolled =
        keyRegistry
            .resolve(claims.issuer(), claims.keyId())
            .orElseThrow(() -> invalid("The source attestation key is invalid."));
    if (!enrolled.deviceId().equals(claims.device().deviceId())) {
      throw invalid("The source attestation key is invalid.");
    }
    byte[] signature = decodeCanonical(segments[2]);
    if (signature.length != 64
        || !verifySignature(
            enrolled.publicKey(), segments[0] + "." + segments[1], signature)) {
      throw invalid("The source attestation signature is invalid.");
    }

    Instant verifiedAt = clock.instant();
    if (claims.issuedAt().isAfter(verifiedAt.plusSeconds(FUTURE_SKEW_SECONDS))) {
      throw invalid("The source attestation was issued in the future.");
    }
    if (!claims.expiresAt().isAfter(verifiedAt)) {
      throw new SourceAttestationException(
          "EXPIRED_SOURCE_ATTESTATION", "The source attestation has expired.");
    }
    if (!audience.equals(claims.audience())) {
      throw invalid("The source attestation audience is invalid.");
    }
    if (!equalText(claims.connectorCredentialFingerprint(), connectorCredentialFingerprint)
        || !equalText(claims.requestHash(), hashRequest(request))) {
      throw invalid("The source attestation binding is invalid.");
    }
    if (!replayStore.consume(
        claims.issuer(), claims.tokenId(), claims.expiresAt(), verifiedAt)) {
      throw new SourceAttestationException(
          "SOURCE_ATTESTATION_REPLAYED", "The source attestation has already been used.");
    }

    return new VerifiedSourceEvidence(
        claims.issuer(),
        claims.keyId(),
        fingerprint(enrolled.publicKey().getEncoded()),
        verifiedAt,
        claims.device(),
        claims.process(),
        claims.triggerEvidence());
  }

  public static String hashRequest(JsonNode request) {
    if (request == null
        || !request.isObject()
        || !"2.0".equals(requiredText(request, "jsonrpc"))) {
      throw invalid("The source attestation request binding is invalid.");
    }
    String method = boundedText(request, "method", 128);
    ObjectNode binding = JsonSupport.MAPPER.createObjectNode();
    binding.put("method", method);
    binding.set(
        "params",
        request.has("params") ? request.get("params").deepCopy() : JsonSupport.MAPPER.nullNode());
    return CanonicalJson.sha256(binding);
  }

  static JsonNode validateClaimsForSigning(JsonNode claims) {
    parseClaims(claims);
    return claims.deepCopy();
  }

  private static ParsedClaims parseClaims(JsonNode payload) {
    requireExactFields(payload, CLAIM_FIELDS);
    if (!"1.0".equals(requiredText(payload, "version"))
        || !"tracepermit-source-attestation+jws".equals(requiredText(payload, "type"))
        || !CanonicalJson.PROFILE.equals(requiredText(payload, "canonicalizationProfile"))
        || !"tracepermit-mcp-method-params-v1"
            .equals(requiredText(payload, "requestBindingProfile"))) {
      throw invalid("The source attestation contains invalid claims.");
    }
    String issuer = boundedText(payload, "issuer", 256);
    String keyId = boundedText(payload, "keyId", 128);
    String audience = boundedText(payload, "audience", 256);
    String tokenId = requiredText(payload, "tokenId");
    if (!UUID.matcher(tokenId).matches()) {
      throw invalid("The source attestation contains invalid claims.");
    }
    Instant issuedAt = instant(payload, "issuedAt");
    Instant expiresAt = instant(payload, "expiresAt");
    Duration lifetime = Duration.between(issuedAt, expiresAt);
    if (lifetime.isZero()
        || lifetime.isNegative()
        || lifetime.compareTo(Duration.ofSeconds(MAX_LIFETIME_SECONDS)) > 0) {
      throw invalid("The source attestation lifetime is invalid.");
    }
    String credentialFingerprint = hash(payload, "connectorCredentialFingerprint");
    String requestHash = hash(payload, "requestHash");

    JsonNode deviceNode = object(payload, "device");
    requireExactFields(deviceNode, DEVICE_FIELDS);
    String deviceId = boundedText(deviceNode, "deviceId", 128);
    String platform = enumText(deviceNode, "platform", Set.of("macos", "linux", "windows"));
    String deviceAssurance =
        enumText(deviceNode, "assurance", Set.of("development", "managed", "hardware_backed"));

    JsonNode processNode = object(payload, "process");
    requireExactFields(processNode, PROCESS_FIELDS);
    String executableName = boundedText(processNode, "executableName", 128);
    String executableHash = hash(processNode, "executableHash");
    String parentExecutableName = boundedText(processNode, "parentExecutableName", 128);
    Instant startedAt = instant(processNode, "startedAt");
    if (startedAt.isAfter(issuedAt)) {
      throw invalid("The source attestation contains invalid claims.");
    }

    JsonNode triggerNode = object(payload, "triggerEvidence");
    requireExactFields(triggerNode, TRIGGER_FIELDS);
    String requestedMode =
        enumText(triggerNode, "requestedMode", Set.of("interactive", "automation", "unknown"));
    JsonNode terminal = triggerNode.get("terminalAttached");
    if (terminal == null || !terminal.isBoolean()) {
      throw invalid("The source attestation contains invalid claims.");
    }

    return new ParsedClaims(
        issuer,
        keyId,
        audience,
        tokenId,
        issuedAt,
        expiresAt,
        credentialFingerprint,
        requestHash,
        new VerifiedSourceEvidence.Device(deviceId, platform, deviceAssurance),
        new VerifiedSourceEvidence.ProcessEvidence(
            executableName, executableHash, parentExecutableName, startedAt),
        new VerifiedSourceEvidence.TriggerEvidence(requestedMode, terminal.booleanValue()));
  }

  private static JsonNode parseCanonicalJson(String segment) {
    byte[] bytes = decodeCanonical(segment);
    JsonNode parsed;
    try {
      parsed = STRICT_MAPPER.readTree(bytes);
    } catch (IOException exception) {
      throw malformed();
    }
    if (parsed == null
        || !CanonicalJson.canonicalize(parsed).equals(new String(bytes, StandardCharsets.UTF_8))) {
      throw malformed();
    }
    return parsed;
  }

  private static byte[] decodeCanonical(String segment) {
    if (!segment.matches("^[A-Za-z0-9_-]+$")) throw malformed();
    byte[] decoded;
    try {
      decoded = Base64.getUrlDecoder().decode(segment);
    } catch (IllegalArgumentException exception) {
      throw malformed();
    }
    if (!Base64.getUrlEncoder().withoutPadding().encodeToString(decoded).equals(segment)) {
      throw malformed();
    }
    return decoded;
  }

  private static boolean verifySignature(PublicKey key, String signingInput, byte[] signature) {
    try {
      Signature verifier = Signature.getInstance("Ed25519");
      verifier.initVerify(key);
      verifier.update(signingInput.getBytes(StandardCharsets.UTF_8));
      return verifier.verify(signature);
    } catch (SignatureException exception) {
      return false;
    } catch (GeneralSecurityException exception) {
      throw new IllegalStateException("Ed25519 verification is unavailable.", exception);
    }
  }

  private static String fingerprint(byte[] value) {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256").digest(value);
      return "sha256:" + HexFormat.of().formatHex(digest);
    } catch (GeneralSecurityException exception) {
      throw new IllegalStateException("SHA-256 is unavailable.", exception);
    }
  }

  private static boolean equalText(String left, String right) {
    if (right == null) return false;
    return MessageDigest.isEqual(
        left.getBytes(StandardCharsets.UTF_8), right.getBytes(StandardCharsets.UTF_8));
  }

  private static String hash(JsonNode value, String field) {
    String hash = requiredText(value, field);
    if (!HASH.matcher(hash).matches()) {
      throw invalid("The source attestation contains invalid claims.");
    }
    return hash;
  }

  private static Instant instant(JsonNode value, String field) {
    try {
      return Instant.parse(requiredText(value, field));
    } catch (DateTimeParseException exception) {
      throw invalid("The source attestation contains invalid claims.");
    }
  }

  private static String enumText(JsonNode value, String field, Set<String> allowed) {
    String result = requiredText(value, field);
    if (!allowed.contains(result)) {
      throw invalid("The source attestation contains invalid claims.");
    }
    return result;
  }

  private static String boundedText(JsonNode value, String field, int maximum) {
    String result = requiredText(value, field);
    if (result.length() > maximum) {
      throw invalid("The source attestation contains invalid claims.");
    }
    return result;
  }

  private static JsonNode object(JsonNode value, String field) {
    JsonNode result = value == null ? null : value.get(field);
    if (result == null || !result.isObject()) {
      throw invalid("The source attestation contains invalid claims.");
    }
    return result;
  }

  private static String requiredText(JsonNode value, String field) {
    JsonNode result = value == null ? null : value.get(field);
    if (result == null || !result.isTextual() || result.textValue().isEmpty()) {
      throw invalid("The source attestation contains invalid claims.");
    }
    return result.textValue();
  }

  private static void requireExactFields(JsonNode value, Set<String> expected) {
    if (value == null || !value.isObject() || value.size() != expected.size()) {
      throw invalid("The source attestation contains invalid claims.");
    }
    for (String field : expected) {
      if (!value.has(field)) throw invalid("The source attestation contains invalid claims.");
    }
  }

  private static String nonEmpty(String value) {
    Objects.requireNonNull(value);
    if (value.isEmpty()) throw new IllegalArgumentException("Value must not be empty.");
    return value;
  }

  private static SourceAttestationException malformed() {
    return new SourceAttestationException(
        "MALFORMED_SOURCE_ATTESTATION", "The source attestation is malformed.");
  }

  private static SourceAttestationException invalid(String message) {
    return new SourceAttestationException("INVALID_SOURCE_ATTESTATION", message);
  }

  private record ParsedClaims(
      String issuer,
      String keyId,
      String audience,
      String tokenId,
      Instant issuedAt,
      Instant expiresAt,
      String connectorCredentialFingerprint,
      String requestHash,
      VerifiedSourceEvidence.Device device,
      VerifiedSourceEvidence.ProcessEvidence process,
      VerifiedSourceEvidence.TriggerEvidence triggerEvidence) {}
}
