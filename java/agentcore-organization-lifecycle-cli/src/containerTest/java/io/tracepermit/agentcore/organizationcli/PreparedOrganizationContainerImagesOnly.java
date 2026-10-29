package io.tracepermit.agentcore.organizationcli;

import io.tracepermit.sandbox.eks.rehearsal.DockerIo;
import java.nio.file.Path;
import java.util.List;
import org.testcontainers.images.ImagePullPolicy;
import org.testcontainers.utility.DockerImageName;

/** Qualification JVM policy: every helper image must already be cached locally. */
public final class PreparedOrganizationContainerImagesOnly implements ImagePullPolicy {
  @Override
  public boolean shouldPull(DockerImageName image) {
    try {
      var docker =
          new DockerIo(Path.of(System.getProperty("tracepermit.container.docker")));
      if (docker
              .control(
                  List.of(
                      "image", "inspect", "--format", "{{.Id}}", image.asCanonicalNameString()))
              .exit()
          != 0) {
        throw new AssertionError("PREPARED_ORGANIZATION_TESTCONTAINERS_IMAGE_MISSING");
      }
      return false;
    } catch (Exception failure) {
      throw new AssertionError("PREPARED_ORGANIZATION_IMAGE_INSPECTION_FAILED");
    }
  }
}
