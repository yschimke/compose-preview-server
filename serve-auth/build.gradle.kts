import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.component.ProjectComponentIdentifier

// `:serve-auth` — GitHub OAuth session cookies, agent-grant verification/store, MCP OAuth,
// image-upload auth, browse cookies, same-origin request checks, site/URL helpers, and owner-only
// secret/file helpers.
//
// Extracted from `:server` as the second foundational seam (alongside `:playground-engine`) so
// standalone services (`ui.coo.ee`, Watch Face Push, Playground, `design`) can share one sign-in
// and grant verification flow without depending on `:server`, render hosts, or daemons.
// Sources keep the `ee.schimke.composeai.cli.serve` package for source continuity.
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
  api(platform(libs.composeai.contracts.bom))
  api(platform(libs.composeai.tools.bom))

  api(libs.composeai.agent.grant.protocol)
  api(libs.composeai.common.web.escaping)
  api(libs.ktor.server.core)
  api(libs.okhttp)
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
 * Enforces that `:serve-auth` stays a thin, reusable auth/grant library: no `:server`, no
 * `:playground-engine`, no UI builder, no Watch Face, no Livery, no render host, and no
 * daemon/renderer implementations.
 */
abstract class CheckServeAuthBoundary : DefaultTask() {
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
      ":serve-auth must stay free of :server, :playground-engine, UI builder, Watch Face, Livery, " +
        "render-host and daemon/renderer implementations. Found on runtimeClasspath: " +
        hits.joinToString(", ")
    }
  }
}

tasks.register<CheckServeAuthBoundary>("checkServeAuthBoundary") {
  description =
    "Fails if :server, UI builder, render-host or daemon/renderer modules reach :serve-auth."
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
      "project :watchface-engine",
      "project :cli",
      "project :ui-builder",
      "project :wasm-ui",
      "module ee.schimke.composeai:compose-preview-ui-builder-",
      "module ee.schimke.composeai:ui-builder-",
      "module ee.schimke.composeai:livery-",
      "module ee.schimke.composeai:render-host",
      "module ee.schimke.composeai:render-session-",
      "module ee.schimke.composeai:renderer-",
      "module ee.schimke.composeai:daemon-",
      "module ee.schimke.composeai:bundle-",
      "module ee.schimke.composeai:compose-preview-plugin",
      "module ee.schimke.watchface:",
    ),
  )
}

tasks.named("check") { dependsOn("checkServeAuthBoundary") }
