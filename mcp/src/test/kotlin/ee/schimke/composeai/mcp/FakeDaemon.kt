package ee.schimke.composeai.mcp

import ee.schimke.composeai.daemon.client.DaemonClient
import ee.schimke.composeai.daemon.client.DaemonClientFactory
import ee.schimke.composeai.daemon.client.DaemonSpawn
import ee.schimke.composeai.daemon.client.WorkspaceId
import ee.schimke.composeai.daemon.protocol.DaemonLaunchDescriptor
import ee.schimke.composeai.daemon.protocol.DataProductCapability
import ee.schimke.composeai.daemon.protocol.InitializeResult
import ee.schimke.composeai.daemon.protocol.Manifest
import ee.schimke.composeai.daemon.protocol.RenderNowResult
import ee.schimke.composeai.daemon.protocol.ServerCapabilities
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Test-only fake daemon speaking the daemon protocol (PROTOCOL.md) over piped streams:
 * - `initialize` → a stub [InitializeResult] with empty capabilities.
 * - `renderNow(previews)` → `queued = previews` plus a [`renderFinished`][..] per id with a
 *   synthetic png path.
 * - `setVisible` / `setFocus` → recorded for watch-propagation assertions.
 * - `shutdown` → null result; `exit` drains the reader. Tests can also push a
 *   [`discoveryUpdated`][..] on demand.
 */
class FakeDaemon : DaemonSpawn {

  private val mcpToDaemon = PipedOutputStream()
  private val daemonReadIn = PipedInputStream(mcpToDaemon, BUFFER)
  private val daemonToMcp = PipedOutputStream()
  private val mcpReadIn = PipedInputStream(daemonToMcp, BUFFER)

