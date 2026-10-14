package io.tracepermit.contracts;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

public final class ActionValidator {
  private static final Pattern TRACE_ID = Pattern.compile("^[a-f0-9]{32}$");
  private static final Pattern HASH = Pattern.compile("^sha256:[a-f0-9]{64}$");
  private static final Pattern UUID = Pattern.compile("^([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-8][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}|00000000-0000-0000-0000-000000000000|ffffffff-ffff-ffff-ffff-ffffffffffff)$");
  private static final Pattern ISO_INSTANT = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}T(?:[01]\\d|2[0-3]):[0-5]\\d:[0-5]\\d(?:\\.\\d+)?Z$");

  private ActionValidator() {}

  public static JsonNode validate(JsonNode event) {
    event = ActionNormalizer.normalize(event);
    require("1.0".equals(text(event, "schemaVersion")), "schemaVersion", "must equal 1.0");
    uuid(text(event, "eventId"), "eventId");
    instant(text(event, "observedAt"), "observedAt");
    require(TRACE_ID.matcher(text(event, "traceId")).matches(), "traceId", "must be 32 lowercase hexadecimal characters");

    JsonNode identity = object(event, "identity");
    JsonNode subject = object(identity, "subject");
    JsonNode evidence = array(identity, "evidence");
    require(!evidence.isEmpty(), "identity.evidence", "requires at least one item");
    for (int index = 0; index < evidence.size(); index++) {
      JsonNode item = evidence.get(index);
      if (!"asserted".equals(text(item, "assurance"))) {
        require(item.hasNonNull("verifiedAt"), "identity.evidence[" + index + "].verifiedAt", "authenticated or delegated evidence requires verifiedAt");
      }
      if (item.hasNonNull("verifiedAt")) instant(text(item, "verifiedAt"), "identity.evidence[" + index + "].verifiedAt");
      if (item.hasNonNull("credentialFingerprint")) {
        require(HASH.matcher(text(item, "credentialFingerprint")).matches(), "identity.evidence[" + index + "].credentialFingerprint", "must be a SHA-256 fingerprint");
      }
    }
    String subjectId = nonEmpty(subject, "id", "identity.subject.id");
    require(subjectId.equals(text(object(event, "lineage"), "rootPrincipalId")), "lineage.rootPrincipalId", "rootPrincipalId must match the identity subject");

    JsonNode trigger = object(event, "trigger");
    if ("human".equals(text(trigger, "kind"))) {
      require("human".equals(text(subject, "type")), "trigger.kind", "a human trigger requires a human subject");
    }
    if ("scheduled".equals(text(trigger, "kind"))) {
      require(trigger.hasNonNull("scheduleId") && !text(trigger, "scheduleId").isBlank(), "trigger.scheduleId", "a scheduled trigger requires scheduleId");
      require("workload".equals(text(subject, "type")), "identity.subject.type", "a scheduled trigger must execute as a workload subject");
    }

    requireEvidence(subject, evidence, "identity.subject.assurance");
    if (identity.has("actor")) requireEvidence(object(identity, "actor"), evidence, "identity.actor.assurance");
    if (identity.has("createdBy")) requireEvidence(object(identity, "createdBy"), evidence, "identity.createdBy.assurance");
    if (event.has("source")) validateSource(object(event, "source"));
    validateLineage(event, subjectId);
    object(event, "action");
    return event.deepCopy();
  }

  private static void validateSource(JsonNode source) {
    require(HASH.matcher(text(source, "keyFingerprint")).matches(), "source.keyFingerprint", "must be a SHA-256 fingerprint");
    Instant verifiedAt = instant(text(source, "verifiedAt"), "source.verifiedAt");
    JsonNode device = object(source, "device");
    require(text(device, "deviceId").length() <= 128, "source.device.deviceId", "must contain at most 128 characters");
    JsonNode process = object(source, "process");
    require(text(process, "executableName").length() <= 128, "source.process.executableName", "must contain at most 128 characters");
    require(text(process, "parentExecutableName").length() <= 128, "source.process.parentExecutableName", "must contain at most 128 characters");
    require(HASH.matcher(text(process, "executableHash")).matches(), "source.process.executableHash", "must be a SHA-256 fingerprint");
    Instant startedAt = instant(text(process, "startedAt"), "source.process.startedAt");
    require(!startedAt.isAfter(verifiedAt), "source.process.startedAt", "must not be after source verification");
    object(source, "triggerEvidence");
  }

  private static void validateLineage(JsonNode event, String subjectId) {
    JsonNode lineage = object(event, "lineage");
    JsonNode hops = array(lineage, "hops");
    require(!hops.isEmpty(), "lineage.hops", "requires at least one hop");
    Map<String, JsonNode> hopById = new HashMap<>();
    int roots = 0;
    String rootSubject = null;
    for (int index = 0; index < hops.size(); index++) {
      JsonNode hop = hops.get(index);
      String id = nonEmpty(hop, "hopId", "lineage.hops[" + index + "].hopId");
      require(hopById.put(id, hop) == null, "lineage.hops[" + index + "].hopId", "lineage hop IDs must be unique");
      instant(text(hop, "observedAt"), "lineage.hops[" + index + "].observedAt");
      if (!hop.hasNonNull("parentHopId")) {
        roots++;
        rootSubject = text(hop, "subjectId");
      }
    }
    require(roots == 1 && subjectId.equals(rootSubject), "lineage.hops", "causal lineage requires exactly one root hop for rootPrincipalId");
    for (int index = 0; index < hops.size(); index++) {
      JsonNode hop = hops.get(index);
      if (hop.hasNonNull("parentHopId")) {
        require(hopById.containsKey(text(hop, "parentHopId")), "lineage.hops[" + index + "].parentHopId", "must reference an existing lineage hop");
      }
      detectHopCycle(hop, hopById, index);
    }

    JsonNode grants = lineage.has("delegations") ? array(lineage, "delegations") : JsonSupport.MAPPER.createArrayNode();
    Map<String, JsonNode> grantById = new HashMap<>();
    int grantRoots = 0;
    for (int index = 0; index < grants.size(); index++) {
      JsonNode grant = grants.get(index);
      String tokenId = nonEmpty(grant, "tokenId", "lineage.delegations[" + index + "].tokenId");
      require(grantById.put(tokenId, grant) == null, "lineage.delegations[" + index + "].tokenId", "delegation token IDs must be unique");
      require(text(event, "traceId").equals(text(grant, "traceId")), "lineage.delegations[" + index + "].traceId", "must match action traceId");
      if (!grant.hasNonNull("parentTokenId")) grantRoots++;
    }
    require(grants.isEmpty() || grantRoots == 1, "lineage.delegations", "delegation chain requires exactly one root grant");
    Instant observed = Instant.parse(text(event, "observedAt"));
    for (int index = 0; index < grants.size(); index++) validateGrant(grants.get(index), index, grantById, observed, subjectId);
    for (int index = 0; index < hops.size(); index++) {
      JsonNode hop = hops.get(index);
      if ("delegated".equals(text(hop, "assurance"))) {
        require(hop.hasNonNull("delegationTokenId") && grantById.containsKey(text(hop, "delegationTokenId")), "lineage.hops[" + index + "].delegationTokenId", "delegated lineage hop requires a known delegation token");
      }
    }
  }

  private static void validateGrant(JsonNode grant, int index, Map<String, JsonNode> grants, Instant observed, String rootSubject) {
    String base = "lineage.delegations[" + index + "]";
    require("verified".equals(text(grant, "verification")), base + ".verification", "unverified delegation grants cannot establish authority");
    require(grant.hasNonNull("tokenHash") && HASH.matcher(text(grant, "tokenHash")).matches(), base + ".tokenHash", "verified delegation requires a non-secret token fingerprint");
    Instant issued = instant(text(grant, "issuedAt"), base + ".issuedAt");
    Instant expires = instant(text(grant, "expiresAt"), base + ".expiresAt");
    require(expires.isAfter(issued), base + ".expiresAt", "delegation must expire after it is issued");
    require(!observed.isBefore(issued) && observed.isBefore(expires), base + ".expiresAt", "delegation must be valid when the action is observed");
    if (!grant.hasNonNull("parentTokenId")) {
      require(rootSubject.equals(text(grant, "actorId")), base + ".actorId", "root delegation must be issued on behalf of rootPrincipalId");
      return;
    }
    JsonNode parent = grants.get(text(grant, "parentTokenId"));
    require(parent != null, base + ".parentTokenId", "parentTokenId must reference an existing delegation");
    require(text(grant, "actorId").equals(text(parent, "subjectId")), base + ".actorId", "child delegation actor must be the parent subject");
    require(subset(array(grant, "scopes"), array(parent, "scopes")), base + ".scopes", "child delegation cannot amplify parent scopes");
    require(subset(array(grant, "resources"), array(parent, "resources")), base + ".resources", "child delegation cannot broaden parent resources");
    require(text(grant, "audience").equals(text(parent, "audience")), base + ".audience", "child delegation cannot change parent audience");
    require(!issued.isBefore(Instant.parse(text(parent, "issuedAt"))), base + ".issuedAt", "child delegation cannot predate its parent");
    require(!expires.isAfter(Instant.parse(text(parent, "expiresAt"))), base + ".expiresAt", "child delegation cannot outlive its parent");
    Set<String> seen = new HashSet<>();
    JsonNode cursor = grant;
    while (cursor != null) {
      String id = text(cursor, "tokenId");
      require(seen.add(id), base + ".parentTokenId", "delegation chain must not contain a cycle");
      cursor = cursor.hasNonNull("parentTokenId") ? grants.get(text(cursor, "parentTokenId")) : null;
    }
  }

  private static void detectHopCycle(JsonNode hop, Map<String, JsonNode> hops, int index) {
    Set<String> seen = new HashSet<>();
    JsonNode cursor = hop;
    while (cursor != null) {
      String id = text(cursor, "hopId");
      require(seen.add(id), "lineage.hops[" + index + "].parentHopId", "causal lineage must not contain a cycle");
      cursor = cursor.hasNonNull("parentHopId") ? hops.get(text(cursor, "parentHopId")) : null;
    }
  }

  private static void requireEvidence(JsonNode entity, JsonNode evidence, String path) {
    if ("asserted".equals(text(entity, "assurance"))) return;
    String id = text(entity, "id");
    for (JsonNode item : evidence) {
      if (id.equals(text(item, "subjectId")) && !"asserted".equals(text(item, "assurance")) && item.hasNonNull("verifiedAt")) return;
    }
    throw new ContractViolation(path, text(entity, "assurance") + " identity requires matching verified evidence");
  }

  private static boolean subset(JsonNode child, JsonNode parent) {
    Set<String> values = new HashSet<>();
    parent.forEach(item -> values.add(item.textValue()));
    for (JsonNode item : child) if (!values.contains(item.textValue())) return false;
    return true;
  }

  private static JsonNode object(JsonNode parent, String name) {
    JsonNode value = parent == null ? null : parent.get(name);
    require(value != null && value.isObject(), name, "must be an object");
    return value;
  }

  private static JsonNode array(JsonNode parent, String name) {
    JsonNode value = parent == null ? null : parent.get(name);
    require(value != null && value.isArray(), name, "must be an array");
    return value;
  }

  private static String nonEmpty(JsonNode parent, String name, String path) {
    String value = text(parent, name);
    require(!value.isBlank(), path, "must not be empty");
    return value;
  }

  private static String text(JsonNode parent, String name) {
    JsonNode value = parent == null ? null : parent.get(name);
    return value != null && value.isTextual() ? value.textValue() : "";
  }

  private static Instant instant(String value, String path) {
    try {
      if (!ISO_INSTANT.matcher(value).matches()) throw new DateTimeParseException("not a strict UTC instant", value, 0);
      return Instant.parse(value);
    } catch (DateTimeParseException exception) {
      throw new ContractViolation(path, "must be an ISO-8601 timestamp");
    }
  }

  private static void uuid(String value, String path) {
    require(UUID.matcher(value).matches(), path, "must be a UUID");
  }

  private static void require(boolean condition, String path, String message) {
    if (!condition) throw new ContractViolation(path, message);
  }
}
