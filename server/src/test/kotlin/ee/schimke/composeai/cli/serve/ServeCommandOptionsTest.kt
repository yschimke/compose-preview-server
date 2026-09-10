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
          "--spare-sandboxes",
          "-2",
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
    assertEquals(0, options.spareSandboxes)
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
  fun `component record arguments are catalog-keyed, and malformed ones are rejected`() {
    val options =
      options(
        listOf("--ui-builder-components", "m3-catalog=/srv/m3.json,remote-m3=/srv/remote.json")
      )
    assertEquals(
      mapOf(
        "m3-catalog" to java.io.File("/srv/m3.json"),
        "remote-m3" to java.io.File("/srv/remote.json"),
      ),
      options.uiBuilderComponents,
    )
    // A bare path is rejected rather than guessed at: a host serving two catalogs has no way to
    // say which one an unkeyed record belongs to.
    assertFailsWith<IllegalArgumentException> {
      options(listOf("--ui-builder-components", "/srv/components.json"))
    }
    assertFailsWith<IllegalArgumentException> {
      options(listOf("--ui-builder-components", "m3-catalog="))
    }
    assertFailsWith<IllegalArgumentException> {
      options(listOf("--ui-builder-components", "not/a/catalog=/srv/one.json"))
    }
    assertFailsWith<IllegalArgumentException> {
      options(listOf("--ui-builder-components", "m3-catalog=/one,m3-catalog=/two"))
    }
  }

  @Test
  fun `component packs are keyed by served catalog and name a platform the builder knows`() {
    assertEquals(
      mapOf("confetti-mobile" to "mobile", "confetti-wear" to "wear"),
      options(listOf("--ui-builder-packs", "confetti-mobile=Mobile, confetti-wear=wear"))
        .uiBuilderPacks,
    )
    assertEquals(emptyMap(), options(emptyList()).uiBuilderPacks)
    // A bare id says nothing about which catalogs receive the pack, and the record cannot.
    assertFailsWith<IllegalArgumentException> {
      options(listOf("--ui-builder-packs", "confetti-mobile"))
    }
    assertFailsWith<IllegalArgumentException> {
      options(listOf("--ui-builder-packs", "confetti-mobile=desktop"))
    }
    assertFailsWith<IllegalArgumentException> {
      options(listOf("--ui-builder-packs", "not/a/catalog=mobile"))
    }
    assertFailsWith<IllegalArgumentException> {
      options(listOf("--ui-builder-packs", "confetti-mobile=mobile,confetti-mobile=wear"))
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

  @Test
  fun `published-catalog lever distinguishes all, none and a named subset`() {
    // Absent is `all`, which is what the loader shipped with — a bump that changed this silently
    // would flip every catalog that publishes without anybody choosing to.
    assertNull(
      options(listOf("--ui-builder-catalogs", "m3-catalog,remote-m3")).uiBuilderPublishedCatalogs
    )
    assertNull(
      options(
          listOf("--ui-builder-catalogs", "m3-catalog", "--ui-builder-published-catalogs", "all")
        )
        .uiBuilderPublishedCatalogs
    )

    // `none` is an empty SET, not null: "no catalog may" and "every catalog may" are opposites and
    // must not collapse into the same value.
    assertEquals(
      emptySet(),
      options(
          listOf("--ui-builder-catalogs", "m3-catalog", "--ui-builder-published-catalogs", "none")
        )
        .uiBuilderPublishedCatalogs,
    )

    assertEquals(
      setOf("m3-catalog"),
      options(
          listOf(
            "--ui-builder-catalogs",
            "m3-catalog,remote-m3",
            "--ui-builder-published-catalogs",
            "m3-catalog",
          )
        )
        .uiBuilderPublishedCatalogs,
    )
  }

  @Test
  fun `published-catalog lever refuses a catalog this host does not serve`() {
    // The typo with a silent failure mode: the operator opts a catalog in and nothing happens,
    // because the name is not one this host serves. Refused rather than ignored.
    val unknown =
      assertFailsWith<IllegalArgumentException> {
        options(
          listOf(
            "--ui-builder-catalogs",
            "m3-catalog",
            "--ui-builder-published-catalogs",
            "wear-m3",
          )
        )
      }
    assertTrue("wear-m3" in unknown.message.orEmpty(), unknown.message.orEmpty())

    assertFailsWith<IllegalArgumentException> {
      options(
        listOf(
          "--ui-builder-catalogs",
          "m3-catalog",
          "--ui-builder-published-catalogs",
          "m3-catalog,m3-catalog",
        )
      )
    }
    assertFailsWith<IllegalArgumentException> {
      options(listOf("--ui-builder-published-catalogs", "not/a/catalog"))
    }
  }

  @Test
  fun `open path defaults to the landing page and refuses a URL that would not open`() {
    assertEquals("/", options(emptyList()).openBrowserPath)
    assertEquals(
      "/ui-builder/m3-catalog/",
      options(listOf("--open-path", "/ui-builder/m3-catalog/")).openBrowserPath,
    )
    // The token is appended as a query, so a path carrying one of its own cannot be honoured.
    assertFailsWith<IllegalArgumentException> { options(listOf("--open-path", "/x?token=1")) }
    assertFailsWith<IllegalArgumentException> { options(listOf("--open-path", "ui-builder/")) }
  }

  private fun options(args: List<String>): ServeCommandOptions =
    ServeCommandOptions(
      args = args,
      defaultTimeoutSeconds = 600L,
      previewMatcher = { _, _, _, _, _, _ -> true },
    )
}
