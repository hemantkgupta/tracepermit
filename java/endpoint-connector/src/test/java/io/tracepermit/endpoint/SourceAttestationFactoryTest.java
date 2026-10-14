package io.tracepermit.endpoint;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.tracepermit.contracts.JsonSupport;
import io.tracepermit.identity.EndpointKeyRegistry;
import io.tracepermit.identity.InMemorySourceAttestationReplayStore;
import io.tracepermit.identity.SourceAttestationException;
import io.tracepermit.identity.SourceAttestationVerifier;
import io.tracepermit.identity.StaticEndpointKeyRegistry;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.Queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

final class SourceAttestationFactoryTest {
  private static final Instant NOW = Instant.parse("2030-01-01T00:00:30Z");
  private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
  private static final String TOKEN = "sentinel-development-bearer-token";

  @Test
  void createsFreshRequestAndCredentialBoundProofsWithoutLeakingLocalPath() throws Exception {
    KeyPair keys = ed25519();
    Queue<String> tokenIds = new ArrayDeque<>(List.of(
        "018f0000-0000-7000-8000-000000000011",
        "018f0000-0000-7000-8000-000000000012"));
    SourceAttestationFactory factory = factory(keys, tokenIds::remove);
    ObjectNode request = request("tools/list", JsonSupport.MAPPER.createObjectNode());

    String first = factory.create(request, TOKEN, observation(true));
    String second = factory.create(request, TOKEN, observation(true));

    assertThat(first).isNotEqualTo(second);
    JsonNode firstClaims = claims(first);
    JsonNode secondClaims = claims(second);
    assertThat(firstClaims.path("tokenId").asText())
        .isEqualTo("018f0000-0000-7000-8000-000000000011");
    assertThat(secondClaims.path("tokenId").asText())
        .isEqualTo("018f0000-0000-7000-8000-000000000012");
    assertThat(firstClaims.path("process").has("executablePath")).isFalse();
    assertThat(firstClaims.path("connectorCredentialFingerprint").asText())
        .isEqualTo(SourceAttestationFactory.fingerprint(TOKEN));

    SourceAttestationVerifier verifier = verifier(keys);
    assertThat(verifier.verify(first, request, SourceAttestationFactory.fingerprint(TOKEN))
        .device().deviceId()).isEqualTo("device-simulated-connector");
    assertThat(verifier.verify(second, request, SourceAttestationFactory.fingerprint(TOKEN))
        .process().executableName()).isEqualTo("codex");
  }

  @Test
  void requestAndCredentialMutationInvalidateTheProof() {
    KeyPair keys = ed25519();
    ObjectNode request = request("tools/list", JsonSupport.MAPPER.createObjectNode());
    SourceAttestationFactory factory = factory(
        keys, () -> "018f0000-0000-7000-8000-000000000021");
    String proof = factory.create(request, TOKEN, observation(true));
    ObjectNode changed = request("tools/call", JsonSupport.MAPPER.createObjectNode());

    assertCode(
        () -> verifier(keys).verify(
            proof, changed, SourceAttestationFactory.fingerprint(TOKEN)),
        "INVALID_SOURCE_ATTESTATION");
    assertCode(
        () -> verifier(keys).verify(
            proof, request, SourceAttestationFactory.fingerprint("another-token")),
        "INVALID_SOURCE_ATTESTATION");
  }

  @Test
  void terminalFreeObservationRemainsUnknownRatherThanHuman() throws Exception {
    SourceAttestationFactory factory = factory(
        ed25519(), () -> "018f0000-0000-7000-8000-000000000031");

    JsonNode claims = claims(factory.create(
        request("tools/list", JsonSupport.MAPPER.createObjectNode()),
        TOKEN,
        observation(false)));

    assertThat(claims.path("triggerEvidence").path("requestedMode").asText())
        .isEqualTo("unknown");
    assertThat(claims.path("triggerEvidence").path("terminalAttached").asBoolean())
        .isFalse();
  }

