package ee.schimke.composeai.cli.serve

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import okio.Path.Companion.toOkioPath
import org.junit.jupiter.api.io.TempDir

/**
 * The editor as a per-instance pin (#1035): fetched by version, checked against its digest and its
 * server-API contract, cached, and written to `catalogs.json` only once all of that has held.
 */
class ServeUiBuilderEditorTest {

  @TempDir lateinit var tmp: File

  private val published = mutableMapOf<String, ByteArray>()
  private val fetched = mutableListOf<String>()

  private fun fetch(url: String) =
    (published[url] ?: throw IOException("GET $url answered HTTP 404")).also { fetched += url }
      .inputStream()

  private fun store() = ServeUiBuilderEditorStore(File(tmp, "editors"), ::fetch, onLog = {})

  private fun zip(entries: Map<String, String>): ByteArray {
    val bytes = ByteArrayOutputStream()
    ZipOutputStream(bytes).use { out ->
      for ((name, text) in entries) {
        out.putNextEntry(ZipEntry(name))
        out.write(text.toByteArray())
        out.closeEntry()
      }
    }
    return bytes.toByteArray()
  }

  private fun sha(bytes: ByteArray) =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

  private fun manifest(version: String, serverApi: Int = 1) =
    """{"schema":"compose-ui-builder-web/v1","version":"$version","serverApi":$serverApi}"""

  /** Publish an editor archive at its GitHub release URL and return a pin that matches it. */
  private fun release(
    version: String,
    entries: Map<String, String> =
      mapOf("index.html" to "<html>$version</html>", "ui-builder-web.json" to manifest(version)),
  ): ServeCatalogsConfig.EditorPin {
    val bytes = zip(entries)
    published[ServeUiBuilderEditor.releaseUrl(version)] = bytes
    return ServeCatalogsConfig.EditorPin(version = version, sha256 = sha(bytes))
  }

  @Test
  fun `a pinned editor is fetched once, unpacked and served from the cache`() {
    val pin = release("3.48.0")
    val first = assertIs<ServeUiBuilderEditorStore.Result.Ready>(store().resolve(pin))
    assertEquals("<html>3.48.0</html>", File(first.dir, "index.html").readText())
    assertEquals("3.48.0", first.manifest?.version)
    assertNull(first.warning)

    // A restart: a new store over the same cache. No second download.
    val second = assertIs<ServeUiBuilderEditorStore.Result.Ready>(store().resolve(pin))
    assertEquals(first.dir, second.dir)
    assertEquals(listOf(ServeUiBuilderEditor.releaseUrl("3.48.0")), fetched)
  }

  @Test
  fun `a digest that does not match is refused and nothing is cached`() {
    val pin = release("3.48.0").copy(sha256 = "0".repeat(64))
    val failed = assertIs<ServeUiBuilderEditorStore.Result.Failed>(store().resolve(pin))
    assertTrue("digest mismatch" in failed.reason, failed.reason)
    assertFalse(store().dirFor(pin).exists())
  }

  @Test
  fun `an editor speaking an unsupported server API is refused`() {
    val pin =
      release(
        "4.0.0",
        mapOf("index.html" to "<html/>", "ui-builder-web.json" to manifest("4.0.0", serverApi = 99)),
      )
    val failed = assertIs<ServeUiBuilderEditorStore.Result.Failed>(store().resolve(pin))
    assertTrue("server API 99" in failed.reason, failed.reason)
  }

  @Test
  fun `an archive naming a different version than the pin is refused`() {
    val pin =
      release(
        "3.48.0",
        mapOf("index.html" to "<html/>", "ui-builder-web.json" to manifest("3.47.0")),
      )
    val failed = assertIs<ServeUiBuilderEditorStore.Result.Failed>(store().resolve(pin))
    assertTrue("not the pinned 3.48.0" in failed.reason, failed.reason)
  }

  @Test
  fun `an editor predating the manifest is served with a warning`() {
    val pin = release("3.47.0", mapOf("index.html" to "<html/>"))
    val ready = assertIs<ServeUiBuilderEditorStore.Result.Ready>(store().resolve(pin))
    assertNull(ready.manifest)
    assertNotNull(ready.warning)
  }

  @Test
  fun `an archive with an escaping path is refused`() {
    val pin = release("3.48.0", mapOf("index.html" to "<html/>", "../evil" to "x"))
    val failed = assertIs<ServeUiBuilderEditorStore.Result.Failed>(store().resolve(pin))
    assertTrue("unsafe path" in failed.reason, failed.reason)
    assertFalse(File(tmp, "evil").exists())
  }

  @Test
  fun `an unreachable release is a failure, not an exception`() {
    val pin = ServeCatalogsConfig.EditorPin(version = "9.9.9", sha256 = "a".repeat(64))
    val failed = assertIs<ServeUiBuilderEditorStore.Result.Failed>(store().resolve(pin))
    assertTrue("404" in failed.reason, failed.reason)
  }

