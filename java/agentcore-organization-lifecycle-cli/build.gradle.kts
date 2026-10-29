plugins { application }

val bootstrap by sourceSets.creating
tasks.named<JavaCompile>(bootstrap.compileJavaTaskName) {
  options.release = 8
  options.compilerArgs.add("-Xlint:-options")
}
tasks.jar { from(bootstrap.output) }

dependencies {
  implementation(project(":agentcore-registration-cli"))
  implementation(project(":agentcore-durable-registration"))
  implementation(project(":adapters-remote"))
  implementation(project(":application"))
  implementation(project(":contracts"))
  implementation(project(":identity"))
  implementation(project(":persistence-operations"))

  testImplementation(project(":persistence-identity"))
  testImplementation(platform("org.testcontainers:testcontainers-bom:1.21.4"))
  testImplementation("org.testcontainers:postgresql")
  testRuntimeOnly("org.slf4j:slf4j-nop:1.7.36")
}

application {
  applicationName = "tracepermit-synthetic-organization-operator"
  mainClass = "io.tracepermit.agentcore.organizationcli.Bootstrap"
  applicationDefaultJvmArgs = listOf("-Xms16m", "-Xmx64m", "-XX:ActiveProcessorCount=2")
}

tasks.test {
  dependsOn(tasks.distTar)
  jvmArgs("-Djava.security.manager=allow")
  systemProperty(
    "tracepermit.organization.archive",
    tasks.distTar.get().archiveFile.get().asFile.absolutePath,
  )
  systemProperty(
    "tracepermit.organization.testClasspath",
    sourceSets.test.get().runtimeClasspath.asPath,
  )
  systemProperty("tracepermit.repositoryRoot", rootProject.projectDir.parentFile.absolutePath)
}
