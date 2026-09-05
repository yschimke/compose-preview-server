package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.agentgrants.AgentGrantCapability
import ee.schimke.composeai.agentgrants.AgentGrantScope
import ee.schimke.composeai.uibuilder.protocol.ErrorResponseV1
import ee.schimke.composeai.uibuilder.protocol.ExportDesignRequestV1
import ee.schimke.composeai.uibuilder.protocol.ExportFormatV1
import ee.schimke.composeai.uibuilder.protocol.HttpRequestEnvelopeV1
import ee.schimke.composeai.uibuilder.protocol.HttpResponseEnvelopeV1
import ee.schimke.composeai.uibuilder.protocol.ListCatalogsRequestV1
import ee.schimke.composeai.uibuilder.protocol.ListDesignsRequestV1
import ee.schimke.composeai.uibuilder.protocol.PresenceDesignUpdateV1
import ee.schimke.composeai.uibuilder.protocol.PresenceUpsertV1
import ee.schimke.composeai.uibuilder.protocol.PresenceV1
import ee.schimke.composeai.uibuilder.protocol.ServiceErrorCodeV1
import ee.schimke.composeai.uibuilder.protocol.UpdatePresenceRequestV1
import ee.schimke.composeai.uibuilder.service.UiBuilderServiceCall
import ee.schimke.composeai.uibuilder.service.UiBuilderServicePort
import ee.schimke.composeai.uibuilder.service.UiBuilderServiceResponse
import ee.schimke.composeai.uibuilder.service.UiBuilderServiceUpdate
import ee.schimke.composeai.uibuilder.service.UiBuilderSubscriptionCall
import java.io.Closeable
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

class ServeUiBuilderRoutesTest {
  private val json = Json {
    encodeDefaults = true
    explicitNulls = false
  }
  private val calls = CopyOnWriteArrayList<UiBuilderServiceCall>()
  private val capabilities = CopyOnWriteArrayList<UiBuilderRouteCapability>()
  private val subscriptions = CopyOnWriteArrayList<UiBuilderSubscriptionCall>()
  private val subscriptionOpened = CountDownLatch(1)
  private val subscriptionClosed = CountDownLatch(1)
  @Volatile private var updateListener: ((UiBuilderServiceUpdate) -> Unit)? = null
  private val service =
    object : UiBuilderServicePort {
      override suspend fun execute(call: UiBuilderServiceCall): UiBuilderServiceResponse {
        calls += call
        return UiBuilderServiceResponse.Catalogs(emptyList())
      }

      override fun subscribe(
        call: UiBuilderSubscriptionCall,
        listener: (UiBuilderServiceUpdate) -> Unit,
      ): Closeable {
        subscriptions += call
        updateListener = listener
        subscriptionOpened.countDown()
        return Closeable {
          updateListener = null
          subscriptionClosed.countDown()
        }
      }
    }
  private val authorization = ServeUiBuilderAuthorization { call, capability ->
    capabilities += capability
    when (val actor = call.request.headers[ACTOR_HEADER]) {
      null -> UiBuilderAuthorizationDecision.Missing
      "forbidden" -> UiBuilderAuthorizationDecision.Forbidden
      else -> UiBuilderAuthorizationDecision.Authorized(actor)
    }
  }
  private val registry = ServeSessionRegistry(open = { null })
  private val server =
    ServeHttpServer(
        host = "127.0.0.1",
        requestedPort = 0,
        token = "operator-token",
        sessions = registry,
        defaultSessionId = "unused",
        uiBuilderService = service,
        uiBuilderAuthorization = authorization,
      )
      .also(ServeHttpServer::start)
  private val client = OkHttpClient()

  @AfterTest
  fun tearDown() {
    server.stop()
    registry.close()
  }

