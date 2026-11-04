import org.gradle.jvm.application.tasks.CreateStartScripts

plugins { application }

dependencies {
  implementation(project(":identity"))
  implementation(project(":contracts"))
  implementation(project(":adapters-http"))
  implementation(project(":adapters-remote"))
  implementation(project(":agentcore-registration-cli"))
  implementation(project(":agentcore-organization-lifecycle-cli"))
  testImplementation(testFixtures(project(":agentcore-organization-lifecycle-cli")))
}

application {
  applicationName = "tracepermit-auth0-reviewer"
  mainClass = "io.tracepermit.auth0reviewer.Auth0ReviewerMain"
  applicationDefaultJvmArgs = listOf("-Xms16m", "-Xmx64m", "-XX:ActiveProcessorCount=2")
}

tasks.test {
  dependsOn(tasks.installDist)
  systemProperty("tracepermit.reviewer.install", layout.buildDirectory.dir("install/tracepermit-auth0-reviewer").get().asFile.absolutePath)
  systemProperty("tracepermit.reviewer.testClasspath", sourceSets.test.get().runtimeClasspath.asPath)
}

val operationReviewScripts by tasks.registering(CreateStartScripts::class) {
  applicationName = "tracepermit-auth0-operation-review"
  mainClass.set("io.tracepermit.auth0reviewer.Auth0OperationReviewMain")
  outputDir = layout.buildDirectory.dir("operation-review-scripts").get().asFile
  classpath = files(tasks.jar) + configurations.runtimeClasspath.get()
  defaultJvmOpts = application.applicationDefaultJvmArgs
}

distributions.named("main") {
  contents {
    from(operationReviewScripts) {
      into("bin")
      filePermissions { unix("rwxr-xr-x") }
    }
  }
}
