dependencies {
  api(project(":adapters-remote"))
  implementation(project(":persistence-identity"))
  implementation(project(":persistence-operations"))
  testImplementation(project(":contracts"))
  testImplementation(project(":application"))
  testImplementation(project(":persistence-identity"))
  testImplementation(project(":persistence-operations"))
  testImplementation(platform("org.testcontainers:testcontainers-bom:1.21.4"))
  testImplementation("org.testcontainers:postgresql")
  testImplementation("org.testcontainers:mariadb")
  testRuntimeOnly("org.slf4j:slf4j-nop:1.7.36")
}

tasks.test {
  // Child JVMs need the actual complete runtime classpath, not Gradle's worker launcher jar.
  systemProperty("tracepermit.registration.childClasspath", sourceSets.test.get().runtimeClasspath.asPath)
}
