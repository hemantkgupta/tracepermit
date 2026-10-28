package io.tracepermit.sandbox.eks.rehearsal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Lost create acknowledgement never becomes success; exact intent is used for cleanup only. */
public final class OwnedContainerCreation {
  private static final ObjectMapper JSON = new ObjectMapper();

  private OwnedContainerCreation() {}

  public static String create(DockerIo docker, List<String> arguments,
      String name, String owner, String image) throws Exception {
    Path evidence = Path.of(System.getProperty("tracepermit.container.evidence")).toRealPath();
    var intent = JSON.createObjectNode().put("name", name).put("owner", owner).put("imageId", image)
        .put("purpose", "RESOURCE_OWNERSHIP_ONLY_NOT_COMMAND_ACK");
    Files.writeString(evidence.resolve("creation-" + owner + ".json"), intent.toString() + "\n");
    try {
      DockerIo.Result result = docker.control(arguments);
      String id = new String(result.out(), StandardCharsets.US_ASCII).trim();
      if (result.exit() == 0 && id.matches("[a-f0-9]{64}")) return id;
    } catch (Exception | AssertionError failure) {
      // The daemon may have created the object. Do not retry creation or reconstruct success.
    }
    boolean interrupted = Thread.interrupted();
    try {
      DockerIo.Result readback = docker.control(List.of("container", "inspect", name));
      if (readback.exit() != 0) throw new AssertionError("CREATE_ACK_UNKNOWN_RECOVERY_UNCONFIRMED");
      JsonNode objects = JSON.readTree(readback.out());
      if (!objects.isArray() || objects.size() != 1) throw new AssertionError("CREATE_ACK_UNKNOWN_RECOVERY_UNCONFIRMED");
      JsonNode object = objects.get(0);
      String id = object.path("Id").asText();
      if (!id.matches("[a-f0-9]{64}") || !image.equals(object.path("Image").asText())
          || !owner.equals(object.path("Config").path("Labels").path(ContainerContract.OWNER_LABEL).asText())) {
        throw new AssertionError("CREATE_ACK_UNKNOWN_OWNERSHIP_NOT_CONFIRMED_NO_REMOVE");
      }
      if (docker.control(List.of("container", "rm", "--force", id)).exit() != 0) {
        throw new AssertionError("CREATE_ACK_UNKNOWN_CLEANUP_UNCONFIRMED");
      }
      DockerIo.Result absence = docker.control(List.of("container", "ls", "--all", "--quiet", "--no-trunc", "--filter", "id=" + id));
      if (absence.exit() != 0 || absence.out().length != 0) throw new AssertionError("CREATE_ACK_UNKNOWN_CLEANUP_UNCONFIRMED");
      Files.writeString(evidence.resolve("creation-" + owner + "-recovery.json"),
          JSON.createObjectNode().put("owner", owner).put("containerId", id)
              .put("removedObserved", true).put("commandAcknowledged", false).toString() + "\n");
    } finally {
      if (interrupted) Thread.currentThread().interrupt();
    }
    throw new AssertionError("CREATE_ACK_UNKNOWN_STOP_NO_RETRY");
  }
}
