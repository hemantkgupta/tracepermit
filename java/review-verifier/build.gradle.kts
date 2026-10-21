plugins {
  application
}

dependencies {
  implementation(project(":contracts"))
}

application {
  mainClass = "io.tracepermit.review.ApprovalReviewVerifierMain"
}

tasks.test {
  dependsOn(tasks.named("installDist"))
  systemProperty("tracepermit.reviewVerifierRuntimeClasspath", sourceSets["main"].runtimeClasspath.asPath)
  systemProperty(
    "tracepermit.reviewVerifierInstallDirectory",
    layout.buildDirectory.dir("install/review-verifier").get().asFile.absolutePath,
  )
}
