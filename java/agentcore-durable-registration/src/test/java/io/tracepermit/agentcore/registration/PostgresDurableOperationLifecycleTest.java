package io.tracepermit.agentcore.registration;

final class PostgresDurableOperationLifecycleTest extends DurableOperationLifecycleContract {
  @Override
  String image() {
    return "postgres:16.4-alpine";
  }
}
