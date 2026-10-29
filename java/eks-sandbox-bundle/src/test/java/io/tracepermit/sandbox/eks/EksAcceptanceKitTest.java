package io.tracepermit.sandbox.eks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.Permission;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@SuppressWarnings("removal")
final class EksAcceptanceKitTest {
  static final Instant NOW = Instant.parse("2026-01-02T03:04:05Z");

  static ObjectNode example() {
    var bundle = new TreeMap<>(EksSandboxBundleTest.example());
    bundle.put("command", "check-config");
    ObjectNode root = EksSandboxBundle.JSON.createObjectNode().put("schemaVersion", "1.0")
        .put("profile", EksAcceptanceKit.PROFILE).put("contextFingerprint", "1".repeat(64))
        .put("clusterUid", "22222222-3333-4444-8555-666666666666")
        .put("operatorProfile", EksAcceptanceKit.OPERATOR_PROFILE)
        .put("organizationImage", "registry.example.invalid/organization@sha256:" + "e".repeat(64))
        .put("organizationArchiveSha256", "f".repeat(64)).put("organizationCommand", "inspect")
        .put("authorityProfileFingerprint", "2".repeat(64)).put("recoveryGeneration", 1)
        .put("maxObservationAgeSeconds", 300).put("evidenceNotBefore", NOW.minusSeconds(3_600).toString());
    root.set("bundle", EksSandboxBundle.JSON.valueToTree(bundle));
    return root;
  }

  static byte[] bytes(JsonNode value) throws Exception { return EksSandboxBundle.JSON.writeValueAsBytes(value); }
  static EksAcceptanceKit.Plan plan() throws Exception { return EksAcceptanceKit.parsePlan(bytes(example())); }

  static ObjectNode snapshot(EksAcceptanceKit.Plan plan, Instant now) throws Exception {
    ObjectNode root = EksSandboxBundle.JSON.createObjectNode().put("schemaVersion", "1.0")
        .put("profile", EksAcceptanceKit.PROFILE).put("planFingerprint", EksAcceptanceKit.fingerprint(plan))
        .put("contextFingerprint", plan.contextFingerprint()).put("clusterUid", plan.clusterUid())
        .put("capturedAt", now.toString());
    ObjectNode posture = root.putObject("posture");
    for (String key : List.of("kubernetesMinor", "compute", "architecture", "ipFamily", "cni", "cniVersion",
        "policyMode", "agentNamespace", "trustedNamespace", "agentNodePool", "trustedNodePool", "databaseImage", "databasePvcUid")) {
      posture.put(key, plan.bundle().values().get(key));
    }
    posture.put("organizationImage", plan.organizationImage())
        .put("organizationLauncher", "/opt/organization/bin/tracepermit-synthetic-organization-operator")
        .put("manifestSha256", hash(bytes(EksAcceptanceKit.render(plan))))
        .put("podSecurityEnforce", "restricted").put("podSecurityVersion", "v1.34")
        .put("policyEngineEnabled", true).put("unexpectedSelectingPolicies", 0)
        .put("agentTokenAutomount", false).put("trustedTokenAutomount", false)
        .put("agentAwsRoleMappings", 0).put("trustedAwsRoleMappings", 0).put("agentApiPermissions", 0)
        .put("trustedApiPermissions", 0).put("hostNamespaceOrSocketAccess", false).put("allContainerKindsInspected", true)
        .put("runAsNonRoot", true).put("readOnlyRootFilesystem", true).put("allowPrivilegeEscalation", false)
        .put("addedCapabilities", 0).put("dropAllCapabilities", true).put("seccompRuntimeDefault", true)
        .put("applicationRestartPolicy", "Never").put("jobBackoffLimit", 0).put("jobParallelism", 1)
        .put("jobCompletions", 1).put("databaseAccessMode", "ReadWriteOncePod");
    ObjectNode controls = root.putObject("controls");
    for (String id : EksAcceptanceKit.CONTROLS) controls.putObject(id).put("status", "PASS_REPORTED")
        .put("evidenceSha256", hash(id.getBytes(StandardCharsets.US_ASCII))).put("observedAt", now.minusSeconds(1).toString());
    root.set("stop", stop(plan, now));
    return root;
  }

  static ObjectNode stop(EksAcceptanceKit.Plan plan, Instant now) {
    return EksSandboxBundle.JSON.createObjectNode().put("profileFingerprint", plan.authorityProfileFingerprint())
        .put("generation", plan.recoveryGeneration()).put("requestedAt", now.minusSeconds(2).toString())
        .put("submissionsClosed", true).put("authorityQuarantined", true).put("newAdmissionDenied", true)
        .put("readOnlyReconciled", true).put("startedAttempts", 1).put("terminalAttempts", 1)
        .put("unknownAttempts", 0).put("ownedCleanupObserved", true).put("auditAccounted", true)
        .put("automaticRetryEnabled", false);
  }

