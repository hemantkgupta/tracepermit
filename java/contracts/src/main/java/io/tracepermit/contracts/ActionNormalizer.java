package io.tracepermit.contracts;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Set;

final class ActionNormalizer {
  private static final Set<String> PRINCIPAL_TYPES = Set.of("human", "workload");
  private static final Set<String> ACTOR_TYPES = Set.of("human", "workload", "orchestrator", "agent", "tool");
  private static final Set<String> ASSURANCE = Set.of("asserted", "authenticated", "delegated");
  private static final Set<String> AUTHENTICATION = Set.of("oauth", "oidc", "mtls", "api_key", "os", "workload_token", "client_metadata", "unknown");
  private static final Set<String> EVIDENCE_KINDS = Set.of("oauth_token", "oidc_token", "mtls_certificate", "api_key", "os_session", "workload_token", "client_metadata");
  private static final Set<String> TRIGGERS = Set.of("human", "service", "scheduled");
  private static final Set<String> ORIGINS = Set.of("chatgpt-web", "claude-web", "cli", "api", "scheduler");
  private static final Set<String> PROTOCOLS = Set.of("mcp", "http", "sdk", "cli");
  private static final Set<String> ENVIRONMENTS = Set.of("dev", "test", "staging", "prod");
  private static final Set<String> HOP_KINDS = Set.of("principal", "agent", "orchestrator", "gateway", "tool");
  private static final Set<String> SOURCE_PLATFORMS = Set.of("macos", "linux", "windows");
  private static final Set<String> SOURCE_ASSURANCE = Set.of("development", "managed", "hardware_backed");
  private static final Set<String> SOURCE_MODES = Set.of("interactive", "automation", "unknown");

  private ActionNormalizer() {}

  static JsonNode normalize(JsonNode input) {
    ObjectNode source = object(input, "action");
    ObjectNode result = JsonSupport.MAPPER.createObjectNode();
    requiredText(source, result, "schemaVersion", "schemaVersion");
    requiredText(source, result, "eventId", "eventId");
    requiredText(source, result, "observedAt", "observedAt");
    requiredText(source, result, "traceId", "traceId");

    ObjectNode identity = object(source.get("identity"), "identity");
    ObjectNode normalizedIdentity = JsonSupport.MAPPER.createObjectNode();
    normalizedIdentity.set("subject", entity(identity.get("subject"), "identity.subject", PRINCIPAL_TYPES));
    if (identity.has("actor")) normalizedIdentity.set("actor", entity(identity.get("actor"), "identity.actor", ACTOR_TYPES));
    if (identity.has("createdBy")) normalizedIdentity.set("createdBy", entity(identity.get("createdBy"), "identity.createdBy", PRINCIPAL_TYPES));
    ArrayNode evidence = array(identity.get("evidence"), "identity.evidence");
    ArrayNode normalizedEvidence = JsonSupport.MAPPER.createArrayNode();
    for (int index = 0; index < evidence.size(); index++) normalizedEvidence.add(evidence(evidence.get(index), "identity.evidence[" + index + "]"));
    normalizedIdentity.set("evidence", normalizedEvidence);
    result.set("identity", normalizedIdentity);

    result.set("trigger", trigger(source.get("trigger")));
    if (source.has("declaredIntent")) result.set("declaredIntent", declaredIntent(source.get("declaredIntent")));
    result.set("action", action(source.get("action")));
    if (source.has("source")) result.set("source", sourceEvidence(source.get("source")));
    result.set("lineage", lineage(source.get("lineage")));
    JsonNode clientClaims = source.get("clientClaims");
    result.set("clientClaims", clientClaims == null ? JsonSupport.MAPPER.createObjectNode() : object(clientClaims, "clientClaims").deepCopy());
    return result;
  }

  private static ObjectNode entity(JsonNode input, String path, Set<String> types) {
    ObjectNode source = object(input, path);
    ObjectNode result = JsonSupport.MAPPER.createObjectNode();
    requiredText(source, result, "id", path + ".id");
    optionalText(source, result, "displayName", path + ".displayName");
    optionalText(source, result, "organizationId", path + ".organizationId");
    enumText(source, result, "authenticationMethod", path + ".authenticationMethod", AUTHENTICATION);
    enumText(source, result, "assurance", path + ".assurance", ASSURANCE);
    enumText(source, result, "type", path + ".type", types);
    return result;
  }

  private static ObjectNode evidence(JsonNode input, String path) {
    ObjectNode source = object(input, path);
    ObjectNode result = JsonSupport.MAPPER.createObjectNode();
    requiredText(source, result, "evidenceId", path + ".evidenceId");
    requiredText(source, result, "subjectId", path + ".subjectId");
    enumText(source, result, "kind", path + ".kind", EVIDENCE_KINDS);
    enumText(source, result, "assurance", path + ".assurance", ASSURANCE);
    requiredText(source, result, "issuer", path + ".issuer");
    optionalText(source, result, "verifiedAt", path + ".verifiedAt");
    optionalText(source, result, "credentialFingerprint", path + ".credentialFingerprint");
    return result;
  }

