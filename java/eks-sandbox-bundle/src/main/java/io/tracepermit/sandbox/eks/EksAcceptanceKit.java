package io.tracepermit.sandbox.eks;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.Duration;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/** Offline P1 preparation and assessment of supplied observations. Never an approval authority. */
public final class EksAcceptanceKit {
  public static final String PROFILE = "tracepermit-eks-organization-acceptance-v1";
  public static final String OPERATOR_PROFILE = "tracepermit-installed-github-org-synthetic-operator-v1";
  public static final String LAUNCHER = "/opt/organization/bin/tracepermit-synthetic-organization-operator";
  static final int PLAN_BYTES = 65_536;
  static final int SNAPSHOT_BYTES = 131_072;
  private static final Set<String> PLAN_FIELDS = Set.of("schemaVersion", "profile", "bundle",
      "contextFingerprint", "clusterUid", "operatorProfile", "organizationImage",
      "organizationArchiveSha256", "organizationCommand", "authorityProfileFingerprint",
      "recoveryGeneration", "maxObservationAgeSeconds", "evidenceNotBefore");
  private static final Set<String> COMMANDS = Set.of("check-config", "register", "inspect", "evaluate",
      "review", "deliver-synthetic", "reconcile");
  public static final List<String> CONTROLS = List.of("VERSION_COMPATIBILITY_REVIEW", "ADMISSION_RESTRICTED_POSITIVE",
      "ADMISSION_RESTRICTED_DENIAL", "AGENT_RBAC_DENIAL",
      "TRUSTED_RBAC_DENIAL", "NETWORK_POSITIVE_CONTROL", "NETWORK_METADATA_DENIAL",
      "NETWORK_KUBEAPI_DENIAL", "NETWORK_PROVIDER_DENIAL", "NETWORK_NODE_SOCKET_DENIAL",
      "NETWORK_STARTUP_DENIAL", "NETWORK_RESCHEDULE_DENIAL", "NETWORK_CONTROLLER_OUTAGE",
      "DNS_PROXY_REDIRECT_DENIAL", "PRIVATE_STDIN_PROVENANCE", "PRIVATE_INPUT_EOF",
      "PRIVATE_INPUT_LEAK_DENIAL", "ATTACH_AUDIT_CUSTODY", "ORG_IMAGE_LAUNCHER_BINDING",
      "PVC_STOPPED_WRITER_BINDING", "RUNTIME_GRANT_DENIAL", "FRESH_IDENTITY_DENIAL",
      "INDEPENDENT_REVIEW_DENIAL", "UNKNOWN_NO_RESEND", "STOP_FRESH_ADMISSION_DENIAL",
      "STOP_READ_ONLY_RECONCILIATION", "STOP_OWNED_CLEANUP", "STOP_AUDIT_ACCOUNTING");

  private EksAcceptanceKit() {}

  public record Plan(EksSandboxBundle.Configuration bundle, String contextFingerprint, String clusterUid,
      String organizationImage, String organizationArchiveSha256, String organizationCommand,
      String authorityProfileFingerprint, int recoveryGeneration, int maxObservationAgeSeconds, Instant evidenceNotBefore) {
    public Plan {
      require(bundle != null && "check-config".equals(bundle.value("command")));
      digest(contextFingerprint); digest(organizationArchiveSha256); digest(authorityProfileFingerprint);
      require(clusterUid != null && clusterUid.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
          && UUID.fromString(clusterUid).toString().equals(clusterUid)
          && !clusterUid.equals("00000000-0000-0000-0000-000000000000"));
      require(organizationCommand != null && COMMANDS.contains(organizationCommand));
      require(recoveryGeneration > 0 && maxObservationAgeSeconds >= 60 && maxObservationAgeSeconds <= 300
          && evidenceNotBefore != null);
      // Reuse the accepted digest-pinned image grammar instead of creating a second image parser.
      var imageCheck = new TreeMap<>(bundle.values());
      imageCheck.put("runtimeImage", organizationImage);
      new EksSandboxBundle.Configuration(imageCheck);
    }
  }

