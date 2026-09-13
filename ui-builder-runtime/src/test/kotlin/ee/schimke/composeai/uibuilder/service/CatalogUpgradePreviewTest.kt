package ee.schimke.composeai.uibuilder.service

import ee.schimke.composeai.uibuilder.protocol.*
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Moving a design off the two Material 3 ids the synthesised `remote-m3` catalog used to borrow.
 *
 * Six designs on `preview.coo.ee` name `m3/text` or `m3/surface` against a published catalog that
 * declares neither, and an unknown component is fatal where an undeclared property is not — there
 * is nothing to draw. The preview says what moving them onto the vocabulary the catalog does
 * publish would cost, BEFORE anything is moved: `remote-m3/remote-text` for the text, a box with a
 * background for the surface, and a named list of what neither can carry.
 */
class CatalogUpgradePreviewTest {

  private val owner = AuthenticatedUiBuilderActor("owner")

  // ── the plan, asserted without a service ──────────────────────────────────────────────────────

  @Test
  fun `text moves onto the component the catalog really publishes`() {
    val document =
      document("d")
        .withNode(
          node(
            "label",
            "m3/text",
            mapOf("text" to "Discover Weekly", "fontSizeSp" to "14", "color" to "#FFFFFF"),
          )
        )

    val outcome = planCatalogUpgrade(document, remoteM3(), TARGET)

    val moved = outcome.candidate.nodes.getValue("label")
    assertEquals("remote-m3/remote-text", moved.componentId)
    assertEquals(
      setOf("text", "fontSize", "color"),
      moved.properties.keys,
      "`fontSizeSp` is `fontSize` in the Remote library's signature; the rest keep their names",
    )
    assertEquals(
      StringValueV1("14"),
      moved.properties["fontSize"],
      "a rename carries the value, it does not reset it",
    )
  }

  @Test
  fun `a property the target has no place for is reported rather than silently lost`() {
    val document =
      document("d")
        .withNode(node("label", "m3/text", mapOf("text" to "hi", "letterSpacingSp" to "0.5")))

    val outcome = planCatalogUpgrade(document, remoteM3(), TARGET)

    assertNull(outcome.candidate.nodes.getValue("label").properties["letterSpacingSp"])
    val issue =
      assertNotNull(outcome.issues.singleOrNull { it.code == "PROPERTY_NOT_DECLARED" }, "one issue")
    assertEquals(CatalogUpgradeIssueSeverityV1.WARNING, issue.severity)
    assertEquals("/nodes/label/properties/letterSpacingSp", issue.path)
    assertTrue(
      outcome.changes.any {
        it is RemoveCatalogUpgradeChangeV1 && it.path == "/nodes/label/properties/letterSpacingSp"
      },
      "and it is a change a reviewer can see, not only prose",
    )
  }

  /** The document the question was asked about is never the document that is edited. */
  @Test
  fun `planning does not touch the document it was given`() {
    val document =
      document("d").withNode(node("label", "m3/text", mapOf("letterSpacingSp" to "0.5")))

    planCatalogUpgrade(document, remoteM3(), TARGET)

    assertEquals("m3/text", document.nodes.getValue("label").componentId)
    assertEquals(setOf("letterSpacingSp"), document.nodes.getValue("label").properties.keys)
  }

  @Test
  fun `a surface becomes a box carrying its colour as a background`() {
    val document =
      document("d")
        .withNode(
          node("panel", "m3/surface", mapOf("containerColor" to "#101010", "shapeDp" to "12"))
            .copy(slots = mapOf("content" to listOf("label")))
        )
        .withNode(node("label", "m3/text", mapOf("text" to "hi")))

    val outcome = planCatalogUpgrade(document, remoteM3(), TARGET)

    val box = outcome.candidate.nodes.getValue("panel")
    assertEquals("layout/box", box.componentId)
    assertEquals(
      listOf(BackgroundModifierV1(StringValueV1("#101010"))),
      box.modifiers,
      "the colour is where a box states a colour: the modifier chain",
    )
    assertEquals(mapOf("children" to listOf("label")), box.slots, "a box fills `children`")
    assertTrue("shapeDp" !in box.properties, "a radius is not a shape token, so it does not move")
    assertTrue(
      outcome.issues.any {
        it.code == "PROPERTY_BECOMES_MODIFIER" && it.severity == CatalogUpgradeIssueSeverityV1.INFO
      },
      "moving a property to the chain is worth saying, but it is not a loss",
    )
  }

