package io.tracepermit.agentcore.lifecyclecli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.tracepermit.sandbox.eks.rehearsal.ContainerContract;
import io.tracepermit.sandbox.eks.rehearsal.DockerIo;
import io.tracepermit.sandbox.eks.rehearsal.OwnedContainer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.UUID;

/** Explicit manual-stage inputs; absence is a failed prerequisite, never a skipped test. */
final class ContainerQualificationEnvironment {
  final DockerIo docker;
  final String image;
  final String source;
  final Path probe;

  ContainerQualificationEnvironment() throws Exception {
    docker = new DockerIo(Path.of(required("docker")));
    image = required("runtimeImage");
    source = required("sourceCommit");
    if (!image.matches("sha256:[a-f0-9]{64}") || !source.matches("[a-f0-9]{40}")) {
      throw new AssertionError("PINNED_CONTAINER_STAGE_INPUT_REQUIRED");
    }
    Path evidence = Path.of(required("evidence")).toRealPath();
    Path checkout = Path.of(System.getProperty("tracepermit.repositoryRoot")).toRealPath();
    if (evidence.startsWith(checkout) || !Files.getPosixFilePermissions(evidence)
        .equals(PosixFilePermissions.fromString("rwx------"))) throw new AssertionError("PRIVATE_EXTERNAL_EVIDENCE_DIRECTORY_REQUIRED");
    probe = evidence.resolve("probe-" + UUID.randomUUID() + ".jar");
    Files.copy(Path.of(required("probeJar")).toRealPath(), probe);
    Files.setPosixFilePermissions(probe, PosixFilePermissions.fromString("r--r--r--"));
    DockerIo.Result inspected = docker.control(List.of("image", "inspect", image));
    if (inspected.exit() != 0) throw new AssertionError("PREPARED_RUNTIME_IMAGE_MISSING");
    JsonNode metadata = new ObjectMapper().readTree(inspected.out());
    if (!metadata.isArray() || metadata.size() != 1
        || !image.equals(metadata.get(0).path("Id").asText())
        || !"linux".equals(metadata.get(0).path("Os").asText())
        || !source.equals(metadata.get(0).path("Config").path("Labels").path(ContainerContract.SOURCE_LABEL).asText())) {
      throw new AssertionError("RUNTIME_IMAGE_SOURCE_MISMATCH");
    }
  }

  OwnedContainer probe(String mode, boolean terminal) throws Exception {
    return new OwnedContainer(docker, new ContainerContract.Plan(image, UUID.randomUUID().toString(),
        source, null, mode, probe, null, terminal));
  }

  OwnedContainer application(String command, String database) throws Exception {
    return new OwnedContainer(docker, new ContainerContract.Plan(image, UUID.randomUUID().toString(),
        source, command, null, null, database, false));
  }

  LifecycleReceipt call(String command, String database, byte[] input, boolean holdOpen) throws Exception {
    try (OwnedContainer owned = application(command, database)) {
      DockerIo.Result result = owned.call(input, holdOpen);
      if (result.err().length != 0) throw new AssertionError("UNEXPECTED_CONTAINER_STDERR");
      LifecycleReceipt receipt = LifecycleReceipt.decode(result.out(), result.exit());
      if (!java.util.Arrays.equals(receipt.bytes(), result.out())) throw new AssertionError("NONCANONICAL_CONTAINER_RECEIPT");
      return receipt;
    } finally { java.util.Arrays.fill(input, (byte) 0); }
  }

  static void fixedOutput(DockerIo.Result result, int exit, String expected) {
    if (result.exit() != exit || result.err().length != 0
        || !expected.equals(new String(result.out(), StandardCharsets.US_ASCII))) {
      throw new AssertionError("FIXED_PROBE_RESULT_MISMATCH");
    }
  }

  private static String required(String name) {
    String value = System.getProperty("tracepermit.container." + name);
    if (value == null || value.isBlank()) throw new AssertionError("CONTAINER_STAGE_PROPERTY_REQUIRED");
    return value;
  }
}
