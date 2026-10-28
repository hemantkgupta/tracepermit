package io.tracepermit.sandbox.eks.rehearsal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/** Human-run, local-only equivalent of the checksum/extract image recipe; no registry builder. */
public final class RehearsalImageAssembler {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String INSTALL = """
      printf '%s  %s\\n' "$1" /tmp/lifecycle.tar "$2" /tmp/sandbox.tar | sha256sum -c -
      mkdir -p /opt/tracepermit /opt/sandbox
      tar -xf /tmp/lifecycle.tar --strip-components=1 -C /opt/tracepermit
      tar -xf /tmp/sandbox.tar --strip-components=1 -C /opt/sandbox
      test -x /opt/tracepermit/bin/tracepermit-synthetic-operation-lifecycle
      test -x /opt/java/openjdk/bin/java
      rm /tmp/lifecycle.tar /tmp/sandbox.tar
      chown -R root:root /opt/tracepermit /opt/sandbox
      chmod -R a-w /opt/tracepermit /opt/sandbox
      """;

  private RehearsalImageAssembler() {}

  public static void main(String[] arguments) throws Exception {
    if (arguments.length != 0) throw new AssertionError("ASSEMBLY_USAGE");
    DockerIo docker = new DockerIo(Path.of(required("docker")));
    String base = required("baseImage"), source = required("sourceCommit");
    if (!base.matches("[a-z0-9][a-z0-9._:/-]{0,220}@sha256:[a-f0-9]{64}")
        || !source.matches("[a-f0-9]{40}")) throw new AssertionError("ASSEMBLY_INPUT_INVALID");
    Path evidence = Path.of(required("evidence")).toRealPath();
    Path lifecycle = Path.of(required("lifecycleArchive")).toRealPath();
    Path sandbox = Path.of(required("bundleArchive")).toRealPath();
    if (Files.size(lifecycle) < 1 || Files.size(lifecycle) > 64L * 1024 * 1024
        || Files.size(sandbox) < 1 || Files.size(sandbox) > 64L * 1024 * 1024) {
      throw new AssertionError("ASSEMBLY_ARCHIVE_BOUND");
    }
    String lifecycleHash = digest(lifecycle), sandboxHash = digest(sandbox);
    // Only public compiled archives are staged; no host checkout or private frame is mounted.
    Path lifecycleCopy = evidence.resolve("lifecycle.tar");
    Path sandboxCopy = evidence.resolve("sandbox.tar");
    Files.copy(lifecycle, lifecycleCopy);
    Files.copy(sandbox, sandboxCopy);
    if (!lifecycleHash.equals(digest(lifecycleCopy)) || !sandboxHash.equals(digest(sandboxCopy))) {
      throw new AssertionError("ASSEMBLY_ARCHIVE_COPY_MISMATCH");
    }
    // Inspect cannot pull. Create below uses only the resulting immutable local ID and --pull=never.
    DockerIo.Result inspected = docker.control(List.of("image", "inspect", base));
    JsonNode metadata = inspected.exit() == 0 ? JSON.readTree(inspected.out()) : null;
    if (metadata == null || !metadata.isArray() || metadata.size() != 1) throw new AssertionError("CACHED_BASE_IMAGE_REQUIRED");
    JsonNode image = metadata.get(0);
    ContainerContract.requireEnvironment(image.path("Config").path("Env"));
    String baseId = image.path("Id").asText();
    if (!baseId.matches("sha256:[a-f0-9]{64}") || !"linux".equals(image.path("Os").asText())
        || !List.of("amd64", "arm64").contains(image.path("Architecture").asText())
        || !contains(image.path("RepoDigests"), base)
        || !empty(image.path("Config").path("Volumes"))
        || !empty(image.path("Config").path("OnBuild"))) {
      throw new AssertionError("CACHED_BASE_PROFILE_REFUSED");
    }
    DockerIo.Result platform = docker.control(List.of("version", "--format", "{{.Server.Os}}/{{.Server.Arch}}"));
    if (platform.exit() != 0 || !("linux/" + image.path("Architecture").asText())
        .equals(new String(platform.out(), StandardCharsets.US_ASCII).trim())) {
      throw new AssertionError("NATIVE_CACHED_BASE_ARCHITECTURE_REQUIRED");
    }
    String owner = UUID.randomUUID().toString();
    String id = OwnedContainerCreation.create(docker, List.of("create", "--pull=never", "--network=none",
        "--name=tp-eks-assembly-" + owner, "--label=" + ContainerContract.OWNER_LABEL + "=" + owner,
        "--user=0:0", "--memory=256m", "--pids-limit=64", "--cpus=2", "--log-driver=none",
        "--no-healthcheck", "--entrypoint=/bin/sh", baseId,
        "-eu", "-c", INSTALL, "--", lifecycleHash, sandboxHash),
        "tp-eks-assembly-" + owner, owner, baseId);
    try {
      requireZero(docker.control(List.of("cp", lifecycleCopy.toString(), id + ":/tmp/lifecycle.tar")));
      requireZero(docker.control(List.of("cp", sandboxCopy.toString(), id + ":/tmp/sandbox.tar")));
      requireZero(docker.run(List.of("start", "--attach", id), new byte[0], false,
          Duration.ofSeconds(60), 4_096, null));
      DockerIo.Result state = docker.control(List.of("container", "inspect", id));
      requireZero(state);
      JsonNode container = JSON.readTree(state.out()).get(0);
      if (container.path("State").path("Running").asBoolean(true)
          || container.path("State").path("ExitCode").intValue() != 0
          || !owner.equals(container.path("Config").path("Labels").path(ContainerContract.OWNER_LABEL).asText())) {
        throw new AssertionError("ASSEMBLY_EXIT_OR_OWNERSHIP_UNCONFIRMED");
      }
      DockerIo.Result committed = docker.run(List.of("commit",
          "--change", "USER 10001:10001", "--change", "WORKDIR /opt/sandbox",
          "--change", "ENTRYPOINT [\"/opt/sandbox/bin/tracepermit-eks-sandbox\"]",
          "--change", "CMD []",
          "--change", "LABEL " + ContainerContract.SOURCE_LABEL + "=" + source,
          "--change", "LABEL io.tracepermit.lifecycle-archive-sha256=" + lifecycleHash,
          "--change", "LABEL io.tracepermit.sandbox-archive-sha256=" + sandboxHash,
          "--change", "LABEL io.tracepermit.rehearsal-base-image=" + baseId, id),
          new byte[0], false, Duration.ofSeconds(60), 4_096, null);
      requireZero(committed);
      String runtime = new String(committed.out(), StandardCharsets.US_ASCII).trim();
      if (!runtime.matches("sha256:[a-f0-9]{64}") || !lifecycleHash.equals(digest(lifecycle)) || !sandboxHash.equals(digest(sandbox))) {
        throw new AssertionError("ASSEMBLY_ARTIFACT_IDENTITY_CHANGED");
      }
      var receipt = JSON.createObjectNode();
      receipt.put("sourceCommit", source).put("baseReference", base).put("baseImageId", baseId)
          .put("architecture", image.path("Architecture").asText()).put("runtimeImageId", runtime)
          .put("lifecycleArchiveSha256", lifecycleHash).put("sandboxArchiveSha256", sandboxHash)
          .put("registryPull", false).put("imagePush", false).put("qualification", "UNRUN");
      Files.writeString(evidence.resolve("assembled-image.json"), receipt.toString() + "\n");
      System.out.println("LOCAL_REHEARSAL_IMAGE_ASSEMBLED");
    } finally {
      DockerIo.Result ownerRead = docker.control(List.of("container", "inspect", "--format",
          "{{index .Config.Labels \"" + ContainerContract.OWNER_LABEL + "\"}}", id));
      if (ownerRead.exit() != 0 || !owner.equals(new String(ownerRead.out(), StandardCharsets.US_ASCII).trim())) {
        throw new AssertionError("ASSEMBLY_OWNERSHIP_NOT_CONFIRMED_NO_REMOVE");
      }
      requireZero(docker.control(List.of("container", "rm", "--force", id)));
      DockerIo.Result absence = docker.control(List.of("container", "ls", "--all", "--quiet", "--no-trunc", "--filter", "id=" + id));
      if (absence.exit() != 0 || absence.out().length != 0) throw new AssertionError("ASSEMBLY_CLEANUP_UNCONFIRMED");
      var cleanup = JSON.createObjectNode().put("owner", owner).put("containerId", id)
          .put("sourceCommit", source).put("removedObserved", true);
      Files.writeString(evidence.resolve("assembly-cleanup.json"), cleanup.toString() + "\n");
    }
  }

  public static String digest(Path file) throws Exception {
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    try (var input = Files.newInputStream(file)) {
      byte[] buffer = new byte[65_536];
      int count;
      while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
    }
    return HexFormat.of().formatHex(digest.digest());
  }

  private static String required(String name) {
    String value = System.getProperty("tracepermit.container." + name);
    if (value == null || value.isBlank()) throw new AssertionError("ASSEMBLY_PROPERTY_REQUIRED");
    return value;
  }

  private static void requireZero(DockerIo.Result result) {
    if (result.exit() != 0) throw new AssertionError("OWNED_ASSEMBLY_COMMAND_FAILED");
  }

  private static boolean empty(JsonNode value) { return value.isNull() || value.isMissingNode() || value.isEmpty(); }
  private static boolean contains(JsonNode values, String expected) {
    if (!values.isArray()) return false;
    for (JsonNode value : values) if (expected.equals(value.asText())) return true;
    return false;
  }
}
