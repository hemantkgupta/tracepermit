package io.tracepermit.adapters.remote;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.tracepermit.application.ActionEnforcer;
import io.tracepermit.application.ActionSanitizer;
import io.tracepermit.application.ActionSequenceStore;
import io.tracepermit.application.BoundedLogReadSanitizer;
import io.tracepermit.application.ExecutionResult;
import io.tracepermit.application.InMemoryActionSequenceStore;
import io.tracepermit.application.SecureExecutionService;
import io.tracepermit.application.SecurityReadiness;
import io.tracepermit.application.TargetExecutionException;
import io.tracepermit.application.TenantActionEnforcer;
import io.tracepermit.application.TrustedTenantContext;
import io.tracepermit.contracts.JsonSupport;
import io.tracepermit.execution.InMemoryEvidenceLedger;
import io.tracepermit.execution.InMemoryExecutionLedger;
import io.tracepermit.identity.AuthenticatedConnector;
import io.tracepermit.identity.AuthenticationException;
import io.tracepermit.identity.ConnectorOrigin;
import io.tracepermit.identity.ConnectorScope;
import io.tracepermit.identity.VerifiedSourceEvidence;
import io.tracepermit.policy.Classification;
import io.tracepermit.policy.PolicyDecision;
import io.tracepermit.policy.PolicyEvaluation;
import io.tracepermit.policy.PolicyEvaluator;
import io.tracepermit.security.ExecutionPermitAuthority;
import io.tracepermit.security.ExecutionPermitException;
import io.tracepermit.security.InMemoryPermitConsumptionStore;
import io.tracepermit.simulator.StatefulTargetSimulator;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

final class EnforcingMcpDispatcherTest {
  private static final Path ROOT = Path.of(System.getProperty("tracepermit.repositoryRoot"));
  private static final Clock CLOCK = Clock.fixed(RemoteTestSupport.NOW, ZoneOffset.UTC);

  @Test
  void allowedReadTraversesPolicyPermitSimulatorAndRedactedEvidence() throws Exception {
    InMemoryEvidenceLedger evidence = evidence();
    AtomicInteger targets = new AtomicInteger();
    StatefulTargetSimulator simulator = new StatefulTargetSimulator();
    EnforcingMcpDispatcher dispatcher =
        dispatcher(
            service(
                evidence,
                action -> {
                  targets.incrementAndGet();
                  return simulator.invoke(action);
                }));

    RemoteMcpResponse response =
        dispatcher.dispatch(
            call(
                1,
                EnforcingMcpDispatcher.FILTER_LOG_EVENTS,
                "{\"logGroupName\":\"/app/prod\",\"filterPattern\":\"ERROR\",\"limit\":20}"),
            RemoteTestSupport.connector(RemoteTestSupport.DISCOVER, RemoteTestSupport.READ));

    JsonNode result = content(response);
    assertThat(response.status()).isEqualTo(200);
    assertThat(response.body().path("result").has("isError")).isFalse();
    assertThat(result.path("evaluation").path("decision").path("outcome").asText())
        .isEqualTo("allow");
    assertThat(result.path("execution").path("status").asText()).isEqualTo("executed");
    assertThat(result.path("execution").path("result").path("matchCount").asInt())
        .isEqualTo(2);
    assertThat(targets).hasValue(1);
    assertThat(evidence.readAll())
        .extracting(item -> item.path("payload").path("eventType").asText())
        .containsExactly(
            "action.observed",
            "policy.evaluated",
            "authorization.issued",
            "target.dispatched",
            "target.succeeded");
    assertThat(evidence.verify(null).valid()).isTrue();
    assertThat(evidence.readAll().toString())
        .doesNotContain(
            "logGroupName",
            "filterPattern",
            "ERROR",
            "events",
            "sha256:" + "a".repeat(64),
            "Bearer");
    assertThat(response.body().toString())
        .doesNotContain(
            "tenant:acme",
            "chatgpt-connector",
            "oidc-key-001",
            "sha256:" + "a".repeat(64));
  }

  @Test
  void openAiCallMetadataIsAcceptedButNeverTrustedOrPersisted() throws Exception {
    AtomicReference<JsonNode> captured = new AtomicReference<>();
    EnforcingMcpDispatcher dispatcher =
        dispatcher(
            event -> {
              captured.set(event.deepCopy());
              return result(
                  "succeeded",
                  "allow",
                  JsonSupport.MAPPER.createObjectNode().put("mode", "simulated"));
            });
    JsonNode request =
        JsonSupport.MAPPER.readTree(
            """
            {"jsonrpc":"2.0","id":2,"method":"tools/call","params":{
              "_meta":{"progressToken":3,"openai/subject":"untrusted-subject"},
              "name":"aws.logs.filter_log_events",
              "arguments":{"logGroupName":"/app/staging","filterPattern":"ERROR","limit":2}
            }}
            """);

    JsonNode result =
        content(
            dispatcher.dispatch(
                request, RemoteTestSupport.connector(RemoteTestSupport.READ)));

    assertThat(result.path("evaluation").path("decision").path("outcome").asText())
        .isEqualTo("allow");
    assertThat(captured.get().toString())
        .doesNotContain("progressToken", "openai/subject", "untrusted-subject");
  }

