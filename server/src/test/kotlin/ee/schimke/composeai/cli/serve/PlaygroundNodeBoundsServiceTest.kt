package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.daemon.protocol.DataFetchResult
import ee.schimke.composeai.data.layoutinspector.ComposeSemanticsNode
import ee.schimke.composeai.data.layoutinspector.ComposeSemanticsPayload
import ee.schimke.composeai.data.layoutinspector.ComposeSemanticsProduct
import ee.schimke.composeai.previewdata.PreviewManifest
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.serialization.json.Json
import okio.Path.Companion.toPath

/**
 * The UI-builder's node-bounds capture: enable → render → await → fetch, and the walk from a
 * semantics tree to the rectangles the native pane outlines and hit-tests.
 *
 * Driven against a fake render session, so what is under test is the orchestration and the
 * projection rather than a daemon. Every failure case asserts the *same* thing in a different
 * shape: no bounds, never an exception — a design whose capture fails still has its frame.
 */
class PlaygroundNodeBoundsServiceTest {

  private val json = Json { ignoreUnknownKeys = true }
  private val tmpDirs = mutableListOf<File>()

  private fun tmp(): File =
    java.nio.file.Files.createTempDirectory("node-bounds-test").toFile().also { tmpDirs += it }

  @AfterTest
  fun cleanup() {
    tmpDirs.forEach { it.deleteRecursively() }
  }

  private fun snippet(previewId: String = PREVIEW_ID) =
    PlaygroundTokenStore.PlaygroundSnippet(
      mode = PlaygroundMode.ANDROID,
      workDir = "/work".toPath(),
      classesDir = "/work/classes".toPath(),
      classpath = listOf("/catalog/app.jar".toPath(), "/work/classes".toPath()),
      moduleName = "playground-android",
      previewId = previewId,
    )

  /**
   * A semantics result on the inline transport, which is what `inline = true` asks the daemon for.
   */
  private fun semanticsResult(root: ComposeSemanticsNode) =
    DataFetchResult(
      kind = ComposeSemanticsProduct.KIND,
      schemaVersion = ComposeSemanticsProduct.SCHEMA_VERSION,
      payload =
        json.encodeToJsonElement(
          ComposeSemanticsPayload.serializer(),
          ComposeSemanticsPayload(root),
        ),
    )

  private fun node(
    id: String,
    bounds: String,
    testTag: String? = null,
    placed: Boolean = true,
    children: List<ComposeSemanticsNode> = emptyList(),
  ) =
    ComposeSemanticsNode(
      nodeId = id,
      boundsInRoot = bounds,
      testTag = testTag,
      placed = placed,
      children = children,
    )

  @Test
  fun `a rendered snippet reports a box per tagged node, in render pixels`() {
    val tree =
      node(
        "0",
        "0,0,800,1600",
        children =
          listOf(
            node("1", "0,0,800,240", testTag = "header"),
            node(
              "2",
              "32,240,768,1360",
              testTag = "grid",
              children = listOf(node("3", "48,256,432,304", testTag = "first-card")),
            ),
          ),
      )
    val fake =
      FakeRenderSession(
        renderRoot = tmp(),
        fetchDataHook = { _, kind ->
          if (kind == ComposeSemanticsProduct.KIND) semanticsResult(tree) else null
        },
      )
    var seenManifest: PreviewManifest? = null
    val svc =
      PlaygroundNodeBoundsService(
        openSession = { classesDir, previewsJson, _, userClasspath ->
          assertTrue(previewsJson.exists(), "the manifest is written before the session opens")
          seenManifest =
            json.decodeFromString(PreviewManifest.serializer(), previewsJson.readText())
          assertEquals("/work/classes", classesDir.path)
          assertTrue(
            userClasspath.any { it.endsWith("app.jar") },
            "catalog jars ride userClasspath",
          )
          fake
        },
        newWorkDir = { tmp() },
      )

    val bounds = svc.capture(snippet())

    assertEquals(
      mapOf(
        "header" to AnnotationBounds(x = 0, y = 0, width = 800, height = 240),
        "grid" to AnnotationBounds(x = 32, y = 240, width = 736, height = 1120),
        "first-card" to AnnotationBounds(x = 48, y = 256, width = 384, height = 48),
      ),
      bounds,
    )
    // The semantics extension registers inactive, and its snapshot is collected by a render hook
    // that only runs while it is active: enable before the render or the fetch reports nothing.
    assertTrue(
      PlaygroundNodeBoundsService.SEMANTICS_EXTENSION_ID in fake.enabledExtensionIds,
      "the semantics extension must be enabled before the render",
    )
    assertEquals(PREVIEW_ID, seenManifest!!.previews.single().id)
  }