  private static ObjectNode trigger(JsonNode input) {
    ObjectNode source = object(input, "trigger");
    ObjectNode result = JsonSupport.MAPPER.createObjectNode();
    enumText(source, result, "kind", "trigger.kind", TRIGGERS);
    enumText(source, result, "origin", "trigger.origin", ORIGINS);
    requiredText(source, result, "requestId", "trigger.requestId");
    optionalText(source, result, "clientName", "trigger.clientName");
    optionalText(source, result, "clientVersion", "trigger.clientVersion");
    optionalText(source, result, "scheduleId", "trigger.scheduleId");
    return result;
  }

  private static ObjectNode declaredIntent(JsonNode input) {
    ObjectNode source = object(input, "declaredIntent");
    ObjectNode result = JsonSupport.MAPPER.createObjectNode();
    requiredText(source, result, "goal", "declaredIntent.goal");
    enumText(source, result, "source", "declaredIntent.source", Set.of("prompt", "api_field", "job_definition"));
    return result;
  }

  private static ObjectNode action(JsonNode input) {
    ObjectNode source = object(input, "action");
    ObjectNode result = JsonSupport.MAPPER.createObjectNode();
    enumText(source, result, "protocol", "action.protocol", PROTOCOLS);
    requiredText(source, result, "toolName", "action.toolName");
    result.set("arguments", object(source.get("arguments"), "action.arguments").deepCopy());
    ObjectNode target = object(source.get("target"), "action.target");
    ObjectNode normalizedTarget = JsonSupport.MAPPER.createObjectNode();
    requiredText(target, normalizedTarget, "system", "action.target.system");
    enumText(target, normalizedTarget, "environment", "action.target.environment", ENVIRONMENTS);
    optionalText(target, normalizedTarget, "accountId", "action.target.accountId");
    optionalText(target, normalizedTarget, "resourceType", "action.target.resourceType");
    result.set("target", normalizedTarget);
    return result;
  }

  private static ObjectNode sourceEvidence(JsonNode input) {
    ObjectNode source = object(input, "source");
    exactFields(source, Set.of("issuer", "keyId", "keyFingerprint", "verifiedAt", "device", "process", "triggerEvidence"), "source");
    ObjectNode result = JsonSupport.MAPPER.createObjectNode();
    for (String field : new String[] {"issuer", "keyId", "keyFingerprint", "verifiedAt"}) {
      requiredText(source, result, field, "source." + field);
    }

    ObjectNode device = object(source.get("device"), "source.device");
    exactFields(device, Set.of("deviceId", "platform", "assurance"), "source.device");
    ObjectNode normalizedDevice = JsonSupport.MAPPER.createObjectNode();
    requiredText(device, normalizedDevice, "deviceId", "source.device.deviceId");
    enumText(device, normalizedDevice, "platform", "source.device.platform", SOURCE_PLATFORMS);
    enumText(device, normalizedDevice, "assurance", "source.device.assurance", SOURCE_ASSURANCE);
    result.set("device", normalizedDevice);

    ObjectNode process = object(source.get("process"), "source.process");
    exactFields(process, Set.of("executableName", "executableHash", "parentExecutableName", "startedAt"), "source.process");
    ObjectNode normalizedProcess = JsonSupport.MAPPER.createObjectNode();
    for (String field : new String[] {"executableName", "executableHash", "parentExecutableName", "startedAt"}) {
      requiredText(process, normalizedProcess, field, "source.process." + field);
    }
    result.set("process", normalizedProcess);

    ObjectNode trigger = object(source.get("triggerEvidence"), "source.triggerEvidence");
    exactFields(trigger, Set.of("requestedMode", "terminalAttached"), "source.triggerEvidence");
    ObjectNode normalizedTrigger = JsonSupport.MAPPER.createObjectNode();
    enumText(trigger, normalizedTrigger, "requestedMode", "source.triggerEvidence.requestedMode", SOURCE_MODES);
    JsonNode terminalAttached = trigger.get("terminalAttached");
    if (terminalAttached == null || !terminalAttached.isBoolean()) {
      throw violation("source.triggerEvidence.terminalAttached", "must be a boolean");
    }
    normalizedTrigger.put("terminalAttached", terminalAttached.booleanValue());
    result.set("triggerEvidence", normalizedTrigger);
    return result;
  }

