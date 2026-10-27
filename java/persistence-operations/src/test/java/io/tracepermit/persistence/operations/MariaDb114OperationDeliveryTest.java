package io.tracepermit.persistence.operations;

import org.testcontainers.containers.JdbcDatabaseContainer;
import org.testcontainers.containers.MariaDBContainer;

final class MariaDb114OperationDeliveryTest extends OperationDeliveryContract {
  @Override JdbcDatabaseContainer<?> container() { return new MariaDBContainer<>("mariadb:11.4.13"); }
}
