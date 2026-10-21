package io.tracepermit.adapters.http;

import com.fasterxml.jackson.databind.JsonNode;
import io.tracepermit.adapters.remote.SsrfSafeHttpsJwkSetSource;
import io.tracepermit.contracts.JsonSupport;
import io.tracepermit.identity.CachingOidcJwkResolver;
import io.tracepermit.identity.OidcAccessTokenVerifier;
import io.tracepermit.identity.OidcVerificationKeyResolver;
import io.tracepermit.identity.StaticOidcProviderRegistry;
import io.tracepermit.identity.BrowserIdTokenVerifier;
import io.tracepermit.persistence.postgres.PostgresDurableEnforcementState;
import io.tracepermit.persistence.postgres.PostgresDatabaseLifecycle;
import io.tracepermit.persistence.postgres.PostgresPermitConsumptionStore;
import io.tracepermit.persistence.postgres.PostgresRecoveryQuarantine;
import io.tracepermit.persistence.postgres.PostgresRecoveryInspection;
import io.tracepermit.security.ExecutionPermitAuthority;
import io.tracepermit.simulator.StatefulTargetSimulator;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;

/** Local simulator approval composition, optionally with browser login. No development issuer or real target. */
public final class SimulatorApprovalMain {
  private SimulatorApprovalMain() {}

  public static void main(String[] args) {
    int code = execute(args, System.getenv(), System.out, System.err, configuration -> {
      try (var server = start(configuration, Clock.systemUTC())) {
        Thread shutdown = new Thread(server::close, "approval-shutdown");
        Runtime.getRuntime().addShutdownHook(shutdown);
        System.out.println("TracePermit simulator approval API listening on http://127.0.0.1:" + server.port());
        new CountDownLatch(1).await();
      }
    });
    if (code != 0) System.exit(code);
  }

  @FunctionalInterface interface Runner {
    void run(SimulatorApprovalConfiguration configuration) throws Exception;
  }

  static int execute(String[] args, Map<String, String> environment, PrintStream out,
      PrintStream err, Runner runner) {
    try {
      if (args.length != 1 || !Set.of("--check", "--run", "--init-db", "--upgrade-db", "--quarantine-db", "--recovery-status", "--recovery-inspect").contains(args[0])) {
        throw new IllegalArgumentException();
      }
      String path = environment.get("TRACEPERMIT_APPROVAL_CONFIG");
      if (path == null || path.isBlank()) throw new IllegalArgumentException();
      var configuration = SimulatorApprovalConfiguration.load(Path.of(path));
      if ("--check".equals(args[0])) {
        out.println("APPROVAL_CONFIGURATION_VALID: offline only; no database, IdP or listener checked.");
      } else if ("--init-db".equals(args[0])) {
        PostgresDatabaseLifecycle.initializeEmpty(configuration.database());
        out.println("APPROVAL_DATABASE_INITIALIZED: fresh simulator schema only; no listener or target started.");
      } else if ("--upgrade-db".equals(args[0])) {
        PostgresDatabaseLifecycle.upgradeFromV9(configuration.database());
        out.println("APPROVAL_DATABASE_UPGRADED: V9 to V10 only; no listener or target started; not recovery authorization.");
      } else if ("--quarantine-db".equals(args[0])) {
        PostgresRecoveryQuarantine.quarantine(configuration.database(), java.time.Instant.now());
        out.println("APPROVAL_DATABASE_QUARANTINED: new durable claims/reviews disabled; previously admitted work may finish; no resume command.");
      } else if ("--recovery-status".equals(args[0])) {
        var status = PostgresRecoveryQuarantine.status(configuration.database());
        out.println("APPROVAL_RECOVERY_MODE: " + status.mode() + "; operator-declared state, not rollback detection.");
      } else if ("--recovery-inspect".equals(args[0])) {
        var snapshot = PostgresRecoveryInspection.inspect(configuration.database());
        // Serialize fully before printing: failed reads/serialization never produce partial success output.
        var report = JsonSupport.MAPPER.createObjectNode().put("schemaVersion", "2.0")
            .put("transactionStartedAt", snapshot.transactionStartedAt().toString())
            .put("recoveryMode", snapshot.recovery().mode())
            .put("reconciliationQueued", snapshot.reconciliationQueued());
        report.set("executions", JsonSupport.MAPPER.valueToTree(snapshot.executions()));
        report.set("approvals", JsonSupport.MAPPER.valueToTree(snapshot.approvals()));
        String json = JsonSupport.MAPPER.writeValueAsString(report);
        out.println("APPROVAL_RECOVERY_SNAPSHOT: " + json);
        out.println("INSPECTION_ONLY: stored statuses, not expiry refresh, quiescence, target outcome proof or permission to resume; no target contacted.");
      } else {
        runner.run(configuration);
      }
      return 0;
    } catch (Exception failure) {
      // No exception/stacktrace: JDBC, parser and IdP failures may contain sensitive configuration.
      err.println("APPROVAL_STARTUP_FAILED: check private configuration and local dependencies; details redacted.");
      return 2;
    }
  }

