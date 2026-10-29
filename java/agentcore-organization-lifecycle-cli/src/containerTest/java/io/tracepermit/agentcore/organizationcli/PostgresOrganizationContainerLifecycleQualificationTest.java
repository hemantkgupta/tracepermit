package io.tracepermit.agentcore.organizationcli;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;

/** Fresh installed processes, generated authority material and restricted PostgreSQL16.4. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Timeout(300)
final class PostgresOrganizationContainerLifecycleQualificationTest {
  private OrganizationContainerQualificationEnvironment environment;

  @BeforeAll
  void prepare() throws Exception {
    environment = new OrganizationContainerQualificationEnvironment();
  }

  @Test
  void registeredApprovedLifecycleDeliversAndReconcilesOneExplicitSyntheticSuccess()
      throws Exception {
    String database = null;
    try (var fixture = new OrganizationContainerFixture()) {
      database = fixture.databaseContainerId();
      String operation = registerAndActivate(fixture, database, "container-success");
      assertThat(call(fixture, database, operation, "inspect", null).outcome())
          .isEqualTo("OBSERVED");
      assertThat(call(fixture, database, operation, "evaluate", null).outcome())
          .isEqualTo("EVALUATED");
      assertThat(call(fixture, database, operation, "review", "APPROVE").outcome())
          .isEqualTo("REVIEW_APPROVED");
      OrganizationOperatorReceipt delivery =
          call(fixture, database, operation, "deliver-synthetic", "CONFIRMED_SUCCESS");
      assertThat(delivery.outcome()).isEqualTo("DELIVERY_SUCCEEDED");
      assertThat(delivery.executionStatus()).isEqualTo("succeeded");
      assertThat(delivery.dispatchStarted()).isTrue();
      OrganizationOperatorReceipt reconciled =
          call(fixture, database, operation, "reconcile", null);
      assertThat(reconciled.outcome()).isEqualTo("RECONCILED");
      assertThat(reconciled.executionStatus()).isEqualTo("succeeded");
      assertThat(reconciled.dispatchStarted()).isTrue();
      assertThat(call(fixture, database, operation, "deliver-synthetic", "CONFIRMED_SUCCESS").outcome())
          .isEqualTo("REJECTED");
      fixture.assertRuntimeGrantsRemainRestricted(operation);
    } finally {
      recordDatabaseCleanup(database);
    }
  }

  @Test
  void independentDenialKeepsExplicitSyntheticDeliveryRejectedAndReconciliationReadOnly()
      throws Exception {
    String database = null;
    try (var fixture = new OrganizationContainerFixture()) {
      database = fixture.databaseContainerId();
      String operation = registerAndActivate(fixture, database, "container-denied");
      assertThat(call(fixture, database, operation, "evaluate", null).outcome())
          .isEqualTo("EVALUATED");
      assertThat(call(fixture, database, operation, "review", "DENY").outcome())
          .isEqualTo("REVIEW_DENIED");
      assertThat(call(fixture, database, operation, "deliver-synthetic", "CONFIRMED_SUCCESS").outcome())
          .isEqualTo("REJECTED");
      OrganizationOperatorReceipt reconciled =
          call(fixture, database, operation, "reconcile", null);
      assertThat(reconciled.outcome()).isEqualTo("RECONCILED");
      assertThat(reconciled.dispatchStarted()).isFalse();
    } finally {
      recordDatabaseCleanup(database);
    }
  }

  @Test
  void knownSyntheticUnknownIsDurableReadOnlyAndNeverAuthorizesAnotherDelivery()
      throws Exception {
    String database = null;
    try (var fixture = new OrganizationContainerFixture()) {
      database = fixture.databaseContainerId();
      String operation = registerAndActivate(fixture, database, "container-unknown");
      assertThat(call(fixture, database, operation, "evaluate", null).outcome())
          .isEqualTo("EVALUATED");
      assertThat(call(fixture, database, operation, "review", "APPROVE").outcome())
          .isEqualTo("REVIEW_APPROVED");
      OrganizationOperatorReceipt first =
          call(fixture, database, operation, "deliver-synthetic", "UNKNOWN");
      assertThat(first.outcome()).isEqualTo("DELIVERY_UNKNOWN");
      assertThat(first.executionStatus()).isEqualTo("unknown");
      assertThat(first.dispatchStarted()).isTrue();
      OrganizationOperatorReceipt reconciled =
          call(fixture, database, operation, "reconcile", null);
      assertThat(reconciled.outcome()).isEqualTo("RECONCILED");
      assertThat(reconciled.executionStatus()).isEqualTo("unknown");
      assertThat(reconciled.dispatchStarted()).isTrue();
      assertThat(call(fixture, database, operation, "deliver-synthetic", "CONFIRMED_SUCCESS").outcome())
          .isEqualTo("REJECTED");
      // Durable state is observed, but no target invocation count or exactly-once claim is made.
    } finally {
      recordDatabaseCleanup(database);
    }
  }

  private String registerAndActivate(
      OrganizationContainerFixture fixture, String database, String requestKey) throws Exception {
    OrganizationOperatorReceipt registered =
        environment.call("register", database, fixture.registrationInput(requestKey), false);
    assertThat(registered.outcome()).isEqualTo("CREATED");
    assertThat(registered.created()).isTrue();
    fixture.activateExactRegisteredAuthority();
    return registered.operationId();
  }

  private OrganizationOperatorReceipt call(
      OrganizationContainerFixture fixture,
      String database,
      String operation,
      String command,
      String selected)
      throws Exception {
    return environment.call(
        command, database, fixture.input(operation, command, selected), false);
  }

  private void recordDatabaseCleanup(String database) throws Exception {
    if (database == null) return;
    var absent =
        environment.docker.control(
            List.of(
                "container",
                "ls",
                "--all",
                "--quiet",
                "--no-trunc",
                "--filter",
                "id=" + database));
    assertThat(absent.exit()).isZero();
    assertThat(absent.out()).isEmpty();
    var cleanup =
        new ObjectMapper()
            .createObjectNode()
            .put("containerId", database)
            .put("removedObserved", true)
            .put("kind", "ORGANIZATION_POSTGRES_FIXTURE_REMOVAL");
    Files.writeString(
        Path.of(System.getProperty("tracepermit.container.evidence"))
            .resolve("organization-postgres-" + database + "-cleanup.json"),
        cleanup + "\n");
  }
}
