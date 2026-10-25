package io.tracepermit.persistence.identity;

import org.testcontainers.containers.JdbcDatabaseContainer;
import org.testcontainers.containers.PostgreSQLContainer;

final class PostgresWorkloadIdentityReplayStoreTest extends WorkloadIdentityReplayStoreContract {
  @Override
  JdbcDatabaseContainer<?> container() {
    return new PostgreSQLContainer<>("postgres:16.4-alpine");
  }
}
