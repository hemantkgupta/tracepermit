package io.tracepermit.persistence.operations;

import org.testcontainers.containers.JdbcDatabaseContainer;
import org.testcontainers.containers.MariaDBContainer;

final class MariaDb106OperationRepositoryTest extends OperationRepositoryContract {
  @org.junit.jupiter.api.Test
  void tableScopedCheckMetadataCannotBeSkipped() throws Exception {
    mariaTableScopedRuntimeStillRequiresThePhysicalChecks();
  }

  @Override
  JdbcDatabaseContainer<?> container() {
    return new MariaDBContainer<>("mariadb:10.6.25");
  }
}
