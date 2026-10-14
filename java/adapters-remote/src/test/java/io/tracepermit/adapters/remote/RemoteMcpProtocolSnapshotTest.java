package io.tracepermit.adapters.remote;

import com.fasterxml.jackson.databind.JsonNode;
import io.tracepermit.contracts.JsonSupport;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

final class RemoteMcpProtocolSnapshotTest {
  private static final Path ROOT =
      Path.of(System.getProperty("tracepermit.repositoryRoot"));

  @Test
  void portableSnapshotPinsTheHardenedSimulatorEnforcementBoundary()
      throws Exception {
    JsonNode contract =
        JsonSupport.MAPPER.readTree(
            Files.readString(ROOT.resolve("contracts/protocols/mcp-v1.json")));
    JsonNode remote = contract.path("remoteOAuthTransport");

    assertThat(remote.path("status").asText())
        .isEqualTo("hardened-simulator-enforcement-foundation");
    assertThat(remote.path("metadataPath").asText())
        .isEqualTo("/.well-known/oauth-protected-resource");
    assertThat(remote.path("mcpPath").asText()).isEqualTo("/mcp");
    assertThat(remote.path("maximumRequestBytes").asInt()).isEqualTo(65_536);
    assertThat(remote.path("authenticationBeforeParsing").asBoolean()).isTrue();
    assertThat(remote.path("exactHostAllowlist").asBoolean()).isTrue();
    assertThat(remote.path("exactOriginAllowlist").asBoolean()).isTrue();
    assertThat(remote.path("tlsRequired").asBoolean()).isTrue();
    assertThat(remote.path("toolInvocationEnabled").asBoolean()).isTrue();
    assertThat(remote.path("invocationScopeCheckedIndependently").asBoolean()).isTrue();
    assertThat(remote.path("serverOwnedCanonicalNormalization").asBoolean()).isTrue();
    assertThat(remote.path("policyBeforePermit").asBoolean()).isTrue();
    assertThat(remote.path("executorPermitVerification").asBoolean()).isTrue();
    assertThat(remote.path("simulatorOnly").asBoolean()).isTrue();
    assertThat(remote.path("realTargetsEnabled").asBoolean()).isFalse();
    assertThat(remote.path("evidenceStoresRawArgumentsOrResults").asBoolean()).isFalse();
    JsonNode hardened = remote.path("hardenedProfile");
    assertThat(hardened.path("senderConstraint").asText())
        .isEqualTo("rfc8705-mtls-x5t-s256");
    assertThat(hardened.path("clientCertificateRequired").asBoolean()).isTrue();
    assertThat(hardened.path("tokenCertificateBindingBeforeParsing").asBoolean()).isTrue();
    assertThat(hardened.path("networkRateLimitBeforeAuthentication").asBoolean()).isTrue();
    assertThat(hardened.path("principalRateLimitAfterAuthentication").asBoolean()).isTrue();
    assertThat(hardened.path("readinessFailsClosed").asBoolean()).isTrue();
    assertThat(hardened.path("durableEnforcementCompositionTested").asBoolean()).isTrue();
    assertThat(hardened.path("sharedRateLimitImplementationIncluded").asBoolean()).isFalse();
    assertThat(hardened.path("productionBootstrapIncluded").asBoolean()).isFalse();
    assertThat(hardened.path("readinessDependencies"))
        .extracting(JsonNode::asText)
        .containsExactlyElementsOf(RemoteEdgeReadiness.REQUIRED);
    assertThat(remote.path("methods"))
        .extracting(JsonNode::asText)
        .containsExactly(
            "initialize", "notifications/initialized", "ping", "tools/list", "tools/call");
    assertThat(remote.path("protocolVersions"))
        .extracting(JsonNode::asText)
        .containsExactly(
            HandshakeOnlyMcpDispatcher.CURRENT_PROTOCOL_VERSION,
            "2025-06-18",
            "2025-03-26");
    assertThat(remote.path("connectorFixtures"))
        .extracting(item -> item.path("name").asText())
        .containsExactly("chatgpt-web-compatible", "claude-web-compatible");
    assertThat(remote.path("connectorFixtures"))
        .allMatch(
            item ->
                "synthetic-protocol-conformance"
                    .equals(item.path("evidence").asText()));
  }
}