  @Test
  fun `authenticated actor and independent capabilities reach the same service port`() {
    assertEquals(200, status("actor", ListCatalogsRequestV1))
    assertEquals(200, status("actor", ListDesignsRequestV1()))
    assertEquals(
      200,
      status("actor", ExportDesignRequestV1("design", format = ExportFormatV1.SVG)),
    )

    assertEquals(
      listOf(
        UiBuilderRouteCapability.READ,
        UiBuilderRouteCapability.READ,
        UiBuilderRouteCapability.EXPORT,
      ),
      capabilities,
    )
    assertEquals(listOf("actor", "actor", "actor"), calls.map { it.actor.actorId })
  }

  @Test
  fun `serve grant authorizer keeps read write and export capabilities independent`() {
    val grantStore =
      ServeAgentGrantStore(
        maxCapabilities =
          setOf(
            AgentGrantCapability.UI_BUILDER_READ,
            AgentGrantCapability.UI_BUILDER_WRITE,
            AgentGrantCapability.UI_BUILDER_EXPORT,
          )
      )
    val request =
      requireNotNull(
        grantStore.openRequest(
          label = "read-only builder",
          client = "test",
          requestedScope = AgentGrantScope.PREVIEW,
          requestedTtlSeconds = 60,
          requestedCapabilities = setOf(AgentGrantCapability.UI_BUILDER_READ),
        )
      )
    val grant =
      requireNotNull(
        grantStore.approve(
          id = request.id,
          approvedBy = "owner",
          scope = AgentGrantScope.PREVIEW,
          ttlSeconds = 60,
          capabilities = setOf(AgentGrantCapability.UI_BUILDER_READ),
        )
      )
    val isolatedRegistry = ServeSessionRegistry(open = { null })
    val isolated =
      ServeHttpServer(
          host = "127.0.0.1",
          requestedPort = 0,
          token = "operator-token",
          sessions = isolatedRegistry,
          defaultSessionId = "unused",
          agentGrants = grantStore,
          uiBuilderService = service,
          uiBuilderAuthorization =
            ServeUiBuilderAuthorization.fromServeIdentity(
              serverToken = "operator-token",
              githubAuth = null,
              agentGrants = grantStore,
            ),
        )
        .also(ServeHttpServer::start)
    try {
      assertEquals(
        200,
        authenticatedPost(
          isolated.port,
          actorId = "agent:${grant.fingerprint}",
          request = ListCatalogsRequestV1,
          bearer = grant.token,
        ),
      )
      assertEquals(
        403,
        authenticatedPost(
          isolated.port,
          actorId = "agent:${grant.fingerprint}",
          request = ExportDesignRequestV1("design", format = ExportFormatV1.SVG),
          bearer = grant.token,
        ),
      )
      assertEquals(
        200,
        authenticatedPost(
          isolated.port,
          actorId = "operator",
          request = ExportDesignRequestV1("design", format = ExportFormatV1.SVG),
          serverToken = "operator-token",
        ),
      )
    } finally {
      isolated.stop()
      isolatedRegistry.close()
    }
  }

  @Test
  fun `missing forbidden and spoofed actors never reach the service`() {
    val missing = post(null, ListCatalogsRequestV1)
    assertEquals(401, missing.code)
    assertEquals("no-store", missing.header("Cache-Control"))
    assertEquals("Bearer", missing.header("WWW-Authenticate"))
    assertEquals(
      ServiceErrorCodeV1.UNAUTHORIZED,
      assertIs<ErrorResponseV1>(decode(missing).response).error.code,
    )

    val forbidden = post("forbidden", ListCatalogsRequestV1)
    assertEquals(403, forbidden.code)
    assertEquals(
      ServiceErrorCodeV1.FORBIDDEN,
      assertIs<ErrorResponseV1>(decode(forbidden).response).error.code,
    )

    val spoofed = post("authenticated", ListCatalogsRequestV1, envelopeActor = "somebody-else")
    assertEquals(403, spoofed.code)
    spoofed.close()

    val nestedSpoof =
      post(
        "authenticated",
        UpdatePresenceRequestV1(
          designId = "design",
          presence =
            PresenceV1(
              actorId = "somebody-else",
              clientId = "browser",
              displayName = "Spoof",
              colorArgbHex = "FF000000",
              observedRevision = 0,
            ),
        ),
      )
    assertEquals(401, nestedSpoof.code)
    assertEquals(
      ServiceErrorCodeV1.UNAUTHORIZED,
      assertIs<ErrorResponseV1>(decode(nestedSpoof).response).error.code,
    )
    assertTrue(calls.isEmpty())
  }

