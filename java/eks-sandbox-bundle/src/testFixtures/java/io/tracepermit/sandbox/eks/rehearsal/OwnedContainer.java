package io.tracepermit.sandbox.eks.rehearsal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;

/** Owns only the full ID returned by its own create call. No listing-based or name-based kill. */
public final class OwnedContainer implements AutoCloseable {
  private static final ObjectMapper JSON = new ObjectMapper();
  private final DockerIo docker;
  private final ContainerContract.Plan plan;
  private final String id;
  private boolean removed;
  public final CountDownLatch inputSent = new CountDownLatch(1);

  public OwnedContainer(DockerIo docker, ContainerContract.Plan plan) throws Exception {
    this.docker = docker;
    this.plan = plan;
    id = OwnedContainerCreation.create(docker, plan.createArguments(),
        "tp-eks-input-" + plan.owner(), plan.owner(), plan.image());
    try { ContainerContract.verify(inspect(), id, plan); }
    catch (Throwable failure) { close(); throw failure; }
  }

  public JsonNode inspect() throws Exception {
    DockerIo.Result result = docker.control(List.of("container", "inspect", id));
    if (result.exit() != 0) throw new AssertionError("OWNED_CONTAINER_INSPECTION_FAILED");
    JsonNode root = JSON.readTree(result.out());
    if (!root.isArray() || root.size() != 1) throw new AssertionError("OWNED_CONTAINER_INSPECTION_INVALID");
    return root.get(0);
  }

  public DockerIo.Result call(byte[] input, boolean holdOpen) throws Exception {
    try {
      List<String> arguments = plan.terminal()
          ? List.of("start", "--attach", id)
          : List.of("start", "--attach", "--interactive", id);
      DockerIo.Result result = docker.run(arguments, input, holdOpen,
          Duration.ofSeconds(70), ContainerContract.CHANNEL_BYTES, inputSent);
      JsonNode observed = inspect();
      ContainerContract.verify(observed, id, plan);
      if (observed.path("State").path("Running").asBoolean(true)
          || observed.path("State").path("ExitCode").intValue() != result.exit()) {
        throw new AssertionError("CONTAINER_EXIT_ACKNOWLEDGEMENT_MISMATCH");
      }
      record(result, false);
      return result;
    } catch (InterruptedException failure) {
      close();
      Thread.currentThread().interrupt();
      throw failure;
    }
  }

  public boolean removalObserved() { return removed; }

  @Override public synchronized void close() {
    if (removed) return;
    boolean interrupted = Thread.interrupted();
    long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
    try {
      DockerIo.Result owner = bounded(List.of("container", "inspect", "--format",
          "{{index .Config.Labels \"" + ContainerContract.OWNER_LABEL + "\"}}", id), deadline);
      if (owner.exit() != 0 || !plan.owner().equals(new String(owner.out(), StandardCharsets.US_ASCII).trim())) {
        throw new AssertionError("OWNERSHIP_NOT_CONFIRMED_NO_CLEANUP_ATTEMPTED");
      }
      DockerIo.Result removedResult = bounded(List.of("container", "rm", "--force", id), deadline);
      if (removedResult.exit() != 0) throw new AssertionError("OWNED_CONTAINER_REMOVE_FAILED");
      DockerIo.Result absence = bounded(List.of("container", "ls", "--all", "--quiet", "--no-trunc", "--filter", "id=" + id), deadline);
      if (absence.exit() != 0 || absence.out().length != 0) throw new AssertionError("OWNED_CONTAINER_REMOVAL_UNCONFIRMED");
      removed = true;
      record(null, true);
    } catch (InterruptedException failure) {
      interrupted = true;
      throw new AssertionError("OWNED_CONTAINER_CLEANUP_INTERRUPTED");
    } catch (Exception failure) {
      throw new AssertionError("OWNED_CONTAINER_CLEANUP_UNCONFIRMED");
    } finally {
      if (interrupted) Thread.currentThread().interrupt();
    }
  }

  private DockerIo.Result bounded(List<String> arguments, long deadline) throws Exception {
    long remaining = deadline - System.nanoTime();
    if (remaining <= 0) throw new AssertionError("OWNED_CONTAINER_CLEANUP_BOUND");
    return docker.run(arguments, new byte[0], false, Duration.ofNanos(remaining), 262_144, null);
  }

  private void record(DockerIo.Result result, boolean cleanup) throws Exception {
    String configured = System.getProperty("tracepermit.container.evidence");
    if (configured == null) throw new AssertionError("PRIVATE_CONTAINER_EVIDENCE_REQUIRED");
    Path directory = Path.of(configured).toRealPath();
    var receipt = JSON.createObjectNode();
    receipt.put("containerId", id).put("owner", plan.owner()).put("imageId", plan.image())
        .put("sourceCommit", plan.source()).put("removedObserved", cleanup)
        .put("probe", plan.probe() == null ? "APPLICATION" : plan.probe());
    if (result != null) receipt.put("exit", result.exit()).put("stdoutBytes", result.out().length).put("stderrBytes", result.err().length);
    Files.writeString(directory.resolve("container-" + plan.owner() + (cleanup ? "-cleanup.json" : "-exit.json")), receipt.toString() + "\n");
  }
}
