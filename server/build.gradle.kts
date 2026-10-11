import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedArtifactResult
import org.gradle.api.attributes.Category
import org.gradle.api.attributes.LibraryElements
import org.gradle.api.attributes.Usage
import org.gradle.api.tasks.ClasspathNormalizer
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.bundling.Compression
import org.gradle.api.tasks.bundling.Tar
import org.gradle.process.CommandLineArgumentProvider

// `compose-preview serve`: the preview server, as its own module. Nothing here can see `:cli`
// (`:cli` depends on this, and `checkServeModuleBoundary` below enforces the direction). Sources
// keep the `ee.schimke.composeai.cli.serve` package; renaming it is a separate change from the move
// (see docs/design/PREVIEW_SERVER_SPLIT.md). The server implementation, argv semantics, defaults
// and usage text live here; `:cli` keeps only a thin adapter.
plugins {
  application
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.ktfmt)
  // `FakeRenderSession` is shared test scaffolding (with `:cli`'s `BundleRenderKnobTest`); a test
  // fixture so it never reaches the server's runtime classpath.
  `java-test-fixtures`
}

group = "ee.schimke.composeai"

kotlin { jvmToolchain(libs.versions.java.server.get().toInt()) }

// The small MCP App is shipped by both independently installable MCP servers. It renders results
// from the existing tool surface; it is deliberately not a second UI Builder editor.
kotlin.sourceSets.named("main") { resources.srcDir(rootProject.file("mcp-app")) }

ktfmt { googleStyle() }

// Same derivation as `:cli` (PLUGIN_VERSION in CI, a patch-bumped SNAPSHOT from
// `.release-please-manifest.json` locally). Without it `generateServeVersionResource` writes
// `version=unspecified`, which the server reports everywhere. Kept separate from `:cli`'s since the
// two are expected to diverge.
version =
  providers.environmentVariable("PLUGIN_VERSION").orNull
    ?: run {
      val manifest = rootDir.resolve(".release-please-manifest.json").readText()
      val current = Regex(""""\.":\s*"([^"]+)"""").find(manifest)!!.groupValues[1]
      val (major, minor, patch) = current.split(".").map { it.toInt() }
      "$major.$minor.${patch + 1}-SNAPSHOT"
    }

// Distinctive jar name for the CLI distribution's `lib/` (like `compose-preview`,
// `compose-preview-mcp`).
base { archivesName.set("compose-preview-serve") }

application {
  applicationName = "compose-preview-server"
  mainClass.set("ee.schimke.composeai.cli.serve.StandaloneServerMainKt")
}

evaluationDependsOn(":wasm-ui")

val uiBuilderWeb =
  configurations.create("uiBuilderWeb") {
    description = "Immutable Compose/Wasm UI-builder frontend archive."
    isCanBeConsumed = false
    isCanBeResolved = true
    isTransitive = false
    attributes {
      attribute(Category.CATEGORY_ATTRIBUTE, objects.named("distribution"))
      attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named("ui-builder-web"))
      attribute(Usage.USAGE_ATTRIBUTE, objects.named("ui-builder-web"))
    }
  }

abstract class UnpackUiBuilderWeb : DefaultTask() {
  @get:InputFile
  @get:PathSensitive(PathSensitivity.NONE)
  abstract val archiveFile: RegularFileProperty

  /**
   * The editor version stamped into `ui-builder-web.json` when the archive doesn't carry one; the
   * server reports the bundled editor from that manifest.
   */
  @get:Input abstract val editorVersion: Property<String>

  @get:OutputDirectory abstract val outputDirectory: DirectoryProperty

  @get:Inject abstract val archiveOperations: ArchiveOperations

  @get:Inject abstract val fileSystemOperations: FileSystemOperations

  @TaskAction
  fun unpack() {
    fileSystemOperations.sync {
      from(archiveOperations.zipTree(archiveFile))
      into(outputDirectory)
    }
    val manifest = outputDirectory.file("ui-builder-web.json").get().asFile
    if (!manifest.isFile) {
      // serverApi 1: the bundled editor is the one this server is built and tested against, so it
      // speaks this server's API by construction. See ServeUiBuilderEditor.SUPPORTED_SERVER_API.
      manifest.writeText(
        """{"schema":"compose-ui-builder-web/v1","version":"${editorVersion.get()}","serverApi":1}""" +
          "\n"
      )
    }
  }
}

val unpackUiBuilderWeb =
  tasks.register<UnpackUiBuilderWeb>("unpackUiBuilderWeb") {
    description =
      "Unpack the immutable UI-builder frontend into the server distribution staging area."
    group = "distribution"
    archiveFile.set(
      layout.file(uiBuilderWeb.elements.map { artifacts -> artifacts.single().asFile })
    )
    editorVersion.set(libs.versions.composeai.ui.builder)
    outputDirectory.set(layout.buildDirectory.dir("ui-builder-web"))
  }

// Subprocess-only renderer/daemon runtimes. Deliberately not on any server classpath: the launcher
// finds them under APP_HOME and the render host passes them only to the isolated daemon JVM.
val composePreviewRenderer =
  configurations.create("composePreviewRenderer") {
    isCanBeResolved = true
    isCanBeConsumed = false
  }
