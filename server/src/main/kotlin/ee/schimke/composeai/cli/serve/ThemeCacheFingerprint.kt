package ee.schimke.composeai.cli.serve

import java.io.File
import java.security.MessageDigest

/**
 * Content identity of one *renderable catalog generation*: everything that decides a theme render's
 * pixels, reduced to a string. [ServeOverrides.cacheKey] says what was asked for; this says what
 * produced it, which a persisted entry needs.
 *
 * Keyed on:
 * - **the classpath's bytes**, not paths (each load stages into a fresh directory), covering code,
 *   themes, resources and dependencies in one move;
 * - **the daemon variant** — Desktop and Robolectric disagree pixel-for-pixel;
 * - **the render environment** ([rendererIdentity]): JVM, rasteriser libraries, system fonts;
 * - **the render config** — server-side defaults absent from the cache key.
 *
 * Deliberately **not** the tool version: releases shipped faster than the cache could fill, and the
 * version only stood proxy for the image, which [rendererIdentity] now keys on directly. It is
 * still recorded in the manifest ([GenerationInputs.toolVersion]). Unenumerated inputs are caught
 * by [CatalogThemeCache.verifySample]; `--theme-cache-evict` discards the store outright.
 */
object ThemeCacheFingerprint {

  /** Past this many classpath entries [of] declines (returns null) rather than guess. */
  const val MAX_CLASSPATH_ENTRIES: Int = 8192

  /** Read size for hashing a classpath entry. */
  private const val BUFFER_BYTES = 1 shl 16

  /**
   * Fingerprint the generation a daemon launched with [classpath] and [variant] will render, or
   * null when it cannot be established.
   *
   * Null means "do not persist": an unknown identity must never be invented, since a wrong pixel
   * starts with two generations agreeing on a name.
   */
  fun of(
    classpath: List<File>,
    variant: String,
    renderConfig: String,
    /**
     * Digest of the catalog-id to daemon-preview alias map. It comes from the manifest, not the
     * bundle, so it can repoint an id while the classpath stays byte-identical.
     */
    routing: String = "",
    /** Digest of the render environment (JVM, freetype, fonts); see [rendererIdentity]. */
    renderer: String = currentRendererIdentity,
  ): String? {
    if (classpath.isEmpty() || classpath.size > MAX_CLASSPATH_ENTRIES) return null
    val digest = MessageDigest.getInstance("SHA-256")
    digest.line("schema", SCHEMA)
    digest.line("variant", variant)
    // Not the tool version; see the class doc.
    digest.line("renderer", renderer)
    digest.line("renderConfig", renderConfig)
    digest.line("routing", routing)
    // In descriptor order, NOT sorted: the JVM resolves duplicate classes/resources from the
    // earlier entry, so a reorder with identical bytes can change the pixels.
    for (entry in classpath) {
      val hash = hashFile(entry) ?: return null
      digest.line("entry", "${entry.name}:$hash")
    }
    return digest.digest().hex()
  }

  /**
   * Everything a daemon launched with this descriptor will actually load, in load order — the
   * parent [classpath] **and** the user classpath carried in [systemProperties].
   *
   * The user half is the catalog itself (`ServeBundleDaemon.splitBundleRuntime` moves the bundle's
   * classes and jars there), so omitting it would give two catalog revisions the same name.
   */
  fun renderedClasspath(
    classpath: List<String>,
    systemProperties: Map<String, String>,
    extraPayloads: List<String> = emptyList(),
  ): List<File> =
    classpath.map(::File) +
      (systemProperties[USER_CLASS_DIRS_PROPERTY]
        ?.split(File.pathSeparator)
        ?.filter { it.isNotBlank() }
        ?.map(::File)
        .orEmpty()) +
      // Payloads rendered FROM rather than executed (IR/Remote Compose captures, the manifest):
      // a regenerated capture changes pixels without changing a class.
      (PAYLOAD_PROPERTIES.mapNotNull { key -> systemProperties[key]?.takeIf { it.isNotBlank() } } +
          CONTENT_PATH_PROPERTIES.mapNotNull { key ->
            systemProperties[key]?.takeIf { it.isNotBlank() }
          } +
          extraPayloads.filter { it.isNotBlank() })
        .distinct()
        .map(::File)

