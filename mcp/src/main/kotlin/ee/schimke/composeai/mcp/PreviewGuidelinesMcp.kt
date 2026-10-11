package ee.schimke.composeai.mcp

import ee.schimke.composeai.guidelines.CatalogGuidelinesLoader
import ee.schimke.composeai.guidelines.GuidelineBudget
import ee.schimke.composeai.guidelines.GuidelineCredentials
import ee.schimke.composeai.guidelines.GuidelineEngine
import ee.schimke.composeai.guidelines.GuidelineModel
import ee.schimke.composeai.guidelines.GuidelineRunOptions
import ee.schimke.composeai.guidelines.GuidelineSurfaces
import ee.schimke.composeai.guidelines.OpenAiCompatibleClient
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
 * - [CHECK_TOOL] (`check_preview_guidelines`) runs the engine on OpenRouter with the key from
 *   [KEY_ENV], else the user's credentials file (`compose-preview guidelines key set`, used only
 *   for a project trusted with `guidelines key trust`), and answers per preview with verdicts that
 *   name the nodes they are about (from the accessibility hierarchy) and any regions, the model
 *   that answered and the cost.
 *
 * The rules are the module's catalog guidelines
 * (`build/compose-previews/ui-builder.guidelines.json`, written by compose-ai-tools' discovery), or
 * a `guidelines` file or URL the caller names.
 */
object PreviewGuidelinesMcp {
  const val PROMPT_TOOL: String = "preview_guidelines_prompt"
  const val CHECK_TOOL: String = "check_preview_guidelines"

  /**
   * The environment variable holding the OpenRouter key, which beats the credentials file. Never a
   * tool argument.
   */
  const val KEY_ENV: String = GuidelineCredentials.ENV

  const val DEFAULT_MAX_COST_USD: Double = 0.10

  /** A preview resolved for checking: its URI, id, render and the evidence the host can give. */
  data class Resolved(
    val uri: String,
    val previewId: String,
    val label: String,
    /** The module's build directory, where its catalog guidelines are discovered. */
    val buildDir: File?,
    /** The workspace's Gradle root: what a stored key must be trusted for. */
    val projectRoot: File? = null,
  )

  /** [Host.a11yHierarchy]'s answer: the payload, or the reason there is none. */
  data class Hierarchy(val payload: JsonElement?, val problem: String? = null)

  /** What the tools need from the MCP server; faked in tests. */
  interface Host {
    /** A preview name, FQN or `compose-preview://` URI; null when it matches nothing. */
    fun resolve(ref: String): Resolved?

    /** The preview's render as PNG bytes. */
    fun render(preview: Resolved): ByteArray

    /**
     * The `a11y/hierarchy` data product, or why it could not be had. A missing hierarchy is said
     * out loud: without nodes the model cannot cite node ids, and a caller should know that rather
     * than receive an empty node list that looks like a preview with no nodes.
     */
    fun a11yHierarchy(preview: Resolved): Hierarchy =
      Hierarchy(null, "this host cannot produce the accessibility hierarchy")

    /** The preview function's source, or null. */
    fun source(preview: Resolved): String? = null

    /**
     * The preview's entry in its module's `previews.json`, or null when there is none to read. What
     * [GuidelineSurfaces.of] reads its surface and profile from — the same answer the CLI's
     * `compose-preview guidelines` reaches, so a preview is asked the same rules here.
     */
    fun manifestEntry(preview: Resolved): JsonObject? {
      val file = preview.buildDir?.let { File(it, "compose-previews/previews.json") } ?: return null
      if (!file.isFile) return null
      val manifest =
        runCatching { Json.parseToJsonElement(file.readText()) }.getOrNull() as? JsonObject
      return (manifest?.get("previews") as? JsonArray)
        ?.mapNotNull { it as? JsonObject }
        ?.firstOrNull { (it["id"] as? JsonPrimitive)?.contentOrNull == preview.previewId }
    }

    /** The OpenRouter key and where it came from: [KEY_ENV], else the user's credentials file. */
    val openRouterKey: GuidelineCredentials.Resolved?

    /**
     * Whether [project] was trusted with the stored [key] (`compose-preview guidelines key trust`).
     * Asked only of a key from the credentials file: the server has no terminal to ask in.
     */
    fun trusted(key: GuidelineCredentials.Resolved, project: File): Boolean =
      GuidelineCredentials.isTrusted(File(key.source), project)

    /** The model client for a key; a fake in tests. */
    fun model(key: String): GuidelineModel = OpenRouterClient(key)

    /**
     * The key stored for the OpenAI-compatible endpoint at [baseUrl] (`compose-preview guidelines
     * key set --for`), from the credentials file only: [GuidelineCredentials.MODEL_ENV] is not
     * read, since the caller names the endpoint and an environment key would go wherever it says.
     */
    fun endpointKey(baseUrl: String): GuidelineCredentials.Resolved? =
      GuidelineCredentials.resolve(
        GuidelineCredentials.endpointProvider(baseUrl),
        GuidelineCredentials.MODEL_ENV,
        env = { null },
      ) {
        System.err.println("compose-preview-mcp: $it")
      }

    /** The client for an OpenAI-compatible endpoint; a fake in tests. */
    fun endpointModel(baseUrl: String, key: String?): GuidelineModel =
      OpenAiCompatibleClient(baseUrl, key)

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
          "(a file or URL), `surface` (component | screen | widget) and `profile` (a Remote " +
          "Compose profile such as wear-widgets); both default to what the preview's manifest " +
          "entry says.",
      inputSchema = inputSchema(withCost = false),
    )

  fun checkToolDef(): ToolDef =
    ToolDef(
      name = CHECK_TOOL,
      description =
        "Check @Previews against their catalog's design guidelines with a model on OpenRouter " +
          "(the key comes from $KEY_ENV in the server's environment, else the key stored by " +
          "`compose-preview guidelines key set` for a project trusted with it). Batches the previews, " +
          "sends each render with its accessibility nodes and source, and returns per-preview " +
          "verdicts naming the nodes (and picture regions) they are about, the model that " +
          "answered and the cost. `max_cost` (USD, default $DEFAULT_MAX_COST_USD) caps the run. " +
          "`base_url` asks an OpenAI-compatible endpoint instead (with `model`): a loopback " +
          "server, or one whose key the user stored with `compose-preview guidelines key set " +
          "--for <base_url>`; it reports no cost.",
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
        put(
          "description",
          "A ui-builder.guidelines.json file (by that name) or an http(s) URL; default: the " +
            "module's.",
        )
      }
      putJsonObject("surface") {
        put("type", "string")
        putJsonArray("enum") {
          add(JsonPrimitive(GuidelineSurfaces.COMPONENT))
          add(JsonPrimitive(GuidelineSurfaces.SCREEN))
          add(JsonPrimitive(GuidelineSurfaces.WIDGET))
        }
        put("description", "Default: the preview's own, from its manifest entry.")
      }
      putJsonObject("profile") {
        put("type", "string")
        put(
          "description",
          "The Remote Compose profile the previews target (wear-widgets, launcher-widgets-v7, …); " +
            "default: the preview's own, from its manifest entry.",
        )
      }
      if (withCost) {
        putJsonObject("max_cost") {
          put("type", "number")
          put("description", "Most USD this call may spend.")
        }
        putJsonObject("model") {
          put("type", "string")
          put(
            "description",
            "OpenRouter model id; default ${OpenRouterClient.DEFAULT_MODEL}. With `base_url`, " +
              "the endpoint's own name for a model that reads pictures (required).",
          )
        }
        putJsonObject("base_url") {
          put("type", "string")
          put(
            "description",
            "An OpenAI-compatible endpoint, before /chat/completions " +
              "(http://localhost:11434/v1); default: OpenRouter.",
          )
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
    prepared.notes.forEach { content += ContentBlock.Text("Note: $it") }
    return CallToolResult(content = content)
  }

  fun check(args: JsonObject, host: Host): CallToolResult {
    val baseUrl = (args["base_url"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
    if (baseUrl != null) return checkEndpoint(args, host, baseUrl)
    val resolvedKey =
      host.openRouterKey?.takeIf { it.key.isNotBlank() }
        ?: return error(
          "$CHECK_TOOL: no OpenRouter key. Store one with `compose-preview guidelines key set`, " +
            "or set $KEY_ENV in the environment the MCP server runs in (create a key at " +
            "openrouter.ai, Settings, then Keys), or use $PROMPT_TOOL and judge the prompt with " +
            "your own model."
        )
    val prepared =
      prepare(CHECK_TOOL, args, host) { previews ->
          if (resolvedKey.stored) requireTrusted(previews, resolvedKey, host, KEY_ENV)
        }
        .getOrElse {
          return error(it.message!!)
        }
    val maxCost = (args["max_cost"] as? JsonPrimitive)?.doubleOrNull ?: DEFAULT_MAX_COST_USD
    val model =
      (args["model"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
        ?: OpenRouterClient.DEFAULT_MODEL
    return run(host.model(resolvedKey.key), model, maxCost, judge = null, prepared)
  }

  /**
   * [check] on the OpenAI-compatible endpoint at [baseUrl]. Only a key stored for that origin is
   * sent, and only for a trusted project; without one, only a loopback server is asked, so a call
   * cannot send previews and source to a host the user never set up.
   */
  private fun checkEndpoint(args: JsonObject, host: Host, baseUrl: String): CallToolResult {
    val provider = runCatching {
      GuidelineCredentials.endpointProvider(baseUrl)
    }
      .getOrElse {
        return error("$CHECK_TOOL: `base_url` $baseUrl is not an http(s) URL")
      }
    val model =
      (args["model"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
        ?: return error(
          "$CHECK_TOOL: `base_url` needs `model`, the endpoint's own name for a model that reads " +
            "pictures"
        )
    val resolvedKey = host.endpointKey(baseUrl)?.takeIf { it.key.isNotBlank() }
    if (resolvedKey == null && !OpenAiCompatibleClient.isLoopback(baseUrl)) {
      return error(
        "$CHECK_TOOL: no key stored for $baseUrl. Ask the user to run `compose-preview " +
          "guidelines key set --for $baseUrl` in a terminal; only a loopback server is asked " +
          "without one."
      )
    }
    val prepared =
      prepare(CHECK_TOOL, args, host) { previews ->
          resolvedKey?.let { requireTrusted(previews, it, host, envVar = null) }
        }
        .getOrElse {
          return error(it.message!!)
        }
    // The endpoint reports no cost, so max_cost cannot stop the run; the batch is the caller's.
    return run(
      host.endpointModel(baseUrl, resolvedKey?.key),
      model,
      maxCost = DEFAULT_MAX_COST_USD,
      judge = "openai:" + provider.removePrefix("endpoint:"),
      prepared,
    )
  }

  private fun run(
    client: GuidelineModel,
    model: String,
    maxCost: Double,
    judge: String?,
    prepared: Prepared,
  ): CallToolResult {
    val run =
      GuidelineEngine(
          client,
          options =
            GuidelineRunOptions(
                model = model,
                budget = GuidelineBudget(),
                maxCostUsd = maxCost,
                rulesSource = prepared.rulesSource,
                ranBy = "mcp",
              )
              .withJudge(judge),
        )
        .run(prepared.guidelines, prepared.subjects)
    val structured = buildJsonObject {
      put("catalog", prepared.guidelines.catalog)
      put("rulesVersion", prepared.guidelines.version)
      put("costUsd", run.costUsd)
      put("requests", run.requests)
      putJsonArray("problems") { run.problems.forEach { add(JsonPrimitive(it)) } }
      putJsonArray("notes") { prepared.notes.forEach { add(JsonPrimitive(it)) } }
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
        run.problems.joinToString("") { "\n! $it" } +
        prepared.notes.joinToString("") { "\nNote: $it" }
    return CallToolResult(
      content = listOf(ContentBlock.Text(summary), ContentBlock.Text(structured.toString())),
      structuredContent = structured,
    )
  }

  private class Prepared(
    val guidelines: CatalogGuidelinesV1,
    val subjects: List<PreviewSubject>,
    val rulesSource: String,
    /** What the prepared evidence lacks, said to the caller (e.g. a preview with no nodes). */
    val notes: List<String> = emptyList(),
  )

  /**
   * Refuses a stored key for a project not trusted with it, before anything is rendered. The user
   * trusts it in a terminal: a tool call, which the model driving the client makes, cannot.
   */
  private fun requireTrusted(
    previews: List<Resolved>,
    key: GuidelineCredentials.Resolved,
    host: Host,
    /** The variable that would supply this key instead, when the server reads one. */
    envVar: String?,
  ) {
    val untrusted =
      previews.map { it.projectRoot }.distinct().filter { it == null || !host.trusted(key, it) }
    require(untrusted.isEmpty()) {
      val roots = untrusted.filterNotNull().map { it.absoluteFile.canonicalPath }
      "$CHECK_TOOL: the key stored in ${key.source} is not trusted for " +
        (if (roots.isEmpty()) "a preview whose project is unknown" else roots.joinToString()) +
        ". Ask the user to run " +
        roots
          .joinToString(" and ") { "`compose-preview guidelines key trust $it`" }
          .ifEmpty { "`compose-preview guidelines key trust <project>`" } +
        " in a terminal" +
        (envVar?.let { ", or set $it for the server" } ?: "") +
        ", or use $PROMPT_TOOL."
    }
  }

  private fun prepare(
    tool: String,
    args: JsonObject,
    host: Host,
    beforeRender: (List<Resolved>) -> Unit = {},
  ): Result<Prepared> = runCatching {
    val refs =
      (args["previews"] as? JsonArray)
        ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        ?.filter { it.isNotBlank() }
        .orEmpty()
    require(refs.isNotEmpty()) { "$tool: `previews` must name at least one preview" }
    val resolved = refs.map { ref ->
      host.resolve(ref) ?: throw IllegalArgumentException("$tool: no preview matches `$ref`")
    }
    beforeRender(resolved)
    val location =
      (args["guidelines"] as? JsonPrimitive)
        ?.contentOrNull
        ?.takeIf { it.isNotBlank() }
        ?.also { requireGuidelinesLocation(tool, it) }
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
          "$tool: ${loaded.problem?.let(::redactedProblem) ?: "no guidelines at $location"}"
        )
    val surfaceArg = (args["surface"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
    val profileArg = (args["profile"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
    val notes = mutableListOf<String>()
    val subjects = resolved.map { preview ->
      val png = host.render(preview)
      val hierarchy = host.a11yHierarchy(preview)
      if (hierarchy.payload == null) {
        notes +=
          "${preview.previewId}: no accessibility nodes (" +
            (hierarchy.problem ?: "the hierarchy was empty") +
            "), so its findings cannot name nodes"
      }
      // The preview's own surface and profile (a Wear widget is `widget` / `wear-widgets`), unless
      // the caller names them; a preview no manifest lists is a component, as before.
      val kind = host.manifestEntry(preview)?.let(GuidelineSurfaces::of)
      PreviewSubject(
        previewId = preview.previewId,
        label = preview.label,
        surface = surfaceArg ?: kind?.surface ?: GuidelineSurfaces.COMPONENT,
        profile = profileArg ?: kind?.profile,
        renderHash = sha256(png),
        pictures = listOf(SubjectPicture(GuidelinePictureV1.KIND_DEVICE, png, 0, 0)),
        nodes = hierarchy.payload?.let(::nodesOf).orEmpty(),
        source = host.source(preview),
      )
    }
    Prepared(guidelines, subjects, location, notes)
  }

  /**
   * A caller-named `guidelines` location: an `http(s)` URL, or a local file named
   * [CatalogGuidelinesV1.FILE_NAME] — by the path given and by the path it resolves to, so a
   * symlink of that name pointing elsewhere is refused too. The tool is reachable by whatever model
   * drives the MCP client, prompt injection included, and an arbitrary path (`.env`, a shell
   * profile) is not something it has any business reading.
   */
  internal fun requireGuidelinesLocation(tool: String, location: String) {
    if (location.startsWith("http://") || location.startsWith("https://")) return
    val file = File(location)
    val resolvedName = runCatching { file.canonicalFile.name }.getOrNull()
    require(file.name == CatalogGuidelinesV1.FILE_NAME && resolvedName == file.name) {
      "$tool: `guidelines` must be an http(s) URL or a file named " +
        "${CatalogGuidelinesV1.FILE_NAME}"
    }
  }

  /**
   * [CatalogGuidelinesLoader.Loaded.problem] fit to hand back to the caller. A decoding failure's
   * message carries the input it failed on (kotlinx's `JSON input: …`, the whole text when it is
   * short), so it is reduced to where the input went wrong; anything else keeps its first line.
   */
  internal fun redactedProblem(problem: String): String {
    val readable = "not a readable ${CatalogGuidelinesV1.FILE_NAME}"
    if (problem.startsWith(readable)) {
      val offset = DECODE_OFFSET.find(problem)?.groupValues?.get(1)
      return readable + (offset?.let { " (invalid at offset $it)" } ?: "")
    }
    return problem.lineSequence().first().take(MAX_PROBLEM_CHARS)
  }

  private val DECODE_OFFSET = Regex("at offset ([0-9]+)")
  private const val MAX_PROBLEM_CHARS = 200

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