  @Test
  void knownDestructiveSimulationReachesPolicyAndNeverReachesTarget() throws Exception {
    AtomicInteger targets = new AtomicInteger();
    InMemoryEvidenceLedger evidence = evidence();
    EnforcingMcpDispatcher dispatcher =
        dispatcher(
            service(
                evidence,
                action -> {
                  targets.incrementAndGet();
                  throw new AssertionError("blocked action reached target");
                }));

    JsonNode result =
        content(
            dispatcher.dispatch(
                call(
                    2,
                    EnforcingMcpDispatcher.TERMINATE_INSTANCES,
                    "{\"instanceIds\":[\"i-simulated-demo-1\"],\"region\":\"us-east-1\"}"),
                RemoteTestSupport.connector(RemoteTestSupport.DISCOVER, RemoteTestSupport.READ)));

    assertThat(result.path("evaluation").path("classification").path("actionClass").asText())
        .isEqualTo("destructive");
    assertThat(result.path("evaluation").path("decision").path("reasonCode").asText())
        .isEqualTo("AGENT_EC2_TERMINATION_BLOCKED");
    assertThat(result.path("execution").path("status").asText()).isEqualTo("denied");
    assertThat(targets).hasValue(0);
    assertThat(evidence.readAll())
        .extracting(item -> item.path("payload").path("eventType").asText())
        .containsExactly("action.observed", "policy.evaluated", "authorization.denied");
  }

  @Test
  void oversizedReadIsSanitizedReevaluatedAndOnlyThenDispatched() throws Exception {
    AtomicInteger targets = new AtomicInteger();
    AtomicReference<JsonNode> dispatched = new AtomicReference<>();
    EnforcingMcpDispatcher dispatcher =
        dispatcher(
            semanticService(
                evidence(),
                action -> {
                  targets.incrementAndGet();
                  dispatched.set(action.deepCopy());
                  return JsonSupport.MAPPER.createObjectNode().put("mode", "simulated");
                },
                new BoundedLogReadSanitizer(),
                ActionSequenceStore.DISABLED));

    JsonNode result =
        content(
            dispatcher.dispatch(
                call(
                    40,
                    EnforcingMcpDispatcher.FILTER_LOG_EVENTS,
                    "{\"logGroupName\":\"/app/prod\",\"filterPattern\":\"ERROR\",\"limit\":500}"),
                RemoteTestSupport.connector(RemoteTestSupport.READ)));

    assertThat(result.path("evaluation").path("decision").path("outcome").asText())
        .isEqualTo("sanitize");
    assertThat(result.path("execution").path("status").asText())
        .isEqualTo("executed_sanitized");
    assertThat(result.path("execution").path("sanitization").path("finalDecision").asText())
        .isEqualTo("allow");
    assertThat(result.path("execution").path("sanitization").path("changes"))
        .containsExactly(JsonSupport.MAPPER.getNodeFactory().textNode("limit_clamped_to_100"));
    assertThat(dispatched.get().path("arguments").path("limit").asInt()).isEqualTo(100);
    assertThat(targets).hasValue(1);
  }

  @Test
  void rapidReadSequenceIsBlockedBeforeThirdDispatch() throws Exception {
    AtomicInteger targets = new AtomicInteger();
    EnforcingMcpDispatcher dispatcher =
        dispatcher(
            semanticService(
                evidence(),
                action -> {
                  targets.incrementAndGet();
                  return JsonSupport.MAPPER.createObjectNode().put("mode", "simulated");
                },
                new BoundedLogReadSanitizer(),
                InMemoryActionSequenceStore.oneMinute()));

    for (int id = 50; id < 52; id++) {
      JsonNode result =
          content(
              dispatcher.dispatch(
                  call(
                      id,
                      EnforcingMcpDispatcher.FILTER_LOG_EVENTS,
                      "{\"logGroupName\":\"/app/prod\",\"limit\":20}"),
                  RemoteTestSupport.connector(RemoteTestSupport.READ)));
      assertThat(result.path("execution").path("status").asText()).isEqualTo("executed");
    }
    JsonNode third =
        content(
            dispatcher.dispatch(
                call(
                    52,
                    EnforcingMcpDispatcher.FILTER_LOG_EVENTS,
                    "{\"logGroupName\":\"/app/prod\",\"limit\":20}"),
                RemoteTestSupport.connector(RemoteTestSupport.READ)));

    assertThat(third.path("evaluation").path("classification").path("signals"))
        .contains(JsonSupport.MAPPER.getNodeFactory().textNode("RAPID_REPEATED_READ_SEQUENCE"));
    assertThat(third.path("evaluation").path("decision").path("reasonCode").asText())
        .isEqualTo("RAPID_LOG_EXTRACTION_SEQUENCE_BLOCKED");
    assertThat(third.path("execution").path("status").asText()).isEqualTo("denied");
    assertThat(targets).hasValue(2);
  }

  @Test
  void discoveryAndInvocationScopesRemainIndependent() throws Exception {
    AtomicInteger enforcements = new AtomicInteger();
    EnforcingMcpDispatcher dispatcher =
        dispatcher(
            event -> {
              enforcements.incrementAndGet();
              return result("succeeded", "allow", JsonSupport.MAPPER.createObjectNode());
            });
    JsonNode list =
        JsonSupport.MAPPER.readTree(
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\",\"params\":{}}");

    RemoteMcpResponse listed =
        dispatcher.dispatch(list, RemoteTestSupport.connector(RemoteTestSupport.DISCOVER));
    assertThat(listed.body().path("result").path("tools")).hasSize(2);
    assertThat(listed.body().toString())
        .contains("strictly simulated", EnforcingMcpDispatcher.TERMINATE_INSTANCES)
        .doesNotContain(EnforcingMcpDispatcher.GET_PROFILE, "securitySchemes", "openai/profile");
    assertThatThrownBy(
            () ->
                dispatcher.dispatch(
                    call(
                        2,
                        EnforcingMcpDispatcher.FILTER_LOG_EVENTS,
                        "{\"logGroupName\":\"/app/prod\"}"),
                    RemoteTestSupport.connector(RemoteTestSupport.DISCOVER)))
        .isInstanceOf(AuthenticationException.class)
        .extracting(error -> ((AuthenticationException) error).code())
        .isEqualTo("AUTHORIZATION_DENIED");
    assertThatThrownBy(
            () -> dispatcher.dispatch(list, RemoteTestSupport.connector(RemoteTestSupport.READ)))
        .isInstanceOf(AuthenticationException.class);
    assertThat(enforcements).hasValue(0);
  }

