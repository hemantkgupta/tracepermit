package io.tracepermit.agentcore.httpcli;

final class PostgresInstalledHttpRegistrationTest extends InstalledHttpRegistrationContract {
  @Override
  String image() {
    return "postgres:16.4-alpine";
  }
}