  /**
   * The render-affecting launch settings that are *values* rather than file contents, as a stable
   * string for [of]'s `renderConfig`.
   *
   * Settings such as `composeai.fonts.offline` and `robolectric.*` change pixels with an identical
   * classpath, so everything is kept except values that name a path (staging churn; contents that
   * matter are hashed by [renderedClasspath]). Erring this way costs a re-warm, never a wrong
   * pixel.
   */
  fun renderConfig(systemProperties: Map<String, String>, jvmArgs: List<String>): String {
    val covered = PAYLOAD_PROPERTIES.toSet() + CONTENT_PATH_PROPERTIES + USER_CLASS_DIRS_PROPERTY
    val settings =
      systemProperties
        .filterKeys { it !in covered }
        .filterValues { !looksLikePath(it) }
        .entries
        .sortedBy { it.key }
        .joinToString(" ") { (key, value) -> "$key=$value" }
    val args = jvmArgs.filterNot(::looksLikePath).sorted().joinToString(" ")
    return listOf(settings, args).filter { it.isNotBlank() }.joinToString(" ")
  }

  /** Deliberately crude: an absolute path, or a JVM argument carrying one. */
  private fun looksLikePath(value: String): Boolean =
    value.startsWith(File.separator) ||
      value.contains("=${File.separator}") ||
      Regex("^[A-Za-z]:[\\\\/]").containsMatchIn(value)

  /**
   * Digest of the render environment: the container image's half of what produces a pixel, which
   * moves on a base-image bump but not on a release.
   *
   * Reads the JVM identity and architecture ([RENDERER_PROPERTIES]), and the system fonts and
   * rasteriser libraries (freetype, fontconfig, harfbuzz) by relative path and size — not content
   * (too large for the load path) and not mtime (restamped by identical rebuilds). An unreadable or
   * missing root is recorded as absent: that is most developer machines.
   */
  fun rendererIdentity(
    systemProperties: Map<String, String> = jvmProperties(),
    fontRoots: List<File> = DEFAULT_FONT_ROOTS,
    libraryRoots: List<File> = DEFAULT_LIBRARY_ROOTS,
  ): String {
    val digest = MessageDigest.getInstance("SHA-256")
    digest.line("schema", SCHEMA)
    for (key in RENDERER_PROPERTIES) digest.line(key, systemProperties[key].orEmpty())
    for (root in fontRoots) {
      digest.line("fonts", inventory(root, FONT_ROOT_DEPTH) { true })
    }
    for (root in libraryRoots) {
      digest.line("libs", inventory(root, LIBRARY_ROOT_DEPTH) { it in RASTERISER_LIBRARIES })
    }
    return digest.digest().hex()
  }

  /**
   * `<relative path>:<size>` for every accepted file under [root], sorted, or `""` when [root] is
   * not there.
   *
   * Bounded by [maxDepth] and by [MAX_CLASSPATH_ENTRIES] files *visited* (not kept), since
   * `walkTopDown` follows directory symlinks; overflow answers the stable `<root>:overflow`.
   */
  private fun inventory(root: File, maxDepth: Int, accept: (String) -> Boolean): String =
    runCatching {
      if (!root.isDirectory) return@runCatching ""
      val entries = mutableListOf<String>()
      var visited = 0
      for (file in root.walkTopDown().maxDepth(maxDepth)) {
        if (file.isDirectory) continue
        if (++visited > MAX_CLASSPATH_ENTRIES) return@runCatching "${root.name}:overflow"
        if (!accept(file.name)) continue
        entries += "${file.relativeTo(root).invariantPath()}:${file.length()}"
      }
      entries.sorted().joinToString("\n")
    }
    .getOrDefault("")

  /** Deep enough for `/usr/share/fonts/truetype/<family>/<face>.ttf` and its usual variations. */
  private const val FONT_ROOT_DEPTH = 6

  /** A rasteriser shared object sits directly in its library directory; below that is not ours. */
  private const val LIBRARY_ROOT_DEPTH = 1

  /** [rendererIdentity] for this process, computed once: it walks the font roots. */
  val currentRendererIdentity: String by lazy { rendererIdentity() }

  /** The JVM's own description of itself, for [rendererIdentity]. */
  private fun jvmProperties(): Map<String, String> = RENDERER_PROPERTIES.associateWith {
    System.getProperty(it).orEmpty()
  }

  /** The JVM's identity and architecture; deliberately no path such as `java.home`. */
  val RENDERER_PROPERTIES: List<String> =
    listOf("java.vm.vendor", "java.vm.version", "java.runtime.version", "os.arch")

  /** Where a Linux/macOS/Windows image keeps the fonts fontconfig will hand the rasteriser. */
  val DEFAULT_FONT_ROOTS: List<File> =
    listOf(
        "/usr/share/fonts",
        "/usr/local/share/fonts",
        "/System/Library/Fonts",
        "/Library/Fonts",
        System.getProperty("user.home")?.let { "$it/.fonts" },
        System.getProperty("user.home")?.let { "$it/.local/share/fonts" },
        System.getenv("WINDIR")?.let { "$it\\Fonts" },
      )
      .filterNotNull()
      .map(::File)

