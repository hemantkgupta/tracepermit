import java.security.MessageDigest
import java.time.Instant
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.bundling.Compression
import org.gradle.api.tasks.bundling.Tar
import org.gradle.api.tasks.testing.logging.TestExceptionFormat

plugins {
  application
}

dependencies {
  implementation(project(":contracts"))
  implementation(project(":policy"))
  implementation(project(":application"))
  implementation(project(":execution"))
  implementation(project(":identity"))
  implementation(project(":security"))
  implementation(project(":simulator"))
  implementation(project(":adapters-remote"))
  implementation(project(":persistence-postgres"))

  testImplementation(project(":endpoint-connector"))
  testImplementation("org.flywaydb:flyway-core:11.8.2")
  testImplementation(platform("org.testcontainers:testcontainers-bom:1.21.4"))
  testImplementation("org.testcontainers:postgresql")
  testRuntimeOnly("org.slf4j:slf4j-nop:1.7.36")
}

application {
  mainClass = "io.tracepermit.adapters.http.DevelopmentBackendMain"
}

tasks.register<JavaExec>("runSimulatorApprovals") {
  group = "application"
  description = "Runs or offline-validates the loopback-only durable simulator approval API."
  classpath = sourceSets["main"].runtimeClasspath
  mainClass = "io.tracepermit.adapters.http.SimulatorApprovalMain"
}

val endpointDistributionArchive = layout.projectDirectory.file(
  "../endpoint-connector/build/distributions/tracepermit-endpoint-connector-0.1.0.tar.gz"
)

val repoRootDir = rootDir.parentFile

// Installed-archive provenance is resolved at the real provider boundary and must fail
// closed. The git probe runs under a supervising perl process that forks, execs git in
// the child, and bounds it with a finite deadline: the child leads its own process
// group, and on timeout the supervisor SIGKILLs that whole group and exits 124, so
// grandchildren holding the output pipe cannot delay failure detection past the
// deadline. A failed exec (missing provenance tooling) exits 127, and any non-zero
// probe exit — including a refused or sabotaged git — fails configuration. The
// deadline lives in the parent, which never execs, so it does not depend on alarm
// semantics surviving exec on the host. Only a single full-match hex commit authorizes
// an expected-head value. The exec-backed provider remains tracked by Gradle's
// configuration cache, so a commit-only change still invalidates caches.
val gitHeadProvider = providers.exec {
  workingDir(repoRootDir)
  commandLine(
    "perl",
    "-e",
    "my \$pid = fork(); die \"provenance probe fork failed\" unless defined \$pid; if (\$pid == 0) { setpgrp(0, 0) or die \"provenance probe setpgrp failed\"; exec(@ARGV) or exit 127; } \$SIG{ALRM} = sub { kill \"KILL\", -\$pid; waitpid(\$pid, 0); exit 124; }; alarm 10; waitpid(\$pid, 0); alarm 0; my \$status = \$?; exit((\$status & 127) ? 128 + (\$status & 127) : (\$status >> 8));",
    "git",
    "rev-parse",
    "HEAD",
  )
}.standardOutput.asText.map { output ->
  val commit = output.trim()
  if (!commit.matches(Regex("[0-9a-f]{40}|[0-9a-f]{64}"))) {
    throw GradleException(
      "Endpoint connector installed-archive provenance is unavailable, ambiguous, or malformed; refusing to authorize an expected-head value.",
    )
  }
  commit
}

val requireInstalledArchiveProperty = providers.systemProperty("tracepermit.endpointConnector.requireInstalledArchive")
  .orElse(providers.environmentVariable("TRACEPERMIT_REQUIRE_INSTALLED_ARCHIVE"))
  .orElse("false")

val expectedHeadCommitProperty = providers.systemProperty("tracepermit.endpointConnector.expectedHeadCommit")
  .orElse(providers.environmentVariable("TRACEPERMIT_EXPECTED_HEAD_COMMIT"))
  .orElse(gitHeadProvider)
  .orElse("")

