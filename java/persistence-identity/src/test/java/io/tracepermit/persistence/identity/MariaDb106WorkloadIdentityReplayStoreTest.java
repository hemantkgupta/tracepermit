package io.tracepermit.persistence.identity;

import org.testcontainers.containers.JdbcDatabaseContainer;
import org.testcontainers.containers.MariaDBContainer;

final class MariaDb106WorkloadIdentityReplayStoreTest extends WorkloadIdentityReplayStoreContract {
  @Override
  JdbcDatabaseContainer<?> container() {
    return new MariaDBContainer<>("mariadb:10.6.25");
  }
}