  @Test
  fun `websocket authenticates read reconnect cursor and closes its subscription`() {
    val opened = CountDownLatch(1)
    val received = CountDownLatch(1)
    val closed = CountDownLatch(1)
    var receivedText: String? = null
    val socket =
      client.newWebSocket(
        Request.Builder()
          .url(
            "ws://127.0.0.1:${server.port}/api/ui-builder/v1/designs/design/updates?afterSequence=41"
          )
          .header(ACTOR_HEADER, "browser")
          .build(),
        object : WebSocketListener() {
          override fun onOpen(webSocket: WebSocket, response: Response) {
            opened.countDown()
          }

          override fun onMessage(webSocket: WebSocket, text: String) {
            receivedText = text
            received.countDown()
            webSocket.close(1000, "test complete")
          }

          override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            closed.countDown()
          }
        },
      )

    assertTrue(opened.await(5, TimeUnit.SECONDS))
    assertTrue(subscriptionOpened.await(5, TimeUnit.SECONDS))
    val presence =
      PresenceV1(
        actorId = "collaborator",
        clientId = "browser-two",
        displayName = "Collaborator",
        colorArgbHex = "FF336699",
        observedRevision = 12,
      )
    updateListener?.invoke(UiBuilderServiceUpdate.Presence(PresenceUpsertV1(presence)))
    assertTrue(received.await(5, TimeUnit.SECONDS))
    assertTrue(closed.await(5, TimeUnit.SECONDS))
    assertTrue(subscriptionClosed.await(5, TimeUnit.SECONDS))
    assertEquals(1, subscriptions.size)
    assertEquals("browser", subscriptions.single().actor.actorId)
    assertEquals("design", subscriptions.single().designId)
    assertEquals(41L, subscriptions.single().afterSequence)
    assertEquals(UiBuilderRouteCapability.READ, capabilities.last())
    val update =
      json.decodeFromString(
        ee.schimke.composeai.uibuilder.protocol.DesignUpdateEnvelopeV1.serializer(),
        requireNotNull(receivedText),
      )
    assertEquals("design", update.designId)
    assertEquals(
      presence,
      assertIs<PresenceDesignUpdateV1>(update.update).update.let {
        assertIs<PresenceUpsertV1>(it).presence
      },
    )
    socket.cancel()
  }

  @Test
  fun `websocket rejects missing authentication before subscribing`() {
    val terminated = CountDownLatch(1)
    var closeCode: Int? = null
    var failure: Throwable? = null
    val socket =
      client.newWebSocket(
        Request.Builder()
          .url("ws://127.0.0.1:${server.port}/api/ui-builder/v1/designs/design/updates")
          .build(),
        object : WebSocketListener() {
          override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            closeCode = code
            terminated.countDown()
          }

          override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            closeCode = code
            terminated.countDown()
          }

          override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            failure = t
            terminated.countDown()
          }
        },
      )

    assertTrue(terminated.await(5, TimeUnit.SECONDS))
    assertTrue(closeCode == 1008 || failure != null)
    assertTrue(subscriptions.isEmpty())
    assertNull(updateListener)
    socket.cancel()
  }

  private fun post(
    authenticatedActor: String?,
    request: ee.schimke.composeai.uibuilder.protocol.UiBuilderRequestV1,
    envelopeActor: String = authenticatedActor.orEmpty(),
  ): Response {
    val body =
      json.encodeToString(
        HttpRequestEnvelopeV1(
          requestId = "request-${capabilities.size}",
          actorId = envelopeActor,
          request = request,
        )
      )
    val builder =
      Request.Builder()
        .url("http://127.0.0.1:${server.port}$UI_BUILDER_REQUEST_PATH")
        .post(body.toRequestBody())
    if (authenticatedActor != null) builder.header(ACTOR_HEADER, authenticatedActor)
    return client.newCall(builder.build()).execute()
  }

  private fun status(
    authenticatedActor: String?,
    request: ee.schimke.composeai.uibuilder.protocol.UiBuilderRequestV1,
    envelopeActor: String = authenticatedActor.orEmpty(),
  ): Int = post(authenticatedActor, request, envelopeActor).use { it.code }

  private fun authenticatedPost(
    port: Int,
    actorId: String,
    request: ee.schimke.composeai.uibuilder.protocol.UiBuilderRequestV1,
    bearer: String? = null,
    serverToken: String? = null,
  ): Int {
    val body =
      json.encodeToString(
        HttpRequestEnvelopeV1(requestId = "real-auth", actorId = actorId, request = request)
      )
    val builder =
      Request.Builder()
        .url("http://127.0.0.1:$port$UI_BUILDER_REQUEST_PATH")
        .post(body.toRequestBody())
    if (bearer != null) builder.header("Authorization", "Bearer $bearer")
    if (serverToken != null) builder.header(ServeHttpServer.TOKEN_HEADER, serverToken)
    return client.newCall(builder.build()).execute().use { it.code }
  }

  @Test
  fun `identity reports the authenticated actor the request lane demands`() {
    assertEquals(401, identity(null).use { it.code })
    assertEquals(403, identity("forbidden").use { it.code })

    val body =
      identity("github:someone").use {
        assertEquals(200, it.code)
        assertEquals(UiBuilderRouteCapability.READ, capabilities.last())
        assertEquals("no-store", it.header("Cache-Control"))
        it.body.string()
      }
    val payload = json.decodeFromString(UiBuilderIdentityV1.serializer(), body)
    assertEquals(1, payload.schemaVersion)
    assertEquals("github:someone", payload.actorId)
    // The point of the endpoint: an envelope declaring what it reports is accepted.
    assertEquals(200, status("github:someone", ListCatalogsRequestV1, payload.actorId))
  }

  private fun identity(authenticatedActor: String?): Response {
    val builder = Request.Builder().url("http://127.0.0.1:${server.port}$UI_BUILDER_IDENTITY_PATH")
    if (authenticatedActor != null) builder.header(ACTOR_HEADER, authenticatedActor)
    return client.newCall(builder.build()).execute()
  }

  @Test
  fun `device presets are read-gated and derived from the render catalog`() {
    assertEquals(401, devicePresets(null).use { it.code })
    assertEquals(403, devicePresets("forbidden").use { it.code })

    val body =
      devicePresets("actor").use {
        assertEquals(200, it.code)
        assertEquals(UiBuilderRouteCapability.READ, capabilities.last())
        it.body.string()
      }
    val payload = json.decodeFromString(UiBuilderDevicePresetsV1.serializer(), body)
    assertEquals(1, payload.schemaVersion)
    // The editor's whole reason for fetching this: the frames are the ones the backend renders.
    val tablet = payload.presets.single { it.id == "id:pixel_tablet" }
    assertEquals(1280, tablet.widthDp)
    assertEquals(800, tablet.heightDp)
    assertEquals(2.0, tablet.density)
    assertEquals("Pixel Tablet", tablet.label)
    assertEquals("Tablets", tablet.group)
  }

  private fun devicePresets(authenticatedActor: String?): Response {
    val builder =
      Request.Builder().url("http://127.0.0.1:${server.port}$UI_BUILDER_DEVICE_PRESETS_PATH")
    if (authenticatedActor != null) builder.header(ACTOR_HEADER, authenticatedActor)
    return client.newCall(builder.build()).execute()
  }

  private fun decode(response: Response): HttpResponseEnvelopeV1 = response.use {
    json.decodeFromString(HttpResponseEnvelopeV1.serializer(), it.body.string())
  }

  private companion object {
    const val ACTOR_HEADER = "X-Test-Ui-Builder-Actor"
  }
}
