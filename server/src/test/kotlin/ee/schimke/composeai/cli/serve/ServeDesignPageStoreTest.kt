package ee.schimke.composeai.cli.serve

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import okio.FileSystem

/**
 * The design-page view is an enhancement over a catalog's grid, so every failure mode here must
 * degrade to "no pages" rather than taking the catalog down — which is what these cover, along with
 * the two rules that are easy to get subtly wrong: a page is only advertised when its export
 * survives sanitizing (because the alternative is a stage that can paint nothing), and the markup
 * the store hands out is the *sanitized* markup on every route.
 */
class ServeDesignPageStoreTest {

  private val svg =
    """
    <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 1200 800" width="1200" height="800">
      <g data-node-id="1:1"><circle cx="180" cy="300" r="90" fill="#6750A4"/></g>
      <g data-node-id="1:2"><rect x="330" y="210" width="180" height="180" fill="#6750A4"/></g>
    </svg>
    """
      .trimIndent()

  private fun store(
    json: String?,
    exports: Map<String, String> = mapOf("shape.svg" to svg),
  ): ServeDesignPageStore {
    val root = Files.createTempDirectory("pages").toFile().also { it.deleteOnExit() }
    val dir = File(root, ServeDesignPageStore.DIRECTORY)
    dir.mkdirs()
    if (json != null) File(dir, ServeDesignPageStore.INDEX_FILE).writeText(json)
    exports.forEach { (name, text) -> File(dir, name).writeText(text) }
    return ServeDesignPageStore.load(root, FileSystem.SYSTEM)
  }

  private fun manifest(
    pages: String,
    version: Int = 2,
    fileKey: String = "ocdacdEsnHipMJD3egzxKb",
  ) = """{"version":$version,"source":"figma","fileKey":"$fileKey","pages":[$pages]}"""

  private val shape =
    """
    {"id":"shape","name":"Shape","nodeId":"58548:7093",
     "frame":{"width":1200,"height":800},
     "image":{"uri":"shape.svg","format":"svg"},
     "nodes":[
       {"nodeId":"1:1","name":"Shape=Circle","depth":3,
        "ref":"figma:ocdacdEsnHipMJD3egzxKb/1:1","link":"manifest",
        "code":"sections/Shapes.kt#CircleShape","previewId":"shape-circle__light",
        "confidence":"high"},
       {"nodeId":"1:9","name":".Header","depth":2,
        "ref":"figma:ocdacdEsnHipMJD3egzxKb/1:9","link":"unlinked"}]}
    """
      .trimIndent()

  @Test
  fun `no manifest yields an empty store`() {
    assertTrue(store(null).pages.isEmpty())
  }

  @Test
  fun `malformed manifest is ignored rather than thrown`() {
    assertTrue(store("{ not json").pages.isEmpty())
  }

  @Test
  fun `the retired page-backdrop version is ignored wholesale`() {
    // Version 1 was design-parity's screen backdrop: a raster with nothing addressable in it. A
    // stale delivery branch shows no pages rather than a page that can do nothing.
    assertTrue(store(manifest(shape, version = 1)).pages.isEmpty())
    assertTrue(store(manifest(shape, version = 99)).pages.isEmpty())
  }

  @Test
  fun `a page and its nodes survive a well-formed manifest`() {
    val loaded = store(manifest(shape))
    val page = assertNotNull(loaded.page("shape"))
    assertEquals("Shape", page.name)
    assertEquals(1200.0, page.frame.width)
    assertEquals(2, page.nodes.size)
    assertEquals("shape-circle__light", page.nodes.first().previewId)
    assertTrue(page.nodes.last().isUnlinked)
    assertEquals("ocdacdEsnHipMJD3egzxKb", loaded.fileKey)
    assertTrue(loaded.svg("shape")!!.contains("data-node-id=\"1:1\""))
  }

  @Test
  fun `the markup handed out is sanitized, on the asset route as much as the view`() {
    // Serving the branch's raw bytes off `/pages/<id>.svg` would publish markup the server has
    // already judged unsafe to inline. One answer per URL, and it is the safe one.
    val hostile =
      svg.replace(
        "<g data-node-id=\"1:1\">",
        "<script>alert(1)</script><g data-node-id=\"1:1\" onclick=\"alert(2)\">",
      )
    val markup =
      assertNotNull(store(manifest(shape), exports = mapOf("shape.svg" to hostile)).svg("shape"))
    assertFalse(markup.contains("script"))
    assertFalse(markup.contains("onclick"))
    // The join survives the scrub — a safe document with no ids in it would be useless.
    assertTrue(markup.contains("data-node-id=\"1:1\""))
  }

  @Test
  fun `a page whose export is missing or not an SVG is never advertised`() {
    assertTrue(store(manifest(shape), exports = emptyMap()).pages.isEmpty())
    assertTrue(
      store(manifest(shape), exports = mapOf("shape.svg" to "<html>nope</html>")).pages.isEmpty()
    )
  }

  @Test
  fun `a page exported as a raster is refused`() {
    // The surface's capability is addressing nodes inside the export. A page the server can only
    // stare at is worse than a page it never advertises.
    val raster = shape.replace("\"format\":\"svg\"", "\"format\":\"png\"")
    assertTrue(store(manifest(raster)).pages.isEmpty())
  }

  @Test
  fun `an export path that escapes the manifest directory is refused`() {
    val escaping = shape.replace("\"uri\":\"shape.svg\"", "\"uri\":\"../../etc/passwd\"")
    assertTrue(store(manifest(escaping)).pages.isEmpty())
  }

