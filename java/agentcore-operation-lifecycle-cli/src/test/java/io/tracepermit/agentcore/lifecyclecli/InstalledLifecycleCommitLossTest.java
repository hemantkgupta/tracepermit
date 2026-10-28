package io.tracepermit.agentcore.lifecyclecli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.tracepermit.contracts.CanonicalJson;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Real extracted lifecycle archives, real restricted engines, independent durable observations.
 * Receipt UNKNOWN is command uncertainty, NOT proof that an in-memory synthetic target was called
 * zero or one times. Restarted reconciliation cannot recover that dead process's target map.
 */
@Timeout(240)
final class InstalledLifecycleCommitLossTest {
  enum Boundary { START, RESULT }
  enum Fault { RELEASE_REPLY, LOSE_REPLY, KILL_WORKER }

  static Stream<Arguments> commits() {
    return Stream.of("postgres:16.4-alpine", "mariadb:11.4.13", "mariadb:10.6.25")
        .flatMap(image -> Stream.of(Boundary.values())
            .flatMap(boundary -> Stream.of(Fault.values())
                .map(fault -> Arguments.of(image, boundary, fault))));
  }

  /** Exact single-case selection for manual diagnosis; the original 18-case matrix stays active. */
  @Test
  void postgresStartReleaseReplyConnectionDiagnostics() throws Exception {
    durableCommitSurvivesReplyUncertaintyAndRestartRefusesNewDelivery(
        "postgres:16.4-alpine", Boundary.START, Fault.RELEASE_REPLY);
  }

  /** Resource control only: real engine, empty TCP sessions; no durability success claim. */
  @Test
  void cumulativeBudgetDoesNotResetWhenConnectionsClose() throws Exception {
    try (var database = new LifecycleTestDatabase("postgres:16.4-alpine")) {
      var gate = new LifecycleCommitGate(true, database.port,
          () -> new LifecycleCommitGate.CommitEvidence(false, 0,
              LifecycleCommitGate.DurablePhase.OTHER));
      try {
        for (int n = 1; n <= LifecycleCommitGate.MAX_CUMULATIVE_CONNECTIONS; n++) {
          try (var client = resourceClient(gate)) {
            assertThat(client.isConnected()).isTrue();
          }
          int closed = n;
          awaitResource(() -> gate.resourceCounts().closed() == closed);
          assertResourceLedger(gate, n, n, 0, n, 1);
        }
        try (var refused = resourceClient(gate)) {
          assertThat(refused.isConnected()).isTrue();
          awaitResource(() -> gate.resourceCounts().rejected() == 1 && gate.failurePublished());
          assertThatThrownBy(gate::requireHealthy).hasMessageContaining("COMMIT_GATE_CONNECTION_BOUND");
          assertThat(gate.resourceCounts().accepted())
              .isEqualTo(LifecycleCommitGate.MAX_CUMULATIVE_CONNECTIONS + 1);
          assertThat(gate.resourceCounts().upstream())
              .isEqualTo(LifecycleCommitGate.MAX_CUMULATIVE_CONNECTIONS);
          assertResourceLedger(gate, 45, 44, 1, 44, 1);
        }
        assertThat(database.count("tp_operation_delivery_events")).isZero();
      } finally {
        assertThatThrownBy(gate::close).hasMessageContaining("COMMIT_GATE_CONNECTION_BOUND");
        assertThat(gate.resourceCounts().active()).isZero();
        assertResourceLedger(gate, 45, 45, 1, 44, 1);
      }
    }
  }

