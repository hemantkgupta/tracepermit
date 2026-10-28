package io.tracepermit.agentcore.registration;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** The child failure receipt distinguishes bounded transport outcomes without logging requests. */
@Timeout(12)
final class RegistrationHttpTestClientDiagnosticTest {
  @Test
  void childFailureParserAcceptsOnlyKnownTransportOutcomes() {
    for (String phase : new String[] {"HTTP_TIMEOUT", "HTTP_SOCKET_CLOSED", "HTTP_EMPTY_RESPONSE"}) {
      assertThat(RegistrationChild.failurePhase(
          ("SYNTHETIC_CHILD_FAILED:" + phase + ":0").getBytes(StandardCharsets.UTF_8)))
          .isEqualTo(phase + ":0");
    }
    assertThat(RegistrationChild.failurePhase(
        "SYNTHETIC_CHILD_FAILED:SYNTHETIC_SECRET:0".getBytes(StandardCharsets.UTF_8)))
        .isEqualTo("UNOBSERVED");
  }

  @Test
  void distinguishesTransportOutcomesWithoutEchoingAuthorization() throws Exception {
    assertOutcome(RegistrationHttpTestClient.Outcome.TIMEOUT);
    assertOutcome(RegistrationHttpTestClient.Outcome.EMPTY_RESPONSE);
    assertOutcome(RegistrationHttpTestClient.Outcome.SOCKET_CLOSED);
  }

  private static void assertOutcome(RegistrationHttpTestClient.Outcome expected) throws Exception {
    var release = new CountDownLatch(1);
    var executor = Executors.newSingleThreadExecutor();
    try (var listener = new ServerSocket(0, 1, InetAddress.getByAddress(new byte[] {127, 0, 0, 1}))) {
      var child = executor.submit(() -> {
        try (var socket = listener.accept()) {
          socket.setSoTimeout(5_000);
          var input = socket.getInputStream();
          int previous = 0;
          int matched = 0;
          byte[] terminator = "\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
          while (matched < terminator.length) {
            int next = input.read();
            if (next < 0) throw new AssertionError("SYNTHETIC_REQUEST_INCOMPLETE");
            matched = next == terminator[matched] ? matched + 1 : (next == terminator[0] ? 1 : 0);
            previous = next;
          }
          if (previous != '\n' || input.readNBytes(2).length != 2) {
            throw new AssertionError("SYNTHETIC_REQUEST_INCOMPLETE");
          }
          if (expected == RegistrationHttpTestClient.Outcome.SOCKET_CLOSED) socket.setSoLinger(true, 0);
          if (expected == RegistrationHttpTestClient.Outcome.TIMEOUT && !release.await(5, TimeUnit.SECONDS)) {
            throw new AssertionError("SYNTHETIC_RELEASE_TIMEOUT");
          }
        }
        return null;
      });
      try {
        var reply = RegistrationHttpTestClient.send(listener.getLocalPort(),
            "Bearer SYNTHETIC_SECRET", "{}".getBytes(StandardCharsets.US_ASCII));
        assertThat(reply.status()).isZero();
        assertThat(reply.outcome()).isEqualTo(expected);
        assertThat(reply.toString()).doesNotContain("SYNTHETIC_SECRET");
      } finally {
        release.countDown();
      }
      child.get(5, TimeUnit.SECONDS);
    } finally {
      release.countDown();
      executor.shutdownNow();
      assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
    }
  }
}
