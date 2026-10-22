package io.tracepermit.adapters.http;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import org.testcontainers.containers.PostgreSQLContainer;

/** Interactive rendered-UI qualification ONLY. Not part of the normal application classpath. */
public final class SimulatorApprovalBrowserQualificationMain {
  private SimulatorApprovalBrowserQualificationMain() {}
  public static void main(String[] args) throws Exception {
    if (args.length > 1 || (args.length == 1 && !"--codex".equals(args[0]))) {
      throw new IllegalArgumentException("Only --codex qualification is supported.");
    }
    boolean codex = args.length == 1;
    String configuredParent = System.getenv("TRACEPERMIT_BROWSER_QUALIFICATION_TEMP");
    Path directory = configuredParent == null
        ? Files.createTempDirectory("tracepermit-browser-qualification-")
        : Files.createTempDirectory(Path.of(configuredParent).toRealPath(),
            "tracepermit-browser-qualification-");
    try (var postgres = new PostgreSQLContainer<>("postgres:16.4-alpine")
        .withPassword("synthetic-browser-qualification-password")) {
      postgres.start();
      try (var fixture = new SimulatorApprovalBrowserFixture(directory, postgres)) {
        if ("1".equals(System.getenv("TRACEPERMIT_BROWSER_EXPORT_QUALIFICATION"))) {
          fixture.clock.now = Instant.parse("2030-01-01T00:00:00Z");
        }
        System.out.println("SYNTHETIC BROWSER QUALIFICATION ONLY - not a real IdP or target.");
        System.out.println("Approval inbox: " + fixture.origin() + "/approvals");
        System.out.println("Accounts: alice=requester, bob=reviewer, carol=viewer, dave=requester+reviewer.");
        if (codex) {
          var tokenFile = directory.resolve("codex-synthetic.token");
          Files.createFile(tokenFile, java.nio.file.attribute.PosixFilePermissions.asFileAttribute(
              java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")));
          Files.writeString(tokenFile, fixture.accessToken("alice"));
          System.out.println("Codex MCP URL: " + fixture.origin() + "/mcp");
          System.out.println("Synthetic requester token file (290s lifetime, never commit/share): " + tokenFile);
          System.out.println("No Codex configuration has been changed. Follow docs/runbooks/codex-durable-approvals.md.");
          System.out.println("Enter 'token' to refresh only the private synthetic token file, or Enter to stop.");
          var input = new java.io.BufferedReader(new java.io.InputStreamReader(System.in, java.nio.charset.StandardCharsets.UTF_8));
          String line;
          while ((line = input.readLine()) != null && !line.isEmpty()) {
            if ("token".equals(line)) {
              Files.writeString(tokenFile, fixture.accessToken("alice"));
              System.out.println("Synthetic token refreshed. Existing approvals still expire 300s after creation.");
            } else System.out.println("Use 'token' or Enter. No other command is accepted.");
          }
        } else {
          System.out.println("Press Enter to stop and discard this qualification database and synthetic keys.");
          System.in.read();
        }
      }
    } finally {
      // Only files created in this unique fixture directory, never operator paths.
      try (var paths = Files.walk(directory)) {
        for (var path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(path);
      }
    }
  }
}