  /** Where the rasteriser shared objects named in [RASTERISER_LIBRARIES] live. */
  val DEFAULT_LIBRARY_ROOTS: List<File> =
    listOf("/usr/lib/${System.getProperty("os.arch") ?: ""}-linux-gnu", "/usr/lib", "/usr/lib64")
      .map(::File)

  /**
   * Shared objects whose version decides how a glyph is rasterised, matched on the exact soname so
   * the fully versioned file beside it does not count twice.
   */
  val RASTERISER_LIBRARIES: Set<String> =
    setOf(
      "libfreetype.so",
      "libfreetype.so.6",
      "libfontconfig.so",
      "libfontconfig.so.1",
      "libharfbuzz.so",
      "libharfbuzz.so.0",
    )

  /** Stable digest of a catalog-id to daemon-id map, for [of]'s `routing`. */
  fun routingDigest(alias: Map<String, String>): String {
    if (alias.isEmpty()) return ""
    val digest = MessageDigest.getInstance("SHA-256")
    digest.line("schema", SCHEMA)
    // Sorted by catalog id: the map's iteration order is not part of what it means.
    alias.entries.sortedBy { it.key }.forEach { (id, daemonId) -> digest.line(id, daemonId) }
    return digest.digest().hex()
  }

  /** Where the daemon launch carries the catalog's own classes — see [renderedClasspath]. */
  const val USER_CLASS_DIRS_PROPERTY: String = "composeai.daemon.userClassDirs"

  /** Launch properties naming rendered-from content: paths a classpath-only reader would miss. */
  val PAYLOAD_PROPERTIES: List<String> =
    listOf(
      "composeai.daemon.irDir",
      "composeai.daemon.bundleManifestPath",
      // Carries each preview's render spec (size, density, device, themes), which a repack can
      // change with an identical classpath.
      "composeai.daemon.previewsJsonPath",
    )

  /** Path-valued launch settings whose directory contents directly affect rendered pixels. */
  val CONTENT_PATH_PROPERTIES: Set<String> = setOf("composeai.fonts.cacheDir")

  /** Fold a multi-module catalog's fingerprints into one, independent of module order. */
  fun combine(parts: List<String>): String? {
    if (parts.isEmpty() || parts.any { it.isBlank() }) return null
    if (parts.size == 1) return parts.single()
    val digest = MessageDigest.getInstance("SHA-256")
    digest.line("schema", SCHEMA)
    parts.sorted().forEach { digest.line("part", it) }
    return digest.digest().hex()
  }

  /** Content hash of one classpath entry, or null when it is missing or unreadable. */
  private fun hashFile(file: File): String? {
    if (!file.isFile) {
      // Exploded class directories are what a from-source catalog puts here; walk them.
      if (file.isDirectory) return hashDirectory(file)
      return null
    }
    return runCatching {
      val digest = MessageDigest.getInstance("SHA-256")
      file.inputStream().use { stream ->
        val buffer = ByteArray(BUFFER_BYTES)
        while (true) {
          val read = stream.read(buffer)
          if (read <= 0) break
          digest.update(buffer, 0, read)
        }
      }
      digest.digest().hex()
    }
      .getOrNull()
  }

  private fun hashDirectory(dir: File): String? = runCatching {
    val digest = MessageDigest.getInstance("SHA-256")
    val files =
      dir.walkTopDown().filter { it.isFile }.sortedBy { it.relativeTo(dir).invariantPath() }
    var count = 0
    for (file in files) {
      if (++count > MAX_CLASSPATH_ENTRIES) return null
      val hash = hashFile(file) ?: return null
      digest.line("file", "${file.relativeTo(dir).invariantPath()}:$hash")
    }
    // An empty directory is legitimate but carries no identity of its own; folding the count in
    // keeps two differently-empty classpaths from colliding.
    digest.line("files", count.toString())
    digest.digest().hex()
  }
    .getOrNull()

  /** Feed one labelled field, length-prefixed so no separator can make two inputs collide. */
  private fun MessageDigest.line(label: String, value: String) {
    val bytes = value.toByteArray()
    update(label.toByteArray())
    update(bytes.size.toString().toByteArray())
    update(0)
    update(bytes)
    update(0)
  }

  private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }

  private fun File.invariantPath(): String = path.replace(File.separatorChar, '/')

  /** Bumped only when the fingerprint's own composition changes; `/2` added [rendererIdentity]. */
  private const val SCHEMA = "theme-cache/2"
}
