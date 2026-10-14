package io.tracepermit.identity;

@FunctionalInterface
public interface ConnectorAuthenticator {
  AuthenticatedConnector authenticate(String authorizationHeader);
}
