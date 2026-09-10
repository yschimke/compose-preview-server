package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.uibuilder.protocol.AnimationStateV1
import ee.schimke.composeai.uibuilder.protocol.CatalogReferenceV1
import ee.schimke.composeai.uibuilder.protocol.ComponentSourceV1
import ee.schimke.composeai.uibuilder.protocol.CreateDesignRequestV1
import ee.schimke.composeai.uibuilder.protocol.DesignComponentV1
import ee.schimke.composeai.uibuilder.protocol.DesignDocumentV1
import ee.schimke.composeai.uibuilder.protocol.DesignEnvironmentV1
import ee.schimke.composeai.uibuilder.protocol.DesignNodeV1
import ee.schimke.composeai.uibuilder.protocol.HttpRequestEnvelopeV1
import ee.schimke.composeai.uibuilder.protocol.LayoutDirectionV1
import ee.schimke.composeai.uibuilder.protocol.ServiceErrorCodeV1
import ee.schimke.composeai.uibuilder.protocol.StringValueV1
import ee.schimke.composeai.uibuilder.protocol.ThemeV1
import ee.schimke.composeai.uibuilder.protocol.WindowPostureV1
import ee.schimke.composeai.uibuilder.service.CurrentM3UiBuilderCatalogExecutor
import ee.schimke.composeai.uibuilder.service.FileUiBuilderStateStorage
import ee.schimke.composeai.uibuilder.service.PersistentUiBuilderService
import ee.schimke.composeai.uibuilder.service.UiBuilderServiceCall
import ee.schimke.composeai.uibuilder.service.UiBuilderServiceError
import ee.schimke.composeai.uibuilder.service.UiBuilderServicePort
import ee.schimke.composeai.uibuilder.service.UiBuilderServiceRequest
import ee.schimke.composeai.uibuilder.service.UiBuilderServiceResponse
import java.io.File
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.jupiter.api.io.TempDir

/**
 * `GET /api/ui-builder/v1/designs/{id}/component-drift`, over the wire the editor uses.
 *
 * The checker's own rules are unit-tested next door. What cannot be unit-tested is the claim the
 * route makes: that it is authorised **twice** — the route capability decides who may use the
 * builder, and the design's own access control, enforced by the snapshot request rather than
 * re-implemented here, decides who may read this design — and that a design the caller cannot open
 * is indistinguishable from one that does not exist. Both of those are wiring, and both fail
 * silently against a mock.
 */
class ServeUiBuilderComponentDriftRoutesTest {
  @TempDir lateinit var stateDirectory: Path

  private val json = Json {
    encodeDefaults = true
    explicitNulls = false
  }
  private val client = OkHttpClient()
  private val project = createTempDirectory("component-drift").toFile()
  private val components =
    File(project, ServeUiBuilderComponentLibrary.COMPONENTS_DIR).apply { mkdirs() }
  private val library = ServeUiBuilderComponentLibrary(fetch = { _, _ -> null })
  private val catalog =
    ServeUiBuilderDesignLibrary.Coordinate(
      system = SYSTEM,
      source = ServeUiBuilderDesignLibrary.Source.Directory(project),
    )
  private var running: RunningServer? = null

  @AfterTest
  fun tearDown() {
    running?.close()
    project.deleteRecursively()
    client.dispatcher.executorService.shutdown()
  }

  @Test
  fun `an unmoved import is reported unchanged, with the palette name the client shows`() {
    publish(title = "Contribution cell")
    val server = start()
    createDesign(server, importedDigest = publishedDigest())

    val row = drift(server, OPERATOR_TOKEN).single()

    assertEquals("unchanged", row["state"]!!.jsonPrimitive.content)
    assertEquals(COMPONENT_KEY, row["componentKey"]!!.jsonPrimitive.content)
    assertEquals(SYSTEM, row["system"]!!.jsonPrimitive.content)
    // The naming rule lives on the server and is published, so a client need not re-derive it — a
    // second copy of it could disagree, and the disagreement would be an Issues row selecting
    // nothing.
    assertEquals("project/$COMPONENT_KEY", row["paletteId"]!!.jsonPrimitive.content)
    // Nothing to show: the evidence field is for the claim that something changed.
    assertNull(row["currentDigest"])
  }

