// The render host, the bundle daemon and the git-backed preview history — the part of the serve
// sources that renders and reads history, with no web server underneath it.
//
// Why this is a module rather than a package in `:server` (yschimke/compose-ai-tools#4832).
// `compose-preview bundle render` and `compose-preview history manifest` are OFFLINE commands: they
// materialise a packed bundle, drive a daemon-backed render session, and read a manifest out of
// git. Neither serves HTTP. But the types they need — `ServeRenderHost`, `ServeBundleDaemon`,
// `RenderOutcome`, `SvgOutcome`, `PreviewHistory`, `PreviewHistoryManifest` — were written inside
// the serve package, so `:cli` over in compose-ai-tools had to depend on the whole published
// `compose-preview-serve` to reach them. That dragged five `ktor-server-*` artifacts, the Ktor
// client, OkHttp, jmdns and the Kotlin Build Tools API onto the classpath of a command that never
// opens a socket.
//
// The split point is measured, not guessed. Of the 143 files in the serve package, exactly TWO
// import `io.ktor` — `ServeHttpServer.kt` and `ServeGithubAuth.kt`. Everything the offline commands
// transitively reach was Ktor-free already; it was only the module boundary that said otherwise. So
// the cut is "what the render/history entry points reach", and this module is that closure: 44
// files.
//
// What that actually removes from a consumer's classpath, measured against `:server`'s resolved
// `runtimeClasspath` rather than claimed:
//
//     io.ktor:ktor-server-core / -cio / -websockets / -compression / -auto-head-response
//     org.jmdns:jmdns                     (the `serve --lan` advertiser)
//     org.jetbrains.kotlin:kotlin-reflect
//     com.typesafe:config
//     ee.schimke.composeai:agent-grant-protocol
//     ee.schimke.composeai:data-pseudolocale-core
//
// What it does NOT remove, deliberately, because both arrive transitively from artifacts the render
// path genuinely uses and neither is this repository's to drop:
//
//   - the Ktor CLIENT and OkHttp, via `ee.schimke.composeai:bundle-coordinates` —
// `ServeBundleDaemon`
//     resolves and fetches coordinates through `CoordinateResolver`, which is an HTTP client by
//     nature. A client is not a server; `bundle render` opens no listening socket either way.
//   - `kotlin-build-tools-api` (the interface, not the compiler) via `daemon-bta` <- `daemon-core`,
//     and `io.github.classgraph:classgraph` via `daemon-core` directly.
//
// `:cli` already depends on `bundle-coordinates` and `daemon-core` directly, so neither is new
// weight for it. `checkRenderHostIsServerFree` below enforces exactly the list this module can
// actually keep out, and no more — a check that asserted "no Ktor at all" would have to be
// suppressed on its first run, which is the same as not having it.
//
// Package note: the sources keep `ee.schimke.composeai.cli.serve`, per AGENTS.md — the package
// rename is a separately reviewed change. Keeping it here is also what makes the move
// SOURCE-COMPATIBLE for consumers: `:cli`'s call sites reference these types in-package and do not
// change at all, they just resolve from a narrower artifact.
//
// The allowed direction is `:server` -> here, and Gradle has no cycles, so nothing in this module
// can reach the HTTP layer, the runner, the catalog store or the web UI.
// `checkRenderHostIsServerFree`
// below makes that a build fact rather than a convention.
plugins {
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.ktfmt)
  alias(libs.plugins.maven.publish)
  // `FakeRenderSession` — the fake `RenderSession` that lets a test drive `ServeRenderHost` without
  // a daemon subprocess. It came here with `ServeRenderHost` itself, and it is shared three ways:
  // this module's own render-host tests, `:server`'s live-host and session-registry tests, and
  // compose-ai-tools' `:cli` `BundleRenderKnobTest`. A test fixture rather than a `main` source: it
  // must not reach any runtime classpath.
  `java-test-fixtures`
}

group = "ee.schimke.composeai"

// Named once for the same reason `:server` names its own: `mavenPublishing.coordinates` below needs
// it, and anything Gradle derives from the project name would get `render-host` instead.
val publishedArtifactId = "compose-preview-render-host"

kotlin { jvmToolchain(17) }

ktfmt { googleStyle() }

