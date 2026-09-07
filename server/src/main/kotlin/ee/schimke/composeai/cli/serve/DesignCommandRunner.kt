package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.uibuilder.protocol.DiagnosticSeverityV1
import ee.schimke.composeai.uibuilder.protocol.ExportArtifactV1
import ee.schimke.composeai.uibuilder.protocol.ExportEncodingV1
import ee.schimke.composeai.uibuilder.protocol.ExportFormatV1
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * The design document a server holds, as JSON.
 *
 * Shared by `design get`, which writes it out, and by `--local`, which compiles it here — so the
 * two cannot disagree about which part of an `ui_builder_get_design` reply *is* the design. The
 * reply is a snapshot; the document is what anyone asking for a design means by it, and the catalog
 * beside it is 70 KB of something else.
 */
internal fun DesignMcpTransport.designDocument(designId: String, revision: Long?): JsonObject =
  call(
      ServeUiBuilderMcp.GET_DESIGN,
      buildJsonObject {
        put("designId", designId)
        revision?.let { put("revision", it) }
      },
    )["snapshot"]
    ?.jsonObject
    ?.get("state")
    ?.jsonObject
    ?.get("document")
    ?.jsonObject
    ?: throw DesignCommandFailure("design get: the reply carried no document for $designId")

/**
 * What each `design` verb does with a reply, and what it costs when the reply is a refusal.
 *
 * The whole point of the command is here rather than in the transport: **diagnostics are not
 * swallowed**. `ui_builder_export` answers a design it cannot express with a parseable artifact
 * whose content is the refusal in comments — "`asset/image` has no Remote Compose counterpart",
 * "the image background `bg-art` needs a RemoteImageBitmap" — and those sentences are the most
 * useful thing the call produces. A tool that wrote that file out and exited 0 would put a Kotlin
 * file in a pipeline that compiles to nothing and report success. So an error diagnostic goes to
 * stderr, the destination is left alone, and the exit code is non-zero.
 *
 * [emit] and [write] are parameters so every one of those decisions is observable in a test that
 * opens no socket and touches no disk.
 */
internal class DesignCommandRunner(
  private val options: DesignCommand.Options,
  private val transport: DesignMcpTransport,
  /** Human-facing lines: progress, diagnostics, refusals. Always stderr. */
  private val emit: (String) -> Unit,
  /** The artifact. [DesignCommand.STDOUT] as the destination means stdout. */
  private val write: (destination: String, bytes: ByteArray) -> Unit,
) {

  /** Runs the verb and returns the process exit code. */
  fun run(): Int =
    try {
      when (options.verb) {
        DesignCommand.LIST -> list()
        DesignCommand.GET -> get()
        DesignCommand.RENDER,
        DesignCommand.EXPORT -> export()
        else -> {
          emit("design: unknown verb '${options.verb}'")
          EXIT_USAGE
        }
      }
    } catch (e: DesignCommandFailure) {
      emit(e.message ?: "design: failed")
      EXIT_FAILURE
    }

  private fun list(): Int {
    val response =
      transport.call(
        ServeUiBuilderMcp.LIST_DESIGNS,
        buildJsonObject { put("limit", options.limit) },
      )
    val designs = response["designs"] as? JsonArray ?: JsonArray(emptyList())
    if (designs.isEmpty()) {
      emit("design list: no designs are visible to this credential.")
      return EXIT_OK
    }
    // Tab-separated on purpose: `design list | cut -f1` is the natural next thing to type, and
    // aligned columns would make that a column-width problem.
    val rows =
      designs.joinToString("\n") { entry ->
        val design = entry.jsonObject
        val id = design.text("designId").orEmpty()
        val revision = design["revision"]?.jsonPrimitive?.longOrNull ?: 0L
        "$id\t$revision\t${design.text("title").orEmpty()}"
      }
    write(options.destination, (rows + "\n").toByteArray())
    if (response.text("nextCursor") != null) {
      emit("design list: more designs follow; ask again with a higher --limit.")
    }
    return EXIT_OK
  }

  private fun get(): Int {
    val document = transport.designDocument(options.designId, options.revision)
    val revision = document["revision"]?.jsonPrimitive?.longOrNull
    write(
      options.destination,
      (PRETTY.encodeToString(JsonObject.serializer(), document) + "\n").toByteArray(),
    )
    note("design get: ${options.designId} at revision ${revision ?: "?"}")
    return EXIT_OK
  }

  private fun export(): Int {
    val format = options.format ?: ExportFormatV1.PNG
    val response =
      transport.call(
        ServeUiBuilderMcp.EXPORT,
        buildJsonObject {
          put("designId", options.designId)
          put("format", format.wire())
          options.revision?.let { put("revision", it) }
        },
      )
    val artifact = runCatching {
      LENIENT.decodeFromJsonElement(
        ExportArtifactV1.serializer(),
        response["artifact"]
          ?: throw DesignCommandFailure("design ${options.verb}: the reply carried no artifact"),
      )
    }
      .getOrElse {
        throw DesignCommandFailure(
          "design ${options.verb}: the reply's artifact is not one this build understands — ${it.message}"
        )
      }
    if (artifact.format != format) {
      throw DesignCommandFailure(
        "design ${options.verb}: asked for ${format.wire()} and got ${artifact.format.wire()}"
      )
    }

    val refused = artifact.diagnostics.filter { it.severity == DiagnosticSeverityV1.ERROR }
    artifact.diagnostics.forEach {
      emit("  ${it.severity.name.lowercase()}: ${it.code}: ${it.message}")
    }
    if (refused.isNotEmpty()) {
      // Nothing is written. The refusal artifact is real Kotlin and reads well in a terminal, but
      // writing it to --out would leave a file behind that looks like an export and is not one.
      emit(
        "design ${options.verb}: ${options.designId} cannot be exported as ${format.wire()} " +
          "(${refused.size} error${if (refused.size == 1) "" else "s"} above). Nothing was written."
      )
      return EXIT_FAILURE
    }

    val bytes =
      when (artifact.encoding) {
        ExportEncodingV1.BASE64 ->
          runCatching { Base64.getDecoder().decode(artifact.content) }
            .getOrElse {
              throw DesignCommandFailure("design ${options.verb}: the artifact is not valid base64")
            }
        ExportEncodingV1.UTF8 -> artifact.content.toByteArray()
      }
    write(options.destination, bytes)
    note(
      "design ${options.verb}: ${bytes.size} bytes of ${artifact.mediaType} (${artifact.contentDigest.take(12)})"
    )
    return EXIT_OK
  }

  /** A one-line summary, but only when it is not competing with the artifact for the terminal. */
  private fun note(line: String) {
    if (options.destination != DesignCommand.STDOUT) emit("$line -> ${options.destination}")
  }

  private fun JsonObject.text(name: String): String? =
    this[name]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }

  internal companion object {
    const val EXIT_OK: Int = 0
    const val EXIT_FAILURE: Int = 1

    /** 64 = EX_USAGE, the code the rest of this binary already answers bad argv with. */
    const val EXIT_USAGE: Int = 64

    /** 77 = EX_NOPERM: a credential is missing and none could be obtained. */
    const val EXIT_NO_PERMISSION: Int = 77

    private val PRETTY = Json { prettyPrint = true }
    private val LENIENT = Json { ignoreUnknownKeys = true }
  }
}
