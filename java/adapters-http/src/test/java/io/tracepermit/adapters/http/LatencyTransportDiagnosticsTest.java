package io.tracepermit.adapters.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.EOFException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketException;
import java.net.http.HttpTimeoutException;
import java.nio.channels.ClosedChannelException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

final class LatencyTransportDiagnosticsTest {
  @Test void boundedCauseCategoriesNeverCopyMessagesOrUnknownClassNames() {
    String marker = "Bearer synthetic-private-canary";
    var categories = List.of(
        new IOException(marker, new EOFException(marker)),
        new IOException(marker, new SocketException("Connection reset")),
        new IOException(marker, new ConnectException(marker)),
        new IOException(marker, new ClosedChannelException()),
        new IOException("HTTP/1.1 header parser received no bytes"),
        new IOException(marker));
    var expected = List.of("HTTP_IO_EOF", "HTTP_IO_RESET", "HTTP_IO_CONNECT", "HTTP_IO_CLOSED", "HTTP_IO_HEADER_EOF", "HTTP_IO_OTHER");
    for (int i = 0; i < categories.size(); i++) {
      String code = ApprovalLatencyMeasurements.classifySafeErrorCode(categories.get(i));
      assertThat(code).isEqualTo(expected.get(i)); assertThat(code).doesNotContain(marker);
      assertThat(LatencyFailureDiagnostics.operationCategories(List.of(code))).containsEntry(code, 1);
    }
    IOException first = new IOException(marker), second = new IOException(marker);
    first.initCause(second); second.initCause(first);
    assertThat(LatencyFailureDiagnostics.transportCategory(first)).isEqualTo("HTTP_IO_OTHER");
  }

  @Test void causeDepthIsFiniteAndWrappedFailuresDoNotChangeOutcomeAccounting() {
    IOException chain = new IOException("private", new EOFException("private"));
    for (int i = 0; i < 9; i++) chain = new IOException("private", chain);
    assertThat(LatencyFailureDiagnostics.transportCategory(chain)).isEqualTo("HTTP_IO_OTHER");
    var failures = new ArrayList<String>();
    var phase = ApprovalLatencyMeasurements.runPhase("probe", 1, List.of(), List.of(
        new ApprovalLatencyMeasurements.ApprovalItem(1), new ApprovalLatencyMeasurements.ApprovalItem(2)), item -> {
      if (item.port == 1) throw new IOException("private", new EOFException("private"));
      throw new HttpTimeoutException("private");
    }, failures);
    assertThat(phase.failureCount()).isEqualTo(1); assertThat(phase.timeoutCount()).isEqualTo(1);
    assertThat(phase.successCount()).isZero(); assertThat(failures).singleElement().asString().contains("HTTP_IO_EOF").doesNotContain("private");
  }

  @Test void consequentialMissingIdentityFailuresStaySeparateFromTransportFailure() {
    for (String code : List.of("CANNOT_QUERY_STATUS_MISSING_ID", "CANNOT_REVIEW_MISSING_ID_OR_HASH", "CANNOT_RESUME_MISSING_ID_OR_HASH")) {
      assertThat(ApprovalLatencyMeasurements.classifySafeErrorCode(new IllegalStateException(code))).isEqualTo(code);
      assertThat(LatencyFailureDiagnostics.operationCategories(List.of(code))).containsEntry(code, 1);
    }
  }

  @Test void statusLookalikesAndUntrustedExceptionNamesCannotReachPhaseDiagnostics() {
    var failures = new ArrayList<String>();
    var phase = ApprovalLatencyMeasurements.runPhase("probe", 1, List.of(), List.of(
        new ApprovalLatencyMeasurements.ApprovalItem(1)), item -> {
      throw new IllegalStateException("HTTP_REVIEW_STATUS_503_PRIVATE_CANARY");
    }, failures);
    assertThat(phase.failureCount()).isEqualTo(1);
    assertThat(failures.toString()).contains("OPERATION_FAILED_IllegalStateException").doesNotContain("PRIVATE_CANARY");
    class PrivateCanaryException extends Exception { private static final long serialVersionUID = 1L; }
    assertThat(ApprovalLatencyMeasurements.classifySafeErrorCode(new PrivateCanaryException())).isEqualTo("OTHER_OPERATION_FAILURE");
    var diagnostics = new LatencyFailureDiagnostics();
    for (int i = 0; i < 65_540; i++) diagnostics.admissionRejected(false, 100, 2000);
    assertThat(diagnostics.admissionCategories()).containsEntry("HTTP_EXCHANGE_REJECTED", 65_535)
        .containsEntry("REJECT_MAX_ACTIVE", 64).containsEntry("REJECT_MAX_QUEUED", 1024);
    assertThatThrownBy(() -> diagnostics.admissionCategories().put("PRIVATE_CANARY", 1)).isInstanceOf(UnsupportedOperationException.class);
  }

  @Test void admissionObservationPreservesCapacityRejectionAndRestoresTheExactHandler() throws Exception {
    var pool = (BoundedDispatchExecutor) ApprovalAdmissionExecutors.newDispatchPool(1, 1);
    var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
    var diagnostics = new LatencyFailureDiagnostics();
    var original = pool.rejectionObserver();
    var observation = new LatencyAdmissionObservation(pool, diagnostics);
    try {
      pool.execute(() -> {
        entered.countDown();
        try { release.await(); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
      });
      assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
      pool.execute(() -> {});
      assertThatThrownBy(() -> pool.execute(() -> {})).isInstanceOf(RejectedExecutionException.class);
      assertThatThrownBy(() -> pool.execute(new FutureTask<>(() -> null))).isInstanceOf(RejectedExecutionException.class);
      assertThat(pool.activeLanes()).isEqualTo(1);
      assertThat(pool.occupancy().pending()).isEqualTo(1);
      assertThat(diagnostics.admissionCategories()).containsEntry("HTTP_EXCHANGE_REJECTED", 1)
          .containsEntry("QUIESCENCE_TASK_REJECTED", 1).containsEntry("REJECT_MAX_ACTIVE", 1).containsEntry("REJECT_MAX_QUEUED", 1);
    } finally {
      release.countDown();
      try { observation.close(); }
      finally { pool.shutdownNow(); assertThat(pool.awaitTermination(2, TimeUnit.SECONDS)).isTrue(); }
    }
      assertThat(pool.rejectionObserver()).isSameAs(original);
  }
}
