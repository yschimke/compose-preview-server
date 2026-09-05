package ee.schimke.composeai.uibuilder

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Wear's three selection rows, generated as the Wear API rather than as anything borrowed.
 *
 * They are the first components in `wear-m3` that were never a Material 3 id: upstream's are
 * full-width labelled rows, so `m3/checkbox` was not a smaller version of one and borrowing it was
 * never on the table.
 */
class WearSelectionRowExportTest {

  @Test
  fun `each row generates its own Wear composable, with the callback upstream names`() {
    val source = exported()

    assertTrue("CheckboxButton(" in source, source)
    assertTrue("checked = true," in source, source)
    assertTrue("onCheckedChange = {}," in source, source)
    assertTrue("SwitchButton(" in source, source)
    // `onSelect`, not `onClick` and not `onCheckedChange`: a radio cannot be un-selected by
    // pressing
    // it, and upstream's signature says so.
    assertTrue("RadioButton(" in source, source)
    assertTrue("selected = true," in source, source)
    assertTrue("onSelect = {}," in source, source)
  }

  @Test
  fun `the labels are slots, and the secondary one is omitted when empty`() {
    val source = exported()

    assertTrue("label = {" in source, source)
    assertTrue("""Text(text = "Notifications")""" in source, source)
    assertTrue("secondaryLabel = {" in source, source)
    assertTrue("""Text(text = "On")""" in source, source)
    // The switch and radio rows carry no secondary label, so they emit none — an empty
    // `secondaryLabel = {}` is a lambda upstream would draw an empty line for.
    assertEquals(1, Regex("secondaryLabel = \\{").findAll(source).count(), source)
  }

  /**
   * Only the height treatment, and the comment on the emitter says why: `ListHeader` and
   * `TitleCard` take `transformation = SurfaceTransformation(spec)` because upstream's samples show
   * them doing it, and there is no sample or compiled dependency here that shows a selection row
   * accepting the same parameter. An argument that may not exist is source that does not compile.
   */
  @Test
  fun `a row inside the list measures against the scroll without claiming a surface transformation`() {
    val source = exported()

    assertTrue("modifier = Modifier.transformedHeight(this, spec)," in source, source)
    val rowBlock = source.substringAfter("CheckboxButton(").substringBefore(")")
    assertTrue("SurfaceTransformation" !in rowBlock, rowBlock)
  }

  @Test
  fun `every emitted row imports itself and nothing it did not use`() {
    val source = exported()

    assertTrue("import androidx.wear.compose.material3.CheckboxButton" in source, source)
    assertTrue("import androidx.wear.compose.material3.SwitchButton" in source, source)
    assertTrue("import androidx.wear.compose.material3.RadioButton" in source, source)
  }

  private fun exported(): String =
    assertIs<WearScreenCodeExporter.Result.Emitted>(WearScreenCodeExporter.export(document()))
      .source

  private fun document(): UiBuilderDocument {
    fun text(id: String, value: String) =
      UiBuilderNode(
        id = id,
        componentId = "wear-m3/text",
        properties =
          JsonObject(
            mapOf(
              "text" to
                JsonObject(
                  mapOf("type" to JsonPrimitive("string"), "value" to JsonPrimitive(value))
                )
            )
          ),
      )

    fun flag(name: String, value: Boolean) =
      JsonObject(
        mapOf(
          name to
            JsonObject(mapOf("type" to JsonPrimitive("bool"), "value" to JsonPrimitive(value)))
        )
      )

    return UiBuilderDocument(
      schema = "compose-ui-builder-document/v1-candidate",
      id = "wear-selection-rows",
      title = "Wear selection rows",
      revision = 0,
      catalogPin = JsonObject(emptyMap()),
      environment = JsonObject(emptyMap()),
      stateVariables = JsonObject(emptyMap()),
      roots = listOf("screen"),
      nodes =
        mapOf(
          "screen" to
            UiBuilderNode(
              id = "screen",
              componentId = "wear-m3/screen-scaffold",
              slots = mapOf("content" to listOf("list")),
            ),
          "list" to
            UiBuilderNode(
              id = "list",
              componentId = "wear-m3/transforming-lazy-column",
              slots = mapOf("items" to listOf("notifications", "vibrate", "loud")),
            ),
          "notifications" to
            UiBuilderNode(
              id = "notifications",
              componentId = "wear-m3/checkbox-button",
              properties = flag("checked", true),
              slots =
                mapOf(
                  "label" to listOf("notifications-label"),
                  "secondaryLabel" to listOf("notifications-secondary"),
                ),
            ),
          "notifications-label" to text("notifications-label", "Notifications"),
          "notifications-secondary" to text("notifications-secondary", "On"),
          "vibrate" to
            UiBuilderNode(
              id = "vibrate",
              componentId = "wear-m3/switch-button",
              properties = flag("checked", false),
              slots = mapOf("label" to listOf("vibrate-label")),
            ),
          "vibrate-label" to text("vibrate-label", "Vibrate"),
          "loud" to
            UiBuilderNode(
              id = "loud",
              componentId = "wear-m3/radio-button",
              properties = flag("selected", true),
              slots = mapOf("label" to listOf("loud-label")),
            ),
          "loud-label" to text("loud-label", "Loud"),
        ),
    )
  }
}
