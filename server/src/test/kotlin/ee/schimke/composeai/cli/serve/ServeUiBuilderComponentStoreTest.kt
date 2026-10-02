package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.uibuilder.protocol.ComponentSourceV1
import ee.schimke.composeai.uibuilder.protocol.DESIGN_COMPONENT_INSTANCE_COMPONENT_ID
import ee.schimke.composeai.uibuilder.protocol.DesignComponentInstanceV1
import ee.schimke.composeai.uibuilder.protocol.DesignComponentV1
import ee.schimke.composeai.uibuilder.protocol.DesignDocumentV1
import ee.schimke.composeai.uibuilder.protocol.DesignNodeV1
import java.io.File
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.io.TempDir

/**
 * Publishing a component to the host's half of the library, and reading it back through the same
 * reader a project's committed library goes through.
 */
class ServeUiBuilderComponentStoreTest {
  @TempDir lateinit var root: Path

  private val library = ServeUiBuilderComponentLibrary(fetch = { _, _ -> null })

  @Test
  fun `a published component is read back by the library reader with the digest it was given`() {
    val store = ServeUiBuilderComponentStore(root)

    val published =
      assertIs<ServeUiBuilderComponentStore.PublishResult.Published>(
        store.publish(SYSTEM, "inbox-email", "Inbox email", "One row", symbol(), null)
      )

    assertTrue(published.created)
    val coordinate = store.coordinates().single()
    assertEquals(SYSTEM, coordinate.system)
    val entry = library.index(coordinate).single()
    assertEquals("inbox-email", entry.componentId)
    assertEquals("Inbox email", entry.title)
    assertEquals("One row", entry.description)
    val read = library.symbol(coordinate, entry)!!
    assertEquals(published.symbol.digest, read.digest)
    assertEquals(setOf("row", "sender"), read.nodes.keys)
    // The file is the one a project would commit: this is what moving it into git copies.
    val file = root.resolve(SYSTEM).resolve(ServeUiBuilderComponentLibrary.COMPONENTS_DIR).toFile()
    assertEquals(setOf("index.json", "inbox-email.json"), file.list()!!.toSet())
  }

  @Test
  fun `the design's own provenance is not published with it`() {
    val store = ServeUiBuilderComponentStore(root)
    val imported =
      symbol().let { document ->
        document.copy(
          components =
            document.components.mapValues { (_, component) ->
              component.copy(source = ComponentSourceV1(SYSTEM, "inbox-email", "sha256:old"))
            }
        )
      }

    val published =
      assertIs<ServeUiBuilderComponentStore.PublishResult.Published>(
        store.publish(SYSTEM, "inbox-email", "Inbox email", null, imported, null)
      )

    assertNull(published.symbol.component.source)
    val plain =
      assertIs<ServeUiBuilderComponentStore.PublishResult.Published>(
        ServeUiBuilderComponentStore(root.resolve("other"))
          .publish(SYSTEM, "inbox-email", "Inbox email", null, symbol(), null)
      )
    assertEquals(plain.symbol.digest, published.symbol.digest)
  }

  @Test
  fun `creating over a published id is a conflict naming the version it holds`() {
    val store = ServeUiBuilderComponentStore(root)
    val first =
      assertIs<ServeUiBuilderComponentStore.PublishResult.Published>(
        store.publish(SYSTEM, "inbox-email", "Inbox email", null, symbol(), null)
      )

    val again =
      assertIs<ServeUiBuilderComponentStore.PublishResult.Conflict>(
        store.publish(SYSTEM, "inbox-email", "Inbox email", null, symbol("Changed"), null)
      )

    assertEquals(first.symbol.digest, again.currentDigest)
  }