  @Test
  void endpointConnectorFailsClosedForUnavailableOrUnstableDependencies() {
    KeyPair keys = ed25519();
    SourceAttestationFactory factory = factory(
        keys, () -> "018f0000-0000-7000-8000-000000000041");
    JsonNode request = request("tools/list", JsonSupport.MAPPER.createObjectNode());
    ProcessObserver stable = observer(observation(true), false);
    GatewayTransport accepted = (body, credential, proof) -> Optional.of(response(body.path("id")));

    assertConnectorCode(
        new EndpointConnector(
            new FailingObserver("ENDPOINT_OBSERVATION_UNAVAILABLE"),
            () -> TOKEN,
            factory,
            accepted),
        request,
        "ENDPOINT_OBSERVATION_UNAVAILABLE");
    assertConnectorCode(
        new EndpointConnector(
            observer(observation(true), true), () -> TOKEN, factory, accepted),
        request,
        "ENDPOINT_EXECUTABLE_CHANGED");
    assertConnectorCode(
        new EndpointConnector(
            stable,
            () -> {
              throw new ConnectorException(
                  "CONNECTOR_CREDENTIAL_UNAVAILABLE", "Credential unavailable.");
            },
            factory,
            accepted),
        request,
        "CONNECTOR_CREDENTIAL_UNAVAILABLE");
    assertConnectorCode(
        new EndpointConnector(
            stable,
            () -> TOKEN,
            factory,
            (body, credential, proof) -> {
              throw new ConnectorException("GATEWAY_UNAVAILABLE", "Gateway unavailable.");
            }),
        request,
        "GATEWAY_UNAVAILABLE");

    EndpointKeyProvider unavailableKey = new EndpointKeyProvider() {
      @Override
      public KeyMetadata metadata() {
        return new KeyMetadata(
            "tracepermit-endpoint:device-simulated-connector",
            "endpoint-key-connector",
            "device-simulated-connector");
      }

      @Override
      public byte[] sign(byte[] input) {
        throw new ConnectorException("SOURCE_SIGNING_UNAVAILABLE", "Signing unavailable.");
      }
    };
    SourceAttestationFactory unavailableFactory = new SourceAttestationFactory(
        unavailableKey,
        CLOCK,
        () -> "018f0000-0000-7000-8000-000000000042",
        Duration.ofSeconds(30));
    assertConnectorCode(
        new EndpointConnector(stable, () -> TOKEN, unavailableFactory, accepted),
        request,
        "SOURCE_SIGNING_UNAVAILABLE");

    ProcessObserver unexpectedObserver = new ProcessObserver() {
      @Override
      public ProcessObservation observe() {
        throw new IllegalStateException("sentinel-observer-detail");
      }

      @Override
      public void verifyStable(ProcessObservation ignored) {}
    };
    assertConnectorCode(
        new EndpointConnector(unexpectedObserver, () -> TOKEN, factory, accepted),
        request,
        "ENDPOINT_OBSERVATION_UNAVAILABLE");
    assertConnectorCode(
        new EndpointConnector(
            stable,
            () -> {
              throw new IllegalStateException("sentinel-credential-detail");
            },
            factory,
            accepted),
        request,
        "CONNECTOR_CREDENTIAL_UNAVAILABLE");
  }

  @Test
  void invalidLifetimeTokenCredentialAndDeviceConfigurationFailClosed() {
    KeyPair keys = ed25519();
    TestKeyProvider provider = new TestKeyProvider(keys);
    assertThatThrownBy(() -> new SourceAttestationFactory(
        provider, CLOCK, () -> "unused", Duration.ZERO))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new SourceAttestationFactory(
        provider, CLOCK, () -> "unused", Duration.ofSeconds(-1)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new SourceAttestationFactory(
        provider, CLOCK, () -> "unused", Duration.ofSeconds(61)))
        .isInstanceOf(IllegalArgumentException.class);

    SourceAttestationFactory invalidToken = new SourceAttestationFactory(
        provider, CLOCK, () -> "not-a-uuid", Duration.ofSeconds(30));
    assertThatThrownBy(() -> invalidToken.create(
        request("tools/list", JsonSupport.MAPPER.createObjectNode()),
        TOKEN,
        observation(true)))
        .isInstanceOf(ConnectorException.class)
        .extracting(error -> ((ConnectorException) error).code())
        .isEqualTo("TOKEN_ID_UNAVAILABLE");

    ProcessObservation wrongDevice = new ProcessObservation(
        99,
        "device-simulated-other",
        "linux",
        "development",
        "codex",
        Path.of("/simulated/codex"),
        "sha256:" + "2".repeat(64),
        "terminal",
        NOW.minusSeconds(60),
        true);
    SourceAttestationFactory valid = factory(
        keys, () -> "018f0000-0000-7000-8000-000000000051");
    assertThatThrownBy(() -> valid.create(
        request("tools/list", JsonSupport.MAPPER.createObjectNode()), TOKEN, wrongDevice))
        .isInstanceOf(ConnectorException.class)
        .extracting(error -> ((ConnectorException) error).code())
        .isEqualTo("ENDPOINT_DEVICE_MISMATCH");
    assertThatThrownBy(() -> SourceAttestationFactory.fingerprint("bad\ncredential"))
        .isInstanceOf(ConnectorException.class)
        .extracting(error -> ((ConnectorException) error).code())
        .isEqualTo("CONNECTOR_CREDENTIAL_UNAVAILABLE");

    EndpointKeyProvider malformedSigner = new EndpointKeyProvider() {
      @Override
      public KeyMetadata metadata() {
        return provider.metadata();
      }

      @Override
      public byte[] sign(byte[] signingInput) {
        return new byte[1];
      }
    };
    SourceAttestationFactory malformedSignature = new SourceAttestationFactory(
        malformedSigner,
        CLOCK,
        () -> "018f0000-0000-7000-8000-000000000052",
        Duration.ofSeconds(30));
    assertThatThrownBy(() -> malformedSignature.create(
        request("tools/list", JsonSupport.MAPPER.createObjectNode()), TOKEN, observation(true)))
        .isInstanceOf(ConnectorException.class)
        .extracting(error -> ((ConnectorException) error).code())
        .isEqualTo("SOURCE_SIGNING_UNAVAILABLE");
  }

