package io.tracepermit.sandbox.eks.rehearsal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Offline harness controls. These tests never contact Docker or start containers. */
@Timeout(20)
final class ContainerContractTest {
  private static final String IMAGE = "sha256:" + "a".repeat(64);
  private static final String ID = "c".repeat(64);
  private static final String SOURCE = "b".repeat(40);
  private static final String OWNER = "12345678-1234-4123-8123-123456789abc";
  private static final ObjectMapper JSON = new ObjectMapper();

  @Test void applicationPlanHasNoPrivateArgumentsHostMountsOrAutomaticPull() {
    var args = plan(null, null, false).createArguments();
    assertThat(args).contains("--pull=never", "--network=none", "--read-only", "--user=10001:10001",
        "--cap-drop=ALL", "--security-opt=no-new-privileges:true", "--log-driver=none", "--restart=no");
    assertThat(args).noneMatch(value -> value.startsWith("--env") || value.startsWith("--mount")
        || value.startsWith("--publish") || value.equals("--tty") || value.contains("docker.sock"));
    assertThat(args.subList(args.size() - 2, args.size())).containsExactly(IMAGE, "check-config");
  }

  @Test void descriptorProbeHasOnlyItsReadOnlyFixtureMount() {
    var args = plan("descriptor", null, false).createArguments();
    assertThat(args).contains("--mount=type=bind,src=/tmp/public-fixture.jar,dst=/opt/probe/fixture.jar,readonly");
    assertThat(args.stream().filter(value -> value.startsWith("--mount"))).hasSize(1);
    assertThat(args).doesNotContain(ContainerContract.CLI);
  }

  @Test void imageNetworkAndTerminalSubstitutionsAreRefused() {
    assertThatThrownBy(() -> new ContainerContract.Plan("latest", OWNER, SOURCE, "check-config", null, null, null, false)).isInstanceOf(AssertionError.class);
    assertThatThrownBy(() -> plan(null, "host", false)).isInstanceOf(AssertionError.class);
    assertThatThrownBy(() -> plan(null, null, true)).isInstanceOf(AssertionError.class);
    assertThatThrownBy(() -> new ContainerContract.Plan(IMAGE, OWNER, SOURCE, null, "descriptor", Path.of("relative.jar"), null, false)).isInstanceOf(AssertionError.class);
  }

  @Test void observedProfileAcceptsTheExactRestrictedApplication() {
    ContainerContract.Plan plan = plan(null, null, false);
    ContainerContract.verify(metadata(plan), ID, plan);
  }

  @Test void observedProfileRejectsSecurityAndResourceDrift() {
    var plan = plan(null, null, false);
    List<Consumer<ObjectNode>> mutations = List.of(
        root -> ((ObjectNode) root.get("Config")).put("User", "0:0"),
        root -> ((ObjectNode) root.get("Config")).put("Tty", true),
        root -> ((ObjectNode) root.get("Config")).put("OpenStdin", false),
        root -> ((ObjectNode) root.get("HostConfig")).put("ReadonlyRootfs", false),
        root -> ((ObjectNode) root.get("HostConfig")).put("Privileged", true),
        root -> ((ObjectNode) root.get("HostConfig")).put("NetworkMode", "host"),
        root -> ((ObjectNode) root.get("HostConfig")).put("PidMode", "host"),
        root -> ((ObjectNode) root.get("HostConfig")).put("PidsLimit", -1),
        root -> ((ObjectNode) root.get("HostConfig")).put("Memory", 0),
        root -> ((ObjectNode) root.get("HostConfig")).put("NanoCpus", 0),
        root -> ((ObjectNode) root.get("HostConfig")).putArray("CapAdd").add("SYS_ADMIN"),
        root -> ((ObjectNode) root.get("HostConfig")).putArray("Binds").add("/var/run/docker.sock:/var/run/docker.sock"),
        root -> ((ObjectNode) root.get("HostConfig")).putObject("PortBindings").put("443/tcp", "unexpected"),
        root -> ((ObjectNode) root.get("HostConfig").get("LogConfig")).put("Type", "json-file"));
    for (var mutation : mutations) {
      ObjectNode root = metadata(plan);
      mutation.accept(root);
      assertThatThrownBy(() -> ContainerContract.verify(root, ID, plan)).isInstanceOf(AssertionError.class);
    }
  }

