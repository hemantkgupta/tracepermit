package io.tracepermit.agentcore.http;

import org.testcontainers.containers.JdbcDatabaseContainer;
import org.testcontainers.containers.PostgreSQLContainer;

final class RegistrationHttpPostgresTest extends RegistrationHttpJdbcContract {
  @Override JdbcDatabaseContainer<?> container() { return new PostgreSQLContainer<>("postgres:16.4-alpine"); }
  @Override String version() { return "16.4"; }
}
