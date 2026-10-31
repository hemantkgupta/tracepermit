package io.tracepermit.adapters.http.requester;

import java.net.URI;
import java.time.Duration;
import java.util.function.Consumer;

/** Narrow reuse of the strict one-attempt callback parser; never accepts an external listener. */
public final class ReviewerCallback {
  private ReviewerCallback() {}

  public static String await(URI callback, String state, String issuer, URI authorization,
      Consumer<URI> browser) {
    if (callback == null || !"http".equals(callback.getScheme()) ||
        !"127.0.0.1".equals(callback.getHost()) || callback.getPort() < 1024 ||
        callback.getPort() > 65535 || !"/reviewer/callback".equals(callback.getRawPath()) ||
        callback.getUserInfo() != null || callback.getQuery() != null || callback.getFragment() != null) {
      throw new IllegalArgumentException("REVIEWER_CALLBACK_INVALID");
    }
    try {
      return new RequesterCallbackListener(callback, Duration.ofSeconds(115), Duration.ofSeconds(5))
          .await(state, issuer, authorization, browser::accept);
    } catch (RuntimeException rejected) {
      throw new IllegalStateException("REVIEWER_CALLBACK_REJECTED");
    }
  }
}
