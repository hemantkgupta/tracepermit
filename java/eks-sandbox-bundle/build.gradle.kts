plugins { application }

dependencies {
  implementation(platform("com.fasterxml.jackson:jackson-bom:2.18.3"))
  implementation("com.fasterxml.jackson.core:jackson-databind")
}

application {
  applicationName = "tracepermit-eks-sandbox"
  mainClass = "io.tracepermit.sandbox.eks.EksSandboxMain"
  applicationDefaultJvmArgs = listOf("-Xms16m", "-Xmx64m", "-XX:ActiveProcessorCount=2")
}

tasks.processResources {
  from(rootProject.layout.projectDirectory.file("../deploy/sandbox/eks/bundle.template.json"))
}

tasks.test {
  dependsOn(tasks.distTar)
  jvmArgs("-Djava.security.manager=allow")
  systemProperty("tracepermit.eks.archive", tasks.distTar.get().archiveFile.get().asFile.absolutePath)
}
