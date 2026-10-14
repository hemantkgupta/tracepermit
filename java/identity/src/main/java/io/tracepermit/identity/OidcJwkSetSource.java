package io.tracepermit.identity;

import java.net.URI;

@FunctionalInterface
public interface OidcJwkSetSource {
  String fetch(URI configuredJwksUri);
}
