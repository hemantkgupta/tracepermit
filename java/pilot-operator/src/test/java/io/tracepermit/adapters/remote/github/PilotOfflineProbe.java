package io.tracepermit.adapters.remote.github;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

/** Separate JVM activates a denying DNS SPI and traps provider/transport construction. */
public final class PilotOfflineProbe {
  private PilotOfflineProbe() {}
  public static void main(String[] args) {
    try { InetAddress.getAllByName("synthetic-offline-tripwire.invalid"); }
    catch (UnknownHostException expected) { /* deliberately denied by the test SPI */ }
    if (PilotNoDnsProvider.LOOKUPS.getAndSet(0) != 1) throw new AssertionError("DNS_CONTROL_INACTIVE");
    var providers = new AtomicInteger();
    var result = PilotOperatorMain.bounded(new String[]{"--check"}, System.getenv("TRACEPERMIT_PILOT_CONFIGURATION"), config -> {
      providers.incrementAndGet(); throw new AssertionError("PROVIDER_CONTROL_ENTERED");
    }, Duration.ofSeconds(5));
    if (result != PilotOperatorMain.Result.CHECK_VALID || providers.get() != 0 || PilotNoDnsProvider.LOOKUPS.get() != 0) {
      throw new AssertionError("OFFLINE_CONTROL_FAILED");
    }
    System.out.println("OFFLINE_ZERO_DNS_ZERO_PROVIDER");
  }
}