  @Test
  void authenticatedProfileIsStableOpaqueAndCannotSelectAnotherSubject() throws Exception {
    AtomicInteger enforcements = new AtomicInteger();
    EnforcingMcpDispatcher dispatcher =
        hostedDispatcher(
            event -> {
              enforcements.incrementAndGet();
              return result("succeeded", "allow", JsonSupport.MAPPER.createObjectNode());
            });

    RemoteMcpResponse first =
        dispatcher.dispatch(
            call(70, EnforcingMcpDispatcher.GET_PROFILE, "{}"),
            RemoteTestSupport.connector(RemoteTestSupport.DISCOVER));
    RemoteMcpResponse second =
        dispatcher.dispatch(
            call(71, EnforcingMcpDispatcher.GET_PROFILE, "{}"),
            RemoteTestSupport.connector(RemoteTestSupport.DISCOVER));
    JsonNode firstProfile = first.body().path("result").path("structuredContent");
    JsonNode secondProfile = second.body().path("result").path("structuredContent");

    assertThat(firstProfile.path("id").asText()).startsWith("prf_").hasSize(36);
    assertThat(firstProfile.path("id").asText()).isEqualTo(secondProfile.path("id").asText());
    assertThat(firstProfile.path("name").asText()).isEqualTo("Alice Example");
    assertThat(first.body().path("result").path("content").get(0).path("text").asText())
        .contains(firstProfile.path("id").asText());
    assertThat(first.body().toString())
        .doesNotContain(RemoteTestSupport.ISSUER, "alice@example.invalid", "tenant:acme");

    JsonNode list =
        JsonSupport.MAPPER.readTree(
            "{\"jsonrpc\":\"2.0\",\"id\":73,\"method\":\"tools/list\",\"params\":{}}");
    JsonNode tools =
        dispatcher
            .dispatch(list, RemoteTestSupport.connector(RemoteTestSupport.DISCOVER))
            .body()
            .path("result")
            .path("tools");
    assertThat(tools).hasSize(3);
    assertThat(tools.toString())
        .contains(EnforcingMcpDispatcher.GET_PROFILE, "securitySchemes", "openai/profile");

    RemoteMcpResponse injection =
        dispatcher.dispatch(
            call(72, EnforcingMcpDispatcher.GET_PROFILE, "{\"userId\":\"admin\"}"),
            RemoteTestSupport.connector(RemoteTestSupport.DISCOVER));
    assertThat(injection.body().path("error").path("data").path("code").asText())
        .isEqualTo("INVALID_TOOL_ARGUMENTS");
    assertThat(enforcements).hasValue(0);
  }

  @Test
  void hostedProfilePublishesOnlyStaticHandshakeAndOAuthToolMetadataWithoutIdentity()
      throws Exception {
    AtomicInteger enforcements = new AtomicInteger();
    EnforcingMcpDispatcher hosted =
        hostedDispatcher(
            event -> {
              enforcements.incrementAndGet();
              return result("succeeded", "allow", JsonSupport.MAPPER.createObjectNode());
            });
    JsonNode list =
        JsonSupport.MAPPER.readTree(
            "{\"jsonrpc\":\"2.0\",\"id\":74,\"method\":\"tools/list\",\"params\":{}}");

    RemoteMcpResponse response = hosted.dispatchPublic(list).orElseThrow();
    JsonNode tools = response.body().path("result").path("tools");

    assertThat(tools).hasSize(3);
    assertThat(tools.toString())
        .contains(
            EnforcingMcpDispatcher.FILTER_LOG_EVENTS,
            EnforcingMcpDispatcher.TERMINATE_INSTANCES,
            EnforcingMcpDispatcher.GET_PROFILE,
            "securitySchemes",
            "openai/profile")
        .doesNotContain("Alice Example", RemoteTestSupport.ISSUER, "tenant:acme");
    assertThat(hosted.dispatchPublic(call(75, EnforcingMcpDispatcher.GET_PROFILE, "{}")))
        .isEmpty();
    assertThat(dispatcher(event -> result("succeeded", "allow", null)).dispatchPublic(list))
        .isEmpty();

    JsonNode initialize =
        JsonSupport.MAPPER.readTree(
            """
            {"jsonrpc":"2.0","id":76,"method":"initialize","params":{
              "protocolVersion":"2025-06-18","capabilities":{},
              "clientInfo":{"name":"hosted-test","version":"1"}}}
            """);
    RemoteMcpResponse initialized = hosted.dispatchPublic(initialize).orElseThrow();
    assertThat(initialized.body().path("result").path("serverInfo").path("name").asText())
        .isEqualTo("TracePermit");

    JsonNode discover =
        JsonSupport.MAPPER.readTree(
            """
            {"jsonrpc":"2.0","id":"discover-76","method":"server/discover","params":{
              "_meta":{"io.modelcontextprotocol/protocolVersion":"2026-07-28"}}}
            """);
    RemoteMcpResponse discovered = hosted.dispatchPublic(discover).orElseThrow();
    assertThat(discovered.body().path("result").path("supportedVersions").toString())
        .contains("2026-07-28");
    assertThat(discovered.body().path("result").path("instructions").asText())
        .contains("independently authorized", "policy enforced");

    JsonNode ping =
        JsonSupport.MAPPER.readTree(
            "{\"jsonrpc\":\"2.0\",\"id\":77,\"method\":\"ping\",\"params\":{}}");
    assertThat(hosted.dispatchPublic(ping).orElseThrow().status()).isEqualTo(200);

    JsonNode notification =
        JsonSupport.MAPPER.readTree(
            "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\",\"params\":{}}");
    assertThat(hosted.dispatchPublic(notification).orElseThrow().status()).isEqualTo(204);

    JsonNode unknown =
        JsonSupport.MAPPER.readTree(
            "{\"jsonrpc\":\"2.0\",\"id\":78,\"method\":\"unknown\",\"params\":{}}");
    assertThat(
            new HandshakeOnlyMcpDispatcher()
                .dispatchPublic(unknown)
                .orElseThrow()
                .body()
                .path("error")
                .path("data")
                .path("code")
                .asText())
        .isEqualTo("METHOD_NOT_FOUND");
    assertThat(enforcements).hasValue(0);
  }

