package ee.schimke.composeai.uibuilder.service

import ee.schimke.composeai.uibuilder.protocol.*
import java.nio.file.Files
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
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonPrimitive

/**
 * The service on the per-design store, end to end.
 *
 * [FileUiBuilderDesignStoreTest] proves the store's own promises against the file tree; this proves
 * the thing that matters to an operator — that a host restarted onto the same directory serves the
 * same designs, that undo still replays, and that an edit to one design writes nothing belonging to
 * another (yschimke/compose-preview-server#578).
 */
class PersistentUiBuilderServiceFileStoreTest {
  private val owner = AuthenticatedUiBuilderActor("owner")

  @Test
  fun `designs survive a restart onto the same directory`() {
    val root = createTempDirectory("ui-builder-service-store")
    val first = service(root)
    create(first, "checkout")
    apply(first, "checkout", "op-1", 0, InsertNodeMutationV1(node("node-1"), NodeLocationV1()))

    val reopened = service(root)
    val snapshot =
      assertIs<UiBuilderServiceResponse.Snapshot>(
        execute(reopened, owner, UiBuilderServiceRequest.OpenDesign("checkout"))
      )
    assertEquals(1, snapshot.snapshot.state.document.revision)
    assertEquals(setOf("node-1"), snapshot.snapshot.state.document.nodes.keys)
  }

  @Test
  fun `undo replays after a restart`() {
    val root = createTempDirectory("ui-builder-service-store")
    val first = service(root)
    create(first, "checkout")
    apply(first, "checkout", "op-1", 0, InsertNodeMutationV1(node("node-1"), NodeLocationV1()))

    val reopened = service(root)
    val undone =
      execute(
        reopened,
        owner,
        UiBuilderServiceRequest.ApplyOperation(
          UiBuilderSubmission.Undo("checkout", "undo-1", "browser", 1, "op-1")
        ),
      )
    assertIs<UiBuilderServiceResponse.OperationOutcome>(undone)
    assertIs<AcceptedOutcomeV1>((undone as UiBuilderServiceResponse.OperationOutcome).outcome)

    val snapshot =
      assertIs<UiBuilderServiceResponse.Snapshot>(
        execute(reopened, owner, UiBuilderServiceRequest.OpenDesign("checkout"))
      )
    assertEquals(emptySet(), snapshot.snapshot.state.document.nodes.keys)
  }

  @Test
  fun `an edit writes nothing belonging to another design`() {
    val root = createTempDirectory("ui-builder-service-store")
    val service = service(root)
    create(service, "checkout")
    create(service, "settings")
    val untouched = fingerprint(designDirectory(root, "settings"))

    apply(service, "checkout", "op-1", 0, InsertNodeMutationV1(node("node-1"), NodeLocationV1()))

    assertEquals(
      untouched,
      fingerprint(designDirectory(root, "settings")),
      "one design's edit is one design's write",
    )
  }

  @Test
  fun `a design whose files cannot be read is reported without taking the host down`() {
    val root = createTempDirectory("ui-builder-service-store")
    val first = service(root)
    create(first, "checkout")
    create(first, "settings")
    val document =
      Files.list(designDirectory(root, "checkout")).use { paths ->
        paths.filter { it.fileName.toString().startsWith("document-") }.toList()
      }
    Files.writeString(document.single(), "not json")

    val reopened = service(root)

    val unusable = reopened.adminUnusableDesigns()
    assertTrue("checkout" in unusable, "the unreadable design is named: $unusable")
    assertIs<UiBuilderServiceResponse.Snapshot>(
      execute(reopened, owner, UiBuilderServiceRequest.OpenDesign("settings")),
      "and every other design still serves",
    )
  }

  @Test
  fun `a design that cannot be read can still be retired`() {
    val root = createTempDirectory("ui-builder-service-store")
    val first = service(root)
    create(first, "checkout")
    create(first, "settings")
    Files.writeString(
      Files.list(designDirectory(root, "checkout"))
        .use { paths -> paths.filter { it.fileName.toString().startsWith("document-") }.toList() }
        .single(),
      "not json",
    )
    val reopened = service(root)

    // Download and repair genuinely cannot work on a design whose document would not decode; being
    // able to retire it is what keeps the quarantine from being a one-way door.
    assertTrue(reopened.adminDeleteDesign("checkout"))

    assertTrue("checkout" !in reopened.adminUnusableDesigns())
    assertFalse(Files.exists(designDirectory(root, "checkout")))
    assertIs<UiBuilderServiceResponse.Snapshot>(
      execute(reopened, owner, UiBuilderServiceRequest.OpenDesign("settings"))
    )
  }