val composePreviewDaemonDesktop =
  configurations.create("composePreviewDaemonDesktop") {
    isCanBeResolved = true
    isCanBeConsumed = false
  }

fun registerIsolatedDesktopSidecar(
  taskName: String,
  configuration: Configuration,
  destination: String,
) =
  tasks.register<Sync>(taskName) {
    destinationDir = layout.buildDirectory.dir(destination).get().asFile
    val artifactsProvider = configuration.incoming.artifacts.resolvedArtifacts
    from(
      artifactsProvider.map { resolved ->
        resolved
          .filterNot { it.file.name.startsWith("skiko-awt-runtime-") }
          .map(ResolvedArtifactResult::getFile)
      }
    )
    val nameByPath = artifactsProvider.map { resolved ->
      val staged = resolved.filterNot { it.file.name.startsWith("skiko-awt-runtime-") }
      val counts = staged.groupingBy { it.file.name }.eachCount()
      staged.associate { artifact ->
        val original = artifact.file.name
        val mapped =
          if (counts.getValue(original) > 1) {
            val id = artifact.id.componentIdentifier
            if (id is ModuleComponentIdentifier) "${id.module}-${id.version}.jar" else original
          } else original
        artifact.file.absolutePath to mapped
      }
    }
    inputs.property("nameByPath", nameByPath)
    eachFile { nameByPath.get()[file.absolutePath]?.let { name = it } }
  }

val stageRendererLibs =
  registerIsolatedDesktopSidecar(
    "stageRendererLibs",
    composePreviewRenderer,
    "staged-renderer-libs",
  )
val stageDaemonDesktopLibs =
  registerIsolatedDesktopSidecar(
    "stageDaemonDesktopLibs",
    composePreviewDaemonDesktop,
    "staged-daemon-desktop-libs",
  )

/**
 * The distribution's Java floor, shipped as data (`java-min.properties` at the distribution root).
 * compose-ai-tools' launchers exec `bin/compose-preview-server`, which uses whatever `java` is on
 * `JAVA_HOME`/`PATH`, so a JVM below the floor fails with an unexplained
 * `UnsupportedClassVersionError` (#344). The floor belongs to this repository, so the distribution
 * states it in a file a launcher can read without executing anything. An older distribution has no
 * file, so nothing is preflighted.
 *
 * Only `java-server`: the UI builder's higher floor belongs to the render bundle and is diagnosed
 * by `ServeUiBuilderRenderPort`.
 */
val writeDistributionJavaMin =
  tasks.register("writeDistributionJavaMin") {
    description = "Write the distribution's Java floor for a launcher to preflight."
    val manifest = layout.buildDirectory.file("generated/distribution/java-min.properties")
    val javaMin = libs.versions.java.server.get().toInt()
    inputs.property("javaMin", javaMin)
    outputs.file(manifest)
    doLast {
      manifest
        .get()
        .asFile
        .also { it.parentFile.mkdirs() }
        .writeText(
          buildString {
            appendLine("# Generated by :server:writeDistributionJavaMin. Do not edit.")
            appendLine("# The minimum Java feature version this distribution can run on.")
            appendLine("javaMin=$javaMin")
          }
        )
    }
  }

// The cmp-jvm Remote Compose lane shapes text with the CMP/Wasm player's own `fonts/`, shipped in
// `<APP_HOME>/rc-player-wasm/` by `stageRcPlayerWasm` and handed to the worker by
// `ServeRcJvmFonts`. One copy, so server-side and in-browser players draw from the same files.
// compose-ai-tools' `RcJvmServerRenderer` also looks there by default.

// The CMP/Wasm Remote Compose player, shipped as `<APP_HOME>/rc-player-wasm/` (the image entrypoint
// passes it as `--rc-player-wasm-dir` when it holds `index.html`). Resolved from Central
// (`rc-player-wasm-dist`) and unpacked as static files.
val rcPlayerWasmDist =
  configurations.create("rcPlayerWasmDist") {
    description = "The CMP/Wasm Remote Compose player's static browser bundle."
    isCanBeConsumed = false
    isCanBeResolved = true
    isTransitive = false
  }

dependencies {
  rcPlayerWasmDist(
    variantOf(libs.rc.player.wasm.dist) {
      classifier("dist")
      artifactType("zip")
    }
  )
}

/** Unpack the rc-player-wasm-dist zip, refusing one without the `index.html` the host serves. */
abstract class UnpackRcPlayerWasm : DefaultTask() {
  @get:InputFile abstract val archiveFile: RegularFileProperty

  @get:OutputDirectory abstract val outputDirectory: DirectoryProperty

  @get:Inject abstract val archiveOperations: ArchiveOperations

  @get:Inject abstract val fileSystemOperations: FileSystemOperations

  @TaskAction
  fun unpack() {
    fileSystemOperations.sync {
      from(archiveOperations.zipTree(archiveFile))
      into(outputDirectory)
    }
    check(outputDirectory.file("index.html").get().asFile.isFile) {
      "rc-player-wasm-dist unpacked without an index.html; the entrypoint would skip it"
    }
  }
}

