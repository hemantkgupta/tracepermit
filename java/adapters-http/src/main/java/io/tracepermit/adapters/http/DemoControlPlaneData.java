package io.tracepermit.adapters.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.tracepermit.application.ExecutionResult;
import io.tracepermit.contracts.CanonicalJson;
import io.tracepermit.contracts.JsonSupport;
import io.tracepermit.execution.EvidenceIntegrityResult;
import io.tracepermit.execution.InMemoryEvidenceLedger;
import io.tracepermit.policy.PolicyEvaluation;
import io.tracepermit.policy.PolicyEvaluator;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

public record DemoControlPlaneData(
    InMemoryActionFeed actionFeed,
    InMemoryControlPlaneViews views,
    JsonNode policy,
    Clock liveClock) {

  private static final Clock RECORDED_CLOCK =
      Clock.fixed(Instant.parse("2026-10-09T08:00:00Z"), ZoneOffset.UTC);

  public static DemoControlPlaneData load(Path repositoryRoot) {
    return load(repositoryRoot, RECORDED_CLOCK);
  }

  public static DemoControlPlaneData load(Path repositoryRoot, Clock liveClock) {
    Objects.requireNonNull(liveClock);
    JsonNode policy = JsonSupport.read(repositoryRoot.resolve("policies/aws-agentic-demo.json"));
    JsonNode web = JsonSupport.read(repositoryRoot.resolve("fixtures/chatgpt-aws-read.json"));
    JsonNode api = JsonSupport.read(repositoryRoot.resolve("fixtures/api-service-aws-modify.json"));
    JsonNode scheduler = JsonSupport.read(repositoryRoot.resolve("fixtures/scheduler-aws-read.json"));
    InMemoryActionFeed feed = new InMemoryActionFeed();
    InMemoryControlPlaneViews views = new InMemoryControlPlaneViews();
    PolicyEvaluator evaluator = new PolicyEvaluator();

    append(feed, views, evaluator, policy, variant(web, "018f47a2-f5ce-7a4b-8f54-80e5d3eaf091",
        "8f3a2c1e4b5d67890123456789abcdef", "2026-10-08T10:42:17.000Z",
        "aws.logs.filter_log_events", "Inspect production application errors", "cloudwatch-log-group"), 412);
    append(feed, views, evaluator, policy, variant(api, "018f47a2-f5ce-7a4b-8f54-80e5d3eaf092",
        "9f3a2c1e4b5d67890123456789abcdef", "2026-10-08T10:41:03.000Z",
        "aws.rds.create_database", "Create a simulated staging database", "database"), 318);
    append(feed, views, evaluator, policy, variant(web, "018f47a2-f5ce-7a4b-8f54-80e5d3eaf094",
        "bf3a2c1e4b5d67890123456789abcdef", "2026-10-08T10:39:28.000Z",
        "aws.ec2.terminate_instance", "Terminate a simulated compute instance", "compute-instance"), 257);
    append(feed, views, evaluator, policy, variant(scheduler, "018f47a2-f5ce-7a4b-8f54-80e5d3eaf093",
        "af3a2c1e4b5d67890123456789abcdef", "2026-10-08T11:00:00.000Z",
        "aws.cost.get_cost_and_usage", "Generate the daily simulated cost report", "cost-report"), 143);
    append(feed, views, evaluator, policy, variant(web, "018f47a2-f5ce-7a4b-8f54-80e5d3eaf095",
        "cf3a2c1e4b5d67890123456789abcdef", "2026-10-08T10:35:44.000Z",
        "aws.ec2.update_security_group", "Modify a simulated security group", "security-group"), 603);
    append(feed, views, evaluator, policy, variant(web, "018f47a2-f5ce-7a4b-8f54-80e5d3eaf096",
        "df3a2c1e4b5d67890123456789abcdef", "2026-10-08T10:33:12.000Z",
        "aws.logs.filter_log_events", "Read bounded staging logs", "cloudwatch-log-group"), 96);
    append(feed, views, evaluator, policy, variant(api, "018f47a2-f5ce-7a4b-8f54-80e5d3eaf097",
        "ef3a2c1e4b5d67890123456789abcdef", "2026-10-08T10:31:48.000Z",
        "aws.iam.rotate_access_key", "Rotate a simulated access key", "access-key"), 730);
    append(feed, views, evaluator, policy, variant(web, "018f47a2-f5ce-7a4b-8f54-80e5d3eaf098",
        "ff3a2c1e4b5d67890123456789abcdef", "2026-10-08T10:29:16.000Z",
        "aws.s3.delete_bucket", "Delete a simulated storage bucket", "bucket"), 601);

    views.appendPolicy(policyView(policy, feed.overview().total()));
    return new DemoControlPlaneData(feed, views, policy.deepCopy(), liveClock);
  }

  public synchronized void recordLive(
      JsonNode event, ExecutionResult result, long latencyMs) {
    Objects.requireNonNull(event);
    Objects.requireNonNull(result);
    String status = switch (result.outcome()) {
      case "succeeded" ->
          result.sanitization() == null ? "succeeded" : "succeeded_sanitized";
      case "block" -> "not_executed";
      case "require_approval" -> "pending_approval";
      default -> "not_executed";
    };
    append(actionFeed, views, event, result, latencyMs, status, liveClock);
    views.replacePolicy(policyView(policy, actionFeed.overview().total()));
  }

  private static void append(
      InMemoryActionFeed feed,
      InMemoryControlPlaneViews views,
      JsonNode event,
      ExecutionResult result,
      long latencyMs,
      String status,
      Clock recordedClock) {
    ActionFeedEntry entry = ActionFeedEntry.from(event, result, latencyMs, status);
    feed.append(entry);
    views.appendEvidence(evidenceView(event, result.evaluation(), status, recordedClock));
    views.appendLineage(lineageView(event));
  }

  private static void append(
      InMemoryActionFeed feed,
      InMemoryControlPlaneViews views,
      PolicyEvaluator evaluator,
      JsonNode policy,
      JsonNode event,
      long latencyMs) {
    PolicyEvaluation evaluation = evaluator.evaluate(event, policy);
    String status = "allow".equals(evaluation.decision().outcome()) ? "succeeded" : "not_executed";
    append(feed, views, event, evaluation, latencyMs, status, RECORDED_CLOCK, false);
  }

  private static void append(
      InMemoryActionFeed feed,
      InMemoryControlPlaneViews views,
      JsonNode event,
      PolicyEvaluation evaluation,
      long latencyMs,
      String status,
      Clock recordedClock,
      boolean currentRunLatencySample) {
    ActionFeedEntry entry = ActionFeedEntry.from(event, evaluation, latencyMs, status);
    if (currentRunLatencySample) {
      feed.append(entry);
    } else {
      feed.appendHistoricalFixture(entry);
    }
    views.appendEvidence(evidenceView(event, evaluation, status, recordedClock));
    views.appendLineage(lineageView(event));
  }

  private static EvidenceIntegrityDetail evidenceView(
      JsonNode event,
      PolicyEvaluation evaluation,
      String executionStatus,
      Clock recordedClock) {
    String eventId = event.path("eventId").asText();
    String traceId = event.path("traceId").asText();
    Instant observedAt = Instant.parse(event.path("observedAt").asText());
    AtomicInteger ids = new AtomicInteger();
    InMemoryEvidenceLedger ledger = new InMemoryEvidenceLedger(
        "urn:tracepermit:evidence:" + eventId,
        recordedClock,
        () -> "evidence-" + eventId + "-" + ids.incrementAndGet());

    ledger.append(boundedEvidence("action.observed", eventId, traceId), observedAt);
    ObjectNode evaluated = boundedEvidence("policy.evaluated", eventId, traceId);
    evaluated.put("policyId", evaluation.decision().policyId());
    evaluated.put("policyVersion", evaluation.decision().policyVersion());
    evaluated.put("decision", evaluation.decision().outcome());
    ledger.append(evaluated, observedAt.plusMillis(1));
    ObjectNode outcome = boundedEvidence(
        "succeeded".equals(executionStatus) ? "execution.succeeded" : "execution.not_executed",
        eventId,
        traceId);
    outcome.put("executionStatus", executionStatus);
    ledger.append(outcome, observedAt.plusMillis(2));

    EvidenceIntegrityResult integrity = ledger.verify(null);
    List<EvidenceIntegrityDetail.EvidenceEventView> projected = ledger.readAll().stream()
        .map(DemoControlPlaneData::evidenceEventView)
        .toList();
    return new EvidenceIntegrityDetail(
        eventId,
        traceId,
        "urn:tracepermit:evidence:" + eventId,
        integrity.valid(),
        integrity.anchored(),
        integrity.eventCount(),
        integrity.headHash(),
        integrity.issueCode(),
        projected,
        true);
  }

  private static ObjectNode boundedEvidence(String eventType, String eventId, String traceId) {
    ObjectNode fields = JsonSupport.MAPPER.createObjectNode();
    fields.put("eventType", eventType);
    fields.put("eventId", eventId);
    fields.put("traceId", traceId);
    return fields;
  }

  private static EvidenceIntegrityDetail.EvidenceEventView evidenceEventView(JsonNode record) {
    JsonNode payload = record.path("payload");
    JsonNode integrity = record.path("integrity");
    return new EvidenceIntegrityDetail.EvidenceEventView(
        payload.path("evidenceEventId").asText(),
        payload.path("sequence").asInt(),
        payload.path("eventType").asText(),
        payload.path("occurredAt").asText(),
        payload.path("recordedAt").asText(),
        integrity.path("previousEventHash").isNull()
            ? null
            : integrity.path("previousEventHash").asText(),
        integrity.path("eventHash").asText());
  }

  private static PolicyVersionDetail policyView(JsonNode policy, int evaluationCount) {
    List<PolicyVersionDetail.PolicyRuleView> rules = new ArrayList<>();
    policy.path("rules").forEach(rule -> rules.add(new PolicyVersionDetail.PolicyRuleView(
        rule.path("id").asText(),
        rule.path("priority").asInt(),
        rule.path("description").asText(),
        rule.path("decision").path("outcome").asText(),
        rule.path("decision").path("reasonCode").asText())));
    return new PolicyVersionDetail(
        policy.path("id").asText(),
        policy.path("version").asInt(),
        policy.path("name").asText(),
        policy.path("description").asText(),
        policy.path("enabled").asBoolean() ? "active" : "inactive",
        policy.path("schemaVersion").asText(),
        CanonicalJson.sha256(policy),
        evaluationCount,
        "2026-10-08T00:00:00.000Z",
        policy.path("defaultDecision").path("outcome").asText(),
        new PolicyVersionDetail.RuntimeVersions(
            "action-envelope/1.0",
            "deterministic/0.1.0",
            "java-loopback/v1",
            "not-used-read-only"),
        rules,
        true);
  }

  private static LineageDetail lineageView(JsonNode event) {
    JsonNode lineage = event.path("lineage");
    Set<String> validatedDelegationClaims = new HashSet<>();
    lineage.path("delegations").forEach(grant -> {
      if ("verified".equals(grant.path("verification").asText())) {
        validatedDelegationClaims.add(grant.path("tokenId").asText());
      }
    });
    List<LineageDetail.LineageNodeView> hops = new ArrayList<>();
    int delegationClaimCount = 0;
    for (JsonNode hop : lineage.path("hops")) {
      boolean delegationClaim = hop.hasNonNull("delegationTokenId")
          && validatedDelegationClaims.contains(hop.path("delegationTokenId").asText());
      if (delegationClaim) delegationClaimCount++;
      hops.add(new LineageDetail.LineageNodeView(
          hop.path("hopId").asText(),
          hop.hasNonNull("parentHopId") ? hop.path("parentHopId").asText() : null,
          hop.path("kind").asText(),
          hop.path("subjectId").asText(),
          hop.path("assurance").asText(),
          hop.path("source").asText(),
          hop.hasNonNull("parentHopId")
              ? (delegationClaim ? "delegation_claim" : "causal")
              : "root"));
    }
    JsonNode action = event.path("action");
    return new LineageDetail(
        event.path("eventId").asText(),
        event.path("traceId").asText(),
        event.path("observedAt").asText(),
        event.path("declaredIntent").path("goal").asText(action.path("toolName").asText()),
        lineage.path("rootPrincipalId").asText(),
        hops.size(),
        delegationClaimCount,
        delegationClaimCount > 0 ? "validated_delegation_claim" : "causal_only",
        action.path("target").path("system").asText(),
        action.path("toolName").asText(),
        hops,
        true);
  }

  private static JsonNode variant(
      JsonNode source,
      String eventId,
      String traceId,
      String observedAt,
      String toolName,
      String intent,
      String resourceType) {
    ObjectNode event = source.deepCopy();
    event.put("eventId", eventId);
    event.put("traceId", traceId);
    event.put("observedAt", observedAt);
    ((ObjectNode) event.path("declaredIntent")).put("goal", intent);
    ((ObjectNode) event.path("action")).put("toolName", toolName);
    ((ObjectNode) event.path("action").path("target")).put("resourceType", resourceType);
    Instant observed = Instant.parse(observedAt);
    event.path("lineage").path("delegations").forEach(grant -> {
      ObjectNode mutableGrant = (ObjectNode) grant;
      mutableGrant.put("traceId", traceId);
      mutableGrant.put("issuedAt", observed.minusSeconds(10).toString());
      mutableGrant.put("expiresAt", observed.plusSeconds(300).toString());
    });
    return event;
  }
}