  @Test
  fun `retiring a design that is neither stored nor quarantined is still false`() {
    val service = service(createTempDirectory("ui-builder-service-store"))
    assertFalse(service.adminDeleteDesign("nothing-here"))
  }

  private fun designDirectory(root: Path, designId: String): Path =
    root.resolve("designs/${FileUiBuilderDesignStore.slug(designId)}")

  private fun fingerprint(directory: Path): Map<String, String> {
    val entries = mutableMapOf<String, String>()
    if (!Files.isDirectory(directory)) return entries
    Files.walk(directory).use { stream ->
      stream.forEach { path ->
        if (Files.isRegularFile(path)) {
          entries[directory.relativize(path).toString()] = Files.readString(path)
        }
      }
    }
    return entries
  }

  private fun service(root: Path): PersistentUiBuilderService =
    PersistentUiBuilderService(
      designStore = UiBuilderDesignStateStore.open(root),
      catalogs = TestCatalogs,
      exporter = UiBuilderExportExecutor { error("no export in this test") },
      clock = Clock.fixed(Instant.ofEpochMilli(1_000), ZoneOffset.UTC),
    )

  private fun create(service: PersistentUiBuilderService, designId: String) {
    assertIs<UiBuilderServiceResponse.Snapshot>(
      execute(service, owner, UiBuilderServiceRequest.CreateDesign(document(designId)))
    )
  }

  private fun apply(
    service: PersistentUiBuilderService,
    designId: String,
    operationId: String,
    baseRevision: Long,
    vararg mutations: DesignMutationV1,
  ) {
    val response =
      execute(
        service,
        owner,
        UiBuilderServiceRequest.ApplyOperation(
          UiBuilderSubmission.Batch(
            designId,
            operationId,
            "browser",
            baseRevision,
            mutations.toList(),
          )
        ),
      )
    val outcome = assertIs<UiBuilderServiceResponse.OperationOutcome>(response)
    assertIs<AcceptedOutcomeV1>(outcome.outcome)
  }

  private fun node(id: String): DesignNodeV1 = DesignNodeV1(id = id, componentId = "m3.Text")

  private fun document(designId: String): DesignDocumentV1 =
    DesignDocumentV1(
      schema = "compose-ui-builder/v1",
      id = designId,
      title = designId,
      revision = 0,
      catalogPin = CATALOG_REFERENCE,
      environment =
        DesignEnvironmentV1(
          widthDp = 1280,
          heightDp = 800,
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
    return checkNotNull(completion) { "suspended without completing" }.getOrThrow()
  }

  private object TestCatalogs : UiBuilderCatalogExecutor {
    override fun listCatalogs(): List<CatalogCapabilityV1> = listOf(CATALOG)

    override fun resolve(reference: CatalogReferenceV1): CatalogCapabilityV1? = CATALOG.takeIf {
      reference == CATALOG_REFERENCE
    }

    override fun reference(catalog: CatalogCapabilityV1): CatalogReferenceV1? =
      CATALOG_REFERENCE.takeIf {
        catalog == CATALOG
      }

    override fun validate(
      document: DesignDocumentV1,
      catalog: CatalogCapabilityV1,
    ): UiBuilderCatalogIssue? =
      document.nodes.values
        .firstOrNull { it.componentId != "m3.Text" }
        ?.let { UiBuilderCatalogIssue("UNKNOWN_COMPONENT", "unknown component", it.id) }
  }

  private companion object {
    private val CATALOG_REFERENCE = CatalogReferenceV1("m3", "catalog", "digest", "m3-runtime")
    private val CATALOG =
      CatalogCapabilityV1(
        schema = "compose-catalog-capabilities/v1",
        benchmark = CatalogBenchmarkV1("m3", "source", "m3", "catalog", "m3-runtime"),
        components =
          listOf(
            ComponentCapabilityV1(
              componentId = "m3.Text",
              displayName = "Text",
              role = "text",
              properties = emptyList(),
              wasm = WasmCapabilityV1(JsonPrimitive(true), WasmAdapterStatusV1.SUPPORTED),
            )
          ),
        exportCapabilities = ExportCapabilitiesV1(composeCode = true, svg = true, png = true),
      )
  }
}
