package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.uibuilder.protocol.AnimationStateV1
import ee.schimke.composeai.uibuilder.protocol.CatalogReferenceV1
import ee.schimke.composeai.uibuilder.protocol.DesignDocumentV1
import ee.schimke.composeai.uibuilder.protocol.DesignEnvironmentV1
import ee.schimke.composeai.uibuilder.protocol.DesignNodeV1
import ee.schimke.composeai.uibuilder.protocol.LayoutDirectionV1
import ee.schimke.composeai.uibuilder.protocol.ServiceErrorCodeV1
import ee.schimke.composeai.uibuilder.protocol.StringValueV1
import ee.schimke.composeai.uibuilder.protocol.ThemeV1
import ee.schimke.composeai.uibuilder.protocol.WindowPostureV1
import ee.schimke.composeai.uibuilder.service.AuthenticatedUiBuilderActor
import ee.schimke.composeai.uibuilder.service.CurrentM3UiBuilderCatalogExecutor
import ee.schimke.composeai.uibuilder.service.FileUiBuilderStateStorage
import ee.schimke.composeai.uibuilder.service.PersistentUiBuilderService
import ee.schimke.composeai.uibuilder.service.UiBuilderServiceCall
import ee.schimke.composeai.uibuilder.service.UiBuilderServicePort
import ee.schimke.composeai.uibuilder.service.UiBuilderServiceRequest
import ee.schimke.composeai.uibuilder.service.UiBuilderServiceResponse
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir

/**
 * `--ui-builder-default-visibility public` against the real design service: what the wrapper grants
 * is what the runtime then enforces, which a mock could only assert about itself.
 */
class ServeUiBuilderPublicDesignIntegrationTest {
  @TempDir lateinit var stateDirectory: Path

  private val owner = AuthenticatedUiBuilderActor("github:owner")
  private val anonymous = AuthenticatedUiBuilderActor(ServeUiBuilderVisibility.ANONYMOUS_ACTOR_ID)
  private val stranger = AuthenticatedUiBuilderActor("github:stranger")

  private fun service(visibility: UiBuilderDefaultVisibility): UiBuilderServicePort =
    ServeUiBuilderVisibility.withDefault(
      PersistentUiBuilderService(
        storage = FileUiBuilderStateStorage(stateDirectory),
        catalogs = CurrentM3UiBuilderCatalogExecutor(catalogSystemIds = setOf(CATALOG)),
        exporter =
          ScreenGeneratorComposeExportExecutor(
            ComponentRecordSource(mapOf(CATALOG to ScreenGeneratorScreenFixture.componentsFile()))::
              record
          ),
      ),
      visibility,
    )

  private fun UiBuilderServicePort.run(
    actor: AuthenticatedUiBuilderActor,
    request: UiBuilderServiceRequest,
  ): UiBuilderServiceResponse = runBlocking { execute(UiBuilderServiceCall(actor, request)) }

  @Test
  fun `a public default lets anyone open a new design, read-only, and lists it to nobody else`() {
    val service = service(UiBuilderDefaultVisibility.PUBLIC)
    assertIs<UiBuilderServiceResponse.Snapshot>(
      service.run(owner, UiBuilderServiceRequest.CreateDesign(document()))
    )

    for (reader in listOf(anonymous, stranger)) {
      assertIs<UiBuilderServiceResponse.Snapshot>(
        service.run(reader, UiBuilderServiceRequest.OpenDesign(DESIGN_ID)),
        "${reader.actorId} opens a public design",
      )
      val listed =
        assertIs<UiBuilderServiceResponse.Designs>(
          service.run(reader, UiBuilderServiceRequest.ListDesigns(null, 10))
        )
      assertTrue(listed.designs.isEmpty(), "a public design is not in ${reader.actorId}'s list")
    }
    val renamed = service.run(stranger, UiBuilderServiceRequest.RenameDesign(DESIGN_ID, "Mine"))
    assertEquals(
      ServiceErrorCodeV1.FORBIDDEN,
      assertIs<UiBuilderServiceResponse.Error>(renamed).error.code,
    )
  }

  @Test
  fun `a private default keeps a new design to its owner`() {
    val service = service(UiBuilderDefaultVisibility.PRIVATE)
    service.run(owner, UiBuilderServiceRequest.CreateDesign(document()))
    val refused = service.run(anonymous, UiBuilderServiceRequest.OpenDesign(DESIGN_ID))
    assertEquals(
      ServiceErrorCodeV1.FORBIDDEN,
      assertIs<UiBuilderServiceResponse.Error>(refused).error.code,
    )
  }

  private fun document(): DesignDocumentV1 =
    DesignDocumentV1(
      schema = "compose-ui-builder-document/v1-candidate",
      id = DESIGN_ID,
      title = "Public screen",
      revision = 0,
      catalogPin = CatalogReferenceV1(CATALOG, "candidate", "candidate", "candidate"),
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
      roots = listOf("text"),
      nodes =
        mapOf(
          "text" to
            DesignNodeV1(
              id = "text",
              componentId = "m3/text",
              properties = mapOf("text" to StringValueV1("Hello")),
            )
        ),
    )

  private companion object {
    const val CATALOG = "m3-catalog"
    const val DESIGN_ID = "public-screen"
  }
}
