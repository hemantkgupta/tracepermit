package io.tracepermit.agentcore.http;

import org.testcontainers.containers.JdbcDatabaseContainer;
import org.testcontainers.containers.MariaDBContainer;

final class RegistrationHttpMariaDb106Test extends RegistrationHttpJdbcContract {
  @Override JdbcDatabaseContainer<?> container() { return new MariaDBContainer<>("mariadb:10.6.25"); }
  @Override String version() { return "10.6.25"; }
}
