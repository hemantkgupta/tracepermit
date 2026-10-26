package io.tracepermit.adapters.remote.agentcore.jdbc;

import org.testcontainers.containers.JdbcDatabaseContainer;
import org.testcontainers.containers.MariaDBContainer;

final class AgentCoreMariaDb106RegistrationTest extends AgentCoreJdbcRegistrationContract {
  @Override
  JdbcDatabaseContainer<?> container() {
    return new MariaDBContainer<>("mariadb:10.6.25");
  }

  @Override
  String expectedVersion() {
    return "10.6.25";
  }
}
