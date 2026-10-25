package io.tracepermit.agentcore.http;

import org.testcontainers.containers.JdbcDatabaseContainer;
import org.testcontainers.containers.MariaDBContainer;

final class RegistrationHttpMariaDb114Test extends RegistrationHttpJdbcContract {
  @Override JdbcDatabaseContainer<?> container() { return new MariaDBContainer<>("mariadb:11.4.13"); }
  @Override String version() { return "11.4.13"; }
}
