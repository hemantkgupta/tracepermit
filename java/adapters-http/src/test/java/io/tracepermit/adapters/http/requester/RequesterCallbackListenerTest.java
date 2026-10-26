package io.tracepermit.adapters.http.requester;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class RequesterCallbackListenerTest {
  private static final String ISSUER = "https://idp.example.invalid/";

  @Test void exactOneShotCallbackReturnsCodeAndClosesListener() throws Exception {
    int port = freePort();
    URI callback = URI.create("http://127.0.0.1:" + port + "/requester/callback");
    var response = new AtomicReference<String>();
    var peer = new AtomicReference<Thread>();
    var listener = new RequesterCallbackListener(
        callback, Duration.ofSeconds(3), Duration.ofSeconds(2));
    String code = listener.await("expected-state", ISSUER, URI.create("https://example.invalid"),
        ignored -> peer.set(Thread.ofPlatform().start(() -> response.set(send(port,
            "GET /requester/callback?code=one-code&state=expected-state&iss="
                + "https%3A%2F%2Fidp.example.invalid%2F HTTP/1.1\r\n"
                + "Host: 127.0.0.1:" + port + "\r\n\r\n")))));
    peer.get().join();
    assertThat(code).isEqualTo("one-code");
    assertThat(response.get()).contains("200 OK").contains("Sign-in received")
        .doesNotContain("one-code", "expected-state");
    assertThatThrownBy(() -> new Socket("127.0.0.1", port)).isInstanceOf(Exception.class);
  }

  @Test void stateMismatchAndForbiddenHeadersConsumeTheOnlyAttempt() throws Exception {
    assertCallbackFailure("REQUESTER_STATE_MISMATCH",
        "?code=one&state=wrong&iss=https%3A%2F%2Fidp.example.invalid%2F", "");
    assertCallbackFailure("REQUESTER_CALLBACK_INVALID",
        "?code=one&state=expected-state&iss=https%3A%2F%2Fidp.example.invalid%2F",
        "Cookie: unsafe=value\r\n");
    assertCallbackFailure("REQUESTER_CALLBACK_INVALID",
        "?code=one&code=two&state=expected-state&iss=https%3A%2F%2Fidp.example.invalid%2F", "");
  }

  @Test void occupiedPortFailsBeforeBrowserOpens() throws Exception {
    try (ServerSocket occupied = new ServerSocket()) {
      occupied.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0));
      var opened = new java.util.concurrent.atomic.AtomicBoolean();
      URI callback = URI.create("http://127.0.0.1:" + occupied.getLocalPort()
          + "/requester/callback");
      assertThatThrownBy(() -> new RequesterCallbackListener(callback).await(
          "state", ISSUER, URI.create("https://example.invalid"), ignored -> opened.set(true)))
          .isInstanceOfSatisfying(InstalledRequesterFailure.class,
              failure -> assertThat(failure.code()).isEqualTo("CALLBACK_LISTENER_CONFLICT"));
      assertThat(opened).isFalse();
    }
  }

  private static void assertCallbackFailure(String expected, String query, String extraHeader)
      throws Exception {
    int port = freePort();
    URI callback = URI.create("http://127.0.0.1:" + port + "/requester/callback");
    var listener = new RequesterCallbackListener(
        callback, Duration.ofSeconds(3), Duration.ofSeconds(2));
    assertThatThrownBy(() -> listener.await("expected-state", ISSUER,
        URI.create("https://example.invalid"), ignored -> Thread.ofPlatform().start(() -> send(port,
            "GET /requester/callback" + query + " HTTP/1.1\r\n"
                + "Host: 127.0.0.1:" + port + "\r\n" + extraHeader + "\r\n"))))
        .isInstanceOfSatisfying(InstalledRequesterFailure.class,
            failure -> assertThat(failure.code()).isEqualTo(expected));
    assertThatThrownBy(() -> new Socket("127.0.0.1", port)).isInstanceOf(Exception.class);
  }

  private static int freePort() throws Exception {
    try (ServerSocket socket = new ServerSocket()) {
      socket.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0));
      return socket.getLocalPort();
    }
  }

  private static String send(int port, String request) {
    try (Socket socket = new Socket("127.0.0.1", port)) {
      socket.setSoTimeout(2_000);
      socket.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));
      socket.getOutputStream().flush();
      ByteArrayOutputStream output = new ByteArrayOutputStream();
      socket.getInputStream().transferTo(output);
      return output.toString(StandardCharsets.UTF_8);
    } catch (Exception failure) {
      throw new RuntimeException(failure);
    }
  }
}
