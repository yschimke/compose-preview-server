import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.tasks.bundling.Compression
import org.gradle.api.tasks.bundling.Tar

// `:livery-host` — standalone host and Ktor route adapter for Livery public component publications
// (`/p/<slug>/r/<rev>/…`).
//
// Extracted from `:server` so deployments like `yschimke/preview-coo-ee` can run public component
// publications as a tiny standalone container behind Caddy without pulling in `:server`, render
// hosts, daemons, UI builder, or Watch Face tooling. `:server` still links this module so
// single-container deployments keep working unchanged.
plugins {
  application
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.ktfmt)
}

group = "ee.schimke.composeai"

version =
  providers.environmentVariable("PLUGIN_VERSION").orNull
    ?: run {
      val manifest = rootDir.resolve(".release-please-manifest.json").readText()
      val current = Regex(""""\.":\s*"([^"]+)"""").find(manifest)!!.groupValues[1]
      val (major, minor, patch) = current.split(".").map { it.toInt() }
      "$major.$minor.${patch + 1}-SNAPSHOT"
    }

kotlin { jvmToolchain(libs.versions.java.server.get().toInt()) }

ktfmt { googleStyle() }

application {
  applicationName = "compose-preview-livery-host"
  mainClass.set("ee.schimke.composeai.cli.serve.LiveryHostMainKt")
}

tasks.named<Tar>("distTar") {
  compression = Compression.GZIP
  archiveExtension.set("tar.gz")
}

dependencies {
  implementation(libs.livery.server)
  implementation(libs.ktor.server.core)
  implementation(libs.ktor.server.cio)
  implementation(libs.ktor.server.auto.head.response)

  testImplementation(kotlin("test"))
}

tasks.withType<Test>().configureEach {
  useJUnitPlatform()
  maxParallelForks =
    providers.gradleProperty("composeai.testForks").orNull?.toInt()
      ?: (Runtime.getRuntime().availableProcessors() / 2).coerceAtLeast(1)
}

/**
 * Enforces that `:livery-host` stays a minimal Livery publication host: no `:server`, no
 * `:playground-engine`, no `:watchface-engine`, no UI builder, no Watch Face, no render host, and
 * no daemon/renderer implementations.
 */
abstract class CheckLiveryHostBoundary : DefaultTask() {
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
      ":livery-host must stay free of :server, :playground-engine, :watchface-engine, UI builder, " +
        "Watch Face, render-host and daemon/renderer implementations. Found on runtimeClasspath: " +
        hits.joinToString(", ")
    }
  }
}

tasks.register<CheckLiveryHostBoundary>("checkLiveryHostBoundary") {
  description =
    "Fails if :server, UI builder, render-host or daemon/renderer modules reach :livery-host."
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
      "project :serve-auth",
      "project :cli",
      "project :ui-builder",
      "project :wasm-ui",
      "module ee.schimke.composeai:compose-preview-ui-builder-",
      "module ee.schimke.composeai:ui-builder-",
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

tasks.named("check") { dependsOn("checkLiveryHostBoundary") }