val stageRcPlayerWasm =
  tasks.register<UnpackRcPlayerWasm>("stageRcPlayerWasm") {
    description = "Unpack the CMP/Wasm Remote Compose player into the distribution staging area."
    group = "distribution"
    archiveFile.set(
      layout.file(rcPlayerWasmDist.elements.map { artifacts -> artifacts.single().asFile })
    )
    outputDirectory.set(layout.buildDirectory.dir("rc-player-wasm"))
  }

// The TypeScript Remote Compose player behind `/rc-player/bundle.js` (the camaelon-js lane and
// shared `/d/<id>` pages), resolved from Central (`remote-compose-player-js-dist`). Used as
// published, with `src/rc-player/inert-custom-host.js` appended: the player wires a live
// `WebCustomHost` into every document, and this server plays documents it didn't write.
val rcPlayerJsDist =
  configurations.create("rcPlayerJsDist") {
    description = "The TypeScript Remote Compose player's browser bundle."
    isCanBeConsumed = false
    isCanBeResolved = true
    isTransitive = false
  }

dependencies {
  rcPlayerJsDist(
    variantOf(libs.remote.compose.player.js.dist) {
      classifier("dist")
      artifactType("zip")
    }
  )
}

val stageRcPlayerJs =
  tasks.register<StageRcPlayerJs>("stageRcPlayerJs") {
    description = "Stage the TypeScript Remote Compose player bundle as a server resource."
    archiveFile.set(
      layout.file(rcPlayerJsDist.elements.map { artifacts -> artifacts.single().asFile })
    )
    shimFile.set(layout.projectDirectory.file("src/rc-player/inert-custom-host.js"))
    resourcePath.set("rc-player/bundle.js")
    outputDirectory.set(layout.buildDirectory.dir("generated/rc-player-js"))
  }

sourceSets.main { resources.srcDir(stageRcPlayerJs) }

distributions {
  main {
    contents { from(project(":wasm-ui").tasks.named("wasmFrontendDist")) { into("wasm-ui") } }
    contents { from(unpackUiBuilderWeb) { into("ui-builder") } }
    // The component record the Compose export reads; without it a packaged host advertises no
    // Compose export.
    contents {
      from(rootProject.layout.projectDirectory.dir("docs/design/fixtures/ui-builder")) {
        include("m3-catalog-components-v1.json")
        into("ui-builder-components")
      }
    }
    contents {
      into("lib-renderer") { from(stageRendererLibs) }
      into("lib-daemon-desktop") { from(stageDaemonDesktopLibs) }
    }
    // The CMP/Wasm Remote Compose player — see `stageRcPlayerWasm`. Its `fonts/` is also the
    // cmp-jvm render worker's typefaces.
    contents { into("rc-player-wasm") { from(stageRcPlayerWasm) } }
    contents { from(writeDistributionJavaMin) }
  }
}

abstract class CheckServerDesktopSidecarPackaging : DefaultTask() {
  @get:InputFiles
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val rendererJars: ConfigurableFileCollection

  @get:InputFiles
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val daemonJars: ConfigurableFileCollection

  @TaskAction
  fun checkPackaging() {
    val renderer = rendererJars.files.flatMap { it.listFiles()?.toList().orEmpty() }
    val daemon = daemonJars.files.flatMap { it.listFiles()?.toList().orEmpty() }
    check(renderer.any { it.name.startsWith("renderer-desktop-") }) {
      "Standalone server distribution lost renderer-desktop"
    }
    check(renderer.any { it.name.matches(Regex("skiko-awt-[^-].*\\.jar")) }) {
      "Standalone server distribution lost the Skiko API used for host-native provisioning"
    }
    check(daemon.any { it.name.startsWith("daemon-desktop-") }) {
      "Standalone server distribution lost daemon-desktop"
    }
    check(daemon.any { it.name.startsWith("components-resources-desktop-") }) {
      "Standalone server distribution lost Compose resource support"
    }
    check((renderer + daemon).none { it.name.startsWith("skiko-awt-runtime-") }) {
      "Portable server distribution contains a host-specific Skiko native"
    }
    // The `androidx.window` classes the daemon force-delegates to its parent loader. Without this
    // jar the UI-builder render fails with `NoClassDefFoundError:
    // androidx/window/core/layout/WindowSizeClass` on the first constrained frame (#812). The
    // dependency is easy to drop; this notices.
    check(renderer.any { it.name.startsWith("window-core-desktop-") }) {
      "Standalone server distribution lost window-core, which the UI-builder render needs on the " +
        "daemon parent loader (#812)"
    }
    listOf("lib-renderer" to renderer, "lib-daemon-desktop" to daemon).forEach { (name, jars) ->
      val duplicates = jars.groupingBy { it.name }.eachCount().filterValues { it > 1 }.keys
      check(duplicates.isEmpty()) { "$name contains colliding filenames: $duplicates" }
    }
  }
}

