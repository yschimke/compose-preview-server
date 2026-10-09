package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.uibuilder.export.LauncherWidgetCodeExporter
import ee.schimke.composeai.uibuilder.export.WEAR_WIDGET_CONTAINER_IDS
import ee.schimke.composeai.uibuilder.guidelines.CatalogGuidelines
import ee.schimke.composeai.uibuilder.guidelines.DesignGuidelineRule
import ee.schimke.composeai.uibuilder.protocol.DesignDocumentV1
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject

class RemoteProfileGuidelinesTest {
  @Test
  fun `a design's own profile wins, with experimental`(): Unit = runBlocking {
    assertEquals(
      "launcher-widgets-v7",
      profile(LauncherWidgetCodeExporter.ROOT, """{"target":"launcher-widgets-v7"}"""),
    )
    assertEquals(
      "launcher-widgets-v7+experimental",
      profile(
        LauncherWidgetCodeExporter.ROOT,
        """{"target":"launcher-widgets-v7","experimental":true}""",
      ),
    )
  }

  @Test
  fun `without one, the default follows the kind of design`(): Unit = runBlocking {
    assertEquals("wear-widgets", profile(WEAR_WIDGET_CONTAINER_IDS.first()))
    assertEquals("launcher-widgets-v6", profile(LauncherWidgetCodeExporter.ROOT))
    assertEquals("androidx", profile("layout/column", platform = "remote-compose"))
    assertNull(profile("layout/column", platform = "mobile"))
  }

  @Test
  fun `a rule written for launcher v7 is asked only of a v7 design`(): Unit = runBlocking {
    val guidelines =
      CatalogGuidelines.parse(
        """
        {"schema":"compose-ui-builder/catalog-guidelines/v1","catalog":"remote-widgets",
         "platform":"launcher","version":1,
         "rules":[
          {"id":"any","kind":"structure","severity":"info","guidance":"g","check":"ok?",
           "source":"https://developer.android.com/a","surfaces":["widget"]},
          {"id":"v7-only","kind":"structure","severity":"warning","guidance":"g","check":"ok?",
           "source":"https://developer.android.com/b","surfaces":["widget"],
           "profiles":["launcher-widgets-v7"]}
         ]}
        """
      )
    suspend fun asked(stated: String?): List<String> {
      val document = document(LauncherWidgetCodeExporter.ROOT, stated)
      val encoded =
        UI_BUILDER_JSON.encodeToJsonElement(DesignDocumentV1.serializer(), document).jsonObject
      return ServeUiBuilderGuidelines.prepare(
          guidelines,
          designId = document.id,
          revision = 0,
          document = encoded,
          pictures = emptyList(),
          source = null,
          profile = remoteProfileOf(document, encoded) { null },
          rulesSource = "test",
        )
        .rules
        .asked
        .map { it.id }
    }
    // What the library asks for a profile is the library's to decide; the server's part is passing
    // the design's profile, so compare against the library's own answer for that profile.
    fun library(profile: String) =
      guidelines.rulesFor(DesignGuidelineRule.SURFACE_WIDGET, profile).map { it.id }
    assertEquals(library("launcher-widgets-v7"), asked("""{"target":"launcher-widgets-v7"}"""))
    assertEquals(library("launcher-widgets-v6"), asked(null))
    // The v7 rule reaches a v7 design and not the default one: the profile really is passed.
    assertTrue("v7-only" in asked("""{"target":"launcher-widgets-v7"}"""))
    assertTrue("v7-only" !in asked(null))
  }

  private suspend fun profile(
    root: String,
    stated: String? = null,
    platform: String? = null,
  ): String? {
    val document = document(root, stated)
    val encoded =
      UI_BUILDER_JSON.encodeToJsonElement(DesignDocumentV1.serializer(), document).jsonObject
    return remoteProfileOf(document, encoded) { platform }
  }

  private fun document(root: String, stated: String?): DesignDocumentV1 =
    UI_BUILDER_JSON.decodeFromString(
      DesignDocumentV1.serializer(),
      """
      {
        "schema": "compose-ui-builder-document/v1-candidate",
        "id": "widget", "title": "Widget", "revision": 0,
        "catalogPin": {"systemId": "remote-widgets", "catalogRevision": "candidate",
          "capabilityDigest": "candidate", "nativeRuntimeId": "candidate"},
        "environment": {
          "widthDp": 203, "heightDp": 220, "density": 1.0, "theme": "dark", "locale": "en-US",
          "fontScale": 1.0, "layoutDirection": "ltr"${stated?.let { ", \"remoteProfile\": $it" } ?: ""}
        },
        "stateVariables": {},
        "roots": ["root"],
        "nodes": {"root": {"id": "root", "componentId": "$root", "properties": {}, "modifiers": [],
          "slots": {}, "eventBindings": {}}}
      }
      """,
    )
}