tasks.test {
  systemProperty("tracepermit.approvalRuntimeClasspath", sourceSets["main"].runtimeClasspath.asPath)
  systemProperty("tracepermit.endpointConnector.requireInstalledArchive", requireInstalledArchiveProperty.get())
  systemProperty("tracepermit.endpointConnector.expectedHeadCommit", expectedHeadCommitProperty.get())

  inputs.files(endpointDistributionArchive)
    .withPropertyName("endpointConnectorArchive")
    .withPathSensitivity(PathSensitivity.NONE)
  inputs.property("endpointConnectorRequireInstalledArchive", requireInstalledArchiveProperty)
  inputs.property("endpointConnectorExpectedHeadCommit", expectedHeadCommitProperty)

  testLogging {
    exceptionFormat = TestExceptionFormat.FULL
  }
}

tasks.register("printEndpointConnectorExpectedHead") {
  group = "verification"
  description =
    "Prints the expected-head commit value injected into the endpoint connector qualification test JVM."
  val resolvedExpectedHead = expectedHeadCommitProperty.get()
  doLast {
    println("TRACEPERMIT_ENDPOINT_CONNECTOR_EXPECTED_HEAD=$resolvedExpectedHead")
  }
}

tasks.register<JavaExec>("runApprovalBrowserQualification") {
  group = "verification"
  description = "Runs a test-classpath-only synthetic IdP and disposable simulator inbox for browser qualification."
  dependsOn(tasks.named("testClasses"))
  classpath = sourceSets["test"].runtimeClasspath
  mainClass = "io.tracepermit.adapters.http.SimulatorApprovalBrowserQualificationMain"
  standardInput = System.`in`
  systemProperty("tracepermit.repositoryRoot", rootDir.parentFile.absolutePath)
}

tasks.register<JavaExec>("runApprovalCodexQualification") {
  group = "verification"
  description = "Runs a disposable synthetic requester / browser reviewer MCP qualification fixture."
  dependsOn(tasks.named("testClasses"))
  classpath = sourceSets["test"].runtimeClasspath
  mainClass = "io.tracepermit.adapters.http.SimulatorApprovalBrowserQualificationMain"
  args("--codex")
  standardInput = System.`in`
  systemProperty("tracepermit.repositoryRoot", rootDir.parentFile.absolutePath)
}

tasks.register<JavaExec>("runHostedClientOAuth") {
  group = "application"
  description = "Runs the simulator-only hosted-client OAuth profile behind a private tunnel."
  classpath = sourceSets["main"].runtimeClasspath
  mainClass = "io.tracepermit.adapters.http.HostedClientOAuthBackendMain"
  standardInput = System.`in`
}

tasks.register<JavaExec>("runSignedScheduledSemanticDemo") {
  group = "application"
  description = "Runs the signed scheduled-workload semantic-control scenario."
  dependsOn(tasks.named("testClasses"))
  classpath = sourceSets["test"].runtimeClasspath
  mainClass = "io.tracepermit.adapters.http.SignedScheduledSemanticDemoMain"
  standardInput = System.`in`
  systemProperty("tracepermit.repositoryRoot", rootDir.parentFile.absolutePath)
}

tasks.register<JavaExec>("runSemanticQualification") {
  group = "verification"
  description = "Measures local semantic enforcement and proves fail-secure behavior."
  dependsOn(tasks.named("testClasses"))
  classpath = sourceSets["test"].runtimeClasspath
  mainClass = "io.tracepermit.adapters.http.SemanticQualificationMain"
  systemProperty("tracepermit.repositoryRoot", rootDir.parentFile.absolutePath)
}

abstract class StageSimulatorApprovalDistribution : DefaultTask() {
  @get:InputFiles
  abstract val runtimeClasspath: ConfigurableFileCollection

  @get:InputFile
  abstract val mainJar: RegularFileProperty

  @get:InputFile
  abstract val launcherScript: RegularFileProperty

  @get:InputDirectory
  abstract val consoleDir: DirectoryProperty

  @get:InputFile
  abstract val exampleConfig: RegularFileProperty

  @get:InputFile
  abstract val runbook: RegularFileProperty

  @get:InputFile
  abstract val license: RegularFileProperty

