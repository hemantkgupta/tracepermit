package io.tracepermit.adapters.http.requester;

import static org.assertj.core.api.Assertions.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

final class ReviewerCallbackTest {
  @ParameterizedTest @ValueSource(strings={"valid", "state", "issuer", "duplicate", "host", "path"})
  void actualLoopbackCallbackIsBoundToOneStateIssuerPathAndHost(String change) throws Exception {
    int port;
    try (var reservation = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) { port = reservation.getLocalPort(); }
    URI callback = URI.create("http://127.0.0.1:" + port + "/reviewer/callback");
    java.util.function.Supplier<String> run = () -> ReviewerCallback.await(callback, "fixed-state",
        "https://synthetic.invalid/", URI.create("https://synthetic.invalid/authorize"), authorization -> {
          try (var client = new Socket("127.0.0.1", port)) {
            String path = change.equals("path") ? "/other" : "/reviewer/callback";
            String state = change.equals("state") ? "wrong" : "fixed-state";
            String issuer = change.equals("issuer") ? "https://wrong.invalid/" : "https://synthetic.invalid/";
            String query = "code=synthetic-code&state=" + state + "&iss=" + URLEncoder.encode(issuer, StandardCharsets.UTF_8);
            if (change.equals("duplicate")) query += "&state=fixed-state";
            String host = change.equals("host") ? "localhost:" + port : "127.0.0.1:" + port;
            client.getOutputStream().write(("GET " + path + "?" + query + " HTTP/1.1\r\nHost: " + host + "\r\n\r\n")
                .getBytes(StandardCharsets.US_ASCII));
          } catch (Exception failure) { throw new AssertionError(failure); }
        });
    if (change.equals("valid")) assertThat(run.get()).isEqualTo("synthetic-code");
    else assertThatThrownBy(run::get).hasMessage("REVIEWER_CALLBACK_REJECTED").hasNoCause();
    try (var rebound = new ServerSocket()) { rebound.bind(new InetSocketAddress("127.0.0.1", port)); }
  }
  @Test void slowHeaderCannotRenewTheAbsoluteReadDeadline() throws Exception {
    int port;
    try (var reservation = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) { port = reservation.getLocalPort(); }
    URI callback = URI.create("http://127.0.0.1:" + port + "/reviewer/callback");
    var writer = new java.util.concurrent.atomic.AtomicReference<Thread>();
    try {
      assertThatThrownBy(() -> new RequesterCallbackListener(callback, java.time.Duration.ofSeconds(1),
          java.time.Duration.ofMillis(50)).await("state", "https://synthetic.invalid/",
          URI.create("https://synthetic.invalid/authorize"), uri -> {
            Thread slow = new Thread(() -> {
              try (var socket = new Socket("127.0.0.1", port)) {
                for (int i = 0; i < 20; i++) {
                  socket.getOutputStream().write('G'); socket.getOutputStream().flush(); Thread.sleep(20);
                }
              } catch (Exception closed) { /* The bounded reader deliberately closes this owned peer. */ }
            });
            writer.set(slow); slow.start();
          })).isInstanceOf(InstalledRequesterFailure.class);
    } finally {
      if (writer.get() != null) { writer.get().interrupt(); writer.get().join(2000); assertThat(writer.get().isAlive()).isFalse(); }
    }
  }

  @Test void nonNumericOrForeignCallbackIsRejectedBeforeBrowser() {
    assertThatThrownBy(() -> ReviewerCallback.await(URI.create("http://localhost:41839/reviewer/callback"),
        "state", "https://synthetic.invalid/", URI.create("https://synthetic.invalid/authorize"),
        uri -> { throw new AssertionError("browser must not open"); }))
        .hasMessage("REVIEWER_CALLBACK_INVALID");
  }
}
