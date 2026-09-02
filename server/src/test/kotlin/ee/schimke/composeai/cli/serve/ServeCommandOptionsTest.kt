package ee.schimke.composeai.cli.serve

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ServeCommandOptionsTest {
  @Test
  fun `constructor normalises network and capacity arguments`() {
    val options =
      options(
        listOf(
          "--lan",
          "--host",
          "ignored.example",
          "--port=9090",
          "--live-seats",
          "-4",
          "--revisions-allow",
          " main, release/*, ,",
          "--accept-bundles-from",
          "artifacts.example, cdn.example",
          "--exit-when-idle=45",
          "--catalog-max-images",
          "2500",
          "--wasm-ui-dir",
          "/srv/wasm-ui",
          "--ui-builder-dir",
          "/srv/ui-builder",
          "--ui-builder-catalogs",
          "m3-catalog,remote-m3",
          "--ui-builder-runtime-dir",
          "m3-2026.09=/srv/runtime-one,m3-2026.10=/srv/runtime-two",
          "--ui-builder-state-dir",
          "/srv/ui-builder-state",
          "--ui-builder-migrate-state",
          "--catalog-mcp",
        )
      )

    assertTrue(options.lan)
    assertEquals(ServeUrls.ALL_INTERFACES, options.host)
    assertEquals(9090, options.requestedPort)
    assertEquals(0, options.liveSeats)
    assertEquals(listOf("main", "release/*"), options.revisionAllowRefs)
    assertEquals(listOf("artifacts.example", "cdn.example"), options.acceptBundlesFrom)
    assertTrue(options.exitWhenIdle)
    assertEquals(45L, options.idleExitSeconds)
    assertEquals(2500, options.catalogMaxImages)
    assertEquals("/srv/wasm-ui", options.wasmUiDir?.path)
    assertEquals("/srv/ui-builder", options.uiBuilderDir?.path)
    assertEquals(setOf("m3-catalog", "remote-m3"), options.uiBuilderCatalogs)
    assertEquals(
      mapOf(
        "m3-2026.09" to java.io.File("/srv/runtime-one"),
        "m3-2026.10" to java.io.File("/srv/runtime-two"),
      ),
      options.uiBuilderRuntimeDirs,
    )
    assertEquals("/srv/ui-builder-state", options.uiBuilderStateDirFlag)
    assertTrue(options.uiBuilderMigrateState)
    assertTrue(options.catalogMcp)
  }

  @Test
  fun `defaults remain loopback token gated and non discovering`() {
    val options = options(emptyList())

    assertEquals(ServeUrls.LOOPBACK, options.host)
    assertFalse(options.lan)
    assertFalse(options.public)
    assertFalse(options.discover)
    assertFalse(options.allowRenderTrusted)
    assertEquals(ServeCatalogStore.DEFAULT_MAX_IMAGES, options.catalogMaxImages)
    assertNull(options.uiBuilderStateDirFlag)
    assertFalse(options.uiBuilderMigrateState)
    assertFalse(options.catalogMcp)
    assertEquals(setOf("m3-catalog"), options.uiBuilderCatalogs)
    assertEquals(
      "none",
      options(listOf("--ui-builder-state-dir=none")).uiBuilderStateDirFlag,
    )
  }

  @Test
  fun `runtime bundle arguments reject duplicates and malformed entries`() {
    assertFailsWith<IllegalArgumentException> {
      options(listOf("--ui-builder-runtime-dir", "runtime="))
    }
    assertFailsWith<IllegalArgumentException> {
      options(listOf("--ui-builder-runtime-dir", "runtime=/one,runtime=/two"))
    }
  }

  @Test
  fun `UI builder catalog allowlist rejects duplicates and unsafe ids`() {
    assertFailsWith<IllegalArgumentException> {
      options(listOf("--ui-builder-catalogs", "remote-m3,remote-m3"))
    }
    assertFailsWith<IllegalArgumentException> {
      options(listOf("--ui-builder-catalogs", "remote-m3,not/a/catalog"))
    }
  }

  private fun options(args: List<String>): ServeCommandOptions =
    ServeCommandOptions(
      args = args,
      defaultTimeoutSeconds = 600L,
      previewMatcher = { _, _, _, _, _, _ -> true },
    )
}
