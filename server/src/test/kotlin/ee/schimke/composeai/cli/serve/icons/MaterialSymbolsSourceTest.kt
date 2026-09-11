package ee.schimke.composeai.cli.serve.icons

import java.io.File
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * Pins the one property that makes fetching a font at runtime acceptable: the bytes are checked.
 *
 * The design keeps 35 MB of variable fonts out of git and out of the distribution by fetching them
 * once into a cache. That is only safe if a wrong file cannot be used — an upstream redraw, a proxy
 * serving an error page, a truncated transfer — because the alternative is every icon in every
 * design quietly changing shape, on a host nobody is watching.
 */
class MaterialSymbolsSourceTest {

  private val fontBytes: ByteArray =
    checkNotNull(javaClass.getResourceAsStream("/material-symbols/outlined-subset.ttf")).use {
      it.readBytes()
    }

  private val codePointBytes: ByteArray =
    checkNotNull(javaClass.getResourceAsStream("/material-symbols/outlined-subset.codepoints"))
      .use { it.readBytes() }

  private fun sha256(bytes: ByteArray) =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

  private val style =
    MaterialSymbolsStyle(
      id = "outlined",
      fontUrl = "https://example.invalid/font.ttf",
      fontDigest = sha256(fontBytes),
      fontBytes = fontBytes.size,
    )

  private fun source(
    directory: File,
    style: MaterialSymbolsStyle = this.style,
    onFetch: (String) -> Unit = {},
  ): MaterialSymbolsSource {
    val served =
      mapOf(
        style.fontUrl to fontBytes,
        MaterialSymbolsSource.CODE_POINTS_URL to codePointBytes,
      )
    return MaterialSymbolsSource(
      cacheDirectory = directory,
      styles = listOf(style),
      codePointsUrl = MaterialSymbolsSource.CODE_POINTS_URL,
      codePointsDigest = sha256(codePointBytes),
      codePointsBytes = codePointBytes.size,
    ) { url, _ ->
      onFetch(url)
      served[url] ?: error("nothing pinned at $url")
    }
  }

  @Test
  fun `fetches once, then reads the cache`(@TempDir temp: File) {
    val fetched = mutableListOf<String>()
    val first = source(temp, onFetch = { fetched += it })
    assertNotNull(first.catalog("outlined")?.pathData("search"))
    assertEquals(2, fetched.size, "expected the font and the code points, fetched: $fetched")

    // A second source over the same directory is a second run of the host, not a second call on a
    // warm object: it must find the files rather than reach for the network again.
    val again = mutableListOf<String>()
    val second = source(temp, onFetch = { again += it })
    assertNotNull(second.catalog("outlined")?.pathData("search"))
    assertTrue(again.isEmpty(), "a warm cache should not fetch, but fetched: $again")
  }

  @Test
  fun `refuses bytes whose digest does not match the pin`(@TempDir temp: File) {
    val wrong = style.copy(fontDigest = sha256("not the font".encodeToByteArray()))
    val failure =
      assertFailsWith<IllegalStateException> { source(temp, style = wrong).catalog("outlined") }
    assertTrue(
      failure.message.orEmpty().contains("refusing to use it"),
      "unhelpful message: ${failure.message}",
    )
    assertTrue(
      File(temp, "outlined.ttf").let { !it.isFile },
      "a file that failed its digest must not be left in the cache",
    )
  }

  @Test
  fun `replaces a cache entry that no longer matches its pin`(@TempDir temp: File) {
    val cached = File(temp, "outlined.ttf")
    temp.mkdirs()
    cached.writeBytes("stale".encodeToByteArray())
    val fetched = mutableListOf<String>()
    assertNotNull(source(temp, onFetch = { fetched += it }).catalog("outlined")?.pathData("search"))
    assertTrue(fetched.contains(style.fontUrl), "a stale entry should be refetched")
    assertEquals(sha256(fontBytes), sha256(cached.readBytes()))
  }

  @Test
  fun `leaves no partial file behind`(@TempDir temp: File) {
    assertNotNull(source(temp).catalog("outlined"))
    val partials = temp.listFiles().orEmpty().filter { it.name.endsWith(".part") }
    assertTrue(partials.isEmpty(), "left partial files: $partials")
  }