  /** One refusal reserve keeps ALL owned active accepted pairs at or below32. */
  @Test
  void activeBudgetRejectsBeforeAnotherUpstreamAllocation() throws Exception {
    try (var database = new LifecycleTestDatabase("postgres:16.4-alpine")) {
      var gate = new LifecycleCommitGate(true, database.port,
          () -> new LifecycleCommitGate.CommitEvidence(false, 0,
              LifecycleCommitGate.DurablePhase.OTHER));
      var clients = new java.util.ArrayList<java.net.Socket>();
      try {
        for (int n = 1; n <= LifecycleCommitGate.MAX_ACTIVE_ADMITTED; n++) {
          clients.add(resourceClient(gate));
          int connected = n;
          awaitResource(() -> gate.resourceCounts().upstream() == connected);
        }
        clients.add(resourceClient(gate));
        awaitResource(() -> gate.resourceCounts().rejected() == 1 && gate.failurePublished());
        assertThatThrownBy(gate::requireHealthy).hasMessageContaining("COMMIT_GATE_ACTIVE_BOUND");
        assertThat(gate.resourceCounts().upstream()).isEqualTo(LifecycleCommitGate.MAX_ACTIVE_ADMITTED);
        assertThat(gate.resourceCounts().peak()).isLessThanOrEqualTo(LifecycleCommitGate.MAX_ACTIVE_PAIRS);
        assertResourceLedger(gate, 32, 0, 1, 31, 32);
        assertThat(database.count("tp_operation_delivery_events")).isZero();
      } finally {
        for (var client : clients) client.close();
        assertThatThrownBy(gate::close).hasMessageContaining("COMMIT_GATE_ACTIVE_BOUND");
        assertThat(gate.resourceCounts().active()).isZero();
        assertResourceLedger(gate, 32, 32, 1, 31, 32);
      }
    }
  }

  /** Closed contract values are explicit test expectations; constants/production are unchanged. */
  private static void assertResourceLedger(LifecycleCommitGate gate, int accepted, int closed,
      int rejected, long upstream, int peak) {
    var counts = gate.resourceCounts();
    assertThat(counts.accepted()).isEqualTo(accepted);
    assertThat(counts.closed()).isEqualTo(closed);
    assertThat(counts.active()).isEqualTo(accepted - closed);
    assertThat(counts.rejected()).isEqualTo(rejected);
    assertThat(counts.upstream()).isEqualTo(upstream);
    assertThat(counts.peak()).isEqualTo(peak);
    assertThat(counts.socketReferences()).isEqualTo(2 * (int) upstream + rejected);
    assertThat(counts.socketReferences()).isLessThanOrEqualTo(89);
    assertThat(counts.threadReferences()).isLessThanOrEqualTo(89);
    assertThat(counts.journalEntries()).isLessThanOrEqualTo(228);
    assertThat(gate.maxRecordedEventCharacters()).isLessThanOrEqualTo(512);
    assertThat(counts.bytes()).isZero(); // Blank resource-control sessions send no protocol bytes.
  }

  @Test
  void upstreamConnectionControlClosesBothOwnedSockets() throws Exception {
    try (var upstream = new java.net.ServerSocket(
        0, 1, java.net.InetAddress.getByName("127.0.0.1"));
        var gate = resourceGate(upstream.getLocalPort())) {
      upstream.setSoTimeout(5_000);
      try (var client = resourceClient(gate); var accepted = upstream.accept()) {
        assertThat(client.isConnected()).isTrue();
        assertThat(accepted.isConnected()).isTrue();
        awaitResource(() -> gate.resourceCounts().upstream() == 1);
        gate.requireHealthy();
      }
      awaitResource(() -> gate.resourceCounts().closed() == 1);
      assertResourceLedger(gate, 1, 1, 0, 1, 1);
    }
  }

