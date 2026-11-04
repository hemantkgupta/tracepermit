package io.tracepermit.agentcore.organizationcli;

import org.testcontainers.images.ImagePullPolicy;
import org.testcontainers.utility.DockerImageName;

/** Human-selected qualification policy: a missing cached image must fail, never download. */
public final class PreparedAuth0QualificationImagesOnly implements ImagePullPolicy {
  @Override
  public boolean shouldPull(DockerImageName image) {
    return false;
  }
}
