package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.uibuilder.protocol.AnimationStateV1
import ee.schimke.composeai.uibuilder.protocol.CatalogReferenceV1
import ee.schimke.composeai.uibuilder.protocol.CreateDesignRequestV1
import ee.schimke.composeai.uibuilder.protocol.DesignDocumentV1
import ee.schimke.composeai.uibuilder.protocol.DesignEnvironmentV1
import ee.schimke.composeai.uibuilder.protocol.DesignNodeV1
import ee.schimke.composeai.uibuilder.protocol.HttpRequestEnvelopeV1
import ee.schimke.composeai.uibuilder.protocol.LayoutDirectionV1
import ee.schimke.composeai.uibuilder.protocol.StringValueV1
import ee.schimke.composeai.uibuilder.protocol.ThemeV1
import ee.schimke.composeai.uibuilder.protocol.WindowPostureV1
import ee.schimke.composeai.uibuilder.service.CurrentM3UiBuilderCatalogExecutor
import ee.schimke.composeai.uibuilder.service.FileUiBuilderStateStorage
import ee.schimke.composeai.uibuilder.service.PersistentUiBuilderService
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.jupiter.api.io.TempDir

/**
 * `/ui-builder/<designId>` — the URL anything holding only a design id builds.
 *
 * The design API is catalog-free (`PUT /api/ui-builder/v1/designs/{id}`, and
 * `ui_builder_create_design` takes a `designId` and hands back no URL), so the obvious link to a
 * design one has just created omits a segment the API never asked for, and the answer was a bare
 * `404` that reads like a deleted design (yschimke/compose-preview-server#509). The server already
 * knows the missing segment: it is the stored document's `catalogPin.systemId`.
 *
 * What the redirect must not become is an existence oracle. Designs are private to their owner and
 * collaborators; a redirect that fired for any id that exists would tell a stranger both that an id
 * is taken and which catalog it pins, and `cheeky-raccoon` ids are guessable. So the lookup is made
 * **as the caller** — the two cases below are the whole rule.
 */
class ServeUiBuilderDesignUrlRedirectTest {
  @TempDir lateinit var stateDirectory: Path

  private val json = Json {
    encodeDefaults = true
    explicitNulls = false
  }
  private val client = OkHttpClient.Builder().followRedirects(false).build()

  @Test
  fun `a design named without its catalog redirects to the catalog it pins`() {
    withServer { port ->
      create(port, "my-remote-screen", "remote-m3")
      create(port, "my-phone-screen", "m3-catalog")

      assertEquals(
        "/ui-builder/remote-m3/my-remote-screen",
        get(port, "/ui-builder/my-remote-screen", OPERATOR_TOKEN).header("Location"),
      )
      // Not a constant: the segment comes from each design's own pin.
      assertEquals(
        "/ui-builder/m3-catalog/my-phone-screen",
        get(port, "/ui-builder/my-phone-screen", OPERATOR_TOKEN).header("Location"),
      )
    }
  }

  @Test
  fun `a caller who cannot open the design gets the same 404 as before`() {
    withServer { port ->
      create(port, "my-remote-screen", "remote-m3")

      // No credential: the redirect would otherwise report that this id exists and where it lives.
      assertEquals(404, get(port, "/ui-builder/my-remote-screen", token = null).code)
      // And an id that names nothing is a 404 for everyone, which is what keeps a merely missing
      // asset from quietly rendering the app shell.
      assertEquals(404, get(port, "/ui-builder/no-such-design", OPERATOR_TOKEN).code)
      assertEquals(404, get(port, "/ui-builder/missing.mjs", OPERATOR_TOKEN).code)
    }
  }

  /** A real file wins: the redirect must never shadow a bundle asset that is actually there. */
  @Test
  fun `a bundle file named like a design is still served`() {
    withServer { port ->
      assertEquals(200, get(port, "/ui-builder/builder.mjs", OPERATOR_TOKEN).code)
    }
  }

  /** Pinned to each catalog's own revision, which is what `catalogUnavailable` refuses without. */
  private val catalogs =
    CurrentM3UiBuilderCatalogExecutor(catalogSystemIds = linkedSetOf("m3-catalog", "remote-m3"))

  private fun pin(catalog: String): CatalogReferenceV1 =
    catalogs
      .listCatalogs()
      .single { it.benchmark.catalogSystemId == catalog }
      .let {
        CatalogReferenceV1(
          systemId = catalog,
          catalogRevision = it.benchmark.catalogRevision,
          capabilityDigest = "candidate",
          nativeRuntimeId = it.benchmark.nativeRuntimeId,
        )
      }

  private fun withServer(body: (Int) -> Unit) {
    val builderDir = Files.createTempDirectory("serve-ui-builder-redirect").toFile()
    File(builderDir, "index.html").writeText("<!doctype html><title>Compose UI builder</title>")
    File(builderDir, "builder.mjs").writeText("window.composeUiBuilder = true")
    val registry = ServeSessionRegistry(open = { null })
    val server =
      ServeHttpServer(
          host = "127.0.0.1",
          requestedPort = 0,
          token = OPERATOR_TOKEN,
          sessions = registry,
          defaultSessionId = "unused",
          uiBuilderDir = builderDir,
          uiBuilderCatalogs = setOf("m3-catalog", "remote-m3"),
          uiBuilderService =
            PersistentUiBuilderService(
              storage = FileUiBuilderStateStorage(stateDirectory),
              catalogs = catalogs,
              // Nothing here exports; a design's URL is the whole subject.
              exporter = { error("this test never exports") },
            ),
          uiBuilderAuthorization =
            ServeUiBuilderAuthorization.fromServeIdentity(OPERATOR_TOKEN, null, null),
        )
        .also(ServeHttpServer::start)
    try {
      body(server.port)
    } finally {
      server.stop()
      registry.close()
      builderDir.deleteRecursively()
    }
  }

  private fun get(port: Int, path: String, token: String?): okhttp3.Response {
    val builder = Request.Builder().url("http://127.0.0.1:$port$path")
    token?.let { builder.header(ServeHttpServer.TOKEN_HEADER, it) }
    return client.newCall(builder.build()).execute().also { it.close() }
  }

  private fun create(port: Int, designId: String, catalog: String) {
    val envelope =
      HttpRequestEnvelopeV1(
        requestId = "request-$designId",
        actorId = "operator",
        request = CreateDesignRequestV1(document(designId, catalog)),
      )
    val response =
      client
        .newCall(
          Request.Builder()
            .url("http://127.0.0.1:$port/api/ui-builder/v1/requests")
            .header(ServeHttpServer.TOKEN_HEADER, OPERATOR_TOKEN)
            .post(
              json
                .encodeToString(HttpRequestEnvelopeV1.serializer(), envelope)
                .toRequestBody("application/json".toMediaType())
            )
            .build()
        )
        .execute()
    response.use { assertEquals(200, it.code, it.body.string()) }
  }

  private fun document(designId: String, catalog: String): DesignDocumentV1 =
    DesignDocumentV1(
      schema = "compose-ui-builder-document/v1-candidate",
      id = designId,
      title = designId,
      revision = 0,
      catalogPin = pin(catalog),
      environment =
        DesignEnvironmentV1(
          widthDp = 400,
          heightDp = 800,
          density = 1.0,
          theme = ThemeV1.DARK,
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
    const val OPERATOR_TOKEN = "operator-token"
  }
}
