package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.bundle.TrustStore
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * One cache generation for a page, its verdict and the frame beside it. Uses
 * [ServePinnedRevisionTest]'s delivery-branch stub, whose tip and previous publish serve different
 * bytes for the same preview id.
 */
class ServeCacheGenerationTest {

  private val system = "compose-m3"
  private val branch = "design-artifacts/compose-m3"
  private val repo = "yschimke/compose-ai-tools"
  private val previewId = "button-filled__ideal__default__dark"
  private val referenceId = "button-figma"
  private val oldCommit = "1111111111111111111111111111111111111111"
  private val newCommit = "2222222222222222222222222222222222222222"

  private val currentRender = png(1)
  private val historicalRender = png(2)
  private val currentReference = png(3)
  private val historicalReference = png(4)

  private val catalogJson =
    """
    {"schema":"design-parity-catalog/v1","system":"compose-m3","components":[
      {"componentId":"Button/Filled","images":[
        {"path":"images/button-filled/ideal__default__dark.png","theme":"dark"}]}]}
    """
      .trimIndent()

  private val referencesJson =
    """
    {"schema":"compose-preview-references/v1","references":[{
       "id":"button-figma","previewId":"$previewId","label":"Figma button",
       "raster":{"path":"references/button.png","width":2,"height":2},
       "source":{"provider":"figma"}}]}
    """
      .trimIndent()

  /**
   * A published tag index, so the tag picker is offered and its coupling to the frame is actually
   * tested.
   */
  private val tagsJson =
    """
    {"schema":"compose-preview-tags/v1","previews":{
      "$previewId":{
        "glyph":{"count":1,"bounds":{"x":0,"y":0,"width":2,"height":2},"space":"render-pixels"}
      }}}
    """
      .trimIndent()

  private val previewIndexJson =
    """
    {"schema":"compose-preview-revision-index/v1","current":["$previewId"],"revisions":[
      {"commit":"$oldCommit","previews":["$previewId"]}]}
    """
      .trimIndent()

  private val feed =
    """
    <feed>
      <entry>
        <id>tag:github.com,2008:Grit::Commit/$newCommit</id>
        <updated>2026-08-13T09:42:57Z</updated>
        <content type="html">regenerate compose-m3 catalog (2026-08-13, 0b0c2063)</content>
      </entry>
      <entry>
        <id>tag:github.com,2008:Grit::Commit/$oldCommit</id>
        <updated>2026-08-01T10:00:00Z</updated>
        <content type="html">regenerate compose-m3 catalog (2026-08-01, b34eff53)</content>
      </entry>
    </feed>
    """
      .trimIndent()

  private val fetch: (String) -> ByteArray? = { url ->
    val tip = "https://raw.githubusercontent.com/$repo/$newCommit/"
    val byBranch = "https://raw.githubusercontent.com/$repo/$branch/"
    val old = "https://raw.githubusercontent.com/$repo/$oldCommit/"
    when (url) {
      ServeCatalogRevision.commitsFeedUrl(repo, branch) -> feed.encodeToByteArray()
      "${tip}catalog.json",
      "${byBranch}catalog.json" -> catalogJson.encodeToByteArray()
      "${tip}preview-index.json",
      "${byBranch}preview-index.json" -> previewIndexJson.encodeToByteArray()
      "${tip}references/index.json",
      "${byBranch}references/index.json" -> referencesJson.encodeToByteArray()
      "${tip}tags/index.json",
      "${byBranch}tags/index.json" -> tagsJson.encodeToByteArray()
      "${tip}references/button.png",
      "${byBranch}references/button.png" -> currentReference
      "${tip}images/button-filled/ideal__default__dark.png",
      "${byBranch}images/button-filled/ideal__default__dark.png" -> currentRender
      "${old}references/button.png" -> historicalReference
      "${old}images/button-filled/ideal__default__dark.png" -> historicalRender
      else -> null
    }
  }

  private val registry = ServeSessionRegistry(open = { null })
  private val client = OkHttpClient()
  private var server: ServeHttpServer? = null

  @AfterTest
  fun tearDown() {
    server?.stop()
  }

  @Test
  fun `scope writes the generation onto an asset query, opening one where there is none`() {
    assertEquals("?gen=$newCommit", ServeCacheGeneration.scope("", newCommit))
    assertEquals("?token=t&gen=$newCommit", ServeCacheGeneration.scope("?token=t", newCommit))
    // A session with no delivery branch has no generation to name, and minting an unanswerable
    // parameter for it would be worse than leaving its frames where they have always been.
    assertEquals("?token=t", ServeCacheGeneration.scope("?token=t", null))
    // A ref is not a generation, for the same reason it is not a pin: it steers a branch read.
    assertEquals("?token=t", ServeCacheGeneration.scope("?token=t", "main"))
  }

