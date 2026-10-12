package ee.schimke.composeai.mcp

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

class RenderDetailReadersTest {
  private fun layoutBoxes(payload: String) =
    RenderDetailReaders.layout("layout/inspector", Json.parseToJsonElement(payload)).boxes.map {
      it.jsonObject
    }

  private fun JsonObject.props() = this["props"]?.jsonArray?.map { it.jsonPrimitive.content }

  @Test
  fun `layout boxes carry the properties the legend lists`() {
    val boxes =
      layoutBoxes(
        """
        {"root":{"component":"Column","bounds":{"left":0,"top":0,"right":200,"bottom":100},
          "size":{"width":200,"height":100},"source":"Screen.kt:12",
          "tokens":{"padding":{"top":"8.0dp","end":"16.0dp","bottom":"8.0dp","start":"16.0dp"},
            "gap":"4.0dp","minHeight":"48.0dp"},
          "children":[{"component":"Button","bounds":{"left":10,"top":60,"right":190,"bottom":95},
            "size":{"width":180,"height":42},
            "transform":{"scaleX":0.8333,"scaleY":0.8333,"scaled":true,"rotated":false}}]}}
        """
          .trimIndent()
      )

    assertThat(boxes).hasSize(2)
    assertThat(boxes[0].props())
      .containsExactly("200×100px", "pad 8.0dp 16.0dp", "gap 4.0dp", "min –×48.0dp")
      .inOrder()
    assertThat(boxes[0]["source"]?.jsonPrimitive?.content).isEqualTo("Screen.kt:12")
    // The unscaled size, and the scale that shrank it on screen: what a bezel-zone ATF finding
    // needs.
    assertThat(boxes[1].props()).containsExactly("180×42px", "scale 0.83×0.83").inOrder()
    assertThat(boxes[1]["depth"]?.jsonPrimitive?.content).isEqualTo("1")
  }

  @Test
  fun `a semantics node without size or tokens still gets its bounds size`() {
    val layout =
      RenderDetailReaders.layout(
        "compose/semantics",
        Json.parseToJsonElement("""{"root":{"boundsInRoot":"0,0,40,20","testTag":"t"}}"""),
      )

    val box = layout.boxes.single().jsonObject
    assertThat(box.props()).containsExactly("40×20px")
    assertThat(box["label"]?.jsonPrimitive?.content).isEqualTo("t")
    assertThat(box["source"]).isNull()
  }
}
