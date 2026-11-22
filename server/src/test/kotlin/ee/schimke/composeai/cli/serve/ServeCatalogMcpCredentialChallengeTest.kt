package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.agentgrants.AgentGrantScope
import ee.schimke.composeai.uibuilder.service.UiBuilderServiceCall
import ee.schimke.composeai.uibuilder.service.UiBuilderServicePort
import ee.schimke.composeai.uibuilder.service.UiBuilderServiceResponse
import ee.schimke.composeai.uibuilder.service.UiBuilderServiceUpdate
import ee.schimke.composeai.uibuilder.service.UiBuilderSubscriptionCall
import java.io.Closeable
import java.util.concurrent.Semaphore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Which refusals [ServeCatalogMcp.handle] marks with a [ServeCatalogMcp.AuthorizationChallenge],
 * the mark the HTTP transport turns into a `401`. Only "nothing was presented" qualifies: a
 * credential that was presented and found wanting stays a tool error, because signing in again
 * would not change the answer.
 */
class ServeCatalogMcpCredentialChallengeTest {

  private val registry = ServeSessionRegistry(open = { null })

  private val service =
    object : UiBuilderServicePort {
      override suspend fun execute(call: UiBuilderServiceCall): UiBuilderServiceResponse =
        UiBuilderServiceResponse.Catalogs(emptyList())

      override fun subscribe(
        call: UiBuilderSubscriptionCall,
        listener: (UiBuilderServiceUpdate) -> Unit,
      ): Closeable = Closeable {}
    }

  private fun call(
    tool: String,
    arguments: String,
    uiBuilder: ServeUiBuilderMcp? = null,
    uiBuilderDecision: UiBuilderAuthorizationDecision = UiBuilderAuthorizationDecision.Missing,
    liveDecision: ServeMachineAuthorization.Decision = ServeMachineAuthorization.Decision.Missing,
  ): ServeCatalogMcp.Reply {
    val mcp = ServeCatalogMcp(registry, Semaphore(1), uiBuilder = uiBuilder)
    val body =
      Json.parseToJsonElement(
          """{"jsonrpc":"2.0","id":1,"method":"tools/call",""" +
            """"params":{"name":"$tool","arguments":$arguments}}""",
        )
        .jsonObject
    return runBlocking {
      mcp.handle(body, uiBuilderAuthorization = { _, _ -> uiBuilderDecision }) { liveDecision }
    }
  }

  private fun ServeCatalogMcp.Reply.result(): JsonObject = body!!["result"]!!.jsonObject

  @Test
  fun `a live render with no credential carries a live challenge and keeps its tool error`() {
    val reply =
      call(
        "catalog_render_preview",
        """{"catalog":"demo","previewId":"example","overrides":{"uiMode":"dark"}}""",
      )
    val challenge = assertNotNull(reply.challenge)
    assertEquals(AgentGrantScope.LIVE.wire, challenge.requiredScope)
    assertTrue(challenge.message.contains("live grant scope is required"), challenge.message)
    // The body is unchanged for a caller that reads it in-process.
    assertEquals("true", reply.result()["isError"]!!.jsonPrimitive.content)
  }

  @Test
  fun `a presented credential without live scope is not challenged`() {
    val reply =
      call(
        "catalog_render_preview",
        """{"catalog":"demo","previewId":"example","overrides":{"uiMode":"dark"}}""",
        liveDecision = ServeMachineAuthorization.Decision.Forbidden("'live' was not approved."),
      )
    assertNull(reply.challenge)
    assertEquals("true", reply.result()["isError"]!!.jsonPrimitive.content)
  }

  @Test
  fun `a UI-builder tool with no credential carries its capability as the challenge`() {
    val reply = call(ServeUiBuilderMcp.LIST_DESIGNS, "{}", uiBuilder = ServeUiBuilderMcp(service))
    val challenge = assertNotNull(reply.challenge)
    assertTrue(challenge.requiredScope.startsWith("ui-builder-"), challenge.requiredScope)
    assertEquals("true", reply.result()["isError"]!!.jsonPrimitive.content)
  }

  @Test
  fun `a UI-builder tool refused on a presented credential is not challenged`() {
    val reply =
      call(
        ServeUiBuilderMcp.LIST_DESIGNS,
        "{}",
        uiBuilder = ServeUiBuilderMcp(service),
        uiBuilderDecision = UiBuilderAuthorizationDecision.Forbidden,
      )
    assertNull(reply.challenge)
    assertEquals("true", reply.result()["isError"]!!.jsonPrimitive.content)
  }

  @Test
  fun `an ordinary tool error is not challenged`() {
    val reply = call("catalog_render_preview", "{}")
    assertNull(reply.challenge)
  }
}
