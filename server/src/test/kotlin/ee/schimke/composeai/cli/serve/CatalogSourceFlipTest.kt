package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.discovery.ComponentRecordFile
import ee.schimke.composeai.uibuilder.protocol.AnimationStateV1
import ee.schimke.composeai.uibuilder.protocol.CatalogCapabilityV1
import ee.schimke.composeai.uibuilder.protocol.CatalogReferenceV1
import ee.schimke.composeai.uibuilder.protocol.DesignDocumentV1
import ee.schimke.composeai.uibuilder.protocol.DesignEnvironmentV1
import ee.schimke.composeai.uibuilder.protocol.DesignNodeV1
import ee.schimke.composeai.uibuilder.protocol.ExportCapabilitiesV1
import ee.schimke.composeai.uibuilder.protocol.LayoutDirectionV1
import ee.schimke.composeai.uibuilder.protocol.ThemeV1
import ee.schimke.composeai.uibuilder.protocol.WindowPostureV1
import ee.schimke.composeai.uibuilder.service.CurrentM3UiBuilderCatalogExecutor
import ee.schimke.composeai.uibuilder.service.PublishedUiBuilderCatalog
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

/**
 * A design pinned to one catalog source still resolves after the server restarts on the other.
 * Flipping `--ui-builder-published-catalogs` changes the catalog's reference, and stored designs
 * pinned to the other source used to become `CATALOG_UNAVAILABLE`. Only synthesised → published is
 * fixed; the reverse is asserted as a known gap, because a server that flipped back never fetched
 * the published file.
 */
class CatalogSourceFlipTest {

  private val json = Json { ignoreUnknownKeys = true }
  private val exports =
    ExportCapabilitiesV1.Builder()
      .also {
        it.composeCode = true
        it.svg = false
        it.png = false
      }
      .build()

  private fun fixture(name: String) = File("../docs/design/fixtures/ui-builder/$name").readText()

  /** m3-catalog as its own repository publishes it — the source the image now serves. */
  private val published: CatalogCapabilityV1 by lazy {
    val record = json.decodeFromString<ComponentRecordFile>(fixture("m3-catalog-record-v1.json"))
    val result =
      PublishedUiBuilderCatalog.compose(fixture("m3-catalog-published-v1.json"), record, exports)
    assertTrue(
      result is PublishedUiBuilderCatalog.Result.Composed,
      "the published pair must compose: ${(result as? PublishedUiBuilderCatalog.Result.Unusable)?.reason}",
    )
    (result as PublishedUiBuilderCatalog.Result.Composed).catalog
  }

  private fun synthesisedServer() =
    CurrentM3UiBuilderCatalogExecutor(catalogSystemIds = linkedSetOf("m3-catalog"))

  private fun publishedServer() =
    CurrentM3UiBuilderCatalogExecutor(
      catalogSystemIds = linkedSetOf("m3-catalog"),
      published = mapOf("m3-catalog" to published),
    )

  private fun referenceOf(server: CurrentM3UiBuilderCatalogExecutor): CatalogReferenceV1 =
    assertNotNull(
      server.reference(server.listCatalogs().single()),
      "a served catalog must have a reference",
    )

  /** The premise: the two sources must disagree on a reference, or these tests check nothing. */
  @Test
  fun `the two sources really do produce different references`() {
    assertTrue(
      referenceOf(synthesisedServer()) != referenceOf(publishedServer()),
      "the sources agree on a reference, so these tests prove nothing about a flip",
    )
  }

  @Test
  fun `a design pinned to the synthesised catalog still resolves after the flip to published`() {
    val pinnedBeforeFlip = referenceOf(synthesisedServer())
    val afterFlip = publishedServer()

    val resolved = afterFlip.resolve(pinnedBeforeFlip)

    assertNotNull(
      resolved,
      "a design saved before the flip is stranded: resolve returns null, so the service reports " +
        "CATALOG_UNAVAILABLE and the design cannot be opened",
    )
    // It resolves to what this server actually serves, not to a ghost of the old catalog.
    assertEquals("published", afterFlip.catalogSources["m3-catalog"])
    assertEquals(afterFlip.listCatalogs().single(), resolved)
  }

  /**
   * The unfixed direction, asserted so the gap is visible. The synthesised catalog is always
   * resident, but a server flipped back to synthesised has never fetched the published file, so it
   * cannot know the published reference. Closing it means fetching withheld catalogs or keeping a
   * reference history; re-pinning on load is the real fix. If this starts failing, invert it —
   * don't delete it.
   */
  @Test
  fun `flipping BACK to synthesised still strands a published-pinned design, for now`() {
    val pinnedBeforeFlip = referenceOf(publishedServer())
    val afterFlip = synthesisedServer()

    assertNull(
      afterFlip.resolve(pinnedBeforeFlip),
      "the reverse direction now resolves -- invert this assertion and update #818",
    )
    assertEquals("synthesised", afterFlip.catalogSources["m3-catalog"])
  }

  /**
   * `unusableReason` calls `resolve` then `validate`; both must accept the pin, or the design is
   * still dead and reported as an invalid document.
   */
  @Test
  fun `validate accepts the other source's pin too, not just resolve`() {
    val pinnedBeforeFlip = referenceOf(synthesisedServer())
    val afterFlip = publishedServer()
    val resolved = assertNotNull(afterFlip.resolve(pinnedBeforeFlip))
    val document = boxScreen(pinnedBeforeFlip)

    assertNull(
      afterFlip.validate(document, resolved),
      "validate refuses the pin resolve just accepted, so the design dies as INTERNAL instead",
    )
  }

  /**
   * Narrowed, not removed: only the same catalog id as this build can produce it is accepted; a
   * revision from neither source is still refused.
   */
  @Test
  fun `a reference from neither source is still refused`() {
    val afterFlip = publishedServer()
    val invented = referenceOf(afterFlip).copy(catalogRevision = "not-a-revision-either-source-has")

    assertNull(
      afterFlip.resolve(invented),
      "accepting an unknown revision would stop the pin catching a drifting document",
    )
  }

  @Test
  fun `a reference for a catalog this deployment does not serve is still refused`() {
    val foreign = referenceOf(publishedServer()).copy(systemId = "not-a-served-catalog")

    assertNull(publishedServer().resolve(foreign))
  }

  /**
   * The smallest accepted document, so `validate` answers only about the pin. `layout/box` has no
   * required properties and is a builder component (m3-catalog publishes none), exercising
   * `withBuilderVocabulary` as real stored designs do.
   */
  private fun boxScreen(pin: CatalogReferenceV1) =
    DesignDocumentV1(
      schema = "compose-ui-builder-document/v1-candidate",
      id = "catalog-source-flip",
      title = "Catalog source flip",
      revision = 1,
      catalogPin = pin,
      environment =
        DesignEnvironmentV1(
          widthDp = 400,
          heightDp = 800,
          density = 1.0,
          theme = ThemeV1.LIGHT,
          locale = "en-US",
          fontScale = 1.0,
          layoutDirection = LayoutDirectionV1.LTR,
          windowPosture = WindowPostureV1.FLAT,
          animations = AnimationStateV1.SETTLED,
          networkAccess = false,
        ),
      roots = listOf("root"),
      nodes = linkedMapOf("root" to DesignNodeV1(id = "root", componentId = "layout/box")),
    )
}
