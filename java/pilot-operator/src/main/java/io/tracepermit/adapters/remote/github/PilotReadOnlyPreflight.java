package io.tracepermit.adapters.remote.github;

import io.tracepermit.adapters.remote.github.credentials.GitHubInstallationCredentialProvider;
import java.time.Clock;

/** Same-package composition reuses the exact verifier, not the dispatch/reconciliation APIs. */
final class PilotReadOnlyPreflight {
  interface Session extends AutoCloseable {
    void verify(GitHubPilotTargetBinding binding, GitHubAppCredentials credentials) throws Exception;
    @Override void close();
  }
  @FunctionalInterface interface Sessions { Session open(); }
  private final GitHubAppCredentialProvider provider;
  private final Sessions sessions;
  private final Clock clock;

  private PilotReadOnlyPreflight(GitHubAppCredentialProvider provider, Sessions sessions, Clock clock) {
    this.provider = provider; this.sessions = sessions; this.clock = clock;
  }

  static PilotReadOnlyPreflight production(PilotConfiguration configuration) {
    var provider = GitHubInstallationCredentialProvider.production(configuration.installation(), configuration.key());
    var transport = new GitHubApiTransport(GitHubDraftPullRequestAdapter.PRODUCTION_GITHUB_API_URI,
        GitHubDraftPullRequestAdapter.DEFAULT_CONNECT_TIMEOUT, GitHubDraftPullRequestAdapter.DEFAULT_CALL_TIMEOUT,
        GitHubDraftPullRequestAdapter.DEFAULT_MAX_RESPONSE_BYTES, null, null);
    return compose(provider, transport::open, Clock.systemUTC());
  }

  static PilotReadOnlyPreflight compose(GitHubAppCredentialProvider provider,
      java.util.function.Supplier<GitHubApiTransport.Session> sessions, Clock clock) {
    return new PilotReadOnlyPreflight(provider, () -> {
      var session = sessions.get();
      return new Session() {
        @Override public void verify(GitHubPilotTargetBinding binding, GitHubAppCredentials credentials) throws Exception {
          GitHubTargetPreflight.verify(session, binding, credentials, clock);
        }
        @Override public void close() { session.close(); }
      };
    }, clock);
  }

  // Package-private seam for deterministic close failures; unreachable from command/configuration.
  static PilotReadOnlyPreflight localFixture(GitHubAppCredentialProvider provider, Sessions sessions, Clock clock) {
    return new PilotReadOnlyPreflight(provider, sessions, clock);
  }

  void observe(PilotConfiguration configuration) throws Exception {
    var credentials = provider.getInstallationCredentials(configuration.installation().repository());
    GitHubApiTransport.requireCredentials(credentials, configuration.target().installationId(), clock);
    try (var session = sessions.open()) {
      session.verify(configuration.target(), credentials);
    }
    // Cleanup and waits must finish before an observation is reported.
    GitHubApiTransport.requireCredentials(credentials, configuration.target().installationId(), clock);
  }
}