  @Test
  void adapterOwnsIdentityTargetLineageAndDefaults() throws Exception {
    AtomicReference<JsonNode> captured = new AtomicReference<>();
    EnforcingMcpDispatcher dispatcher =
        dispatcher(
            event -> {
              captured.set(event.deepCopy());
              return result(
                  "succeeded",
                  "allow",
                  JsonSupport.MAPPER.createObjectNode().put("mode", "simulated"));
            });
    dispatcher.dispatch(
        call(
            3,
            EnforcingMcpDispatcher.FILTER_LOG_EVENTS,
            "{\"logGroupName\":\"/app/staging\"}"),
        RemoteTestSupport.connector(RemoteTestSupport.READ));

    JsonNode event = captured.get();
    assertThat(event.path("identity").path("subject").path("id").asText())
        .isEqualTo(RemoteTestSupport.ISSUER + "#user:alice@example.invalid");
    assertThat(event.path("identity").path("actor").path("id").asText())
        .isEqualTo("chatgpt-connector");
    assertThat(event.path("trigger").path("origin").asText()).isEqualTo("chatgpt-web");
    assertThat(event.path("action").path("target").path("accountId").asText())
        .isEqualTo("simulated-123");
    assertThat(event.path("action").path("target").path("environment").asText())
        .isEqualTo("staging");
    assertThat(event.path("action").path("arguments").path("filterPattern").asText())
        .isEmpty();
    assertThat(event.path("action").path("arguments").path("limit").asInt()).isEqualTo(20);
    assertThat(event.path("lineage").path("delegations")).isEmpty();
    assertThat(event.path("clientClaims").path("transport").asText())
        .isEqualTo("direct-tls-mcp");

    dispatcher.dispatch(
        call(
            4,
            EnforcingMcpDispatcher.TERMINATE_INSTANCES,
            "{\"instanceIds\":[\"i-simulated-demo-2\"]}"),
        RemoteTestSupport.connector(RemoteTestSupport.READ));
    assertThat(captured.get().path("action").path("arguments").path("region").asText())
        .isEqualTo("us-east-1");
  }

  @Test
  void postEnforcementObserverCannotChangeTheDecisionOrReceiveRejectedCalls() throws Exception {
    AtomicInteger observed = new AtomicInteger();
    AtomicReference<JsonNode> event = new AtomicReference<>();
    AtomicReference<ExecutionResult> result = new AtomicReference<>();
    AtomicReference<Long> latency = new AtomicReference<>();
    EnforcingMcpDispatcher dispatcher =
        new EnforcingMcpDispatcher(
            action -> result("succeeded", "allow", JsonSupport.MAPPER.createObjectNode()),
            CLOCK,
            () -> "00000000-0000-7000-8000-000000000123",
            () -> "12".repeat(16),
            (candidate, outcome, latencyMs) -> {
              observed.incrementAndGet();
              event.set(candidate);
              result.set(outcome);
              latency.set(latencyMs);
              throw new IllegalStateException("projection unavailable");
            });

    RemoteMcpResponse allowed =
        dispatcher.dispatch(
            call(
                30,
                EnforcingMcpDispatcher.FILTER_LOG_EVENTS,
                "{\"logGroupName\":\"/app/staging\"}"),
            RemoteTestSupport.connector(RemoteTestSupport.READ));
    RemoteMcpResponse rejected =
        dispatcher.dispatch(
            call(31, "unknown.tool", "{}"),
            RemoteTestSupport.connector(RemoteTestSupport.READ));

    assertThat(content(allowed).path("execution").path("status").asText())
        .isEqualTo("executed");
    assertThat(rejected.body().path("error").path("data").path("code").asText())
        .isEqualTo("TOOL_NOT_FOUND");
    assertThat(observed).hasValue(1);
    assertThat(event.get().path("eventId").asText())
        .isEqualTo("00000000-0000-7000-8000-000000000123");
    assertThat(result.get().outcome()).isEqualTo("succeeded");
    assertThat(latency.get()).isNotNegative();
  }

  @Test
  void verifiedTenantContextAndSenderEvidenceRemainServerOwned() throws Exception {
    AtomicReference<TrustedTenantContext> tenant = new AtomicReference<>();
    AtomicReference<JsonNode> captured = new AtomicReference<>();
    EnforcingMcpDispatcher dispatcher =
        dispatcher(
            (TenantActionEnforcer)
                (context, event) -> {
                  tenant.set(context);
                  captured.set(event.deepCopy());
                  return result(
                      "succeeded", "allow", JsonSupport.MAPPER.createObjectNode().put("safe", true));
                });
    RemoteTestSupport.MutualTlsMaterial tls = RemoteTestSupport.mutualTlsMaterial();

    dispatcher.dispatch(
        call(
            5,
            EnforcingMcpDispatcher.FILTER_LOG_EVENTS,
            "{\"logGroupName\":\"/app/prod\"}"),
        RemoteTestSupport.senderConstrainedConnector(
            tls.clientCertificate(), RemoteTestSupport.READ));

    assertThat(tenant.get().tenant().value()).isEqualTo("tenant:acme");
    assertThat(captured.get().path("identity").path("evidence")).hasSize(3);
    JsonNode sender = captured.get().path("identity").path("evidence").get(1);
    assertThat(sender.path("kind").asText()).isEqualTo("mtls_certificate");
    assertThat(sender.path("assurance").asText()).isEqualTo("authenticated");
    assertThat(sender.path("credentialFingerprint").asText())
        .matches("^sha256:[a-f0-9]{64}$");
    assertThat(captured.get().toString()).doesNotContain("BEGIN CERTIFICATE", "x5t#S256");
  }