  @Test
  fun `an edited symbol is drifted, and the new digest is the evidence`() {
    publish(title = "Contribution cell")
    val imported = publishedDigest()
    val server = start()
    createDesign(server, importedDigest = imported)

    // A real edit to the symbol's body, not a reformat: the digest is taken over the component and
    // its nodes with keys sorted at every level, so only a content change moves it.
    publish(title = "Contribution cell", text = "Contributions this year")

    val row = drift(server, OPERATOR_TOKEN).single()

    assertEquals("drifted", row["state"]!!.jsonPrimitive.content)
    assertEquals(imported, row["importedDigest"]!!.jsonPrimitive.content)
    val current = row["currentDigest"]!!.jsonPrimitive.content
    assertTrue(current.startsWith("sha256:"), current)
    assertTrue(current != imported, current)
  }

  @Test
  fun `a removed symbol is withdrawn and a broken one is unusable`() {
    publish(title = "Contribution cell")
    val server = start()
    createDesign(server, importedDigest = publishedDigest())

    // Named by the index and unreadable: a broken publish or a half-written branch, which must not
    // read as a deletion — somebody told their component was removed would go looking for a symbol
    // that is still there.
    File(components, "$COMPONENT_KEY.json").writeText("{ not a document")
    assertEquals(
      "unusable",
      drift(server, OPERATOR_TOKEN).single()["state"]!!.jsonPrimitive.content,
    )

    // Not named at all: a removal.
    File(components, "index.json")
      .writeText("""{"schema":"${ServeUiBuilderComponentLibrary.INDEX_SCHEMA}","components":[]}""")
    assertEquals(
      "withdrawn",
      drift(server, OPERATOR_TOKEN).single()["state"]!!.jsonPrimitive.content,
    )
  }

  @Test
  fun `an index this host cannot read is unusable, not a removal`() {
    publish(title = "Contribution cell")
    val server = start()
    createDesign(server, importedDigest = publishedDigest())

    // The index itself, not the symbol file: a half-written index or a branch that is briefly
    // unreachable used to read out here as "the project deleted your component", because the
    // library flattened a failed read into an empty list of published components.
    File(components, "index.json").writeText("{ not an index")

    assertEquals(
      "unusable",
      drift(server, OPERATOR_TOKEN).single()["state"]!!.jsonPrimitive.content,
    )
  }

  /**
   * A refusal that is not about access keeps its own status.
   *
   * `CATALOG_UNAVAILABLE` is retryable and says so; answering it with the 404 above would tell an
   * owner their own design does not exist because a base catalog is down.
   */
  @Test
  fun `a service refusal that is not about access is not a missing design`() {
    publish(title = "Contribution cell")
    val server = start(refuseSnapshots = true)

    val (code, body) = ask(server, OPERATOR_TOKEN)

    assertEquals(409, code, body)
    assertTrue(
      Json.parseToJsonElement(body)
        .jsonObject["error"]!!
        .jsonPrimitive
        .content
        .contains("catalog is rebuilding"),
      body,
    )
  }

  @Test
  fun `an entry the index still names but this host refuses is unusable, not a removal`() {
    publish(title = "Contribution cell")
    val server = start()
    createDesign(server, importedDigest = publishedDigest())

    // The index parses and its other entries are fine; only this one is refused, for a file name
    // that cannot be joined onto a fetch URL. The project is still publishing the component — the
    // entry is broken — so this is `unusable`. It read as `withdrawn` before, because a dropped
    // entry is indistinguishable from an absent one once the index is a plain list.
    File(components, "index.json")
      .writeText(
        """{"schema":"${ServeUiBuilderComponentLibrary.INDEX_SCHEMA}",
           "components":[{"id":"$COMPONENT_KEY","title":"Cell","file":"../escape.json"}]}"""
      )

    assertEquals(
      "unusable",
      drift(server, OPERATOR_TOKEN).single()["state"]!!.jsonPrimitive.content,
    )
  }

  @Test
  fun `a component authored in the design is not a row`() {
    publish(title = "Contribution cell")
    val server = start()
    createDesign(server, importedDigest = null)

    // Nothing to drift against, and listing it as unchanged would pad the report with a row that
    // can never say anything else.
    assertEquals(emptyList(), drift(server, OPERATOR_TOKEN))
  }

