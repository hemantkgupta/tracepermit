package io.tracepermit.simulator;

import io.tracepermit.application.TargetExecutionException;

public final class SimulatorException extends TargetExecutionException {
  private static final long serialVersionUID = 1L;

  public SimulatorException(String code, String message) {
    super(code, message);
  }
}
