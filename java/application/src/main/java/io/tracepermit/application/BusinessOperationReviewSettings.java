package io.tracepermit.application;

import static io.tracepermit.application.BusinessOperationStorageException.Code.OPERATION_PROFILE_INVALID;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.tracepermit.contracts.JsonSupport;
import java.time.Duration;
import java.time.Instant;

/** Explicit operator settings. No default group, enrollment source or approval lifetime. */
public record BusinessOperationReviewSettings(
    String reviewerGroupId, String membershipSourceFingerprint, Duration approvalLifetime) {
  public BusinessOperationReviewSettings {
    if (reviewerGroupId == null || !reviewerGroupId.matches("[A-Za-z0-9][A-Za-z0-9._:@/+\\-]{0,255}")
        || membershipSourceFingerprint == null || !membershipSourceFingerprint.matches("sha256:[a-f0-9]{64}")
        || approvalLifetime == null || approvalLifetime.isZero() || approvalLifetime.isNegative()
        || approvalLifetime.compareTo(Duration.ofMinutes(5)) > 0) {
      throw new BusinessOperationStorageException(OPERATION_PROFILE_INVALID);
    }
  }

  /** Exact versioned hash data, not mutable configuration or a membership assertion. */
  public ObjectNode pins() {
    var pins = JsonSupport.MAPPER.createObjectNode();
    pins.put("settings", "tracepermit-operation-review-settings-v1");
    pins.put("reviewerGroupId", reviewerGroupId);
    pins.put("membershipSourceFingerprint", membershipSourceFingerprint);
    pins.put("approvalLifetimeSeconds", approvalLifetime.getSeconds());
    pins.put("approvalLifetimeNanos", approvalLifetime.getNano());
    return pins;
  }

  /** Caller must supply fresh writer time AFTER every authority lock; this method takes no clock. */
  public Instant deadlineFromWriterTime(Instant writerTime) {
    try {
      if (writerTime == null) throw new IllegalArgumentException();
      return writerTime.plus(approvalLifetime);
    } catch (RuntimeException failure) {
      throw new BusinessOperationStorageException(OPERATION_PROFILE_INVALID);
    }
  }

  @Override public String toString() {
    return "BusinessOperationReviewSettings[redacted; operator pins, not authority]";
  }
}
