package io.tracepermit.endpoint;

@FunctionalInterface
public interface TokenIdGenerator {
  String nextTokenId();
}
