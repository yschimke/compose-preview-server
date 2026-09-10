package ee.schimke.composeai.cli.serve

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A project's shared components, read the way its designs already are.
 *
 * The claims worth pinning are the two honesty rules — a symbol carries a content digest so an
 * importing design can be told its library moved, and a symbol may only use its catalog's
 * components — plus the refusals that keep one unusable file from taking the rest with it.
 */
class ServeUiBuilderComponentLibraryTest {

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
  fun `a project's components are read from the conventional path on its own branch`() {
    val asked = mutableListOf<String>()
    val library =
      library(asked) { url ->
        if (url.endsWith(ServeUiBuilderComponentLibrary.INDEX_PATH))
          index(entry("contribution-cell", "Contribution cell"))
        else null
      }

    val entries = library.index(m3)

    assertEquals(listOf("contribution-cell"), entries.map { it.componentId })
    assertEquals("Contribution cell", entries.single().title)
    assertEquals("contribution-cell.json", entries.single().file, "the file defaults to <id>.json")
    assertEquals(
      listOf(
        "https://raw.githubusercontent.com/yschimke/m3-catalog/design-artifacts/m3-catalog/" +
          "ui-builder/components/index.json"
      ),
      asked,
    )
  }

  @Test
  fun `a project that publishes no components contributes nothing and is not an error`() {
    assertEquals(emptyList(), library { null }.index(m3))
  }

  @Test
  fun `a malformed index contributes nothing and says so once`() {
    val logged = mutableListOf<String>()
    val library = library(onLog = logged::add) { "{ not json".toByteArray() }

    assertEquals(emptyList(), library.index(m3))
    assertTrue(logged.any { "not readable" in it }, logged.toString())
  }

  @Test
  fun `a symbol file is read as a design document and its body resolved`() {
    val library = library { url -> if (url.endsWith("index.json")) index(entry()) else symbol() }

    val symbol = assertNotNull(library.symbol(m3, "contribution-cell"))

    assertEquals("Contribution cell", symbol.component.name)
    assertEquals(setOf("cell", "cell-label"), symbol.nodes.keys)
    assertEquals("m3-catalog", symbol.catalogPin.systemId)
    assertTrue(symbol.digest.startsWith("sha256:"), symbol.digest)
  }

  /**
   * The body is the subtree the component names, not the file.
   *
   * A symbol file may carry a frame or a swatch around the thing it publishes; an importing design
   * copies in the component's own subtree and nothing else.
   */
  @Test
  fun `a node the body does not reach is not part of the symbol`() {
    val library = library { url ->
      if (url.endsWith("index.json")) index(entry())
      else symbol(extraNodes = """, "loose": {"id": "loose", "componentId": "m3/text"}""")
    }

    val symbol = assertNotNull(library.symbol(m3, "contribution-cell"))

    assertEquals(setOf("cell", "cell-label"), symbol.nodes.keys)
  }

  @Test
  fun `the digest ignores key order and formatting but not content`() {
    val plain =
      assertNotNull(
        library { url -> if (url.endsWith("index.json")) index(entry()) else symbol() }
          .symbol(m3, "contribution-cell")
      )
    val reordered =
      assertNotNull(
        library { url ->
            if (url.endsWith("index.json")) index(entry()) else symbol(reorderedKeys = true)
          }
          .symbol(m3, "contribution-cell")
      )
    val edited =
      assertNotNull(
        library { url ->
            if (url.endsWith("index.json")) index(entry()) else symbol(label = "Changed")
          }
          .symbol(m3, "contribution-cell")
      )

    assertEquals(plain.digest, reordered.digest, "reformatting a file is not drift")
    assertNotEquals(plain.digest, edited.digest, "editing a property is")
  }

  /**
   * The second honest rule: a published symbol uses only its catalog's components.
   *
   * A symbol placing another symbol is a dependency the importing design never named, and there is
   * no import graph to name it in yet, so it is refused rather than half-supported.
   */
  @Test
  fun `a symbol whose body places another component is refused by name`() {
    val logged = mutableListOf<String>()
    val library =
      library(onLog = logged::add) { url ->
        if (url.endsWith("index.json")) index(entry())
        else
          symbol(
            childId = "inner",
            extraNodes =
              """, "inner": {"id": "inner", "componentId": "design/component-instance",
                 "component": {"componentKey": "other"}}""",
          )
      }

    assertNull(library.symbol(m3, "contribution-cell"))
    assertTrue(logged.any { "places another component" in it }, logged.toString())
  }

  @Test
  fun `a file declaring no component, or two, is refused`() {
    val none = mutableListOf<String>()
    assertNull(
      library(onLog = none::add) { url ->
          if (url.endsWith("index.json")) index(entry()) else symbol(components = "{}")
        }
        .symbol(m3, "contribution-cell")
    )
    assertTrue(none.any { "declares 0 components" in it }, none.toString())

    val two = mutableListOf<String>()
    assertNull(
      library(onLog = two::add) { url ->
          if (url.endsWith("index.json")) index(entry())
          else
            symbol(
              components =
                """{"contribution-cell": {"name": "Contribution cell", "root": "cell"},
                  "other": {"name": "Other", "root": "cell"}}"""
            )
        }
        .symbol(m3, "contribution-cell")
    )
    assertTrue(two.any { "declares 2 components" in it }, two.toString())
  }