  public static Plan parsePlan(byte[] bytes) throws IOException {
    JsonNode root = parse(bytes, PLAN_BYTES);
    fields(root, PLAN_FIELDS);
    require("1.0".equals(text(root, "schemaVersion")) && PROFILE.equals(text(root, "profile"))
        && OPERATOR_PROFILE.equals(text(root, "operatorProfile")));
    return new Plan(EksSandboxBundle.parse(EksSandboxBundle.JSON.writeValueAsBytes(root.get("bundle"))),
        text(root, "contextFingerprint"), text(root, "clusterUid"), text(root, "organizationImage"),
        text(root, "organizationArchiveSha256"), text(root, "organizationCommand"),
        text(root, "authorityProfileFingerprint"), integer(root, "recoveryGeneration", 1, Integer.MAX_VALUE),
        integer(root, "maxObservationAgeSeconds", 60, 300), instant(root, "evidenceNotBefore"));
  }

  public static String fingerprint(Plan plan) throws IOException {
    var values = new TreeMap<String, Object>();
    values.put("profile", PROFILE); values.put("operatorProfile", OPERATOR_PROFILE);
    values.put("bundle", new TreeMap<>(plan.bundle().values()));
    values.put("contextFingerprint", plan.contextFingerprint()); values.put("clusterUid", plan.clusterUid());
    values.put("organizationImage", plan.organizationImage());
    values.put("organizationArchiveSha256", plan.organizationArchiveSha256());
    values.put("organizationCommand", plan.organizationCommand());
    values.put("authorityProfileFingerprint", plan.authorityProfileFingerprint());
    values.put("recoveryGeneration", plan.recoveryGeneration());
    values.put("maxObservationAgeSeconds", plan.maxObservationAgeSeconds());
    values.put("evidenceNotBefore", plan.evidenceNotBefore().toString());
    return hash(EksSandboxBundle.JSON.writeValueAsBytes(values));
  }

  /** Retains the accepted fixed template; only the trusted command/image and binding annotations vary. */
  public static JsonNode render(Plan plan) throws IOException {
    ObjectNode list = EksSandboxBundle.render(plan.bundle()).deepCopy();
    String fingerprint = fingerprint(plan);
    int changed = 0;
    for (JsonNode item : list.path("items")) {
      ObjectNode annotations = (ObjectNode) item.path("metadata").path("annotations");
      annotations.put("tracepermit.dev/acceptance-profile", PROFILE)
          .put("tracepermit.dev/acceptance-plan-sha256", fingerprint);
      if ("Job".equals(item.path("kind").asText())
          && plan.bundle().value("trustedNamespace").equals(item.path("metadata").path("namespace").asText())) {
        require(item.path("spec").path("suspend").asBoolean(false));
        ObjectNode container = (ObjectNode) item.at("/spec/template/spec/containers/0");
        container.put("image", plan.organizationImage());
        container.putArray("command").add(LAUNCHER);
        container.putArray("args").add(plan.organizationCommand());
        annotations.put("tracepermit.dev/archive-sha256", plan.organizationArchiveSha256())
            .put("tracepermit.dev/probe-archive-sha256", plan.bundle().value("archiveSha256"))
            .put("tracepermit.dev/organization-archive-sha256", plan.organizationArchiveSha256())
            .put("tracepermit.dev/operator-profile", OPERATOR_PROFILE);
        changed++;
      }
    }
    require(changed == 1);
    return list;
  }

