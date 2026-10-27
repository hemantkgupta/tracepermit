package io.tracepermit.agentcore.lifecyclecli;

final class PostgresInstalledLifecycleTest extends InstalledLifecycleContract {
  @Override
  String image() {
    return "postgres:16.4-alpine";
  }
}