  @Test
  fun `an update names the version it replaces, and a stale one is refused`() {
    val store = ServeUiBuilderComponentStore(root)
    val first =
      assertIs<ServeUiBuilderComponentStore.PublishResult.Published>(
        store.publish(SYSTEM, "inbox-email", "Inbox email", null, symbol(), null)
      )

    val second =
      assertIs<ServeUiBuilderComponentStore.PublishResult.Published>(
        store.publish(
          SYSTEM,
          "inbox-email",
          "Inbox email",
          null,
          symbol("Changed"),
          first.symbol.digest,
        )
      )
    assertEquals(false, second.created)
    assertNotEquals(first.symbol.digest, second.symbol.digest)
    assertEquals(1, library.index(store.coordinate(SYSTEM)).size)

    // Somebody else's design still holds the first version.
    val stale =
      assertIs<ServeUiBuilderComponentStore.PublishResult.Conflict>(
        store.publish(
          SYSTEM,
          "inbox-email",
          "Inbox email",
          null,
          symbol("Mine"),
          first.symbol.digest,
        )
      )
    assertEquals(second.symbol.digest, stale.currentDigest)
    assertIs<ServeUiBuilderComponentStore.PublishResult.Conflict>(
      store.publish(SYSTEM, "absent", "Absent", null, symbol(key = "absent"), first.symbol.digest)
    )
  }

  @Test
  fun `a document the reader would drop is refused before anything is written`() {
    val store = ServeUiBuilderComponentStore(root)
    val nested =
      symbol()
        .let { document ->
          document.copy(
            nodes =
              document.nodes +
                ("placed" to
                  DesignNodeV1(
                    id = "placed",
                    componentId = DESIGN_COMPONENT_INSTANCE_COMPONENT_ID,
                    component = DesignComponentInstanceV1("other"),
                  )),
            roots = listOf("row"),
          )
        }
        .let { document ->
          document.copy(
            nodes =
              document.nodes +
                ("row" to
                  document.nodes
                    .getValue("row")
                    .copy(slots = mapOf("children" to listOf("sender", "placed"))))
          )
        }

    val refused =
      assertIs<ServeUiBuilderComponentStore.PublishResult.Refused>(
        store.publish(SYSTEM, "inbox-email", "Inbox email", null, nested, null)
      )
    assertTrue("places another component" in refused.reason, refused.reason)

    val twice =
      symbol().let { document ->
        document.copy(
          components =
            document.components + ("again" to DesignComponentV1(name = "Again", root = "sender"))
        )
      }
    assertIs<ServeUiBuilderComponentStore.PublishResult.Refused>(
      store.publish(SYSTEM, "inbox-email", "Inbox email", null, twice, null)
    )
    assertIs<ServeUiBuilderComponentStore.PublishResult.Refused>(
      store.publish("other-system", "inbox-email", "Inbox email", null, symbol(), null)
    )
    assertIs<ServeUiBuilderComponentStore.PublishResult.Refused>(
      store.publish(SYSTEM, "../escape", "Escape", null, symbol(key = "../escape"), null)
    )
    assertTrue(store.coordinates().isEmpty())
    assertEquals(emptyList(), File(root.toFile(), SYSTEM).list()?.toList().orEmpty())
  }

  /** A one-component document: a row holding one text, as the editor sends it. */
  private fun symbol(text: String = "Sender", key: String = "inbox-email"): DesignDocumentV1 =
    Json {
      ignoreUnknownKeys = true
    }
    .decodeFromString(
      DesignDocumentV1.serializer(),
      """
        {
          "schema": "compose-ui-builder-document/v1-candidate",
          "id": "inbox",
          "title": "Inbox",
          "revision": 7,
          "catalogPin": {
            "systemId": "$SYSTEM", "catalogRevision": "candidate",
            "capabilityDigest": "candidate", "nativeRuntimeId": "candidate"
          },
          "environment": {
            "widthDp": 412, "heightDp": 915, "density": 1.0, "theme": "light",
            "dynamicColor": false, "locale": "en-US", "fontScale": 1.0,
            "layoutDirection": "ltr", "windowPosture": "flat", "browserZoomPercent": 100,
            "fixedTime": "2024-05-16T12:00:00Z", "animations": "settled", "networkAccess": false
          },
          "stateVariables": {},
          "roots": ["row"],
          "nodes": {
            "row": {
              "id": "row", "componentId": "layout/row",
              "properties": {}, "modifiers": [], "slots": {"children": ["sender"]},
              "eventBindings": {}
            },
            "sender": {
              "id": "sender", "componentId": "m3/text",
              "properties": {"text": {"type": "binding", "value": "sender"}},
              "modifiers": [], "slots": {}, "eventBindings": {}
            }
          },
          "components": {
            ${JsonPrimitive(key)}: {"name": "InboxEmail", "root": "row", "description": "$text"}
          }
        }
        """
        .trimIndent(),
    )

  private companion object {
    const val SYSTEM = "m3-catalog"
  }
}
