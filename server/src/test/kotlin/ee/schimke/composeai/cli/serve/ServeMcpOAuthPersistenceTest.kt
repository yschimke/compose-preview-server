package ee.schimke.composeai.cli.serve

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ServeMcpOAuthPersistenceTest {
  private fun withRegistry(test: (java.nio.file.Path) -> Unit) {
    val directory = Files.createTempDirectory("oauth-registry-test-")
    try {
      test(directory.resolve("state/clients.json"))
    } finally {
      directory.toFile().deleteRecursively()
    }
  }

  @Test
  fun `restart retains public registration but drops authorization and refresh state`() =
    withRegistry { file ->
      val first = ServeMcpOAuth.Store(file) { 1000L }
      val client =
        assertNotNull(first.register("MCP host", listOf("https://host.example/callback")))
      val pending =
        assertNotNull(
          first.open(
            "request",
            client.clientId,
            client.redirectUris.single(),
            "challenge",
            "state",
            "https://server.example/mcp",
          )
        )
      val refresh = assertNotNull(first.issueRefresh("grant", client.clientId))
      val second = ServeMcpOAuth.Store(file) { 2000L }
      val restored = assertNotNull(second.client(client.clientId))
      assertEquals(client.clientName, restored.clientName)
      assertEquals(client.redirectUris, restored.redirectUris)
      assertEquals(client.issuedAtMillis, restored.issuedAtMillis)
      assertTrue(ServeMcpOAuth.isRegisteredRedirect(restored, "https://host.example/callback"))
      assertFalse(ServeMcpOAuth.isRegisteredRedirect(restored, "https://evil.example/callback"))
      assertNull(second.client("unknown"))
      assertNull(second.forRequest("request"))
      assertNull(second.redeem(pending.code))
      assertNull(second.redeemRefresh(refresh, client.clientId))
      val json = Files.readString(file)
      assertFalse(json.contains(pending.code))
      assertFalse(json.contains(refresh))
    }

  @Test
  fun `active cached client survives original registration lifetime and restart`() =
    withRegistry { file ->
      var now = 0L
      val first = ServeMcpOAuth.Store(file) { now }
      val client = assertNotNull(first.register("cached", listOf("https://host.example/callback")))
      now = 20 * 86_400_000L
      assertNotNull(first.client(client.clientId))
      now = 40 * 86_400_000L
      val second = ServeMcpOAuth.Store(file) { now }
      assertNotNull(second.client(client.clientId))
      now += (ServeMcpOAuth.CLIENT_TTL_SECONDS + 1) * 1000
      assertNull(ServeMcpOAuth.Store(file) { now }.client(client.clientId))
    }

  @Test
  fun `overlapping stores retain each others registrations`() = withRegistry { file ->
    val first = ServeMcpOAuth.Store(file)
    val second = ServeMcpOAuth.Store(file)
    val a = assertNotNull(first.register("first", listOf("https://a.example/callback")))
    val b = assertNotNull(second.register("second", listOf("https://b.example/callback")))
    assertNotNull(first.client(b.clientId))
    assertNotNull(second.client(a.clientId))
    assertEquals(2, ServeMcpOAuth.Store(file).clientCount())
  }

  @Test
  fun `registration cap survives restart and releases inactive registrations`() =
    withRegistry { file ->
      var now = 0L
      val first = ServeMcpOAuth.Store(file) { now }
      repeat(ServeMcpOAuth.MAX_REGISTERED_CLIENTS) {
        assertNotNull(first.register("client $it", listOf("https://host.example/callback")))
      }
      val second = ServeMcpOAuth.Store(file) { now }
      assertNull(second.register("full", listOf("https://host.example/callback")))
      now += (ServeMcpOAuth.CLIENT_TTL_SECONDS + 1) * 1000
      assertNotNull(second.register("free", listOf("https://host.example/callback")))
      assertEquals(1, ServeMcpOAuth.Store(file) { now }.clientCount())
    }

  @Test
  fun `persisted registry and directory are owner only on POSIX filesystems`() =
    withRegistry { file ->
      val store = ServeMcpOAuth.Store(file)
      assertNotNull(store.register("private label", listOf("https://host.example/callback")))
      if (
        Files.getFileAttributeView(
          file,
          java.nio.file.attribute.PosixFileAttributeView::class.java,
        ) != null
      ) {
        assertEquals(
          java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"),
          Files.getPosixFilePermissions(file),
        )
        assertEquals(
          java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"),
          Files.getPosixFilePermissions(file.parent),
        )
      }
    }

  @Test
  fun `corrupt state or unwritable directory does not silently fall back to memory`() =
    withRegistry { file ->
      Files.createDirectories(file.parent)
      Files.writeString(file, "invalid JSON")
      assertFails { ServeMcpOAuth.Store(file) }
      Files.delete(file)
      val blocker = file.parent.resolve("not-a-directory")
      Files.writeString(blocker, "blocked")
      assertFails { ServeMcpOAuth.Store(blocker.resolve("clients.json")) }
    }
}
