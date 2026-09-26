package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.agentgrants.AgentGrantScope
import ee.schimke.composeai.data.overrides.PreviewOverrideType
import java.io.File
import java.io.InputStream
import java.io.PrintStream
import java.time.Duration
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * `compose-preview-server a2ui render` — an A2UI document on disk to a PNG, through a running
 * server's catalog renderer.
 *
 * The A2UI catalog publishes a preview that renders `previewOverrideString("document", …)`: an A2UI
 * document (JSON Lines of v0.9 messages, a JSON array, or a `{"components":[…]}` shorthand). This
 * command is the non-browser half of `/{system}/a2ui`: it finds that preview by the knob it
 * declares (or takes `--preview`), calls the catalog MCP `render_preview` with the document as
 * `knob.document` and `observe=png`, and writes the decoded PNG.
 *
 * A client like [DesignCommand], and built from the same parts: the same `--server` /
 * `$COMPOSE_PREVIEW_TOKEN` resolution, the same `POST <server>/mcp` transport, and the same
 * authorise-and-retry when a server has no grant for this caller. Parsing is a pure function of
 * argv and the environment; [A2uiCommandRunner] is the half that talks.
 */
internal object A2uiCommand {

  const val NAME: String = "a2ui"
  const val RENDER: String = "render"

  /** Every verb, in the order [usage] lists them. */
  val VERBS: List<String> = listOf(RENDER)

  /** The catalog `preview.coo.ee` publishes the A2UI renderer under. */
  const val DEFAULT_CATALOG: String = "a2ui-catalog"

  private const val DEFAULT_TIMEOUT_SECONDS = 120L

  data class Options(
    val verb: String,
    val server: String,
    val catalog: String,
    /** Null means "the preview in [catalog] declaring a string `document` knob". */
    val previewId: String?,
    /** A path, or [DesignCommand.STDOUT] (`-`) for stdin. */
    val document: String,
    /** A path, or [DesignCommand.STDOUT] for stdout. */
    val out: String,
    val authorize: Boolean,
    val timeoutSeconds: Long,
  )

  sealed interface Parsed {
    data class Run(val options: Options) : Parsed

    data object Help : Parsed

    data class Invalid(val message: String) : Parsed
  }

  fun parse(args: List<String>, env: (String) -> String? = System::getenv): Parsed {
    if (args.isEmpty()) return Parsed.Help
    val verb = args.first()
    if (verb == "help" || verb == "--help" || verb == "-h") return Parsed.Help
    if (verb !in VERBS) {
      return Parsed.Invalid("a2ui: unknown verb '$verb'. Verbs: ${VERBS.joinToString(", ")}.")
    }
    val rest = args.drop(1)
    if (rest.any { it == "--help" || it == "-h" }) return Parsed.Help

    var server: String? = null
    var catalog: String? = null
    var preview: String? = null
    var document: String? = null
    var out: String? = null
    var authorize = true
    var timeout = DEFAULT_TIMEOUT_SECONDS

    var index = 0
    while (index < rest.size) {
      val argument = rest[index]
      fun value(): String? =
        rest.getOrNull(index + 1)?.takeUnless { it.startsWith("-") && it != DesignCommand.STDOUT }
      when (argument) {
        "--server" -> server = value() ?: return missingValue(argument)
        "--catalog" -> catalog = value() ?: return missingValue(argument)
        "--preview" -> preview = value() ?: return missingValue(argument)
        "--document" -> document = value() ?: return missingValue(argument)
        "--out",
        "-o" -> out = value() ?: return missingValue(argument)
        "--timeout" -> {
          val raw = value() ?: return missingValue(argument)
          timeout =
            raw.toLongOrNull()?.takeIf { it > 0 }
              ?: return Parsed.Invalid(
                "a2ui: --timeout must be a positive number of seconds, not '$raw'"
              )
        }
        "--no-authorize" -> {
          authorize = false
          index++
          continue
        }
        "--token" ->
          return Parsed.Invalid(
            "a2ui: there is no --token flag on purpose — a credential on a command line lands in " +
              "a shell history and a CI log. Set \$${DesignCommand.TOKEN_ENV}, or let this " +
              "command ask a human for a grant."
          )
        else ->
          return Parsed.Invalid(
            if (argument.startsWith("-")) "a2ui: unknown option '$argument'"
            else "a2ui $verb: unexpected argument '$argument'"
          )
      }
      // Every flag that reaches here took a value.
      index += 2
    }

    val source = document ?: return Parsed.Invalid("a2ui $verb: --document <file|-> is required")
    if (catalog != null && catalog.isBlank()) return Parsed.Invalid("a2ui: --catalog is blank")
    if (preview != null && preview.isBlank()) return Parsed.Invalid("a2ui: --preview is blank")
    return Parsed.Run(
      Options(
        verb = verb,
        server =
          server
            ?: env(DesignCommand.SERVER_ENV)?.takeIf { it.isNotBlank() }
            ?: DesignCommand.defaultServer(),
        catalog = catalog ?: DEFAULT_CATALOG,
        previewId = preview,
        document = source,
        out = out ?: defaultOut(source),
        authorize = authorize,
        timeoutSeconds = timeout,
      )
    )
  }

