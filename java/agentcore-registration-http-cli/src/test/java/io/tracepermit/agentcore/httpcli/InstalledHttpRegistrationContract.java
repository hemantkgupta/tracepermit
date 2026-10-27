package io.tracepermit.agentcore.httpcli;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Timeout(180)
abstract class InstalledHttpRegistrationContract {
  private InstalledHttpArchive installed;
  private LocalDatabases database;

  abstract String image();

  @BeforeAll
  void start() throws Exception {
    installed = new InstalledHttpArchive();
    database = new LocalDatabases(image());
  }

  @AfterAll
  void stop() throws Exception {
    if (database != null) database.close();
    if (installed != null) installed.close();
  }

  @BeforeEach
  void reset() {
    database.reset();
  }

  @Test
  void fourInstalledServersShareReplayAndDurableIdempotencyAcrossProcesses() throws Exception {
    var sessions = startFour(14_000);
    try {
      String firstToken = HttpCliFixtures.token();
      var first = send(sessions.get(0), firstToken, HttpCliFixtures.request("shared-key"));
      assertThat(first.status()).isEqualTo(201);
      JsonNode created = HttpCliFixtures.JSON.readTree(first.body());
      assertThat(created.path("executionEnabled").booleanValue()).isFalse();
      assertThat(created.size()).isEqualTo(3);

      assertThat(send(sessions.get(1), firstToken, HttpCliFixtures.request("different-key")).status())
          .isEqualTo(409);
      var existing =
          send(sessions.get(2), HttpCliFixtures.token(), HttpCliFixtures.request("shared-key"));
      assertThat(existing.status()).isEqualTo(200);
      assertThat(HttpCliFixtures.JSON.readTree(existing.body()).path("operationId"))
          .isEqualTo(created.path("operationId"));

      String changedToken = HttpCliFixtures.token();
      assertThat(
              send(
                      sessions.get(3),
                      changedToken,
                      HttpCliFixtures.request("shared-key").put("title", "Changed"))
                  .status())
          .isEqualTo(409);
      assertThat(
              send(sessions.get(0), changedToken, HttpCliFixtures.request("changed-key")).status())
          .isEqualTo(409);
      counts(3, 1);

      String invalid =
          HttpCliFixtures.token(claims -> claims.put("exp", Instant.now().minusSeconds(1).getEpochSecond()));
      assertThat(send(sessions.get(1), invalid, HttpCliFixtures.request("invalid")).status())
          .isEqualTo(401);
      assertThat(
              send(
                      sessions.get(2),
                      HttpCliFixtures.token(),
                      HttpCliFixtures.request("authority").put("authenticated", true))
                  .status())
          .isEqualTo(400);
      assertThat(
              RegistrationHttpClient.send(
                      sessions.get(3).ready.port(),
                      HttpCliFixtures.token(),
                      HttpCliFixtures.token(),
                      HttpCliFixtures.bytes(HttpCliFixtures.request("human")))
                  .status())
          .isEqualTo(400);
      counts(3, 1);
    } finally {
      finish(sessions);
    }
  }

  @Test
  void trueConcurrentRequestsAcrossFourInstalledServersKeepTokenAndRequestKeyDistinct()
      throws Exception {
    var sessions = startFour(18_000);
    try {
      String oneToken = HttpCliFixtures.token();
      var exact = new ArrayList<Call>();
      for (int i = 0; i < 16; i++) {
        exact.add(new Call(sessions.get(i % 4), oneToken, HttpCliFixtures.request("race")));
      }
      var exactReplies = race(exact);
      assertThat(exactReplies.stream().filter(reply -> reply.status() == 201)).hasSize(1);
      assertThat(exactReplies.stream().filter(reply -> reply.status() == 409)).hasSize(15);
      counts(1, 1);

      database.reset();
      var distinct = new ArrayList<Call>();
      for (int i = 0; i < 16; i++) {
        distinct.add(
            new Call(sessions.get(i % 4), HttpCliFixtures.token(), HttpCliFixtures.request("race")));
      }
      var distinctReplies = race(distinct);
      assertThat(distinctReplies.stream().filter(reply -> reply.status() == 201)).hasSize(1);
      assertThat(distinctReplies.stream().filter(reply -> reply.status() == 200)).hasSize(15);
      assertThat(
              distinctReplies.stream()
                  .map(reply -> parse(reply).path("operationId").textValue())
                  .distinct())
          .hasSize(1);
      counts(16, 1);
    } finally {
      finish(sessions);
    }
  }

