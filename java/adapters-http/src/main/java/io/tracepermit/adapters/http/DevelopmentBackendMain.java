package io.tracepermit.adapters.http;

import io.tracepermit.adapters.remote.EnforcingMcpDispatcher;
import io.tracepermit.application.BoundedLogReadSanitizer;
import io.tracepermit.application.DurableExecutionService;
import io.tracepermit.application.InMemoryActionSequenceStore;
import io.tracepermit.application.SecureExecutionService;
import io.tracepermit.application.SecurityReadiness;
import io.tracepermit.application.TenantActionEnforcer;
import io.tracepermit.contracts.JsonSupport;
import io.tracepermit.execution.InMemoryEvidenceLedger;
import io.tracepermit.execution.InMemoryExecutionLedger;
import io.tracepermit.identity.DevelopmentTestTokenIssuer;
import io.tracepermit.identity.DevelopmentTokenVerifier;
import io.tracepermit.identity.EndpointKeyRegistry;
import io.tracepermit.identity.InMemorySourceAttestationReplayStore;
import io.tracepermit.identity.SourceAttestationVerifier;
import io.tracepermit.identity.StaticEndpointKeyRegistry;
import io.tracepermit.persistence.postgres.PostgresDurableEnforcementState;
import io.tracepermit.persistence.postgres.PostgresPermitConsumptionStore;
import io.tracepermit.policy.PolicyEvaluator;
import io.tracepermit.security.ExecutionPermitAuthority;
import io.tracepermit.security.InMemoryPermitConsumptionStore;
import io.tracepermit.simulator.StatefulTargetSimulator;
import org.postgresql.ds.PGSimpleDataSource;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.X509EncodedKeySpec;
import java.time.Clock;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;

/** Starts the complete loopback-only Java development backend. */
public final class DevelopmentBackendMain {
  private static final SecureRandom RANDOM = new SecureRandom();
  private static final List<String> ENDPOINT_CONFIGURATION =
      List.of(
          "TRACEPERMIT_ENDPOINT_ISSUER",
          "TRACEPERMIT_ENDPOINT_KEY_ID",
          "TRACEPERMIT_ENDPOINT_DEVICE_ID",
          "TRACEPERMIT_ENDPOINT_PUBLIC_KEY_SPKI_BASE64");

  private DevelopmentBackendMain() {}

  public static void main(String[] args) throws Exception {
    RunningBackend backend = start(System.getenv(), Clock.systemUTC());
    Runtime.getRuntime().addShutdownHook(new Thread(backend::close));
    System.out.println(
        "TracePermit Java development MCP listening on http://127.0.0.1:"
            + backend.mcpPort());
    System.out.println(
        "TracePermit Java read-only control plane listening on http://127.0.0.1:"
            + backend.controlPlanePort());
    new CountDownLatch(1).await();
  }

  static RunningBackend start(Map<String, String> environment, Clock clock) throws Exception {
    Path repositoryRoot = repositoryRoot(environment);
    SecurityReadiness readiness = SecurityReadiness.allReady(clock.instant());
    StatefulTargetSimulator simulator = new StatefulTargetSimulator();
    TenantActionEnforcer enforcement =
        createEnforcer(environment, repositoryRoot, simulator, readiness, clock);
    DemoControlPlaneData data = DemoControlPlaneData.load(repositoryRoot, clock);
    EnforcingMcpDispatcher dispatcher =
        new EnforcingMcpDispatcher(
            enforcement,
            clock,
            () -> UUID.randomUUID().toString(),
            DevelopmentBackendMain::traceId,
            data::recordLive);

    byte[] developmentKey = randomBytes(32);
    DevelopmentTokenVerifier tokenVerifier = new DevelopmentTokenVerifier(developmentKey, clock);
    DevelopmentTestTokenIssuer tokenIssuer =
        new DevelopmentTestTokenIssuer(
            developmentKey, clock, () -> UUID.randomUUID().toString(), 300);
    SourceAttestationVerifier sourceVerifier = sourceVerifier(environment, clock);

    InetAddress loopback = InetAddress.getByName("127.0.0.1");
    int mcpPort = port(environment, "TRACEPERMIT_JAVA_MCP_PORT", 8080);
    int apiPort = port(environment, "TRACEPERMIT_JAVA_API_PORT", 8090);
    LoopbackDevelopmentMcpServer mcp = null;
    try {
      mcp =
          LoopbackDevelopmentMcpServer.start(
              new InetSocketAddress(loopback, mcpPort),
              tokenVerifier,
              tokenIssuer,
              sourceVerifier,
              dispatcher,
              () -> readiness);
      ControlPlaneServer controlPlane =
          ControlPlaneServer.start(
              new InetSocketAddress(loopback, apiPort),
              data.actionFeed(),
              data.views(),
              simulator,
              clock,
              DevelopmentRuntimeInfo.from(environment));
      return new RunningBackend(mcp, controlPlane);
    } catch (RuntimeException exception) {
      if (mcp != null) mcp.close();
      throw exception;
    }
  }