  /**
   * `doc.jsonl` renders to `doc.png` beside it; a document from stdin has no name, so `a2ui.png`.
   */
  fun defaultOut(document: String): String =
    if (document == DesignCommand.STDOUT) "$NAME.png"
    else File(document).let { File(it.parentFile, it.nameWithoutExtension + ".png").path }

  fun usage(): String =
    """
    compose-preview-server a2ui render --document <file|-> [options]

    Render an A2UI document (JSON Lines of v0.9 messages, a JSON array of them, or a
    {"components":[...]} shorthand) with a running server's A2UI catalog, and write the PNG.

    Options:
      --document <file|->       The document to render; `-` reads stdin. Required.
      --out, -o <path>          Where the PNG goes; `-` is stdout. Defaults to the document's name
                                with .png (a2ui.png for stdin).
      --server <url>            The server to ask (default ${DesignCommand.defaultServer()}, or ${'$'}${DesignCommand.SERVER_ENV}).
      --catalog <id>            The catalog to render with (default $DEFAULT_CATALOG).
      --preview <id>            The preview to render. Omitted, the catalog's preview that declares
                                a string `document` knob is used.
      --no-authorize            Fail on a missing or expired grant instead of asking a human for
                                one. For CI, where nobody is there to approve.
      --timeout <seconds>       Give up on one call after this long (default $DEFAULT_TIMEOUT_SECONDS).

    Rendering an edited document is a live render: it needs a `live` grant. Credentials come from
    ${'$'}${DesignCommand.TOKEN_ENV}, never from a flag; with none, this command asks the server for one
    and prints a link and a code for a human to approve, unless --no-authorize.

    Example:
      a2ui render --server https://preview.coo.ee --document card.jsonl -o card.png
    """
      .trimIndent()

  private fun missingValue(flag: String): Parsed = Parsed.Invalid("a2ui: $flag needs a value")
}

/** One catalog MCP `tools/call`, answered with the raw reply body. [DesignHttpTransport] is one. */
internal fun interface CatalogMcpTransport {
  fun callRaw(tool: String, arguments: JsonObject): String
}

/**
 * Resolves the preview, renders the document, writes the PNG. Returns an exit code; throws
 * [DesignAuthorizationRequired] for a credential problem so [A2uiCommandEntry] can retry.
 */