val checkServerDesktopSidecarPackaging =
  tasks.register<CheckServerDesktopSidecarPackaging>("checkServerDesktopSidecarPackaging") {
    description = "Checks the standalone server's isolated desktop renderer sidecars."
    group = "verification"
    dependsOn(stageRendererLibs, stageDaemonDesktopLibs)
    rendererJars.from(stageRendererLibs)
    daemonJars.from(stageDaemonDesktopLibs)
  }

tasks.named("check") { dependsOn(checkServerDesktopSidecarPackaging) }

tasks.named<Tar>("distTar") {
  compression = Compression.GZIP
  archiveExtension.set("tar.gz")
}

// The standalone server application (no longer published; module edges still state the code
// boundary). Deliberately without `explicitApi()`: this is the server, not a contract, and marking
// ~1,200 declarations public would freeze an undesigned ABI. Narrowing to what `:cli` uses comes
// first.

dependencies {
  // The editor archive from yschimke/compose-ui-builder's GitHub release: a bare ZIP with no module
  // metadata, so requested artifact-only (`@zip`). `-PcomposeUiBuilderDir` substitutes the included
  // build's `:ui-builder-web`, whose `runtimeElements` carries the same archive.
  add(
    "uiBuilderWeb",
    "${libs.composeai.ui.builder.web.get()}@zip",
  )

  // The render host, bundle daemon and git-backed history, published from compose-ai-tools so the
  // CLI's offline `bundle render` / `history manifest` reach them without a web server. `api`
  // because their types (`ServeHost`, `RenderOutcome`) are throughout this module's signatures. The
  // sources keep the `ee.schimke.composeai.cli.serve` package.
  api(libs.composeai.render.host)

  // The build-host protocol, `implementation`: only `ServeBuildHost` is programmed against
  // elsewhere.
  implementation(libs.composeai.build.host.protocol)
  // Authoritative persistence, validation, collaboration and export orchestration. The server
  // supplies Ktor/auth and the narrow render-host adapter; the runtime has neither dependency.
  api(libs.composeai.ui.builder.runtime)
  // The saved-document projection and the generator behind it. Multiplatform, so the
  // browser editor reaches the same code rather than keeping an emitter of its own.
  implementation(libs.composeai.ui.builder.export)

  api(libs.composeai.common.web.escaping)
  // Published wire-format DTOs and the bundle format, `api` because they appear in signatures
  // `:cli` reads. Versions come from these platforms, on `api` so they reach `implementation` too.
  api(platform(libs.composeai.tools.bom))
  api(platform(libs.composeai.contracts.bom))
  api(platform(libs.composeai.daemon.bom))
  // The UI-builder runtime, export and render bundle get their versions from this platform, so
  // runtime and export can't skew.
  api(platform(libs.composeai.ui.builder.bom))
  api(libs.composeai.preview.data.api)
  // `ScreenGenerator` and its component record. The UI-builder runtime can't depend on it, which is
  // why the Compose-source executor is constructed here.
  implementation(libs.composeai.preview.discovery)
  implementation(libs.composeai.common.image.crop)
  api(libs.composeai.bundle.format)
  api(libs.composeai.agent.grant.protocol)
  api(libs.composeai.bundle.coordinates)
  api(libs.composeai.daemon.core)
  api(libs.composeai.daemon.client)
  api(libs.composeai.render.session.api)
  api(libs.composeai.render.session.subprocess)

  implementation(libs.composeai.common.io)
  implementation(libs.composeai.data.layoutinspector.core)
  implementation(libs.composeai.data.theme.core)
  implementation(libs.composeai.data.pseudolocale.core)
  implementation(libs.composeai.data.preview.overrides.core)
  implementation(libs.composeai.data.remotecompose.core)
  // Projects a captured `.rc` into JSON for `GET /render/<id>.rc.json`. Unrelated to
  // `data-remotecompose-core` (the knob payload) despite the name.
  implementation(libs.composeai.remotecompose.json)
  implementation(libs.composeai.data.render.core)

  implementation(libs.kotlinx.serialization.json)
  implementation(libs.ktor.client.core)
  implementation(libs.ktor.client.okhttp)
  implementation(libs.okhttp)
  implementation(libs.ktor.server.core)
  implementation(libs.ktor.server.cio)
  implementation(libs.ktor.server.websockets)
  implementation(libs.ktor.server.compression)
  implementation(libs.ktor.server.auto.head.response)
  implementation(libs.classgraph)
  implementation(libs.jmdns)

  // The renderer and daemon publish from compose-preview-daemon on their own line. Versions come
  // from that release's BOM rather than the pin, since a release republishes only changed modules.
  add("composePreviewRenderer", platform(libs.composeai.daemon.bom))
  add("composePreviewRenderer", "ee.schimke.composeai:renderer-desktop")
  add("composePreviewDaemonDesktop", platform(libs.composeai.daemon.bom))
  add("composePreviewDaemonDesktop", "ee.schimke.composeai:daemon-desktop")

  // `androidx.window` on the renderer sidecar. `UserClassLoaderHolder.mustDelegateToParent` sends
  // every `androidx.` package to the parent loader, but
  // `ServeBundleDaemon.shouldPrecedeDaemonSidecar` promotes jars by group, and
  // `org.jetbrains.androidx.window:window-core` matches neither rule, so its classes were delegated
  // to a parent without them (#812). Until that rule learns `org.jetbrains.androidx.*` upstream,
  // the sidecar carries the library. `checkServerDesktopSidecarPackaging` fails if it stops being
  // packaged; the version is pinned beside `:ui-builder`'s (see the catalog).
  add("composePreviewRenderer", libs.androidx.window.core)

  // BTA interfaces only, for the playground compiler's in-process compile types. Not transitive
  // from `:daemon:core`; the impl jars ride in the CLI distribution's `lib-bta/`.
  implementation("org.jetbrains.kotlin:kotlin-build-tools-api:${libs.versions.kotlin.get()}")

  testImplementation(kotlin("test"))

  // `FakeRenderSession` lives in `:render-host`'s fixtures with `ServeRenderHost`, which it fakes.
  testImplementation(testFixtures(libs.composeai.render.host))

  // Re-exported so this module's test-fixtures variant keeps carrying `FakeRenderSession` for
  // consumers asking by the `compose-preview-serve` name; otherwise an empty fixtures jar would
  // still advertise the capability. `api` because consumers compile against it. Can go once `:cli`
  // takes the fixture from `:render-host` directly.
  testFixturesApi(testFixtures(libs.composeai.render.host))

  // In-memory FileSystem for tests asserting on-disk output.
  testImplementation(libs.okio.fakefilesystem)

  // The parse-only PSI spike (`PsiParseSpikeTest`). Test-only: the server's runtime classpath must
  // stay free of the compiler frontend; a real change would load it via the isolated `lib-bta/`
  // classloader.
  testImplementation(
    "org.jetbrains.kotlin:kotlin-compiler-embeddable:${libs.versions.kotlin.get()}"
  )
}

