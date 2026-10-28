package io.tracepermit.adapters.remote.agentcore;

import io.tracepermit.application.BusinessOperationAuthorityProfile;
import io.tracepermit.application.BusinessOperationScope;
import io.tracepermit.application.TrustedOperationEvaluatorContext;
import io.tracepermit.application.TrustedOperationGroupMembership;
import io.tracepermit.application.TrustedOperationReviewerContext;
import io.tracepermit.application.TrustedRequesterContext;
import io.tracepermit.application.TrustedReviewerContext;
import io.tracepermit.execution.TenantPartition;
import io.tracepermit.identity.AuthenticatedConnector;
import io.tracepermit.identity.ConnectorScope;
import io.tracepermit.identity.OidcAccessTokenVerifier;
import io.tracepermit.identity.OidcTokenProfile;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Verified Okta token plus exact offline enrollment authority for the operation lifecycle.
 *
 * <p>This is a library boundary only: it opens no listener and accepts no command-body identity.
 */
public final class OktaOperationAuthority {
  private static final Set<String> REQUESTER_SCOPES =
      Set.of("operations.register", "operations.evaluate", "operations.execute");

  public record RequesterEnrollment(
      String issuer,
      String verifiedSubjectId,
      String clientId,
      String tenantId,
      String workspaceId,
      String requesterPrincipalId) {
    public RequesterEnrollment {
      OktaOperationAuthority.require(
          issuer != null && issuer.startsWith("https://") && issuer.length() <= 512);
      OktaOperationAuthority.require(
          verifiedSubjectId != null
              && verifiedSubjectId.startsWith(issuer + "#")
              && verifiedSubjectId.length() > issuer.length() + 1
              && verifiedSubjectId.length() <= 1_024);
      require(clientId, "[A-Za-z0-9][A-Za-z0-9._:@/+\\-=]{0,255}");
      require(tenantId, "[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");
      require(workspaceId, "[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");
      require(requesterPrincipalId, "[A-Za-z0-9][A-Za-z0-9._:@/+\\-]{0,255}");
    }

    private static void require(String value, String pattern) {
      OktaOperationAuthority.require(value != null && value.matches(pattern));
    }
  }

  public record ReviewAuthority(
      TrustedOperationReviewerContext reviewer, TrustedOperationGroupMembership membership) {
    public ReviewAuthority {
      Objects.requireNonNull(reviewer);
      Objects.requireNonNull(membership);
    }
  }

  private final OidcAccessTokenVerifier requesterVerifier;
  private final OidcAccessTokenVerifier reviewerVerifier;
  private final BusinessOperationScope scope;
  private final RequesterEnrollment requesterEnrollment;
  private final OktaOperationReviewerRoster reviewerRoster;
  private final Clock clock;

  public OktaOperationAuthority(
      OidcAccessTokenVerifier requesterVerifier,
      OidcAccessTokenVerifier reviewerVerifier,
      BusinessOperationScope scope,
      RequesterEnrollment requesterEnrollment,
      OktaOperationReviewerRoster reviewerRoster,
      BusinessOperationAuthorityProfile authorityProfile,
      String reviewerClientId,
      Clock clock) {
    try {
      require(
          requesterVerifier != null
              && reviewerVerifier != null
              && requesterVerifier != reviewerVerifier
              && scope != null
              && requesterEnrollment != null
              && reviewerRoster != null
              && authorityProfile != null
              && clock != null);
      require(scope.equals(authorityProfile.scope()));
      require(scope.tenantId().equals(requesterEnrollment.tenantId()));
      require(scope.workspaceId().equals(requesterEnrollment.workspaceId()));
      require(scope.requesterPrincipalId().equals(requesterEnrollment.requesterPrincipalId()));
      require(
          reviewerClientId != null
              && reviewerClientId.matches("[A-Za-z0-9][A-Za-z0-9._:@/+\\-=]{0,255}")
              && !reviewerClientId.equals(requesterEnrollment.clientId()));
      reviewerRoster.requireProfile(
          scope.tenantId(),
          scope.workspaceId(),
          reviewerClientId,
          authorityProfile.reviewSettings().reviewerGroupId());
      require(
          reviewerRoster
              .fingerprint()
              .equals(authorityProfile.reviewSettings().membershipSourceFingerprint()));
    } catch (RuntimeException rejected) {
      throw invalidConfiguration();
    }
    this.requesterVerifier = requesterVerifier;
    this.reviewerVerifier = reviewerVerifier;
    this.scope = scope;
    this.requesterEnrollment = requesterEnrollment;
    this.reviewerRoster = reviewerRoster;
    this.clock = clock;
  }

