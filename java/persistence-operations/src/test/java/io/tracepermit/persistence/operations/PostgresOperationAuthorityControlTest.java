package io.tracepermit.persistence.operations;

import org.testcontainers.containers.JdbcDatabaseContainer;
import org.testcontainers.containers.PostgreSQLContainer;

final class PostgresOperationAuthorityControlTest extends OperationAuthorityControlContract {
  @org.junit.jupiter.api.Test
  void physicalReadinessRejectsCrossSchemaFunctionTruncateTrigger() throws Exception {
    assertTruncateTriggerRejected();
  }
  @Override JdbcDatabaseContainer<?> container() {
    return new PostgreSQLContainer<>("postgres:16.4-alpine");
  }
}
