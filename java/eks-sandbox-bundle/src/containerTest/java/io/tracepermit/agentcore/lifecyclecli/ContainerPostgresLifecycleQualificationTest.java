package io.tracepermit.agentcore.lifecyclecli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.SQLException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;

/** Requires the separately passed stdin gate. Real installed commands, restricted PG16.4. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Timeout(240)
final class ContainerPostgresLifecycleQualificationTest {
  private ContainerQualificationEnvironment environment;

  @BeforeAll void prepare() throws Exception { environment = new ContainerQualificationEnvironment(); }

  @Test void freshContainersCompleteAndReconcileOneSyntheticDeliveryWithRestrictedGrants() throws Exception {
    lifecycle("CONFIRMED_SUCCESS", "DELIVERY_SUCCEEDED", "succeeded");
  }

  @Test void knownSyntheticUnknownRemainsReadOnlyAndNeverAuthorizesAnotherDelivery() throws Exception {
    lifecycle("UNKNOWN", "DELIVERY_UNKNOWN", "unknown");
  }

  private void lifecycle(String selected, String outcome, String execution) throws Exception {
    String ownedDatabase;
    try (var fixture = new ContainerLifecycleFixture(true)) {
      String operation = fixture.registerAndActivate();
      String database = fixture.database.container.getContainerId();
      ownedDatabase = database;
      assertThat(environment.call("inspect", database, fixture.input(operation, "inspect", null), false).outcome()).isEqualTo("OBSERVED");
      assertThat(environment.call("evaluate", database, fixture.input(operation, "evaluate", null), false).outcome()).isEqualTo("EVALUATED");
      assertThat(fixture.database.count("tp_operation_delivery_events")).isZero();
      assertThat(environment.call("review", database, fixture.input(operation, "review", "APPROVE"), false).outcome()).isEqualTo("REVIEW_APPROVED");
      assertThat(fixture.database.count("tp_operation_delivery_events")).isZero();
      LifecycleReceipt delivery = environment.call("deliver-synthetic", database,
          fixture.input(operation, "deliver-synthetic", selected), false);
      assertThat(delivery.outcome()).isEqualTo(outcome);
      assertThat(delivery.executionStatus()).isEqualTo(execution);
      assertThat(delivery.dispatchStarted()).isTrue();
      long events = fixture.database.count("tp_operation_delivery_events");
      assertThat(events).isEqualTo(3);
      assertThat(environment.call("deliver-synthetic", database,
          fixture.input(operation, "deliver-synthetic", selected), false).outcome()).isEqualTo("REJECTED");
      LifecycleReceipt reconciled = environment.call("reconcile", database,
          fixture.input(operation, "reconcile", null), false);
      assertThat(reconciled.outcome()).isEqualTo("RECONCILED");
      assertThat(reconciled.executionStatus()).isEqualTo(execution);
      assertThat(reconciled.dispatchStarted()).isTrue();
      assertThat(fixture.database.count("tp_operation_delivery_events")).isEqualTo(events);
      assertThat(fixture.database.count("tp_operation_admissions")).isOne();
      assertThatThrownBy(() -> {
        try (var connection = fixture.database.operations.getConnection();
            var statement = connection.prepareStatement("DELETE FROM tp_operation_delivery_events")) {
          statement.setQueryTimeout(5);
          statement.executeUpdate();
        }
      }).isInstanceOfSatisfying(SQLException.class,
          failure -> assertThat(failure.getSQLState()).isEqualTo("42501"));
      assertThat(fixture.database.count("tp_operation_delivery_events")).isEqualTo(events);
      // No in-memory target-count or external exactly-once inference from durable rows.
    }
    var absent = environment.docker.control(java.util.List.of("container", "ls", "--all", "--quiet", "--no-trunc", "--filter", "id=" + ownedDatabase));
    assertThat(absent.exit()).isZero();
    assertThat(absent.out().length).isZero();
    var cleanup = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode()
        .put("containerId", ownedDatabase).put("removedObserved", true).put("kind", "POSTGRES_FIXTURE_REMOVAL");
    java.nio.file.Files.writeString(java.nio.file.Path.of(System.getProperty("tracepermit.container.evidence"))
        .resolve("postgres-" + ownedDatabase + "-cleanup.json"), cleanup.toString() + "\n");
  }
}