  @Test
  fun `an unpinned comparison scopes the frames it draws without pinning the page`() {
    val port = start().port

    val page = text("http://127.0.0.1:$port/$system/compare/$previewId")

    assertTrue(page.contains("render/$previewId.png?gen=$newCommit"), page)
    assertTrue(page.contains("reference/$referenceId.png?gen=$newCommit"), page)
    // The generation must not leak into copyable links, where `gen=` would read as a false
    // permalink.
    assertFalse(page.contains("/compare/$previewId?gen="), page)
    assertFalse(page.contains("/p/$previewId?gen="), page)
    // …and it is not a pin, so the page shows no pin banner and withholds nothing.
    assertFalse(page.contains("Pinned to catalog revision"), page)
  }

  @Test
  fun `the viewer is handed its generation so the frame it fetches carries one too`() {
    val port = start().port

    val page = text("http://127.0.0.1:$port/$system/p/$previewId")

    // The viewer builds its frame URL in the browser, so the generation reaches it as data.
    assertTrue(page.contains("data-generation=\"$newCommit\""), page)
    // …and the unfurl card points at the frame this page drew, so a link shared out of the viewer
    // is not a different picture from the one that was on screen.
    val ogImage =
      Regex("<meta property=\"og:image\" content=\"([^\"]+)\"").find(page)?.groupValues?.get(1)
    assertTrue(ogImage?.contains("gen=$newCommit") == true, page)
  }

  @Test
  fun `Catalog mode hides the revision control without dropping the generation`() {
    val port = start().port

    val page = text("http://127.0.0.1:$port/$system/p/$previewId?chrome=catalog")

    // The embedded browser offers no publish history, but still scopes its stage URL to the page's
    // generation.
    assertFalse(page.contains("cp-revisions"), page)
    assertTrue(page.contains("data-generation=\"$newCommit\""), page)
  }

  @Test
  fun `a page a refresh overtook still gets its own generation's frames`() {
    val port = start().port

    // The scoped frame answers with the pixels the verdict was measured on, even after the catalog
    // moved on.
    assertContentEquals(
      historicalRender,
      bytes("http://127.0.0.1:$port/$system/render/$previewId.png?gen=$oldCommit"),
    )
    assertContentEquals(
      historicalReference,
      bytes("http://127.0.0.1:$port/$system/reference/$referenceId.png?gen=$oldCommit"),
    )
    assertContentEquals(
      currentRender,
      bytes("http://127.0.0.1:$port/$system/render/$previewId.png?gen=$newCommit"),
    )
    assertContentEquals(
      currentRender,
      bytes("http://127.0.0.1:$port/$system/render/$previewId.png"),
    )
  }

  @Test
  fun `a generation the branch cannot answer 404s rather than serving today's pixels`() {
    val port = start().port
    val absent = "3333333333333333333333333333333333333333"

    val response = get("http://127.0.0.1:$port/$system/render/$previewId.png?gen=$absent")

    assertEquals(404, response.first)
    // The failure that matters is the silent one: 200 with today's pixels is exactly the pairing
    // this parameter exists to make impossible, and nothing downstream could detect it.
    assertFalse(response.second.contentEquals(currentRender))
  }

  @Test
  fun `a scoped frame is immutable while an unscoped one stays on the short public lifetime`() {
    val port = start().port

    val scoped = cacheControl("http://127.0.0.1:$port/$system/render/$previewId.png?gen=$newCommit")
    val unscoped = cacheControl("http://127.0.0.1:$port/$system/render/$previewId.png")

    // A generation makes the URL content-addressed, hence `immutable`.
    assertTrue(scoped.contains("immutable"), scoped)
    // Without one the URL is the moving target it always was, and caching the ambiguity for longer
    // is not the fix.
    assertFalse(unscoped.contains("immutable"), unscoped)
    assertNotEquals(scoped, unscoped)
  }

  @Test
  fun `a ref-shaped generation is refused instead of quietly resolving to the tip`() {
    val port = start().port

    for (path in
      listOf(
        "/$system/render/$previewId.png?gen=$branch",
        "/$system/reference/$referenceId.png?gen=main",
      )) {
      assertEquals(400, get("http://127.0.0.1:$port$path").first, path)
    }
  }

  @Test
  fun `a stale generation steps aside for a product made to order rather than refusing`() {
    val port = start().port

    // An override makes the response `no-store` and generation-free, so turning a knob still works;
    // a pin on the same URL is contradictory and refused.
    val overridden =
      get("http://127.0.0.1:$port/$system/render/$previewId.png?gen=$oldCommit&fontScale=1.5")
    assertNotEquals(400, overridden.first)
    assertEquals(
      400,
      get("http://127.0.0.1:$port/$system/render/$previewId.png?at=$oldCommit&fontScale=1.5").first,
    )
    // The daemon-made products are the same case: `.annotations` is produced for this request and
    // served `no-store`, so a stale generation has nothing to reconcile there.
    assertNotEquals(
      400,
      get("http://127.0.0.1:$port/$system/render/$previewId.annotations?gen=$oldCommit").first,
    )
  }

