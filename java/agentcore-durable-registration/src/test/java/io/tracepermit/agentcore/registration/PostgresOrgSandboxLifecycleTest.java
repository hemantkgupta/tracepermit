package io.tracepermit.agentcore.registration;

final class PostgresOrgSandboxLifecycleTest extends OrgSandboxLifecycleContract {
  @Override
  String image() {
    return "postgres:16.4-alpine";
  }
}
