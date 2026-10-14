package io.tracepermit.adapters.http;

import io.tracepermit.adapters.remote.EnforcingMcpDispatcher;
import io.tracepermit.adapters.remote.SsrfSafeHttpsJwkSetSource;
import io.tracepermit.application.BoundedLogReadSanitizer;
import io.tracepermit.application.InMemoryActionSequenceStore;
import io.tracepermit.application.SecureExecutionService;
import io.tracepermit.application.SecurityReadiness;
import io.tracepermit.contracts.JsonSupport;
import io.tracepermit.execution.InMemoryEvidenceLedger;
import io.tracepermit.execution.InMemoryExecutionLedger;
import io.tracepermit.identity.CachingOidcJwkResolver;
import io.tracepermit.identity.ConnectorOrigin;
import io.tracepermit.identity.OAuthProtectedResourceMetadata;
import io.tracepermit.identity.OidcAccessTokenVerifier;
import io.tracepermit.identity.StaticOidcProviderRegistry;
import io.tracepermit.policy.PolicyEvaluator;
import io.tracepermit.security.ExecutionPermitAuthority;
import io.tracepermit.security.InMemoryPermitConsumptionStore;
import io.tracepermit.simulator.StatefulTargetSimulator;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;

/** Starts the simulator-only OAuth resource server used behind a hosted-client tunnel. */
public final class HostedClientOAuthBackendMain {
  private static final SecureRandom RANDOM = new SecureRandom();

  private HostedClientOAuthBackendMain() {}

  public static void main(String[] args) throws Exception {
    RunningBackend backend = start(System.getenv(), Clock.systemUTC());
    Runtime.getRuntime().addShutdownHook(new Thread(backend::close));
    System.out.println(
        "TracePermit hosted-client OAuth MCP listening on http://127.0.0.1:"
            + backend.mcpPort());
    System.out.println(
        "TracePermit OAuth demo control plane listening on http://127.0.0.1:"
            + backend.controlPlanePort());
    System.out.println(
        "TracePermit OAuth profile: external IdP verification enabled; local token issuer absent; simulator only.");
    new CountDownLatch(1).await();
  }

  static RunningBackend start(Map<String, String> environment, Clock clock) throws Exception {
    HostedClientOAuthConfiguration configuration =
        HostedClientOAuthConfiguration.from(environment);
    SecurityReadiness readiness = SecurityReadiness.allReady(clock.instant());
    StatefulTargetSimulator simulator = new StatefulTargetSimulator();
    SecureExecutionService enforcement =
        enforcement(configuration, simulator, readiness, clock);
    DemoControlPlaneData data = DemoControlPlaneData.load(configuration.repositoryRoot(), clock);
    EnforcingMcpDispatcher dispatcher =
        new EnforcingMcpDispatcher(
            enforcement,
            clock,
            () -> UUID.randomUUID().toString(),
            HostedClientOAuthBackendMain::traceId,
            data::recordLive,
            true);

    var provider = configuration.provider();
    var jwks =
        new SsrfSafeHttpsJwkSetSource(
            Set.of(provider.jwksUri()), Duration.ofSeconds(3), Duration.ofSeconds(5));
    var verifier =
        new OidcAccessTokenVerifier(
            new StaticOidcProviderRegistry(java.util.List.of(provider)),
            new CachingOidcJwkResolver(jwks),
            clock);
    Set<String> allowedOrigins =
        provider.origin() == ConnectorOrigin.CHATGPT_WEB
            ? Set.of("https://chatgpt.com")
            : Set.of("https://claude.ai");

    InetAddress loopback = InetAddress.getByName("127.0.0.1");
    LoopbackOAuthMcpServer mcp = null;
    try {
      mcp =
          LoopbackOAuthMcpServer.start(
              new InetSocketAddress(loopback, configuration.mcpPort()),
              configuration.resourceMetadataUri(),
              OAuthProtectedResourceMetadata.from(provider),
              configuration.requiredToolScopes(),
              allowedOrigins,
              verifier,
              dispatcher,
              () -> readiness);
      ControlPlaneServer controlPlane =
          ControlPlaneServer.start(
              new InetSocketAddress(loopback, configuration.controlPlanePort()),
              data.actionFeed(),
              data.views(),
              simulator,
              clock,
              DevelopmentRuntimeInfo.hostedClientOAuth(environment));
      return new RunningBackend(mcp, controlPlane);
    } catch (RuntimeException exception) {
      if (mcp != null) mcp.close();
      throw exception;
    }
  }

  private static SecureExecutionService enforcement(
      HostedClientOAuthConfiguration configuration,
      StatefulTargetSimulator simulator,
      SecurityReadiness readiness,
      Clock clock) {
    return new SecureExecutionService(
        new PolicyEvaluator(),
        JsonSupport.read(configuration.repositoryRoot().resolve("policies/aws-agentic-demo.json")),
        new ExecutionPermitAuthority(
            ExecutionPermitAuthority.DEFAULT_ISSUER,
            "ephemeral-hosted-client-oauth-key",
            randomBytes(32),
            30,
            clock,
            () -> "permit-" + UUID.randomUUID(),
            new InMemoryPermitConsumptionStore()),
        new InMemoryExecutionLedger(() -> "attempt-" + UUID.randomUUID()),
        new InMemoryEvidenceLedger(
            "urn:tracepermit:hosted-client-oauth",
            clock,
            () -> "evidence-" + UUID.randomUUID()),
        simulator,
        () -> readiness,
        clock,
        "urn:tracepermit:hosted-client-oauth-simulator",
        new BoundedLogReadSanitizer(),
        InMemoryActionSequenceStore.oneMinute());
  }

  private static byte[] randomBytes(int size) {
    byte[] value = new byte[size];
    RANDOM.nextBytes(value);
    return value;
  }

  private static String traceId() {
    return HexFormat.of().formatHex(randomBytes(16));
  }

  static final class RunningBackend implements AutoCloseable {
    private final LoopbackOAuthMcpServer mcp;
    private final ControlPlaneServer controlPlane;

    RunningBackend(LoopbackOAuthMcpServer mcp, ControlPlaneServer controlPlane) {
      this.mcp = mcp;
      this.controlPlane = controlPlane;
    }

    int mcpPort() {
      return mcp.port();
    }

    int controlPlanePort() {
      return controlPlane.port();
    }

    @Override
    public void close() {
      controlPlane.close();
      mcp.close();
    }
  }
}
