package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.bundle.BundleReader
import ee.schimke.composeai.uibuilder.protocol.DesignDocumentV1
import ee.schimke.composeai.uibuilder.service.FileUiBuilderAssetStore
import ee.schimke.composeai.uibuilder.service.UiBuilderAssetStore
import java.io.File
import java.util.Base64
import java.util.concurrent.atomic.AtomicLong
import okio.Path.Companion.toPath

/**
 * Compiling and rendering a design **in this process**, so a broken render can be stepped through.
 *
 * ## Why the mode exists
 *
 * `design render` asks a server, and a server is exactly the thing under suspicion when a render
 * misbehaves: its reply is deliberately lossy, so the failure cannot be taken anywhere it can be
 * debugged. Two bugs paid for this. One was found by hand — fetching a document and its uploaded
 * PNGs through `/mcp`, writing a throwaway JUnit test, standing up a throwaway Gradle project to
 * resolve the catalog's jars, and driving `kotlin-compiler-embeddable` to reproduce an `Unresolved
 * reference 'graphics'` ([#544](https://github.com/yschimke/compose-preview-server/issues/544)).
 * The other was not found at all: `exception: null` + `image: null` + a valid preview id is the
 * identical observable for a missing sidecar, a render that timed out and a render that threw, and
 * the reason only ever reached the server's log
 * ([#481](https://github.com/yschimke/compose-preview-server/issues/481)).
 *
 * So this is a seam, not a reimplementation. [DesignLocalCompileLane] wires the **same**
 * [ScreenGeneratorComposeExportExecutor] → [UiBuilderGeneratedPreviewAdapter] →
 * [PlaygroundCompileService] → daemon path the server drives through [ServeUiBuilderNativePreview];
 * a lane that merely resembled the server's would be unable to reproduce the server's bugs, which
 * is the one job it has.
 *
 * ## Being chattier than the wire is correct here
 *
 * The HTTP surface answers a client and keeps its reasons to itself. This one answers the person
 * holding the failure, so [describe] reports what was actually built — the classpath it resolved,
 * the daemon opener it got, the record it read — beside the reason a frame is missing. Those three
 * facts are what #481 needed and could not have.
 */
internal interface DesignLocalLane {

  /** How this lane came out: the facts a missing frame has to be read against. One line each. */
  fun describe(): List<String>

  /** The generated Kotlin for [document], or why there is none. */
  fun generate(document: DesignDocumentV1): Source

  /** The design's first frame, or why there isn't one. */
  fun render(document: DesignDocumentV1): Frame

  sealed interface Source {
    data class Emitted(val source: String, val screenName: String) : Source

    /** The generator's own code and reasons, unchanged — the same ones the server would send. */
    data class Refused(val code: String, val reasons: List<String>) : Source
  }

  sealed interface Frame {
    data class Rendered(val png: ByteArray) : Frame

    /**
     * The design compiled (or did not) and no picture came back. [reason] is [noFrameReason]'s —
     * the compiler's own diagnostics, the exception, or the honest "this host's renderer produced
     * no frame" — which is precisely what the wire reply drops.
     */
    data class NoFrame(val reason: String) : Frame

    data class Refused(val code: String, val reasons: List<String>) : Frame
  }
}

/**
 * The production [DesignLocalLane]: a bundle on disk, this process, and no server anywhere.
 *
 * Everything is resolved lazily and remembered, because [describe] is only useful once it can
 * report what was really built — asking before a render would report intentions.
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
   * No jail by default, unlike the server's lane.
   *
   * The sandbox exists because a served playground compiles a **stranger's** snippet. Here the
   * document is one the operator handed this process on their own machine, from their own shell,
   * and a jail would only stand between them and the failure they are trying to read. The daemon
   * still runs as its own subprocess, so a render that hangs is still killable.
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
        // One bundle, one mode: the manifest already decided which daemon draws this, so a request
        // for the other one is a wiring bug rather than a catalog choice. Named rather than
        // ignored, so the refusal says which mode this bundle is.
        catalogClasspath = { requested, _ -> classpath.takeIf { requested == mode } },
        compiler = compiler,
        discoverer = PlaygroundPreviewDiscoverer(),
        tokenStore = PlaygroundTokenStore(),
        newWorkDir = {
          File(workRoot, "snippet-${snippets.incrementAndGet()}").absolutePath.toPath()
        },
        // A null renderer is not fatal to the compile, exactly as on the server — and it is one of
        // the three states [describe] reports, because it is indistinguishable on the wire from a
        // render that threw.
        renderFirstFrame = { snippet -> renderer?.render(snippet) },
      )
    val adapter = UiBuilderGeneratedPreviewAdapter(service)
    ServeUiBuilderNativePreview(
      executor = executor,
      // Every design compiles against the one bundle this invocation named. The server maps a
      // builder catalog id to a served catalog because it serves several; here the operator has
      // already made that choice by naming a file, and silently refusing a design whose catalog id
      // does not match the bundle's would be second-guessing it.
      nativeTarget = { UiBuilderNativeTarget(catalog = CATALOG, confType = confType) },
      compile = { generated ->
        // `true`, for a different reason than the server's: there is no capability to check
        // because there is no actor — the document came from this shell, and the Kotlin came from
        // `ScreenGenerator` against the component record rather than from anything the document
        // could write. A local `--document` is trusted exactly as much as the file the operator
        // chose to run.
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
    when (val generated = executor.generate(document)) {
      is ScreenGeneratorComposeExportExecutor.Generated.Emitted ->
        DesignLocalLane.Source.Emitted(generated.source, generated.screenName)
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
            runCatching { Base64.getDecoder().decode(image) }
              .fold(
                onSuccess = { DesignLocalLane.Frame.Rendered(it) },
                onFailure = {
                  DesignLocalLane.Frame.NoFrame("the render lane's frame is not valid base64")
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
     * The one catalog target this lane offers.
     *
     * `PlaygroundRunRequest.catalog` has to name something and there is exactly one thing to name:
     * the bundle the caller passed. A design's own catalog id is deliberately not used — it names a
     * catalog on a *server*, and this lane has none.
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
