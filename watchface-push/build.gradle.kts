import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.component.ProjectComponentIdentifier

// `:watchface-push` — Watch Face Push marketplace domain: publication store, device linking,
// signed APK build queue, catalog face discovery, and push settings.
//
// Extracted from `:server` as a UI-builder-free, HTTP-route-free module depending only on
// `:watchface-engine` and `:serve-auth` so it can move to `yschimke/watchface-tools` or ship as a
// standalone Watch Face Push service. Sources keep the `ee.schimke.composeai.cli.serve` package
// for source continuity.
plugins {
  `java-library`
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.ktfmt)
}

group = "ee.schimke.composeai"

kotlin { jvmToolchain(libs.versions.java.server.get().toInt()) }

ktfmt { googleStyle() }

dependencies {
  api(project(":watchface-engine"))
  api(project(":serve-auth"))
  implementation(libs.kotlinx.serialization.json)

  testImplementation(kotlin("test"))
}

tasks.withType<Test>().configureEach {
  useJUnitPlatform()
  maxParallelForks =
    providers.gradleProperty("composeai.testForks").orNull?.toInt()
      ?: (Runtime.getRuntime().availableProcessors() / 2).coerceAtLeast(1)
}

/**
 * Enforces that `:watchface-push` stays free of `:server`, `:playground-engine`, `:livery-host`, UI
 * builder, Livery, renderer/daemon implementations, and `wff-packager` / `xerces`.
 */
abstract class CheckWatchFacePushBoundary : DefaultTask() {
  @get:Input abstract val resolvedComponents: SetProperty<String>

  @get:Input abstract val forbiddenPrefixes: ListProperty<String>

  @TaskAction
  fun check() {
    val resolved = resolvedComponents.get()
    val prefixes = forbiddenPrefixes.get()
    val hits =
      resolved
        .filter { component -> prefixes.any { prefix -> component.startsWith(prefix) } }
        .sorted()
    check(hits.isEmpty()) {
      ":watchface-push must stay free of :server, :playground-engine, :livery-host, UI builder, " +
        "Livery, renderer/daemon implementations and wff-packager/xerces. " +
        "Found on runtimeClasspath: ${hits.joinToString(", ")}"
    }
  }
}

tasks.register<CheckWatchFacePushBoundary>("checkWatchFacePushBoundary") {
  description = "Fails if :server, UI builder, Livery or wff-packager reaches :watchface-push."
  group = "verification"

  resolvedComponents.set(
    configurations.named("runtimeClasspath").flatMap { configuration ->
      configuration.incoming.artifacts.resolvedArtifacts.map { artifacts ->
        artifacts
          .map { artifact ->
            when (val id = artifact.id.componentIdentifier) {
              is ProjectComponentIdentifier -> "project ${id.projectPath}"
              is ModuleComponentIdentifier -> "module ${id.group}:${id.module}"
              else -> "other ${id.displayName}"
            }
          }
          .toSet()
      }
    },
  )

  forbiddenPrefixes.set(
    listOf(
      "project :server",
      "project :playground-engine",
      "project :livery-host",
      "project :cli",
      "project :ui-builder",
      "project :wasm-ui",
      "module ee.schimke.composeai:compose-preview-ui-builder-",
      "module ee.schimke.composeai:ui-builder-",
      "module ee.schimke.composeai:livery-",
      "module ee.schimke.composeai:renderer-",
      "module ee.schimke.composeai:daemon-android",
      "module ee.schimke.composeai:daemon-desktop",
      "module ee.schimke.composeai:compose-preview-plugin",
      "module ee.schimke.watchface:wff-packager",
      "module xerces:",
    ),
  )
}

tasks.named("check") { dependsOn("checkWatchFacePushBoundary") }
