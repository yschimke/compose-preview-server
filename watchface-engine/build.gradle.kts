import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.component.ProjectComponentIdentifier

// `:watchface-engine` — Watch Face Format (WFF/DWF) packaging, validation, preview model,
// preview session lifecycle and APK daemon runtime bridge.
//
// Extracted from `:server` so watch-face hosting and validation can be unbundled into a
// standalone service without depending on `:server`, Ktor server, UI builder, or Livery.
// Sources keep the `ee.schimke.composeai.cli.serve` package for source continuity.
plugins {
  `java-library`
  `java-test-fixtures`
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.ktfmt)
}

group = "ee.schimke.composeai"

kotlin { jvmToolchain(libs.versions.java.server.get().toInt()) }

ktfmt { googleStyle() }

dependencies {
  api(platform(libs.composeai.tools.bom))
  api(platform(libs.composeai.contracts.bom))
  api(platform(libs.composeai.daemon.bom))

  api(libs.composeai.render.host)
  api(libs.wff.core)
  implementation(libs.composeai.common.io)
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.kotlinx.serialization.json)

  testFixturesImplementation(libs.kotlinx.serialization.json)

  testImplementation(kotlin("test"))
}

tasks.withType<Test>().configureEach {
  useJUnitPlatform()
  maxParallelForks =
    providers.gradleProperty("composeai.testForks").orNull?.toInt()
      ?: (Runtime.getRuntime().availableProcessors() / 2).coerceAtLeast(1)
}

/**
 * Enforces that `:watchface-engine` stays free of Ktor server, UI builder, Livery, `:server`,
 * `:playground-engine`, renderer/daemon implementations, and `wff-packager` / `xercesImpl` (which
 * must stay quarantined in `lib-wff-packager/`).
 */
abstract class CheckWatchFaceEngineBoundary : DefaultTask() {
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
      ":watchface-engine must stay free of Ktor server, UI builder, Livery, :server, " +
        ":playground-engine, renderer/daemon implementations and wff-packager/xerces. " +
        "Found on runtimeClasspath: ${hits.joinToString(", ")}"
    }
  }
}

tasks.register<CheckWatchFaceEngineBoundary>("checkWatchFaceEngineBoundary") {
  description =
    "Fails if Ktor server, UI builder, :server or wff-packager reaches :watchface-engine."
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
      "project :cli",
      "project :ui-builder",
      "project :wasm-ui",
      // `ktor-client-*` arrives transitively via `render-host`; no HTTP server engine may enter.
      "module io.ktor:ktor-server-",
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

tasks.named("check") { dependsOn("checkWatchFaceEngineBoundary") }
