package io.tracepermit.auth0reviewer;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.tracepermit.agentcore.organizationcli.Auth0OrganizationReview;
import io.tracepermit.agentcore.organizationcli.OrganizationOperatorFixture;
import io.tracepermit.agentcore.organizationcli.OrganizationReviewTestStore;
import io.tracepermit.contracts.CanonicalJson;
import java.io.ByteArrayInputStream;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Fake browser/exchange with real signed tokens, real PKCE core and real restricted MariaDB review. */
@Timeout(180)
final class MariaDb114OperationReviewSessionTest {
  private OrganizationReviewTestStore store;
  private AtomicInteger exchanges;
  private String id;
  private String snapshot;

  @BeforeEach void start() {
    store = new OrganizationReviewTestStore();exchanges = new AtomicInteger();
    id = store.pending(UUID.randomUUID().toString());
    snapshot = CanonicalJson.sha256(store.snapshot(id));
  }
  @AfterEach void stop() { if (store != null) {store.close();store = null;} }

  @Test void explicitConfirmationApprovesOnlyPinnedOperationAndDoesNotDeliver() throws Exception {
    assertThat(store.deliveryOutcome(id)).isEqualTo("REJECTED");
    long facts = store.count("tp_operation_lifecycle_facts");
    var input = input("APPROVE", snapshot);
    var terminal = new StringWriter();
    var result = run(input, new StringReader(confirmation("APPROVE") + "\n"), terminal, c -> {});
    assertThat(result.exit()).isZero();
    assertThat(text(result)).contains("REVIEW_APPROVED", "\"executionEnabled\":false");
    assertThat(store.snapshot(id).path("authorization").path("status").asText()).isEqualTo("approved");
    assertThat(store.count("tp_operation_lifecycle_facts")).isEqualTo(facts + 1);
    assertThat(store.count("tp_operation_admissions")).isZero();
    assertThat(store.count("tp_operation_admission_outbox")).isZero();
    assertThat(store.count("tp_operation_delivery_events")).isZero();
    assertThat(terminal.toString()).contains(id, snapshot, "\\u03c0", "\\n").doesNotContain("π", "😀");
    // An explicitly separate synthetic requester call is possible only after committed review.
    assertThat(store.deliveryOutcome(id)).isEqualTo("DELIVERY_SUCCEEDED");
    assertThat(input.plan.review(store.fixture.reviewerToken(store.fixture.reviewerSubject())).exitCode())
        .isEqualTo(1);
    assertThat(exchanges).hasValue(1);
  }

  @Test void explicitDenialCannotBecomeDeliveryAuthority() throws Exception {
    var result = run(input("DENY", snapshot), new StringReader(confirmation("DENY") + "\n"),
        new StringWriter(), c -> {});
    assertThat(result.exit()).isZero();assertThat(text(result)).contains("REVIEW_DENIED");
    assertThat(store.snapshot(id).path("authorization").path("status").asText()).isEqualTo("denied");
    assertThat(store.deliveryOutcome(id)).isEqualTo("REJECTED");
    assertThat(store.count("tp_operation_delivery_events")).isZero();
  }

  @ParameterizedTest @ValueSource(strings = {"", "yes\n", "DENY\n", "APPROVE other\n", "\u001b[31m\n", "π\n"})
  void loginWithoutExactTerminalDecisionDoesNotWrite(String answer) throws Exception {
    long facts = store.count("tp_operation_lifecycle_facts");
    var result = run(input("APPROVE", snapshot), new StringReader(answer), new StringWriter(), c -> {});
    assertThat(result.exit()).isEqualTo(1);assertThat(text(result)).contains("REVIEW_NOT_CONFIRMED");
    assertThat(CanonicalJson.sha256(store.snapshot(id))).isEqualTo(snapshot);
    assertThat(store.count("tp_operation_lifecycle_facts")).isEqualTo(facts);
    assertThat(store.count("tp_operation_delivery_events")).isZero();
    assertThat(exchanges).hasValue(1);
  }

  @ParameterizedTest @ValueSource(strings = {"sub", "client_id", "role", "scope", "tenant", "expired"})
  void substitutedSignedIdentityNeverReachesConfirmationOrWrite(String mutation) throws Exception {
    var terminal = new StringWriter();
    var result = run(input("APPROVE", snapshot), new StringReader(confirmation("APPROVE") + "\n"), terminal, c -> {
      switch (mutation) {
        case "sub" -> c.put("sub", "auth0|unenrolled");
        case "client_id" -> c.put("client_id", OrganizationOperatorFixture.REQUESTER_CLIENT);
        case "role" -> c.putArray(ReviewerConfiguration.ROLE_CLAIM).add("REQUESTER");
        case "scope" -> c.put("scope", "operations.execute");
        case "tenant" -> c.put(ReviewerConfiguration.TENANT_CLAIM, "another-tenant");
        case "expired" -> c.put("exp", 1);
        default -> throw new AssertionError();
      }
    });
    assertThat(text(result)).contains("REVIEW_REJECTED");assertThat(result.exit()).isEqualTo(1);
    assertThat(terminal.toString()).isEmpty();
    assertThat(CanonicalJson.sha256(store.snapshot(id))).isEqualTo(snapshot);
    assertThat(exchanges).hasValue(1);
  }

