package io.tracepermit.contracts.redaction;

/**
 * Strategy applied to sensitive fields or values when redacting evidence and arguments.
 */
public enum RedactionStrategy {
  /**
   * Replaces the sensitive value with a static mask indicator (e.g., {@code [REDACTED]}).
   */
  MASK,

  /**
   * Irreversibly transforms the sensitive value into a deterministic SHA-256 fingerprint
   * (e.g., {@code sha256:<hex>}). Allows equality verification across audit records without
   * revealing plaintext, satisfying DATA-003.
   */
  IRREVERSIBLE_HASH,

  /**
   * Drops the property entirely from the enclosing object.
   */
  DROP
}
