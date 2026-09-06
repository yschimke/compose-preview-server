package ee.schimke.composeai.cli.serve

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ServeUiBuilderDesignLibraryTest {

  private val m3 =
    ServeUiBuilderDesignLibrary.Coordinate(
      system = "m3-catalog",
      source =
        ServeUiBuilderDesignLibrary.Source.Branch(
          repo = "yschimke/m3-catalog",
          branch = "design-artifacts/m3-catalog",
        ),
      generation = "abc123",
    )

  @Test
  fun `a project's index is read from the conventional path on its own branch`() {
    val asked = mutableListOf<String>()
    val library =
      library(asked) { url ->
        if (url.endsWith(ServeUiBuilderDesignLibrary.INDEX_PATH))
          index(entry("checkout", "Checkout"))
        else null
      }

    val entries = library.index(m3)

    assertEquals(listOf("checkout"), entries.map { it.designId })
    assertEquals("Checkout", entries.single().title)
    assertEquals("checkout.json", entries.single().file, "the file defaults to <id>.json")
    assertEquals(
      listOf(
        "https://raw.githubusercontent.com/yschimke/m3-catalog/design-artifacts/m3-catalog/ui-builder/designs/index.json"
      ),
      asked,
    )
  }

  @Test
  fun `a catalog that publishes nothing contributes nothing and is not an error`() {
    val library = library { null }

    assertEquals(emptyList(), library.index(m3))
  }

  @Test
  fun `a malformed index contributes nothing and says so once`() {
    val logged = mutableListOf<String>()
    val library = library(onLog = logged::add) { "{ not json".toByteArray() }

    assertEquals(emptyList(), library.index(m3))
    assertTrue(logged.any { "not readable" in it }, logged.toString())
  }

  @Test
  fun `an index of the wrong schema is refused rather than guessed at`() {
    val logged = mutableListOf<String>()
    val library =
      library(onLog = logged::add) {
        """{"schema":"something-else/v1","designs":[{"id":"checkout"}]}""".toByteArray()
      }

    assertEquals(emptyList(), library.index(m3))
    assertTrue(logged.any { "not readable" in it }, logged.toString())
  }

  @Test
  fun `an id that could not be a design id here is left out`() {
    val logged = mutableListOf<String>()
    val library =
      library(onLog = logged::add) { index(entry("../../etc/passwd", "Sneaky"), entry("ok", "Ok")) }

    assertEquals(listOf("ok"), library.index(m3).map { it.designId })
    assertTrue(logged.any { "unusable id" in it }, logged.toString())
  }

  @Test
  fun `a file that could climb out of the designs directory is left out`() {
    val logged = mutableListOf<String>()
    val library =
      library(onLog = logged::add) {
        """
        {"schema":"${ServeUiBuilderDesignLibrary.INDEX_SCHEMA}","designs":[
          {"id":"escape","file":"../../../catalog.json"},
          {"id":"ok","file":"ok.json"}
        ]}
        """
          .trimIndent()
          .toByteArray()
      }

    assertEquals(listOf("ok"), library.index(m3).map { it.designId })
    assertTrue(logged.any { "unusable file" in it }, logged.toString())
  }

  @Test
  fun `a duplicate id keeps the first and says so`() {
    val logged = mutableListOf<String>()
    val library =
      library(onLog = logged::add) {
        index(entry("checkout", "First"), entry("checkout", "Second"))
      }

    assertEquals(listOf("First"), library.index(m3).map { it.title })
    assertTrue(logged.any { "twice" in it }, logged.toString())
  }

  @Test
  fun `the index is cached until the catalog reloads`() {
    var reads = 0
    val library = library {
      reads++
      index(entry("checkout", "Checkout"))
    }

    library.index(m3)
    library.index(m3)
    assertEquals(1, reads, "a second read on the same generation must not reach the branch")

    library.index(m3.copy(generation = "def456"))
    assertEquals(2, reads, "a moved generation must be re-read")
  }

  @Test
  fun `the index is re-read once its time to live expires, even on a still catalog`() {
    var reads = 0
    var now = 0L
    val library =
      ServeUiBuilderDesignLibrary(
        fetch = { _, _ ->
          reads++
          index(entry("checkout", "Checkout"))
        },
        ttlMillis = 1_000,
        clock = { now },
      )

    library.index(m3)
    now = 999
    library.index(m3)
    assertEquals(1, reads)

    now = 1_000
    library.index(m3)
    assertEquals(2, reads)
  }

  @Test
  fun `a document is fetched from the file the index names`() {
    val asked = mutableListOf<String>()
    val library =
      library(asked) { url ->
        when {
          url.endsWith(ServeUiBuilderDesignLibrary.INDEX_PATH) ->
            """
          {"schema":"${ServeUiBuilderDesignLibrary.INDEX_SCHEMA}","designs":[
            {"id":"checkout","title":"Checkout","file":"checkout-screen.json"}
          ]}
          """
              .trimIndent()
              .toByteArray()
          url.endsWith("checkout-screen.json") -> DOCUMENT.toByteArray()
          else -> null
        }
      }

    val document = library.document(m3, "checkout")

    assertNotNull(document)
    assertEquals("checkout", document.id)
    assertTrue(
      asked.any { it.endsWith("ui-builder/designs/checkout-screen.json") },
      "the document is read from the file the index names, not from <id>.json: $asked",
    )
  }

  @Test
  fun `a design the index does not name is not fetched at all`() {
    val asked = mutableListOf<String>()
    val library =
      library(asked) { url ->
        if (url.endsWith(ServeUiBuilderDesignLibrary.INDEX_PATH))
          index(entry("checkout", "Checkout"))
        else DOCUMENT.toByteArray()
      }

    assertNull(library.document(m3, "not-published"))
    assertTrue(
      asked.none { it.endsWith("not-published.json") },
      "an unlisted id must not become a fetch: $asked",
    )
  }

  @Test
  fun `a published file that is not a document is refused rather than half-loaded`() {
    val logged = mutableListOf<String>()
    val library =
      library(onLog = logged::add) { url ->
        if (url.endsWith(ServeUiBuilderDesignLibrary.INDEX_PATH)) index(entry("checkout", "C"))
        else """{"schema":"compose-ui-builder-document/v1-candidate"}""".toByteArray()
      }

    assertNull(library.document(m3, "checkout"))
    assertTrue(logged.any { "not a DesignDocumentV1" in it }, logged.toString())
  }

  @Test
  fun `every catalog is asked, and one that answers nothing does not hide the others`() {
    val wear =
      ServeUiBuilderDesignLibrary.Coordinate(
        system = "wear-m3",
        source =
          ServeUiBuilderDesignLibrary.Source.Branch("yschimke/wear-m3", "design-artifacts/wear-m3"),
        generation = "z",
      )
    val library = library { url ->
      if ("wear" in url) null else index(entry("checkout", "Checkout"))
    }

    val entries = library.list(listOf(m3, wear))

    assertEquals(listOf("m3-catalog" to "checkout"), entries.map { it.system to it.designId })
  }

  @Test
  fun `a project's own checkout is read from disk, at the same two paths`() {
    val dir = createTempDirectory("designs").toFile()
    val designs = File(dir, ServeUiBuilderDesignLibrary.DESIGNS_DIR).apply { mkdirs() }
    File(designs, "index.json").writeText(String(index(entry("checkout", "Checkout"))))
    File(designs, "checkout.json").writeText(DOCUMENT)
    val library =
      ServeUiBuilderDesignLibrary(fetch = { _, _ -> error("must not reach the network") })
    val local =
      ServeUiBuilderDesignLibrary.Coordinate(
        system = "m3-catalog",
        source = ServeUiBuilderDesignLibrary.Source.Directory(dir),
      )

    assertEquals(listOf("checkout"), library.index(local).map { it.designId })
    assertEquals("checkout", library.document(local, "checkout")?.id)
  }

  @Test
  fun `a checkout is re-read every time, because that is the half that changes under you`() {
    val dir = createTempDirectory("designs").toFile()
    val designs = File(dir, ServeUiBuilderDesignLibrary.DESIGNS_DIR).apply { mkdirs() }
    val index = File(designs, "index.json")
    index.writeText(String(index(entry("first", "First"))))
    val library = ServeUiBuilderDesignLibrary(fetch = { _, _ -> null })
    val local =
      ServeUiBuilderDesignLibrary.Coordinate(
        system = "m3-catalog",
        source = ServeUiBuilderDesignLibrary.Source.Directory(dir),
      )

    assertEquals(listOf("first"), library.index(local).map { it.designId })

    // Somebody exports a second design out of the editor. It must appear now, not in five minutes.
    index.writeText(String(index(entry("first", "First"), entry("second", "Second"))))
    assertEquals(listOf("first", "second"), library.index(local).map { it.designId })
  }

  @Test
  fun `a checkout that has no designs directory is simply a project with none`() {
    val library = ServeUiBuilderDesignLibrary(fetch = { _, _ -> null })
    val local =
      ServeUiBuilderDesignLibrary.Coordinate(
        system = "m3-catalog",
        source =
          ServeUiBuilderDesignLibrary.Source.Directory(createTempDirectory("empty").toFile()),
      )

    assertEquals(emptyList(), library.index(local))
  }

  private fun library(
    asked: MutableList<String>? = null,
    onLog: (String) -> Unit = {},
    fetch: (String) -> ByteArray?,
  ) =
    ServeUiBuilderDesignLibrary(
      fetch = { url, _ ->
        asked?.add(url)
        fetch(url)
      },
      onLog = onLog,
    )

  private fun entry(id: String, title: String) = """{"id":"$id","title":"$title"}"""

  private fun index(vararg entries: String) =
    """{"schema":"${ServeUiBuilderDesignLibrary.INDEX_SCHEMA}","designs":[${entries.joinToString(",")}]}"""
      .toByteArray()

  private companion object {
    val DOCUMENT =
      """
      {
        "schema": "compose-ui-builder-document/v1-candidate",
        "id": "checkout",
        "title": "Checkout",
        "revision": 0,
        "catalogPin": {
          "systemId": "m3-catalog",
          "catalogRevision": "candidate",
          "capabilityDigest": "candidate",
          "nativeRuntimeId": "candidate"
        },
        "environment": {
          "widthDp": 412, "heightDp": 915, "density": 1.0, "theme": "dark",
          "dynamicColor": false, "locale": "en-US", "fontScale": 1.0,
          "layoutDirection": "ltr", "windowPosture": "flat", "browserZoomPercent": 100,
          "fixedTime": "2024-05-16T12:00:00Z", "animations": "settled", "networkAccess": false
        },
        "stateVariables": {},
        "roots": ["root"],
        "nodes": {
          "root": {
            "id": "root", "componentId": "m3/text",
            "properties": {"text": {"type": "string", "value": "Checkout"}},
            "modifiers": [], "slots": {}, "eventBindings": {}
          }
        }
      }
      """
        .trimIndent()
  }
}
