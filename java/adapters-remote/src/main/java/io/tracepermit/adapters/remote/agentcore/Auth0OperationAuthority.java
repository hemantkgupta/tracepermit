package io.tracepermit.adapters.remote.agentcore;

import io.tracepermit.application.BusinessOperationAuthorityProfile;
import io.tracepermit.application.BusinessOperationScope;
import io.tracepermit.application.TrustedOperationEvaluatorContext;
import io.tracepermit.application.TrustedRequesterContext;
import io.tracepermit.identity.OidcAccessTokenVerifier;
import io.tracepermit.identity.OidcTokenProfile;
import java.time.Clock;

/** Separate Auth0 profile; reuses the exact enrollment, roster and independence checks. */
public final class Auth0OperationAuthority {
  private final OktaOperationAuthority delegate;

  public Auth0OperationAuthority(OidcAccessTokenVerifier requesterVerifier,
      OidcAccessTokenVerifier reviewerVerifier, BusinessOperationScope scope,
      OktaOperationAuthority.RequesterEnrollment enrollment, OktaOperationReviewerRoster roster,
      BusinessOperationAuthorityProfile profile, String reviewerClientId, Clock clock) {
    try {
      delegate = new OktaOperationAuthority(requesterVerifier, reviewerVerifier, scope, enrollment,
          roster, profile, reviewerClientId, clock, OidcTokenProfile.AUTH0);
    } catch (RuntimeException rejected) {
      throw new Auth0OperationAuthorityException("AUTH0_AUTHORITY_CONFIGURATION_INVALID");
    }
  }

  public TrustedRequesterContext requester(String authorization, String requiredScope) {
    try { return delegate.requester(authorization, requiredScope); }
    catch (RuntimeException rejected) { throw new Auth0OperationAuthorityException("AUTH0_AUTHORITY_REJECTED"); }
  }

  public TrustedOperationEvaluatorContext evaluator(String authorization) {
    try { return delegate.evaluator(authorization); }
    catch (RuntimeException rejected) { throw new Auth0OperationAuthorityException("AUTH0_AUTHORITY_REJECTED"); }
  }

  public OktaOperationAuthority.ReviewAuthority reviewer(String authorization) {
    try { return delegate.reviewer(authorization); }
    catch (RuntimeException rejected) { throw new Auth0OperationAuthorityException("AUTH0_AUTHORITY_REJECTED"); }
  }

  @Override public String toString() { return "Auth0OperationAuthority[redacted]"; }
}
