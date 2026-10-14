package io.tracepermit.identity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.tracepermit.contracts.ActionValidator;
import io.tracepermit.contracts.JsonSupport;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Trusted boundary that verifies credentials before constructing a canonical scheduled action. */
public final class ScheduledActionAdapter {
  private static final Set<String> REQUIRED_INPUT_FIELDS =
      Set.of("schemaVersion", "action", "clientClaims");
  private static final Set<String> ALLOWED_INPUT_FIELDS =
      Set.of("schemaVersion", "declaredIntent", "action", "clientClaims");
  private static final Set<String> ACTION_FIELDS =
      Set.of("protocol", "toolName", "arguments", "target");
  private static final Set<String> REQUIRED_TARGET_FIELDS = Set.of("system", "environment");
  private static final Set<String> TARGET_FIELDS =
      Set.of("system", "environment", "accountId", "resourceType");
  private static final Set<String> INTENT_FIELDS = Set.of("goal", "source");
  private static final Set<String> PROTOCOLS = Set.of("mcp", "http", "sdk", "cli");
  private static final Set<String> ENVIRONMENTS = Set.of("dev", "test", "staging", "prod");
  private static final Set<String> INTENT_SOURCES = Set.of("prompt", "api_field", "job_definition");

  private final ScheduledWorkloadVerifier verifier;

  public ScheduledActionAdapter(ScheduledWorkloadVerifier verifier) {
    this.verifier = Objects.requireNonNull(verifier);
  }

  public JsonNode verifyAndNormalize(
      JsonNode input,
      String workloadCredential,
      List<String> delegationCredentials,
      TrustedScheduledActionContext context) {
    if (input == null || !input.isObject()) throw invalidInput();
    Set<String> fields = new HashSet<>();
    input.fieldNames().forEachRemaining(fields::add);
    if (!fields.containsAll(REQUIRED_INPUT_FIELDS)
        || !ALLOWED_INPUT_FIELDS.containsAll(fields)
        || !"1.0".equals(input.path("schemaVersion").asText())
        || !input.path("action").isObject()
        || !input.path("clientClaims").isObject()
        || !validAction(input.path("action"))) {
      throw invalidInput();
    }
    if (input.has("declaredIntent") && !validIntent(input.path("declaredIntent"))) {
      throw invalidInput();
    }

    JsonNode action = input.path("action").deepCopy();
    VerifiedScheduledWorkload verified =
        verifier.verify(workloadCredential, delegationCredentials, action, context);

    ObjectNode canonical = JsonSupport.MAPPER.createObjectNode();
    canonical.put("schemaVersion", "1.0");
    canonical.put("eventId", context.eventId());
    canonical.put("observedAt", context.observedAt().toString());
    canonical.put("traceId", context.traceId());

    ObjectNode identity = canonical.putObject("identity");
    ObjectNode subject = identity.putObject("subject");
    subject.put("id", verified.subjectId());
    subject.put("type", "workload");
    subject.put("organizationId", verified.tenantId());
    subject.put("authenticationMethod", "workload_token");
    subject.put("assurance", "authenticated");
    ArrayNode evidence = identity.putArray("evidence");
    ObjectNode workloadEvidence = evidence.addObject();
    workloadEvidence.put(
        "evidenceId",
        "workload:" + verified.credentialFingerprint().substring("sha256:".length(), 23));
    workloadEvidence.put("subjectId", verified.subjectId());
    workloadEvidence.put("kind", "workload_token");
    workloadEvidence.put("assurance", "authenticated");
    workloadEvidence.put("issuer", verified.issuer());
    workloadEvidence.put("verifiedAt", verified.verifiedAt().toString());
    workloadEvidence.put("credentialFingerprint", verified.credentialFingerprint());

    ObjectNode trigger = canonical.putObject("trigger");
    trigger.put("kind", "scheduled");
    trigger.put("origin", "scheduler");
    trigger.put("requestId", context.runId());
    trigger.put("clientName", context.schedulerId());
    trigger.put("scheduleId", context.scheduleId());

    if (input.has("declaredIntent")) {
      canonical.set("declaredIntent", input.path("declaredIntent").deepCopy());
    }
    canonical.set("action", action);

    ObjectNode lineage = canonical.putObject("lineage");
    lineage.put("rootPrincipalId", verified.subjectId());
    ArrayNode hops = lineage.putArray("hops");
    ObjectNode rootHop = hops.addObject();
    rootHop.put("hopId", "hop-workload-root");
    rootHop.put("kind", "principal");
    rootHop.put("subjectId", verified.subjectId());
    rootHop.put("assurance", "authenticated");
    rootHop.put("source", "signed-workload:" + verified.issuer());
    rootHop.put("observedAt", context.observedAt().toString());

    String parentHopId = "hop-workload-root";
    ArrayNode grants = lineage.putArray("delegations");
    for (int index = 0; index < verified.delegations().size(); index++) {
      VerifiedScheduledWorkload.VerifiedDelegationGrant grant =
          verified.delegations().get(index);
      String hopId = "hop-delegated-" + (index + 1);
      ObjectNode hop = hops.addObject();
      hop.put("hopId", hopId);
      hop.put("parentHopId", parentHopId);
      hop.put(
          "kind", index == verified.delegations().size() - 1 ? "tool" : "agent");
      hop.put("subjectId", grant.subjectId());
      hop.put("assurance", "delegated");
      hop.put("source", "signed-delegation:" + grant.issuer());
      hop.put("observedAt", context.observedAt().toString());
      hop.put("delegationTokenId", grant.tokenId());
      parentHopId = hopId;

      ObjectNode canonicalGrant = grants.addObject();
      canonicalGrant.put("tokenId", grant.tokenId());
      if (grant.parentTokenId() != null) {
        canonicalGrant.put("parentTokenId", grant.parentTokenId());
      }
      canonicalGrant.put("issuer", grant.issuer());
      canonicalGrant.put("subjectId", grant.subjectId());
      canonicalGrant.put("actorId", grant.actorId());
      canonicalGrant.put("audience", grant.audience());
      ArrayNode scopes = canonicalGrant.putArray("scopes");
      grant.scopes().forEach(scopes::add);
      ArrayNode resources = canonicalGrant.putArray("resources");
      grant.resources().forEach(resources::add);
      canonicalGrant.put("purpose", grant.purpose());
      canonicalGrant.put("traceId", grant.traceId());
      canonicalGrant.put("issuedAt", grant.issuedAt().toString());
      canonicalGrant.put("expiresAt", grant.expiresAt().toString());
      canonicalGrant.put("tokenHash", grant.tokenHash());
      canonicalGrant.put("verification", "verified");
    }

    canonical.set("clientClaims", JsonSupport.MAPPER.createObjectNode());
    return ActionValidator.validate(canonical);
  }

