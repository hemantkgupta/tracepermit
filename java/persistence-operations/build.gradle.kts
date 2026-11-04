dependencies {
  api(project(":application"))
  api(project(":adapters-remote")) // Reuse the pure, closed GitHub binding; no transport port in the writer.
  implementation("org.flywaydb:flyway-core:13.10.3")
  implementation("org.flywaydb:flyway-database-postgresql:13.10.3")
  implementation("org.flywaydb:flyway-mysql:13.10.3")
  runtimeOnly("org.postgresql:postgresql:42.7.14")
  runtimeOnly("org.mariadb.jdbc:mariadb-java-client:4.0.0")

  testImplementation(platform("org.testcontainers:testcontainers-bom:1.21.4"))
  testImplementation("org.testcontainers:postgresql")
  testImplementation("org.testcontainers:mariadb")
  testRuntimeOnly("org.slf4j:slf4j-nop:1.7.36")
}

tasks.test {
  systemProperty("tracepermit.operationsTestClasspath", sourceSets["test"].runtimeClasspath.asPath)
}
