package ee.schimke.composeai.cli.serve

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StandaloneServerMainTest {
  @Test
  fun `standalone matcher preserves exact filter and preview reference semantics`() {
    val id = "com.example.GreetingPreview.Dark"

    assertTrue(previewIdMatchesStandaloneRequest(id, id, null, null, null, null))
    assertTrue(previewIdMatchesStandaloneRequest(id, null, "greeting", null, null, null))
    assertTrue(
      previewIdMatchesStandaloneRequest(
        id,
        null,
        null,
        "GreetingPreview.Dark",
        "GreetingPreview",
        "Dark",
      )
    )
    assertFalse(previewIdMatchesStandaloneRequest(id, "other", null, null, null, null))
  }
}
