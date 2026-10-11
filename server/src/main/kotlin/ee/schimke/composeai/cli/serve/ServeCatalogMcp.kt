package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.daemon.devices.DeviceDimensions
import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import ee.schimke.composeai.web.WebEscaping
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * MCP 2025-06-18 surface aggregating every served catalog. [ServeHttpServer] owns the transport;
 * this class owns lifecycle messages and catalog resources/tools. Shares the HTTP render semaphore,
 * so agents can't open a second, unmetered render lane.
 */
class ServeCatalogMcp(
  private val sessions: ServeSessionRegistry,
  private val renderSemaphore: Semaphore,
  private val renderQueueWaitSeconds: Long = 2,
  /**
   * Project mode's locally derived timeline; null on a hosted box, where the published manifest is
   * authoritative. See [historyJson].
   */
  private val projectHistory: ServeProjectHistory? = null,
  /**
   * The UI-builder door, or null, in which case its tools are absent from `tools/list` rather than
   * failing.
   */
  private val uiBuilder: ServeUiBuilderMcp? = null,
  /** Whether this box can also compile a design natively; see [ServeUiBuilderMcp.RENDER_NATIVE]. */
  private val uiBuilderNative: Boolean = false,
  /** Wall clock for [signResourceLink] expiry; a seam so tests can age a signed link. */
  private val nowMillis: () -> Long = System::currentTimeMillis,
  /**
   * This box's public origin. Render results carry a signed PNG URL only when set (#1160);
   * otherwise `data:`.
   */
  private val publicOrigin: () -> String? = { null },
  /**
   * Configured catalogs not loaded yet, so after a restart a request for one isn't answered "no
   * such catalog".
   */
  private val pendingCatalogs: () -> List<String> = { emptyList() },
) {
  /**
   * Per-process key for short-lived signed resource links; a restart invalidates them, like grants.
   */
  private val resourceLinkKey: ByteArray = ByteArray(32).also { SecureRandom().nextBytes(it) }

  /**
   * Pictures `ui_builder_view` handed out as signed links, by the random id in their URI. A view is
   * drawn for one actor and can't be re-rendered from a grant-less link, so it is kept for the
   * link's lifetime, count-bounded.
   */
  private val viewImages =
    object : LinkedHashMap<String, Pair<ByteArray, Long>>(16, 0.75f, true) {
      override fun removeEldestEntry(
        eldest: MutableMap.MutableEntry<String, Pair<ByteArray, Long>>
      ) = size > MAX_VIEW_IMAGES
    }

  data class Reply(val body: JsonObject?, val accepted: Boolean = false)

  /**
   * Request-scoped client interaction, only on a negotiated Streamable HTTP session. Stateless
   * callers keep [Unsupported], so every tool needs a complete text fallback.
   */
  interface ClientInteraction {
    val formElicitationSupported: Boolean

    suspend fun elicitForm(
      message: String,
      requestedSchema: JsonObject,
      timeoutMillis: Long,
    ): FormElicitationResult?

    /**
     * Whether the client declared OpenAI form elicitation
     * (`extensions["openai/elicitation"].form`); see [ServeOpenAiForms]. Independent of
     * [formElicitationSupported].
     */
    val openAiFormsSupported: Boolean
      get() = false

    /** Sends `openai/elicitation/create`; [OpenAiFormElicitation.Unsupported] when not declared. */
    suspend fun elicitOpenAiForm(
      message: String,
      requestedSchema: JsonObject,
      timeoutMillis: Long,
    ): OpenAiFormElicitation = OpenAiFormElicitation.Unsupported

    /**
     * This interaction, but an accepted answer is dropped unless [stillAuthorized] holds on
     * arrival. Declines and cancels pass through, since they write nothing.
     */
    fun reauthorizedOnAccept(stillAuthorized: () -> Boolean): ClientInteraction {
      if (!formElicitationSupported && !openAiFormsSupported) return this
      val delegate = this
      return object : ClientInteraction {
        override val formElicitationSupported = delegate.formElicitationSupported
        override val openAiFormsSupported = delegate.openAiFormsSupported

        override suspend fun elicitForm(
          message: String,
          requestedSchema: JsonObject,
          timeoutMillis: Long,
        ): FormElicitationResult? {
          val answer = delegate.elicitForm(message, requestedSchema, timeoutMillis) ?: return null
          if (answer.action == FormElicitationAction.ACCEPT && !stillAuthorized()) return null
          return answer
        }

        override suspend fun elicitOpenAiForm(
          message: String,
          requestedSchema: JsonObject,
          timeoutMillis: Long,
        ): OpenAiFormElicitation {
          val answer = delegate.elicitOpenAiForm(message, requestedSchema, timeoutMillis)
          if (
            answer is OpenAiFormElicitation.Answered &&
              answer.result.action == FormElicitationAction.ACCEPT &&
              !stillAuthorized()
          ) {
            return OpenAiFormElicitation.NoAnswer
          }
          return answer
        }
      }
    }

    companion object {
      val Unsupported =
        object : ClientInteraction {
          override val formElicitationSupported = false

          override suspend fun elicitForm(
            message: String,
            requestedSchema: JsonObject,
            timeoutMillis: Long,
          ): FormElicitationResult? = null
        }
    }
  }

  enum class FormElicitationAction {
    ACCEPT,
    DECLINE,
    CANCEL,
  }

  data class FormElicitationResult(
    val action: FormElicitationAction,
    val content: JsonObject? = null,
  )

  /**
   * The grant flow, as much as an MCP client needs. A seam rather than a store reference so this
   * class stays free of HTTP and rate limits; each method returns the same JSON as the matching
   * `/agent-access/…` route. Null means throttled: a tool error, not an exception.
   */
  interface AgentAccess {
    suspend fun open(
      label: String,
      scope: String,
      ttlSeconds: Long,
      capabilities: List<String>,
    ): String?

    suspend fun poll(requestId: String, deviceSecret: String, waitSeconds: Long): String?

    /** The canonical browser approval URL for [requestId], on this request's public origin. */
    fun approvalUrl(requestId: String): String?
  }

  private data class UrlElicitationRequired(
    val elicitationId: String,
    val url: String,
    override val message: String,
  ) : RuntimeException(message)

  private data class PreviewTarget(val catalog: String, val previewId: String)

  /**
   * [liveAuthorization] stays last for trailing-lambda use; [access] is absent on boxes without
   * grants.
   */
  suspend fun handle(
    request: JsonObject,
    access: AgentAccess? = null,
    /**
     * Optional request-scoped interaction channel; transport support alone must not change
     * stateless behaviour.
     */
    clientInteraction: ClientInteraction = ClientInteraction.Unsupported,
    /**
     * The UI-builder capability check for this request, asked of the transport (which holds the
     * credential). Defaults to refusing, so a forgetful caller can't open the builder
     * unauthenticated. The second argument is the in-band token ([TOKEN_ARGUMENT]) the transport
     * can't see.
     */
    uiBuilderAuthorization: (UiBuilderRouteCapability, String?) -> UiBuilderAuthorizationDecision =
      { _, _ ->
        UiBuilderAuthorizationDecision.Missing
      },
    liveAuthorization: (String?) -> ServeMachineAuthorization.Decision,
  ): Reply {
    val id = request["id"]
    if ((request["jsonrpc"] as? JsonPrimitive)?.contentOrNull != "2.0") {
      return Reply(error(id, INVALID_REQUEST, "Expected JSON-RPC 2.0"))
    }
    val method =
      (request["method"] as? JsonPrimitive)?.contentOrNull
        ?: return Reply(error(id, INVALID_REQUEST, "Missing JSON-RPC method"))
    if (id == null) return Reply(body = null, accepted = true)
    val params = request["params"] as? JsonObject ?: JsonObject(emptyMap())

    val result =
      try {
        when (method) {
          "initialize" -> initialize(params)
          "ping" -> JsonObject(emptyMap())
          "tools/list" -> buildJsonObject { put("tools", tools(access != null)) }
          "prompts/list" -> prompts()
          "prompts/get" -> prompt(params)
          "tools/call" ->
            try {
              callTool(
                withDeclaredUrlElicitation(params),
                liveAuthorization,
                access,
                uiBuilderAuthorization,
                clientInteraction,
              )
            } catch (e: McpRequestException) {
              toolError(e.message ?: "Tool call failed")
            }
          "resources/list" -> listResources()
          "resources/read" -> readResource(params, presentedToken(request), liveAuthorization)
          else -> return Reply(error(id, METHOD_NOT_FOUND, "Unknown method '$method'"))
        }
      } catch (e: McpRequestException) {
        return Reply(error(id, INVALID_PARAMS, e.message ?: "Invalid parameters"))
      } catch (e: UrlElicitationRequired) {
        return Reply(urlElicitationRequired(id, e))
      } catch (e: Exception) {
        return Reply(error(id, INTERNAL_ERROR, e.message ?: "Catalog MCP request failed"))
      }
    return Reply(success(id, result))
  }

  private fun initialize(params: JsonObject): JsonObject {
    val requested = params["protocolVersion"]?.jsonPrimitive?.contentOrNull
    val negotiated =
      when (requested) {
        null,
        MCP_PROTOCOL_VERSION -> MCP_PROTOCOL_VERSION
        MCP_PROTOCOL_VERSION_2025_03 -> MCP_PROTOCOL_VERSION_2025_03
        MCP_PROTOCOL_VERSION_2025_11 -> MCP_PROTOCOL_VERSION_2025_11
        else -> MCP_PROTOCOL_VERSION
      }
    return buildJsonObject {
      put("protocolVersion", negotiated)
      put(
        "capabilities",
        buildJsonObject {
          put("tools", JsonObject(emptyMap()))
          put("resources", buildJsonObject { put("subscribe", false) })
          if (uiBuilder != null) put("prompts", JsonObject(emptyMap()))
        },
      )
      put(
        "serverInfo",
        buildJsonObject {
          put("name", "compose-preview-catalog")
          put("version", SERVE_VERSION)
        },
      )
      put(
        "instructions",
        "This endpoint exposes every hosted Compose Preview catalog. Use catalog_list_projects to " +
          "discover catalog ids. Reading published previews needs preview access; made-to-order " +
          "renders and data products need live access. With no credential, call request_access, " +
          "show the human its approveUrl and userCode, then poll_access (which waits for the " +
          "decision) until it answers approved. Send the token it returns as the " +
          "X-Compose-Preview-Token header if you control your own headers; if you cannot set " +
          "them — an MCP client fixes its headers when it connects — pass the token as the " +
          "'token' argument of each gated tool instead, and access approved during this session " +
          "works in it." +
          accessElicitationInstruction(params) +
          if (uiBuilder == null) ""
          else
            " UI-builder tools accept optional agentName and agentModel for the participant toolbar; report your client name and current model only when known. Active tool calls and the last 30 seconds of activity appear on that design. A UI-builder document's `home` is canonical: edit that original, and never " +
              "re-import it as a second design or move, save back, or discard it without the " +
              "human explicitly choosing." +
              if (uiBuilder?.supportsComments != true) ""
              else
                " Keep design discussion at that home: read and " +
                  "acknowledge its pending comments and post design-specific findings there; an " +
                  "external issue or pull-request link supplements but never replaces that " +
                  "discussion.",
      )
    }
  }

  /**
   * The endpoint is stateless, so `initialize` capabilities aren't remembered; the handshake's
   * instructions tell URL-elicitation clients to use it for access and others to keep the text
   * flow.
   */
  private fun accessElicitationInstruction(initializeParams: JsonObject): String {
    val elicitation =
      (initializeParams["capabilities"] as? JsonObject)?.get("elicitation") as? JsonObject
    return if (elicitation?.get("url") is JsonObject) {
      " Your client declared URL elicitation: call poll_access with urlMode=true so the person " +
        "approves in the browser dialog your client opens."
    } else {
      " Your client did not declare URL elicitation: omit urlMode and show approveUrl and " +
        "userCode in chat."
    }
  }

  /**
   * Honours a per-request capability declaration on `tools/call` (`params._meta`,
   * [CLIENT_CAPABILITIES_META]). When present it decides `poll_access`'s URL mode outright (a
   * -32042 the client can't show would strand the request); otherwise the explicit `urlMode`
   * argument stands.
   */
  private fun withDeclaredUrlElicitation(params: JsonObject): JsonObject {
    if ((params["name"] as? JsonPrimitive)?.contentOrNull != "poll_access") return params
    val declared =
      ((params["_meta"] as? JsonObject)?.get(CLIENT_CAPABILITIES_META) as? JsonObject)
        ?: return params
    val urlMode = (declared["elicitation"] as? JsonObject)?.get("url") is JsonObject
    val arguments = params["arguments"] as? JsonObject ?: JsonObject(emptyMap())
    return JsonObject(
      params + ("arguments" to JsonObject(arguments + ("urlMode" to JsonPrimitive(urlMode))))
    )
  }

  /** Remote UI-builder slash commands. Kept text-complete for clients that only render prompts. */
  private fun prompts(): JsonObject = buildJsonObject {
    putJsonArray("prompts") {
      if (uiBuilder != null) {
        add(
          buildJsonObject {
            put("name", "review-design")
            put(
              "description",
              "Open a server-homed design, read its discussion, and report attention items.",
            )
            putJsonArray("arguments") {
              add(
                buildJsonObject {
                  put("name", "designUrl")
                  put(
                    "description",
                    "The design's UI-builder URL on this server, such as " +
                      "https://<host>/ui-builder/<designId> or …/ui-builder/<designId>?node=<nodeId>. " +
                      "A bare design id is accepted too.",
                  )
                  put("required", true)
                }
              )
              add(
                buildJsonObject {
                  put("name", "designId")
                  put(
                    "description",
                    "The design id on this server; kept for older clients, use designUrl.",
                  )
                  put("required", false)
                }
              )
            }
          }
        )
        add(
          buildJsonObject {
            put("name", "design-status")
            put(
              "description",
              "Report a design revision, discussion state, and known home/copy gaps.",
            )
            putJsonArray("arguments") {
              add(
                buildJsonObject {
                  put("name", "designId")
                  put("description", "The design id on this server.")
                  put("required", true)
                }
              )
            }
          }
        )
      }
    }
  }

  private fun prompt(params: JsonObject): JsonObject {
    if (uiBuilder == null)
      throw McpRequestException("This server does not expose UI-builder prompts")
    val name = params.requiredString("name")
    val arguments = params["arguments"] as? JsonObject ?: JsonObject(emptyMap())
    val text =
      when (name) {
        "review-design" -> {
          val target = arguments.reviewTarget()
          reviewDesignPrompt(target.designId, target.nodeId)
        }
        "design-status" -> designStatusPrompt(arguments.validatedDesignId())
        else -> throw McpRequestException("unknown prompt: $name")
      }
    return buildJsonObject {
      put("description", "Compose Preview UI-builder workflow")
      putJsonArray("messages") {
        add(
          buildJsonObject {
            put("role", "user")
            put(
              "content",
              buildJsonObject {
                put("type", "text")
                put("text", text)
              },
            )
          }
        )
      }
    }
  }

  private fun JsonObject.validatedDesignId(): String {
    val designId = requiredString("designId")
    if (!designId.matches(PROMPT_DESIGN_ID)) {
      throw McpRequestException(
        "designId must be 1-64 URL-safe letters, digits, dots, underscores, or hyphens"
      )
    }
    return designId
  }

  private data class ReviewTarget(val designId: String, val nodeId: String?)

  /**
   * `review-design`'s design, from `designUrl` (#1120) or the older `designId`. Accepts
   * `/ui-builder/[<catalog>/]<designId>[?node=<nodeId>]`; only id and node are kept, so a pasted
   * query credential goes nowhere. A URL for another origin is refused, since the same id there is
   * a different design.
   */
  private fun JsonObject.reviewTarget(): ReviewTarget {
    val url = optionalString("designUrl")
    if (url == null) {
      if (optionalString("designId") == null) throw McpRequestException("'designUrl' is required")
      return ReviewTarget(validatedDesignId(), null)
    }
    val trimmed = url.trim()
    if (trimmed.matches(PROMPT_DESIGN_ID)) return ReviewTarget(trimmed, null)
    val parsed =
      runCatching { URI(trimmed) }.getOrNull()
        ?: throw McpRequestException("designUrl is not a URL: expected …/ui-builder/<designId>")
    if (parsed.isAbsolute) {
      val scheme = parsed.scheme.lowercase()
      if ((scheme != "http" && scheme != "https") || parsed.host == null) {
        throw McpRequestException("designUrl must be an http(s) UI-builder URL")
      }
      val own = publicOrigin()?.let(::normalizeServerHomeUrl)
      val theirs = normalizeServerHomeUrl("$scheme://${parsed.rawAuthority.substringAfter('@')}")
      if (own != null && theirs != own) {
        throw McpRequestException(
          "designUrl names another server ($theirs); review it through that server's /mcp, " +
            "since design ids are per server"
        )
      }
    }
    val segments = parsed.rawPath.orEmpty().split('/').filter(String::isNotEmpty)
    val builder = segments.indexOf("ui-builder")
    val rest = if (builder < 0) emptyList() else segments.drop(builder + 1)
    val designId =
      rest.takeIf { it.size in 1..2 }?.last()?.takeIf { it.matches(PROMPT_DESIGN_ID) }
        ?: throw McpRequestException(
          "designUrl must look like …/ui-builder/<designId> (optionally ?node=<nodeId>), where " +
            "designId is 1-64 URL-safe letters, digits, dots, underscores, or hyphens"
        )
    val nodeId =
      parsed.rawQuery
        ?.split('&')
        ?.firstOrNull { it.startsWith("node=") }
        ?.let { URLDecoder.decode(it.removePrefix("node="), StandardCharsets.UTF_8) }
        ?.takeIf(String::isNotBlank)
        ?.also {
          if (!it.matches(PROMPT_NODE_ID)) {
            throw McpRequestException(
              "designUrl's node must be 1-128 URL-safe letters, digits, dots, underscores, colons, " +
                "or hyphens"
            )
          }
        }
    return ReviewTarget(designId, nodeId)
  }

  private fun reviewDesignPrompt(designId: String, nodeId: String? = null): String =
    """
    Review the UI-builder design `$designId` at its server home.${
      if (nodeId == null) ""
      else " The person pointed at node `$nodeId`: start there, and keep the rest of the design in view."
    }

    1. Call `ui_builder_get_design` with designId `$designId`.
    2. If `ui_builder_list_comments` is advertised, read it before proposing edits. Report each
       unresolved or unacknowledged thread; discussion belongs on the design, not in a new PR.
    3. Look at the design with `ui_builder_view`: it returns the editor canvas as a person sees
       it — selection outline, reference overlay and comment pins drawn over the render — plus the
       node boxes and pin positions as JSON. Pass `renderer: "native"` when node boxes matter and
       that lane is advertised; the default PNG export reports none (compose-preview-server#1114).
       Where `ui_builder_render_design_matrix` is advertised, use it for other device sizes.
    4. Run `ui_builder_check_design` and report its findings by node; they are document checks,
       not render evidence, unless the reply says they were measured on a render.
    5. Summarize concrete attention items, separating observed render evidence from document-only
       checks. Do not claim to have seen editor-only state you could not view.
    """
      .trimIndent()

  private fun designStatusPrompt(designId: String): String =
    """
    Report the status of UI-builder design `$designId`.

    1. Call `ui_builder_get_design`; report its revision, catalog pin and `home`.
    2. If `ui_builder_list_comments` is advertised, report unresolved and unacknowledged comments;
       otherwise say this host has no design-discussion surface.
    3. Temporary copies live in checkouts, which this server cannot see. Say so, and point to
       `compose-preview design status` in the checkout; never claim there are no copies.
    4. Do not move, save back or discard anything from this prompt: those need the person's choice.
    """
      .trimIndent()

  // `JsonArrayBuilder.addAll` is still experimental; the UI-builder block below is the only caller.
  @OptIn(ExperimentalSerializationApi::class)
  private fun tools(accessEnabled: Boolean): JsonArray = buildJsonArray {
    if (accessEnabled) {
      // First in the list on purpose: a client with no credential can call only these two, and a
      // model reading the list top-down should meet the way in before the tools it cannot use yet.
      add(
        tool(
          "request_access",
          "Ask a human for access to this server. Returns an approveUrl and a userCode: show " +
            "BOTH to the person you are working with, ask them to open the link and check that " +
            "the code on the page matches, then call poll_access. When the client supports URL " +
            "elicitation, call poll_access with urlMode=true instead of pasting the link into " +
            "chat; clients without it keep this complete text fallback. The link grants nothing by " +
            "itself — keep the deviceSecret this returns, it is what collects the token. Use " +
            "this when a call answered 'authorization_required', or when your token stopped " +
            "working (a server restart drops every grant).",
          """{"type":"object","properties":{"label":{"type":"string"},"scope":{"type":"string","enum":["preview","live","playground"]},"ttlSeconds":{"type":"integer"},"capabilities":{"type":"array","items":{"type":"string"}}}}""",
        )
      )
      add(
        tool(
          "poll_access",
          "Collect the outcome of a request_access, proving possession of its deviceSecret. " +
            "It HOLDS THE CALL OPEN and answers the moment the human decides — one call " +
            "instead of a dozen, since each poll here costs a whole round trip through you. It " +
            "waits 8 seconds by default; pass waitSeconds (up to 30) if your client tolerates a " +
            "longer call. Pass urlMode=true when the client supports URL elicitation: while the " +
            "request is pending this returns the standard -32042 URL-elicitation-required error, " +
            "and retrying the same call after the browser decision returns the outcome. A wait " +
            "that times out answers status=pending, and you simply call " +
            "again. Then approved (with the token) or denied/expired. Use the token on every " +
            "later call: as the X-Compose-Preview-Token header where you control headers, and " +
            "otherwise as each gated tool's 'token' argument — which is what an MCP client " +
            "reaching this flow mid-session needs, since its headers were fixed when it " +
            "connected.",
          """{"type":"object","properties":{"requestId":{"type":"string"},"deviceSecret":{"type":"string"},"waitSeconds":{"type":"integer","minimum":0,"maximum":30},"urlMode":{"type":"boolean","description":"Use the protocol-standard URL elicitation UI while this request is pending."}},"required":["requestId","deviceSecret"]}""",
        )
      )
    }
    add(
      tool(
        "status",
        "Report readiness and the aggregate catalog set.",
        EMPTY_SCHEMA,
      )
    )
    add(
      tool(
        "list_projects",
        "List every remote catalog with its stable id and preview count. Call this first: " +
          "catalog_list_previews and catalog_list_data_products take one of these ids as 'catalog'.",
        EMPTY_SCHEMA,
      )
    )
    // Sidebar apps (#1241): OpenAI global entrypoints opening the library MCP App.
    add(ServeLibraryMcp.libraryTool(::tool))
    if (uiBuilder != null) add(ServeLibraryMcp.uiBuilderOpenTool(::tool))
    add(
      tool(
        "list_previews",
        "List the Compose previews and published metadata of one hosted catalog. 'catalog' is " +
          "required (ids from catalog_list_projects). Pass 'query' (a component name such as " +
          "EdgeButton) to narrow by id or label; results are paged ($DEFAULT_PREVIEW_PAGE by " +
          "default, 'offset'/'limit' for more). This server holds published library catalogs " +
          "only: previews of the project you are editing come from the local " +
          "compose-preview-mcp server, not from here.",
        LIST_PREVIEWS_SCHEMA,
      )
    )
    add(
      tool(
        "render_preview",
        "Render one preview. Like local compose-ai-tools, the default semantics observation is " +
          "token-frugal; request observe=png for pixels, observe=svg for the compose/figma-svg " +
          "vector export as SVG source, or observe=scroll-png / observe=scroll-svg for the " +
          "full-page capture of a scrollable screen rather than the viewport crop. Overrides, " +
          "other observations and fresh renders require live grant scope; without it, a call " +
          "with no overrides returns the published snapshot (as resources/read does).",
        """{"type":"object","properties":{"uri":{"type":"string"},"catalog":{"type":"string"},"previewId":{"type":"string"},"observe":{"type":"string","enum":["png","svg","scroll-png","scroll-svg","semantics","hash"]},"overrides":{"type":"object","additionalProperties":{"type":["string","number","boolean"]}}},"anyOf":[{"required":["uri"]},{"required":["catalog","previewId"]}]}""",
      )
    )
    add(
      tool(
        "render_matrix",
        "Render one preview across the cross-product of the given override axes in a single " +
          "call, returning a hash/size observation per cell (observe=png adds the pixels). " +
          "Prefer this over a catalog_render_preview per combination: the cells share one catalog lease " +
          "and are reported together, so comparing axes costs one round trip instead of N. " +
          "Capped at $MAX_MATRIX_CELLS cells. Requires live grant scope.",
        """{"type":"object","properties":{"uri":{"type":"string"},"catalog":{"type":"string"},"previewId":{"type":"string"},"observe":{"type":"string","enum":["png","hash"]},"overrides":{"type":"object","additionalProperties":{"type":["string","number","boolean"]}},"axes":{"type":"object","additionalProperties":{"type":"array","items":{"type":["string","number","boolean"]},"minItems":1}}},"required":["axes"],"anyOf":[{"required":["uri"]},{"required":["catalog","previewId"]}]}""",
      )
    )
    add(
      tool(
        "list_devices",
        "List the `@Preview(device = ...)` ids this server's render lane recognises, with each " +
          "one's dp size and density. The `device` override takes one of these ids; an " +
          "unrecognised name renders the default frame rather than failing, so check here " +
          "instead of guessing.",
        EMPTY_SCHEMA,
      )
    )
    add(
      tool(
        "diff_semantics",
        "Compare two previews' semantics by testTag: which tags are only in one side, which " +
          "moved, and which changed occupancy count. Identity is the authored testTag, not a " +
          "positional ref, so a tag that stops resolving is reported rather than silently " +
          "retargeted at different pixels. Requires live grant scope.",
        """{"type":"object","properties":{"catalog":{"type":"string"},"previewId":{"type":"string"},"uri":{"type":"string"},"other":{"type":"object","properties":{"catalog":{"type":"string"},"previewId":{"type":"string"},"uri":{"type":"string"}}},"overrides":{"type":"object","additionalProperties":{"type":["string","number","boolean"]}},"otherOverrides":{"type":"object","additionalProperties":{"type":["string","number","boolean"]}}},"required":["other"],"anyOf":[{"required":["uri"]},{"required":["catalog","previewId"]}]}""",
      )
    )
    add(
      tool(
        "history_list",
        "The render timeline for one preview: which versions of its rendered bytes exist, when " +
          "each appeared, and whether the preview is unstable (re-renders differently on every " +
          "publish) rather than genuinely changing. Where this server holds the timeline it is " +
          "returned inline; where the catalog is published from a delivery branch the manifest " +
          "lives on that branch and this reports where to fetch it.",
        """{"type":"object","properties":{"uri":{"type":"string"},"catalog":{"type":"string"},"previewId":{"type":"string"}},"anyOf":[{"required":["uri"]},{"required":["catalog","previewId"]}]}""",
      )
    )
    add(
      tool(
        "history_diff",
        "Compare two of a preview's recorded renders. Defaults to the two newest — did the last " +
          "publish move this preview? A metadata comparison: the timeline's versions are already " +
          "collapsed distinct renders, so whether the bytes changed is answered without fetching " +
          "either image. Reports `unstable` so a difference on a nondeterministic preview is not " +
          "mistaken for a real change.",
        """{"type":"object","properties":{"uri":{"type":"string"},"catalog":{"type":"string"},"previewId":{"type":"string"},"from":{"type":"string"},"to":{"type":"string"}},"anyOf":[{"required":["uri"]},{"required":["catalog","previewId"]}]}""",
      )
    )
    add(
      tool(
        "history_read",
        "Fetch one historical render's pixels through this server, by `commit` or `blob` (a " +
          "prefix is enough). Use when an agent cannot reach the delivery branch itself, or wants " +
          "the bytes rather than the timeline.",
        """{"type":"object","properties":{"uri":{"type":"string"},"catalog":{"type":"string"},"previewId":{"type":"string"},"commit":{"type":"string"},"blob":{"type":"string"}},"anyOf":[{"required":["uri"]},{"required":["catalog","previewId"]}]}""",
      )
    )
    uiBuilder?.let {
      addAll(
        ServeUiBuilderMcp.declarations(
          ::tool,
          uiBuilderNative,
          it.supportsComments,
          it.supportsAssets,
          it.supportsLinks,
          it.supportsValidation,
          it.supportsReviews,
          it.supportsBranches,
          it.supportsReferences,
          it.supportsGuidelineRecords,
        )
      )
    }
    add(
      tool(
        "list_data_products",
        "List the structured data-product kinds of one catalog, optionally one preview. Name " +
          "the catalog with 'catalog' (ids from catalog_list_projects) or a preview 'uri'.",
        """{"type":"object","properties":{"catalog":{"type":"string"},"previewId":{"type":"string"},"uri":{"type":"string"}},"anyOf":[{"required":["catalog"]},{"required":["uri"]}]}""",
      )
    )
    add(
      tool(
        "get_preview_data",
        "Fetch the merged accessibility or annotation product for a preview. This lane " +
          "requires live grant scope.",
        """{"type":"object","properties":{"uri":{"type":"string"},"catalog":{"type":"string"},"previewId":{"type":"string"},"kind":{"type":"string"},"overrides":{"type":"object","additionalProperties":{"type":["string","number","boolean"]}}},"required":["kind"],"anyOf":[{"required":["uri"]},{"required":["catalog","previewId"]}]}""",
      )
    )
    add(
      tool(
        "list-all-documentation",
        "Storybook-MCP-compatible alias that lists every preview as a story.",
        EMPTY_SCHEMA,
      )
    )
    add(
      tool(
        "get-documentation-for-story",
        "Storybook-MCP-compatible preview metadata lookup.",
        """{"type":"object","properties":{"storyId":{"type":"string"},"id":{"type":"string"}},"anyOf":[{"required":["storyId"]},{"required":["id"]}]}""",
      )
    )
    add(
      tool(
        "preview-stories",
        "Storybook-MCP-compatible rendering of one or more story ids. Requires live scope.",
        """{"type":"object","properties":{"storyIds":{"type":"array","items":{"type":"string"}},"storyId":{"type":"string"},"ids":{"type":"array","items":{"type":"string"}},"id":{"type":"string"},"observe":{"type":"string","enum":["png","svg","scroll-png","scroll-svg","semantics","hash"]},"overrides":{"type":"object","additionalProperties":{"type":["string","number","boolean"]}}},"anyOf":[{"required":["storyIds"]},{"required":["storyId"]},{"required":["ids"]},{"required":["id"]}]}""",
      )
    )
  }

  private suspend fun callTool(
    params: JsonObject,
    authorizeLive: (String?) -> ServeMachineAuthorization.Decision,
    access: AgentAccess?,
    uiBuilderAuthorization: (UiBuilderRouteCapability, String?) -> UiBuilderAuthorizationDecision,
    clientInteraction: ClientInteraction,
  ): JsonObject {
    // Dispatch is by the canonical name; the wire name carries the `catalog_` prefix (#1105).
    val name = canonicalName(params.requiredString("name"))
    val rawArgs = params["arguments"] as? JsonObject ?: JsonObject(emptyMap())
    // Stripped before dispatch: the credential is how this call was authorized, never an input to
    // what it does, and a tool that forwards its arguments must not forward a token with them.
    val presented = tokenArgument(rawArgs)
    val args = if (TOKEN_ARGUMENT in rawArgs) JsonObject(rawArgs - TOKEN_ARGUMENT) else rawArgs
    val liveAuthorization = { authorizeLive(presented) }
    uiBuilderTool(name, args, presented, uiBuilderAuthorization, clientInteraction)?.let {
      return withStructuredContent(name, it)
    }
    val result = catalogTool(name, foldUriOverrides(name, args), liveAuthorization, access)
    // An in-band-authorized caller can't attach its token to a host's own `resources/read` of a
    // returned link, so sign each override-bearing link for a few minutes instead.
    return if (presented != null) signResourceLinks(result) else result
  }

  private suspend fun catalogTool(
    name: String,
    args: JsonObject,
    liveAuthorization: () -> ServeMachineAuthorization.Decision,
    access: AgentAccess?,
  ): JsonObject {
    return when (name) {
      "request_access" -> {
        val broker = access ?: return toolError(ACCESS_DISABLED)
        val body =
          broker.open(
            label = args.optionalString("label").orEmpty(),
            scope = args.optionalString("scope").orEmpty(),
            ttlSeconds = args["ttlSeconds"]?.jsonPrimitive?.longOrNull ?: 0L,
            capabilities =
              (args["capabilities"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }
                ?: emptyList(),
          )
        body?.let { textResult(it) } ?: toolError(ACCESS_THROTTLED)
      }
      "poll_access" -> {
        val broker = access ?: return toolError(ACCESS_DISABLED)
        val urlMode = args["urlMode"]?.jsonPrimitive?.booleanOrNull == true
        val body =
          broker.poll(
            args.requiredString("requestId"),
            args.requiredString("deviceSecret"),
            // Default to waiting rather than to spinning: a client that says nothing is a client
            // that would otherwise call this again in three seconds, through a model.
            if (urlMode) 0L
            else
              args["waitSeconds"]?.jsonPrimitive?.longOrNull
                ?: ServeAgentGrants.DEFAULT_POLL_WAIT_SECONDS,
          )
        if (body == null) return toolError(ACCESS_THROTTLED)
        if (urlMode && pendingAccess(body)) {
          val requestId = args.requiredString("requestId")
          val approvalUrl = broker.approvalUrl(requestId)
          if (approvalUrl != null) {
            throw UrlElicitationRequired(
              elicitationId = requestId,
              url = approvalUrl,
              message =
                "Approve or decline this access request in the browser, checking the user code " +
                  "returned by request_access, then continue the same poll_access call.",
            )
          }
        }
        textResult(body)
      }
      "status" -> textResult(statusJson().toString())
      "list_projects" -> textResult(projectsJson().toString())
      ServeLibraryMcp.LIBRARY ->
        ServeLibraryMcp.libraryResult(libraryCatalogs(args), designsAvailable = uiBuilder != null)
      ServeLibraryMcp.UI_BUILDER_OPEN ->
        if (uiBuilder == null) toolError("unknown tool: $name")
        else ServeLibraryMcp.uiBuilderOpenResult()
      "list_previews" ->
        textResult(
          previewsJson(requireCatalog("list_previews", args), PreviewPage.of(args)).toString()
        )
      "list_data_products" -> textResult(dataProductsJson(args).toString())
      "list-all-documentation" -> textResult(storiesJson().toString())
      "get-documentation-for-story" -> {
        val requested = args.firstString("storyId", "id")
        val target = storyTarget(requested)
        withCatalog(target.catalog) { host ->
          val preview = resolvePreview(host, target.previewId)
          textResult(storyDocumentationJson(target.catalog, preview, host).toString())
        }
      }
      "render_preview" -> {
        val target = args.previewTarget()
        if (servesPublishedSnapshot(args, liveAuthorization)) {
          return publishedSnapshotResult(target.catalog, target.previewId)
        }
        requireLive(liveAuthorization)
        withCatalog(target.catalog) { host ->
          val preview = resolvePreview(host, target.previewId)
          val rawOverrides = args["overrides"] as? JsonObject
          val overrides = parseOverrides(preview, rawOverrides)
          renderResult(
            host,
            preview.id,
            resourceUri(target.catalog, preview.id),
            overrides,
            args["observe"]?.jsonPrimitive?.contentOrNull?.lowercase() ?: "semantics",
            rawOverrides?.keys.orEmpty().toList(),
            rawOverrides,
          )
        }
      }
      "list_devices" -> textResult(devicesJson().toString())
      "history_diff" -> diffHistoryResult(args)
      "history_read" -> readHistoryResult(args)
      "history_list" -> {
        val target = args.previewTarget()
        withCatalog(target.catalog) { host ->
          val preview = resolvePreview(host, target.previewId)
          textResult(historyJson(host, target.catalog, preview.id).toString())
        }
      }
      "diff_semantics" -> {
        diffSemanticsTargets(args)
        requireLive(liveAuthorization)
        diffSemanticsResult(args)
      }
      "render_matrix" -> {
        val target = args.previewTarget()
        requireLive(liveAuthorization)
        withCatalog(target.catalog) { host ->
          val preview = resolvePreview(host, target.previewId)
          matrixResult(host, preview, target.catalog, args)
        }
      }
      "preview-stories" -> {
        val ids =
          ((args["storyIds"] ?: args["ids"]) as? JsonArray)?.map { it.jsonPrimitive.content }
            ?: listOf(args.firstString("storyId", "id"))
        if (ids.size > MAX_STORIES_PER_CALL) {
          throw McpRequestException(
            "preview-stories accepts at most $MAX_STORIES_PER_CALL ids per call"
          )
        }
        requireLive(liveAuthorization)
        val observe = args["observe"]?.jsonPrimitive?.contentOrNull?.lowercase() ?: "png"
        val content = buildJsonArray {
          ids.forEach { storyId ->
            val target = storyTarget(storyId)
            withCatalog(target.catalog) { host ->
              val preview = resolvePreview(host, target.previewId)
              val rawOverrides = args["overrides"] as? JsonObject
              val overrides = parseOverrides(preview, rawOverrides)
              renderContent(
                  host,
                  preview.id,
                  resourceUri(target.catalog, preview.id),
                  overrides,
                  observe,
                  rawOverrides?.keys.orEmpty().toList(),
                  rawOverrides,
                )
                .forEach(::add)
            }
          }
        }
        buildJsonObject { put("content", content) }
      }
      "get_preview_data" -> {
        val target = args.previewTarget()
        val kind = args.requiredString("kind")
        requireLive(liveAuthorization)
        withCatalog(target.catalog) { host ->
          val preview = resolvePreview(host, target.previewId)
          val overrides = parseOverrides(preview, args["overrides"] as? JsonObject)
          dataProductResult(host, preview.id, kind, overrides)
        }
      }
      else -> toolError("unknown tool: $name")
    }.let { withStructuredContent(name, it) }
  }

  private suspend fun listResources(): JsonObject {
    val resources = buildJsonArray {
      add(
        buildJsonObject {
          put("uri", MCP_APP_VIEWER_URI)
          put("name", "Compose Preview viewer")
          put(
            "description",
            "Interactive render and matrix viewer for Compose Preview tools.",
          )
          put("mimeType", MCP_APP_MIME_TYPE)
          put("_meta", viewerResourceMeta())
        }
      )
      add(ServeLibraryMcp.resourceDescriptor())
      // UI-builder schemas, static and public, so an agent has them before its first call rather
      // than after a refusal.
      if (uiBuilder != null) {
        UiBuilderJsonSchemas.served.forEach { schema ->
          add(
            buildJsonObject {
              put("uri", schema.uri)
              put("name", schema.title)
              put("description", schema.description)
              put("mimeType", UiBuilderJsonSchemas.MEDIA_TYPE)
            }
          )
        }
      }
      catalogIds().forEach { catalog ->
        val view = peekCatalog(catalog)
        view.previews?.forEach { preview ->
          add(
            buildJsonObject {
              put("uri", resourceUri(catalog, preview.id))
              put("name", "${view.label}: ${preview.label}")
              put("description", "$catalog: ${preview.id}")
              put("mimeType", "image/png")
            }
          )
        }
      }
    }
    return buildJsonObject { put("resources", resources) }
  }

  private suspend fun readResource(
    params: JsonObject,
    presentedToken: String?,
    liveAuthorization: (String?) -> ServeMachineAuthorization.Decision,
  ): JsonObject {
    val uri = params.requiredString("uri")
    UiBuilderJsonSchemas.byUri(uri)
      ?.takeIf { uiBuilder != null }
      ?.let { schema ->
        return buildJsonObject {
          putJsonArray("contents") {
            add(
              buildJsonObject {
                put("uri", uri)
                put("mimeType", UiBuilderJsonSchemas.MEDIA_TYPE)
                put("text", schema.text)
              }
            )
          }
        }
      }
    if (uri == ServeLibraryMcp.RESOURCE_URI) return ServeLibraryMcp.readResource()
    if (uri == MCP_APP_VIEWER_URI) {
      return buildJsonObject {
        put(
          "contents",
          buildJsonArray {
            add(
              buildJsonObject {
                put("uri", uri)
                put("mimeType", MCP_APP_MIME_TYPE)
                put("text", viewerHtml())
                put("_meta", viewerResourceMeta())
              }
            )
          },
        )
      }
    }
    val target = targetFromUri(uri)
    // Checked before a catalog is leased: a signed link is admitted at the door without a grant
    // (see [requiresGrant]), so anything short of a valid signature must hold live scope here.
    val rawOverrides = resourceOverrides(uri)
    if ((rawOverrides != null || isSignedOverrideUri(uri)) && !hasValidResourceSignature(uri)) {
      requireLive { liveAuthorization(presentedToken) }
    }
    return withCatalog(target.catalog) { host ->
      val preview = resolvePreview(host, target.previewId)
      val png =
        renderPng(
            host,
            preview.id,
            parseOverrides(preview, rawOverrides),
            preferPublished = rawOverrides == null,
          )
          .png
      buildJsonObject {
        put(
          "contents",
          buildJsonArray {
            add(
              buildJsonObject {
                put("uri", uri)
                put("mimeType", "image/png")
                put("blob", Base64.getEncoder().encodeToString(png))
              }
            )
          },
        )
      }
    }
  }

  /**
   * The cross-product of [axes] over one preview, rendered cell by cell under one shared lease
   * (instead of many `render_preview` round trips). Each cell still takes the render permit,
   * competing fairly with browser traffic. Base `overrides` apply to every cell; an axis value with
   * the same key wins.
   */
  private suspend fun matrixResult(
    host: ServeHost,
    preview: ServePreview,
    catalog: String,
    args: JsonObject,
  ): JsonObject {
    val observe = args["observe"]?.jsonPrimitive?.contentOrNull?.lowercase() ?: "hash"
    if (observe !in MATRIX_OBSERVATION_MODES) {
      throw McpRequestException("catalog_render_matrix 'observe' must be one of png or hash")
    }
    val rawAxes =
      args["axes"] as? JsonObject
        ?: throw McpRequestException("catalog_render_matrix requires an 'axes' object")
    if (rawAxes.isEmpty())
      throw McpRequestException("catalog_render_matrix requires at least one axis")
    val base =
      (args["overrides"] as? JsonObject)?.mapValues { (_, v) -> v.asOverrideString() }.orEmpty()

    val axes = rawAxes.map { (key, value) ->
      val values =
        (value as? JsonArray) ?: throw McpRequestException("axis '$key' must be an array of values")
      if (values.isEmpty()) throw McpRequestException("axis '$key' must list at least one value")
      key to values.map { it.asOverrideString() }
    }

    // Refused before any rendering: the cap exists to bound machine time, so discovering it after
    // spending most of that time would defeat it.
    val cells = axes.fold(1) { acc, (_, values) -> acc * values.size }
    if (cells > MAX_MATRIX_CELLS) {
      throw McpRequestException(
        "catalog_render_matrix would produce $cells cells; the cap is $MAX_MATRIX_CELLS. " +
          "Narrow an axis or split the call."
      )
    }

    val combinations =
      axes.fold(listOf(base)) { acc, (key, values) ->
        acc.flatMap { partial -> values.map { partial + (key to it) } }
      }

    val knobKinds = ServeOverrides.declaredKnobKinds(preview)
    val uri = resourceUri(catalog, preview.id)
    // With a public origin, pixels leave the text: each cell gets a signed re-renderable https
    // link, the viewer gets bytes in `_meta`, and chat surfaces get one numbered contact sheet
    // (Slack allows five attachments; a matrix is up to 24).
    val linked = observe == "png" && publicOrigin() != null
    val cellPngs = mutableListOf<ByteArray>()
    val cellLabels = mutableListOf<String>()
    val axisKeys = axes.map { it.first }
    // Every cell's knobs are checked before any cell renders, like the cell cap above: a refusal
    // discovered on cell 7 would already have spent six renders answering nothing.
    combinations.forEach { refuseUndeclaredKnobs(preview, it, knobKinds) }
    val rendered = buildJsonArray {
      combinations.forEachIndexed { index, params ->
        val unknown = params.keys.filterNot(ServeOverrides::isOverrideParam).sorted()
        if (unknown.isNotEmpty()) {
          throw McpRequestException(
            "unknown override ${unknown.joinToString()} in catalog_render_matrix"
          )
        }
        val overrides =
          when (val parsed = ServeRcPlayerIds.parseOverrides(params, knobKinds)) {
            is OverrideParse.Ok -> parsed.overrides
            is OverrideParse.Invalid -> throw McpRequestException(parsed.message)
          }
        val cell = renderPng(host, preview.id, overrides)
        add(
          buildJsonObject {
            put(
              "overrides",
              JsonObject(params.mapValues { (_, v) -> JsonPrimitive(v) }),
            )
            put("sha256", sha256Hex(cell.png))
            put("sizeBytes", cell.png.size)
            pngDimensions(cell.png)?.let { (width, height) ->
              put("widthPx", width)
              put("heightPx", height)
            }
            put("generation", cell.generation.wire)
            if (linked) {
              put("index", index + 1)
              val cellOverrides = JsonObject(params.mapValues { (_, v) -> JsonPrimitive(v) })
              signedImageUrl(resourceUriWithOverrides(uri, cellOverrides))?.let {
                put("imageUrl", it)
              }
              cellPngs += cell.png
              cellLabels += axisKeys.joinToString(", ") { key -> "$key=${params[key]}" }
            } else if (observe == "png") {
              put("png", Base64.getEncoder().encodeToString(cell.png))
            }
          }
        )
      }
    }

    // Distinct hashes across the matrix: all-identical means the axes don't affect this preview.
    val distinct =
      rendered.mapNotNull { it.jsonObject["sha256"]?.jsonPrimitive?.contentOrNull }.toSet().size
    val sheet =
      if (linked) ServeContactSheet.render(cellPngs, cellLabels)?.let { signedViewUrl(it) }
      else null
    val body = buildJsonObject {
      put("schema", "compose-preview/catalog-mcp-matrix/v1")
      put("catalog", catalog)
      put("previewId", preview.id)
      put("uri", uri)
      put("observe", observe)
      put("cellCount", rendered.size)
      put("distinctRenders", distinct)
      sheet?.let { (url, expiry) ->
        putJsonObject("contactSheet") {
          put("url", url)
          put("expiresAtEpochSeconds", expiry)
          put(
            "description",
            "One PNG of every cell, each captioned with its 'index' and overrides. To let a " +
              "person pick in chat, " +
              "post this with the numbered options and take their reply as the choice.",
          )
        }
      }
      put("cells", rendered)
    }
    if (!linked) return textResult(body.toString())
    return buildJsonObject {
      put(
        "content",
        buildJsonArray {
          add(textContent(body.toString()))
          sheet?.let { (url, expiry) ->
            add(imageUrlLinkContent(url))
            add(chatImageText(url, expiry))
          }
        },
      )
      putJsonObject("_meta") {
        putJsonArray(CELL_PNGS_META_KEY) {
          cellPngs.forEach { add(Base64.getEncoder().encodeToString(it)) }
        }
      }
    }
  }

  /**
   * The `device` override's vocabulary, resolved through [DeviceDimensions.resolve] (the render
   * path's own call). Exists because an unknown device name silently falls back to the default
   * frame.
   */
  /**
   * One preview's render timeline, with `mode` saying which shape, so "no versions" isn't misread
   * as "never changed":
   * - `published`: the catalog's `history.json` on its delivery branch, returned as a URL (not
   *   proxied, to avoid a stale copy) plus a per-render URL template;
   * - `local`: project mode, derived from the checkout and served inline via
   *   `/history/render/<blob>.png`;
   * - `none`: an uploaded bundle with neither, reported with a reason.
   */
  private suspend fun historyJson(
    host: ServeHost,
    catalog: String,
    previewId: String,
  ): JsonObject {
    val provenance = bundleHost(host)?.provenance
    val base = buildJsonObject {
      put("schema", "compose-preview/catalog-mcp-history/v1")
      put("catalog", catalog)
      put("previewId", previewId)
      put("uri", resourceUri(catalog, previewId))
    }

    if (provenance != null) {
      val manifestUrl = ServeUrls.historyManifestUrl(provenance.repo, provenance.branch)
      val bundle = bundleHost(host)
      // The load already parsed `history.json` from the served tree, so answer with this preview's
      // slice rather than a ~1 MB whole-catalog document.
      val timeline = bundle?.indexedTimeline(previewId)
      return JsonObject(
        base +
          buildJsonObject {
            put("mode", "published")
            put("repo", provenance.repo)
            put("branch", provenance.branch)
            provenance.commit?.let { put("commit", it) }
            manifestUrl?.let { put("manifestUrl", it) }
            put(
              "renderUrlTemplate",
              "https://raw.githubusercontent.com/${provenance.repo}/{commit}/{path}",
            )
            if (timeline == null) {
              put(
                "reason",
                if (manifestUrl == null)
                  "this catalog names a delivery branch but not one a manifest URL can be built " +
                    "from, so its timeline is not addressable"
                else
                  "this catalog's publisher ships no history.json, or none naming this preview; " +
                    "manifestUrl is where one would be if the branch grows one",
              )
            } else {
              // Pinned to the catalog, not to the branch tip: an agent comparing this against a
              // later answer needs to know which catalog state it described.
              provenance.commit?.let { put("pinnedCommit", it) }
              put("path", timeline.path)
              put("observations", timeline.observations)
              put("unstable", timeline.unstable)
              put("flapCount", timeline.flapCount)
              put("versions", versionsJson(timeline, provenance.repo))
            }
          }
      )
    }

    val history =
      projectHistory
        ?: return JsonObject(
          base +
            buildJsonObject {
              put("mode", "none")
              put(
                "reason",
                "this catalog has no delivery-branch provenance and this server has no checkout to " +
                  "derive a timeline from; an uploaded bundle carries no history",
              )
            }
        )

    // Off the request dispatcher: the first call per refresh window shells out to `git log`.
    val timeline =
      withContext(Dispatchers.IO) { history.timelineJsonFor(previewId) }
        ?: return JsonObject(
          base +
            buildJsonObject {
              put("mode", "local")
              put("versions", JsonArray(emptyList()))
              put(
                "reason",
                "the local delivery-branch timeline names fewer than two distinct renders for this " +
                  "preview, so there is no change to show",
              )
            }
        )

    val parsed = runCatching { JSON.parseToJsonElement(timeline).jsonObject }.getOrNull()
    val entry = parsed?.get("previews")?.jsonObject?.get(previewId)?.jsonObject
    return JsonObject(
      base +
        buildJsonObject {
          put("mode", "local")
          entry?.get("unstable")?.let { put("unstable", it) }
          entry?.get("flapCount")?.let { put("flapCount", it) }
          entry?.get("observations")?.let { put("observations", it) }
          put(
            "versions",
            buildJsonArray {
              entry?.get("versions")?.jsonArray?.forEach { version ->
                val v = version.jsonObject
                add(
                  JsonObject(
                    v +
                      buildJsonObject {
                        // Content-addressed and constrained to blobs this timeline already names,
                        // so the URL cannot be steered at an arbitrary object in the repository.
                        v["blob"]?.jsonPrimitive?.contentOrNull?.let {
                          put("renderUrl", "/history/render/$it.png")
                        }
                      }
                  )
                )
              }
            },
          )
        }
    )
  }

  /**
   * The manifest's versions, each with the `raw.githubusercontent.com/<repo>/<commit>/<path>` URL
   * serving those bytes (the branch tip carries only current bytes).
   */
  private fun versionsJson(
    timeline: PreviewHistoryManifest.PreviewTimeline,
    repo: String,
  ): JsonArray = buildJsonArray {
    timeline.versions.forEach { version ->
      add(
        buildJsonObject {
          put("commit", version.commit)
          put("date", version.date)
          put("blob", version.blob)
          put("commits", version.commits)
          version.sourceSha?.let { put("sourceSha", it) }
          version.occurrences?.let { put("occurrences", it) }
          put(
            "renderUrl",
            "https://raw.githubusercontent.com/$repo/${version.commit}/${timeline.path}",
          )
        }
      )
    }
  }

  /**
   * One version of a render: `blob` is project mode's address, `commit` the published lane's; both
   * are present.
   */
  private data class HistoryVersion(
    val commit: String,
    val blob: String,
    val date: String,
    val path: String?,
  )

  private data class HistoryView(
    val mode: String,
    val versions: List<HistoryVersion>,
    val unstable: Boolean,
    val repo: String?,
  )

  /**
   * The timeline behind [previewId] for the diff and read lanes, with [historyJson]'s precedence
   * (published manifest over local checkout).
   */
  private suspend fun historyView(host: ServeHost, previewId: String): HistoryView? {
    val bundle = bundleHost(host)
    val provenance = bundle?.provenance
    if (provenance != null) {
      val timeline = bundle.indexedTimeline(previewId) ?: return null
      return HistoryView(
        mode = "published",
        versions =
          timeline.versions.map { HistoryVersion(it.commit, it.blob, it.date, timeline.path) },
        unstable = timeline.unstable,
        repo = provenance.repo,
      )
    }
    val history = projectHistory ?: return null
    val json = withContext(Dispatchers.IO) { history.timelineJsonFor(previewId) } ?: return null
    val entry =
      runCatching { JSON.parseToJsonElement(json).jsonObject }
        .getOrNull()
        ?.get("previews")
        ?.jsonObject
        ?.get(previewId)
        ?.jsonObject ?: return null
    return HistoryView(
      mode = "local",
      versions =
        entry["versions"]?.jsonArray.orEmpty().mapNotNull { element ->
          val v = element.jsonObject
          val commit = v["commit"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
          val blob = v["blob"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
          HistoryVersion(
            commit,
            blob,
            v["date"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            entry["path"]?.jsonPrimitive?.contentOrNull,
          )
        },
      unstable = entry["unstable"]?.jsonPrimitive?.contentOrNull == "true",
      repo = null,
    )
  }

  /**
   * Compare two of a preview's renders as a metadata diff: versions are already distinct renders,
   * so blob ids answer "did the bytes change" with no image fetch (`history_read` gets pixels).
   * Defaults to the two newest.
   */
  private suspend fun diffHistoryResult(args: JsonObject): JsonObject {
    val target = args.previewTarget()
    return withCatalog(target.catalog) { host ->
      val preview = resolvePreview(host, target.previewId)
      val view =
        historyView(host, preview.id)
          ?: throw McpRequestException(
            "no timeline for '${preview.id}'; call catalog_history_list to see why this catalog has none"
          )
      if (view.versions.size < 2) {
        throw McpRequestException(
          "'${preview.id}' has ${view.versions.size} recorded render(s); a diff needs two"
        )
      }
      val requestedTo = args.optionalString("to")
      val requestedFrom = args.optionalString("from")
      // Newest first, as the manifest orders them.
      val toIndex =
        requestedTo?.let { wanted -> view.versions.indexOfFirst { it.commit.startsWith(wanted) } }
          ?: 0
      val fromIndex =
        requestedFrom?.let { wanted -> view.versions.indexOfFirst { it.commit.startsWith(wanted) } }
          ?: 1
      if (toIndex < 0) throw McpRequestException("no recorded render at commit '$requestedTo'")
      if (fromIndex < 0) throw McpRequestException("no recorded render at commit '$requestedFrom'")
      val to = view.versions[toIndex]
      val from = view.versions[fromIndex]

      textResult(
        buildJsonObject {
          put("schema", "compose-preview/catalog-mcp-history-diff/v1")
          put("mode", view.mode)
          put("catalog", target.catalog)
          put("previewId", preview.id)
          put("from", versionRef(from, view))
          put("to", versionRef(to, view))
          put("changed", from.blob != to.blob)
          // Strictly between, in either direction — the caller may name them either way round.
          put("versionsBetween", (kotlin.math.abs(toIndex - fromIndex) - 1).coerceAtLeast(0))
          put("unstable", view.unstable)
          if (view.unstable) {
            put(
              "note",
              "this preview is marked unstable: it re-renders differently on publishes that " +
                "did not change it, so a difference here is not evidence of a real change",
            )
          }
        }
          .toString()
      )
    }
  }

  private fun versionRef(version: HistoryVersion, view: HistoryView): JsonObject = buildJsonObject {
    put("commit", version.commit)
    put("blob", version.blob)
    if (version.date.isNotEmpty()) put("date", version.date)
    val path = version.path
    if (view.repo != null && path != null) {
      put("renderUrl", "https://raw.githubusercontent.com/${view.repo}/${version.commit}/$path")
    } else {
      put("renderUrl", "/history/render/${version.blob}.png")
    }
  }

  /**
   * One historical render's bytes. `preview` scope since it replays published bytes and commissions
   * no render; still bounded by [ServeBundleHost]'s pinned-fetch permit and miss cache, or limited
   * to blobs the local timeline names.
   */
  private suspend fun readHistoryResult(args: JsonObject): JsonObject {
    val target = args.previewTarget()
    val wanted =
      args.optionalString("commit")
        ?: args.optionalString("blob")
        ?: throw McpRequestException("catalog_history_read requires 'commit' or 'blob'")
    return withCatalog(target.catalog) { host ->
      val preview = resolvePreview(host, target.previewId)
      val view =
        historyView(host, preview.id)
          ?: throw McpRequestException(
            "no timeline for '${preview.id}'; call catalog_history_list to see why this catalog has none"
          )
      val version =
        view.versions.firstOrNull { it.commit.startsWith(wanted) || it.blob.startsWith(wanted) }
          ?: throw McpRequestException(
            "'$wanted' names no recorded render of '${preview.id}'; catalog_history_list lists the ones " +
              "this catalog can serve"
          )
      val bytes =
        if (view.mode == "published") {
          val bundle =
            bundleHost(host) ?: throw McpRequestException("this catalog has no pinned-asset lane")
          when (val outcome = bundle.pinnedIndexedRender(version.commit, preview.id)) {
            is ServeBundleHost.PinnedOutcome.Ok -> outcome.bytes
            ServeBundleHost.PinnedOutcome.Busy ->
              throw McpRequestException("branch fetch queue saturated; retry shortly")
            ServeBundleHost.PinnedOutcome.Missing ->
              throw McpRequestException(
                "the delivery branch has no render for '${preview.id}' at ${version.commit}"
              )
          }
        } else {
          withContext(Dispatchers.IO) { projectHistory?.renderBytes(version.blob) }
            ?: throw McpRequestException(
              "the local repository has no object ${version.blob} for '${preview.id}'"
            )
        }
      buildJsonObject {
        put(
          "content",
          buildJsonArray {
            add(imageContent(bytes))
            add(textContent(versionRef(version, view).toString()))
            keptImageFallback(bytes).forEach(::add)
          },
        )
      }
    }
  }

  /** The baked bundle behind [host], which is where delivery-branch provenance lives. */
  private fun bundleHost(host: ServeHost): ServeBundleHost? =
    when (host) {
      is ServeBundleHost -> host
      is ServeCatalogLiveHost -> host.bakedHost as? ServeBundleHost
      is ServePerPreviewLiveHost -> host.bakedHost as? ServeBundleHost
      else -> null
    }

  private fun devicesJson(): JsonObject = buildJsonObject {
    put("schema", "compose-preview/catalog-mcp-devices/v1")
    put(
      "devices",
      buildJsonArray {
        DeviceDimensions.KNOWN_DEVICE_IDS.forEach { id ->
          val spec = DeviceDimensions.resolve(id)
          add(
            buildJsonObject {
              put("id", id)
              put("widthDp", spec.widthDp)
              put("heightDp", spec.heightDp)
              put("density", spec.density.toDouble())
            }
          )
        }
      },
    )
  }

  /**
   * Compare two previews' semantics by authored testTag, not positional ref ([ServeSemanticsTags]):
   * refs retarget when siblings are inserted, while a tag survives an edit or stops resolving.
   * Reads the `tags` index from each side's [ServeAnnotationsPayload], including `count` (a tag is
   * an identity only while exactly one node carries it).
   */
  /** The two previews `diff_semantics` compares, validated before any grant check. */
  private fun diffSemanticsTargets(args: JsonObject): Pair<PreviewTarget, PreviewTarget> {
    val left = args.previewTarget()
    val other =
      args["other"] as? JsonObject
        ?: throw McpRequestException(
          "catalog_diff_semantics requires an 'other' preview to compare with"
        )
    return left to other.previewTarget()
  }

  private suspend fun diffSemanticsResult(args: JsonObject): JsonObject {
    val (left, right) = diffSemanticsTargets(args)
    val leftOverrides = args["overrides"] as? JsonObject
    val rightOverrides = args["otherOverrides"] as? JsonObject

    val leftTags = tagIndex(left, leftOverrides)
    val rightTags = tagIndex(right, rightOverrides)

    val onlyLeft = (leftTags.keys - rightTags.keys).sorted()
    val onlyRight = (rightTags.keys - leftTags.keys).sorted()
    val shared = leftTags.keys.intersect(rightTags.keys).sorted()

    val moved = buildJsonArray {
      shared.forEach { tag ->
        val a = leftTags[tag]!!
        val b = rightTags[tag]!!
        val boundsA = a["bounds"]
        val boundsB = b["bounds"]
        val countA = a["count"]?.jsonPrimitive?.contentOrNull
        val countB = b["count"]?.jsonPrimitive?.contentOrNull
        if (boundsA == boundsB && countA == countB) return@forEach
        add(
          buildJsonObject {
            put("testTag", tag)
            if (boundsA != boundsB) {
              put(
                "bounds",
                buildJsonObject {
                  put("before", boundsA ?: JsonNull)
                  put("after", boundsB ?: JsonNull)
                },
              )
            }
            if (countA != countB) {
              // A count change (ambiguity appearing or disappearing) is reported separately from a
              // move.
              put(
                "count",
                buildJsonObject {
                  put("before", a["count"] ?: JsonNull)
                  put("after", b["count"] ?: JsonNull)
                },
              )
            }
          }
        )
      }
    }

    return textResult(
      buildJsonObject {
        put("schema", "compose-preview/catalog-mcp-semantics-diff/v1")
        put("identity", "testTag")
        put(
          "left",
          buildJsonObject {
            put(
              "uri",
              resourceUriWithOverrides(
                resourceUri(left.catalog, left.previewId),
                leftOverrides,
              ),
            )
            put("taggedNodes", leftTags.size)
          },
        )
        put(
          "right",
          buildJsonObject {
            put(
              "uri",
              resourceUriWithOverrides(
                resourceUri(right.catalog, right.previewId),
                rightOverrides,
              ),
            )
            put("taggedNodes", rightTags.size)
          },
        )
        put("onlyInLeft", JsonArray(onlyLeft.map(::JsonPrimitive)))
        put("onlyInRight", JsonArray(onlyRight.map(::JsonPrimitive)))
        put("changed", moved)
        put(
          "identical",
          JsonPrimitive(onlyLeft.isEmpty() && onlyRight.isEmpty() && moved.isEmpty()),
        )
        if (leftTags.isEmpty() && rightTags.isEmpty()) {
          put(
            "note",
            "neither preview carries a testTag, so there is nothing to compare by; this is an " +
              "empty result, not a match",
          )
        }
      }
        .toString()
    )
  }

  /** One side's `testTag -> {count, bounds, space}` index, off its annotations payload. */
  private suspend fun tagIndex(
    target: PreviewTarget,
    rawOverrides: JsonObject?,
  ): Map<String, JsonObject> =
    withCatalog(target.catalog) { host ->
      val preview = resolvePreview(host, target.previewId)
      val overrides = parseOverrides(preview, rawOverrides)
      val payload =
        withRenderPermit {
          when (val outcome = host.renderAnnotations(preview.id, overrides)) {
            is AnnotationsOutcome.Ok -> outcome.json
            AnnotationsOutcome.NotFound -> null
            is AnnotationsOutcome.Failed -> throw McpRequestException(outcome.reason)
          }
        }
          ?: throw McpRequestException(
            "compose/semantics is not available for '${target.previewId}', so it cannot be diffed"
          )
      val tags =
        runCatching { JSON.parseToJsonElement(payload.decodeToString()) }
          .getOrNull()
          ?.let { it as? JsonObject }
          ?.get("tags") as? JsonObject
      tags?.mapValues { (_, value) -> value as? JsonObject ?: JsonObject(emptyMap()) }.orEmpty()
    }

  private suspend fun renderResult(
    host: ServeHost,
    previewId: String,
    uri: String,
    overrides: PreviewOverrides,
    observe: String,
    requestedKeys: List<String> = emptyList(),
    rawOverrides: JsonObject? = null,
  ): JsonObject = buildJsonObject {
    val content =
      renderContent(host, previewId, uri, overrides, observe, requestedKeys, rawOverrides)
    put("content", JsonArray(content))
    // Also as `structuredContent.imageUrl`, for hosts that read the structure not the blocks.
    content
      .firstOrNull { it["name"]?.jsonPrimitive?.contentOrNull == IMAGE_URL_LINK_NAME }
      ?.get("uri")
      ?.let { url -> putJsonObject("structuredContent") { put("imageUrl", url) } }
  }

  private suspend fun renderContent(
    host: ServeHost,
    previewId: String,
    uri: String,
    overrides: PreviewOverrides,
    observe: String,
    requestedKeys: List<String> = emptyList(),
    rawOverrides: JsonObject? = null,
  ): List<JsonObject> {
    if (observe !in OBSERVATION_MODES) {
      throw McpRequestException("'observe' must be one of ${OBSERVATION_MODES.orList()}")
    }
    // Answered before the raster below, deliberately: each of these lanes has its own export and
    // would otherwise pay for a viewport PNG whose bytes are then discarded.
    if (observe == "svg") return listOf(textContent(renderSvg(host, previewId, overrides)))
    if (observe == "scroll-svg") {
      return listOf(textContent(renderScrollSvg(host, previewId, overrides)))
    }
    if (observe == "scroll-png") {
      // A full-page capture cannot be replayed from a signed resource URI (that lane renders the
      // viewport), so the picture is kept for the link's lifetime, as a `ui_builder_view` is.
      val png = renderScrollPng(host, previewId, overrides)
      return listOf(imageContent(png)) + keptImageFallback(png)
    }
    val rendered = renderPng(host, previewId, overrides)
    val png = rendered.png
    if (observe == "png") {
      // Override-bearing renders get a provenance block, since identical pixels can mean the
      // overrides applied and changed nothing or that a baked lane ignored them.
      val renderedUri = resourceUriWithOverrides(uri, rawOverrides)
      val signed = signedImage(renderedUri)
      val imageUrl = listOfNotNull(signed?.let { imageUrlLinkContent(it.first) })
      // Last, so a caller reading the first text block still finds the provenance JSON there.
      val chatLine = listOfNotNull(signed?.let { chatImageText(it.first, it.second) })
      return if (requestedKeys.isEmpty())
        listOf(imageContent(png), resourceLinkContent(renderedUri)) + imageUrl + chatLine
      else
        listOf(
          imageContent(png),
          resourceLinkContent(renderedUri),
        ) +
          imageUrl +
          textContent(JsonObject(provenance(rendered, requestedKeys)).toString()) +
          chatLine
    }

    val observation = buildJsonObject {
      put("observe", observe)
      put("uri", uri)
      put("sha256", sha256Hex(png))
      put("sizeBytes", png.size)
      pngDimensions(png)?.let { (width, height) ->
        put("widthPx", width)
        put("heightPx", height)
      }
      provenance(rendered, requestedKeys).forEach { (key, value) -> put(key, value) }
      if (observe == "semantics") {
        val semantics = withRenderPermit {
          when (val outcome = host.renderAnnotations(previewId, overrides)) {
            is AnnotationsOutcome.Ok ->
              runCatching { JSON.parseToJsonElement(outcome.json.decodeToString()) }.getOrNull()
            AnnotationsOutcome.NotFound,
            is AnnotationsOutcome.Failed -> null
          }
        }
        if (semantics == null) {
          put("semanticsUnavailable", "compose/semantics is not available for this catalog preview")
        } else {
          put("semantics", semantics)
        }
      }
    }
    return listOf(
      textContent(observation.toString()),
      resourceLinkContent(resourceUriWithOverrides(uri, rawOverrides)),
    )
  }

  private suspend fun dataProductResult(
    host: ServeHost,
    previewId: String,
    kind: String,
    overrides: PreviewOverrides,
  ): JsonObject {
    // A stored result, not a render: no permit, and an answer either way.
    if (kind == GUIDELINES_RESULT_KIND) return textResult(guidelinesResultJson(host, previewId))
    val bytes = withRenderPermit {
      when {
        kind.startsWith("a11y/") ->
          when (val outcome = host.renderA11y(previewId, overrides)) {
            is A11yOutcome.Ok -> outcome.json
            A11yOutcome.NotFound -> throw McpRequestException("data product '$kind' unavailable")
            is A11yOutcome.Failed -> throw McpRequestException(outcome.reason)
          }
        kind in ANNOTATION_KINDS ->
          when (val outcome = host.renderAnnotations(previewId, overrides)) {
            is AnnotationsOutcome.Ok -> outcome.json
            AnnotationsOutcome.NotFound ->
              throw McpRequestException("data product '$kind' unavailable")
            is AnnotationsOutcome.Failed -> throw McpRequestException(outcome.reason)
          }
        else -> throw McpRequestException("unsupported data product '$kind'")
      }
    }
    return textResult(bytes.decodeToString())
  }

  /**
   * What answered this request and whether the requested overrides reached it
   * ([RenderOutcome.Generation]; `baked` means no renderer ran).
   */
  private fun provenance(
    rendered: Rendered,
    requestedKeys: List<String>,
  ): Map<String, JsonElement> = buildMap {
    put("generation", JsonPrimitive(rendered.generation.wire))
    if (requestedKeys.isEmpty()) return@buildMap
    put("requestedOverrides", JsonArray(requestedKeys.sorted().map(::JsonPrimitive)))
    if (rendered.generation == RenderOutcome.Generation.BAKED) {
      put(
        "overridesApplied",
        JsonPrimitive(false),
      )
      put(
        "overridesIgnoredReason",
        JsonPrimitive(
          "answered from the published bundle, which carries no renderer; these overrides are " +
            "not reflected in the returned bytes"
        ),
      )
    } else {
      put("overridesApplied", JsonPrimitive(true))
    }
  }

  /** A render plus how it was produced — [RenderOutcome.Generation] is the diagnosis, see below. */
  private data class Rendered(val png: ByteArray, val generation: RenderOutcome.Generation)

  private suspend fun renderPng(
    host: ServeHost,
    previewId: String,
    overrides: PreviewOverrides,
    preferPublished: Boolean = false,
  ): Rendered {
    if (preferPublished) {
      val baked =
        host.bakedRender(previewId, overrides)
          ?: throw McpRequestException(
            "published preview '$previewId' is unavailable; use catalog_render_preview with live scope"
          )
      return Rendered(baked.png, RenderOutcome.Generation.BAKED)
    }
    return withRenderPermit {
      when (val outcome = host.render(previewId, overrides)) {
        is RenderOutcome.Ok -> Rendered(outcome.png, outcome.generation)
        RenderOutcome.NotFound -> throw McpRequestException("no such preview '$previewId'")
        RenderOutcome.Busy -> throw McpRequestException("render busy; retry shortly")
        is RenderOutcome.Failed -> throw McpRequestException(outcome.reason)
      }
    }
  }

  /**
   * The `compose/figma-svg` counterpart of [renderPng], returned as SVG source (SVG image blocks
   * render in almost no MCP client). Shares [withRenderPermit], so it isn't an unmetered render
   * path.
   */
  private suspend fun renderSvg(
    host: ServeHost,
    previewId: String,
    overrides: PreviewOverrides,
  ): String {
    // Distinguishes "this catalog has no vectors" from "you typed the id wrong", which a bare
    // NotFound below cannot: both arrive as the same outcome.
    if (!host.hasSvgExportFor(previewId)) {
      throw McpRequestException(
        "preview '$previewId' has no compose/figma-svg export; this catalog serves raster only"
      )
    }
    return withRenderPermit {
      when (val outcome = host.renderSvg(previewId, overrides)) {
        is SvgOutcome.Ok -> outcome.svg.decodeToString()
        SvgOutcome.NotFound -> throw McpRequestException("no such preview '$previewId'")
        is SvgOutcome.Failed -> throw McpRequestException(outcome.reason)
      }
    }
  }

  /**
   * Full-page lanes (`compose/figma-svg-long`, `render/scroll/long`), gated on
   * [ServeHost.hasScrollExportFor]: they need a daemon, and a static bundle's `NotFound` would read
   * as "no such preview".
   */
  private fun requireScroll(host: ServeHost, previewId: String) {
    if (!host.hasScrollExportFor(previewId)) {
      throw McpRequestException(
        "preview '$previewId' has no full-page scroll export; the tall re-render needs a daemon, " +
          "and this catalog is serving published bytes"
      )
    }
  }

  private suspend fun renderScrollSvg(
    host: ServeHost,
    previewId: String,
    overrides: PreviewOverrides,
  ): String {
    requireScroll(host, previewId)
    return withRenderPermit {
      when (val outcome = host.renderScrollSvg(previewId, overrides)) {
        is SvgOutcome.Ok -> outcome.svg.decodeToString()
        SvgOutcome.NotFound -> throw McpRequestException("no such preview '$previewId'")
        is SvgOutcome.Failed -> throw McpRequestException(outcome.reason)
      }
    }
  }

  private suspend fun renderScrollPng(
    host: ServeHost,
    previewId: String,
    overrides: PreviewOverrides,
  ): ByteArray {
    requireScroll(host, previewId)
    return withRenderPermit {
      when (val outcome = host.renderScrollPng(previewId, overrides)) {
        is RenderOutcome.Ok -> outcome.png
        RenderOutcome.NotFound -> throw McpRequestException("no such preview '$previewId'")
        RenderOutcome.Busy -> throw McpRequestException("render busy; retry shortly")
        is RenderOutcome.Failed -> throw McpRequestException(outcome.reason)
      }
    }
  }

  private suspend fun <T> withRenderPermit(block: () -> T): T =
    withContext(Dispatchers.IO) {
      if (!renderSemaphore.tryAcquire(renderQueueWaitSeconds, TimeUnit.SECONDS)) {
        throw McpRequestException("render queue saturated; retry shortly")
      }
      try {
        block()
      } finally {
        renderSemaphore.release()
      }
    }

  private fun parseOverrides(preview: ServePreview, raw: JsonObject?): PreviewOverrides {
    if (raw == null || raw.isEmpty()) return PreviewOverrides()
    val params = raw.mapValues { (_, value) -> value.asOverrideString() }
    // Unknown keys are refused here (unlike `GET /render`, which tolerates cache-busters): every
    // MCP override key was typed on purpose, and silently dropping one answers a different
    // question.
    val unknown = params.keys.filterNot(ServeOverrides::isOverrideParam).sorted()
    if (unknown.isNotEmpty()) {
      throw McpRequestException(
        "unknown override ${if (unknown.size == 1) "key" else "keys"} ${unknown.joinToString()}; " +
          "supported: ${ServeOverrides.SUPPORTED_KEYS.sorted().joinToString()}, " +
          "plus ${ServeOverrides.KNOB_PREFIX}<knob> and ${ServeOverrides.RC_NAMED_PREFIX}<name>"
      )
    }
    val knobKinds = ServeOverrides.declaredKnobKinds(preview)
    refuseUndeclaredKnobs(preview, params, knobKinds)
    return when (val parsed = ServeRcPlayerIds.parseOverrides(params, knobKinds)) {
      is OverrideParse.Ok -> parsed.overrides
      is OverrideParse.Invalid -> throw McpRequestException(parsed.message)
    }
  }

  /**
   * The same refusal applied to knobs (#1277): the renderer silently drops undeclared knobs and
   * unparseable values, so each is checked against [ServeOverrides.declaredKnobKinds] and a
   * mismatch names what the preview declares. Unknown kinds pass through to the renderer.
   */
  private fun refuseUndeclaredKnobs(
    preview: ServePreview,
    params: Map<String, String?>,
    knobKinds: Map<String, String>,
  ) {
    for ((key, value) in params) {
      if (!key.startsWith(ServeOverrides.KNOB_PREFIX)) continue
      val name = key.removePrefix(ServeOverrides.KNOB_PREFIX)
      val kind =
        knobKinds[name]
          ?: knobKinds[key]
          ?: throw McpRequestException(
            if (knobKinds.isEmpty()) {
              "'${preview.id}' declares no knobs, so '$key' would change nothing; " +
                "catalog_list_previews lists each preview's knobs"
            } else {
              "'${preview.id}' declares no knob '$name'; its knobs are " +
                knobKinds.keys.sorted().joinToString { ServeOverrides.KNOB_PREFIX + it }
            }
          )
      if (value != null && !knobValueFits(kind, value)) {
        throw McpRequestException("'$key' is a $kind knob, and '$value' is not a $kind")
      }
    }
  }

  private fun knobValueFits(kind: String, value: String): Boolean =
    when (kind.lowercase()) {
      "bool",
      "boolean" -> value.lowercase() == "true" || value.lowercase() == "false"
      "int",
      "long" -> value.trim().toLongOrNull() != null
      "float",
      "double" -> value.trim().toDoubleOrNull() != null
      else -> true
    }

  private fun JsonElement.asOverrideString(): String =
    when (this) {
      is JsonPrimitive ->
        when {
          isString -> content
          booleanOrNull != null -> booleanOrNull.toString()
          longOrNull != null -> longOrNull.toString()
          doubleOrNull != null -> doubleOrNull.toString()
          else -> content
        }
      else -> throw McpRequestException("override values must be strings, numbers, or booleans")
    }

  /**
   * [previewId]'s design-guidelines result, as the catalog's bundle carries it (`guidelines.json`,
   * written by `compose-preview guidelines`): the `GuidelineRecordV1` — verdicts with the nodes and
   * regions they point at, the model that answered and its cost — or a `found: false` answer saying
   * there is none, so a caller can tell "no result" from a failure.
   */
  internal fun guidelinesResultJson(host: ServeHost, previewId: String): String {
    val record = host.guidelineResultFor(previewId)
    return buildJsonObject {
      put("kind", GUIDELINES_RESULT_KIND)
      put("previewId", previewId)
      if (record == null) {
        put("found", false)
        put(
          "message",
          "no guidelines result for this preview: the catalog has not published one for it",
        )
      } else {
        put("found", true)
        put(
          "record",
          GUIDELINES_RESULT_JSON.encodeToJsonElement(
            ee.schimke.composeai.guidelines.protocol.GuidelineRecordV1.serializer(),
            record,
          ),
        )
      }
    }
      .toString()
  }

  private suspend fun dataProductsJson(args: JsonObject): JsonArray {
    val uriTarget = args.optionalString("uri")?.let(::targetFromUri)
    val selectedCatalog = uriTarget?.catalog ?: requireCatalog("list_data_products", args)
    val selectedPreview = uriTarget?.previewId ?: args.optionalString("previewId")
    return buildJsonArray {
      catalogIds(selectedCatalog).forEach { catalog ->
        withCatalog(catalog) { host ->
          host.previews
            .filter { selectedPreview == null || it.id == selectedPreview }
            .forEach { preview ->
              val kinds = buildSet {
                addAll(preview.dataProductKinds)
                if (host.hasA11yOverlayFor(preview.id)) add("a11y/hierarchy")
                if (host.guidelineResultFor(preview.id) != null) add(GUIDELINES_RESULT_KIND)
                if (
                  host.hasDesignAnnotationsFor(preview.id) ||
                    host.hasPublishedTypographyFor(preview.id)
                ) {
                  add("compose/annotations")
                }
              }
              add(
                buildJsonObject {
                  put("catalog", catalog)
                  put("previewId", preview.id)
                  put("uri", resourceUri(catalog, preview.id))
                  put("kinds", JsonArray(kinds.sorted().map(::JsonPrimitive)))
                }
              )
            }
        }
      }
    }
  }

  /**
   * Catalogs for `catalog_library`: every one by label and count, but previews only for `projectId`
   * (listing all returned megabytes).
   */
  private suspend fun libraryCatalogs(args: JsonObject): List<ServeLibraryMcp.Catalog> {
    val selected = args.optionalString("projectId")
    return catalogIds().map { catalog ->
      val peeked = peekCatalog(catalog)
      val previews = if (catalog == selected) withCatalog(catalog) { it.previews } else null
      ServeLibraryMcp.Catalog(
        id = catalog,
        label = peeked.label,
        previews =
          previews?.map { ServeLibraryMcp.Preview(resourceUri(catalog, it.id), it.id, it.label) },
        previewCount = previews?.size ?: peeked.previews?.size,
      )
    }
  }

  private suspend fun statusJson(): JsonObject = buildJsonObject {
    put("schema", "compose-preview-mcp-status/v1")
    put("ready", pendingCatalogs().isEmpty())
    put("remote", true)
    put("aggregate", true)
    put("toolCatalog", buildJsonObject { put("status", "ready") })
    put("projects", projectsJson()["projects"]!!)
    putLoading()
  }

  private suspend fun projectsJson(): JsonObject = buildJsonObject {
    put(
      "projects",
      buildJsonArray {
        catalogIds().forEach { catalog -> add(projectJson(catalog, peekCatalog(catalog))) }
      },
    )
    putLoading()
  }

  private fun projectJson(catalog: String, view: CatalogView): JsonObject = buildJsonObject {
    put("workspaceId", catalog)
    put("rootProjectName", view.label)
    put("catalog", catalog)
    put("label", view.label)
    view.previews?.let { put("previewCount", it.size) }
    put("remote", true)
  }

  /**
   * One page of `list_previews` (a whole large catalog is too big for clients). [query] narrows by
   * id or label before paging.
   */
  private data class PreviewPage(val query: String?, val offset: Int, val limit: Int) {
    fun matches(preview: ServePreview): Boolean =
      query == null ||
        preview.id.contains(query, ignoreCase = true) ||
        preview.label.contains(query, ignoreCase = true)

    companion object {
      fun of(args: JsonObject): PreviewPage {
        fun int(name: String): Int? {
          val value = args[name] ?: return null
          return (value as? JsonPrimitive)?.contentOrNull?.toIntOrNull()
            ?: throw McpRequestException("'$name' must be an integer")
        }
        val offset = int("offset") ?: 0
        val limit = int("limit") ?: DEFAULT_PREVIEW_PAGE
        if (offset < 0) throw McpRequestException("'offset' must not be negative")
        if (limit !in 1..MAX_PREVIEW_PAGE) {
          throw McpRequestException("'limit' must be between 1 and $MAX_PREVIEW_PAGE")
        }
        val query =
          (args["query"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
        return PreviewPage(query, offset, limit)
      }
    }
  }

  private suspend fun previewsJson(selectedCatalog: String?, page: PreviewPage): JsonObject =
    buildJsonObject {
      put(
        "catalogs",
        buildJsonArray {
          catalogIds(selectedCatalog).forEach { catalog ->
            withCatalog(catalog) { host ->
              val matching = host.previews.filter(page::matches)
              val shown = matching.drop(page.offset).take(page.limit)
              add(
                buildJsonObject {
                  put("catalog", catalog)
                  put("label", host.label)
                  page.query?.let { put("query", it) }
                  put("total", matching.size)
                  put("offset", page.offset)
                  put("previews", JsonArray(shown.map { previewJson(catalog, it, host) }))
                  val next = page.offset + shown.size
                  if (next < matching.size) {
                    put("nextOffset", next)
                    put(
                      "note",
                      "Showing ${shown.size} of ${matching.size}. Pass offset=$next for more, or " +
                        "a 'query' (such as a component name) to narrow the list.",
                    )
                  }
                }
              )
            }
          }
        },
      )
    }

  private suspend fun storiesJson(): JsonObject = buildJsonObject {
    val stories = buildJsonArray {
      catalogIds().forEach { catalog ->
        peekCatalog(catalog).previews?.forEach { add(storyJson(catalog, it)) }
      }
    }
    put("schema", "compose-preview-mcp-storybook/v1")
    put("count", stories.size)
    put("stories", stories)
  }

  private fun storyJson(catalog: String, preview: ServePreview): JsonObject = buildJsonObject {
    put("id", storyId(catalog, preview.id))
    put("storyId", storyId(catalog, preview.id))
    put("title", hostTitle(preview))
    put("name", preview.label)
    put("type", "story")
    put("importPath", "virtual:compose-preview/${preview.id}")
    put("catalog", catalog)
    put("uri", resourceUri(catalog, preview.id))
  }

  private fun storyDocumentationJson(
    catalog: String,
    preview: ServePreview,
    host: ServeHost,
  ): JsonObject =
    JsonObject(
      previewJson(catalog, preview, host) +
        storyJson(catalog, preview) +
        mapOf(
          "schema" to JsonPrimitive("compose-preview-mcp-storybook/v1"),
          "workspaceId" to JsonPrimitive(catalog),
          "note" to
            JsonPrimitive(
              "Render with preview-stories. Native catalog_render_preview and catalog_get_preview_data also " +
                "accept this story's URI."
            ),
        )
    )

  private fun hostTitle(preview: ServePreview): String =
    preview.id.substringBeforeLast('.', missingDelimiterValue = preview.label)

  private fun previewJson(
    catalog: String,
    preview: ServePreview,
    host: ServeHost,
  ): JsonObject = buildJsonObject {
    put("id", preview.id)
    put("label", preview.label)
    put("catalog", catalog)
    put("uri", resourceUri(catalog, preview.id))
    put("modes", JsonArray(preview.modes.map { JsonPrimitive(it.wire) }))
    put("dataProductKinds", JsonArray(preview.dataProductKinds.sorted().map(::JsonPrimitive)))
    // Advertised per preview so an agent needn't discover the vector lane by refusal.
    put("svgAvailable", host.hasSvgExportFor(preview.id))
    put("scrollAvailable", host.hasScrollExportFor(preview.id))
    preview.state?.let { put("state", it) }
    preview.theme?.let { put("theme", it) }
    // Declared `previewOverride*` knobs by their `knob.<key>` wire key, so a client can find e.g.
    // the preview with a `document` knob without extra calls. Omitted when none.
    if (preview.overrides.isNotEmpty()) {
      put(
        "knobs",
        buildJsonArray {
          preview.overrides.forEach { knob ->
            add(
              buildJsonObject {
                put("key", knob.seedKey)
                put("type", knob.type)
              }
            )
          }
        },
      )
    }
  }

  private fun resolvePreview(host: ServeHost, id: String): ServePreview =
    host.previews.firstOrNull { it.id == id } ?: throw McpRequestException("no such preview '$id'")

  private fun JsonObject.previewTarget(): PreviewTarget {
    optionalString("uri")?.let {
      return targetFromUri(it)
    }
    val catalog = optionalString("catalog")
    val previewId = optionalString("previewId")
    if (catalog == null || previewId == null) {
      val missing =
        listOfNotNull(
          "catalog".takeIf { catalog == null },
          "previewId".takeIf { previewId == null },
        )
      throw McpRequestException(
        "needs 'uri', or 'catalog' and 'previewId' (missing: ${missing.joinToString()}). " +
          "Ids come from catalog_list_projects and catalog_list_previews."
      )
    }
    return PreviewTarget(catalog, previewId)
  }

  private fun storyTarget(value: String): PreviewTarget {
    if (value.startsWith(RESOURCE_URI_PREFIX)) {
      if (resourceOverrides(value) != null) {
        throw McpRequestException(
          "story id '$value' carries render overrides; pass them as the 'overrides' argument"
        )
      }
      return targetFromUri(value)
    }
    val separator = value.indexOf(STORY_ID_SEPARATOR)
    if (separator <= 0 || separator + STORY_ID_SEPARATOR.length >= value.length) {
      throw McpRequestException(
        "story id '$value' is not catalog-qualified; use an id from list-all-documentation"
      )
    }
    return PreviewTarget(
      value.substring(0, separator),
      value.substring(separator + STORY_ID_SEPARATOR.length),
    )
  }

  private fun storyId(catalog: String, previewId: String): String =
    "$catalog$STORY_ID_SEPARATOR$previewId"

  private fun resourceUri(catalog: String, previewId: String): String =
    "$RESOURCE_URI_PREFIX${WebEscaping.urlEncodeSegment(catalog)}/" +
      WebEscaping.urlEncodeSegment(previewId)

  private fun targetFromUri(uri: String): PreviewTarget {
    if (!uri.startsWith(RESOURCE_URI_PREFIX)) {
      throw McpRequestException("invalid compose-preview resource URI")
    }
    val parts = uri.substringBefore('?').removePrefix(RESOURCE_URI_PREFIX).split('/', limit = 2)
    if (parts.size != 2) throw McpRequestException("invalid compose-preview resource URI")
    return PreviewTarget(decode(parts[0]), decode(parts[1]))
  }

  private fun resourceUriWithOverrides(uri: String, overrides: JsonObject?): String {
    if (overrides == null || overrides.isEmpty()) return uri
    val encoded =
      Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(overrides.toString().encodeToByteArray())
    return "$uri?overrides=$encoded"
  }

  /**
   * Fold `?overrides=` from a returned `resource_link` back into the call so replaying a link
   * renders that state. Tools without overrides refuse such a URI, as does a disagreeing explicit
   * `overrides`. `diff_semantics` folds `other.uri` likewise.
   */
  private fun foldUriOverrides(name: String, args: JsonObject): JsonObject {
    val folded = foldOne(name, args, "overrides")
    if (name != "diff_semantics") return folded
    val other = folded["other"] as? JsonObject ?: return folded
    val otherUri = (other["uri"] as? JsonPrimitive)?.contentOrNull ?: return folded
    if (!otherUri.startsWith(RESOURCE_URI_PREFIX)) return folded
    val otherOverrides = resourceOverrides(otherUri) ?: return folded
    val explicit = folded["otherOverrides"]
    if (explicit != null && explicit !is JsonNull && explicit != otherOverrides) {
      throw McpRequestException(
        "catalog_diff_semantics: 'other.uri' carries render overrides that differ from " +
          "'otherOverrides'; pass one or the other"
      )
    }
    return JsonObject(
      folded +
        mapOf(
          "other" to JsonObject(other + ("uri" to JsonPrimitive(otherUri.substringBefore('?')))),
          "otherOverrides" to otherOverrides,
        )
    )
  }

  private fun foldOne(name: String, args: JsonObject, overridesKey: String): JsonObject {
    val uri = (args["uri"] as? JsonPrimitive)?.contentOrNull ?: return args
    if (!uri.startsWith(RESOURCE_URI_PREFIX)) return args
    val uriOverrides = resourceOverrides(uri) ?: return args
    if (name !in URI_OVERRIDE_TOOLS) {
      throw McpRequestException(
        "$name: 'uri' carries render overrides (?overrides=) that this tool does not apply; " +
          "pass the preview URI without the overrides query"
      )
    }
    val explicit = args[overridesKey]
    if (explicit != null && explicit !is JsonNull && explicit != uriOverrides) {
      throw McpRequestException(
        "$name: 'uri' carries render overrides that differ from the '$overridesKey' argument; " +
          "pass one or the other"
      )
    }
    return JsonObject(
      args + mapOf("uri" to JsonPrimitive(uri.substringBefore('?')), overridesKey to uriOverrides)
    )
  }

  /** Adds a short-lived signature to every override-bearing `resource_link` in [result]. */
  private fun signResourceLinks(result: JsonObject): JsonObject {
    val content = result["content"] as? JsonArray ?: return result
    if (
      content.none {
        (it as? JsonObject)?.get("type")?.jsonPrimitive?.contentOrNull == "resource_link"
      }
    ) {
      return result
    }
    val signed =
      JsonArray(
        content.map { block ->
          val obj = block as? JsonObject ?: return@map block
          if (obj["type"]?.jsonPrimitive?.contentOrNull != "resource_link") return@map block
          val uri = obj["uri"]?.jsonPrimitive?.contentOrNull ?: return@map block
          if (resourceOverrides(uri) == null) return@map block
          JsonObject(obj + ("uri" to JsonPrimitive(signResourceUri(uri))))
        }
      )
    return JsonObject(result + ("content" to signed))
  }

  /**
   * `<uri>&exp=<epoch seconds>&sig=<HMAC-SHA256>`: authorizes reading exactly that preview state
   * until [SIGNED_RESOURCE_TTL_SECONDS] pass, carrying no part of the grant token.
   */
  internal fun signResourceUri(uri: String): String {
    val unsigned = unsignedResourceUri(uri)
    val expiry = nowMillis() / 1000 + SIGNED_RESOURCE_TTL_SECONDS
    val separator = if ('?' in unsigned) '&' else '?'
    return "$unsigned${separator}exp=$expiry&sig=${resourceSignature(unsigned, expiry)}"
  }

  /**
   * `<origin>/mcp/render.png?uri=…&exp=…&sig=…`, or null without a public origin; grants exactly
   * one render for [SIGNED_RESOURCE_TTL_SECONDS].
   */
  private fun signedImageUrl(resourceUri: String): String? = signedImage(resourceUri)?.first

  /** [signedImageUrl] with the epoch second it stops verifying. */
  private fun signedImage(resourceUri: String): Pair<String, Long>? {
    val origin = publicOrigin()?.trimEnd('/') ?: return null
    val unsigned = unsignedResourceUri(resourceUri)
    val expiry = nowMillis() / 1000 + SIGNED_RESOURCE_TTL_SECONDS
    val encoded = URLEncoder.encode(unsigned, StandardCharsets.UTF_8)
    return "$origin$IMAGE_URL_PATH?uri=$encoded&exp=$expiry&sig=${resourceSignature(unsigned, expiry)}" to
      expiry
  }

  /**
   * Chat fallback for pixels that can't be replayed from a resource URI: bytes kept for the link's
   * lifetime ([signedViewUrl]) and offered as an https `resource_link` plus a line of text. Empty
   * without a public origin.
   */
  private fun keptImageFallback(png: ByteArray): List<JsonObject> {
    val (url, expiry) = signedViewUrl(png) ?: return emptyList()
    return listOf(imageUrlLinkContent(url), chatImageText(url, expiry))
  }

  /**
   * The PNG behind a [signedImageUrl], or null for a bad or expired signature. Leases the catalog
   * and takes a render permit as usual.
   */
  suspend fun signedImagePng(resourceUri: String, expiry: Long, signature: String): ByteArray? {
    if (nowMillis() / 1000 > expiry) return null
    val unsigned = unsignedResourceUri(resourceUri)
    val expected = resourceSignature(unsigned, expiry)
    if (!MessageDigest.isEqual(expected.encodeToByteArray(), signature.encodeToByteArray())) {
      return null
    }
    if (unsigned.startsWith(VIEW_URI_PREFIX)) {
      val key = unsigned.removePrefix(VIEW_URI_PREFIX)
      return synchronized(viewImages) { viewImages[key]?.takeIf { it.second >= expiry }?.first }
    }
    val target = runCatching { targetFromUri(unsigned) }.getOrNull() ?: return null
    val rawOverrides = resourceOverrides(unsigned)
    return withCatalog(target.catalog) { host ->
      val preview = resolvePreview(host, target.previewId)
      renderPng(
          host,
          preview.id,
          parseOverrides(preview, rawOverrides),
          // The link is minted after a live render, so it serves that lane's pixels, not the
          // published snapshot `resources/read` prefers for an override-free uri.
          preferPublished = false,
        )
        .png
    }
  }

  private fun hasValidResourceSignature(uri: String): Boolean {
    val query = uri.substringAfter('?', missingDelimiterValue = "").split('&')
    fun param(name: String) =
      query.firstOrNull { it.substringBefore('=') == name }?.substringAfter('=', "")
    val expiry = param("exp")?.toLongOrNull() ?: return false
    val signature = param("sig")?.takeIf { it.isNotEmpty() } ?: return false
    if (query.count { it.substringBefore('=') == "exp" || it.substringBefore('=') == "sig" } != 2) {
      return false
    }
    if (nowMillis() / 1000 > expiry) return false
    val expected = resourceSignature(unsignedResourceUri(uri), expiry)
    return MessageDigest.isEqual(
      expected.encodeToByteArray(),
      signature.encodeToByteArray(),
    )
  }

  private fun unsignedResourceUri(uri: String): String {
    val base = uri.substringBefore('?')
    val query =
      uri.substringAfter('?', missingDelimiterValue = "").split('&').filter {
        it.isNotEmpty() && it.substringBefore('=') != "exp" && it.substringBefore('=') != "sig"
      }
    return if (query.isEmpty()) base else "$base?${query.joinToString("&")}"
  }

  private fun resourceSignature(unsignedUri: String, expiry: Long): String {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(resourceLinkKey, "HmacSHA256"))
    val digest = mac.doFinal("$unsignedUri\n$expiry".encodeToByteArray())
    return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
  }

  private fun resourceOverrides(uri: String): JsonObject? {
    val encoded =
      uri
        .substringAfter('?', missingDelimiterValue = "")
        .split('&')
        .firstOrNull { it.substringBefore('=') == "overrides" }
        ?.removePrefix("overrides=")
        ?.takeIf { it.isNotEmpty() } ?: return null
    return runCatching {
      JSON.parseToJsonElement(Base64.getUrlDecoder().decode(encoded).decodeToString()).jsonObject
    }
      .getOrElse { throw McpRequestException("invalid compose-preview resource overrides") }
  }

  private fun decode(value: String): String =
    URLDecoder.decode(value.replace("+", "%2B"), StandardCharsets.UTF_8)

  private fun catalogIds(selected: String? = null): List<String> {
    val ids = sessions.knownSessionIds()
    if (selected == null) return ids
    if (selected !in ids) throw unknownCatalog(selected)
    return listOf(selected)
  }

  /** "Still loading" for a configured catalog that has not registered yet, else "no such". */
  private fun unknownCatalog(catalog: String): McpRequestException {
    val pending = pendingCatalogs()
    return if (catalog in pending) {
      McpRequestException(
        "catalog '$catalog' is still loading on this server (${pending.size} catalog(s) " +
          "pending after a restart); retry in a minute"
      )
    } else {
      McpRequestException("no such catalog '$catalog'")
    }
  }

  /** Marks a listing taken while catalogs are still loading, so it is not read as complete. */
  private fun JsonObjectBuilder.putLoading() {
    val pending = pendingCatalogs()
    if (pending.isEmpty()) return
    put("complete", false)
    putJsonArray("loading") { pending.forEach { add(it) } }
    put(
      "note",
      "${pending.size} catalog(s) are still loading after a restart, so this list is " +
        "incomplete. Retry in a minute before concluding a catalog is missing.",
    )
  }

  /**
   * The one catalog a per-catalog listing reads: the only one, or the one the caller names (#1162).
   * Listing all would resume every suspended host and serialise thousands of previews; the refusal
   * names the ids and the local server.
   */
  private fun requireCatalog(tool: String, args: JsonObject): String {
    args.optionalString("catalog")?.let {
      return it
    }
    val ids = catalogIds()
    ids.singleOrNull()?.let {
      return it
    }
    val shown = ids.take(MAX_CATALOGS_IN_ERROR)
    val more = if (ids.size > shown.size) ", and ${ids.size - shown.size} more" else ""
    throw McpRequestException(
      "$tool needs a 'catalog' argument; this hosted server does not list every catalog at " +
        "once. Available catalogs: ${if (shown.isEmpty()) "(none)" else shown.joinToString()}" +
        "$more. Call catalog_list_projects for their labels and preview counts. Previews of the project " +
        "you are working on are not hosted here: use the local compose-preview-mcp server."
    )
  }

  /** What an enumeration may say about one catalog, read without resuming it. */
  private class CatalogView(val label: String, val previews: List<ServePreview>?)

  /**
   * [catalog]'s label and previews as the registry holds them (resident host, else retained state),
   * never leasing, so enumerations don't wake idle daemons. Null previews: listed by id only.
   */
  private fun peekCatalog(catalog: String): CatalogView {
    sessions.peekHost(catalog)?.let {
      return CatalogView(it.label, it.previews)
    }
    sessions.peekState(catalog)?.let {
      return CatalogView(it.label, it.previews)
    }
    return CatalogView(catalog, null)
  }

  private suspend fun <T> withCatalog(catalog: String, block: suspend (ServeHost) -> T): T {
    if (catalog !in sessions.knownSessionIds()) throw unknownCatalog(catalog)
    val lease =
      withContext(Dispatchers.IO) { sessions.lease(catalog) }
        ?: throw McpRequestException("catalog '$catalog' is unavailable")
    return try {
      block(lease.host)
    } finally {
      lease.close()
    }
  }

  /**
   * Whether `render_preview` should serve the published snapshot instead of refusing: no live
   * grant, and nothing a snapshot can't give (no overrides, picture only). Agents try
   * `render_preview` before `resources/read`.
   */
  private fun servesPublishedSnapshot(
    args: JsonObject,
    liveAuthorization: () -> ServeMachineAuthorization.Decision,
  ): Boolean {
    val overrides = args["overrides"] as? JsonObject
    if (!overrides.isNullOrEmpty()) return false
    val observe = args["observe"]?.jsonPrimitive?.contentOrNull?.lowercase()
    if (observe != null && observe != "png") return false
    return liveAuthorization() == ServeMachineAuthorization.Decision.Missing
  }

  /** The published render of one preview, as `resources/read` serves it, with a provenance note. */
  private suspend fun publishedSnapshotResult(catalog: String, previewId: String): JsonObject =
    withCatalog(catalog) { host ->
      val preview = resolvePreview(host, previewId)
      val uri = resourceUri(catalog, preview.id)
      val png =
        renderPng(host, preview.id, parseOverrides(preview, null), preferPublished = true).png
      buildJsonObject {
        putJsonArray("content") {
          add(imageContent(png))
          add(resourceLinkContent(uri))
          add(
            textContent(
              buildJsonObject {
                put("uri", uri)
                put("published", true)
                put("note", PUBLISHED_SNAPSHOT_NOTE)
              }
                .toString()
            )
          )
        }
      }
    }

  private fun requireLive(check: () -> ServeMachineAuthorization.Decision) {
    when (val decision = check()) {
      is ServeMachineAuthorization.Decision.Authorized -> Unit
      ServeMachineAuthorization.Decision.Missing ->
        throw McpRequestException(
          "live grant scope is required for a made-to-order render. To look at a published " +
            "render, read its resource instead (resources/read on the uri from " +
            "catalog_list_previews), which needs only preview scope. For a live render, call " +
            "request_access with scope \"live\"."
        )
      is ServeMachineAuthorization.Decision.Forbidden -> throw McpRequestException(decision.message)
    }
  }

  /**
   * One UI-builder tool, or null for a catalog-surface name. Authorized here because the credential
   * belongs to the transport's call, like the HTTP routes ([TOKEN_ARGUMENT] is resolved the same
   * way). A missing grant is a tool error, not a transport status.
   */
  private suspend fun uiBuilderTool(
    name: String,
    args: JsonObject,
    presentedToken: String?,
    authorize: (UiBuilderRouteCapability, String?) -> UiBuilderAuthorizationDecision,
    clientInteraction: ClientInteraction,
  ): JsonObject? {
    val builder = uiBuilder ?: return null
    val capability = builder.capabilityFor(name) ?: return null
    val actor =
      when (val decision = authorize(capability, presentedToken)) {
        is UiBuilderAuthorizationDecision.Authorized -> decision.actor
        UiBuilderAuthorizationDecision.Missing ->
          return toolError(
            "this tool needs a UI-builder ${capability.name.lowercase()} grant; none was " +
              "presented. Call request_access with capability " +
              "'${capability.agentGrantCapability().wire}', have a human approve it, then pass the " +
              "token poll_access returns as this tool's '$TOKEN_ARGUMENT' argument."
          )
        UiBuilderAuthorizationDecision.Forbidden ->
          return toolError(
            "the presented identity lacks the UI-builder ${capability.name.lowercase()} capability. " +
              "A grant only carries what this server's --agent-grant-capabilities allows and what " +
              "the approver ticked, so either the ask was narrowed at approval or the box's " +
              "ceiling excludes it — request again, and if the approval page says the box does " +
              "not offer it, the operator has to add the capability and restart."
          )
      }
    builder.additionalCapabilityFor(name, args)?.let { additional ->
      if (authorize(additional, presentedToken) !is UiBuilderAuthorizationDecision.Authorized) {
        return toolError(
          "this call also needs a UI-builder ${additional.name.lowercase()} grant. Call " +
            "request_access with capability '${additional.agentGrantCapability().wire}', have a " +
            "human approve it, then pass the token poll_access returns as this tool's " +
            "'$TOKEN_ARGUMENT' argument."
        )
      }
    }
    // A person may take a while to answer a form, and the answer is what licenses a write. So an
    // accepted answer only counts if the SAME credential still authorizes the SAME actor for the
    // SAME capability at the moment it arrives; a grant revoked or expired while the form was open
    // turns the answer into a timeout — nothing written, the text decision returned.
    val interaction = clientInteraction.reauthorizedOnAccept {
      val again = authorize(capability, presentedToken)
      again is UiBuilderAuthorizationDecision.Authorized && again.actor == actor
    }
    val text = builder.call(name, args, actor, callId = name, clientInteraction = interaction)
    if (name == ServeUiBuilderMcp.GUIDELINES_PROMPT) return uiBuilderGuidelinesPromptResult(text)
    if (
      name == ServeUiBuilderMcp.VIEW ||
        name == ServeUiBuilderMcp.RENDER_DESIGN_MATRIX ||
        name in ServeUiBuilderAlternativeTools.TOOL_NAMES
    ) {
      return uiBuilderViewResult(
        text,
        inline = args[ServeUiBuilderMcp.INLINE_ARGUMENT]?.jsonPrimitive?.booleanOrNull == true,
      )
    }
    return uiBuilderToolResult(name, text)
  }

  /**
   * `ui_builder_view`'s reply: JSON as text, the picture as a signed https link, and image bytes
   * when asked or without a public origin. Base64 never goes in the text.
   */
  internal fun uiBuilderViewResult(text: String, inline: Boolean): JsonObject {
    val reply =
      runCatching { JSON.parseToJsonElement(text) as? JsonObject }.getOrNull()
        ?: return textResult(text)
    val encoded =
      reply["imageBase64"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        ?: return textResult(text)
    val png =
      runCatching { Base64.getDecoder().decode(encoded) }.getOrNull() ?: return textResult(text)
    val link = signedViewUrl(png)
    val image = reply["image"] as? JsonObject ?: JsonObject(emptyMap())
    val described =
      JsonObject(
        reply - "imageBase64" +
          ("image" to
            JsonObject(
              image +
                (link?.let {
                  mapOf(
                    "url" to JsonPrimitive(it.first),
                    "expiresAtEpochSeconds" to JsonPrimitive(it.second),
                  )
                } ?: emptyMap())
            ))
      )
    return buildJsonObject {
      put(
        "content",
        buildJsonArray {
          add(textContent(described.toString()))
          link?.let {
            add(
              buildJsonObject {
                put("type", "resource_link")
                put("uri", it.first)
                put("name", VIEW_LINK_NAME)
                put("mimeType", "image/png")
                put(
                  "description",
                  "Short-lived signed https URL of this exact view; it grants nothing beyond " +
                    "that image.",
                )
              }
            )
            add(chatImageText(it.first, it.second))
          }
          if (inline || link == null) add(imageContent(png))
        },
      )
    }
  }

  /**
   * `ui_builder_guidelines_prompt`'s reply: the request JSON with each `dataUrl` removed, then the
   * pictures as image blocks in the user message's order.
   */
  internal fun uiBuilderGuidelinesPromptResult(text: String): JsonObject {
    val reply =
      runCatching { JSON.parseToJsonElement(text) as? JsonObject }.getOrNull()
        ?: return textResult(text)
    val pictures = (reply["pictures"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
    val pngs = pictures.map { picture ->
      picture["dataUrl"]?.jsonPrimitive?.contentOrNull?.let(::pngPayload)
    }
    val described =
      JsonObject(reply + ("pictures" to JsonArray(pictures.map { JsonObject(it - "dataUrl") })))
    // Pictures not attached this time (still drawing, or wrong size) get their own text block so
    // the agent asks again.
    val missing =
      (reply["provenance"] as? JsonArray)
        .orEmpty()
        .mapNotNull { it.jsonPrimitive.contentOrNull }
        .filter { "still being drawn" in it || " is left out:" in it }
    return buildJsonObject {
      put(
        "content",
        buildJsonArray {
          add(textContent(described.toString()))
          // After the request rather than before it: clients read the first text block as the
          // request's JSON.
          if (missing.isNotEmpty()) add(textContent(missing.joinToString("\n")))
          pngs.filterNotNull().forEach { png ->
            add(
              buildJsonObject {
                put("type", "image")
                put("data", png)
                put("mimeType", "image/png")
              }
            )
          }
        },
      )
    }
  }

  /** `<origin>/mcp/render.png` for a kept view, or null on a box with no public origin. */
  private fun signedViewUrl(png: ByteArray): Pair<String, Long>? {
    val origin = publicOrigin()?.trimEnd('/') ?: return null
    val id = ByteArray(18).also { SecureRandom().nextBytes(it) }
    val key = Base64.getUrlEncoder().withoutPadding().encodeToString(id)
    val uri = "$VIEW_URI_PREFIX$key"
    val expiry = nowMillis() / 1000 + SIGNED_RESOURCE_TTL_SECONDS
    synchronized(viewImages) {
      val now = nowMillis() / 1000
      viewImages.entries.removeIf { it.value.second < now }
      viewImages[key] = png to expiry
    }
    val encoded = URLEncoder.encode(uri, StandardCharsets.UTF_8)
    return "$origin$IMAGE_URL_PATH?uri=$encoded&exp=$expiry&sig=${resourceSignature(uri, expiry)}" to
      expiry
  }

  /**
   * Keeps the UI-builder reply as a text fallback while giving MCP App hosts an image block for the
   * two PNG-carrying calls (the text omits the binary). The native render's playground capability
   * stays in the reply for live preview. The viewer understands only MCP content blocks, not every
   * UI-builder schema.
   */
  internal fun uiBuilderToolResult(name: String, text: String): JsonObject {
    val png = uiBuilderPng(name, text)
    val fallback = uiBuilderViewerFallback(name, text, hasPng = png != null)
    if (png == null) return textResult(fallback)
    return buildJsonObject {
      put(
        "content",
        buildJsonArray {
          add(textContent(fallback))
          add(
            buildJsonObject {
              put("type", "image")
              put("data", png)
              put("mimeType", "image/png")
            }
          )
          // A chat surface shows the person text and links, never the block above.
          runCatching { Base64.getDecoder().decode(png) }
            .getOrNull()
            ?.let(::keptImageFallback)
            ?.forEach(::add)
        },
      )
    }
  }

  private fun uiBuilderViewerFallback(name: String, text: String, hasPng: Boolean): String {
    return runCatching {
        val reply = JSON.parseToJsonElement(text) as? JsonObject ?: return@runCatching text
        when (name) {
          ServeUiBuilderMcp.RENDER_NATIVE -> JsonObject(reply - "imageBase64").toString()
          ServeUiBuilderMcp.EXPORT_DOCUMENT -> {
            if (!hasPng) return@runCatching text
            val response = reply["response"] as? JsonObject ?: return@runCatching text
            val artifact = response["artifact"] as? JsonObject ?: return@runCatching text
            JsonObject(
                reply +
                  ("response" to
                    JsonObject(response + ("artifact" to JsonObject(artifact - "content"))))
              )
              .toString()
          }
          else -> text
        }
      }
      .getOrDefault(text)
  }

  private fun uiBuilderPng(name: String, text: String): String? = runCatching {
    val reply = JSON.parseToJsonElement(text) as? JsonObject ?: return@runCatching null
    when (name) {
      ServeUiBuilderMcp.RENDER_NATIVE ->
        reply["imageBase64"]?.jsonPrimitive?.contentOrNull?.let(::pngPayload)
      ServeUiBuilderMcp.EXPORT_DOCUMENT -> {
        val response = reply["response"] as? JsonObject
        val artifact = response?.get("artifact") as? JsonObject
        if (
          artifact?.get("mediaType")?.jsonPrimitive?.contentOrNull?.substringBefore(';') !=
            "image/png" || artifact["encoding"]?.jsonPrimitive?.contentOrNull != "base64"
        ) {
          null
        } else {
          artifact["content"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        }
      }
      else -> null
    }
  }
    .getOrNull()

  private fun pngPayload(value: String): String? {
    val payload =
      when {
        value.startsWith("data:image/png;base64,") -> value.substringAfter(',')
        value.startsWith("data:") -> return null
        else -> value
      }
    return payload.takeIf { it.isNotBlank() }
  }

  private fun tool(name: String, description: String, schema: String): JsonObject =
    buildJsonObject {
      put("name", wireName(name))
      put("description", description)
      put(
        "inputSchema",
        withAgentIdentity(
          name,
          withTokenArgument(name, JSON.parseToJsonElement(schema).jsonObject),
        ),
      )
      put("outputSchema", outputSchema(name))
      if (name in VIEWER_TOOL_NAMES) put("_meta", viewerToolMeta())
    }

  private fun viewerHtml(): String =
    checkNotNull(javaClass.classLoader.getResourceAsStream(MCP_APP_VIEWER_ASSET)) {
        "missing bundled MCP App viewer: $MCP_APP_VIEWER_ASSET"
      }
      .bufferedReader()
      .use { it.readText() }

  private fun viewerToolMeta(): JsonObject = buildJsonObject {
    put("ui", buildJsonObject { put("resourceUri", MCP_APP_VIEWER_URI) })
    // Pre-2026-01-26 MCP Apps hosts read the flat key; current hosts read `ui.resourceUri`.
    put("ui/resourceUri", MCP_APP_VIEWER_URI)
  }

  private fun viewerResourceMeta(): JsonObject = buildJsonObject {
    put("ui", buildJsonObject { put("prefersBorder", true) })
  }

  /**
   * The `outputSchema` every tool declares, always an object: a class-encoded reply gets its
   * generated schema ([ServeUiBuilderMcp.VIEW], [ServeUiBuilderMcp.VALIDATE]), legacy arrays their
   * wrapper ([withStructuredContent]), renders their `imageUrl`, and the rest an open object.
   */
  private fun outputSchema(name: String): JsonObject =
    when (name) {
      "list_data_products" -> arrayWrapperSchema("dataProducts")
      "preview-stories" -> arrayWrapperSchema("observations")
      ServeUiBuilderMcp.VIEW -> UiBuilderJsonSchemas.viewOutput
      ServeUiBuilderMcp.VALIDATE -> UiBuilderJsonSchemas.validationOutput
      ServeUiBuilderMcp.CHECK_DESIGN -> UiBuilderJsonSchemas.designCheckOutput
      ServeUiBuilderMcp.RENDER_DESIGN_MATRIX -> UiBuilderJsonSchemas.designMatrixOutput
      ServeUiBuilderMcp.RECORD_DECISION,
      ServeUiBuilderMcp.AWAIT_DECISION -> UiBuilderJsonSchemas.decisionOutput
      ServeUiBuilderMcp.IMPLEMENTATION_STATUS -> UiBuilderJsonSchemas.implementationOutput
      ServeUiBuilderMcp.FIND_DESIGN_FOR_PR -> UiBuilderJsonSchemas.prLookupOutput
      ServeUiBuilderMcp.SET_REFERENCE -> UiBuilderJsonSchemas.referenceAttachedOutput
      ServeUiBuilderMcp.COMPARE_REFERENCE -> UiBuilderJsonSchemas.referenceComparisonOutput
      ServeUiBuilderMcp.GUIDELINES_PROMPT -> UiBuilderJsonSchemas.guidelinesPromptOutput
      ServeUiBuilderMcp.GET_GUIDELINES,
      ServeUiBuilderMcp.RECORD_GUIDELINES -> UiBuilderJsonSchemas.guidelinesOutput
      in ServeUiBuilderHistoryTools.TOOL_NAMES -> ServeUiBuilderHistoryTools.outputSchema(name)!!
      in ServeUiBuilderBranchTools.TOOL_NAMES -> ServeUiBuilderBranchTools.outputSchema(name)!!
      in ServeUiBuilderAlternativeTools.TOOL_NAMES ->
        ServeUiBuilderAlternativeTools.outputSchema(name)!!
      "render_preview" ->
        buildJsonObject {
          put("type", "object")
          putJsonObject(PROPERTIES) {
            putJsonObject("imageUrl") {
              put("type", "string")
              put(
                "description",
                "Short-lived signed https URL of the rendered PNG, on a host with a public origin.",
              )
            }
          }
        }
      else -> buildJsonObject { put("type", "object") }
    }

  private fun arrayWrapperSchema(field: String): JsonObject = buildJsonObject {
    put("type", "object")
    putJsonObject(PROPERTIES) {
      putJsonObject(field) {
        put("type", "array")
        putJsonObject("items") { put("type", "object") }
      }
    }
    putJsonArray("required") { add(field) }
    put("additionalProperties", false)
  }

  /**
   * Output schemas validate `structuredContent`, so mirror the JSON there while keeping the text
   * block. Legacy arrays are wrapped under their declared field; batched story calls aggregate
   * every observation; image-only and non-JSON results carry an empty object; existing structure is
   * kept.
   */
  private fun withStructuredContent(name: String, result: JsonObject): JsonObject {
    if (result["isError"]?.jsonPrimitive?.booleanOrNull == true) return result
    val existing = result["structuredContent"] as? JsonObject ?: JsonObject(emptyMap())
    val parsedText =
      (result["content"] as? JsonArray)
        ?.asSequence()
        ?.mapNotNull { it as? JsonObject }
        ?.mapNotNull { block ->
          if (block["type"]?.jsonPrimitive?.contentOrNull != "text") return@mapNotNull null
          block["text"]?.jsonPrimitive?.contentOrNull?.let { text ->
            runCatching { JSON.parseToJsonElement(text) }.getOrNull()
          }
        }
        ?.toList()
        .orEmpty()
    val structured =
      when (name) {
        "list_data_products" ->
          (parsedText.firstOrNull() as? JsonArray)?.let {
            buildJsonObject { put("dataProducts", it) }
          }
        "preview-stories" ->
          buildJsonObject {
            put("observations", JsonArray(parsedText.filterIsInstance<JsonObject>()))
          }
        else -> parsedText.firstOrNull() as? JsonObject
      } ?: JsonObject(emptyMap())
    return JsonObject(result + ("structuredContent" to JsonObject(structured + existing)))
  }

  /**
   * Adds the in-band credential to a gated tool's input schema, since some schemas forbid extra
   * properties and models only pass visible arguments. Skipped on the two access tools, used before
   * a token exists.
   */
  /** Cosmetic, explicitly reported identity. Never grants access or changes authorization. */
  private fun withAgentIdentity(name: String, schema: JsonObject): JsonObject {
    if (!name.startsWith("ui_builder_")) return schema
    val properties = schema[PROPERTIES] as? JsonObject ?: JsonObject(emptyMap())
    val identity =
      listOf("agentName", "agentModel").associateWith { field ->
        buildJsonObject {
          put("type", "string")
          put("maxLength", 80)
          put(
            "description",
            if (field == "agentName")
              "Optional agent/client name shown to design viewers; self-reported."
            else
              "Optional current model name shown to design viewers; self-reported. Omit when unknown.",
          )
        }
      }
    return JsonObject(schema + (PROPERTIES to JsonObject(properties + identity)))
  }

  private fun withTokenArgument(name: String, schema: JsonObject): JsonObject {
    if (name in UNGATED_TOOLS) return schema
    val properties = schema[PROPERTIES] as? JsonObject ?: JsonObject(emptyMap())
    if (TOKEN_ARGUMENT in properties) return schema
    return JsonObject(
      schema +
        (PROPERTIES to
          JsonObject(
            properties +
              (TOKEN_ARGUMENT to
                buildJsonObject {
                  put("type", "string")
                  put("description", TOKEN_ARGUMENT_DESCRIPTION)
                })
          ))
    )
  }

  private fun textResult(text: String): JsonObject = buildJsonObject {
    put(
      "content",
      buildJsonArray {
        add(
          buildJsonObject {
            put("type", "text")
            put("text", text)
          }
        )
      },
    )
  }

  private fun toolError(message: String): JsonObject = buildJsonObject {
    put(
      "content",
      buildJsonArray {
        add(
          buildJsonObject {
            put("type", "text")
            put("text", message)
          }
        )
      },
    )
    put("isError", true)
  }

  private fun textContent(text: String): JsonObject = buildJsonObject {
    put("type", "text")
    put("text", text)
  }

  private fun imageContent(png: ByteArray): JsonObject = buildJsonObject {
    put("type", "image")
    put("data", Base64.getEncoder().encodeToString(png))
    put("mimeType", "image/png")
  }

  /** Keeps the replayable render address beside PNG bytes for hosts that render resource links. */
  private fun resourceLinkContent(uri: String): JsonObject = buildJsonObject {
    put("type", "resource_link")
    put("uri", uri)
    put("name", "Compose Preview render")
    put("mimeType", "image/png")
    put(
      "description",
      "Preview render resource; override-bearing reads require the same live scope.",
    )
  }

  /**
   * The image as one line of text for text-only hosts like Slack: a bare https URL, nothing else
   * (no base64, `file://` or grant).
   */
  private fun chatImageText(url: String, expiresAtEpochSeconds: Long): JsonObject =
    textContent(
      "Image: $url\n" +
        "(signed https PNG, valid until ${Instant.ofEpochSecond(expiresAtEpochSeconds)}; " +
        "attach or link it in chat rather than describing it)"
    )

  /** A plain `https` link a host can put in an `<img>`: no scheme it has to know, no bridge. */
  private fun imageUrlLinkContent(url: String): JsonObject = buildJsonObject {
    put("type", "resource_link")
    put("uri", url)
    put("name", IMAGE_URL_LINK_NAME)
    put("mimeType", "image/png")
    put(
      "description",
      "Short-lived signed https URL of this exact render; it grants nothing beyond that image.",
    )
  }

  private fun success(id: JsonElement, result: JsonObject): JsonObject = buildJsonObject {
    put("jsonrpc", "2.0")
    put("id", id)
    put("result", result)
  }

  private fun urlElicitationRequired(
    id: JsonElement?,
    required: UrlElicitationRequired,
  ): JsonObject = buildJsonObject {
    put("jsonrpc", "2.0")
    put("id", id ?: JsonNull)
    putJsonObject("error") {
      put("code", URL_ELICITATION_REQUIRED)
      put("message", "This access request needs a browser decision.")
      putJsonObject("data") {
        putJsonArray("elicitations") {
          add(
            buildJsonObject {
              put("mode", "url")
              put("elicitationId", required.elicitationId)
              put("url", required.url)
              put("message", required.message)
            }
          )
        }
      }
    }
  }

  private fun pendingAccess(body: String): Boolean = runCatching {
    JSON.parseToJsonElement(body).jsonObject["status"]?.jsonPrimitive?.contentOrNull ==
      ServeAgentGrants.PollResponse.PENDING
  }
    .getOrDefault(false)

  private fun error(id: JsonElement?, code: Int, message: String): JsonObject = buildJsonObject {
    put("jsonrpc", "2.0")
    put("id", id ?: JsonNull)
    put(
      "error",
      buildJsonObject {
        put("code", code)
        put("message", message)
      },
    )
  }

  private fun JsonObject.requiredString(name: String): String =
    this[name]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)
      ?: throw McpRequestException("'$name' is required")

  private fun JsonObject.optionalString(name: String): String? =
    (this[name] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)

  private fun JsonObject.firstString(vararg names: String): String =
    names.firstNotNullOfOrNull { name ->
      this[name]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)
    } ?: throw McpRequestException("'${names.joinToString("' or '")}' is required")

  private fun pngDimensions(bytes: ByteArray): Pair<Int, Int>? {
    if (bytes.size < 24) return null
    fun int32(offset: Int): Int =
      ((bytes[offset].toInt() and 0xFF) shl 24) or
        ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
        ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
        (bytes[offset + 3].toInt() and 0xFF)
    val width = int32(16)
    val height = int32(20)
    return if (width in 1..100_000 && height in 1..100_000) width to height else null
  }

  private fun sha256Hex(bytes: ByteArray): String =
    java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
      "%02x".format(it)
    }

  companion object {
    const val MCP_PROTOCOL_VERSION = "2025-06-18"
    const val MCP_PROTOCOL_VERSION_2025_03 = "2025-03-26"
    /**
     * The revision defining URL-mode elicitation and `poll_access`'s -32042. 2026-07-28 isn't
     * negotiated: unverified here, and the pinned MCP Kotlin SDK (0.15.0) knows nothing past
     * 2025-11-25.
     */
    const val MCP_PROTOCOL_VERSION_2025_11 = "2025-11-25"
    val SUPPORTED_PROTOCOL_VERSIONS =
      setOf(MCP_PROTOCOL_VERSION, MCP_PROTOCOL_VERSION_2025_03, MCP_PROTOCOL_VERSION_2025_11)

    /** Per-request client capabilities, for clients that declare them on each stateless call. */
    const val CLIENT_CAPABILITIES_META = "io.modelcontextprotocol/clientCapabilities"

    private const val EMPTY_SCHEMA = """{"type":"object","properties":{}}"""
    private const val INVALID_REQUEST = -32600
    private const val METHOD_NOT_FOUND = -32601
    private const val INVALID_PARAMS = -32602
    private const val INTERNAL_ERROR = -32603
    private const val MAX_STORIES_PER_CALL = 16
    /**
     * Cells one `render_matrix` call may commission, bounding what one message can cost (like
     * [MAX_STORIES_PER_CALL]).
     */
    private const val MAX_MATRIX_CELLS = 24

    /** Where a variant grid's PNGs ride for the viewer, out of the model's text; see the viewer. */
    private const val CELL_PNGS_META_KEY = "composePreview/cellPngs"
    private val MATRIX_OBSERVATION_MODES = setOf("png", "hash")
    private val PROMPT_DESIGN_ID = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")
    private val PROMPT_NODE_ID = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")

    /** `a, b, or c` — the list keeps its grammar as observations are added to it. */
    private fun Collection<String>.orList(): String {
      val items = toList()
      return if (items.size < 2) items.joinToString()
      else items.dropLast(1).joinToString() + ", or " + items.last()
    }

    private const val RESOURCE_URI_PREFIX = "compose-preview://catalog/"

    /** Lifetime of a signed override-bearing resource link; see [signResourceUri]. */
    internal const val SIGNED_RESOURCE_TTL_SECONDS = 600L

    /** The public route [signedImageUrl] points at; served by [ServeHttpServer], not MCP. */
    const val IMAGE_URL_PATH = "/mcp/render.png"
    private const val IMAGE_URL_LINK_NAME = "Compose Preview render (https)"

    /** The resource URI a kept `ui_builder_view` picture is signed under; see [viewImages]. */
    private const val VIEW_URI_PREFIX = "compose-preview://ui-builder-view/"
    private const val VIEW_LINK_NAME = "UI-builder view (https)"

    /** How many view pictures are kept for their signed links at once. */
    private const val MAX_VIEW_IMAGES = 32

    /**
     * Catalog tools that accept an `overrides` argument, so a link's overrides can be folded in.
     */
    private val URI_OVERRIDE_TOOLS =
      setOf("render_preview", "render_matrix", "get_preview_data", "diff_semantics")
    const val MCP_APP_VIEWER_URI = "ui://compose-preview/viewer"
    private const val MCP_APP_MIME_TYPE = "text/html;profile=mcp-app"
    private const val MCP_APP_VIEWER_ASSET = "compose-preview-viewer.html"
    private val VIEWER_TOOL_NAMES =
      setOf(
        "render_preview",
        "render_matrix",
        "diff_semantics",
        ServeUiBuilderMcp.EXPORT_DOCUMENT,
        ServeUiBuilderMcp.RENDER_NATIVE,
        ServeUiBuilderMcp.VIEW,
        ServeUiBuilderMcp.RENDER_DESIGN_MATRIX,
      )
    private const val STORY_ID_SEPARATOR = "::"
    private val OBSERVATION_MODES =
      setOf("png", "svg", "scroll-png", "scroll-svg", "semantics", "hash")
    private const val LIST_PREVIEWS_SCHEMA =
      """{"type":"object","properties":{"catalog":{"type":"string","description":"A catalog id from catalog_list_projects."},"query":{"type":"string","description":"Case-insensitive substring of a preview id or label, such as a component name."},"offset":{"type":"integer","minimum":0},"limit":{"type":"integer","minimum":1,"maximum":500}},"required":["catalog"]}"""
    private const val DEFAULT_PREVIEW_PAGE = 100
    private const val MAX_PREVIEW_PAGE = 500
    internal const val PUBLISHED_SNAPSHOT_NOTE =
      "Published snapshot: no live grant was presented, so this is the catalog's published " +
        "render. Overrides, other observations and fresh renders need live scope " +
        "(request_access with scope \"live\")."
    /** Enough ids to pick from in a refusal without the refusal becoming the listing. */
    private const val MAX_CATALOGS_IN_ERROR = 50
    private val ANNOTATION_KINDS =
      setOf("compose/annotations", "compose/semantics", "compose/typography", "compose/tags")
    private val JSON = Json { ignoreUnknownKeys = false }

    private const val ACCESS_DISABLED =
      "This server does not issue agent access grants; ask its operator for a token."
    private const val ACCESS_THROTTLED =
      "Too many access requests from this address just now — wait a minute and try again."

    /**
     * JSON-RPC methods any caller may send. Discovery only, so a tokenless agent can reach the tool
     * that requests a credential; nothing here reads a catalog.
     */
    /**
     * Methods answered before any credential is examined; none disclose this host's catalogs.
     * Prompts are static text, and prompt requests can't carry an in-session token.
     * `resources/list` is deliberately excluded (it enumerates previews);
     * [ServeMachineAuthorization] instead admits it on a `--public` box where `preview` scope needs
     * no credential.
     */
    private val UNGATED_METHODS =
      setOf("initialize", "ping", "tools/list", "prompts/list", "prompts/get")

    private const val URL_ELICITATION_REQUIRED = -32042

    /** The two tools that obtain a grant; every other tool needs at least `preview` scope. */
    private val UNGATED_TOOLS = setOf("request_access", "poll_access")

    /**
     * Prefix on the hosted catalog's data tools so they never clash with the local
     * `compose-preview` server's tool names (#1105).
     */
    private const val CATALOG_PREFIX = "catalog_"

    private val CATALOG_TOOL_NAMES =
      setOf(
        ServeLibraryMcp.LIBRARY,
        "list_projects",
        "list_previews",
        "list_data_products",
        "render_preview",
        "render_matrix",
        "list_devices",
        "diff_semantics",
        "get_preview_data",
        "history_list",
        "history_diff",
        "history_read",
      )

    private fun wireName(name: String): String =
      if (name in CATALOG_TOOL_NAMES) CATALOG_PREFIX + name else name

    /** The internal name for a called tool; the pre-prefix name still dispatches. */
    private fun canonicalName(name: String): String =
      name.removePrefix(CATALOG_PREFIX).takeIf { it in CATALOG_TOOL_NAMES } ?: name

    private const val PROPERTIES = "properties"

    /**
     * The argument a gated tool call carries its grant token in.
     *
     * The HTTP credential is still preferred (it keeps secrets out of the transcript), but an agent
     * completing `request_access` mid-session receives its token as a tool result and can't change
     * its transport headers. Accepting the token as an argument lets it escalate within that
     * session on any client. Checked by the same [ServeMachineAuthorization] against the same grant
     * store: a second door, not new authority. See docs/design/AGENT_ACCESS_GRANTS.md.
     */
    const val TOKEN_ARGUMENT = "token"

    private const val RESOURCE_TOKEN_META_KEY = "compose-preview/token"

    private const val TOKEN_ARGUMENT_DESCRIPTION =
      "A grant token from poll_access, when you cannot set the X-Compose-Preview-Token header " +
        "yourself — an MCP client fixes its headers at connect time, so this is how a token " +
        "approved during this session is used in it. Prefer the header where you control it."

    /**
     * The grant token presented in-band, if any; read by both the transport and [callTool] so they
     * agree. Tool calls use `params.arguments`; resource reads use `params._meta` (no arguments
     * object). Blank is absent (an unset template variable).
     */
    fun presentedToken(request: JsonObject): String? {
      val params = request["params"] as? JsonObject ?: return null
      val method = (request["method"] as? JsonPrimitive)?.contentOrNull
      return if (method == "resources/read") {
        val metadata = params["_meta"] as? JsonObject ?: return null
        (metadata[RESOURCE_TOKEN_META_KEY] as? JsonPrimitive)?.contentOrNull?.takeIf {
          it.isNotBlank()
        }
      } else tokenArgument(params["arguments"] as? JsonObject ?: return null)
    }

    /** Shape check only: an override-bearing catalog URI that carries `exp` and `sig`. */
    internal fun isSignedOverrideUri(uri: String): Boolean {
      if (!uri.startsWith(RESOURCE_URI_PREFIX)) return false
      val params = uri.substringAfter('?', missingDelimiterValue = "").split('&')
      // Same first-match rule as the handler's own parse, so the door and the handler agree.
      fun present(name: String) =
        params
          .firstOrNull { it.substringBefore('=') == name }
          ?.substringAfter('=', "")
          ?.isNotEmpty() == true
      return present("overrides") && present("exp") && present("sig")
    }

    internal fun tokenArgument(arguments: JsonObject): String? =
      (arguments[TOKEN_ARGUMENT] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

    /**
     * Whether this message must present a grant. Kept beside the tools so the transport needs no
     * second list; anything unrecognised is gated.
     */
    fun requiresGrant(request: JsonObject): Boolean {
      val method = (request["method"] as? JsonPrimitive)?.contentOrNull ?: return true
      // A notification (no `id`) is accepted and dropped without being handled at all.
      if (request["id"] == null) return false
      if (method in UNGATED_METHODS) return false
      // The MCP App loader reads this public, static asset before it can present a token returned
      // by a tool. Do not open resource reads generally: hosted preview resources remain private.
      if (method == "resources/read") {
        val params = request["params"] as? JsonObject ?: return true
        val uri = (params["uri"] as? JsonPrimitive)?.contentOrNull
        // A signed override link is admitted without a grant; the handler verifies the signature
        // (or demands live scope) before it touches a catalog. See [signResourceUri].
        return uri != MCP_APP_VIEWER_URI &&
          uri != ServeLibraryMcp.RESOURCE_URI &&
          UiBuilderJsonSchemas.byUri(uri.orEmpty()) == null &&
          (uri == null || !isSignedOverrideUri(uri))
      }
      if (method != "tools/call") return true
      val params = request["params"] as? JsonObject ?: return true
      val name = (params["name"] as? JsonPrimitive)?.contentOrNull ?: return true
      return name !in UNGATED_TOOLS
    }
  }
}

/**
 * A tool call this surface understood and refused. Carried as a tool error, not a transport one.
 */
internal class McpRequestException(message: String) : RuntimeException(message)

/** The data product a hosted catalog serves each preview's design-guidelines result as. */
internal const val GUIDELINES_RESULT_KIND = "guidelines/result"

private val GUIDELINES_RESULT_JSON = kotlinx.serialization.json.Json { explicitNulls = false }
