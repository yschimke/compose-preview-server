package ee.schimke.composeai.mcp

import ee.schimke.composeai.mcp.protocol.CallToolResult
import ee.schimke.composeai.mcp.protocol.ContentBlock
import ee.schimke.composeai.mcp.protocol.ToolDef
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.ServerSession
import io.modelcontextprotocol.kotlin.sdk.server.StdioServerTransport
import io.modelcontextprotocol.kotlin.sdk.shared.RequestOptions
import io.modelcontextprotocol.kotlin.sdk.types.BlobResourceContents
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.ClientCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.ContentBlock as SdkContentBlock
import io.modelcontextprotocol.kotlin.sdk.types.ElicitRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.ElicitResult
import io.modelcontextprotocol.kotlin.sdk.types.EmbeddedResource
import io.modelcontextprotocol.kotlin.sdk.types.EmptyResult
import io.modelcontextprotocol.kotlin.sdk.types.GetPromptRequest
import io.modelcontextprotocol.kotlin.sdk.types.Icon
import io.modelcontextprotocol.kotlin.sdk.types.ImageContent
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.InitializeResult
import io.modelcontextprotocol.kotlin.sdk.types.ListPromptsRequest
import io.modelcontextprotocol.kotlin.sdk.types.ListPromptsResult
import io.modelcontextprotocol.kotlin.sdk.types.ListResourcesRequest
import io.modelcontextprotocol.kotlin.sdk.types.ListResourcesResult
import io.modelcontextprotocol.kotlin.sdk.types.ListToolsRequest
import io.modelcontextprotocol.kotlin.sdk.types.ListToolsResult
import io.modelcontextprotocol.kotlin.sdk.types.McpException
import io.modelcontextprotocol.kotlin.sdk.types.Method
import io.modelcontextprotocol.kotlin.sdk.types.ProgressNotification
import io.modelcontextprotocol.kotlin.sdk.types.ProgressNotificationParams
import io.modelcontextprotocol.kotlin.sdk.types.RPCError
import io.modelcontextprotocol.kotlin.sdk.types.ReadResourceRequest
import io.modelcontextprotocol.kotlin.sdk.types.ReadResourceResult
import io.modelcontextprotocol.kotlin.sdk.types.RequestId
import io.modelcontextprotocol.kotlin.sdk.types.Resource
import io.modelcontextprotocol.kotlin.sdk.types.ResourceContents
import io.modelcontextprotocol.kotlin.sdk.types.ResourceLink
import io.modelcontextprotocol.kotlin.sdk.types.ResourceUpdatedNotification
import io.modelcontextprotocol.kotlin.sdk.types.ResourceUpdatedNotificationParams
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.SubscribeRequest
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.TextResourceContents
import io.modelcontextprotocol.kotlin.sdk.types.Tool
import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import io.modelcontextprotocol.kotlin.sdk.types.UnsubscribeRequest
import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Transport-agnostic session surface; the stdio implementation comes from the Kotlin MCP SDK. */
interface Session {
  /** Sends `notifications/resources/updated` for [uri]. */
  fun notifyResourceUpdated(uri: String)

  /** Sends `notifications/resources/list_changed`. */
  fun notifyResourceListChanged()

  /** Sends `notifications/tools/list_changed`. */
  fun notifyToolListChanged()

  /**
   * Sends `notifications/progress` for [token]. No-op when the client didn't opt in or sent a token
   * shape the SDK cannot represent.
   */
  fun notifyProgress(
    token: JsonElement,
    progress: Double,
    total: Double? = null,
    message: String? = null,
  )
}

