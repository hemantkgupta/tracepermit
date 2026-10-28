package io.tracepermit.agentcore.registration;

final class MariaDb114OrgSandboxLifecycleTest extends OrgSandboxLifecycleContract {
  @Override
  String image() {
    return "mariadb:11.4.13";
  }
}
