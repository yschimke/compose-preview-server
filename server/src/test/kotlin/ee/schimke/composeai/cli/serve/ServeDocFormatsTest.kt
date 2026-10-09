package ee.schimke.composeai.cli.serve

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Content-sniffing + summarising of the known document formats the serve host ingests.
 *
 * The sniff is the actual security boundary of the document lane: an upload that isn't a known
 * document must be refused rather than parked on the host's origin, so the "rejects" cases below
 * matter as much as the happy paths.
 */
class ServeDocFormatsTest {

  /** The vendored Lottie player is the light build: SVG only, without the expression engine. */
  @Test
  fun `the vendored Lottie player is the light build`() {
    val player =
      checkNotNull(javaClass.getResourceAsStream(ServeDocFormats.LOTTIE.playerResource)) {
          "missing ${ServeDocFormats.LOTTIE.playerResource}"
        }
        .use { it.readBytes().decodeToString() }
    assertTrue(player.contains("loadAnimation"), "not a Lottie player")
    assertTrue("eval(" !in player, "expected the light Lottie build")
    assertTrue("expression_function" !in player, "expected the light Lottie build")
  }

  @Test
  fun `a Lottie document that uses expressions is refused with a reason`() {
    val withExpression =
      """
      {"v":"5.7.4","fr":30,"ip":0,"op":60,"w":100,"h":100,
       "layers":[{"ind":1,"ty":4,"ks":{"o":{"a":0,"k":100,"x":"var ${'$'}bm_rt = 50;"}}}]}
      """
        .trimIndent()
        .toByteArray()
    val easingOnly =
      """
      {"v":"5.7.4","fr":30,"ip":0,"op":60,"w":100,"h":100,
       "layers":[{"ind":1,"ty":4,"ks":{"r":{"a":1,"k":[
         {"t":0,"s":[0],"i":{"x":[0.5],"y":[0.5]},"o":{"x":[0.5],"y":[0.5]}},{"t":60,"s":[360]}]}}}]}
      """
        .trimIndent()
        .toByteArray()

    assertEquals(ServeDocFormats.LOTTIE, ServeDocFormats.detect(withExpression))
    assertTrue(ServeDocFormats.LOTTIE.unsupported(withExpression).orEmpty().contains("expressions"))
    assertNull(ServeDocFormats.LOTTIE.unsupported(easingOnly))
    assertNull(ServeDocFormats.LOTTIE.unsupported(ServeDocFixtures.lottieDoc()))
  }

  @Test
  fun `remote compose document is detected and summarised from its header`() {
    val doc =
      ServeDocFixtures.remoteComposeDoc(major = 1, minor = 2, patch = 3, width = 480, height = 240)

    assertEquals(ServeDocFormats.REMOTE_COMPOSE, ServeDocFormats.detect(doc))
    assertEquals(ServeDocSize(480, 240), ServeDocFormats.REMOTE_COMPOSE.size(doc))
    val facts = ServeDocFormats.REMOTE_COMPOSE.describe(doc)
    assertEquals("1.2.3", facts.first { it.key == "Format version" }.value)
    assertEquals("480 × 240", facts.first { it.key == "Document size" }.value)
  }

  @Test
  fun `a truncated remote compose header still yields what it could read`() {
    val full = ServeDocFixtures.remoteComposeDoc(width = 100, height = 100)
    val truncated = full.copyOf(16)

    // Still recognisably an RC document (the magic is intact) — the walk just stops early, so the
    // page shows the version and no size rather than failing the whole upload.
    assertEquals(ServeDocFormats.REMOTE_COMPOSE, ServeDocFormats.detect(truncated))
    assertNull(ServeDocFormats.REMOTE_COMPOSE.size(truncated))
    assertTrue(
      ServeDocFormats.REMOTE_COMPOSE.describe(truncated).any { it.key == "Format version" }
    )
  }