  @Test
  void malformedUnknownAndAuthorityInjectionNeverReachEnforcement() throws Exception {
    AtomicInteger enforcements = new AtomicInteger();
    EnforcingMcpDispatcher dispatcher =
        dispatcher(
            event -> {
              enforcements.incrementAndGet();
              return result("succeeded", "allow", JsonSupport.MAPPER.nullNode());
            });
    List<JsonNode> invalid =
        List.of(
            call(1, "unknown.tool", "{}"),
            call(2, EnforcingMcpDispatcher.FILTER_LOG_EVENTS, "{}"),
            call(
                3,
                EnforcingMcpDispatcher.FILTER_LOG_EVENTS,
                "{\"logGroupName\":\"/app/prod\",\"identity\":{\"authenticated\":true}}"),
            call(
                4,
                EnforcingMcpDispatcher.FILTER_LOG_EVENTS,
                "{\"logGroupName\":\"/other\"}"),
            call(
                5,
                EnforcingMcpDispatcher.FILTER_LOG_EVENTS,
                "{\"logGroupName\":\"/app/prod\",\"limit\":0}"),
            call(
                6,
                EnforcingMcpDispatcher.FILTER_LOG_EVENTS,
                "{\"logGroupName\":\"/app/prod\",\"filterPattern\":1}"),
            call(
                7,
                EnforcingMcpDispatcher.TERMINATE_INSTANCES,
                "{\"instanceIds\":[]}"),
            call(
                8,
                EnforcingMcpDispatcher.TERMINATE_INSTANCES,
                "{\"instanceIds\":[\"i-real-1\"]}"),
            call(
                9,
                EnforcingMcpDispatcher.TERMINATE_INSTANCES,
                "{\"instanceIds\":[\"i-simulated-a\"],\"region\":\"eu-west-1\"}"),
            JsonSupport.MAPPER.readTree(
                "{\"jsonrpc\":\"2.0\",\"id\":10,\"method\":\"tools/call\",\"params\":{\"name\":\"aws.logs.filter_log_events\"}}"),
            JsonSupport.MAPPER.readTree(
                "{\"jsonrpc\":\"2.0\",\"id\":11,\"method\":\"tools/call\",\"params\":{\"name\":\"aws.logs.filter_log_events\",\"arguments\":{},\"approved\":true}}"),
            JsonSupport.MAPPER.readTree(
                "{\"jsonrpc\":\"2.0\",\"id\":12,\"method\":\"tools/call\",\"params\":{\"name\":\"aws.logs.filter_log_events\",\"arguments\":{\"logGroupName\":\"/app/prod\"},\"_meta\":\"untrusted\"}}"));

    for (JsonNode request : invalid) {
      RemoteMcpResponse response =
          dispatcher.dispatch(request, RemoteTestSupport.connector(RemoteTestSupport.READ));
      assertThat(response.body().path("error").path("data").path("code").asText())
          .isIn("TOOL_NOT_FOUND", "INVALID_TOOL_ARGUMENTS");
    }
    assertThat(
            dispatcher
                .dispatch(
                    call(
                        12,
                        EnforcingMcpDispatcher.FILTER_LOG_EVENTS,
                        "{\"logGroupName\":\"/app/prod\"}"),
                    RemoteTestSupport.connector(ConnectorOrigin.CLI, RemoteTestSupport.READ))
                .body()
                .path("error")
                .path("data")
                .path("code")
                .asText())
        .isEqualTo("REMOTE_CONNECTOR_ORIGIN_NOT_SUPPORTED");
    assertThat(enforcements).hasValue(0);
  }

  @Test
  void identifierArgumentAndNormalizationBoundariesFailClosed() throws Exception {
    AtomicInteger enforcements = new AtomicInteger();
    EnforcingMcpDispatcher dispatcher =
        dispatcher(
            event -> {
              enforcements.incrementAndGet();
              return result("succeeded", "allow", JsonSupport.MAPPER.nullNode());
            });
    List<JsonNode> invalid = new ArrayList<>();
    invalid.add(
        JsonSupport.MAPPER.readTree(
            "{\"jsonrpc\":\"2.0\",\"method\":\"tools/call\",\"params\":{\"name\":\"aws.logs.filter_log_events\",\"arguments\":{\"logGroupName\":\"/app/prod\"}}}"));
    invalid.add(
        JsonSupport.MAPPER.readTree(
            "{\"jsonrpc\":\"2.0\",\"id\":true,\"method\":\"tools/call\",\"params\":{\"name\":\"aws.logs.filter_log_events\",\"arguments\":{\"logGroupName\":\"/app/prod\"}}}"));
    invalid.add(
        call(
            12,
            EnforcingMcpDispatcher.FILTER_LOG_EVENTS,
            "{\"logGroupName\":\"/app/prod\",\"filterPattern\":\""
                + "x".repeat(129)
                + "\"}"));
    invalid.add(
        call(
            13,
            EnforcingMcpDispatcher.FILTER_LOG_EVENTS,
            "{\"logGroupName\":\"/app/prod\",\"limit\":1.5}"));
    invalid.add(
        call(
            14,
            EnforcingMcpDispatcher.TERMINATE_INSTANCES,
            "{\"instanceIds\":["
                + String.join(",", Collections.nCopies(11, "\"i-simulated-a\""))
                + "]}"));
    invalid.add(
        call(
            15,
            EnforcingMcpDispatcher.TERMINATE_INSTANCES,
            "{\"instanceIds\":[\"i-simulated-a\"],\"target\":\"real\"}"));

    for (JsonNode request : invalid) {
      assertThat(
              dispatcher
                  .dispatch(request, RemoteTestSupport.connector(RemoteTestSupport.READ))
                  .body()
                  .path("error")
                  .path("data")
                  .path("code")
                  .asText())
          .isIn("INVALID_REQUEST", "INVALID_TOOL_ARGUMENTS");
    }
    EnforcingMcpDispatcher invalidFactory =
        new EnforcingMcpDispatcher(
            event -> {
              enforcements.incrementAndGet();
              return result("succeeded", "allow", JsonSupport.MAPPER.nullNode());
            },
            CLOCK,
            () -> "not-a-uuid",
            () -> "not-a-trace");
    assertThat(
            invalidFactory
                .dispatch(
                    call(
                        16,
                        EnforcingMcpDispatcher.FILTER_LOG_EVENTS,
                        "{\"logGroupName\":\"/app/prod\"}"),
                    RemoteTestSupport.connector(RemoteTestSupport.READ))
                .body()
                .path("error")
                .path("data")
                .path("code")
                .asText())
        .isEqualTo("INVALID_ACTION");
    assertThat(enforcements).hasValue(0);
  }

