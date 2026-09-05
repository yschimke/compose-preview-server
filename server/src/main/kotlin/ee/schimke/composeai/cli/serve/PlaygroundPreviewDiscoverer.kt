package ee.schimke.composeai.cli.serve

import io.github.classgraph.ClassGraph
import okio.Path

/**
 * Finds the `@Preview` id(s) in a just-compiled playground snippet — the production backing for
 * [PlaygroundCompileService.PreviewDiscoverer].
 *
 * A scoped ClassGraph scan of **only** the snippet's `classesDir`: since that directory holds just
 * the snippet's compiled output, every `@Preview`-annotated method in it is the snippet's own (no
 * source-file filtering needed, unlike `:daemon:core`'s incremental `IncrementalDiscovery`, which
 * scans a shared classpath and must filter by source file). The id shape —
 * `<className>.<methodName>` — matches what the gradle plugin emits and what the render pipeline
 * expects, so the token's `previewId` renders directly.
 *
 * Only **direct** `@Preview` annotations are recognised in v1; multi-preview meta-annotations
 * (`@LightDarkPreviews` → many) are a follow-up. Fail-safe: returns an empty list (logging) on any
 * scan failure, which the orchestrator reports as "no @Preview found".
 */
class PlaygroundPreviewDiscoverer(
  private val previewAnnotationFqns: Set<String> = DEFAULT_PREVIEW_ANNOTATION_FQNS
) : PlaygroundCompileService.PreviewDiscoverer {

  override val recognisedAnnotationFqns: Set<String>
    get() = previewAnnotationFqns

  override fun discover(classesDir: Path, classpath: List<Path>): List<String> =
    try {
      ClassGraph()
        .enableMethodInfo()
        .enableAnnotationInfo()
        .ignoreMethodVisibility()
        .overrideClasspath(classesDir.toNioPath().toAbsolutePath().toString())
        .ignoreParentClassLoaders()
        .scan()
        .use { scan ->
          val ids = LinkedHashSet<String>()
          for (classInfo in scan.allClasses) {
            for (method in classInfo.methodInfo) {
              val isPreview =
                method.annotationInfo?.any { it.name in previewAnnotationFqns } == true
              if (isPreview) ids.add(previewId(classInfo.name, method.name))
            }
          }
          ids.toList()
        }
    } catch (t: Throwable) {
      System.err.println(
        "playground: preview discovery failed (${t.javaClass.simpleName}: ${t.message}); none found"
      )
      emptyList()
    }

  /**
   * The preview-shaped method annotations in [classesDir] that [previewAnnotationFqns] does **not**
   * cover — so a snippet that imported, say, Wear's or Glance's `@Preview` is told what it actually
   * declared instead of being told it declared nothing.
   *
   * A second scan, deliberately: it runs only on the empty-result path (a failure the user is
   * already waiting on), which keeps the hot path a single scan and this class stateless — one
   * instance serves every concurrent request.
   */
  override fun unrecognisedPreviewAnnotations(classesDir: Path): List<String> =
    try {
      ClassGraph()
        .enableMethodInfo()
        .enableAnnotationInfo()
        .ignoreMethodVisibility()
        .overrideClasspath(classesDir.toNioPath().toAbsolutePath().toString())
        .ignoreParentClassLoaders()
        .scan()
        .use { scan ->
          val fqns = sortedSetOf<String>()
          for (classInfo in scan.allClasses) {
            for (method in classInfo.methodInfo) {
              val annotations = method.annotationInfo ?: continue
              for (annotation in annotations) {
                if (annotation.name in previewAnnotationFqns) continue
                if (looksLikePreview(annotation.name)) fqns.add(annotation.name)
              }
            }
          }
          fqns.toList()
        }
    } catch (t: Throwable) {
      System.err.println(
        "playground: unrecognised-annotation scan failed " +
          "(${t.javaClass.simpleName}: ${t.message}); reporting none"
      )
      emptyList()
    }

  companion object {
    /**
     * The `@Preview` annotation FQNs a playground snippet can use: Android Compose, Compose Desktop
     * (`androidx.compose.desktop.*` — what a `compose-cmp` snippet's classpath offers and what an
     * IDE import-fixes to on a desktop target) and Compose Multiplatform
     * (`org.jetbrains.compose.*`). Mirrors `:daemon:core`'s `DEFAULT_PREVIEW_ANNOTATION_FQNS`,
     * which carries the desktop FQN too; leaving it out here compiled a desktop snippet clean and
     * then reported "no @Preview found" for it.
     */
    val DEFAULT_PREVIEW_ANNOTATION_FQNS =
      setOf(
        "androidx.compose.ui.tooling.preview.Preview",
        "androidx.compose.desktop.ui.tooling.preview.Preview",
        "org.jetbrains.compose.ui.tooling.preview.Preview",
      )

    /**
     * Whether [fqn] is preview-shaped — an annotation a snippet author plausibly meant as a
     * `@Preview` (Wear tiles', Glance's, a project's own multi-preview) but that this host does not
     * render. Name-based on purpose: the point is to name what the snippet declared, and the
     * classes are not loaded.
     */
    internal fun looksLikePreview(fqn: String): Boolean =
      fqn.substringAfterLast('.').substringAfterLast('$').contains("Preview")

    /** The `<className>.<methodName>` id the render pipeline keys a preview by. */
    internal fun previewId(className: String, methodName: String): String = "$className.$methodName"
  }
}
