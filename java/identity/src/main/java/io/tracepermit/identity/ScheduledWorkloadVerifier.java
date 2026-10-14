package io.tracepermit.identity;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.tracepermit.contracts.CanonicalJson;

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
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** Verifies one request-bound scheduled workload and its complete delegation chain. */
public final class ScheduledWorkloadVerifier {
  public static final int MAX_WORKLOAD_LIFETIME_SECONDS = 300;
  public static final int MAX_DELEGATION_LIFETIME_SECONDS = 900;
  public static final int FUTURE_SKEW_SECONDS = 5;
  public static final int MAX_DELEGATION_DEPTH = 8;

  private static final int MAX_COMPACT_LENGTH = 16 * 1024;
  private static final Pattern IDENTIFIER =
      Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._:@/+\\-]{0,255}$");
  private static final Pattern HASH = Pattern.compile("^sha256:[a-f0-9]{64}$");
  private static final Pattern TRACE_ID = Pattern.compile("^[a-f0-9]{32}$");
  private static final Pattern UUID =
      Pattern.compile(
          "^([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-8][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}|00000000-0000-0000-0000-000000000000|ffffffff-ffff-ffff-ffff-ffffffffffff)$");
  private static final Set<String> HEADER_FIELDS = Set.of("alg", "typ", "kid");
  private static final Set<String> WORKLOAD_FIELDS =
      Set.of(
          "version",
          "type",
          "canonicalizationProfile",
          "requestBindingProfile",
          "issuer",
          "keyId",
          "audience",
          "tokenId",
          "tenantId",
          "subjectId",
          "schedulerId",
          "scheduleId",
          "runId",
          "eventId",
          "traceId",
          "actionHash",
          "issuedAt",
          "expiresAt");
  private static final Set<String> ROOT_GRANT_FIELDS =
      Set.of(
          "version",
          "type",
          "canonicalizationProfile",
          "issuer",
          "keyId",
          "tenantId",
          "tokenId",
          "actorId",
          "subjectId",
          "audience",
          "scopes",
          "resources",
          "purpose",
          "traceId",
          "issuedAt",
          "expiresAt");
  private static final Set<String> CHILD_GRANT_FIELDS;
  private static final ObjectMapper STRICT_MAPPER =
      new ObjectMapper(
              JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

  static {
    Set<String> childFields = new HashSet<>(ROOT_GRANT_FIELDS);
    childFields.add("parentTokenId");
    childFields.add("parentTokenHash");
    CHILD_GRANT_FIELDS = Set.copyOf(childFields);
  }

  private final WorkloadTrustRegistry trustRegistry;
  private final CredentialRevocationChecker revocationChecker;
  private final WorkloadIdentityReplayStore replayStore;
  private final Clock clock;

  public ScheduledWorkloadVerifier(
      WorkloadTrustRegistry trustRegistry,
      CredentialRevocationChecker revocationChecker,
      WorkloadIdentityReplayStore replayStore,
      Clock clock) {
    this.trustRegistry = Objects.requireNonNull(trustRegistry);
    this.revocationChecker = Objects.requireNonNull(revocationChecker);
    this.replayStore = Objects.requireNonNull(replayStore);
    this.clock = Objects.requireNonNull(clock);
  }

  public VerifiedScheduledWorkload verify(
      String compactWorkloadCredential,
      List<String> compactDelegations,
      JsonNode action,
      TrustedScheduledActionContext context) {
    Objects.requireNonNull(context);
    if (action == null || !action.isObject()) {
      throw invalidWorkload("The scheduled action binding is invalid.");
    }
    List<String> presentedDelegations = List.copyOf(compactDelegations);
    if (presentedDelegations.size() > MAX_DELEGATION_DEPTH) {
      throw invalidChain("The delegation chain is too deep.");
    }

    CompactCredential workloadCompact =
        parseCompact(
            compactWorkloadCredential,
            "tracepermit-workload-identity+jws",
            "MALFORMED_WORKLOAD_CREDENTIAL");
    ParsedWorkload workload = parseWorkload(workloadCompact.payload());
    WorkloadTrustRegistry.TrustedWorkloadKey workloadKey =
        requireTrustedKey(
            workload.issuer(),
            workload.keyId(),
            workload.tenantId(),
            workload.schedulerId(),
            WorkloadTrustRegistry.KeyUsage.WORKLOAD_IDENTITY,
            "INVALID_WORKLOAD_CREDENTIAL",
            "WORKLOAD_KEY_REVOKED");
    require(
        workloadCompact.keyId().equals(workload.keyId())
            && verifySignature(
                workloadKey.publicKey(), workloadCompact.signingInput(), workloadCompact.signature()),
        "INVALID_WORKLOAD_CREDENTIAL",
        "The workload credential signature is invalid.");

    Instant now = clock.instant();
    validateWorkloadBinding(workload, action, context, now);
    requireActive(
        revocationChecker.status(
            workload.tenantId(), workload.issuer(), workload.tokenId(), now),
        "WORKLOAD_CREDENTIAL_REVOKED",
        "WORKLOAD_REVOCATION_UNAVAILABLE");

    List<ParsedGrant> grants = new ArrayList<>();
    for (String compact : presentedDelegations) {
      CompactCredential parsed =
          parseCompact(
              compact,
              "tracepermit-delegation-grant+jws",
              "MALFORMED_DELEGATION_CREDENTIAL");
      ParsedGrant grant = parseGrant(parsed.payload(), compact);
      WorkloadTrustRegistry.TrustedWorkloadKey key =
          requireTrustedKey(
              grant.issuer(),
              grant.keyId(),
              grant.tenantId(),
              grant.actorId(),
              WorkloadTrustRegistry.KeyUsage.DELEGATION,
              "INVALID_DELEGATION_CREDENTIAL",
              "DELEGATION_KEY_REVOKED");
      require(
          parsed.keyId().equals(grant.keyId())
              && verifySignature(key.publicKey(), parsed.signingInput(), parsed.signature()),
          "INVALID_DELEGATION_CREDENTIAL",
          "The delegation credential signature is invalid.");
      validateGrantContext(grant, context, now);
      requireActive(
          revocationChecker.status(grant.tenantId(), grant.issuer(), grant.tokenId(), now),
          "DELEGATION_REVOKED",
          "DELEGATION_REVOCATION_UNAVAILABLE");
      grants.add(grant.withKeyFingerprint(fingerprint(key.publicKey().getEncoded())));
    }

    List<ParsedGrant> ordered = validateChain(workload, grants, context);
    if (!replayStore.consume(
        workload.tenantId(),
        workload.issuer(),
        workload.tokenId(),
        workload.expiresAt(),
        now)) {
      throw new WorkloadCredentialException(
          "WORKLOAD_CREDENTIAL_REPLAYED", "The workload credential has already been used.");
    }

    return new VerifiedScheduledWorkload(
        workload.tenantId(),
        workload.subjectId(),
        workload.schedulerId(),
        workload.issuer(),
        workload.keyId(),
        fingerprint(workloadKey.publicKey().getEncoded()),
        fingerprint(compactWorkloadCredential.getBytes(StandardCharsets.UTF_8)),
        now,
        ordered.stream().map(ParsedGrant::projection).toList());
  }

  private static void validateWorkloadBinding(
      ParsedWorkload workload,
      JsonNode action,
      TrustedScheduledActionContext context,
      Instant now) {
    require(
        workload.tenantId().equals(context.tenantId())
            && workload.schedulerId().equals(context.schedulerId())
            && workload.scheduleId().equals(context.scheduleId())
            && workload.runId().equals(context.runId())
            && workload.eventId().equals(context.eventId())
            && workload.traceId().equals(context.traceId())
            && workload.audience().equals(context.audience())
            && workload.actionHash().equals(CanonicalJson.sha256(action)),
        "INVALID_WORKLOAD_CREDENTIAL",
        "The workload credential binding is invalid.");
    validateTime(
        workload.issuedAt(),
        workload.expiresAt(),
        context.observedAt(),
        now,
        MAX_WORKLOAD_LIFETIME_SECONDS,
        "INVALID_WORKLOAD_CREDENTIAL",
        "EXPIRED_WORKLOAD_CREDENTIAL");
  }

  private static void validateGrantContext(
      ParsedGrant grant, TrustedScheduledActionContext context, Instant now) {
    require(
        grant.tenantId().equals(context.tenantId())
            && grant.audience().equals(context.audience())
            && grant.traceId().equals(context.traceId()),
        "INVALID_DELEGATION_CREDENTIAL",
        "The delegation credential binding is invalid.");
    validateTime(
        grant.issuedAt(),
        grant.expiresAt(),
        context.observedAt(),
        now,
        MAX_DELEGATION_LIFETIME_SECONDS,
        "INVALID_DELEGATION_CREDENTIAL",
        "EXPIRED_DELEGATION_CREDENTIAL");
  }

  private static List<ParsedGrant> validateChain(
      ParsedWorkload workload,
      List<ParsedGrant> grants,
      TrustedScheduledActionContext context) {
    if (grants.isEmpty()) {
      if (context.requireDelegation()) {
        throw invalidChain("A delegation chain is required for this scheduled action.");
      }
      return List.of();
    }

    Map<String, ParsedGrant> byId = new HashMap<>();
    Map<String, ParsedGrant> childByParent = new HashMap<>();
    ParsedGrant root = null;
    for (ParsedGrant grant : grants) {
      if (byId.put(grant.tokenId(), grant) != null) {
        throw invalidChain("Delegation token IDs must be unique.");
      }
      if (grant.parentTokenId() == null) {
        if (root != null) throw invalidChain("The delegation chain requires one root.");
        root = grant;
      } else if (childByParent.put(grant.parentTokenId(), grant) != null) {
        throw invalidChain("A presented delegation chain must not branch.");
      }
    }
    if (root == null || !root.actorId().equals(workload.subjectId())) {
      throw invalidChain("The delegation root must be issued by the workload subject.");
    }

    List<ParsedGrant> ordered = new ArrayList<>();
    Set<String> visited = new HashSet<>();
    ParsedGrant current = root;
    while (current != null) {
      if (!visited.add(current.tokenId())) {
        throw invalidChain("The delegation chain must not contain a cycle.");
      }
      ordered.add(current);
      ParsedGrant child = childByParent.get(current.tokenId());
      if (child != null) {
        if (!current.tokenHash().equals(child.parentTokenHash())
            || !current.subjectId().equals(child.actorId())
            || !subset(child.scopes(), current.scopes())
            || !subset(child.resources(), current.resources())
            || !current.audience().equals(child.audience())
            || child.issuedAt().isBefore(current.issuedAt())
            || child.expiresAt().isAfter(current.expiresAt())) {
          throw invalidChain("The child delegation broadens or breaks its parent authority.");
        }
      }
      current = child;
    }
    if (ordered.size() != grants.size()) {
      throw invalidChain("Every delegation must belong to the complete root chain.");
    }
    ParsedGrant leaf = ordered.get(ordered.size() - 1);
    if (!leaf.scopes().contains(context.requiredScope())
        || !leaf.resources().contains(context.requiredResource())) {
      throw invalidChain("The delegation leaf does not authorize the mapped action.");
    }
    return List.copyOf(ordered);
  }

  private WorkloadTrustRegistry.TrustedWorkloadKey requireTrustedKey(
      String issuer,
      String keyId,
      String tenantId,
      String principalId,
      WorkloadTrustRegistry.KeyUsage usage,
      String invalidCode,
      String revokedCode) {
    WorkloadTrustRegistry.TrustedWorkloadKey key =
        trustRegistry
            .resolve(issuer, keyId)
            .orElseThrow(
                () -> new WorkloadCredentialException(invalidCode, "The signing key is not trusted."));
    require(
        key.tenantId().equals(tenantId)
            && key.principalId().equals(principalId)
            && key.usages().contains(usage),
        invalidCode,
        "The signing key trust context is invalid.");
    if (key.state() != WorkloadTrustRegistry.KeyState.ACTIVE) {
      throw new WorkloadCredentialException(revokedCode, "The signing key is revoked.");
    }
    return key;
  }

  private static ParsedWorkload parseWorkload(JsonNode payload) {
    requireExactFields(payload, WORKLOAD_FIELDS, "INVALID_WORKLOAD_CREDENTIAL");
    require(
        "1.0".equals(text(payload, "version", 8, "INVALID_WORKLOAD_CREDENTIAL"))
            && "tracepermit-workload-identity+jws"
                .equals(text(payload, "type", 64, "INVALID_WORKLOAD_CREDENTIAL"))
            && CanonicalJson.PROFILE.equals(
                text(payload, "canonicalizationProfile", 64, "INVALID_WORKLOAD_CREDENTIAL"))
            && "tracepermit-scheduled-action-v1"
                .equals(text(payload, "requestBindingProfile", 64, "INVALID_WORKLOAD_CREDENTIAL")),
        "INVALID_WORKLOAD_CREDENTIAL",
        "The workload credential format is invalid.");
    return new ParsedWorkload(
        identifier(payload, "issuer", 256, "INVALID_WORKLOAD_CREDENTIAL"),
        identifier(payload, "keyId", 128, "INVALID_WORKLOAD_CREDENTIAL"),
        identifier(payload, "audience", 256, "INVALID_WORKLOAD_CREDENTIAL"),
        uuid(payload, "tokenId", "INVALID_WORKLOAD_CREDENTIAL"),
        identifier(payload, "tenantId", 128, "INVALID_WORKLOAD_CREDENTIAL"),
        identifier(payload, "subjectId", 256, "INVALID_WORKLOAD_CREDENTIAL"),
        identifier(payload, "schedulerId", 256, "INVALID_WORKLOAD_CREDENTIAL"),
        identifier(payload, "scheduleId", 256, "INVALID_WORKLOAD_CREDENTIAL"),
        identifier(payload, "runId", 256, "INVALID_WORKLOAD_CREDENTIAL"),
        uuid(payload, "eventId", "INVALID_WORKLOAD_CREDENTIAL"),
        traceId(payload, "traceId", "INVALID_WORKLOAD_CREDENTIAL"),
        hash(payload, "actionHash", "INVALID_WORKLOAD_CREDENTIAL"),
        instant(payload, "issuedAt", "INVALID_WORKLOAD_CREDENTIAL"),
        instant(payload, "expiresAt", "INVALID_WORKLOAD_CREDENTIAL"));
  }

  private static ParsedGrant parseGrant(JsonNode payload, String compact) {
    boolean child = payload.has("parentTokenId") || payload.has("parentTokenHash");
    requireExactFields(
        payload, child ? CHILD_GRANT_FIELDS : ROOT_GRANT_FIELDS, "INVALID_DELEGATION_CREDENTIAL");
    require(
        "1.0".equals(text(payload, "version", 8, "INVALID_DELEGATION_CREDENTIAL"))
            && "tracepermit-delegation-grant+jws"
                .equals(text(payload, "type", 64, "INVALID_DELEGATION_CREDENTIAL"))
            && CanonicalJson.PROFILE.equals(
                text(payload, "canonicalizationProfile", 64, "INVALID_DELEGATION_CREDENTIAL")),
        "INVALID_DELEGATION_CREDENTIAL",
        "The delegation credential format is invalid.");
    return new ParsedGrant(
        identifier(payload, "issuer", 256, "INVALID_DELEGATION_CREDENTIAL"),
        identifier(payload, "keyId", 128, "INVALID_DELEGATION_CREDENTIAL"),
        identifier(payload, "tenantId", 128, "INVALID_DELEGATION_CREDENTIAL"),
        uuid(payload, "tokenId", "INVALID_DELEGATION_CREDENTIAL"),
        child ? uuid(payload, "parentTokenId", "INVALID_DELEGATION_CREDENTIAL") : null,
        child ? hash(payload, "parentTokenHash", "INVALID_DELEGATION_CREDENTIAL") : null,
        identifier(payload, "actorId", 256, "INVALID_DELEGATION_CREDENTIAL"),
        identifier(payload, "subjectId", 256, "INVALID_DELEGATION_CREDENTIAL"),
        identifier(payload, "audience", 256, "INVALID_DELEGATION_CREDENTIAL"),
        strings(payload, "scopes", 32, 256),
        strings(payload, "resources", 64, 512),
        boundedText(payload, "purpose", 256, "INVALID_DELEGATION_CREDENTIAL"),
        traceId(payload, "traceId", "INVALID_DELEGATION_CREDENTIAL"),
        instant(payload, "issuedAt", "INVALID_DELEGATION_CREDENTIAL"),
        instant(payload, "expiresAt", "INVALID_DELEGATION_CREDENTIAL"),
        fingerprint(compact.getBytes(StandardCharsets.UTF_8)),
        null);
  }

  private static CompactCredential parseCompact(
      String compact, String expectedType, String malformedCode) {
    if (compact == null || compact.isEmpty() || compact.length() > MAX_COMPACT_LENGTH) {
      throw malformed(malformedCode);
    }
    String[] segments = compact.split("\\.", -1);
    if (segments.length != 3
        || segments[0].isEmpty()
        || segments[1].isEmpty()
        || segments[2].isEmpty()) {
      throw malformed(malformedCode);
    }
    JsonNode header = parseCanonicalJson(segments[0], malformedCode);
    JsonNode payload = parseCanonicalJson(segments[1], malformedCode);
    requireExactFields(header, HEADER_FIELDS, invalidCode(expectedType));
    require(
        "EdDSA".equals(text(header, "alg", 16, invalidCode(expectedType)))
            && expectedType.equals(text(header, "typ", 64, invalidCode(expectedType))),
        invalidCode(expectedType),
        "The signed credential header is invalid.");
    byte[] signature = decodeCanonical(segments[2], malformedCode);
    require(
        signature.length == 64,
        invalidCode(expectedType),
        "The signed credential signature is invalid.");
    return new CompactCredential(
        identifier(header, "kid", 128, invalidCode(expectedType)),
        payload,
        segments[0] + "." + segments[1],
        signature);
  }

  private static JsonNode parseCanonicalJson(String segment, String malformedCode) {
    byte[] bytes = decodeCanonical(segment, malformedCode);
    JsonNode parsed;
    try {
      parsed = STRICT_MAPPER.readTree(bytes);
    } catch (IOException exception) {
      throw malformed(malformedCode);
    }
    if (parsed == null
        || !parsed.isObject()
        || !CanonicalJson.canonicalize(parsed).equals(new String(bytes, StandardCharsets.UTF_8))) {
      throw malformed(malformedCode);
    }
    return parsed;
  }

  private static byte[] decodeCanonical(String segment, String malformedCode) {
    if (!segment.matches("^[A-Za-z0-9_-]+$")) throw malformed(malformedCode);
    byte[] decoded;
    try {
      decoded = Base64.getUrlDecoder().decode(segment);
    } catch (IllegalArgumentException exception) {
      throw malformed(malformedCode);
    }
    if (!Base64.getUrlEncoder().withoutPadding().encodeToString(decoded).equals(segment)) {
      throw malformed(malformedCode);
    }
    return decoded;
  }

  private static void validateTime(
      Instant issuedAt,
      Instant expiresAt,
      Instant observedAt,
      Instant now,
      int maximumLifetimeSeconds,
      String invalidCode,
      String expiredCode) {
    Duration lifetime = Duration.between(issuedAt, expiresAt);
    require(
        !lifetime.isZero()
            && !lifetime.isNegative()
            && lifetime.compareTo(Duration.ofSeconds(maximumLifetimeSeconds)) <= 0
            && !issuedAt.isAfter(now.plusSeconds(FUTURE_SKEW_SECONDS))
            && !observedAt.isAfter(now.plusSeconds(FUTURE_SKEW_SECONDS))
            && !observedAt.isBefore(issuedAt),
        invalidCode,
        "The signed credential lifetime is invalid.");
    if (!expiresAt.isAfter(now) || !observedAt.isBefore(expiresAt)) {
      throw new WorkloadCredentialException(expiredCode, "The signed credential has expired.");
    }
  }

  private static void requireActive(
      CredentialRevocationChecker.RevocationStatus status,
      String revokedCode,
      String unavailableCode) {
    if (status == CredentialRevocationChecker.RevocationStatus.REVOKED) {
      throw new WorkloadCredentialException(revokedCode, "The signed credential is revoked.");
    }
    if (status != CredentialRevocationChecker.RevocationStatus.ACTIVE) {
      throw new WorkloadCredentialException(
          unavailableCode, "Credential revocation status is unavailable.");
    }
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

  private static List<String> strings(
      JsonNode payload, String name, int maximumItems, int maximumLength) {
    JsonNode values = payload.get(name);
    require(
        values != null
            && values.isArray()
            && !values.isEmpty()
            && values.size() <= maximumItems,
        "INVALID_DELEGATION_CREDENTIAL",
        "The delegation credential contains an invalid " + name + " list.");
    List<String> result = new ArrayList<>();
    Set<String> unique = new HashSet<>();
    for (JsonNode value : values) {
      String item = value.isTextual() ? value.textValue() : "";
      require(
          !item.isEmpty()
              && item.length() <= maximumLength
              && IDENTIFIER.matcher(item).matches()
              && unique.add(item),
          "INVALID_DELEGATION_CREDENTIAL",
          "The delegation credential contains an invalid " + name + " value.");
      result.add(item);
    }
    return List.copyOf(result);
  }

  private static void requireExactFields(JsonNode value, Set<String> fields, String code) {
    if (value == null || !value.isObject()) {
      throw new WorkloadCredentialException(code, "The signed credential structure is invalid.");
    }
    Set<String> actual = new HashSet<>();
    value.fieldNames().forEachRemaining(actual::add);
    require(actual.equals(fields), code, "The signed credential fields are invalid.");
  }

  private static String identifier(JsonNode value, String field, int maximum, String code) {
    String text = text(value, field, maximum, code);
    require(IDENTIFIER.matcher(text).matches(), code, "The " + field + " is invalid.");
    return text;
  }

  private static String uuid(JsonNode value, String field, String code) {
    String text = text(value, field, 64, code);
    require(UUID.matcher(text).matches(), code, "The " + field + " is invalid.");
    return text;
  }

  private static String traceId(JsonNode value, String field, String code) {
    String text = text(value, field, 64, code);
    require(TRACE_ID.matcher(text).matches(), code, "The trace ID is invalid.");
    return text;
  }

  private static String hash(JsonNode value, String field, String code) {
    String text = text(value, field, 80, code);
    require(HASH.matcher(text).matches(), code, "The " + field + " is invalid.");
    return text;
  }

  private static String boundedText(JsonNode value, String field, int maximum, String code) {
    String text = text(value, field, maximum, code);
    require(
        text.chars().allMatch(character -> character >= 0x20 && character != 0x7f),
        code,
        "The " + field + " is invalid.");
    return text;
  }

  private static String text(JsonNode value, String field, int maximum, String code) {
    JsonNode item = value == null ? null : value.get(field);
    String result = item != null && item.isTextual() ? item.textValue() : "";
    require(
        !result.isEmpty() && result.length() <= maximum,
        code,
        "The " + field + " is invalid.");
    return result;
  }

  private static Instant instant(JsonNode value, String field, String code) {
    try {
      return Instant.parse(text(value, field, 64, code));
    } catch (DateTimeParseException exception) {
      throw new WorkloadCredentialException(code, "The " + field + " is invalid.");
    }
  }

  private static boolean subset(List<String> child, List<String> parent) {
    return new HashSet<>(parent).containsAll(child);
  }

  private static String fingerprint(byte[] value) {
    try {
      return "sha256:"
          + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    } catch (GeneralSecurityException exception) {
      throw new IllegalStateException("SHA-256 is unavailable.", exception);
    }
  }

  private static String invalidCode(String expectedType) {
    return expectedType.contains("workload")
        ? "INVALID_WORKLOAD_CREDENTIAL"
        : "INVALID_DELEGATION_CREDENTIAL";
  }

  private static WorkloadCredentialException malformed(String code) {
    return new WorkloadCredentialException(code, "The signed credential is malformed.");
  }

  private static WorkloadCredentialException invalidWorkload(String message) {
    return new WorkloadCredentialException("INVALID_WORKLOAD_CREDENTIAL", message);
  }

  private static WorkloadCredentialException invalidChain(String message) {
    return new WorkloadCredentialException("INVALID_DELEGATION_CHAIN", message);
  }

  private static void require(boolean condition, String code, String message) {
    if (!condition) throw new WorkloadCredentialException(code, message);
  }

  private record CompactCredential(
      String keyId, JsonNode payload, String signingInput, byte[] signature) {
    private CompactCredential {
      signature = signature.clone();
    }

    @Override
    public byte[] signature() {
      return signature.clone();
    }
  }

  private record ParsedWorkload(
      String issuer,
      String keyId,
      String audience,
      String tokenId,
      String tenantId,
      String subjectId,
      String schedulerId,
      String scheduleId,
      String runId,
      String eventId,
      String traceId,
      String actionHash,
      Instant issuedAt,
      Instant expiresAt) {}

  private record ParsedGrant(
      String issuer,
      String keyId,
      String tenantId,
      String tokenId,
      String parentTokenId,
      String parentTokenHash,
      String actorId,
      String subjectId,
      String audience,
      List<String> scopes,
      List<String> resources,
      String purpose,
      String traceId,
      Instant issuedAt,
      Instant expiresAt,
      String tokenHash,
      String keyFingerprint) {
    private ParsedGrant {
      scopes = List.copyOf(scopes);
      resources = List.copyOf(resources);
    }

    private ParsedGrant withKeyFingerprint(String value) {
      return new ParsedGrant(
          issuer,
          keyId,
          tenantId,
          tokenId,
          parentTokenId,
          parentTokenHash,
          actorId,
          subjectId,
          audience,
          scopes,
          resources,
          purpose,
          traceId,
          issuedAt,
          expiresAt,
          tokenHash,
          value);
    }

    private VerifiedScheduledWorkload.VerifiedDelegationGrant projection() {
      return new VerifiedScheduledWorkload.VerifiedDelegationGrant(
          tokenId,
          parentTokenId,
          issuer,
          keyId,
          keyFingerprint,
          actorId,
          subjectId,
          audience,
          scopes,
          resources,
          purpose,
          traceId,
          issuedAt,
          expiresAt,
          tokenHash);
    }
  }
}