  static SimulatorApprovalServer start(SimulatorApprovalConfiguration configuration, Clock clock)
      throws Exception {
    var source = new SsrfSafeHttpsJwkSetSource(Set.of(configuration.provider().jwksUri()),
        Duration.ofSeconds(3), Duration.ofSeconds(5));
    return start(configuration, clock, new CachingOidcJwkResolver(source));
  }

  // Package-private resolver injection for synthetic-signature integration tests, not operator configuration.
  static SimulatorApprovalServer start(SimulatorApprovalConfiguration configuration, Clock clock,
      OidcVerificationKeyResolver resolver) throws Exception {
    var verifier = new OidcAccessTokenVerifier(
        new StaticOidcProviderRegistry(List.of(configuration.provider())), resolver, clock);
    // Configuration/key/enrollment validation and strict schema checks precede listener creation.
    var state = PostgresDurableEnforcementState.openExisting(configuration.database(), clock,
        () -> UUID.randomUUID().toString());
    if (!"ready".equals(PostgresRecoveryQuarantine.status(configuration.database()).mode())) {
      throw new IllegalStateException("DATABASE_RECOVERY_QUARANTINED");
    }
    var permits = new ExecutionPermitAuthority(ExecutionPermitAuthority.DEFAULT_ISSUER,
        configuration.keyId(), configuration.permitKey(), 30, clock,
        () -> "permit-" + UUID.randomUUID(), PostgresPermitConsumptionStore.openExisting(configuration.database()));
    ApprovalBrowserAdapter browser = null;
    var web = configuration.browser();
    if (web != null) {
      var transport = new SsrfSafeHttpsJwkSetSource(Set.of(web.tokenUri),
          Duration.ofSeconds(3), Duration.ofSeconds(5));
      String clientId = configuration.provider().clientNames().keySet().iterator().next();
      browser = new ApprovalBrowserAdapter(web.authorizationUri, configuration.provider().issuer().toString(),
          configuration.provider().resourceAudience(), clientId, web.consoleDirectory,
          new BrowserCodeExchange(web.tokenUri, configuration.port(), clientId,
              web.tokenEndpointAuthMethod, web.clientSecret, transport::postForm), verifier,
          new BrowserIdTokenVerifier(configuration.provider(), resolver, clock, web.organization),
          configuration.memberships(), clock, web.organization);
    }
    return SimulatorApprovalServer.start(
        new InetSocketAddress(InetAddress.getByName("127.0.0.1"), configuration.port()),
        verifier, configuration.memberships(), state, policy(), permits, new StatefulTargetSimulator(),
        clock, browser, () -> "ready".equals(
            PostgresRecoveryQuarantine.status(configuration.database()).mode()));
  }

  static JsonNode policy() {
    try {
      return JsonSupport.MAPPER.readTree("""
          {"schemaVersion":"1.0","id":"pol-local-simulator-approval","version":2,
           "name":"Local simulator exact-action review","enabled":true,
           "description":"Require independent review for one fixed simulated mutation; deny other actions.",
           "defaultDecision":{"outcome":"block","reasonCode":"NO_EXPLICIT_ALLOW"},
           "rules":[{"id":"review-simulated-security-group","priority":100,
             "description":"Require exact-action human review before simulator dispatch.",
             "match":{"origins":["api","cli"],"targetSystems":["aws"],"toolNames":["aws.ec2.update_security_group"],
                      "actionClasses":["modify"]},
             "decision":{"outcome":"require_approval","reasonCode":"EXACT_SIMULATED_CHANGE_REVIEW"}}]}
          """);
    } catch (java.io.IOException impossible) {
      throw new IllegalStateException("APPROVAL_POLICY_UNAVAILABLE");
    }
  }
}