  public static JsonNode catalog(Plan plan) throws IOException {
    ObjectNode result = header(plan);
    result.put("kind", "MANUAL_ACCEPTANCE_CATALOG");
    ArrayNode controls = result.putArray("requiredControls");
    CONTROLS.forEach(controls::add);
    ArrayNode steps = result.putArray("emergencyStopOrder");
    for (String step : List.of("NAMED_OPERATOR_CLOSES_NEW_SUBMISSIONS",
        "SEPARATELY_AUTHORIZED_OPERATOR_QUARANTINES_EXACT_PROFILE",
        "OBSERVE_FRESH_ADMISSION_DENIAL_FOR_THE_PINNED_PROFILE_AND_GENERATION",
        "ACCOUNT_FOR_EVERY_STARTED_ATTEMPT_AS_TERMINAL_OR_UNKNOWN",
        "RECONCILE_READ_ONLY_WITHOUT_RECONSTRUCTING_AUTHORITY",
        "OBSERVE_OWNED_PROCESS_AND_DATABASE_QUIESCENCE_WITHOUT_DELETING_EVIDENCE",
        "RECORD_INDEPENDENT_AUDIT_AND_HUMAN_DISPOSITION")) steps.add(step);
    result.put("administrationImplemented", false).put("clusterCommandsExecuted", false);
    result.put("startedOrUnknownWorkMayHaveLateEffects", true);
    return result;
  }

  /** The snapshot is a private, normalized operator projection, not trusted API/token material. */
  public static JsonNode assess(Plan plan, byte[] bytes, Instant now) throws IOException {
    JsonNode root = parse(bytes, SNAPSHOT_BYTES);
    fields(root, Set.of("schemaVersion", "profile", "planFingerprint", "contextFingerprint", "clusterUid",
        "capturedAt", "posture", "controls", "stop"));
    require("1.0".equals(text(root, "schemaVersion")) && PROFILE.equals(text(root, "profile")) && now != null);
    Instant captured = instant(root, "capturedAt");
    ObjectNode result = header(plan);
    result.put("kind", "SUPPLIED_SNAPSHOT_ASSESSMENT").put("snapshotSha256", hash(bytes));
    ArrayNode checks = result.putArray("checks");
    match(checks, "PLAN_BINDING", fingerprint(plan).equals(text(root, "planFingerprint")));
    match(checks, "CONTEXT_BINDING", plan.contextFingerprint().equals(text(root, "contextFingerprint")));
    match(checks, "CLUSTER_UID_BINDING", plan.clusterUid().equals(text(root, "clusterUid")));
    match(checks, "CAPTURE_FRESHNESS", fresh(captured, now, plan.maxObservationAgeSeconds()));
    match(checks, "EVIDENCE_WINDOW", !plan.evidenceNotBefore().isAfter(captured)
        && Duration.between(plan.evidenceNotBefore(), captured).compareTo(Duration.ofHours(24)) <= 0);
    assessPosture(plan, root.get("posture"), checks);
    assessControls(plan, root.get("controls"), captured, checks);
    assessStop(plan, root.get("stop"), captured, now, checks);
    int blocked = 0, incomplete = 0, reported = 0;
    for (JsonNode check : checks) {
      switch (check.path("status").asText()) {
        case "MISMATCH_REPORTED", "FAIL_REPORTED", "STALE_REPORT" -> blocked++;
        case "NOT_SUPPLIED", "UNKNOWN_REPORTED" -> incomplete++;
        case "MATCH_REPORTED", "PASS_REPORTED" -> reported++;
        default -> throw new IllegalArgumentException("ACCEPTANCE_STATUS_INVALID");
      }
    }
    result.put("status", blocked == 0 ? "REVIEW_REQUIRED" : "BLOCKED")
        .put("reportedMatchingChecks", reported).put("incompleteChecks", incomplete).put("blockingChecks", blocked);
    return result;
  }

  private static void assessPosture(Plan plan, JsonNode posture, ArrayNode checks) throws IOException {
    require(posture.isObject());
    ObjectNode expected = expectedPosture(plan);
    require(names(expected).containsAll(names(posture)));
    for (String key : names(expected).stream().sorted().toList()) {
      JsonNode actual = posture.get(key);
      report(checks, "POSTURE_" + key, actual == null || actual.isNull() ? "NOT_SUPPLIED"
          : actual.equals(expected.get(key)) ? "MATCH_REPORTED" : "MISMATCH_REPORTED");
    }
  }

