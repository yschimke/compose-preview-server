package ee.schimke.composeai.cli.serve

import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Public registrations only: no grants, tokens, PKCE verifiers or authorization codes. */
internal class ServeMcpOAuthClients(file: Path?, private val clock: () -> Long) {
  private val file = file?.toAbsolutePath()?.normalize()
  private val memory = mutableMapOf<String, ServeMcpOAuth.RegisteredClient>()

  @Serializable
  private data class Snapshot(
    val version: Int = 1,
    val clients: List<ServeMcpOAuth.RegisteredClient>,
  )

  init {
    // Fail at startup on unreadable/corrupt state instead of silently forgetting registrations.
    transaction {}
  }

  fun register(name: String, redirectUris: List<String>): ServeMcpOAuth.RegisteredClient? =
    transaction { clients ->
      if (clients.size >= ServeMcpOAuth.MAX_REGISTERED_CLIENTS) null
      else {
        val client =
          ServeMcpOAuth.RegisteredClient(
            clientId = ServeMcpOAuth.randomId(),
            clientName = ServeAgentGrantStore.sanitizeLabel(name),
            redirectUris = redirectUris,
            issuedAtMillis = clock(),
          )
        clients[client.clientId] = client
        client
      }
    }

  fun client(id: String?): ServeMcpOAuth.RegisteredClient? {
    if (id == null) return null
    return transaction { clients ->
      clients[id]?.copy(lastUsedAtMillis = clock())?.also { clients[id] = it }
    }
  }

  fun purge() = transaction {}

  fun clear() = transaction { it.clear() }

  fun count(): Int = transaction { it.size }

  /** Reload under an OS lock so overlapping deployments cannot overwrite each other's clients. */
  @Synchronized
  private fun <T> transaction(
    action: (MutableMap<String, ServeMcpOAuth.RegisteredClient>) -> T
  ): T {
    val target = file
    if (target == null) {
      memory.entries.removeIf { it.value.isExpired(clock()) }
      return action(memory)
    }
    synchronized(locks.computeIfAbsent(target) { Any() }) {
      ServeOwnerOnlyFiles.createDirectories(target.parent)
      val lockFile = target.resolveSibling("${target.fileName}.lock")
      FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel
        ->
        channel.lock().use {
          val clients = read(target)
          val before = clients.toMap()
          clients.entries.removeIf { it.value.isExpired(clock()) }
          val result = action(clients)
          if (before != clients) write(target, clients.values.toList())
          return result
        }
      }
    }
  }

  private fun read(target: Path): MutableMap<String, ServeMcpOAuth.RegisteredClient> {
    if (!Files.exists(target)) return mutableMapOf()
    require(Files.size(target) <= MAX_BYTES) { "OAuth client registry is too large: $target" }
    val snapshot = Json.decodeFromString<Snapshot>(Files.readString(target))
    require(snapshot.version == 1) { "Unsupported OAuth client registry version" }
    require(snapshot.clients.size <= ServeMcpOAuth.MAX_REGISTERED_CLIENTS)
    val clients = snapshot.clients.associateBy { it.clientId }.toMutableMap()
    require(clients.size == snapshot.clients.size && clients.keys.none { it.isBlank() }) {
      "Invalid OAuth client registry IDs"
    }
    return clients
  }

  private fun write(target: Path, clients: List<ServeMcpOAuth.RegisteredClient>) {
    val json = Json.encodeToString(Snapshot(clients = clients))
    require(json.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) {
      "OAuth client registry is too large"
    }
    val temporary = Files.createTempFile(target.parent, "oauth-clients-", ".json")
    try {
      Files.writeString(temporary, json)
      try {
        Files.move(
          temporary,
          target,
          StandardCopyOption.ATOMIC_MOVE,
          StandardCopyOption.REPLACE_EXISTING,
        )
      } catch (_: AtomicMoveNotSupportedException) {
        Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
      }
    } finally {
      Files.deleteIfExists(temporary)
    }
  }

  companion object {
    /** Config may be a read-only bind mount or the image's baked-in fallback. */
    fun defaultFile(catalogsFile: Path?, fallbackRoot: Path): Path {
      val root = catalogsFile?.toAbsolutePath()?.normalize()?.parent
      val candidate = root?.resolve("mcp-oauth/clients.json")
      if (root != null && candidate != null && Files.isDirectory(root) && Files.isWritable(root)) {
        val state = candidate.parent
        if (
          (!Files.exists(state) || (Files.isDirectory(state) && Files.isWritable(state))) &&
            (!Files.exists(candidate) || Files.isWritable(candidate))
        )
          return candidate
      }
      return fallbackRoot.toAbsolutePath().normalize().resolve("mcp-oauth/clients.json")
    }

    private const val MAX_BYTES = 8 * 1024 * 1024L
    // JVM locks prevent overlapping FileChannel locks between stores in the same process.
    private val locks = ConcurrentHashMap<Path, Any>()
  }
}
