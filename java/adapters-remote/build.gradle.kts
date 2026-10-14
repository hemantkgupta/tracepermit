dependencies {
  api(project(":identity"))
  implementation(project(":application"))
  implementation(project(":contracts"))
  implementation("com.squareup.okhttp3:okhttp:4.12.0")

  testImplementation(project(":simulator"))
  testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
  testImplementation("com.squareup.okhttp3:okhttp-tls:4.12.0")
}
