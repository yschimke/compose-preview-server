// The Model Context Protocol server — `compose-preview mcp serve`, as its own module in this
// repository.
//
// **This module moved here**, from compose-ai-tools, and the reason is the layer rule itself:
// `docs/design/REPOSITORY_LAYERS.md` places a module by *does it need an HTTP server, a browser,
// or the UI builder to do its job?*, and this one runs a Ktor server for the UI-builder Streamable
// HTTP endpoint plus everything the MCP Kotlin SDK's server brings with it. compose-ai-tools#5176
// decided that against the alternative — writing the rule a carve-out saying MCP is "a transport
// for the CLI, not a preview surface" — because a rule with an exception in it is a weaker rule,
// and the exception would be the thing the next module argues from.
//
// What that costs, stated rather than discovered later: the agent entry point now lives in the
// repository that owns the web UI, and what this module uses from layer 1 — `daemon-core`,
// `render-session-api`, `daemon-client`, `render-matrix` — is published surface across a
// repository boundary instead of a project dependency. That is the same trade `serve` made in
// the other direction, and it is why the CLI's offline `render-matrix` command was lifted OUT of
// `:mcp` into
// its own coordinate first (compose-ai-tools#5188): nothing that leaves for layer 2 may be
// something layer 1 still calls.
//
// Package note: the sources keep `ee.schimke.composeai.mcp`, exactly as `:server` kept
// `ee.schimke.composeai.cli.serve` when it moved. The rename is a separately reviewed change in
// both repositories, and keeping it is what makes this move source-compatible — a reader diffs
// the two trees and sees no edits at all.
//
// Coordinate change, deliberate, the `:render-host` lesson applied in the other direction: it
// published as `ee.schimke.composeai:mcp` from compose-ai-tools and publishes as
// `compose-preview-mcp` from here. Keeping the old coordinate would mean two repositories
// publishing one artifact on two version lines; a new coordinate in this repository's naming
// (`compose-preview-serve`, `compose-preview-ui-builder-runtime`) has neither problem, and the old
// one stays resolvable at its final 1.x for anyone pinned to it.
plugins {
  application
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.ktfmt)
  alias(libs.plugins.maven.publish)
}

group = "ee.schimke.composeai"

// Named once, for the same reason `:server` names its own: `mavenPublishing.coordinates` and
// `archivesName` both need it, and the Gradle project is `:mcp`, so anything Gradle derives from
// the project name would get `mcp`.
val publishedArtifactId = "compose-preview-mcp"

kotlin { jvmToolchain(libs.versions.java.server.get().toInt()) }

ktfmt { googleStyle() }

// Same derivation as `:server` — `PLUGIN_VERSION` in CI, a patch-bumped SNAPSHOT off
// `.release-please-manifest.json` locally. This module ships in lockstep with the server, so it
// reads the same manifest rather than carrying a version line of its own.
version =
  providers.environmentVariable("PLUGIN_VERSION").orNull
    ?: run {
      val manifest = rootDir.resolve(".release-please-manifest.json").readText()
      val current = Regex(""""\.":\s*"([^"]+)"""").find(manifest)!!.groupValues[1]
      val (major, minor, patch) = current.split(".").map { it.toInt() }
      "$major.$minor.${patch + 1}-SNAPSHOT"
    }

base { archivesName.set(publishedArtifactId) }

application {
  applicationName = "compose-preview-mcp"
  mainClass.set("ee.schimke.composeai.mcp.DaemonMcpMain")
}

// `archiveExtension = "tar.gz"` keeps the in-archive root as `compose-preview-mcp-<version>/`
// rather than leaking `.tar.gz` into the directory name. Carried over from compose-ai-tools,
// where the GitHub Release artifact this produces is what `compose-preview mcp serve` runs.
tasks.named<Tar>("distTar") {
  archiveExtension.set("tar.gz")
  compression = Compression.GZIP
}

// The same Java floor `:server`'s distribution carries, for the same launcher and the same reason:
// `compose-preview mcp serve` execs `bin/compose-preview-mcp`, which resolves `java` from
// `JAVA_HOME`/`PATH` and does not inherit the CLI's JVM. `:server`'s build file has the argument,
// including why this is a file rather than a flag the start script answers; it is repeated here
// rather than shared because the two distributions are assembled independently and a launcher must
// find the file beside whichever binary it resolved.
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
            appendLine("# Generated by :mcp:writeDistributionJavaMin. Do not edit.")
            appendLine("# The minimum Java feature version this distribution can run on.")
            appendLine("javaMin=$javaMin")
          }
        )
    }
  }

distributions { main { contents { from(writeDistributionJavaMin) } } }

