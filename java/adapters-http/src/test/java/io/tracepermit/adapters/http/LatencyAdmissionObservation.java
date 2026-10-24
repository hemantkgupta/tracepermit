package io.tracepermit.adapters.http;

import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.RunnableFuture;
import java.util.concurrent.ThreadPoolExecutor;

/** Test-classpath-only observation; delegates the exact rejection without retries or capacity changes. */
final class LatencyAdmissionObservation implements AutoCloseable {
  private final ThreadPoolExecutor pool;
  private final RejectedExecutionHandler original;
  private final RejectedExecutionHandler observation;

  static LatencyAdmissionObservation attach(SimulatorApprovalServer server, LatencyFailureDiagnostics diagnostics) {
    try {
      var field = SimulatorApprovalServer.class.getDeclaredField("workers");
      field.setAccessible(true);
      return new LatencyAdmissionObservation((ThreadPoolExecutor) field.get(server), diagnostics);
    } catch (ReflectiveOperationException | ClassCastException failure) {
      throw new IllegalStateException("ADMISSION_DIAGNOSTIC_UNAVAILABLE");
    }
  }

  LatencyAdmissionObservation(ThreadPoolExecutor pool, LatencyFailureDiagnostics diagnostics) {
    this.pool = pool;
    original = pool.getRejectedExecutionHandler();
    observation = (task, executor) -> {
      diagnostics.admissionRejected(task instanceof RunnableFuture<?>, executor.getActiveCount(), executor.getQueue().size());
      original.rejectedExecution(task, executor);
    };
    pool.setRejectedExecutionHandler(observation);
  }

  @Override public void close() {
    if (pool.getRejectedExecutionHandler() != observation) throw new IllegalStateException("ADMISSION_DIAGNOSTIC_CHANGED");
    pool.setRejectedExecutionHandler(original);
  }
}
