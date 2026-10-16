package io.tracepermit.execution;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

final class ApprovalPolicyBindingTest {
  private static final Instant NOW = Instant.parse("2026-10-16T10:00:00Z");
  private static final String HASH = "sha256:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

  @Test
  void validatesConstructorFields() {
    assertThatThrownBy(() -> new ApprovalPolicyBinding(null, "pol-1", 1, 1L, HASH, NOW))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new ApprovalPolicyBinding("ws-1", null, 1, 1L, HASH, NOW))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new ApprovalPolicyBinding("ws-1", "pol-1", 0, 1L, HASH, NOW))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("policyVersion must be positive");
    assertThatThrownBy(() -> new ApprovalPolicyBinding("ws-1", "pol-1", 1, 0L, HASH, NOW))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("policyGeneration must be positive");
    assertThatThrownBy(() -> new ApprovalPolicyBinding("ws-1", "pol-1", 1, 1L, "invalid-hash", NOW))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Invalid policyContentHash format");
    assertThatThrownBy(() -> new ApprovalPolicyBinding("ws-1", "pol-1", 1, 1L, HASH, null))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void createsValidBinding() {
    ApprovalPolicyBinding binding = new ApprovalPolicyBinding("ws-prod", "pol-gate", 2, 5L, HASH, NOW);
    assertThat(binding.workspaceId()).isEqualTo("ws-prod");
    assertThat(binding.policyId()).isEqualTo("pol-gate");
    assertThat(binding.policyVersion()).isEqualTo(2);
    assertThat(binding.policyGeneration()).isEqualTo(5L);
    assertThat(binding.policyContentHash()).isEqualTo(HASH);
    assertThat(binding.boundAt()).isEqualTo(NOW);
  }
}
