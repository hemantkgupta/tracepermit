package io.tracepermit.sandbox.eks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.Permission;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

@Timeout(30)
@SuppressWarnings("removal")
final class EksSandboxBundleTest {
  @TempDir Path temporary;

  static Map<String, String> example() {
    var v = new HashMap<String, String>();
    v.put("schemaVersion", "1.0");
    v.put("profile", EksSandboxBundle.PROFILE);
    v.put("stage", "P1_SYNTHETIC_ONLY");
    v.put("accountId", "123456789012");
    v.put("region", "us-east-1");
    v.put("clusterName", "synthetic-only");
    v.put("clusterArn", "arn:aws:eks:us-east-1:123456789012:cluster/synthetic-only");
    v.put("kubernetesMinor", "1.34");
    v.put("compute", "ec2-linux");
    v.put("architecture", "arm64");
    v.put("ipFamily", "IPv4");
    v.put("cni", "amazon-vpc-cni");
    v.put("cniVersion", "v1.19.2-eksbuild.1");
    v.put("policyMode", "strict");
    v.put("agentNamespace", "tp-sbx-synthetic-agent");
    v.put("trustedNamespace", "tp-sbx-synthetic-trusted");
    v.put("agentNodePool", "synthetic-agent");
    v.put("trustedNodePool", "synthetic-trusted");
    v.put("runtimeImage", "registry.example.invalid/pilot@sha256:" + "a".repeat(64));
    v.put("databaseImage", "registry.example.invalid/postgres@sha256:" + "b".repeat(64));
    v.put("databasePvc", "synthetic-preinitialized-pg");
    v.put("databasePvcUid", "11111111-2222-4333-8444-555555555555");
    v.put("sourceCommit", "c".repeat(40));
    v.put("archiveSha256", "d".repeat(64));
    v.put("rehearsalId", "synthetic-001");
    v.put("command", "inspect");
    v.put("controlIPv4", "10.23.0.8");
    v.put("kubernetesApiIPv4", "10.23.0.1");
    v.put("protectedTargetIPv4", "192.0.2.42");
    return v;
  }

  private static EksSandboxBundle.Configuration config() {
    return new EksSandboxBundle.Configuration(example());
  }

