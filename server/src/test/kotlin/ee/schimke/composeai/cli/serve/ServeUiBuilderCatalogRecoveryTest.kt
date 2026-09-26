package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.cli.serve.ServeUiBuilderCatalogRecovery.Companion.recoveryCommand
import ee.schimke.composeai.uibuilder.protocol.AnimationStateV1
import ee.schimke.composeai.uibuilder.protocol.CatalogReferenceV1
import ee.schimke.composeai.uibuilder.protocol.CatalogUpgradePreviewStatusV1
import ee.schimke.composeai.uibuilder.protocol.CreateDesignRequestV1
import ee.schimke.composeai.uibuilder.protocol.DesignCommandV1
import ee.schimke.composeai.uibuilder.protocol.DesignDocumentV1
import ee.schimke.composeai.uibuilder.protocol.DesignEnvironmentV1
import ee.schimke.composeai.uibuilder.protocol.DesignNodeV1
import ee.schimke.composeai.uibuilder.protocol.LayoutDirectionV1
import ee.schimke.composeai.uibuilder.protocol.OpenDesignRequestV1
import ee.schimke.composeai.uibuilder.protocol.ServiceErrorCodeV1
import ee.schimke.composeai.uibuilder.protocol.StringValueV1
import ee.schimke.composeai.uibuilder.protocol.ThemeV1
import ee.schimke.composeai.uibuilder.protocol.WindowPostureV1
import ee.schimke.composeai.uibuilder.service.AuthenticatedUiBuilderActor
import ee.schimke.composeai.uibuilder.service.CurrentM3UiBuilderCatalogExecutor
import ee.schimke.composeai.uibuilder.service.FileUiBuilderStateStorage
import ee.schimke.composeai.uibuilder.service.PersistentUiBuilderService
import ee.schimke.composeai.uibuilder.service.UiBuilderCatalogExecutor
import ee.schimke.composeai.uibuilder.service.UiBuilderExportExecutor
import ee.schimke.composeai.uibuilder.service.UiBuilderServiceCall
import ee.schimke.composeai.uibuilder.service.UiBuilderServiceRequest
import ee.schimke.composeai.uibuilder.service.UiBuilderServiceResponse
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir

/**
 * A republished catalog moves the designs it stranded onto the pin now served, by itself, whenever
 * the move would succeed (#1054) — and records that the refresh did it, not the owner.
 */
class ServeUiBuilderCatalogRecoveryTest {
  @TempDir lateinit var stateDirectory: Path

  private val owner = AuthenticatedUiBuilderActor(OWNER)

  private fun executor(runtimeId: String): UiBuilderCatalogExecutor =
    CurrentM3UiBuilderCatalogExecutor.Builder()
      .also {
        it.catalogSystemIds = setOf(SYSTEM)
        it.nativeRuntimeIds = mapOf(SYSTEM to runtimeId)
      }
      .build()

  private val catalogs = SwappableUiBuilderCatalogExecutor(executor("rt-old"))
  private val service: PersistentUiBuilderService by lazy {
    PersistentUiBuilderService(
      storage = FileUiBuilderStateStorage(stateDirectory),
      catalogs = catalogs,
      // Nothing here exports; the service only needs somewhere to send an export it never makes.
      exporter = UiBuilderExportExecutor { error("no export in this test") },
    )
  }
  private val logs = mutableListOf<String>()
  private val recovery by lazy {
    ServeUiBuilderCatalogRecovery(service, service, catalogs, onLog = { logs += it })
  }

  private fun servedPin(): CatalogReferenceV1 =
    catalogs.reference(catalogs.listCatalogs().single { it.benchmark.catalogSystemId == SYSTEM })!!

  private fun open(): UiBuilderServiceResponse = runBlocking {
    service.executeMapped(OpenDesignRequestV1(DESIGN), owner)
  }

