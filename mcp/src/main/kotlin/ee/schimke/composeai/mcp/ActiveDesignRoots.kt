package ee.schimke.composeai.mcp

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/** Roots of connected MCP sessions, shared with the sidebar's sibling process. No design bytes. */
class ActiveDesignRoots(
  directory: File? = null,
  private val processId: Long = ProcessHandle.current().pid(),
  private val processStart: Long =
    ProcessHandle.current().info().startInstant().map { it.toEpochMilli() }.orElse(0L),
  scope: String? = null,
  private val isAlive: (Long, Long) -> Boolean = { pid, start ->
    ProcessHandle.of(pid)
      .map {
        it.isAlive &&
          it.info().startInstant().map { time -> time.toEpochMilli() == start }.orElse(false)
      }
      .orElse(false)
  },
) : AutoCloseable {
  // Sharing is opt-in. A host must supply the same opaque scope to its chat/sidebar processes;
  // absent that identity, discovery remains local to this process. Ignore legacy unscoped records.
  private val directory =
    scope
      ?.takeIf { it.isNotBlank() }
      ?.let {
        directory?.resolve(
          java.security.MessageDigest.getInstance("SHA-256")
            .digest(it.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
        )
      }
  private val sessions = mutableMapOf<Any, List<File>>()
  private var closed = false
  private val record = this.directory?.let { File(it, "${UUID.randomUUID()}.json") }

  @Synchronized
  fun register(session: Any, roots: List<File>) {
    if (closed) return
    sessions.putIfAbsent(session, emptyList())
    update(session, roots)
  }

  @Synchronized
  fun update(session: Any, roots: List<File>) {
    // A roots/list response arriving after disconnect must not resurrect that session.
    if (closed || session !in sessions) return
    val canonical =
      roots.map { runCatching { it.canonicalFile }.getOrDefault(it.absoluteFile) }.distinct()
    if (sessions[session] == canonical) return
    sessions[session] = canonical
    save()
  }

  @Synchronized
  fun remove(session: Any) {
    if (sessions.remove(session) != null) save()
  }

  @Synchronized
  fun all(): List<File> {
    val roots = sessions.values.flatten().toMutableList()
    directory
      ?.listFiles { file -> file.extension == "json" }
      ?.forEach { file ->
        if (file == record) return@forEach
        val data =
          runCatching { Json.parseToJsonElement(file.readText()) as? JsonObject }.getOrNull()
            ?: return@forEach
        val pid = (data["pid"] as? JsonPrimitive)?.longOrNull ?: return@forEach
        val start = (data["start"] as? JsonPrimitive)?.longOrNull ?: return@forEach
        if (!isAlive(pid, start)) {
          file.delete()
          return@forEach
        }
        (data["roots"] as? JsonArray).orEmpty().forEach { root ->
          (root as? JsonPrimitive)?.contentOrNull?.let { roots += File(it) }
        }
      }
    return roots.distinct()
  }

  private fun save() {
    val target = record ?: return
    if (sessions.isEmpty()) {
      target.delete()
      return
    }
    runCatching {
      target.parentFile.mkdirs()
      val temp = File.createTempFile("roots", ".tmp", target.parentFile)
      temp.writeText(
        buildJsonObject {
          put("pid", processId)
          put("start", processStart)
          putJsonArray("roots") {
            sessions.values.flatten().distinct().forEach {
              add(kotlinx.serialization.json.JsonPrimitive(it.path))
            }
          }
        }
          .toString()
      )
      Files.move(
        temp.toPath(),
        target.toPath(),
        StandardCopyOption.REPLACE_EXISTING,
        StandardCopyOption.ATOMIC_MOVE,
      )
    }
      .onFailure {
        System.err.println("compose-preview-mcp: could not save session roots: ${it.message}")
      }
  }

  @Synchronized
  override fun close() {
    closed = true
    sessions.clear()
    record?.delete()
  }

  companion object {
    const val SCOPE_ENV: String = "COMPOSE_PREVIEW_DESIGN_SESSION_SCOPE"

    fun defaultDirectory(): File =
      File(WorkspaceStore.defaultFile().parentFile, "active-design-roots")
  }
}
