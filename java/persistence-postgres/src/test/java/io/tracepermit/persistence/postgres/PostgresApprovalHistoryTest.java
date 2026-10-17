package io.tracepermit.persistence.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.tracepermit.contracts.CanonicalJson;
import io.tracepermit.contracts.JsonSupport;
import io.tracepermit.execution.ApprovalBinding;
import io.tracepermit.execution.ApprovalHistoryQuery;
import io.tracepermit.execution.TenantPartition;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

/** Raw persistence scoping proof, not authentication or permission to dispatch. */
final class PostgresApprovalHistoryTest {
  static final Instant NOW = Instant.parse("2026-10-17T00:00:00Z");
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.4-alpine");
  @BeforeAll static void start() { POSTGRES.start(); }
  @AfterAll static void stop() { POSTGRES.stop(); }

  @Test void identicalIdentifiersInDifferentTenantsCannotJoinOrPageAcrossPartitions() throws Exception {
    var source = new PGSimpleDataSource();
    source.setUrl(POSTGRES.getJdbcUrl()); source.setUser(POSTGRES.getUsername()); source.setPassword(POSTGRES.getPassword());
    var state = new PostgresDurableEnforcementState(source, Clock.fixed(NOW, ZoneOffset.UTC), () -> UUID.randomUUID().toString());
    var a = new TenantPartition("simulated-alpha");
    var b = new TenantPartition("simulated-beta");
    for (var tenant : java.util.List.of(a, b)) {
      seed(state, tenant, "same-id", "principal:alice", true);
      seed(state, tenant, "second-id", "principal:bob", true);
      seed(state, tenant, "without-snapshot", "principal:alice", false);
    }
    var query = new ApprovalHistoryQuery(1, "pending", "aws.ec2.update_security_group", null, null, null);
    var page = state.approvalHistory(a, null, query);
    assertThat(page.hasMore()).isTrue();
    assertThat(page.items()).hasSize(1).allSatisfy(row -> assertThat(row.binding().tenant()).isEqualTo(a));
    var last = page.items().getLast().binding();
    var next = state.approvalHistory(a, null, new ApprovalHistoryQuery(1, query.status(), query.toolName(), null,
        last.createdAt(), last.approvalRequestId()));
    assertThat(next.hasMore()).isFalse();
    assertThat(next.items()).hasSize(1).allSatisfy(row -> assertThat(row.binding().tenant()).isEqualTo(a));
    assertThat(next.items().getFirst().binding().approvalRequestId()).isNotEqualTo(last.approvalRequestId());
    var owned = state.approvalHistory(a, "principal:alice", new ApprovalHistoryQuery(50, null, null, null, null, null));
    assertThat(owned.items()).hasSize(1);
    assertThat(owned.items().getFirst().binding().approvalRequestId()).isEqualTo("same-id");
    assertThat(owned.items().getFirst().binding().tenant()).isEqualTo(a);
    assertThat(state.approvalHistory(b, "principal:alice", query).items()).hasSize(1)
        .allSatisfy(row -> assertThat(row.binding().tenant()).isEqualTo(b));
    assertThat(state.approvalHistory(a, "principal:missing", query).items()).isEmpty();
    assertThatThrownBy(() -> state.approvalHistory(a, "' OR true--", query)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> state.approvalHistory(null, null, query)).isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> state.approvalHistory(a, null, null)).isInstanceOf(NullPointerException.class);
    assertThat(state.readEvidence(a)).hasSize(3);
    assertThat(state.readEvidence(b)).hasSize(3);
  }

  private static void seed(PostgresDurableEnforcementState state, TenantPartition tenant, String id, String subject, boolean snapshot) {
    ObjectNode action = (ObjectNode) JsonSupport.read(Path.of(System.getProperty("tracepermit.repositoryRoot"))
        .resolve("fixtures/api-service-aws-modify.json"));
    action.put("eventId", UUID.nameUUIDFromBytes((tenant.value() + ":" + id)
        .getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString());
    ((ObjectNode) action.path("identity").path("subject")).put("organizationId", tenant.value());
    String hash = CanonicalJson.sha256(action);
    var binding = new ApprovalBinding(tenant, id, hash, hash, action.path("eventId").asText(), "eval-" + id, subject, hash,
        "policy-simulated", 1, "require_approval", "aws.ec2.update_security_group", hash, "reviewers", NOW, NOW.plusSeconds(300), 1);
    if (snapshot) state.createPending(binding, action);
    else state.createPending(binding);
  }
}
