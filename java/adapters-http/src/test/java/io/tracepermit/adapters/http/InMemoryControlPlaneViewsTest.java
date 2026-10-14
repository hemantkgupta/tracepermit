package io.tracepermit.adapters.http;

import io.tracepermit.contracts.JsonSupport;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

final class InMemoryControlPlaneViewsTest {
  private static final Path ROOT = Path.of(System.getProperty("tracepermit.repositoryRoot"));
  private static final String API_EVENT_ID = "018f47a2-f5ce-7a4b-8f54-80e5d3eaf092";
  private static final String API_TRACE_ID = "9f3a2c1e4b5d67890123456789abcdef";
  private static final String WEB_TRACE_ID = "8f3a2c1e4b5d67890123456789abcdef";

  @Test
  void evidenceProjectionReportsLocalIntegrityWithoutClaimingAnAnchor() throws Exception {
    InMemoryControlPlaneViews views = DemoControlPlaneData.load(ROOT).views();
    EvidenceIntegrityPage page = views.evidencePage();
    EvidenceIntegrityDetail detail = views.evidence(API_EVENT_ID);

    assertThat(page.total()).isEqualTo(8);
    assertThat(page.items()).allSatisfy(item -> {
      assertThat(item.valid()).isTrue();
      assertThat(item.anchored()).isFalse();
      assertThat(item.eventCount()).isEqualTo(3);
      assertThat(item.headHash()).startsWith("sha256:");
      assertThat(item.issueCode()).isNull();
    });
    assertThat(detail.events())
        .extracting(EvidenceIntegrityDetail.EvidenceEventView::eventType)
        .containsExactly("action.observed", "policy.evaluated", "execution.not_executed");
    assertThat(detail.events().getFirst().previousEventHash()).isNull();
    assertThat(detail.events().get(1).previousEventHash())
        .isEqualTo(detail.events().getFirst().eventHash());
    assertThatThrownBy(() -> detail.events().clear())
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void policyProjectionNamesTheActiveVersionAndRuntimeProvenance() {
    InMemoryControlPlaneViews views = DemoControlPlaneData.load(ROOT).views();
    PolicyVersionPage page = views.policyPage();
    PolicyVersionDetail detail = views.policy("pol-aws-agentic-demo-001", 1);

    assertThat(page.total()).isOne();
    assertThat(page.items().getFirst().status()).isEqualTo("active");
    assertThat(page.items().getFirst().evaluationCount()).isEqualTo(8);
    assertThat(detail.defaultOutcome()).isEqualTo("require_approval");
    assertThat(detail.runtimeVersions().canonicalSchema()).isEqualTo("action-envelope/1.0");
    assertThat(detail.runtimeVersions().classifier()).isEqualTo("deterministic/0.1.0");
    assertThat(detail.runtimeVersions().adapter()).isEqualTo("java-loopback/v1");
    assertThat(detail.runtimeVersions().executionKey()).isEqualTo("not-used-read-only");
    assertThat(detail.rules()).extracting(PolicyVersionDetail.PolicyRuleView::ruleId)
        .containsExactly(
            "block-rapid-log-read-sequence",
            "sanitize-oversized-log-window",
            "block-agent-ec2-termination",
            "block-web-aws-mutation",
            "allow-cloudwatch-read",
            "allow-signed-scheduled-cloudwatch-read");
  }

  @Test
  void lineageProjectionSeparatesCausalityFromVerifiedDelegation() {
    InMemoryControlPlaneViews views = DemoControlPlaneData.load(ROOT).views();
    LineagePage page = views.lineagePage();
    LineageDetail delegated = views.lineage(API_TRACE_ID);
    LineageDetail causal = views.lineage(WEB_TRACE_ID);

    assertThat(page.total()).isEqualTo(8);
    assertThat(delegated.authoritySummary()).isEqualTo("validated_delegation_claim");
    assertThat(delegated.delegationClaimCount()).isOne();
    assertThat(delegated.hops()).extracting(LineageDetail.LineageNodeView::relation)
        .containsExactly("root", "delegation_claim");
    assertThat(causal.authoritySummary()).isEqualTo("causal_only");
    assertThat(causal.delegationClaimCount()).isZero();
    assertThat(causal.hops()).extracting(LineageDetail.LineageNodeView::relation)
        .containsExactly("root", "causal");
  }

  @Test
  void everyProjectionIsSecretMinimizedAndMissingLookupsReturnNull() throws Exception {
    InMemoryControlPlaneViews views = DemoControlPlaneData.load(ROOT).views();
    String serialized = JsonSupport.MAPPER.writeValueAsString(List.of(
        views.evidencePage(),
        views.evidence(API_EVENT_ID),
        views.policyPage(),
        views.policy("pol-aws-agentic-demo-001", 1),
        views.lineagePage(),
        views.lineage(API_TRACE_ID)));

    assertThat(serialized)
        .doesNotContain(
            "arguments",
            "targetResult",
            "credential",
            "credentialFingerprint",
            "bearer",
            "tokenHash",
            "delegationTokenId",
            "clientClaims",
            "scopes",
            "resources");
    assertThat(views.evidence("00000000-0000-7000-8000-000000000000")).isNull();
    assertThat(views.policy("pol-missing-001", 1)).isNull();
    assertThat(views.lineage("00000000000000000000000000000000")).isNull();
  }
}
