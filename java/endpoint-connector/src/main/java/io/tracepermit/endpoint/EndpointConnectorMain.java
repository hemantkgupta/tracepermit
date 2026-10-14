package io.tracepermit.endpoint;

import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.URI;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public final class EndpointConnectorMain {
  private EndpointConnectorMain() {}

  public static void main(String[] args) {
    int exit = run(System.getenv(), System.in, System.out, System.err);
    if (exit != 0) System.exit(exit);
  }

  static int run(
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
      ConnectorCredentialProvider credentialProvider =
          new EnvironmentConnectorCredentialProvider(environment);
      SourceAttestationFactory attestationFactory = new SourceAttestationFactory(
          keyProvider, Clock.systemUTC(), () -> UUID.randomUUID().toString(), Duration.ofSeconds(30));
      GatewayTransport transport = new LoopbackHttpGatewayTransport(
          gatewayUri, Duration.ofSeconds(2), Duration.ofSeconds(10));
      EndpointConnector connector = new EndpointConnector(
          observer, credentialProvider, attestationFactory, transport);
      return new StdioMcpServer(input, output, diagnostics, connector).run();
    } catch (ConnectorException | IllegalArgumentException exception) {
      diagnostics.println("TracePermit endpoint connector: CONFIGURATION_INVALID");
      return 2;
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