internal class A2uiCommandRunner(
  private val options: A2uiCommand.Options,
  private val transport: CatalogMcpTransport,
  private val readDocument: (String) -> String,
  private val emit: (String) -> Unit,
  private val write: (String, ByteArray) -> Unit,
) {

  fun run(): Int {
    val document = readDocument(options.document)
    if (document.isBlank()) throw DesignCommandFailure("a2ui: the document is empty")
    val previewId = options.previewId ?: documentPreview()
    val reply =
      catalogResult(
        "render_preview",
        transport.callRaw(
          "render_preview",
          buildJsonObject {
            put("catalog", options.catalog)
            put("previewId", previewId)
            put("observe", "png")
            put(
              "overrides",
              buildJsonObject { put(ServeOverrides.KNOB_PREFIX + DOCUMENT_KNOB, document) },
            )
          },
        ),
      )
    val content = reply["content"] as? JsonArray ?: JsonArray(emptyList())
    // The provenance block: a baked answer ignored the document, and writing it would hand back the
    // catalog's default render under the caller's filename.
    content
      .mapNotNull { (it as? JsonObject)?.text() }
      .mapNotNull { runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull() }
      .firstOrNull { "overridesApplied" in it }
      ?.let { provenance ->
        if (provenance["overridesApplied"]?.jsonPrimitive?.booleanOrNull == false) {
          throw DesignCommandFailure(
            "a2ui: ${options.catalog} answered with its published render, not this document — " +
              (provenance["overridesIgnoredReason"]?.jsonPrimitive?.contentOrNull
                ?: "the catalog has no live renderer")
          )
        }
      }
    val data =
      content
        .mapNotNull { it as? JsonObject }
        .firstOrNull { it["type"]?.jsonPrimitive?.contentOrNull == "image" }
        ?.get("data")
        ?.jsonPrimitive
        ?.contentOrNull ?: throw DesignCommandFailure("a2ui: render_preview answered with no image")
    val png = runCatching {
      Base64.getDecoder().decode(data)
    }
      .getOrElse { throw DesignCommandFailure("a2ui: the image is not base64 — ${it.message}") }
    write(options.out, png)
    if (options.out != DesignCommand.STDOUT) {
      emit("a2ui: ${options.catalog}/$previewId -> ${options.out} (${png.size} bytes)")
    }
    return DesignCommandRunner.EXIT_OK
  }

  /** The catalog's preview declaring a string `document` knob, via `list_previews`. */
  private fun documentPreview(): String {
    val listing =
      catalogResult(
        "list_previews",
        transport.callRaw("list_previews", buildJsonObject { put("catalog", options.catalog) }),
      )
    val text =
      (listing["content"] as? JsonArray)?.firstNotNullOfOrNull { (it as? JsonObject)?.text() }
        ?: throw DesignCommandFailure("a2ui: list_previews answered with no listing")
    val previews = runCatching {
      Json.parseToJsonElement(text)
        .jsonObject["catalogs"]!!
        .let { it as JsonArray }
        .flatMap { (it.jsonObject["previews"] as? JsonArray).orEmpty() }
    }
      .getOrElse { throw DesignCommandFailure("a2ui: list_previews is not a listing — $text") }
    return previews
      .mapNotNull { it as? JsonObject }
      .firstOrNull { preview ->
        (preview["knobs"] as? JsonArray).orEmpty().any { knob ->
          val obj = knob as? JsonObject
          obj?.get("key")?.jsonPrimitive?.contentOrNull == DOCUMENT_KNOB &&
            obj["type"]?.jsonPrimitive?.contentOrNull.equals(PreviewOverrideType.STRING, true)
        }
      }
      ?.get("id")
      ?.jsonPrimitive
      ?.contentOrNull
      ?: throw DesignCommandFailure(
        "a2ui: ${options.catalog} declares no preview with a string `$DOCUMENT_KNOB` knob. " +
          "Name one with --preview, or check --catalog."
      )
  }

  private fun JsonObject.text(): String? = takeIf {
    it["type"]?.jsonPrimitive?.contentOrNull == "text"
  }
    ?.get("text")
    ?.jsonPrimitive
    ?.contentOrNull

  companion object {
    const val DOCUMENT_KNOB: String = ServeWeb.A2UI_DOCUMENT_KNOB

    /**
     * A catalog tool reply → its `CallToolResult`, refusing the two error shapes: JSON-RPC's own
     * `error`, and a tool error (`isError`), whose text is the diagnosis. A missing grant arrives
     * as either, and is raised as [DesignAuthorizationRequired] so the entry point can ask for one.
     */
    fun catalogResult(tool: String, raw: String): JsonObject {
      val payload =
        if (raw.startsWith("event:") || raw.startsWith("data:")) {
          raw.lineSequence().firstOrNull { it.startsWith("data:") }?.removePrefix("data:")?.trim()
            ?: throw DesignCommandFailure("$tool: an SSE reply with no data frame")
        } else raw
      val message = runCatching {
        Json.parseToJsonElement(payload).jsonObject
      }
        .getOrElse { throw DesignCommandFailure("$tool: the reply is not JSON") }
      message["error"]?.let { error ->
        val text =
          (error as? JsonObject)?.get("message")?.jsonPrimitive?.contentOrNull ?: error.toString()
        if (text.isAuthorizationRefusal()) throw DesignAuthorizationRequired(null, "$tool: $text")
        throw DesignCommandFailure("$tool: $text")
      }
      val result =
        message["result"]?.jsonObject
          ?: throw DesignCommandFailure("$tool: the reply carries neither a result nor an error")
      if (result["isError"]?.jsonPrimitive?.booleanOrNull == true) {
        val text =
          (result["content"] as? JsonArray)
            ?.mapNotNull { (it as? JsonObject)?.get("text")?.jsonPrimitive?.contentOrNull }
            ?.joinToString("\n")
            .orEmpty()
        if (text.isAuthorizationRefusal()) throw DesignAuthorizationRequired(null, "$tool: $text")
        throw DesignCommandFailure("$tool: $text")
      }
      return result
    }

    private fun String.isAuthorizationRefusal(): Boolean =
      contains("grant scope is required") ||
        contains("authorization_required") ||
        contains("unauthorized", ignoreCase = true)
  }
}

