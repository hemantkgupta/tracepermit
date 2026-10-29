import javax.inject.Inject
import org.gradle.process.ExecOperations

plugins { application }

val bootstrap by sourceSets.creating
// Build-only producer code: never included in the installed application JAR.
val archiveTool by sourceSets.creating
val lifecycleUstarToolJar by tasks.registering(Jar::class) {
  description = "Build-only USTAR finalizer shared with source-free distribution producers."
  archiveBaseName.set("tracepermit-lifecycle-ustar-tool")
  archiveClassifier.set("build-only")
  from(archiveTool.output)
}
val lifecycleUstarToolElements by configurations.creating {
  isCanBeConsumed = true
  isCanBeResolved = false
  description = "Compiled JDK-only USTAR producer; never a product runtime dependency."
}
artifacts { add(lifecycleUstarToolElements.name, lifecycleUstarToolJar) }
abstract class LifecycleArchiveExecServices {
  @get:Inject abstract val execOperations: ExecOperations
}
val archiveExec = objects.newInstance<LifecycleArchiveExecServices>().execOperations
val rawLifecycleTar = layout.buildDirectory.file("tmp/ustar-producer/fresh-gradle.tar")
tasks.named<JavaCompile>(bootstrap.compileJavaTaskName) {
  options.release = 8
  options.compilerArgs.add("-Xlint:-options")
}
tasks.jar { from(bootstrap.output) }

dependencies {
  implementation(project(":agentcore-registration-cli"))
  implementation(project(":adapters-remote"))
  implementation(project(":application"))
  implementation(project(":contracts"))
  implementation(project(":identity"))
  implementation(project(":persistence-operations"))

  testImplementation(project(":agentcore-durable-registration"))
  testImplementation(project(":persistence-identity"))
  testImplementation(platform("org.testcontainers:testcontainers-bom:1.21.4"))
  testImplementation("org.testcontainers:postgresql")
  testImplementation("org.testcontainers:mariadb")
  testRuntimeOnly("org.slf4j:slf4j-nop:1.7.36")
  testImplementation(archiveTool.output)
}

application {
  applicationName = "tracepermit-synthetic-operation-lifecycle"
  mainClass = "io.tracepermit.agentcore.lifecyclecli.Bootstrap"
  applicationDefaultJvmArgs = listOf("-Xms16m", "-Xmx64m", "-XX:ActiveProcessorCount=2")
}

tasks.distTar {
  dependsOn(tasks.named(archiveTool.classesTaskName))
  inputs.files(archiveTool.output).withPropertyName("ustarProducerClasses")
  outputs.file(rawLifecycleTar)
  // Capture task-local values: script-level properties are not configuration-cache serializable.
  val rawArchive = rawLifecycleTar
  val finalArchive = archiveFile
  val producerExec = archiveExec
  val producerRoot = archiveBaseName.zip(archiveVersion) { name, version -> "$name-$version" }
  val producerClasspath = archiveTool.runtimeClasspath
  val producerJava = javaToolchains.launcherFor(java.toolchain).map { it.executablePath.asFile }
  // Normalize only this newly generated output before the producer task completes.
  // Preserve the task type/path used by existing test and container consumers.
  doLast {
    val raw = rawArchive.get().asFile
    raw.parentFile.mkdirs()
    finalArchive.get().asFile.copyTo(raw, overwrite = true)
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
  systemProperty("tracepermit.lifecycle.archive", tasks.distTar.get().archiveFile.get().asFile.absolutePath)
  systemProperty("tracepermit.lifecycle.rawArchive", rawLifecycleTar.get().asFile.absolutePath)
  systemProperty("tracepermit.lifecycle.testClasspath", sourceSets.test.get().runtimeClasspath.asPath)
  systemProperty("tracepermit.repositoryRoot", rootProject.projectDir.parentFile.absolutePath)
}
