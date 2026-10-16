package io.tracepermit.security;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import io.tracepermit.contracts.ActionValidator;
import io.tracepermit.contracts.CanonicalJson;
import io.tracepermit.contracts.JsonSupport;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeFormatterBuilder;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Authority responsible for issuing and verifying single-use execution permits.
 *
 * <p>Supports zero-trust asymmetric signing via Ed25519 (EdDSA), allowing remote
 * executors and dispatchers to verify permits with only a {@link PublicKey} while the
 * issuance private key remains confined to the policy enforcement domain. Also supports
 * symmetric HMAC-SHA256 (HS256) for golden test vector compatibility.
 */
public final class ExecutionPermitAuthority {
  public static final String DEFAULT_ISSUER = "https://execution-permits.tracepermit.invalid";
  private static final int MAX_LIFETIME_SECONDS = 60;
  private static final int MAX_PERMIT_LENGTH = 16 * 1024;
  private static final java.time.format.DateTimeFormatter MILLIS =
      new DateTimeFormatterBuilder().appendInstant(3).toFormatter();

  private final String issuer;
  private final String keyId;
  private final byte[] signingKey;
  private final PrivateKey privateKey;
  private final PublicKey publicKey;
  private final int lifetimeSeconds;
  private final Clock clock;
  private final Supplier<String> createId;
  private final PermitConsumptionStore consumptionStore;

  /** Constructs an authority using symmetric HMAC-SHA256 (HS256). */
  public ExecutionPermitAuthority(
      String issuer,
      String keyId,
      byte[] signingKey,
      int lifetimeSeconds,
      Clock clock,
      Supplier<String> createId,
      PermitConsumptionStore consumptionStore) {
    this.issuer = Objects.requireNonNull(issuer);
    this.keyId = Objects.requireNonNull(keyId);
    this.signingKey = Objects.requireNonNull(signingKey).clone();
    this.privateKey = null;
    this.publicKey = null;
    this.lifetimeSeconds = lifetimeSeconds;
    this.clock = Objects.requireNonNull(clock);
    this.createId = Objects.requireNonNull(createId);
    this.consumptionStore = Objects.requireNonNull(consumptionStore);
    if (this.signingKey.length < 32) {
      throw new IllegalArgumentException("Execution permit signing keys require at least 32 bytes.");
    }
    if (lifetimeSeconds <= 0 || lifetimeSeconds > MAX_LIFETIME_SECONDS) {
      throw new IllegalArgumentException("Execution permit lifetime must be between 1 and 60 seconds.");
    }
  }

  /** Constructs an authority using asymmetric Ed25519 (EdDSA) key pair. */
  public ExecutionPermitAuthority(
      String issuer,
      String keyId,
      PrivateKey privateKey,
      PublicKey publicKey,
      int lifetimeSeconds,
      Clock clock,
      Supplier<String> createId,
      PermitConsumptionStore consumptionStore) {
    this.issuer = Objects.requireNonNull(issuer);
    this.keyId = Objects.requireNonNull(keyId);
    this.signingKey = null;
    this.privateKey = privateKey;
    this.publicKey = Objects.requireNonNull(publicKey);
    this.lifetimeSeconds = lifetimeSeconds;
    this.clock = Objects.requireNonNull(clock);
    this.createId = Objects.requireNonNull(createId);
    this.consumptionStore = Objects.requireNonNull(consumptionStore);
    if (privateKey != null
        && !"EdDSA".equalsIgnoreCase(privateKey.getAlgorithm())
        && !"Ed25519".equalsIgnoreCase(privateKey.getAlgorithm())) {
      throw new IllegalArgumentException("Asymmetric execution permits require an Ed25519 private key.");
    }
    if (!"EdDSA".equalsIgnoreCase(publicKey.getAlgorithm())
        && !"Ed25519".equalsIgnoreCase(publicKey.getAlgorithm())) {
      throw new IllegalArgumentException("Asymmetric execution permits require an Ed25519 public key.");
    }
    if (lifetimeSeconds <= 0 || lifetimeSeconds > MAX_LIFETIME_SECONDS) {
      throw new IllegalArgumentException("Execution permit lifetime must be between 1 and 60 seconds.");
    }
  }

