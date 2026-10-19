import java.security.MessageDigest
import java.time.Instant
import org.gradle.api.tasks.bundling.Compression
import org.gradle.api.tasks.bundling.Tar

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

tasks.test {
  systemProperty("tracepermit.endpointRuntimeClasspath", sourceSets["main"].runtimeClasspath.asPath)
  systemProperty("tracepermit.endpointTestClasspath", sourceSets["test"].runtimeClasspath.asPath)
}

abstract class PrintClasspathTask : DefaultTask() {
  @get:InputFiles
  abstract val classpath: ConfigurableFileCollection

  @TaskAction
  fun printClasspath() {
    println(classpath.asPath)
  }
}

tasks.register<PrintClasspathTask>("printTestClasspath") {
  classpath.from(sourceSets["test"].runtimeClasspath)
}

abstract class StageEndpointConnectorDistribution : DefaultTask() {
  @get:InputFiles
  abstract val runtimeClasspath: ConfigurableFileCollection

  @get:InputFile
  abstract val mainJar: RegularFileProperty

  @get:InputFile
  abstract val launcherScript: RegularFileProperty

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
    val docsDir = stageDir.resolve("docs").apply { mkdirs() }

    // 1. Copy launcher
    val launcherDest = binDir.resolve("tracepermit-endpoint-connector")
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

    // 3. Copy Docs & Legal
    val sourceDocs = "https://github.com/hemantkgupta/tracepermit/blob/${sourceCommit.get()}/docs"
    val installedRunbook = runbook.get().asFile.readText()
      .replace("](../architecture/", "]($sourceDocs/architecture/")
      .replace("](endpoint-connector-distribution.md)", "]($sourceDocs/runbooks/endpoint-connector-distribution.md)")
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

    // 4. Generate manifest.json
    val jars = libDir.listFiles { f -> f.isFile && f.name.endsWith(".jar") }
      ?.sortedBy { it.name } ?: emptyList()

    val manifestJson = buildString {
      append("{\n")
      append("  \"schemaVersion\": \"1.1\",\n")
      append("  \"packageName\": \"tracepermit-endpoint-connector\",\n")
      append("  \"packageVersion\": \"0.1.0\",\n")
      append("  \"sourceCommit\": \"${sourceCommit.get()}\",\n")
      append("  \"sourceCommitTimestamp\": \"${Instant.ofEpochSecond(sourceCommitEpochSeconds.get())}\",\n")
      append("  \"sourceTree\": \"clean\",\n")
      append("  \"requiredJava\": \"21\",\n")
      append("  \"supportedPlatforms\": [\n    \"macOS\",\n    \"Linux\"\n  ],\n")
      append("  \"entryPoint\": \"io.tracepermit.endpoint.EndpointConnectorMain\",\n")
      append("  \"launcher\": \"bin/tracepermit-endpoint-connector\",\n")
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

    // 5. Generate SHA256SUMS
    val sums = buildString {
      append("# TracePermit Endpoint Connector Distribution SHA-256 Checksums\n")
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

val stageEndpointConnectorDistribution = tasks.register<StageEndpointConnectorDistribution>("stageEndpointConnectorDistribution") {
  group = "distribution"
  description = "Stages files for the source-free endpoint connector distribution."
  dependsOn(tasks.named("jar"))
  runtimeClasspath.from(configurations["runtimeClasspath"])
  mainJar.set(tasks.named<Jar>("jar").flatMap { it.archiveFile })
  launcherScript.set(layout.projectDirectory.file("src/main/scripts/tracepermit-endpoint-connector"))
  runbook.set(layout.projectDirectory.file("../../docs/runbooks/endpoint-connector-distribution.md"))
  license.set(layout.projectDirectory.file("../../LICENSE"))
  notice.set(layout.projectDirectory.file("../../NOTICE"))
  sourceCommit.set(distributionCommit)
  sourceCommitEpochSeconds.set(distributionCommitEpoch)
  sourceTreeClean.set(distributionSourceClean)
  outputDir.set(layout.buildDirectory.dir("staged-distribution/tracepermit-endpoint-connector-0.1.0"))
}

val archiveEndpointConnectorDistribution = tasks.register<Tar>("archiveEndpointConnectorDistribution") {
  group = "distribution"
  description = "Packages the source-free endpoint connector archive."
  dependsOn(stageEndpointConnectorDistribution)
  archiveBaseName.set("tracepermit-endpoint-connector")
  archiveVersion.set("0.1.0")
  archiveExtension.set("tar.gz")
  compression = Compression.GZIP
  destinationDirectory.set(layout.buildDirectory.dir("distributions"))
  isPreserveFileTimestamps = false
  isReproducibleFileOrder = true
  dirPermissions { unix("0755") }
  filePermissions { unix("0644") }
  from(stageEndpointConnectorDistribution.flatMap { it.outputDir }) {
    into("tracepermit-endpoint-connector-0.1.0")
  }
  eachFile {
    if (name == "tracepermit-endpoint-connector") {
      permissions { unix("0755") }
    }
  }
}

val checksumEndpointConnectorDistribution = tasks.register<GenerateArchiveChecksum>("checksumEndpointConnectorDistribution") {
  group = "distribution"
  description = "Generates SHA-256 checksum for the endpoint connector archive."
  archiveFile.set(archiveEndpointConnectorDistribution.flatMap { it.archiveFile })
  checksumFile.set(archiveEndpointConnectorDistribution.flatMap { tar ->
    tar.destinationDirectory.file(tar.archiveFile.map { "${it.asFile.name}.sha256" })
  })
}

tasks.register("packageEndpointConnectorDistribution") {
  group = "distribution"
  description = "Builds and checksums the source-free endpoint connector archive."
  dependsOn(checksumEndpointConnectorDistribution)
}

tasks.register<Sync>("installEndpointConnectorDistribution") {
  group = "distribution"
  description = "Installs the unpacked endpoint connector distribution into build/install/tracepermit-endpoint-connector-0.1.0."
  dependsOn(stageEndpointConnectorDistribution)
  from(layout.buildDirectory.dir("staged-distribution/tracepermit-endpoint-connector-0.1.0"))
  into(layout.buildDirectory.dir("install/tracepermit-endpoint-connector-0.1.0"))
}
