package io.tracepermit.adapters.remote.github;

/**
 * Trusted provider for short-lived GitHub App installation credentials.
 * Transports or callers must not supply their own verified credential claims.
 */
@FunctionalInterface
public interface GitHubAppCredentialProvider {
  GitHubAppCredentials getInstallationCredentials(String repository);
}
