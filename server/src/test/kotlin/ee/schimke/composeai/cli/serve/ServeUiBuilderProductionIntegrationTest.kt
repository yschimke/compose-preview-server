package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.uibuilder.protocol.*
import ee.schimke.composeai.uibuilder.service.CurrentM3UiBuilderCatalogExecutor
import ee.schimke.composeai.uibuilder.service.FileUiBuilderStateStorage
import ee.schimke.composeai.uibuilder.service.PersistentUiBuilderService
import ee.schimke.composeai.uibuilder.service.RevisionPinnedUiBuilderExport
import ee.schimke.composeai.uibuilder.service.UiBuilderExportExecutor
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.jupiter.api.io.TempDir

class ServeUiBuilderProductionIntegrationTest {
  @TempDir lateinit var stateDirectory: Path

  private val json = Json {
    encodeDefaults = true
    explicitNulls = false
  }
  private val client = OkHttpClient()

  @Test
  fun `authenticated http design and compose artifact survive a complete server restart`() {
    lateinit var persisted: DesignDocumentV1
    val first = startServer()
    try {
      assertEquals(
        401,
        post(first.server.port, CreateDesignRequestV1(document()), token = null).code,
      )
      val created =
        response(post(first.server.port, CreateDesignRequestV1(document()), token = OPERATOR_TOKEN))
      persisted = assertIs<SnapshotResponseV1>(created).snapshot.state.document
      assertEquals(
        document(),
        persisted.copy(createdAtEpochMillis = null, updatedAtEpochMillis = null),
      )
    } finally {
      first.close()
    }

    val restarted = startServer()
    try {
      val opened =
        response(post(restarted.server.port, OpenDesignRequestV1(document().id), OPERATOR_TOKEN))
      assertEquals(persisted, assertIs<SnapshotResponseV1>(opened).snapshot.state.document)
      val exported =
        response(
          post(
            restarted.server.port,
            ExportDesignRequestV1(document().id, revision = 0, ExportFormatV1.COMPOSE),
            OPERATOR_TOKEN,
          )
        )
      val artifact = assertIs<ExportResponseV1>(exported).artifact
      assertEquals(ExportFormatV1.COMPOSE, artifact.format)
      assertTrue(artifact.content.contains("Restart-persistent content"))
    } finally {
      restarted.close()
    }
  }

  private fun startServer(): RunningServer {
    val registry = ServeSessionRegistry(open = { null })
    val service =
      PersistentUiBuilderService(
        storage = FileUiBuilderStateStorage(stateDirectory),
        catalogs = CurrentM3UiBuilderCatalogExecutor(),
        exporter = EchoingComposeExportExecutor(),
      )
    val server =
      ServeHttpServer(
          host = "127.0.0.1",
          requestedPort = 0,
          token = OPERATOR_TOKEN,
          sessions = registry,
          defaultSessionId = "unused",
          uiBuilderService = service,
          uiBuilderAuthorization =
            ServeUiBuilderAuthorization.fromServeIdentity(OPERATOR_TOKEN, null, null),
        )
        .also(ServeHttpServer::start)
    return RunningServer(server, registry)
  }

  private fun post(
    port: Int,
    request: UiBuilderRequestV1,
    token: String?,
  ): okhttp3.Response {
    val envelope =
      HttpRequestEnvelopeV1(
        requestId = "request-${System.nanoTime()}",
        actorId = "operator",
        request = request,
      )
    val builder =
      Request.Builder()
        .url("http://127.0.0.1:$port/api/ui-builder/v1/requests")
        .post(
          json
            .encodeToString(HttpRequestEnvelopeV1.serializer(), envelope)
            .toRequestBody(JSON_MEDIA_TYPE)
        )
    token?.let { builder.header(ServeHttpServer.TOKEN_HEADER, it) }
    return client.newCall(builder.build()).execute()
  }

  private fun response(http: okhttp3.Response): UiBuilderResponseV1 = http.use {
    val text = it.body.string()
    assertEquals(200, it.code, text)
    json.decodeFromString(HttpResponseEnvelopeV1.serializer(), text).response
  }

  private fun document(): DesignDocumentV1 =
    DesignDocumentV1(
      schema = "compose-ui-builder-document/v1-candidate",
      id = "restart-design",
      title = "Restart design",
      revision = 0,
      catalogPin = CatalogReferenceV1("m3-catalog", "candidate", "candidate", "candidate"),
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
              properties = mapOf("text" to StringValueV1("Restart-persistent content")),
            )
        ),
    )

  private data class RunningServer(
    val server: ServeHttpServer,
    val registry: ServeSessionRegistry,
  ) : AutoCloseable {
    override fun close() {
      server.stop()
      registry.close()
    }
  }

  companion object {
    private const val OPERATOR_TOKEN = "production-operator-token"
    private val JSON_MEDIA_TYPE = "application/json".toMediaType()
  }
}

/**
 * Echoes the pinned document, because this test is about **restart persistence**, not source.
 *
 * It used to inject `RevisionPinnedComposeExportExecutor`, the default this module's production
 * wiring never took — and asserting that the design's text survives a restart never needed a
 * Compose emitter, only one that carries the document through. The real generator
 * (`ScreenGeneratorComposeExportExecutor`) is exercised by
 * `ServeUiBuilderScreenExportIntegrationTest`, which supplies it the component record it needs;
 * reaching for it here would make a persistence test fail on a catalog mismatch.
 *
 * A near-twin of the fake in `ProductionUiBuilderRuntimeTest`, and deliberately not shared: the two
 * modules cannot see each other's test source without new build plumbing, and a duplicated
 * fifteen-line test double is a far cheaper duplication than the emitter this change removed.
 */
private class EchoingComposeExportExecutor : UiBuilderExportExecutor {
  override fun export(request: RevisionPinnedUiBuilderExport): ExportArtifactV1 {
    require(request.format == ExportFormatV1.COMPOSE) {
      "${request.format} export is unsupported by this executor"
    }
    val content =
      "// pinned ${request.designId}@${request.revision} ${request.documentHash}\n" +
        ECHO_JSON.encodeToString(DesignDocumentV1.serializer(), request.document)
    return ExportArtifactV1(
      format = ExportFormatV1.COMPOSE,
      mediaType = "text/x-kotlin; charset=utf-8",
      encoding = ExportEncodingV1.UTF8,
      content = content,
      // A real digest, because `PersistentUiBuilderService` verifies it — an executor
      // whose digest does not match its content is refused as an internal error, which is
      // exactly the check that caught this fake's first attempt at a hash code.
      contentDigest =
        java.security.MessageDigest.getInstance("SHA-256")
          .digest(content.toByteArray())
          .joinToString("") { "%02x".format(it) },
    )
  }
}

private val ECHO_JSON = kotlinx.serialization.json.Json { encodeDefaults = true }
