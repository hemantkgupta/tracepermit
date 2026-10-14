package io.tracepermit.identity;

import java.time.Instant;

public record VerifiedSourceEvidence(
    String issuer,
    String keyId,
    String keyFingerprint,
    Instant verifiedAt,
    Device device,
    ProcessEvidence process,
    TriggerEvidence triggerEvidence) {
  public record Device(String deviceId, String platform, String assurance) {}

  public record ProcessEvidence(
      String executableName,
      String executableHash,
      String parentExecutableName,
      Instant startedAt) {}

  public record TriggerEvidence(String requestedMode, boolean terminalAttached) {}
}
