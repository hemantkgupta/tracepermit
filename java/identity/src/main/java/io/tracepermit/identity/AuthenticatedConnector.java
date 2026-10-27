package io.tracepermit.identity;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record AuthenticatedConnector(
    String subjectId,
    String displayName,
    String organizationId,
    ConnectorOrigin origin,
    String clientId,
    String clientName,
    String issuer,
    Instant verifiedAt,
    String authenticationProfile,
    String verificationKeyId,
    CredentialFingerprint credentialFingerprint,
    List<ConnectorScope> scopes,
    Instant expiresAt,
    Optional<TokenSenderConstraint> senderConstraint,
    Optional<String> mappedRole) {

  public AuthenticatedConnector(String subjectId, String displayName, String organizationId,
      ConnectorOrigin origin, String clientId, String clientName, String issuer, Instant verifiedAt,
      String authenticationProfile, String verificationKeyId, CredentialFingerprint credentialFingerprint,
      List<ConnectorScope> scopes, Instant expiresAt, Optional<TokenSenderConstraint> senderConstraint) {
    this(subjectId, displayName, organizationId, origin, clientId, clientName, issuer, verifiedAt,
        authenticationProfile, verificationKeyId, credentialFingerprint, scopes, expiresAt,
        senderConstraint, Optional.empty());
  }

  public AuthenticatedConnector(
      String subjectId,
      String displayName,
      String organizationId,
      ConnectorOrigin origin,
      String clientId,
      String clientName,
      String issuer,
      Instant verifiedAt,
      String authenticationProfile,
      String verificationKeyId,
      CredentialFingerprint credentialFingerprint,
      List<ConnectorScope> scopes,
      Instant expiresAt) {
    this(
        subjectId,
        displayName,
        organizationId,
        origin,
        clientId,
        clientName,
        issuer,
        verifiedAt,
        authenticationProfile,
        verificationKeyId,
        credentialFingerprint,
        scopes,
        expiresAt,
        Optional.empty());
  }

  public AuthenticatedConnector {
    subjectId = nonEmpty(subjectId, "subjectId");
    displayName = nonEmpty(displayName, "displayName");
    organizationId = nonEmpty(organizationId, "organizationId");
    Objects.requireNonNull(origin);
    clientId = nonEmpty(clientId, "clientId");
    clientName = nonEmpty(clientName, "clientName");
    issuer = nonEmpty(issuer, "issuer");
    Objects.requireNonNull(verifiedAt);
    authenticationProfile = nonEmpty(authenticationProfile, "authenticationProfile");
    verificationKeyId = nonEmpty(verificationKeyId, "verificationKeyId");
    Objects.requireNonNull(credentialFingerprint);
    scopes = List.copyOf(scopes);
    Objects.requireNonNull(expiresAt);
    senderConstraint = Objects.requireNonNull(senderConstraint);
    mappedRole = Objects.requireNonNull(mappedRole);
    if (mappedRole.isPresent() && !java.util.Set.of("REQUESTER", "REVIEWER", "VIEWER")
        .contains(mappedRole.orElseThrow())) throw new IllegalArgumentException("Invalid mapped role.");
    if (!expiresAt.isAfter(verifiedAt)) {
      throw new IllegalArgumentException("Authenticated connector credentials must not be expired.");
    }
  }

  public boolean hasScope(ConnectorScope required) {
    return scopes.contains(Objects.requireNonNull(required));
  }

  private static String nonEmpty(String value, String field) {
    Objects.requireNonNull(value);
    if (value.isEmpty()) throw new IllegalArgumentException(field + " must not be empty.");
    return value;
  }
}
