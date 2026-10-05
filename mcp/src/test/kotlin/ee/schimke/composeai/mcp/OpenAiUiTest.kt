package ee.schimke.composeai.mcp

import com.google.common.truth.Truth.assertThat
import ee.schimke.composeai.mcp.protocol.ToolDef
import java.util.Base64
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertThrows
import org.junit.Test

class OpenAiUiTest {
  private fun parse(text: String): JsonObject = Json.parseToJsonElement(text) as JsonObject

  @Test
  fun `appToolMeta keeps the portable MCP Apps keys and adds entrypoints`() {
    val meta =
      OpenAiUi.appToolMeta(
        resourceUri = "ui://compose-preview/viewer",
        entrypoints =
          listOf(
            OpenAiEntrypoint.Global,
            OpenAiEntrypoint.Thread,
            OpenAiEntrypoint.File(listOf("rc", "uid")),
          ),
        visibility = listOf("model", "app"),
      )
    assertThat(meta)
      .isEqualTo(
        parse(
          """
          {
            "ui": {"resourceUri": "ui://compose-preview/viewer", "visibility": ["model", "app"]},
            "ui/resourceUri": "ui://compose-preview/viewer",
            "openai/ui": {"entrypoints": [
              {"type": "global"},
              {"type": "thread"},
              {"type": "file", "extensions": ["rc", "uid"]}
            ]}
          }
          """
        )
      )
  }

  @Test
  fun `withEntrypoints merges into existing meta without touching other openai-ui fields`() {
    val existing = parse("""{"ui": {"resourceUri": "ui://x"}, "openai/ui": {"keep": true}}""")
    val merged = OpenAiUi.withEntrypoints(existing, listOf(OpenAiEntrypoint.Thread))
    assertThat(merged)
      .isEqualTo(
        parse(
          """{"ui": {"resourceUri": "ui://x"},
             "openai/ui": {"keep": true, "entrypoints": [{"type": "thread"}]}}"""
        )
      )
    assertThat(OpenAiUi.withEntrypoints(existing, emptyList())).isEqualTo(existing)
  }

  @Test
  fun `duplicate entrypoint types and malformed extensions are refused`() {
    assertThrows(IllegalArgumentException::class.java) {
      OpenAiUi.withEntrypoints(null, listOf(OpenAiEntrypoint.Global, OpenAiEntrypoint.Global))
    }
    assertThrows(IllegalArgumentException::class.java) { OpenAiEntrypoint.File(listOf(".rc")) }
    assertThrows(IllegalArgumentException::class.java) { OpenAiEntrypoint.File(listOf(".")) }
    assertThrows(IllegalArgumentException::class.java) { OpenAiEntrypoint.File(emptyList()) }
  }

  @Test
  fun `display modes merge into resource meta and validate the preference`() {
    val meta =
      OpenAiUi.withDisplayModes(
        parse("""{"ui": {"prefersBorder": true}}"""),
        preferred = OpenAiUi.DisplayMode.FULLSCREEN,
      )
    assertThat(meta)
      .isEqualTo(
        parse(
          """{"ui": {"prefersBorder": true},
             "openai/ui": {"availableDisplayModes": ["inline", "fullscreen"],
                           "preferredDisplayMode": "fullscreen"}}"""
        )
      )
    assertThat(OpenAiUi.withDisplayModes(null))
      .isEqualTo(parse("""{"openai/ui": {"availableDisplayModes": ["inline", "fullscreen"]}}"""))
    assertThrows(IllegalArgumentException::class.java) {
      OpenAiUi.withDisplayModes(
        null,
        available = listOf(OpenAiUi.DisplayMode.INLINE),
        preferred = OpenAiUi.DisplayMode.FULLSCREEN,
      )
    }
  }

  @Test
  fun `mention search meta marks the extension and forces app visibility`() {
    assertThat(OpenAiUi.mentionSearchToolMeta())
      .isEqualTo(
        parse("""{"ui": {"visibility": ["app"]}, "openai/extensions": {"mentions/search": {}}}""")
      )
    val merged =
      OpenAiUi.mentionSearchToolMeta(parse("""{"ui": {"visibility": ["model"]}, "x": 1}"""))
    assertThat(merged)
      .isEqualTo(
        parse(
          """{"x": 1, "ui": {"visibility": ["model", "app"]},
             "openai/extensions": {"mentions/search": {}}}"""
        )
      )
  }

  @Test
  fun `svg icon is a base64 data uri of a currentColor 20x20 svg`() {
    val icon = OpenAiUi.svgIcon()
    assertThat(icon.mimeType).isEqualTo("image/svg+xml")
    assertThat(icon.src).startsWith("data:image/svg+xml;base64,")
    val svg = String(Base64.getDecoder().decode(icon.src.substringAfter("base64,")), Charsets.UTF_8)
    assertThat(svg).isEqualTo(OpenAiUi.PREVIEW_ICON_SVG)
    assertThat(svg).contains("viewBox=\"0 0 20 20\"")
    assertThat(svg).contains("currentColor")
    assertThat(svg).contains("stroke-width=\"1.33\"")
  }

  @Test
  fun `entrypointTool adds title icon and entrypoints and maps onto the SDK tool`() {
    val tool =
      ToolDef(
        name = "probe_global",
        description = "d",
        inputSchema = OpenAiUi.EMPTY_INPUT_SCHEMA,
        meta = buildJsonObject { putJsonObject("ui") { put("resourceUri", "ui://x") } },
      )
    val entry = OpenAiUi.entrypointTool(tool, listOf(OpenAiEntrypoint.Global), title = "Probe")
    assertThat(entry.title).isEqualTo("Probe")
    assertThat(entry.icons).containsExactly(OpenAiUi.svgIcon())
    val sdk = entry.toSdkTool()
    assertThat(sdk.title).isEqualTo("Probe")
    assertThat(sdk.icons!!.single().mimeType).isEqualTo("image/svg+xml")
    assertThat(sdk.meta!!["openai/ui"])
      .isEqualTo(parse("""{"entrypoints": [{"type": "global"}]}"""))
    assertThrows(IllegalArgumentException::class.java) {
      OpenAiUi.entrypointTool(tool.copy(meta = null), listOf(OpenAiEntrypoint.Global))
    }
  }

  @Test
  fun `file input schema requires file name and resourceUri`() {
    assertThat(OpenAiUi.FILE_INPUT_SCHEMA)
      .isEqualTo(
        parse(
          """{"type": "object",
             "properties": {"file": {"type": "object",
               "properties": {
                 "name": {"type": "string",
                   "description": "Name of the opened file, with extension, without the path."},
                 "resourceUri": {"type": "string",
                   "description": "Opaque host-resource:// URI; read it through the host."}},
               "required": ["name", "resourceUri"]}},
             "required": ["file"]}"""
        )
      )
  }

  @Test
  fun `resource path is read from the request meta in the coroutine context`() {
    val meta = parse("""{"openai/resource": {"path": "/work/a.rc"}, "progressToken": 1}""")
    assertThat(OpenAiUi.resourcePath(meta)).isEqualTo("/work/a.rc")
    assertThat(OpenAiUi.resourcePath(parse("""{"openai/resource": {"path": " "}}"""))).isNull()
    assertThat(OpenAiUi.resourcePath(null)).isNull()
    runBlocking {
      assertThat(OpenAiUi.currentResourcePath()).isNull()
      withContext(OpenAiRequestMeta(meta)) {
        assertThat(OpenAiUi.currentResourcePath()).isEqualTo("/work/a.rc")
      }
    }
  }
}
