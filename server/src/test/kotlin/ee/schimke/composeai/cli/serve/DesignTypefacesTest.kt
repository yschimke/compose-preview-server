package ee.schimke.composeai.cli.serve

import kotlin.test.Test
import kotlin.test.assertEquals

/** The families a design draws in, read the way the canvas reads them. */
class DesignTypefacesTest {
  private val document =
    """{"schema":"compose-ui-builder-document/v1-candidate","id":"aurora","title":"Aurora","revision":4,"catalogPin":{"systemId":"wear-m3"},"environment":{"typeface":"Inter"},"stateVariables":{},"roots":["screen"],"nodes":{"screen":{"id":"screen","componentId":"wear-m3/screen-scaffold","properties":{"themeDisplayTypeface":{"type":"string","value":"Michroma"},"themeBodyTypeface":{"type":"string","value":"google:Exo 2"},"themeLabelTypeface":{"type":"string","value":" "}},"modifiers":[],"slots":{},"eventBindings":{}},"label":{"id":"label","componentId":"wear-m3/text","properties":{"text":{"type":"string","value":"Michroma"}},"modifiers":[],"slots":{},"eventBindings":{}}}}"""

  @Test
  fun `the environment's face and every theme host's typefaces, and nothing else`() {
    assertEquals(
      setOf("Inter", "Michroma", "google:Exo 2"),
      DesignTypefaces.ofRendererDocument(document),
    )
  }

  @Test
  fun `a document it cannot read names nothing`() {
    assertEquals(emptySet(), DesignTypefaces.ofRendererDocument("not json"))
  }
}
