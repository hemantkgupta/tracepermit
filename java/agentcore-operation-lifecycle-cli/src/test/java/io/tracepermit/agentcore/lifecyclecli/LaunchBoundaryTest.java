package io.tracepermit.agentcore.lifecyclecli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.security.Permission;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

final class LaunchBoundaryTest {
  private static final class Exit extends SecurityException {
    private static final long serialVersionUID = 1L;
    final int status;

    Exit(int status) {
      super("FIXED_EXIT");
      this.status = status;
    }
  }

  @SuppressWarnings("removal")
  @Test
  void installedEntrypointsEmitOneFixedReceiptBeforeHalting() {
    assertLaunch(args -> LifecycleCli.main(args), new String[0], "USAGE");
    assertLaunch(args -> LifecycleCli.main(args), new String[] {"inspect"}, "INPUT_INVALID");
    assertLaunch(args -> LifecycleWorker.main(args), new String[] {"inspect"}, "INPUT_INVALID");
  }

  @SuppressWarnings("removal")
  private static void assertLaunch(
      Consumer<String[]> launch, String[] arguments, String expectedOutcome) {
    SecurityManager original = System.getSecurityManager();
    PrintStream saved = System.out;
    var output = new ByteArrayOutputStream();
    System.setOut(new PrintStream(output, true, java.nio.charset.StandardCharsets.UTF_8));
    System.setSecurityManager(
        new SecurityManager() {
          @Override
          public void checkPermission(Permission permission) {}

          @Override
          public void checkExit(int status) {
            throw new Exit(status);
          }
        });
    try {
      assertThatThrownBy(() -> launch.accept(arguments))
          .isInstanceOfSatisfying(
              Exit.class,
              failure -> {
                LifecycleReceipt receipt =
                    LifecycleReceipt.decode(output.toByteArray(), failure.status);
                assertThat(receipt.outcome()).isEqualTo(expectedOutcome);
                assertThat(receipt.bytes()).isEqualTo(output.toByteArray());
              });
    } finally {
      System.setSecurityManager(original);
      System.setOut(saved);
    }
  }
}
