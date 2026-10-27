import java.security.MessageDigest
import java.time.Instant
import org.gradle.api.tasks.bundling.Compression
import org.gradle.api.tasks.bundling.Tar

plugins {
  application
}

dependencies {
  implementation(project(":adapters-remote"))
  implementation(project(":contracts"))
  testImplementation("com.squareup.okhttp3:mockwebserver:5.5.0")
}

application {
  mainClass = "io.tracepermit.adapters.remote.github.PilotOperatorMain"
}

val pilotOfflineProbeJar = tasks.register<Jar>("pilotOfflineProbeJar") {
  group = "verification"
  description = "Packages only the DNS/provider tripwire helpers used beside installed pilot runtime JARs."
  archiveClassifier.set("offline-probe")
  duplicatesStrategy = DuplicatesStrategy.FAIL
  from(sourceSets["test"].output) {
    include("io/tracepermit/adapters/remote/github/PilotOfflineProbe.class")
    include("io/tracepermit/adapters/remote/github/PilotNoDnsProvider*.class")
    include("META-INF/services/java.net.spi.InetAddressResolverProvider")
  }
}

tasks.test {
  dependsOn(tasks.named("jar"), pilotOfflineProbeJar)
  systemProperty("tracepermit.pilotJar", tasks.named<Jar>("jar").flatMap { it.archiveFile }.get().asFile.absolutePath)
  providers.gradleProperty("pilotArchiveRoot").orNull?.let { systemProperty("tracepermit.pilotArchiveRoot", it) }
  systemProperty("tracepermit.pilotProbeJar", pilotOfflineProbeJar.flatMap { it.archiveFile }.get().asFile.absolutePath)
  systemProperty("tracepermit.pilotLegacyTestClasspath", sourceSets["test"].runtimeClasspath.asPath)
  systemProperty("tracepermit.pilotRuntimeClasspath", sourceSets["main"].runtimeClasspath.asPath)
}

abstract class StagePilotOperatorDistribution : DefaultTask() {
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
    val launcherDest = binDir.resolve("tracepermit-pilot-operator")
    launcherScript.get().asFile.copyTo(launcherDest, overwrite = true)
    launcherDest.setExecutable(true, false)

    // 2. Copy JARs; this archive has its own additive inventory, no existing package changes.
    val requiredJars = setOf(
      "pilot-operator", "adapters-remote", "identity", "application", "contracts", "execution", "security", "policy",
    ).map { "$it-0.1.0-SNAPSHOT.jar" }.toSet() + setOf(
      "jackson-annotations-2.18.3.jar", "jackson-core-2.18.3.jar", "jackson-databind-2.18.3.jar",
      "jackson-datatype-jsr310-2.18.3.jar", "nimbus-jose-jwt-10.5.jar", "okhttp-jvm-5.5.0.jar",
      "okio-jvm-3.18.1.jar", "kotlin-stdlib-2.2.21.jar", "annotations-13.0.jar",
    )
    val inputs = runtimeClasspath.files + mainJar.get().asFile
    if (inputs.any { !it.isFile } || inputs.map { it.name }.toSet() != requiredJars
        || inputs.groupBy { it.name }.any { (_, files) -> files.distinct().size != 1 }) {
      throw GradleException("Pilot distribution inventory rejected.")
    }
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
      .replace("](pilot-operator-distribution.md)", "]($sourceDocs/runbooks/pilot-operator-distribution.md)")
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
      append("  \"packageName\": \"tracepermit-pilot-operator\",\n")
      append("  \"packageVersion\": \"0.1.0\",\n")
      append("  \"sourceCommit\": \"${sourceCommit.get()}\",\n")
      append("  \"sourceCommitTimestamp\": \"${Instant.ofEpochSecond(sourceCommitEpochSeconds.get())}\",\n")
      append("  \"sourceTree\": \"clean\",\n")
      append("  \"requiredJava\": \"21\",\n")
      append("  \"intendedOperatorPlatform\": \"macOS\",\n")
      append("  \"qualification\": \"Synthetic POSIX tests only; macOS host validation pending.\",\n")
      append("  \"entryPoint\": \"io.tracepermit.adapters.remote.github.PilotOperatorMain\",\n")
      append("  \"launcher\": \"bin/tracepermit-pilot-operator\",\n")
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

val stagePilotOperatorDistribution = tasks.register<StagePilotOperatorDistribution>("stagePilotOperatorDistribution") {
  group = "distribution"
  description = "Stages files for the source-free pilot operator distribution."
  dependsOn(tasks.named("jar"))
  runtimeClasspath.from(configurations["runtimeClasspath"])
  mainJar.set(tasks.named<Jar>("jar").flatMap { it.archiveFile })
  launcherScript.set(layout.projectDirectory.file("src/main/scripts/tracepermit-pilot-operator"))
  runbook.set(layout.projectDirectory.file("../../docs/runbooks/pilot-operator-distribution.md"))
  license.set(layout.projectDirectory.file("../../LICENSE"))
  notice.set(layout.projectDirectory.file("../../NOTICE"))
  sourceCommit.set(distributionCommit)
  sourceCommitEpochSeconds.set(distributionCommitEpoch)
  sourceTreeClean.set(distributionSourceClean)
  outputDir.set(layout.buildDirectory.dir("staged-distribution/tracepermit-pilot-operator-0.1.0"))
}

val archivePilotOperatorDistribution = tasks.register<Tar>("archivePilotOperatorDistribution") {
  group = "distribution"
  description = "Packages the source-free pilot operator archive."
  dependsOn(stagePilotOperatorDistribution)
  archiveBaseName.set("tracepermit-pilot-operator")
  archiveVersion.set("0.1.0")
  archiveExtension.set("tar.gz")
  compression = Compression.GZIP
  destinationDirectory.set(layout.buildDirectory.dir("distributions"))
  isPreserveFileTimestamps = false
  isReproducibleFileOrder = true
  dirPermissions { unix("0755") }
  filePermissions { unix("0644") }
  from(stagePilotOperatorDistribution.flatMap { it.outputDir }) {
    into("tracepermit-pilot-operator-0.1.0")
  }
  eachFile {
    if (name == "tracepermit-pilot-operator") {
      permissions { unix("0755") }
    }
  }
}

val checksumPilotOperatorDistribution = tasks.register<GenerateArchiveChecksum>("checksumPilotOperatorDistribution") {
  group = "distribution"
  description = "Generates SHA-256 checksum for the endpoint connector archive."
  archiveFile.set(archivePilotOperatorDistribution.flatMap { it.archiveFile })
  checksumFile.set(archivePilotOperatorDistribution.flatMap { tar ->
    tar.destinationDirectory.file(tar.archiveFile.map { "${it.asFile.name}.sha256" })
  })
}

tasks.register("packagePilotOperatorDistribution") {
  group = "distribution"
  description = "Builds and checksums the source-free pilot operator archive."
  dependsOn(checksumPilotOperatorDistribution)
}

tasks.register<Sync>("installPilotOperatorDistribution") {
  group = "distribution"
  description = "Installs the unpacked pilot operator distribution into build/install/tracepermit-pilot-operator-0.1.0."
  dependsOn(stagePilotOperatorDistribution)
  from(layout.buildDirectory.dir("staged-distribution/tracepermit-pilot-operator-0.1.0"))
  into(layout.buildDirectory.dir("install/tracepermit-pilot-operator-0.1.0"))
}
