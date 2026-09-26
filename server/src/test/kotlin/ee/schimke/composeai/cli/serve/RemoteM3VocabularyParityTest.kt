package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.uibuilder.export.REMOTE_CONTENT_COMPONENT_IDS
import ee.schimke.composeai.uibuilder.export.REMOTE_CONTENT_MODIFIERS
import ee.schimke.composeai.uibuilder.service.CurrentM3UiBuilderCatalogExecutor
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The `remote-m3` palette and the generator's vocabulary, checked against each other.
 *
 * They are two lists, and they have to be: `RemoteContentEmitter` lives in `:ui-builder-export`,
 * the catalog in `:ui-builder-runtime`, and `CheckUiBuilderRuntimeBoundary` keeps that module's
 * classpath deliberately narrow, so the catalog cannot import the emitter's answer. What it can do
 * is be wrong about it, which is what happened: the palette offered 28 modifiers and a component
 * the generator had no case for, the canvas drew all of it, and the export refused **after** the
 * design was built (yschimke/compose-preview-server#508).
 *
 * This test is the join the module boundary forbids, in the one module that sees both. The
 * published catalog deliberately includes renderer-only specimens that Compose export refuses and
 * owns a narrower modifier vocabulary than the emitter. What must remain true is that every
 * modifier it does advertise is one the generator understands.
 */
class RemoteM3VocabularyParityTest {
  private val catalog =
    UiBuilderCheckoutCatalogFixtures.executor(
        catalogSystemIds =
          linkedSetOf("m3-catalog", CurrentM3UiBuilderCatalogExecutor.REMOTE_M3_CATALOG_SYSTEM_ID)
      )
      .listCatalogs()
      .single {
        it.benchmark.catalogSystemId ==
          CurrentM3UiBuilderCatalogExecutor.REMOTE_M3_CATALOG_SYSTEM_ID
      }

  /**
   * The scaffolds are excluded because they are not body content: the launcher draws the container
   * and the generated code names it nowhere, so no emitter case is owed for one.
   */
  private val authoringComponents =
    catalog.components.filterNot { it.componentId.startsWith("remote-m3/widget-container-") }

  @Test
  fun `components without an authored export answer stay explicit`() {
    assertEquals(
      setOf(
        "remote-m3/remote-horizontal-page-indicator",
        "remote-m3/remote-icon",
        "remote-m3/remote-vertical-page-indicator",
        "remote-m3/theme-specimen",
      ),
      authoringComponents
        .map { it.componentId }
        .filterNot { it in REMOTE_CONTENT_COMPONENT_IDS }
        .toSet(),
    )
  }

  @Test
  fun `no component advertises a modifier the generator cannot write`() {
    assertEquals(
      emptyMap(),
      authoringComponents
        .associate {
          it.componentId to it.modifierCapabilities.filterNot { m -> m in REMOTE_CONTENT_MODIFIERS }
        }
        .filterValues { it.isNotEmpty() },
    )
  }
}
