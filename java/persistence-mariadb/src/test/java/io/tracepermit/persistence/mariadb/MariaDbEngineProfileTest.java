package io.tracepermit.persistence.mariadb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

final class MariaDbEngineProfileTest {
  @Test
  void exactPrimaryAndTransitionalVersionsAreQualified() {
    MariaDbEngineProfile primary = MariaDbEngineProfile.qualified(
        "MariaDB", "11.4.13-MariaDB-ubu2404", "InnoDB", "tracepermit");
    MariaDbEngineProfile transitional = MariaDbEngineProfile.qualified(
        "MariaDB", "10.6.25-MariaDB-log", "innodb", "tracepermit");

    assertThat(primary.normalizedVersion()).isEqualTo("11.4.13");
    assertThat(primary.supportLevel()).isEqualTo(MariaDbEngineProfile.SupportLevel.PRIMARY);
    assertThat(transitional.normalizedVersion()).isEqualTo("10.6.25");
    assertThat(transitional.supportLevel())
        .isEqualTo(MariaDbEngineProfile.SupportLevel.TRANSITIONAL);
  }

  @Test
  void unknownEngineVersionStorageAndDatabaseFailClosed() {
    assertThatThrownBy(() -> MariaDbEngineProfile.qualified(
        "MySQL", "11.4.13", "InnoDB", "tracepermit"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("DATABASE_ENGINE_UNSUPPORTED");
    assertThatThrownBy(() -> MariaDbEngineProfile.qualified(
        "MariaDB", "11.4.12", "InnoDB", "tracepermit"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("DATABASE_ENGINE_VERSION_UNQUALIFIED");
    assertThatThrownBy(() -> MariaDbEngineProfile.qualified(
        "MariaDB", "11.4.13", "MyISAM", "tracepermit"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("DATABASE_STORAGE_ENGINE_UNSUPPORTED");
    assertThatThrownBy(() -> MariaDbEngineProfile.qualified(
        "MariaDB", "11.4.13", "InnoDB", "mysql"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("DATABASE_NAME_UNSUPPORTED");
  }

  @Test
  void malformedOrMissingVersionFailsClosed() {
    assertThatThrownBy(() -> MariaDbEngineProfile.normalizeVersion("11.4"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("DATABASE_ENGINE_VERSION_UNRECOGNIZED");
    assertThatThrownBy(() -> MariaDbEngineProfile.normalizeVersion(null))
        .isInstanceOf(NullPointerException.class);
  }
}
