package io.tracepermit.adapters.http;

import static org.assertj.core.api.Assertions.assertThat;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;

/** Opt-in negative scheduling probe, never a replacement for the full real qualification. */
final class LatencyBarrierProbeTest {
  @Test void completedBarrierCanStillRejectTheNextRealHttpWave() throws Exception {
    var release = new CountDownLatch(1);
    var afterCompletion = new CountDownLatch(8);
    var controlTimedOut = new AtomicBoolean();
    var pool = new ThreadPoolExecutor(8, 8, 60, TimeUnit.SECONDS, new ArrayBlockingQueue<>(16),
        new ThreadPoolExecutor.AbortPolicy()) {
      @Override protected void afterExecute(Runnable task, Throwable failure) {
        super.afterExecute(task, failure);
        if (task instanceof RunnableFuture<?>) {
          afterCompletion.countDown();
          try { if (!release.await(5, TimeUnit.SECONDS)) controlTimedOut.set(true); }
          catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        }
      }
    };
    var diagnostics = new LatencyFailureDiagnostics();
    var observation = new LatencyAdmissionObservation(pool, diagnostics);
    var calls = new AtomicInteger(); var handled = new AtomicInteger();
    var driver = Executors.newSingleThreadExecutor();
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 32);
    server.setExecutor(pool);
    server.createContext("/probe", exchange -> {
      handled.incrementAndGet();
      byte[] body = "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
      exchange.getRequestBody().readAllBytes();
      exchange.sendResponseHeaders(200, body.length);
      exchange.getResponseBody().write(body); exchange.close();
    });
    server.start();
    int port = server.getAddress().getPort();
    var client = ApprovalLatencyMeasurements.newQualificationHttpClient();
    try {
      boolean reportedQuiet = ApprovalAdmissionExecutors.awaitQuiescence(pool, 8, Duration.ofSeconds(1));
      assertThat(reportedQuiet).isTrue();
      assertThat(afterCompletion.await(1, TimeUnit.SECONDS)).isTrue();
      assertThat(pool.getActiveCount()).isEqualTo(8);
      var items = new ArrayList<ApprovalLatencyMeasurements.ApprovalItem>();
      for (int i = 0; i < 20; i++) items.add(new ApprovalLatencyMeasurements.ApprovalItem(i));
      var result = driver.submit(() -> ApprovalLatencyMeasurements.runConcurrent(20, items, item -> {
        calls.incrementAndGet();
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/probe"))
            .timeout(Duration.ofSeconds(3)).POST(HttpRequest.BodyPublishers.ofString("{}")).build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) throw new IllegalStateException("PROBE_HTTP_STATUS");
      }, Duration.ofSeconds(4)));
      long stop = System.nanoTime() + Duration.ofSeconds(2).toNanos();
      while (diagnostics.admissionCategories().getOrDefault("HTTP_EXCHANGE_REJECTED", 0) < 4 && System.nanoTime() < stop) {
        Thread.sleep(5);
      }
      release.countDown();
      var batch = result.get(5, TimeUnit.SECONDS);
      System.out.println("CONTROL reported_quiet=" + reportedQuiet + " client_attempts=" + calls.get()
          + " handled=" + handled.get() + " failures=" + batch.failureCount()
          + " admission=" + diagnostics.admissionCategories()
          + " causes=" + LatencyFailureDiagnostics.operationCategories(batch.failureCodes()));
      assertThat(calls).hasValue(20);
      assertThat(diagnostics.admissionCategories().get("HTTP_EXCHANGE_REJECTED")).isGreaterThanOrEqualTo(4);
      assertThat(batch.failureCount()).isGreaterThanOrEqualTo(4);
      assertThat(batch.successCount() + batch.failureCount() + batch.timeoutCount() + batch.cancelledCount()).isEqualTo(20);
      assertThat(controlTimedOut).isFalse();
    } finally {
      release.countDown();
      try {
        server.stop(0);
      } finally {
        try {
          observation.close();
        } finally {
          pool.shutdownNow(); driver.shutdownNow();
          try {
            assertThat(pool.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
            assertThat(driver.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
          } finally {
            ApprovalLatencyMeasurements.retireQualificationHttpClient(client);
          }
        }
      }
    }
    try (var releasedPort = new java.net.ServerSocket()) {
      releasedPort.setReuseAddress(true);
      releasedPort.bind(new InetSocketAddress("127.0.0.1", port));
    }
    System.out.println("CONTROL cleanup=CLIENT_RETIRED_EXECUTORS_TERMINATED_LOOPBACK_PORT_RELEASED");
  }
}
