package io.tracepermit.adapters.http.requester;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Fixed child launch: the bearer exists only in one child environment variable. */
final class CodexLauncher {
  @FunctionalInterface interface ChildStarter {
    Process start(List<String> command, Map<String, String> environment) throws IOException;
  }

  private final InstalledRequesterConfiguration configuration;
  private final ChildStarter starter;

  CodexLauncher(InstalledRequesterConfiguration configuration) {
    this(configuration, (command, environment) -> {
      ProcessBuilder builder = new ProcessBuilder(command).inheritIO();
      builder.environment().clear();
      builder.environment().putAll(environment);
      return builder.start();
    });
  }

  CodexLauncher(InstalledRequesterConfiguration configuration, ChildStarter starter) {
    this.configuration = configuration;
    this.starter = starter;
  }

  int launch(String bearer) {
    String server = "mcp_servers.tracepermit={url=\"" + configuration.mcpUri()
        + "\",bearer_token_env_var=\"" + InstalledRequesterConfiguration.BEARER_ENVIRONMENT
        + "\",required=true,default_tools_approval_mode=\"prompt\"}";
    List<String> command = List.of(configuration.codexExecutable().toString(), "--no-daemon",
        "--sandbox", "read-only", "-c", server);
    Map<String, String> environment = childEnvironment();
    environment.put(InstalledRequesterConfiguration.BEARER_ENVIRONMENT, bearer);
    try {
      Process process = starter.start(command, Map.copyOf(environment));
      return process.waitFor();
    } catch (IOException failure) {
      throw new InstalledRequesterFailure("CODEX_LAUNCH_FAILED");
    } catch (InterruptedException failure) {
      Thread.currentThread().interrupt();
      throw new InstalledRequesterFailure("CODEX_LAUNCH_FAILED");
    } finally {
      environment.remove(InstalledRequesterConfiguration.BEARER_ENVIRONMENT);
    }
  }

  private static Map<String, String> childEnvironment() {
    Map<String, String> environment = new LinkedHashMap<>();
    // Preserve ordinary interactive/Codex account context, but never requester config or JVM
    // option injection. The token is added only after this copy.
    for (var entry : System.getenv().entrySet()) {
      if (!Set.of("TRACEPERMIT_REQUESTER_CONFIG", "TRACEPERMIT_REQUESTER_BEARER",
          "JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS").contains(entry.getKey())) {
        environment.put(entry.getKey(), entry.getValue());
      }
    }
    return environment;
  }
}
