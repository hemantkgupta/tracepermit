dependencies {
  api(project(":identity"))
  implementation(project(":application"))
  implementation(project(":contracts"))
  implementation("com.squareup.okhttp3:okhttp:5.5.0")

  testImplementation(project(":simulator"))
  testImplementation("com.squareup.okhttp3:mockwebserver:5.5.0")
  testImplementation("com.squareup.okhttp3:okhttp-tls:5.5.0")
}
