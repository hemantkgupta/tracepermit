import org.gradle.api.tasks.SourceSetContainer
import org.gradle.jvm.application.tasks.CreateStartScripts

plugins {
  application
  `java-test-fixtures`
}

dependencies {
  implementation(platform("com.fasterxml.jackson:jackson-bom:2.22.4"))
  implementation("com.fasterxml.jackson.core:jackson-databind")
  testFixturesImplementation(project(":agentcore-registration-cli"))
  testFixturesImplementation(platform("com.fasterxml.jackson:jackson-bom:2.22.4"))
  testFixturesImplementation("com.fasterxml.jackson.core:jackson-databind")
}

application {
  applicationName = "tracepermit-eks-sandbox"
  mainClass = "io.tracepermit.sandbox.eks.EksSandboxMain"
  applicationDefaultJvmArgs = listOf("-Xms16m", "-Xmx64m", "-XX:ActiveProcessorCount=2")
}

val acceptanceScripts by tasks.registering(CreateStartScripts::class) {
  applicationName = "tracepermit-eks-acceptance"
  mainClass.set("io.tracepermit.sandbox.eks.EksAcceptanceMain")
  outputDir = layout.buildDirectory.dir("acceptanceScripts").get().asFile
  classpath = files(tasks.jar) + configurations.runtimeClasspath.get()
  defaultJvmOpts = application.applicationDefaultJvmArgs
}
distributions.named("main") {
  contents {
    from(rootProject.layout.projectDirectory.dir("../deploy/sandbox/eks")) {
      include("acceptance-plan.example.json", "acceptance-snapshot.example.json")
      into("examples")
    }
    from(acceptanceScripts) {
      into("bin")
      filePermissions { unix("rwxr-xr-x") }
    }
  }
}

tasks.processResources {
  from(rootProject.layout.projectDirectory.file("../deploy/sandbox/eks/bundle.template.json"))
}

tasks.test {
  dependsOn(tasks.distTar)
  jvmArgs("-Djava.security.manager=allow")
  systemProperty("tracepermit.eks.archive", tasks.distTar.get().archiveFile.get().asFile.absolutePath)
}

// Explicit, human-run container qualification. It is not an automatic check dependency.
// Reuse accepted test-only identity/database fixtures; never copy production trust code.
evaluationDependsOn(":agentcore-operation-lifecycle-cli")
val lifecycleProject = project(":agentcore-operation-lifecycle-cli")
val lifecycleTests = lifecycleProject.extensions.getByType<SourceSetContainer>().named("test")
val containerTest by sourceSets.creating {
  compileClasspath += sourceSets.named("testFixtures").get().output + lifecycleTests.get().runtimeClasspath
  runtimeClasspath += output + compileClasspath
}
configurations[containerTest.implementationConfigurationName].extendsFrom(configurations.testImplementation.get())
configurations[containerTest.runtimeOnlyConfigurationName].extendsFrom(configurations.testRuntimeOnly.get())

val containerProbeJar by tasks.registering(Jar::class) {
  dependsOn(tasks.named("testFixturesClasses"))
  archiveBaseName.set("tracepermit-eks-container-probe")
  from(sourceSets.named("testFixtures").get().output) {
    include("io/tracepermit/sandbox/eks/rehearsal/ContainerInputProbe*.class")
  }
}

tasks.register<JavaExec>("assembleContainerRehearsal") {
  description = "Human-run offline assembly from an existing local base ID; never pulls or pushes."
  group = "distribution"
  dependsOn(tasks.distTar, lifecycleProject.tasks.named("distTar"), containerProbeJar)
  classpath = sourceSets.named("testFixtures").get().runtimeClasspath
  mainClass.set("io.tracepermit.sandbox.eks.rehearsal.RehearsalImageAssembler")
  systemProperty("tracepermit.container.lifecycleArchive",
      lifecycleProject.tasks.named<Tar>("distTar").get().archiveFile.get().asFile.absolutePath)
  systemProperty("tracepermit.container.bundleArchive", tasks.distTar.get().archiveFile.get().asFile.absolutePath)
  listOf("docker", "dockerEndpoint", "baseImage", "evidence", "sourceCommit").forEach { name ->
    providers.gradleProperty("tracepermit.container.$name").orNull?.let {
      systemProperty("tracepermit.container.$name", it)
    }
  }
}

tasks.register<Test>("containerQualification") {
  description = "Human-run OCI stdin/containment/lifecycle gates; requires prepared local images."
  group = "verification"
  dependsOn(containerProbeJar, lifecycleProject.tasks.named("testClasses"))
  testClassesDirs = containerTest.output.classesDirs
  classpath = containerTest.runtimeClasspath
  maxParallelForks = 1
  failFast = true
  environment("TESTCONTAINERS_PULL_POLICY", "io.tracepermit.agentcore.lifecyclecli.PreparedContainerImagesOnly")
  environment("TESTCONTAINERS_DOCKER_CLIENT_STRATEGY", "org.testcontainers.dockerclient.EnvironmentAndSystemPropertyClientProviderStrategy")
  providers.gradleProperty("tracepermit.container.dockerEndpoint").orNull?.let {
    environment("DOCKER_HOST", it)
    environment("DOCKER_CONTEXT", "")
    environment("DOCKER_TLS_VERIFY", "0")
  }
  systemProperty("tracepermit.container.probeJar", containerProbeJar.flatMap { it.archiveFile }.get().asFile.absolutePath)
  listOf("docker", "dockerEndpoint", "runtimeImage", "evidence", "sourceCommit").forEach { name ->
    providers.gradleProperty("tracepermit.container.$name").orNull?.let {
      systemProperty("tracepermit.container.$name", it)
    }
  }
}