  /** Constructs an authority using asymmetric Ed25519 KeyPair. */
  public ExecutionPermitAuthority(
      String issuer,
      String keyId,
      KeyPair keyPair,
      int lifetimeSeconds,
      Clock clock,
      Supplier<String> createId,
      PermitConsumptionStore consumptionStore) {
    this(
        issuer,
        keyId,
        Objects.requireNonNull(keyPair).getPrivate(),
        keyPair.getPublic(),
        lifetimeSeconds,
        clock,
        createId,
        consumptionStore);
  }

  /**
   * Constructs a verifier-only authority holding only an Ed25519 public key.
   *
   * <p>Permit issuance calls will fail, enforcing zero-trust privilege separation on executors.
   */
  public ExecutionPermitAuthority(
      String issuer,
      String keyId,
      PublicKey publicKey,
      int lifetimeSeconds,
      Clock clock,
      PermitConsumptionStore consumptionStore) {
    this(
        issuer,
        keyId,
        null,
        publicKey,
        lifetimeSeconds,
        clock,
        () -> {
          throw new IllegalStateException("Execution permit authority is configured in verifier-only mode.");
        },
        consumptionStore);
  }

  public String keyId() {
    return keyId;
  }

  public String algorithm() {
    return signingKey != null ? "HS256" : "EdDSA";
  }

  public boolean isVerifierOnly() {
    return privateKey == null && signingKey == null;
  }

  public String issue(JsonNode event, JsonNode evaluation, String audience) {
    JsonNode action = ActionValidator.validate(event);
    require(
        evaluation.path("eventId").asText().equals(action.path("eventId").asText()),
        "PERMIT_EVENT_MISMATCH",
        "The policy evaluation does not belong to this action.");
    require(
        "allow".equals(evaluation.path("decision").path("outcome").asText()),
        "DECISION_NOT_EXECUTABLE",
        "Only an allow decision can produce an execution permit.");
    return issue(action, evaluation, audience, null, null);
  }

  /** Issues a version-2 permit whose effective allow is derived from one durable human approval. */
  public String issueApproved(
      JsonNode event,
      JsonNode evaluation,
      String audience,
      String approvalRequestId,
      String approvalDecisionId) {
    JsonNode action = ActionValidator.validate(event);
    require(
        evaluation.path("eventId").asText().equals(action.path("eventId").asText()),
        "PERMIT_EVENT_MISMATCH",
        "The policy evaluation does not belong to this action.");
    require(
        "require_approval".equals(evaluation.path("decision").path("outcome").asText()),
        "DECISION_NOT_APPROVABLE",
        "Only a require_approval decision can use human approval authority.");
    requireIdentifier(approvalRequestId, "approval request ID");
    requireIdentifier(approvalDecisionId, "approval decision ID");
    return issue(action, evaluation, audience, approvalRequestId, approvalDecisionId);
  }

  private String issue(
      JsonNode action,
      JsonNode evaluation,
      String audience,
      String approvalRequestId,
      String approvalDecisionId) {
    if (signingKey == null && privateKey == null) {
      throw new IllegalStateException(
          "Execution permit authority is configured in verifier-only mode without a private signing key.");
    }
    Instant issuedAt = clock.instant();

    String alg = privateKey != null ? "EdDSA" : "HS256";
    Map<String, Object> header = new LinkedHashMap<>();
    header.put("alg", alg);
    header.put("typ", "tracepermit-execution-permit+jws");
    header.put("kid", keyId);
    Map<String, Object> decision = new LinkedHashMap<>();
    decision.put("outcome", "allow");
    decision.put("policyId", evaluation.path("decision").path("policyId").asText());
    decision.put("policyVersion", evaluation.path("decision").path("policyVersion").asInt());
    Map<String, Object> claims = new LinkedHashMap<>();
    claims.put("schemaVersion", approvalRequestId == null ? "1.0" : "2.0");
    claims.put("canonicalizationProfile", CanonicalJson.PROFILE);
    claims.put("permitId", createId.get());
    claims.put("issuer", issuer);
    claims.put("audience", audience);
    claims.put("subjectId", action.path("identity").path("subject").path("id").asText());
    claims.put("eventId", action.path("eventId").asText());
    claims.put("evaluationId", evaluation.path("evaluationId").asText());
    claims.put("actionHash", CanonicalJson.sha256(action));
    claims.put("decision", decision);
    if (approvalRequestId != null) {
      Map<String, Object> authorization = new LinkedHashMap<>();
      authorization.put("kind", "human_approval");
      authorization.put("approvalRequestId", approvalRequestId);
      authorization.put("approvalDecisionId", approvalDecisionId);
      authorization.put("originalPolicyOutcome", "require_approval");
      claims.put("authorization", authorization);
    }
    claims.put("issuedAt", MILLIS.format(issuedAt));
    claims.put("expiresAt", MILLIS.format(issuedAt.plusSeconds(lifetimeSeconds)));
    claims.put("maxUses", 1);
    String headerSegment = encode(header);
    String claimsSegment = encode(claims);
    String signingInput = headerSegment + "." + claimsSegment;
    return signingInput + "." + CanonicalJson.base64Url(sign(signingInput));
  }

