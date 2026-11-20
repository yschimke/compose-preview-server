import java.io.File
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.tasks.ClasspathNormalizer
import org.gradle.process.CommandLineArgumentProvider

// `:playground-engine` — the compile, sandbox and render engine shared by the HTTP playground,
// the UI-builder native preview/export lane, and `compose-preview-server design --local`.
//
// Extracted from `:server` so those consumers depend on a transport-free, UI-builder-free engine
// rather than reaching into `:server`'s package internals
// (`docs/design/COMPOSE_PREVIEW_DESIGN_SPLIT.md`).
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
  api(platform(libs.composeai.tools.bom))
  api(platform(libs.composeai.contracts.bom))
  api(platform(libs.composeai.daemon.bom))

  api(libs.composeai.render.host)
  api(libs.composeai.bundle.format)
  api(libs.composeai.bundle.coordinates)
  api(libs.composeai.daemon.core)
  api(libs.composeai.render.session.api)
  api(libs.composeai.render.session.subprocess)

  implementation(libs.composeai.common.io)
  implementation(libs.composeai.data.layoutinspector.core)
  implementation(libs.composeai.data.remotecompose.core)
  implementation(libs.kotlinx.serialization.json)
  implementation(libs.classgraph)

  // BTA interfaces only, for `PlaygroundBtaCompiler`'s in-process compile types. Not transitive
  // from `daemon-core`; the impl jars ride in the host's `lib-bta/` isolated classloader.
  implementation("org.jetbrains.kotlin:kotlin-build-tools-api:${libs.versions.kotlin.get()}")

  testImplementation(kotlin("test"))
  testImplementation(testFixtures(libs.composeai.render.host))
  testImplementation(libs.okio.fakefilesystem)
}

// Isolated BTA + Compose compiler plugin jars for tests exercising `PlaygroundBtaCompiler`.
val composePreviewBta =
  configurations.create("composePreviewBta") {
    isCanBeResolved = true
    isCanBeConsumed = false
  }

dependencies {
  add(
    "composePreviewBta",
    "org.jetbrains.kotlin:kotlin-build-tools-impl:${libs.versions.kotlin.get()}",
  )
  add(
    "composePreviewBta",
    "org.jetbrains.kotlin:kotlin-compose-compiler-plugin-embeddable:${libs.versions.kotlin.get()}",
  )
}

tasks.withType<Test>().configureEach {
  useJUnitPlatform()

  maxParallelForks =
    providers.gradleProperty("composeai.testForks").orNull?.toInt()
      ?: (Runtime.getRuntime().availableProcessors() / 2).coerceAtLeast(1)

  val btaJars = composePreviewBta.incoming.files
  inputs.files(btaJars).withPropertyName("libBtaJars").withNormalizer(ClasspathNormalizer::class)
  jvmArgumentProviders.add(
    CommandLineArgumentProvider {
      listOf(
        "-Dcomposeai.libBtaJars=" + btaJars.joinToString(File.pathSeparator) { it.absolutePath },
      )
    },
  )
}

/**
 * Enforces that `:playground-engine` stays a transport-free, product-agnostic compile/render
 * engine: no Ktor/HTTP server, no UI builder, no Watch Face packager, no Livery, no `:server`, and
 * no renderer/daemon implementations.
 */
abstract class CheckPlaygroundEngineBoundary : DefaultTask() {
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
      ":playground-engine must stay free of HTTP/Ktor, UI builder, Watch Face, Livery, :server " +
        "and renderer/daemon implementations. Found on runtimeClasspath: ${hits.joinToString(", ")}"
    }
  }
}

tasks.register<CheckPlaygroundEngineBoundary>("checkPlaygroundEngineBoundary") {
  description =
    "Fails if HTTP/Ktor, UI builder, :server or renderer implementations reach :playground-engine."
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
      "module ee.schimke.watchface:",
      "module org.jetbrains.kotlin:kotlin-compiler-embeddable",
      "module org.jetbrains.kotlin:kotlin-build-tools-impl",
    ),
  )
}

tasks.named("check") { dependsOn("checkPlaygroundEngineBoundary") }
