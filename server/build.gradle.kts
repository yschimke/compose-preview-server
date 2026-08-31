import java.io.File
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedArtifactResult
import org.gradle.api.tasks.ClasspathNormalizer
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.bundling.Compression
import org.gradle.api.tasks.bundling.Tar
import org.gradle.process.CommandLineArgumentProvider

// `compose-preview serve` — the preview server, as its own module.
//
// #3824 preparation item 7. `serve` was a package inside `:cli`, and a package has no boundary: the
// only thing keeping the server from reaching into the CLI was `scripts/check-serve-seam.py`, a
// source scanner. This module makes it a build fact. Nothing here can see `:cli`, because `:cli`
// depends on this and Gradle has no cycles — and `checkServeModuleBoundary` below says so in a way
// that survives someone adding the dependency back.
//
// Package note: the sources keep `ee.schimke.composeai.cli.serve`. Renaming the package is a
// separate change from moving the module — the lesson `:bundle-format` taught twice (see
// docs/design/PREVIEW_SERVER_SPLIT.md) is that the two are independent, and doing them together
// makes a 300-file move unreviewable.
//
// The allowed direction is `:cli` -> here. The server implementation, argv semantics, defaults,
// and usage text live in this module; `:cli` keeps only the thin adapter that supplies Gradle build
// operations and the tool-wide preview matcher.
plugins {
  application
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.ktfmt)
  alias(libs.plugins.maven.publish)
  // `FakeRenderSession` is scaffolding this module's own tests share with `:cli`'s
  // `BundleRenderKnobTest`, which drives `bundle render --knob` against a fake session rather than
  // a daemon subprocess. A test fixture rather than a `main` source: it must not reach the server's
  // runtime classpath.
  `java-test-fixtures`
}

group = "ee.schimke.composeai"

// The artifactId this module publishes under. Named once, because two places need it and they
// silently disagreed until 2.0.0 shipped: `mavenPublishing.coordinates` below, and the capability
// on the test-fixtures variants (see `testFixturesCapabilities` further down). The Gradle project
// is `:server`, so anything Gradle derives from the project name gets `server`, not this.
val publishedArtifactId = "compose-preview-serve"

kotlin { jvmToolchain(17) }

ktfmt { googleStyle() }

