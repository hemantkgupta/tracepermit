package io.tracepermit.adapters.remote.github;

/**
 * Immutable operator-owned GitHub repository and ref identity.
 *
 * <p>Implementations are configuration values, never request-body projections or provider claims.
 */
public sealed interface GitHubTargetBinding
    permits GitHubPilotTargetBinding, GitHubOrganizationTargetBinding {
  long installationId();

  long repositoryId();

  long ownerId();

  String repository();

  String head();

  String base();

  String headSha();

  String baseSha();

  String fingerprint();

  default void requireAction(GitHubDraftPullRequestAction action) {
    if (!repository().equals(action.repository())
        || !head().equals(action.head())
        || !base().equals(action.base())) {
      throw new GitHubPullRequestException(
          "TARGET_BINDING_INVALID",
          "TARGET_BINDING_INVALID",
          "GitHub target binding was rejected.");
    }
  }
}
