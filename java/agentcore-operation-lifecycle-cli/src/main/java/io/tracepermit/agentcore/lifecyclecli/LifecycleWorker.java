package io.tracepermit.agentcore.lifecyclecli;

import io.tracepermit.agentcore.cli.InstalledPrivateInput;
import java.io.InputStream;

/** One closed worker; no configurable verifier, repository, signer or target callbacks. */
public final class LifecycleWorker {
  private LifecycleWorker() {}

  public static void main(String[] args) {
    System.setProperty("mariadb.logging.disable", "true");
    java.util.logging.LogManager.getLogManager().reset();
    String command = args.length == 1 ? args[0] : "UNKNOWN";
    LifecycleReceipt receipt;
    try {
      receipt =
          InstalledPrivateInput.anonymousPipe()
              ? execute(System.in, command)
              : LifecycleReceipt.failure(command, "INPUT_INVALID");
    } catch (Throwable failure) {
      receipt = LifecycleReceipt.failure(command, "UNKNOWN");
    }
    System.out.writeBytes(receipt.bytes());
    System.out.flush();
    Runtime.getRuntime().halt(receipt.exitCode());
  }

  static LifecycleReceipt execute(InputStream source, String command) {
    if (!LifecycleProtocol.COMMANDS.contains(command)) {
      return LifecycleReceipt.failure(command, "USAGE");
    }
    try (var input = LifecycleProtocol.read(source, command)) {
      if (command.equals("check-config")) {
        return LifecycleReceipt.failure(command, "CONFIG_VALID");
      }
      return new LifecycleRuntime(input.configuration()).execute(command, input);
    } catch (LifecycleFailure failure) {
      return LifecycleReceipt.failure(command, failure.code);
    } catch (RuntimeException failure) {
      return LifecycleReceipt.failure(command, "UNKNOWN");
    }
  }
}
