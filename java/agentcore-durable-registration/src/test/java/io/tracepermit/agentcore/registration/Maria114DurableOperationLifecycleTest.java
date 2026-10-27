package io.tracepermit.agentcore.registration;

final class Maria114DurableOperationLifecycleTest extends DurableOperationLifecycleContract {
  @Override
  String image() {
    return "mariadb:11.4.13";
  }
}
