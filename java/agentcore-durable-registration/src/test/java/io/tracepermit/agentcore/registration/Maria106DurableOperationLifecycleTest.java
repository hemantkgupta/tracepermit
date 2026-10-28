package io.tracepermit.agentcore.registration;

final class Maria106DurableOperationLifecycleTest extends DurableOperationLifecycleContract {
  @Override
  String image() {
    return "mariadb:10.6.25";
  }
}
