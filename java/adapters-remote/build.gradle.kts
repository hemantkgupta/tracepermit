dependencies {
  api(project(":identity"))
  implementation(project(":application"))
  implementation(project(":contracts"))
  implementation(project(":execution"))
  implementation(project(":security"))
  implementation(project(":policy"))
  implementation("com.squareup.okhttp3:okhttp:5.5.0")

  testImplementation(project(":simulator"))
  // Test-only composition of the unchanged ingress and JDBC registration store.
  testImplementation(project(":persistence-operations"))
  testImplementation(platform("org.testcontainers:testcontainers-bom:2.0.5"))
  testImplementation("org.testcontainers:postgresql")
  testImplementation("org.testcontainers:mariadb")
  testRuntimeOnly("org.slf4j:slf4j-nop:1.7.36")
  testImplementation("com.squareup.okhttp3:mockwebserver:5.5.0")
  testImplementation("com.squareup.okhttp3:okhttp-tls:5.5.0")
}
