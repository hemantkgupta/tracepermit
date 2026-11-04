package io.tracepermit.auth0reviewer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.tracepermit.agentcore.organizationcli.OrganizationOperatorFixture;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

final class OperationReviewInputTest {
  @Test void threeFramesAndExactBindingsPrepareOfflineWithoutAuthorityOrCredentialExport() throws Exception {
    var fixture = fixture();
    var input = OperationReviewInput.read(new ByteArrayInputStream(frames(fixture, request(fixture))));
    assertThat(input.reviewer.client()).isEqualTo(OrganizationOperatorFixture.REVIEWER_CLIENT);
    assertThat(input.plan.toString()).doesNotContain("password", "replay", "publicKeySpki");
    assertThat(input.toString()).isEqualTo("OperationReviewInput[redacted]");
  }

  @ParameterizedTest @ValueSource(strings = {"issuer", "audience", "clientId", "expectedSubject", "tenant", "keyId", "publicKeySpki"})
  void differentLoginTrustCannotBeJoinedToAnOrganizationReview(String name) {
    var f = fixture();var reviewer = f.reviewerConfiguration();
    switch (name) {
      case "issuer" -> reviewer.put(name, "https://different.au.auth0.com/");
      case "audience" -> reviewer.put(name, "https://different.example.invalid/api");
      case "expectedSubject" -> reviewer.put(name, "auth0|different-human");
      case "publicKeySpki" -> reviewer.put(name,
          java.util.Base64.getEncoder().encodeToString(f.requesterKey.getPublic().getEncoded()));
      default -> reviewer.put(name, "different-value");
    }
    byte[] bytes = OrganizationOperatorFixture.frames(ReviewerFixtures.bytes(reviewer), f.configBytes(), ReviewerFixtures.bytes(request(f)));
    assertThatThrownBy(() -> OperationReviewInput.read(new ByteArrayInputStream(bytes)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @ParameterizedTest @ValueSource(strings = {"identity", "authenticated", "approved", "bearer", "callback", "privateKey", "target"})
  void decisionBodyCannotAddIdentityCredentialsOrExecutionAuthority(String field) {
    var f = fixture();var request = request(f).put(field, "untrusted");
    assertThatThrownBy(() -> OperationReviewInput.read(new ByteArrayInputStream(frames(f, request))))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @ParameterizedTest @ValueSource(strings = {"schemaVersion", "profile", "operationId", "decision", "expectedSnapshotHash", "authorityProfileFingerprint", "expectedProfileFingerprint"})
  void missingOrSubstitutedDecisionPinsNeverPrepare(String field) {
    var f = fixture();var missing = request(f);missing.remove(field);
    assertThatThrownBy(() -> OperationReviewInput.read(new ByteArrayInputStream(frames(f, missing))))
        .isInstanceOf(IllegalArgumentException.class);
    var changed = request(f).put(field, "untrusted");
    assertThatThrownBy(() -> OperationReviewInput.read(new ByteArrayInputStream(frames(f, changed))))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test void duplicateTrailingMalformedAndOversizeInputNeverPrepares() throws Exception {
    var f = fixture();String request = request(f).toString();
    for (String invalid : new String[]{"null", "[]", "{} {}", "{", request.replace("\"decision\":\"APPROVE\"", "\"decision\":\"APPROVE\",\"decision\":\"DENY\""), "x".repeat(1025)}) {
      byte[] bytes = OrganizationOperatorFixture.frames(ReviewerFixtures.bytes(f.reviewerConfiguration()), f.configBytes(), invalid.getBytes(StandardCharsets.UTF_8));
      assertThatThrownBy(() -> OperationReviewInput.read(new ByteArrayInputStream(bytes)))
          .isInstanceOf(IllegalArgumentException.class);
    }
    byte[] valid = frames(f, request(f));
    byte[] extra = java.util.Arrays.copyOf(valid, valid.length + 1);
    assertThatThrownBy(() -> OperationReviewInput.read(new ByteArrayInputStream(extra)))
        .isInstanceOf(IllegalArgumentException.class);
    for (int length : new int[]{-1, 0, 16385, 10}) {
      var bytes = new ByteArrayOutputStream();var out = new DataOutputStream(bytes);
      out.writeInt(length);if (length==10) out.write(new byte[]{'{','}'});
      assertThatThrownBy(() -> OperationReviewInput.read(new ByteArrayInputStream(bytes.toByteArray())))
          .isInstanceOfAny(IllegalArgumentException.class, java.io.EOFException.class);
    }
  }

  @Test void boundedStageDoesNotRenewTimeoutAndExpiredTotalCannotStartWork() throws Exception {
    assertThat(Auth0OperationReviewMain.bounded(System.nanoTime(), Duration.ofSeconds(1), () -> "completed"))
        .isEqualTo("completed");
    AtomicBoolean called = new AtomicBoolean();
    long expired = System.nanoTime() - Duration.ofSeconds(240).toNanos();
    assertThatThrownBy(() -> Auth0OperationReviewMain.bounded(expired, Duration.ofSeconds(1), () -> {called.set(true);return true;}))
        .isInstanceOf(TimeoutException.class);
    assertThat(called).isFalse();
    assertThatThrownBy(() -> Auth0OperationReviewMain.bounded(System.nanoTime(), Duration.ofMillis(10), () -> {
      Thread.sleep(5000);return true;
    })).isInstanceOf(TimeoutException.class);
  }

  static OrganizationOperatorFixture fixture() {
    var f = OrganizationOperatorFixture.auth0();f.useReviewHelperClaims();return f;
  }
  static ObjectNode request(OrganizationOperatorFixture f) {
    return f.reviewCommand(UUID.randomUUID().toString(), "sha256:" + "a".repeat(64), "APPROVE");
  }
  static byte[] frames(OrganizationOperatorFixture f, ObjectNode command) {
    return OrganizationOperatorFixture.frames(ReviewerFixtures.bytes(f.reviewerConfiguration()),
        f.configBytes(), ReviewerFixtures.bytes(command));
  }
}
