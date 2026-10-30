import javax.inject.Inject
import org.gradle.process.ExecOperations

plugins { application }

val bootstrap by sourceSets.creating
val organizationUstarTool by configurations.creating {
  isCanBeConsumed = false
  isCanBeResolved = true
  description = "Build-only accepted lifecycle USTAR producer for this distribution."
}
abstract class OrganizationArchiveExecServices {
  @get:Inject abstract val execOperations: ExecOperations
}
val organizationArchiveExec =
  objects.newInstance<OrganizationArchiveExecServices>().execOperations
val rawOrganizationTar = layout.buildDirectory.file("tmp/ustar-producer/fresh-gradle.tar")
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
  testImplementation(platform("org.testcontainers:testcontainers-bom:2.0.5"))
  testImplementation("org.testcontainers:postgresql")
  testRuntimeOnly("org.slf4j:slf4j-nop:1.7.36")
  add(
    organizationUstarTool.name,
    project(
      mapOf(
        "path" to ":agentcore-operation-lifecycle-cli",
        "configuration" to "lifecycleUstarToolElements",
      ),
    ),
  )
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

tasks.distTar {
  dependsOn(organizationUstarTool)
  inputs.files(organizationUstarTool).withPropertyName("acceptedLifecycleUstarTool")
  outputs.file(rawOrganizationTar)
  // Capture task-local values so both producer and consumer remain configuration-cache safe.
  val rawArchive = rawOrganizationTar
  val finalArchive = archiveFile
  val producerExec = organizationArchiveExec
  val producerRoot = archiveBaseName.zip(archiveVersion) { name, version -> "$name-$version" }
  // Capture the artifact view rather than the Configuration itself. Gradle can
  // snapshot this FileCollection for configuration-cache task actions without
  // serializing the unsupported Configuration model object.
  val producerClasspath = organizationUstarTool.incoming.artifactView {}.files
  val producerJava = javaToolchains.launcherFor(java.toolchain).map { it.executablePath.asFile }
  doLast {
    val raw = rawArchive.get().asFile
    raw.parentFile.mkdirs()
    if (raw.exists() && !raw.delete()) {
      throw GradleException("Cannot replace the prior private raw organization archive")
    }
    finalArchive.get().asFile.copyTo(raw, overwrite = false)
    if (!raw.setReadOnly()) {
      throw GradleException("Cannot make the raw organization archive read-only")
    }
    producerExec.javaexec {
      executable = producerJava.get().absolutePath
      classpath = producerClasspath
      mainClass.set("io.tracepermit.agentcore.archive.LifecycleUstar")
      args(finalArchive.get().asFile.absolutePath, producerRoot.get())
    }.assertNormalExitValue()
  }
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
  systemProperty(
    "tracepermit.organization.rawArchive",
    rawOrganizationTar.get().asFile.absolutePath,
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