  public JsonNode verifyAndConsume(String permit, JsonNode event, String expectedAudience) {
    return verifyAndConsume(null, permit, event, expectedAudience);
  }

  public JsonNode verifyAndConsume(
      String tenantId, String permit, JsonNode event, String expectedAudience) {
    JsonNode claims = verify(permit, event, expectedAudience);
    Instant now = clock.instant();
    Instant expiresAt = parseInstant(claims.path("expiresAt").asText());
    String actionHash = claims.path("actionHash").asText();
    boolean consumed =
        tenantId == null
            ? consumptionStore.consume(
                claims.path("permitId").asText(), expiresAt, actionHash, now)
            : consumptionStore.consume(
                tenantId, claims.path("permitId").asText(), expiresAt, actionHash, now);
    require(
        consumed,
        "EXECUTION_PERMIT_REPLAYED",
        "The single-use execution permit has already been consumed.");
    return claims;
  }


  /**
   * Verifies a permit without claiming it. Durable executors use this method and
   * atomically persist the claim with the execution attempt and initial evidence.
   */
  public JsonNode verify(String permit, JsonNode event, String expectedAudience) {
    require(
        permit != null && !permit.isEmpty() && permit.length() <= MAX_PERMIT_LENGTH,
        "MALFORMED_EXECUTION_PERMIT",
        "The execution permit is malformed.");
    String[] segments = permit.split("\\.", -1);
    require(
        segments.length == 3 && !segments[0].isEmpty() && !segments[1].isEmpty() && !segments[2].isEmpty(),
        "MALFORMED_EXECUTION_PERMIT",
        "The execution permit is malformed.");
    JsonNode header = decode(segments[0]);
    JsonNode claims = decode(segments[1]);

    String alg = header.path("alg").asText();
    String expectedAlg = publicKey != null ? "EdDSA" : "HS256";
    require(
        header.size() == 3
            && expectedAlg.equals(alg)
            && "tracepermit-execution-permit+jws".equals(header.path("typ").asText())
            && keyId.equals(header.path("kid").asText()),
        "INVALID_EXECUTION_PERMIT",
        "The execution permit contains invalid claims.");
    String schemaVersion = claims.path("schemaVersion").asText();
    require(
        ("1.0".equals(schemaVersion) || "2.0".equals(schemaVersion))
            && CanonicalJson.PROFILE.equals(claims.path("canonicalizationProfile").asText())
            && claims.path("maxUses").asInt() == 1,
        "INVALID_EXECUTION_PERMIT",
        "The execution permit contains invalid claims.");
    require(
        "allow".equals(claims.path("decision").path("outcome").asText())
            && !claims.path("decision").path("policyId").asText().isEmpty()
            && claims.path("decision").path("policyVersion").asInt() > 0,
        "INVALID_EXECUTION_PERMIT",
        "The execution permit contains invalid claims.");
    if ("1.0".equals(schemaVersion)) {
      require(!claims.has("authorization"), "INVALID_EXECUTION_PERMIT", "The execution permit contains invalid claims.");
    } else {
      JsonNode authorization = claims.path("authorization");
      require(
          authorization.size() == 4
              && "human_approval".equals(authorization.path("kind").asText())
              && "require_approval".equals(authorization.path("originalPolicyOutcome").asText()),
          "INVALID_EXECUTION_PERMIT",
          "The execution permit contains invalid approval authority.");
      requireIdentifier(authorization.path("approvalRequestId").asText(), "approval request ID");
      requireIdentifier(authorization.path("approvalDecisionId").asText(), "approval decision ID");
    }

    byte[] actual;
    try {
      actual = Base64.getUrlDecoder().decode(segments[2]);
    } catch (IllegalArgumentException exception) {
      throw error("INVALID_EXECUTION_PERMIT", "The execution permit signature is invalid.");
    }
    require(
        CanonicalJson.base64Url(actual).equals(segments[2]),
        "INVALID_EXECUTION_PERMIT",
        "The execution permit signature is invalid.");

    String signingInput = segments[0] + "." + segments[1];
    if (publicKey != null) {
      if (actual.length != 64) {
        throw error("INVALID_EXECUTION_PERMIT", "The execution permit signature is invalid.");
      }
      try {
        Signature verifier = Signature.getInstance("Ed25519");
        verifier.initVerify(publicKey);
        verifier.update(signingInput.getBytes(StandardCharsets.UTF_8));
        require(verifier.verify(actual), "INVALID_EXECUTION_PERMIT", "The execution permit signature is invalid.");
      } catch (GeneralSecurityException exception) {
        throw error("INVALID_EXECUTION_PERMIT", "The execution permit signature is invalid.");
      }
    } else {
      byte[] expected = sign(signingInput);
      require(
          MessageDigest.isEqual(expected, actual),
          "INVALID_EXECUTION_PERMIT",
          "The execution permit signature is invalid.");
    }

    require(issuer.equals(claims.path("issuer").asText()), "INVALID_EXECUTION_PERMIT", "The execution permit issuer is not trusted.");
    require(expectedAudience.equals(claims.path("audience").asText()), "WRONG_PERMIT_AUDIENCE", "The execution permit was issued for another executor.");
    Instant now = clock.instant();
    Instant issuedAt = parseInstant(claims.path("issuedAt").asText());
    Instant expiresAt = parseInstant(claims.path("expiresAt").asText());
    require(
        !issuedAt.isAfter(now.plusSeconds(30))
            && expiresAt.isAfter(issuedAt)
            && !expiresAt.isAfter(issuedAt.plusSeconds(MAX_LIFETIME_SECONDS)),
        "INVALID_EXECUTION_PERMIT",
        "The execution permit lifetime is invalid.");
    require(expiresAt.isAfter(now), "EXPIRED_EXECUTION_PERMIT", "The execution permit has expired.");
    JsonNode action = ActionValidator.validate(event);
    require(claims.path("eventId").asText().equals(action.path("eventId").asText()), "PERMIT_EVENT_MISMATCH", "The execution permit belongs to another event.");
    require(claims.path("subjectId").asText().equals(action.path("identity").path("subject").path("id").asText()), "PERMIT_SUBJECT_MISMATCH", "The execution permit belongs to another subject.");
    require(claims.path("actionHash").asText().equals(CanonicalJson.sha256(action)), "PERMIT_ACTION_MISMATCH", "The action changed after policy evaluation.");
    return claims.deepCopy();
  }