  private static SourceAttestationFactory factory(KeyPair keys, TokenIdGenerator tokenIds) {
    return new SourceAttestationFactory(
        new TestKeyProvider(keys), CLOCK, tokenIds, Duration.ofSeconds(30));
  }

  private static SourceAttestationVerifier verifier(KeyPair keys) {
    return new SourceAttestationVerifier(
        new StaticEndpointKeyRegistry(List.of(
            new EndpointKeyRegistry.EnrolledEndpointKey(
                "tracepermit-endpoint:device-simulated-connector",
                "endpoint-key-connector",
                "device-simulated-connector",
                keys.getPublic()))),
        new InMemorySourceAttestationReplayStore(),
        CLOCK);
  }

  private static ProcessObservation observation(boolean terminalAttached) {
    return new ProcessObservation(
        42,
        "device-simulated-connector",
        "linux",
        "development",
        "codex",
        Path.of("/simulated/codex"),
        "sha256:" + "2".repeat(64),
        "terminal",
        NOW.minusSeconds(60),
        terminalAttached);
  }

  private static ProcessObserver observer(ProcessObservation observation, boolean unstable) {
    return new ProcessObserver() {
      @Override
      public ProcessObservation observe() {
        return observation;
      }

      @Override
      public void verifyStable(ProcessObservation ignored) {
        if (unstable) {
          throw new ConnectorException(
              "ENDPOINT_EXECUTABLE_CHANGED", "Executable changed.");
        }
      }
    };
  }

  private static ObjectNode request(String method, JsonNode params) {
    ObjectNode request = JsonSupport.MAPPER.createObjectNode();
    request.put("jsonrpc", "2.0");
    request.put("id", 7);
    request.put("method", method);
    request.set("params", params);
    return request;
  }

  private static ObjectNode response(JsonNode id) {
    ObjectNode response = JsonSupport.MAPPER.createObjectNode();
    response.put("jsonrpc", "2.0");
    response.set("id", id.deepCopy());
    response.set("result", JsonSupport.MAPPER.createObjectNode());
    return response;
  }

  private static JsonNode claims(String compact) throws Exception {
    String payload = compact.split("\\.")[1];
    return JsonSupport.MAPPER.readTree(Base64.getUrlDecoder().decode(payload));
  }

  private static KeyPair ed25519() {
    try {
      return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    } catch (GeneralSecurityException exception) {
      throw new IllegalStateException(exception);
    }
  }

  private static void assertCode(
      org.assertj.core.api.ThrowableAssert.ThrowingCallable operation, String expected) {
    assertThatThrownBy(operation)
        .isInstanceOf(SourceAttestationException.class)
        .extracting(error -> ((SourceAttestationException) error).code())
        .isEqualTo(expected);
  }

  private static void assertConnectorCode(
      EndpointConnector connector, JsonNode request, String expected) {
    assertThatThrownBy(() -> connector.forward(request))
        .isInstanceOf(ConnectorException.class)
        .extracting(error -> ((ConnectorException) error).code())
        .isEqualTo(expected);
  }

  private static final class TestKeyProvider implements EndpointKeyProvider {
    private final KeyPair keys;

    private TestKeyProvider(KeyPair keys) {
      this.keys = keys;
    }

    @Override
    public KeyMetadata metadata() {
      return new KeyMetadata(
          "tracepermit-endpoint:device-simulated-connector",
          "endpoint-key-connector",
          "device-simulated-connector");
    }

    @Override
    public byte[] sign(byte[] signingInput) {
      try {
        Signature signature = Signature.getInstance("Ed25519");
        signature.initSign(keys.getPrivate());
        signature.update(signingInput);
        return signature.sign();
      } catch (GeneralSecurityException exception) {
        throw new IllegalStateException(exception);
      }
    }
  }

  private static final class FailingObserver implements ProcessObserver {
    private final String code;

    private FailingObserver(String code) {
      this.code = code;
    }

    @Override
    public ProcessObservation observe() {
      throw new ConnectorException(code, "Observation unavailable.");
    }

    @Override
    public void verifyStable(ProcessObservation observation) {
      throw new AssertionError("Stability must not be checked after failed observation.");
    }
  }
}
