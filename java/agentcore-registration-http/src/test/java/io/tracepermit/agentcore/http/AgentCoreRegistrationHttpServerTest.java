package io.tracepermit.agentcore.http;

import static io.tracepermit.agentcore.http.HttpRegistrationTestSupport.*;
import static org.assertj.core.api.Assertions.*;

import io.tracepermit.application.BusinessOperationRegistration;
import io.tracepermit.application.BusinessOperationScope;
import io.tracepermit.application.BusinessOperationStorageException;
import io.tracepermit.contracts.JsonSupport;
import java.io.IOException;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

final class AgentCoreRegistrationHttpServerTest {
  @Test
  void realSignedHttpRegistersMinimalReceiptRetriesOriginalAndReleasesEveryConnection() throws Exception {
    try (var support = new HttpRegistrationTestSupport()) {
      var repository = new MemoryRepository();
      try (var server = new AgentCoreRegistrationHttpServer(support.ingress(repository), 0, AgentCoreRegistrationHttpServer.Limits.defaults())) {
        var first = send(server.port(), token(), body());
        assertThat(first.status()).isEqualTo(201);
        var json = JsonSupport.MAPPER.readTree(first.body());
        assertThat(json.size()).isEqualTo(3);
        assertThat(json.path("created").booleanValue()).isTrue();
        assertThat(json.path("executionEnabled").booleanValue()).isFalse();
        String id = json.path("operationId").textValue();
        for (int i = 0; i < 20; i++) {
          var retry = send(server.port(), token(), body());
          assertThat(retry.status()).isEqualTo(200);
          assertThat(JsonSupport.MAPPER.readTree(retry.body()).path("operationId").textValue()).isEqualTo(id);
          assertThat(retry.body()).doesNotContain("canonicalAction", "Fingerprint", "trustedContext", "authorization", "sha256:");
        }
        var conflict = send(server.port(), token(), body().put("title", "Synthetic changed"));
        assertThat(conflict.status()).isEqualTo(409);
        assertThat(conflict.body()).isEqualTo("{\"error\":\"AGENTCORE_REQUEST_KEY_CONFLICT\"}");
        await(() -> server.occupancy().held() == 0);
        assertThat(support.jwksFetches.get()).isEqualTo(1);
        assertThat(repository.originals).hasSize(1);
      }
    }
  }