  @Test void changedSnapshotBeforePreviewDoesNotInviteConfirmation() throws Exception {
    store.deny(id);
    var terminal = new StringWriter();
    var result = run(input("APPROVE", snapshot), new StringReader(confirmation("APPROVE") + "\n"), terminal, c -> {});
    assertThat(result.exit()).isEqualTo(1);assertThat(text(result)).contains("REVIEW_REJECTED");
    assertThat(terminal.toString()).isEmpty();
    assertThat(store.snapshot(id).path("authorization").path("status").asText()).isEqualTo("denied");
  }

  @Test void concurrentDecisionAfterPreviewCannotBeOverwritten() throws Exception {
    var input = input("APPROVE", snapshot);
    AtomicBoolean changed = new AtomicBoolean();
    var answer = new StringReader(confirmation("APPROVE") + "\n") {
      @Override public int read() throws java.io.IOException {
        if (changed.compareAndSet(false, true)) store.deny(id);
        return super.read();
      }
    };
    var result = run(input, answer, new StringWriter(), c -> {});
    assertThat(result.exit()).isEqualTo(1);assertThat(text(result)).contains("REJECTED");
    assertThat(store.snapshot(id).path("authorization").path("status").asText()).isEqualTo("denied");
    assertThat(store.count("tp_operation_delivery_events")).isZero();
  }

  @Test void quarantineAfterPreviewIsRecheckedBeforeReviewCommit() throws Exception {
    AtomicBoolean stopped = new AtomicBoolean();
    var answer = new StringReader(confirmation("APPROVE") + "\n") {
      @Override public int read() throws java.io.IOException {
        if (stopped.compareAndSet(false, true)) store.quarantine();
        return super.read();
      }
    };
    var result = run(input("APPROVE", snapshot), answer, new StringWriter(), c -> {});
    assertThat(result.exit()).isEqualTo(1);
    assertThat(text(result)).containsAnyOf("NOT_READY", "REJECTED");
    assertThat(CanonicalJson.sha256(store.rawSnapshot(id))).isEqualTo(snapshot);
    assertThat(store.count("tp_operation_delivery_events")).isZero();
  }

  @Test void unavailableStorageAfterConfirmationIsUnknownAndAttemptCannotRetry() throws Exception {
    var input = input("APPROVE", snapshot);
    AtomicBoolean stopped = new AtomicBoolean();
    var answer = new StringReader(confirmation("APPROVE") + "\n") {
      @Override public int read() throws java.io.IOException {
        if (stopped.compareAndSet(false, true)) store.close();
        return super.read();
      }
    };
    var result = run(input, answer, new StringWriter(), c -> {});
    assertThat(result.exit()).isEqualTo(1);assertThat(text(result)).contains("UNKNOWN", "\"automaticRetryAllowed\":false");
    assertThat(input.plan.review(store.fixture.reviewerToken(store.fixture.reviewerSubject())).exitCode()).isEqualTo(1);
    assertThat(exchanges).hasValue(1);
  }

  @Test void outputFailureAndExpiredOverallBudgetCannotWrite() throws Exception {
    var output = new java.io.Writer() {
      @Override public void write(char[] c, int o, int n) throws java.io.IOException {throw new java.io.IOException("private-canary");}
      @Override public void flush() {}
      @Override public void close() {}
    };
    var input = input("APPROVE", snapshot);
    var result = run(input, new StringReader(confirmation("APPROVE") + "\n"), output, c -> {});
    assertThat(result.exit()).isEqualTo(1);assertThat(text(result)).contains("REVIEW_REJECTED").doesNotContain("private-canary");
    var expired = OperationReviewSession.run(System.nanoTime() - java.time.Duration.ofSeconds(240).toNanos(),
        input, login(input,c -> {}), new ReviewConfirmation(new StringReader(""), new StringWriter()));
    assertThat(text(expired)).contains("UNKNOWN");
    assertThat(CanonicalJson.sha256(store.snapshot(id))).isEqualTo(snapshot);
  }

  private OperationReviewInput input(String decision, String hash) throws Exception {
    return OperationReviewInput.read(new ByteArrayInputStream(OperationReviewInputTest.frames(
        store.fixture, store.fixture.reviewCommand(id, hash, decision))));
  }
  private String confirmation(String decision) { return decision + " " + id + " " + snapshot; }
  private OperationReviewSession.Outcome run(OperationReviewInput input, java.io.Reader answer,
      java.io.Writer terminal, Consumer<ObjectNode> mutation) {
    return OperationReviewSession.run(System.nanoTime(),input,login(input,mutation),new ReviewConfirmation(answer,terminal));
  }
  private ReviewerLogin login(OperationReviewInput input, Consumer<ObjectNode> mutation) {
    return new ReviewerLogin(input.reviewer, (state,uri) -> "synthetic-code", form -> {
      exchanges.incrementAndGet();
      assertThat(form.keySet()).containsExactlyInAnyOrder("grant_type","client_id","redirect_uri","code","code_verifier");
      byte[] bearer = store.fixture.reviewerTokenWithClaims(mutation);
      try {
        return ReviewerConfiguration.JSON.createObjectNode().put("access_token",new String(bearer,StandardCharsets.US_ASCII))
            .put("token_type","Bearer").put("expires_in",300).put("scope","operations.decide").toString();
      } finally { java.util.Arrays.fill(bearer,(byte)0); }
    },new SecureRandom(),Clock.systemUTC());
  }
  private static String text(OperationReviewSession.Outcome result) { return new String(result.bytes(),StandardCharsets.UTF_8); }
}