// Sidecar jars the server loads through an isolated classloader: BTA + Compose compiler plugin
// (`lib-bta/`) and the playground cleaner's Kotlin parser (`lib-usage-psi/`). Handed to the tests
// that exercise those reflective paths; without them the tests silently take the fallback branch
// and pass.
val composePreviewBta =
  configurations.create("composePreviewBta") {
    isCanBeResolved = true
    isCanBeConsumed = false
  }

val composePreviewUsagePsi =
  configurations.create("composePreviewUsagePsi") {
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
  // Private server implementation: staged only into the isolated parser classloader used by the
  // tests/host distribution, never onto the server's runtimeClasspath and never published.
  add("composePreviewUsagePsi", project(":usage-source-psi"))
}

tasks.withType<Test>().configureEach {
  // JUnit 5; without it the platform defaults to JUnit 4 and `org.junit.jupiter` tests don't
  // compile.
  useJUnitPlatform()

  // ~350 test classes in one JVM dominated wall time. Forks are separate JVMs (statics per fork);
  // half the cores by default. `-Pcomposeai.testForks=<n>` overrides it where tests own the machine
  // (CI).
  maxParallelForks =
    providers.gradleProperty("composeai.testForks").orNull?.toInt()
      ?: (Runtime.getRuntime().availableProcessors() / 2).coerceAtLeast(1)

  // Catalog checkouts for `UsageSnippetCorpusTest`, supplied by `scripts/usage-corpus.sh` (absent
  // by default, so a no-op). Forwarded so they're build inputs. `repos` is one `name=path,…`
  // property, so adding a catalog can't silently produce an empty corpus. The icon-migration
  // fixture inputs travel the same way; absent (as in CI), `LegacyIconNamesFixtureTest` audits the
  // committed fixtures.
  for (key in
    listOf(
      "composeai.usageCorpus.repos",
      "composeai.usageCorpus.out",
      "composeai.usageCorpus.samples",
      "composeai.materialSymbols.codePoints",
      "composeai.materialIcons.inventory",
    )) {
    providers.systemProperty(key).orNull?.let { systemProperty(key, it) }
  }

  // Via a `CommandLineArgumentProvider` so configurations resolve at execution time (configuration
  // cache).
  val btaJars = composePreviewBta.incoming.files
  inputs.files(btaJars).withPropertyName("libBtaJars").withNormalizer(ClasspathNormalizer::class)
  val usagePsiJars = composePreviewUsagePsi.incoming.files
  inputs
    .files(usagePsiJars)
    .withPropertyName("libUsagePsiJars")
    .withNormalizer(ClasspathNormalizer::class)

  // Wire fixtures under `scripts/design-artifacts/fixtures/` that tests read from disk; declared so
  // a fixture edit re-runs them.
  inputs
    .files(
      rootProject.layout.projectDirectory
        .dir("scripts/design-artifacts/fixtures")
        .asFileTree
        .matching { include("*.json", "state-actions.kt.txt") }
    )
    .withPropertyName("sharedWireFixtures")
    .withPathSensitivity(PathSensitivity.RELATIVE)

  // The UI-builder catalog fixtures read from disk by the published-catalog equivalence tests (the
  // cutover gates); declared so a regenerated fixture re-runs them.
  inputs
    .files(
      rootProject.layout.projectDirectory
        .dir("docs/design/fixtures/ui-builder")
        .asFileTree
        .matching { include("*.json") }
    )
    .withPropertyName("uiBuilderCatalogFixtures")
    .withPathSensitivity(PathSensitivity.RELATIVE)

  // The image Dockerfile, read by `ImageSandboxCountMirrorTest`; declared so editing it re-runs the
  // test.
  inputs
    .files(rootProject.layout.projectDirectory.file("deploy/image/Dockerfile"))
    .withPropertyName("imageDockerfile")
    .withPathSensitivity(PathSensitivity.RELATIVE)

  // The exact player bundle the distribution packages as `rc-player-wasm/`, so
  // `ServeRcJvmFontsTest` checks the fonts that ship rather than a source tree.
  inputs
    .files(stageRcPlayerWasm)
    .withPropertyName("rcPlayerWasm")
    .withPathSensitivity(PathSensitivity.RELATIVE)
  val rcJvmFontsDir = layout.buildDirectory.dir("rc-player-wasm/fonts")
  jvmArgumentProviders.add(
    CommandLineArgumentProvider {
      listOf("-Dcomposeai.test.rcJvmFontsDir=${rcJvmFontsDir.get().asFile.absolutePath}")
    }
  )

  jvmArgumentProviders.add(
    CommandLineArgumentProvider {
      listOf(
        "-Dcomposeai.libBtaJars=" + btaJars.joinToString(File.pathSeparator) { it.absolutePath },
        "-Dcomposeai.usagePsi.jars=" +
          (usagePsiJars + btaJars).joinToString(File.pathSeparator) { it.absolutePath },
      )
    }
  )
}

