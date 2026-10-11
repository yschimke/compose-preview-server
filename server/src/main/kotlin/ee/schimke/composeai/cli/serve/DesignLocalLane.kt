package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.bundle.BundleReader
import ee.schimke.composeai.uibuilder.export.SystemFontLookups
import ee.schimke.composeai.uibuilder.export.TypefaceTarget
import ee.schimke.composeai.uibuilder.export.VariableFontExportMode
import ee.schimke.composeai.uibuilder.protocol.DesignDocumentV1
import ee.schimke.composeai.uibuilder.service.FileUiBuilderAssetStore
import ee.schimke.composeai.uibuilder.service.UiBuilderAssetStore
import java.io.File
import java.util.Base64
import java.util.concurrent.atomic.AtomicLong
import okio.Path.Companion.toPath

/**
 * Compiling and rendering a design in this process, so a broken render can be stepped through.
 * `design render` asks a server, whose deliberately lossy reply can't be debugged: e.g. `exception:
 * null` + `image: null` looks identical for a missing sidecar, a timeout and a throw.
 *
 * A seam, not a reimplementation: [DesignLocalCompileLane] wires the same
 * [ScreenGeneratorComposeExportExecutor] → [UiBuilderGeneratedPreviewAdapter] →
 * [PlaygroundCompileService] → daemon path as [ServeUiBuilderNativePreview], so it reproduces the
 * server's bugs.
 *
 * Unlike the HTTP surface, [describe] reports what was actually built (classpath, daemon opener,
 * record) beside the reason a frame is missing.
 */
internal interface DesignLocalLane {

  /** How this lane came out: the facts a missing frame has to be read against. One line each. */
  fun describe(): List<String>

  /** The generated Kotlin for [document], or why there is none. */
  fun generate(document: DesignDocumentV1): Source

  /** The design's first frame, or why there isn't one. */
  fun render(document: DesignDocumentV1): Frame

  sealed interface Source {
    data class Emitted(
      val source: String,
      val screenName: String,
      /** What the source draws differently from the design — today, typefaces it cannot ship. */
      val warnings: List<String> = emptyList(),
    ) : Source

    /** The generator's own code and reasons, unchanged — the same ones the server would send. */
    data class Refused(val code: String, val reasons: List<String>) : Source
  }

  sealed interface Frame {
    data class Rendered(val png: ByteArray) : Frame

    /**
     * The design compiled (or not) and no picture came back. [reason] is [noFrameReason]'s: the
     * diagnostics, exception, or "no frame" that the wire reply drops.
     */
    data class NoFrame(val reason: String) : Frame

    data class Refused(val code: String, val reasons: List<String>) : Frame
  }
}

/**
 * The base64 payload of a rendered frame. `PlaygroundRunResponse.image` is a data URL (the
 * playground assigns it to an `<img>` `src`); a bare payload, as the MCP tool's `imageBase64` name
 * suggests, is accepted too.
 */
internal fun renderedFrameBase64(image: String): String =
  if (image.startsWith("data:")) image.substringAfter(',', missingDelimiterValue = "") else image

/**
 * The production [DesignLocalLane]: a bundle on disk, this process, no server. Resolved lazily and
 * remembered, so [describe] reports what was really built.
 */
