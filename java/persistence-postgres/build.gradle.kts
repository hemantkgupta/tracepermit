dependencies {
  api(project(":execution"))
  implementation(project(":contracts"))
  implementation("org.flywaydb:flyway-core:11.8.2")
  implementation("org.flywaydb:flyway-database-postgresql:11.8.2")
  api("org.postgresql:postgresql:42.7.5")

  testImplementation(project(":application"))
  testImplementation(project(":identity"))
  testImplementation(project(":adapters-remote"))
  testImplementation(project(":policy"))
  testImplementation(project(":security"))
  testImplementation(project(":simulator"))
  testImplementation(platform("org.testcontainers:testcontainers-bom:1.21.4"))
  testImplementation("org.testcontainers:postgresql")
  testImplementation("org.postgresql:postgresql:42.7.5")
  testRuntimeOnly("org.slf4j:slf4j-nop:1.7.36")
}

tasks.register<JavaExec>("runApprovalDemo") {
  group = "application"
  description = "Runs the simulator-only request-bound human-approval scenario."
  dependsOn(tasks.named("testClasses"))
  classpath = sourceSets["test"].runtimeClasspath
  mainClass = "io.tracepermit.persistence.postgres.RequestBoundApprovalDemoMain"
  standardInput = System.`in`
  systemProperty("tracepermit.repositoryRoot", rootDir.parentFile.absolutePath)
}

tasks.register<JavaExec>("runProductionDeletionApprovalDemo") {
  group = "application"
  description = "Runs the signed-workload, simulator-only production deletion approval scenario."
  dependsOn(tasks.named("testClasses"))
  classpath = sourceSets["test"].runtimeClasspath
  mainClass = "io.tracepermit.persistence.postgres.ProductionDeletionApprovalDemoMain"
  standardInput = System.`in`
  systemProperty("tracepermit.repositoryRoot", rootDir.parentFile.absolutePath)
}