  @Test
  fun `the route is gated by the builder credential before any design is read`() {
    publish(title = "Contribution cell")
    val server = start()
    createDesign(server, importedDigest = publishedDigest())

    assertEquals(401, ask(server, null).first)
    assertEquals(403, ask(server, STRANGER_TOKEN).first)
  }

  /**
   * A design this actor may not open answers exactly as one that does not exist.
   *
   * Not a nicety: distinguishing them would turn a read capability into a way of enumerating the
   * design ids a host holds.
   */
  @Test
  fun `a design the caller cannot read is a design that does not exist`() {
    publish(title = "Contribution cell")
    val server = start()
    createDesign(server, importedDigest = publishedDigest())

    val hidden = ask(server, OTHER_TOKEN)
    val absent = ask(server, OTHER_TOKEN, designId = "no-such-design")
    assertEquals(404, hidden.first)
    assertEquals(404, absent.first)
    assertEquals(
      Json.parseToJsonElement(absent.second).jsonObject.keys,
      Json.parseToJsonElement(hidden.second).jsonObject.keys,
    )
  }

  /** The digest the library computes for what is on disk right now. */
  private fun publishedDigest(): String {
    val entry = library.index(catalog).single { it.componentId == COMPONENT_KEY }
    return assertNotNull(library.symbol(catalog, entry)).digest
  }

  private fun publish(title: String, text: String = "Contributions") {
    File(components, "index.json")
      .writeText(
        """{"schema":"${ServeUiBuilderComponentLibrary.INDEX_SCHEMA}",
           "components":[{"id":"$COMPONENT_KEY","title":"$title"}]}"""
      )
    File(components, "$COMPONENT_KEY.json")
      .writeText(
        """
        {
          "schema": "compose-ui-builder-document/v1-candidate",
          "id": "$COMPONENT_KEY",
          "title": "$title",
          "revision": 0,
          "catalogPin": {
            "systemId": "$SYSTEM", "catalogRevision": "candidate",
            "capabilityDigest": "candidate", "nativeRuntimeId": "candidate"
          },
          "environment": {
            "widthDp": 412, "heightDp": 915, "density": 1.0, "theme": "dark",
            "dynamicColor": false, "locale": "en-US", "fontScale": 1.0,
            "layoutDirection": "ltr", "windowPosture": "flat", "browserZoomPercent": 100,
            "fixedTime": "2024-05-16T12:00:00Z", "animations": "settled", "networkAccess": false
          },
          "stateVariables": {},
          "roots": ["cell"],
          "nodes": {
            "cell": {
              "id": "cell", "componentId": "m3/text",
              "properties": {"text": {"type": "string", "value": "$text"}},
              "modifiers": [], "slots": {}, "eventBindings": {}
            }
          },
          "components": {
            "$COMPONENT_KEY": {"name": "$title", "root": "cell"}
          }
        }
        """
          .trimIndent()
      )
  }

  private fun drift(server: RunningServer, token: String) =
    ask(server, token).let { (code, body) ->
      assertEquals(200, code, body)
      Json.parseToJsonElement(body).jsonObject["components"]!!.jsonArray.map { it.jsonObject }
    }

  private fun ask(
    server: RunningServer,
    token: String?,
    designId: String = DESIGN_ID,
  ): Pair<Int, String> {
    val request =
      Request.Builder()
        .url(
          "http://127.0.0.1:${server.server.port}/api/ui-builder/v1/designs/$designId/component-drift"
        )
        .apply { if (token != null) header(ServeHttpServer.TOKEN_HEADER, token) }
        .build()
    client.newCall(request).execute().use {
      return it.code to (it.body?.string() ?: "")
    }
  }

  private fun createDesign(server: RunningServer, importedDigest: String?) {
    val envelope =
      HttpRequestEnvelopeV1(
        requestId = "create-$DESIGN_ID",
        actorId = "operator",
        request = CreateDesignRequestV1(document(importedDigest)),
      )
    client
      .newCall(
        Request.Builder()
          .url("http://127.0.0.1:${server.server.port}/api/ui-builder/v1/requests")
          .header(ServeHttpServer.TOKEN_HEADER, OPERATOR_TOKEN)
          .post(json.encodeToString(envelope).toRequestBody(JSON_MEDIA_TYPE))
          .build()
      )
      .execute()
      .use { assertEquals(200, it.code, it.body?.string()) }
  }

