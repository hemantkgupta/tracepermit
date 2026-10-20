package io.tracepermit.executor.simulator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.tracepermit.application.TargetExecutionException;
import io.tracepermit.contracts.JsonSupport;
import io.tracepermit.persistence.postgres.PostgresPermitConsumptionStore;
import io.tracepermit.persistence.postgres.PostgresStateException;
import io.tracepermit.security.ExecutionPermitAuthority;
import io.tracepermit.security.ExecutionPermitException;
import io.tracepermit.simulator.StatefulTargetSimulator;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.time.Clock;

/** One-shot stdio entry point. It exposes no network listener and retains no signing key. */
public final class VerifierOnlyExecutorMain {
  private VerifierOnlyExecutorMain() {}

  public static void main(String[] args) {
    System.exit(run(args, System.in, System.out));
  }

  static int run(String[] args, InputStream input, OutputStream output) {
    if (args.length != 2 || !("--check".equals(args[0]) || "--execute".equals(args[0]))) {
      write(output, failure("INVALID_EXECUTOR_COMMAND"));
      return 2;
    }

    VerifierOnlyExecutorConfiguration configuration;
    try {
      configuration = VerifierOnlyExecutorConfiguration.load(Path.of(args[1]));
    } catch (RuntimeException failure) {
      write(output, failure("INVALID_EXECUTOR_CONFIGURATION"));
      return 2;
    }

    if ("--check".equals(args[0])) {
      ObjectNode result = JsonSupport.MAPPER.createObjectNode();
      result.put("schemaVersion", "1.0");
      result.put("status", "ready");
      result.put("mode", "verifier_only_simulator");
      result.put("algorithm", "EdDSA");
      result.put("keyId", configuration.keyId());
      result.put("keyFingerprint", configuration.keyFingerprint());
      result.put("audience", configuration.audience());
      result.put("routeCount", configuration.routes().size());
      return write(output, result) ? 0 : 2;
    }

    try {
      ExecutorRequest request = ExecutorRequest.read(input);
      var store = PostgresPermitConsumptionStore.openExisting(configuration.database());
      var authority = new ExecutionPermitAuthority(
          configuration.issuer(),
          configuration.keyId(),
          configuration.publicKey(),
          60,
          Clock.systemUTC(),
          store);
      var executor = new VerifierOnlySimulatorExecutor(
          authority,
          configuration.audience(),
          configuration.routes(),
          new StatefulTargetSimulator());
      JsonNode receipt = executor.execute(request.permit(), request.action());
      // Losing the response after dispatch does not undo the effect or prove failure.
      return write(output, receipt) ? 0 : 5;
    } catch (PostgresStateException failure) {
      write(output, failure("EXECUTOR_DEPENDENCY_UNAVAILABLE"));
      return 3;
    } catch (ExecutionPermitException | IllegalArgumentException | TargetExecutionException failure) {
      write(output, failure("EXECUTION_REJECTED"));
      return 4;
    } catch (RuntimeException failure) {
      write(output, failure("EXECUTION_OUTCOME_UNKNOWN"));
      return 5;
    }
  }

  private static JsonNode failure(String code) {
    return JsonSupport.MAPPER.createObjectNode()
        .put("schemaVersion", "1.0")
        .put("status", "failed")
        .put("code", code);
  }

  private static boolean write(OutputStream output, JsonNode value) {
    try {
      JsonSupport.MAPPER.writeValue(output, value);
      output.write('\n');
      output.flush();
      return true;
    } catch (IOException ignored) {
      // The exit status carries uncertainty; a response failure never restores authority.
      return false;
    }
  }
}
