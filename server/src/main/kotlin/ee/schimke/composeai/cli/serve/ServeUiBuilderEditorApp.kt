package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.uibuilder.protocol.AcceptedOutcomeV1
import ee.schimke.composeai.uibuilder.protocol.DesignAccessActionV1
import ee.schimke.composeai.uibuilder.protocol.DesignDocumentV1
import ee.schimke.composeai.uibuilder.protocol.RejectedOutcomeV1
import ee.schimke.composeai.uibuilder.protocol.RejectionCodeV1
import ee.schimke.composeai.uibuilder.protocol.ServiceErrorCodeV1
import ee.schimke.composeai.uibuilder.service.AuthenticatedUiBuilderActor
import ee.schimke.composeai.uibuilder.service.UiBuilderServiceCall
import ee.schimke.composeai.uibuilder.service.UiBuilderServicePort
import ee.schimke.composeai.uibuilder.service.UiBuilderServiceRequest
import ee.schimke.composeai.uibuilder.service.UiBuilderServiceResponse
import ee.schimke.composeai.uibuilder.service.UiBuilderServiceUpdate
import ee.schimke.composeai.uibuilder.service.UiBuilderSubscriptionCall
import ee.schimke.composeai.uibuilder.service.UiBuilderSubscriptionRejectedException
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * The UI Builder editor as an MCP App over a design this server holds, for Claude and any other
 * host without file resources (compose-ui-builder's `docs/design/UI_BUILDER_MCP_APP_HOST.md`,
 * "Server designs").
 *
 * The resource [RESOURCE_URI] is the web archive's own shell, `mcp-app/ui-builder-mcp-app.html`,
 * with its asset base pointed at the archive this server already serves under its immutable
 * `/ui-builder/v/<bundle>/` prefix. The editor then loads its Wasm from there (CORS-open) and reads
 * and saves the design through the app-only tools in [ServeUiBuilderEditorTools], which the host
 * forwards with the connector's own credential.
 *
 * Registered only when the archive's manifest says `mcpApp >= 2` ([forArchive]), the version of the
 * shell that understands a `{ designId }` tool input. With an older archive nothing here exists:
 * neither the four tools nor the resource.
 */
