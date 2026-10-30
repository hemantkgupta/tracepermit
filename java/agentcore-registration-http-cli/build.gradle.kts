plugins { application }

// A Java 8 bytecode entry point reports an unsupported VM before Java 21 classes load.
val bootstrap by sourceSets.creating
tasks.named<JavaCompile>(bootstrap.compileJavaTaskName) {
  options.release = 8
  options.compilerArgs.add("-Xlint:-options")
}
tasks.jar { from(bootstrap.output) }

dependencies {
  implementation(project(":agentcore-registration-cli"))
  implementation(project(":contracts"))
  testImplementation(project(":persistence-identity"))
  testImplementation(project(":persistence-operations"))
  testImplementation(platform("org.testcontainers:testcontainers-bom:2.0.5"))
  testImplementation("org.testcontainers:postgresql")
  testImplementation("org.testcontainers:mariadb")
  testRuntimeOnly("org.slf4j:slf4j-nop:1.7.36")
}

application {
  applicationName = "tracepermit-synthetic-registration-http"
  mainClass = "io.tracepermit.agentcore.httpcli.Bootstrap"
  applicationDefaultJvmArgs = listOf("-Xms16m", "-Xmx64m", "-XX:ActiveProcessorCount=2")
}

tasks.test {
  jvmArgs("-Djava.security.manager=allow")
  dependsOn(tasks.distTar)
  systemProperty("tracepermit.httpcli.archive", tasks.distTar.get().archiveFile.get().asFile.absolutePath)
  systemProperty("tracepermit.httpcli.testClasspath", sourceSets.test.get().runtimeClasspath.asPath)
}
