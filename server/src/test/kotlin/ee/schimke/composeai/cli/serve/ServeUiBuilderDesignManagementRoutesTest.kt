package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.uibuilder.protocol.AnimationStateV1
import ee.schimke.composeai.uibuilder.protocol.CatalogBenchmarkV1
import ee.schimke.composeai.uibuilder.protocol.CatalogCapabilityV1
import ee.schimke.composeai.uibuilder.protocol.CatalogReferenceV1
import ee.schimke.composeai.uibuilder.protocol.DesignAccessActionV1
import ee.schimke.composeai.uibuilder.protocol.DesignAccessControlV1
import ee.schimke.composeai.uibuilder.protocol.DesignAccessRoleV1
import ee.schimke.composeai.uibuilder.protocol.DesignActorAccessV1
import ee.schimke.composeai.uibuilder.protocol.DesignDocumentV1
import ee.schimke.composeai.uibuilder.protocol.DesignEnvironmentV1
import ee.schimke.composeai.uibuilder.protocol.DesignListItemV1
import ee.schimke.composeai.uibuilder.protocol.DesignStateV1
import ee.schimke.composeai.uibuilder.protocol.ExportCapabilitiesV1
import ee.schimke.composeai.uibuilder.protocol.LayoutDirectionV1
import ee.schimke.composeai.uibuilder.protocol.ServiceErrorCodeV1
import ee.schimke.composeai.uibuilder.protocol.ServiceSnapshotV1
import ee.schimke.composeai.uibuilder.protocol.ThemeV1
import ee.schimke.composeai.uibuilder.protocol.WindowPostureV1
import ee.schimke.composeai.uibuilder.service.UiBuilderServiceCall
import ee.schimke.composeai.uibuilder.service.UiBuilderServiceError
import ee.schimke.composeai.uibuilder.service.UiBuilderServicePort
import ee.schimke.composeai.uibuilder.service.UiBuilderServiceRequest
import ee.schimke.composeai.uibuilder.service.UiBuilderServiceResponse
import ee.schimke.composeai.uibuilder.service.UiBuilderServiceUpdate
import ee.schimke.composeai.uibuilder.service.UiBuilderSubscriptionCall
import java.io.Closeable
import java.io.File
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Managing designs from the page they are listed on: copy one, delete one, and see both offered.
 *
 * The three routes here are what turned `/ui-builder/designs` from a list into a file manager, and
 * each of them is a mutation reached by a form, so what is asserted is the same in every case: the
 * same-origin guard, the capability, and that a refusal changes nothing. The service is a stub
 * because none of that is the service's behaviour — `DeleteDesign`'s owner-only rule is already
 * `PersistentUiBuilderServiceTest`'s, and what this file pins is that the route asks it.
 */
class ServeUiBuilderDesignManagementRoutesTest {
  private val documents = ConcurrentHashMap<String, DesignDocumentV1>()
  private val deleted = CopyOnWriteArrayList<String>()

  private fun document(id: String, title: String) =
    DesignDocumentV1(
      schema = "compose-ui-builder-document/v1-candidate",
      id = id,
      title = title,
      revision = 4,
      catalogPin = CatalogReferenceV1("m3-catalog", "candidate", "candidate", "candidate"),
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
      roots = emptyList(),
      nodes = emptyMap(),
      createdAtEpochMillis = 1_700_000_000_000,
      updatedAtEpochMillis = 1_700_000_100_000,
    )

