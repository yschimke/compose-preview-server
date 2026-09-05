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

  // The capability this binary did not have until now: with a build host, `--module app --discover`
  // finds the Gradle project, builds its previews and serves them, which previously required the
  // compose-ai-tools CLI to BE the server (#9). Without one, `StandaloneBuildHost` — which is no
  // longer a set of stubs standing in for a real implementation, but the honest answer for a server
  // that has no Gradle build behind it.
  val buildHost =
    BuildHostDiscovery.choose(args)?.let { choice ->
      ProcessBuildHost.spawn(choice.binary, workingDirectory = null)?.also {
        System.err.println("compose-preview build host: ${choice.binary} (from ${choice.source})")
      }
    }

  try {
    ServeRunner(options, buildHost ?: StandaloneBuildHost).run()
  } finally {
    buildHost?.close()
  }
}

/**
 * What the server can answer with no Gradle build behind it.
 *
 * This used to be a placeholder — seven methods stubbed because the real implementation lived in
 * the compose-ai-tools CLI and could not be reached from here (#9). It is now the deliberate
 * no-build mode: a server hosting published catalogs and prebuilt bundles has nothing to ask
 * Gradle, and that is the common deployed case rather than a degraded one. When a build host IS
 * available, [ProcessBuildHost] answers instead.
 */
internal object StandaloneBuildHost : ServeBuildHost {
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

/**
 * Does one preview satisfy this invocation's `--id` / `--filter` / `--preview` request?
 *
 * The three selectors are independent predicates and **intersect**: `--id` is the id exactly and
 * case-sensitively, `--filter` a case-insensitive substring of it, and `--preview` a loose
 * reference matching any of the exact id, `<className>.<functionName>`, the bare function name, or
 * a case-insensitive substring of the id. [className] / [functionName] are absent at call sites
 * that only hold an id, where the metadata forms of `--preview` cannot fire.
 *
 * **This rule is stated twice.** compose-ai-tools answers the same question with
 * `previewIdMatchesRequest` for every command that is not `serve`; the CLI used to pass that rule
 * in here, and stopped when `serve` became a launcher (#180 step 4). The two are pinned against
 * each other by a shared golden table — `docs/serve/preview-selector-fixtures.json`, owned upstream
 * and vendored here by `scripts/sync-preview-selector-fixtures.sh` — which both repositories run
 * ([compose-ai-tools#5185](https://github.com/yschimke/compose-ai-tools/issues/5185)). Change this
 * rule and the table changes with it, or the two sides silently disagree, and a preview that stops
 * matching raises no error anywhere.
 */
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
