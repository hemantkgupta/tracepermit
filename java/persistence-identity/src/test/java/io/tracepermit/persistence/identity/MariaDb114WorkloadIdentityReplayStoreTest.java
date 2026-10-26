package io.tracepermit.persistence.identity;

import org.testcontainers.containers.JdbcDatabaseContainer;
import org.testcontainers.containers.MariaDBContainer;

final class MariaDb114WorkloadIdentityReplayStoreTest extends WorkloadIdentityReplayStoreContract {
  @Override
  JdbcDatabaseContainer<?> container() {
    return new MariaDBContainer<>("mariadb:11.4.13");
  }
}
