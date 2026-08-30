package ee.schimke.composeai.cli.serve

import java.io.File

/**
 * **What Compose previews are in this checkout?** — answered by *reading* it, never by running it.
 *
 * The onboarding flow ([ServeOnboarding]) can only publish what a repository already delivers on a
 * `design-artifacts/` branch, which is nothing at all for a project that has never heard of this
 * tool — and those are exactly the projects someone pastes a URL for (issue #12). Before anything
 * can be built from source, the box has to be able to say *what there is to build*: which Gradle
 * modules hold `@Preview` composables, and which of those the preview plugin can actually be
 * injected into.
 *
 * ### Why a reader and not a Gradle model query
 *
 * Asking Gradle would be more accurate and is the wrong first step: configuring a build **is**
 * running its build scripts as the server user, the exact thing [GradleRevisionBuilder] warns about
 * at its exec point. A scan has to be safe on a repository nobody has vouched for yet, because its
 * whole purpose is to tell an operator whether vouching for it is worth it. So this reads
 * `settings.gradle[.kts]`, the per-module build files, the version catalog and the Kotlin sources
 * as **text**. Nothing here executes, and nothing it reports is authoritative — it is a
 * well-founded guess, deliberately labelled as one ([ServeSourceModule.buildable] is "worth
 * trying", not "will work").
 *
 * ### What it looks for
 *
 * A module is worth building when it has previews *and* something to hang the plugin off:
 * - **Previews**: `@Preview`-annotated functions under `src/`, which is how both flavours spell it
 *   (`androidx.compose.ui.tooling.preview.Preview`, `org.jetbrains.compose.ui.tooling.preview`).
 * - **A host plugin**: `com.android.application`, `com.android.library`,
 *   `com.android.kotlin.multiplatform.library` or `org.jetbrains.compose` — the ids the auto-inject
 *   init script hooks with `pluginManager.withPlugin(...)`. A module that already applies
 *   `ee.schimke.composeai.preview` itself needs no injection and counts too.
 *
 * Version-catalog aliases are resolved because that is how the projects this was written for spell
 * their plugins: `alias(libs.plugins.androidApplication)` says nothing on its own, and
 * `gradle/libs.versions.toml` is where it says `com.android.application`. Missing that mapping
 * would report "no Compose modules" for a repository full of them — the one failure mode that makes
 * the whole feature look broken.
 */
object ServeSourceScan {

  /** Plugin ids the auto-inject init script can hang the preview plugin off. */
  private val HOST_PLUGIN_IDS =
    listOf(
      "com.android.application",
      "com.android.library",
      "com.android.kotlin.multiplatform.library",
      "org.jetbrains.compose",
    )

  /** The plugin itself: a module applying this one already has previews wired without injection. */
  private const val PREVIEW_PLUGIN_ID = "ee.schimke.composeai.preview"

  /** Directory names never worth walking for sources. */
  private val SKIPPED_DIRS = setOf("build", ".git", ".gradle", ".idea", "node_modules")

  /** Preview functions named per module before the list is truncated (the response stays small). */
  private const val MAX_NAMED_PREVIEWS = 25

  /** Kotlin files read per module. A ceiling, so a monorepo can't turn a scan into a walk of it. */
  private const val MAX_SOURCE_FILES = 4_000

  /** Scan [root] — a checkout of the repository — for modules that hold Compose previews. */
  fun scan(root: File): ServeSourceScanResult {
    val notes = mutableListOf<String>()
    val settings = settingsFile(root)
    if (settings == null) {
      notes += "no settings.gradle[.kts] — treating the checkout root as a single module"
    }
    val aliases = pluginAliases(root)
    val paths = settings?.let { includedProjects(it.readTextOrEmpty()) }.orEmpty()
    // The root project is a module in its own right in a single-module repository, and harmless to
    // scan in a multi-module one: it either holds previews (rare, but real) or reports zero.
    val refs = (listOf("") + paths).distinct()
    val modules =
      refs.mapNotNull { path -> scanModule(root, path, aliases) }.sortedBy { it.gradlePath }
    if (modules.none { it.buildable }) {
      notes +=
        if (modules.none { it.previewCount > 0 }) {
          "no @Preview functions found under any module's src/"
        } else {
          "previews were found, but no module applies an Android or Compose Multiplatform plugin " +
            "the preview plugin can be injected alongside"
        }
    }
    return ServeSourceScanResult(modules = modules, notes = notes)
  }

