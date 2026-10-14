package io.tracepermit.application;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.tracepermit.contracts.JsonSupport;
import io.tracepermit.policy.PolicyEvaluator;
import java.time.Instant;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

final class InMemoryActionSequenceStoreTest {
  private static final Instant NOW = Instant.parse("2026-10-12T08:00:00Z");

  @Test
  void crossTargetSignalIsScopedToTenantSubjectAndOneMinuteWindow() {
    InMemoryActionSequenceStore store = InMemoryActionSequenceStore.oneMinute();
    ObjectNode read = event("tenant:a", "workload:one", "synthetic-crm", "demo.customer.bulk_read");
    ObjectNode send = event("tenant:a", "workload:one", "synthetic-egress", "demo.egress.send_external");

    assertThat(store.contextFor(send, NOW).signals()).isEmpty();
    store.record(read, new PolicyEvaluator().classify(read), NOW);
    assertThat(store.contextFor(send, NOW.plusSeconds(59)).signals())
        .containsExactly(InMemoryActionSequenceStore.PII_READ_THEN_EXTERNAL_SEND);
    assertThat(store.contextFor(send, NOW.plusSeconds(61)).signals()).isEmpty();
    assertThat(store.contextFor(send, NOW.minusSeconds(1)).signals()).isEmpty();

    assertThat(store.contextFor(
        event("tenant:b", "workload:one", "synthetic-egress", "demo.egress.send_external"),
        NOW.plusSeconds(1)).signals()).isEmpty();
    assertThat(store.contextFor(
        event("tenant:a", "workload:two", "synthetic-egress", "demo.egress.send_external"),
        NOW.plusSeconds(1)).signals()).isEmpty();
    assertThat(store.contextFor(
        event("tenant:a", "workload:one", "synthetic-crm", "demo.egress.send_external"),
        NOW.plusSeconds(1)).signals()).isEmpty();
  }

  private static ObjectNode event(String tenant, String subjectId, String system, String tool) {
    ObjectNode event = JsonSupport.MAPPER.createObjectNode();
    event.putObject("identity").putObject("subject")
        .put("organizationId", tenant).put("id", subjectId);
    event.putObject("action").put("toolName", tool).putObject("target").put("system", system);
    return event;
  }
}
