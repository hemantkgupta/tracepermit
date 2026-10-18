package io.tracepermit.endpoint;

import io.tracepermit.contracts.JsonSupport;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

final class LoopbackGatewayDeadlineTest {
  @Test
  void stalledBodyCannotOutliveTheExchangeDeadline() throws Exception {
    // No headers, no body after headers, partial JSON, denied response and redirect.
    for (String prefix : List.of("", headers(200), headers(200) + "{",
        headers(401) + "{", headers(302) + "{")) {
      try (StalledPeer peer = new StalledPeer(prefix)) {
        FutureTask<CallResult> result = call(peer.uri(), Duration.ofMillis(500));
        Thread caller = Thread.ofVirtual().start(result);
        try {
          assertThat(peer.requestReceived.await(3, TimeUnit.SECONDS)).isTrue();
          CallResult outcome = result.get(3, TimeUnit.SECONDS);
          assertThat(outcome.code()).isEqualTo("GATEWAY_UNAVAILABLE");
          assertThat(outcome.interrupted()).isFalse();
          assertThat(outcome.elapsedMillis()).isLessThan(2500);
          assertThat(peer.connectionClosed.await(2, TimeUnit.SECONDS)).isTrue();
          assertThat(peer.requests).hasValue(1);
          assertThat(peer.failure.get()).isNull();
        } finally {
          result.cancel(true);
          peer.stopConnections();
          caller.join(3000);
          assertThat(caller.isAlive()).isFalse();
        }
      }
    }
  }

  @Test
  void callerInterruptionCancelsTheBodyAndRetainsTheInterruptFlag() throws Exception {
    try (StalledPeer peer = new StalledPeer(headers(200) + "{")) {
      FutureTask<CallResult> result = call(peer.uri(), Duration.ofSeconds(10));
      Thread caller = Thread.ofVirtual().start(result);
      try {
        assertThat(peer.responseStarted.await(3, TimeUnit.SECONDS)).isTrue();
        caller.interrupt();
        CallResult outcome = result.get(3, TimeUnit.SECONDS);
        assertThat(outcome.code()).isEqualTo("GATEWAY_UNAVAILABLE");
        assertThat(outcome.interrupted()).isTrue();
        assertThat(peer.connectionClosed.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(peer.requests).hasValue(1);
        assertThat(peer.failure.get()).isNull();
      } finally {
        result.cancel(true);
        peer.stopConnections();
        caller.join(3000);
        assertThat(caller.isAlive()).isFalse();
      }
    }
  }

  private static FutureTask<CallResult> call(URI uri, Duration deadline) {
    return new FutureTask<>(() -> {
      var transport = new LoopbackHttpGatewayTransport(uri, Duration.ofSeconds(1), deadline);
      long started = System.nanoTime();
      String code;
      try {
        transport.forward(JsonSupport.MAPPER.readTree(
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}"),
            "synthetic-deadline-token", "synthetic.source.proof");
        code = "UNEXPECTED_SUCCESS";
      } catch (ConnectorException failure) {
        code = failure.code();
        assertThat(failure.getMessage()).doesNotContain("synthetic-deadline-token", "synthetic.source.proof");
      }
      return new CallResult(code, Thread.currentThread().isInterrupted(),
          TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
    });
  }

  private static String headers(int status) {
    return "HTTP/1.1 " + status + " Synthetic\r\nContent-Length: 256\r\n"
        + "Location: https://example.invalid/never-follow\r\nConnection: close\r\n\r\n";
  }

  private record CallResult(String code, boolean interrupted, long elapsedMillis) {}

  private static final class StalledPeer implements AutoCloseable {
    private final ServerSocket listener;
    private final AtomicReference<Socket> connection = new AtomicReference<>();
    private final AtomicReference<Throwable> failure = new AtomicReference<>();
    private final AtomicInteger requests = new AtomicInteger();
    private final CountDownLatch requestReceived = new CountDownLatch(1);
    private final CountDownLatch responseStarted = new CountDownLatch(1);
    private final CountDownLatch connectionClosed = new CountDownLatch(1);
    private final Thread worker;

    StalledPeer(String responsePrefix) throws IOException {
      listener = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
      listener.setSoTimeout(6000);
      worker = Thread.ofVirtual().start(() -> {
        try (Socket socket = listener.accept()) {
          connection.set(socket);
          if (listener.isClosed()) return;
          socket.setSoTimeout(6000);
          var input = socket.getInputStream();
          ByteArrayOutputStream header = new ByteArrayOutputStream();
          int value;
          while ((value = input.read()) >= 0) {
            header.write(value);
            if (header.size() > 4096) throw new IOException("Synthetic header bound exceeded");
            if (header.toString(StandardCharsets.US_ASCII).endsWith("\r\n\r\n")) break;
          }
          int contentLength = header.toString(StandardCharsets.US_ASCII).lines()
              .filter(line -> line.toLowerCase(java.util.Locale.ROOT).startsWith("content-length:"))
              .mapToInt(line -> Integer.parseInt(line.substring(line.indexOf(':') + 1).trim()))
              .findFirst().orElseThrow();
          assertThat(contentLength).isBetween(1, 4096);
          assertThat(input.readNBytes(contentLength)).hasSize(contentLength);
          requests.incrementAndGet();
          requestReceived.countDown();
          if (!responsePrefix.isEmpty()) {
            socket.getOutputStream().write(responsePrefix.getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
          }
          responseStarted.countDown();
          try {
            assertThat(input.read()).isEqualTo(-1);
          } catch (SocketException reset) {
            // Either FIN or reset demonstrates cancellation at this owned peer.
          }
          connectionClosed.countDown();
        } catch (Throwable exception) {
          if (!listener.isClosed()) failure.set(exception);
        }
      });
    }

    URI uri() {
      return URI.create("http://127.0.0.1:" + listener.getLocalPort() + "/mcp");
    }

    @Override
    public void close() throws IOException {
      stopConnections();
      try {
        worker.join(3000);
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new IOException("Synthetic peer cleanup interrupted", interrupted);
      }
      assertThat(worker.isAlive()).isFalse();
    }

    void stopConnections() throws IOException {
      listener.close();
      Socket socket = connection.get();
      if (socket != null) socket.close();
    }
  }
}
