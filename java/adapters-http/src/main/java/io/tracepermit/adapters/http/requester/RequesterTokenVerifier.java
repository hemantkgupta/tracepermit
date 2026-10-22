package io.tracepermit.adapters.http.requester;

import io.tracepermit.identity.AuthenticatedConnector;
import io.tracepermit.identity.BrowserIdTokenVerifier;
import io.tracepermit.identity.ConnectorScope;
import io.tracepermit.identity.OidcAccessTokenVerifier;
import java.time.Clock;
import java.time.Instant;
import java.util.Set;

/** Composes existing strict verifiers with the exact installed-requester profile. */
final class RequesterTokenVerifier {
  static final class VerifiedAccess {
    private final AuthenticatedConnector connector;
    private final Instant expiresAt;

    private VerifiedAccess(AuthenticatedConnector connector) {
      this.connector = connector;
      this.expiresAt = connector.expiresAt();
    }

    AuthenticatedConnector connector() { return connector; }
    Instant expiresAt() { return expiresAt; }
  }

  private final InstalledRequesterConfiguration configuration;
  private final OidcAccessTokenVerifier access;
  private final BrowserIdTokenVerifier identity;
  private final Clock clock;

  RequesterTokenVerifier(InstalledRequesterConfiguration configuration,
      OidcAccessTokenVerifier access, BrowserIdTokenVerifier identity, Clock clock) {
    this.configuration = configuration;
    this.access = access;
    this.identity = identity;
    this.clock = clock;
  }

  VerifiedAccess verifyAccess(String token) {
    try {
      AuthenticatedConnector connector = access.authenticate("Bearer " + token);
      Set<ConnectorScope> scopes = Set.copyOf(connector.scopes());
      if (!connector.subjectId().equals(configuration.issuer() + "#"
          + configuration.expectedSubject())) {
        throw new InstalledRequesterFailure("REQUESTER_IDENTITY_MISMATCH");
      }
      if (!connector.clientId().equals(configuration.clientId())
          || !connector.organizationId().equals(configuration.organization())
          || connector.senderConstraint().isPresent()
          || !scopes.containsAll(InstalledRequesterConfiguration.API_SCOPES)
          || !InstalledRequesterConfiguration.ALLOWED_SCOPES.containsAll(scopes)
          || !connector.expiresAt().isAfter(clock.instant().plusSeconds(59))) {
        throw new InstalledRequesterFailure("ACCESS_TOKEN_INVALID");
      }
      return new VerifiedAccess(connector);
    } catch (InstalledRequesterFailure failure) {
      throw failure;
    } catch (RuntimeException failure) {
      throw new InstalledRequesterFailure("ACCESS_TOKEN_INVALID");
    }
  }

  VerifiedAccess verifyLogin(String accessToken, String idToken, String nonce) {
    VerifiedAccess verified = verifyAccess(accessToken);
    try {
      Instant identityExpiry = identity.verify(idToken, configuration.clientId(), nonce,
          accessToken, verified.connector());
      if (!identityExpiry.isAfter(clock.instant().plusSeconds(59))) {
        throw new InstalledRequesterFailure("ID_TOKEN_INVALID");
      }
      return verified;
    } catch (InstalledRequesterFailure failure) {
      throw failure;
    } catch (RuntimeException failure) {
      throw new InstalledRequesterFailure("ID_TOKEN_INVALID");
    }
  }
}
