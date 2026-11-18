package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.cli.serve.ServeUiBuilderCatalogRecovery.Companion.recoveryCommand
import ee.schimke.composeai.uibuilder.protocol.AcceptedOutcomeV1
import ee.schimke.composeai.uibuilder.protocol.AnimationStateV1
import ee.schimke.composeai.uibuilder.protocol.ApplyOperationRequestV1
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
import ee.schimke.composeai.uibuilder.protocol.SetPropertyMutationV1
import ee.schimke.composeai.uibuilder.protocol.StringValueV1
import ee.schimke.composeai.uibuilder.protocol.ThemeV1
import ee.schimke.composeai.uibuilder.protocol.WindowPostureV1
import ee.schimke.composeai.uibuilder.service.AuthenticatedUiBuilderActor
import ee.schimke.composeai.uibuilder.service.CurrentM3UiBuilderCatalogExecutor
import ee.schimke.composeai.uibuilder.service.PersistentUiBuilderService
import ee.schimke.composeai.uibuilder.service.UiBuilderCatalogExecutor
import ee.schimke.composeai.uibuilder.service.UiBuilderDesignStateStore
import ee.schimke.composeai.uibuilder.service.UiBuilderExportExecutor
import ee.schimke.composeai.uibuilder.service.UiBuilderPersistenceException
import ee.schimke.composeai.uibuilder.service.UiBuilderServiceCall
import ee.schimke.composeai.uibuilder.service.UiBuilderServiceLimits
import ee.schimke.composeai.uibuilder.service.UiBuilderServicePort
import ee.schimke.composeai.uibuilder.service.UiBuilderServiceRequest
import ee.schimke.composeai.uibuilder.service.UiBuilderServiceResponse
import ee.schimke.composeai.uibuilder.service.UiBuilderStoreLimits
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isRegularFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir

/**
 * yschimke/watchface-tools#45: a design of about 3,900 nodes (1.8 MB canonical) at revision 28 was
 * stranded by a catalog bump, and the pin-only `upgradeCatalog` its own recovery preview offered
 * failed with a retryable `internal` error, twice.
 *
 * The cause was not the upgrade: the per-design store refused the commit (`is N bytes; limit is
 * 67108864`) because the service retains at least 32 whole copies of a design whatever its size.
 * Every write to that design would have failed the same way; the upgrade was just the first one
 * after the redeploy.
 */
class ServeUiBuilderLargeCatalogUpgradeTest {
  @TempDir lateinit var stateDirectory: Path

  private val owner = AuthenticatedUiBuilderActor(OWNER)

  private fun executor(runtimeId: String): UiBuilderCatalogExecutor =
    CurrentM3UiBuilderCatalogExecutor.Builder()
      .also {
        it.catalogSystemIds = setOf(SYSTEM)
        it.published = mapOf(SYSTEM to benchmarkCatalog())
        it.nativeRuntimeIds = mapOf(SYSTEM to runtimeId)
      }
      .build()

  private val catalogs = SwappableUiBuilderCatalogExecutor(executor("rt-old"))

  /** The service exactly as `ServeRunner` opens it. */
  private fun service(
    store: UiBuilderStoreLimits = ServeUiBuilderStorageLimits.store,
    limits: UiBuilderServiceLimits = ServeUiBuilderStorageLimits.service,
  ) =
    PersistentUiBuilderService(
      designStore = UiBuilderDesignStateStore.open(stateDirectory, store),
      catalogs = catalogs,
      exporter = UiBuilderExportExecutor { error("no export in this test") },
      limits = limits,
    )

  private fun servedPin(): CatalogReferenceV1 =
    catalogs.reference(catalogs.listCatalogs().single { it.benchmark.catalogSystemId == SYSTEM })!!

  private fun edit(service: PersistentUiBuilderService, designId: String, revision: Long) =
    runBlocking {
      service.executeMapped(
        ApplyOperationRequestV1(
          DesignCommandV1(
            designId = designId,
            operationId = "edit-$revision",
            actorId = OWNER,
            clientId = "test",
            baseRevision = revision,
            operations =
              listOf(SetPropertyMutationV1("text-$revision", "text", StringValueV1("v$revision"))),
          ),
        ),
        owner,
      )
    }

