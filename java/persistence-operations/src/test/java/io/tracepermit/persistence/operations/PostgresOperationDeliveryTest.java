package io.tracepermit.persistence.operations;

import org.testcontainers.containers.JdbcDatabaseContainer;
import org.testcontainers.containers.PostgreSQLContainer;

final class PostgresOperationDeliveryTest extends OperationDeliveryContract {
  @Override JdbcDatabaseContainer<?> container() { return new PostgreSQLContainer<>("postgres:16.4-alpine"); }
}
