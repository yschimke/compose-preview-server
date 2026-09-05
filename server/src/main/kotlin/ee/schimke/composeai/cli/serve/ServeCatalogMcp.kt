package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.daemon.devices.DeviceDimensions
import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import ee.schimke.composeai.uibuilder.service.AuthenticatedUiBuilderActor
import ee.schimke.composeai.web.WebEscaping
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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

/**
 * Stateless MCP 2025-06-18 surface aggregating every served catalog.
 *
 * Transport is owned by [ServeHttpServer]; this class owns only MCP lifecycle messages and the
 * catalog-facing resources/tools. It shares the HTTP server's render semaphore, so a remote agent
 * cannot open a second, unmetered render lane beside the browser routes.
 */
class ServeCatalogMcp(
  private val sessions: ServeSessionRegistry,
  private val renderSemaphore: Semaphore,
  private val renderQueueWaitSeconds: Long = 2,
  /**
   * Project mode's locally-derived timeline, when this server was started against a checkout. Null
   * on a hosted box, where the published manifest on the delivery branch — not this process — is
   * the truth about what a catalog has rendered. See [historyJson].
   */
  private val projectHistory: ServeProjectHistory? = null,
  /**
   * The UI-builder door, when this box serves one. Null leaves the surface exactly as it was: the
   * tools are absent from `tools/list` rather than present and failing, so a client discovers what
   * this server can actually do.
   */
  private val uiBuilder: ServeUiBuilderMcp? = null,
  /** Whether this box can also compile a design natively; see [ServeUiBuilderMcp.RENDER_NATIVE]. */
  private val uiBuilderNative: Boolean = false,
) {
  data class Reply(val body: JsonObject?, val accepted: Boolean = false)

  /**
   * The grant flow, as much of it as an MCP client needs and no more.
   *
   * Kept as a seam rather than a store reference so this class stays free of HTTP, rate limits and
   * the request's own address: each method answers with the SAME JSON body the matching
   * `/agent-access/…` route returns, so the two surfaces cannot drift into describing one flow two
   * ways. Null means the box is throttling — a tool error, not an exception, because a client that
   * asked too fast should be told to wait rather than handed a broken session.
   */
  interface AgentAccess {
    suspend fun open(
      label: String,
      scope: String,
      ttlSeconds: Long,
      capabilities: List<String>,
    ): String?

    suspend fun poll(requestId: String, deviceSecret: String, waitSeconds: Long): String?
  }

  private data class PreviewTarget(val catalog: String, val previewId: String)

  /**
   * [liveAuthorization] stays last so a caller can pass it as a trailing lambda; [access] is the
   * optional one, absent on a box that issues no grants.
   */
  suspend fun handle(
    request: JsonObject,
    access: AgentAccess? = null,
    /**
     * The UI-builder capability check for this particular request, asked of the transport because
     * only it holds the call the credential arrived on. Defaults to refusing, so a caller that
     * forgets to pass one cannot accidentally open the builder to an unauthenticated agent.
     */
    uiBuilderAuthorization: (UiBuilderRouteCapability) -> UiBuilderAuthorizationDecision = {
      UiBuilderAuthorizationDecision.Missing
    },
    liveAuthorization: () -> ServeMachineAuthorization.Decision,
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
          "tools/call" ->
            try {
              callTool(params, liveAuthorization, access, uiBuilderAuthorization)
            } catch (e: McpRequestException) {
              toolError(e.message ?: "Tool call failed")
            }
          "resources/list" -> listResources()
          "resources/read" -> readResource(params)
          else -> return Reply(error(id, METHOD_NOT_FOUND, "Unknown method '$method'"))
        }
      } catch (e: McpRequestException) {
        return Reply(error(id, INVALID_PARAMS, e.message ?: "Invalid parameters"))
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
        else -> MCP_PROTOCOL_VERSION
      }
    return buildJsonObject {
      put("protocolVersion", negotiated)
      put(
        "capabilities",
        buildJsonObject {
          put("tools", JsonObject(emptyMap()))
          put("resources", buildJsonObject { put("subscribe", false) })
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
        "This endpoint exposes every hosted Compose Preview catalog. Use list_projects to " +
          "discover catalog ids. Reading published previews needs preview access; made-to-order " +
          "renders and data products need live access. With no credential, call request_access, " +
          "show the human its approveUrl and userCode, then poll_access (which waits for the " +
          "decision) until it answers approved; send the token it returns as the " +
          "X-Compose-Preview-Token header.",
      )
    }
  }

  private fun tools(accessEnabled: Boolean): JsonArray = buildJsonArray {
    if (accessEnabled) {
      // First in the list on purpose: a client with no credential can call only these two, and a
      // model reading the list top-down should meet the way in before the tools it cannot use yet.
      add(
        tool(
          "request_access",
          "Ask a human for access to this server. Returns an approveUrl and a userCode: show " +
            "BOTH to the person you are working with, ask them to open the link and check that " +
            "the code on the page matches, then call poll_access. The link grants nothing by " +
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
            "longer call. A wait that times out answers status=pending, and you simply call " +
            "again. Then approved (with the token) or denied/expired. Send the " +
            "token as the X-Compose-Preview-Token header on every later call.",
          """{"type":"object","properties":{"requestId":{"type":"string"},"deviceSecret":{"type":"string"},"waitSeconds":{"type":"integer","minimum":0,"maximum":30}},"required":["requestId","deviceSecret"]}""",
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
        "List every remote catalog with its stable id and preview count.",
        EMPTY_SCHEMA,
      )
    )
    add(
      tool(
        "list_previews",
        "List Compose previews and published metadata across every catalog, or one named catalog.",
        CATALOG_FILTER_SCHEMA,
      )
    )
    add(
      tool(
        "render_preview",
        "Render one preview. Like local compose-ai-tools, the default semantics observation is " +
          "token-frugal; request observe=png for pixels, observe=svg for the compose/figma-svg " +
          "vector export as SVG source, or observe=scroll-png / observe=scroll-svg for the " +
          "full-page capture of a scrollable screen rather than the viewport crop. This " +
          "made-to-order lane requires live grant scope. Use resources/read for the published " +
          "snapshot lane.",
        """{"type":"object","properties":{"uri":{"type":"string"},"catalog":{"type":"string"},"previewId":{"type":"string"},"observe":{"type":"string","enum":["png","svg","scroll-png","scroll-svg","semantics","hash"]},"overrides":{"type":"object","additionalProperties":{"type":["string","number","boolean"]}}},"anyOf":[{"required":["uri"]},{"required":["catalog","previewId"]}]}""",
      )
    )
    add(
      tool(
        "render_matrix",
        "Render one preview across the cross-product of the given override axes in a single " +
          "call, returning a hash/size observation per cell (observe=png adds the pixels). " +
          "Prefer this over a render_preview per combination: the cells share one catalog lease " +
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
      addAll(ServeUiBuilderMcp.declarations(::tool, uiBuilderNative, it.supportsComments))
    }
    add(
      tool(
        "list_data_products",
        "List structured data-product kinds across catalogs, optionally filtered by target.",
        """{"type":"object","properties":{"catalog":{"type":"string"},"previewId":{"type":"string"},"uri":{"type":"string"}}}""",
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
    liveAuthorization: () -> ServeMachineAuthorization.Decision,
    access: AgentAccess?,
    uiBuilderAuthorization: (UiBuilderRouteCapability) -> UiBuilderAuthorizationDecision,
  ): JsonObject {
    val name = params.requiredString("name")
    val args = params["arguments"] as? JsonObject ?: JsonObject(emptyMap())
    uiBuilderTool(name, args, uiBuilderAuthorization)?.let {
      return it
    }
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
        val body =
          broker.poll(
            args.requiredString("requestId"),
            args.requiredString("deviceSecret"),
            // Default to waiting rather than to spinning: a client that says nothing is a client
            // that would otherwise call this again in three seconds, through a model.
            args["waitSeconds"]?.jsonPrimitive?.longOrNull
              ?: ServeAgentGrants.DEFAULT_POLL_WAIT_SECONDS,
          )
        body?.let { textResult(it) } ?: toolError(ACCESS_THROTTLED)
      }
      "status" -> textResult(statusJson().toString())
      "list_projects" -> textResult(projectsJson().toString())
      "list_previews" -> textResult(previewsJson(args.optionalString("catalog")).toString())
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
        requireLive(liveAuthorization)
        val target = args.previewTarget()
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
        requireLive(liveAuthorization)
        diffSemanticsResult(args)
      }
      "render_matrix" -> {
        requireLive(liveAuthorization)
        val target = args.previewTarget()
        withCatalog(target.catalog) { host ->
          val preview = resolvePreview(host, target.previewId)
          matrixResult(host, preview, target.catalog, args)
        }
      }
      "preview-stories" -> {
        requireLive(liveAuthorization)
        val ids =
          ((args["storyIds"] ?: args["ids"]) as? JsonArray)?.map { it.jsonPrimitive.content }
            ?: listOf(args.firstString("storyId", "id"))
        if (ids.size > MAX_STORIES_PER_CALL) {
          throw McpRequestException(
            "preview-stories accepts at most $MAX_STORIES_PER_CALL ids per call"
          )
        }
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
                )
                .forEach(::add)
            }
          }
        }
        buildJsonObject { put("content", content) }
      }
      "get_preview_data" -> {
        requireLive(liveAuthorization)
        val target = args.previewTarget()
        withCatalog(target.catalog) { host ->
          val preview = resolvePreview(host, target.previewId)
          val overrides = parseOverrides(preview, args["overrides"] as? JsonObject)
          dataProductResult(host, preview.id, args.requiredString("kind"), overrides)
        }
      }
      else -> toolError("unknown tool: $name")
    }
  }

  private suspend fun listResources(): JsonObject {
    val resources = buildJsonArray {
      catalogIds().forEach { catalog ->
        withCatalog(catalog) { host ->
          host.previews.forEach { preview ->
            add(
              buildJsonObject {
                put("uri", resourceUri(catalog, preview.id))
                put("name", "${host.label}: ${preview.label}")
                put("description", "$catalog: ${preview.id}")
                put("mimeType", "image/png")
              }
            )
          }
        }
      }
    }
    return buildJsonObject { put("resources", resources) }
  }

  private suspend fun readResource(params: JsonObject): JsonObject {
    val uri = params.requiredString("uri")
    val target = targetFromUri(uri)
    return withCatalog(target.catalog) { host ->
      val preview = resolvePreview(host, target.previewId)
      val png = renderPng(host, preview.id, PreviewOverrides(), preferPublished = true).png
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
   * The cross-product of [axes] over one preview, rendered cell by cell.
   *
   * Exists because comparing axes is the common agent task and the per-cell alternative is a round
   * trip each: probing eight axes over this endpoint took twenty sequential `render_preview` calls,
   * every one of them a fresh catalog lease and a fresh permit acquisition. Here the cells share
   * the lease, and each still takes the render permit individually so the matrix competes with
   * browser traffic on equal terms rather than reserving the renderer for itself.
   *
   * The base `overrides` (if any) are the floor every cell starts from; an axis value with the same
   * key wins for that cell, so a caller can pin `uiMode=dark` once and vary `fontScale` over it.
   */
  private suspend fun matrixResult(
    host: ServeHost,
    preview: ServePreview,
    catalog: String,
    args: JsonObject,
  ): JsonObject {
    val observe = args["observe"]?.jsonPrimitive?.contentOrNull?.lowercase() ?: "hash"
    if (observe !in MATRIX_OBSERVATION_MODES) {
      throw McpRequestException("render_matrix 'observe' must be one of png or hash")
    }
    val rawAxes =
      args["axes"] as? JsonObject
        ?: throw McpRequestException("render_matrix requires an 'axes' object")
    if (rawAxes.isEmpty()) throw McpRequestException("render_matrix requires at least one axis")
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
        "render_matrix would produce $cells cells; the cap is $MAX_MATRIX_CELLS. " +
          "Narrow an axis or split the call."
      )
    }

    val combinations =
      axes.fold(listOf(base)) { acc, (key, values) ->
        acc.flatMap { partial -> values.map { partial + (key to it) } }
      }

    val knobKinds = ServeOverrides.declaredKnobKinds(preview)
    val rendered = buildJsonArray {
      combinations.forEach { params ->
        val unknown = params.keys.filterNot(ServeOverrides::isOverrideParam).sorted()
        if (unknown.isNotEmpty()) {
          throw McpRequestException("unknown override ${unknown.joinToString()} in render_matrix")
        }
        val overrides =
          when (val parsed = ServeOverrides.parse(params, knobKinds)) {
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
            if (observe == "png") put("png", Base64.getEncoder().encodeToString(cell.png))
          }
        )
      }
    }

    // Distinct hashes over the whole matrix: the one number that says whether these axes actually
    // move the pixels. All-identical means the axes are inert for this preview, which is the
    // question the twenty-call version was being used to answer.
    val distinct =
      rendered.mapNotNull { it.jsonObject["sha256"]?.jsonPrimitive?.contentOrNull }.toSet().size
    return textResult(
      buildJsonObject {
        put("schema", "compose-preview/catalog-mcp-matrix/v1")
        put("catalog", catalog)
        put("previewId", preview.id)
        put("uri", resourceUri(catalog, preview.id))
        put("observe", observe)
        put("cellCount", rendered.size)
        put("distinctRenders", distinct)
        put("cells", rendered)
      }
        .toString()
    )
  }

  /**
   * The `device` override's accepted vocabulary, resolved from the render lane's own catalog.
   *
   * Geometry is not authored here — every value comes from [DeviceDimensions.resolve], the same
   * call the render path makes when it decides what a `@Preview(device = …)` produces, so a name
   * listed here is a frame the backend will actually render. The tool exists because an
   * unrecognised device name is *not* an error on the render path: it falls through to the default
   * frame, which from the caller's side is indistinguishable from a device that renders identically
   * to the default.
   */
  /**
   * One preview's render timeline, in whichever of the three shapes this deployment can honestly
   * produce. The `mode` field says which, because the three are not interchangeable and an agent
   * that cannot tell them apart would read "no versions" as "nothing ever changed".
   *
   * - `published` — the catalog came from a delivery branch, so the timeline it *has* is the
   *   `history.json` published on that branch. This server does not proxy it: the manifest is a
   *   whole-catalog document on a public host, the browser viewer fetches it directly for the same
   *   reason, and a server-side copy would be a cache with its own staleness against a branch that
   *   moves independently of this process. So the fetchable URL is returned instead, alongside the
   *   template for addressing any single historical render.
   * - `local` — project mode, where the timeline is derived from the checkout's own delivery-branch
   *   commits and served inline, with each version addressable through this server's
   *   `/history/render/<blob>.png` lane.
   * - `none` — an uploaded bundle with neither. Reported as a reason rather than an empty list,
   *   which would read as a preview that has never changed.
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
      // The catalog load already fetched and parsed `history.json` from the same immutable tree as
      // `catalog.json`, so the timeline is in memory and pinned to the catalog being served. Where
      // it is, answer with the preview's own slice rather than sending the caller to fetch a
      // whole-catalog document for one row: measured at 1 MB to read 497 bytes on `m3-catalog`.
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
   * The manifest's versions, each with the absolute URL that serves those exact bytes.
   *
   * `raw.githubusercontent.com/<repo>/<commit>/<path>` is what makes a timeline viewable at all:
   * the delivery branch carries only the *current* bytes at its tip, while the raw host serves any
   * commit. Built here so a caller never has to join `commit` to `path` itself.
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
   * One version of a preview's render, in whichever mode produced it. `blob` is the content id
   * project mode addresses by; `commit` is what the published lane addresses by; both are present
   * in the manifest, so a caller can key on either.
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
   * The timeline behind [previewId], flattened for the diff and read lanes.
   *
   * Same precedence as [historyJson]: a catalog fetched from a delivery branch has already
   * published what it rendered, so its manifest wins over whatever a local checkout holds.
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
   * Compare two of a preview's renders.
   *
   * A **metadata** diff, deliberately: the manifest's versions are already collapsed distinct
   * renders, so "did the bytes change" is answered by the blob ids alone and needs no image fetch
   * on either side. `history_read` is there for the pixels when a caller actually wants them.
   *
   * With no `from`/`to` this compares the two newest versions, which is the question that gets
   * asked: did the last publish move this preview?
   */
  private suspend fun diffHistoryResult(args: JsonObject): JsonObject {
    val target = args.previewTarget()
    return withCatalog(target.catalog) { host ->
      val preview = resolvePreview(host, target.previewId)
      val view =
        historyView(host, preview.id)
          ?: throw McpRequestException(
            "no timeline for '${preview.id}'; call history_list to see why this catalog has none"
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
   * One historical render's bytes, through this server.
   *
   * `preview` scope rather than `live`, matching the HTTP permalink lane: this replays bytes that
   * were already published, and commissions no render. It is still bounded — the published lane
   * goes through [ServeBundleHost]'s pinned-fetch permit and its miss cache, and the local lane
   * only ever serves blobs the timeline already names.
   */
  private suspend fun readHistoryResult(args: JsonObject): JsonObject {
    val target = args.previewTarget()
    val wanted =
      args.optionalString("commit")
        ?: args.optionalString("blob")
        ?: throw McpRequestException("history_read requires 'commit' or 'blob'")
    return withCatalog(target.catalog) { host ->
      val preview = resolvePreview(host, target.previewId)
      val view =
        historyView(host, preview.id)
          ?: throw McpRequestException(
            "no timeline for '${preview.id}'; call history_list to see why this catalog has none"
          )
      val version =
        view.versions.firstOrNull { it.commit.startsWith(wanted) || it.blob.startsWith(wanted) }
          ?: throw McpRequestException(
            "'$wanted' names no recorded render of '${preview.id}'; history_list lists the ones " +
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
   * Compare two previews' semantics by **authored testTag**, not by positional ref.
   *
   * The identity choice is the whole design, and it is [ServeSemanticsTags]': a `SemanticsRefs` ref
   * indexes siblings that share an anchor, so `r/role:Button[0]` means "the first Button under this
   * parent" and inserting a Button ahead of it silently retargets the same string at different
   * pixels — a diff built on refs reports "unchanged" for exactly the edit a reader most needs to
   * see. A `testTag` is authored, so it either survives an edit or stops resolving, and both are
   * reported here.
   *
   * Reads the `tags` index off each side's annotations payload rather than re-walking the tree:
   * that index is already the wire contract [ServeAnnotationsPayload] publishes, including its
   * `count` (how many nodes carry the tag — a tag is only a usable identity while exactly one does)
   * and its explicitly-named coordinate space.
   */
  private suspend fun diffSemanticsResult(args: JsonObject): JsonObject {
    val left = args.previewTarget()
    val other =
      args["other"] as? JsonObject
        ?: throw McpRequestException("diff_semantics requires an 'other' preview to compare with")
    val right = other.previewTarget()

    val leftTags = tagIndex(left, args["overrides"] as? JsonObject)
    val rightTags = tagIndex(right, args["otherOverrides"] as? JsonObject)

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
              // A count change is an ambiguity appearing or disappearing, which is a different
              // event from a move and is worth naming separately: a tag carried by two nodes is no
              // longer an identity anything can resolve.
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
            put("uri", resourceUri(left.catalog, left.previewId))
            put("taggedNodes", leftTags.size)
          },
        )
        put(
          "right",
          buildJsonObject {
            put("uri", resourceUri(right.catalog, right.previewId))
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
  ): JsonObject = buildJsonObject {
    put(
      "content",
      JsonArray(renderContent(host, previewId, uri, overrides, observe, requestedKeys)),
    )
  }

  private suspend fun renderContent(
    host: ServeHost,
    previewId: String,
    uri: String,
    overrides: PreviewOverrides,
    observe: String,
    requestedKeys: List<String> = emptyList(),
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
    if (observe == "scroll-png")
      return listOf(imageContent(renderScrollPng(host, previewId, overrides)))
    val rendered = renderPng(host, previewId, overrides)
    val png = rendered.png
    if (observe == "png") {
      // An override-free browse keeps the bare image it has always returned. An override-bearing
      // one gets the provenance block beside the pixels, because pixels alone cannot answer the
      // question that actually matters to the caller: did my override reach the renderer? Two
      // different overrides can produce byte-identical output either because both applied and
      // neither moved anything, or because a baked lane answered and ignored them both.
      return if (requestedKeys.isEmpty()) listOf(imageContent(png))
      else
        listOf(
          imageContent(png),
          textContent(JsonObject(provenance(rendered, requestedKeys)).toString()),
        )
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
      buildJsonObject {
        put("type", "text")
        put("text", observation.toString())
      }
    )
  }

  private suspend fun dataProductResult(
    host: ServeHost,
    previewId: String,
    kind: String,
    overrides: PreviewOverrides,
  ): JsonObject {
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
   * What answered this request, and whether the overrides asked for reached it.
   *
   * [RenderOutcome.Generation] is already threaded through the host for exactly this purpose — a
   * `baked` generation means no renderer ran and the request's overrides are NOT reflected in the
   * bytes — but the MCP lane used to drop it on the floor, leaving a caller unable to tell an
   * override that applied and changed nothing from one that was never honoured.
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
            "published preview '$previewId' is unavailable; use render_preview with live scope"
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
   * The `compose/figma-svg` counterpart of [renderPng], returned as SVG source rather than a base64
   * `image` block: the bytes are XML, and an `image/svg+xml` image block is symmetric with PNG but
   * renders in almost no MCP client, while the source is what a vector consumer wants.
   *
   * Shares [withRenderPermit] with the raster lane so SVG cannot become a second, unmetered render
   * path — the same reason the PNG lane holds the HTTP server's semaphore.
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
   * The **full-page** counterparts of [renderSvg] and [renderPng] — `compose/figma-svg-long` and
   * `render/scroll/long`, the whole scrollable screen rather than the viewport crop.
   *
   * Gated on [ServeHost.hasScrollExportFor] for the same reason the vector lane is gated on
   * `hasSvgExportFor`: the tall re-render needs a daemon, so a static bundle has no scroll producer
   * and its `NotFound` would otherwise read as "no such preview".
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
    // Unknown keys are REFUSED here, unlike on `GET /render` where they are ignored so a URL may
    // carry a cache-buster or an analytics tag beside the axes. An MCP `overrides` object has no
    // such passengers: every key in it was typed on purpose, so a key this server does not consume
    // is a caller error, and silently dropping it produces a render that answers a different
    // question than the one asked — indistinguishable, from the outside, from an override that
    // applied and changed nothing.
    val unknown = params.keys.filterNot(ServeOverrides::isOverrideParam).sorted()
    if (unknown.isNotEmpty()) {
      throw McpRequestException(
        "unknown override ${if (unknown.size == 1) "key" else "keys"} ${unknown.joinToString()}; " +
          "supported: ${ServeOverrides.SUPPORTED_KEYS.sorted().joinToString()}, " +
          "plus ${ServeOverrides.KNOB_PREFIX}<knob> and ${ServeOverrides.RC_NAMED_PREFIX}<name>"
      )
    }
    val knobKinds = ServeOverrides.declaredKnobKinds(preview)
    return when (val parsed = ServeOverrides.parse(params, knobKinds)) {
      is OverrideParse.Ok -> parsed.overrides
      is OverrideParse.Invalid -> throw McpRequestException(parsed.message)
    }
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

  private suspend fun dataProductsJson(args: JsonObject): JsonArray {
    val uriTarget = args.optionalString("uri")?.let(::targetFromUri)
    val selectedCatalog = uriTarget?.catalog ?: args.optionalString("catalog")
    val selectedPreview = uriTarget?.previewId ?: args.optionalString("previewId")
    if (selectedPreview != null && selectedCatalog == null) {
      throw McpRequestException("'catalog' is required when filtering by 'previewId'")
    }
    return buildJsonArray {
      catalogIds(selectedCatalog).forEach { catalog ->
        withCatalog(catalog) { host ->
          host.previews
            .filter { selectedPreview == null || it.id == selectedPreview }
            .forEach { preview ->
              val kinds = buildSet {
                addAll(preview.dataProductKinds)
                if (host.hasA11yOverlayFor(preview.id)) add("a11y/hierarchy")
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

  private suspend fun statusJson(): JsonObject = buildJsonObject {
    put("schema", "compose-preview-mcp-status/v1")
    put("ready", true)
    put("remote", true)
    put("aggregate", true)
    put("toolCatalog", buildJsonObject { put("status", "ready") })
    put("projects", projectsJson()["projects"]!!)
  }

  private suspend fun projectsJson(): JsonObject = buildJsonObject {
    put(
      "projects",
      buildJsonArray {
        catalogIds().forEach { catalog ->
          withCatalog(catalog) { host -> add(projectJson(catalog, host)) }
        }
      },
    )
  }

  private fun projectJson(catalog: String, host: ServeHost): JsonObject = buildJsonObject {
    put("workspaceId", catalog)
    put("rootProjectName", host.label)
    put("catalog", catalog)
    put("label", host.label)
    put("previewCount", host.previews.size)
    put("remote", true)
  }

  private suspend fun previewsJson(selectedCatalog: String?): JsonObject = buildJsonObject {
    put(
      "catalogs",
      buildJsonArray {
        catalogIds(selectedCatalog).forEach { catalog ->
          withCatalog(catalog) { host ->
            add(
              buildJsonObject {
                put("catalog", catalog)
                put("label", host.label)
                put("previews", JsonArray(host.previews.map { previewJson(catalog, it, host) }))
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
        withCatalog(catalog) { host -> host.previews.forEach { add(storyJson(catalog, it)) } }
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
              "Render with preview-stories. Native render_preview and get_preview_data also " +
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
    // Advertised beside `dataProductKinds` for the same reason that is: `observe=svg` exists per
    // preview, not per catalog, so without this an agent can only discover the vector lane by
    // asking for it and reading the refusal.
    put("svgAvailable", host.hasSvgExportFor(preview.id))
    put("scrollAvailable", host.hasScrollExportFor(preview.id))
    preview.state?.let { put("state", it) }
    preview.theme?.let { put("theme", it) }
  }

  private fun resolvePreview(host: ServeHost, id: String): ServePreview =
    host.previews.firstOrNull { it.id == id } ?: throw McpRequestException("no such preview '$id'")

  private fun JsonObject.previewTarget(): PreviewTarget {
    optionalString("uri")?.let {
      return targetFromUri(it)
    }
    return PreviewTarget(requiredString("catalog"), requiredString("previewId"))
  }

  private fun storyTarget(value: String): PreviewTarget {
    if (value.startsWith(RESOURCE_URI_PREFIX)) return targetFromUri(value)
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
    val parts = uri.removePrefix(RESOURCE_URI_PREFIX).split('/', limit = 2)
    if (parts.size != 2) throw McpRequestException("invalid compose-preview resource URI")
    return PreviewTarget(decode(parts[0]), decode(parts[1]))
  }

  private fun decode(value: String): String =
    URLDecoder.decode(value.replace("+", "%2B"), StandardCharsets.UTF_8)

  private fun catalogIds(selected: String? = null): List<String> {
    val ids = sessions.knownSessionIds()
    if (selected == null) return ids
    if (selected !in ids) throw McpRequestException("no such catalog '$selected'")
    return listOf(selected)
  }

  private suspend fun <T> withCatalog(catalog: String, block: suspend (ServeHost) -> T): T {
    if (catalog !in sessions.knownSessionIds()) {
      throw McpRequestException("no such catalog '$catalog'")
    }
    val lease =
      withContext(Dispatchers.IO) { sessions.lease(catalog) }
        ?: throw McpRequestException("catalog '$catalog' is unavailable")
    return try {
      block(lease.host)
    } finally {
      lease.close()
    }
  }

  private fun requireLive(check: () -> ServeMachineAuthorization.Decision) {
    when (val decision = check()) {
      is ServeMachineAuthorization.Decision.Authorized -> Unit
      ServeMachineAuthorization.Decision.Missing ->
        throw McpRequestException("live grant scope is required")
      is ServeMachineAuthorization.Decision.Forbidden -> throw McpRequestException(decision.message)
    }
  }

  /**
   * One UI-builder tool, or null when the name belongs to the catalog surface.
   *
   * The capability check happens here rather than inside [ServeUiBuilderMcp] because the credential
   * lives on the transport's call, not in the JSON-RPC message — the same reason the HTTP routes
   * authorize before they map. A missing grant is a tool error rather than a transport status: the
   * agent asked a question this surface understands and is being told it may not.
   */
  private suspend fun uiBuilderTool(
    name: String,
    args: JsonObject,
    authorize: (UiBuilderRouteCapability) -> UiBuilderAuthorizationDecision,
  ): JsonObject? {
    val builder = uiBuilder ?: return null
    val capability = builder.capabilityFor(name) ?: return null
    val actor =
      when (val decision = authorize(capability)) {
        is UiBuilderAuthorizationDecision.Authorized ->
          AuthenticatedUiBuilderActor(decision.actorId)
        UiBuilderAuthorizationDecision.Missing ->
          return toolError(
            "this tool needs a UI-builder ${capability.name.lowercase()} grant; none was presented"
          )
        UiBuilderAuthorizationDecision.Forbidden ->
          return toolError(
            "the presented identity lacks the UI-builder ${capability.name.lowercase()} capability"
          )
      }
    return textResult(builder.call(name, args, actor, callId = name))
  }

  private fun tool(name: String, description: String, schema: String): JsonObject =
    buildJsonObject {
      put("name", name)
      put("description", description)
      put("inputSchema", JSON.parseToJsonElement(schema))
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

  private fun success(id: JsonElement, result: JsonObject): JsonObject = buildJsonObject {
    put("jsonrpc", "2.0")
    put("id", id)
    put("result", result)
  }

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
    val SUPPORTED_PROTOCOL_VERSIONS = setOf(MCP_PROTOCOL_VERSION, MCP_PROTOCOL_VERSION_2025_03)

    private const val EMPTY_SCHEMA = """{"type":"object","properties":{}}"""
    private const val INVALID_REQUEST = -32600
    private const val METHOD_NOT_FOUND = -32601
    private const val INVALID_PARAMS = -32602
    private const val INTERNAL_ERROR = -32603
    private const val MAX_STORIES_PER_CALL = 16
    /**
     * Cells one `render_matrix` call may commission. Each is a full render under the shared permit,
     * so this bounds what a single MCP message can cost the box — the same reason
     * [MAX_STORIES_PER_CALL] exists, applied to a product rather than a list.
     */
    private const val MAX_MATRIX_CELLS = 24
    private val MATRIX_OBSERVATION_MODES = setOf("png", "hash")

    /** `a, b, or c` — the list keeps its grammar as observations are added to it. */
    private fun Collection<String>.orList(): String {
      val items = toList()
      return if (items.size < 2) items.joinToString()
      else items.dropLast(1).joinToString() + ", or " + items.last()
    }

    private const val RESOURCE_URI_PREFIX = "compose-preview://catalog/"
    private const val STORY_ID_SEPARATOR = "::"
    private val OBSERVATION_MODES =
      setOf("png", "svg", "scroll-png", "scroll-svg", "semantics", "hash")
    private const val CATALOG_FILTER_SCHEMA =
      """{"type":"object","properties":{"catalog":{"type":"string"}}}"""
    private val ANNOTATION_KINDS =
      setOf("compose/annotations", "compose/semantics", "compose/typography", "compose/tags")
    private val JSON = Json { ignoreUnknownKeys = false }

    private const val ACCESS_DISABLED =
      "This server does not issue agent access grants; ask its operator for a token."
    private const val ACCESS_THROTTLED =
      "Too many access requests from this address just now — wait a minute and try again."

    /**
     * JSON-RPC methods any caller may send, credential or not.
     *
     * Discovery only: what protocol this speaks, whether it is alive, and what it can be asked to
     * do. None of them reads a catalog, renders anything, or names a preview — [listResources] and
     * every catalog tool stay behind the gate. The reason to open these at all is that a client
     * which cannot complete `initialize` cannot reach the tool that asks for a credential either,
     * so an agent with no token has nowhere to start but out-of-band `curl`.
     */
    private val UNGATED_METHODS = setOf("initialize", "ping", "tools/list")

    /**
     * Tools callable without a grant — the two that exist to obtain one. Everything else in [tools]
     * answers about this host's catalogs and needs at least `preview` scope.
     */
    private val UNGATED_TOOLS = setOf("request_access", "poll_access")

    /**
     * Whether this message must present a grant before it is handled.
     *
     * Lives here, beside the tools it speaks for, so the transport does not have to keep its own
     * copy of the list — a second list is how a tool added on one side becomes reachable
     * unauthenticated on the other. Anything unrecognised is gated: a method or tool name this
     * version has never heard of is not a thing to open by default.
     */
    fun requiresGrant(request: JsonObject): Boolean {
      val method = (request["method"] as? JsonPrimitive)?.contentOrNull ?: return true
      // A notification (no `id`) is accepted and dropped without being handled at all.
      if (request["id"] == null) return false
      if (method in UNGATED_METHODS) return false
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
