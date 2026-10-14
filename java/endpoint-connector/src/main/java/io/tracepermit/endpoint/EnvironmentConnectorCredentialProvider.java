package io.tracepermit.endpoint;

import java.util.Map;
import java.util.Objects;

public final class EnvironmentConnectorCredentialProvider
    implements ConnectorCredentialProvider {
  public static final String VARIABLE = "TRACEPERMIT_CONNECTOR_BEARER_TOKEN";

  private final String bearerToken;

  public EnvironmentConnectorCredentialProvider(Map<String, String> environment) {
    Objects.requireNonNull(environment);
    bearerToken = environment.get(VARIABLE);
    SourceAttestationFactory.fingerprint(bearerToken);
  }

  @Override
  public String currentBearerToken() {
    return bearerToken;
  }
}
