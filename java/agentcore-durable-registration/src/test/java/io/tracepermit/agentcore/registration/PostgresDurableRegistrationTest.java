package io.tracepermit.agentcore.registration;

final class PostgresDurableRegistrationTest extends DurableRegistrationContract {
  @Override String image() { return "postgres:16.4-alpine"; }
}
