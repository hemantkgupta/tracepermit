plugins {
  application
}

dependencies {
  implementation(project(":contracts"))
  implementation(project(":identity"))
}

application {
  mainClass = "io.tracepermit.endpoint.EndpointConnectorMain"
}
