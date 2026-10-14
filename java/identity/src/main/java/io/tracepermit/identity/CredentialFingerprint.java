package io.tracepermit.identity;

import java.util.Objects;
import java.util.regex.Pattern;

public record CredentialFingerprint(String value) {
  private static final Pattern SHA256 = Pattern.compile("^sha256:[a-f0-9]{64}$");

  public CredentialFingerprint {
    Objects.requireNonNull(value);
    if (!SHA256.matcher(value).matches()) {
      throw new IllegalArgumentException("Credential fingerprints must be SHA-256 values.");
    }
  }
}
