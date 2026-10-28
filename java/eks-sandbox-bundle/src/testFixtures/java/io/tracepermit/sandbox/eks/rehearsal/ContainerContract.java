package io.tracepermit.sandbox.eks.rehearsal;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Closed test-only container profile. Metadata equality is not EKS isolation/custody proof. */
public final class ContainerContract {
  public static final String OWNER_LABEL = "io.tracepermit.rehearsal.owner";
  public static final String SOURCE_LABEL = "io.tracepermit.source-commit";
  public static final String CLI = "/opt/tracepermit/bin/tracepermit-synthetic-operation-lifecycle";
  public static final String PROBE = "io.tracepermit.sandbox.eks.rehearsal.ContainerInputProbe";
  public static final long MEMORY = 256L * 1024 * 1024;
  public static final int CHANNEL_BYTES = 2_048;
  private static final Set<String> COMMANDS = Set.of("check-config", "inspect", "evaluate", "review", "deliver-synthetic", "reconcile");
  private static final Set<String> PROBES = Set.of("descriptor", "file", "fifo", "unlinked-fifo", "containment", "flood-out", "flood-err");

  private ContainerContract() {}

  public record Plan(String image, String owner, String source, String command,
      String probe, Path probeJar, String database, boolean terminal) {
    public Plan {
      require(image != null && image.matches("sha256:[a-f0-9]{64}"));
      require(owner != null && UUID.fromString(owner).toString().equals(owner));
      require(source != null && source.matches("[a-f0-9]{40}"));
      require((probe == null && COMMANDS.contains(command)) || (command == null && PROBES.contains(probe)));
      require(database == null || database.matches("[a-f0-9]{64}"));
      require(!terminal || "descriptor".equals(probe));
      require(probe == null ? probeJar == null : probeJar != null && probeJar.isAbsolute()
          && !probeJar.toString().matches(".*[,\\r\\n].*"));
    }

    public String network() { return database == null ? "none" : "container:" + database; }

    public List<String> createArguments() {
      var args = new ArrayList<>(List.of("create", "--pull=never", "--interactive",
          "--name=tp-eks-input-" + owner, "--label=" + OWNER_LABEL + "=" + owner,
          "--user=10001:10001", "--read-only", "--cap-drop=ALL",
          "--security-opt=no-new-privileges:true", "--ipc=private", "--cgroupns=private",
          "--pids-limit=64", "--memory=256m", "--memory-swap=256m", "--cpus=2",
          "--tmpfs=/tmp:rw,nosuid,nodev,noexec,size=16m,mode=1777",
          "--network=" + network(), "--restart=no", "--log-driver=none", "--no-healthcheck"));
      if (terminal) args.add("--tty"); // Descriptor-only negative control; never application input.
      if (probe != null) {
        args.add("--mount=type=bind,src=" + probeJar + ",dst=/opt/probe/fixture.jar,readonly");
        args.add("--entrypoint=/opt/java/openjdk/bin/java");
        args.add(image);
        args.addAll(List.of("-Xms16m", "-Xmx64m", "-XX:ActiveProcessorCount=2", "-cp",
            "/opt/tracepermit/lib/*:/opt/probe/fixture.jar", PROBE, probe));
      } else {
        args.add("--entrypoint=" + CLI);
        args.add(image);
        args.add(command);
      }
      return List.copyOf(args);
    }
  }

  public static void verify(JsonNode container, String id, Plan plan) {
    require(container != null && container.isObject());
    require(id.matches("[a-f0-9]{64}") && id.equals(container.path("Id").asText()));
    require(plan.image().equals(container.path("Image").asText()));
    JsonNode config = container.path("Config"), host = container.path("HostConfig");
    require("10001:10001".equals(config.path("User").asText()));
    require(plan.owner().equals(config.path("Labels").path(OWNER_LABEL).asText()));
    require(plan.source().equals(config.path("Labels").path(SOURCE_LABEL).asText()));
    require(config.path("OpenStdin").isBoolean() && config.path("OpenStdin").booleanValue());
    require(config.path("Tty").isBoolean() && config.path("Tty").booleanValue() == plan.terminal());
    require(config.path("Volumes").isMissingNode() || config.path("Volumes").isNull() || config.path("Volumes").isEmpty());
    require(array(config.path("Healthcheck").path("Test"), "NONE"));
    require(host.path("ReadonlyRootfs").isBoolean() && host.path("ReadonlyRootfs").booleanValue());
    require(host.path("Privileged").isBoolean() && !host.path("Privileged").booleanValue());
    require(empty(host.path("CapAdd")) && empty(host.path("Devices")) && empty(host.path("VolumesFrom")) && empty(host.path("PortBindings")));
    require(array(host.path("CapDrop"), "ALL"));
    require(array(host.path("SecurityOpt"), "no-new-privileges:true"));
    require("".equals(host.path("PidMode").asText()));
    require("private".equals(host.path("IpcMode").asText()));
    require("private".equals(host.path("CgroupnsMode").asText()));
    require(plan.network().equals(host.path("NetworkMode").asText()));
    require("no".equals(host.path("RestartPolicy").path("Name").asText()));
    require("none".equals(host.path("LogConfig").path("Type").asText()));
    require(host.path("Memory").longValue() == MEMORY && host.path("MemorySwap").longValue() == MEMORY);
    require(host.path("PidsLimit").longValue() == 64 && host.path("NanoCpus").longValue() == 2_000_000_000L);
    require(!host.path("PublishAllPorts").asBoolean(true));
    JsonNode tmpfs = host.path("Tmpfs");
    require(tmpfs.isObject() && tmpfs.size() == 1
        && "rw,nosuid,nodev,noexec,size=16m,mode=1777".equals(tmpfs.path("/tmp").asText()));
    JsonNode mounts = host.path("Mounts");
    if (plan.probe() == null) require(empty(mounts));
    else {
      require(mounts.isArray() && mounts.size() == 1);
      JsonNode mount = mounts.get(0);
      require("bind".equals(mount.path("Type").asText())
          && plan.probeJar().toString().equals(mount.path("Source").asText())
          && "/opt/probe/fixture.jar".equals(mount.path("Target").asText())
          && mount.path("ReadOnly").asBoolean(false));
    }
    require(empty(host.path("Binds")));
    requireEnvironment(config.path("Env"));
  }

  public static void requireEnvironment(JsonNode environment) {
    if (environment.isNull() || environment.isMissingNode()) return;
    require(environment.isArray());
    for (JsonNode entry : environment) {
      require(entry.isTextual());
      String name = entry.asText().split("=", 2)[0].toUpperCase(java.util.Locale.ROOT);
      require(!name.matches(".*(TOKEN|PASSWORD|SECRET|PRIVATE_KEY|CREDENTIAL|PERMIT).*")
          && !Set.of("HTTP_PROXY", "HTTPS_PROXY", "ALL_PROXY", "NO_PROXY", "JAVA_TOOL_OPTIONS",
              "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS", "JAVA_OPTS", "BASH_ENV", "ENV").contains(name));
    }
  }

  private static boolean array(JsonNode value, String item) {
    return value.isArray() && value.size() == 1 && item.equals(value.get(0).asText());
  }

  private static boolean empty(JsonNode value) {
    return value.isNull() || value.isMissingNode() || ((value.isArray() || value.isObject()) && value.isEmpty());
  }

  private static void require(boolean accepted) {
    if (!accepted) throw new AssertionError("CONTAINER_PROFILE_REFUSED");
  }
}