/** SDK-backed stdio session with the same lifecycle shape the old hand-rolled session exposed. */
class McpSession(
  private val serverInfo: Implementation,
  private val options: ServerOptions,
  private val input: InputStream,
  private val output: OutputStream,
  private val configure: (ServerSession) -> Unit,
  private val onClose: () -> Unit,
  /** `initialize` instructions for the client's `clientInfo.name`; null sends none. */
  private val instructions: (clientName: String?) -> String? = { null },
) : Closeable, Session {
  private val closed = CompletableFuture<Unit>()
  @Volatile private var sdkSession: ServerSession? = null
  private val thread =
    Thread(
        {
          try {
            runBlocking(Dispatchers.IO) {
              // Build the session and install handlers BEFORE connecting the transport:
              // `Server.createSession` starts draining messages inside the call, so a pipelined
              // `tools/call` could hit the SDK's default handler (empty registry → "Tool not
              // found"). The ServerSession constructor wires initialize/ping/logging itself; its
              // third argument is the initialize `instructions`, replaced per-client in
              // [wrapInitialize].
              val session = ServerSession(serverInfo, options, instructions(null))
              sdkSession = session
              wrapInitialize(session)
              session.onClose {
                closed.complete(Unit)
                onClose()
              }
              configure(session)
              // RawParamsTransport lets `openai/elicitation/create` carry its untyped schema.
              session.connect(
                RawParamsTransport(
                  StdioServerTransport(input.asSource().buffered(), output.asSink().buffered()) {}
                )
              )
              while (!closed.isDone) {
                delay(100)
              }
            }
          } catch (t: Throwable) {
            System.err.println("compose-preview-mcp: SDK stdio session failed: ${t.message}")
            t.printStackTrace(System.err)
          } finally {
            onClose()
          }
        },
        "mcp-sdk-stdio-session",
      )
      .apply { isDaemon = true }

  fun start() {
    thread.start()
  }

  /**
   * Routes `initialize` through the SDK's handler (which records client capabilities), then logs
   * the client and swaps in that client's instructions — the SDK only takes them once, at
   * construction.
   */
  private fun wrapInitialize(session: ServerSession) {
    val method = Method.Defined.Initialize.value
    val builtIn = session.requestHandlers[method] ?: return
    session.removeRequestHandler(Method.Defined.Initialize)
    val fallback = session.fallbackRequestHandler
    session.fallbackRequestHandler = { request, extra ->
      if (request.method == method) {
        val result = builtIn(request, extra)
        val client = session.clientVersion
        System.err.println(
          "compose-preview-mcp: client ${client?.name ?: "<unknown>"} ${client?.version ?: ""}"
            .trimEnd()
        )
        if (result is InitializeResult) result.copy(instructions = instructions(client?.name))
        else result
      } else {
        fallback?.invoke(request, extra)
          ?: throw McpException(
            RPCError.ErrorCode.METHOD_NOT_FOUND,
            "Method not found: ${request.method}",
          )
      }
    }
  }

  fun awaitClose() {
    thread.join()
  }

  override fun close() {
    runBlocking { sdkSession?.close() }
    closed.complete(Unit)
    thread.join(2_000)
  }

  override fun notifyResourceUpdated(uri: String) {
    val session = sdkSession ?: return
    runBlocking {
      session.sendResourceUpdated(
        ResourceUpdatedNotification(ResourceUpdatedNotificationParams(uri = uri))
      )
    }
  }

  override fun notifyResourceListChanged() {
    val session = sdkSession ?: return
    runBlocking { session.sendResourceListChanged() }
  }

  override fun notifyToolListChanged() {
    val session = sdkSession ?: return
    runBlocking { session.sendToolListChanged() }
  }

  override fun notifyProgress(
    token: JsonElement,
    progress: Double,
    total: Double?,
    message: String?,
  ) {
    val progressToken = token.toRequestId() ?: return
    val session = sdkSession ?: return
    runBlocking {
      session.notification(
        ProgressNotification(
          ProgressNotificationParams(
            progressToken = progressToken,
            progress = progress,
            total = total,
            message = message,
          )
        ),
        progressToken,
      )
    }
  }

  /**
   * Ask through a typed MCP form when the client supports form elicitation (`elicitation.form`, or
   * a bare pre-2025-11 `elicitation: {}`). [FormElicitation.Unsupported] means use the text
   * fallback; a timeout is reported separately so the caller can say the question went unanswered.
   */
  suspend fun elicitForm(
    message: String,
    requestedSchema: JsonObject,
    timeoutMs: Long = DEFAULT_ELICITATION_TIMEOUT_MS,
  ): FormElicitation {
    val session = sdkSession ?: return FormElicitation.Unsupported
    if (!supportsFormElicitation(session.clientCapabilities?.elicitation)) {
      return FormElicitation.Unsupported
    }
    val schema = Json {
      ignoreUnknownKeys = true
    }
      .decodeFromJsonElement<ElicitRequestParams.RequestedSchema>(requestedSchema)
    return awaitElicitation(timeoutMs) {
      session.createElicitation(message, schema, RequestOptions(timeout = timeoutMs.milliseconds))
    }
  }

  /** True when the client declared OpenAI form elicitation ([OpenAiForms.supportsForms]). */
  val supportsOpenAiForms: Boolean
    get() = OpenAiForms.supportsForms(sdkSession?.clientCapabilities)

  /**
   * Ask through an OpenAI extended form (`openai/elicitation/create`). Same outcomes as
   * [elicitForm].
   */
  suspend fun elicitOpenAiForm(
    message: String,
    requestedSchema: JsonObject,
    timeoutMs: Long = DEFAULT_ELICITATION_TIMEOUT_MS,
  ): FormElicitation = OpenAiForms.elicit(sdkSession, message, requestedSchema, timeoutMs)

  /**
   * True when the client declared the MCP Apps extension (`io.modelcontextprotocol/ui`, under
   * `capabilities.extensions`, or `experimental` for earlier hosts).
   */
  val supportsMcpApps: Boolean
    get() {
      val capabilities = sdkSession?.clientCapabilities ?: return false
      return capabilities.extensions?.containsKey(MCP_APPS_EXTENSION) == true ||
        capabilities.experimental?.containsKey(MCP_APPS_EXTENSION) == true
    }

  /** The client's `clientInfo.name` from `initialize`, or null before the handshake. */
  val clientName: String?
    get() = sdkSession?.clientVersion?.name

  /**
   * Local directories the client offers as MCP roots. Empty when it declared no `roots` capability,
   * timed out, or offered only non-`file:` roots.
   */
  suspend fun rootDirectories(timeoutMs: Long = ROOTS_TIMEOUT_MS): List<File> {
    val session = sdkSession ?: return emptyList()
    if (session.clientCapabilities?.roots == null) return emptyList()
    val roots =
      try {
        withTimeoutOrNull(timeoutMs) {
          session.listRoots(options = RequestOptions(timeout = timeoutMs.milliseconds)).roots
        }
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (_: Exception) {
        null
      } ?: return emptyList()
    return roots.mapNotNull { root ->
      runCatching { File(java.net.URI(root.uri)) }.getOrNull()?.takeIf { it.isDirectory }
    }
  }

  internal companion object {
    /** Bound on a `roots/list` round trip; a client that never answers must not stall a render. */
    const val ROOTS_TIMEOUT_MS = 5_000L

    /**
     * Longer than the SDK's 60 s default so a person still filling in a form is not silently
     * skipped.
     */
    const val DEFAULT_ELICITATION_TIMEOUT_MS = 5 * 60_000L
  }
}

/** Outcome of asking the client for a form answer. */
sealed interface FormElicitation {
  /** The client cannot show a form (or rejected the request); use the text fallback. */
  data object Unsupported : FormElicitation

  /** The person did not answer within the bound. The form may have been dismissed by the client. */
  data object TimedOut : FormElicitation

  data class Answered(val result: ElicitResult) : FormElicitation
}

/** `form != null || (form == null && url == null)`: an empty capability object means form. */
internal fun supportsFormElicitation(capability: ClientCapabilities.Elicitation?): Boolean =
  capability != null && (capability.form != null || capability.url == null)

internal suspend fun awaitElicitation(
  timeoutMs: Long,
  request: suspend () -> ElicitResult,
): FormElicitation =
  try {
    // The SDK enforces the same bound through RequestOptions; this outer guard (with a little
    // slack) also covers a request implementation that ignores it.
    withTimeoutOrNull(timeoutMs + ELICITATION_TIMEOUT_SLACK_MS) {
      FormElicitation.Answered(request())
    } ?: FormElicitation.TimedOut
  } catch (cancelled: CancellationException) {
    // Coroutine cancellation is rethrown so cancelling tools/call terminates the handler.
    throw cancelled
  } catch (failure: McpException) {
    if (failure.code == RPCError.ErrorCode.REQUEST_TIMEOUT) FormElicitation.TimedOut
    else FormElicitation.Unsupported
  } catch (_: Exception) {
    // A rejected or malformed client reply is treated like an unsupported client: the caller
    // always includes an equivalent text workflow.
    FormElicitation.Unsupported
  }

private const val ELICITATION_TIMEOUT_SLACK_MS = 1_000L

/** Tracks every live [Session] so notifications can fan out to multiple connected clients. */
class SessionRegistry {
  private val sessions = ConcurrentHashMap.newKeySet<Session>()

  fun register(session: Session) {
    sessions.add(session)
  }

  fun unregister(session: Session) {
    sessions.remove(session)
  }

  fun forEach(block: (Session) -> Unit) {
    sessions.forEach { runCatching { block(it) } }
  }
}

internal fun installComposePreviewHandlers(
  sdkSession: ServerSession,
  session: Session,
  listTools: () -> List<ToolDef>,
  listPrompts: () -> List<io.modelcontextprotocol.kotlin.sdk.types.Prompt>,
  getPrompt:
    (
      name: String,
      arguments: Map<String, String>,
    ) -> io.modelcontextprotocol.kotlin.sdk.types.GetPromptResult,
  callTool:
    suspend (name: String, arguments: JsonElement?, progressToken: JsonElement?) -> CallToolResult,
  listResources: () -> List<ee.schimke.composeai.mcp.protocol.ResourceDescriptor>,
  readResource:
    (
      uri: String,
      progressToken: JsonElement?,
    ) -> ee.schimke.composeai.mcp.protocol.ReadResourceResult,
  subscribe: (uri: String) -> Unit,
  unsubscribe: (uri: String) -> Unit,
) {
  sdkSession.setRequestHandler<ListToolsRequest>(Method.Defined.ToolsList) { _, _ ->
    ListToolsResult(tools = listTools().map { it.toSdkTool() }, nextCursor = null)
  }
  sdkSession.setRequestHandler<ListPromptsRequest>(Method.Defined.PromptsList) { _, _ ->
    ListPromptsResult(prompts = listPrompts(), nextCursor = null)
  }
  sdkSession.setRequestHandler<GetPromptRequest>(Method.Defined.PromptsGet) { request, _ ->
    try {
      getPrompt(request.name, request.arguments.orEmpty())
    } catch (invalid: IllegalArgumentException) {
      throw McpException(RPCError.ErrorCode.INVALID_PARAMS, invalid.message ?: "Invalid prompt")
    }
  }
  sdkSession.setRequestHandler<CallToolRequest>(Method.Defined.ToolsCall) { request, _ ->
    // The request's `_meta` rides in the coroutine context so tools can read host-injected keys
    // ([OpenAiRequestMeta]) without a parameter.
    withContext(OpenAiRequestMeta(request.meta?.json)) {
        callTool(request.name, request.arguments, request.meta?.json?.get("progressToken"))
      }
      .toSdkCallToolResult()
  }
  sdkSession.setRequestHandler<ListResourcesRequest>(Method.Defined.ResourcesList) { _, _ ->
    ListResourcesResult(resources = listResources().map { it.toSdkResource() }, nextCursor = null)
  }
  sdkSession.setRequestHandler<ReadResourceRequest>(Method.Defined.ResourcesRead) { request, _ ->
    val progressToken = request.meta?.json?.get("progressToken")
    readResource(request.uri, progressToken).toSdkReadResourceResult()
  }
  sdkSession.setRequestHandler<SubscribeRequest>(Method.Defined.ResourcesSubscribe) { request, _ ->
    subscribe(request.uri)
    EmptyResult()
  }
  sdkSession.setRequestHandler<UnsubscribeRequest>(Method.Defined.ResourcesUnsubscribe) { request, _
    ->
    unsubscribe(request.uri)
    EmptyResult()
  }
}

/**
 * [extensions] go out both as `capabilities.extensions` and the legacy `capabilities.experimental`,
 * which MCP `2025-11-25` and earlier hosts read.
 */
internal fun composePreviewServerOptions(
  extensions: Map<String, JsonObject> = emptyMap()
): ServerOptions =
  ServerOptions(
    capabilities =
      ServerCapabilities(
        tools = ServerCapabilities.Tools(listChanged = true),
        resources = ServerCapabilities.Resources(subscribe = true, listChanged = true),
        prompts = ServerCapabilities.Prompts(listChanged = false),
        experimental = extensions.takeIf { it.isNotEmpty() }?.let(::JsonObject),
        extensions = extensions.takeIf { it.isNotEmpty() },
      )
  )

internal fun ToolDef.toSdkTool(): Tool {
  val schemaObject = inputSchema.jsonObject
  return Tool(
    name = name,
    inputSchema =
      ToolSchema(
        properties = schemaObject["properties"] as? JsonObject ?: JsonObject(emptyMap()),
        required =
          (schemaObject["required"] as? kotlinx.serialization.json.JsonArray)?.mapNotNull {
            it.jsonPrimitive.contentOrNull
          },
      ),
    description = description,
    title = title,
    icons = icons?.map { Icon(src = it.src, mimeType = it.mimeType, sizes = it.sizes) },
    meta = meta,
    outputSchema =
      outputSchema?.let { schema ->
        ToolSchema(
          properties = schema["properties"] as? JsonObject ?: JsonObject(emptyMap()),
          required =
            (schema["required"] as? kotlinx.serialization.json.JsonArray)?.mapNotNull {
              it.jsonPrimitive.contentOrNull
            },
        )
      },
    annotations = readOnlyHint?.let { ToolAnnotations(readOnlyHint = it) },
  )
}

private fun ee.schimke.composeai.mcp.protocol.ResourceDescriptor.toSdkResource(): Resource =
  Resource(uri = uri, name = name, description = description, mimeType = mimeType, meta = meta)

private fun ee.schimke.composeai.mcp.protocol.ReadResourceResult.toSdkReadResourceResult():
  ReadResourceResult = ReadResourceResult(contents = contents.map { it.toSdkResourceContents() })

private fun ee.schimke.composeai.mcp.protocol.ResourceContents.toSdkResourceContents():
  ResourceContents =
  when (this) {
    is ee.schimke.composeai.mcp.protocol.ResourceContents.Text ->
      TextResourceContents(text = text, uri = uri, mimeType = mimeType, meta = meta)
    is ee.schimke.composeai.mcp.protocol.ResourceContents.Blob ->
      BlobResourceContents(blob = blob, uri = uri, mimeType = mimeType, meta = meta)
  }

internal fun CallToolResult.toSdkCallToolResult():
  io.modelcontextprotocol.kotlin.sdk.types.CallToolResult =
  io.modelcontextprotocol.kotlin.sdk.types.CallToolResult(
    content = content.map { it.toSdkContent() },
    isError = isError ?: false,
    meta = meta,
    structuredContent = structuredContent,
  )

private fun ContentBlock.toSdkContent(): SdkContentBlock =
  when (this) {
    is ContentBlock.Text -> TextContent(text = text)
    is ContentBlock.Image -> ImageContent(data = data, mimeType = mimeType)
    is ContentBlock.ResourceLink ->
      ResourceLink(uri = uri, name = name, mimeType = mimeType, description = description)
    is ContentBlock.EmbeddedResource ->
      EmbeddedResource(resource = resource.toSdkResourceContents())
  }

private fun JsonElement.toRequestId(): RequestId? =
  when (this) {
    is JsonPrimitive -> {
      contentOrNull?.toLongOrNull()?.let { RequestId.NumberId(it) }
        ?: contentOrNull?.let { RequestId.StringId(it) }
    }
    else -> null
  }

/** Plain text result, for tools that just confirm an action. */
fun textCallToolResult(text: String): CallToolResult =
  CallToolResult(content = listOf(ContentBlock.Text(text)))

/** Convenience: image PNG response, base64-encoded data. */
fun pngCallToolResult(bytesBase64: String): CallToolResult =
  CallToolResult(content = listOf(ContentBlock.Image(data = bytesBase64, mimeType = "image/png")))

/**
 * Adds `structuredContent` for a tool with an `outputSchema`, since some clients refuse a result
 * without it. Results with their own structure, and errors, are unchanged; otherwise JSON-object
 * text blocks are merged in order (later keys win), and image/prose-only results get an empty
 * object.
 */
internal fun CallToolResult.withJsonTextStructure(): CallToolResult {
  if (isError == true || structuredContent != null) return this
  val objects =
    content.filterIsInstance<ContentBlock.Text>().mapNotNull { block ->
      runCatching { Json.parseToJsonElement(block.text) as? JsonObject }.getOrNull()
    }
  return copy(
    structuredContent = JsonObject(objects.fold(emptyMap<String, JsonElement>()) { a, b -> a + b })
  )
}

/** Convenience: error response — `isError = true` per MCP spec for tool-level errors. */
fun errorCallToolResult(message: String, structured: JsonObject? = null): CallToolResult =
  CallToolResult(
    content = listOf(ContentBlock.Text(message)),
    isError = true,
    structuredContent = structured,
  )

/** The MCP Apps client capability key (MCP Apps spec 2026-01-26). */
internal const val MCP_APPS_EXTENSION: String = "io.modelcontextprotocol/ui"