  private val service =
    object : UiBuilderServicePort {
      override suspend fun execute(call: UiBuilderServiceCall): UiBuilderServiceResponse =
        when (val request = call.request) {
          is UiBuilderServiceRequest.OpenDesign -> {
            val stored = documents[request.designId]
            if (stored == null)
              UiBuilderServiceResponse.Error(
                UiBuilderServiceError(ServiceErrorCodeV1.NOT_FOUND, "no design ${request.designId}")
              )
            else
              UiBuilderServiceResponse.Snapshot(
                ServiceSnapshotV1(
                  designId = stored.id,
                  state = DesignStateV1(lastSequence = 4, document = stored),
                  catalog = catalog,
                  retainedFromSequence = 0,
                  presence = emptyList(),
                  access = access(stored.id),
                )
              )
          }
          is UiBuilderServiceRequest.ListDesigns ->
            UiBuilderServiceResponse.Designs(
              documents.values.map {
                DesignListItemV1(
                  designId = it.id,
                  title = it.title,
                  revision = it.revision,
                  accessRevision = 1,
                  catalogPin = it.catalogPin,
                  createdAtEpochMillis = it.createdAtEpochMillis,
                  updatedAtEpochMillis = it.updatedAtEpochMillis,
                  ownerActorId = OWNER,
                  requesterAccess =
                    DesignActorAccessV1(
                      actorId = OWNER,
                      role = DesignAccessRoleV1.OWNER,
                      allowedActions = DesignAccessActionV1.entries.toList(),
                    ),
                )
              },
              null,
            )
          is UiBuilderServiceRequest.GetDesignAccess ->
            UiBuilderServiceResponse.DesignAccess(request.designId, access(request.designId))
          is UiBuilderServiceRequest.ListCatalogs ->
            UiBuilderServiceResponse.Catalogs(listOf(catalog))
          is UiBuilderServiceRequest.CreateDesign -> {
            documents[request.document.id] = request.document
            UiBuilderServiceResponse.Catalogs(emptyList())
          }
          is UiBuilderServiceRequest.DeleteDesign ->
            if (documents.remove(request.designId) == null)
              UiBuilderServiceResponse.Error(
                UiBuilderServiceError(ServiceErrorCodeV1.NOT_FOUND, "no design ${request.designId}")
              )
            else {
              deleted += request.designId
              UiBuilderServiceResponse.DesignDeleted(request.designId)
            }
          else -> UiBuilderServiceResponse.Catalogs(emptyList())
        }

      override fun subscribe(
        call: UiBuilderSubscriptionCall,
        listener: (UiBuilderServiceUpdate) -> Unit,
      ): Closeable = Closeable {}
    }

  private fun access(designId: String) =
    DesignAccessControlV1(accessRevision = 1, ownerActorId = OWNER, actorGrants = emptyList())
      .also { require(designId.isNotBlank()) }

  private val authorization = ServeUiBuilderAuthorization { call, _, _ ->
    when (val actor = call.request.headers["X-Test-Actor"]) {
      null -> UiBuilderAuthorizationDecision.Missing
      "forbidden" -> UiBuilderAuthorizationDecision.Forbidden
      else -> UiBuilderAuthorizationDecision.Authorized(actor)
    }
  }

  private val builderDir =
    Files.createTempDirectory("serve-ui-builder-manage").toFile().also { dir ->
      dir.deleteOnExit()
      File(dir, "index.html").writeText("<!doctype html><title>Compose UI builder</title>")
      File(dir, "jetcaster-discover-operations-v1.json")
        .writeText(
          File("../docs/design/fixtures/ui-builder/jetcaster-discover-operations-v1.json")
            .takeIf { it.isFile }
            ?.readText()
            ?: File("docs/design/fixtures/ui-builder/jetcaster-discover-operations-v1.json")
              .readText()
        )
    }

  private val registry = ServeSessionRegistry(open = { null })
  private val server =
    ServeHttpServer(
        host = "127.0.0.1",
        requestedPort = 0,
        token = "operator-token",
        sessions = registry,
        defaultSessionId = "unused",
        uiBuilderDir = builderDir,
        uiBuilderCatalogs = setOf("m3-catalog"),
        uiBuilderService = service,
        uiBuilderAuthorization = authorization,
      )
      .also(ServeHttpServer::start)
  private val client = OkHttpClient.Builder().followRedirects(false).build()

  init {
    documents["morning-player"] = document("morning-player", "Morning player")
  }

  @AfterTest
  fun tearDown() {
    server.stop()
    registry.close()
  }

  private fun url(path: String) = "http://127.0.0.1:${server.port}$path"

  private fun post(
    path: String,
    form: FormBody,
    actor: String? = OWNER,
    origin: String? = "same",
  ): Pair<Int, String?> {
    val builder = Request.Builder().url(url(path)).post(form)
    if (actor != null) builder.header("X-Test-Actor", actor)
    if (origin == "same") builder.header("Origin", "http://127.0.0.1:${server.port}")
    else if (origin != null) builder.header("Origin", origin)
    return client.newCall(builder.build()).execute().use { it.code to it.header("Location") }
  }

  private fun designsPage(actor: String? = OWNER): String {
    val builder = Request.Builder().url(url("/ui-builder/designs"))
    if (actor != null) builder.header("X-Test-Actor", actor)
    return client.newCall(builder.build()).execute().use { it.body.string() }
  }

