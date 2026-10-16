package io.tracepermit.contracts.redaction;

import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Declares a rule for matching and transforming sensitive fields, paths, or patterns.
 */
public final class RedactionRule {
  private final String name;
  private final RedactionStrategy strategy;
  private final Set<String> fieldNames;
  private final Set<String> fieldPaths;
  private final Pattern valuePattern;

  public RedactionRule(
      String name,
      RedactionStrategy strategy,
      Set<String> fieldNames,
      Set<String> fieldPaths,
      Pattern valuePattern) {
    this.name = Objects.requireNonNull(name, "name");
    this.strategy = Objects.requireNonNull(strategy, "strategy");
    if (fieldNames == null && fieldPaths == null && valuePattern == null) {
      throw new IllegalArgumentException("At least one matching criterion must be specified.");
    }
    Set<String> normalizedNames = new HashSet<>();
    if (fieldNames != null) {
      for (String field : fieldNames) {
        if (field != null && !field.isBlank()) {
          normalizedNames.add(field.trim().toLowerCase(Locale.ROOT));
        }
      }
    }
    this.fieldNames = Collections.unmodifiableSet(normalizedNames);

    Set<String> normalizedPaths = new HashSet<>();
    if (fieldPaths != null) {
      for (String path : fieldPaths) {
        if (path != null && !path.isBlank()) {
          normalizedPaths.add(normalizePath(path));
        }
      }
    }
    this.fieldPaths = Collections.unmodifiableSet(normalizedPaths);
    this.valuePattern = valuePattern;
  }

  public static RedactionRule forFieldNames(
      String name, RedactionStrategy strategy, String... names) {
    return new RedactionRule(name, strategy, Set.of(names), null, null);
  }

  public static RedactionRule forFieldNames(
      String name, RedactionStrategy strategy, Set<String> names) {
    return new RedactionRule(name, strategy, names, null, null);
  }

  public static RedactionRule forFieldPaths(
      String name, RedactionStrategy strategy, String... paths) {
    return new RedactionRule(name, strategy, null, Set.of(paths), null);
  }

  public static RedactionRule forFieldPaths(
      String name, RedactionStrategy strategy, Set<String> paths) {
    return new RedactionRule(name, strategy, null, paths, null);
  }

  public static RedactionRule forValuePattern(
      String name, RedactionStrategy strategy, Pattern valuePattern) {
    return new RedactionRule(name, strategy, null, null, valuePattern);
  }

  public String name() {
    return name;
  }

  public RedactionStrategy strategy() {
    return strategy;
  }

  public Set<String> fieldNames() {
    return fieldNames;
  }

  public Set<String> fieldPaths() {
    return fieldPaths;
  }

  public Pattern valuePattern() {
    return valuePattern;
  }

  public boolean matchesFieldName(String fieldName) {
    if (fieldName == null || fieldNames.isEmpty()) {
      return false;
    }
    return fieldNames.contains(fieldName.trim().toLowerCase(Locale.ROOT));
  }

  public boolean matchesFieldPath(String currentPath) {
    if (currentPath == null || fieldPaths.isEmpty()) {
      return false;
    }
    return fieldPaths.contains(normalizePath(currentPath));
  }

  public boolean matchesValue(String text) {
    if (text == null || valuePattern == null) {
      return false;
    }
    return valuePattern.matcher(text).find();
  }

  private static String normalizePath(String path) {
    String trimmed = path.trim();
    if (trimmed.startsWith("/")) {
      trimmed = trimmed.substring(1).replace('/', '.');
    }
    return trimmed;
  }
}