// Enforces that nothing on this module's resolved runtime classpath (transitives included) reaches
// `:cli`, a renderer, or a plugin implementation. `scripts/check-serve-seam.py` still measures the
// `:cli` → here direction by scanning source; a resolved classpath can't be defeated by reflection
// or string literals. An extracted preview server is a protocol client and never loads a renderer.
abstract class CheckServeModuleBoundary : DefaultTask() {
  /**
   * Every component on the resolved runtime classpath by identity (`project :cli`, `module
   * <group>:<name>`), not file location: a project published as a Maven coordinate sits in Gradle's
   * cache under no project directory, which a path check misses.
   */
  @get:Input abstract val resolvedComponents: SetProperty<String>

  @get:Input abstract val forbiddenProjects: SetProperty<String>

  @get:Input abstract val forbiddenModules: SetProperty<String>

  @get:Input abstract val allowedComposeAiModules: SetProperty<String>

  /**
   * Projects this module may depend on. `:render-host` is a deliberate exception (the server sits
   * on top of it); an allowlist keeps the rule against `:cli` or renderer projects.
   */
  @get:Input abstract val allowedProjects: SetProperty<String>

  @TaskAction
  fun checkBoundary() {
    val forbidden =
      forbiddenProjects.get().map { "project $it" } + forbiddenModules.get().map { "module $it" }
    val resolved = resolvedComponents.get()
    val projectDependencies =
      resolved
        .filter { it.startsWith("project ") }
        .filterNot { it.removePrefix("project ") in allowedProjects.get() }
    val unexpectedComposeAi =
      resolved
        .filter { it.startsWith("module ee.schimke.composeai:") }
        .filterNot { it.removePrefix("module ") in allowedComposeAiModules.get() }
    val hits =
      (resolved.filter { it in forbidden } + projectDependencies + unexpectedComposeAi).sorted()

    check(hits.isEmpty()) {
      "The preview server must not depend on the CLI or on a renderer implementation — that is " +
        "the whole point of #3824's split, and it is why `serve` is a module rather than a " +
        "package. Found on :server's resolved runtimeClasspath: ${hits.joinToString(", ")}"
    }
  }
}