  @Test
  fun `names need the code point list, not the font`(@TempDir temp: File) {
    val fetched = mutableListOf<String>()
    val names = source(temp, onFetch = { fetched += it }).names("outlined")
    assertEquals(16, names?.size)
    assertEquals(
      listOf(MaterialSymbolsSource.CODE_POINTS_URL),
      fetched,
      "the picker opens on this call; it must not pull a 10 MB face to list names",
    )
  }

  @Test
  fun `a cache entry another writer published first is accepted`(@TempDir temp: File) {
    // Two hosts sharing a cold directory: the one that loses the rename must not fail, because the
    // bytes are content-addressed and the destination is already correct.
    temp.mkdirs()
    File(temp, "outlined.ttf").writeBytes(fontBytes)
    File(temp, "symbols.codepoints").writeBytes(codePointBytes)
    val fetched = mutableListOf<String>()
    assertNotNull(source(temp, onFetch = { fetched += it }).catalog("outlined")?.pathData("search"))
    assertTrue(fetched.isEmpty(), "both files were already published, yet it fetched: $fetched")
  }

  @Test
  fun `an unknown style has no names`(@TempDir temp: File) {
    assertNull(source(temp).names("engraved"))
  }

  @Test
  fun `an unknown style is absent rather than an error`(@TempDir temp: File) {
    assertNull(source(temp).catalog("engraved"))
    assertEquals(listOf("outlined"), source(temp).styleIds)
  }

  @Test
  fun `refuses a body that is not the size the pin says`(@TempDir temp: File) {
    // The digest would catch this too, but only after the bytes are in the heap; the size is the
    // half of the check that can be made before reading, and an injected fetcher that ignores the
    // bound must still not get its bytes used.
    val longer = fontBytes + "extra".encodeToByteArray()
    val style = this.style.copy(fontDigest = sha256(longer))
    val failure =
      assertFailsWith<IllegalStateException> {
        MaterialSymbolsSource(
            cacheDirectory = temp,
            styles = listOf(style),
            codePointsUrl = MaterialSymbolsSource.CODE_POINTS_URL,
            codePointsDigest = sha256(codePointBytes),
            codePointsBytes = codePointBytes.size,
          ) { _, _ ->
            longer
          }
          .catalog("outlined")
      }
    assertTrue(
      failure.message.orEmpty().contains("expected ${style.fontBytes}"),
      "unhelpful message: ${failure.message}",
    )
  }

  @Test
  fun `reads no more than the expected size`() {
    val endless =
      object : java.io.InputStream() {
        var produced = 0

        override fun read(): Int {
          produced++
          return 0
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
          produced += len
          return len
        }
      }
    val read = MaterialSymbolsSource.readAtMost(endless, 1_024)
    // One byte past the limit, so the caller can tell "exactly this much" from "more than this",
    // and not a byte further however long the host keeps talking.
    assertEquals(1_025, read.size)
    assertEquals(1_025, endless.produced)
  }

  @Test
  fun `a short body is returned as it arrived, for the digest to reject`() {
    val read = MaterialSymbolsSource.readAtMost("half".byteInputStream(), 1_024)
    assertEquals(4, read.size)
  }

  @Test
  fun `the shipped pins name three faces and one code point list`() {
    val pinned = MaterialSymbolsSource.STYLES
    assertEquals(listOf("outlined", "rounded", "sharp"), pinned.map { it.id })
    pinned.forEach {
      assertEquals(64, it.fontDigest.length, "${it.id} digest should be a SHA-256")
      assertTrue(it.fontBytes > 8_000_000, "${it.id} is a whole variable font")
      assertTrue(it.fontUrl.startsWith("https://"), "${it.id} must be fetched over TLS")
    }
    assertEquals(64, MaterialSymbolsSource.CODE_POINTS_DIGEST.length)
    assertEquals(79_029, MaterialSymbolsSource.CODE_POINTS_BYTES)
    // Every URL names an immutable commit. A branch here is a time bomb: it keeps working until
    // upstream pushes, and then no cold host can ever fetch the pinned bytes again.
    (pinned.map { it.fontUrl } + MaterialSymbolsSource.CODE_POINTS_URL).forEach {
      assertTrue(
        Regex("https://raw\\.githubusercontent\\.com/google/material-design-icons/[0-9a-f]{40}/")
          .containsMatchIn(it),
        "not pinned to a commit: $it",
      )
    }
  }
}
