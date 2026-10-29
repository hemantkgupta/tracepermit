package io.tracepermit.persistence.operations;

import org.testcontainers.DockerClientFactory;
import org.testcontainers.images.ImagePullPolicy;
import org.testcontainers.utility.DockerImageName;

/** Opt-in focused manual JVM only: never fall back to pulling a missing fixture/helper image. */
public final class PreparedQuarantineImagesOnly implements ImagePullPolicy {
  @Override public boolean shouldPull(DockerImageName image) {
    try {
      DockerClientFactory.instance().client().inspectImageCmd(image.asCanonicalNameString()).exec();
      return false;
    } catch (RuntimeException failure) {
      throw new AssertionError("PREPARED_QUARANTINE_IMAGE_REQUIRED");
    }
  }
}