  static String hash(byte[] bytes) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
  }

  @Test void normalizedPlanFingerprintBindsEveryOrgFieldAndIgnoresJsonMemberOrder() throws Exception {
    var input = example(); var original = EksAcceptanceKit.parsePlan(bytes(input));
    var reordered = EksSandboxBundle.JSON.createObjectNode();
    var sorted = new TreeMap<String, JsonNode>();
    input.fields().forEachRemaining(e -> sorted.put(e.getKey(), e.getValue()));
    sorted.descendingMap().forEach(reordered::set);
    assertThat(EksAcceptanceKit.fingerprint(EksAcceptanceKit.parsePlan(bytes(reordered))))
        .isEqualTo(EksAcceptanceKit.fingerprint(original));
    for (String field : List.of("contextFingerprint", "organizationArchiveSha256", "authorityProfileFingerprint")) {
      var changed = input.deepCopy().put(field, "9".repeat(64));
      assertThat(EksAcceptanceKit.fingerprint(EksAcceptanceKit.parsePlan(bytes(changed))))
          .isNotEqualTo(EksAcceptanceKit.fingerprint(original));
    }
    assertThat(original.bundle().values()).containsEntry("command", "check-config");
  }

  @ParameterizedTest @ValueSource(strings = {"check-config", "register", "inspect", "evaluate", "review", "deliver-synthetic", "reconcile"})
  void fixedOrganizationCommandsRenderWithoutChangingTheLegacyTemplate(String command) throws Exception {
    var input = example().put("organizationCommand", command);
    var plan = EksAcceptanceKit.parsePlan(bytes(input));
    JsonNode legacy = EksSandboxBundle.render(plan.bundle());
    JsonNode rendered = EksAcceptanceKit.render(plan);
    assertThat(rendered.path("items").size()).isEqualTo(13);
    int trusted = 0, probe = 0;
    for (JsonNode item : rendered.path("items")) if (item.path("kind").asText().equals("Job")) {
      assertThat(item.at("/spec/suspend").booleanValue()).isTrue();
      assertThat(item.at("/spec/backoffLimit").intValue()).isZero();
      assertThat(item.at("/spec/template/spec/restartPolicy").textValue()).isEqualTo("Never");
      JsonNode container = item.at("/spec/template/spec/containers/0");
      if (item.at("/metadata/namespace").textValue().equals(plan.bundle().values().get("trustedNamespace"))) {
        trusted++;
        assertThat(container.path("image").textValue()).isEqualTo(plan.organizationImage());
        assertThat(container.path("command").get(0).textValue()).isEqualTo(EksAcceptanceKit.LAUNCHER);
        assertThat(container.path("args").get(0).textValue()).isEqualTo(command);
        assertThat(container.path("stdin").booleanValue()).isTrue();
        assertThat(container.path("tty").booleanValue()).isFalse();
        assertThat(item.at("/spec/template/spec/initContainers/0/image").textValue())
            .isEqualTo(plan.bundle().values().get("databaseImage"));
      } else {
        probe++;
        assertThat(container.path("image").textValue()).isEqualTo(plan.bundle().values().get("runtimeImage"));
      }
    }
    assertThat(trusted).isOne(); assertThat(probe).isOne();
    assertThat(EksSandboxBundle.render(plan.bundle())).isEqualTo(legacy);
    assertThatThrownBy(() -> EksSandboxBundle.verify(plan.bundle(), bytes(rendered)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @ParameterizedTest @ValueSource(strings = {"unknown", "profile", "operatorProfile", "command", "image", "digest", "uid", "generation", "age", "seed"})
  void invalidOrImplicitPlanSelectionsAreRefused(String mutation) throws Exception {
    var input = example();
    switch (mutation) {
      case "unknown" -> input.put("authenticated", true);
      case "profile" -> input.put("profile", "unknown-v2");
      case "operatorProfile" -> input.put("operatorProfile", "live-provider");
      case "command" -> input.put("organizationCommand", "kubectl delete");
      case "image" -> input.put("organizationImage", "organization:latest");
      case "digest" -> input.put("organizationArchiveSha256", "0".repeat(64));
      case "uid" -> input.put("clusterUid", "00000000-0000-0000-0000-000000000000");
      case "generation" -> input.put("recoveryGeneration", "1");
      case "age" -> input.put("maxObservationAgeSeconds", 301);
      case "seed" -> ((ObjectNode) input.get("bundle")).put("command", "deliver-synthetic");
      default -> throw new AssertionError();
    }
    assertThatThrownBy(() -> EksAcceptanceKit.parsePlan(bytes(input)))
        .isInstanceOfAny(IllegalArgumentException.class, java.io.IOException.class);
  }

  @Test void malformedDuplicateTrailingAndOversizedDocumentsFailClosed() throws Exception {
    for (byte[] bad : List.of(new byte[0], "null".getBytes(StandardCharsets.UTF_8),
        "[]".getBytes(StandardCharsets.UTF_8), "{".getBytes(StandardCharsets.UTF_8),
        "{\"profile\":1,\"profile\":2}".getBytes(StandardCharsets.UTF_8),
        (new String(bytes(example()), StandardCharsets.UTF_8) + " {}").getBytes(StandardCharsets.UTF_8),
        new byte[65_537])) {
      assertThatThrownBy(() -> EksAcceptanceKit.parsePlan(bad)).isInstanceOfAny(IllegalArgumentException.class, java.io.IOException.class);
    }
    assertThatThrownBy(() -> EksAcceptanceKit.parsePlan(null)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> EksAcceptanceKit.assess(plan(), new byte[131_073], NOW)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test void completeReportedSuccessStillCannotGrantAcceptanceExecutionOrRetry() throws Exception {
    var plan = plan(); JsonNode result = EksAcceptanceKit.assess(plan, bytes(snapshot(plan, NOW)), NOW);
    assertThat(result.path("status").textValue()).isEqualTo("REVIEW_REQUIRED");
    assertThat(result.path("blockingChecks").intValue()).isZero();
    assertThat(result.path("incompleteChecks").intValue()).isZero();
    for (String flag : List.of("executionEnabled", "automaticRetryAllowed", "clusterAcceptanceGranted", "stopGuaranteesNoLateEffect")) {
      assertThat(result.path(flag).isBoolean()).isTrue(); assertThat(result.path(flag).booleanValue()).isFalse();
    }
    assertThat(result.toString()).doesNotContain("123456789012", "synthetic-only", "tp-sbx-synthetic", plan.clusterUid(), "registry.example.invalid");
  }

  @Test void missingPostureControlsAndStopStayExplicitlyUnobserved() throws Exception {
    var plan = plan(); var input = snapshot(plan, NOW);
    input.putObject("posture"); input.putObject("controls"); input.putNull("stop");
    JsonNode result = EksAcceptanceKit.assess(plan, bytes(input), NOW);
    assertThat(result.path("status").textValue()).isEqualTo("REVIEW_REQUIRED");
    assertThat(result.path("incompleteChecks").intValue()).isGreaterThan(50);
    assertThat(result.toString()).contains("NOT_SUPPLIED").doesNotContain("PASS_REPORTED");
  }

  @Test void postureWeakeningAndWrongTypesAreBlockingEvenWithReportedPassingControls() throws Exception {
    var plan = plan();
    for (String key : List.of("agentTokenAutomount", "hostNamespaceOrSocketAccess", "allowPrivilegeEscalation")) {
      var input = snapshot(plan, NOW); ((ObjectNode) input.get("posture")).put(key, true);
      assertThat(EksAcceptanceKit.assess(plan, bytes(input), NOW).path("status").textValue()).isEqualTo("BLOCKED");
    }
    var input = snapshot(plan, NOW); ((ObjectNode) input.get("posture")).put("addedCapabilities", "0");
    assertThat(EksAcceptanceKit.assess(plan, bytes(input), NOW).path("blockingChecks").intValue()).isOne();
  }

  @Test void clusterContextPlanAndFreshnessCannotBeSubstituted() throws Exception {
    var plan = plan();
    for (String key : List.of("planFingerprint", "contextFingerprint", "clusterUid")) {
      var input = snapshot(plan, NOW).put(key, "substituted-public-value");
      assertThat(EksAcceptanceKit.assess(plan, bytes(input), NOW).path("status").textValue()).isEqualTo("BLOCKED");
    }
    for (Instant time : List.of(NOW.minusSeconds(301), NOW.plusSeconds(1))) {
      var input = snapshot(plan, NOW).put("capturedAt", time.toString());
      assertThat(EksAcceptanceKit.assess(plan, bytes(input), NOW).path("status").textValue()).isEqualTo("BLOCKED");
    }
  }

  @Test void controlFailuresUnknownsNotRunAndStaleReportsRemainDistinct() throws Exception {
    var plan = plan();
    for (String status : List.of("FAIL_REPORTED", "UNKNOWN", "NOT_RUN")) {
      var input = snapshot(plan, NOW);
      ObjectNode c = (ObjectNode) input.path("controls").get("ATTACH_AUDIT_CUSTODY");
      c.put("status", status);
      if (!status.equals("FAIL_REPORTED")) c.putNull("evidenceSha256").putNull("observedAt");
      JsonNode result = EksAcceptanceKit.assess(plan, bytes(input), NOW);
      assertThat(result.path("status").textValue()).isEqualTo(status.equals("FAIL_REPORTED") ? "BLOCKED" : "REVIEW_REQUIRED");
      if (!status.equals("FAIL_REPORTED")) assertThat(result.path("incompleteChecks").intValue()).isOne();
    }
    for (Instant time : List.of(NOW.minusSeconds(3_601), NOW.plusSeconds(1))) {
      var input = snapshot(plan, NOW);
      ((ObjectNode) input.path("controls").get("NETWORK_METADATA_DENIAL")).put("observedAt", time.toString());
      assertThat(EksAcceptanceKit.assess(plan, bytes(input), NOW).toString()).contains("STALE_REPORT");
    }
  }

  @Test void unknownClaimsEvidenceSubstitutionsAndMalformedControlUnionsAreRejected() throws Exception {
    var plan = plan();
    for (String mutation : List.of("root", "posture", "controlId", "controlField", "status", "digest", "partial", "reportedWithoutEvidence")) {
      var input = snapshot(plan, NOW); var controls = (ObjectNode) input.get("controls");
      var c = (ObjectNode) controls.get("PRIVATE_INPUT_EOF");
      switch (mutation) {
        case "root" -> input.put("approved", true);
        case "posture" -> ((ObjectNode) input.get("posture")).put("credential", "PUBLIC_SENTINEL");
        case "controlId" -> controls.putObject("CUSTOM_PASS");
        case "controlField" -> c.put("authenticated", true);
        case "status" -> c.put("status", "PASS");
        case "digest" -> c.put("evidenceSha256", "0".repeat(64));
        case "partial" -> c.put("status", "UNKNOWN").putNull("evidenceSha256");
        case "reportedWithoutEvidence" -> c.putNull("evidenceSha256").putNull("observedAt");
        default -> throw new AssertionError();
      }
      assertThatThrownBy(() -> EksAcceptanceKit.assess(plan, bytes(input), NOW)).isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test void stopNeedsExactProfileGenerationFreshDenialQuarantineAndReadOnlyAccounting() throws Exception {
    var plan = plan();
    for (String field : List.of("submissionsClosed", "authorityQuarantined", "newAdmissionDenied", "readOnlyReconciled", "ownedCleanupObserved", "auditAccounted")) {
      var input = snapshot(plan, NOW); ((ObjectNode) input.get("stop")).put(field, false);
      assertThat(EksAcceptanceKit.assess(plan, bytes(input), NOW).path("status").textValue()).isEqualTo("BLOCKED");
    }
    for (String mutation : List.of("profile", "generation", "time", "retry", "accounting")) {
      var input = snapshot(plan, NOW); var stop = (ObjectNode) input.get("stop");
      switch (mutation) {
        case "profile" -> stop.put("profileFingerprint", "3".repeat(64));
        case "generation" -> stop.put("generation", 2);
        case "time" -> stop.put("requestedAt", NOW.plusSeconds(1).toString());
        case "retry" -> stop.put("automaticRetryEnabled", true);
        case "accounting" -> stop.put("terminalAttempts", 0);
        default -> throw new AssertionError();
      }
      assertThat(EksAcceptanceKit.assess(plan, bytes(input), NOW).path("status").textValue()).isEqualTo("BLOCKED");
    }
  }

  @Test void unknownStartedAttemptIsAccountedWithoutAnyNoLateEffectOrResendClaim() throws Exception {
    var plan = plan(); var input = snapshot(plan, NOW);
    ((ObjectNode) input.get("stop")).put("terminalAttempts", 0).put("unknownAttempts", 1);
    JsonNode result = EksAcceptanceKit.assess(plan, bytes(input), NOW);
    assertThat(result.path("status").textValue()).isEqualTo("REVIEW_REQUIRED");
    assertThat(result.path("incompleteChecks").intValue()).isOne();
    assertThat(result.toString()).contains("STOP_UNKNOWN_MAY_HAVE_LATE_EFFECT");
    assertThat(result.path("stopGuaranteesNoLateEffect").booleanValue()).isFalse();
    assertThat(result.path("automaticRetryAllowed").booleanValue()).isFalse();
  }

  @Test void stopScalarCoercionExtraFieldsAndOutOfScopeWorkloadAreRejected() throws Exception {
    var plan = plan();
    for (String mutation : List.of("boolean", "generation", "counter", "extra")) {
      var input = snapshot(plan, NOW); var stop = (ObjectNode) input.get("stop");
      switch (mutation) {
        case "boolean" -> stop.put("newAdmissionDenied", "true");
        case "generation" -> stop.put("generation", 1.0);
        case "counter" -> stop.put("startedAttempts", 2);
        case "extra" -> stop.put("rollbackConfirmed", true);
        default -> throw new AssertionError();
      }
      assertThatThrownBy(() -> EksAcceptanceKit.assess(plan, bytes(input), NOW)).isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test void snapshotGrammarAndUnknownEvidenceRemainClosedWithoutInventingSuccess() throws Exception {
    var plan = plan();
    for (String mutation : List.of("version", "profile", "missing", "posture", "controls", "stop", "notRunWithEvidence")) {
      var input = snapshot(plan, NOW);
      switch (mutation) {
        case "version" -> input.put("schemaVersion", "2.0");
        case "profile" -> input.put("profile", "unknown");
        case "missing" -> input.remove("stop");
        case "posture" -> input.putArray("posture");
        case "controls" -> input.putArray("controls");
        case "stop" -> input.putObject("stop");
        case "notRunWithEvidence" -> ((ObjectNode) input.path("controls").get("PRIVATE_INPUT_EOF")).put("status", "NOT_RUN");
        default -> throw new AssertionError();
      }
      assertThatThrownBy(() -> EksAcceptanceKit.assess(plan, bytes(input), NOW)).isInstanceOf(IllegalArgumentException.class);
    }
    var input = snapshot(plan, NOW);
    ((ObjectNode) input.path("controls").get("PRIVATE_INPUT_EOF")).put("status", "UNKNOWN");
    assertThat(EksAcceptanceKit.assess(plan, bytes(input), NOW).path("incompleteChecks").intValue()).isOne();
    input.put("capturedAt", "PUBLIC_SENTINEL_DO_NOT_ECHO");
    assertThatThrownBy(() -> EksAcceptanceKit.assess(plan, bytes(input), NOW))
        .hasMessage("EKS_ACCEPTANCE_INPUT_INVALID");
    var oldPlan = EksAcceptanceKit.parsePlan(bytes(example().put("evidenceNotBefore", NOW.minusSeconds(86_401).toString())));
    assertThat(EksAcceptanceKit.assess(oldPlan, bytes(snapshot(oldPlan, NOW)), NOW).path("status").textValue())
        .isEqualTo("BLOCKED");
  }

  @Test void catalogIsAStopDrillWithoutExecutableAdministrationOrApproval() throws Exception {
    JsonNode catalog = EksAcceptanceKit.catalog(plan());
    assertThat(catalog.path("requiredControls").size()).isEqualTo(28);
    assertThat(catalog.path("emergencyStopOrder").size()).isEqualTo(7);
    assertThat(catalog.path("administrationImplemented").booleanValue()).isFalse();
    assertThat(catalog.path("clusterCommandsExecuted").booleanValue()).isFalse();
    assertThat(catalog.path("startedOrUnknownWorkMayHaveLateEffects").booleanValue()).isTrue();
    assertThat(catalog.toString()).doesNotContain("kubectl delete", "kubeconfig", "Bearer ");
  }

  @Test void offlinePreparationAndAssessmentCannotStartProcessesDnsOrNetwork() throws Exception {
    var plan = plan(); byte[] snapshot = bytes(snapshot(plan, NOW));
    SecurityManager previous = System.getSecurityManager();
    try {
      System.setSecurityManager(new SecurityManager() {
        @Override public void checkPermission(Permission permission) {}
        @Override public void checkExec(String command) { throw new AssertionError("NO_PROCESS"); }
        @Override public void checkConnect(String host, int port) { throw new AssertionError("NO_DNS_OR_CONNECT"); }
        @Override public void checkListen(int port) { throw new AssertionError("NO_LISTENER"); }
      });
      EksAcceptanceKit.render(plan); EksAcceptanceKit.catalog(plan);
      assertThat(EksAcceptanceKit.assess(plan, snapshot, NOW).path("status").textValue()).isEqualTo("REVIEW_REQUIRED");
    } finally { System.setSecurityManager(previous); }
  }
}