  @Test
  fun `an unroutable page id is dropped and its siblings survive`() {
    val bad = shape.replace("\"id\":\"shape\"", "\"id\":\"../escape\"")
    val good =
      shape.replace("\"id\":\"shape\"", "\"id\":\"styles\"").replace("shape.svg", "styles.svg")
    val loaded =
      store(manifest("$bad,$good"), exports = mapOf("shape.svg" to svg, "styles.svg" to svg))
    assertEquals(listOf("styles"), loaded.pages.map { it.id })
  }

  @Test
  fun `a page id ending in svg or json is refused — those suffixes are the page's own routes`() {
    // `/{system}/pages/shape.svg` reads as "the export of the page `shape`" and `…/shape.json` as
    // "its node join", so a page genuinely id'd either way would be unreachable behind them while
    // only the thing about it resolved.
    for (id in listOf("styles.svg", "styles.json")) {
      val shadowing = shape.replace("\"id\":\"shape\"", "\"id\":\"$id\"")
      assertTrue(store(manifest(shadowing)).pages.isEmpty(), "page id '$id' must not be advertised")
    }
  }

  @Test
  fun `a dot-segment page id is refused — a browser would normalise it away`() {
    for (id in listOf(".", "..")) {
      val dotted = shape.replace("\"id\":\"shape\"", "\"id\":\"$id\"")
      assertTrue(store(manifest(dotted)).pages.isEmpty(), "page id '$id' must not be advertised")
    }
  }

  @Test
  fun `the per-page node cap is inclusive at 500`() {
    // The importer walks at most 500 nodes. Pin both sides of that boundary: 499 and 500 survive
    // whole, while a malformed producer carrying 501 is bounded to the same supported maximum.
    for (input in listOf(499, 500, 501)) {
      val many =
        (1..input).joinToString(",") { i ->
          """{"nodeId":"1:$i","name":"Item $i","link":"unlinked"}"""
        }
      val crowded =
        shape.replace(
          Regex("\"nodes\":\\[.*\\]", RegexOption.DOT_MATCHES_ALL),
          "\"nodes\":[$many]",
        )
      assertEquals(
        minOf(input, ServeDesignPageStore.MAX_NODES_PER_PAGE),
        store(manifest(crowded)).pages.single().nodes.size,
        "input node count $input",
      )
    }
  }

  @Test
  fun `a duplicate page id keeps the first declaration`() {
    val second = shape.replace("\"name\":\"Shape\"", "\"name\":\"Impostor\"")
    val loaded = store(manifest("$shape,$second"))
    assertEquals(1, loaded.pages.size)
    assertEquals("Shape", loaded.pages.single().name)
  }

  @Test
  fun `a page with no usable frame is dropped, and a node with no id with it`() {
    val zeroFrame = shape.replace("\"width\":1200,\"height\":800", "\"width\":0,\"height\":800")
    assertTrue(store(manifest(zeroFrame)).pages.isEmpty())

    // The node id is the only handle this contract carries — there is no recorded rectangle,
    // because the SVG is the geometry. A blank one names nothing in the export.
    val anonymous = shape.replace("\"nodeId\":\"1:1\"", "\"nodeId\":\"  \"")
    val loaded = store(manifest(anonymous))
    assertEquals(1, loaded.pages.single().nodes.size)
    assertEquals(".Header", loaded.pages.single().nodes.single().name)
  }

  @Test
  fun `a hostile file key yields no deep link rather than an attacker-chosen href`() {
    val loaded = store(manifest(shape, fileKey = "javascript:alert(1)"))
    assertEquals("", loaded.fileKey)
    assertNull(ServeFigmaSpec.url(loaded.fileKey, "58548:7093"))
  }

  @Test
  fun `an unknown link method drops the manifest rather than mis-colouring a node`() {
    // The four methods are a typed enum in the contract, and an unrecognised one fails the parse of
    // the whole document.
    //
    // This assertion has been both ways round, which is why it is spelled out here.
    // compose-preview-daemon#119 put `coerceInputValues` on `DesignPagesJson` so an unvetted blend
    // mode would degrade instead of throwing, and this test was rewritten to expect the same
    // leniency for `link`. #128 reverted that flag: it is not scoped to one field, so it silently
    // disabled the contract's rule for EVERY enum the document carries, `PageNodeLink` among them.
    //
    // Dropping the document is the deliberate outcome, not collateral damage. Reinterpreting an
    // unknown method draws a node in a colour that claims coverage nobody verified, and the
    // producer already refuses to emit an unvetted value — so a manifest carrying one is a
    // hand-edit or a newer producer, and both are better surfaced than silently reinterpreted.
    // Every reader wraps this parse in `runCatching`, so the failure degrades to "this catalog
    // serves no pages", which is visible, rather than to a sheet that quietly lies about how it
    // was drawn.
    val odd = shape.replace("\"link\":\"manifest\"", "\"link\":\"vibes\"")
    assertTrue(store(manifest(odd)).pages.isEmpty())
  }

  @Test
  fun `a node with no ref still deep-links, rebuilt from the file key and node id`() {
    // `ref` is optional, and for an unlinked node the design-tool link is the only one it has.
    val noRef = shape.replace("\"ref\":\"figma:ocdacdEsnHipMJD3egzxKb/1:9\",", "")
    val loaded = store(manifest(noRef))
    val header = loaded.pages.single().nodes.last()
    assertEquals("figma:ocdacdEsnHipMJD3egzxKb/1:9", loaded.refFor(header))
  }
}