  static TenantActionEnforcer createEnforcer(
      Map<String, String> environment,
      Path repositoryRoot,
      StatefulTargetSimulator simulator,
      SecurityReadiness readiness,
      Clock clock) {
    String storage = environment.getOrDefault("TRACEPERMIT_STORAGE", "memory");
    if ("postgres".equalsIgnoreCase(storage)) {
      return postgresEnforcer(environment, repositoryRoot, simulator, readiness, clock);
    }
    if (!"memory".equalsIgnoreCase(storage)) {
      throw new IllegalArgumentException("Unsupported development storage mode.");
    }
    SecureExecutionService secure = enforcement(repositoryRoot, simulator, readiness, clock);
    return (context, event) -> secure.execute(event);
  }

  private static TenantActionEnforcer postgresEnforcer(
      Map<String, String> environment,
      Path repositoryRoot,
      StatefulTargetSimulator simulator,
      SecurityReadiness readiness,
      Clock clock) {
    PGSimpleDataSource dataSource = new PGSimpleDataSource();
    String jdbcUrl = environment.get("TRACEPERMIT_POSTGRES_JDBC_URL");
    if (jdbcUrl == null || jdbcUrl.isBlank()) {
      jdbcUrl = environment.get("TRACEPERMIT_POSTGRES_URL");
    }
    if (jdbcUrl == null || jdbcUrl.isBlank() || !jdbcUrl.startsWith("jdbc:postgresql://")) {
      throw new IllegalArgumentException("PostgreSQL development storage requires an explicit JDBC URL.");
    }
    dataSource.setUrl(jdbcUrl);
    if (environment.containsKey("TRACEPERMIT_POSTGRES_USER")) {
      dataSource.setUser(environment.get("TRACEPERMIT_POSTGRES_USER"));
    }
    if (environment.containsKey("TRACEPERMIT_POSTGRES_PASSWORD")) {
      dataSource.setPassword(environment.get("TRACEPERMIT_POSTGRES_PASSWORD"));
    }
    PostgresDurableEnforcementState state =
        new PostgresDurableEnforcementState(
            dataSource,
            clock,
            () -> "attempt-" + UUID.randomUUID());
    ExecutionPermitAuthority authority =
        new ExecutionPermitAuthority(
            ExecutionPermitAuthority.DEFAULT_ISSUER,
            "ephemeral-java-durable-key",
            randomBytes(32),
            30,
            clock,
            () -> "permit-" + UUID.randomUUID(),
            new PostgresPermitConsumptionStore(dataSource));
    return new DurableExecutionService(
        new PolicyEvaluator(),
        JsonSupport.read(repositoryRoot.resolve("policies/aws-web-readonly.json")),
        authority,
        state,
        simulator,
        () -> readiness,
        clock,
        "urn:tracepermit:java-development-simulator",
        "urn:tracepermit:adapter:development-mcp");
  }