  public TrustedRequesterContext requester(String authorization, String requiredScope) {
    if (!REQUESTER_SCOPES.contains(requiredScope)) throw rejected();
    AuthenticatedConnector connector = verify(requesterVerifier, authorization, requiredScope, "REQUESTER");
    if (!connector.issuer().equals(requesterEnrollment.issuer())
        || !connector.subjectId().equals(requesterEnrollment.verifiedSubjectId())
        || !connector.clientId().equals(requesterEnrollment.clientId())
        || !connector.organizationId().equals(scope.tenantId())) {
      throw rejected();
    }
    return new TrustedRequesterContext(
        new TenantPartition(scope.tenantId()),
        scope.requesterPrincipalId(),
        true,
        Set.of("operation_requester"),
        connector.scopes().stream()
            .map(ConnectorScope::value)
            .collect(Collectors.toUnmodifiableSet()),
        connector.authenticationProfile(),
        connector.credentialFingerprint().value(),
        connector.expiresAt());
  }

  public TrustedOperationEvaluatorContext evaluator(String authorization) {
    return new TrustedOperationEvaluatorContext(
        requester(authorization, "operations.evaluate"), scope.workspaceId());
  }

  public ReviewAuthority reviewer(String authorization) {
    AuthenticatedConnector connector =
        verify(reviewerVerifier, authorization, "operations.decide", "REVIEWER");
    OktaOperationReviewerRoster.Resolved resolved =
        reviewerRoster.resolve(connector, scope.workspaceId());
    OktaOperationReviewerRoster.Entry entry = resolved.entry();
    if (entry.principalId().equals(scope.requesterPrincipalId())
        || entry.verifiedSubjectId().equals(requesterEnrollment.verifiedSubjectId())) {
      throw rejected();
    }
    var identity =
        new TrustedReviewerContext(
            new TenantPartition(entry.tenantId()),
            entry.principalId(),
            "human",
            true,
            Set.of("operation_reviewer"),
            Set.of("operations.decide"),
            connector.authenticationProfile(),
            connector.credentialFingerprint().value());
    return new ReviewAuthority(
        new TrustedOperationReviewerContext(identity, scope.workspaceId(), connector.expiresAt()),
        new TrustedOperationGroupMembership(
            entry.tenantId(),
            entry.workspaceId(),
            entry.principalId(),
            entry.groupId(),
            reviewerRoster.fingerprint(),
            resolved.observedAt(),
            resolved.expiresAt()));
  }

  private AuthenticatedConnector verify(
      OidcAccessTokenVerifier verifier,
      String authorization,
      String requiredScope,
      String requiredRole) {
    try {
      AuthenticatedConnector connector = verifier.authenticate(authorization);
      Instant now = clock.instant();
      if (!OidcTokenProfile.OKTA.equals(connector.authenticationProfile())
          || connector.senderConstraint().isPresent()
          || !connector.hasScope(ConnectorScope.of(requiredScope))
          || connector.mappedRole().filter(requiredRole::equals).isEmpty()
          || connector.verifiedAt().isAfter(now)
          || !connector.expiresAt().isAfter(now)) {
        throw rejected();
      }
      return connector;
    } catch (RuntimeException rejected) {
      throw rejected();
    }
  }

  private static void require(boolean condition) {
    if (!condition) throw invalidConfiguration();
  }

  private static OktaOperationAuthorityException invalidConfiguration() {
    return new OktaOperationAuthorityException("OKTA_AUTHORITY_CONFIGURATION_INVALID");
  }

  private static OktaOperationAuthorityException rejected() {
    return OktaOperationReviewerRoster.rejected();
  }

  @Override
  public String toString() {
    return "OktaOperationAuthority[redacted; verified tokens plus offline enrollment]";
  }
}