  @Test void inheritedCredentialEnvironmentIsNeverAcceptedAsAContainerProfile() {
    var plan = plan(null, null, false);
    for (String name : List.of("SYNTHETIC_SECRET", "HTTP_PROXY", "https_proxy", "JAVA_TOOL_OPTIONS")) {
      ObjectNode root = metadata(plan);
      ((ObjectNode) root.get("Config")).putArray("Env").add(name + "=PUBLIC_TEST_SENTINEL");
      assertThatThrownBy(() -> ContainerContract.verify(root, ID, plan)).hasMessage("CONTAINER_PROFILE_REFUSED");
    }
  }

  @Test void outputCaptureAcceptsExactlyTheBoundAndRefusesTheNextByte() throws Exception {
    var accepted = new ByteArrayOutputStream();
    var flag = new AtomicBoolean();
    DockerIo.capture(new ByteArrayInputStream(new byte[2_048]), accepted, 2_048, flag);
    assertThat(accepted.size()).isEqualTo(2_048);
    assertThat(flag.get()).isFalse();
    var refused = new ByteArrayOutputStream();
    DockerIo.capture(new ByteArrayInputStream(new byte[2_049]), refused, 2_048, flag);
    assertThat(flag.get()).isTrue();
    assertThat(refused.size()).isLessThanOrEqualTo(2_048);
  }

  @Test void normalDistributionDoesNotShipProbeFixturesOrJUnit() throws Exception {
    Process tar = new ProcessBuilder("/usr/bin/tar", "-tf", System.getProperty("tracepermit.eks.archive")).start();
    try {
      assertThat(tar.waitFor(10, TimeUnit.SECONDS)).isTrue();
      assertThat(tar.exitValue()).isZero();
      String inventory = new String(tar.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      assertThat(inventory).doesNotContain("test-fixtures", "container-probe", "junit", ".java");
    } finally {
      if (tar.isAlive()) tar.destroyForcibly();
      assertThat(tar.waitFor(5, TimeUnit.SECONDS)).isTrue();
    }
  }

  private static ContainerContract.Plan plan(String probe, String database, boolean tty) {
    return new ContainerContract.Plan(IMAGE, OWNER, SOURCE, probe == null ? "check-config" : null,
        probe, probe == null ? null : Path.of("/tmp/public-fixture.jar"), database, tty);
  }

  private static ObjectNode metadata(ContainerContract.Plan plan) {
    ObjectNode root = JSON.createObjectNode().put("Id", ID).put("Image", IMAGE);
    ObjectNode config = root.putObject("Config").put("User", "10001:10001").put("Tty", plan.terminal()).put("OpenStdin", true);
    config.putObject("Labels").put(ContainerContract.OWNER_LABEL, OWNER).put(ContainerContract.SOURCE_LABEL, SOURCE);
    config.putObject("Healthcheck").putArray("Test").add("NONE");
    ObjectNode host = root.putObject("HostConfig").put("ReadonlyRootfs", true).put("Privileged", false)
        .put("PidMode", "").put("IpcMode", "private").put("CgroupnsMode", "private").put("NetworkMode", plan.network())
        .put("Memory", ContainerContract.MEMORY).put("MemorySwap", ContainerContract.MEMORY)
        .put("PidsLimit", 64).put("NanoCpus", 2_000_000_000L).put("PublishAllPorts", false);
    host.putArray("CapDrop").add("ALL");
    host.putArray("SecurityOpt").add("no-new-privileges:true");
    host.putObject("RestartPolicy").put("Name", "no");
    host.putObject("LogConfig").put("Type", "none");
    host.putObject("Tmpfs").put("/tmp", "rw,nosuid,nodev,noexec,size=16m,mode=1777");
    return root;
  }
}
