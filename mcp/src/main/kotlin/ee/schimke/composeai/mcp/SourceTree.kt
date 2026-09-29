package ee.schimke.composeai.mcp

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Tells a render whether any source in a Gradle build changed since the last look, so the server
 * can recompile before rendering when nobody called `notify_file_changed`. Host edit tools (Claude
 * Code's Edit, an IDE's save) never notify this server, so freshness cannot depend on that call.
 *
 * It covers what a preview's classes are built from: `*.kt` / `*.java` and resources (`res/`,
 * `composeResources/`) under every module's `src/`, apart from test source sets. It skips hidden
 * directories, which is also what keeps a Claude Code worktree under `.claude/worktrees/` out of
 * the build that contains it, and `build/` outputs.
 *
 * The first [refresh] walks the tree once and caches every directory's and file's stamp. Later
 * refreshes only `stat`: a directory is re-listed only when its own mtime moved (a file was added,
 * removed or renamed into it, as atomic saves do), and a file counts as changed when its mtime or
 * size moved. An unchanged build therefore costs one stat per cached entry and no directory walk,
 * and [budgetMs] caps even that: a refresh that runs out of time stops, reports `complete = false`,
 * and the next one resumes where it stopped.
 *
 * Files newer than every compiled class output in the build count as changed on the first walk: an
 * edit made before the server first looked would otherwise become the baseline and render stale
 * (the first-render-after-edit case the per-file probe missed).
 */
class SourceTree(val root: File, private val budgetMs: Long = DEFAULT_BUDGET_MS) {

  data class Refresh(
    /** Files that changed since the previous refresh, in the order they were seen. */
    val changed: List<File>,
    /** Entries stat-ed. */
    val statted: Int,
    /** Directories listed: every one on the first walk, only moved ones after. */
    val listed: Int,
    val ms: Long,
    /** False when [budgetMs] ran out before every entry was checked. */
    val complete: Boolean,
    /** True for the first walk, which builds the cache. */
    val initial: Boolean,
  )

  private data class Stamp(val modifiedMs: Long, val size: Long)

  private val dirs = LinkedHashMap<File, Long>()
  private val files = LinkedHashMap<File, Stamp>()
  private var initialized = false
  private var fileCursor = 0

  /** Bumped on every refresh that saw a change; callers compare it with what they compiled. */
  @Volatile
  var generation: Long = 0
    private set

  /** Which [generation] each changed file was last seen changing in. */
  private val changedAt = LinkedHashMap<File, Long>()

