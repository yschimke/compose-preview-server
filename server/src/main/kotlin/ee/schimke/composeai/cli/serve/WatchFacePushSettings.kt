package ee.schimke.composeai.cli.serve

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * `watchFacePushBuilds` (`SERVE_WATCH_FACE_PUSH_BUILDS`): whether public watch-face designs are
 * built for watches on their own ([WatchFacePushAutomatic]). Off by default: building runs the
 * packager, so an operator turns it on deliberately. With it off the automatic listing never
 * starts, and an owner's explicit publish still works, through the one packager queue.
 */
internal object WatchFacePushBuildsSetting {
  const val ENV = "SERVE_WATCH_FACE_PUSH_BUILDS"

  fun enabled(env: Map<String, String> = System.getenv()): Boolean =
    env["SERVE_WATCH_FACE_PUSH_BUILDS"]?.trim()?.lowercase() in setOf("1", "true")
}

/**
 * The designs in the UI builder's per-design store, read from each design's header on disk
 * (`designs/<dir>/design.json`) rather than through the service, so the startup sweep of the
 * automatic listing never takes the service's lock to find them. A header that does not parse is
 * skipped; the service is the authority on every design the sweep then looks at.
 */
internal object WatchFacePushStoredDesigns {
  data class Head(val designId: String, val catalogSystemId: String?)

  private val json = Json { ignoreUnknownKeys = true }

  fun read(stateDirectory: Path): List<Head> {
    val designs = stateDirectory.resolve("designs")
    if (!Files.isDirectory(designs)) return emptyList()
    return Files.list(designs).use { dirs ->
      dirs
        .filter { Files.isDirectory(it) && !it.fileName.toString().startsWith(".") }
        .toList()
        .mapNotNull { dir -> head(dir.resolve("design.json")) }
        .sortedBy { it.designId }
    }
  }

  private fun head(file: Path): Head? = runCatching {
    val payload = json.parseToJsonElement(Files.readString(file)) as JsonObject
    val design = payload["payload"] as? JsonObject ?: return null
    val id = design["designId"]?.jsonPrimitive?.contentOrNull ?: return null
    val catalog =
      (design["catalogPin"] as? JsonObject)?.get("systemId")?.jsonPrimitive?.contentOrNull
    Head(id, catalog)
  }
    .getOrNull()
}