class ServeUiBuilderEditorApp(
  private val shellFile: File,
  /**
   * The path the unpacked archive is served under on this server, starting and ending with `/`.
   * Asked per read, since the bundle digest that names it is computed lazily.
   */
  private val assetPath: () -> String,
  /** `focused` or `full`, the editor the panel opens in; anything else opens `focused`. */
  private val layout: () -> String = { LAYOUT_FOCUSED },
) {
  fun resourceDescriptor(): JsonObject = buildJsonObject {
    put("uri", RESOURCE_URI)
    put("name", "Compose UI Builder editor")
    put("description", "Edit a UI Builder design held by this server.")
    put("mimeType", MIME_TYPE)
    put("_meta", resourceMeta(origin = null))
  }

  /**
   * The shell with its placeholders filled for [origin], the server's external origin as the host
   * reached it (scheme and authority, no trailing slash).
   */
  fun readResource(origin: String): JsonObject {
    val base = origin.trimEnd('/')
    val html = shellHtml(base)
    return buildJsonObject {
      putJsonArray("contents") {
        addJsonObject {
          put("uri", RESOURCE_URI)
          put("mimeType", MIME_TYPE)
          put("text", html)
          put("_meta", resourceMeta(base))
        }
      }
    }
  }

  /** The asset base the shell is given: absolute, ending in `/`. */
  fun assetBase(origin: String): String {
    val path = assetPath().let { if (it.endsWith("/")) it else "$it/" }
    return origin.trimEnd('/') + (if (path.startsWith("/")) path else "/$path")
  }

  private fun shellHtml(origin: String): String {
    val chosen = layout().takeIf { it == LAYOUT_FOCUSED || it == LAYOUT_FULL } ?: LAYOUT_FOCUSED
    // The device presets are this server's public, CORS-open route, so the sandboxed frame can
    // fetch them and the editor offers device menus. The shell derives its catalog base from this
    // URL's origin, which is the same origin as the assets.
    return shellFile
      .readText()
      .replace(ASSET_BASE_PLACEHOLDER, assetBase(origin))
      .replace(LAYOUT_PLACEHOLDER, chosen)
      .replace(DEVICE_PRESETS_PLACEHOLDER, origin.trimEnd('/') + UI_BUILDER_DEVICE_PRESETS_PATH)
  }

  private fun resourceMeta(origin: String?): JsonObject = buildJsonObject {
    putJsonObject("ui") {
      if (origin != null) {
        putJsonObject("csp") {
          putJsonArray("resourceDomains") { add(origin) }
          putJsonArray("connectDomains") { add(origin) }
        }
      }
      put("preferredDisplayMode", "fullscreen")
    }
    putJsonObject("openai/ui") {
      put("preferredDisplayMode", "fullscreen")
      putJsonArray("availableDisplayModes") { add("fullscreen") }
    }
  }

  companion object {
    const val RESOURCE_URI: String = "ui://compose-ui-builder/editor"
    const val MIME_TYPE: String = "text/html;profile=mcp-app"

    /** The shell's path inside the archive, fixed by the `mcpApp` contract. */
    const val SHELL_PATH: String = "mcp-app/ui-builder-mcp-app.html"

    /** The first `mcpApp` contract whose shell edits a server design. */
    const val MIN_MCP_APP: Int = 2

    const val ASSET_BASE_PLACEHOLDER: String = "__COMPOSE_UI_BUILDER_ASSET_BASE__"
    const val LAYOUT_PLACEHOLDER: String = "__COMPOSE_UI_BUILDER_MCP_APP_LAYOUT__"
    const val DEVICE_PRESETS_PLACEHOLDER: String = "__COMPOSE_UI_BUILDER_DEVICE_PRESETS__"
    const val LAYOUT_FOCUSED: String = "focused"
    const val LAYOUT_FULL: String = "full"

    /**
     * The editor app for the unpacked archive at [dir], or null unless its manifest carries
     * `mcpApp >= `[MIN_MCP_APP] and the shell is packaged.
     */
    fun forArchive(
      dir: File,
      assetPath: () -> String,
      layout: () -> String = { LAYOUT_FOCUSED },
    ): ServeUiBuilderEditorApp? {
      val manifest = ServeUiBuilderEditor.readManifest(dir) ?: return null
      if (manifest.mcpApp < MIN_MCP_APP) return null
      val shell = File(dir, SHELL_PATH)
      if (!shell.isFile) return null
      return ServeUiBuilderEditorApp(shell, assetPath, layout)
    }

    /**
     * The four tools' declarations. [tool] is `ServeCatalogMcp`'s own builder; the `_meta` keys are
     * added here.
     */
    fun declarations(tool: (String, String, String) -> JsonObject): List<JsonObject> =
      listOf(
        withMeta(
          tool(
            ServeUiBuilderEditorTools.EDIT_DESIGN,
            "Open a UI Builder design in the editor, as an app in this conversation, for the " +
              "person to edit by hand. Takes the designId (from ui_builder_list_designs). The " +
              "editor reads and saves the design itself; to change a design yourself use " +
              "ui_builder_apply.",
            """{"type":"object","properties":{"designId":{"type":"string","description":"The design to open."}},"required":["designId"]}""",
          ),
          appOnly = false,
        ),
        withMeta(
          tool(
            ServeUiBuilderEditorTools.EDITOR_READ,
            "The UI Builder editor app's load: the design's document, revision and whether this " +
              "caller may write it. Called by the app, not the model.",
            """{"type":"object","properties":{"designId":{"type":"string"}},"required":["designId"]}""",
          ),
          appOnly = true,
        ),
        withMeta(
          tool(
            ServeUiBuilderEditorTools.EDITOR_WRITE,
            "The UI Builder editor app's save: the whole document at baseRevision. Answers " +
              "saved, conflict, read-only or too-large. Called by the app, not the model.",
            """{"type":"object","properties":{"designId":{"type":"string"},"baseRevision":{"type":"integer","minimum":0},"document":{"type":"object","description":"The whole DesignDocumentV1."}},"required":["designId","baseRevision","document"]}""",
          ),
          appOnly = true,
        ),
        withMeta(
          tool(
            ServeUiBuilderEditorTools.EDITOR_AWAIT,
            "The UI Builder editor app's follow: returns as soon as the design's revision passes " +
              "afterRevision, or after waitSeconds (at most " +
              "${ServeUiBuilderEditorTools.MAX_WAIT_SECONDS}) with changed false. Called by the " +
              "app, not the model.",
            """{"type":"object","properties":{"designId":{"type":"string"},"afterRevision":{"type":"integer","minimum":0},"waitSeconds":{"type":"integer","minimum":0,"maximum":${ServeUiBuilderEditorTools.MAX_WAIT_SECONDS}}},"required":["designId","afterRevision"]}""",
          ),
          appOnly = true,
        ),
      )

    private fun withMeta(tool: JsonObject, appOnly: Boolean): JsonObject {
      val existing = tool["_meta"] as? JsonObject ?: JsonObject(emptyMap())
      val meta = buildJsonObject {
        existing.forEach { (key, value) -> put(key, value) }
        putJsonObject("ui") {
          put("resourceUri", RESOURCE_URI)
          putJsonArray("visibility") {
            if (!appOnly) add("model")
            add("app")
          }
        }
        // Pre-2026-01-26 MCP Apps hosts read the flat key; current hosts read `ui.resourceUri`.
        put("ui/resourceUri", RESOURCE_URI)
      }
      return JsonObject(tool + ("_meta" to meta))
    }
  }
}

