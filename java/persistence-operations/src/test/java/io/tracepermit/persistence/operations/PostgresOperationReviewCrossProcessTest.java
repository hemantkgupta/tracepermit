package io.tracepermit.persistence.operations;

import org.testcontainers.containers.JdbcDatabaseContainer;
import org.testcontainers.containers.PostgreSQLContainer;

final class PostgresOperationReviewCrossProcessTest extends OperationReviewCrossProcessContract {
  @Override
  JdbcDatabaseContainer<?> container() {
    return new PostgreSQLContainer<>("postgres:16.4-alpine");
  }
}
