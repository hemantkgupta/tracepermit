package io.tracepermit.policy;

import java.util.List;

/** Trusted, server-derived context supplied alongside an immutable canonical action. */
public record ClassificationContext(List<String> signals, int scoreAdjustment) {
  public static final ClassificationContext EMPTY = new ClassificationContext(List.of(), 0);

  public ClassificationContext {
    signals = List.copyOf(signals);
    if (signals.size() > 8 || signals.stream().anyMatch(value -> value == null || value.isBlank())) {
      throw new IllegalArgumentException("Classification context signals are invalid.");
    }
    if (scoreAdjustment < 0 || scoreAdjustment > 100) {
      throw new IllegalArgumentException("Classification context score is invalid.");
    }
  }
}