  @Test
  void exactConfigRoundTripsDeterministicallyAndFingerprintBindsEveryField() throws Exception {
    var original = example();
    var parsed = EksSandboxBundle.parse(EksSandboxBundle.JSON.writeValueAsBytes(original));
    assertThat(parsed.values()).isEqualTo(original);
    assertThatThrownBy(() -> parsed.values().put("command", "other"))
        .isInstanceOf(UnsupportedOperationException.class);
    String fingerprint = EksSandboxBundle.fingerprint(parsed);
    assertThat(fingerprint).matches("[0-9a-f]{64}");
    var bundle = EksSandboxBundle.render(parsed);
    assertThat(EksSandboxBundle.render(parsed)).isEqualTo(bundle);
    EksSandboxBundle.verify(parsed, EksSandboxBundle.JSON.writeValueAsBytes(bundle));
    original.put("command", "review");
    assertThat(EksSandboxBundle.fingerprint(new EksSandboxBundle.Configuration(original)))
        .isNotEqualTo(fingerprint);
    assertThatThrownBy(() -> EksSandboxBundle.verify(new EksSandboxBundle.Configuration(original),
        EksSandboxBundle.JSON.writeValueAsBytes(bundle))).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void fixedResourcesHaveNoRolesSecretsPublicRoutesOrAutomaticApplicationRetries() throws Exception {
    JsonNode bundle = EksSandboxBundle.render(config());
    assertThat(bundle.path("kind").asText()).isEqualTo("List");
    assertThat(bundle.path("items").size()).isEqualTo(13);
    var kinds = new ArrayList<String>();
    for (JsonNode item : bundle.path("items")) {
      kinds.add(item.path("kind").asText());
      switch (item.path("kind").asText()) {
        case "Namespace" -> {
          assertThat(item.at("/metadata/labels/pod-security.kubernetes.io~1enforce").asText())
              .isEqualTo("restricted");
          assertThat(item.at("/metadata/labels/pod-security.kubernetes.io~1enforce-version").asText())
              .isEqualTo("v1.34");
        }
        case "ServiceAccount" -> assertThat(item.path("automountServiceAccountToken").asBoolean(true)).isFalse();
        case "Job" -> {
          assertThat(item.at("/spec/suspend").asBoolean()).isTrue();
          assertThat(item.at("/spec/backoffLimit").asInt(-1)).isZero();
          assertThat(item.at("/spec/parallelism").asInt()).isEqualTo(1);
          assertThat(item.at("/spec/completions").asInt()).isEqualTo(1);
          JsonNode pod = item.at("/spec/template/spec");
          for (String field : List.of("automountServiceAccountToken", "hostNetwork", "hostPID",
              "hostIPC", "shareProcessNamespace", "enableServiceLinks")) {
            assertThat(pod.get(field).asBoolean(true)).as(field).isFalse();
          }
          assertThat(pod.path("restartPolicy").asText()).isEqualTo("Never");
          assertThat(pod.at("/nodeSelector/tracepermit.node-restriction.kubernetes.io~1pool").asText())
              .isIn("synthetic-agent", "synthetic-trusted");
          assertThat(pod.at("/nodeSelector/kubernetes.io~1arch").asText()).isEqualTo("arm64");
          assertThat(pod.at("/tolerations/0/operator").asText()).isEqualTo("Equal");
          for (String array : List.of("containers", "initContainers")) {
            for (JsonNode container : pod.path(array)) {
              assertThat(container.path("imagePullPolicy").asText()).isEqualTo("Never");
              assertThat(container.path("env").isMissingNode()).isTrue();
              assertThat(container.path("envFrom").isMissingNode()).isTrue();
              JsonNode security = container.path("securityContext");
              assertThat(security.path("privileged").asBoolean(false)).isFalse();
              assertThat(security.path("allowPrivilegeEscalation").asBoolean(true)).isFalse();
              assertThat(security.path("readOnlyRootFilesystem").asBoolean()).isTrue();
              assertThat(security.path("runAsNonRoot").asBoolean()).isTrue();
              assertThat(security.at("/capabilities/drop/0").asText()).isEqualTo("ALL");
              assertThat(security.at("/seccompProfile/type").asText()).isEqualTo("RuntimeDefault");
            }
          }
        }
        default -> { /* The full allowed kind inventory is independently asserted below. */ }
      }
    }
    assertThat(Set.copyOf(kinds))
        .containsExactlyInAnyOrder("Namespace", "ServiceAccount", "NetworkPolicy", "Job", "ResourceQuota", "LimitRange");
    for (int index : new int[] {2, 7}) {
      assertThat(bundle.at("/items/" + index + "/spec/ingress").size()).isZero();
      assertThat(bundle.at("/items/" + index + "/spec/egress").size()).isZero();
      assertThat(bundle.at("/items/" + index + "/spec/podSelector").size()).isZero();
    }
    assertThat(bundle.at("/items/10/spec/egress/0/to/0/ipBlock/cidr").asText()).isEqualTo("10.23.0.8/32");
    assertThat(bundle.at("/items/10/spec/egress/0/ports/0/port").asInt()).isEqualTo(18080);
    JsonNode trusted = bundle.at("/items/11/spec/template/spec");
    assertThat(bundle.at("/items/11/metadata/annotations/tracepermit.dev~1database-pvc-uid").asText())
        .isEqualTo("11111111-2222-4333-8444-555555555555");
    assertThat(bundle.at("/items/11/metadata/annotations/tracepermit.dev~1database-access-mode").asText())
        .isEqualTo("ReadWriteOncePod");
    assertThat(trusted.at("/initContainers/0/restartPolicy").asText()).isEqualTo("Always");
    assertThat(trusted.at("/initContainers/0/command").toString()).contains("127.0.0.1", "postgres")
        .doesNotContain("initdb");
    assertThat(trusted.at("/containers/0/stdin").asBoolean()).isTrue();
    assertThat(trusted.at("/containers/0/tty").asBoolean(true)).isFalse();
    assertThat(trusted.at("/containers/0/volumeMounts").toString()).doesNotContain("database");
    assertThat(trusted.at("/volumes/0/persistentVolumeClaim/claimName").asText()).isEqualTo("synthetic-preinitialized-pg");
    assertThat(bundle.toString()).doesNotContain("${", "hostPath", "secretKeyRef", "envFrom", "LoadBalancer", "NodePort");
  }

  @Test
  void rejectsMissingUnknownTypedDuplicateTrailingAndMalformedConfiguration() throws Exception {
    for (String field : EksSandboxBundle.FIELDS) {
      ObjectNode missing = EksSandboxBundle.JSON.valueToTree(example());
      missing.remove(field);
      assertThatThrownBy(() -> EksSandboxBundle.parse(EksSandboxBundle.JSON.writeValueAsBytes(missing)))
          .as("missing %s", field).isInstanceOf(IllegalArgumentException.class);
      ObjectNode typed = EksSandboxBundle.JSON.valueToTree(example());
      typed.put(field, true);
      byte[] scalar = EksSandboxBundle.JSON.writeValueAsBytes(typed);
      assertThatThrownBy(() -> EksSandboxBundle.parse(scalar)).isInstanceOf(IllegalArgumentException.class);
    }
    ObjectNode extra = EksSandboxBundle.JSON.valueToTree(example());
    extra.put("bearer", "SENTINEL_NEVER_LOG");
    assertThatThrownBy(() -> EksSandboxBundle.parse(EksSandboxBundle.JSON.writeValueAsBytes(extra)))
        .isInstanceOf(IllegalArgumentException.class);
    String valid = EksSandboxBundle.JSON.writeValueAsString(example());
    for (byte[] invalid : List.of(new byte[0], new byte[65_537], "null".getBytes(StandardCharsets.UTF_8),
        "[]".getBytes(StandardCharsets.UTF_8), (valid + "{}").getBytes(StandardCharsets.UTF_8),
        ("{\"stage\":\"P1_SYNTHETIC_ONLY\"," + valid.substring(1)).getBytes(StandardCharsets.UTF_8),
        new byte[] {(byte) 0xff}, "{".getBytes(StandardCharsets.UTF_8), " ".getBytes(StandardCharsets.UTF_8),
        ("[".repeat(40) + "0" + "]".repeat(40)).getBytes(StandardCharsets.UTF_8))) {
      assertThatThrownBy(() -> EksSandboxBundle.parse(invalid)).isInstanceOf(Exception.class);
    }
    for (String field : EksSandboxBundle.FIELDS) {
      for (String invalid : List.of("", " ", "x".repeat(513), "REQUIRED_OPERATOR_VALUE")) {
        var changed = example(); changed.put(field, invalid);
        assertThatThrownBy(() -> new EksSandboxBundle.Configuration(changed))
            .as("invalid %s", field).isInstanceOf(IllegalArgumentException.class);
      }
    }
  }

  @Test
  void rejectsBroaderPlatformAuthorityImagesAndNumericIpAliases() {
    Map<String, List<String>> invalids = Map.ofEntries(
        Map.entry("stage", List.of("P3_LIVE", "P2")),
        Map.entry("accountId", List.of("000000000000", "123")),
        Map.entry("kubernetesMinor", List.of("1.32", "latest", "1.99")),
        Map.entry("ipFamily", List.of("IPv6", "dual-stack")),
        Map.entry("compute", List.of("fargate", "auto-mode", "windows")),
        Map.entry("architecture", List.of("x86_64", "any")),
        Map.entry("policyMode", List.of("standard", "disabled")),
        Map.entry("agentNamespace", List.of("default", "kube-system", "../../escape")),
        Map.entry("runtimeImage", List.of("repo/image:latest", "repo/image@sha256:" + "0".repeat(64),
            "https://registry.example.invalid/image@sha256:" + "a".repeat(64))),
        Map.entry("sourceCommit", List.of("0".repeat(40), "a".repeat(64))),
        Map.entry("archiveSha256", List.of("0".repeat(64))),
        Map.entry("databasePvcUid", List.of("00000000-0000-0000-0000-000000000000", "1-2-3-4-5")),
        Map.entry("command", List.of("apply", "--run", "merge")),
        Map.entry("controlIPv4", List.of("8.8.8.8", "127.0.0.1", "169.254.169.254", "010.1.1.1",
            "256.1.1.1", "1.2.3", "0.1.1.1", "224.1.1.1", "::1", "localhost")),
        Map.entry("kubernetesApiIPv4", List.of("198.51.100.1")));
    for (var entry : invalids.entrySet()) {
      for (String invalid : entry.getValue()) {
        var changed = example(); changed.put(entry.getKey(), invalid);
        assertThatThrownBy(() -> new EksSandboxBundle.Configuration(changed))
            .as("refused field %s", entry.getKey()).isInstanceOf(IllegalArgumentException.class);
      }
    }
    for (String pair : List.of("agentNamespace:trustedNamespace", "agentNodePool:trustedNodePool",
        "controlIPv4:kubernetesApiIPv4", "controlIPv4:protectedTargetIPv4")) {
      String[] keys = pair.split(":"); var changed = example(); changed.put(keys[0], changed.get(keys[1]));
      assertThatThrownBy(() -> new EksSandboxBundle.Configuration(changed)).isInstanceOf(IllegalArgumentException.class);
    }
    for (String control : List.of("172.16.1.1", "172.31.1.1", "192.168.1.1")) {
      var changed = example(); changed.put("controlIPv4", control);
      assertThat(new EksSandboxBundle.Configuration(changed).values()).containsEntry("controlIPv4", control);
    }
    for (String control : List.of("172.15.1.1", "172.32.1.1", "192.167.1.1")) {
      var changed = example(); changed.put("controlIPv4", control);
      assertThatThrownBy(() -> new EksSandboxBundle.Configuration(changed)).isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  void tamperControlsRejectWeakenedWorkloadsRulesAndUnexpectedResources() throws Exception {
    var v = config(); JsonNode good = EksSandboxBundle.render(v);
    for (String pointer : List.of("/items/11/spec/suspend", "/items/12/spec/suspend",
        "/items/11/spec/template/spec/automountServiceAccountToken", "/items/11/spec/template/spec/hostNetwork",
        "/items/11/spec/template/spec/hostPID", "/items/11/spec/template/spec/shareProcessNamespace",
        "/items/11/spec/template/spec/containers/0/securityContext/allowPrivilegeEscalation",
        "/items/11/spec/template/spec/containers/0/securityContext/readOnlyRootFilesystem",
        "/items/11/spec/template/spec/initContainers/0/securityContext/runAsNonRoot")) {
      JsonNode altered = good.deepCopy();
      int slash = pointer.lastIndexOf('/');
      ((ObjectNode) altered.at(pointer.substring(0, slash))).put(pointer.substring(slash + 1),
          !altered.at(pointer).asBoolean());
      assertThatThrownBy(() -> EksSandboxBundle.verify(v, EksSandboxBundle.JSON.writeValueAsBytes(altered)))
          .as(pointer).isInstanceOf(IllegalArgumentException.class);
    }
    for (String key : List.of("backoffLimit", "parallelism", "completions", "activeDeadlineSeconds")) {
      JsonNode altered = good.deepCopy(); ((ObjectNode) altered.at("/items/11/spec")).put(key, 999);
      assertThatThrownBy(() -> EksSandboxBundle.verify(v, EksSandboxBundle.JSON.writeValueAsBytes(altered)))
          .isInstanceOf(IllegalArgumentException.class);
    }
    for (String pointer : List.of("/items/2/spec/egress", "/items/7/spec/ingress", "/items/11/spec/template/spec/containers/0/securityContext")) {
      JsonNode altered = good.deepCopy(); int slash = pointer.lastIndexOf('/');
      ((ObjectNode) altered.at(pointer.substring(0, slash))).remove(pointer.substring(slash + 1));
      assertThatThrownBy(() -> EksSandboxBundle.verify(v, EksSandboxBundle.JSON.writeValueAsBytes(altered)))
          .isInstanceOf(IllegalArgumentException.class);
    }
    Map<String, String> replacements = Map.of(
        "/items/11/spec/template/spec/serviceAccountName", "default",
        "/items/11/spec/template/spec/containers/0/imagePullPolicy", "Always",
        "/items/11/spec/template/spec/containers/0/image", "registry.example.invalid/pilot:latest",
        "/items/11/spec/template/spec/containers/0/args/0", "merge",
        "/items/10/spec/egress/0/to/0/ipBlock/cidr", "0.0.0.0/0",
        "/items/11/spec/template/spec/volumes/0/persistentVolumeClaim/claimName", "other-store");
    for (var replacement : replacements.entrySet()) {
      JsonNode changed = good.deepCopy(); String pointer = replacement.getKey();
      int slash = pointer.lastIndexOf('/'); JsonNode parent = changed.at(pointer.substring(0, slash));
      if (parent.isArray()) {
        ((ArrayNode) parent).set(Integer.parseInt(pointer.substring(slash + 1)),
            EksSandboxBundle.JSON.getNodeFactory().textNode(replacement.getValue()));
      } else {
        ((ObjectNode) parent).put(pointer.substring(slash + 1), replacement.getValue());
      }
      assertThatThrownBy(() -> EksSandboxBundle.verify(v, EksSandboxBundle.JSON.writeValueAsBytes(changed)))
          .isInstanceOf(IllegalArgumentException.class);
    }
    JsonNode altered = good.deepCopy(); ((ArrayNode) altered.path("items")).addObject().put("kind", "ClusterRoleBinding");
    assertThatThrownBy(() -> EksSandboxBundle.verify(v, EksSandboxBundle.JSON.writeValueAsBytes(altered)))
        .isInstanceOf(IllegalArgumentException.class);
    for (byte[] bytes : List.of(new byte[0], new byte[262_145], "{} {}".getBytes(StandardCharsets.UTF_8))) {
      assertThatThrownBy(() -> EksSandboxBundle.verify(v, bytes)).isInstanceOf(Exception.class);
    }
    assertThatThrownBy(() -> EksSandboxBundle.substitute(EksSandboxBundle.JSON.getNodeFactory().textNode("${unknown}"), Map.of()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> EksSandboxBundle.substitute(EksSandboxBundle.JSON.getNodeFactory().textNode("${unfinished"), Map.of()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private Path privateConfig() throws Exception {
    Files.setPosixFilePermissions(temporary, PosixFilePermissions.fromString("rwx------"));
    Path config = temporary.resolve("operator.json");
    Files.write(config, EksSandboxBundle.JSON.writeValueAsBytes(example()));
    Files.setPosixFilePermissions(config, PosixFilePermissions.fromString("rw-------"));
    return config.toRealPath();
  }

  @Test
  void cliCreatesPrivateOutputChecksItAndRefusesOverwriteOrBadPathsWithoutEcho() throws Exception {
    Path config = privateConfig(); Path out = temporary.toRealPath().resolve("bundle.json");
    var bytes = new ByteArrayOutputStream(); var output = new PrintStream(bytes, true, StandardCharsets.UTF_8);
    assertThat(EksSandboxMain.run(new String[] {"render", config.toString(), out.toString()}, output)).isZero();
    assertThat(bytes.toString(StandardCharsets.UTF_8)).startsWith("EKS_BUNDLE_RENDERED:")
        .doesNotContain("synthetic-only", config.toString());
    assertThat(Files.getPosixFilePermissions(out)).isEqualTo(PosixFilePermissions.fromString("rw-------"));
    assertThat(EksSandboxMain.run(new String[] {"check", config.toString(), out.toString()}, output)).isZero();
    byte[] preserved = Files.readAllBytes(out);
    assertThat(EksSandboxMain.run(new String[] {"render", config.toString(), out.toString()}, output)).isEqualTo(1);
    assertThat(Files.readAllBytes(out)).isEqualTo(preserved);
    assertThat(EksSandboxMain.run(new String[] {"apply", config.toString(), out.toString()}, output)).isEqualTo(64);
    assertThat(EksSandboxMain.run(new String[0], output)).isEqualTo(64);
    Files.writeString(config, "SENTINEL_NEVER_LOG"); bytes.reset();
    assertThat(EksSandboxMain.run(new String[] {"render", config.toString(), out.toString()}, output)).isEqualTo(1);
    assertThat(bytes.toString(StandardCharsets.UTF_8)).isEqualTo("EKS_BUNDLE_REFUSED\n");
  }

  @Test
  void filesystemGuardsRejectLinksPermissiveFilesAndPublicOutputParents() throws Exception {
    Path config = privateConfig(); Path out = temporary.toRealPath().resolve("bundle.json");
    var output = new PrintStream(new ByteArrayOutputStream());
    Path link = temporary.resolve("linked.json"); Files.createSymbolicLink(link, config);
    assertThat(EksSandboxMain.run(new String[] {"render", link.toString(), out.toString()}, output)).isEqualTo(1);
    Files.setPosixFilePermissions(config, PosixFilePermissions.fromString("rw-r--r--"));
    assertThat(EksSandboxMain.run(new String[] {"render", config.toString(), out.toString()}, output)).isEqualTo(1);
    Files.setPosixFilePermissions(config, PosixFilePermissions.fromString("rw-------"));
    Path publicParent = Files.createDirectory(temporary.resolve("public"));
    Files.setPosixFilePermissions(publicParent, PosixFilePermissions.fromString("rwxr-xr-x"));
    assertThat(EksSandboxMain.run(new String[] {"render", config.toString(), publicParent.resolve("out").toString()}, output)).isEqualTo(1);
    Files.write(config, new byte[65_537]);
    assertThat(EksSandboxMain.run(new String[] {"render", config.toString(), out.toString()}, output)).isEqualTo(1);
    assertThatThrownBy(() -> EksSandboxMain.writeNewPrivate(Path.of("/"), new byte[0])).isInstanceOf(Exception.class);
  }

  @Test
  void offlineRendererAndCheckerCannotConnectListenOrExecuteProcesses() throws Exception {
    Path config = privateConfig(); Path out = temporary.toRealPath().resolve("offline.json");
    var output = new PrintStream(new ByteArrayOutputStream());
    // Warm only code/resources, never a network operation, before installing the tripwire.
    EksSandboxBundle.render(EksSandboxBundle.parse(Files.readAllBytes(config)));
    var tripwire = new OfflineTripwire();
    SecurityManager previous = System.getSecurityManager();
    try {
      System.setSecurityManager(tripwire);
      assertThat(EksSandboxMain.run(new String[] {"render", config.toString(), out.toString()}, output)).isZero();
      assertThat(EksSandboxMain.run(new String[] {"check", config.toString(), out.toString()}, output)).isZero();
    } finally { System.setSecurityManager(previous); }
    assertThat(tripwire.effects).isZero();
  }

  @Test
  void probeClassifiesObservationsAndUsesOnlyBoundedNumericTcp() throws Exception {
    var output = new PrintStream(new ByteArrayOutputStream());
    assertThat(EksNetworkProbe.report(false, 0, output)).isEqualTo(2);
    assertThat(EksNetworkProbe.report(true, 0, output)).isZero();
    assertThat(EksNetworkProbe.report(true, 1, output)).isEqualTo(1);
    assertThat(EksNetworkProbe.run(new String[0], output)).isEqualTo(64);
    assertThat(EksNetworkProbe.run(new String[] {"localhost", "10.1.1.2", "192.0.2.1"}, output)).isEqualTo(64);
    assertThatThrownBy(() -> EksNetworkProbe.connect("127.0.0.1", 0)).isInstanceOf(IllegalArgumentException.class);
    try (var server = new ServerSocket(0, 1, InetAddress.getByAddress(new byte[] {127, 0, 0, 1}))) {
      assertThat(EksNetworkProbe.connect("127.0.0.1", server.getLocalPort())).isTrue();
    }
    SecurityManager previous = System.getSecurityManager();
    try {
      System.setSecurityManager(new OfflineTripwire());
      assertThat(EksNetworkProbe.run(new String[] {"10.1.1.1", "10.1.1.2", "192.0.2.1"}, output)).isEqualTo(2);
    } finally { System.setSecurityManager(previous); }
  }

  @Test
  void extractedSourceFreeDistributionRunsTheActualJavaCli() throws Exception {
    Path installed = Files.createDirectory(temporary.resolve("installed"));
    assertThat(installed.toRealPath().startsWith(
        Path.of(System.getProperty("tracepermit.repositoryRoot")).toRealPath())).isFalse();
    Process tar = new ProcessBuilder("/usr/bin/tar", "-xf", System.getProperty("tracepermit.eks.archive"), "-C", installed.toString()).start();
    assertThat(tar.waitFor(10, TimeUnit.SECONDS)).isTrue(); assertThat(tar.exitValue()).isZero();
    Path home;
    try (var directories = Files.list(installed)) { home = directories.findFirst().orElseThrow(); }
    try (var files = Files.walk(home)) {
      assertThat(files.filter(Files::isRegularFile).map(Path::toString).toList())
          .noneMatch(path -> path.endsWith(".java") || path.endsWith(".kts") || path.contains("src/test"));
    }
    Path config = privateConfig(); Path out = temporary.toRealPath().resolve("installed-output.json");
    ProcessBuilder builder = new ProcessBuilder(home.resolve("bin/tracepermit-eks-sandbox").toString(), "render", config.toString(), out.toString());
    builder.directory(installed.toFile());
    builder.environment().clear(); builder.environment().put("JAVA_HOME", System.getProperty("java.home"));
    builder.environment().put("PATH", "/usr/bin:/bin");
    Process process = builder.start();
    try {
      assertThat(process.waitFor(10, TimeUnit.SECONDS)).isTrue();
      assertThat(process.exitValue()).isZero();
      assertThat(new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8)).startsWith("EKS_BUNDLE_RENDERED:");
      assertThat(process.getErrorStream().readAllBytes()).isEmpty();
      EksSandboxBundle.verify(config(), Files.readAllBytes(out));
    } finally { process.destroyForcibly(); process.waitFor(5, TimeUnit.SECONDS); }
  }

  private static final class OfflineTripwire extends SecurityManager {
    int effects;
    @Override public void checkPermission(Permission permission) { /* Other local test operations allowed. */ }
    @Override public void checkConnect(String host, int port) { effects++; throw new SecurityException("OFFLINE_NETWORK_REFUSED"); }
    @Override public void checkListen(int port) { effects++; throw new SecurityException("OFFLINE_LISTENER_REFUSED"); }
    @Override public void checkExec(String command) { effects++; throw new SecurityException("OFFLINE_PROCESS_REFUSED"); }
  }
}