  @Test
  fun `copying an existing design creates a new one and redirects to it`() {
    val (code, location) =
      post(
        "/ui-builder/designs/copy",
        FormBody.Builder()
          .add("sourceDesignId", "morning-player")
          .add("designId", "evening-player")
          .build(),
      )
    assertEquals(303, code)
    assertEquals("/ui-builder/evening-player", location)

    val copy = documents.getValue("evening-player")
    assertEquals(0, copy.revision, "a copy starts its own history")
    assertEquals("Morning player copy", copy.title)
    assertEquals("m3-catalog", copy.catalogPin.systemId)
    // The source is read, never written: its revision and title are exactly what they were.
    assertEquals(4, documents.getValue("morning-player").revision)
    assertEquals("Morning player", documents.getValue("morning-player").title)
  }

  @Test
  fun `a copy is refused when it cannot be honoured, and creates nothing`() {
    val before = documents.keys.toSet()
    assertEquals(
      404,
      post(
          "/ui-builder/designs/copy",
          FormBody.Builder()
            .add("sourceDesignId", "never-existed")
            .add("designId", "wanted")
            .build(),
        )
        .first,
    )
    assertEquals(
      400,
      post(
          "/ui-builder/designs/copy",
          FormBody.Builder()
            .add("sourceDesignId", "morning-player")
            .add("designId", "morning-player")
            .build(),
        )
        .first,
      "a copy needs an id of its own",
    )
    assertEquals(
      400,
      post(
          "/ui-builder/designs/copy",
          FormBody.Builder()
            .add("sourceDesignId", "morning-player")
            .add("designId", "../escape")
            .build(),
        )
        .first,
    )
    val wanted =
      FormBody.Builder().add("sourceDesignId", "morning-player").add("designId", "wanted").build()
    assertEquals(401, post("/ui-builder/designs/copy", wanted, actor = null).first)
    assertEquals(403, post("/ui-builder/designs/copy", wanted, actor = "forbidden").first)
    assertEquals(
      403,
      post("/ui-builder/designs/copy", wanted, origin = "https://evil.example").first,
    )
    assertEquals(before, documents.keys.toSet(), "no refusal may have created a design")
  }

  @Test
  fun `deleting is confirmed, same-origin, and answers with the index`() {
    val confirmed = FormBody.Builder().add("confirm", "delete").build()

    // The confirmation is part of the request, so a form posted without it removes nothing.
    assertEquals(
      400,
      post("/ui-builder/morning-player/delete", FormBody.Builder().build()).first,
    )
    assertEquals(
      403,
      post("/ui-builder/morning-player/delete", confirmed, origin = "https://evil.example").first,
    )
    assertEquals(401, post("/ui-builder/morning-player/delete", confirmed, actor = null).first)
    assertTrue(deleted.isEmpty(), "no refusal may have deleted a design: $deleted")

    val (code, location) = post("/ui-builder/morning-player/delete", confirmed)
    assertEquals(303, code)
    assertEquals("/ui-builder/designs", location)
    assertEquals(listOf("morning-player"), deleted)

    // The service's own refusal is the answer the second attempt gets.
    assertEquals(404, post("/ui-builder/morning-player/delete", confirmed).first)
  }

  @Test
  fun `the designs page offers a preview, a duplicate and a delete for a design you own`() {
    val page = designsPage()
    assertTrue(
      page.contains("/api/ui-builder/v1/designs/morning-player/export.svg"),
      "the card leads with the design's own render",
    )
    assertTrue(page.contains("/ui-builder/morning-player/delete"), "an owner may delete")
    assertTrue(page.contains("/ui-builder/designs/copy"), "and may start from it")
    assertTrue(page.contains("Start from an existing design"))
    assertTrue(page.contains("Start a new design"))
    // The starting points come from the seed, so the form cannot offer one the route would refuse.
    assertTrue(page.contains("""<option value="m3-catalog|blank">"""))
    assertFalse(page.contains("wear-widget-small"), "a catalog this host does not author")
  }

  @Test
  fun `the New design form accepts one control carrying catalog and template`() {
    val (code, location) =
      post(
        "/ui-builder/designs",
        FormBody.Builder().add("start", "m3-catalog|blank").add("designId", "from-one").build(),
      )
    assertEquals(303, code)
    assertEquals("/ui-builder/from-one", location)
    assertTrue(documents.containsKey("from-one"))
  }

  private companion object {
    const val OWNER = "operator"

    val catalog =
      CatalogCapabilityV1.Builder("compose-catalog-capabilities/v1", CatalogBenchmarkV1.Builder("m3", "source", "m3-catalog", "candidate", "candidate").build(), emptyList()).also { it.exportCapabilities = ExportCapabilitiesV1.Builder().also { it.composeCode = true; it.svg = false; it.png = false }.build() }.build()
  }
}
