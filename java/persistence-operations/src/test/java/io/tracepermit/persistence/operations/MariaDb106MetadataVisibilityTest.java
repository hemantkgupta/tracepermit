package io.tracepermit.persistence.operations;

import org.testcontainers.containers.JdbcDatabaseContainer;
import org.testcontainers.containers.MariaDBContainer;

final class MariaDb106MetadataVisibilityTest extends OperationMetadataVisibilityContract {
  @Override JdbcDatabaseContainer<?> container() {
    return new MariaDBContainer<>("mariadb:10.6.25");
  }
}
