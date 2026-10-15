package io.tracepermit.adapters.http;

import java.nio.file.Files;
import org.testcontainers.containers.PostgreSQLContainer;

/** Interactive rendered-UI qualification ONLY. Not part of the normal application classpath. */
public final class SimulatorApprovalBrowserQualificationMain {
  private SimulatorApprovalBrowserQualificationMain() {}
  public static void main(String[] args) throws Exception {
    var directory = Files.createTempDirectory("tracepermit-browser-qualification-");
    try (var postgres = new PostgreSQLContainer<>("postgres:16.4-alpine")
        .withPassword("synthetic-browser-qualification-password")) {
      postgres.start();
      try (var fixture = new SimulatorApprovalBrowserFixture(directory, postgres)) {
        System.out.println("SYNTHETIC BROWSER QUALIFICATION ONLY - not a real IdP or target.");
        System.out.println("Approval inbox: " + fixture.origin() + "/approvals");
        System.out.println("Accounts: alice=requester, bob=reviewer, carol=viewer, dave=requester+reviewer.");
        System.out.println("Press Enter to stop and discard this qualification database and synthetic keys.");
        System.in.read();
      }
    } finally {
      // Only files created in this unique fixture directory, never operator paths.
      try (var paths = Files.walk(directory)) {
        for (var path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(path);
      }
    }
  }
}
