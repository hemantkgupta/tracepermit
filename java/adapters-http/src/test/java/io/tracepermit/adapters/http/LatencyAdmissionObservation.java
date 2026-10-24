package io.tracepermit.adapters.http;

/** Test-classpath-only observation; the executor always refuses independently of this observer. */
final class LatencyAdmissionObservation implements AutoCloseable {
  private final BoundedDispatchExecutor pool;
  private final BoundedDispatchExecutor.RejectionObserver original;
  private final BoundedDispatchExecutor.RejectionObserver observation;

  static LatencyAdmissionObservation attach(SimulatorApprovalServer server, LatencyFailureDiagnostics diagnostics) {
    try {
      var field = SimulatorApprovalServer.class.getDeclaredField("workers");
      field.setAccessible(true);
      return new LatencyAdmissionObservation((BoundedDispatchExecutor) field.get(server), diagnostics);
    } catch (ReflectiveOperationException | ClassCastException failure) {
      throw new IllegalStateException("ADMISSION_DIAGNOSTIC_UNAVAILABLE");
    }
  }

  LatencyAdmissionObservation(BoundedDispatchExecutor pool, LatencyFailureDiagnostics diagnostics) {
    this.pool = pool;
    original = pool.rejectionObserver();
    observation = (qualificationTask, occupancy) -> {
      diagnostics.admissionRejected(qualificationTask, occupancy.reserved(), occupancy.pending());
      original.rejected(qualificationTask, occupancy);
    };
    pool.replaceRejectionObserver(original, observation);
  }

  @Override public void close() {
    pool.replaceRejectionObserver(observation, original);
  }
}