// Same derivation as `:server` — PLUGIN_VERSION in CI, a patch-bumped SNAPSHOT off
// `.release-please-manifest.json` locally. This module ships in lockstep with the server today
// (release-please bumps the whole repository), so a shared root version would also be correct; the
// duplication follows `:server`'s existing comment about the two one day diverging.
version =
  providers.environmentVariable("PLUGIN_VERSION").orNull
    ?: run {
      val manifest = rootDir.resolve(".release-please-manifest.json").readText()
      val current = Regex(""""\.":\s*"([^"]+)"""").find(manifest)!!.groupValues[1]
      val (major, minor, patch) = current.split(".").map { it.toInt() }
      "$major.$minor.${patch + 1}-SNAPSHOT"
    }

// The jar lands in the CLI distribution's `lib/` beside a hundred third-party jars, where a bare
// `render-host.jar` (the default from the project name) says nothing. Same naming rule as
// `:server`'s `compose-preview-serve`.
base { archivesName.set(publishedArtifactId) }

dependencies {
  // `api` for everything that appears in this module's own public signatures, because `:server` and
  // `:cli` both write against those types directly: `ServeRenderHost.render` returns products from
  // `:data-*`, `ServeBundleDaemon.materialize` takes a `DaemonLaunchDescriptor`, and `ServeHost`
  // exposes `PreviewOverrides` and `StreamFrameParams` on its interface.
  api(libs.composeai.preview.data.api)
  api(libs.composeai.bundle.format)
  api(libs.composeai.bundle.coordinates)
  api(libs.composeai.daemon.core)
  api(libs.composeai.render.session.api)
  api(libs.composeai.render.session.subprocess)
  api(libs.composeai.data.layoutinspector.core)
  api(libs.composeai.data.theme.core)
  api(libs.composeai.ui.builder.protocol)
  // Both reached by FULLY-QUALIFIED name rather than an import, so they are easy to miss when
  // reading the sources for what this module needs: `ServePreview.overrides` and
  // `ServePreview.remoteComposeKnobs` are declared as
  // `List<ee.schimke.composeai.data.overrides.PreviewOverrideDeclaration>` and
  // `List<ee.schimke.composeai.data.remotecompose.RemoteComposeKnobDeclaration>`. Public
  // signatures, hence `api`.
  api(libs.composeai.data.preview.overrides.core)
  api(libs.composeai.data.remotecompose.core)

  implementation(libs.composeai.common.io)
  implementation(libs.composeai.common.image.crop)
  implementation(libs.kotlinx.serialization.json)

  testImplementation(kotlin("test"))
  // In-memory FileSystem for the store tests, which assert on-disk output without touching the real
  // FS. Okio itself is on the compile classpath via `:common-io`; the fake ships separately.
  testImplementation(libs.okio.fakefilesystem)

  testFixturesImplementation(kotlin("test"))
  // `FakeRenderSession` implements `RenderSession`, so the interface is part of the fixture's own
  // signature, not an implementation detail of it.
  testFixturesApi(libs.composeai.render.session.api)
}

// Publish the test fixtures under the capability a consumer's `testFixtures(...)` actually asks
// for.
//
// The same trap `:server` fell into and documents at length: `java-test-fixtures` derives the
// capability from the GRADLE PROJECT name (`render-host`), while `testFixtures(...)` on the
// consumer
// side looks for `<group>:<artifactId>-test-fixtures` — and this project publishes as
// `compose-preview-render-host`. Declared correctly from the first release, so unlike `:server`
// there is no legacy spelling to keep alive.
val testFixturesCapabilities = listOf("$group:$publishedArtifactId-test-fixtures")

// `sourcesElements` too, not just the two that carry code: a sources variant left on the wrong
// capability is one an IDE cannot attach when the consumer resolved the right one.
//
// `configureEach` rather than `named`: `testFixturesSourcesElements` does not exist when this file
// is evaluated — the publishing plugin adds it later — so naming it directly fails.
val testFixturesVariants =
  setOf("testFixturesApiElements", "testFixturesRuntimeElements", "testFixturesSourcesElements")

configurations.configureEach {
  if (name in testFixturesVariants) {
    outgoing { testFixturesCapabilities.forEach { capability("$it:${project.version}") } }
  }
}

// Asserts the property a consumer depends on rather than comparing the wiring to itself: the
// capability Gradle synthesises from the PUBLICATION coordinates is among the ones advertised.
// Derived from `publishedArtifactId`, so renaming the artifact keeps the check honest.
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
        "fixtures unreachable by their documented spelling, which is what `:server` 2.0.0 shipped."
    }
  }
}

