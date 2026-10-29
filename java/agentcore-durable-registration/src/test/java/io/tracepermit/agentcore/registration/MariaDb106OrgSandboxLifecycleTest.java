package io.tracepermit.agentcore.registration;

final class MariaDb106OrgSandboxLifecycleTest extends OrgSandboxLifecycleContract {
  @Override
  String image() {
    return "mariadb:10.6.25";
  }
}