  private static SecureExecutionService enforcement(
      Path repositoryRoot,
      StatefulTargetSimulator simulator,
      SecurityReadiness readiness,
      Clock clock) {
    return new SecureExecutionService(
        new PolicyEvaluator(),
        JsonSupport.read(repositoryRoot.resolve("policies/aws-agentic-demo.json")),
        new ExecutionPermitAuthority(
            ExecutionPermitAuthority.DEFAULT_ISSUER,
            "ephemeral-java-development-key",
            randomBytes(32),
            30,
            clock,
            () -> "permit-" + UUID.randomUUID(),
            new InMemoryPermitConsumptionStore()),
        new InMemoryExecutionLedger(() -> "attempt-" + UUID.randomUUID()),
        new InMemoryEvidenceLedger(
            "urn:tracepermit:java-development",
            clock,
            () -> "evidence-" + UUID.randomUUID()),
        simulator,
        () -> readiness,
        clock,
        "urn:tracepermit:java-development-simulator",
        new BoundedLogReadSanitizer(),
        InMemoryActionSequenceStore.oneMinute());
  }

  static SourceAttestationVerifier sourceVerifier(
      Map<String, String> environment, Clock clock) {
    List<String> configured =
        ENDPOINT_CONFIGURATION.stream()
            .filter(name -> !environment.getOrDefault(name, "").isEmpty())
            .toList();
    if (configured.isEmpty()) return null;
    if (configured.size() != ENDPOINT_CONFIGURATION.size()) {
      throw new IllegalArgumentException(
          "Development endpoint-key configuration must be complete.");
    }
    String issuer = bounded(environment, ENDPOINT_CONFIGURATION.get(0), 256);
    String keyId = bounded(environment, ENDPOINT_CONFIGURATION.get(1), 128);
    String deviceId = bounded(environment, ENDPOINT_CONFIGURATION.get(2), 128);
    PublicKey publicKey = publicKey(bounded(environment, ENDPOINT_CONFIGURATION.get(3), 4096));
    EndpointKeyRegistry.EnrolledEndpointKey enrolled =
        new EndpointKeyRegistry.EnrolledEndpointKey(issuer, keyId, deviceId, publicKey);
    return new SourceAttestationVerifier(
        new StaticEndpointKeyRegistry(List.of(enrolled)),
        new InMemorySourceAttestationReplayStore(),
        clock);
  }

  static PublicKey publicKey(String encoded) {
    byte[] der;
    try {
      der = Base64.getDecoder().decode(encoded);
    } catch (IllegalArgumentException exception) {
      throw new IllegalArgumentException("Development endpoint public key is invalid.", exception);
    }
    String canonical = Base64.getEncoder().encodeToString(der);
    if (der.length == 0 || !canonical.equals(encoded)) {
      throw new IllegalArgumentException("Development endpoint public key is invalid.");
    }
    try {
      return KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(der));
    } catch (GeneralSecurityException exception) {
      throw new IllegalArgumentException("Development endpoint public key is invalid.", exception);
    }
  }

  static String bounded(
      Map<String, String> environment, String name, int maximumLength) {
    String value = environment.get(name);
    if (value == null
        || value.isEmpty()
        || value.length() > maximumLength
        || value.indexOf('\r') >= 0
        || value.indexOf('\n') >= 0) {
      throw new IllegalArgumentException("Development endpoint configuration is invalid.");
    }
    return value;
  }

  static Path repositoryRoot(Map<String, String> environment) {
    String configured = environment.get("TRACEPERMIT_REPOSITORY_ROOT");
    return configured == null
        ? Path.of("..").toAbsolutePath().normalize()
        : Path.of(configured).toAbsolutePath().normalize();
  }

  static int port(Map<String, String> environment, String name, int defaultValue) {
    try {
      int value = Integer.parseInt(environment.getOrDefault(name, Integer.toString(defaultValue)));
      if (value < 0 || value > 65_535) throw new NumberFormatException("out of range");
      return value;
    } catch (NumberFormatException exception) {
      throw new IllegalArgumentException(name + " must be a valid TCP port.", exception);
    }
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
    private final LoopbackDevelopmentMcpServer mcp;
    private final ControlPlaneServer controlPlane;

    RunningBackend(LoopbackDevelopmentMcpServer mcp, ControlPlaneServer controlPlane) {
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