  @Test
  fun `untagged AndroidX remote compose header is detected and summarised`() {
    // The leading bytes of rc-players' compat-test fixture `androidx-layout.rc`, written by the
    // AndroidX writer: Header opcode, major 1 with no magic in its high half, minor 1, patch 0,
    // width 320, height 180, capabilities 0 — then the first operation (0x65) of the body.
    val doc = ServeDocFixtures.androidxUntaggedRemoteComposePrefix()

    assertEquals(ServeDocFormats.REMOTE_COMPOSE, ServeDocFormats.detect(doc))
    assertEquals(ServeDocSize(320, 180), ServeDocFormats.REMOTE_COMPOSE.size(doc))
    val facts = ServeDocFormats.REMOTE_COMPOSE.describe(doc).associate { it.key to it.value }
    assertEquals("1.1.0", facts["Format version"])
    assertEquals("320 × 180", facts["Document size"])
  }

  @Test
  fun `implausible or short untagged headers are refused`() {
    val doc = ServeDocFixtures.androidxUntaggedRemoteComposePrefix()

    // Missing the fixed width/height/capabilities tail: no magic, so the whole layout is required.
    assertNull(ServeDocFormats.detect(doc.copyOf(28)))
    // Major version 0 — no writer emits it, and it is what a zero-filled buffer looks like.
    assertNull(ServeDocFormats.detect(doc.copyOf().also { it[4] = 0 }))
    // A zero declared width or height.
    assertNull(ServeDocFormats.detect(doc.copyOf().also { it.fill(0, 13, 17) }))
    assertNull(ServeDocFormats.detect(doc.copyOf().also { it[20] = 0 }))
    // A high half that is neither zero nor the magic.
    assertNull(ServeDocFormats.detect(doc.copyOf().also { it[2] = 0x12 }))
    assertNull(ServeDocFormats.detect(doc.copyOf().also { it[1] = 0xFF.toByte() }))
    // A non-Header first opcode.
    assertNull(ServeDocFormats.detect(doc.copyOf().also { it[0] = 0x65 }))
  }

  @Test
  fun `random and short buffers are not remote compose`() {
    val random = Random(0x5EED)
    repeat(2_000) {
      val bytes = random.nextBytes(random.nextInt(0, 128))
      assertNull(ServeDocFormats.detect(bytes), "accepted random bytes ${bytes.toHex()}")
    }
    val tagged = ServeDocFixtures.remoteComposeDoc()
    for (length in 0 until 13) {
      assertNull(ServeDocFormats.detect(tagged.copyOf(length)), "accepted a $length-byte prefix")
    }
  }

  @Test
  fun `lottie animation is detected and summarised`() {
    val doc = ServeDocFixtures.lottieDoc()

    assertEquals(ServeDocFormats.LOTTIE, ServeDocFormats.detect(doc))
    assertEquals(ServeDocSize(200, 100), ServeDocFormats.LOTTIE.size(doc))
    val facts = ServeDocFormats.LOTTIE.describe(doc).associate { it.key to it.value }
    assertEquals("Spinner", facts["Name"])
    assertEquals("5.7.4", facts["Bodymovin version"])
    assertEquals("200 × 100", facts["Size"])
    assertEquals("60 @ 30 fps", facts["Frames"])
    assertEquals("2s", facts["Duration"])
    assertEquals("2", facts["Layers"])
  }

  @Test
  fun `json that is not an animation is not a document`() {
    // A `layers`-carrying object without the frame-rate / in-out trio isn't a playable animation.
    val notAnimation = """{"layers":[],"hello":"world"}""".toByteArray()
    assertNull(ServeDocFormats.detect(notAnimation))
    assertNull(ServeDocFormats.detect("""{"v":"5.7.4"}""".toByteArray()))
    assertNull(ServeDocFormats.detect("not json at all".toByteArray()))
  }

  @Test
  fun `binary uploads that are not documents are refused`() {
    val zip =
      ByteArrayOutputStream()
        .also { out ->
          ZipOutputStream(out).use {
            it.putNextEntry(ZipEntry("previews/a.png"))
            it.write(byteArrayOf(1, 2, 3))
            it.closeEntry()
          }
        }
        .toByteArray()
    val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte())
    val html = "<html><script>alert(1)</script></html>".toByteArray()

