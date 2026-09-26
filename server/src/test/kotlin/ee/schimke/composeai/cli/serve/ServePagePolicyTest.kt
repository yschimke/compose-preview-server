package ee.schimke.composeai.cli.serve

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ServePagePolicyTest {
  private fun directives(path: String, formActions: List<String> = emptyList()) =
    ServePagePolicy.forPath(path, formActions).split("; ").associate {
      it.substringBefore(' ') to it.substringAfter(' ', "")
    }

  private val pages =
    listOf(
      "/",
      "/m3-catalog/",
      "/m3-catalog/p/com.example.Red",
      "/d/abc123",
      "/docs",
      "/playground",
      "/ui-builder/",
      "/ui-builder/designs",
      "/ui-builder/m3-catalog/",
      "/ui-builder/runtime/m3-2026.09/index.html",
      "/wasm/m3-catalog/",
      "/wasm-private/a/m3-catalog/",
      "/rc-player-wasm/",
      "/iframe.html",
      "/m3-catalog/iframe.html",
    )

  @Test
  fun `no page may evaluate strings as script`() {
    for (path in pages) {
      val policy = ServePagePolicy.forPath(path)
      assertFalse(Regex("(?<!wasm-)unsafe-eval").containsMatchIn(policy), "$path: $policy")
      val d = directives(path)
      assertEquals("'none'", d["object-src"], path)
      assertEquals("'self'", d["base-uri"], path)
      assertEquals("'self'", d["default-src"], path)
      assertEquals("'self' https://github.com", d["form-action"], path)
    }
  }

  @Test
  fun `only the Wasm app paths may compile Wasm`() {
    val wasm =
      setOf(
        "/ui-builder/",
        "/ui-builder/designs",
        "/ui-builder/m3-catalog/",
        "/ui-builder/runtime/m3-2026.09/index.html",
        "/wasm/m3-catalog/",
        "/wasm-private/a/m3-catalog/",
        "/rc-player-wasm/",
      )
    for (path in pages) {
      val scripts = directives(path).getValue("script-src")
      assertEquals(path in wasm, scripts.contains("'wasm-unsafe-eval'"), "$path: $scripts")
      assertTrue(scripts.startsWith("'self' 'unsafe-inline'"), "$path: $scripts")
    }
  }

  @Test
  fun `the framable pages match the proxy's X-Frame-Options exemption`() {
    // deploy/image/Caddyfile: `not path /iframe.html /*/iframe.html /wasm/* /ui-builder/runtime/*`.
    val framable =
      setOf(
        "/iframe.html",
        "/m3-catalog/iframe.html",
        "/wasm/m3-catalog/",
        "/ui-builder/runtime/m3-2026.09/index.html",
      )
    for (path in pages) {
      val ancestors = directives(path)["frame-ancestors"]
      if (path in framable) assertNull(ancestors, path) else assertEquals("'self'", ancestors, path)
    }
  }

  @Test
  fun `the history strip, fonts and images the pages load are admitted`() {
    val d = directives("/m3-catalog/p/com.example.Red")
    assertTrue(d.getValue("img-src").contains("https://raw.githubusercontent.com"))
    assertTrue(d.getValue("img-src").contains("data:"))
    assertTrue(d.getValue("img-src").contains("blob:"))
    assertTrue(d.getValue("connect-src").contains("https://raw.githubusercontent.com"))
    assertTrue(d.getValue("style-src").contains("https://fonts.googleapis.com"))
    assertTrue(d.getValue("font-src").contains("https://fonts.gstatic.com"))
    assertEquals("'self'", d["frame-src"])
    assertEquals("'self'", d["worker-src"])
  }

  @Test
  fun `extra form destinations are appended once`() {
    val d =
      directives(
        "/agent-access/r1",
        listOf("https://preview.example.com", "vscode:", "https://preview.example.com"),
      )
    assertEquals(
      "'self' https://github.com https://preview.example.com vscode:",
      d["form-action"],
    )
  }

  @Test
  fun `a redirect uri becomes its origin or its app scheme`() {
    assertEquals(
      "http://127.0.0.1:8976",
      ServePagePolicy.formActionSource("http://127.0.0.1:8976/callback?x=1"),
    )
    assertEquals(
      "https://claude.ai",
      ServePagePolicy.formActionSource("https://Claude.ai/api/mcp/auth_callback"),
    )
    assertEquals("vscode:", ServePagePolicy.formActionSource("vscode://publisher.ext/callback"))
    assertEquals("cursor:", ServePagePolicy.formActionSource("cursor://anysphere/cb"))
    assertNull(ServePagePolicy.formActionSource("javascript:alert(1)"))
    assertNull(ServePagePolicy.formActionSource("data:text/html,hi"))
    assertNull(ServePagePolicy.formActionSource("/relative/only"))
    assertNull(ServePagePolicy.formActionSource("https:///no-host"))
    assertNull(ServePagePolicy.formActionSource("not a uri"))
  }
}
