plugins { application }

val bootstrap by sourceSets.creating
tasks.named<JavaCompile>(bootstrap.compileJavaTaskName) {
  options.release = 8
  options.compilerArgs.add("-Xlint:-options")
}
tasks.jar { from(bootstrap.output) }

dependencies {
  implementation(project(":agentcore-registration-cli"))
  implementation(project(":agentcore-durable-registration"))
  implementation(project(":adapters-remote"))
  implementation(project(":application"))
  implementation(project(":contracts"))
  implementation(project(":identity"))
  implementation(project(":persistence-operations"))

  testImplementation(project(":persistence-identity"))
  testImplementation(platform("org.testcontainers:testcontainers-bom:1.21.4"))
  testImplementation("org.testcontainers:postgresql")
  testRuntimeOnly("org.slf4j:slf4j-nop:1.7.36")
}

// Explicit human-run organization-container qualification. It is not part of checkAll or CI.
val organizationContainerTest by sourceSets.creating {
  java.srcDir("src/containerTest/java")
  compileClasspath += sourceSets.named("test").get().runtimeClasspath
  runtimeClasspath += output + compileClasspath
}
configurations[organizationContainerTest.implementationConfigurationName]
  .extendsFrom(configurations.testImplementation.get())
configurations[organizationContainerTest.runtimeOnlyConfigurationName]
  .extendsFrom(configurations.testRuntimeOnly.get())
tasks.named(organizationContainerTest.compileJavaTaskName) {
  dependsOn(tasks.named("testClasses"))
}
dependencies {
  add(
    organizationContainerTest.implementationConfigurationName,
    testFixtures(project(":eks-sandbox-bundle")),
  )
}

application {
  applicationName = "tracepermit-synthetic-organization-operator"
  mainClass = "io.tracepermit.agentcore.organizationcli.Bootstrap"
  applicationDefaultJvmArgs = listOf("-Xms16m", "-Xmx64m", "-XX:ActiveProcessorCount=2")
}

tasks.test {
  dependsOn(tasks.distTar)
  jvmArgs("-Djava.security.manager=allow")
  systemProperty(
    "tracepermit.organization.archive",
    tasks.distTar.get().archiveFile.get().asFile.absolutePath,
  )
  systemProperty(
    "tracepermit.organization.testClasspath",
    sourceSets.test.get().runtimeClasspath.asPath,
  )
  systemProperty("tracepermit.repositoryRoot", rootProject.projectDir.parentFile.absolutePath)
}

val organizationContainerProbeJar by tasks.registering(Jar::class) {
  dependsOn(tasks.named(organizationContainerTest.classesTaskName))
  archiveBaseName.set("tracepermit-organization-container-probe")
  from(organizationContainerTest.output) {
    include("io/tracepermit/agentcore/organizationcli/OrganizationContainerInputProbe*.class")
  }
}

tasks.register<JavaExec>("assembleOrganizationContainerImage") {
  description = "Human-run local assembly from the fixed organization archive and cached base."
  group = "distribution"
  dependsOn(tasks.distTar, tasks.named(organizationContainerTest.classesTaskName))
  classpath = organizationContainerTest.runtimeClasspath
  mainClass.set("io.tracepermit.agentcore.organizationcli.OrganizationContainerImageAssembler")
  systemProperty(
    "tracepermit.container.organizationArchive",
    tasks.distTar.get().archiveFile.get().asFile.absolutePath,
  )
  listOf(
    "docker",
    "dockerEndpoint",
    "baseImage",
    "evidence",
    "sourceCommit",
    "sourceManifestHash",
  ).forEach { name ->
    providers.gradleProperty("tracepermit.container.$name").orNull?.let {
      systemProperty("tracepermit.container.$name", it)
    }
  }
}

fun Test.configureOrganizationContainerTest() {
  dependsOn(tasks.named(organizationContainerTest.classesTaskName))
  testClassesDirs = organizationContainerTest.output.classesDirs
  classpath = organizationContainerTest.runtimeClasspath
  maxParallelForks = 1
  failFast = true
  outputs.upToDateWhen { false }
  outputs.cacheIf { false }
  jvmArgs("-Djava.security.manager=allow")
  systemProperty("tracepermit.repositoryRoot", rootProject.projectDir.parentFile.absolutePath)
}

tasks.register<Test>("organizationContainerInventory") {
  description = "Human-run source-free organization archive and fixed image-recipe inventory."
  group = "verification"
  configureOrganizationContainerTest()
  dependsOn(tasks.distTar, organizationContainerProbeJar)
  include("**/OrganizationContainerInventoryTest.class")
  systemProperty(
    "tracepermit.container.organizationArchive",
    tasks.distTar.get().archiveFile.get().asFile.absolutePath,
  )
  systemProperty(
    "tracepermit.container.dockerfile",
    rootProject.layout.projectDirectory.file("../deploy/sandbox/organization-operator/Dockerfile")
      .asFile.absolutePath,
  )
  systemProperty(
    "tracepermit.container.probeJar",
    organizationContainerProbeJar.flatMap { it.archiveFile }.get().asFile.absolutePath,
  )
  listOf("sourceCommit", "sourceManifestHash", "evidence").forEach { name ->
    providers.gradleProperty("tracepermit.container.$name").orNull?.let {
      systemProperty("tracepermit.container.$name", it)
    }
  }
}

fun Test.configureOrganizationDockerQualification() {
  configureOrganizationContainerTest()
  dependsOn(organizationContainerProbeJar, tasks.named("testClasses"))
  environment(
    "TESTCONTAINERS_PULL_POLICY",
    "io.tracepermit.agentcore.organizationcli.PreparedOrganizationContainerImagesOnly",
  )
  environment(
    "TESTCONTAINERS_DOCKER_CLIENT_STRATEGY",
    "org.testcontainers.dockerclient.EnvironmentAndSystemPropertyClientProviderStrategy",
  )
  providers.gradleProperty("tracepermit.container.dockerEndpoint").orNull?.let {
    environment("DOCKER_HOST", it)
    environment("DOCKER_CONTEXT", "")
    environment("DOCKER_TLS_VERIFY", "0")
  }
  systemProperty(
    "tracepermit.container.probeJar",
    organizationContainerProbeJar.flatMap { it.archiveFile }.get().asFile.absolutePath,
  )
  listOf(
    "docker",
    "dockerEndpoint",
    "runtimeImage",
    "evidence",
    "sourceCommit",
    "archiveSha256",
  ).forEach { name ->
    providers.gradleProperty("tracepermit.container.$name").orNull?.let {
      systemProperty("tracepermit.container.$name", it)
    }
  }
}

tasks.register<Test>("organizationContainerTransportQualification") {
  description = "Human-run attached-input and containment gate for the organization image."
  group = "verification"
  configureOrganizationDockerQualification()
  include("**/OrganizationContainerPrivateInputQualificationTest.class")
}

tasks.register<Test>("organizationContainerPostgresQualification") {
  description = "Human-run PostgreSQL16.4 installed organization lifecycle gate."
  group = "verification"
  configureOrganizationDockerQualification()
  include("**/PostgresOrganizationContainerLifecycleQualificationTest.class")
}
