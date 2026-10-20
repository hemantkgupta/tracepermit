plugins {
  application
}

dependencies {
  implementation(project(":application"))
  implementation(project(":contracts"))
  implementation(project(":execution"))
  implementation(project(":persistence-postgres"))
  implementation(project(":security"))
  implementation(project(":simulator"))
  implementation("org.postgresql:postgresql:42.7.14")

  testImplementation(platform("org.testcontainers:testcontainers-bom:1.21.4"))
  testImplementation("org.testcontainers:postgresql")
  testRuntimeOnly("org.slf4j:slf4j-nop:1.7.36")
}

application {
  mainClass = "io.tracepermit.executor.simulator.VerifierOnlyExecutorMain"
  applicationName = "tracepermit-verifier-only-executor"
}

distributions {
  named("main") {
    distributionBaseName.set("tracepermit-verifier-only-executor")
    contents {
      from("../../config/verifier-only-executor.example.json") {
        into("examples")
      }
      from("../../docs/runbooks/verifier-only-simulator-executor.md") {
        into("docs")
      }
      from("../../LICENSE") {
        into("docs")
      }
      from("../../NOTICE") {
        into("docs")
      }
    }
  }
}

tasks.test {
  systemProperty("tracepermit.executorRuntimeClasspath", sourceSets["main"].runtimeClasspath.asPath)
}