dependencies {
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.kotlinx.serialization.json)
  implementation(libs.mcp.kotlin.sdk.server)
  // CIO hosts the optional remote UI-builder Streamable HTTP endpoint. The MCP SDK owns the
  // protocol route (POST/GET/DELETE + SSE); this module only supplies the engine and auth bridge.
  // It is also the dependency that makes this module layer 2 rather than layer 1.
  implementation(libs.ktor.server.cio)
  // Okio-based file IO (`SystemFileSystem`) for descriptor reads + PNG/video byte reads.
  implementation(libs.composeai.common.io)
  implementation(libs.composeai.agent.grant.protocol)
  implementation(libs.composeai.ui.builder.protocol)

  // `api` for the three that appear on this module's own public surface:
  // `SupervisedDaemon.session` is a `RenderSession`, `SupervisedDaemon.client` is a
  // `DaemonClient`, `DaemonSupervisor` takes a
  // `DaemonClientFactory`, and the protocol message types are all over the tool implementations.
  // Without `api` the generated POM scopes them as `runtime` only and a consumer resolving from POM
  // metadata cannot compile against the MCP APIs.
  //
  // Project dependencies became published coordinates in the move. That is the cost the layer rule
  // charges for putting this module on the right side of the boundary, and it is why the version
  // pin below matters: this module is compiled against a compose-ai-tools RELEASE, not against its
  // main branch, so an API it needs must be in a release before it can be used here.
  api(libs.composeai.daemon.core)
  api(libs.composeai.render.session.api)
  api(libs.composeai.daemon.client)

  // Semantics diff engine + payload model for the `diff_semantics` tool.
  implementation(libs.composeai.data.layoutinspector.core)
  // Axis expansion + contact-sheet stitching behind the `render_matrix` tool, shared with the CLI's
  // offline `render-matrix` command so the two agree by construction.
  implementation(libs.composeai.render.matrix)

  testImplementation(libs.junit)
  testImplementation(libs.truth)
  testImplementation(libs.kotlinx.coroutines.core)
  testImplementation(libs.ktor.server.test.host)
}

// JUnit 4, deliberately, unlike `:server`'s JUnit 5. These tests came with the module and assert
// through Truth; converting ~1.5k lines of assertions is a separate change from moving them, and
// doing both at once makes the move unreviewable — the lesson `PREVIEW_SERVER_SPLIT.md` records
// from `:bundle-format`.
tasks.withType<Test>().configureEach {
  // Opt-in real-mode: `-Pmcp.real=true` flips the JUnit `Assume` gate in `RealMcpEndToEndTest`. The
  // optional `-Pmcp.workdir=<path>` lets out-of-tree runs point the test at a different checkout;
  // it defaults to the test's own working directory.
  val mcpReal = providers.gradleProperty("mcp.real").orNull == "true"
  systemProperty("composeai.mcp.real", mcpReal.toString())
  providers.gradleProperty("mcp.workdir").orNull?.let {
    systemProperty("composeai.mcp.workdir", it)
  }
}

// Boundary check, ported with the module: `:mcp` must NOT pull `gradle-tooling-api`, directly or
// transitively. It mattered in compose-ai-tools because the cold-shell gradle invocation behind
// `mcp install` / `mcp doctor` lives in the CLI and routes through `:gradle-preview-driver`, and it
// matters MORE here: AGENTS.md and the layer rule both say the Tooling API stays off this
// repository's floor entirely, and a server that needs a local Gradle build asks compose-ai-tools
// for one across a process boundary.
abstract class CheckMcpToolingApiBoundary : DefaultTask() {
  @get:org.gradle.api.tasks.Classpath abstract val runtimeClasspath: ConfigurableFileCollection

  @TaskAction
  fun check() {
    val forbidden =
      runtimeClasspath.files
        .map { it.name }
        .filter { it.startsWith("gradle-tooling-api") && it.endsWith(".jar") }
        .sorted()
    check(forbidden.isEmpty()) {
      ":mcp must not depend on gradle-tooling-api. Driving a build is layer-1 behaviour and this " +
        "repository asks compose-ai-tools for it across a process boundary — see AGENTS.md and " +
        "docs/design/REPOSITORY_LAYERS.md. Found on runtimeClasspath: " +
        forbidden.joinToString(", ")
    }
  }
}

val checkMcpToolingApiBoundary =
  tasks.register<CheckMcpToolingApiBoundary>("checkMcpToolingApiBoundary") {
    description = "Fails if gradle-tooling-api leaks onto :mcp's runtime classpath."
    group = "verification"
    runtimeClasspath.from(configurations.named("runtimeClasspath"))
  }

// `check` for anyone running it, and `test` because that is what CI actually invokes — the same
// pairing the task carried in compose-ai-tools, where `check` was never run by any workflow and the
// guard had therefore never executed.
tasks.named("check") { dependsOn(checkMcpToolingApiBoundary) }

tasks.named("test") { finalizedBy(checkMcpToolingApiBoundary) }

mavenPublishing {
  publishToMavenCentral(automaticRelease = true)
  if (!project.version.toString().endsWith("SNAPSHOT")) signAllPublications()
  coordinates(group.toString(), publishedArtifactId, project.version.toString())
  pom {
    name.set("Compose Preview — MCP Server")
    description.set(
      "Model Context Protocol server for compose-preview. Multiplexes per-(workspace, module) " +
        "daemon JVMs spawned from launch descriptors emitted by composePreviewDaemonStart, and " +
        "serves the UI-builder Streamable HTTP endpoint."
    )
    url.set("https://github.com/yschimke/compose-preview-server")
    inceptionYear.set("2025")
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
