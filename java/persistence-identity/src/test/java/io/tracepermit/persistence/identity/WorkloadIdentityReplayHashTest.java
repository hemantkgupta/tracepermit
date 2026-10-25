package io.tracepermit.persistence.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.Test;

final class WorkloadIdentityReplayHashTest {
  private static final Instant T = Instant.parse("2030-01-01T00:00:00Z");

  @Test
  void domainSeparationAndLengthDelimitationPreventFieldBoundaryCollisions() {
    String hash1 = hash("tenant", "issuer", "token");
    String hash2 = hash("ten", "antissuer", "token");
    String hash3 = hash("t", "enant", "issuertoken");
    String hash4 = hash("tenant", "is", "suertoken");

    assertThat(hash1).isNotEqualTo(hash2);
    assertThat(hash1).isNotEqualTo(hash3);
    assertThat(hash1).isNotEqualTo(hash4);
    assertThat(hash2).isNotEqualTo(hash3);
    assertThat(hash2).isNotEqualTo(hash4);
    assertThat(hash3).isNotEqualTo(hash4);

    assertThat(hash1).matches("^sha256:[0-9a-f]{64}$");
  }

  @Test
  void embeddedDelimitersDoNotCauseCollisions() {
    String hash1 = hash("t\u0000i", "i", "x");
    String hash2 = hash("t", "i\u0000x", "pad");
    String hash3 = hash("t", "i", "x\u0000pad");
    String hash4 = hash("t:i", "i", "x");
    String hash5 = hash("t", "i:x", "x");

    assertThat(hash1).isNotEqualTo(hash2);
    assertThat(hash1).isNotEqualTo(hash3);
    assertThat(hash1).isNotEqualTo(hash4);
    assertThat(hash1).isNotEqualTo(hash5);
    assertThat(hash2).isNotEqualTo(hash3);
  }

  @Test
  void caseSensitivityIsPreservedAndNeverFolded() {
    String lower = hash("tenant:acme", "issuer:primary", "token:abc");
    String upper = hash("TENANT:ACME", "ISSUER:PRIMARY", "TOKEN:ABC");
    String mixed = hash("Tenant:Acme", "Issuer:Primary", "Token:Abc");

    assertThat(lower).isNotEqualTo(upper);
    assertThat(lower).isNotEqualTo(mixed);
    assertThat(upper).isNotEqualTo(mixed);
  }

  @Test
  void trailingWhitespaceDoesNotCollide() {
    String standard = hash("tenant:acme", "issuer:primary", "token:abc");
    String paddedTenant = hash("tenant:acme ", "issuer:primary", "token:abc");
    String paddedIssuer = hash("tenant:acme", "issuer:primary ", "token:abc");
    String paddedToken = hash("tenant:acme", "issuer:primary", "token:abc ");

    assertThat(standard).isNotEqualTo(paddedTenant);
    assertThat(standard).isNotEqualTo(paddedIssuer);
    assertThat(standard).isNotEqualTo(paddedToken);
    assertThat(paddedTenant).isNotEqualTo(paddedIssuer);
  }

  @Test
  void validMultiByteUnicodeEncodesDeterministically() {
    String hash1 = hash("テナント", "発行者", "トークン-123");
    String hash2 = hash("テナント", "発行者", "トークン-123");
    String hashDifferent = hash("テナント", "発行者", "トークン-124");

    assertThat(hash1).isEqualTo(hash2);
    assertThat(hash1).isNotEqualTo(hashDifferent);
  }

  @Test
  void malformedUnicodeIsRejectedWithoutEchoingInput() {
    String invalidSurrogate = "tenant\uD800invalid";
    assertThatThrownBy(() ->
        JdbcWorkloadIdentityReplayStore.encodeUtf8("tenantId", invalidSurrogate, 128))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("tenantId contains malformed or unmappable Unicode characters")
        .hasMessageNotContaining("invalid");
  }

  @Test
  void lengthBoundariesAreEnforced() {
    String tooLongTenant = "a".repeat(129);
    assertThatThrownBy(() ->
        JdbcWorkloadIdentityReplayStore.encodeUtf8("tenantId", tooLongTenant, 128))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("tenantId exceeds maximum allowed length of 128")
        .hasMessageNotContaining("aaaa");

    String tooLongIssuer = "b".repeat(257);
    assertThatThrownBy(() ->
        JdbcWorkloadIdentityReplayStore.encodeUtf8("issuer", tooLongIssuer, 256))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("issuer exceeds maximum allowed length of 256");

    String tooLongToken = "c".repeat(257);
    assertThatThrownBy(() ->
        JdbcWorkloadIdentityReplayStore.encodeUtf8("tokenId", tooLongToken, 256))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("tokenId exceeds maximum allowed length of 256");
  }

  @Test
  void exceptionCodeAndMessageBehaviors() {
    var ex1 = new WorkloadIdentityReplayException(WorkloadIdentityReplayException.Code.REPLAY_STORAGE_UNAVAILABLE);
    assertThat(ex1.code()).isEqualTo(WorkloadIdentityReplayException.Code.REPLAY_STORAGE_UNAVAILABLE);
    assertThat(ex1.getMessage()).isEqualTo("REPLAY_STORAGE_UNAVAILABLE");

    var ex2 = new WorkloadIdentityReplayException(WorkloadIdentityReplayException.Code.REPLAY_INPUT_INVALID, "custom-safe-message");
    assertThat(ex2.code()).isEqualTo(WorkloadIdentityReplayException.Code.REPLAY_INPUT_INVALID);
    assertThat(ex2.getMessage()).isEqualTo("REPLAY_INPUT_INVALID: custom-safe-message");
  }

  @Test
  void constructorValidationFailsOnNonPositiveCapacity() {
    assertThatThrownBy(() -> new JdbcWorkloadIdentityReplayStore(new WorkloadIdentityReplayStoreContract.TestSource("jdbc:fake", "u", "p"), 0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("capacity must be positive");

    assertThatThrownBy(() -> new JdbcWorkloadIdentityReplayStore(new WorkloadIdentityReplayStoreContract.TestSource("jdbc:fake", "u", "p"), -5))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("capacity must be positive");
  }

  @Test
  void emptyOrNullInputsAreRejected() {
    assertThatThrownBy(() ->
        JdbcWorkloadIdentityReplayStore.encodeUtf8("tenantId", null, 128))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("tenantId must not be null or empty");

    assertThatThrownBy(() ->
        JdbcWorkloadIdentityReplayStore.encodeUtf8("tenantId", "", 128))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("tenantId must not be null or empty");
  }

  private static String hash(String tenant, String issuer, String token) {
    return JdbcWorkloadIdentityReplayStore.computeClaimHash(
        tenant.getBytes(StandardCharsets.UTF_8),
        issuer.getBytes(StandardCharsets.UTF_8),
        token.getBytes(StandardCharsets.UTF_8));
  }
}
