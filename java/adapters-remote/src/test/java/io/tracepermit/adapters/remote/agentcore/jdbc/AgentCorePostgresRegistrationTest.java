package io.tracepermit.adapters.remote.agentcore.jdbc;

import org.testcontainers.containers.JdbcDatabaseContainer;
import org.testcontainers.containers.PostgreSQLContainer;

final class AgentCorePostgresRegistrationTest extends AgentCoreJdbcRegistrationContract {
  @Override
  JdbcDatabaseContainer<?> container() {
    return new PostgreSQLContainer<>("postgres:16.4-alpine");
  }

  @Override
  String expectedVersion() {
    return "16.4";
  }
}