  @Test
  fun `a url override is fetched instead of the release`() {
    val bytes = zip(mapOf("index.html" to "<html/>", "ui-builder-web.json" to manifest("3.48.1")))
    published["https://mirror.example/editor.zip"] = bytes
    val pin =
      ServeCatalogsConfig.EditorPin("3.48.1", sha(bytes), url = "https://mirror.example/editor.zip")
    assertIs<ServeUiBuilderEditorStore.Result.Ready>(store().resolve(pin))
    assertEquals(listOf("https://mirror.example/editor.zip"), fetched)
  }

  @Test
  fun `prune keeps the serving editor and the most recent others`() {
    val s = store()
    val dirs =
      listOf("3.48.0", "3.48.1", "3.48.2", "3.48.3").map { v ->
        assertIs<ServeUiBuilderEditorStore.Result.Ready>(s.resolve(release(v))).dir.also {
          File(it, ServeUiBuilderEditorStore.COMPLETE_MARKER)
            .setLastModified(1_000_000L * (v.last() - '0' + 1))
        }
      }
    s.prune(keep = dirs[0], retain = 2)
    assertEquals(listOf(true, false, true, true), dirs.map { it.exists() })
  }

  // ── Config ─────────────────────────────────────────────────────────────────────────────────

  @Test
  fun `catalogs json round-trips an editor pin and validates it`() {
    val config =
      ServeCatalogsConfig.parse(
        """{ "editor": { "version": "3.48.0", "sha256": "${"a".repeat(64)}" }, "catalogs": [] }"""
      )
    assertEquals(ServeCatalogsConfig.EditorPin("3.48.0", "a".repeat(64)), config.editor)
    assertEquals(config, ServeCatalogsConfig.parse(ServeCatalogsConfig.encode(config)))
    assertEquals(emptyList(), config.problems())

    assertNull(ServeCatalogsConfig.parse("""{ "catalogs": [] }""").editor)
    assertTrue(
      ServeCatalogsConfig(editor = ServeCatalogsConfig.EditorPin("../x", "a".repeat(64)))
        .problems()
        .single()
        .startsWith("invalid editor version")
    )
    assertNotNull(
      ServeCatalogsConfig.validateEditor(ServeCatalogsConfig.EditorPin("3.48.0", "abc"))
    )
    assertNotNull(
      ServeCatalogsConfig.validateEditor(
        ServeCatalogsConfig.EditorPin("3.48.0", "a".repeat(64), url = "http://insecure/x.zip")
      )
    )
  }

  // ── Admin ──────────────────────────────────────────────────────────────────────────────────

  private val configFile by lazy {
    ServeCatalogsConfigFile(File(tmp, "catalogs.json").toOkioPath()).also {
      it.save(
        ServeCatalogsConfig(catalogs = listOf(ServeCatalogsConfig.Entry("m3-catalog", "a/b")))
      )
    }
  }

  private fun admin(servingPin: ServeCatalogsConfig.EditorPin? = null) =
    ServeUiBuilderEditorAdmin(
      store = store(),
      configFile = configFile,
      servingState = { ServeUiBuilderEditorState("3.47.0", servingPin, servingPin?.version) },
      onLog = {},
    )

  @Test
  fun `pinning verifies first, then writes the pin and owes a restart`() {
    val pin = release("3.48.0")
    val result = assertIs<ServeUiBuilderEditorAdmin.Result.Ok>(admin().set(pin))
    assertTrue(result.restartRequired)
    assertEquals(pin, configFile.load().editor)
    // The rest of the file is untouched, and the archive is already cached for the restart.
    assertEquals(listOf("m3-catalog"), configFile.load().catalogs.map { it.system })
    assertTrue(File(store().dirFor(pin), "index.html").isFile)
    // Re-posting the same pin is the reconcile's "already present".
    assertIs<ServeUiBuilderEditorAdmin.Result.Conflict>(admin().set(pin))
  }

  @Test
  fun `a pin that fails verification is never written`() {
    val bad = release("3.48.0").copy(sha256 = "b".repeat(64))
    assertIs<ServeUiBuilderEditorAdmin.Result.Invalid>(admin().set(bad))
    assertNull(configFile.load().editor)
  }

  @Test
  fun `clearing the pin rolls back to the bundled editor`() {
    val pin = release("3.48.0")
    admin().set(pin)
    val result = assertIs<ServeUiBuilderEditorAdmin.Result.Ok>(admin(servingPin = pin).clear())
    assertTrue(result.restartRequired)
    assertNull(configFile.load().editor)
    assertIs<ServeUiBuilderEditorAdmin.Result.Conflict>(admin().clear())
  }

  @Test
  fun `pinning without a config file is refused`() {
    val admin =
      ServeUiBuilderEditorAdmin(
        store(),
        configFile = null,
        servingState = { ServeUiBuilderEditorState(null, null, null) },
        onLog = {},
      )
    assertIs<ServeUiBuilderEditorAdmin.Result.Unavailable>(admin.set(release("3.48.0")))
  }
}