  @Test
  fun `a modifier this build cannot write blocks, rather than losing the value it carried`() {
    val document =
      document("d").withNode(node("panel", "m3/surface", mapOf("containerColor" to "#101010")))

    val outcome =
      planCatalogUpgrade(document, remoteM3(containerColorBecomes = "elevation"), TARGET)

    val issue = assertNotNull(outcome.issues.singleOrNull { it.code == "UNSUPPORTED_MODIFIER" })
    assertEquals(CatalogUpgradeIssueSeverityV1.ERROR, issue.severity)
    assertEquals(
      StringValueV1("#101010"),
      outcome.candidate.nodes.getValue("panel").properties["containerColor"],
      "a colour this build cannot move is kept where it is, not quietly dropped",
    )
  }

  @Test
  fun `a component with no successor is an error, and the node is left alone`() {
    val document = document("d").withNode(node("chip", "m3/assist-chip", mapOf("label" to "hi")))

    val outcome = planCatalogUpgrade(document, remoteM3(), TARGET)

    val issue = assertNotNull(outcome.issues.singleOrNull { it.code == "UNKNOWN_COMPONENT" })
    assertEquals(CatalogUpgradeIssueSeverityV1.ERROR, issue.severity)
    assertEquals(
      "m3/assist-chip",
      outcome.candidate.nodes.getValue("chip").componentId,
      "deleting somebody's content to make a document validate is never the repair",
    )
  }

  // ── the service ───────────────────────────────────────────────────────────────────────────────

  @Test
  fun `a preview is READY and changes nothing on disk`() {
    val root = createTempDirectory("upgrade")
    val service = service(root)
    create(service, "widget")

    val preview = preview(service, "widget", revision = 0)

    assertEquals(CatalogUpgradePreviewStatusV1.READY, preview.status)
    assertEquals(
      "remote-m3/remote-text",
      preview.candidateDocument.nodes.getValue("label").componentId,
    )
    val stored =
      checkNotNull(UiBuilderDesignStateStore.open(root).store.load().designs["widget"]).document
    assertEquals(
      "m3/text",
      stored.nodes.getValue("label").componentId,
      "a preview proposes; nothing moves until an apply does it",
    )
    assertEquals(SOURCE, stored.catalogPin, "including the pin")
  }

  @Test
  fun `a design the target cannot draw previews as BLOCKED rather than failing`() {
    val root = createTempDirectory("upgrade")
    val service = service(root)
    create(service, "widget", componentId = "m3/assist-chip")

    val preview = preview(service, "widget", revision = 0)

    assertEquals(CatalogUpgradePreviewStatusV1.BLOCKED, preview.status)
    assertTrue(
      preview.issues.any { it.severity == CatalogUpgradeIssueSeverityV1.ERROR },
      "the refusal travels with the answer",
    )
  }

  @Test
  fun `a stale base revision is refused, because the candidate would describe another document`() {
    val root = createTempDirectory("upgrade")
    val service = service(root)
    create(service, "widget")

    val response =
      execute(
        service,
        owner,
        UiBuilderServiceRequest.PreviewCatalogUpgrade("widget", 99, SOURCE, TARGET),
      )

    assertEquals(
      ServiceErrorCodeV1.BAD_REQUEST,
      assertIs<UiBuilderServiceResponse.Error>(response).error.code,
    )
  }

