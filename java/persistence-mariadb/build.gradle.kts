dependencies {
  api(project(":execution"))
  api(project(":security"))
  implementation("org.flywaydb:flyway-core:13.10.3")
  implementation("org.flywaydb:flyway-mysql:13.10.3")
  api("org.mariadb.jdbc:mariadb-java-client:3.5.7")

  testImplementation(platform("org.testcontainers:testcontainers-bom:1.21.4"))
  testImplementation("org.testcontainers:mariadb")
  testRuntimeOnly("org.slf4j:slf4j-nop:1.7.36")
}
