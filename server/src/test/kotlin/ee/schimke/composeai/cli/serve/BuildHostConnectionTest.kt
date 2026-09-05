package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.buildhost.BuildHostCodec
import ee.schimke.composeai.buildhost.BuildHostEnvelope
import ee.schimke.composeai.buildhost.BuildHostEvent
import ee.schimke.composeai.buildhost.BuildHostProtocol
import ee.schimke.composeai.buildhost.BuildHostRequest
import ee.schimke.composeai.buildhost.BuildHostResponse
import java.io.StringWriter
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Drives [BuildHostConnection] over strings rather than a process.
 *
 * The behaviour under test is what happens when the far end misbehaves — dies mid-request, answers
 * out of order, speaks a version this server does not — because that is what a real build host on a
 * user's machine will eventually do, and none of it is reachable through a happy-path integration
 * test.
 */
class BuildHostConnectionTest {

  private val logged = mutableListOf<String>()

  private fun connect(vararg lines: String): Pair<BuildHostConnection, StringWriter> {
    val sent = StringWriter()
    val connection =
      BuildHostConnection(
        requests = sent,
        responses =
          lines.joinToString("\n").let { if (it.isEmpty()) it else it + "\n" }.reader().buffered(),
        onLog = { logged += it },
      )
    return connection to sent
  }

  private fun response(id: Long, response: BuildHostResponse): String =
    BuildHostCodec.encode(BuildHostEnvelope(id, response = response))

  private fun event(id: Long, line: String): String =
    BuildHostCodec.encode(BuildHostEnvelope(id, event = BuildHostEvent.Log(line)))

  @Test
  fun `a handshake at the same version succeeds`() {
    val (connection, sent) =
      connect(response(1, BuildHostResponse.Handshake(BuildHostProtocol.VERSION, "1.78.0")))

    assertTrue(connection.handshake())
    assertNull(connection.unusable)
    assertContains(sent.toString(), "handshake")
  }

  /** A version that disagrees means the messages mean different things. Stop asking. */
  @Test
  fun `a refused handshake makes the connection unusable`() {
    val (connection, _) =
      connect(response(1, BuildHostResponse.Failure("protocol mismatch: 9 vs 1")))

    assertTrue(!connection.handshake())
    assertContains(assertNotNull(connection.unusable), "protocol mismatch")
    assertNull(connection.exchange(BuildHostRequest.GradleProjects), "kept talking after a refusal")
  }

  @Test
  fun `a handshake answered by the wrong message is refused`() {
    val (connection, _) = connect(response(1, BuildHostResponse.Strings(listOf("x"))))

    assertTrue(!connection.handshake())
    assertNotNull(connection.unusable)
  }

  /**
   * The host exiting must not hang the server on a stream that will never produce another line, and
   * must not raise — the caller's answer is to serve without a build host.
   */
  @Test
  fun `an exited host yields null rather than blocking or throwing`() {
    val (connection, _) = connect()

    assertNull(connection.exchange(BuildHostRequest.GradleProjects))
    assertContains(assertNotNull(connection.unusable), "exited")
  }

  /** Log events precede the response they belong to, and must not be mistaken for it. */
  @Test
  fun `events before a response are forwarded and the response still arrives`() {
    val (connection, _) =
      connect(
        response(1, BuildHostResponse.Handshake(BuildHostProtocol.VERSION, "1.78.0")),
        event(2, "> Task :app:compileKotlin"),
        event(2, "> Task :app:composePreviewRenderAll"),
        response(2, BuildHostResponse.BuildResult(buildOk = true)),
      )
    connection.handshake()

    val result =
      assertIs<BuildHostResponse.BuildResult>(connection.exchange(BuildHostRequest.GradleProjects))

    assertTrue(result.buildOk)
    assertEquals(listOf("> Task :app:compileKotlin", "> Task :app:composePreviewRenderAll"), logged)
  }

  /** An event tagged with another operation is not this caller's output to print. */
  @Test
  fun `an event for a different operation is not forwarded`() {
    val (connection, _) =
      connect(
        response(1, BuildHostResponse.Handshake(BuildHostProtocol.VERSION, "1.78.0")),
        event(99, "output from something else"),
        response(2, BuildHostResponse.BuildResult(buildOk = true)),
      )
    connection.handshake()
    connection.exchange(BuildHostRequest.GradleProjects)

    assertEquals(emptyList(), logged)
  }

  /** A stale response from an operation already abandoned must not be returned as this one's. */
  @Test
  fun `a response with the wrong id is skipped`() {
    val (connection, _) =
      connect(
        response(1, BuildHostResponse.Handshake(BuildHostProtocol.VERSION, "1.78.0")),
        response(97, BuildHostResponse.Strings(listOf("stale"))),
        response(2, BuildHostResponse.Strings(listOf("mine"))),
      )
    connection.handshake()

    val strings =
      assertIs<BuildHostResponse.Strings>(connection.exchange(BuildHostRequest.GradleVariantArgs))

    assertEquals(listOf("mine"), strings.values)
  }

