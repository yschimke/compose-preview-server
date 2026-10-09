plugins {
  kotlin("multiplatform") version "2.4.21"
  id("org.jetbrains.kotlin.plugin.compose") version "2.4.21"
  id("org.jetbrains.compose") version "1.12.1"
}

kotlin {
  jvmToolchain(21)
  jvm()
  wasmJs {
    browser()
    binaries.executable()
  }
  sourceSets {
    commonMain.dependencies {
      implementation(compose.runtime)
      implementation(compose.foundation)
      implementation(compose.material3)
      implementation(compose.ui)
      // Versionless through the publishers' BOMs: rc-players and compose-ai-tools publish only the
      // modules a release changes, so a module pinned at a BOM's release version can 404.
      implementation(project.dependencies.platform("ee.schimke.composeai:rc-players-bom:2.4.0"))
      implementation("ee.schimke.composeai:rc-player-compose")
    }
    jvmMain.dependencies {
      implementation(compose.desktop.currentOs)
      implementation(project.dependencies.platform("ee.schimke.composeai:compose-ai-tools-bom:2.38.0"))
      implementation("ee.schimke.composeai:remotecompose-json")
      implementation("androidx.compose.remote:remote-creation-core:1.0.0-alpha21")
      implementation("org.json:json:20250517")
    }
    jvmTest.dependencies {
      implementation(kotlin("test-junit"))
      implementation(compose.desktop.uiTestJUnit4)
    }
  }
}

tasks.register<JavaExec>("compileDocument") {
  dependsOn("jvmMainClasses")
  val target =
    kotlin.targets.getByName("jvm") as org.jetbrains.kotlin.gradle.targets.jvm.KotlinJvmTarget
  classpath =
    target.compilations.getByName("main").output.allOutputs +
      configurations.getByName("jvmRuntimeClasspath")
  mainClass.set("poc.CompilerKt")
  args(projectDir.resolve("fixture.remote.json"), projectDir.resolve("build/fixture.rc"))
}

tasks.withType<Test>().configureEach {
  providers.gradleProperty("repetitionProofDir").orNull?.let { path ->
    inputs.dir(path)
    systemProperty("repetitionProofDir", file(path).absolutePath)
  }
  providers.gradleProperty("productionStringJsonDir").orNull?.let { path ->
    inputs.dir(path)
    systemProperty("productionStringJsonDir", file(path).absolutePath)
  }
  providers.gradleProperty("productionJsonDir").orNull?.let { path ->
    inputs.dir(path)
    systemProperty("productionJsonDir", file(path).absolutePath)
  }
  systemProperty(
    "verifySharedJsonCompiler",
    providers.gradleProperty("localJsonCompilerManifest").isPresent,
  )
  inputs.file("fixture.remote.json")
  inputs.file("fixture-density2.remote.json")
  systemProperty("java.awt.headless", "true")
  systemProperty("skiko.renderApi", "SOFTWARE")
  testLogging {
    events("passed", "failed", "skipped")
    exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
  }
}

tasks.register("writeRuntimeClasspath") {
  dependsOn("jvmMainClasses")
  doLast {
    val target =
      kotlin.targets.getByName("jvm") as org.jetbrains.kotlin.gradle.targets.jvm.KotlinJvmTarget
    val cp =
      target.compilations.getByName("main").output.allOutputs +
        configurations.getByName("jvmRuntimeClasspath")
    layout.buildDirectory.file("runtime-classpath.txt").get().asFile.writeText(cp.asPath)
  }
}