  static ObjectNode expectedPosture(Plan plan) throws IOException {
    ObjectNode p = EksSandboxBundle.JSON.createObjectNode();
    for (String key : List.of("kubernetesMinor", "compute", "architecture", "ipFamily", "cni", "cniVersion",
        "policyMode", "agentNamespace", "trustedNamespace", "agentNodePool", "trustedNodePool", "databaseImage", "databasePvcUid")) {
      p.put(key, plan.bundle().value(key));
    }
    p.put("organizationImage", plan.organizationImage()).put("organizationLauncher", LAUNCHER)
        .put("manifestSha256", hash(EksSandboxBundle.JSON.writeValueAsBytes(render(plan))))
        .put("podSecurityEnforce", "restricted").put("podSecurityVersion", "v" + plan.bundle().value("kubernetesMinor"))
        .put("policyEngineEnabled", true).put("unexpectedSelectingPolicies", 0)
        .put("agentTokenAutomount", false).put("trustedTokenAutomount", false)
        .put("agentAwsRoleMappings", 0).put("trustedAwsRoleMappings", 0).put("agentApiPermissions", 0)
        .put("trustedApiPermissions", 0).put("hostNamespaceOrSocketAccess", false).put("allContainerKindsInspected", true)
        .put("runAsNonRoot", true).put("readOnlyRootFilesystem", true).put("allowPrivilegeEscalation", false)
        .put("addedCapabilities", 0).put("dropAllCapabilities", true).put("seccompRuntimeDefault", true)
        .put("applicationRestartPolicy", "Never").put("jobBackoffLimit", 0).put("jobParallelism", 1)
        .put("jobCompletions", 1).put("databaseAccessMode", "ReadWriteOncePod");
    return p;
  }

  private static void assessControls(Plan plan, JsonNode records, Instant captured, ArrayNode checks) {
    require(records.isObject() && new HashSet<>(CONTROLS).containsAll(names(records)));
    for (String id : CONTROLS) {
      JsonNode record = records.get(id);
      if (record == null) { report(checks, id, "NOT_SUPPLIED"); continue; }
      fields(record, Set.of("status", "evidenceSha256", "observedAt"));
      String status = text(record, "status");
      require(Set.of("NOT_RUN", "UNKNOWN", "PASS_REPORTED", "FAIL_REPORTED").contains(status));
      boolean absent = record.get("evidenceSha256").isNull() && record.get("observedAt").isNull();
      if (status.equals("NOT_RUN")) require(absent);
      else if (!status.equals("UNKNOWN") || !absent) {
        digest(text(record, "evidenceSha256"));
        Instant observed = instant(record, "observedAt");
        if (observed.isAfter(captured) || observed.isBefore(plan.evidenceNotBefore())) {
          report(checks, id, "STALE_REPORT"); continue;
        }
      }
      report(checks, id, status.equals("NOT_RUN") ? "NOT_SUPPLIED" : status.equals("UNKNOWN") ? "UNKNOWN_REPORTED" : status);
    }
  }

