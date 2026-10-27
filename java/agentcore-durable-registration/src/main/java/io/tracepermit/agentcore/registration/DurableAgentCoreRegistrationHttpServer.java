package io.tracepermit.agentcore.registration;

import io.tracepermit.adapters.remote.agentcore.AgentCoreRegistrationIngress;
import io.tracepermit.agentcore.http.AgentCoreRegistrationHttpServer;
import io.tracepermit.identity.OidcAccessTokenVerifier;
import java.time.Clock;
import java.time.Duration;
import javax.sql.DataSource;

/**
 * Closed trusted-operator composition of numeric-loopback transport and durable registration.
 *
 * <p>The concrete durable factory continues to own verifier, replay, repository, and ingress
 * construction. The concrete HTTP server continues to own transport parsing, admission, deadlines,
 * cancellation, and response mapping. This wrapper adds no launcher, default wiring, fallback store,
 * authority callback, approval, admission, permit, credential, target, or execution port.
 */
public final class DurableAgentCoreRegistrationHttpServer implements AutoCloseable {
  private final AgentCoreRegistrationHttpServer server;

  private DurableAgentCoreRegistrationHttpServer(AgentCoreRegistrationHttpServer server) {
    this.server = server;
  }

  /**
   * Validates the two existing dedicated stores, constructs the closed durable ingress, and binds the
   * existing numeric-loopback HTTP server. Supplied datasources and pools remain caller-owned.
   */
  public static DurableAgentCoreRegistrationHttpServer create(
      OidcAccessTokenVerifier verifier,
      AgentCoreRegistrationIngress.Enrollment enrollment,
      Clock clock,
      DataSource replaySource,
      DataSource operationSource,
      long replayCapacity,
      Duration replayDmlBudget,
      int requestedPort,
      AgentCoreRegistrationHttpServer.Limits limits) {
    DurableAgentCoreRegistration registration =
        DurableAgentCoreRegistration.create(
            verifier,
            enrollment,
            clock,
            replaySource,
            operationSource,
            replayCapacity,
            replayDmlBudget);
    return new DurableAgentCoreRegistrationHttpServer(
        new AgentCoreRegistrationHttpServer(registration.ingress(), requestedPort, limits));
  }

  /** Returns the actual numeric-loopback listener port; a requested port of zero is resolved here. */
  public int port() {
    return server.port();
  }

  /** Bounded transport shutdown. Caller-owned datasources are never closed. */
  @Override
  public void close() {
    server.close();
  }
}
