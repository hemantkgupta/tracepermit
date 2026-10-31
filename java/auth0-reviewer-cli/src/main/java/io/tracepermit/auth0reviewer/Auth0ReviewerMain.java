package io.tracepermit.auth0reviewer;

import io.tracepermit.adapters.http.requester.ReviewerCallback;
import io.tracepermit.adapters.remote.SsrfSafeHttpsJwkSetSource;
import io.tracepermit.agentcore.cli.InstalledPrivateInput;
import java.awt.Desktop;
import java.io.DataInputStream;
import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.*;

/** Explicit check-config or login only; one length-prefixed anonymous-pipe frame, then EOF. */
public final class Auth0ReviewerMain {
  private Auth0ReviewerMain() {}
  public static void main(String[] args) {
    int exit = 1;
    try {
      if (args.length != 1 || !Set.of("check-config", "login").contains(args[0])) {
        System.err.println("Usage: tracepermit-auth0-reviewer check-config|login");
        System.exit(64);
      }
      if (!InstalledPrivateInput.anonymousPipe()) throw new IllegalArgumentException();
      byte[] bytes = frame();
      ReviewerConfiguration config;
      try { config = ReviewerConfiguration.parse(bytes); }
      finally { Arrays.fill(bytes, (byte) 0); }
      if ("check-config".equals(args[0])) {
        System.out.println("{\"schemaVersion\":1,\"outcome\":\"CONFIG_VALID\",\"executionEnabled\":false}");
      } else {
        var transport = new SsrfSafeHttpsJwkSetSource(Set.of(config.issuer().resolve("oauth/token")),
            Duration.ofSeconds(3), Duration.ofSeconds(5));
        var connector = boundedLogin(() -> new ReviewerLogin(config,
            (state, authorization) -> ReviewerCallback.await(ReviewerConfiguration.CALLBACK, state,
                config.issuer().toString(), authorization, uri -> {
                  try {
                    if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.BROWSE))
                      throw new IllegalStateException();
                    Desktop.getDesktop().browse(uri);
                  } catch (Exception denied) { throw new IllegalStateException("BROWSER_UNAVAILABLE"); }
                }),
            form -> transport.postForm(config.issuer().resolve("oauth/token"), form, null),
            new java.security.SecureRandom(), Clock.systemUTC()).login());
        System.out.println("{\"schemaVersion\":1,\"outcome\":\"REVIEWER_VERIFIED\",\"credentialFingerprint\":\"" +
            connector.credentialFingerprint().value() + "\",\"executionEnabled\":false}");
      }
      exit = 0;
    } catch (Exception denied) { System.err.println("REVIEWER_REJECTED"); }
    System.exit(exit);
  }

  private static io.tracepermit.identity.AuthenticatedConnector boundedLogin(
      java.util.function.Supplier<io.tracepermit.identity.AuthenticatedConnector> call) throws Exception {
    ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
      Thread thread = new Thread(r, "reviewer-login"); thread.setDaemon(true); return thread;
    });
    try { return executor.submit(call::get).get(120, TimeUnit.SECONDS); }
    finally { executor.shutdownNow(); }
  }

  private static byte[] frame() throws Exception {
    ExecutorService reader = Executors.newSingleThreadExecutor(r -> {
      Thread thread = new Thread(r, "reviewer-input"); thread.setDaemon(true); return thread;
    });
    try {
      return reader.submit(() -> {
        DataInputStream input = new DataInputStream(System.in);
        int length = input.readInt();
        if (length < 1 || length > 16384) throw new IllegalArgumentException();
        byte[] bytes = input.readNBytes(length);
        if (bytes.length != length || input.read() != -1) {
          Arrays.fill(bytes, (byte) 0); throw new IllegalArgumentException();
        }
        return bytes;
      }).get(5, TimeUnit.SECONDS);
    } finally { reader.shutdownNow(); }
  }
}
