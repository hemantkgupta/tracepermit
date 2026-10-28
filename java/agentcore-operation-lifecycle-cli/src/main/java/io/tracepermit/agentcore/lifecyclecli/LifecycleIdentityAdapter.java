package io.tracepermit.agentcore.lifecyclecli;

import io.tracepermit.application.TrustedOperationEvaluatorContext;
import io.tracepermit.application.TrustedOperationGroupMembership;
import io.tracepermit.application.TrustedOperationReviewerContext;
import io.tracepermit.application.TrustedRequesterContext;
import io.tracepermit.application.TrustedReviewerContext;
import io.tracepermit.execution.TenantPartition;
import io.tracepermit.identity.AuthenticatedConnector;
import io.tracepermit.identity.ConnectorScope;
import java.time.Clock;
import java.util.Set;
import java.util.stream.Collectors;

/** Converts verified tokens plus exact operator enrollment into internal trusted contexts. */
final class LifecycleIdentityAdapter {
  record ReviewAuthority(
      TrustedOperationReviewerContext reviewer, TrustedOperationGroupMembership membership) {}

  private final LifecycleOperatorConfig configuration;
  private final Clock clock;

  LifecycleIdentityAdapter(LifecycleOperatorConfig configuration, Clock clock) {
    this.configuration = configuration;
    this.clock = clock;
  }

  TrustedRequesterContext requester(String token, String requiredScope) {
    AuthenticatedConnector connector =
        verify(configuration.requesterProvider(), token, requiredScope);
    var enrollment = configuration.requesterEnrollment();
    if (!connector.subjectId().equals(enrollment.verifiedSubjectId())
        || !connector.clientId().equals(enrollment.clientId())
        || !connector.organizationId().equals(configuration.scope().tenantId())
        || !enrollment.tenantId().equals(configuration.scope().tenantId())
        || !enrollment.workspaceId().equals(configuration.scope().workspaceId())
        || !enrollment.requesterPrincipalId().equals(
            configuration.scope().requesterPrincipalId())) {
      throw new LifecycleFailure("REJECTED");
    }
    return new TrustedRequesterContext(
        new TenantPartition(configuration.scope().tenantId()),
        configuration.scope().requesterPrincipalId(),
        true,
        Set.of("operation_requester"),
        connector.scopes().stream().map(ConnectorScope::value).collect(Collectors.toUnmodifiableSet()),
        connector.authenticationProfile(),
        connector.credentialFingerprint().value(),
        connector.expiresAt());
  }

  TrustedOperationEvaluatorContext evaluator(String token) {
    return new TrustedOperationEvaluatorContext(
        requester(token, "operations.evaluate"), configuration.scope().workspaceId());
  }

  ReviewAuthority reviewer(String token) {
    AuthenticatedConnector connector =
        verify(configuration.reviewerProvider(), token, "operations.decide");
    // Principal aliases and different clients must not make one verified subject independent.
    if (connector.subjectId().equals(configuration.requesterEnrollment().verifiedSubjectId())) {
      throw new LifecycleFailure("REJECTED");
    }
    LifecycleMemberships.Resolved resolved =
        configuration.memberships().resolve(connector, configuration.scope().workspaceId());
    LifecycleMemberships.Entry entry = resolved.entry();
    if (!entry.tenantId().equals(configuration.scope().tenantId())
        || !entry.workspaceId().equals(configuration.scope().workspaceId())
        || !entry.groupId().equals(
            configuration.authority().reviewSettings().reviewerGroupId())
        || entry.principalId().equals(configuration.scope().requesterPrincipalId())
        || !configuration.memberships().fingerprint().equals(
            configuration.authority().reviewSettings().membershipSourceFingerprint())) {
      throw new LifecycleFailure("REJECTED");
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
        new TrustedOperationReviewerContext(
            identity, configuration.scope().workspaceId(), connector.expiresAt()),
        new TrustedOperationGroupMembership(
            entry.tenantId(),
            entry.workspaceId(),
            entry.principalId(),
            entry.groupId(),
            configuration.memberships().fingerprint(),
            resolved.observedAt(),
            resolved.expiresAt()));
  }

  private AuthenticatedConnector verify(
      LifecycleOperatorConfig.Provider provider, String token, String requiredScope) {
    try {
      AuthenticatedConnector connector = provider.verifier(clock).authenticate("Bearer " + token);
      if (connector.senderConstraint().isPresent()
          || !connector.hasScope(ConnectorScope.of(requiredScope))
          || !connector.expiresAt().isAfter(clock.instant())) {
        throw new LifecycleFailure("REJECTED");
      }
      return connector;
    } catch (LifecycleFailure failure) {
      throw failure;
    } catch (RuntimeException failure) {
      throw new LifecycleFailure("REJECTED");
    }
  }
}
