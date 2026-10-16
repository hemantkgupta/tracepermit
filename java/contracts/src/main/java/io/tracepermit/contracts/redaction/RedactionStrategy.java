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
   * Replaces the value with an unkeyed deterministic SHA-256 fingerprint of its
   * canonical JSON (e.g., {@code sha256:<hex>}). This supports correlation, not
   * confidentiality: low-entropy values remain vulnerable to guessing. Prefer
   * MASK or DROP for secrets. The enum name is retained for API compatibility.
   */
  IRREVERSIBLE_HASH,

  /**
   * Omits an object property, replaces a matched standalone/array value with JSON
   * null, or removes a matching span from diagnostic text.
   */
  DROP
}
