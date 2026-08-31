package ee.schimke.composeai.servewasm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ClientConfigTest {
  @Test
  fun `catalog path selects canonical server routes`() {
    val config =
      ClientConfig.fromLocation(
        search = "?token=secret&session=wrong&preview=button",
        pathname = "/wasm/compose-m3/",
      )

    assertEquals("compose-m3", config.session)
    assertTrue(config.sessionInPath)
    assertEquals("/compose-m3/api/previews", config.serverPath("/api/previews"))
    assertEquals("?token=secret", config.suffix())
    assertEquals("button", config.initialPreview)
  }

  @Test
  fun `legacy query selection remains usable outside a catalog path`() {
    val config = ClientConfig.fromLocation("?session=wear-m3&token=secret", "/wasm/preview-ui/")

    assertEquals("wear-m3", config.session)
    assertFalse(config.sessionInPath)
    assertEquals("/api/previews", config.serverPath("/api/previews"))
    assertEquals("?session=wear-m3&token=secret", config.suffix())
  }

  @Test
  fun `only wasm catalog paths supply a session`() {
    assertEquals("wear m3", catalogFromWasmPath("/wasm/wear%20m3/"))
    assertNull(catalogFromWasmPath("/wear-m3/"))
    assertNull(catalogFromWasmPath("/wasm/preview-ui/"))
  }
}