  private static WorkloadCredentialException invalidInput() {
    return new WorkloadCredentialException(
        "INVALID_SCHEDULED_ACTION", "The scheduled action input is invalid.");
  }

  private static boolean validAction(JsonNode action) {
    if (!exactFields(action, ACTION_FIELDS)
        || !PROTOCOLS.contains(action.path("protocol").asText())
        || !nonEmptyText(action.path("toolName"))
        || !action.path("arguments").isObject()) {
      return false;
    }
    JsonNode target = action.path("target");
    Set<String> targetFields = fields(target);
    return target.isObject()
        && targetFields.containsAll(REQUIRED_TARGET_FIELDS)
        && TARGET_FIELDS.containsAll(targetFields)
        && nonEmptyText(target.path("system"))
        && ENVIRONMENTS.contains(target.path("environment").asText())
        && optionalText(target, "accountId")
        && optionalText(target, "resourceType");
  }

  private static boolean validIntent(JsonNode intent) {
    return exactFields(intent, INTENT_FIELDS)
        && nonEmptyText(intent.path("goal"))
        && INTENT_SOURCES.contains(intent.path("source").asText());
  }

  private static boolean exactFields(JsonNode value, Set<String> expected) {
    return value.isObject() && fields(value).equals(expected);
  }

  private static Set<String> fields(JsonNode value) {
    Set<String> result = new HashSet<>();
    value.fieldNames().forEachRemaining(result::add);
    return result;
  }

  private static boolean nonEmptyText(JsonNode value) {
    return value.isTextual() && !value.textValue().isBlank();
  }

  private static boolean optionalText(JsonNode parent, String field) {
    return !parent.has(field) || nonEmptyText(parent.path(field));
  }
}