  @Test
  void checkSessionIsOfflineAndCapacityMismatchRefusesBeforeListening() throws Exception {
    SessionReceipt checked =
        installed.check(HttpCliFixtures.input(database, 1_000));
    assertThat(checked.outcome()).isEqualTo("CONFIG_VALID");
    counts(0, 0);

    var config = HttpCliFixtures.config(database).put("replayCapacity", 129);
    SessionReceipt refused =
        installed.check(
            HttpCliFixtures.frames(
                HttpCliFixtures.bytes(config), HttpCliFixtures.bytes(HttpCliFixtures.session(1_000))));
    assertThat(refused.outcome()).isEqualTo("CONFIG_VALID");
    Process process = installed.start("serve-bounded");
    try {
      process.getOutputStream().write(
          HttpCliFixtures.frames(
              HttpCliFixtures.bytes(config), HttpCliFixtures.bytes(HttpCliFixtures.session(1_000))));
      process.getOutputStream().close();
      SessionReceipt result = InstalledHttpArchive.read(process);
      assertThat(result.outcome()).isEqualTo("NOT_READY");
      assertThat(process.waitFor(10, TimeUnit.SECONDS)).isTrue();
      assertThat(process.exitValue()).isEqualTo(1);
      assertThat(process.getErrorStream().readAllBytes()).isEmpty();
      counts(0, 0);
    } finally {
      process.toHandle().destroyForcibly();
      process.waitFor(2, TimeUnit.SECONDS);
    }
  }

