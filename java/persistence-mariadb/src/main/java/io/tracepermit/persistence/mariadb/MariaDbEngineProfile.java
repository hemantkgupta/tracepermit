package io.tracepermit.persistence.mariadb;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Explicitly qualified MariaDB engine profile; unknown versions fail closed. */
public record MariaDbEngineProfile(
    String productName,
    String reportedVersion,
    String normalizedVersion,
    SupportLevel supportLevel,
    String defaultStorageEngine,
    String databaseName) {
  private static final Pattern VERSION = Pattern.compile("^(\\d+)\\.(\\d+)\\.(\\d+)(?:[-.+].*)?$");

  public enum SupportLevel {
    PRIMARY,
    TRANSITIONAL
  }

  public MariaDbEngineProfile {
    Objects.requireNonNull(productName, "productName");
    Objects.requireNonNull(reportedVersion, "reportedVersion");
    Objects.requireNonNull(normalizedVersion, "normalizedVersion");
    Objects.requireNonNull(supportLevel, "supportLevel");
    Objects.requireNonNull(defaultStorageEngine, "defaultStorageEngine");
    Objects.requireNonNull(databaseName, "databaseName");
  }

  static MariaDbEngineProfile qualified(
      String productName, String reportedVersion, String defaultStorageEngine, String databaseName) {
    if (!"mariadb".equals(productName.toLowerCase(Locale.ROOT))) {
      throw new IllegalStateException("DATABASE_ENGINE_UNSUPPORTED");
    }
    if (!"innodb".equals(defaultStorageEngine.toLowerCase(Locale.ROOT))) {
      throw new IllegalStateException("DATABASE_STORAGE_ENGINE_UNSUPPORTED");
    }
    if (databaseName.isBlank() || isSystemDatabase(databaseName)) {
      throw new IllegalStateException("DATABASE_NAME_UNSUPPORTED");
    }
    String normalized = normalizeVersion(reportedVersion);
    SupportLevel level = switch (normalized) {
      case "11.4.13" -> SupportLevel.PRIMARY;
      case "10.6.25" -> SupportLevel.TRANSITIONAL;
      default -> throw new IllegalStateException("DATABASE_ENGINE_VERSION_UNQUALIFIED");
    };
    return new MariaDbEngineProfile(
        productName, reportedVersion, normalized, level, defaultStorageEngine, databaseName);
  }

  static String normalizeVersion(String reportedVersion) {
    Objects.requireNonNull(reportedVersion, "reportedVersion");
    Matcher matcher = VERSION.matcher(reportedVersion);
    if (!matcher.matches()) {
      throw new IllegalStateException("DATABASE_ENGINE_VERSION_UNRECOGNIZED");
    }
    return matcher.group(1) + "." + matcher.group(2) + "." + matcher.group(3);
  }

  private static boolean isSystemDatabase(String databaseName) {
    return switch (databaseName.toLowerCase(Locale.ROOT)) {
      case "information_schema", "mysql", "performance_schema", "sys" -> true;
      default -> false;
    };
  }
}