  @get:InputFile
  abstract val notice: RegularFileProperty

  @get:Input
  abstract val sourceCommit: Property<String>

  @get:Input
  abstract val sourceCommitEpochSeconds: Property<Long>

  @get:Input
  abstract val sourceTreeClean: Property<Boolean>

  @get:OutputDirectory
  abstract val outputDir: DirectoryProperty

  @TaskAction
  fun stage() {
    if (!sourceTreeClean.get()) {
      throw GradleException("Distribution packaging requires a clean Git source tree. Commit or isolate source changes before packaging.")
    }
    val stageDir = outputDir.get().asFile
    stageDir.deleteRecursively()
    stageDir.mkdirs()

    val binDir = stageDir.resolve("bin").apply { mkdirs() }
    val libDir = stageDir.resolve("lib").apply { mkdirs() }
    val consoleTargetDir = stageDir.resolve("console").apply { mkdirs() }
    val examplesDir = stageDir.resolve("examples").apply { mkdirs() }
    val docsDir = stageDir.resolve("docs").apply { mkdirs() }

    // 1. Copy launcher
    val launcherDest = binDir.resolve("tracepermit-simulator-approval")
    launcherScript.get().asFile.copyTo(launcherDest, overwrite = true)
    launcherDest.setExecutable(true, false)

    // 2. Copy JARs
    val mainJarFile = mainJar.get().asFile
    mainJarFile.copyTo(libDir.resolve(mainJarFile.name), overwrite = true)
    runtimeClasspath.files.forEach { jarFile ->
      if (jarFile.isFile && jarFile.name.endsWith(".jar")) {
        jarFile.copyTo(libDir.resolve(jarFile.name), overwrite = true)
      }
    }

    // 3. Copy Console
    val consoleSourceDir = consoleDir.get().asFile
    val indexHtml = consoleSourceDir.resolve("index.html")
    if (!indexHtml.isFile) {
      throw GradleException("Built console assets missing at ${consoleSourceDir.absolutePath}; run 'pnpm build' first.")
    }
    consoleSourceDir.copyRecursively(consoleTargetDir, overwrite = true)

    // 4. Copy Examples
    exampleConfig.get().asFile.copyTo(examplesDir.resolve("simulator-approvals.example.json"), overwrite = true)

    // 5. Copy Docs & Legal
    // The installed runbook is relocated: its repository-relative links would otherwise break.
    val sourceDocs = "https://github.com/hemantkgupta/tracepermit/blob/${sourceCommit.get()}/docs"
    val installedRunbook = runbook.get().asFile.readText()
      .replace("](../architecture/", "]($sourceDocs/architecture/")
      .replace("](authenticated-approval-api-foundation.md)", "]($sourceDocs/runbooks/authenticated-approval-api-foundation.md)")
      .replace("](operator-recovery-quarantine.md)", "]($sourceDocs/runbooks/operator-recovery-quarantine.md)")
    docsDir.resolve("setup-runbook.md").writeText(installedRunbook)
    license.get().asFile.copyTo(docsDir.resolve("LICENSE"), overwrite = true)
    notice.get().asFile.copyTo(docsDir.resolve("NOTICE"), overwrite = true)

    fun sha256(file: File): String {
      val md = MessageDigest.getInstance("SHA-256")
      file.inputStream().use { input ->
        val buffer = ByteArray(8192)
        var read: Int
        while (input.read(buffer).also { read = it } != -1) {
          md.update(buffer, 0, read)
        }
      }
      return md.digest().joinToString("") { "%02x".format(it) }
    }

    // 6. Generate manifest.json
    val jars = libDir.listFiles { f -> f.isFile && f.name.endsWith(".jar") }
      ?.sortedBy { it.name } ?: emptyList()

    val manifestJson = buildString {
      append("{\n")
      append("  \"schemaVersion\": \"1.1\",\n")
      append("  \"packageName\": \"tracepermit-simulator-approval\",\n")
      append("  \"packageVersion\": \"0.1.0\",\n")
      append("  \"sourceCommit\": \"${sourceCommit.get()}\",\n")
      append("  \"sourceCommitTimestamp\": \"${Instant.ofEpochSecond(sourceCommitEpochSeconds.get())}\",\n")
      append("  \"sourceTree\": \"clean\",\n")
      append("  \"requiredJava\": \"21\",\n")
      append("  \"supportedPlatforms\": [\n    \"macOS\",\n    \"Linux\"\n  ],\n")
      append("  \"entryPoint\": \"io.tracepermit.adapters.http.SimulatorApprovalMain\",\n")
      append("  \"launcher\": \"bin/tracepermit-simulator-approval\",\n")
      append("  \"integrityNotice\": \"Artifact checksums verify transmission and storage integrity. They do not constitute an independent cryptographic code signature or third-party provenance attestation.\",\n")
      append("  \"dependencyInventory\": [\n")
      jars.forEachIndexed { index, jar ->
        append("    {\n")
        append("      \"name\": \"${jar.name}\",\n")
        append("      \"sizeBytes\": ${jar.length()},\n")
        append("      \"sha256\": \"${sha256(jar)}\"\n")
        append("    }")
        if (index < jars.size - 1) append(",")
        append("\n")
      }
      append("  ]\n")
      append("}\n")
    }
    stageDir.resolve("manifest.json").writeText(manifestJson)

    // 7. Generate SHA256SUMS
    val sums = buildString {
      append("# TracePermit Simulator Approval Distribution SHA-256 Checksums\n")
      append("# Notice: Checksums verify transmission and storage integrity.\n")
      append("# They do not constitute an independent cryptographic code signature.\n")
      val allFiles = stageDir.walkTopDown().filter { it.isFile && it.name != "SHA256SUMS" }.toList()
        .sortedBy { it.relativeTo(stageDir).path.replace('\\', '/') }
      allFiles.forEach { file ->
        val relPath = file.relativeTo(stageDir).path.replace('\\', '/')
        append("${sha256(file)}  $relPath\n")
      }
    }
    stageDir.resolve("SHA256SUMS").writeText(sums)
  }
}

