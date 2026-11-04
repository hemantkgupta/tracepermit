package io.tracepermit.adapters.remote.agentcore;

import io.tracepermit.application.TrustedOperationEvaluatorContext;
import io.tracepermit.application.TrustedRequesterContext;

/** Closed provider-specific authority implementations; no caller-supplied identity or callbacks. */
public sealed interface OperationIdentityAuthority
    permits OktaOperationAuthority, Auth0OperationAuthority {
  TrustedRequesterContext requester(String authorization, String requiredScope);

  TrustedOperationEvaluatorContext evaluator(String authorization);

  OktaOperationAuthority.ReviewAuthority reviewer(String authorization);
}
