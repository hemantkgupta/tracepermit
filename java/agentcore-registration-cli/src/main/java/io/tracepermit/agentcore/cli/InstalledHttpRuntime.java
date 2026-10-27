package io.tracepermit.agentcore.cli;

import io.tracepermit.agentcore.http.AgentCoreRegistrationHttpServer;
import io.tracepermit.agentcore.registration.DurableAgentCoreRegistration;
import io.tracepermit.agentcore.registration.DurableAgentCoreRegistrationHttpServer;
import java.io.DataInputStream;
import java.io.InputStream;
import java.time.Clock;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/** Closed reuse facade for the installed bounded HTTP pilot. No configurable authority ports. */
public final class InstalledHttpRuntime {
  public static final String PROFILE = "tracepermit-local-synthetic-registration-http-session-v1";
  public static final int SESSION_BYTES = 1_024;

  private InstalledHttpRuntime() {}

  /** Both the launcher and worker must verify the real descriptor, never a caller assertion. */
  public static boolean anonymousInput() {
    return RegistrationCli.anonymousInput();
  }

  /** Reads exactly the existing operator frame, the closed session frame, and EOF before effects. */
  public static Plan readHttpPlan(InputStream source) {
    try {
      var input = new DataInputStream(source);
      byte[] operatorBytes = Protocol.frame(input, Protocol.CONFIG_BYTES);
      byte[] sessionBytes = Protocol.frame(input, SESSION_BYTES);
      if (input.read() != -1) throw new CliFailure("INPUT_INVALID");

      // Parsing deliberately starts only after EOF. It remains offline and creates no verifier,
      // datasource, JDBC driver, DNS lookup, network connection, or listener.
      OperatorConfig operator = OperatorConfig.parse(Protocol.object(operatorBytes));
      var session = Protocol.object(sessionBytes);
      Protocol.fields(
          session,
          Set.of("schemaVersion", "profile", "requestedPort", "sessionMillis"),
          Set.of());
      if (!"1.0".equals(Protocol.text(session, "schemaVersion", 3))
          || !PROFILE.equals(Protocol.text(session, "profile", 96))) {
        throw new CliFailure("CONFIG_INVALID");
      }
      long requestedPort = Protocol.number(session, "requestedPort", 0, 65_535);
      if (requestedPort != 0 && requestedPort < 1_024) {
        throw new CliFailure("CONFIG_INVALID");
      }
      Duration duration =
          Duration.ofMillis(Protocol.number(session, "sessionMillis", 1_000, 30_000));
      return new Plan(operator, (int) requestedPort, duration);
    } catch (CliFailure failure) {
      throw new Failure(failure.code);
    } catch (Exception failure) {
      throw new Failure("INPUT_INVALID");
    }
  }

  /** Opaque validated plan. It can open the one approved concrete runtime exactly once. */
  public static final class Plan {
    private final OperatorConfig operator;
    private final int requestedPort;
    private final Duration duration;
    private final AtomicBoolean started = new AtomicBoolean();

    private Plan(OperatorConfig operator, int requestedPort, Duration duration) {
      this.operator = operator;
      this.requestedPort = requestedPort;
      this.duration = duration;
    }

    public Duration sessionDuration() {
      return duration;
    }

    public Session start() {
      if (!started.compareAndSet(false, true)) throw new Failure("START_FAILED");
      try {
        Clock clock = Clock.systemUTC();
        DurableAgentCoreRegistrationHttpServer server =
            DurableAgentCoreRegistrationHttpServer.create(
                operator.verifier(clock),
                operator.enrollment,
                clock,
                new LoopbackSource(operator.replay),
                new LoopbackSource(operator.operations),
                operator.capacity,
                operator.dmlBudget,
                requestedPort,
                AgentCoreRegistrationHttpServer.Limits.defaults());
        return new Session(server);
      } catch (DurableAgentCoreRegistration.InitializationException notReady) {
        throw new Failure("NOT_READY");
      } catch (Failure failure) {
        throw failure;
      } catch (RuntimeException startFailure) {
        throw new Failure("START_FAILED");
      }
    }

    @Override
    public String toString() {
      return "InstalledHttpPlan[redacted]";
    }
  }

  /** Only the actual bound port and bounded close cross the facade. */
  public static final class Session implements AutoCloseable {
    private final DurableAgentCoreRegistrationHttpServer server;

    private Session(DurableAgentCoreRegistrationHttpServer server) {
      this.server = server;
    }

    public int port() {
      return server.port();
    }

    @Override
    public void close() {
      server.close();
    }

    @Override
    public String toString() {
      return "InstalledHttpSession[redacted]";
    }
  }

  /** Fixed public failure only; no cause, diagnostics, configuration, or credential text. */
  public static final class Failure extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private static final Set<String> CODES =
        Set.of("INPUT_INVALID", "CONFIG_INVALID", "NOT_READY", "START_FAILED", "UNKNOWN");
    private final String code;

    private Failure(String code) {
      super(CODES.contains(code) ? code : "UNKNOWN", null, false, false);
      this.code = CODES.contains(code) ? code : "UNKNOWN";
    }

    public String code() {
      return code;
    }

    @Override
    public synchronized Throwable initCause(Throwable cause) {
      return this;
    }
  }
}
