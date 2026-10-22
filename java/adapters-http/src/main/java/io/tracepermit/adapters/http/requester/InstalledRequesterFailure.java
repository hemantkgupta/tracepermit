package io.tracepermit.adapters.http.requester;

/** Stable, secret-free operator failure. Never attach a provider or filesystem cause. */
final class InstalledRequesterFailure extends RuntimeException {
  private static final long serialVersionUID = 1L;
  private final String code;

  InstalledRequesterFailure(String code) {
    super(code);
    this.code = code;
  }

  String code() {
    return code;
  }
}