/**
 * The four tools of [ServeUiBuilderEditorApp], over the same [UiBuilderServicePort] and actor rule
 * as the rest of the UI-builder surface. Each answers JSON text, which `ServeCatalogMcp` mirrors
 * into `structuredContent`; a refusal that is not one of a save's outcomes is a
 * [McpRequestException], i.e. a tool error.
 */
internal class ServeUiBuilderEditorTools(private val service: UiBuilderServicePort) {

  suspend fun call(
    tool: String,
    args: JsonObject,
    actor: AuthenticatedUiBuilderActor,
    mayWrite: () -> Boolean,
  ): String =
    when (tool) {
      EDIT_DESIGN -> {
        val document = snapshot(args.requiredText("designId"), actor)
        buildJsonObject {
          put("designId", document.id)
          put("title", document.title)
          put("revision", document.revision)
        }
          .toString()
      }
      EDITOR_READ -> {
        val designId = args.requiredText("designId")
        val document = snapshot(designId, actor)
        val writable =
          mayWrite() &&
            service.designActions(actor, designId)?.contains(DesignAccessActionV1.WRITE) == true
        buildJsonObject {
          put("designId", document.id)
          put("title", document.title)
          put("revision", document.revision)
          put("writable", writable)
          put(
            "document",
            UI_BUILDER_JSON.encodeToJsonElement(DesignDocumentV1.serializer(), document),
          )
        }
          .toString()
      }
      EDITOR_WRITE -> write(args, actor, mayWrite).toString()
      EDITOR_AWAIT -> await(args, actor).toString()
      else -> throw McpRequestException("unknown UI-builder editor tool '$tool'")
    }

  private suspend fun snapshot(
    designId: String,
    actor: AuthenticatedUiBuilderActor,
  ): DesignDocumentV1 {
    val response =
      execute(actor, UiBuilderServiceRequest.GetSnapshot(designId = designId, revision = null))
    return when (response) {
      is UiBuilderServiceResponse.Snapshot ->
        service.shapeForReader(actor, response).let {
          (it as? UiBuilderServiceResponse.Snapshot ?: response).snapshot.state.document
        }
      is UiBuilderServiceResponse.Error -> throw McpRequestException(response.error.message)
      else -> throw McpRequestException("no design `$designId` this actor can read")
    }
  }

  /**
   * One save, as `ReplaceDesignDocument`: the whole document at `baseRevision`, an ordinary
   * revision attributed to [actor]. Never the dry-run / elicitation path of
   * [ServeUiBuilderMcp.REPLACE_DESIGN_DOCUMENT], which asks a person to choose; here the person is
   * the one saving.
   */
  private suspend fun write(
    args: JsonObject,
    actor: AuthenticatedUiBuilderActor,
    mayWrite: () -> Boolean,
  ): JsonObject {
    val designId = args.requiredText("designId")
    val baseRevision =
      args["baseRevision"]?.jsonPrimitive?.longOrNull?.takeIf { it >= 0 }
        ?: throw McpRequestException("`baseRevision` is required and must be an integer")
    val documentJson =
      args["document"] as? JsonObject ?: throw McpRequestException("`document` is required")
    val document =
      try {
        UI_BUILDER_JSON.decodeFromJsonElement(DesignDocumentV1.serializer(), documentJson)
      } catch (failure: SerializationException) {
        throw McpRequestException("`document` is not a DesignDocumentV1: ${failure.message}")
      } catch (failure: IllegalArgumentException) {
        throw McpRequestException("`document` is not a DesignDocumentV1: ${failure.message}")
      }
    // A design this actor cannot read is "no design", never "read-only", so a save cannot be used
    // to learn that a private design exists.
    val actions =
      service.designActions(actor, designId)
        ?: throw McpRequestException("no design `$designId` this actor can read")
    if (!mayWrite() || DesignAccessActionV1.WRITE !in actions) return readOnly()
    val response =
      execute(
        actor,
        UiBuilderServiceRequest.ReplaceDesignDocument(
          designId = designId,
          document = document,
          baseRevision = baseRevision,
          operationId = "editor-${UUID.randomUUID()}",
        ),
      )
    return when (response) {
      is UiBuilderServiceResponse.OperationOutcome ->
        when (val outcome = response.outcome) {
          is AcceptedOutcomeV1 ->
            buildJsonObject {
              put("outcome", SAVED)
              put("revision", outcome.committedRevision)
            }
          is RejectedOutcomeV1 ->
            when {
              outcome.code == RejectionCodeV1.REVISION_MISMATCH ->
                buildJsonObject {
                  put("outcome", CONFLICT)
                  put("revision", outcome.currentRevision)
                  put("message", outcome.message)
                }
              outcome.message in TOO_LARGE_REFUSALS ->
                buildJsonObject {
                  put("outcome", TOO_LARGE)
                  put(
                    "maxBytes",
                    ServeUiBuilderStorageLimits.service.maximumSerializedDocumentBytes.toLong(),
                  )
                  put("message", outcome.message)
                }
              // A validation failure is the editor's error notice, with the service's reason.
              else -> throw McpRequestException(outcome.message)
            }
        }
      is UiBuilderServiceResponse.Error ->
        if (response.error.code == ServiceErrorCodeV1.FORBIDDEN) readOnly(response.error.message)
        else throw McpRequestException(response.error.message)
      else -> throw McpRequestException("unexpected reply to a document save")
    }
  }

