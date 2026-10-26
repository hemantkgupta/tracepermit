import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.testing.Test
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.testing.jacoco.tasks.JacocoReport
import org.gradle.testing.jacoco.tasks.JacocoCoverageVerification

plugins {
  base
  jacoco
}

// CI partitions execution, then checks the same aggregate coverage over every shard.
// Local checkAll remains the complete suite with the original coverage thresholds.
val ciLane = providers.gradleProperty("tracepermitCiLane").orNull
val ciLanes = setOf("core", "postgres", "mariadb", "operations-postgres", "operations-mariadb106", "operations-mariadb114")
require(ciLane == null || ciLane in ciLanes) { "Unknown TracePermit CI lane" }
val ciCoverageDir = providers.gradleProperty("tracepermitCiCoverageDir").orNull
val databaseModules = setOf("persistence-postgres", "persistence-mariadb", "persistence-operations")

allprojects {
  group = "io.tracepermit"
  version = "0.1.0-SNAPSHOT"

  repositories {
    mavenCentral()
  }
}

subprojects {
  apply(plugin = "java-library")
  apply(plugin = "jacoco")

  configure<JavaPluginExtension> {
    toolchain {
      languageVersion = JavaLanguageVersion.of(21)
    }
  }

  dependencies {
    "testImplementation"(platform("org.junit:junit-bom:5.12.2"))
    "testImplementation"("org.junit.jupiter:junit-jupiter")
    "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
    "testImplementation"("org.assertj:assertj-core:3.27.3")
  }

  tasks.withType<JavaCompile>().configureEach {
    options.release = 21
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
  }

  tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    systemProperty("tracepermit.repositoryRoot", rootDir.parentFile.absolutePath)
    testLogging {
      events("failed", "skipped")
    }
    if (ciLane != null && project.name in databaseModules) {
      // A cached green result is never fresh database qualification.
      outputs.upToDateWhen { false }
      outputs.cacheIf { false }
    }
    if (ciLane != null && project.name == "persistence-operations") {
      when (ciLane) {
        "core" -> exclude("**/Postgres*Test.class", "**/MariaDb106*Test.class", "**/MariaDb114*Test.class")
        "operations-postgres" -> include("**/Postgres*Test.class")
        "operations-mariadb106" -> include("**/MariaDb106*Test.class")
        "operations-mariadb114" -> include("**/MariaDb114*Test.class")
        else -> throw GradleException("Operation tests requested from an unrelated CI lane")
      }
    }
  }

  tasks.named<JacocoReport>("jacocoTestReport") {
    dependsOn(tasks.named("test"))
    reports {
      xml.required = true
      html.required = true
    }
  }
}

tasks.register("ciTest") {
  group = "verification"
  require(ciLane != null) { "ciTest requires tracepermitCiLane" }
  val selected = when (ciLane) {
    "core" -> subprojects.filter { it.name !in databaseModules || it.name == "persistence-operations" }
    "postgres" -> subprojects.filter { it.name == "persistence-postgres" }
    "mariadb" -> subprojects.filter { it.name == "persistence-mariadb" }
    else -> subprojects.filter { it.name == "persistence-operations" }
  }
  dependsOn(selected.map { it.tasks.named("check") })
}

tasks.register("checkAll") {
  group = "verification"
  dependsOn(subprojects.map { it.tasks.named("check") })
  dependsOn("jacocoAggregateCoverageVerification")
  finalizedBy("jacocoAggregateReport")
}

tasks.register<JacocoReport>("jacocoAggregateReport") {
  group = "verification"
  description = "Aggregates Java coverage across backend and conformance tests."
  dependsOn(subprojects.map { it.tasks.named(if (ciCoverageDir == null) "test" else "classes") })
  val mainSourceSets = subprojects.map {
    it.extensions.getByType<SourceSetContainer>().named("main").get()
  }
  executionData.from(subprojects.map { it.layout.buildDirectory.file("jacoco/test.exec") })
  if (ciCoverageDir != null) executionData.setFrom(fileTree(ciCoverageDir) { include("**/*.exec") })
  sourceDirectories.from(mainSourceSets.flatMap { it.allSource.srcDirs })
  classDirectories.from(mainSourceSets.map { it.output })
  reports {
    xml.required = true
    html.required = true
  }
}

tasks.register<JacocoCoverageVerification>("jacocoAggregateCoverageVerification") {
  group = "verification"
  description = "Enforces the measured Java coverage baseline."
  dependsOn(subprojects.map { it.tasks.named(if (ciCoverageDir == null) "test" else "classes") })
  val mainSourceSets = subprojects.map {
    it.extensions.getByType<SourceSetContainer>().named("main").get()
  }
  executionData.from(subprojects.map { it.layout.buildDirectory.file("jacoco/test.exec") })
  if (ciCoverageDir != null) executionData.setFrom(fileTree(ciCoverageDir) { include("**/*.exec") })
  sourceDirectories.from(mainSourceSets.flatMap { it.allSource.srcDirs })
  classDirectories.from(mainSourceSets.map { it.output })
  violationRules {
    rule {
      limit {
        counter = "LINE"
        minimum = "0.94".toBigDecimal()
      }
      limit {
        counter = "BRANCH"
        minimum = "0.75".toBigDecimal()
      }
    }
  }
}