  @Test
  fun `an unplaced node has no box, and neither does a duplicated tag`() {
    // Both are honest outcomes rather than errors. A node the render never placed drew nothing, so
    // there is no rectangle to outline; a tag two nodes carry no longer identifies either of them,
    // and drawing one of the two boxes would select the wrong node on a click.
    val tree =
      node(
        "0",
        "0,0,400,800",
        children =
          listOf(
            // `boundsInRoot` is left,top,right,bottom — the space the semantics tree reports in.
            node("1", "0,0,400,40", testTag = "offscreen", placed = false),
            node("2", "0,40,400,120", testTag = "twice"),
            node("3", "0,120,400,200", testTag = "twice"),
            node("4", "0,200,400,280", testTag = "once"),
          ),
      )
    val svc = service(tree)

    assertEquals(
      mapOf("once" to AnnotationBounds(x = 0, y = 200, width = 400, height = 80)),
      svc.capture(snippet()),
    )
  }

  @Test
  fun `a backend without the semantics extension yields no bounds`() {
    val fake =
      FakeRenderSession(
        renderRoot = tmp(),
        unknownExtensionIds = setOf(PlaygroundNodeBoundsService.SEMANTICS_EXTENSION_ID),
        fetchDataHook = { _, kind ->
          if (kind == ComposeSemanticsProduct.KIND) semanticsResult(node("0", "0,0,10,10", "tag"))
          else null
        },
      )
    val svc =
      PlaygroundNodeBoundsService(openSession = { _, _, _, _ -> fake }, newWorkDir = { tmp() })

    assertEquals(emptyMap(), svc.capture(snippet()))
  }

  @Test
  fun `a render that never finishes times out to no bounds`() {
    val fake = FakeRenderSession(renderRoot = tmp(), renderHook = { _, _ -> })
    val svc =
      PlaygroundNodeBoundsService(
        openSession = { _, _, _, _ -> fake },
        newWorkDir = { tmp() },
        renderBudget = 50.milliseconds,
        ackTimeout = 50.milliseconds,
      )

    assertEquals(emptyMap(), svc.capture(snippet()))
  }

  @Test
  fun `a session that cannot be opened yields no bounds rather than an exception`() {
    val svc =
      PlaygroundNodeBoundsService(
        openSession = { _, _, _, _ -> error("no daemon sidecar on this host") },
        newWorkDir = { tmp() },
      )

    assertEquals(emptyMap(), svc.capture(snippet()))
  }

  private fun service(tree: ComposeSemanticsNode): PlaygroundNodeBoundsService {
    val fake =
      FakeRenderSession(
        renderRoot = tmp(),
        fetchDataHook = { _, kind ->
          if (kind == ComposeSemanticsProduct.KIND) semanticsResult(tree) else null
        },
      )
    return PlaygroundNodeBoundsService(
      openSession = { _, _, _, _ -> fake },
      newWorkDir = { tmp() },
    )
  }

  private companion object {
    const val PREVIEW_ID = "generated.uibuilder.preview.UiBuilderGeneratedPreviewKt.Preview"
  }
}