  @Test
  void policyOutcomesAndFailuresUseBoundedToolResults() throws Exception {
    for (String outcome : List.of("block", "require_approval", "sanitize")) {
      EnforcingMcpDispatcher dispatcher =
          dispatcher(event -> result(outcome, outcome, JsonSupport.MAPPER.nullNode()));
      JsonNode response =
          dispatcher
              .dispatch(
                  call(
                      20,
                      EnforcingMcpDispatcher.FILTER_LOG_EVENTS,
                      "{\"logGroupName\":\"/app/prod\"}"),
                  RemoteTestSupport.connector(RemoteTestSupport.READ))
              .body();
      assertThat(response.path("result").path("isError").asBoolean()).isTrue();
      assertThat(content(response).path("execution").path("status").asText())
          .isIn("denied", "pending_approval", "not_executed");
    }

    List<ActionEnforcer> failures =
        List.of(
            event -> {
              throw new TargetExecutionException("SIMULATOR_FAILURE", "target-secret");
            },
            event -> {
              throw new ExecutionPermitException("PERMIT_SECRET", "permit-secret");
            },
            event -> {
              throw new IllegalStateException("SECURITY_DEPENDENCY_UNAVAILABLE");
            },
            event -> {
              throw new IllegalStateException("EXECUTION_ALREADY_IN_PROGRESS");
            },
            event -> {
              throw new IllegalStateException("EXECUTION_OUTCOME_UNKNOWN");
            },
            event -> {
              throw new IllegalStateException("EXECUTION_PREVIOUSLY_FAILED");
            },
            event -> {
              throw new IllegalStateException((String) null);
            },
            event -> {
              throw new IllegalStateException("internal-secret");
            },
            event -> {
              throw new RuntimeException("runtime-secret");
            });
    List<String> statuses =
        List.of(
            "failed",
            "not_executed",
            "not_executed",
            "executing",
            "unknown",
            "failed",
            "unknown",
            "unknown",
            "unknown");
    for (int index = 0; index < failures.size(); index++) {
      JsonNode response =
          dispatcher(failures.get(index))
              .dispatch(
                  call(
                      21,
                      EnforcingMcpDispatcher.FILTER_LOG_EVENTS,
                      "{\"logGroupName\":\"/app/prod\"}"),
                  RemoteTestSupport.connector(RemoteTestSupport.READ))
              .body();
      assertThat(response.path("result").path("isError").asBoolean()).isTrue();
      assertThat(content(response).path("execution").path("status").asText())
          .isEqualTo(statuses.get(index));
      assertThat(response.toString())
          .doesNotContain(
              "target-secret",
              "permit-secret",
              "PERMIT_SECRET",
              "internal-secret",
              "runtime-secret");
    }
  }