  private val running = AtomicBoolean(true)
  private val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = false
  }

  /** Recorded when the MCP shim issues `setVisible`; tests assert ordering. */
  val visibleSets = java.util.concurrent.LinkedBlockingQueue<List<String>>()
  /** Same for `setFocus`. */
  val focusSets = java.util.concurrent.LinkedBlockingQueue<List<String>>()
  /** `renderNow` invocations the fake observed. */
  val renderRequests = java.util.concurrent.LinkedBlockingQueue<List<String>>()
  /** `fileChanged` notifications the fake observed. */
  val fileChanges = java.util.concurrent.LinkedBlockingQueue<JsonObject>()
  /**
   * `renderNow.overrides` payloads, in lockstep with [renderRequests]. A `CopyOnWriteArrayList`
   * because `LinkedBlockingQueue` rejects nulls; tests poll by index.
   */
  val renderOverrides: MutableList<ee.schimke.composeai.daemon.protocol.PreviewOverrides?> =
    java.util.concurrent.CopyOnWriteArrayList()

  /** Kinds advertised in `initialize.capabilities.dataProducts`; assign before spawning. */
  @Volatile var advertisedDataProducts: List<DataProductCapability> = emptyList()

  /**
   * `PreviewOverrides` field names advertised in `supportedOverrides`; assign before spawning.
   * Empty keeps validation falling open.
   */
  @Volatile var advertisedSupportedOverrides: List<String> = emptyList()

  /** Devices advertised in `knownDevices`; assign before spawning. */
  @Volatile
  var advertisedKnownDevices: List<ee.schimke.composeai.daemon.protocol.KnownDevice> = emptyList()

  /**
   * Recording formats advertised in `recordingFormats`; assign before spawning. Empty makes
   * `record_preview` format validation fall open.
   */
  @Volatile var advertisedRecordingFormats: List<String> = emptyList()

  /**
   * Extensions `extensions/enable` can turn on, by id, with the data products each adds
   * (PROTOCOL.md § 3a). Unlisted ids are `unknown`.
   */
  @Volatile var enableableExtensions: Map<String, List<DataProductCapability>> = emptyMap()

  /** Ids the fake observed across every `extensions/enable` call. */
  val enabledExtensionRequests = java.util.concurrent.LinkedBlockingQueue<List<String>>()

  /** Data extensions advertised in `initialize.capabilities.dataExtensions`. */
  @Volatile
  var advertisedDataExtensions: List<ee.schimke.composeai.daemon.protocol.DataExtensionDescriptor> =
    emptyList()

  /**
   * Handler for `data/fetch`: `(previewId, kind, params, inline)` → [DataFetchOutcome]. Defaults to
   * [DataFetchOutcome.Unknown].
   */
  @Volatile
  var dataFetchHandler:
    (previewId: String, kind: String, params: JsonObject?, inline: Boolean) -> DataFetchOutcome =
    { _, _, _, _ ->
      DataFetchOutcome.Unknown
    }

  /** Recorded `data/subscribe` calls — list of (previewId, kind) tuples. */
  val dataSubscribes = java.util.concurrent.LinkedBlockingQueue<Pair<String, String>>()
  /** Recorded `data/unsubscribe` calls — same shape. */
  val dataUnsubscribes = java.util.concurrent.LinkedBlockingQueue<Pair<String, String>>()

  // Recording (RECORDING.md) fake state for the four-call flow; each call is recorded below.

  /** Recorded `recording/start` calls in arrival order — `(previewId, fps, scale, overrides?)`. */
  data class RecordingStartCall(
    val previewId: String,
    val fps: Int?,
    val scale: Float?,
    val overrides: ee.schimke.composeai.daemon.protocol.PreviewOverrides?,
  )

  val recordingStarts = java.util.concurrent.LinkedBlockingQueue<RecordingStartCall>()

  /** Recorded `recording/script` calls — `(recordingId, events)`. */
  data class RecordingScriptCall(
    val recordingId: String,
    val events: List<ee.schimke.composeai.daemon.protocol.RecordingScriptEvent>,
  )

  val recordingScripts = java.util.concurrent.LinkedBlockingQueue<RecordingScriptCall>()

  /** Recorded `recording/stop` calls — just the recordingId. */
  val recordingStops = java.util.concurrent.LinkedBlockingQueue<String>()

  /** Recorded `recording/encode` calls — `(recordingId, format)`. */
  data class RecordingEncodeCall(
    val recordingId: String,
    val format: ee.schimke.composeai.daemon.protocol.RecordingFormat,
  )

  val recordingEncodes = java.util.concurrent.LinkedBlockingQueue<RecordingEncodeCall>()

  /**
   * Payload returned by the next `recording/encode`, written to a temp file whose path / size /
   * mime are returned. Defaults to the PNG signature so `sizeBytes` is non-zero.
   */
  @Volatile var recordingEncodedBytes: ByteArray = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)

  /** Directory for the encoded file; null uses `java.io.tmpdir`. */
  @Volatile var recordingEncodeDir: java.io.File? = null

  /**
   * Canned `recording/stop` response; defaults match a 30fps × 500ms script (16 frames at 120×60).
   */
  @Volatile
  var recordingStopResult: ee.schimke.composeai.daemon.protocol.RecordingStopResult =
    ee.schimke.composeai.daemon.protocol.RecordingStopResult(
      frameCount = 16,
      durationMs = 500L,
      framesDir = "/tmp/fake-frames",
      frameWidthPx = 120,
      frameHeightPx = 60,
    )

  private val nextRecordingId = java.util.concurrent.atomic.AtomicLong(1)

  /** Captures the `initialize` params, e.g. to assert `options.attachDataProducts`. */
  @Volatile var onInitializeReceived: (JsonObject) -> Unit = {}

  /** Answers `compileSources`; null answers "method not found" like an older daemon. */
  @Volatile
  var onCompileSources:
    ((List<String>) -> ee.schimke.composeai.daemon.protocol.CompileSourcesResult)? =
    null

  /**
   * Outcome the fake's `data/fetch` returns; mirrors `DataProductRegistry.Outcome` without
   * depending on it.
   */
  sealed interface DataFetchOutcome {
    /** Wire-success: the fake returns a `DataFetchResult` with the given fields. */
    data class Ok(
      val kind: String,
      val schemaVersion: Int,
      val payload: JsonElement? = null,
      val path: String? = null,
      val bytes: String? = null,
    ) : DataFetchOutcome

    /** -32020 DataProductUnknown. */
    data object Unknown : DataFetchOutcome

    /** -32021 DataProductNotAvailable. */
    data object NotAvailable : DataFetchOutcome

    /** -32022 DataProductFetchFailed. */
    data class FetchFailed(val message: String) : DataFetchOutcome

    /** -32023 DataProductBudgetExceeded. */
    data object BudgetExceeded : DataFetchOutcome
  }

  /**
   * When set, auto-emit `renderFinished` for each id in a `renderNow` whose path the lambda
   * returns, avoiding a poll-and-emit thread in tests.
   */
  @Volatile var autoRenderPngPath: ((previewId: String) -> String?)? = null

  /**
   * With [autoRenderPngPath], sets the auto-emitted `unchanged` flag; `null` omits it. Used by the
   * freshness-sampling tests.
   */
  @Volatile var autoRenderUnchanged: ((previewId: String) -> Boolean?)? = null

  /** With [autoRenderPngPath], attaches the returned element as `workTrace`; `null` omits it. */
  @Volatile var autoRenderWorkTrace: ((previewId: String) -> JsonElement?)? = null

  /**
   * Rejects a preview's `renderNow` when it returns a reason, like the daemon's `coalesced:`
   * rejection: listed in `rejected`, with no `renderFinished`/`renderFailed` following.
   */
  @Volatile
  var rejectRenderNow:
    ((
      previewId: String,
      overrides: ee.schimke.composeai.daemon.protocol.PreviewOverrides?,
    ) -> String?)? =
    null

  /**
   * Path returned as `InitializeResult.manifest.path`, which the supervisor caches for the manifest
   * poller.
   */
  @Volatile var advertisedManifestPath: String = ""

  private lateinit var _client: DaemonClient

  override val client: DaemonClient
    get() = _client

  init {
    Thread({ runDaemonReader() }, "fake-daemon-reader").apply { isDaemon = true }.start()
  }

  override fun client(
    onNotification: (method: String, params: JsonObject?) -> Unit,
    onClose: () -> Unit,
  ): DaemonClient {
    _client =
      DaemonClient(
        input = mcpReadIn,
        output = mcpToDaemon,
        onNotification = onNotification,
        onClose = onClose,
        threadName = "mcp-fake-daemon-client",
      )
    return _client
  }

  override fun shutdown() {
    if (!running.compareAndSet(true, false)) return
    runCatching { daemonToMcp.close() }
    runCatching { daemonReadIn.close() }
  }

  /**
   * Pushes a `discoveryUpdated` adding one preview. [functionName] defaults to the last id segment
   * but can differ to model a variant preview; sent under the wire key `functionName`.
   */
  fun emitDiscovery(
    previewId: String,
    displayName: String = previewId,
    sourceFile: String? = null,
    functionName: String = previewId.substringAfterLast('.'),
    bodyLine: Int? = null,
  ) {
    val params = buildJsonObject {
      putJsonArray("added") {
        add(
          buildJsonObject {
            put("id", previewId)
            put("className", previewId.substringBeforeLast('.'))
            put("functionName", functionName)
            put("displayName", displayName)
            if (sourceFile != null) put("sourceFile", sourceFile)
            if (bodyLine != null) put("bodyLine", bodyLine)
          }
        )
      }
      putJsonArray("removed") {}
      putJsonArray("changed") {}
      put("totalPreviews", 1)
    }
    sendNotification("discoveryUpdated", params)
  }

  /**
   * Pushes a `discoveryUpdated` removing [previewIds], as incremental discovery does after a failed
   * compile.
   */
  fun emitRemoved(vararg previewIds: String) {
    val params = buildJsonObject {
      putJsonArray("added") {}
      putJsonArray("removed") { previewIds.forEach { add(JsonPrimitive(it)) } }
      putJsonArray("changed") {}
      put("totalPreviews", 0)
    }
    sendNotification("discoveryUpdated", params)
  }

  /**
   * Pushes `classpathDirty` (at most once per lifetime, PROTOCOL.md § 6); treat the daemon as
   * dying.
   */
  fun emitClasspathDirty(
    reason: String = "fingerprintMismatch",
    detail: String = "test-driven classpathDirty",
  ) {
    val params = buildJsonObject {
      put("reason", reason)
      put("detail", detail)
    }
    sendNotification("classpathDirty", params)
  }

  /** One auto-emitted `renderFinished`, captured when its `renderNow` arrived. */
  private data class AutoRender(
    val previewId: String,
    val pngPath: String,
    val unchanged: Boolean?,
    val workTrace: JsonElement?,
  )

  /** Pushes a `renderFinished` notification. Returns the synthetic pngPath emitted. */
  fun emitRenderFinished(
    previewId: String,
    pngPath: String,
    unchanged: Boolean? = null,
    workTrace: JsonElement? = null,
  ): String {
    val params = buildJsonObject {
      put("id", previewId)
      put("pngPath", pngPath)
      put("tookMs", 50L)
      if (unchanged != null) put("unchanged", unchanged)
      if (workTrace != null) put("workTrace", workTrace)
    }
    sendNotification("renderFinished", params)
    return pngPath
  }

  /**
   * Pushes `renderFinished` with [attachments] under `dataProducts`, each `(kind, schemaVersion,
   * payload?, path?)` (DATA-PRODUCTS.md), for `dataProductCache` tests.
   */
  fun emitRenderFinishedWithDataProducts(
    previewId: String,
    pngPath: String,
    attachments: List<JsonObject>,
  ): String {
    val params = buildJsonObject {
      put("id", previewId)
      put("pngPath", pngPath)
      put("tookMs", 50L)
      putJsonArray("dataProducts") { attachments.forEach { add(it) } }
    }
    sendNotification("renderFinished", params)
    return pngPath
  }

  // Daemon-side reader: framed JSON-RPC from the MCP shim.

  private fun runDaemonReader() {
    try {
      while (running.get()) {
        val frame = readFrame(daemonReadIn) ?: return
        val obj = json.parseToJsonElement(frame.toString(Charsets.UTF_8)).jsonObject
        val responseId = obj["id"]?.jsonPrimitive?.long
        val method = obj["method"]?.jsonPrimitive?.contentOrNull
        val params = obj["params"] as? JsonObject
        if (responseId != null && method != null) {
          handleRequest(responseId, method, params)
        } else if (method != null) {
          handleNotification(method, params)
        }
      }
    } catch (_: IOException) {
      // EOF
    }
  }

  private fun handleRequest(id: Long, method: String, params: JsonObject?) {
    when (method) {
      "initialize" -> {
        if (params != null) runCatching { onInitializeReceived(params) }
        val result =
          InitializeResult(
            protocolVersion = 2,
            daemonVersion = "fake",
            pid = 0,
            capabilities =
              ServerCapabilities(
                incrementalDiscovery = true,
                sandboxRecycle = false,
                leakDetection = emptyList(),
                dataProducts = advertisedDataProducts,
                dataExtensions = advertisedDataExtensions,
                knownDevices = advertisedKnownDevices,
                supportedOverrides = advertisedSupportedOverrides,
                recordingFormats = advertisedRecordingFormats,
              ),
            classpathFingerprint = "fake-fingerprint",
            manifest = Manifest(path = advertisedManifestPath, previewCount = 0),
          )
        sendResponse(id, json.encodeToJsonElement(InitializeResult.serializer(), result))
      }
      "renderNow" -> {
        val previews =
          (params?.get("previews") as? kotlinx.serialization.json.JsonArray)?.mapNotNull {
            it.jsonPrimitive.contentOrNull
          } ?: emptyList()
        val overrides =
          params
            ?.get("overrides")
            ?.takeUnless { it is kotlinx.serialization.json.JsonNull }
            ?.let {
              json.decodeFromJsonElement(
                ee.schimke.composeai.daemon.protocol.PreviewOverrides.serializer(),
                it,
              )
            }
        // Record overrides before offering on `renderRequests`, since tests poll that queue then
        // read `renderOverrides`.
        renderOverrides.add(overrides)
        // Decide the auto-emitted outcome before offering on `renderRequests`, so a test that
        // reassigns [autoRenderUnchanged] after polling sees this request answered with the lambda
        // set when it was made.
        val rejected = previews.mapNotNull { pid ->
          rejectRenderNow?.invoke(pid, overrides)?.let {
            ee.schimke.composeai.daemon.protocol.RejectedRender(pid, it)
          }
        }
        val rejectedIds = rejected.map { it.id }.toSet()
        val finished =
          autoRenderPngPath?.let { provider ->
            previews
              .filterNot { it in rejectedIds }
              .mapNotNull { pid ->
                provider(pid)?.let { path ->
                  AutoRender(
                    pid,
                    path,
                    autoRenderUnchanged?.invoke(pid),
                    autoRenderWorkTrace?.invoke(pid),
                  )
                }
              }
          } ?: emptyList()
        renderRequests.offer(previews)
        val result =
          RenderNowResult(queued = previews.filterNot { it in rejectedIds }, rejected = rejected)
        sendResponse(id, json.encodeToJsonElement(RenderNowResult.serializer(), result))
        // Auto-emit renderFinished after the response, matching a real backend's ordering.
        finished.forEach { (pid, path, unchanged, workTrace) ->
          emitRenderFinished(pid, path, unchanged, workTrace)
        }
      }
      "shutdown" -> {
        sendResponse(id, kotlinx.serialization.json.JsonNull)
      }
      "history/list" -> {
        // Echo `historyEntries` in the wire shape; filtering and cursors are stubbed.
        val payload = buildJsonObject {
          putJsonArray("entries") { historyEntries.forEach { add(it) } }
          put("totalCount", historyEntries.size)
        }
        sendResponse(id, payload)
      }
      "history/read" -> {
        val entryId = params?.get("id")?.jsonPrimitive?.contentOrNull
        val entry = historyEntries.firstOrNull { it["id"]?.jsonPrimitive?.contentOrNull == entryId }
        if (entry == null) {
          sendError(id, -32010, "HistoryEntryNotFound: $entryId")
        } else {
          val inline = params?.get("inline")?.jsonPrimitive?.contentOrNull == "true"
          val pngPath = entry["pngPath"]?.jsonPrimitive?.contentOrNull ?: "/tmp/missing.png"
          val payload = buildJsonObject {
            put("entry", entry)
            put("pngPath", pngPath)
            if (inline) {
              val bytes =
                historyInlineBytes[entryId] ?: byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47)
              put("pngBytes", java.util.Base64.getEncoder().encodeToString(bytes))
            }
          }
          sendResponse(id, payload)
        }
      }
      "history/diff" -> {
        val from = params?.get("from")?.jsonPrimitive?.contentOrNull
        val to = params?.get("to")?.jsonPrimitive?.contentOrNull
        val fromEntry = historyEntries.firstOrNull {
          it["id"]?.jsonPrimitive?.contentOrNull == from
        }
        val toEntry = historyEntries.firstOrNull { it["id"]?.jsonPrimitive?.contentOrNull == to }
        if (fromEntry == null || toEntry == null) {
          sendError(id, -32010, "HistoryEntryNotFound: from=$from to=$to")
        } else {
          val payload = buildJsonObject {
            put(
              "pngHashChanged",
              fromEntry["pngHash"]?.jsonPrimitive?.contentOrNull !=
                toEntry["pngHash"]?.jsonPrimitive?.contentOrNull,
            )
            put("fromMetadata", fromEntry)
            put("toMetadata", toEntry)
          }
          sendResponse(id, payload)
        }
      }
      "data/fetch" -> {
        val previewId = params?.get("previewId")?.jsonPrimitive?.contentOrNull ?: ""
        val kind = params?.get("kind")?.jsonPrimitive?.contentOrNull ?: ""
        val perKindParams = params?.get("params") as? JsonObject
        // The wire shape encodes booleans as actual JSON booleans, but kotlinx serialises a
        // `Boolean` as a primitive that prints as "true" / "false"; tolerate both forms.
        val inline =
          when (val raw = params?.get("inline")?.jsonPrimitive?.contentOrNull) {
            null -> false
            else -> raw == "true"
          }
        when (val outcome = dataFetchHandler(previewId, kind, perKindParams, inline)) {
          is DataFetchOutcome.Ok -> {
            val payload = buildJsonObject {
              put("kind", outcome.kind)
              put("schemaVersion", outcome.schemaVersion)
              if (outcome.payload != null) put("payload", outcome.payload)
              if (outcome.path != null) put("path", outcome.path)
              if (outcome.bytes != null) put("bytes", outcome.bytes)
            }
            sendResponse(id, payload)
          }
          DataFetchOutcome.Unknown ->
            sendError(id, -32020, "DataProductUnknown: kind not advertised: $kind")
          DataFetchOutcome.NotAvailable ->
            sendError(id, -32021, "DataProductNotAvailable: $previewId has no render available")
          is DataFetchOutcome.FetchFailed -> sendError(id, -32022, outcome.message)
          DataFetchOutcome.BudgetExceeded ->
            sendError(id, -32023, "DataProductBudgetExceeded for kind $kind")
        }
      }
      "data/subscribe",
      "data/unsubscribe" -> {
        val previewId = params?.get("previewId")?.jsonPrimitive?.contentOrNull ?: ""
        val kind = params?.get("kind")?.jsonPrimitive?.contentOrNull ?: ""
        // Validate the kind is advertised and `attachable`, like the real handler.
        val capability = advertisedDataProducts.firstOrNull { it.kind == kind }
        if (capability == null || !capability.attachable) {
          sendError(
            id,
            -32020,
            if (capability == null) "DataProductUnknown: kind not advertised: $kind"
            else "DataProductUnknown: kind '$kind' is not attachable",
          )
        } else {
          if (method == "data/subscribe") dataSubscribes.offer(previewId to kind)
          else dataUnsubscribes.offer(previewId to kind)
          sendResponse(id, buildJsonObject { put("ok", true) })
        }
      }
      "recording/start" -> {
        val previewId = params?.get("previewId")?.jsonPrimitive?.contentOrNull ?: ""
        val fps = params?.get("fps")?.jsonPrimitive?.contentOrNull?.toIntOrNull()
        val scale = params?.get("scale")?.jsonPrimitive?.contentOrNull?.toFloatOrNull()
        val overrides =
          params
            ?.get("overrides")
            ?.takeUnless { it is kotlinx.serialization.json.JsonNull }
            ?.let {
              json.decodeFromJsonElement(
                ee.schimke.composeai.daemon.protocol.PreviewOverrides.serializer(),
                it,
              )
            }
        recordingStarts.offer(
          RecordingStartCall(previewId = previewId, fps = fps, scale = scale, overrides = overrides)
        )
        val recordingId = "fake-rec-${nextRecordingId.getAndIncrement()}"
        sendResponse(
          id,
          json.encodeToJsonElement(
            ee.schimke.composeai.daemon.protocol.RecordingStartResult.serializer(),
            ee.schimke.composeai.daemon.protocol.RecordingStartResult(recordingId = recordingId),
          ),
        )
      }
      "recording/stop" -> {
        val recordingId = params?.get("recordingId")?.jsonPrimitive?.contentOrNull ?: ""
        recordingStops.offer(recordingId)
        sendResponse(
          id,
          json.encodeToJsonElement(
            ee.schimke.composeai.daemon.protocol.RecordingStopResult.serializer(),
            recordingStopResult,
          ),
        )
      }
      "recording/encode" -> {
        val recordingId = params?.get("recordingId")?.jsonPrimitive?.contentOrNull ?: ""
        val format =
          when (params?.get("format")?.jsonPrimitive?.contentOrNull) {
            "gif" -> ee.schimke.composeai.daemon.protocol.RecordingFormat.GIF
            "mp4" -> ee.schimke.composeai.daemon.protocol.RecordingFormat.MP4
            "webm" -> ee.schimke.composeai.daemon.protocol.RecordingFormat.WEBM
            null,
            "apng" -> ee.schimke.composeai.daemon.protocol.RecordingFormat.APNG
            else -> ee.schimke.composeai.daemon.protocol.RecordingFormat.APNG
          }
        recordingEncodes.offer(RecordingEncodeCall(recordingId, format))
        val (extension, mime) =
          when (format) {
            ee.schimke.composeai.daemon.protocol.RecordingFormat.APNG -> "apng" to "image/apng"
            ee.schimke.composeai.daemon.protocol.RecordingFormat.GIF -> "gif" to "image/gif"
            ee.schimke.composeai.daemon.protocol.RecordingFormat.MP4 -> "mp4" to "video/mp4"
            ee.schimke.composeai.daemon.protocol.RecordingFormat.WEBM -> "webm" to "video/webm"
          }
        // Materialise the canned bytes onto disk so DaemonMcpServer's `Files.readAllBytes` works.
        val dir = recordingEncodeDir ?: java.io.File(System.getProperty("java.io.tmpdir"))
        dir.mkdirs()
        val out = java.io.File(dir, "$recordingId.$extension")
        out.writeBytes(recordingEncodedBytes)
        sendResponse(
          id,
          json.encodeToJsonElement(
            ee.schimke.composeai.daemon.protocol.RecordingEncodeResult.serializer(),
            ee.schimke.composeai.daemon.protocol.RecordingEncodeResult(
              videoPath = out.absolutePath,
              mimeType = mime,
              sizeBytes = out.length(),
            ),
          ),
        )
      }
      "extensions/enable" -> {
        val ids =
          (params?.get("ids") as? kotlinx.serialization.json.JsonArray)
            ?.map { it.jsonPrimitive.content }
            .orEmpty()
        enabledExtensionRequests.add(ids)
        val (known, unknown) = ids.partition { it in enableableExtensions }
        val added = known.flatMap { enableableExtensions.getValue(it) }
        advertisedDataProducts =
          advertisedDataProducts +
            added.filter { cap -> advertisedDataProducts.none { it.kind == cap.kind } }
        sendResponse(
          id,
          json.encodeToJsonElement(
            ee.schimke.composeai.daemon.protocol.ExtensionsEnableResult.serializer(),
            ee.schimke.composeai.daemon.protocol.ExtensionsEnableResult.Builder()
              .apply {
                newlyEnabled = known
                this.unknown = unknown
                dataProducts = advertisedDataProducts
                dataExtensions = advertisedDataExtensions
              }
              .build(),
          ),
        )
      }
      "compileSources" -> {
        val handler = onCompileSources
        if (handler == null) {
          // A daemon without the in-process compiler, as before compileSources existed.
          sendError(id, -32601, "method not found: $method")
        } else {
          val sources =
            (params?.get("sources") as? kotlinx.serialization.json.JsonArray)
              ?.map { it.jsonPrimitive.content }
              .orEmpty()
          sendResponse(
            id,
            json.encodeToJsonElement(
              ee.schimke.composeai.daemon.protocol.CompileSourcesResult.serializer(),
              handler(sources),
            ),
          )
        }
      }
      else -> {
        // Unknown methods: error response so the client doesn't hang.
        sendError(id, -32601, "method not found: $method")
      }
    }
  }

  /** Test helper — preload `history/list`/`history/read`/`history/diff` results. */
  private val historyEntries: MutableList<JsonObject> = java.util.concurrent.CopyOnWriteArrayList()
  private val historyInlineBytes: MutableMap<String, ByteArray> =
    java.util.concurrent.ConcurrentHashMap()

  fun setHistory(entries: List<JsonObject>) {
    historyEntries.clear()
    historyEntries.addAll(entries)
  }

  fun setHistoryInlineBytes(entryId: String, bytes: ByteArray) {
    historyInlineBytes[entryId] = bytes
  }

  /** Pushes a `historyAdded` notification carrying [entry]. */
  fun emitHistoryAdded(entry: JsonObject) {
    val params = buildJsonObject { put("entry", entry) }
    sendNotification("historyAdded", params)
  }

  private fun handleNotification(method: String, params: JsonObject?) {
    when (method) {
      "initialized" -> {}
      "fileChanged" -> {
        if (params != null) fileChanges.offer(params)
      }
      "setVisible" -> {
        val ids =
          (params?.get("ids") as? kotlinx.serialization.json.JsonArray)?.mapNotNull {
            it.jsonPrimitive.contentOrNull
          } ?: emptyList()
        visibleSets.offer(ids)
      }
      "setFocus" -> {
        val ids =
          (params?.get("ids") as? kotlinx.serialization.json.JsonArray)?.mapNotNull {
            it.jsonPrimitive.contentOrNull
          } ?: emptyList()
        focusSets.offer(ids)
      }
      "recording/script" -> {
        val recordingId = params?.get("recordingId")?.jsonPrimitive?.contentOrNull ?: ""
        val eventsArr = params?.get("events") as? kotlinx.serialization.json.JsonArray
        val events =
          eventsArr?.map {
            json.decodeFromJsonElement(
              ee.schimke.composeai.daemon.protocol.RecordingScriptEvent.serializer(),
              it,
            )
          } ?: emptyList()
        recordingScripts.offer(RecordingScriptCall(recordingId, events))
      }
      "exit" -> running.set(false)
      else -> {}
    }
  }

  private fun sendResponse(id: Long, result: JsonElement) {
    val payload = buildJsonObject {
      put("jsonrpc", "2.0")
      put("id", id)
      put("result", result)
    }
    sendFrame(payload.toString())
  }

  private fun sendError(id: Long, code: Int, message: String) {
    val payload = buildJsonObject {
      put("jsonrpc", "2.0")
      put("id", id)
      putJsonObject("error") {
        put("code", code)
        put("message", message)
      }
    }
    sendFrame(payload.toString())
  }

  private fun sendNotification(method: String, params: JsonElement) {
    val payload = buildJsonObject {
      put("jsonrpc", "2.0")
      put("method", method)
      put("params", params)
    }
    sendFrame(payload.toString())
  }

  private fun sendFrame(jsonText: String) {
    val bytes = jsonText.toByteArray(Charsets.UTF_8)
    val header = "Content-Length: ${bytes.size}\r\n\r\n".toByteArray(Charsets.US_ASCII)
    synchronized(daemonToMcp) {
      daemonToMcp.write(header)
      daemonToMcp.write(bytes)
      daemonToMcp.flush()
    }
  }

  private fun readFrame(input: InputStream): ByteArray? {
    var contentLength = -1
    val buf = ByteArrayOutputStream(64)
    var sawAny = false
    while (true) {
      val line = readHeaderLine(input, buf) ?: return if (sawAny) null else null
      sawAny = true
      if (line.isEmpty()) break
      val colon = line.indexOf(':')
      if (colon <= 0) error("malformed header line: '$line'")
      if (line.substring(0, colon).trim().equals("Content-Length", ignoreCase = true)) {
        contentLength = line.substring(colon + 1).trim().toInt()
      }
    }
    if (contentLength < 0) error("missing Content-Length")
    val payload = ByteArray(contentLength)
    var off = 0
    while (off < contentLength) {
      val n = input.read(payload, off, contentLength - off)
      if (n < 0) return null
      off += n
    }
    return payload
  }

  private fun readHeaderLine(input: InputStream, buf: ByteArrayOutputStream): String? {
    buf.reset()
    while (true) {
      val b = input.read()
      if (b < 0) return if (buf.size() == 0) null else buf.toString(Charsets.US_ASCII.name())
      if (b == '\n'.code) {
        val bytes = buf.toByteArray()
        val end =
          if (bytes.isNotEmpty() && bytes.last() == '\r'.code.toByte()) bytes.size - 1
          else bytes.size
        return String(bytes, 0, end, Charsets.US_ASCII)
      }
      buf.write(b)
    }
  }

  companion object {
    private const val BUFFER = 64 * 1024
  }
}

