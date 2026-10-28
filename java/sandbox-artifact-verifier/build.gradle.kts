plugins { application }

dependencies {
  implementation(platform("com.fasterxml.jackson:jackson-bom:2.18.3"))
  implementation("com.fasterxml.jackson.core:jackson-databind")
}

application {
  applicationName = "tracepermit-sandbox-artifact-verifier"
  mainClass = "io.tracepermit.sandbox.verifier.SandboxArtifactVerifier"
}