abstract class GenerateArchiveChecksum : DefaultTask() {
  @get:InputFile
  abstract val archiveFile: RegularFileProperty

  @get:OutputFile
  abstract val checksumFile: RegularFileProperty

  @TaskAction
  fun generate() {
    val archive = archiveFile.get().asFile
    val md = MessageDigest.getInstance("SHA-256")
    archive.inputStream().use { input ->
      val buffer = ByteArray(8192)
      var read: Int
      while (input.read(buffer).also { read = it } != -1) {
        md.update(buffer, 0, read)
      }
    }
    val sha256 = md.digest().joinToString("") { "%02x".format(it) }
    checksumFile.get().asFile.writeText("$sha256  ${archive.name}\n")
    logger.lifecycle("Distribution archive created: ${archive.absolutePath} (${archive.length()} bytes)")
    logger.lifecycle("Distribution SHA-256: $sha256")
  }
}

// Exec-backed providers are tracked by Gradle's configuration cache and are explicit
// task inputs. A commit-only change must invalidate both caches; no "unknown" fallback.
val distributionRepository = layout.projectDirectory.dir("../..")
val distributionCommit = providers.exec {
  workingDir(distributionRepository)
  commandLine("git", "rev-parse", "--verify", "HEAD")
}.standardOutput.asText.map { output ->
  output.trim().also { commit ->
    if (!commit.matches(Regex("[0-9a-f]{40}|[0-9a-f]{64}"))) {
      throw GradleException("Distribution source commit is unavailable.")
    }
  }
}
val distributionCommitEpoch = providers.exec {
  workingDir(distributionRepository)
  commandLine("git", "show", "-s", "--format=%ct", "HEAD")
}.standardOutput.asText.map { output ->
  output.trim().toLongOrNull()?.takeIf { it >= 0 }
    ?: throw GradleException("Distribution source commit timestamp is unavailable.")
}
val distributionSourceClean = providers.exec {
  workingDir(distributionRepository)
  commandLine("git", "status", "--porcelain=v1", "--untracked-files=all", "--ignore-submodules=none")
}.standardOutput.asText.map { it.isBlank() }

