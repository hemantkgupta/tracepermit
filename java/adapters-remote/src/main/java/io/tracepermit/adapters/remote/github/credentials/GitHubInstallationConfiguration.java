package io.tracepermit.adapters.remote.github.credentials;

import io.tracepermit.adapters.remote.github.GitHubDraftPullRequestAction;
import java.util.Map;

/** Trusted operator input, never a request/body projection or execution authority. */
public record GitHubInstallationConfiguration(long appId, long installationId,
    long repositoryId, long ownerId, String repository, Map<String, String> permissions) {
  private static final long MAX_ID = 9_007_199_254_740_991L;
  private static final Map<String, String> REQUIRED = Map.of(
      "contents", "read", "metadata", "read", "pull_requests", "write");
  public GitHubInstallationConfiguration {
    try {
      if (!valid(appId) || !valid(installationId) || !valid(repositoryId) || !valid(ownerId)
          || !GitHubDraftPullRequestAction.ALLOWED_REPOSITORY.equals(repository)) throw new IllegalArgumentException();
      permissions = Map.copyOf(permissions);
      if (!REQUIRED.equals(permissions)) throw new IllegalArgumentException();
    } catch (Throwable failure) {
      throw GitHubCredentialException.reject(GitHubCredentialException.Code.CONFIGURATION_REJECTED);
    }
  }
  private static boolean valid(long id) { return id > 0 && id <= MAX_ID; }
  public static Map<String, String> requiredPermissions() { return REQUIRED; }
  @Override public String toString() { return "GitHubInstallationConfiguration[redacted]"; }
}
