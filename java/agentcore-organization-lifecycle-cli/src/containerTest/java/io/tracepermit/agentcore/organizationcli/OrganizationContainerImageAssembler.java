package io.tracepermit.agentcore.organizationcli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.tracepermit.sandbox.eks.rehearsal.DockerIo;
import io.tracepermit.sandbox.eks.rehearsal.OwnedContainerCreation;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/** Human-run local-only assembly from the accepted archive and one cached Java21 base. */
public final class OrganizationContainerImageAssembler {
  static final String FIXED_BASE =
      "eclipse-temurin@sha256:23cc5734ff46e776f51ce66eb9ee08444fd47caabec0a95b7eb6e94be2d3696d";
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String INSTALL =
      """
      printf '%s  %s\\n' "$1" /tmp/organization.tar | sha256sum -c -
      mkdir -p /opt/organization
      tar -xf /tmp/organization.tar --strip-components=1 -C /opt/organization
      test -x /opt/organization/bin/tracepermit-synthetic-organization-operator
      test -x /opt/java/openjdk/bin/java
      rm /tmp/organization.tar
      chown -R root:root /opt/organization
      chmod -R a+rX,a-w /opt/organization
      """;

  private OrganizationContainerImageAssembler() {}

  public static void main(String[] arguments) throws Exception {
    if (arguments.length != 0) throw new AssertionError("ORGANIZATION_ASSEMBLY_USAGE");
    DockerIo docker = new DockerIo(Path.of(required("docker")));
    String base = required("baseImage");
    String source = required("sourceCommit");
    String manifest = required("sourceManifestHash");
    if (!FIXED_BASE.equals(base)
        || !source.matches("[a-f0-9]{40}")
        || !manifest.matches("[a-f0-9]{64}")) {
      throw new AssertionError("ORGANIZATION_ASSEMBLY_INPUT_INVALID");
    }
    Path evidence = Path.of(required("evidence")).toRealPath();
    Path archive = Path.of(required("organizationArchive")).toRealPath();
    if (Files.size(archive) < 1 || Files.size(archive) > 64L * 1024 * 1024) {
      throw new AssertionError("ORGANIZATION_ASSEMBLY_ARCHIVE_BOUND");
    }
    String archiveHash = digest(archive);
    Path archiveCopy = evidence.resolve("organization.tar");
    Files.copy(archive, archiveCopy);
    if (!archiveHash.equals(digest(archiveCopy))) {
      throw new AssertionError("ORGANIZATION_ASSEMBLY_ARCHIVE_COPY_MISMATCH");
    }

    DockerIo.Result inspected = docker.control(List.of("image", "inspect", base));
    JsonNode metadata = inspected.exit() == 0 ? JSON.readTree(inspected.out()) : null;
    if (metadata == null || !metadata.isArray() || metadata.size() != 1) {
      throw new AssertionError("CACHED_ORGANIZATION_BASE_IMAGE_REQUIRED");
    }
    JsonNode image = metadata.get(0);
    OrganizationContainerContract.requireEnvironment(image.path("Config").path("Env"));
    String baseId = image.path("Id").asText();
    if (!baseId.matches("sha256:[a-f0-9]{64}")
        || !"linux".equals(image.path("Os").asText())
        || !List.of("amd64", "arm64").contains(image.path("Architecture").asText())
        || !contains(image.path("RepoDigests"), base)
        || !empty(image.path("Config").path("Volumes"))
        || !empty(image.path("Config").path("OnBuild"))) {
      throw new AssertionError("CACHED_ORGANIZATION_BASE_PROFILE_REFUSED");
    }
    DockerIo.Result platform =
        docker.control(List.of("version", "--format", "{{.Server.Os}}/{{.Server.Arch}}"));
    if (platform.exit() != 0
        || !("linux/" + image.path("Architecture").asText())
            .equals(new String(platform.out(), StandardCharsets.US_ASCII).trim())) {
      throw new AssertionError("NATIVE_CACHED_ORGANIZATION_BASE_ARCHITECTURE_REQUIRED");
    }

    String owner = UUID.randomUUID().toString();
    String name = "tp-org-assembly-" + owner;
    String id =
        OwnedContainerCreation.create(
            docker,
            List.of(
                "create",
                "--pull=never",
                "--network=none",
                "--name=" + name,
                "--label=" + OrganizationContainerContract.OWNER_LABEL + "=" + owner,
                "--user=0:0",
                "--memory=256m",
                "--pids-limit=64",
                "--cpus=2",
                "--log-driver=none",
                "--no-healthcheck",
                "--entrypoint=/bin/sh",
                baseId,
                "-eu",
                "-c",
                INSTALL,
                "--",
                archiveHash),
            name,
            owner,
            baseId);
    try {
      requireZero(
          docker.control(List.of("cp", archiveCopy.toString(), id + ":/tmp/organization.tar")));
      requireZero(
          docker.run(
              List.of("start", "--attach", id),
              new byte[0],
              false,
              Duration.ofSeconds(60),
              4_096,
              null));
      DockerIo.Result state = docker.control(List.of("container", "inspect", id));
      requireZero(state);
      JsonNode container = JSON.readTree(state.out()).get(0);
      if (container.path("State").path("Running").asBoolean(true)
          || container.path("State").path("ExitCode").intValue() != 0
          || !owner
              .equals(
                  container
                      .path("Config")
                      .path("Labels")
                      .path(OrganizationContainerContract.OWNER_LABEL)
                      .asText())) {
        throw new AssertionError("ORGANIZATION_ASSEMBLY_EXIT_OR_OWNERSHIP_UNCONFIRMED");
      }
      DockerIo.Result committed =
          docker.run(
              List.of(
                  "commit",
                  "--change",
                  "USER 10001:10001",
                  "--change",
                  "WORKDIR /opt/organization",
                  "--change",
                  "ENTRYPOINT [\""
                      + OrganizationContainerContract.CLI
                      + "\"]",
                  "--change",
                  "CMD []",
                  "--change",
                  "LABEL " + OrganizationContainerContract.SOURCE_LABEL + "=" + source,
                  "--change",
                  "LABEL "
                      + OrganizationContainerContract.ARCHIVE_LABEL
                      + "="
                      + archiveHash,
                  "--change",
                  "LABEL " + OrganizationContainerContract.BASE_LABEL + "=" + baseId,
                  id),
              new byte[0],
              false,
              Duration.ofSeconds(60),
              4_096,
              null);
      requireZero(committed);
      String runtime = new String(committed.out(), StandardCharsets.US_ASCII).trim();
      if (!runtime.matches("sha256:[a-f0-9]{64}")
          || !archiveHash.equals(digest(archive))) {
        throw new AssertionError("ORGANIZATION_ASSEMBLY_ARTIFACT_IDENTITY_CHANGED");
      }
      var receipt = JSON.createObjectNode();
      receipt
          .put("sourceCommit", source)
          .put("sourceManifestSha256", manifest)
          .put("baseReference", base)
          .put("baseImageId", baseId)
          .put("architecture", image.path("Architecture").asText())
          .put("runtimeImageId", runtime)
          .put("organizationArchiveSha256", archiveHash)
          .put("installRoot", "/opt/organization")
          .put("entrypoint", OrganizationContainerContract.CLI)
          .put("registryPull", false)
          .put("imagePush", false)
          .put("qualification", "UNRUN");
      Files.writeString(
          evidence.resolve("assembled-organization-image.json"), receipt + "\n");
      System.out.println("LOCAL_ORGANIZATION_IMAGE_ASSEMBLED");
    } finally {
      DockerIo.Result ownerRead =
          docker.control(
              List.of(
                  "container",
                  "inspect",
                  "--format",
                  "{{index .Config.Labels \""
                      + OrganizationContainerContract.OWNER_LABEL
                      + "\"}}",
                  id));
      if (ownerRead.exit() != 0
          || !owner.equals(new String(ownerRead.out(), StandardCharsets.US_ASCII).trim())) {
        throw new AssertionError("ORGANIZATION_ASSEMBLY_OWNERSHIP_NOT_CONFIRMED_NO_REMOVE");
      }
      requireZero(docker.control(List.of("container", "rm", "--force", id)));
      DockerIo.Result absence =
          docker.control(
              List.of(
                  "container",
                  "ls",
                  "--all",
                  "--quiet",
                  "--no-trunc",
                  "--filter",
                  "id=" + id));
      if (absence.exit() != 0 || absence.out().length != 0) {
        throw new AssertionError("ORGANIZATION_ASSEMBLY_CLEANUP_UNCONFIRMED");
      }
      var cleanup =
          JSON.createObjectNode()
              .put("owner", owner)
              .put("containerId", id)
              .put("sourceCommit", source)
              .put("removedObserved", true);
      Files.writeString(
          evidence.resolve("organization-assembly-cleanup.json"), cleanup + "\n");
    }
  }

  static String digest(Path file) throws Exception {
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
    if (value == null || value.isBlank()) {
      throw new AssertionError("ORGANIZATION_ASSEMBLY_PROPERTY_REQUIRED");
    }
    return value;
  }

  private static void requireZero(DockerIo.Result result) {
    if (result.exit() != 0) {
      throw new AssertionError("OWNED_ORGANIZATION_ASSEMBLY_COMMAND_FAILED");
    }
  }

  private static boolean empty(JsonNode value) {
    return value.isNull() || value.isMissingNode() || value.isEmpty();
  }

  private static boolean contains(JsonNode values, String expected) {
    if (!values.isArray()) return false;
    for (JsonNode value : values) if (expected.equals(value.asText())) return true;
    return false;
  }
}