    assertNull(ServeDocFormats.detect(zip))
    assertNull(ServeDocFormats.detect(png))
    assertNull(ServeDocFormats.detect(html))
    assertNull(ServeDocFormats.detect(ByteArray(0)))
    // A zero-filled file opens with the Header opcode and an untagged-looking major word, but
    // declares major 0 and a 0 × 0 size: it must not pass as Remote Compose.
    assertNull(ServeDocFormats.detect(ByteArray(64)))
  }

  @Test
  fun `every format resolves by id and mounts its player under a distinct path`() {
    for (format in ServeDocFormats.ALL) {
      assertEquals(format, ServeDocFormats.byId(format.id))
      assertEquals("/doc-player/${format.id}/bundle.js", format.playerPath)
    }
    assertNull(ServeDocFormats.byId("../../etc/passwd"))
    assertEquals(
      ServeDocFormats.ALL.size,
      ServeDocFormats.ALL.map { it.playerPath }.distinct().size,
      "each format serves its own player path",
    )
  }
}

/** Shared document fixtures for the store / routing tests. */
object ServeDocFixtures {

  /**
   * A minimal Remote Compose document: the `Header` operation (opcode 0, `magic|major`, minor,
   * patch) followed by a two-entry property table carrying `DOC_WIDTH` / `DOC_HEIGHT`.
   */
  fun remoteComposeDoc(
    major: Int = 1,
    minor: Int = 0,
    patch: Int = 0,
    width: Int = 256,
    height: Int = 256,
  ): ByteArray {
    val out = ByteArrayOutputStream()
    fun int(value: Int) {
      out.write((value ushr 24) and 0xFF)
      out.write((value ushr 16) and 0xFF)
      out.write((value ushr 8) and 0xFF)
      out.write(value and 0xFF)
    }
    fun short(value: Int) {
      out.write((value ushr 8) and 0xFF)
      out.write(value and 0xFF)
    }
    out.write(0) // Header OP_CODE
    int((0x048C shl 16) or major)
    int(minor)
    int(patch)
    int(2) // property count
    short(5) // DOC_WIDTH, DATA_TYPE_INT
    short(4)
    int(width)
    short(6) // DOC_HEIGHT, DATA_TYPE_INT
    short(4)
    int(height)
    return out.toByteArray()
  }

  /**
   * The first 32 bytes of rc-players' `androidx-layout.rc` compat fixture (built by
   * `:rc-player-compat-tests` from the AndroidX writer): an untagged `Header` — opcode 0, major 1,
   * minor 1, patch 0, width 320, height 180, capabilities 0 — and the start of the first body
   * operation.
   */
  fun androidxUntaggedRemoteComposePrefix(): ByteArray =
    intArrayOf(
        0x00,
        0x00,
        0x00,
        0x00,
        0x01,
        0x00,
        0x00,
        0x00,
        0x01,
        0x00,
        0x00,
        0x00,
        0x00,
        0x00,
        0x00,
        0x01,
        0x40,
        0x00,
        0x00,
        0x00,
        0xb4,
        0x00,
        0x00,
        0x00,
        0x00,
        0x00,
        0x00,
        0x00,
        0x00,
        0x65,
        0x00,
        0x00,
      )
      .map { it.toByte() }
      .toByteArray()

  /** A minimal but shape-complete Lottie (Bodymovin) animation. */
  fun lottieDoc(name: String = "Spinner"): ByteArray =
    """
    {"v":"5.7.4","nm":"$name","fr":30,"ip":0,"op":60,"w":200,"h":100,
     "layers":[{"ind":1,"ty":4,"nm":"a"},{"ind":2,"ty":4,"nm":"b"}]}
    """
      .trimIndent()
      .toByteArray()
}

private fun ByteArray.toHex(): String = joinToString(" ") { "%02x".format(it) }