  @Test
  void exactTokenReplayIsRejectedOverHttpBeforeAnotherRepositoryCall() throws Exception {
    try (var support = new HttpRegistrationTestSupport()) {
      var repository = new MemoryRepository();
      try (var server = new AgentCoreRegistrationHttpServer(support.ingress(repository), 0, AgentCoreRegistrationHttpServer.Limits.defaults())) {
        String authorization = token();
        assertThat(send(server.port(), authorization, body()).status()).isEqualTo(201);
        var rejected = send(server.port(), authorization, body());
        assertThat(rejected.status()).isEqualTo(409);
        assertThat(rejected.body()).isEqualTo("{\"error\":\"AGENTCORE_REGISTRATION_REJECTED\"}");
        assertThat(repository.calls.get()).isEqualTo(1);
      }
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"query", "fragment", "trailing", "encoded", "absolute", "double", "method", "host", "cookie",
      "forwarded", "x-forwarded", "mixed-authorization", "duplicate-authorization", "duplicate-length", "duplicate-host",
      "comma-authorization", "transfer-encoding", "content-type", "content-encoding", "expect", "fold", "colon", "bare-lf"})
  void rejectsAmbiguousRouteHeaderAndFramingBeforeVerification(String change) throws Exception {
    try (var support = new HttpRegistrationTestSupport()) {
      var repository = new MemoryRepository();
      try (var server = new AgentCoreRegistrationHttpServer(support.ingress(repository), 0, AgentCoreRegistrationHttpServer.Limits.defaults())) {
        byte[] bytes = bytes(body());
        String method = change.equals("method") ? "GET" : "POST";
        String path = switch (change) {
          case "query" -> AgentCoreRegistrationHttpServer.ROUTE + "?tenantId=other";
          case "fragment" -> AgentCoreRegistrationHttpServer.ROUTE + "#other";
          case "trailing" -> AgentCoreRegistrationHttpServer.ROUTE + "/";
          case "encoded" -> "/v1/agentcore/%72egistrations";
          case "absolute" -> "http://127.0.0.1:" + server.port() + AgentCoreRegistrationHttpServer.ROUTE;
          case "double" -> "/v1//agentcore/registrations";
          default -> AgentCoreRegistrationHttpServer.ROUTE;
        };
        var fields = new ArrayList<>(List.of("Host: " + (change.equals("host") ? "localhost:" : "127.0.0.1:") + server.port(),
            "Authorization: " + (change.equals("comma-authorization") ? token() + ", " + token() : token()),
            "Content-Type: " + (change.equals("content-type") ? "text/plain" : "application/json"), "Content-Length: " + bytes.length));
        switch (change) {
          case "cookie" -> fields.add("Cookie: session=" + CANARY);
          case "forwarded" -> fields.add("Forwarded: for=127.0.0.1");
          case "x-forwarded" -> fields.add("X-Forwarded-User: " + CANARY);
          case "mixed-authorization" -> fields.add("aUtHoRiZaTiOn: " + token());
          case "duplicate-authorization" -> fields.add("Authorization: " + token());
          case "duplicate-length" -> fields.add("Content-Length: " + bytes.length);
          case "duplicate-host" -> fields.add("Host: 127.0.0.1:" + server.port());
          case "transfer-encoding" -> fields.add("Transfer-Encoding: chunked");
          case "content-encoding" -> fields.add("Content-Encoding: gzip");
          case "expect" -> fields.add("Expect: 100-continue");
          case "fold" -> fields.add(" " + CANARY);
          case "colon" -> fields.add("Authorization : " + token());
          case "bare-lf" -> fields.add("X-Bad: value\n" + CANARY);
          default -> { }
        }
        var result = send(server.port(), request(server.port(), method, path, fields, bytes));
        assertThat(result.status()).isIn(400, 404, 405, 415, 417);
        assertThat(result.body()).doesNotContain(CANARY, "tenantId", "session", "Bearer", "127.0.0.1");
        assertThat(support.verifications.get()).isZero();
        assertThat(repository.calls.get()).isZero();
      }
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"Human-Authorization", "X-Human-Authorization", "Delegation", "X-Delegation",
      "On-Behalf-Of", "X-On-Behalf-Of", "Actor-Token", "Subject-Token"})
  void unrelatedHumanHeadersRemainContractRequiredEvenWithRealValidTokens(String header) throws Exception {
    try (var support = new HttpRegistrationTestSupport()) {
      var repository = new MemoryRepository();
      try (var server = new AgentCoreRegistrationHttpServer(support.ingress(repository), 0, AgentCoreRegistrationHttpServer.Limits.defaults())) {
        byte[] bytes = bytes(body());
        var fields = List.of("Host: 127.0.0.1:" + server.port(), "Authorization: " + token(),
            header + ": " + token(claims -> claims.put("sub", "human:synthetic")), "Content-Length: " + bytes.length,
            "Content-Type: application/json");
        var response = send(server.port(), request(server.port(), "POST", AgentCoreRegistrationHttpServer.ROUTE, fields, bytes));
        assertThat(response.status()).isEqualTo(400);
        assertThat(response.body()).isEqualTo("{\"error\":\"AGENTCORE_DELEGATION_CONTRACT_REQUIRED\"}");
        assertThat(support.verifications.get()).isZero(); assertThat(repository.calls.get()).isZero();
      }
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"issuer", "audience", "subject", "client", "tenant", "scope", "expired", "sender-bound"})
  void realIdentityAndEnrollmentRejectionsNeverReachRepository(String change) throws Exception {
    try (var support = new HttpRegistrationTestSupport()) {
      var repository = new MemoryRepository();
      try (var server = new AgentCoreRegistrationHttpServer(support.ingress(repository), 0, AgentCoreRegistrationHttpServer.Limits.defaults())) {
        String authorization = token(claims -> {
          switch (change) {
            case "issuer" -> claims.put("iss", "https://other.example.invalid");
            case "audience" -> claims.put("aud", "https://other-resource.example.invalid");
            case "subject" -> claims.put("sub", "workload:other");
            case "client" -> claims.put("client_id", "other-client");
            case "tenant" -> claims.put("org_id", "other-org");
            case "scope" -> claims.put("scope", "operations.other");
            case "expired" -> claims.put("exp", NOW.getEpochSecond());
            case "sender-bound" -> claims.putObject("cnf").put("x5t#S256", java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]));
            default -> throw new AssertionError("UNKNOWN_SYNTHETIC_CASE");
          }
        });
        var result = send(server.port(), authorization, body());
        assertThat(result.status()).isEqualTo(401); assertThat(repository.calls.get()).isZero();
        assertThat(result.headers()).contains("WWW-Authenticate: Bearer");
        assertThat(result.body()).isEqualTo("{\"error\":\"AGENTCORE_IDENTITY_REJECTED\"}");
      }
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"tenantId", "workspaceId", "requesterPrincipalId", "authenticated", "approved", "verified", "policyRelease", "generation", "target"})
  void bodyAuthorityTamperingIsOwnedByTheRealIngress(String field) throws Exception {
    try (var support = new HttpRegistrationTestSupport()) {
      var repository = new MemoryRepository();
      try (var server = new AgentCoreRegistrationHttpServer(support.ingress(repository), 0, AgentCoreRegistrationHttpServer.Limits.defaults())) {
        var reply = send(server.port(), token(), body().put(field, CANARY));
        assertThat(reply.status()).isEqualTo(400);
        assertThat(reply.body()).isEqualTo("{\"error\":\"AGENTCORE_REQUEST_INVALID\"}");
        assertThat(support.verifications.get()).isZero(); assertThat(repository.calls.get()).isZero();
      }
    }
  }

  @Test
  void overflowHeadersAndDeclaredBodyRejectWithoutReadingOrAllocatingAnOversizedBody() throws Exception {
    try (var support = new HttpRegistrationTestSupport()) {
      var repository = new MemoryRepository();
      try (var server = new AgentCoreRegistrationHttpServer(support.ingress(repository), 0, AgentCoreRegistrationHttpServer.Limits.defaults())) {
        var fields = List.of("Host: 127.0.0.1:" + server.port(), "Authorization: " + token(),
            "Content-Type: application/json", "Content-Length: 32769");
        assertThat(send(server.port(), request(server.port(), "POST", AgentCoreRegistrationHttpServer.ROUTE, fields, new byte[0])).status()).isEqualTo(413);
        byte[] oversizedHeaders = ("POST " + AgentCoreRegistrationHttpServer.ROUTE + " HTTP/1.1\r\nX: "
            + "x".repeat(RegistrationHttpCodec.MAX_HEADER_BYTES)).getBytes(StandardCharsets.US_ASCII);
        assertThat(send(server.port(), oversizedHeaders).status()).isEqualTo(431);
        assertThat(support.verifications.get()).isZero(); assertThat(repository.calls.get()).isZero();
      }
    }
  }

  @Test
  void malformedUtf8AndAmbiguousJsonReachOnlyTheExistingStrictParser() throws Exception {
    try (var support = new HttpRegistrationTestSupport()) {
      var repository = new MemoryRepository();
      try (var server = new AgentCoreRegistrationHttpServer(support.ingress(repository), 0, AgentCoreRegistrationHttpServer.Limits.defaults())) {
        for (byte[] bad : List.of(new byte[] {(byte) 0xc3, 0x28}, "{} {}".getBytes(StandardCharsets.UTF_8),
            "{\"schemaVersion\":\"1.0\",\"schemaVersion\":\"1.0\"}".getBytes(StandardCharsets.UTF_8))) {
          assertThat(send(server.port(), request(server.port(), token(), bad)).body()).isEqualTo("{\"error\":\"AGENTCORE_REQUEST_INVALID\"}");
        }
        assertThat(support.verifications.get()).isZero(); assertThat(repository.calls.get()).isZero();
      }
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void tricklingPartialHeadersAndBodiesCannotExtendAbsolutePhaseDeadline(boolean bodyPhase) throws Exception {
    try (var support = new HttpRegistrationTestSupport()) {
      var repository = new MemoryRepository();
      var limits = new AgentCoreRegistrationHttpServer.Limits(4, 8, Duration.ofMillis(250), Duration.ofMillis(250), Duration.ofSeconds(2), Duration.ofMillis(500));
      try (var server = new AgentCoreRegistrationHttpServer(support.ingress(repository), 0, limits);
          Socket socket = socket(server.port())) {
        long started = System.nanoTime();
        if (bodyPhase) {
          var fields = List.of("Host: 127.0.0.1:" + server.port(), "Authorization: " + token(), "Content-Type: application/json", "Content-Length: 100");
          socket.getOutputStream().write(request(server.port(), "POST", AgentCoreRegistrationHttpServer.ROUTE, fields, new byte[0]));
        } else socket.getOutputStream().write(("POST " + AgentCoreRegistrationHttpServer.ROUTE + " HTTP/1.1\r\nX: ").getBytes(StandardCharsets.US_ASCII));
        var trickler = Executors.newSingleThreadExecutor();
        try {
          var future = trickler.submit(() -> {
            for (int i = 0; i < 30; i++) {
              try { socket.getOutputStream().write('x'); socket.getOutputStream().flush(); Thread.sleep(40); }
              catch (IOException closed) { break; }
              catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); break; }
            }
          });
          assertClosed(socket);
          assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofMillis(1_500));
          future.cancel(true);
        } finally { trickler.shutdownNow(); assertThat(trickler.awaitTermination(2, TimeUnit.SECONDS)).isTrue(); }
        assertThat(support.verifications.get()).isZero(); assertThat(repository.calls.get()).isZero();
      }
    }
  }

  @Test
  void defaultWorkerQueueExhaustionAndCloseAreBoundedWithoutRunningOverflowOrQueuedWork() throws Exception {
    try (var support = new HttpRegistrationTestSupport()) {
      CountDownLatch entered = new CountDownLatch(4), release = new CountDownLatch(1);
      var repository = blocked(entered, release);
      var server = new AgentCoreRegistrationHttpServer(support.ingress(repository), 0, AgentCoreRegistrationHttpServer.Limits.defaults());
      var clients = new ArrayList<Socket>();
      try {
        for (int i = 0; i < 4; i++) clients.add(openRequest(server.port()));
        assertThat(entered.await(4, TimeUnit.SECONDS)).isTrue();
        for (int i = 0; i < 8; i++) clients.add(openRequest(server.port()));
        await(() -> server.occupancy().pending() == 8);
        assertThat(server.occupancy().held()).isEqualTo(12);
        var overflow = send(server.port(), token(), body());
        assertThat(overflow.status()).isIn(0, 503);
        if (overflow.status() == 503) assertThat(overflow.body()).isEqualTo("{\"error\":\"HTTP_CAPACITY_UNAVAILABLE\"}");
        assertThat(repository.calls.get()).isEqualTo(4); assertThat(support.verifications.get()).isEqualTo(4);
        long start = System.nanoTime(); server.close();
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofMillis(1_700));
        assertThat(repository.calls.get()).isEqualTo(4);
        assertThatThrownBy(() -> socket(server.port())).isInstanceOf(IOException.class);
      } finally {
        release.countDown(); server.close();
        for (Socket client : clients) client.close();
        await(() -> server.occupancy().active() == 0 && server.occupancy().held() == 0);
      }
    }
  }

  @Test
  void overallDeadlineClosesNetworkButRetainsRunningCapacityUntilActualReturn() throws Exception {
    try (var support = new HttpRegistrationTestSupport()) {
      CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
      var repository = blocked(entered, release);
      var limits = new AgentCoreRegistrationHttpServer.Limits(4, 8, Duration.ofMillis(250), Duration.ofMillis(250), Duration.ofMillis(500), Duration.ofMillis(200));
      try (var server = new AgentCoreRegistrationHttpServer(support.ingress(repository), 0, limits);
          Socket client = openRequest(server.port())) {
        try {
          assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
          assertClosed(client);
          assertThat(server.occupancy().held()).isEqualTo(1);
          assertThat(server.occupancy().active()).isEqualTo(1);
          assertThat(repository.originals).isEmpty();
        } finally { release.countDown(); }
        await(() -> server.occupancy().held() == 0 && server.occupancy().active() == 0);
        assertThat(repository.originals).hasSize(1); // Late metadata is possible, never dispatch authority.
      }
    }
  }

  @Test
  void fixedFailuresNeverEchoExceptionsOrCredentialsAndUnknownCommitNeverReportsSuccess() throws Exception {
    try (var support = new HttpRegistrationTestSupport()) {
      for (var code : List.of(BusinessOperationStorageException.Code.OPERATION_COMMIT_UNKNOWN,
          BusinessOperationStorageException.Code.OPERATION_STORAGE_UNAVAILABLE)) {
        var repository = new MemoryRepository() {
          @Override public BusinessOperationRegistration createOrGet(BusinessOperationScope scope, com.fasterxml.jackson.databind.JsonNode operation) {
            throw new BusinessOperationStorageException(code);
          }
        };
        try (var server = new AgentCoreRegistrationHttpServer(support.ingress(repository), 0, AgentCoreRegistrationHttpServer.Limits.defaults())) {
          String authorization = token();
          var response = send(server.port(), authorization, body().put("title", CANARY));
          assertThat(response.status()).isEqualTo(503);
          assertThat(response.body()).contains(code == BusinessOperationStorageException.Code.OPERATION_COMMIT_UNKNOWN ? "AGENTCORE_REGISTRATION_UNKNOWN" : "AGENTCORE_STORAGE_UNAVAILABLE")
              .doesNotContain(CANARY, authorization, "created", "Fingerprint", "snapshot");
        }
      }
    }
  }

  @Test
  void bindFailureIsFixedAndNoAddressOrExecutableOverrideExists() throws Exception {
    try (var support = new HttpRegistrationTestSupport();
        var server = new AgentCoreRegistrationHttpServer(support.ingress(new MemoryRepository()), 0, AgentCoreRegistrationHttpServer.Limits.defaults())) {
      assertThatThrownBy(() -> new AgentCoreRegistrationHttpServer(support.ingress(new MemoryRepository()), server.port(), AgentCoreRegistrationHttpServer.Limits.defaults()))
          .hasMessage("HTTP_START_FAILED").hasNoCause();
      assertThat(AgentCoreRegistrationHttpServer.class.getDeclaredConstructors()).hasSize(1);
      assertThat(java.util.Arrays.stream(AgentCoreRegistrationHttpServer.class.getMethods()).map(java.lang.reflect.Method::getName))
          .doesNotContain("main", "bind", "setAddress");
    }
  }

  @Test
  void exact32768ByteUtf8BodyIsAcceptedWithoutChangingTheIngressCeiling() throws Exception {
    try (var support = new HttpRegistrationTestSupport()) {
      var repository = new MemoryRepository();
      try (var server = new AgentCoreRegistrationHttpServer(support.ingress(repository), 0, AgentCoreRegistrationHttpServer.Limits.defaults())) {
        var requestBody = body().put("body", "\u00e9".repeat(16_300));
        int padding = 32_768 - bytes(requestBody).length;
        assertThat(padding).isBetween(0, 84);
        requestBody.put("body", "\u00e9".repeat(16_300) + "x".repeat(padding));
        assertThat(bytes(requestBody)).hasSize(32_768);
        assertThat(send(server.port(), token(), requestBody).status()).isEqualTo(201);
        assertThat(repository.calls.get()).isEqualTo(1);
      }
    }
  }

  @Test
  void ordinaryJdkHttp11ClientReceivesOnlyTheMinimalReceipt() throws Exception {
    try (var support = new HttpRegistrationTestSupport();
        var server = new AgentCoreRegistrationHttpServer(support.ingress(new MemoryRepository()), 0, AgentCoreRegistrationHttpServer.Limits.defaults());
        var client = java.net.http.HttpClient.newBuilder().version(java.net.http.HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(2)).build()) {
      var request = java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:" + server.port() + AgentCoreRegistrationHttpServer.ROUTE))
          .version(java.net.http.HttpClient.Version.HTTP_1_1).timeout(Duration.ofSeconds(3))
          .header("Authorization", token()).header("Content-Type", "application/json")
          .POST(java.net.http.HttpRequest.BodyPublishers.ofByteArray(bytes(body()))).build();
      var reply = client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
      assertThat(reply.statusCode()).isEqualTo(201);
      assertThat(JsonSupport.MAPPER.readTree(reply.body()).size()).isEqualTo(3);
      assertThat(reply.headers().firstValue("connection")).contains("close");
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"missing-auth", "empty-auth", "missing-length", "negative-length", "leading-zero-length", "huge-length", "unknown-scheme", "empty-body", "wrong-version", "unknown-header", "space-value", "control", "too-many"})
  void closedHeaderSubsetAndLengthErrorsAreFixed(String change) throws Exception {
    try (var support = new HttpRegistrationTestSupport()) {
      var repository = new MemoryRepository();
      try (var server = new AgentCoreRegistrationHttpServer(support.ingress(repository), 0, AgentCoreRegistrationHttpServer.Limits.defaults())) {
        byte[] data = change.equals("empty-body") ? new byte[0] : bytes(body());
        var fields = new ArrayList<String>();
        fields.add("Host: 127.0.0.1:" + server.port()); fields.add("Content-Type: application/json");
        if (!change.equals("missing-auth")) fields.add("Authorization: " + (change.equals("empty-auth") ? "" : change.equals("unknown-scheme") ? "Basic synthetic" : token()));
        if (!change.equals("missing-length")) fields.add("Content-Length: " + switch (change) {
          case "negative-length" -> "-1";
          case "leading-zero-length" -> "001";
          case "huge-length" -> "999999999999999999999999";
          default -> Integer.toString(data.length);
        });
        if (change.equals("unknown-header")) fields.add("X-Unknown: " + CANARY);
        if (change.equals("space-value")) fields.add("Accept:  application/json");
        if (change.equals("control")) fields.add("Accept: application/json\t");
        if (change.equals("too-many")) for (int i = 0; i < 17; i++) fields.add("X-Header-" + i + ": value");
        byte[] wire = request(server.port(), "POST", AgentCoreRegistrationHttpServer.ROUTE, fields, data);
        if (change.equals("wrong-version")) wire = new String(wire, StandardCharsets.US_ASCII).replace("HTTP/1.1", "HTTP/1.0").getBytes(StandardCharsets.US_ASCII);
        var reply = send(server.port(), wire);
        assertThat(reply.status()).isIn(400, 401, 411, 413);
        assertThat(reply.body()).doesNotContain(CANARY, "Basic synthetic");
        assertThat(repository.calls.get()).isZero();
      }
    }
  }

  @Test
  void queuedRequestsExpireWithoutLaterAuthenticationWhenBlockedWorkersReturn() throws Exception {
    try (var support = new HttpRegistrationTestSupport()) {
      CountDownLatch entered = new CountDownLatch(4), release = new CountDownLatch(1);
      var repository = blocked(entered, release);
      var limits = new AgentCoreRegistrationHttpServer.Limits(4, 8, Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofMillis(200));
      try (var server = new AgentCoreRegistrationHttpServer(support.ingress(repository), 0, limits)) {
        var clients = new ArrayList<Socket>();
        try {
          for (int i = 0; i < 4; i++) clients.add(openRequest(server.port()));
          assertThat(entered.await(4, TimeUnit.SECONDS)).isTrue();
          try (Socket queued = openRequest(server.port())) {
            await(() -> server.occupancy().pending() == 1);
            assertClosed(queued);
            await(() -> server.occupancy().pending() == 0);
            assertThat(repository.calls.get()).isEqualTo(4);
            assertThat(support.verifications.get()).isEqualTo(4);
            assertThat(server.occupancy().held()).isEqualTo(4);
          }
        } finally {
          release.countDown(); for (Socket client : clients) client.close();
        }
        await(() -> server.occupancy().active() == 0 && server.occupancy().held() == 0);
        assertThat(repository.calls.get()).isEqualTo(4);
      }
    }
  }

  @Test
  void closePreservesCallerInterruptionAndIsIdempotent() throws Exception {
    try (var support = new HttpRegistrationTestSupport()) {
      var server = new AgentCoreRegistrationHttpServer(support.ingress(new MemoryRepository()), 0, AgentCoreRegistrationHttpServer.Limits.defaults());
      AtomicBoolean retained = new AtomicBoolean();
      Thread caller = new Thread(() -> {
        Thread.currentThread().interrupt(); server.close(); retained.set(Thread.currentThread().isInterrupted());
      });
      caller.start(); caller.join(2_000);
      assertThat(caller.isAlive()).isFalse(); assertThat(retained.get()).isTrue();
      server.close();
      assertThatThrownBy(() -> socket(server.port())).isInstanceOf(IOException.class);
    }
  }

  @Test
  void configurationBoundsAndUnexpectedDependencyErrorsCannotExposeSensitiveDiagnostics() throws Exception {
    var defaults = AgentCoreRegistrationHttpServer.Limits.defaults();
    for (int workers : new int[] {0, 17}) assertThatThrownBy(() -> new AgentCoreRegistrationHttpServer.Limits(workers, 8,
        defaults.headers(), defaults.body(), defaults.overall(), defaults.close())).hasMessage("HTTP_LIMITS_INVALID");
    for (int pending : new int[] {0, 65}) assertThatThrownBy(() -> new AgentCoreRegistrationHttpServer.Limits(4, pending,
        defaults.headers(), defaults.body(), defaults.overall(), defaults.close())).hasMessage("HTTP_LIMITS_INVALID");
    for (Duration duration : java.util.Arrays.asList(null, Duration.ZERO, Duration.ofSeconds(31))) {
      assertThatThrownBy(() -> new AgentCoreRegistrationHttpServer.Limits(4, 8, duration, defaults.body(), defaults.overall(), defaults.close())).hasMessage("HTTP_LIMITS_INVALID");
    }
    assertThatThrownBy(() -> new AgentCoreRegistrationHttpServer.Limits(4, 8, defaults.headers(), defaults.body(), defaults.overall(), Duration.ofSeconds(3))).hasMessage("HTTP_LIMITS_INVALID");
    assertThatThrownBy(() -> new AgentCoreRegistrationHttpServer.Limits(4, 8, Duration.ofSeconds(6), defaults.body(), defaults.overall(), defaults.close())).hasMessage("HTTP_LIMITS_INVALID");
    assertThatThrownBy(() -> new AgentCoreRegistrationHttpServer.Limits(4, 8, defaults.headers(), Duration.ofSeconds(6), defaults.overall(), defaults.close())).hasMessage("HTTP_LIMITS_INVALID");
    assertThatThrownBy(() -> new AgentCoreRegistrationHttpServer(null, 0, defaults)).hasMessage("HTTP_CONFIGURATION_INVALID");
    assertThat(RegistrationHttpCodec.ingressError(CANARY).toString()).doesNotContain(CANARY);
    try (var support = new HttpRegistrationTestSupport()) {
      var repository = new MemoryRepository() {
        @Override public BusinessOperationRegistration createOrGet(BusinessOperationScope scope, com.fasterxml.jackson.databind.JsonNode operation) {
          throw new AssertionError(CANARY);
        }
      };
      try (var server = new AgentCoreRegistrationHttpServer(support.ingress(repository), 0, defaults)) {
        var response = send(server.port(), token(), body());
        assertThat(response.status()).isEqualTo(503);
        assertThat(response.body()).isEqualTo("{\"error\":\"HTTP_REGISTRATION_UNAVAILABLE\"}").doesNotContain(CANARY);
      }
    }
  }

  private static MemoryRepository blocked(CountDownLatch entered, CountDownLatch release) {
    return new MemoryRepository() {
      @Override public BusinessOperationRegistration createOrGet(BusinessOperationScope scope, com.fasterxml.jackson.databind.JsonNode operation) {
        calls.incrementAndGet(); entered.countDown();
        boolean interrupted = false;
        while (true) {
          try { if (!release.await(8, TimeUnit.SECONDS)) throw new AssertionError("SYNTHETIC_GATE_TIMEOUT"); break; }
          catch (InterruptedException ignored) { interrupted = true; }
        }
        try {
          // Calls is an entry counter, avoid double-counting the delegated memory fixture write.
          calls.decrementAndGet(); return super.createOrGet(scope, operation);
        } finally { if (interrupted) Thread.currentThread().interrupt(); }
      }
    };
  }
  private static Socket openRequest(int port) throws IOException {
    Socket socket = socket(port); socket.getOutputStream().write(request(port, token(), bytes(body()))); socket.getOutputStream().flush(); return socket;
  }
  private static void assertClosed(Socket socket) throws IOException {
    try { assertThat(socket.getInputStream().read()).isEqualTo(-1); }
    catch (SocketTimeoutException failure) { throw new AssertionError("TRANSPORT_DEADLINE_NOT_ENFORCED"); }
    catch (SocketException reset) { /* A reset is transport closure, not a rollback assertion. */ }
  }
}
