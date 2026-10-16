package io.tracepermit.policy;

import java.util.Locale;

public enum PolicyState {
  STAGED,
  ACTIVE,
  ROLLED_BACK,
  DEPRECATED;

  public String serialized() {
    return name().toLowerCase(Locale.ROOT);
  }
}
