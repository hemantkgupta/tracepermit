package io.tracepermit.policy;

import java.util.List;

public record Classification(
    String actionClass,
    String severity,
    int score,
    String operation,
    List<String> signals,
    List<String> semanticIntents,
    String classifierKind,
    String classifierVersion) {
  public Classification(
      String actionClass,
      String severity,
      int score,
      String operation,
      List<String> signals,
      String classifierKind,
      String classifierVersion) {
    this(
        actionClass,
        severity,
        score,
        operation,
        signals,
        List.of(),
        classifierKind,
        classifierVersion);
  }

  public Classification {
    signals = List.copyOf(signals);
    semanticIntents = List.copyOf(semanticIntents);
  }
}