  @Test
  void refusedUpstreamConnectionPublishesFailureAndClosesOwnedSockets() throws Exception {
    // A bound, non-listening TCP socket reserves this loopback port without another process
    // being able to acquire it. No database credentials, payload or address are logged.
    try (var reserved = new java.net.Socket()) {
      reserved.bind(new java.net.InetSocketAddress("127.0.0.1", 0));
      var gate = resourceGate(reserved.getLocalPort());
      try {
        try (var client = resourceClient(gate)) {
          assertThat(client.isConnected()).isTrue();
          awaitResource(gate::failurePublished);
          assertThatThrownBy(gate::requireHealthy)
              .hasMessageContaining("ACCEPT_OR_CONNECT:ConnectException:REDACTED");
          assertThat(gate.resourceCounts().accepted()).isEqualTo(1);
          assertThat(gate.resourceCounts().rejected()).isZero();
          assertThat(gate.resourceCounts().upstream()).isZero();
          assertThat(gate.resourceCounts().socketReferences()).isEqualTo(2);
        }
      } finally {
        assertThatThrownBy(gate::close)
            .hasMessageContaining("ACCEPT_OR_CONNECT:ConnectException:REDACTED");
        assertThat(gate.resourceCounts().closed()).isEqualTo(1);
        assertThat(gate.resourceCounts().active()).isZero();
        assertThat(gate.resourceCounts().socketReferences()).isEqualTo(2);
      }
    }
  }

  @Test
  void injectedUpstreamTimeoutRemainsAccountedUntilGateCleanup() throws Exception {
    // This injects only the connection timeout exception; it does not simulate a database
    // transaction, an elapsed kernel timeout, or any durable/target outcome.
    var gate = new LifecycleCommitGate(true, 1,
        () -> new LifecycleCommitGate.CommitEvidence(false, 0,
            LifecycleCommitGate.DurablePhase.OTHER),
        (socket, port) -> { throw new java.net.SocketTimeoutException(); });
    try {
      try (var client = resourceClient(gate)) {
        assertThat(client.isConnected()).isTrue();
        awaitResource(() -> gate.upstreamTimeoutCount() == 1);
        gate.requireHealthy();
        assertThat(gate.resourceCounts().accepted()).isEqualTo(1);
        assertThat(gate.resourceCounts().active()).isEqualTo(1);
        assertThat(gate.resourceCounts().rejected()).isZero();
        assertThat(gate.resourceCounts().upstream()).isZero();
        assertThat(gate.resourceCounts().socketReferences()).isEqualTo(2);
      }
    } finally {
      gate.close();
    }
    assertThat(gate.upstreamTimeoutCount()).isEqualTo(1);
    assertThat(gate.resourceCounts().active()).isZero();
    assertThat(gate.resourceCounts().closed()).isEqualTo(1);
    assertThat(gate.ownedResourcesTerminated()).isTrue();
  }

  @Test
  void postgresFrameLengthsAcceptLimitsAndRejectInvalidLengths() throws Exception {
    int maximum = 1_048_576;
    byte[] startupMinimum = java.nio.ByteBuffer.allocate(8).putInt(8).putInt(196608).array();
    byte[] startupMaximum = new byte[maximum];
    java.nio.ByteBuffer.wrap(startupMaximum).putInt(maximum).putInt(196608);
    assertThat(LifecycleCommitGate.startup(new java.io.ByteArrayInputStream(startupMinimum)))
        .isEqualTo(startupMinimum);
    assertThat(LifecycleCommitGate.startup(new java.io.ByteArrayInputStream(startupMaximum)))
        .isEqualTo(startupMaximum);
    for (int invalid : new int[] {7, maximum + 1}) {
      byte[] header = java.nio.ByteBuffer.allocate(4).putInt(invalid).array();
      assertThatThrownBy(() -> LifecycleCommitGate.startup(new java.io.ByteArrayInputStream(header)))
          .hasMessage("COMMIT_GATE_STARTUP_BOUND");
    }

    byte[] minimum = java.nio.ByteBuffer.allocate(5).put((byte) 'Z').putInt(4).array();
    byte[] maximumFrame = new byte[maximum + 1];
    java.nio.ByteBuffer.wrap(maximumFrame).put((byte) 'Z').putInt(maximum);
    assertThat(LifecycleCommitGate.pgFrame(new java.io.ByteArrayInputStream(minimum)))
        .isEqualTo(minimum);
    assertThat(LifecycleCommitGate.pgFrame(new java.io.ByteArrayInputStream(maximumFrame)))
        .isEqualTo(maximumFrame);
    for (int invalid : new int[] {3, maximum + 1}) {
      byte[] header = java.nio.ByteBuffer.allocate(5).put((byte) 'Z').putInt(invalid).array();
      assertThatThrownBy(() -> LifecycleCommitGate.pgFrame(new java.io.ByteArrayInputStream(header)))
          .hasMessage("COMMIT_GATE_PG_FRAME_BOUND");
    }
  }

