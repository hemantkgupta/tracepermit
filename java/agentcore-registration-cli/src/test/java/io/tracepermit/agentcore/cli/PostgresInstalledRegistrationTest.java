package io.tracepermit.agentcore.cli;
final class PostgresInstalledRegistrationTest extends InstalledRegistrationContract {
  @Override String image() { return "postgres:16.4-alpine"; }
}
