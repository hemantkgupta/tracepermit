package io.tracepermit.agentcore.cli;

import com.fasterxml.jackson.databind.JsonNode;
import javax.sql.DataSource;

/** Narrow reuse of the installed CLI's exact loopback database parser and datasource. */
public final class InstalledOperationsRuntime {
  private InstalledOperationsRuntime() {}

  public static Plan parse(JsonNode operations) {
    try {
      return new Plan(OperatorConfig.Database.parse(operations));
    } catch (RuntimeException invalid) {
      throw new Failure();
    }
  }

  /** Opaque validated runtime plan; credentials are never exposed by accessors or diagnostics. */
  public static final class Plan {
    private final DataSource source;

    private Plan(OperatorConfig.Database database) {
      source = new LoopbackSource(database);
    }

    public DataSource source() {
      return source;
    }

    @Override
    public String toString() {
      return "InstalledOperationsPlan[redacted]";
    }
  }

  /** Fixed configuration failure with no retained private input or cause. */
  public static final class Failure extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private Failure() {
      super("CONFIG_INVALID", null, false, false);
    }

    @Override
    public synchronized Throwable initCause(Throwable cause) {
      return this;
    }
  }
}