  @Test
  void byteAccountingAcceptsExactLimitsAndRejectsNextAttempt() throws Exception {
    long perDirection = 64L * 1_048_576;
    assertThat(LifecycleCommitGate.bounded(perDirection - 1, 1)).isEqualTo(perDirection);
    assertThatThrownBy(() -> LifecycleCommitGate.bounded(perDirection, 1))
        .hasMessage("COMMIT_GATE_BYTE_BOUND");
    try (var gate = resourceGate(1)) {
      // This tests the actual gate accounting function without sending gigabytes or changing
      // its counter. A failed write attempt remains included in the cumulative accounting.
      long remaining = LifecycleCommitGate.MAX_GATE_BYTE_ATTEMPTS;
      while (remaining > 0) {
        int chunk = (int) Math.min(remaining, 1_048_576L);
        gate.accountByteAttempt(chunk);
        remaining -= chunk;
      }
      assertThat(gate.resourceCounts().bytes()).isEqualTo(LifecycleCommitGate.MAX_GATE_BYTE_ATTEMPTS);
      assertThatThrownBy(() -> gate.accountByteAttempt(1)).hasMessage("COMMIT_GATE_TOTAL_BYTE_BOUND");
      assertThat(gate.resourceCounts().bytes()).isEqualTo(LifecycleCommitGate.MAX_GATE_BYTE_ATTEMPTS + 1);
    }
  }

