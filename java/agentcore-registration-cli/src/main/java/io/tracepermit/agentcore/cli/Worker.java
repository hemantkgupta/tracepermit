package io.tracepermit.agentcore.cli;

import io.tracepermit.adapters.remote.agentcore.AgentCoreRegistrationIngress;
import io.tracepermit.agentcore.registration.DurableAgentCoreRegistration;
import java.io.InputStream;
import java.time.Clock;

/** Internal worker entry point. No extension points or alternative registration implementations. */
public final class Worker {
  private Worker() {}

  public static void main(String[] args) {
    // Disable MariaDB's console fallback and JUL before any driver is loaded.
    // Private runtime diagnostics never enter a receipt or log.
    System.setProperty("mariadb.logging.disable", "true");
    java.util.logging.LogManager.getLogManager().reset();
    Receipt receipt;
    try { receipt = RegistrationCli.anonymousInput()
        ? execute(System.in, args.length == 1 ? args[0] : "") : Receipt.failure("INPUT_INVALID"); }
    catch (Throwable failure) { receipt = Receipt.failure("UNKNOWN"); }
    // The parent supervises both this potentially blocked write and worker exit.
    System.out.writeBytes(receipt.bytes()); System.out.flush();
    Runtime.getRuntime().halt(receipt.exitCode());
  }

  static Receipt execute(InputStream source, String command) {
    if (!command.equals("check-config") && !command.equals("register-once")) return Receipt.failure("USAGE");
    try {
      var input = Protocol.read(source, command);
      if (command.equals("check-config")) return Receipt.failure("CONFIG_VALID");
      var config = input.config();
      var clock = Clock.systemUTC();
      var registration = DurableAgentCoreRegistration.create(config.verifier(clock), config.enrollment,
          clock, new LoopbackSource(config.replay), new LoopbackSource(config.operations),
          config.capacity, config.dmlBudget);
      var result = registration.register(input.authorization(), null, input.body());
      return new Receipt(result.created() ? "CREATED" : "EXISTING", result.created(), result.operationId());
    } catch (CliFailure failure) { return Receipt.failure(failure.code); }
    catch (DurableAgentCoreRegistration.InitializationException notReady) { return Receipt.failure("NOT_READY"); }
    catch (AgentCoreRegistrationIngress.IngressException rejected) {
      // Replay and storage failures can conceal an unacknowledged commit. Never assert rollback.
      return Receipt.failure(java.util.Set.of("AGENTCORE_IDENTITY_REJECTED",
          "AGENTCORE_DELEGATION_CONTRACT_REQUIRED", "AGENTCORE_REQUEST_KEY_CONFLICT")
          .contains(rejected.code()) ? "REJECTED" : "UNKNOWN");
    }
    catch (RuntimeException unknown) { return Receipt.failure("UNKNOWN"); }
  }
}
