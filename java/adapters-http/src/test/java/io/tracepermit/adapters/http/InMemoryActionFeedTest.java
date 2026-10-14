package io.tracepermit.adapters.http;

import com.fasterxml.jackson.databind.JsonNode;
import io.tracepermit.application.ExecutionResult;
import io.tracepermit.application.SanitizationSummary;
import io.tracepermit.contracts.JsonSupport;
import io.tracepermit.policy.Classification;
import io.tracepermit.policy.PolicyDecision;
import io.tracepermit.policy.PolicyEvaluation;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

final class InMemoryActionFeedTest {
  private static final Path ROOT = Path.of(System.getProperty("tracepermit.repositoryRoot"));

  @Test
  void demoProjectionIsSearchableFilterableAndSecretMinimized() throws Exception {
    InMemoryActionFeed feed = DemoActionFeed.load(ROOT);
    ActionFeedOverview overview = feed.overview();
    assertThat(overview.total()).isEqualTo(8);
    assertThat(overview.allowed()).isEqualTo(2);
    assertThat(overview.blocked()).isEqualTo(3);
    assertThat(overview.sanitized()).isZero();
    assertThat(overview.approvalRequired()).isEqualTo(3);
    assertThat(overview.p95LatencyMs()).isZero();
    assertThat(overview.latencySampleSize()).isZero();
    assertThat(overview.latencySampleScope()).isEqualTo("current_run");

    ActionFeedPage blocked = feed.list("block", null, "chatgpt-web", "terminate", 10);
    assertThat(blocked.total()).isEqualTo(1);
    assertThat(blocked.items().getFirst().target().toolName())
        .isEqualTo("aws.ec2.terminate_instance");
    ActionFeedPage critical = feed.list(null, "critical", null, null, 1);
    assertThat(critical.total()).isEqualTo(2);
    assertThat(critical.items()).hasSize(1);

    String serialized = JsonSupport.MAPPER.writeValueAsString(feed.list(null, null, null, null, 100));
    assertThat(serialized)
        .doesNotContain("arguments", "credentialFingerprint", "clientClaims", "tokenHash")
        .contains("\"simulated\":true");
  }

  @Test
  void duplicatesAndInvalidLimitsAreRejectedAndEmptyFeedHasZeroLatency() {
    InMemoryActionFeed source = DemoActionFeed.load(ROOT);
    ActionFeedEntry entry = source.list(null, null, null, null, 1).items().getFirst();
    InMemoryActionFeed target = new InMemoryActionFeed();
    target.append(entry);
    assertThat(target.overview().p95LatencyMs()).isEqualTo(entry.latencyMs());
    assertThat(target.overview().latencySampleSize()).isEqualTo(1);
    assertThatThrownBy(() -> target.append(entry))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("already exists");
    assertThatThrownBy(() -> target.list(null, null, null, null, 0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(target.find("missing")).isNull();
    assertThat(new InMemoryActionFeed().overview().p95LatencyMs()).isZero();
    assertThat(new InMemoryActionFeed().overview().latencySampleSize()).isZero();
  }

  @Test
  void sanitizedProjectionExplainsInitialAndFinalDecisionWithoutRawArgumentsOrHashes()
      throws Exception {
    JsonNode original = JsonSupport.read(ROOT.resolve("fixtures/chatgpt-aws-read.json"));
    Classification classification =
        new Classification(
            "read",
            "high",
            60,
            "aws.logs.filter_log_events",
            List.of("READ_VERB", "OVERSIZED_RESULT_WINDOW"),
            List.of("POTENTIAL_DATA_EXFILTRATION"),
            "deterministic",
            "0.1.0");
    PolicyEvaluation initial =
        new PolicyEvaluation(
            classification,
            new PolicyDecision(
                "sanitize",
                "LOG_RESULT_WINDOW_REQUIRES_SANITIZATION",
                null,
                "sanitize-oversized-log-window",
                "pol-aws-agentic-demo-001",
                1));
    PolicyEvaluation effective =
        new PolicyEvaluation(
            classification,
            new PolicyDecision(
                "allow",
                "BOUNDED_LOG_READ",
                null,
                "allow-cloudwatch-read",
                "pol-aws-agentic-demo-001",
                1));
    SanitizationSummary summary =
        new SanitizationSummary(
            "bounded-log-read/v1",
            List.of("limit_clamped_to_100"),
            "sha256:" + "a".repeat(64),
            "sha256:" + "b".repeat(64),
            "00000000-0000-7000-8000-000000000099");
    ExecutionResult result =
        new ExecutionResult(
            "succeeded", initial, null, JsonSupport.MAPPER.createObjectNode(), original, effective, summary);
    InMemoryActionFeed feed = new InMemoryActionFeed();

    feed.append(ActionFeedEntry.from(original, result, 2, "succeeded"));

    assertThat(feed.overview().sanitized()).isOne();
    ActionFeedEntry entry = feed.list("sanitize", null, null, null, 10).items().getFirst();
    assertThat(entry.risk().semanticIntents()).containsExactly("POTENTIAL_DATA_EXFILTRATION");
    assertThat(entry.sanitization().finalOutcome()).isEqualTo("allow");
    String serialized = JsonSupport.MAPPER.writeValueAsString(entry);
    assertThat(serialized)
        .contains("bounded-log-read/v1", "limit_clamped_to_100")
        .doesNotContain("originalActionHash", "effectiveActionHash", "logGroupName", "arguments");
  }
}