  @Test
  fun `a large design with a long history takes the pin-only upgrade its preview offers`() {
    var service = service()
    assertIs<UiBuilderServiceResponse.Snapshot>(
      runBlocking {
        service.executeMapped(CreateDesignRequestV1(document(DESIGN, servedPin(), NODES)), owner)
      },
    )
    // Past the released floor of 32 retained copies, which is where the store overflowed.
    repeat(EDITS) { revision ->
      val outcome = edit(service, DESIGN, revision.toLong())
      assertIs<AcceptedOutcomeV1>(
        assertIs<UiBuilderServiceResponse.OperationOutcome>(outcome, outcome.toString()).outcome,
      )
    }

    // The redeploy: a new runtime, and a fresh service over the same state.
    catalogs.swap(executor("rt-new"))
    service = service()
    val stranded =
      assertIs<UiBuilderServiceResponse.Error>(
        runBlocking { service.executeMapped(OpenDesignRequestV1(DESIGN), owner) },
      )
    assertEquals(ServiceErrorCodeV1.CATALOG_UNAVAILABLE, stranded.error.code)

    val preview =
      assertIs<UiBuilderServiceResponse.CatalogUpgradePreview>(
          runBlocking {
            service.execute(
              UiBuilderServiceCall(
                owner,
                UiBuilderServiceRequest.PreviewCurrentCatalogUpgrade(DESIGN),
              ),
            )
          },
        )
        .preview
    assertEquals(CatalogUpgradePreviewStatusV1.READY, preview.status)
    assertEquals(listOf("/catalogPin"), preview.changes.map { it.path }, "a pin-only move")

    // The preview's own hashes, `candidateDocumentHash` as `targetDocumentHash`, as an agent sends.
    val applied = runBlocking {
      service.executeMapped(ApplyOperationRequestV1(preview.recoveryCommand(OWNER)!!), owner)
    }
    val accepted =
      assertIs<AcceptedOutcomeV1>(
        assertIs<UiBuilderServiceResponse.OperationOutcome>(applied, applied.toString().take(500))
          .outcome,
      )
    assertEquals(EDITS + 1L, accepted.committedRevision)

    val opened =
      assertIs<UiBuilderServiceResponse.Snapshot>(
        runBlocking { service.executeMapped(OpenDesignRequestV1(DESIGN), owner) },
      )
    assertEquals("rt-new", opened.snapshot.state.document.catalogPin.nativeRuntimeId)
    assertTrue(
      designBytes() < ServeUiBuilderStorageLimits.MAXIMUM_DESIGN_BYTES,
      "stored design stays inside its budget: ${designBytes()} bytes",
    )
    // And the design keeps taking ordinary edits afterwards.
    assertIs<AcceptedOutcomeV1>(
      assertIs<UiBuilderServiceResponse.OperationOutcome>(edit(service, DESIGN, EDITS + 1L))
        .outcome,
    )
  }

