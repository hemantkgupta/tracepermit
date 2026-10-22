package io.tracepermit.adapters.http.requester;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class RequesterQualificationClientTest {
  @Test void exactNoContentResponseQualifiesOnceWithoutBody() throws Exception {
    try (ServerSocket server = server()) {
      var request = new AtomicReference<String>();
      Thread peer = Thread.ofPlatform().start(() -> serve(server, request,
          "HTTP/1.1 204 No Content\r\nCache-Control: no-store\r\nContent-Length: 0\r\n\r\n"));
      RequesterQualificationClient.production(uri(server)).qualify("sentinelA.sentinelB.sentinelC");
      peer.join();
      assertThat(request.get()).contains("GET /api/v1/requester-qualification HTTP/1.1")
          .contains("Authorization: Bearer sentinelA.sentinelB.sentinelC")
          .doesNotContain("Cookie:", "Content-Length:");
    }
  }

  @Test void redirectBodyAndTransferEncodingFailClosed() throws Exception {
    rejected("HTTP/1.1 302 Found\r\nLocation: /other\r\nContent-Length: 0\r\n\r\n");
    rejected("HTTP/1.1 204 No Content\r\nContent-Length: 1\r\n\r\nx");
    rejected("HTTP/1.1 204 No Content\r\nTransfer-Encoding: chunked\r\n\r\n");
  }

  private static void rejected(String response) throws Exception {
    try (ServerSocket server = server()) {
      Thread peer = Thread.ofPlatform().start(() -> serve(server, new AtomicReference<>(), response));
      assertThatThrownBy(() -> RequesterQualificationClient.production(uri(server))
          .qualify("sentinelA.sentinelB.sentinelC"))
          .isInstanceOfSatisfying(InstalledRequesterFailure.class,
              failure -> assertThat(failure.code()).isEqualTo("REQUESTER_ENROLLMENT_REQUIRED"));
      peer.join();
    }
  }

  private static ServerSocket server() throws Exception {
    ServerSocket server = new ServerSocket();
    server.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0));
    return server;
  }

  private static URI uri(ServerSocket server) {
    return URI.create("http://127.0.0.1:" + server.getLocalPort()
        + "/api/v1/requester-qualification");
  }

  private static void serve(ServerSocket server, AtomicReference<String> request, String response) {
    try (var socket = server.accept()) {
      ByteArrayOutputStream output = new ByteArrayOutputStream();
      int state = 0;
      while (state != 4) {
        int value = socket.getInputStream().read();
        if (value < 0) break;
        output.write(value);
        state = switch (state) {
          case 0 -> value == '\r' ? 1 : 0;
          case 1 -> value == '\n' ? 2 : 0;
          case 2 -> value == '\r' ? 3 : 0;
          case 3 -> value == '\n' ? 4 : 0;
          default -> state;
        };
      }
      request.set(output.toString(StandardCharsets.US_ASCII));
      socket.getOutputStream().write(response.getBytes(StandardCharsets.US_ASCII));
      socket.getOutputStream().flush();
    } catch (Exception failure) {
      throw new RuntimeException(failure);
    }
  }
}