  @Test
  void installedDefaultFourPlusEightCapacityRefusesThirteenthAndResetsAfterRealJdbcHold()
      throws Exception {
    try (var gate = new CommitGate(database.port, true, 4)) {
      var config = HttpCliFixtures.config(database);
      ((com.fasterxml.jackson.databind.node.ObjectNode) config.get("replay"))
          .put("port", gate.port());
      var session = installed.serve(config, 12_000);
      var pool = Executors.newFixedThreadPool(12);
      try {
        var futures = new ArrayList<java.util.concurrent.Future<RegistrationHttpClient.Reply>>();
        for (int i = 0; i < 4; i++) {
          int index = i;
          futures.add(
              pool.submit(
                  () ->
                      send(
                          session,
                          HttpCliFixtures.token(),
                          HttpCliFixtures.request("active-" + index))));
        }
        assertThat(gate.held.await(10, TimeUnit.SECONDS)).as("four real commits held").isTrue();
        for (int i = 4; i < 12; i++) {
          int index = i;
          futures.add(
              pool.submit(
                  () ->
                      send(
                          session,
                          HttpCliFixtures.token(),
                          HttpCliFixtures.request("queued-" + index))));
        }
        Thread.sleep(300);
        var overflow =
            send(session, HttpCliFixtures.token(), HttpCliFixtures.request("thirteenth"));
        assertThat(overflow.status()).isIn(0, 503);
        if (overflow.status() == 503) {
          assertThat(overflow.body()).isEqualTo("{\"error\":\"HTTP_CAPACITY_UNAVAILABLE\"}");
        }

        gate.releaseAcknowledgements();
        for (var future : futures) assertThat(future.get(10, TimeUnit.SECONDS).status()).isEqualTo(201);
        assertThat(
                send(session, HttpCliFixtures.token(), HttpCliFixtures.request("after-reset"))
                    .status())
            .isEqualTo(201);
        counts(13, 13);
        assertThat(session.finish().outcome()).isEqualTo("SESSION_ENDED");
      } finally {
        gate.releaseAcknowledgements();
        pool.shutdownNow();
        assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        session.close();
      }
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void installedReplayAndRegistrationCommitReplyLossRemainUnknownAndNeverRetry(
      boolean replay)
      throws Exception {
    String authorization = HttpCliFixtures.token();
    var request = HttpCliFixtures.request("ack-loss");
    try (var gate = new CommitGate(database.port, replay, 1)) {
      var config = HttpCliFixtures.config(database);
      ((com.fasterxml.jackson.databind.node.ObjectNode)
              config.get(replay ? "replay" : "operations"))
          .put("port", gate.port());
      var session = installed.serve(config, 4_000);
      var pool = Executors.newSingleThreadExecutor();
      try {
        var result = pool.submit(() -> send(session, authorization, request));
        assertThat(gate.held.await(10, TimeUnit.SECONDS)).as("real commit reply held").isTrue();
        counts(1, replay ? 0 : 1);
        gate.loseAcknowledgements();
        var lost = result.get(10, TimeUnit.SECONDS);
        assertThat(lost.status()).isEqualTo(replay ? 409 : 503);
        assertThat(lost.body())
            .isEqualTo(
                replay
                    ? "{\"error\":\"AGENTCORE_REGISTRATION_REJECTED\"}"
                    : "{\"error\":\"AGENTCORE_REGISTRATION_UNKNOWN\"}");
        assertThat(session.finish().outcome()).isEqualTo("SESSION_ENDED");
      } finally {
        gate.loseAcknowledgements();
        pool.shutdownNow();
        assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        session.close();
      }
    }

    var recovery = installed.serve(database, 4_000);
    try {
      assertThat(send(recovery, authorization, HttpCliFixtures.request("cannot-bypass")).status())
          .isEqualTo(409);
      var explicit = send(recovery, HttpCliFixtures.token(), request);
      assertThat(explicit.status()).isEqualTo(replay ? 201 : 200);
      assertThat(HttpCliFixtures.JSON.readTree(explicit.body()).path("executionEnabled").booleanValue())
          .isFalse();
      counts(2, 1);
      assertThat(recovery.finish().outcome()).isEqualTo("SESSION_ENDED");
    } finally {
      recovery.close();
    }
  }

  @Test
  void killingOwnedWorkerAfterReadyProducesOneBoundFinalUnknownAndNoOrphan()
      throws Exception {
    var session = installed.serve(database, 10_000);
    try {
      long deadline = System.nanoTime() + java.time.Duration.ofSeconds(5).toNanos();
      List<ProcessHandle> workers = List.of();
      while (workers.isEmpty() && System.nanoTime() < deadline) {
        workers =
            session.process.descendants()
                .filter(
                    process ->
                        process
                            .info()
                            .arguments()
                            .map(
                                arguments ->
                                    java.util.Arrays.asList(arguments)
                                        .contains(InstalledHttpWorker.class.getName()))
                            .orElse(false))
                .toList();
        if (workers.isEmpty()) Thread.sleep(10);
      }
      assertThat(workers).hasSize(1);
      ProcessHandle worker = workers.get(0);
      assertThat(worker.destroyForcibly()).isTrue();
      SessionReceipt result = session.finish();
      assertThat(result.outcome()).isEqualTo("UNKNOWN");
      assertThat(result.port()).isEqualTo(session.ready.port());
      assertThat(worker.isAlive()).isFalse();
      counts(0, 0);
    } finally {
      session.close();
    }
  }

  private List<InstalledHttpArchive.RunningSession> startFour(long millis) throws Exception {
    var sessions = new ArrayList<InstalledHttpArchive.RunningSession>();
    try {
      for (int i = 0; i < 4; i++) sessions.add(installed.serve(database, millis));
      assertThat(sessions.stream().map(session -> session.process.pid()).distinct()).hasSize(4);
      return sessions;
    } catch (Throwable failure) {
      for (var session : sessions) session.close();
      throw failure;
    }
  }

  private RegistrationHttpClient.Reply send(
      InstalledHttpArchive.RunningSession session,
      String token,
      com.fasterxml.jackson.databind.node.ObjectNode request)
      throws Exception {
    return RegistrationHttpClient.send(
        session.ready.port(), token, new RegistrationHttpClient.ObjectNodeBody(request));
  }

  private List<RegistrationHttpClient.Reply> race(List<Call> calls) throws Exception {
    var ready = new CountDownLatch(calls.size());
    var go = new CountDownLatch(1);
    var pool = Executors.newFixedThreadPool(calls.size());
    try {
      var futures =
          calls.stream()
              .map(
                  call ->
                      pool.submit(
                          () -> {
                            ready.countDown();
                            assertThat(go.await(5, TimeUnit.SECONDS)).isTrue();
                            return send(call.session, call.token, call.request);
                          }))
              .toList();
      assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
      go.countDown();
      var replies = new ArrayList<RegistrationHttpClient.Reply>();
      for (var future : futures) replies.add(future.get(15, TimeUnit.SECONDS));
      return replies;
    } finally {
      go.countDown();
      pool.shutdownNow();
      assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
    }
  }

  private JsonNode parse(RegistrationHttpClient.Reply reply) {
    try {
      return HttpCliFixtures.JSON.readTree(reply.body());
    } catch (Exception failure) {
      throw new AssertionError("SYNTHETIC_RESPONSE_INVALID", failure);
    }
  }

  private void counts(long replay, long operations) {
    assertThat(database.count(database.replay, "tp_workload_replay_claims")).isEqualTo(replay);
    assertThat(database.count(database.operations, "tp_business_operations")).isEqualTo(operations);
    assertThat(database.count(database.operations, "tp_business_operation_facts"))
        .isEqualTo(operations * 3);
    assertThat(database.count(database.operations, "tp_operation_lifecycle_heads"))
        .isEqualTo(operations);
    assertThat(database.count(database.operations, "tp_operation_lifecycle_facts")).isZero();
  }

  private static void finish(List<InstalledHttpArchive.RunningSession> sessions) throws Exception {
    for (var session : sessions) {
      SessionReceipt result = session.finish();
      assertThat(result.outcome()).isEqualTo("SESSION_ENDED");
      assertThat(result.port()).isEqualTo(session.ready.port());
    }
  }

  private record Call(
      InstalledHttpArchive.RunningSession session,
      String token,
      com.fasterxml.jackson.databind.node.ObjectNode request) {}
}