val stageSimulatorApprovalDistribution = tasks.register<StageSimulatorApprovalDistribution>("stageSimulatorApprovalDistribution") {
  group = "distribution"
  description = "Stages files for the source-free simulator approval distribution."
  dependsOn(tasks.named("jar"))
  runtimeClasspath.from(configurations["runtimeClasspath"])
  mainJar.set(tasks.named<Jar>("jar").flatMap { it.archiveFile })
  launcherScript.set(layout.projectDirectory.file("src/main/scripts/tracepermit-simulator-approval"))
  consoleDir.set(layout.projectDirectory.dir("../../apps/console/dist"))
  exampleConfig.set(layout.projectDirectory.file("../../config/simulator-approvals.example.json"))
  runbook.set(layout.projectDirectory.file("../../docs/runbooks/simulator-approval-distribution.md"))
  license.set(layout.projectDirectory.file("../../LICENSE"))
  notice.set(layout.projectDirectory.file("../../NOTICE"))
  sourceCommit.set(distributionCommit)
  sourceCommitEpochSeconds.set(distributionCommitEpoch)
  sourceTreeClean.set(distributionSourceClean)
  outputDir.set(layout.buildDirectory.dir("staged-distribution/tracepermit-simulator-approval-0.1.0"))
}

val archiveSimulatorApprovalDistribution = tasks.register<Tar>("archiveSimulatorApprovalDistribution") {
  group = "distribution"
  description = "Packages the source-free simulator approval host-native archive."
  dependsOn(stageSimulatorApprovalDistribution)
  archiveBaseName.set("tracepermit-simulator-approval")
  archiveVersion.set("0.1.0")
  archiveExtension.set("tar.gz")
  compression = Compression.GZIP
  destinationDirectory.set(layout.buildDirectory.dir("distributions"))
  isPreserveFileTimestamps = false
  isReproducibleFileOrder = true
  dirPermissions { unix("0755") }
  filePermissions { unix("0644") }
  from(stageSimulatorApprovalDistribution.flatMap { it.outputDir }) {
    into("tracepermit-simulator-approval-0.1.0")
  }
  eachFile {
    if (name == "tracepermit-simulator-approval") {
      permissions { unix("0755") }
    }
  }
}

val checksumSimulatorApprovalDistribution = tasks.register<GenerateArchiveChecksum>("checksumSimulatorApprovalDistribution") {
  group = "distribution"
  description = "Generates SHA-256 checksum for the simulator approval archive."
  archiveFile.set(archiveSimulatorApprovalDistribution.flatMap { it.archiveFile })
  checksumFile.set(archiveSimulatorApprovalDistribution.flatMap { tar ->
    tar.destinationDirectory.file(tar.archiveFile.map { "${it.asFile.name}.sha256" })
  })
}

tasks.register("packageSimulatorApprovalDistribution") {
  group = "distribution"
  description = "Builds and checksums the source-free simulator approval host-native archive."
  dependsOn(checksumSimulatorApprovalDistribution)
}

tasks.register<Sync>("installSimulatorApprovalDistribution") {
  group = "distribution"
  description = "Installs the unpacked simulator approval distribution into build/install/tracepermit-simulator-approval."
  dependsOn(stageSimulatorApprovalDistribution)
  from(layout.buildDirectory.dir("staged-distribution/tracepermit-simulator-approval-0.1.0"))
  into(layout.buildDirectory.dir("install/tracepermit-simulator-approval-0.1.0"))
}

tasks.register<JavaExec>("runSimulatorApprovalLatencyQualification") {
  group = "verification"
  description = "Measures reproducible loopback MCP and HTTP approval latency against PostgreSQL."
  dependsOn(tasks.named("testClasses"))
  classpath = sourceSets["test"].runtimeClasspath
  mainClass = "io.tracepermit.adapters.http.SimulatorApprovalLatencyQualificationMain"
  systemProperty("tracepermit.repositoryRoot", rootDir.parentFile.absolutePath)
}

