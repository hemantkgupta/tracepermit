plugins {
  application
}

dependencies {
  implementation(project(":contracts"))
  implementation(project(":policy"))
  implementation(project(":application"))
  implementation(project(":execution"))
  implementation(project(":identity"))
  implementation(project(":security"))
  implementation(project(":simulator"))
  implementation(project(":adapters-remote"))
  implementation(project(":persistence-postgres"))

  testImplementation(project(":endpoint-connector"))
  testImplementation(platform("org.testcontainers:testcontainers-bom:1.21.4"))
  testImplementation("org.testcontainers:postgresql")
  testRuntimeOnly("org.slf4j:slf4j-nop:1.7.36")
}

application {
  mainClass = "io.tracepermit.adapters.http.DevelopmentBackendMain"
}

tasks.register<JavaExec>("runSimulatorApprovals") {
  group = "application"
  description = "Runs or offline-validates the loopback-only durable simulator approval API."
  classpath = sourceSets["main"].runtimeClasspath
  mainClass = "io.tracepermit.adapters.http.SimulatorApprovalMain"
}

tasks.test {
  systemProperty("tracepermit.approvalRuntimeClasspath", sourceSets["main"].runtimeClasspath.asPath)
}

tasks.register<JavaExec>("runApprovalBrowserQualification") {
  group = "verification"
  description = "Runs a test-classpath-only synthetic IdP and disposable simulator inbox for browser qualification."
  dependsOn(tasks.named("testClasses"))
  classpath = sourceSets["test"].runtimeClasspath
  mainClass = "io.tracepermit.adapters.http.SimulatorApprovalBrowserQualificationMain"
  standardInput = System.`in`
  systemProperty("tracepermit.repositoryRoot", rootDir.parentFile.absolutePath)
}

tasks.register<JavaExec>("runHostedClientOAuth") {
  group = "application"
  description = "Runs the simulator-only hosted-client OAuth profile behind a private tunnel."
  classpath = sourceSets["main"].runtimeClasspath
  mainClass = "io.tracepermit.adapters.http.HostedClientOAuthBackendMain"
  standardInput = System.`in`
}

tasks.register<JavaExec>("runSignedScheduledSemanticDemo") {
  group = "application"
  description = "Runs the signed scheduled-workload semantic-control scenario."
  dependsOn(tasks.named("testClasses"))
  classpath = sourceSets["test"].runtimeClasspath
  mainClass = "io.tracepermit.adapters.http.SignedScheduledSemanticDemoMain"
  standardInput = System.`in`
  systemProperty("tracepermit.repositoryRoot", rootDir.parentFile.absolutePath)
}

tasks.register<JavaExec>("runSemanticQualification") {
  group = "verification"
  description = "Measures local semantic enforcement and proves fail-secure behavior."
  dependsOn(tasks.named("testClasses"))
  classpath = sourceSets["test"].runtimeClasspath
  mainClass = "io.tracepermit.adapters.http.SemanticQualificationMain"
  systemProperty("tracepermit.repositoryRoot", rootDir.parentFile.absolutePath)
}