  /**
   * Wait for the design's revision to pass `afterRevision`, on the same subscription the browser's
   * updates socket uses. Subscribing without a cursor delivers the current snapshot first, so a
   * design already past `afterRevision` answers at once.
   */
  private suspend fun await(args: JsonObject, actor: AuthenticatedUiBuilderActor): JsonObject {
    val designId = args.requiredText("designId")
    val afterRevision =
      args["afterRevision"]?.jsonPrimitive?.longOrNull?.takeIf { it >= 0 }
        ?: throw McpRequestException("`afterRevision` is required and must be an integer")
    val waitSeconds =
      (args["waitSeconds"]?.jsonPrimitive?.longOrNull ?: MAX_WAIT_SECONDS).coerceIn(
        0,
        MAX_WAIT_SECONDS,
      )
    val latest = AtomicLong(afterRevision)
    val changed = CompletableDeferred<Long>()
    val subscription =
      try {
        service.subscribe(UiBuilderSubscriptionCall(actor, designId, afterSequence = null)) { update
          ->
          val revision =
            when (update) {
              is UiBuilderServiceUpdate.Snapshot -> update.snapshot.state.document.revision
              is UiBuilderServiceUpdate.Delta -> update.delta.currentRevision
              else -> null
            } ?: return@subscribe
          latest.accumulateAndGet(revision, ::maxOf)
          if (revision > afterRevision) changed.complete(revision)
        }
      } catch (rejected: UiBuilderSubscriptionRejectedException) {
        throw McpRequestException(rejected.error.message)
      }
    val revision =
      try {
        if (changed.isCompleted) changed.await()
        else
          try {
            withTimeout(waitSeconds * 1000) { changed.await() }
          } catch (_: TimeoutCancellationException) {
            null
          }
      } finally {
        subscription.close()
      }
    return buildJsonObject {
      put("revision", revision ?: latest.get())
      put("changed", revision != null)
    }
  }

  private suspend fun execute(
    actor: AuthenticatedUiBuilderActor,
    request: UiBuilderServiceRequest,
  ): UiBuilderServiceResponse =
    try {
      service.execute(UiBuilderServiceCall(actor, request))
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (failure: Exception) {
      uiBuilderServiceFailure(failure)
    }

  private fun readOnly(
    message: String = "this caller may read the design but not write it",
  ): JsonObject = buildJsonObject {
    put("outcome", READ_ONLY)
    put("message", message)
  }

  private fun JsonObject.requiredText(name: String): String =
    (this[name] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
      ?: throw McpRequestException("`$name` is required")

  companion object {
    const val EDIT_DESIGN: String = "ui_builder_edit_design"
    const val EDITOR_READ: String = "ui_builder_editor_read"
    const val EDITOR_WRITE: String = "ui_builder_editor_write"
    const val EDITOR_AWAIT: String = "ui_builder_editor_await"

    val TOOL_NAMES: List<String> = listOf(EDIT_DESIGN, EDITOR_READ, EDITOR_WRITE, EDITOR_AWAIT)

    /** The three the model never sees (`_meta.ui.visibility: ["app"]`). */
    val APP_ONLY_TOOL_NAMES: List<String> = listOf(EDITOR_READ, EDITOR_WRITE, EDITOR_AWAIT)

    const val MAX_WAIT_SECONDS: Long = 25

    const val SAVED: String = "saved"
    const val CONFLICT: String = "conflict"
    const val READ_ONLY: String = "read-only"
    const val TOO_LARGE: String = "too-large"

    /** The service's document-size refusals, which the editor shows as `too-large`. */
    private val TOO_LARGE_REFUSALS =
      setOf("serialized document byte limit exceeded", "embedded asset byte limit exceeded")

    fun capabilityFor(tool: String): UiBuilderRouteCapability? =
      when (tool) {
        EDIT_DESIGN,
        EDITOR_READ,
        EDITOR_AWAIT -> UiBuilderRouteCapability.READ
        EDITOR_WRITE -> UiBuilderRouteCapability.WRITE
        else -> null
      }
  }
}
