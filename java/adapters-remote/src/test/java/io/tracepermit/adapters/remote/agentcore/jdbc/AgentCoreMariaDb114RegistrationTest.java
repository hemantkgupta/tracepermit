package io.tracepermit.adapters.remote.agentcore.jdbc;

import org.testcontainers.containers.JdbcDatabaseContainer;
import org.testcontainers.containers.MariaDBContainer;

final class AgentCoreMariaDb114RegistrationTest extends AgentCoreJdbcRegistrationContract {
  @Override
  JdbcDatabaseContainer<?> container() {
    return new MariaDBContainer<>("mariadb:11.4.13");
  }

  @Override
  String expectedVersion() {
    return "11.4.13";
  }
}