// Same derivation as `:cli` (PLUGIN_VERSION in CI, a patch-bumped SNAPSHOT off
// `.release-please-manifest.json` locally). Without it Gradle leaves `project.version` as
// `unspecified`, `generateServeVersionResource` below writes `version=unspecified`, and the server
// reports that string through `/version`, the session handshake, the page footers and every bug
// report — silently, because nothing type-checks a version string.
//
// A separate assignment from `:cli`'s rather than a shared one, deliberately: the two agree today
// because they ship together, and the whole point of #3824 is that one day they will not.
version =
  providers.environmentVariable("PLUGIN_VERSION").orNull
    ?: run {
      val manifest = rootDir.resolve(".release-please-manifest.json").readText()
      val current = Regex(""""\.":\s*"([^"]+)"""").find(manifest)!!.groupValues[1]
      val (major, minor, patch) = current.split(".").map { it.toInt() }
      "$major.$minor.${patch + 1}-SNAPSHOT"
    }

// The jar lands in the CLI distribution's `lib/` beside a hundred third-party jars, where a bare
// `serve.jar` (the default from the project name) says nothing and could collide. Same naming as
// `:cli`'s `compose-preview` and `:mcp`'s `compose-preview-mcp`.
base { archivesName.set("compose-preview-serve") }

application {
  applicationName = "compose-preview-server"
  mainClass.set("ee.schimke.composeai.cli.serve.StandaloneServerMainKt")
}

evaluationDependsOn(":wasm-ui")

evaluationDependsOn(":ui-builder")

// Subprocess-only Compose renderer/daemon runtimes. These intentionally do not extend any server
// classpath: the generated launcher discovers them below APP_HOME, and the render host passes them
// only to the isolated daemon JVM. This gives the standalone server distribution the same honest
// PNG/SVG capability as the compose-ai-tools CLI distribution without making :server load a
// renderer implementation.
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

distributions {
  main {
    contents {
      from(project(":wasm-ui").tasks.named("wasmFrontendDist")) { into("wasm-ui") }
      from(project(":ui-builder").tasks.named("wasmFrontendDist")) { into("ui-builder") }
      into("lib-renderer") { from(stageRendererLibs) }
      into("lib-daemon-desktop") { from(stageDaemonDesktopLibs) }
    }
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

// Published, because after #3824's repo split `:cli` cannot reach the server any other way.
//
// The seam register is down to 11 `:cli` -> serve crossings (`ServeCommand`'s four seam types plus
// `bundle` and the history commands), and every one of them is a compile-time dependency.
// Once the two live in separate repositories the only way to satisfy them is a published artifact,
// so an unpublished `:cli:serve` is the remaining hard blocker on the split regardless of how low
// that number goes. Every project dependency this module has is already published, so nothing here
// makes a POM that points at something nobody can resolve.
//
// Deliberately WITHOUT `explicitApi()`, unlike the contract modules (`:common-io`,
// `:bundle-format`, `:common-image-crop`). Turning it on here reports 1,719 declarations needing an
// explicit modifier — this is the server, not a contract, and marking all 1,719 `public` would
// freeze an ABI nobody designed, which is the exact failure `explicitApi()` exists to prevent. The
// surface worth designing is the 16 symbols `:cli` actually uses; narrowing to that, and only then
// turning the gate on, is its own change.

dependencies {
  // The render host, the bundle daemon and the git-backed preview history, split out so the CLI's
  // OFFLINE `bundle render` / `history manifest` can reach them without a web server on the
  // classpath (yschimke/compose-ai-tools#4832). `api`, because those types are all over this
  // module's own signatures — `ServeHost` is the interface every catalog/live host implements, and
  // `RenderOutcome` is the return type of the render lane the HTTP routes call.
  //
  // The sources kept their `ee.schimke.composeai.cli.serve` package, so nothing in this module
  // changed at the call sites; only the module that compiles them did.
  api(project(":render-host"))

  api(libs.composeai.common.web.escaping)
  // Published wire-format DTOs and the bundle format. `api` because they appear in this module's
  // own signatures, which `:cli` reads.
  api(libs.composeai.preview.data.api)
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

  val composeAiToolsVersion = libs.versions.composeai.tools.get()
  add(
    "composePreviewRenderer",
    "ee.schimke.composeai:renderer-desktop:$composeAiToolsVersion",
  )
  add(
    "composePreviewDaemonDesktop",
    "ee.schimke.composeai:daemon-desktop:$composeAiToolsVersion",
  )

  // BTA *interfaces only* — the playground compiler references `BtaCompileSession`'s
  // build-tools-api parameter types (`CompilerPlugin`, `KotlinLogger`, `SourcesChanges`) to drive
  // an in-process compile. `:daemon:core` declares this as `implementation`, so it is not
  // transitive; the impl JARs ride in the CLI distribution's `lib-bta/`, not here.
  implementation("org.jetbrains.kotlin:kotlin-build-tools-api:${libs.versions.kotlin.get()}")

  testImplementation(kotlin("test"))

  // The fixture source set compiles against the module's own API and the render-session contract
  // it fakes.
  // `FakeRenderSession` moved to `:render-host` with `ServeRenderHost`, which is the thing it
  // fakes. This module's live-host, session-registry and stream tests still drive it.
  testImplementation(testFixtures(project(":render-host")))

  // Re-exported so this module's PUBLISHED test-fixtures variant keeps carrying `FakeRenderSession`
  // for consumers that already ask for it by the `compose-preview-serve` spelling —
  // compose-ai-tools'
  // `:cli` `BundleRenderKnobTest` does, and that dependency resolves against the released artifact,
  // not against this build. Without the re-export, moving the fixture out would publish an EMPTY
  // fixtures jar under an artifactId that still advertises the capability: the consumer resolves,
  // compiles nothing, and finds out at the call site. `api`, not `implementation`, because the
  // consumer compiles against the type.
  //
  // This source set now holds no sources of its own. It stays declared for exactly this
  // compatibility hop, and can go once `:cli` asks `:render-host` for the fixture directly.
  testFixturesApi(testFixtures(project(":render-host")))

  // In-memory FileSystem for the playground and store tests, which assert on-disk output without
  // touching the real FS. Okio itself is on the compile classpath via `:common-io`; the fake ships
  // separately.
  testImplementation(libs.okio.fakefilesystem)

  // The *parse-only* PSI spike (`PsiParseSpikeTest`) measures whether a Kotlin frontend parse can
  // replace the playground cleaner's text passes. Deliberately `testImplementation` and nothing
  // else — the server's own runtime classpath must stay free of the compiler frontend, the same
  // rule `:cli` applies. If the spike says yes, the real change loads these jars through the
  // isolated `lib-bta/` classloader, not from here.
  testImplementation(
    "org.jetbrains.kotlin:kotlin-compiler-embeddable:${libs.versions.kotlin.get()}"
  )
}

// Sidecar jars the server loads through an ISOLATED classloader, never from its own classpath:
// the BTA implementation + Compose compiler plugin (`lib-bta/`) and the Kotlin parser behind the
// playground's source cleaner (`lib-usage-psi/`). `:cli` declares the same two configurations for
// the distribution it stages; these exist so the serve tests that exercise those reflective load
// paths get the real jars handed to them, exactly as they did while they lived in `:cli`.
//
// Without them the tests do not fail — they take the fallback branch and pass, which is worse: the
// parser-backed rewrite and the in-process compile, the whole point of both code paths, would have
// no coverage at all while CI stayed green.
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
  // JUnit 5, as `:cli` runs these same tests today — `kotlin("test")` resolves its junit5 variant
  // off the back of this, which is where the `org.junit.jupiter` API (`@TempDir`, `@Test`) comes
  // from. Without it the platform defaults to JUnit 4 and roughly a dozen serve test classes stop
  // compiling.
  useJUnitPlatform()

  // Catalog checkouts for the usage-snippet corpus (`UsageSnippetCorpusTest`, which moved here with
  // the serve sources). Absent by default, so the corpus is a no-op in a normal build;
  // `scripts/usage-corpus.sh` supplies them. Forwarded rather than read from the environment so the
  // paths show up in the build's own inputs. `repos` is ONE property carrying every checkout as
  // `name=path,name=path`, rather than a key per catalog: a fixed key list silently ignores any
  // checkout not named in it, so adding a third catalog would produce an empty corpus and a
  // passing run.
  for (key in
    listOf(
      "composeai.usageCorpus.repos",
      "composeai.usageCorpus.out",
      "composeai.usageCorpus.samples",
    )) {
    providers.systemProperty(key).orNull?.let { systemProperty(key, it) }
  }

  // Through a `CommandLineArgumentProvider` (resolved at execution time, declared as an input) so
  // the configuration cache stays valid rather than resolving a configuration at configuration
  // time. Moved here with the tests; see the configurations above for why an absent jar is a
  // silent pass rather than a failure.
  val btaJars = composePreviewBta.incoming.files
  inputs.files(btaJars).withPropertyName("libBtaJars").withNormalizer(ClasspathNormalizer::class)
  val usagePsiJars = composePreviewUsagePsi.incoming.files
  inputs
    .files(usagePsiJars)
    .withPropertyName("libUsagePsiJars")
    .withNormalizer(ClasspathNormalizer::class)

  // The shared wire fixtures under `scripts/design-artifacts/fixtures/`, which
  // `ServeIssueReportTest` and `ServeParityIssuesStoreTest` read straight off disk rather than
  // through the test classpath. Undeclared, Gradle cannot know that editing one changes what those
  // tests assert, so a fixture edit could be served UP-TO-DATE or from the build cache without the
  // assertions ever running.
  inputs
    .files(
      rootProject.layout.projectDirectory
        .dir("scripts/design-artifacts/fixtures")
        .asFileTree
        .matching { include("*.json") }
    )
    .withPropertyName("sharedWireFixtures")
    .withPathSensitivity(PathSensitivity.RELATIVE)

  // The image Dockerfile, which `ImageSandboxCountMirrorTest` reads off disk for the same reason
  // and with the same hazard: undeclared, editing `JAVA_TOOL_OPTIONS` could be served UP-TO-DATE or
  // from the build cache with the assertion never re-running — which is precisely the silent drift
  // that test exists to catch.
  inputs
    .files(rootProject.layout.projectDirectory.file("deploy/image/Dockerfile"))
    .withPropertyName("imageDockerfile")
    .withPathSensitivity(PathSensitivity.RELATIVE)

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

// The boundary #3824 preparation item 7 asks for, now that there is a classpath to check.
//
// `scripts/check-serve-seam.py` proved this by scanning source, because until this module existed
// there was no classpath to look at — `serve` was a package, and packages have no boundary. That
// scanner stays (it still measures the `:cli` -> here direction, which the build permits and the
// split needs to shrink). What it could never do is prove the *reverse*: that nothing here reaches
// into `:cli`. A source scanner can be defeated by reflection, by a string literal, or by a rule
// its tokenizer does not model. A resolved classpath cannot.
//
// The check is deliberately not "does `:cli` appear in my dependency block" — that is a fact about
// this file, which is exactly the thing a mistake would edit. It walks the RESOLVED runtime
// classpath, transitives included, so a `:cli` dependency arriving through some third module fails
// here too.
//
// Also forbidden: the renderer and plugin implementations, mirroring `:cli`'s own
// `checkCliDaemonLibraryBoundary` and the `forbiddenPackages` list in the seam allowlist. An
// extracted preview server is a protocol client; it never loads a renderer in its own JVM.
abstract class CheckServeModuleBoundary : DefaultTask() {
  /**
   * Every component on the resolved runtime classpath, as a stable identity string: `project :cli`
   * for a project in this build, `module <group>:<name>` for anything resolved from a repository.
   *
   * Identity rather than file location, and that distinction is the whole point. An earlier version
   * of this task compared each classpath *file* against the forbidden projects' `projectDir`s,
   * which silently passed the case it most needed to catch: `renderers/desktop` publishes as
   * `ee.schimke.composeai:renderer-desktop`, so once it arrives as a published or transitively
   * substituted Maven dependency its jar sits in Gradle's cache, under no project directory at all.
   * The prefix compare found nothing and the boundary reported clean.
   */
  @get:Input abstract val resolvedComponents: SetProperty<String>

  @get:Input abstract val forbiddenProjects: SetProperty<String>

  @get:Input abstract val forbiddenModules: SetProperty<String>

  @get:Input abstract val allowedComposeAiModules: SetProperty<String>

  /**
   * Projects in this build that this module is allowed to depend on.
   *
   * Until #4832 the answer was "none", and the check said so by treating EVERY project dependency
   * as a hit — which was right while `:server` was the only Kotlin module here. `:render-host` is a
   * deliberate exception: the server sits ON TOP of the render host, not beside it, and the
   * direction is enforced by Gradle's own acyclicity. An allowlist rather than dropping the project
   * rule, because the rule's real target — `:cli` or a renderer arriving as a project — is still
   * exactly what must not happen.
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

  // The render host, split out of this module so the CLI's offline commands can reach it without a
  // web server (#4832). The only project dependency this module may have; everything else in this
  // build reaching its classpath is still a failure.
  allowedProjects.set(listOf(":render-host"))

  // The same implementations named twice, because they can arrive by two different routes and the
  // identity differs between them.
  forbiddenProjects.set(
    listOf(":cli", ":daemon:android", ":daemon:desktop", ":renderer-android", ":renderer-desktop")
  )
  // Their published coordinates, for the transitive case a project-path check cannot see. The
  // Gradle plugin has only a published identity in this standalone build, so a project-path check
  // could never see it.
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

  // The full resolved Compose Preview floor, transitives included. This is a positive allowlist,
  // so adding a new internal artifact fails even when it is not one of the known renderer/CLI
  // implementations above.
  allowedComposeAiModules.set(
    listOf(
      "ee.schimke.composeai:agent-grant-protocol",
      "ee.schimke.composeai:bundle-coordinates",
      "ee.schimke.composeai:bundle-format",
      "ee.schimke.composeai:common-image-crop",
      "ee.schimke.composeai:common-io",
      "ee.schimke.composeai:common-web-escaping",
      "ee.schimke.composeai:daemon-bta",
      "ee.schimke.composeai:daemon-client",
      "ee.schimke.composeai:daemon-core",
      "ee.schimke.composeai:daemon-devices",
      "ee.schimke.composeai:daemon-protocol",
      "ee.schimke.composeai:data-layoutinspector-core",
      "ee.schimke.composeai:data-preview-overrides-core",
      "ee.schimke.composeai:data-pseudolocale-core",
      "ee.schimke.composeai:data-remotecompose-core",
      "ee.schimke.composeai:data-render-core",
      "ee.schimke.composeai:data-theme-core",
      "ee.schimke.composeai:preview-data-api",
      "ee.schimke.composeai:render-session-api",
      "ee.schimke.composeai:render-session-subprocess",
      "ee.schimke.composeai:ui-builder-protocol",
      "ee.schimke.composeai:ui-builder-protocol-jvm",
    )
  )
}

// Publish the test fixtures under a capability consumers can actually ask for.
//
// `java-test-fixtures` derives the fixtures capability from the GRADLE PROJECT name, and this
// project is `:server` while it publishes as `compose-preview-serve`. So 2.0.0 went to Maven
// Central
// advertising `ee.schimke.composeai:server-test-fixtures`, while `testFixtures(...)` on the
// consumer
// side looks for `<group>:<artifactId>-test-fixtures`. Nothing matched, and a consumer writing the
// one documented spelling got:
//
//     Unable to find a variant of ee.schimke.composeai:compose-preview-serve:2.0.0 with the
//     requested capability: feature 'test-fixtures'
//
// which is how compose-ai-tools' `:cli` found it (yschimke/compose-ai-tools#4839) —
// `FakeRenderSession`
// lives in these fixtures, and the CLI's `BundleRenderKnobTest` needs it. It had to request
// `server-test-fixtures` by name to consume 2.0.0 at all.
//
// BOTH names are declared, deliberately. Declaring any capability explicitly replaces the implicit
// one, so listing only the conventional name would break every consumer already pinned to the
// spelling 2.0.0 shipped — including that `:cli` workaround, which must keep resolving until it is
// removed on their side. The conventional name is what new consumers get from plain
// `testFixtures(...)`; the legacy one is kept for compatibility and can go once no consumer asks
// for it.
val testFixturesCapabilities =
  listOf(
    "$group:$publishedArtifactId-test-fixtures",
    // Legacy: the project-name-derived spelling 2.0.0 published. Keep until consumers migrate.
    "$group:${project.name}-test-fixtures",
  )

// `sourcesElements` too, not just the two that carry code: a sources variant left on the old
// capability alone is one an IDE cannot attach when the consumer resolved the new one.
//
// `configureEach` rather than `named`: `testFixturesSourcesElements` does not exist when this file
// is evaluated — the publishing plugin adds it later, when it wires the sources jar — so naming it
// directly fails with "Configuration with name 'testFixturesSourcesElements' not found". This runs
// for each of the three whenever it is created, whichever order that happens in.
val testFixturesVariants =
  setOf("testFixturesApiElements", "testFixturesRuntimeElements", "testFixturesSourcesElements")

configurations.configureEach {
  if (name in testFixturesVariants) {
    outgoing { testFixturesCapabilities.forEach { capability("$it:${project.version}") } }
  }
}

// A comment cannot fail a build, and the whole point is that this drifted unnoticed through a
// release. What the check asserts is deliberately NOT "the declared list equals the list above" —
// that compares the wiring to itself and passes however wrong both are. It asserts the property a
// consumer depends on: the capability Gradle synthesises from the PUBLICATION coordinates
// (`<group>:<artifactId>-test-fixtures`, what `testFixtures(...)` resolves) is among the ones this
// module advertises. Derived from `publishedArtifactId`, so renaming the artifact keeps the check
// honest rather than moving both sides together.
//
// Captured at configuration time so the task stays configuration-cache safe, and read from the
// configuration's own `outgoing.capabilities` rather than generated metadata so it needs no jar.
val requiredTestFixturesCapability = "$group:$publishedArtifactId-test-fixtures"

val declaredTestFixturesCapabilities = provider {
  configurations.getByName("testFixturesApiElements").outgoing.capabilities.map {
    "${it.group}:${it.name}"
  }
}

tasks.register("checkTestFixturesCapabilities") {
  description = "Fails if the published test-fixtures capability stops matching the artifactId."
  group = "verification"
  val declared = declaredTestFixturesCapabilities
  val required = requiredTestFixturesCapability
  outputs.upToDateWhen { false }
  doLast {
    val actual = declared.get()
    check(required in actual) {
      "the test-fixtures variants advertise $actual, which does not include `$required` — the " +
        "capability a consumer's `testFixtures(...)` resolves. Publishing without it makes the " +
        "fixtures unreachable by their documented spelling, which is what 2.0.0 shipped."
    }
  }
}

tasks.named("check") { dependsOn("checkTestFixturesCapabilities") }

tasks.named("check") { dependsOn("checkServeModuleBoundary") }

// The version the server reports, as its own resource.
//
// `ServeVersion.kt` used to read `:cli`'s `cli-version.properties`, which was fine while `serve`
// shipped inside the CLI jar and stopped being fine the moment it did not: the resource is
// generated into `:cli`'s source set, so every `/version` read threw
// "cli-version.properties missing from compose-preview jar" and the routing tests came back 500.
// That was the change `ServeVersion.kt`'s comment said this day would need.
//
// Same shape as `:cli`'s `generateCliVersionResource`, and the same value — both derive from
// `project.version`, which honours the `PLUGIN_VERSION` env override CI sets and the
// `.release-please-manifest.json` fallback for local builds. They are separate facts that happen
// to agree today; when the server ships from its own repo they stop agreeing, and nothing here has
// to change for that.
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

// The typefaces the served viewer registers for its client-side Remote Compose lanes
// (`ServeRcFonts`): without them the browser lane paints a document's generic families in whatever
// the *viewer's* machine calls `sans-serif`, at different metrics and without the Medium weight,
// while the baked PNG beside it used these files (issue #3480).
//
// STAGED, not committed a second time. The source is the one vendored directory the offline parity
// harness reads (`scripts/design-artifacts/rc-fonts.mjs`'s `DEFAULT_FONTS_DIR`) and the snapshot
// renderer rasterizes with, so "the viewer's faces" and "the faces parity is measured against"
// cannot become different files. The named-family faces in that directory (Orbitron, Lobster Two)
// are deliberately left out — the player fetches those itself through `WebFonts.ts`; only the four
// behind the generic families need registering, and they are what `ServeRcFonts.FACES` declares
// (`ServeRcFontsTest` fails when this list and that table disagree).
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

mavenPublishing {
  publishToMavenCentral(automaticRelease = true)
  if (!project.version.toString().endsWith("SNAPSHOT")) signAllPublications()
  coordinates(group.toString(), publishedArtifactId, project.version.toString())
  pom {
    name.set("Compose Preview — Preview Server")
    description.set(
      "The compose-preview server: catalog hosting, live render sessions, the playground and viewer web surfaces."
    )
    url.set("https://github.com/yschimke/compose-preview-server")
    inceptionYear.set("2026")
    licenses {
      license {
        name.set("The Apache License, Version 2.0")
        url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
        distribution.set("repo")
      }
    }
    developers {
      developer {
        id.set("yschimke")
        name.set("Yuri Schimke")
        url.set("https://github.com/yschimke")
      }
    }
    scm {
      url.set("https://github.com/yschimke/compose-preview-server")
      connection.set("scm:git:https://github.com/yschimke/compose-preview-server.git")
      developerConnection.set("scm:git:ssh://git@github.com/yschimke/compose-preview-server.git")
    }
  }
}