internal class DesignLocalCompileLane(
  /** The catalog bundle the design is compiled against; its manifest picks the daemon. */
  private val bundleFile: File,
  /** `<catalog system id>` → its `components.json`, as `serve --ui-builder-components` takes. */
  componentRecords: Map<String, File> = emptyMap(),
  /** Uploaded asset bytes by `storageKey`, for the widget lane that inlines them. */
  assets: UiBuilderAssetStore? = null,
  private val workRoot: File,
  private val log: (String) -> Unit,
  /**
   * No jail by default: the document is the operator's own, on their machine, and a jail would only
   * hide the failure. The daemon is still a killable subprocess.
   */
  private val sandbox: PlaygroundSandbox =
    PlaygroundSandbox(profile = PlaygroundSandbox.Profile.NONE),
  private val extraMavenRepos: List<String> = emptyList(),
) : DesignLocalLane {

  private val records = ComponentRecordSource(componentRecords)

  private val executor = ScreenGeneratorComposeExportExecutor(records::record, assetStore = assets)

  private val recordNote =
    if (componentRecords.isEmpty()) "component record: none named (--components)"
    else
      "component record: " +
        componentRecords.entries.joinToString(", ") { "${it.key}=${it.value.path}" }

  private val snippets = AtomicLong()
  private val renders = AtomicLong()

  /** `android` or `desktop` — the bundle's own declaration, which decides everything below it. */
  private val backend: String? by lazy {
    runCatching { BundleReader.readMetadata(bundleFile).manifest.backend }
      .onFailure { log("could not read ${bundleFile.path}: ${it.message}") }
      .getOrNull()
  }

  private val android: Boolean
    get() = backend == ANDROID_BACKEND

  private val classpath: PlaygroundCompileService.Classpath? by lazy {
    PlaygroundCatalogClasspath.resolve(
      bundleFile = bundleFile,
      destDir = File(workRoot, "catalog"),
      system = CATALOG,
      extraMavenRepos = extraMavenRepos,
      onLog = log,
    )
  }

  private val opener: PlaygroundAndroidSessionOpener? by lazy {
    if (android) PlaygroundDaemonOpeners.android(sandbox, log)
    else PlaygroundDaemonOpeners.desktop(sandbox, log)
  }

  private val compiler: PlaygroundCompileService.Compiler? by lazy {
    PlaygroundBtaCompiler.fromInstall(File(workRoot, "bta-ic").toPath())
  }

  private val preview: UiBuilderNativePreviewLane? by lazy {
    val classpath = classpath ?: return@lazy null
    val compiler = compiler ?: return@lazy null
    val renderer = opener?.let { openSession ->
      PlaygroundAndroidRenderService(
        openSession = openSession,
        newWorkDir = { File(workRoot, "render-${renders.incrementAndGet()}") },
      )
    }
    val service =
      PlaygroundCompileService(
        // One bundle, one mode: a request for the other mode is a wiring bug, refused by name.
        catalogClasspath = { requested, _ -> classpath.takeIf { requested == mode } },
        compiler = compiler,
        discoverer = PlaygroundPreviewDiscoverer(),
        tokenStore = PlaygroundTokenStore(),
        newWorkDir = {
          File(workRoot, "snippet-${snippets.incrementAndGet()}").absolutePath.toPath()
        },
        // A null renderer isn't fatal to the compile, as on the server; it is one of the states
        // [describe] reports.
        renderFirstFrameWithReason = { snippet ->
          renderer?.renderFrame(snippet) ?: PlaygroundFirstFrame(null)
        },
      )
    val adapter = UiBuilderGeneratedPreviewAdapter(service)
    ServeUiBuilderNativePreview(
      executor = executor,
      // Every design compiles against the bundle the operator named; the server's catalog mapping
      // doesn't apply.
      nativeTarget = { UiBuilderNativeTarget(catalog = CATALOG, confType = confType) },
      compile = { generated ->
        // `true` because there is no actor to check: the document came from this shell and the
        // Kotlin from `ScreenGenerator`, trusted as much as the file the operator chose to run.
        adapter.compile(generated, isSecurityChecked = true)
      },
    )
  }

  private val mode: PlaygroundMode
    get() = if (android) PlaygroundMode.ANDROID else PlaygroundMode.CMP

  private val confType: String
    get() =
      if (android) UiBuilderGeneratedCompose.COMPOSE_ANDROID
      else UiBuilderGeneratedCompose.COMPOSE_CMP

  override fun describe(): List<String> =
    listOf(
      "bundle: ${bundleFile.path} (backend ${backend ?: "unreadable"}, mode ${mode.name})",
      "compile classpath: " +
        (classpath?.let { "${it.entries.size} entries" } ?: "unresolved — see the lines above"),
      "compiler: " + (compiler?.let { "bta" } ?: "none — no lib-bta/ in this install"),
      "daemon opener: " +
        (opener?.let { if (android) "robolectric (android)" else "skiko (desktop)" }
          ?: "none — this render has no still frame to produce"),
      recordNote,
    )

  override fun generate(document: DesignDocumentV1): DesignLocalLane.Source =
    // Written for this lane's bundle, as the render below compiles it: a desktop bundle gets
    // `SystemFont` lookups for its typefaces, because it has no Android `GoogleFont` to resolve.
    when (
      val generated =
        executor.generate(
          document,
          typefaces = TypefaceTarget.forNativeBackend(backend),
          // The bundle this compiles against carries Compose, not flexpress.
          variableFontMode = VariableFontExportMode.STANDALONE,
        )
    ) {
      is ScreenGeneratorComposeExportExecutor.Generated.Emitted ->
        DesignLocalLane.Source.Emitted(
          generated.source,
          generated.screenName,
          warnings = generated.systemFontFamilies.map(SystemFontLookups::note),
        )
      is ScreenGeneratorComposeExportExecutor.Generated.Refused ->
        DesignLocalLane.Source.Refused(generated.code, generated.reasons)
    }

  override fun render(document: DesignDocumentV1): DesignLocalLane.Frame {
    val preview =
      preview
        ?: return DesignLocalLane.Frame.NoFrame(
          "this install cannot compile the design at all: " +
            (if (classpath == null) "the bundle resolved no classpath"
            else "no Kotlin compiler (lib-bta/) is installed")
        )
    return when (val outcome = preview.render(document)) {
      is UiBuilderNativePreviewOutcome.Refused ->
        DesignLocalLane.Frame.Refused(outcome.code, outcome.reasons)
      is UiBuilderNativePreviewOutcome.Rendered -> {
        val image = outcome.response.image
        val failure = outcome.failure
        when {
          failure != null -> DesignLocalLane.Frame.NoFrame(failure)
          image == null ->
            DesignLocalLane.Frame.NoFrame(
              "the render lane answered with neither a frame nor a reason"
            )
          else ->
            runCatching { Base64.getDecoder().decode(renderedFrameBase64(image)) }
              .fold(
                onSuccess = { DesignLocalLane.Frame.Rendered(it) },
                onFailure = {
                  DesignLocalLane.Frame.NoFrame(
                    "the render lane's frame is not valid base64 (${image.take(32)}…)"
                  )
                },
              )
        }
      }
    }
  }

  internal companion object {
    /** `manifest.backend` for a bundle whose previews are drawn by Robolectric-backed Android. */
    const val ANDROID_BACKEND: String = "android"

    /**
     * The one catalog target this lane offers: the bundle the caller passed. A design's own catalog
     * id names a server catalog, and there is none here.
     */
    const val CATALOG: String = "local"

    /** The asset store a `--assets <dir>` names, or null when the directory cannot be read. */
    fun assetStore(directory: File?, log: (String) -> Unit): UiBuilderAssetStore? {
      if (directory == null) return null
      return runCatching { FileUiBuilderAssetStore(directory.toPath().toAbsolutePath()) }
        .onFailure {
          log(
            "--assets ${directory.path} is not usable (${it.message}); a design that inlines an " +
              "uploaded picture will refuse by name"
          )
        }
        .getOrNull()
    }
  }
}
