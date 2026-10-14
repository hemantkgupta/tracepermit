package io.tracepermit.contracts;

public final class ContractViolation extends IllegalArgumentException {
  private static final long serialVersionUID = 1L;
  private final String path;

  public ContractViolation(String path, String message) {
    super(message);
    this.path = path;
  }

  public String path() {
    return path;
  }
}
