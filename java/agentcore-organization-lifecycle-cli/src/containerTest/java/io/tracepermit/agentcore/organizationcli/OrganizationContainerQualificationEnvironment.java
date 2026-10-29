package io.tracepermit.agentcore.organizationcli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.tracepermit.sandbox.eks.rehearsal.DockerIo;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.UUID;

/** Exact manual-stage inputs; missing or mismatched prepared artifacts fail the gate. */
final class OrganizationContainerQualificationEnvironment {
  final DockerIo docker;
  final String image;
  final String source;
  final Path probe;

  OrganizationContainerQualificationEnvironment() throws Exception {
    docker = new DockerIo(Path.of(required("docker")));
    image = required("runtimeImage");
    source = required("sourceCommit");
    String archive = required("archiveSha256");
    if (!image.matches("sha256:[a-f0-9]{64}")
        || !source.matches("[a-f0-9]{40}")
        || !archive.matches("[a-f0-9]{64}")) {
      throw new AssertionError("PINNED_ORGANIZATION_CONTAINER_STAGE_INPUT_REQUIRED");
    }
    Path evidence = Path.of(required("evidence")).toRealPath();
    Path checkout = Path.of(System.getProperty("tracepermit.repositoryRoot")).toRealPath();
    if (evidence.startsWith(checkout)
        || !Files.getPosixFilePermissions(evidence)
            .equals(PosixFilePermissions.fromString("rwx------"))) {
      throw new AssertionError("PRIVATE_EXTERNAL_ORGANIZATION_EVIDENCE_REQUIRED");
    }
    probe = evidence.resolve("organization-probe-" + UUID.randomUUID() + ".jar");
    Files.copy(Path.of(required("probeJar")).toRealPath(), probe);
    Files.setPosixFilePermissions(probe, PosixFilePermissions.fromString("r--r--r--"));
    DockerIo.Result inspected = docker.control(List.of("image", "inspect", image));
    if (inspected.exit() != 0) {
      throw new AssertionError("PREPARED_ORGANIZATION_RUNTIME_IMAGE_MISSING");
    }
    JsonNode metadata = new ObjectMapper().readTree(inspected.out());
    if (!metadata.isArray() || metadata.size() != 1) {
      throw new AssertionError("ORGANIZATION_RUNTIME_IMAGE_SOURCE_MISMATCH");
    }
    JsonNode fixed = metadata.get(0);
    JsonNode config = fixed.path("Config");
    if (!image.equals(fixed.path("Id").asText())
        || !"linux".equals(fixed.path("Os").asText())
        || !source.equals(config.path("Labels").path(OrganizationContainerContract.SOURCE_LABEL).asText())
        || !archive.equals(config.path("Labels").path(OrganizationContainerContract.ARCHIVE_LABEL).asText())
        || !"10001:10001".equals(config.path("User").asText())
        || !"/opt/organization".equals(config.path("WorkingDir").asText())
        || !single(config.path("Entrypoint"), OrganizationContainerContract.CLI)
        || !empty(config.path("Volumes"))
        || !empty(config.path("OnBuild"))) {
      throw new AssertionError("ORGANIZATION_RUNTIME_IMAGE_SOURCE_MISMATCH");
    }
    OrganizationContainerContract.requireEnvironment(config.path("Env"));
  }

  OrganizationOwnedContainer probe(String mode, boolean terminal) throws Exception {
    return new OrganizationOwnedContainer(
        docker,
        new OrganizationContainerContract.Plan(
            image,
            UUID.randomUUID().toString(),
            source,
            null,
            mode,
            probe,
            null,
            terminal));
  }

  OrganizationOwnedContainer application(String command, String database) throws Exception {
    return new OrganizationOwnedContainer(
        docker,
        new OrganizationContainerContract.Plan(
            image,
            UUID.randomUUID().toString(),
            source,
            command,
            null,
            null,
            database,
            false));
  }

  OrganizationOperatorReceipt call(
      String command, String database, byte[] input, boolean holdOpen) throws Exception {
    try (OrganizationOwnedContainer owned = application(command, database)) {
      DockerIo.Result result = owned.call(input, holdOpen);
      if (result.err().length != 0) {
        throw new AssertionError("UNEXPECTED_ORGANIZATION_CONTAINER_STDERR");
      }
      OrganizationOperatorReceipt receipt =
          OrganizationOperatorReceipt.decode(result.out(), result.exit());
      if (!java.util.Arrays.equals(receipt.bytes(), result.out())) {
        throw new AssertionError("NONCANONICAL_ORGANIZATION_CONTAINER_RECEIPT");
      }
      return receipt;
    } finally {
      java.util.Arrays.fill(input, (byte) 0);
    }
  }

  static void fixedOutput(DockerIo.Result result, int exit, String expected) {
    if (result.exit() != exit
        || result.err().length != 0
        || !expected.equals(new String(result.out(), StandardCharsets.US_ASCII))) {
      throw new AssertionError("FIXED_ORGANIZATION_PROBE_RESULT_MISMATCH");
    }
  }

  private static boolean single(JsonNode value, String expected) {
    return value.isArray() && value.size() == 1 && expected.equals(value.get(0).asText());
  }

  private static boolean empty(JsonNode value) {
    return value.isNull() || value.isMissingNode() || value.isEmpty();
  }

  private static String required(String name) {
    String value = System.getProperty("tracepermit.container." + name);
    if (value == null || value.isBlank()) {
      throw new AssertionError("ORGANIZATION_CONTAINER_STAGE_PROPERTY_REQUIRED");
    }
    return value;
  }
}
