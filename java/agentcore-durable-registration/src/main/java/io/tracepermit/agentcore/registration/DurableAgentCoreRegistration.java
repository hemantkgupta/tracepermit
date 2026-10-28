package io.tracepermit.agentcore.registration;

import io.tracepermit.adapters.remote.agentcore.AgentCoreRegistrationIngress;
import io.tracepermit.identity.OidcAccessTokenVerifier;
import io.tracepermit.persistence.identity.JdbcWorkloadIdentityReplayStore;
import io.tracepermit.persistence.operations.JdbcBusinessOperationRepository;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import javax.sql.DataSource;

/** Execution-disabled composition. Replay and registration commit separately; neither grants authority. */
public final class DurableAgentCoreRegistration {
  private final AgentCoreRegistrationIngress ingress;

  private DurableAgentCoreRegistration(AgentCoreRegistrationIngress ingress) {
    this.ingress = ingress;
  }

  /** Validate existing dedicated stores only. Supplied datasources/pools remain caller-owned. */
  public static DurableAgentCoreRegistration create(
      OidcAccessTokenVerifier verifier, AgentCoreRegistrationIngress.Enrollment enrollment,
      Clock clock, DataSource replaySource, DataSource operationSource,
      long replayCapacity, Duration replayDmlBudget) {
    try {
      Objects.requireNonNull(verifier);
      Objects.requireNonNull(enrollment);
      Objects.requireNonNull(clock);
      var replay = new JdbcWorkloadIdentityReplayStore(
          Objects.requireNonNull(replaySource), replayCapacity, replayDmlBudget);
      var operations = new JdbcBusinessOperationRepository(Objects.requireNonNull(operationSource));
      return new DurableAgentCoreRegistration(
          new AgentCoreRegistrationIngress(verifier, replay, operations, clock, enrollment));
    } catch (RuntimeException invalid) {
      throw new InitializationException();
    }
  }

  /**
   * Validate existing dedicated organization stores only. Supplied datasources remain caller-owned.
   */
  public static DurableAgentCoreRegistration organization(
      OidcAccessTokenVerifier verifier,
      AgentCoreRegistrationIngress.OrganizationEnrollment enrollment,
      Clock clock,
      DataSource replaySource,
      DataSource operationSource,
      long replayCapacity,
      Duration replayDmlBudget) {
    try {
      Objects.requireNonNull(verifier);
      Objects.requireNonNull(enrollment);
      Objects.requireNonNull(clock);
      var replay =
          new JdbcWorkloadIdentityReplayStore(
              Objects.requireNonNull(replaySource), replayCapacity, replayDmlBudget);
      var operations =
          new JdbcBusinessOperationRepository(Objects.requireNonNull(operationSource));
      return new DurableAgentCoreRegistration(
          AgentCoreRegistrationIngress.organization(
              verifier, replay, operations, clock, enrollment));
    } catch (RuntimeException invalid) {
      throw new InitializationException();
    }
  }

  public AgentCoreRegistrationIngress.Receipt register(
      String authorization, String humanAuthorization, byte[] body) {
    return ingress.register(authorization, humanAuthorization, body);
  }

  AgentCoreRegistrationIngress ingress() {
    return ingress;
  }

  public static final class InitializationException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private InitializationException() {
      super("DURABLE_REGISTRATION_NOT_READY", null, false, false);
    }

    @Override public synchronized Throwable initCause(Throwable cause) { return this; }
  }
}
