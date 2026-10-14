package io.tracepermit.adapters.http;

import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

public record DevelopmentRuntimeInfo(
    String schemaVersion,
    String productVersion,
    String buildCommit,
    String backend,
    String javaVersion,
    String mode,
    boolean simulated) {
  private static final Pattern VERSION = Pattern.compile("[0-9A-Za-z][0-9A-Za-z.+-]{0,63}");
  private static final Pattern COMMIT = Pattern.compile("(?:development|[0-9a-f]{7,40})");

  public DevelopmentRuntimeInfo {
    schemaVersion = exact(schemaVersion, "1.0");
    productVersion = bounded(productVersion, VERSION, "Product version");
    buildCommit = bounded(buildCommit, COMMIT, "Build commit");
    backend = exact(backend, "java");
    javaVersion = safeText(javaVersion, "Java version", 64);
    if (!"simulated-loopback".equals(mode) && !"simulated-oauth-loopback".equals(mode)) {
      throw new IllegalArgumentException("Invalid runtime information.");
    }
    if (!simulated) throw new IllegalArgumentException("The development runtime must be simulated.");
  }

  public static DevelopmentRuntimeInfo from(Map<String, String> environment) {
    Objects.requireNonNull(environment);
    return new DevelopmentRuntimeInfo(
        "1.0",
        environment.getOrDefault("TRACEPERMIT_BUILD_VERSION", "0.1.0"),
        environment.getOrDefault("TRACEPERMIT_BUILD_COMMIT", "development"),
        "java",
        System.getProperty("java.version", "unknown"),
        "simulated-loopback",
        true);
  }

  public static DevelopmentRuntimeInfo hostedClientOAuth(Map<String, String> environment) {
    Objects.requireNonNull(environment);
    return new DevelopmentRuntimeInfo(
        "1.0",
        environment.getOrDefault("TRACEPERMIT_BUILD_VERSION", "0.1.0"),
        environment.getOrDefault("TRACEPERMIT_BUILD_COMMIT", "development"),
        "java",
        System.getProperty("java.version", "unknown"),
        "simulated-oauth-loopback",
        true);
  }

  private static String exact(String value, String expected) {
    if (!expected.equals(value)) throw new IllegalArgumentException("Invalid runtime information.");
    return value;
  }

  private static String bounded(String value, Pattern pattern, String label) {
    if (value == null || !pattern.matcher(value).matches()) {
      throw new IllegalArgumentException(label + " is invalid.");
    }
    return value;
  }

  private static String safeText(String value, String label, int maximumLength) {
    if (value == null
        || value.isBlank()
        || value.length() > maximumLength
        || value.chars().anyMatch(Character::isISOControl)) {
      throw new IllegalArgumentException(label + " is invalid.");
    }
    return value;
  }
}