tasks.named("check") { dependsOn("checkTestFixturesCapabilities") }

tasks.withType<Test>().configureEach {
  // JUnit 5, matching `:server` — `kotlin("test")` resolves its junit5 variant off the back of
  // this, which is where `@TempDir` and the Jupiter `@Test` come from. Without it the platform
  // defaults to JUnit 4 and the moved test classes stop compiling.
  useJUnitPlatform()
}

// The whole point of the module, asserted against the RESOLVED runtime classpath rather than the
// `dependencies {}` block above — a transitive Ktor would not show up in the block, and reading the
// block back would only re-state what someone just wrote.
//
// A negative check rather than `:server`'s positive allowlist, because the invariant here is
// narrower and stateable: no web server, no service discovery, no Kotlin compiler. A new Compose
// Preview artifact arriving transitively is fine; `io.ktor:ktor-server-cio` is not.
//
// Scoped to what this module can actually hold out — see the header for what it cannot (the Ktor
// client, `kotlin-build-tools-api` and `classgraph`, all transitives of artifacts the render path
// needs and none of them this repository's to drop). Listing those here would produce a check that
// fails on the commit introducing it, which is the same as not having one.
abstract class CheckRenderHostIsServerFree : DefaultTask() {
  @get:Input abstract val resolvedModules: SetProperty<String>

  @get:Input abstract val forbiddenPrefixes: ListProperty<String>

  @TaskAction
  fun check() {
    val prefixes = forbiddenPrefixes.get()
    val offenders =
      resolvedModules.get().filter { module -> prefixes.any { module.startsWith(it) } }.sorted()
    if (offenders.isNotEmpty()) {
      throw GradleException(
        buildString {
          appendLine("`:render-host` resolved artifacts it exists to stay free of:")
          offenders.forEach { appendLine("  - $it") }
          appendLine()
          appendLine(
            "This module backs the OFFLINE `bundle render` and `history manifest` commands. Pulling"
          )
          appendLine(
            "a web server, service discovery or the Kotlin compiler onto its classpath undoes the"
          )
          appendLine(
            "reason it was split out of `:server` (yschimke/compose-ai-tools#4832). Either the new code"
          )
          appendLine(
            "belongs in `:server`, or the dependency belongs behind an interface it implements."
          )
        }
      )
    }
  }
}

tasks.register<CheckRenderHostIsServerFree>("checkRenderHostIsServerFree") {
  description =
    "Fails if a web server, HTTP client, mDNS or the Kotlin compiler reaches this module."
  group = "verification"

  resolvedModules.set(
    configurations.named("runtimeClasspath").flatMap { configuration ->
      configuration.incoming.artifacts.resolvedArtifacts.map { artifacts ->
        artifacts
          .mapNotNull { artifact ->
            (artifact.id.componentIdentifier as? ModuleComponentIdentifier)?.let {
              "${it.group}:${it.module}"
            }
          }
          .toSet()
      }
    }
  )

  // Prefixes rather than exact coordinates: `io.ktor:ktor-server-cio` today, but the invariant is
  // "no Ktor at all", and an exact list would pass the first time someone adds a different engine.
  forbiddenPrefixes.set(
    listOf(
      // Every server engine and plugin, by prefix rather than exact coordinate: the invariant is
      // "no web server", and an exact list would pass the first time someone swaps CIO for Netty.
      "io.ktor:ktor-server",
      "org.jmdns:",
      // The Kotlin compiler frontend behind the playground's in-process compile. `:server` loads it
      // through an isolated classloader and keeps it off its own runtime classpath too; on this
      // module it should not appear by any route. Note this is NOT `kotlin-build-tools-api`, which
      // is the interface, arrives via `daemon-core`, and is out of this repository's hands.
      "org.jetbrains.kotlin:kotlin-compiler",
      "org.jetbrains.kotlin:kotlin-build-tools-impl",
    )
  )
}

tasks.named("check") { dependsOn("checkRenderHostIsServerFree") }

mavenPublishing {
  publishToMavenCentral(automaticRelease = true)
  if (!project.version.toString().endsWith("SNAPSHOT")) signAllPublications()
  coordinates(group.toString(), publishedArtifactId, project.version.toString())
  pom {
    name.set("Compose Preview — Render Host")
    description.set(
      "Daemon-backed preview rendering, packed-bundle materialisation and git-backed preview history, without a web server."
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
