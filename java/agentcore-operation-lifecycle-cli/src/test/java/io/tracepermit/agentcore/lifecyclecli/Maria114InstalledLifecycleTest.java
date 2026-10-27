package io.tracepermit.agentcore.lifecyclecli;

final class Maria114InstalledLifecycleTest extends InstalledLifecycleContract {
  @Override
  String image() {
    return "mariadb:11.4.13";
  }
}
