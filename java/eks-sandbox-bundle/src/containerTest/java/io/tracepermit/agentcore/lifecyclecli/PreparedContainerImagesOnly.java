package io.tracepermit.agentcore.lifecyclecli;

import io.tracepermit.sandbox.eks.rehearsal.DockerIo;
import java.nio.file.Path;
import java.util.List;
import org.testcontainers.images.ImagePullPolicy;
import org.testcontainers.utility.DockerImageName;

/** Explicit qualification JVM only: missing application/helper images refuse, never pull. */
public final class PreparedContainerImagesOnly implements ImagePullPolicy {
  @Override public boolean shouldPull(DockerImageName image) {
    try {
      var docker = new DockerIo(Path.of(System.getProperty("tracepermit.container.docker")));
      if (docker.control(List.of("image", "inspect", "--format", "{{.Id}}", image.asCanonicalNameString())).exit() != 0) {
        throw new AssertionError("PREPARED_TESTCONTAINERS_IMAGE_MISSING");
      }
      return false;
    } catch (Exception failure) {
      throw new AssertionError("PREPARED_IMAGE_INSPECTION_FAILED");
    }
  }
}
