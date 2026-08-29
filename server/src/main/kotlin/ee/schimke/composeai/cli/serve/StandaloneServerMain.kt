package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.previewdata.PreviewModule
import java.io.File

/** Starts the published server without the compose-ai-tools CLI or a local Gradle build. */
public fun main(rawArgs: Array<String>) {
  val args = rawArgs.toList().let { if (it.firstOrNull() == "serve") it.drop(1) else it }
  require(args.firstOrNull()?.startsWith("-") != false) {
    "Unknown command '${args.first()}'. Pass server flags directly (or use the optional 'serve' alias)."
  }
  val options =
    ServeCommandOptions(
      args = args,
      defaultTimeoutSeconds = DEFAULT_TIMEOUT_SECONDS,
      previewMatcher = ::previewIdMatchesStandaloneRequest,
    )
  if (options.helpRequested) {
    options.printUsage()
    return
  }
  ServeRunner(options, StandaloneBuildHost).run()
}

private object StandaloneBuildHost : ServeBuildHost {
  override fun autoInjectInitScriptArgs(projectRoot: File): List<String> = emptyList()

  override fun gradleProjectRoot(): File? = null

  override fun gradleVariantArgs(): List<String> = emptyList()

  override fun gradleBuildArgs(extra: List<String>): List<String> = extra

  override fun gradleProjects(): List<PreviewModule> = emptyList()

  override fun runGradleTasks(
    vararg tasks: String,
    arguments: List<String>,
    silenceStdout: Boolean,
  ): Boolean = false

  override fun discoverAndBuild(silenceStdout: Boolean): ServeDiscovery =
    ServeDiscovery(buildOk = false, manifests = emptyList())
}

internal fun previewIdMatchesStandaloneRequest(
  id: String,
  exactId: String?,
  filter: String?,
  previewRef: String?,
  className: String?,
  functionName: String?,
): Boolean {
  if (exactId != null && id != exactId) return false
  if (filter != null && !id.contains(filter, ignoreCase = true)) return false
  if (
    previewRef != null &&
      id != previewRef &&
      !(className != null && functionName != null && "$className.$functionName" == previewRef) &&
      functionName != previewRef &&
      !id.contains(previewRef, ignoreCase = true)
  ) {
    return false
  }
  return true
}

private const val DEFAULT_TIMEOUT_SECONDS = 600L
