package io.tracepermit.endpoint;

public interface ProcessObserver {
  ProcessObservation observe();

  void verifyStable(ProcessObservation observation);
}