  // ── harness ───────────────────────────────────────────────────────────────────────────────────

  /**
   * The published catalog's vocabulary, narrowed to what these cases turn on.
   *
   * The `supersedes` block is the fixture's own, and that is the point: the runtime states no
   * catalog's successors (`ui-builder-catalog-literals.sh`), so the mapping under test is the one a
   * catalog publishes. These two entries are the ones `remote-catalog/ui-builder.policy.json` in
   * yschimke/wear-m3-catalog is to declare — `RemoteText` for the borrowed Material 3 text, a box
   * with a background for the surface Remote Compose Material 3 does not publish.
   */
  private fun remoteM3(containerColorBecomes: String = "background"): CatalogCapabilityV1 =
    CatalogCapabilityV1(
      schema = "compose-catalog-capabilities/v1",
      benchmark =
        CatalogBenchmarkV1("remote-m3", "source", "remote-m3", "published", "remote-runtime"),
      statusSemantics =
        JsonObject(
          mapOf(
            "supersedes" to
              JsonObject(
                mapOf(
                  "m3/text" to
                    JsonObject(
                      mapOf(
                        "componentId" to JsonPrimitive("remote-m3/remote-text"),
                        "properties" to
                          JsonObject(mapOf("fontSizeSp" to JsonPrimitive("fontSize"))),
                      )
                    ),
                  "m3/surface" to
                    JsonObject(
                      mapOf(
                        "componentId" to JsonPrimitive("layout/box"),
                        "slots" to JsonObject(mapOf("content" to JsonPrimitive("children"))),
                        "modifiers" to
                          JsonObject(
                            mapOf("containerColor" to JsonPrimitive(containerColorBecomes))
                          ),
                      )
                    ),
                )
              )
          )
        ),
      components =
        listOf(
          component(
            "remote-m3/remote-text",
            // `RemoteText`'s own parameters, as the published record states them: no letter
            // spacing, and the size is `fontSize`.
            listOf("text", "color", "fontSize", "fontWeight", "textAlign", "overflow", "maxLines"),
          ),
          component("layout/box", listOf("contentAlignment")),
        ),
      exportCapabilities = ExportCapabilitiesV1(composeCode = true, svg = true, png = true),
    )

  private fun component(id: String, properties: List<String>) =
    ComponentCapabilityV1(
      componentId = id,
      displayName = id,
      role = if (id == "layout/box") "Container" else "Leaf",
      properties =
        properties.map {
          PropertyCapabilityV1(name = it, jsonType = JsonPrimitive("string"), required = false)
        },
      wasm = WasmCapabilityV1(JsonPrimitive(true), WasmAdapterStatusV1.SUPPORTED),
    )

  /** Serves the published catalog under [TARGET] and the borrowed vocabulary under [SOURCE]. */
  private inner class TwoPinCatalogs : UiBuilderCatalogExecutor {
    private val published = remoteM3()
    private val borrowed =
      published.copy(
        benchmark = published.benchmark.copy(catalogRevision = "candidate"),
        components =
          published.components +
            component("m3/text", listOf("text", "color", "fontSizeSp", "letterSpacingSp")) +
            // Declared here and nowhere in the published catalog: the shape of a design authored
            // when the borrowed vocabulary was wider than what replaced it.
            component("m3/assist-chip", listOf("text")),
      )

    override fun listCatalogs(): List<CatalogCapabilityV1> = listOf(published, borrowed)

    override fun resolve(reference: CatalogReferenceV1): CatalogCapabilityV1? =
      when (reference) {
        TARGET -> published
        SOURCE -> borrowed
        else -> null
      }

    override fun reference(catalog: CatalogCapabilityV1): CatalogReferenceV1? =
      when (catalog) {
        published -> TARGET
        borrowed -> SOURCE
        else -> null
      }

    override fun validate(
      document: DesignDocumentV1,
      catalog: CatalogCapabilityV1,
    ): UiBuilderCatalogIssue? {
      val declared = catalog.components.associateBy { it.componentId }
      document.nodes.values.forEach { node ->
        val component =
          declared[node.componentId]
            ?: return UiBuilderCatalogIssue(
              "UNKNOWN_COMPONENT",
              "component ${node.componentId} is not in ${catalog.benchmark.catalogSystemId}",
              node.id,
            )
        val names = component.properties.mapTo(mutableSetOf()) { it.name }
        node.properties.keys
          .firstOrNull { it !in names }
          ?.let {
            return UiBuilderCatalogIssue(
              "UNKNOWN_PROPERTY",
              "property $it is not declared by ${node.componentId}",
              node.id,
              it,
            )
          }
      }
      return null
    }
  }