  @Test
  fun `a stranded design is moved onto the served pin, as the refresh on the owner's behalf`() {
    val created = runBlocking {
      service.executeMapped(CreateDesignRequestV1(document(servedPin())), owner)
    }
    assertIs<UiBuilderServiceResponse.Snapshot>(created, created.toString())
    assertTrue(
      runBlocking { recovery.recoverStranded() }.upgraded.isEmpty(),
      "nothing stranded yet",
    )

    catalogs.swap(executor("rt-new"))
    val stranded = assertIs<UiBuilderServiceResponse.Error>(open())
    assertEquals(ServiceErrorCodeV1.CATALOG_UNAVAILABLE, stranded.error.code)

    val result = runBlocking { recovery.recoverStranded() }

    assertEquals(listOf(DESIGN), result.upgraded, result.toString())
    val opened = assertIs<UiBuilderServiceResponse.Snapshot>(open(), "opens again")
    val document = opened.snapshot.state.document
    assertEquals("rt-new", document.catalogPin.nativeRuntimeId)
    assertEquals(1L, document.revision)
    val delta =
      assertIs<UiBuilderServiceResponse.Delta>(
          runBlocking {
            service.execute(
              UiBuilderServiceCall(owner, UiBuilderServiceRequest.GetDelta(DESIGN, 0, 10))
            )
          }
        )
        .delta
    assertEquals(
      ServeUiBuilderCatalogRecovery.ACTOR_ID,
      assertIs<DesignCommandV1>(delta.operations.last().submission).actorId,
      "the history says the refresh moved it, not the owner",
    )
    assertTrue(logs.single().contains("upgraded 1 design"), logs.toString())

    val again = runBlocking { recovery.recoverStranded() }
    assertTrue(
      again.upgraded.isEmpty() && again.failed.isEmpty(),
      "a second pass has nothing to do",
    )
  }

  @Test
  fun `a blocked preview is left for the owner`() {
    val preview =
      ee.schimke.composeai.uibuilder.protocol.CatalogUpgradePreviewV1(
        designId = DESIGN,
        baseRevision = 3,
        sourceCatalogPin = CatalogReferenceV1(SYSTEM, "r", "d", "rt-old"),
        targetCatalogPin = CatalogReferenceV1(SYSTEM, "r", "d", "rt-new"),
        sourceDocumentHash = "sha256:source",
        status = CatalogUpgradePreviewStatusV1.BLOCKED,
        previewDigest = "sha256:digest",
        candidateDocument = null,
        candidateDocumentHash = "sha256:candidate",
        changes = emptyList(),
        issues = emptyList(),
      )
    assertNull(preview.recoveryCommand(ServeUiBuilderCatalogRecovery.ACTOR_ID))
    val ready = preview.copy(status = CatalogUpgradePreviewStatusV1.READY)
    val command = ready.recoveryCommand(ServeUiBuilderCatalogRecovery.ACTOR_ID)!!
    assertEquals(
      3L,
      command.baseRevision,
      "an edit since the preview is a conflict, not overwritten",
    )
    assertEquals("catalog-recovery:sha256:digest", command.operationId, "the editor's own id")
  }

  private fun document(pin: CatalogReferenceV1) =
    DesignDocumentV1(
      schema = "compose-ui-builder-document/v1-candidate",
      id = DESIGN,
      title = "Stranded screen",
      revision = 0,
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
      roots = listOf("column"),
      nodes =
        mapOf(
          "column" to
            DesignNodeV1(
              id = "column",
              componentId = "layout/column",
              slots = mapOf("children" to listOf("title")),
            ),
          "title" to
            DesignNodeV1(
              id = "title",
              componentId = "m3/text",
              properties = mapOf("text" to StringValueV1("Still here")),
            ),
        ),
    )

  private companion object {
    const val SYSTEM = "m3-catalog"
    const val DESIGN = "stranded-screen"
    const val OWNER = "github:owner"
  }
}
