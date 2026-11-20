package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.daemon.protocol.DataFetchResult
import ee.schimke.composeai.data.layoutinspector.ComposeSemanticsNode
import ee.schimke.composeai.data.layoutinspector.ComposeSemanticsPayload
import ee.schimke.composeai.data.layoutinspector.ComposeSemanticsProduct
import ee.schimke.composeai.uibuilder.export.NativeNodeTags
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json
import okio.Path.Companion.toPath

class NativeNodeTagSemanticsTest {

  private val json = Json { ignoreUnknownKeys = true }
  private val tmpDirs = mutableListOf<File>()

  private fun tmp(): File =
    Files.createTempDirectory("native-node-tag-semantics").toFile().also { tmpDirs += it }

  @AfterTest
  fun cleanup() {
    tmpDirs.forEach { it.deleteRecursively() }
  }

  private fun snippet() =
    PlaygroundTokenStore.PlaygroundSnippet(
      mode = PlaygroundMode.ANDROID,
      workDir = "/work".toPath(),
      classesDir = "/work/classes".toPath(),
      classpath = listOf("/catalog/app.jar".toPath(), "/work/classes".toPath()),
      moduleName = "playground-android",
      previewId = "demo.SampleKt.CardPreview",
    )

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
    label: String? = null,
    placed: Boolean = true,
    children: List<ComposeSemanticsNode> = emptyList(),
  ) =
    ComposeSemanticsNode(
      nodeId = id,
      boundsInRoot = bounds,
      label = label,
      testTag = testTag,
      placed = placed,
      children = children,
    )

  /**
   * A Wear widget played by the CMP player: Remote Compose has no `testTag`, so each node carries
   * its id as a leading content description, reported as the node's `label`. Labels without the
   * prefix (a node's own description) are not ids, and a real test tag still wins.
   */
  @Test
  fun `a Remote Compose render reports a box per node tagged in its content description`() {
    val tree =
      node(
        "0",
        "0,0,232,144",
        children =
          listOf(
            node(
              "1",
              "32,16,200,128",
              label = NativeNodeTags.tag("column"),
              children =
                listOf(
                  node("2", "32,16,200,48", label = NativeNodeTags.tag("title, with a comma")),
                  node("3", "32,48,80,96", label = "Play"),
                  node("4", "80,48,128,96", label = NativeNodeTags.tag("x"), testTag = "real"),
                ),
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
    val svc =
      PlaygroundNodeBoundsService(
        openSession = { _, _, _, _ -> fake },
        newWorkDir = { tmp() },
        retagSemantics = NativeNodeTagSemantics::retag,
      )

    assertEquals(
      mapOf(
        "column" to AnnotationBounds(x = 32, y = 16, width = 168, height = 112),
        "title, with a comma" to AnnotationBounds(x = 32, y = 16, width = 168, height = 32),
        "real" to AnnotationBounds(x = 80, y = 48, width = 48, height = 48),
      ),
      svc.capture(snippet()),
    )
  }
}