  private fun service(root: Path): PersistentUiBuilderService =
    PersistentUiBuilderService(
      designStore = UiBuilderDesignStateStore.open(root),
      catalogs = TwoPinCatalogs(),
      exporter = UiBuilderExportExecutor { error("no export in this test") },
      clock = Clock.fixed(Instant.ofEpochMilli(1_000), ZoneOffset.UTC),
    )

  private fun create(
    service: PersistentUiBuilderService,
    designId: String,
    componentId: String = "m3/text",
  ) {
    val document =
      document(designId).withNode(node("label", componentId, mapOf("text" to "Discover Weekly")))
    assertIs<UiBuilderServiceResponse.Snapshot>(
      execute(service, owner, UiBuilderServiceRequest.CreateDesign(document))
    )
  }

  private fun preview(
    service: PersistentUiBuilderService,
    designId: String,
    revision: Long,
  ): CatalogUpgradePreviewV1 =
    assertIs<UiBuilderServiceResponse.CatalogUpgradePreview>(
        execute(
          service,
          owner,
          UiBuilderServiceRequest.PreviewCatalogUpgrade(designId, revision, SOURCE, TARGET),
        )
      )
      .preview

  private fun node(id: String, componentId: String, properties: Map<String, String>) =
    DesignNodeV1(
      id = id,
      componentId = componentId,
      properties = properties.mapValues { (_, v) -> StringValueV1(v) },
    )

  private fun DesignDocumentV1.withNode(node: DesignNodeV1): DesignDocumentV1 =
    copy(nodes = nodes + (node.id to node), roots = if (roots.isEmpty()) listOf(node.id) else roots)

  private fun document(designId: String): DesignDocumentV1 =
    DesignDocumentV1(
      schema = "compose-ui-builder/v1",
      id = designId,
      title = designId,
      revision = 0,
      catalogPin = SOURCE,
      environment =
        DesignEnvironmentV1(
          widthDp = 216,
          heightDp = 124,
          density = 1.0,
          theme = ThemeV1.DARK,
          locale = "en-GB",
          fontScale = 1.0,
          layoutDirection = LayoutDirectionV1.LTR,
        ),
      roots = emptyList(),
      nodes = emptyMap(),
    )

  private fun execute(
    service: PersistentUiBuilderService,
    actor: AuthenticatedUiBuilderActor,
    request: UiBuilderServiceRequest,
  ): UiBuilderServiceResponse = runSuspend { service.execute(UiBuilderServiceCall(actor, request)) }

  private fun <T> runSuspend(block: suspend () -> T): T {
    var completion: Result<T>? = null
    block.startCoroutine(
      object : Continuation<T> {
        override val context = EmptyCoroutineContext

        override fun resumeWith(result: Result<T>) {
          completion = result
        }
      }
    )
    return assertNotNull(completion, "suspend function did not complete").getOrThrow()
  }

  private companion object {
    private val SOURCE = CatalogReferenceV1("remote-m3", "candidate", "candidate", "remote-runtime")
    private val TARGET = CatalogReferenceV1("remote-m3", "published", "published", "remote-runtime")
  }
}