/** `a2ui`, wired to a real process: argv in, a PNG and an exit code out. */
internal object A2uiCommandEntry {

  private const val APPROVAL_DEADLINE_SECONDS = 600L

  fun run(
    args: List<String>,
    env: (String) -> String? = System::getenv,
    stdin: InputStream = System.`in`,
    out: PrintStream = System.out,
    err: PrintStream = System.err,
  ): Int {
    val options =
      when (val parsed = A2uiCommand.parse(args, env)) {
        is A2uiCommand.Parsed.Help -> {
          out.println(A2uiCommand.usage())
          return DesignCommandRunner.EXIT_OK
        }
        is A2uiCommand.Parsed.Invalid -> {
          err.println(parsed.message)
          err.println("Try `compose-preview-server a2ui help`.")
          return DesignCommandRunner.EXIT_USAGE
        }
        is A2uiCommand.Parsed.Run -> parsed.options
      }
    var token = DesignCommand.token(env)
    val http =
      DesignHttpTransport(
        server = options.server,
        token = { token },
        timeout = Duration.ofSeconds(options.timeoutSeconds),
      )
    // Read once: a retry after authorising must not find stdin already drained.
    val document by lazy {
      if (options.document == DesignCommand.STDOUT) stdin.readBytes().decodeToString()
      else
        File(options.document).takeIf { it.isFile }?.readText()
          ?: throw DesignCommandFailure("a2ui: --document ${options.document} is not a file")
    }
    val runner =
      A2uiCommandRunner(
        options = options,
        transport = http::callRaw,
        readDocument = { document },
        emit = err::println,
        write = { destination, bytes ->
          if (destination == DesignCommand.STDOUT) {
            out.write(bytes)
            out.flush()
          } else {
            File(destination).also { it.absoluteFile.parentFile?.mkdirs() }.writeBytes(bytes)
          }
        },
      )
    return try {
      try {
        runner.run()
      } catch (refused: DesignAuthorizationRequired) {
        if (!options.authorize) {
          err.println(refused.message)
          err.println(
            "a2ui: --no-authorize, so no grant was requested. Set " +
              "\$${DesignCommand.TOKEN_ENV} to a token with the live scope."
          )
          return DesignCommandRunner.EXIT_NO_PERMISSION
        }
        err.println(
          if (token == null) "a2ui: no credential, and rendering a document needs a live grant."
          else "a2ui: this server does not honour that token for a live render."
        )
        token =
          DesignAuthorizer(
              server = options.server,
              timeout = Duration.ofSeconds(ServeAgentGrants.MAX_POLL_WAIT_SECONDS + 15),
              log = err::println,
            )
            .authorize(
              scope = AgentGrantScope.LIVE.wire,
              capabilities = emptyList(),
              label = "compose-preview-server a2ui ${options.verb}",
              deadlineSeconds = APPROVAL_DEADLINE_SECONDS,
            )
        err.println("a2ui: the grant is held for this run only, and is not printed.")
        runner.run()
      }
    } catch (refused: DesignAuthorizationRequired) {
      err.println(refused.message)
      err.println("a2ui: the new grant still cannot render; it needs the live scope.")
      DesignCommandRunner.EXIT_NO_PERMISSION
    } catch (failure: DesignCommandFailure) {
      err.println(failure.message ?: "a2ui: failed")
      DesignCommandRunner.EXIT_FAILURE
    }
  }
}