  @Test
  fun `a lane that can only describe today refuses a stale generation rather than answering`() {
    val port = start().port

    // Frame-describing products (tag index, semantics, typography, a11y) are measured on the
    // current catalog and have no older copy, so they must refuse a stale generation rather than
    // mix publishes.
    assertEquals(
      409,
      get("http://127.0.0.1:$port/$system/tags/$previewId?gen=$oldCommit").first,
    )
    for (suffix in listOf(".annotations", ".a11y", ".slots", ".svg", ".rc")) {
      assertEquals(
        409,
        get("http://127.0.0.1:$port/$system/render/$previewId$suffix?gen=$oldCommit").first,
        suffix,
      )
    }
    // The current generation is the ordinary browse and answers as it always did.
    assertEquals(200, get("http://127.0.0.1:$port/$system/tags/$previewId?gen=$newCommit").first)
    assertEquals(200, get("http://127.0.0.1:$port/$system/tags/$previewId").first)
  }

  @Test
  fun `the comparison scopes the tag index to the same publish as the frame`() {
    val port = start().port

    val page = text("http://127.0.0.1:$port/$system/compare/$previewId")

    // Clicking a tag entry persists its bounds as an acceptance baseline, so the index and the
    // pixels have to be one publish. Scoping the URL is what lets the lane refuse a stale one.
    assertTrue(page.contains("data-cp-tags="), page)
    assertTrue(page.contains("/tags/$previewId?gen=$newCommit"), page)
  }

  @Test
  fun `a prebaked thumbnail never answers for a generation it is not`() {
    val port = start().port

    // Thumbnails come from the current catalog and their fast path precedes generation routing, so
    // a stale `gen=` must leave the thumbnail lane first.
    val hash =
      Regex("[?&]${ServeHeroImages.THUMB_PARAM}=([A-Za-z0-9_-]+)")
        .find(text("http://127.0.0.1:$port/$system/"))
        ?.groupValues
        ?.get(1)
    assertTrue(hash != null, "the fixture published no grid thumbnail to test the fast path with")
    val stale =
      get(
        "http://127.0.0.1:$port/$system/render/$previewId.png" +
          "?${ServeHeroImages.THUMB_PARAM}=$hash&gen=$oldCommit"
      )
    assertEquals(200, stale.first)
    assertContentEquals(historicalRender, stale.second)
    // …and the current generation stays on the fast path, which is what keeps a scoped card cheap.
    assertEquals(
      200,
      get(
          "http://127.0.0.1:$port/$system/render/$previewId.png" +
            "?${ServeHeroImages.THUMB_PARAM}=$hash&gen=$newCommit"
        )
        .first,
    )
  }

  @Test
  fun `a pinned page writes the pin and does not add a second sha beside it`() {
    val port = start().port

    val page = text("http://127.0.0.1:$port/$system/compare/$previewId?at=$oldCommit")

    assertTrue(page.contains("render/$previewId.png?at=$oldCommit"), page)
    // A pin already fixes the publish every frame comes from. Adding the generation would put two
    // shas on one URL for the lane to choose between.
    assertFalse(page.contains("gen="), page)
  }

  private fun start(): ServeHttpServer {
    val store =
      ServeCatalogStore(
        root = tempRoot(),
        register = { name, host -> registry.register(name, host = host, pinned = true) },
        trust = { TrustStore.EMPTY },
        fetch = fetch,
      )
    assertTrue(store.load(system) is ServeCatalogStore.Result.Ok)
    return ServeHttpServer(
        host = "127.0.0.1",
        requestedPort = 0,
        token = "unused-in-public",
        sessions = registry,
        defaultSessionId = system,
        isPublic = true,
        catalogSessions = listOf(system),
      )
      .also {
        it.start()
        server = it
      }
  }

  private fun get(url: String): Pair<Int, ByteArray> =
    client.newCall(Request.Builder().url(url).build()).execute().use { response ->
      response.code to response.body.bytes()
    }

  private fun cacheControl(url: String): String =
    client.newCall(Request.Builder().url(url).build()).execute().use { response ->
      assertEquals(200, response.code, url)
      response.header("Cache-Control").orEmpty()
    }

  private fun bytes(url: String): ByteArray {
    val (code, body) = get(url)
    assertEquals(200, code, url)
    return body
  }

  private fun text(url: String): String {
    val (code, body) = get(url)
    assertEquals(200, code, url)
    return body.decodeToString()
  }

  private fun tempRoot(): File =
    Files.createTempDirectory("serve-generation").toFile().also { it.deleteOnExit() }

  /** A distinguishable 2×2 PNG per [seed], so "which version came back" is decidable. */
  private fun png(seed: Int): ByteArray =
    ByteArrayOutputStream()
      .also { out ->
        val image = BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB)
        image.setRGB(0, 0, seed * 0x3F3F3F)
        ImageIO.write(image, "png", out)
      }
      .toByteArray()
}
