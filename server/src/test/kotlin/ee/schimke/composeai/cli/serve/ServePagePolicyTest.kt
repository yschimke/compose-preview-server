package ee.schimke.composeai.cli.serve

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ServePagePolicyTest {
  private fun directives(
    path: String,
    formActions: List<String> = emptyList(),
    fetchDest: String? = null,
  ) =
    ServePagePolicy.forPath(path, formActions, fetchDest).split("; ").associate {
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
      "/reference/phone.html",
      "/app/reference/phone.html",
      "/app/reference/phone.png",
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
        "/reference/phone.html",
        "/app/reference/phone.html",
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
  fun `catalog-built app shells get an opaque origin when opened top-level`() {
    // The editor keeps a person's OpenRouter key in this origin's localStorage, so no page running
    // code a catalog producer built may have this origin when loaded as a document. These fail
    // closed: no fetch metadata at all (plain-HTTP LAN origin, older browser) also sandboxes.
    val catalogApps =
      listOf(
        "/wasm/m3-catalog/",
        "/wasm/m3-catalog/index.html",
        "/wasm-private/a/m3-catalog/",
        "/ui-builder/runtime/m3-2026.09/index.html",
      )
    for (path in catalogApps) {
      for (dest in listOf(null, "", "document", "embed", "object")) {
        assertEquals(
          "allow-scripts",
          directives(path, fetchDest = dest)["sandbox"],
          "$path ($dest)",
        )
      }
      assertFalse(ServePagePolicy.forPath(path).contains("allow-same-origin"), path)
    }
    // The Remote Compose player is this server's own, and its frames need their origin, so it is
    // sandboxed only when the browser says the load is not a frame.
    for (path in listOf("/rc-player-wasm/", "/rc-player-wasm/index.html")) {
      for (dest in listOf("document", "embed", "object")) {
        assertEquals(
          "allow-scripts",
          directives(path, fetchDest = dest)["sandbox"],
          "$path ($dest)",
        )
      }
      for (dest in listOf(null, "")) {
        assertNull(directives(path, fetchDest = dest)["sandbox"], "$path ($dest)")
      }
    }
  }

  @Test
  fun `a framed app shell is left to its embedding frame, except a renderer runtime`() {
    // A trusted catalog's app and the Remote Compose player are framed with their real origin on
    // purpose; a response-level sandbox would override the iframe's `allow-same-origin`.
    for (path in listOf("/wasm/m3-catalog/", "/wasm-private/a/m3-catalog/", "/rc-player-wasm/")) {
      for (dest in listOf("iframe", "frame", "IFRAME")) {
        assertNull(directives(path, fetchDest = dest)["sandbox"], "$path ($dest)")
      }
      assertTrue(ServePagePolicy.variesByFetchDest(path), path)
    }
    // The editor only ever frames a runtime opaque, so it is sandboxed whatever the request says.
    val runtime = "/ui-builder/runtime/m3-2026.09/index.html"
    assertEquals("allow-scripts", directives(runtime, fetchDest = "iframe")["sandbox"])
    assertFalse(ServePagePolicy.variesByFetchDest(runtime))
  }

  @Test
  fun `a server-owned shell at a catalog-app path keeps its origin, a renderer runtime never`() {
    for (path in listOf("/wasm/m3-catalog/", "/wasm-private/a/m3-catalog/")) {
      val policy = ServePagePolicy.forPath(path, fetchDest = "document", serverOwned = true)
      assertFalse(policy.contains("sandbox"), "$path: $policy")
    }
    assertTrue(
      ServePagePolicy.forPath(
          "/ui-builder/runtime/m3-2026.09/index.html",
          fetchDest = "document",
          serverOwned = true,
        )
        .endsWith("; sandbox allow-scripts")
    )
  }

  @Test
  fun `the editor and the catalog pages are not sandboxed`() {
    for (path in
      pages -
        setOf(
          "/ui-builder/runtime/m3-2026.09/index.html",
          "/wasm/m3-catalog/",
          "/wasm-private/a/m3-catalog/",
          "/rc-player-wasm/",
        )) {
      assertNull(directives(path, fetchDest = "document")["sandbox"], path)
      assertFalse(ServePagePolicy.variesByFetchDest(path), path)
    }
  }

  @Test
  fun `only the UI-builder editor may reach OpenRouter, not the runtimes it frames`() {
    assertTrue(directives("/ui-builder").getValue("connect-src").contains("https://openrouter.ai"))
    assertTrue(
      directives("/ui-builder/designs/x").getValue("connect-src").contains("https://openrouter.ai")
    )
    assertFalse(
      directives("/ui-builder/runtime/r1/index.html").getValue("connect-src").contains("openrouter")
    )
    assertFalse(directives("/m3-catalog/").getValue("connect-src").contains("openrouter"))
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