  @Test
  void interruptedCleanupPreservesInterruptAndOwnedResourcesCanTerminate() throws Exception {
    var gate = resourceGate(1);
    var entered = new java.util.concurrent.CountDownLatch(1);
    var release = new java.util.concurrent.CountDownLatch(1);
    gate.startResourceControlThread(() -> {
      entered.countDown();
      try {
        if (!release.await(5, java.util.concurrent.TimeUnit.SECONDS)) {
          throw new AssertionError("RESOURCE_CONTROL_THREAD_TIMEOUT");
        }
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new AssertionError("RESOURCE_CONTROL_THREAD_INTERRUPTED");
      }
    });
    var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
    var interrupted = new java.util.concurrent.atomic.AtomicBoolean();
    var cleanup = new Thread(() -> {
      try {
        gate.close();
      } catch (Throwable error) {
        failure.set(error);
        interrupted.set(Thread.currentThread().isInterrupted());
      }
    }, "lifecycle-resource-interrupted-cleanup");
    cleanup.setDaemon(true);
    try {
      assertThat(entered.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
      cleanup.start();
      awaitResource(() -> cleanup.getState() == Thread.State.TIMED_WAITING);
      cleanup.interrupt();
      cleanup.join(5_000);
      assertThat(cleanup.isAlive()).isFalse();
      assertThat(failure.get()).isInstanceOf(AssertionError.class)
          .hasMessage("COMMIT_GATE_CLEANUP_INTERRUPTED");
      assertThat(interrupted.get()).isTrue();
    } finally {
      release.countDown();
      if (cleanup.isAlive()) {
        cleanup.interrupt();
        cleanup.join(5_000);
      }
      assertThat(cleanup.isAlive()).isFalse();
      awaitResource(gate::ownedResourcesTerminated);
      assertThat(gate.resourceCounts().active()).isZero();
    }
  }

  @Test
  void closingDuringUpstreamConnectClosesLateOwnedResources() throws Exception {
    var entered = new java.util.concurrent.CountDownLatch(1);
    var release = new java.util.concurrent.CountDownLatch(1);
    try (var upstream = new java.net.ServerSocket(
        0, 1, java.net.InetAddress.getByName("127.0.0.1"))) {
      var gate = new LifecycleCommitGate(true, upstream.getLocalPort(),
          () -> new LifecycleCommitGate.CommitEvidence(false, 0,
              LifecycleCommitGate.DurablePhase.OTHER),
          (socket, port) -> {
            entered.countDown();
            try {
              if (!release.await(5, java.util.concurrent.TimeUnit.SECONDS)) {
                throw new java.io.IOException("RESOURCE_CONNECT_BARRIER_TIMEOUT");
              }
            } catch (InterruptedException interrupted) {
              Thread.currentThread().interrupt();
              throw new java.io.IOException("RESOURCE_CONNECT_BARRIER_INTERRUPTED");
            }
            socket.connect(new java.net.InetSocketAddress("127.0.0.1", port), 5_000);
          });
      var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
      var cleanup = new Thread(() -> {
        try {
          gate.close();
        } catch (Throwable error) {
          failure.set(error);
        }
      }, "lifecycle-resource-concurrent-cleanup");
      cleanup.setDaemon(true);
      try {
        try (var client = resourceClient(gate)) {
          assertThat(client.isConnected()).isTrue();
          assertThat(entered.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
          cleanup.start();
          awaitResource(gate::closeStarted);
          awaitResource(gate::ownedSocketsClosed);
          release.countDown();
          cleanup.join(5_000);
          assertThat(cleanup.isAlive()).isFalse();
          assertThat(failure.get()).isNull();
        }
      } finally {
        release.countDown();
        if (cleanup.getState() == Thread.State.NEW) gate.close();
        else if (cleanup.isAlive()) cleanup.join(5_000);
        assertThat(cleanup.isAlive()).isFalse();
      }
      assertThat(gate.ownedResourcesTerminated()).isTrue();
      assertThat(gate.resourceCounts().accepted()).isEqualTo(1);
      assertThat(gate.resourceCounts().closed()).isEqualTo(1);
      assertThat(gate.resourceCounts().active()).isZero();
    }
  }

  @Test
  void closedSessionReferencesStopAtTheirOwnBound() throws Exception {
    var gate = resourceGate(1);
    try {
      for (int n = 0; n < LifecycleCommitGate.MAX_SESSION_REFERENCES; n++) {
        var closed = new java.net.Socket();
        closed.close();
        gate.registerClosedResourceControlSocket(closed);
      }
      assertThat(gate.resourceCounts().accepted()).isEqualTo(45);
      assertThat(gate.resourceCounts().closed()).isEqualTo(45);
      assertThat(gate.resourceCounts().active()).isZero();
      var refused = new java.net.Socket();
      refused.close();
      assertThatThrownBy(() -> gate.registerClosedResourceControlSocket(refused))
          .hasMessage("COMMIT_GATE_SESSION_REFERENCE_BOUND");
      assertThat(refused.isClosed()).isTrue();
      assertThat(gate.resourceCounts().accepted()).isEqualTo(45);
      assertThat(gate.resourceCounts().socketReferences()).isEqualTo(46);
    } finally {
      gate.close();
    }
    assertThat(gate.ownedResourcesTerminated()).isTrue();
  }

  @Test
  void closedSocketReferencesStopAtTheirOwnBound() throws Exception {
    var gate = resourceGate(1);
    try {
      for (int n = 0; n < LifecycleCommitGate.MAX_SOCKET_REFERENCES; n++) {
        var closed = new java.net.Socket();
        closed.close();
        gate.retainSocket(closed);
      }
      assertThat(gate.resourceCounts().socketReferences()).isEqualTo(89);
      var refused = new java.net.Socket();
      assertThatThrownBy(() -> gate.retainSocket(refused))
          .hasMessage("COMMIT_GATE_SOCKET_REFERENCE_BOUND");
      assertThat(refused.isClosed()).isTrue();
      assertThat(gate.resourceCounts().socketReferences()).isEqualTo(89);
    } finally {
      gate.close();
    }
    assertThat(gate.ownedResourcesTerminated()).isTrue();
  }

  @Test
  void sequentialThreadReferencesStopAtTheirOwnBound() throws Exception {
    var gate = resourceGate(1);
    try {
      // One slot belongs to the listener thread; every control thread finishes independently.
      for (int n = 1; n < LifecycleCommitGate.MAX_GATE_THREAD_REFERENCES; n++) {
        var completed = new java.util.concurrent.CountDownLatch(1);
        gate.startResourceControlThread(completed::countDown);
        assertThat(completed.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
      }
      assertThat(gate.resourceCounts().threadReferences()).isEqualTo(89);
      assertThatThrownBy(() -> gate.startResourceControlThread(() -> {}))
          .hasMessage("COMMIT_GATE_THREAD_REFERENCE_BOUND");
      assertThat(gate.resourceCounts().threadReferences()).isEqualTo(89);
    } finally {
      gate.close();
    }
    assertThat(gate.ownedResourcesTerminated()).isTrue();
  }

  @Test
  void journalBoundsRejectExcessAndOwnedResourcesStillTerminate() throws Exception {
    LifecycleCommitGate.checkEventCharacters("X".repeat(512));
    assertThatThrownBy(() -> LifecycleCommitGate.checkEventCharacters("X".repeat(513)))
        .hasMessage("COMMIT_GATE_JOURNAL_CHARACTER_BOUND");
    var gate = resourceGate(1);
    try {
      var closed = new java.net.Socket();
      closed.close();
      gate.registerClosedResourceControlSocket(closed);
      while (gate.resourceCounts().journalEntries() < 228) gate.recordResourceControlEvent();
      assertThat(gate.resourceCounts().journalEntries()).isEqualTo(228);
      assertThatThrownBy(gate::recordResourceControlEvent)
          .hasMessage("COMMIT_GATE_JOURNAL_BOUND");
      assertThat(gate.droppedDiagnosticEventCount()).isEqualTo(1);
    } finally {
      // The full journal also refuses cleanup's CLOSE event; check actual owned resources
      // independently because that exception precedes close()'s final assertions.
      assertThatThrownBy(gate::close).hasMessage("COMMIT_GATE_JOURNAL_BOUND");
      assertThat(gate.ownedResourcesTerminated()).isTrue();
      assertThat(gate.resourceCounts().active()).isZero();
      assertThat(gate.droppedDiagnosticEventCount()).isEqualTo(2);
    }
  }

  private static LifecycleCommitGate resourceGate(int upstreamPort) throws Exception {
    return new LifecycleCommitGate(true, upstreamPort,
        () -> new LifecycleCommitGate.CommitEvidence(false, 0,
            LifecycleCommitGate.DurablePhase.OTHER));
  }

  private static java.net.Socket resourceClient(LifecycleCommitGate gate) throws Exception {
    var client = new java.net.Socket();
    try {
      client.connect(new java.net.InetSocketAddress("127.0.0.1", gate.port()), 5_000);
      return client;
    } catch (Exception failure) {
      client.close();
      throw failure;
    }
  }

  private static void awaitResource(java.util.function.BooleanSupplier complete) throws Exception {
    long end = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
    while (!complete.getAsBoolean() && System.nanoTime() < end) Thread.sleep(10);
    assertThat(complete.getAsBoolean()).as("resource control completed within existing5s reserve").isTrue();
  }

  @Test
  void postgresBudgetReviewStartLoseReply() throws Exception {
    durableCommitSurvivesReplyUncertaintyAndRestartRefusesNewDelivery(
        "postgres:16.4-alpine", Boundary.START, Fault.LOSE_REPLY);
  }

  @Test
  void postgresBudgetReviewStartKillWorker() throws Exception {
    durableCommitSurvivesReplyUncertaintyAndRestartRefusesNewDelivery(
        "postgres:16.4-alpine", Boundary.START, Fault.KILL_WORKER);
  }

  @Test
  void postgresBudgetReviewResultReleaseReply() throws Exception {
    durableCommitSurvivesReplyUncertaintyAndRestartRefusesNewDelivery(
        "postgres:16.4-alpine", Boundary.RESULT, Fault.RELEASE_REPLY);
  }

  @Test
  void postgresBudgetReviewResultLoseReply() throws Exception {
    durableCommitSurvivesReplyUncertaintyAndRestartRefusesNewDelivery(
        "postgres:16.4-alpine", Boundary.RESULT, Fault.LOSE_REPLY);
  }

  @Test
  void postgresBudgetReviewResultKillWorker() throws Exception {
    durableCommitSurvivesReplyUncertaintyAndRestartRefusesNewDelivery(
        "postgres:16.4-alpine", Boundary.RESULT, Fault.KILL_WORKER);
  }

  @ParameterizedTest(name = "{0} {1} {2}")
  @MethodSource("commits")
  void durableCommitSurvivesReplyUncertaintyAndRestartRefusesNewDelivery(
      String image, Boundary boundary, Fault fault) throws Exception {
    try (var fixture = new InstalledLifecycleCommitLossFixture(image)) {
      int selectedEvents = boundary == Boundary.START ? 2 : 3;
      String selectedState = boundary == Boundary.START ? "executing" : "unknown";
      var observed = new AtomicReference<InstalledLifecycleCommitLossFixture.Observation>();
      try (var gate = new LifecycleCommitGate(fixture.database.postgres, fixture.database.port, () -> {
        var state = fixture.observe();
        boolean eligible = state.events().size() == selectedEvents;
        if (eligible) {
          assertThat(state.execution()).isEqualTo(selectedState);
          observed.set(state);
        }
        return new LifecycleCommitGate.CommitEvidence(
            eligible, state.events().size(), durablePhase(state));
      })) {
        ObjectNode gated = fixture.config.deepCopy();
        ((ObjectNode) gated.get("operations")).put("port", gate.port());
        // RESULT deliberately uses the existing deterministic UNKNOWN target outcome. It proves
        // a durable unknown result differs from an UNKNOWN command whose start/result ACK was lost.
        try (var process = new InstalledLifecycleCommitLossProcess(
            fixture.installed, fixture.delivery(gated, "UNKNOWN"))) {
          gate.awaitHeld();
          process.requireWaitingWorker();
          var committed = observed.get();
          assertThat(committed).isNotNull();
          assertAnchors(committed, selectedEvents);
          // Independent connection, bypassing the gate, sees the same committed head/history while
          // the successful reply has NOT reached the worker. No uncommitted-row inference.
          assertThat(fixture.observe()).isEqualTo(committed);
          if (fault == Fault.KILL_WORKER) process.killOwnedWorker();
          gate.decide(fault == Fault.RELEASE_REPLY);
          LifecycleReceipt receipt = process.receipt();
          if (fault == Fault.RELEASE_REPLY) {
            assertThat(receipt.outcome()).isEqualTo("DELIVERY_UNKNOWN");
            assertThat(receipt.executionStatus()).isEqualTo("unknown");
            assertThat(receipt.dispatchStarted()).isTrue();
            assertThat(receipt.exitCode()).isZero();
          } else {
            assertThat(receipt.outcome()).isEqualTo("UNKNOWN");
            assertThat(receipt.operationId()).isNull();
            assertThat(receipt.executionStatus()).isNull();
            assertThat(receipt.dispatchStarted()).isNull();
            assertThat(receipt.exitCode()).isEqualTo(1);
          }
          gate.requireHealthy();
        }
      }

      // Every following call starts a NEW extracted-archive process, using the direct restricted
      // endpoint. No fault gate or prior worker memory participates in restart observations.
      var durable = fixture.observe();
      boolean completed = boundary == Boundary.RESULT || fault == Fault.RELEASE_REPLY;
      assertAnchors(durable, completed ? 3 : 2);
      assertThat(durable.execution()).isEqualTo(completed ? "unknown" : "executing");
      if (fault != Fault.RELEASE_REPLY) assertThat(durable).isEqualTo(observed.get());
      LifecycleReceipt reconciled = fixture.call("reconcile", fixture.command(), false);
      assertThat(reconciled.outcome()).isEqualTo("RECONCILED");
      assertThat(reconciled.exitCode()).isZero();
      assertThat(reconciled.dispatchStarted()).isTrue();
      assertThat(reconciled.executionStatus()).isEqualTo(durable.execution());
      assertThat(fixture.observe()).isEqualTo(durable);

      // A fresh signed permit request through the installed entry point must not create another
      // admission/attempt/start/result. Check immutable rows AND head, not just a receipt label.
      LifecycleReceipt repeat = fixture.installed.call(
          "deliver-synthetic", fixture.delivery(fixture.config, "CONFIRMED_SUCCESS"));
      assertThat(repeat.outcome()).isEqualTo("REJECTED");
      assertThat(repeat.exitCode()).isEqualTo(1);
      assertThat(fixture.observe()).isEqualTo(durable);
      LifecycleReceipt after = fixture.call("reconcile", fixture.command(), false);
      assertThat(after.outcome()).isEqualTo("RECONCILED");
      assertThat(after.dispatchStarted()).isTrue();
      assertThat(after.executionStatus()).isEqualTo(durable.execution());
      assertThat(fixture.observe()).isEqualTo(durable);
      assertThat(fixture.database.count("tp_operation_admissions")).isOne();
      assertThat(fixture.database.count("tp_operation_admission_outbox")).isOne();
      // Intentionally NO target invocation count assertion: the installed worker's target map is
      // process-local. Neither row counts nor a fresh target object reveal dead in-memory effects.
    }
  }

  private static LifecycleCommitGate.DurablePhase durablePhase(
      InstalledLifecycleCommitLossFixture.Observation state) {
    if (state.events().isEmpty()) {
      return switch (state.execution()) {
        case "not_started" -> LifecycleCommitGate.DurablePhase.NOT_STARTED;
        case "claimed" -> LifecycleCommitGate.DurablePhase.CLAIMED;
        default -> LifecycleCommitGate.DurablePhase.OTHER;
      };
    }
    return switch (state.events().getLast().path("kind").asText()) {
      case "lease" -> LifecycleCommitGate.DurablePhase.LEASE_ONLY;
      case "dispatch_started" -> LifecycleCommitGate.DurablePhase.STARTED;
      case "result" -> LifecycleCommitGate.DurablePhase.RESULT;
      default -> LifecycleCommitGate.DurablePhase.OTHER;
    };
  }

  private static void assertAnchors(
      InstalledLifecycleCommitLossFixture.Observation state, int size) {
    assertThat(state.events()).hasSize(size);
    assertThat(state.events().stream().map(e -> e.path("kind").asText()).toList())
        .isEqualTo(size == 2 ? List.of("lease", "dispatch_started")
            : List.of("lease", "dispatch_started", "result"));
    var lease = state.events().get(0);
    var start = state.events().get(1);
    assertThat(start.path("leaseHash").asText()).isEqualTo(CanonicalJson.sha256(lease));
    assertThat(start.path("previousEventHash").asText()).isEqualTo(CanonicalJson.sha256(lease));
    for (var event : state.events()) {
      assertThat(event.path("attemptId")).isEqualTo(lease.path("attemptId"));
      assertThat(event.path("outboxHash")).isEqualTo(lease.path("outboxHash"));
      assertThat(event.path("admissionHash")).isEqualTo(lease.path("admissionHash"));
      assertThat(event.path("fence").asInt()).isEqualTo(1);
    }
    if (size == 3) {
      var result = state.events().get(2);
      assertThat(result.path("startHash").asText()).isEqualTo(CanonicalJson.sha256(start));
      assertThat(result.path("previousEventHash").asText()).isEqualTo(CanonicalJson.sha256(start));
      assertThat(result.path("outcome").asText()).isEqualTo("unknown");
    }
  }
}
