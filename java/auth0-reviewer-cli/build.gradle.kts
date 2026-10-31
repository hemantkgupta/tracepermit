plugins { application }

dependencies {
  implementation(project(":identity"))
  implementation(project(":contracts"))
  implementation(project(":adapters-http"))
  implementation(project(":adapters-remote"))
  implementation(project(":agentcore-registration-cli"))
}

application {
  applicationName = "tracepermit-auth0-reviewer"
  mainClass = "io.tracepermit.auth0reviewer.Auth0ReviewerMain"
  applicationDefaultJvmArgs = listOf("-Xms16m", "-Xmx64m", "-XX:ActiveProcessorCount=2")
}

tasks.test {
  systemProperty("tracepermit.reviewer.testClasspath", sourceSets.test.get().runtimeClasspath.asPath)
}