  private fun document(importedDigest: String?): DesignDocumentV1 =
    DesignDocumentV1(
      schema = "compose-ui-builder-document/v1-candidate",
      id = DESIGN_ID,
      title = "Home",
      revision = 0,
      catalogPin = CatalogReferenceV1(SYSTEM, "candidate", "candidate", "candidate"),
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
      roots = listOf("cell"),
      nodes =
        mapOf(
          "cell" to
            DesignNodeV1(
              id = "cell",
              componentId = "m3/text",
              properties = mapOf("text" to StringValueV1("Contributions")),
            )
        ),
      components =
        mapOf(
          COMPONENT_KEY to
            DesignComponentV1(
              name = "Contribution cell",
              root = "cell",
              source =
                importedDigest?.let {
                  ComponentSourceV1(system = SYSTEM, componentId = COMPONENT_KEY, digest = it)
                },
            )
        ),
    )

  private fun start(refuseSnapshots: Boolean = false): RunningServer {
    val registry = ServeSessionRegistry(open = { null })
    val service =
      PersistentUiBuilderService(
        storage = FileUiBuilderStateStorage(stateDirectory),
        catalogs = CurrentM3UiBuilderCatalogExecutor(catalogSystemIds = setOf(SYSTEM)),
        // Never exercised here — this route reads a snapshot and exports nothing — but the service
        // requires one, so it gets the same one the rest of the server runs.
        exporter =
          ScreenGeneratorComposeExportExecutor(
            ComponentRecordSource(mapOf(SYSTEM to ScreenGeneratorScreenFixture.componentsFile()))::
              record
          ),
      )
    val server =
      ServeHttpServer(
          host = "127.0.0.1",
          requestedPort = 0,
          token = OPERATOR_TOKEN,
          sessions = registry,
          defaultSessionId = "unused",
          uiBuilderService = if (refuseSnapshots) RefusingSnapshots(service) else service,
          uiBuilderAuthorization = credentials(),
          uiBuilderComponentLibrary = library,
          uiBuilderDesignCatalogs = { listOf(catalog) },
        )
        .also(ServeHttpServer::start)
    return RunningServer(server, registry).also { running = it }
  }

  /** An owner, somebody the builder refuses outright, and a reader who owns a different design. */
  private fun credentials(): ServeUiBuilderAuthorization =
    ServeUiBuilderAuthorization { call, _, presented ->
      when (presented ?: call.request.headers[ServeHttpServer.TOKEN_HEADER]) {
        OPERATOR_TOKEN -> UiBuilderAuthorizationDecision.Authorized("operator")
        OTHER_TOKEN -> UiBuilderAuthorizationDecision.Authorized("github:someone-else")
        STRANGER_TOKEN -> UiBuilderAuthorizationDecision.Forbidden
        else -> UiBuilderAuthorizationDecision.Missing
      }
    }

  /** A service whose snapshot reads are down for a reason that has nothing to do with access. */
  private class RefusingSnapshots(private val delegate: UiBuilderServicePort) :
    UiBuilderServicePort by delegate {
    override suspend fun execute(call: UiBuilderServiceCall): UiBuilderServiceResponse =
      if (call.request is UiBuilderServiceRequest.GetSnapshot)
        UiBuilderServiceResponse.Error(
          UiBuilderServiceError(
            code = ServiceErrorCodeV1.CATALOG_UNAVAILABLE,
            message = "the base catalog is rebuilding",
            retryable = true,
          )
        )
      else delegate.execute(call)
  }

  private data class RunningServer(
    val server: ServeHttpServer,
    val registry: ServeSessionRegistry,
  ) : AutoCloseable {
    override fun close() {
      server.stop()
      registry.close()
    }
  }

  private companion object {
    const val OPERATOR_TOKEN = "drift-operator-token"
    const val OTHER_TOKEN = "drift-other-reader-token"
    const val STRANGER_TOKEN = "drift-stranger-token"
    const val SYSTEM = "m3-catalog"
    const val DESIGN_ID = "drifting-screen"
    const val COMPONENT_KEY = "contribution-cell"
    val JSON_MEDIA_TYPE = "application/json".toMediaType()
  }
}
