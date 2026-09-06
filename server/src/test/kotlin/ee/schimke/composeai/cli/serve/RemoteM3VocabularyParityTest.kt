package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.uibuilder.REMOTE_CONTENT_COMPONENT_IDS
import ee.schimke.composeai.uibuilder.REMOTE_CONTENT_MODIFIERS
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
 * This test is the join the module boundary forbids, in the one module that sees both. It fails
 * when the palette grows something the generator cannot write — which is a failing build, at the
 * moment the palette changes, rather than a refusal an author discovers at the end of a design.
 */
class RemoteM3VocabularyParityTest {
  private val catalog =
    CurrentM3UiBuilderCatalogExecutor(
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
  fun `every component the palette offers has an authored answer in the generator`() {
    assertEquals(
      emptyList(),
      authoringComponents.map { it.componentId }.filterNot { it in REMOTE_CONTENT_COMPONENT_IDS },
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

  /**
   * And the palette is not narrower than it needs to be either.
   *
   * The direction that matters to an author is the one above; this one catches the answer nobody
   * wants to the drift — narrowing the catalog until it agrees, rather than teaching the emitter.
   * `layout/box` carries the widest borrowed vocabulary, so what it keeps is what the generator can
   * do minus the four Remote Compose has no counterpart for.
   */
  @Test
  fun `the palette keeps every modifier the generator can write`() {
    val box = authoringComponents.single { it.componentId == "layout/box" }

    assertEquals(
      emptyList(),
      (REMOTE_CONTENT_MODIFIERS - box.modifierCapabilities.toSet()).sorted() -
        // Never offered on a box by the base catalog either: `weight` and the cross-axis
        // alignments are a child's word to its row or column, and a box has no scope for them.
        setOf("alignHorizontal", "alignVertical"),
    )
  }
}