  /** Read one module directory; null when the directory doesn't exist in this checkout. */
  private fun scanModule(
    root: File,
    gradlePath: String,
    aliases: Map<String, String>,
  ): ServeSourceModule? {
    val relativePath = gradlePath.replace(':', '/')
    val dir = if (relativePath.isEmpty()) root else File(root, relativePath)
    if (!dir.isDirectory) return null
    val buildFile =
      listOf("build.gradle.kts", "build.gradle").map { File(dir, it) }.firstOrNull { it.isFile }
    // A directory with no build file is a container (`:features` holding `:features:home`), not a
    // module — reported as such rather than silently dropped, so the caller can see the shape of
    // the repository it pasted.
    val buildText = buildFile?.readTextOrEmpty().orEmpty()
    val declared = declaredPluginIds(buildText, aliases)
    val hostPlugins = HOST_PLUGIN_IDS.filter { it in declared }
    val preApplied = PREVIEW_PLUGIN_ID in declared
    val previews = findPreviews(dir)
    val buildable = previews.isNotEmpty() && (hostPlugins.isNotEmpty() || preApplied)
    return ServeSourceModule(
      gradlePath = gradlePath,
      relativePath = relativePath,
      previewCount = previews.size,
      previewFunctions = previews.take(MAX_NAMED_PREVIEWS),
      hostPlugins = hostPlugins,
      pluginPreApplied = preApplied,
      buildable = buildable,
      skipReason =
        when {
          buildable -> null
          buildFile == null -> "no build file — not a Gradle module"
          previews.isEmpty() -> "no @Preview functions under src/"
          else -> "no Android or Compose Multiplatform plugin to inject the preview plugin beside"
        },
    )
  }

  /** `settings.gradle.kts`, else `settings.gradle`, else null. */
  private fun settingsFile(root: File): File? =
    listOf("settings.gradle.kts", "settings.gradle")
      .map { File(root, it) }
      .firstOrNull { it.isFile }

  /**
   * Gradle paths (`app`, `features:home` — no leading colon, matching [ServeModuleRef]) named by
   * `include(...)` in a settings file.
   *
   * Both DSLs and both spellings of the argument list are accepted (`include(":a", ":b")`, `include
   * ':a'`, a line-continued list), because a text reader that only understood one of them would
   * report a Groovy project as empty. `includeBuild` is deliberately not matched: a composite
   * build's modules are not paths in this build.
   */
  internal fun includedProjects(text: String): List<String> {
    val result = LinkedHashSet<String>()
    val includes = Regex("""(?m)^\s*include\s*(?:\(|\s)([^\n]*)""")
    val quoted = Regex(""""([^"]+)"|'([^']+)'""")
    for (match in includes.findAll(text)) {
      for (arg in quoted.findAll(match.groupValues[1])) {
        val raw = (arg.groupValues[1].ifEmpty { arg.groupValues[2] }).trim()
        val path = raw.trim(':').trim()
        if (path.isNotEmpty() && !path.contains(' ')) result += path
      }
    }
    return result.toList()
  }

  /**
   * Plugin ids a build file applies, with catalog aliases resolved through [aliases].
   *
   * Covers the three spellings in the wild: `id("x")` / `id 'x'`, `alias(libs.plugins.x)`, and the
   * legacy `apply plugin: 'x'`.
   */
  internal fun declaredPluginIds(buildText: String, aliases: Map<String, String>): Set<String> {
    val ids = LinkedHashSet<String>()
    Regex("""id\s*\(?\s*["']([A-Za-z0-9._-]+)["']""").findAll(buildText).forEach {
      ids += it.groupValues[1]
    }
    Regex("""apply\s+plugin\s*:\s*["']([A-Za-z0-9._-]+)["']""").findAll(buildText).forEach {
      ids += it.groupValues[1]
    }
    Regex("""alias\s*\(\s*libs\.plugins\.([A-Za-z0-9._]+)\s*\)""").findAll(buildText).forEach {
      aliases[normalizeAlias(it.groupValues[1])]?.let { id -> ids += id }
    }
    return ids
  }

