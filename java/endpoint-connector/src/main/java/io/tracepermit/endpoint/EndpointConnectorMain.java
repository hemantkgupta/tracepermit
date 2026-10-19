package io.tracepermit.endpoint;

import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.time.Clock;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public final class EndpointConnectorMain {
  private EndpointConnectorMain() {}

  public static void main(String[] args) {
    int exit = execute(args, System.getenv(), System.in, System.out, System.err);
    if (exit != 0) System.exit(exit);
  }

  public static int execute(
      String[] args,
      Map<String, String> environment,
      InputStream input,
      OutputStream output,
      PrintStream diagnostics) {
    if (args == null || args.length == 0) {
      return run(environment, input, output, diagnostics);
    }
    if (args.length == 1) {
      String flag = args[0];
      if ("--help".equals(flag) || "-h".equals(flag)) {
        PrintStream out = new PrintStream(output, true, StandardCharsets.UTF_8);
        out.println("Usage: tracepermit-endpoint-connector [--help | --check | --run]");
        out.println("Commands:");
        out.println("  --help, -h  Display this help message and exit.");
        out.println("  --check     Perform offline configuration validation without network calls.");
        out.println("  --run       Start the stdio Model Context Protocol (MCP) connector (default).");
        return 0;
      }
      if ("--check".equals(flag)) {
        return check(environment, output, diagnostics);
      }
      if ("--run".equals(flag)) {
        return run(environment, input, output, diagnostics);
      }
    }
    diagnostics.println("TracePermit endpoint connector: CONFIGURATION_INVALID");
    return 2;
  }

  public static int check(
      Map<String, String> environment,
      OutputStream output,
      PrintStream diagnostics) {
    try {
      if (Runtime.version().feature() < 21) {
        throw new IllegalArgumentException("Java 21 or newer is required.");
      }
      String issuer = required(environment, "TRACEPERMIT_ENDPOINT_ISSUER", 256);
      String keyId = required(environment, "TRACEPERMIT_ENDPOINT_KEY_ID", 128);
      String deviceId = required(environment, "TRACEPERMIT_ENDPOINT_DEVICE_ID", 128);
      Path keyPath = Path.of(required(
          environment, "TRACEPERMIT_ENDPOINT_PRIVATE_KEY_PKCS8_PATH", 4096));
      URI gatewayUri = URI.create(required(environment, "TRACEPERMIT_GATEWAY_URL", 2048));
      LoopbackHttpGatewayTransport.validateGatewayUri(gatewayUri);

      // Validate key format and permissions offline
      new FileEndpointKeyProvider(
          new EndpointKeyProvider.KeyMetadata(issuer, keyId, deviceId), keyPath);

      // Validate bearer token shape & fingerprint offline
      String bearerToken = required(
          environment, EnvironmentConnectorCredentialProvider.VARIABLE, 16 * 1024);
      SourceAttestationFactory.fingerprint(bearerToken);

      // Validate observation directory attributes if configured without creating files
      if (environment.containsKey("TRACEPERMIT_ENDPOINT_OBSERVATION_DIRECTORY")) {
        Path captureDirectory = Path.of(required(
            environment, "TRACEPERMIT_ENDPOINT_OBSERVATION_DIRECTORY", 4096));
        validateObservationDirectory(captureDirectory);
      }

      PrintStream out = new PrintStream(output, true, StandardCharsets.UTF_8);
      out.println("ENDPOINT_CONFIGURATION_VALID: offline only; no gateway, IdP, observer or client checked.");
      return 0;
    } catch (ConnectorException | IllegalArgumentException | SecurityException exception) {
      diagnostics.println("TracePermit endpoint connector: CONFIGURATION_INVALID");
      return 2;
    }
  }

  public static int run(
      Map<String, String> environment,
      InputStream input,
      OutputStream output,
      PrintStream diagnostics) {
    try {
      String issuer = required(environment, "TRACEPERMIT_ENDPOINT_ISSUER", 256);
      String keyId = required(environment, "TRACEPERMIT_ENDPOINT_KEY_ID", 128);
      String deviceId = required(environment, "TRACEPERMIT_ENDPOINT_DEVICE_ID", 128);
      Path keyPath = Path.of(required(
          environment, "TRACEPERMIT_ENDPOINT_PRIVATE_KEY_PKCS8_PATH", 4096));
      URI gatewayUri = URI.create(required(environment, "TRACEPERMIT_GATEWAY_URL", 2048));

      EndpointKeyProvider keyProvider = new FileEndpointKeyProvider(
          new EndpointKeyProvider.KeyMetadata(issuer, keyId, deviceId), keyPath);
      ProcessObserver observer = observer(deviceId);
      Path captureDirectory = environment.containsKey("TRACEPERMIT_ENDPOINT_OBSERVATION_DIRECTORY")
          ? Path.of(required(environment, "TRACEPERMIT_ENDPOINT_OBSERVATION_DIRECTORY", 4096))
          : null;
      ConnectorCredentialProvider credentialProvider =
          new EnvironmentConnectorCredentialProvider(environment);
      SourceAttestationFactory attestationFactory = new SourceAttestationFactory(
          keyProvider, Clock.systemUTC(), () -> UUID.randomUUID().toString(), Duration.ofSeconds(30));
      GatewayTransport transport = new LoopbackHttpGatewayTransport(
          gatewayUri, Duration.ofSeconds(2), Duration.ofSeconds(10));
      try (FileProcessObservationRecorder recorder = captureDirectory == null ? null
          : new FileProcessObservationRecorder(captureDirectory)) {
        EndpointConnector connector = new EndpointConnector(
            observer, credentialProvider, attestationFactory, transport,
            recorder == null ? EndpointObservationCapture.NONE : recorder);
        return new StdioMcpServer(input, output, diagnostics, connector).run();
      }
    } catch (ConnectorException | IllegalArgumentException | SecurityException exception) {
      diagnostics.println("TracePermit endpoint connector: CONFIGURATION_INVALID");
      return 2;
    }
  }

  private static void validateObservationDirectory(Path directory) {
    try {
      Path normalized = directory.normalize();
      if (!normalized.isAbsolute() || !normalized.equals(normalized.toRealPath())) {
        throw new IllegalArgumentException("Invalid observation directory");
      }
      UserPrincipal owner = normalized.getFileSystem().getUserPrincipalLookupService()
          .lookupPrincipalByName(System.getProperty("user.name"));
      PosixFileAttributes attributes = Files.readAttributes(
          normalized, PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
      if (!attributes.isDirectory()
          || !attributes.owner().equals(owner)
          || !attributes.permissions().equals(PosixFilePermissions.fromString("rwx------"))
          || attributes.fileKey() == null) {
        throw new IllegalArgumentException("Invalid observation directory attributes");
      }
    } catch (Exception exception) {
      throw new IllegalArgumentException("Invalid observation directory", exception);
    }
  }

  private static ProcessObserver observer(String deviceId) {
    String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
    if (os.contains("mac")) return new MacOsDevelopmentProcessObserver(deviceId);
    return new ProcessHandleProcessObserver(deviceId, ignored -> System.console() != null);
  }

  private static String required(
      Map<String, String> environment, String name, int maximumLength) {
    String value = environment.get(name);
    if (value == null
        || value.isBlank()
        || value.length() > maximumLength
        || value.indexOf('\r') >= 0
        || value.indexOf('\n') >= 0) {
      throw new IllegalArgumentException("Required endpoint connector configuration is missing.");
    }
    return value;
  }
}
