package ee.schimke.composeai.mcp

import ee.schimke.composeai.guidelines.CatalogGuidelinesLoader
import ee.schimke.composeai.guidelines.GuidelineBudget
import ee.schimke.composeai.guidelines.GuidelineEngine
import ee.schimke.composeai.guidelines.GuidelineModel
import ee.schimke.composeai.guidelines.GuidelineRunOptions
import ee.schimke.composeai.guidelines.GuidelineSurfaces
import ee.schimke.composeai.guidelines.OpenRouterClient
import ee.schimke.composeai.guidelines.PreviewGuidelineRequests
import ee.schimke.composeai.guidelines.PreviewGuidelineResult
import ee.schimke.composeai.guidelines.PreviewNode
import ee.schimke.composeai.guidelines.PreviewSubject
import ee.schimke.composeai.guidelines.SubjectPicture
import ee.schimke.composeai.guidelines.protocol.CatalogGuidelinesV1
import ee.schimke.composeai.guidelines.protocol.GuidelinePictureV1
import ee.schimke.composeai.guidelines.protocol.GuidelineRecordV1
import ee.schimke.composeai.guidelines.protocol.GuidelineRequestV1
import ee.schimke.composeai.mcp.protocol.CallToolResult
import ee.schimke.composeai.mcp.protocol.ContentBlock
import ee.schimke.composeai.mcp.protocol.ToolDef
import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Design guidelines for `@Preview`s, through the `ee.schimke.composeai:design-guidelines` engine —
 * the same batching, prompt and verdict reading the CLI's `compose-preview guidelines` runs:
 * - [PROMPT_TOOL] (`preview_guidelines_prompt`) builds the batched request for one or more previews
 *   and returns it with each render as an image block. It spends no key: an agent judges it with
 *   its own model.
 * - [CHECK_TOOL] (`check_preview_guidelines`) runs the engine on OpenRouter with the key in
 *   [KEY_ENV], and answers per preview with verdicts that name the nodes they are about (from the
 *   accessibility hierarchy) and any regions, the model that answered and the cost.
 *
 * The rules are the module's catalog guidelines
 * (`build/compose-previews/ui-builder.guidelines.json`, written by compose-ai-tools' discovery), or
 * a `guidelines` file or URL the caller names.
 */
object PreviewGuidelinesMcp {
  const val PROMPT_TOOL: String = "preview_guidelines_prompt"
  const val CHECK_TOOL: String = "check_preview_guidelines"

  /** Where the OpenRouter key is read from — the environment only, never a tool argument. */
  const val KEY_ENV: String = "COMPOSE_PREVIEW_OPENROUTER_KEY"

  const val DEFAULT_MAX_COST_USD: Double = 0.10

  /** A preview resolved for checking: its URI, id, render and the evidence the host can give. */
  data class Resolved(
    val uri: String,
    val previewId: String,
    val label: String,
    /** The module's build directory, where its catalog guidelines are discovered. */
    val buildDir: File?,
  )

  /** What the tools need from the MCP server; faked in tests. */
  interface Host {
    /** A preview name, FQN or `compose-preview://` URI; null when it matches nothing. */
    fun resolve(ref: String): Resolved?

    /** The preview's render as PNG bytes. */
    fun render(preview: Resolved): ByteArray

    /** The `a11y/hierarchy` data product's payload, or null when the host cannot produce it. */
    fun a11yHierarchy(preview: Resolved): JsonElement? = null

    /** The preview function's source, or null. */
    fun source(preview: Resolved): String? = null

    /** The OpenRouter key, from [KEY_ENV]. */
    val openRouterKey: String?

    /** The model client for a key; a fake in tests. */
    fun model(key: String): GuidelineModel = OpenRouterClient(key)

    /** Loads a guidelines file or URL. */
    fun loadGuidelines(location: String): CatalogGuidelinesLoader.Loaded =
      if (location.startsWith("http://") || location.startsWith("https://")) {
        CatalogGuidelinesLoader.load(location)
      } else CatalogGuidelinesLoader.load(File(location))
  }

  fun promptToolDef(): ToolDef =
    ToolDef(
      name = PROMPT_TOOL,
      description =
        "Build the design-guidelines prompt for one or more @Previews — the module's catalog " +
          "rules (ui-builder.guidelines.json), each render, its accessibility nodes and source — " +
          "as one batched request (compose-ui-builder/guidelines-prompt/v1), returned with the " +
          "renders as images. Spends no key: judge it with your own model, citing node ids. " +
          "Takes `previews` (names, FQNs or compose-preview:// URIs) and optional `guidelines` " +
          "(a file or URL) and `surface` (component | screen | widget).",
      inputSchema = inputSchema(withCost = false),
    )

  fun checkToolDef(): ToolDef =
    ToolDef(
      name = CHECK_TOOL,
      description =
        "Check @Previews against their catalog's design guidelines with a model on OpenRouter " +
          "(the key comes from $KEY_ENV in the server's environment). Batches the previews, " +
          "sends each render with its accessibility nodes and source, and returns per-preview " +
          "verdicts naming the nodes (and picture regions) they are about, the model that " +
          "answered and the cost. `max_cost` (USD, default $DEFAULT_MAX_COST_USD) caps the run.",
      inputSchema = inputSchema(withCost = true),
    )

  private fun inputSchema(withCost: Boolean): JsonObject = buildJsonObject {
    put("type", "object")
    putJsonObject("properties") {
      putJsonObject("previews") {
        put("type", "array")
        putJsonObject("items") { put("type", "string") }
        put("description", "Preview names, FQNs or compose-preview:// URIs; one batch.")
      }
      putJsonObject("guidelines") {
        put("type", "string")
        put("description", "A ui-builder.guidelines.json file or URL; default: the module's.")
      }
      putJsonObject("surface") {
        put("type", "string")
        putJsonArray("enum") {
          add(JsonPrimitive(GuidelineSurfaces.COMPONENT))
          add(JsonPrimitive(GuidelineSurfaces.SCREEN))
          add(JsonPrimitive(GuidelineSurfaces.WIDGET))
        }
      }
      if (withCost) {
        putJsonObject("max_cost") {
          put("type", "number")
          put("description", "Most USD this call may spend.")
        }
        putJsonObject("model") {
          put("type", "string")
          put("description", "OpenRouter model id; default ${OpenRouterClient.DEFAULT_MODEL}.")
        }
      }
    }
    putJsonArray("required") { add(JsonPrimitive("previews")) }
  }

  fun prompt(args: JsonObject, host: Host): CallToolResult {
    val prepared =
      prepare(PROMPT_TOOL, args, host).getOrElse {
        return error(it.message!!)
      }
    val batches = PreviewGuidelineRequests.batches(prepared.guidelines, prepared.subjects)
    val requests = batches.map { batch ->
      PreviewGuidelineRequests.request(
        prepared.guidelines,
        batch,
        prepared.rulesSource,
        evidenceAvailable = emptyList(),
      )
    }
    val content = mutableListOf<ContentBlock>()
    requests.forEach { request ->
      content +=
        ContentBlock.Text(
          REPLY_JSON.encodeToString(GuidelineRequestV1.serializer(), stripped(request))
        )
      request.pictures.forEach { picture ->
        picture.dataUrl
          ?.substringAfter("base64,", "")
          ?.takeIf { it.isNotEmpty() }
          ?.let { content += ContentBlock.Image(it, "image/png") }
      }
    }
    content +=
      ContentBlock.Text(
        "Judge every rule for every subject; name the subject and cite node ids from the " +
          "a11y-hierarchy evidence in each verdict, as the request's response schema asks."
      )
    return CallToolResult(content = content)
  }

  fun check(args: JsonObject, host: Host): CallToolResult {
    val key =
      host.openRouterKey?.takeIf { it.isNotBlank() }
        ?: return error(
          "$CHECK_TOOL: no OpenRouter key. Set $KEY_ENV in the environment the MCP server runs " +
            "in (create a key at openrouter.ai, Settings, then Keys), or use $PROMPT_TOOL and " +
            "judge the prompt with your own model."
        )
    val prepared =
      prepare(CHECK_TOOL, args, host).getOrElse {
        return error(it.message!!)
      }
    val maxCost = (args["max_cost"] as? JsonPrimitive)?.doubleOrNull ?: DEFAULT_MAX_COST_USD
    val model =
      (args["model"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
        ?: OpenRouterClient.DEFAULT_MODEL
    val run =
      GuidelineEngine(
          host.model(key),
          options =
            GuidelineRunOptions(
              model = model,
              budget = GuidelineBudget(),
              maxCostUsd = maxCost,
              rulesSource = prepared.rulesSource,
              ranBy = "mcp",
            ),
        )
        .run(prepared.guidelines, prepared.subjects)
    val structured = buildJsonObject {
      put("catalog", prepared.guidelines.catalog)
      put("rulesVersion", prepared.guidelines.version)
      put("costUsd", run.costUsd)
      put("requests", run.requests)
      putJsonArray("problems") { run.problems.forEach { add(JsonPrimitive(it)) } }
      putJsonArray("previews") { run.results.forEach { add(resultJson(it)) } }
    }
    val summary =
      "${run.results.size} preview(s) checked against `${prepared.guidelines.catalog}` " +
        "guidelines v${prepared.guidelines.version} — ${run.requests} request(s), $" +
        "%.4f".format(run.costUsd) +
        run.results.joinToString("") { result ->
          val fails = result.record.verdicts.filter { it.verdict == "fail" }
          "\n- ${result.previewId}: " +
            (if (fails.isEmpty()) "no broken rules"
            else
              fails.joinToString("; ") {
                "${it.ruleId} (${it.nodeIds.joinToString()}): ${it.reason}"
              })
        } +
        run.problems.joinToString("") { "\n! $it" }
    return CallToolResult(
      content = listOf(ContentBlock.Text(summary), ContentBlock.Text(structured.toString())),
      structuredContent = structured,
    )
  }

  private class Prepared(
    val guidelines: CatalogGuidelinesV1,
    val subjects: List<PreviewSubject>,
    val rulesSource: String,
  )

  private fun prepare(tool: String, args: JsonObject, host: Host): Result<Prepared> = runCatching {
    val refs =
      (args["previews"] as? JsonArray)
        ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        ?.filter { it.isNotBlank() }
        .orEmpty()
    require(refs.isNotEmpty()) { "$tool: `previews` must name at least one preview" }
    val resolved = refs.map { ref ->
      host.resolve(ref) ?: throw IllegalArgumentException("$tool: no preview matches `$ref`")
    }
    val location =
      (args["guidelines"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
        ?: resolved
          .firstNotNullOfOrNull { it.buildDir }
          ?.let { File(it, "compose-previews/${CatalogGuidelinesV1.FILE_NAME}") }
          ?.takeIf { it.isFile }
          ?.path
        ?: throw IllegalArgumentException(
          "$tool: the module has no ${CatalogGuidelinesV1.FILE_NAME} (its catalog publishes " +
            "none, or discovery has not run); pass `guidelines` with a file or URL"
        )
    val loaded = host.loadGuidelines(location)
    val guidelines =
      loaded.guidelines
        ?: throw IllegalArgumentException(
          "$tool: ${loaded.problem ?: "no guidelines at $location"}"
        )
    val surface =
      (args["surface"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
        ?: GuidelineSurfaces.COMPONENT
    val subjects = resolved.map { preview ->
      val png = host.render(preview)
      PreviewSubject(
        previewId = preview.previewId,
        label = preview.label,
        surface = surface,
        renderHash = sha256(png),
        pictures = listOf(SubjectPicture(GuidelinePictureV1.KIND_DEVICE, png, 0, 0)),
        nodes = host.a11yHierarchy(preview)?.let(::nodesOf).orEmpty(),
        source = host.source(preview),
      )
    }
    Prepared(guidelines, subjects, location)
  }

  /** `a11y/hierarchy` nodes as the engine's [PreviewNode]s: id, role, label and pixel bounds. */
  internal fun nodesOf(payload: JsonElement): List<PreviewNode> {
    val nodes =
      ((payload as? JsonObject)?.get("nodes") as? JsonArray)
        ?: (payload as? JsonArray)
        ?: return emptyList()
    return nodes.mapIndexedNotNull { index, element ->
      val node = element as? JsonObject ?: return@mapIndexedNotNull null
      fun text(name: String) = (node[name] as? JsonPrimitive)?.contentOrNull
      val bounds = text("boundsInScreen") ?: return@mapIndexedNotNull null
      PreviewNode.parseBounds(
        text("ref") ?: text("id") ?: "n$index",
        bounds,
        text("role"),
        text("label").orEmpty(),
      )
    }
  }

  private fun resultJson(result: PreviewGuidelineResult): JsonObject = buildJsonObject {
    put("previewId", result.previewId)
    result.renderHash?.let { put("renderHash", it) }
    put("record", REPLY_JSON.encodeToJsonElement(GuidelineRecordV1.serializer(), result.record))
    putJsonArray("unchecked") { result.unchecked.forEach { add(JsonPrimitive(it)) } }
    put("fromCache", result.fromCache)
  }

  /** The request with picture bytes left out of the JSON; they travel as image blocks. */
  private fun stripped(request: GuidelineRequestV1): GuidelineRequestV1 =
    request
      .newBuilder()
      .apply {
        pictures = request.pictures.map { it.newBuilder().apply { dataUrl = null }.build() }
      }
      .build()

  private fun error(message: String) =
    CallToolResult(content = listOf(ContentBlock.Text(message)), isError = true)

  private fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

  private val REPLY_JSON = Json {
    explicitNulls = false
    ignoreUnknownKeys = true
  }
}
