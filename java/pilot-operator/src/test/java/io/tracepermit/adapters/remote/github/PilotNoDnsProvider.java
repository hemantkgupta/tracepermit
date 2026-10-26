package io.tracepermit.adapters.remote.github;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.net.spi.InetAddressResolver;
import java.net.spi.InetAddressResolverProvider;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

/** Test-process DNS tripwire: the positive control never touches the host resolver. */
public final class PilotNoDnsProvider extends InetAddressResolverProvider {
  static final AtomicInteger LOOKUPS = new AtomicInteger();
  @Override public String name() { return "synthetic-no-dns"; }
  @Override public InetAddressResolver get(Configuration configuration) {
    return new InetAddressResolver() {
      @Override public Stream<InetAddress> lookupByName(String host, LookupPolicy policy) throws UnknownHostException {
        LOOKUPS.incrementAndGet(); throw new UnknownHostException("SYNTHETIC_DNS_TRIPWIRE");
      }
      @Override public String lookupByAddress(byte[] address) throws UnknownHostException {
        LOOKUPS.incrementAndGet(); throw new UnknownHostException("SYNTHETIC_DNS_TRIPWIRE");
      }
    };
  }
}