  @Test
  void handshakeAdvertisesEnforcedToolsAndPriorFoundationRemainsDisabled()
      throws Exception {
    EnforcingMcpDispatcher dispatcher =
        dispatcher(event -> result("succeeded", "allow", JsonSupport.MAPPER.nullNode()));
    JsonNode initialize =
        JsonSupport.MAPPER.readTree(
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{"
                + "\"protocolVersion\":\"2026-07-28\",\"capabilities\":{},"
                + "\"clientInfo\":{\"name\":\"test\",\"version\":\"1\"}}}");
    assertThat(
            dispatcher
                .dispatch(initialize, RemoteTestSupport.connector(RemoteTestSupport.DISCOVER))
                .body()
                .path("result")
                .path("instructions")
                .asText())
        .contains("independently authorized", "policy enforced");
    assertThat(
            new HandshakeOnlyMcpDispatcher()
                .dispatch(
                    call(
                        2,
                        EnforcingMcpDispatcher.FILTER_LOG_EVENTS,
                        "{\"logGroupName\":\"/app/prod\"}"),
                    RemoteTestSupport.connector(RemoteTestSupport.DISCOVER, RemoteTestSupport.READ))
                .body()
                .path("error")
                .path("data")
                .path("code")
                .asText())
        .isEqualTo("REMOTE_TOOL_INVOCATION_NOT_CONFIGURED");
    assertThatThrownBy(
            () ->
                new EnforcingMcpDispatcher(
                    (ActionEnforcer) null, CLOCK, () -> "id", () -> "trace"))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void concurrentAuthenticatedCallsReceiveIndependentSingleUsePermits() throws Exception {
    AtomicInteger targetCalls = new AtomicInteger();
    InMemoryEvidenceLedger evidence = evidence();
    EnforcingMcpDispatcher dispatcher =
        dispatcher(
            service(
                evidence,
                action -> {
                  targetCalls.incrementAndGet();
                  return JsonSupport.MAPPER.createObjectNode().put("mode", "simulated");
                }));
    int count = 32;
    CountDownLatch ready = new CountDownLatch(count);
    CountDownLatch start = new CountDownLatch(1);
    List<String> outcomes = Collections.synchronizedList(new ArrayList<>());
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      for (int index = 0; index < count; index++) {
        final int id = index;
        executor.submit(
            () -> {
              ready.countDown();
              start.await();
              RemoteMcpResponse response =
                  dispatcher.dispatch(
                      call(
                          id,
                          EnforcingMcpDispatcher.FILTER_LOG_EVENTS,
                          "{\"logGroupName\":\"/app/prod\"}"),
                      RemoteTestSupport.connector(RemoteTestSupport.READ));
              outcomes.add(content(response).path("execution").path("status").asText());
              return null;
            });
      }
      assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
      start.countDown();
    }
    assertThat(outcomes).hasSize(count).containsOnly("executed");
    assertThat(targetCalls).hasValue(count);
    assertThat(evidence.readAll()).hasSize(count * 5);
    assertThat(evidence.verify(null).valid()).isTrue();
  }

  private static EnforcingMcpDispatcher dispatcher(ActionEnforcer enforcer) {
    AtomicInteger ids = new AtomicInteger();
    return new EnforcingMcpDispatcher(
        enforcer,
        CLOCK,
        () -> String.format("00000000-0000-7000-8000-%012d", ids.incrementAndGet()),
        () -> String.format("%032x", ids.get()));
  }

  private static EnforcingMcpDispatcher hostedDispatcher(ActionEnforcer enforcer) {
    AtomicInteger ids = new AtomicInteger();
    return new EnforcingMcpDispatcher(
        enforcer,
        CLOCK,
        () -> String.format("00000000-0000-7000-8000-%012d", ids.incrementAndGet()),
        () -> String.format("%032x", ids.get()),
        McpOutcomeObserver.NONE,
        true);
  }

  private static EnforcingMcpDispatcher dispatcher(TenantActionEnforcer enforcer) {
    AtomicInteger ids = new AtomicInteger();
    return new EnforcingMcpDispatcher(
        enforcer,
        CLOCK,
        () -> String.format("00000000-0000-7000-8000-%012d", ids.incrementAndGet()),
        () -> String.format("%032x", ids.get()));
  }

  private static SecureExecutionService service(
      InMemoryEvidenceLedger evidence, io.tracepermit.application.SimulatedTarget target) {
    return service(
        evidence,
        target,
        "policies/aws-web-readonly.json",
        ActionSanitizer.NONE,
        ActionSequenceStore.DISABLED);
  }

  private static SecureExecutionService semanticService(
      InMemoryEvidenceLedger evidence,
      io.tracepermit.application.SimulatedTarget target,
      ActionSanitizer sanitizer,
      ActionSequenceStore sequenceStore) {
    return service(
        evidence,
        target,
        "policies/aws-agentic-demo.json",
        sanitizer,
        sequenceStore);
  }

  private static SecureExecutionService service(
      InMemoryEvidenceLedger evidence,
      io.tracepermit.application.SimulatedTarget target,
      String policyPath,
      ActionSanitizer sanitizer,
      ActionSequenceStore sequenceStore) {
    AtomicInteger permitIds = new AtomicInteger();
    AtomicInteger attemptIds = new AtomicInteger();
    ExecutionPermitAuthority permits =
        new ExecutionPermitAuthority(
            ExecutionPermitAuthority.DEFAULT_ISSUER,
            "remote-dispatch-test-key",
            new byte[32],
            30,
            CLOCK,
            () -> "permit-" + permitIds.incrementAndGet(),
            new InMemoryPermitConsumptionStore());
    return new SecureExecutionService(
        new PolicyEvaluator(),
        JsonSupport.read(ROOT.resolve(policyPath)),
        permits,
        new InMemoryExecutionLedger(() -> "attempt-" + attemptIds.incrementAndGet()),
        evidence,
        target,
        () -> SecurityReadiness.allReady(RemoteTestSupport.NOW),
        CLOCK,
        "urn:tracepermit:simulator",
        sanitizer,
        sequenceStore);
  }

  private static InMemoryEvidenceLedger evidence() {
    AtomicInteger ids = new AtomicInteger();
    return new InMemoryEvidenceLedger(
        "urn:tracepermit:remote-dispatch-test", CLOCK, () -> "evidence-" + ids.incrementAndGet());
  }

  private static ExecutionResult result(String outcome, String decision, JsonNode value) {
    Classification classification =
        new Classification(
            "read",
            "low",
            10,
            EnforcingMcpDispatcher.FILTER_LOG_EVENTS,
            List.of("READ_VERB"),
            "deterministic",
            "0.1.0");
    PolicyDecision policy =
        new PolicyDecision(
            decision,
            "TEST_REASON",
            "require_approval".equals(decision) ? "security" : null,
            "rule-test",
            "pol-test",
            1);
    return new ExecutionResult(
        outcome, new PolicyEvaluation(classification, policy), null, value);
  }

  private static JsonNode call(int id, String name, String arguments) throws Exception {
    return JsonSupport.MAPPER.readTree(
        "{\"jsonrpc\":\"2.0\",\"id\":"
            + id
            + ",\"method\":\"tools/call\",\"params\":{\"name\":\""
            + name
            + "\",\"arguments\":"
            + arguments
            + "}}");
  }

  private static JsonNode content(RemoteMcpResponse response) throws Exception {
    return content(response.body());
  }

  private static JsonNode content(JsonNode response) throws Exception {
    return JsonSupport.MAPPER.readTree(
        response.path("result").path("content").get(0).path("text").asText());
  }

  @Test
  void customToolRegistrationAndScopedDispatchSucceeds() throws Exception {
    McpToolRegistry registry = EnforcingMcpDispatcher.createDefaultRegistry();
    ConnectorScope customScope = ConnectorScope.of("custom.analytics.read");

    registry.register(
        new McpToolDefinition() {
          @Override
          public String name() {
            return "analytics.reports.query";
          }

          @Override
          public String description() {
            return "Query simulated analytics reports.";
          }

          @Override
          public JsonNode inputSchema() {
            ObjectNode schema = JsonSupport.MAPPER.createObjectNode();
            schema.put("type", "object");
            return schema;
          }

          @Override
          public ConnectorScope requiredScope() {
            return customScope;
          }

          @Override
          public JsonNode normalizeArguments(JsonNode rawArguments) {
            if ("throw".equals(rawArguments.path("query").asText())) {
              throw new IllegalArgumentException("Rejected test query.");
            }
            return rawArguments.deepCopy();
          }

          @Override
          public JsonNode createEvent(
              String eventId,
              String traceId,
              Instant observedAt,
              JsonNode normalizedArguments,
              AuthenticatedConnector connector,
              Optional<VerifiedSourceEvidence> sourceEvidence) {
            return EnforcingMcpDispatcher.buildStandardEvent(
                "analytics.reports.query",
                "analytics",
                "prod",
                "report-query",
                normalizedArguments,
                eventId,
                traceId,
                observedAt,
                connector,
                sourceEvidence);
          }
        });

    AtomicInteger customEnforcements = new AtomicInteger();
    EnforcingMcpDispatcher dispatcher =
        new EnforcingMcpDispatcher(
            (context, event) -> {
              customEnforcements.incrementAndGet();
              return result("succeeded", "allow", JsonSupport.MAPPER.createObjectNode().put("rows", 42));
            },
            CLOCK,
            () -> "018f47a2-f5ce-7a4b-8f54-80e5d3eaf091",
            () -> "8f3a2c1e4b5d67890123456789abcdef",
            McpOutcomeObserver.NONE,
            registry);
    assertThatThrownBy(() -> dispatcher.toolRegistry().register(registry.find("analytics.reports.query").orElseThrow()))
        .isInstanceOf(IllegalStateException.class);

    // 1. tools/list exposes the custom tool alongside standard tools
    JsonNode listRequest =
        JsonSupport.MAPPER.readTree(
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\",\"params\":{}}");
    RemoteMcpResponse listResponse =
        dispatcher.dispatch(listRequest, RemoteTestSupport.connector(RemoteTestSupport.DISCOVER));
    assertThat(listResponse.body().path("result").path("tools")).hasSize(3);
    assertThat(listResponse.body().toString()).contains("analytics.reports.query");

    McpToolRegistry customOnly = new McpToolRegistry();
    customOnly.register(registry.find("analytics.reports.query").orElseThrow());
    EnforcingMcpDispatcher customOnlyDispatcher =
        new EnforcingMcpDispatcher(
            (context, event) -> result("block", "block", null),
            CLOCK,
            () -> "018f47a2-f5ce-7a4b-8f54-80e5d3eaf093",
            () -> "8f3a2c1e4b5d67890123456789abcdea",
            McpOutcomeObserver.NONE,
            customOnly);
    JsonNode customOnlyTools =
        customOnlyDispatcher
            .dispatch(listRequest, RemoteTestSupport.connector(RemoteTestSupport.DISCOVER))
            .body()
            .path("result")
            .path("tools");
    assertThat(customOnlyTools).hasSize(1);
    assertThat(customOnlyTools.get(0).path("name").asText()).isEqualTo("analytics.reports.query");

    EnforcingMcpDispatcher hosted =
        new EnforcingMcpDispatcher(
            (context, event) -> result("block", "block", null),
            CLOCK,
            () -> "018f47a2-f5ce-7a4b-8f54-80e5d3eaf092",
            () -> "8f3a2c1e4b5d67890123456789abcdee",
            McpOutcomeObserver.NONE,
            true,
            registry);
    JsonNode hostedTools =
        hosted.dispatchPublic(listRequest).orElseThrow().body().path("result").path("tools");
    assertThat(hostedTools).hasSize(4);
    assertThat(hostedTools.toString()).contains("tracepermit.get_profile");
    assertThat(hostedTools.toString()).contains("custom.analytics.read");

    // 2. Calling with matching scope succeeds
    RemoteMcpResponse callResponse =
        dispatcher.dispatch(
            call(2, "analytics.reports.query", "{\"query\":\"SELECT 1\"}"),
            RemoteTestSupport.connector(customScope));
    assertThat(callResponse.status()).isEqualTo(200);
    assertThat(customEnforcements).hasValue(1);

    RemoteMcpResponse invalid =
        dispatcher.dispatch(
            call(4, "analytics.reports.query", "{\"query\":\"throw\"}"),
            RemoteTestSupport.connector(customScope));
    assertThat(invalid.body().path("error").path("data").path("code").asText())
        .isEqualTo("INVALID_TOOL_ARGUMENTS");
    assertThat(customEnforcements).hasValue(1);

    // 3. Calling without the required scope fails with AUTHORIZATION_DENIED
    assertThatThrownBy(
            () ->
                dispatcher.dispatch(
                    call(3, "analytics.reports.query", "{\"query\":\"SELECT 1\"}"),
                    RemoteTestSupport.connector(RemoteTestSupport.READ)))
        .isInstanceOf(AuthenticationException.class)
        .extracting(error -> ((AuthenticationException) error).code())
        .isEqualTo("AUTHORIZATION_DENIED");
  }

  @Test
  void mcpToolRegistryDirectValidations() {
    McpToolRegistry registry = new McpToolRegistry();
    assertThat(registry.size()).isEqualTo(0);
    assertThat(registry.hasTool("nonexistent")).isFalse();
    assertThat(registry.find("nonexistent")).isEmpty();
    assertThat(registry.allTools()).isEmpty();

    assertThatThrownBy(() -> registry.register(null)).isInstanceOf(NullPointerException.class);
  }
}
