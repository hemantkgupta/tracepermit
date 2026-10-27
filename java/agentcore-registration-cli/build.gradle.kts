plugins { application }

// A Java 8 bytecode entry point gives an explicit diagnostic before loading Java 21 classes.
val bootstrap by sourceSets.creating
tasks.named<JavaCompile>(bootstrap.compileJavaTaskName) {
  options.release = 8
  options.compilerArgs.add("-Xlint:-options")
}
tasks.jar { from(bootstrap.output) }

dependencies {
  implementation(project(":agentcore-durable-registration"))
  implementation(project(":adapters-remote"))
  implementation(project(":identity"))
  implementation(project(":application"))
  implementation(project(":contracts"))
  implementation(project(":persistence-identity"))
  testImplementation(project(":persistence-operations"))
  testImplementation(platform("org.testcontainers:testcontainers-bom:1.21.4"))
  testImplementation("org.testcontainers:postgresql")
  testImplementation("org.testcontainers:mariadb")
  testRuntimeOnly("org.slf4j:slf4j-nop:1.7.36")
}

application {
  applicationName = "tracepermit-synthetic-registration"
  mainClass = "io.tracepermit.agentcore.cli.Bootstrap"
  applicationDefaultJvmArgs = listOf("-Xms16m", "-Xmx64m", "-XX:ActiveProcessorCount=2")
}

tasks.test {
  jvmArgs("-Djava.security.manager=allow")
  dependsOn(tasks.distTar)
  systemProperty("tracepermit.cli.archive", tasks.distTar.get().archiveFile.get().asFile.absolutePath)
  systemProperty("tracepermit.cli.testClasspath", sourceSets.test.get().runtimeClasspath.asPath)
}
