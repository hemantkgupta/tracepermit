package io.tracepermit.persistence.operations;

import org.testcontainers.containers.JdbcDatabaseContainer;
import org.testcontainers.containers.MariaDBContainer;

final class MariaDb106OperationAuthorityControlTest extends OperationAuthorityControlContract {
  @org.junit.jupiter.api.Test
  void writerRejectsDisabledSessionConstraints() throws Exception {
    assertDisabledSessionConstraintsRejected();
  }
  @Override JdbcDatabaseContainer<?> container() {
    return new MariaDBContainer<>("mariadb:10.6.25");
  }
}
