package io.tracepermit.adapters.remote;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import io.tracepermit.application.ExecutionResult;
import io.tracepermit.application.TargetExecutionException;
import io.tracepermit.application.TenantActionEnforcer;
import io.tracepermit.contracts.JsonSupport;
import io.tracepermit.policy.Classification;
import io.tracepermit.policy.PolicyDecision;
import io.tracepermit.policy.PolicyEvaluation;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Admission control on the dispatcher: saturated enforcement capacity fails closed
 * without queueing, and the permit is released on every outcome including failures.
 */
final class EnforcingMcpDispatcherCapacityTest {
  private static final Clock CLOCK = Clock.fixed(RemoteTestSupport.NOW, ZoneOffset.UTC);

  @Test
  void saturatedCapacityFailsClosedWithoutQueueing() throws Exception {
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    TenantActionEnforcer enforcer =
        (context, event) -> {
          entered.countDown();
          try {
            assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
          } catch (InterruptedException failure) {
            throw new IllegalStateException("interrupted while holding capacity", failure);
          }
          return result(JsonSupport.MAPPER.createObjectNode().put("mode", "simulated"));
        };
    EnforcingMcpDispatcher dispatcher = dispatcher(enforcer, 1);
    try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
      Future<RemoteMcpResponse> first =
          pool.submit(() ->
              dispatcher.dispatch(
                  call(1, EnforcingMcpDispatcher.FILTER_LOG_EVENTS, logArguments()),
                  RemoteTestSupport.connector(RemoteTestSupport.DISCOVER, RemoteTestSupport.READ)));
      assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();

      RemoteMcpResponse saturated =
          dispatcher.dispatch(
              call(2, EnforcingMcpDispatcher.FILTER_LOG_EVENTS, logArguments()),
              RemoteTestSupport.connector(RemoteTestSupport.DISCOVER, RemoteTestSupport.READ));

      assertThat(saturated.body().path("result").path("isError").asBoolean()).isTrue();
      assertThat(content(saturated).path("error").asText())
          .isEqualTo("ENFORCEMENT_CAPACITY_EXCEEDED");
      assertThat(content(saturated).path("execution").path("status").asText())
          .isEqualTo("not_executed");

      release.countDown();
      assertThat(content(first.get(10, TimeUnit.SECONDS)).path("execution").path("status").asText())
          .isEqualTo("executed");
      assertThat(content(first.get()).path("error").isMissingNode()).isTrue();
    }

    RemoteMcpResponse admitted =
        dispatcher.dispatch(
            call(3, EnforcingMcpDispatcher.FILTER_LOG_EVENTS, logArguments()),
            RemoteTestSupport.connector(RemoteTestSupport.DISCOVER, RemoteTestSupport.READ));
    assertThat(content(admitted).path("execution").path("status").asText())
        .isEqualTo("executed");
  }

  @Test
  void capacityIsReleasedWhenEnforcementFails() throws Exception {
    AtomicInteger calls = new AtomicInteger();
    TenantActionEnforcer enforcer =
        (context, event) -> {
          if (calls.incrementAndGet() == 1) {
            throw new TargetExecutionException(
                "SIMULATED_TARGET_FAILURE", "The simulated target failed.");
          }
          return result(JsonSupport.MAPPER.createObjectNode().put("mode", "simulated"));
        };
    EnforcingMcpDispatcher dispatcher = dispatcher(enforcer, 1);

    RemoteMcpResponse failed =
        dispatcher.dispatch(
            call(1, EnforcingMcpDispatcher.FILTER_LOG_EVENTS, logArguments()),
            RemoteTestSupport.connector(RemoteTestSupport.DISCOVER, RemoteTestSupport.READ));
    assertThat(content(failed).path("error").asText()).isEqualTo("TARGET_EXECUTION_FAILED");

    RemoteMcpResponse next =
        dispatcher.dispatch(
            call(2, EnforcingMcpDispatcher.FILTER_LOG_EVENTS, logArguments()),
            RemoteTestSupport.connector(RemoteTestSupport.DISCOVER, RemoteTestSupport.READ));
    assertThat(content(next).path("error").isMissingNode()).isTrue();
    assertThat(content(next).path("execution").path("status").asText()).isEqualTo("executed");
  }

  @Test
  void enforcementConcurrencyIsBoundedAtConstruction() {
    AtomicInteger ids = new AtomicInteger();
    TenantActionEnforcer enforcer =
        (context, event) ->
            result(JsonSupport.MAPPER.createObjectNode().put("mode", "simulated"));
    assertThatThrownBy(
            () ->
                new EnforcingMcpDispatcher(
                    enforcer,
                    CLOCK,
                    () -> String.format("00000000-0000-7000-8000-%012d", ids.incrementAndGet()),
                    () -> String.format("%032x", ids.get()),
                    McpOutcomeObserver.NONE,
                    false,
                    EnforcingMcpDispatcher.createDefaultRegistry(),
                    0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new EnforcingMcpDispatcher(
                    enforcer,
                    CLOCK,
                    () -> String.format("00000000-0000-7000-8000-%012d", ids.incrementAndGet()),
                    () -> String.format("%032x", ids.get()),
                    McpOutcomeObserver.NONE,
                    false,
                    EnforcingMcpDispatcher.createDefaultRegistry(),
                    1_025))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static EnforcingMcpDispatcher dispatcher(
      TenantActionEnforcer enforcer, int enforcementConcurrency) {
    AtomicInteger ids = new AtomicInteger();
    return new EnforcingMcpDispatcher(
        enforcer,
        CLOCK,
        () -> String.format("00000000-0000-7000-8000-%012d", ids.incrementAndGet()),
        () -> String.format("%032x", ids.get()),
        McpOutcomeObserver.NONE,
        false,
        EnforcingMcpDispatcher.createDefaultRegistry(),
        enforcementConcurrency);
  }

  private static String logArguments() {
    return "{\"logGroupName\":\"/app/prod\",\"filterPattern\":\"ERROR\",\"limit\":20}";
  }

  private static ExecutionResult result(JsonNode value) {
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
        new PolicyDecision("allow", "TEST_REASON", null, "rule-test", "pol-test", 1);
    return new ExecutionResult(
        "succeeded", new PolicyEvaluation(classification, policy), null, value);
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
    return JsonSupport.MAPPER.readTree(
        response.body().path("result").path("content").get(0).path("text").asText());
  }
}
