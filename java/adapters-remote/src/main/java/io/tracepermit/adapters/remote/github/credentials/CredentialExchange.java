package io.tracepermit.adapters.remote.github.credentials;

import com.fasterxml.jackson.databind.JsonNode;

/** Package-local transport seam. Production callers cannot choose a destination or transport. */
interface CredentialExchange extends AutoCloseable {
  record Reply(int status, boolean paginated, byte[] bytes) {
    @Override public String toString() { return "CredentialReply[redacted]"; }
  }
  Reply installation(String jwt) throws Exception;
  Reply token(String jwt, JsonNode restrictions) throws Exception;
  @Override void close();
  @FunctionalInterface interface Factory { CredentialExchange open() throws Exception; }
}
