package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.agentgrants.AgentGrantScope
import java.io.File
import java.nio.file.Files
import java.util.Base64
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * A request resolves its agent grant once, and every gate on it sees that one answer.
 *
 * The gates disagree about what an absent grant means, and that is the whole problem.
 * `rejectBadToken` refuses one; a scope gate reads it as "no grant presented, nothing to say" and
 * falls through to the human path. So a grant that died between the two did not tighten the request
 * — it **widened** it, past a scope check the door had already admitted it for.
 *
 * The window is small in wall-clock terms and entirely real: an agent knows its own expiry and can
 * aim at it. This pins the behaviour with the store's own clock rather than a sleep, so it is
 * deterministic and does not rot.
 */
class ServeAgentGrantResolutionTest {

  private val operatorToken = "operator-secret-token"
  private val registry = ServeSessionRegistry(open = { null })
  private val client = OkHttpClient.Builder().followRedirects(false).build()
  private var server: ServeHttpServer? = null

  @AfterTest
  fun tearDown() {
    server?.stop()
    registry.close()
  }

  @Test
  fun `a grant that expires between two gates is still refused, not admitted`() {
    val base = 1_000_000L
    // Static while the grant is minted; after that, valid for the FIRST reading of the request and
    // expired for every one after it. A request that resolves its credential twice therefore sees
    // it die in between — which is exactly the race, made repeatable.
    var minting = true
    var reads = 0
    val clock: () -> Long = {
      if (minting) base else if (reads++ == 0) base else base + 10_000_000L
    }

    val grants =
      ServeAgentGrantStore(
        maxScope = AgentGrantScope.PLAYGROUND,
        maxGrantTtlSeconds = 3600,
        clock = clock,
      )
    val opened =
      checkNotNull(grants.openRequest("fix #1", "10.0.0.1", AgentGrantScope.PREVIEW, 1800))
    grants.approve(opened.id, "@yuri", AgentGrantScope.PREVIEW, 600)
    val polled = grants.poll(opened.id, opened.deviceSecret)
    val token = (polled as ServeAgentGrantStore.Poll.Approved).grant.token
    minting = false

    val dir = Files.createTempDirectory("grant-resolution").toFile().also { it.deleteOnExit() }
    File(dir, "index.html").writeText("<html></html>")
    File(dir, "previews").mkdirs()
    File(dir, "previews/example.png")
      .writeBytes(
        Base64.getDecoder()
          .decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUB" +
              "AScY42YAAAAASUVORK5CYII="
          )
      )
    registry.register("demo", host = ServeBundleHost(dir, label = "demo"), pinned = true)

    val started =
      ServeHttpServer(
          host = "127.0.0.1",
          requestedPort = 0,
          token = operatorToken,
          sessions = registry,
          defaultSessionId = "demo",
          isPublic = false,
          agentGrants = grants,
          machineAuthorization =
            ServeMachineAuthorization(operatorToken, githubAuth = null, agentGrants = grants),
        )
        .also { it.start() }
    server = started

    // `?uiMode=dark` is a real override, so this route runs the token gate and then the `live`
    // scope gate — two questions about the same credential. The grant reaches `preview` only, so
    // the honest answer is 403 whichever reading the second gate gets.
    val request =
      Request.Builder()
        .url("http://127.0.0.1:${started.port}/render/example.png?uiMode=dark")
        .header(ServeHttpServer.TOKEN_HEADER, token)
        .build()
    val code = client.newCall(request).execute().use { it.code }

    // Resolved twice, this was NOT 403: the second lookup found nothing, `rejectGrantBelowScope`
    // returned "nothing to say", and the request carried on into a live render it had never been
    // approved for.
    assertEquals(403, code)
  }
}
