package io.tracepermit.agentcore.lifecyclecli;

final class Maria106InstalledLifecycleTest extends InstalledLifecycleContract {
  @Override
  String image() {
    return "mariadb:10.6.25";
  }
}
