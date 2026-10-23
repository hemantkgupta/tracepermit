package io.tracepermit.contracts;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Validates the strict, internal business-operation snapshot contract.
 *
 * <p>This validator does not authenticate a caller and the contract is not a public request body. A
 * trusted adapter must derive the tenant, workspace, and requester fields before constructing the
 * snapshot. Repository implementations remain responsible for request-key uniqueness, atomic
 * persistence, authorization of snapshot reads, and append-only database constraints.
 */
public final class BusinessOperationValidator {
  public static final String SCHEMA_VERSION = "1.0";

  private static final long MAX_SAFE_INTEGER = 9_007_199_254_740_991L;
  private static final Pattern HASH = Pattern.compile("^sha256:[a-f0-9]{64}$");
  private static final Pattern UUID =
      Pattern.compile(
          "^([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-8][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}|00000000-0000-0000-0000-000000000000|ffffffff-ffff-ffff-ffff-ffffffffffff)$");
  private static final Pattern IDENTIFIER =
      Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._:@/-]{0,255}$");
  private static final Pattern PARTITION =
      Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$");
  private static final Pattern REASON = Pattern.compile("^[A-Z][A-Z0-9_]{0,127}$");
  private static final Pattern ISO_INSTANT =
      Pattern.compile("^\\d{4}-\\d{2}-\\d{2}T(?:[01]\\d|2[0-3]):[0-5]\\d:[0-5]\\d(?:\\.\\d+)?Z$");

  private static final Set<String> ENVIRONMENTS = Set.of("dev", "test", "staging", "prod");
  private static final Set<String> PRECONDITIONS =
      Set.of("none", "version", "etag", "commit_sha", "resource_state_digest");
  private static final Map<Dimension, Set<String>> STATES =
      Map.of(
          Dimension.AUTHORIZATION,
              Set.of(
                  "evaluating",
                  "denied",
                  "awaiting_approval",
                  "approved",
                  "expired",
                  "revoked",
                  "cancelled"),
          Dimension.EXECUTION,
              Set.of("not_started", "claimed", "executing", "succeeded", "failed", "unknown"),
          Dimension.EVIDENCE,
              Set.of("pending", "leased", "published", "delivery_unknown"));
  private static final Map<Dimension, Map<String, Set<String>>> TRANSITIONS = transitions();

  private BusinessOperationValidator() {}

  public static JsonNode validate(JsonNode input) {
    ObjectNode root = object(input, "$businessOperation");
    exactFields(
        root,
        Set.of(
            "schemaVersion",
            "operationId",
            "trustedContext",
            "adapterId",
            "clientRequestKeyHash",
            "canonicalization",
            "canonicalAction",
            "canonicalActionHash",
            "semanticPayloadDigest",
            "resourceBinding",
            "resourceBindingHash",
            "targetIdempotencyKeyHash",
            "policyRelease",
            "artifactDigests",
            "recoveryGeneration",
            "retention",
            "authorization",
            "execution",
            "evidence",
            "createdAt",
            "updatedAt",
            "transitions"),
        Set.of(),
        "$businessOperation");

    require(SCHEMA_VERSION.equals(text(root, "schemaVersion", "schemaVersion")), "schemaVersion", "must equal 1.0");
    String operationId = uuid(root, "operationId", "operationId");
    ObjectNode trustedContext = object(root.get("trustedContext"), "trustedContext");
    validateTrustedContext(trustedContext);
    String adapterId = identifier(root, "adapterId", "adapterId");
    hash(root, "clientRequestKeyHash", "clientRequestKeyHash");
    require(
        CanonicalJson.PROFILE.equals(text(root, "canonicalization", "canonicalization")),
        "canonicalization",
        "must use the frozen tracepermit-canonical-json-v1 profile");

    JsonNode suppliedAction = object(root.get("canonicalAction"), "canonicalAction");
    JsonNode canonicalAction = ActionValidator.validate(suppliedAction);
    require(
        canonicalAction.equals(suppliedAction),
        "canonicalAction",
        "must already be normalized and contain no unsupported fields");
    require(
        trustedContext
            .path("requesterPrincipalId")
            .asText()
            .equals(canonicalAction.path("identity").path("subject").path("id").asText()),
        "trustedContext.requesterPrincipalId",
        "must match canonicalAction.identity.subject.id");
    require(
        trustedContext
            .path("tenantId")
            .asText()
            .equals(
                canonicalAction
                    .path("identity")
                    .path("subject")
                    .path("organizationId")
                    .asText()),
        "trustedContext.tenantId",
        "must match canonicalAction.identity.subject.organizationId");
    requireHashBinding(
        root,
        "canonicalActionHash",
        CanonicalJson.sha256(canonicalAction),
        "must bind the exact canonical action");
    requireHashBinding(
        root,
        "semanticPayloadDigest",
        CanonicalJson.sha256(canonicalAction.path("action")),
        "must bind the canonical action payload");

    JsonNode resourceBinding = validateResourceBinding(object(root.get("resourceBinding"), "resourceBinding"));
    JsonNode actionTarget = canonicalAction.path("action").path("target");
    for (String field : Set.of("system", "environment", "resourceType")) {
      require(
          resourceBinding.path(field).asText().equals(actionTarget.path(field).asText()),
          "resourceBinding." + field,
          "must match canonicalAction.action.target." + field);
    }
    requireHashBinding(
        root,
        "resourceBindingHash",
        CanonicalJson.sha256(resourceBinding),
        "must bind the exact resource and precondition");

    long recoveryGeneration = positiveInteger(root, "recoveryGeneration", "recoveryGeneration");
    ObjectNode targetKeyMaterial = JsonSupport.MAPPER.createObjectNode();
    targetKeyMaterial.put("operationId", operationId);
    targetKeyMaterial.put("adapterId", adapterId);
    targetKeyMaterial.put("recoveryGeneration", recoveryGeneration);
    requireHashBinding(
        root,
        "targetIdempotencyKeyHash",
        CanonicalJson.sha256(targetKeyMaterial),
        "must derive from operationId, adapterId, and recoveryGeneration");

    validatePolicyRelease(object(root.get("policyRelease"), "policyRelease"));
    validateArtifactDigests(array(root.get("artifactDigests"), "artifactDigests"));
    Instant createdAt = instant(root, "createdAt", "createdAt");
    require(
        !createdAt.isBefore(Instant.parse(canonicalAction.path("observedAt").asText())),
        "createdAt",
        "must not be before canonicalAction.observedAt");
    Instant updatedAt = instant(root, "updatedAt", "updatedAt");
    require(!updatedAt.isBefore(createdAt), "updatedAt", "must not be before createdAt");
    validateRetention(object(root.get("retention"), "retention"), createdAt);

    EnumMap<Dimension, Snapshot> snapshots = new EnumMap<>(Dimension.class);
    snapshots.put(
        Dimension.AUTHORIZATION,
        validateSnapshot(
            object(root.get("authorization"), "authorization"),
            Dimension.AUTHORIZATION,
            "authorization"));
    snapshots.put(
        Dimension.EXECUTION,
        validateSnapshot(
            object(root.get("execution"), "execution"), Dimension.EXECUTION, "execution"));
    snapshots.put(
        Dimension.EVIDENCE,
        validateSnapshot(
            object(root.get("evidence"), "evidence"), Dimension.EVIDENCE, "evidence"));
    validateTransitions(
        array(root.get("transitions"), "transitions"), snapshots, createdAt, updatedAt);

    return root.deepCopy();
  }

  public static String targetIdempotencyKeyHash(
      String operationId, String adapterId, long recoveryGeneration) {
    ObjectNode material = JsonSupport.MAPPER.createObjectNode();
    material.put("operationId", operationId);
    material.put("adapterId", adapterId);
    material.put("recoveryGeneration", recoveryGeneration);
    return CanonicalJson.sha256(material);
  }

  private static void validateTrustedContext(ObjectNode context) {
    exactFields(
        context,
        Set.of("tenantId", "workspaceId", "requesterPrincipalId"),
        Set.of(),
        "trustedContext");
    partition(context, "tenantId", "trustedContext.tenantId");
    partition(context, "workspaceId", "trustedContext.workspaceId");
    identifier(context, "requesterPrincipalId", "trustedContext.requesterPrincipalId");
  }

  private static JsonNode validateResourceBinding(ObjectNode binding) {
    exactFields(
        binding,
        Set.of("system", "environment", "resourceType", "resourceIdHash", "precondition"),
        Set.of(),
        "resourceBinding");
    identifier(binding, "system", "resourceBinding.system");
    String environment = text(binding, "environment", "resourceBinding.environment");
    require(
        ENVIRONMENTS.contains(environment),
        "resourceBinding.environment",
        "contains an unsupported value");
    identifier(binding, "resourceType", "resourceBinding.resourceType");
    hash(binding, "resourceIdHash", "resourceBinding.resourceIdHash");
    ObjectNode precondition = object(binding.get("precondition"), "resourceBinding.precondition");
    exactFields(
        precondition,
        Set.of("kind", "valueHash"),
        Set.of("valueHash"),
        "resourceBinding.precondition");
    String kind = text(precondition, "kind", "resourceBinding.precondition.kind");
    require(
        PRECONDITIONS.contains(kind),
        "resourceBinding.precondition.kind",
        "contains an unsupported value");
    if ("none".equals(kind)) {
      require(
          !precondition.has("valueHash"),
          "resourceBinding.precondition.valueHash",
          "must be absent when kind is none");
    } else {
      hash(precondition, "valueHash", "resourceBinding.precondition.valueHash");
    }
    return binding.deepCopy();
  }

  private static void validatePolicyRelease(ObjectNode policy) {
    exactFields(policy, Set.of("policyId", "version", "contentHash"), Set.of(), "policyRelease");
    identifier(policy, "policyId", "policyRelease.policyId");
    positiveInteger(policy, "version", "policyRelease.version");
    hash(policy, "contentHash", "policyRelease.contentHash");
  }

  private static void validateArtifactDigests(ArrayNode artifacts) {
    require(!artifacts.isEmpty(), "artifactDigests", "requires at least one item");
    Set<String> kinds = new HashSet<>();
    for (int index = 0; index < artifacts.size(); index++) {
      String path = "artifactDigests[" + index + "]";
      ObjectNode artifact = object(artifacts.get(index), path);
      exactFields(artifact, Set.of("kind", "digest"), Set.of(), path);
      String kind = identifier(artifact, "kind", path + ".kind");
      require(kinds.add(kind), path + ".kind", "must be unique");
      hash(artifact, "digest", path + ".digest");
    }
  }

  private static void validateRetention(ObjectNode retention, Instant createdAt) {
    exactFields(retention, Set.of("policyId", "retainUntil"), Set.of(), "retention");
    identifier(retention, "policyId", "retention.policyId");
    Instant retainUntil = instant(retention, "retainUntil", "retention.retainUntil");
    require(retainUntil.isAfter(createdAt), "retention.retainUntil", "must be after createdAt");
  }

  private static Snapshot validateSnapshot(ObjectNode node, Dimension dimension, String path) {
    exactFields(node, Set.of("status", "version", "updatedAt"), Set.of(), path);
    String status = text(node, "status", path + ".status");
    require(STATES.get(dimension).contains(status), path + ".status", "contains an unsupported value");
    long version = positiveInteger(node, "version", path + ".version");
    Instant updatedAt = instant(node, "updatedAt", path + ".updatedAt");
    return new Snapshot(status, version, updatedAt);
  }

  private static void validateTransitions(
      ArrayNode transitions,
      EnumMap<Dimension, Snapshot> snapshots,
      Instant createdAt,
      Instant updatedAt) {
    require(!transitions.isEmpty(), "transitions", "requires at least one item");
    EnumMap<Dimension, String> current = new EnumMap<>(Dimension.class);
    EnumMap<Dimension, Long> versions = new EnumMap<>(Dimension.class);
    EnumMap<Dimension, Instant> lastAt = new EnumMap<>(Dimension.class);
    Instant previousAt = null;
    for (int index = 0; index < transitions.size(); index++) {
      String path = "transitions[" + index + "]";
      ObjectNode transition = object(transitions.get(index), path);
      exactFields(
          transition,
          Set.of("sequence", "dimension", "from", "to", "version", "at", "reasonCode"),
          Set.of("from"),
          path);
      long sequence = positiveInteger(transition, "sequence", path + ".sequence");
      require(sequence == index + 1L, path + ".sequence", "must be globally contiguous from 1");
      Dimension dimension = dimension(transition, path + ".dimension");
      String to = text(transition, "to", path + ".to");
      require(STATES.get(dimension).contains(to), path + ".to", "contains an unsupported value");
      long version = positiveInteger(transition, "version", path + ".version");
      long expectedVersion = versions.getOrDefault(dimension, 0L) + 1L;
      require(version == expectedVersion, path + ".version", "must be contiguous within its dimension");
      String before = current.get(dimension);
      if (before == null) {
        require(!transition.has("from"), path + ".from", "must be absent on the first dimension transition");
        require(initialState(dimension).equals(to), path + ".to", "contains an invalid initial state");
      } else {
        require(transition.has("from"), path + ".from", "is required after the initial dimension transition");
        String from = text(transition, "from", path + ".from");
        require(from.equals(before), path + ".from", "must match the previous dimension state");
        require(
            TRANSITIONS.get(dimension).getOrDefault(from, Set.of()).contains(to),
            path + ".to",
            "contains an invalid state transition");
      }
      Instant at = instant(transition, "at", path + ".at");
      require(!at.isBefore(createdAt), path + ".at", "must not be before createdAt");
      require(!at.isAfter(updatedAt), path + ".at", "must not be after updatedAt");
      if (previousAt != null) {
        require(!at.isBefore(previousAt), path + ".at", "must be globally nondecreasing");
      }
      String reason = text(transition, "reasonCode", path + ".reasonCode");
      require(REASON.matcher(reason).matches(), path + ".reasonCode", "must be a stable uppercase reason code");
      current.put(dimension, to);
      versions.put(dimension, version);
      lastAt.put(dimension, at);
      previousAt = at;
    }
    require(
        instant(object(transitions.get(0), "transitions[0]"), "at", "transitions[0].at")
            .equals(createdAt),
        "createdAt",
        "must equal the first transition timestamp");
    require(previousAt != null && previousAt.equals(updatedAt), "updatedAt", "must equal the last transition timestamp");
    for (Dimension dimension : Dimension.values()) {
      String path = dimension.jsonName;
      require(current.containsKey(dimension), "transitions", "must initialize the " + path + " dimension");
      Snapshot snapshot = snapshots.get(dimension);
      require(snapshot.status.equals(current.get(dimension)), path + ".status", "must match transition history");
      require(snapshot.version == versions.get(dimension), path + ".version", "must match transition history");
      require(snapshot.updatedAt.equals(lastAt.get(dimension)), path + ".updatedAt", "must match transition history");
    }
  }

  private static Dimension dimension(ObjectNode transition, String path) {
    String value = text(transition, "dimension", path);
    for (Dimension dimension : Dimension.values()) {
      if (dimension.jsonName.equals(value)) return dimension;
    }
    throw violation(path, "contains an unsupported value");
  }

  private static String initialState(Dimension dimension) {
    return switch (dimension) {
      case AUTHORIZATION -> "evaluating";
      case EXECUTION -> "not_started";
      case EVIDENCE -> "pending";
    };
  }

  private static Map<Dimension, Map<String, Set<String>>> transitions() {
    EnumMap<Dimension, Map<String, Set<String>>> result = new EnumMap<>(Dimension.class);
    Map<String, Set<String>> authorization = new HashMap<>();
    authorization.put("evaluating", Set.of("denied", "awaiting_approval", "approved", "cancelled"));
    authorization.put("awaiting_approval", Set.of("approved", "denied", "expired", "cancelled"));
    authorization.put("approved", Set.of("expired", "revoked", "cancelled"));
    result.put(Dimension.AUTHORIZATION, Map.copyOf(authorization));
    Map<String, Set<String>> execution = new HashMap<>();
    execution.put("not_started", Set.of("claimed"));
    execution.put("claimed", Set.of("executing", "unknown"));
    execution.put("executing", Set.of("succeeded", "failed", "unknown"));
    execution.put("unknown", Set.of("succeeded", "failed"));
    result.put(Dimension.EXECUTION, Map.copyOf(execution));
    Map<String, Set<String>> evidence = new HashMap<>();
    evidence.put("pending", Set.of("leased"));
    evidence.put("leased", Set.of("pending", "published", "delivery_unknown"));
    evidence.put("delivery_unknown", Set.of("leased", "published"));
    result.put(Dimension.EVIDENCE, Map.copyOf(evidence));
    return Map.copyOf(result);
  }

  private static ObjectNode object(JsonNode value, String path) {
    if (value == null || !value.isObject()) throw violation(path, "must be an object");
    return (ObjectNode) value;
  }

  private static ArrayNode array(JsonNode value, String path) {
    if (value == null || !value.isArray()) throw violation(path, "must be an array");
    return (ArrayNode) value;
  }

  private static String text(ObjectNode parent, String name, String path) {
    JsonNode value = parent.get(name);
    if (value == null || !value.isTextual() || value.textValue().isBlank()) {
      throw violation(path, "must be a non-empty string");
    }
    return value.textValue();
  }

  private static String identifier(ObjectNode parent, String name, String path) {
    String value = text(parent, name, path);
    require(IDENTIFIER.matcher(value).matches(), path, "contains an invalid identifier");
    return value;
  }

  private static String partition(ObjectNode parent, String name, String path) {
    String value = text(parent, name, path);
    require(PARTITION.matcher(value).matches(), path, "contains an invalid partition identifier");
    return value;
  }

  private static String hash(ObjectNode parent, String name, String path) {
    String value = text(parent, name, path);
    require(HASH.matcher(value).matches(), path, "must be a SHA-256 fingerprint");
    return value;
  }

  private static void requireHashBinding(
      ObjectNode parent, String name, String expected, String message) {
    require(hash(parent, name, name).equals(expected), name, message);
  }

  private static String uuid(ObjectNode parent, String name, String path) {
    String value = text(parent, name, path);
    require(UUID.matcher(value).matches(), path, "must be a UUID");
    return value;
  }

  private static long positiveInteger(ObjectNode parent, String name, String path) {
    JsonNode value = parent.get(name);
    require(
        value != null && value.isIntegralNumber() && value.canConvertToLong(),
        path,
        "must be an integer");
    long number = value.longValue();
    require(number > 0 && number <= MAX_SAFE_INTEGER, path, "must be a positive safe integer");
    return number;
  }

  private static Instant instant(ObjectNode parent, String name, String path) {
    String value = text(parent, name, path);
    try {
      if (!ISO_INSTANT.matcher(value).matches()) {
        throw new DateTimeParseException("not a strict UTC instant", value, 0);
      }
      return Instant.parse(value);
    } catch (DateTimeParseException exception) {
      throw violation(path, "must be an ISO-8601 UTC timestamp");
    }
  }

  private static void exactFields(
      ObjectNode object, Set<String> allowed, Set<String> optional, String path) {
    Set<String> actual = new HashSet<>();
    object.fieldNames().forEachRemaining(actual::add);
    Set<String> unsupported = new HashSet<>(actual);
    unsupported.removeAll(allowed);
    require(unsupported.isEmpty(), path, "contains unsupported fields");
    Set<String> required = new HashSet<>(allowed);
    required.removeAll(optional);
    require(actual.containsAll(required), path, "is missing required fields");
  }

  private static void require(boolean condition, String path, String message) {
    if (!condition) throw violation(path, message);
  }

  private static ContractViolation violation(String path, String message) {
    return new ContractViolation(path, message);
  }

  private enum Dimension {
    AUTHORIZATION("authorization"),
    EXECUTION("execution"),
    EVIDENCE("evidence");

    private final String jsonName;

    Dimension(String jsonName) {
      this.jsonName = jsonName;
    }
  }

  private record Snapshot(String status, long version, Instant updatedAt) {}
}
