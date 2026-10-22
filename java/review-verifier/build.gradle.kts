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
    "tracepermit.approvalReviewVector",
    rootProject.projectDir.parentFile
      .resolve("contracts/vectors/approval-review-export-v1.json")
      .absolutePath,
  )
  systemProperty(
    "tracepermit.reviewVerifierInstallDirectory",
    layout.buildDirectory.dir("install/review-verifier").get().asFile.absolutePath,
  )
}
