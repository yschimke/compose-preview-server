package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.buildhost.BuildHostProtocol
import ee.schimke.composeai.buildhost.BuildHostRequest
import ee.schimke.composeai.buildhost.BuildHostResponse
import ee.schimke.composeai.buildhost.WireModule
import ee.schimke.composeai.previewdata.PreviewModule
import java.io.Closeable
import java.io.File

/**
 * A [ServeBuildHost] backed by a `compose-preview build-host --stdio` process.
 *
 * This is what lets the standalone server do `--module app --discover` — the capability that until
 * now existed only when `serve` was a compose-ai-tools CLI command, because that command was the
 * only real implementation of this interface
 * ([#9](https://github.com/yschimke/compose-preview-server/issues/9)). The Gradle Tooling API stays
 * in compose-ai-tools, on the other side of a pipe; nothing here links it, and
 * `checkServeModuleBoundary` still holds.
 *
 * **Every failure degrades rather than propagates.** Each method answers with the neutral value the
 * operation would have had with no build host at all — which is exactly [StandaloneBuildHost]'s
 * behaviour, and a real serving mode rather than an error state. A server hosting published
 * catalogs should not fall over because a Gradle build died, and the interface has no way to say "I
 * could not tell" that its callers could act on differently anyway.
 *
 * The reason is reported once, on the transition, instead of per call: a broken pipe answered
 * sixteen times is sixteen lines saying the same thing.
 */
internal class ProcessBuildHost
private constructor(private val process: Process, private val connection: BuildHostConnection) :
  ServeBuildHost, Closeable {

  private var reportedUnusable = false

  override fun autoInjectInitScriptArgs(projectRoot: File): List<String> =
    strings(BuildHostRequest.AutoInjectInitScriptArgs(WireModule.wirePath(projectRoot)))

  override fun gradleProjectRoot(): File? =
    (ask(BuildHostRequest.GradleProjectRoot) as? BuildHostResponse.Path)?.path?.let(::File)

  override fun gradleVariantArgs(): List<String> = strings(BuildHostRequest.GradleVariantArgs)

  override fun gradleBuildArgs(extra: List<String>): List<String> =
    strings(BuildHostRequest.GradleBuildArgs(extra))

  override fun gradleProjects(): List<PreviewModule> =
    (ask(BuildHostRequest.GradleProjects) as? BuildHostResponse.Modules)
      ?.modules
      ?.map(WireModule::toPreviewModule)
      .orEmpty()

  override fun runGradleTasks(
    vararg tasks: String,
    arguments: List<String>,
    silenceStdout: Boolean,
  ): Boolean =
    (ask(
        BuildHostRequest.RunGradleTasks(
          tasks = tasks.toList(),
          arguments = arguments,
          silenceStdout = silenceStdout,
        )
      )
        as? BuildHostResponse.BuildResult)
      ?.buildOk == true

  override fun discoverAndBuild(silenceStdout: Boolean): ServeDiscovery {
    val response =
      ask(BuildHostRequest.DiscoverAndBuild(silenceStdout)) as? BuildHostResponse.Discovery
        ?: return ServeDiscovery(buildOk = false, manifests = emptyList())
    return ServeDiscovery(
      buildOk = response.buildOk,
      manifests = response.manifests.map { it.module.toPreviewModule() to it.manifest },
    )
  }

  private fun strings(request: BuildHostRequest): List<String> =
    (ask(request) as? BuildHostResponse.Strings)?.values.orEmpty()

  private fun ask(request: BuildHostRequest): BuildHostResponse? {
    val response = connection.exchange(request)
    if (response is BuildHostResponse.Failure) {
      System.err.println("compose-preview build host: ${response.message}")
    }
    connection.unusable?.let { reason ->
      if (!reportedUnusable) {
        reportedUnusable = true
        System.err.println(
          "compose-preview build host is no longer answering ($reason); serving published " +
            "catalogs and prebuilt bundles only."
        )
      }
    }
    return response
  }

  override fun close() {
    // Closing stdin IS the shutdown signal — the host's read loop ends and it exits. Destroying the
    // process without that would kill a Gradle build mid-write.
    runCatching { process.outputStream.close() }
    if (!process.waitFor(SHUTDOWN_SECONDS, java.util.concurrent.TimeUnit.SECONDS)) {
      process.destroy()
    }
  }

  internal companion object {
    private const val SHUTDOWN_SECONDS = 10L

    /** The CLI subcommand that speaks this protocol. */
    private const val SUBCOMMAND = "build-host"

    /**
     * Spawns a build host and completes its handshake, or returns null.
     *
     * Null covers every way this can legitimately not happen — no CLI installed, a version that
     * disagrees, a binary that will not start — because they all mean the same thing to the caller:
     * serve without one. Each is reported to stderr on the way past, since "the server quietly did
     * not use the build host you installed" is the failure mode worth avoiding.
     */
    fun spawn(binary: String, workingDirectory: File?): ProcessBuildHost? {
      // The full argv is built here, from the binary alone, and that is deliberate. An earlier
      // shape
      // took the whole command and appended only `--stdio`, which left every caller responsible for
      // remembering the `build-host` subcommand — and the first caller that forgot got a CLI usage
      // banner instead of a handshake, spawn() returning null, and a server that quietly served
      // without a build host. One place builds it now, so there is nothing to forget.
      val command = listOf(binary, SUBCOMMAND, BuildHostProtocol.STDIO_FLAG)
      val process =
        try {
          ProcessBuilder(command)
            .directory(workingDirectory)
            // The host's own diagnostics are on stderr and are never framed; letting them through
            // is what makes a failure to start visible instead of silent.
            .redirectError(ProcessBuilder.Redirect.INHERIT)
            .start()
        } catch (t: Throwable) {
          System.err.println(
            "compose-preview build host could not be started (${command.joinToString(" ")}): " +
              "${t.message ?: t.javaClass.name}. Serving without a local Gradle build."
          )
          return null
        }

      val connection =
        BuildHostConnection(
          requests = process.outputStream.bufferedWriter(),
          responses = process.inputStream.bufferedReader(),
          onLog = { line -> println(line) },
        )
      if (!connection.handshake()) {
        System.err.println(
          "compose-preview build host did not agree a protocol " +
            "(${connection.unusable ?: "no response"}); serving without a local Gradle build."
        )
        runCatching { process.outputStream.close() }
        process.destroy()
        return null
      }
      return ProcessBuildHost(process, connection)
    }
  }
}
