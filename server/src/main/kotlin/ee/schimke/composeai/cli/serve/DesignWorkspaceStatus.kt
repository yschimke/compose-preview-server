package ee.schimke.composeai.cli.serve

import java.io.File
import java.net.URI
import java.nio.file.Files
import java.nio.file.LinkOption
import java.time.Duration
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * A bounded, read-only inventory of the UI-builder designs published by one checkout.
 *
 * A tracked design document is untrusted input. In particular its `home.url` must never decide
 * where an ambient credential is sent. A remote read therefore always uses the independently
 * selected `--server`, and only after the document's home has been proved to name that same origin.
 */
internal class DesignWorkspaceStatus(
  private val options: DesignCommand.Options,
  private val clockNanos: () -> Long = System::nanoTime,
  private val remote: (designId: String, timeout: Duration) -> JsonObject,
) {
  data class Result(
    val workspaceDesigns: Int,
    val serverLinked: Int,
    val unsavedTemporaryCopies: Int,
    val unacknowledgedComments: Int,
    val unavailable: Int,
    val designs: List<Design>,
  ) {
    val verdict: String
      get() =
        when {
          unavailable > 0 -> "unavailable"
          unsavedTemporaryCopies > 0 || unacknowledgedComments > 0 -> "attention"
          else -> "ok"
        }

    fun summary(): String = buildList {
      if (unacknowledgedComments > 0) {
        add(
          "$unacknowledgedComments unacknowledged design ${plural(unacknowledgedComments, "comment")}"
        )
      }
      if (unsavedTemporaryCopies > 0) {
        add(
          "$unsavedTemporaryCopies unsaved temporary ${plural(unsavedTemporaryCopies, "copy", "copies")}"
        )
      }
      if (unavailable > 0) {
        add(
          "design inventory unavailable for $unavailable workspace-linked ${plural(unavailable, "design")}"
        )
      }
    }
      .joinToString("; ")
      .let { if (it.isBlank()) "" else "$it." }

    fun json(): JsonObject = buildJsonObject {
      put("schema", SCHEMA)
      put("verdict", verdict)
      put(
        "totals",
        buildJsonObject {
          put("workspaceDesigns", workspaceDesigns)
          put("serverLinked", serverLinked)
          put("unsavedTemporaryCopies", unsavedTemporaryCopies)
          put("unacknowledgedComments", unacknowledgedComments)
          put("unavailable", unavailable)
        },
      )
      put(
        "designs",
        buildJsonArray {
          designs.forEach { design ->
            add(
              buildJsonObject {
                put("designId", design.designId)
                put("file", design.file)
                put("state", design.state)
                design.unacknowledgedComments?.let { put("unacknowledgedComments", it) }
                design.code?.let { put("code", it) }
              }
            )
          }
        },
      )
    }

    private companion object {
      fun plural(count: Int, singular: String, plural: String = "${singular}s"): String =
        if (count == 1) singular else plural
    }
  }

  data class Design(
    val designId: String,
    val file: String,
    val state: String,
    val unacknowledgedComments: Int? = null,
    val code: String? = null,
    val serverLinked: Boolean = false,
  )

  fun inspect(): Result {
    val deadline = clockNanos() + Duration.ofSeconds(options.timeoutSeconds).toNanos()
    val root = workspaceRoot()
    val indexed = readIndex(root)
    if (indexed.failure != null) {
      return Result(
        0,
        0,
        0,
        0,
        1,
        listOf(
          Design("index", "ui-builder/designs/index.json", "unavailable", code = indexed.failure)
        ),
      )
    }
    val designs = indexed.entries.map { entry -> inspectDesign(root, entry, deadline) }
    return Result(
      workspaceDesigns = designs.size,
      serverLinked = designs.count { it.serverLinked },
      unsavedTemporaryCopies = designs.count { it.state == "unsaved" },
      unacknowledgedComments = designs.sumOf { it.unacknowledgedComments ?: 0 },
      unavailable = designs.count { it.state == "unavailable" },
      designs = designs,
    )
  }

  private fun inspectDesign(root: File, entry: Entry, deadline: Long): Design {
    val relative = "${ServeUiBuilderDesignLibrary.DESIGNS_DIR}/${entry.file}"
    val document =
      readDocument(root, entry.file)
        ?: return Design(entry.id, relative, "unavailable", code = MALFORMED_DOCUMENT)
    val home =
      document["home"] as? JsonObject
        ?: return Design(entry.id, relative, "unavailable", code = MISSING_HOME)
    return when (home.text("kind")) {
      "repo" -> Design(entry.id, relative, "clean")
      "server" -> inspectServerDesign(entry, relative, document, home, deadline)
      else -> Design(entry.id, relative, "unavailable", code = MISSING_HOME)
    }
  }

  private fun inspectServerDesign(
    entry: Entry,
    relative: String,
    local: JsonObject,
    home: JsonObject,
    deadline: Long,
  ): Design {
    val homeUrl = home.text("url")
    val remoteId = home.text("designId")
    if (homeUrl == null || remoteId == null || !DESIGN_ID.matches(remoteId)) {
      return Design(entry.id, relative, "unavailable", code = INVALID_HOME, serverLinked = true)
    }
    if (!sameTrustedOrigin(homeUrl, options.server)) {
      return Design(entry.id, relative, "unavailable", code = UNTRUSTED_HOME, serverLinked = true)
    }
    val remaining = deadline - clockNanos()
    if (remaining <= 0) {
      return Design(entry.id, relative, "unavailable", code = TIMED_OUT, serverLinked = true)
    }
    val response =
      try {
        remote(remoteId, Duration.ofNanos(remaining))
      } catch (_: DesignAuthorizationRequired) {
        return Design(
          entry.id,
          relative,
          "unavailable",
          code = AUTHORIZATION_REQUIRED,
          serverLinked = true,
        )
      } catch (_: DesignCommandFailure) {
        return Design(
          entry.id,
          relative,
          "unavailable",
          code = if (clockNanos() >= deadline) TIMED_OUT else REMOTE_UNAVAILABLE,
          serverLinked = true,
        )
      } catch (_: Exception) {
        return Design(
          entry.id,
          relative,
          "unavailable",
          code = REMOTE_UNAVAILABLE,
          serverLinked = true,
        )
      }
    val remoteDocument =
      response["snapshot"]
        ?.let { it as? JsonObject }
        ?.get("state")
        ?.let { it as? JsonObject }
        ?.get("document") as? JsonObject
        ?: return Design(
          entry.id,
          relative,
          "unavailable",
          code = REMOTE_MALFORMED,
          serverLinked = true,
        )
    val comments = (response["unacknowledgedComments"] as? JsonPrimitive)?.intOrNull
    if (comments == null || comments < 0) {
      return Design(
        entry.id,
        relative,
        "unavailable",
        code = COMMENT_COUNT_UNAVAILABLE,
        serverLinked = true,
      )
    }
    return Design(
      designId = entry.id,
      file = relative,
      state = if (canonical(local) == canonical(remoteDocument)) "clean" else "unsaved",
      unacknowledgedComments = comments,
      serverLinked = true,
    )
  }

  private data class Entry(val id: String, val file: String)

  private data class Index(val entries: List<Entry> = emptyList(), val failure: String? = null)

  private fun readIndex(root: File): Index {
    val file = root.resolve(ServeUiBuilderDesignLibrary.INDEX_PATH)
    if (!file.exists()) return Index()
    val objectValue = readObject(file, MAX_INDEX_BYTES) ?: return Index(failure = MALFORMED_INDEX)
    if (objectValue.text("schema") != ServeUiBuilderDesignLibrary.INDEX_SCHEMA) {
      return Index(failure = MALFORMED_INDEX)
    }
    val elements = objectValue["designs"] as? JsonArray ?: return Index(failure = MALFORMED_INDEX)
    if (elements.size > MAX_DESIGNS) return Index(failure = TOO_MANY_DESIGNS)
    val seen = mutableSetOf<String>()
    val entries = mutableListOf<Entry>()
    for (element in elements) {
      val value = element as? JsonObject ?: return Index(failure = MALFORMED_INDEX)
      val id = value.text("id") ?: return Index(failure = MALFORMED_INDEX)
      val name = value.text("file") ?: "$id.json"
      if (!DESIGN_ID.matches(id) || !DESIGN_FILE.matches(name) || !seen.add(id)) {
        return Index(failure = MALFORMED_INDEX)
      }
      entries += Entry(id, name)
    }
    return Index(entries)
  }

  private fun readDocument(root: File, name: String): JsonObject? =
    readObject(
      root.resolve(ServeUiBuilderDesignLibrary.DESIGNS_DIR).resolve(name),
      MAX_DOCUMENT_BYTES,
    )

  private fun readObject(file: File, maxBytes: Long): JsonObject? {
    val path = file.toPath()
    if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) return null
    val size = runCatching { Files.size(path) }.getOrNull() ?: return null
    if (size > maxBytes) return null
    return runCatching { JSON.parseToJsonElement(Files.readString(path)).jsonObject }.getOrNull()
  }

  private fun workspaceRoot(): File = runCatching {
    File(options.workspace).canonicalFile
  }
    .getOrElse { throw DesignCommandFailure("design status: workspace is not readable") }
    .also {
      if (!it.isDirectory) throw DesignCommandFailure("design status: workspace is not a directory")
    }

  companion object {
    const val SCHEMA = "compose-preview-design-status/v1"
    const val MALFORMED_INDEX = "MALFORMED_INDEX"
    const val TOO_MANY_DESIGNS = "TOO_MANY_DESIGNS"
    const val MALFORMED_DOCUMENT = "MALFORMED_DOCUMENT"
    const val MISSING_HOME = "MISSING_HOME"
    const val INVALID_HOME = "INVALID_HOME"
    const val UNTRUSTED_HOME = "UNTRUSTED_HOME"
    const val AUTHORIZATION_REQUIRED = "AUTHORIZATION_REQUIRED"
    const val TIMED_OUT = "TIMED_OUT"
    const val REMOTE_UNAVAILABLE = "REMOTE_UNAVAILABLE"
    const val REMOTE_MALFORMED = "REMOTE_MALFORMED"
    const val COMMENT_COUNT_UNAVAILABLE = "COMMENT_COUNT_UNAVAILABLE"
    private const val MAX_DESIGNS = 100
    private const val MAX_INDEX_BYTES = 256L * 1024
    private const val MAX_DOCUMENT_BYTES = 8L * 1024 * 1024
    private val DESIGN_ID = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")
    private val DESIGN_FILE = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}\\.json")
    private val JSON = Json { isLenient = false }

    private fun JsonObject.text(key: String): String? =
      (this[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

    private fun canonical(element: JsonElement): String =
      when (element) {
        is JsonObject ->
          element.entries
            .sortedBy { it.key }
            .joinToString(prefix = "{", postfix = "}") { (key, value) ->
              "${JsonPrimitive(key)}:${canonical(value)}"
            }
        is JsonArray -> element.joinToString(prefix = "[", postfix = "]") { canonical(it) }
        is JsonNull -> "null"
        else -> element.toString()
      }

    private fun sameTrustedOrigin(home: String, selected: String): Boolean {
      val homeUri = safeUri(home) ?: return false
      // `--server localhost:8080` is an existing supported spelling. Normalize the independently
      // selected server exactly as the transport does, while still requiring the tracked home to
      // be an absolute URL rather than letting repository data opt into shorthand semantics.
      val selectedSpelling = selected.trim().let { if ("://" in it) it else "http://$it" }
      val selectedUri = safeUri(selectedSpelling) ?: return false
      return homeUri.scheme.equals(selectedUri.scheme, ignoreCase = true) &&
        homeUri.host.equals(selectedUri.host, ignoreCase = true) &&
        effectivePort(homeUri) == effectivePort(selectedUri)
    }

    private fun safeUri(raw: String): URI? = runCatching {
      URI(raw.trim().trimEnd('/'))
    }
      .getOrNull()
      ?.takeIf {
        it.scheme in setOf("http", "https") &&
          !it.host.isNullOrBlank() &&
          it.userInfo == null &&
          it.query == null &&
          it.fragment == null
      }

    private fun effectivePort(uri: URI): Int =
      if (uri.port >= 0) uri.port else if (uri.scheme == "https") 443 else 80
  }
}
