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
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

/**
 * A design pinned to one catalog SOURCE still resolves after the server restarts on the other.
 *
 * This is the test #796 says nobody has, and the reason it matters is that nothing else would have
 * caught the failure. `--ui-builder-published-catalogs` is one variable, documented as per catalog
 * and reversible, and flipping it changes the catalog's `benchmark` and therefore its reference.
 * `ProductionUiBuilderRuntime.resolve` accepted only the exact current reference, so every design
 * persisted on `/config/ui-builder-state` against the other source became `CATALOG_UNAVAILABLE` on
 * the next restart, with no upgrade path in the runtime. Nothing fails at build time; the cost
 * lands on somebody's saved work, on a box, after a deploy.
 *
 * The blast radius was never knowable from the repository — how many stored designs a deployment
 * holds is deployment state — which is exactly why the fix is a property to assert rather than a
 * number to look up.
 *
 * These assert the property in BOTH directions. A cutover that strands work going one way and not
 * the other is not reversible, and reversibility is the whole claim the lever makes.
 */
class CatalogSourceFlipTest {

  private val json = Json { ignoreUnknownKeys = true }
  private val exports = ExportCapabilitiesV1(composeCode = true, svg = false, png = false)

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

  /**
   * The premise. If the two sources agreed on a reference there would be no bug to fix and these
   * tests would pass against the unfixed runtime while checking nothing — the failure mode this
   * repository has been bitten by twice today.
   */
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
   * The direction this does NOT fix, asserted so the gap is visible rather than assumed away.
   *
   * The fix computes the alternate reference from a catalog that is already in the process, and
   * that only works one way round. The synthesised catalog is generated in Kotlin and always
   * resident, so a server on the published source can always compute the synthesised reference. The
   * reverse is not true: `ServeRunner` fetches a catalog's published file only for the ids
   * `--ui-builder-published-catalogs` names, so a server that has flipped BACK has never seen the
   * published file and cannot know the reference a design was pinned to.
   *
   * Closing it would mean either fetching published files for catalogs deliberately withheld -- a
   * startup network cost for a source nobody asked to serve -- or persisting a reference history,
   * which is the state this fix exists to avoid needing. #818's re-pinning is the answer that makes
   * the question moot, because a design re-pinned while the published source was serving carries a
   * reference the synthesised server also refuses; the real fix there is re-pinning on every load,
   * in whichever direction.
   *
   * If this test starts failing, the gap has been closed and the assertion should be inverted --
   * not deleted.
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
   * The half that makes the fix safe rather than merely permissive.
   *
   * `unusableReason` calls `resolve` and then `validate`. Accepting a pin in one and refusing it in
   * the other would leave the design just as dead, reported as an INTERNAL "invalid stored design"
   * that blames the document instead of the pin — a worse answer than the one being fixed.
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
   * The check is narrowed, not removed.
   *
   * Only the SAME catalog id as this build can produce it is accepted. A revision from neither
   * source — a design carried from a different deployment, or a catalog that has genuinely moved on
   * — is still refused, which is the drift this pin exists to catch.
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
   * The smallest document the catalog accepts, so `validate` answers about the PIN and nothing
   * else.
   *
   * `layout/box` is the root on purpose: it declares no required properties and one `children`
   * slot, so there is no property value to get wrong and no second component in the picture. It is
   * also one of the builder's OWN components, which m3-catalog deliberately publishes none of — so
   * this document exercises the published catalog exactly where `withBuilderVocabulary` supplies
   * it, which is the shape a real stored design has.
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
