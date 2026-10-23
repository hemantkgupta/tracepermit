package io.tracepermit.application;

import static org.assertj.core.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

final class BusinessOperationScopeTest {
  @Test
  void scopeFingerprintIsDeterministicCaseSensitiveAndCoversAllThreePartitions() {
    var scope = new BusinessOperationScope("synthetic", "workspace", "principal:requester");
    assertThat(scope.fingerprint()).isEqualTo(
        new BusinessOperationScope("synthetic", "workspace", "principal:requester").fingerprint());
    assertThat(List.of(scope.fingerprint(),
        new BusinessOperationScope("Synthetic", "workspace", "principal:requester").fingerprint(),
        new BusinessOperationScope("synthetic", "other", "principal:requester").fingerprint(),
        new BusinessOperationScope("synthetic", "workspace", "principal:other").fingerprint()))
        .doesNotHaveDuplicates();
    scope.trustedContext().put("tenantId", "changed");
    assertThat(scope.trustedContext().path("tenantId").asText()).isEqualTo("synthetic");
    assertThat(scope.toString()).isEqualTo("BusinessOperationScope[redacted]");
  }

  @Test
  void scopeRejectsMissingInvalidAndOversizedPartitions() {
    assertThatThrownBy(() -> new BusinessOperationScope(null, "workspace", "principal:requester"))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new BusinessOperationScope("../other", "workspace", "principal:requester"))
        .isInstanceOf(IllegalArgumentException.class).hasMessage("OPERATION_SCOPE_INVALID");
    assertThatThrownBy(() -> new BusinessOperationScope("synthetic", "", "principal:requester"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new BusinessOperationScope("synthetic", "workspace", "x".repeat(257)))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