  @Test
  fun `a design over its storage budget answers a non-retryable bad request, not internal`() {
    // The same overflow at a small scale: the released retention floor against a tight store.
    val service =
      service(
        store = UiBuilderStoreLimits(maximumDesignBytes = 2L * 1_024 * 1_024),
        limits = UiBuilderServiceLimits(),
      )
    assertIs<UiBuilderServiceResponse.Snapshot>(
      runBlocking {
        service.executeMapped(CreateDesignRequestV1(document(DESIGN, servedPin(), 400)), owner)
      },
    )
    val failure =
      try {
        repeat(64) { revision -> edit(service, DESIGN, revision.toLong()) }
        null
      } catch (overflow: UiBuilderPersistenceException) {
        overflow
      }
    requireNotNull(failure) { "the tight store never overflowed; the scenario no longer holds" }

    val answer = uiBuilderServiceFailure(failure)

    assertEquals(ServiceErrorCodeV1.BAD_REQUEST, answer.error.code)
    assertFalse(answer.error.retryable, "retrying an over-budget write can never succeed")
    assertTrue(answer.error.message.contains("storage budget"), answer.error.message)
    assertFalse(answer.error.message.contains(stateDirectory.toString()), "no state path leaks")
    // Anything else keeps the generic answer, since its message may carry a path.
    val other = uiBuilderServiceFailure(IllegalStateException("$stateDirectory is broken"))
    assertEquals(ServiceErrorCodeV1.INTERNAL, other.error.code)
    assertEquals("UI-builder service failed", other.error.message)

    // The stuck design heals on its next write once it runs under the server's limits: that commit
    // prunes its retained copies, which is what unlocks a design already over budget on redeploy.
    val healed = service()
    val revision =
      assertIs<UiBuilderServiceResponse.Snapshot>(
          runBlocking { healed.executeMapped(OpenDesignRequestV1(DESIGN), owner) },
        )
        .snapshot
        .state
        .document
        .revision
    assertIs<AcceptedOutcomeV1>(
      assertIs<UiBuilderServiceResponse.OperationOutcome>(edit(healed, DESIGN, revision)).outcome,
    )
  }

  @Test
  fun `one design whose upgrade throws does not end the recovery pass for the rest`() {
    val service = service()
    listOf(BROKEN, DESIGN).forEach { id ->
      assertIs<UiBuilderServiceResponse.Snapshot>(
        runBlocking {
          service.executeMapped(CreateDesignRequestV1(document(id, servedPin(), 2)), owner)
        },
      )
    }
    catalogs.swap(executor("rt-new"))
    val throwing =
      object : UiBuilderServicePort by service {
        override suspend fun execute(call: UiBuilderServiceCall): UiBuilderServiceResponse {
          val request = call.request
          if (
            request is UiBuilderServiceRequest.ApplyOperation &&
              request.submission.designId == BROKEN
          ) {
            throw UiBuilderPersistenceException(
              "UI-builder design $BROKEN is 69382499 bytes; limit is 67108864",
            )
          }
          return service.execute(call)
        }
      }
    val logs = mutableListOf<String>()

    val result = runBlocking {
      ServeUiBuilderCatalogRecovery(throwing, service, catalogs, onLog = { logs += it })
        .recoverStranded()
    }

    assertEquals(listOf(DESIGN), result.upgraded, result.toString())
    assertTrue(result.failed.getValue(BROKEN).contains("storage budget"), result.toString())
  }

  @Test
  fun `retention always fits inside the store budget it is paired with`() {
    assertTrue(
      ServeUiBuilderStorageLimits.worstCaseDesignBytes() <=
        ServeUiBuilderStorageLimits.store.maximumDesignBytes,
      "${ServeUiBuilderStorageLimits.worstCaseDesignBytes()} worst case vs " +
        "${ServeUiBuilderStorageLimits.store.maximumDesignBytes} budget",
    )
  }

  private fun designBytes(): Long =
    Files.walk(stateDirectory).use { paths ->
      paths
        .filter { it.isRegularFile() && it.toString().contains(DESIGN) }
        .mapToLong(Files::size)
        .sum()
    }

  /** A column of [count] texts, each long enough that [NODES] of them are about 2 MB canonical. */
  private fun document(id: String, pin: CatalogReferenceV1, count: Int): DesignDocumentV1 {
    val texts = (0 until count).map { "text-$it" }
    return DesignDocumentV1(
      schema = "compose-ui-builder-document/v1-candidate",
      id = id,
      title = "Large screen",
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
              slots = mapOf("children" to texts),
            ),
        ) +
          texts.associateWith {
            DesignNodeV1(
              id = it,
              componentId = "m3/text",
              properties = mapOf("text" to StringValueV1("$it: " + FILLER)),
            )
          },
    )
  }

  private companion object {
    const val SYSTEM = "m3-catalog"
    const val DESIGN = "large-watch-face"
    const val BROKEN = "over-budget"
    const val OWNER = "github:owner"
    const val NODES = 3_900
    const val EDITS = 36
    val FILLER = "lorem ipsum dolor sit amet ".repeat(16)
  }
}