/** Test [DaemonClientFactory] that always returns a [FakeDaemon]. */
class FakeDaemonClientFactory : DaemonClientFactory {
  /** Map of (workspaceId, modulePath) → most-recent fake. */
  val daemons: MutableMap<Pair<WorkspaceId, String>, FakeDaemon> = mutableMapOf()

  /**
   * Every fake spawned by this factory, in order (e.g. the `classpathDirty` replacement lands at
   * index 1).
   */
  val spawnHistory: MutableList<FakeDaemon> = java.util.concurrent.CopyOnWriteArrayList()

  /**
   * Descriptors handed to [spawn], parallel to [spawnHistory], to assert the injected
   * `composeai.daemon.sandboxCount` (SANDBOX-POOL.md).
   */
  val spawnDescriptors: MutableList<DaemonLaunchDescriptor> =
    java.util.concurrent.CopyOnWriteArrayList()

  /**
   * Pre-`initialize` hook run inside [spawn], for per-spawn state that must exist before the
   * handshake.
   */
  @Volatile var daemonConfigurer: (FakeDaemon) -> Unit = {}

  override fun spawn(workspaceId: WorkspaceId, descriptor: DaemonLaunchDescriptor): DaemonSpawn {
    val daemon = FakeDaemon()
    runCatching { daemonConfigurer(daemon) }
    synchronized(this) {
      daemons[workspaceId to descriptor.modulePath] = daemon
      spawnHistory.add(daemon)
      spawnDescriptors.add(descriptor)
    }
    return daemon
  }
}

/** Test [DescriptorProvider] returning a stub descriptor for any module. */
class FakeDescriptorProvider : DescriptorProvider {
  override fun descriptorFor(
    project: RegisteredProject,
    modulePath: String,
  ): DaemonLaunchDescriptor =
    DaemonLaunchDescriptor(
      schemaVersion = 1,
      modulePath = modulePath,
      variant = "debug",
      enabled = true,
      mainClass = "fake.Main",
      classpath = emptyList(),
      jvmArgs = emptyList(),
      systemProperties = emptyMap(),
      workingDirectory = project.path.absolutePath,
      manifestPath = "",
    )
}
