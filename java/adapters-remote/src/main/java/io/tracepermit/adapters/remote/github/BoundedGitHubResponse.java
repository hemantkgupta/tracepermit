package io.tracepermit.adapters.remote.github;

import java.io.IOException;
import java.io.InputStream;

/** Bounded decoded-body read shared by confirmation and read-only reconciliation. */
final class BoundedGitHubResponse {
  private BoundedGitHubResponse() {}

  /**
   * Reads at most the configured limit plus one overflow byte, without trusting Content-Length.
   * The caller owns the response/stream and must reject a returned array longer than the limit.
   */
  static byte[] read(InputStream input, int maximumBytes) throws IOException {
    if (maximumBytes <= 0 || maximumBytes > 1_048_576) {
      throw new IllegalArgumentException("maximumBytes is outside safe limits.");
    }
    return input.readNBytes(maximumBytes + 1);
  }
}
