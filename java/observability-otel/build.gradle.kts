dependencies {
  api(project(":application"))
  implementation("io.opentelemetry:opentelemetry-api:1.67.0")

  testImplementation("io.opentelemetry:opentelemetry-sdk:1.67.0")
  testImplementation("io.opentelemetry:opentelemetry-sdk-testing:1.67.0")
}