  /**
   * The index's id and the file's id have to agree.
   *
   * A design records a reference by id; a stale export that publishes an entry for one id whose
   * file declares another would let that reference resolve to content the designer was never shown.
   */
  @Test
  fun `an entry whose file declares a different id is refused`() {
    val logged = mutableListOf<String>()
    val library =
      library(onLog = logged::add) { url ->
        if (url.endsWith("index.json")) index(entry())
        else symbol(components = """{"renamed": {"name": "Contribution cell", "root": "cell"}}""")
      }

    assertNull(library.symbol(m3, "contribution-cell"))
    assertTrue(logged.any { "declares `renamed`" in it }, logged.toString())
  }

  @Test
  fun `a body root or child the file does not carry is refused`() {
    val noRoot = mutableListOf<String>()
    assertNull(
      library(onLog = noRoot::add) { url ->
          if (url.endsWith("index.json")) index(entry())
          else symbol(components = """{"contribution-cell": {"name": "Cell", "root": "absent"}}""")
        }
        .symbol(m3, "contribution-cell")
    )
    assertTrue(noRoot.any { "does not carry" in it }, noRoot.toString())

    val danglingChild = mutableListOf<String>()
    assertNull(
      library(onLog = danglingChild::add) { url ->
          if (url.endsWith("index.json")) index(entry()) else symbol(childId = "absent")
        }
        .symbol(m3, "contribution-cell")
    )
    assertTrue(danglingChild.any { "absent" in it }, danglingChild.toString())
  }

  @Test
  fun `an unusable id or file is dropped and the components either side of it are kept`() {
    val logged = mutableListOf<String>()
    val library =
      library(onLog = logged::add) {
        index(
          entry("first", "First"),
          """{"id":"../escape","title":"Climbing out"}""",
          """{"id":"traversal","file":"../secrets.json"}""",
          entry("last", "Last"),
        )
      }

    assertEquals(listOf("first", "last"), library.index(m3).map { it.componentId })
    assertTrue(logged.any { "unusable id" in it }, logged.toString())
    assertTrue(logged.any { "unusable file" in it }, logged.toString())
  }

  @Test
  fun `a local directory is read without caching, so an export shows up on the next read`() {
    val dir = createTempDirectory("components").toFile()
    val components = File(dir, ServeUiBuilderComponentLibrary.COMPONENTS_DIR).apply { mkdirs() }
    val local =
      ServeUiBuilderDesignLibrary.Coordinate(
        system = "local",
        source = ServeUiBuilderDesignLibrary.Source.Directory(dir),
      )
    val library = library { error("a directory source never fetches") }

    assertEquals(emptyList(), library.index(local), "no index yet is an ordinary answer")

    File(components, "index.json").writeBytes(index(entry()))
    assertEquals(listOf("contribution-cell"), library.index(local).map { it.componentId })
  }

  @Test
  fun `a palette id names the project the symbol came from`() {
    assertEquals(
      "project/contribution-cell",
      ServeUiBuilderComponentLibrary.paletteId("contribution-cell"),
    )
  }

  private fun library(
    asked: MutableList<String>? = null,
    onLog: (String) -> Unit = {},
    fetch: (String) -> ByteArray?,
  ) =
    ServeUiBuilderComponentLibrary(
      fetch = { url, _ ->
        asked?.add(url)
        fetch(url)
      },
      onLog = onLog,
    )

  private fun entry(id: String = "contribution-cell", title: String = "Contribution cell") =
    """{"id":"$id","title":"$title"}"""

  private fun index(vararg entries: String) =
    """{"schema":"${ServeUiBuilderComponentLibrary.INDEX_SCHEMA}","components":[${entries.joinToString(",")}]}"""
      .toByteArray()

  /** One symbol file: a design document declaring exactly one component. */
  private fun symbol(
    components: String = """{"contribution-cell": {"name": "Contribution cell", "root": "cell"}}""",
    childId: String = "cell-label",
    label: String = "Cell",
    extraNodes: String = "",
    reorderedKeys: Boolean = false,
  ): ByteArray {
    val labelNode =
      if (childId != "cell-label") ""
      else
        """,
        "cell-label": {
          "id": "cell-label", "componentId": "m3/text",
          "properties": {"text": {"type": "string", "value": "$label"}},
          "modifiers": [], "slots": {}, "eventBindings": {}
        }"""
    val cell =
      if (reorderedKeys)
        """"cell": {
          "eventBindings": {}, "slots": {"content": ["$childId"]}, "modifiers": [],
          "properties": {}, "componentId": "m3/card", "id": "cell"
        }"""
      else
        """"cell": {
          "id": "cell", "componentId": "m3/card",
          "properties": {}, "modifiers": [], "slots": {"content": ["$childId"]},
          "eventBindings": {}
        }"""
    return """
      {
        "schema": "compose-ui-builder-document/v1-candidate",
        "id": "contribution-cell",
        "title": "Contribution cell",
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
        "roots": ["cell"],
        "nodes": {$cell$labelNode$extraNodes},
        "components": $components
      }
      """
      .trimIndent()
      .toByteArray()
  }
}
