dependencies {
  api(project(":application"))
  implementation("io.opentelemetry:opentelemetry-api:1.49.0")

  testImplementation("io.opentelemetry:opentelemetry-sdk:1.49.0")
  testImplementation("io.opentelemetry:opentelemetry-sdk-testing:1.49.0")
}