tasks.register<CheckServeModuleBoundary>("checkServeModuleBoundary") {
  description = "Fails if the CLI, a renderer, or the Gradle plugin reaches the server's classpath."
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
    }
  )

  // The UI-builder runtime, export and render bundle. Named as projects for `-PcomposeUiBuilderDir`
  // (included-build substitution); the default build matches them in `allowedComposeAiModules`.
  // `:ui-builder-render-bundle` arrives via the runtime's `api` edge and carries only a PNG.
  allowedProjects.set(
    listOf(":ui-builder-runtime", ":ui-builder-export", ":ui-builder-render-bundle")
  )

  // The same implementations named twice, because they can arrive by two different routes and the
  // identity differs between them.
  forbiddenProjects.set(
    listOf(":cli", ":daemon:android", ":daemon:desktop", ":renderer-android", ":renderer-desktop")
  )
  // Their published coordinates, for the transitive case a project-path check can't see.
  forbiddenModules.set(
    listOf(
      "ee.schimke.composeai:renderer-android",
      "ee.schimke.composeai:renderer-desktop",
      "ee.schimke.composeai:daemon-android",
      "ee.schimke.composeai:daemon-desktop",
      "ee.schimke.composeai:compose-preview-plugin",
      "ee.schimke.composeai.preview:ee.schimke.composeai.preview.gradle.plugin",
    )
  )

  // The full resolved Compose Preview floor, transitives included. A positive allowlist, so any new
  // internal artifact must be declared.
  allowedComposeAiModules.set(
    listOf(
      "ee.schimke.composeai:agent-grant-protocol",
      "ee.schimke.composeai:bundle-coordinates",
      "ee.schimke.composeai:bundle-format",
      "ee.schimke.composeai:common-image-crop",
      "ee.schimke.composeai:common-io",
      "ee.schimke.composeai:common-web-escaping",
      // Component record wire shapes from compose-preview-contracts, via `preview-discovery`;
      // contracts only.
      "ee.schimke.composeai:component-catalog-protocol",
      "ee.schimke.composeai:component-catalog-protocol-jvm",
      "ee.schimke.composeai:daemon-bta",
      "ee.schimke.composeai:daemon-client",
      // The connector SPI `daemon-core` exposes as `api`; no renderer behind it.
      "ee.schimke.composeai:daemon-connector-api",
      "ee.schimke.composeai:daemon-core",
      "ee.schimke.composeai:daemon-devices",
      "ee.schimke.composeai:daemon-protocol",
      // Design-guidelines wire shapes from compose-preview-contracts, via `render-host`; contracts
      // only.
      "ee.schimke.composeai:design-guidelines-protocol",
      "ee.schimke.composeai:design-guidelines-protocol-jvm",
      "ee.schimke.composeai:data-layoutinspector-core",
      // A `runtime` edge of `daemon-core`: the pure-JVM APNG codec and interaction-script types.
      "ee.schimke.composeai:data-motion-core",
      "ee.schimke.composeai:data-preview-overrides-core",
      "ee.schimke.composeai:data-pseudolocale-core",
      "ee.schimke.composeai:data-remotecompose-core",
      "ee.schimke.composeai:data-render-core",
      // The Remote Compose JSON codec behind `GET /render/<id>.rc.json`; carries a Remote Compose
      // runtime, so it is declared.
      "ee.schimke.composeai:remotecompose-json",
      "ee.schimke.composeai:data-theme-core",
      "ee.schimke.composeai:parity-issues-protocol",
      "ee.schimke.composeai:preview-data-api",
      // The render host, published from compose-ai-tools.
      "ee.schimke.composeai:build-host-protocol",
      "ee.schimke.composeai:render-host",
      "ee.schimke.composeai:preview-discovery",
      "ee.schimke.composeai:render-session-api",
      "ee.schimke.composeai:render-session-subprocess",
      // The UI-builder seams as published coordinates (projects under `-PcomposeUiBuilderDir`).
      // `-render-bundle` arrives via the runtime's `api` edge, so only this list names it;
      // `-export-jvm` is the export module's KMP variant.
      "ee.schimke.composeai:compose-preview-ui-builder-runtime",
      "ee.schimke.composeai:compose-preview-ui-builder-export",
      "ee.schimke.composeai:compose-preview-ui-builder-export-jvm",
      "ee.schimke.composeai:compose-preview-ui-builder-render-bundle",
      // The offline screen model and generator, reached through `:ui-builder-export`.
      "ee.schimke.composeai:screen-document",
      "ee.schimke.composeai:screen-document-jvm",
      "ee.schimke.composeai:screen-model",
      "ee.schimke.composeai:screen-model-jvm",
      "ee.schimke.composeai:ui-builder-protocol",
      "ee.schimke.composeai:ui-builder-protocol-jvm",
    )
  )
}

// No test-fixtures capability wiring: nothing publishes now, and `FakeRenderSession`'s consumer
// takes it from compose-ai-tools' `:render-host` as a project dependency.

tasks.named("check") { dependsOn("checkServeModuleBoundary") }

/**
 * Checks the JVM class-file floor of everything on the resolved `runtimeClasspath`.
 * compose-ai-tools pins its JVM modules to 17, so a single class file 65 here (e.g. from a
 * third-party jar such as `dev.snipme:highlights`) fails a build in another repository. An artifact
 * allowlist can't see disallowed bytes in an allowed coordinate.
 */
abstract class CheckJvmClassFileFloor : DefaultTask() {
  // `@Classpath`, not `@InputFiles`: what this reads is the bytes of each class, so a jar
  // rebuilt with the same content — a timestamp, a reordered manifest — must not re-run it.
  @get:Classpath abstract val classpath: ConfigurableFileCollection

  /** The Java feature version every class on [classpath] must be loadable by. */
  @get:Input abstract val floor: Property<Int>

