package io.tracepermit.adapters.remote.github.credentials;

import java.security.PrivateKey;

/** Trusted key source, injected only by the private operator factory or package-local tests. */
@FunctionalInterface
interface AppSigningKeySource {
  PrivateKey read() throws Exception;
}
