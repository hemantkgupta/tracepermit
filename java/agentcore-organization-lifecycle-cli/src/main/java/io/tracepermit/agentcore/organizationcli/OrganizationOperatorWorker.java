package io.tracepermit.agentcore.organizationcli;

import io.tracepermit.agentcore.cli.InstalledPrivateInput;
import java.io.InputStream;

/** One closed worker; no configurable identity, repository, signer or delivery callbacks. */
public final class OrganizationOperatorWorker {
  private OrganizationOperatorWorker() {}

  public static void main(String[] args) {
    java.util.logging.LogManager.getLogManager().reset();
    String command = args.length == 1 ? args[0] : "UNKNOWN";
    OrganizationOperatorReceipt receipt;
    try {
      receipt =
          InstalledPrivateInput.anonymousPipe()
              ? execute(System.in, command)
              : OrganizationOperatorReceipt.failure(command, "INPUT_INVALID");
    } catch (Throwable failure) {
      receipt = OrganizationOperatorReceipt.failure(command, "UNKNOWN");
    }
    System.out.writeBytes(receipt.bytes());
    System.out.flush();
    Runtime.getRuntime().halt(receipt.exitCode());
  }

  static OrganizationOperatorReceipt execute(InputStream source, String command) {
    if (!OrganizationOperatorProtocol.COMMANDS.contains(command)) {
      return OrganizationOperatorReceipt.failure(command, "USAGE");
    }
    try (var input = OrganizationOperatorProtocol.read(source, command)) {
      if (command.equals("check-config")) {
        return OrganizationOperatorReceipt.failure(command, "CONFIG_VALID");
      }
      return new OrganizationOperatorRuntime(input.configuration()).execute(command, input);
    } catch (OrganizationFailure failure) {
      return OrganizationOperatorReceipt.failure(command, failure.code);
    } catch (RuntimeException failure) {
      return OrganizationOperatorReceipt.failure(command, "UNKNOWN");
    }
  }
}