  @TaskAction
  fun check() {
    val floor = floor.get()
    // JVMS 4.1: Java 1.0.2 is 45, and the major version has gone up by one per release since.
    val maxMajor = floor + 44
    val offenders = mutableListOf<String>()
    classpath.files
      .filter { it.exists() }
      .sorted()
      .forEach { entry ->
        if (entry.isDirectory) {
          entry
            .walkTopDown()
            .filter { it.isFile && it.name.endsWith(".class") }
            .forEach { classFile ->
              classFile.inputStream().use { stream ->
                majorVersion(stream)?.let { major ->
                  if (major > maxMajor) offenders += "${entry.name}!${classFile.name} is $major"
                }
              }
            }
        } else if (entry.name.endsWith(".jar")) {
          ZipFile(entry).use { jar ->
            jar
              .entries()
              .asSequence()
              .filter { it.name.endsWith(".class") && !it.isDirectory }
              // A multi-release jar's versioned tree and `module-info` aren't read by an older
              // classpath JVM.
              .filterNot {
                it.name.startsWith("META-INF/versions/") || it.name.endsWith("module-info.class")
              }
              .forEach { zipEntry ->
                jar.getInputStream(zipEntry).use { stream ->
                  majorVersion(stream)?.let { major ->
                    if (major > maxMajor) offenders += "${entry.name}!${zipEntry.name} is $major"
                  }
                }
              }
          }
        }
      }
    check(offenders.isEmpty()) {
      "The server distribution must load on Java $floor (class file $maxMajor or lower), because " +
        "compose-ai-tools' `:cli` compiles against it on a $floor toolchain. Found " +
        "${offenders.size} class file(s) above that floor: " +
        offenders.sorted().take(10).joinToString(", ") +
        if (offenders.size > 10) ", …" else ""
    }
  }

  /** The `major_version` at offset 6 of a `.class`, or null when the entry is not one. */
  private fun majorVersion(stream: InputStream): Int? {
    val header = ByteArray(8)
    var read = 0
    while (read < header.size) {
      val next = stream.read(header, read, header.size - read)
      if (next < 0) return null
      read += next
    }
    val magic =
      ((header[0].toInt() and 0xff) shl 24) or
        ((header[1].toInt() and 0xff) shl 16) or
        ((header[2].toInt() and 0xff) shl 8) or
        (header[3].toInt() and 0xff)
    if (magic != -0x35014542) return null
    return ((header[6].toInt() and 0xff) shl 8) or (header[7].toInt() and 0xff)
  }
}

val checkServerJvmFloor =
  tasks.register<CheckJvmClassFileFloor>("checkServerJvmFloor") {
    description = "Fails when anything on the server's runtime classpath needs a newer Java."
    group = "verification"
    floor.set(libs.versions.java.server.get().toInt())
    classpath.from(configurations.named("runtimeClasspath"))
  }

tasks.named("check") { dependsOn(checkServerJvmFloor) }

// The version the server reports, as its own resource. Same derivation as `:cli`'s
// `generateCliVersionResource` (`project.version`), but a separate fact so the two may diverge.
val generateServeVersionResource =
  tasks.register("generateServeVersionResource") {
    val outputDir = layout.buildDirectory.dir("generated/serve-version-resource")
    val serveVersion = project.version.toString()
    inputs.property("version", serveVersion)
    outputs.dir(outputDir)
    doLast {
      val file =
        outputDir.get().file("ee/schimke/composeai/cli/serve/serve-version.properties").asFile
      file.parentFile.mkdirs()
      file.writeText("version=$serveVersion\n")
    }
  }

sourceSets.main.get().resources.srcDir(generateServeVersionResource)

// The typefaces the viewer registers for its client-side Remote Compose lanes (`ServeRcFonts`), so
// the browser lane matches the baked PNG. Staged from the vendored directory the offline parity
// harness and snapshot renderer use, so they can't diverge. Only the four generic-family faces
// (`ServeRcFonts.FACES`, checked by `ServeRcFontsTest`); named families are fetched by the player.
val stageRcFontResources =
  tasks.register<Sync>("stageRcFontResources") {
    description =
      "Stage the vendored generic-family faces the serve viewer registers for its RC lanes."
    from(rootDir.resolve("assets/rc-fonts")) {
      include(
        "Roboto-Regular.ttf",
        "Roboto-Medium.ttf",
        "NotoSerif-Regular.ttf",
        "DroidSansMono.ttf",
        // The faces' own licence, so the jar carries it beside the bytes.
        "LICENSE.txt",
      )
      into("rc-fonts")
    }
    into(layout.buildDirectory.dir("generated/rc-font-resources"))
  }

sourceSets.main.get().resources.srcDir(stageRcFontResources)

// Staged rather than committed twice: the fixture a test reads is the one the jar carries.
// `ComponentRecordSource` unions it onto every catalog's record, so it is packaged rather than
// passed by flag. `ComponentRecordSourceFoundationTest` loads it by the same resource path.
val stageFoundationRecord =
  tasks.register<Sync>("stageFoundationRecord") {
    description =
      "Stage the builder's own layout/, shape/ and asset/ component record into the jar."
    // Composite builds must use the record owned by the same builder checkout as the code.
    // Otherwise its new slot scopes are checked against this repository's older release fixture.
    val builderRoot = providers.gradleProperty("composeUiBuilderDir").orNull?.let(rootProject::file)
    from((builderRoot ?: rootProject.projectDir).resolve("docs/design/fixtures/ui-builder")) {
      include("compose-foundation-components-v1.json")
      into("ui-builder")
    }
    into(layout.buildDirectory.dir("generated/foundation-record-resources"))
  }

sourceSets.main.get().resources.srcDir(stageFoundationRecord)
