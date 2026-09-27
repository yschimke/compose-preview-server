package ee.schimke.composeai.mcp

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Every workspace this server (or an earlier run of it) registered, as `{id, path, lastUsed}`.
 *
 * It is the source of truth for "which path does this workspace id name": [DaemonSupervisor] only
 * caches the live projects, and when it is asked for an id it does not hold (the server restarted,
 * or a second server process for the same host took the call) it registers the id again from here
 * instead of failing with "workspace not registered".
 *
 * With a [file] (production: `~/.cache/composeai/mcp/workspaces.json`, see [defaultFile]) the
 * entries survive restarts and are shared between server processes; a lookup that misses re-reads
 * the file. Without one (tests) the store is in memory only.
 */
class WorkspaceStore(private val file: File?) {

  data class Entry(val id: String, val path: String, val name: String?, val lastUsed: Long)

  private val entries = ConcurrentHashMap<String, Entry>()

  /** Ids this process unregistered, so merging the file back in does not resurrect them. */
  private val forgotten = ConcurrentHashMap.newKeySet<String>()

  init {
    load()
  }

  /** The entry for [id], re-reading the file once when this process has not seen it. */
  fun get(id: String): Entry? =
    entries[id]
      ?: run {
        load()
        entries[id]
      }

  /** Every entry, most recently used first. */
  fun all(): List<Entry> {
    load()
    return entries.values.sortedByDescending { it.lastUsed }
  }

  /** Records (or refreshes) [id] at [path]. */
  fun remember(id: String, path: File, name: String?) {
    val now = System.currentTimeMillis()
    val entry = Entry(id, path.absolutePath, name, now)
    forgotten.remove(id)
    val previous = entries.put(id, entry)
    if (
      previous == null ||
        previous.path != entry.path ||
        previous.name != name ||
        now - previous.lastUsed >= TOUCH_INTERVAL_MS
    ) {
      save()
    }
  }

  /** Marks [id] as used; written at most once per [TOUCH_INTERVAL_MS] per id. */
  fun touch(id: String) {
    val entry = entries[id] ?: return
    val now = System.currentTimeMillis()
    if (now - entry.lastUsed < TOUCH_INTERVAL_MS) return
    entries[id] = entry.copy(lastUsed = now)
    save()
  }

  /** Forgets [id]; only an explicit `unregister_project` does this. */
  fun forget(id: String) {
    forgotten.add(id)
    if (entries.remove(id) != null) save()
  }

  private fun load() {
    val source = file?.takeIf(File::isFile) ?: return
    val root =
      runCatching { Json.parseToJsonElement(source.readText()) as? JsonObject }.getOrNull()
        ?: return
    (root["workspaces"] as? JsonArray).orEmpty().forEach { element ->
      val obj = element as? JsonObject ?: return@forEach
      val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: return@forEach
      if (id in forgotten) return@forEach
      val path = obj["path"]?.jsonPrimitive?.contentOrNull ?: return@forEach
      val lastUsed = obj["lastUsed"]?.jsonPrimitive?.longOrNull ?: 0L
      val name = obj["name"]?.jsonPrimitive?.contentOrNull
      entries.merge(id, Entry(id, path, name, lastUsed)) { mine, theirs ->
        if (theirs.lastUsed > mine.lastUsed) theirs else mine
      }
    }
  }

  @Synchronized
  private fun save() {
    val target = file ?: return
    runCatching {
      // Merge what other server processes wrote since this one last read the file.
      load()
      val kept = entries.values.sortedByDescending { it.lastUsed }.take(MAX_ENTRIES)
      val text = buildJsonObject {
        putJsonArray("workspaces") {
          kept.forEach { entry ->
            add(
              buildJsonObject {
                put("id", entry.id)
                put("path", entry.path)
                entry.name?.let { put("name", it) }
                put("lastUsed", entry.lastUsed)
              }
            )
          }
        }
      }
        .toString()
      target.parentFile?.mkdirs()
      val temp = File.createTempFile("workspaces", ".json.tmp", target.parentFile)
      temp.writeText(text)
      Files.move(
        temp.toPath(),
        target.toPath(),
        StandardCopyOption.REPLACE_EXISTING,
        StandardCopyOption.ATOMIC_MOVE,
      )
    }
      .onFailure {
        System.err.println("compose-preview-mcp: could not save $target: ${it.message}")
      }
  }

  companion object {
    private const val TOUCH_INTERVAL_MS = 60_000L
    private const val MAX_ENTRIES = 200

    /** `$XDG_CACHE_HOME/composeai/mcp/workspaces.json`, else `~/.cache/composeai/mcp/…`. */
    fun defaultFile(
      environment: Map<String, String> = System.getenv(),
      userHome: File = File(System.getProperty("user.home") ?: "."),
    ): File {
      val cache =
        environment["XDG_CACHE_HOME"]?.takeIf { it.isNotBlank() }?.let(::File)
          ?: File(userHome, ".cache")
      return File(cache, "composeai/mcp/workspaces.json")
    }
  }
}