  @Synchronized
  fun refresh(): Refresh {
    val startedAt = System.nanoTime()
    if (!initialized) return initialWalk(startedAt)
    val deadline = startedAt + TimeUnit.MILLISECONDS.toNanos(budgetMs)
    val changed = LinkedHashSet<File>()
    var statted = 0
    var listed = 0
    var complete = true
    // Directories first: a moved directory mtime is how additions, deletions and atomic renames
    // show up, and a re-list is the only walk an unchanged tree never does.
    for ((dir, known) in dirs.entries.toList()) {
      statted++
      val now = dir.lastModified()
      if (now == known) continue
      if (now == 0L) {
        dirs.remove(dir)
        files.keys
          .filter { it.parentFile == dir }
          .forEach {
            files.remove(it)
            changed += it
          }
        continue
      }
      dirs[dir] = now
      listed++
      val children = dir.listFiles().orEmpty()
      children
        .filter { it.isFile && isSource(it) && it !in files }
        .forEach {
          files[it] = stamp(it)
          changed += it
        }
      children
        .filter { it.isDirectory && watched(dir, it) && it !in dirs }
        .forEach { sub -> listed += register(sub) { changed += it } }
    }
    val entries = files.entries.toList()
    if (entries.isNotEmpty()) {
      if (fileCursor >= entries.size) fileCursor = 0
      var index = fileCursor
      var checked = 0
      while (checked < entries.size) {
        if (System.nanoTime() > deadline) {
          complete = false
          break
        }
        val (file, known) = entries[index]
        statted++
        val now = stamp(file)
        if (now.modifiedMs == 0L) {
          files.remove(file)
          changed += file
        } else if (now != known) {
          files[file] = now
          changed += file
        }
        checked++
        index = (index + 1) % entries.size
      }
      fileCursor = index
    }
    if (changed.isNotEmpty()) {
      generation++
      changed.forEach { changedAt[it] = generation }
    }
    return Refresh(
      changed = changed.toList(),
      statted = statted,
      listed = listed,
      ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt),
      complete = complete,
      initial = false,
    )
  }

  /** Files that changed after [generation], for the compile's source list. */
  @Synchronized
  fun changedSince(generation: Long): List<File> =
    changedAt.filterValues { it > generation }.keys.toList()

  private fun initialWalk(startedAt: Long): Refresh {
    var listed = 0
    sourceRoots().forEach { src -> listed += register(src) {} }
    initialized = true
    val compiledAt = newestClassOutput()
    val pending =
      if (compiledAt == null) emptyList()
      else files.filter { (_, stamp) -> stamp.modifiedMs > compiledAt }.keys.toList()
    if (pending.isNotEmpty()) {
      generation++
      pending.forEach { changedAt[it] = generation }
    }
    return Refresh(
      changed = pending,
      statted = dirs.size + files.size,
      listed = listed,
      ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt),
      complete = true,
      initial = true,
    )
  }

  /** Adds [dir] and everything below it; returns how many directories it listed. */
  private fun register(dir: File, onFile: (File) -> Unit): Int {
    var listed = 0
    val stack = ArrayDeque(listOf(dir))
    while (stack.isNotEmpty()) {
      val next = stack.removeLast()
      dirs[next] = next.lastModified()
      listed++
      next.listFiles().orEmpty().forEach { child ->
        if (child.isDirectory) {
          if (watched(next, child)) stack.addLast(child)
        } else if (isSource(child)) {
          files[child] = stamp(child)
          onFile(child)
        }
      }
    }
    return listed
  }

  /**
   * Each module's `src/`: a `src` directory beside a Gradle build file, at most a few levels deep.
   */
  private fun sourceRoots(): List<File> {
    val roots = mutableListOf<File>()
    val stack = ArrayDeque(listOf(root to 0))
    while (stack.isNotEmpty()) {
      val (dir, depth) = stack.removeLast()
      val src = File(dir, "src")
      if (src.isDirectory && BUILD_FILES.any { File(dir, it).isFile }) roots += src
      if (depth >= MAX_MODULE_DEPTH) continue
      dir.listFiles().orEmpty().forEach { child ->
        if (child.isDirectory && walkable(child) && child.name != "src") {
          stack.addLast(child to depth + 1)
        }
      }
    }
    return roots
  }

  private fun newestClassOutput(): Long? {
    var newest: Long? = null
    val moduleDirs = sourceRoots().map { it.parentFile }
    moduleDirs.forEach { module ->
      CLASS_OUTPUTS.map { File(module, it) }
        .filter(File::isDirectory)
        .forEach { output ->
          output
            .walkTopDown()
            .filter { it.isFile && it.extension == "class" }
            .forEach { newest = maxOf(newest ?: 0L, it.lastModified()) }
        }
    }
    return newest
  }

  private fun stamp(file: File) = Stamp(file.lastModified(), file.length())

  private fun walkable(dir: File): Boolean = !dir.name.startsWith(".") && dir.name !in SKIPPED_DIRS

  /** Whether [child], a directory in [parent], is watched: walkable and not a test source set. */
  private fun watched(parent: File, child: File): Boolean =
    walkable(child) && !(parent.name == "src" && TEST_SOURCE_SET.containsMatchIn(child.name))

  private fun isSource(file: File): Boolean {
    if (file.extension in SOURCE_EXTENSIONS) return true
    val path = file.invariantSeparatorsPath
    return RESOURCE_SEGMENTS.any { path.contains(it) }
  }

  companion object {
    const val DEFAULT_BUDGET_MS = 200L
    private const val MAX_MODULE_DEPTH = 4
    private val BUILD_FILES = listOf("build.gradle.kts", "build.gradle")
    private val SKIPPED_DIRS = setOf("build", "out", "node_modules", "gradle")
    private val SOURCE_EXTENSIONS = setOf("kt", "java")
    private val RESOURCE_SEGMENTS = listOf("/res/", "/composeResources/")
    private val TEST_SOURCE_SET = Regex("(?i)test")
    private val CLASS_OUTPUTS =
      listOf(
        "build/tmp/kotlin-classes",
        "build/intermediates/built_in_kotlinc",
        "build/intermediates/javac",
        "build/classes",
      )
  }
}
