package io.tracepermit.adapters.http;

import java.nio.file.Path;

public final class DemoActionFeed {
  private DemoActionFeed() {}

  public static InMemoryActionFeed load(Path repositoryRoot) {
    return DemoControlPlaneData.load(repositoryRoot).actionFeed();
  }
}
