package io.tracepermit.adapters.remote.github;

import java.time.Instant;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Ephemeral GitHub App installation credentials derived by a trusted provider.
 * Secrets are never committed to disk, logs, or evidence records.
 */
public record GitHubAppCredentials(
    long installationId,
    String credentialFingerprint,
    String token,
    Instant expiresAt) {

  private static final Pattern FINGERPRINT_PATTERN = Pattern.compile("^sha256:[a-f0-9]{64}$");

  public GitHubAppCredentials {
    if (installationId <= 0) {
      throw new IllegalArgumentException("The GitHub installation ID must be positive.");
    }
    Objects.requireNonNull(credentialFingerprint, "credentialFingerprint must not be null");
    if (!FINGERPRINT_PATTERN.matcher(credentialFingerprint).matches()) {
      throw new IllegalArgumentException("The credential fingerprint must be a SHA-256 hash.");
    }
    Objects.requireNonNull(token, "token must not be null");
    if (token.isBlank()) {
      throw new IllegalArgumentException("The installation token must not be blank.");
    }
    Objects.requireNonNull(expiresAt, "expiresAt must not be null");
  }

  @Override
  public String toString() {
    // Redact the raw token from any string representation to prevent accidental log leaks.
    return "GitHubAppCredentials[installationId=" + installationId
        + ", fingerprint=" + credentialFingerprint
        + ", expiresAt=" + expiresAt + "]";
  }
}
