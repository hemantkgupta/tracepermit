package io.tracepermit.identity;

import java.util.Objects;

/** Verified access-token confirmation binding retained without certificate material. */
public record TokenSenderConstraint(String profile, CredentialFingerprint certificateFingerprint) {
  public static final String MTLS_X5T_S256_V1 = "mtls-x5t-s256-v1";

  public TokenSenderConstraint {
    if (!MTLS_X5T_S256_V1.equals(profile)) {
      throw new IllegalArgumentException("The sender-constraint profile is not supported.");
    }
    Objects.requireNonNull(certificateFingerprint);
  }
}
