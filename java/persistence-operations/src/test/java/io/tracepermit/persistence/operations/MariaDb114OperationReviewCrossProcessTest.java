package io.tracepermit.persistence.operations;

import org.testcontainers.containers.JdbcDatabaseContainer;
import org.testcontainers.containers.MariaDBContainer;

final class MariaDb114OperationReviewCrossProcessTest extends OperationReviewCrossProcessContract {
  @Override
  JdbcDatabaseContainer<?> container() {
    return new MariaDBContainer<>("mariadb:11.4.13");
  }
}
