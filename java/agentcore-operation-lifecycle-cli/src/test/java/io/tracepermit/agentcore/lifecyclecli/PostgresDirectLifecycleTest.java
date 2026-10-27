package io.tracepermit.agentcore.lifecyclecli;

import org.junit.jupiter.api.Test;

/** In-process mirror of the installed PostgreSQL contract for coverage of the hardened worker. */
final class PostgresDirectLifecycleTest extends InstalledLifecycleContract {
  @Override
  String image() {
    return "postgres:16.4-alpine";
  }

  @Override
  boolean directWorker() {
    return true;
  }

  @Test
  void malformedOperatorConfigurationIsRejectedBeforeLifecycleEffects() throws Exception {
    verifyMalformedOperatorConfigurationIsRejectedBeforeLifecycleEffects();
  }

  @Test
  void closedProtocolFacadesAndInspectorAreRedactedAndFailClosed() throws Exception {
    verifyClosedProtocolFacadesAndInspector();
  }
}