  private static void assessStop(Plan plan, JsonNode stop, Instant captured, Instant now, ArrayNode checks) {
    if (stop.isNull()) { report(checks, "STOP_RECORD", "NOT_SUPPLIED"); return; }
    fields(stop, Set.of("profileFingerprint", "generation", "requestedAt", "submissionsClosed", "authorityQuarantined",
        "newAdmissionDenied", "readOnlyReconciled", "startedAttempts", "terminalAttempts", "unknownAttempts",
        "ownedCleanupObserved", "auditAccounted", "automaticRetryEnabled"));
    match(checks, "STOP_PROFILE_BINDING", plan.authorityProfileFingerprint().equals(text(stop, "profileFingerprint")));
    match(checks, "STOP_GENERATION_BINDING", plan.recoveryGeneration() == integer(stop, "generation", 1, Integer.MAX_VALUE));
    Instant requested = instant(stop, "requestedAt");
    match(checks, "STOP_TIME_BINDING", !requested.isAfter(captured) && fresh(requested, now, plan.maxObservationAgeSeconds()));
    for (String key : List.of("submissionsClosed", "authorityQuarantined", "newAdmissionDenied", "readOnlyReconciled",
        "ownedCleanupObserved", "auditAccounted")) match(checks, "STOP_" + key, bool(stop, key));
    match(checks, "STOP_NO_AUTOMATIC_RETRY", !bool(stop, "automaticRetryEnabled"));
    int started = integer(stop, "startedAttempts", 0, 1), terminal = integer(stop, "terminalAttempts", 0, 1);
    int unknown = integer(stop, "unknownAttempts", 0, 1);
    match(checks, "STOP_ATTEMPT_ACCOUNTING", started == terminal + unknown);
    if (unknown != 0) report(checks, "STOP_UNKNOWN_MAY_HAVE_LATE_EFFECT", "UNKNOWN_REPORTED");
  }

  private static ObjectNode header(Plan plan) throws IOException {
    return EksSandboxBundle.JSON.createObjectNode().put("schemaVersion", "1.0").put("profile", PROFILE)
        .put("planFingerprint", fingerprint(plan)).put("executionEnabled", false).put("automaticRetryAllowed", false)
        .put("clusterAcceptanceGranted", false).put("stopGuaranteesNoLateEffect", false);
  }

  private static boolean fresh(Instant value, Instant now, int maximumAge) {
    return !value.isAfter(now) && !value.isBefore(now.minusSeconds(maximumAge));
  }

  private static JsonNode parse(byte[] bytes, int maximum) throws IOException {
    require(bytes != null && bytes.length > 0 && bytes.length <= maximum);
    JsonNode root;
    try { root = EksSandboxBundle.JSON.readTree(bytes); }
    catch (IOException invalid) { throw new IllegalArgumentException("EKS_ACCEPTANCE_INPUT_INVALID"); }
    require(root != null && root.isObject());
    return root;
  }

  private static void fields(JsonNode node, Set<String> expected) {
    require(node != null && node.isObject() && names(node).equals(expected));
  }

  private static Set<String> names(JsonNode node) {
    var names = new HashSet<String>(); node.fieldNames().forEachRemaining(names::add); return names;
  }

  private static String text(JsonNode node, String field) {
    JsonNode value = node.get(field);
    require(value != null && value.isTextual() && !value.textValue().isBlank() && value.textValue().length() <= 512);
    return value.textValue();
  }

  private static int integer(JsonNode node, String field, int minimum, int maximum) {
    JsonNode value = node.get(field);
    require(value != null && value.isIntegralNumber() && value.canConvertToInt()
        && value.intValue() >= minimum && value.intValue() <= maximum);
    return value.intValue();
  }

  private static boolean bool(JsonNode node, String field) {
    JsonNode value = node.get(field); require(value != null && value.isBoolean()); return value.booleanValue();
  }

  private static Instant instant(JsonNode node, String field) {
    try { return Instant.parse(text(node, field)); }
    catch (java.time.DateTimeException invalid) { throw new IllegalArgumentException("EKS_ACCEPTANCE_INPUT_INVALID"); }
  }

  private static void digest(String value) {
    require(value != null && value.matches("[0-9a-f]{64}") && !value.equals("0".repeat(64)));
  }

  private static String hash(byte[] bytes) {
    try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
    catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA256_UNAVAILABLE"); }
  }

  private static void report(ArrayNode checks, String id, String status) { checks.addObject().put("id", id).put("status", status); }
  private static void match(ArrayNode checks, String id, boolean matches) { report(checks, id, matches ? "MATCH_REPORTED" : "MISMATCH_REPORTED"); }
  private static void require(boolean value) { if (!value) throw new IllegalArgumentException("EKS_ACCEPTANCE_INPUT_INVALID"); }
}