  /**
   * A line this server cannot parse came from a NEWER host. The protocol version gates the changes
   * that alter meaning, so skipping is right — failing would make every additive message breaking.
   */
  @Test
  fun `an unparseable line is skipped rather than fatal`() {
    val (connection, _) =
      connect(
        response(1, BuildHostResponse.Handshake(BuildHostProtocol.VERSION, "1.78.0")),
        """{"id":2,"response":{"kind":"somethingFromTheFuture"}}""",
        response(2, BuildHostResponse.Strings(listOf("ok"))),
      )
    connection.handshake()

    val strings =
      assertIs<BuildHostResponse.Strings>(connection.exchange(BuildHostRequest.GradleVariantArgs))

    assertEquals(listOf("ok"), strings.values)
    assertNull(connection.unusable)
  }

  /** A failed operation is ordinary. It must not poison the connection the way a refusal does. */
  @Test
  fun `an operation failure leaves the connection usable`() {
    val (connection, _) =
      connect(
        response(1, BuildHostResponse.Handshake(BuildHostProtocol.VERSION, "1.78.0")),
        response(2, BuildHostResponse.Failure("no gradlew above us")),
        response(3, BuildHostResponse.Strings(listOf("still here"))),
      )
    connection.handshake()

    assertIs<BuildHostResponse.Failure>(connection.exchange(BuildHostRequest.GradleProjects))
    assertNull(connection.unusable, "an ordinary build failure poisoned the connection")
    assertIs<BuildHostResponse.Strings>(connection.exchange(BuildHostRequest.GradleVariantArgs))
  }

  @Test
  fun `each request gets a fresh id`() {
    val (connection, sent) =
      connect(
        response(1, BuildHostResponse.Handshake(BuildHostProtocol.VERSION, "1.78.0")),
        response(2, BuildHostResponse.Strings(emptyList())),
        response(3, BuildHostResponse.Strings(emptyList())),
      )
    connection.handshake()
    connection.exchange(BuildHostRequest.GradleVariantArgs)
    connection.exchange(BuildHostRequest.GradleVariantArgs)

    val ids =
      sent.toString().lines().filter { it.isNotBlank() }.map { BuildHostCodec.decode(it).id }
    assertEquals(listOf(1L, 2L, 3L), ids)
  }
}

class BuildHostDiscoveryTest {

  private val nothingOnPath: (String) -> java.io.File? = { null }

  @Test
  fun `the flag wins over the environment`() {
    val choice =
      BuildHostDiscovery.choose(
        listOf("--build-host", "/opt/from-flag"),
        env = { "/opt/from-env" },
        pathLookup = nothingOnPath,
      )

    assertEquals("/opt/from-flag", assertNotNull(choice).binary)
    assertEquals(BuildHostDiscovery.FLAG, choice.source)
  }

  @Test
  fun `the environment wins over PATH`() {
    val choice =
      BuildHostDiscovery.choose(
        emptyList(),
        env = { "/opt/from-env" },
        pathLookup = { java.io.File("/usr/bin/compose-preview") },
      )

    assertEquals("/opt/from-env", assertNotNull(choice).binary)
    assertEquals(BuildHostDiscovery.ENV, choice.source)
  }

  @Test
  fun `PATH is the last resort and says so`() {
    val choice =
      BuildHostDiscovery.choose(
        emptyList(),
        env = { null },
        pathLookup = { java.io.File("/usr/bin/compose-preview") },
      )

    assertEquals("/usr/bin/compose-preview", assertNotNull(choice).binary)
    assertEquals("PATH", choice.source)
  }

  /** No build host is the common deployed case, not an error. */
  @Test
  fun `nothing found is null`() {
    assertNull(BuildHostDiscovery.choose(emptyList(), env = { null }, pathLookup = nothingOnPath))
  }

  /** An operator who wants it definitely unused must be able to say so despite a PATH hit. */
  @Test
  fun `none disables the search`() {
    assertNull(
      BuildHostDiscovery.choose(
        listOf("--build-host", "none"),
        env = { null },
        pathLookup = { java.io.File("/usr/bin/compose-preview") },
      )
    )
  }

  @Test
  fun `none is also honoured from the environment`() {
    assertNull(
      BuildHostDiscovery.choose(
        emptyList(),
        env = { "none" },
        pathLookup = { java.io.File("/usr/bin/compose-preview") },
      )
    )
  }

  @Test
  fun `a flag with no value falls through rather than consuming the next flag`() {
    val choice =
      BuildHostDiscovery.choose(
        listOf("--build-host"),
        env = { null },
        pathLookup = nothingOnPath,
      )

    assertNull(choice)
  }
}