  private static ObjectNode lineage(JsonNode input) {
    ObjectNode source = object(input, "lineage");
    ObjectNode result = JsonSupport.MAPPER.createObjectNode();
    requiredText(source, result, "rootPrincipalId", "lineage.rootPrincipalId");
    ArrayNode hops = array(source.get("hops"), "lineage.hops");
    ArrayNode normalizedHops = JsonSupport.MAPPER.createArrayNode();
    for (int index = 0; index < hops.size(); index++) {
      String path = "lineage.hops[" + index + "]";
      ObjectNode hop = object(hops.get(index), path);
      ObjectNode normalized = JsonSupport.MAPPER.createObjectNode();
      requiredText(hop, normalized, "hopId", path + ".hopId");
      optionalText(hop, normalized, "parentHopId", path + ".parentHopId");
      enumText(hop, normalized, "kind", path + ".kind", HOP_KINDS);
      requiredText(hop, normalized, "subjectId", path + ".subjectId");
      enumText(hop, normalized, "assurance", path + ".assurance", ASSURANCE);
      requiredText(hop, normalized, "source", path + ".source");
      requiredText(hop, normalized, "observedAt", path + ".observedAt");
      optionalText(hop, normalized, "delegationTokenId", path + ".delegationTokenId");
      normalizedHops.add(normalized);
    }
    result.set("hops", normalizedHops);
    JsonNode delegationsValue = source.get("delegations");
    ArrayNode delegations = delegationsValue == null ? JsonSupport.MAPPER.createArrayNode() : array(delegationsValue, "lineage.delegations");
    ArrayNode normalizedDelegations = JsonSupport.MAPPER.createArrayNode();
    for (int index = 0; index < delegations.size(); index++) normalizedDelegations.add(delegation(delegations.get(index), index));
    result.set("delegations", normalizedDelegations);
    return result;
  }

  private static ObjectNode delegation(JsonNode input, int index) {
    String path = "lineage.delegations[" + index + "]";
    ObjectNode source = object(input, path);
    ObjectNode result = JsonSupport.MAPPER.createObjectNode();
    requiredText(source, result, "tokenId", path + ".tokenId");
    optionalText(source, result, "parentTokenId", path + ".parentTokenId");
    for (String field : new String[] {"issuer", "subjectId", "actorId", "audience"}) requiredText(source, result, field, path + "." + field);
    result.set("scopes", stringArray(source.get("scopes"), path + ".scopes"));
    result.set("resources", stringArray(source.get("resources"), path + ".resources"));
    for (String field : new String[] {"purpose", "traceId", "issuedAt", "expiresAt"}) requiredText(source, result, field, path + "." + field);
    optionalText(source, result, "tokenHash", path + ".tokenHash");
    enumText(source, result, "verification", path + ".verification", Set.of("verified", "unverified"));
    return result;
  }

  private static ArrayNode stringArray(JsonNode input, String path) {
    ArrayNode source = array(input, path);
    if (source.isEmpty()) throw violation(path, "requires at least one item");
    ArrayNode result = JsonSupport.MAPPER.createArrayNode();
    for (int index = 0; index < source.size(); index++) {
      JsonNode value = source.get(index);
      if (!value.isTextual() || value.textValue().isBlank()) throw violation(path + "[" + index + "]", "must be a non-empty string");
      result.add(value.textValue());
    }
    return result;
  }

  private static void enumText(ObjectNode source, ObjectNode target, String field, String path, Set<String> allowed) {
    String value = required(source, field, path);
    if (!allowed.contains(value)) throw violation(path, "contains an unsupported value");
    target.put(field, value);
  }

  private static void requiredText(ObjectNode source, ObjectNode target, String field, String path) {
    target.put(field, required(source, field, path));
  }

  private static String required(ObjectNode source, String field, String path) {
    JsonNode value = source.get(field);
    if (value == null || !value.isTextual() || value.textValue().isBlank()) throw violation(path, "must be a non-empty string");
    return value.textValue();
  }

  private static void optionalText(ObjectNode source, ObjectNode target, String field, String path) {
    if (!source.has(field)) return;
    requiredText(source, target, field, path);
  }

  private static void exactFields(ObjectNode source, Set<String> expected, String path) {
    Set<String> actual = new java.util.HashSet<>();
    source.fieldNames().forEachRemaining(actual::add);
    if (!actual.equals(expected)) throw violation(path, "contains unsupported or missing fields");
  }

  private static ObjectNode object(JsonNode value, String path) {
    if (value == null || !value.isObject()) throw violation(path, "must be an object");
    return (ObjectNode) value;
  }

  private static ArrayNode array(JsonNode value, String path) {
    if (value == null || !value.isArray()) throw violation(path, "must be an array");
    return (ArrayNode) value;
  }

  private static ContractViolation violation(String path, String message) {
    return new ContractViolation(path, message);
  }
}
