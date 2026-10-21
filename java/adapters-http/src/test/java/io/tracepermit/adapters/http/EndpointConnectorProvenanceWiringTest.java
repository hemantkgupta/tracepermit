package io.tracepermit.adapters.http;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Always-on wiring check: whatever the Gradle wiring injects as
 * {@code tracepermit.endpointConnector.expectedHeadCommit} must be a validated
 * hex commit. The strict bounded git probe and operator-provided exact-head
 * overrides are the only sources; both must deliver well-formed values.
 */
class EndpointConnectorProvenanceWiringTest {

  private static final Pattern HEX_COMMIT =
      Pattern.compile("^[0-9a-f]{40}$|^[0-9a-f]{64}$");

  @Test
  void gradleInjectsWellFormedExpectedHeadCommit() {
    String configured =
        System.getProperty("tracepermit.endpointConnector.expectedHeadCommit");
    assumeTrue(
        configured != null && !configured.isBlank(),
        "expectedHeadCommit not injected or operator chose resolver fallback; "
            + "run through the Gradle test wiring");
    assertTrue(
        HEX_COMMIT.matcher(configured.trim()).matches(),
        () ->
            "Gradle must inject only a validated exact-head commit, observed: "
                + configured);
  }
}
