package io.tracepermit.endpoint;

import com.fasterxml.jackson.databind.JsonNode;

/** Local diagnostics invoked after stability verification and before gateway forwarding. */
@FunctionalInterface
public interface EndpointObservationCapture {
  EndpointObservationCapture NONE = (observation, request) -> {};

  void record(ProcessObservation observation, JsonNode request);
}