  private String encode(Object value) {
    try {
      return CanonicalJson.base64Url(JsonSupport.MAPPER.writeValueAsBytes(value));
    } catch (JsonProcessingException exception) {
      throw new IllegalArgumentException("Cannot encode execution permit", exception);
    }
  }

  private JsonNode decode(String segment) {
    try {
      return JsonSupport.MAPPER.readTree(Base64.getUrlDecoder().decode(segment));
    } catch (IllegalArgumentException | java.io.IOException exception) {
      throw error("MALFORMED_EXECUTION_PERMIT", "The execution permit is malformed.");
    }
  }

  private byte[] sign(String input) {
    if (privateKey != null) {
      try {
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(privateKey);
        signer.update(input.getBytes(StandardCharsets.UTF_8));
        return signer.sign();
      } catch (GeneralSecurityException exception) {
        throw new IllegalStateException("Ed25519 signing failed", exception);
      }
    }
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(signingKey, "HmacSHA256"));
      return mac.doFinal(input.getBytes(StandardCharsets.UTF_8));
    } catch (GeneralSecurityException exception) {
      throw new IllegalStateException("HMAC-SHA256 is unavailable", exception);
    }
  }

  private static Instant parseInstant(String value) {
    try {
      return Instant.parse(value);
    } catch (java.time.format.DateTimeParseException exception) {
      throw error("INVALID_EXECUTION_PERMIT", "The execution permit lifetime is invalid.");
    }
  }

  private static void require(boolean condition, String code, String message) {
    if (!condition) throw error(code, message);
  }

  private static void requireIdentifier(String value, String label) {
    require(
        value != null && value.matches("^[A-Za-z0-9][A-Za-z0-9._:@/+\\-]{0,255}$"),
        "INVALID_EXECUTION_PERMIT",
        "The " + label + " is invalid.");
  }

  private static ExecutionPermitException error(String code, String message) {
    return new ExecutionPermitException(code, message);
  }
}
