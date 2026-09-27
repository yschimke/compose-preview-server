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
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.jupiter.api.io.TempDir

/**
 * A `--public` box whose designs default to public: the link works for a signed-out visitor and
 * unfurls as the design, a private design unfurls as nothing but the builder, and the history page
 * offers what the reader may do with each revision.
 */
class ServeUiBuilderHistoryAndUnfurlTest {
  @TempDir lateinit var stateDirectory: Path

  private val json = Json {
    encodeDefaults = true
    explicitNulls = false
  }
  private val client = OkHttpClient.Builder().followRedirects(false).build()
  private val catalogs = CurrentM3UiBuilderCatalogExecutor(catalogSystemIds = linkedSetOf(CATALOG))

  private fun withServer(
    visibility: UiBuilderDefaultVisibility,
    body: (Int) -> Unit,
  ) {
    val builderDir = Files.createTempDirectory("serve-ui-builder-history").toFile()
    File(builderDir, "index.html")
      .writeText(
        "<!doctype html><html><head><title>Compose UI Builder Preview</title></head></html>"
      )
    val registry = ServeSessionRegistry(open = { null })
    val server =
      ServeHttpServer(
          host = "127.0.0.1",
          requestedPort = 0,
          token = OPERATOR_TOKEN,
          sessions = registry,
          defaultSessionId = "unused",
          isPublic = true,
          uiBuilderDir = builderDir,
          uiBuilderCatalogs = setOf(CATALOG),
          uiBuilderService =
            ServeUiBuilderVisibility.withDefault(
              PersistentUiBuilderService(
                storage = FileUiBuilderStateStorage(stateDirectory),
                catalogs = catalogs,
                exporter = { error("this test never exports") },
              ),
              visibility,
            ),
          uiBuilderAuthorization =
            ServeUiBuilderAuthorization.fromMachineAuthorization(
              ServeMachineAuthorization(OPERATOR_TOKEN, null, null, isPublic = true)
            ),
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

  @Test
  fun `a public design opens signed out and unfurls as itself`() {
    withServer(UiBuilderDefaultVisibility.PUBLIC) { port ->
      create(port)
      val (code, page) = get(port, "/ui-builder/$DESIGN_ID", token = null)
      assertEquals(200, code, page)
      assertTrue(page.contains("<title>Keynote schedule</title>"), page)
      assertTrue(page.contains("""<meta property="og:title" content="Keynote schedule">"""), page)
      assertTrue(
        page.contains("/api/ui-builder/v1/designs/$DESIGN_ID/thumbnail.png?revision="),
        "the design's own picture is the card image: $page",
      )
      // Installable from inside the builder, as from any other page: the shell links the manifest,
      // and the manifest (like the icons) is served without a credential.
      assertTrue(page.contains("""<link rel="manifest" href="/manifest.webmanifest">"""), page)
      val (manifestCode, manifest) = get(port, "/manifest.webmanifest", token = null)
      assertEquals(200, manifestCode)
      assertTrue(manifest.contains("\"display\":\"standalone\""), manifest)
      assertTrue(manifest.contains("/ui-builder/designs"), "the designs shortcut: $manifest")
      assertEquals(200, get(port, "/icons/app-512.png", token = null).first)
      // Signed out is still not "mine": the designs page asks who you are.
      assertEquals(401, get(port, "/ui-builder/designs", token = null).first)
    }
  }

  @Test
  fun `a private design serves only the generic shell signed out and never lends its title`() {
    withServer(UiBuilderDefaultVisibility.PRIVATE) { port ->
      create(port)
      val (signedOutCode, signedOutPage) = get(port, "/ui-builder/$DESIGN_ID", token = null)
      assertEquals(200, signedOutCode)
      assertFalse(
        signedOutPage.contains("Keynote schedule"),
        "a private design must not unfurl signed out: $signedOutPage",
      )
      assertTrue(
        signedOutPage.contains("""<meta property="og:title" content="Compose UI builder">"""),
        signedOutPage,
      )
      val (code, page) = get(port, "/ui-builder/$DESIGN_ID", OPERATOR_TOKEN)
      assertEquals(200, code)
      assertFalse(page.contains("Keynote schedule"), "a private design unfurls generically: $page")
      assertTrue(page.contains("""<meta property="og:title" content="Compose UI builder">"""), page)
    }
  }

  @Test
  fun `history lists revisions, forks one into a new design, and offers nothing to a stranger`() {
    withServer(UiBuilderDefaultVisibility.PUBLIC) { port ->
      create(port)
      val (code, page) = get(port, "/ui-builder/$DESIGN_ID/history", OPERATOR_TOKEN)
      assertEquals(200, code, page)
      assertTrue(page.contains("Revision 0"), page)
      assertTrue(page.contains("/revisions/0/thumbnail.png"), page)
      assertTrue(page.contains("/history/0/fork"), "the owner may fork: $page")

      // A signed-out visitor may look at a public design's history, and do nothing with it.
      val (anonymousCode, anonymousPage) = get(port, "/ui-builder/$DESIGN_ID/history", null)
      assertEquals(200, anonymousCode)
      assertFalse(anonymousPage.contains("/fork"), anonymousPage)
      assertFalse(anonymousPage.contains("/restore"), anonymousPage)

      val fork =
        client
          .newCall(
            Request.Builder()
              .url("http://127.0.0.1:$port/ui-builder/$DESIGN_ID/history/0/fork")
              .header(ServeHttpServer.TOKEN_HEADER, OPERATOR_TOKEN)
              .post(FormBody.Builder().build())
              .build()
          )
          .execute()
          .use { it.code to it.header("Location").orEmpty() }
      assertEquals(303, fork.first)
      assertTrue(fork.second.startsWith("/ui-builder/$DESIGN_ID-r0-"), fork.second)
      assertEquals(200, get(port, fork.second, OPERATOR_TOKEN).first)
    }
  }

  private fun get(port: Int, path: String, token: String?): Pair<Int, String> {
    val builder = Request.Builder().url("http://127.0.0.1:$port$path").header("Accept", "text/html")
    token?.let { builder.header(ServeHttpServer.TOKEN_HEADER, it) }
    return client.newCall(builder.build()).execute().use { it.code to it.body.string() }
  }

  private fun create(port: Int) {
    val envelope =
      HttpRequestEnvelopeV1(
        requestId = "request-$DESIGN_ID",
        actorId = "operator",
        request = CreateDesignRequestV1(document()),
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

  private fun document(): DesignDocumentV1 {
    val capability = catalogs.listCatalogs().single().benchmark
    return DesignDocumentV1(
      schema = "compose-ui-builder-document/v1-candidate",
      id = DESIGN_ID,
      title = "Keynote schedule",
      revision = 0,
      catalogPin =
        CatalogReferenceV1(
          systemId = CATALOG,
          catalogRevision = capability.catalogRevision,
          capabilityDigest = "candidate",
          nativeRuntimeId = capability.nativeRuntimeId,
        ),
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
              properties = mapOf("text" to StringValueV1("Opening keynote")),
            )
        ),
    )
  }

  private companion object {
    const val OPERATOR_TOKEN = "ui-builder-history-operator-token"
    const val CATALOG = "m3-catalog"
    const val DESIGN_ID = "keynote-schedule"
  }
}
