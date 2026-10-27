package io.tracepermit.agentcore.registration;

final class PostgresDurableRegistrationHttpTest extends DurableRegistrationHttpContract {
  @Override String image() { return "postgres:16.4-alpine"; }
}