  /**
   * `alias key → plugin id` from `gradle/libs.versions.toml`'s `[plugins]` table.
   *
   * Keys are normalized ([normalizeAlias]) because the catalog and the accessor spell the same
   * alias differently: `android-application` in the TOML is `libs.plugins.android.application` in
   * the build file, and `androidApplication` is `libs.plugins.androidApplication`. Comparing the
   * letters-and-digits of each is the only comparison that gets both right.
   */
  internal fun pluginAliases(root: File): Map<String, String> {
    val toml = File(root, "gradle/libs.versions.toml").takeIf { it.isFile } ?: return emptyMap()
    val result = LinkedHashMap<String, String>()
    var inPlugins = false
    val entry = Regex("""^\s*([A-Za-z0-9_.-]+)\s*=\s*(.+)$""")
    val id = Regex("""id\s*=\s*"([^"]+)"""")
    for (line in toml.readTextOrEmpty().lineSequence()) {
      val trimmed = line.trim()
      if (trimmed.startsWith("[")) {
        inPlugins = trimmed.removeSuffix("]").removePrefix("[").trim() == "plugins"
        continue
      }
      if (!inPlugins) continue
      val match = entry.find(trimmed) ?: continue
      val value = match.groupValues[2]
      // Both shapes a plugin entry takes: `x = { id = "…", … }` and the string form `x = "…:…"`.
      val pluginId =
        id.find(value)?.groupValues?.get(1)
          ?: Regex("""^"([^"]+)"""").find(value)?.groupValues?.get(1)?.substringBefore(':')
      if (pluginId != null) result[normalizeAlias(match.groupValues[1])] = pluginId
    }
    return result
  }

  /** An alias reduced to its letters and digits, so every spelling of one compares equal. */
  private fun normalizeAlias(raw: String): String = raw.filter { it.isLetterOrDigit() }.lowercase()

  /**
   * Names of `@Preview`-annotated functions under `<dir>/src`.
   *
   * Matched on the annotation rather than an import so the multiplatform spelling, an aliased
   * import and a `@Preview`-annotated *annotation class* (`@MyDarkPreview`) are all seen. The
   * function name is read from the next `fun` on or after the annotation, so a stack of annotations
   * still resolves to one name; an annotation whose function can't be read is still counted, since
   * the count is what decides whether the module is worth building.
   */
  private fun findPreviews(dir: File): List<String> {
    val srcRoot = File(dir, "src").takeIf { it.isDirectory } ?: return emptyList()
    val names = mutableListOf<String>()
    var files = 0
    val stack = ArrayDeque(listOf(srcRoot))
    val fn = Regex("""\bfun\s+([A-Za-z_][A-Za-z0-9_]*)\s*\(""")
    while (stack.isNotEmpty()) {
      val next = stack.removeFirst()
      val children = next.listFiles() ?: continue
      for (child in children.sortedBy { it.name }) {
        if (child.isDirectory) {
          if (child.name !in SKIPPED_DIRS) stack.addLast(child)
          continue
        }
        if (!child.name.endsWith(".kt")) continue
        if (++files > MAX_SOURCE_FILES) return names
        val text = child.readTextOrEmpty()
        if (!text.contains("@Preview")) continue
        var index = text.indexOf("@Preview")
        while (index >= 0) {
          // A `@PreviewParameter` parameter annotation is not a preview; neither is a longer
          // identifier that merely starts with the word.
          val after = text.getOrNull(index + "@Preview".length)
          if (after == null || !(after.isLetterOrDigit() || after == '_')) {
            names += fn.find(text, index)?.groupValues?.get(1) ?: "(unnamed)"
          }
          index = text.indexOf("@Preview", index + 1)
        }
      }
    }
    return names
  }

  private fun File.readTextOrEmpty(): String = runCatching { readText() }.getOrDefault("")
}

/** One Gradle module a [ServeSourceScan] looked at. */
data class ServeSourceModule(
  /** Gradle path without its leading colon, as [ServeModuleRef] spells it (`app`, `ui:core`). */
  val gradlePath: String,
  /** The module directory relative to the checkout root. */
  val relativePath: String,
  val previewCount: Int,
  /** Up to a bounded number of the preview function names, so the report is readable. */
  val previewFunctions: List<String>,
  /** Plugin ids found that the preview plugin can be injected beside. */
  val hostPlugins: List<String>,
  /** The module applies `ee.schimke.composeai.preview` itself — nothing to inject. */
  val pluginPreApplied: Boolean,
  /**
   * Worth attempting a build for. Not a promise that the build succeeds — see [ServeSourceScan].
   */
  val buildable: Boolean,
  /** Why not, when [buildable] is false. Null when it is true. */
  val skipReason: String?,
)

/** What a scan of one checkout found. */
data class ServeSourceScanResult(
  val modules: List<ServeSourceModule>,
  /** Honest, human-readable remarks — chiefly why an apparently-Compose repo yielded nothing. */
  val notes: List<String> = emptyList(),
) {
  /** The modules a build pass would attempt, in scan order. */
  val buildable: List<ServeSourceModule>
    get() = modules.filter { it.buildable }
}
