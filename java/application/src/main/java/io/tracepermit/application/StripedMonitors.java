package io.tracepermit.application;

import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Fixed lock striping for per-principal authorization windows. Striping keeps the
 * sequence snapshot, policy evaluation, and sequence record atomic per principal
 * without serializing unrelated principals on one monitor. ReentrantLock keeps the
 * window virtual-thread friendly on Java 21.
 */
final class StripedMonitors {
  static final int DEFAULT_STRIPES = 64;
  private final ReentrantLock[] locks;

  StripedMonitors() {
    this(DEFAULT_STRIPES);
  }

  StripedMonitors(int stripes) {
    if (Integer.bitCount(stripes) != 1 || stripes < 1) {
      throw new IllegalArgumentException("The stripe count must be a positive power of two.");
    }
    this.locks = new ReentrantLock[stripes];
    for (int index = 0; index < locks.length; index++) {
      locks[index] = new ReentrantLock();
    }
  }

  ReentrantLock lockFor(String principalKey) {
    Objects.requireNonNull(principalKey);
    return locks[Math.floorMod(principalKey.hashCode(), locks.length)];
  }
}
