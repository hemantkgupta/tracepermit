package io.tracepermit.adapters.http.requester;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class CodexLauncherTest {
  @TempDir Path directory;

  @Test void bearerAppearsOnlyInChildEnvironmentAndExitStatusIsInherited() throws Exception {
    var fixture = InstalledRequesterTestSupport.fixture(directory);
    var command = new AtomicReference<List<String>>();
    var environment = new AtomicReference<Map<String, String>>();
    var launcher = new CodexLauncher(fixture.load(), (capturedCommand, capturedEnvironment) -> {
      command.set(capturedCommand);
      environment.set(capturedEnvironment);
      return new ProcessBuilder("/usr/bin/false").start();
    });
    String sentinel = "sentinelA.sentinelB.sentinelC";
    assertThat(launcher.launch(sentinel)).isEqualTo(1);
    assertThat(command.get()).containsExactly(fixture.executable().toString(), "--no-daemon",
        "--sandbox", "read-only", "-c",
        "mcp_servers.tracepermit={url=\"http://127.0.0.1:8085/mcp\","
            + "bearer_token_env_var=\"TRACEPERMIT_REQUESTER_BEARER\",required=true,"
            + "default_tools_approval_mode=\"prompt\"}");
    assertThat(String.join(" ", command.get())).doesNotContain(sentinel);
    assertThat(environment.get()).containsEntry("TRACEPERMIT_REQUESTER_BEARER", sentinel);
    assertThat(environment.get()).doesNotContainKeys(
        "TRACEPERMIT_REQUESTER_CONFIG", "JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS");
  }

  @Test void launchFailureIsStableAndNeverPlacesBearerInException() throws Exception {
    var fixture = InstalledRequesterTestSupport.fixture(directory);
    String sentinel = "sentinelA.sentinelB.sentinelC";
    assertThatThrownBy(() -> new CodexLauncher(fixture.load(), (command, environment) -> {
      throw new IOException("must not leak " + sentinel);
    }).launch(sentinel)).isInstanceOfSatisfying(InstalledRequesterFailure.class, failure -> {
      assertThat(failure.code()).isEqualTo("CODEX_LAUNCH_FAILED");
      assertThat(failure).hasMessageNotContaining(sentinel);
    });
  }
}
