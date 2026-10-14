package io.tracepermit.endpoint;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Objects;
import java.util.Optional;

public final class EndpointConnector {
  private final ProcessObserver processObserver;
  private final ConnectorCredentialProvider credentialProvider;
  private final SourceAttestationFactory attestationFactory;
  private final GatewayTransport gatewayTransport;

  public EndpointConnector(
      ProcessObserver processObserver,
      ConnectorCredentialProvider credentialProvider,
      SourceAttestationFactory attestationFactory,
      GatewayTransport gatewayTransport) {
    this.processObserver = Objects.requireNonNull(processObserver);
    this.credentialProvider = Objects.requireNonNull(credentialProvider);
    this.attestationFactory = Objects.requireNonNull(attestationFactory);
    this.gatewayTransport = Objects.requireNonNull(gatewayTransport);
  }

  public Optional<JsonNode> forward(JsonNode request) {
    ProcessObservation observation;
    try {
      observation = processObserver.observe();
    } catch (ConnectorException exception) {
      throw exception;
    } catch (RuntimeException exception) {
      throw new ConnectorException(
          "ENDPOINT_OBSERVATION_UNAVAILABLE",
          "Endpoint observation is unavailable.",
          exception);
    }

    String bearerToken;
    try {
      bearerToken = credentialProvider.currentBearerToken();
    } catch (ConnectorException exception) {
      throw exception;
    } catch (RuntimeException exception) {
      throw new ConnectorException(
          "CONNECTOR_CREDENTIAL_UNAVAILABLE",
          "The connector credential is unavailable.",
          exception);
    }

    String compactAttestation =
        attestationFactory.create(request, bearerToken, observation);
    processObserver.verifyStable(observation);
    return gatewayTransport.forward(request, bearerToken, compactAttestation);
  }
}
