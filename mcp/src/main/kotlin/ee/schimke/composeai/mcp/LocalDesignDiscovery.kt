package ee.schimke.composeai.mcp

import java.io.File
import java.nio.file.Files
import java.util.ArrayDeque

/**
 * Bounded, metadata-only discovery of .uid files in registered projects and active session roots.
 */
object LocalDesignDiscovery {
  private const val MAX_VISITED = 10_000
  private const val MAX_FILES = 200
  private const val MAX_DEPTH = 12
  private val skipped = setOf("build", "node_modules", "vendor", "out", "dist")

  fun discover(roots: List<File>): List<PreviewLibrary.Design> {
    val queue = ArrayDeque<Pair<File, Int>>()
    roots
      .distinct()
      .filter { it.isDirectory && it.parentFile != null }
      .forEach { queue.add(it to 0) }
    val seen = mutableSetOf<String>()
    val found = mutableListOf<PreviewLibrary.Design>()
    var visited = 0
    while (queue.isNotEmpty() && visited < MAX_VISITED && found.size < MAX_FILES) {
      val (dir, depth) = queue.removeFirst()
      if (Files.isSymbolicLink(dir.toPath())) continue
      val canonical = runCatching { dir.canonicalPath }.getOrNull() ?: continue
      if (!seen.add(canonical)) continue
      for (child in dir.listFiles().orEmpty().sortedBy { it.name }) {
        if (++visited > MAX_VISITED || found.size >= MAX_FILES) break
        if (Files.isSymbolicLink(child.toPath())) continue
        if (child.isDirectory) {
          if (depth < MAX_DEPTH && !child.name.startsWith('.') && child.name !in skipped)
            queue.add(child to depth + 1)
        } else if (child.isFile && child.extension.equals("uid", ignoreCase = true)) {
          val path = child.canonicalPath
          found += PreviewLibrary.Design(path, child.nameWithoutExtension, path)
        }
      }
    }
    return found.distinctBy { it.path }.sortedBy { it.path }
  }
}
