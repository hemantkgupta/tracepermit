import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.testing.Test
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.testing.jacoco.tasks.JacocoReport
import org.gradle.testing.jacoco.tasks.JacocoCoverageVerification

plugins {
  base
  jacoco
}

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
    "testImplementation"(platform("org.junit:junit-bom:6.1.3"))
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
  }

  tasks.named<JacocoReport>("jacocoTestReport") {
    dependsOn(tasks.named("test"))
    reports {
      xml.required = true
      html.required = true
    }
  }
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
  dependsOn(subprojects.map { it.tasks.named("test") })
  val mainSourceSets = subprojects.map {
    it.extensions.getByType<SourceSetContainer>().named("main").get()
  }
  executionData.from(subprojects.map { it.layout.buildDirectory.file("jacoco/test.exec") })
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
  dependsOn(subprojects.map { it.tasks.named("test") })
  val mainSourceSets = subprojects.map {
    it.extensions.getByType<SourceSetContainer>().named("main").get()
  }
  executionData.from(subprojects.map { it.layout.buildDirectory.file("jacoco/test.exec") })
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
