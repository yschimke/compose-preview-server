package ee.schimke.composeai.cli.serve

import java.io.IOException
import java.net.IDN
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermissions
import java.security.KeyPair
import java.security.Signature
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * What a person can ask to be told about by push. Each is opted into separately, and each is a
 * moment where somebody is now waiting on the person being notified — the rule of thumb in
 * compose-preview-server#1299. Everything else stays in the product.
 */
internal enum class PushKind(val wire: String, val label: String) {
  /** Somebody replied on a comment thread I have taken part in. */
  REPLIES("replies", "Replies to my comment threads"),

  /** Somebody wrote `@my-login` in a comment on a design I can read. */
  MENTIONS("mentions", "@mentions of me"),

  /** A verdict recorded on a design I own, or a pull request linked as implementing it. */
  REVIEWS("reviews", "Reviews and implementations of my designs");

  companion object {
    fun parse(value: String): PushKind? = entries.firstOrNull {
      it.wire == value.trim().lowercase()
    }

    val ALL: Set<PushKind> = entries.toSet()
  }
}

/**
 * One browser's push subscription, owned by the signed-in GitHub identity that created it.
 *
 * [endpoint], [p256dh] and [auth] are **secrets**. Anybody holding the endpoint can address that
 * browser through its push service (VAPID stops them *sending*, not knowing), and the two keys are
 * what keep the payload private from the push service. So none of them is ever logged, returned by
 * a route, or reachable from MCP: the store hands them to the sender and nowhere else, and a log
 * line names a subscription by [ServeUiBuilderCommentWebhook.fingerprintOf] its endpoint.
 */
@Serializable
internal data class StoredPushSubscription(
  val endpoint: String,
  val p256dh: String,
  val auth: String,
  /** The comment board's actor id for the subscriber: `github:<login>`. */
  val actor: String,
  val kinds: Set<String>,
  val createdAt: Long,
  val lastSuccess: Long? = null,
)

@Serializable
private data class StoredPushSubscriptions(
  val version: Int = 1,
  val subscriptions: List<StoredPushSubscription> = emptyList(),
)

/** The outcome of [ServePushSubscriptionStore.subscribe]. */
internal sealed interface PushSubscribeResult {
  data class Stored(val subscription: StoredPushSubscription) : PushSubscribeResult

  data class Refused(val reason: String) : PushSubscribeResult
}

/**
 * The Web Push subscriptions on this host: one small JSON file beside the UI-builder state.
 *
 * Keyed by endpoint, because the endpoint is the browser: a second subscribe from the same browser
 * replaces the first, and when a shared machine changes hands the subscription follows whoever
 * signed in last rather than continuing to deliver the previous person's replies to it.
 *
 * Bounded per actor and in total, refusing rather than evicting, for the reason the comment store
 * gives: a refusal is something somebody can act on, and silently dropping the oldest device is a
 * notification that stops arriving with nobody told why.
 *
 * The directory is `0700` and the file `0600` ([ServeOwnerOnlyFiles]); the file holds every
 * subscriber's endpoint and keys.
 *
 * More than one process may hold this store over the same directory — two replicas during a rolling
 * deployment — so every write is a read-modify-write under a file lock, and every read re-reads the
 * file when it has changed since this process last saw it ([exclusive], [load]).
 */
internal class ServePushSubscriptionStore(
  private val root: Path,
  private val now: () -> Long = System::currentTimeMillis,
  private val maximumPerActor: Int = DEFAULT_MAXIMUM_PER_ACTOR,
  private val maximumTotal: Int = DEFAULT_MAXIMUM_TOTAL,
  /** Why an endpoint may not be stored; replaced only by tests that run a loopback receiver. */
  private val endpointRejection: (String) -> String? = ServePushEndpoints::rejection,
) {
  init {
    ServeOwnerOnlyFiles.createDirectories(root)
    require(Files.isDirectory(root)) { "push subscription root is not a directory: $root" }
  }

  private val file: Path = root.resolve(FILE_NAME)
  private val lockFile: Path = root.resolve(".$FILE_NAME.lock")

  /**
   * Shared by every store over this directory in this process. A `FileChannel` lock belongs to the
   * whole JVM, and asking for one the JVM already holds throws rather than waits, so two instances
   * here (a test, or a host that opens the store twice) queue on this monitor before the file lock.
   */
  private val lock: Any =
    PROCESS_LOCKS.computeIfAbsent(lockFile.toAbsolutePath().normalize()) { Any() }

  private var cached: List<StoredPushSubscription>? = null

  /** What the file was when [cached] was read or written; a different one means re-read it. */
  private var cachedStamp: FileStamp? = null

  fun all(): List<StoredPushSubscription> = synchronized(lock) { load() }

  fun forActor(actor: String): List<StoredPushSubscription> = all().filter { it.actor == actor }

  /**
   * The kinds [actor] has chosen: those of their newest subscription, or every kind when they have
   * none yet — which is what a first subscribe offers, before anybody has narrowed it.
   */
  fun kinds(actor: String): Set<PushKind> = kindsOf(all(), actor)

  fun subscribe(
    actor: String,
    endpoint: String,
    p256dh: String,
    auth: String,
    kinds: Set<PushKind>?,
  ): PushSubscribeResult {
    endpointRejection(endpoint)?.let {
      return PushSubscribeResult.Refused("the endpoint $it")
    }
    ServePushEndpoints.keyRejection(p256dh, auth)?.let {
      return PushSubscribeResult.Refused(it)
    }
    return exclusive {
      val current = load()
      val others = current.filter { it.endpoint != endpoint }
      val mine = others.count { it.actor == actor }
      if (mine >= maximumPerActor) {
        return@exclusive PushSubscribeResult.Refused(
          "you already have $maximumPerActor devices subscribed; turn notifications off on one first"
        )
      }
      if (others.size >= maximumTotal) {
        return@exclusive PushSubscribeResult.Refused(
          "this server holds the most push subscriptions it allows"
        )
      }
      val chosen = kinds ?: kindsOf(current, actor)
      val stored =
        StoredPushSubscription(
          endpoint = endpoint,
          p256dh = p256dh.trim().trimEnd('='),
          auth = auth.trim().trimEnd('='),
          actor = actor,
          kinds = chosen.map { it.wire }.toSortedSet(),
          createdAt = now(),
        )
      save(others + stored)
      PushSubscribeResult.Stored(stored)
    }
  }

  /** Remove [endpoint] when it belongs to [actor]; false when there was nothing of theirs there. */
  fun unsubscribe(actor: String, endpoint: String): Boolean = removeWhere {
    it.endpoint == endpoint && it.actor == actor
  }

  /** Every one of [actor]'s devices takes [kinds]; answers how many devices that was. */
  fun setKinds(actor: String, kinds: Set<PushKind>): Int = exclusive {
    val current = load()
    val wire = kinds.map { it.wire }.toSortedSet()
    var changed = 0
    val next = current.map {
      if (it.actor == actor) {
        changed++
        it.copy(kinds = wire)
      } else it
    }
    if (changed > 0) save(next)
    changed
  }

  /** The push service said this subscription is gone (404/410); forget it. */
  fun remove(endpoint: String): Boolean = removeWhere { it.endpoint == endpoint }

  fun recordSuccess(endpoint: String) {
    exclusive {
      val current = load()
      if (current.none { it.endpoint == endpoint }) return@exclusive
      save(current.map { if (it.endpoint == endpoint) it.copy(lastSuccess = now()) else it })
    }
  }

  private fun removeWhere(predicate: (StoredPushSubscription) -> Boolean): Boolean = exclusive {
    val current = load()
    val next = current.filterNot(predicate)
    if (next.size == current.size) return@exclusive false
    save(next)
    true
  }

  private fun kindsOf(current: List<StoredPushSubscription>, actor: String): Set<PushKind> =
    current
      .filter { it.actor == actor }
      .maxByOrNull { it.createdAt }
      ?.kinds
      ?.mapNotNull(PushKind::parse)
      ?.toSet() ?: PushKind.ALL

  /**
   * Read-modify-write under an exclusive lock on a sibling `.lock` file, so a second process over
   * the same directory cannot interleave.
   *
   * That second process is real: `deploy/image` keeps this directory on the shared config volume,
   * and a rolling deployment runs the retiring and the replacement replica side by side for a
   * moment. Each holds its own [cached] list; without this, whichever wrote last would rewrite the
   * file from its snapshot and silently drop the other's subscribe, unsubscribe or removal. [load]
   * re-reads the file whenever it changed under us, which inside this lock is exactly "the other
   * replica wrote". The same pattern as [ServeEngagementStore]. A filesystem that cannot lock still
   * gets the in-process lock and a warning, never a failed request.
   */
  private fun <T> exclusive(block: () -> T): T =
    synchronized(lock) {
      val channel =
        try {
          FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE)
        } catch (e: IOException) {
          System.err.println("serve: push subscription lock unavailable: ${e.message}")
          return@synchronized block()
        }
      channel.use {
        val held =
          try {
            it.lock()
          } catch (e: IOException) {
            System.err.println("serve: push subscription lock unavailable: ${e.message}")
            null
          }
        try {
          block()
        } finally {
          runCatching { held?.release() }
        }
      }
    }

  /** The subscriptions as the file holds them now; re-read only when the file has changed. */
  private fun load(): List<StoredPushSubscription> {
    val stamp = stampOf(file)
    cached?.let { if (stamp == cachedStamp) return it }
    val loaded =
      if (stamp == null) emptyList()
      else
        try {
          JSON.decodeFromString(
              StoredPushSubscriptions.serializer(),
              Files.readString(file, StandardCharsets.UTF_8),
            )
            .subscriptions
        } catch (_: IOException) {
          emptyList()
        } catch (_: SerializationException) {
          // A file this release cannot read costs everybody a re-subscribe, never the server.
          emptyList()
        }
    cached = loaded
    cachedStamp = stamp
    return loaded
  }

  private fun save(subscriptions: List<StoredPushSubscription>) {
    writeOwnerOnly(
      file,
      JSON.encodeToString(
        StoredPushSubscriptions.serializer(),
        StoredPushSubscriptions(subscriptions = subscriptions),
      ),
    )
    cached = subscriptions
    cachedStamp = stampOf(file)
  }

  /**
   * Which file this is, and which version of it. Every save is a new file moved into place, so the
   * file key (the inode, where the platform has one) changes on each write; the modified time and
   * size cover a platform without one.
   */
  private data class FileStamp(val key: Any?, val modified: FileTime, val size: Long)

  private fun stampOf(path: Path): FileStamp? =
    try {
      val attributes = Files.readAttributes(path, BasicFileAttributes::class.java)
      FileStamp(attributes.fileKey(), attributes.lastModifiedTime(), attributes.size())
    } catch (_: NoSuchFileException) {
      null
    } catch (_: IOException) {
      null
    }

  internal companion object {
    const val FILE_NAME = "subscriptions.json"
    const val DEFAULT_MAXIMUM_PER_ACTOR = 10
    const val DEFAULT_MAXIMUM_TOTAL = 10_000

    private val JSON = Json {
      encodeDefaults = true
      ignoreUnknownKeys = true
    }

    private val PROCESS_LOCKS = ConcurrentHashMap<Path, Any>()

    /**
     * [text] into [target] via a temporary file that is created `0600`, then moved into place, so
     * the file is never briefly readable by others and a crash never leaves half a file.
     */
    fun writeOwnerOnly(target: Path, text: String) {
      val temp = Files.createTempFile(target.parent, ".${target.fileName}", ".tmp")
      try {
        restrictFile(temp)
        Files.writeString(temp, text, StandardCharsets.UTF_8)
        Files.move(
          temp,
          target,
          StandardCopyOption.REPLACE_EXISTING,
          StandardCopyOption.ATOMIC_MOVE,
        )
        restrictFile(target)
      } finally {
        Files.deleteIfExists(temp)
      }
    }

    private fun restrictFile(file: Path) {
      val view = Files.getFileAttributeView(file, PosixFileAttributeView::class.java) ?: return
      runCatching { view.setPermissions(PosixFilePermissions.fromString("rw-------")) }
    }
  }
}

/**
 * Where this server may be told to POST: the guard against turning a push subscription into a
 * server-side request forgery.
 *
 * A subscription's endpoint is a URL a signed-in browser hands us, and the sender will POST to it.
 * Accepted unchecked, that is a way to make this host send requests to its own loopback, to the
 * cloud metadata service, or to anything else on its private network. So an endpoint must be
 * `https`, on the default port, with a public DNS name or a public address — checked here when it
 * is stored, and its resolved addresses checked again by the sender before every delivery, so a
 * name that later re-points at `10.0.0.1` is caught then too.
 *
 * Deliberately not an allowlist of today's push services (FCM, Mozilla autopush, Apple, Windows):
 * the standard leaves the push service to the browser, and an allowlist would refuse the next one
 * silently. The address rules are what actually stop the forgery.
 */
internal object ServePushEndpoints {
  const val MAX_ENDPOINT_CHARS = 2048

  /** Why [endpoint] is refused, as the end of a sentence, or null when it may be stored. */
  fun rejection(endpoint: String): String? {
    if (endpoint.length > MAX_ENDPOINT_CHARS) return "is too long"
    val uri = runCatching { URI(endpoint) }.getOrNull() ?: return "is not a URL"
    if (uri.scheme?.lowercase() != "https") return "must be https"
    if (uri.rawUserInfo != null) return "must not carry credentials"
    if (uri.port != -1 && uri.port != 443) return "must use the default https port"
    if (uri.rawFragment != null) return "must not carry a fragment"
    val host = uri.host?.lowercase()?.removeSurrounding("[", "]")?.trimEnd('.')
    if (host.isNullOrEmpty()) return "names no host"
    if (host == "localhost" || PRIVATE_SUFFIXES.any { host.endsWith(it) }) {
      return "must name a public host"
    }
    literalAddress(host)?.let { address ->
      if (!isPublic(address)) return "must name a public host"
    }
    if (literalAddress(host) == null && !host.contains('.')) return "must name a public host"
    if (runCatching { IDN.toASCII(host) }.isFailure) return "names an invalid host"
    return null
  }

  /** Why the browser's keys are refused, or null when they are a P-256 point and 16 bytes. */
  fun keyRejection(p256dh: String, auth: String): String? {
    val point = ServeWebPush.fromBase64Url(p256dh) ?: return "`p256dh` is not base64url"
    if (point.size != 65) return "`p256dh` must be a 65-byte uncompressed P-256 point"
    if (ServeWebPush.publicKeyFromRaw(point) == null) return "`p256dh` is not a P-256 point"
    val secret = ServeWebPush.fromBase64Url(auth) ?: return "`auth` is not base64url"
    if (secret.size != 16) return "`auth` must be 16 bytes"
    return null
  }

  /**
   * Whether every address [host] resolves to is public. The sender's second look, so a DNS answer
   * that changed after the subscription was stored cannot point a delivery inward.
   */
  fun resolvesPublic(
    host: String,
    resolve: (String) -> Array<InetAddress> = InetAddress::getAllByName,
  ): Boolean {
    val addresses = runCatching { resolve(host) }.getOrNull() ?: return false
    return addresses.isNotEmpty() && addresses.all(::isPublic)
  }

  fun isPublic(address: InetAddress): Boolean {
    if (
      address.isAnyLocalAddress ||
        address.isLoopbackAddress ||
        address.isLinkLocalAddress ||
        address.isSiteLocalAddress ||
        address.isMulticastAddress
    ) {
      return false
    }
    val bytes = address.address
    if (address is Inet4Address) {
      val a = bytes[0].toInt() and 0xff
      val b = bytes[1].toInt() and 0xff
      return when {
        a == 0 -> false
        a == 100 && b in 64..127 -> false // carrier-grade NAT
        a == 192 && b == 0 && (bytes[2].toInt() and 0xff) == 0 -> false
        a == 198 && (b == 18 || b == 19) -> false // benchmarking
        a >= 240 -> false
        else -> true
      }
    }
    if (address is Inet6Address) {
      val first = bytes[0].toInt() and 0xff
      if (first and 0xfe == 0xfc) return false // fc00::/7 unique local
      // An IPv4-mapped address is judged as the IPv4 address it carries.
      if (
        bytes.copyOfRange(0, 10).all { it == 0.toByte() } &&
          bytes[10] == 0xff.toByte() &&
          bytes[11] == 0xff.toByte()
      ) {
        return isPublic(InetAddress.getByAddress(bytes.copyOfRange(12, 16)))
      }
    }
    return true
  }

  private fun literalAddress(host: String): InetAddress? {
    val looksLiteral = host.contains(':') || host.all { it.isDigit() || it == '.' }
    if (!looksLiteral) return null
    return runCatching { InetAddress.getByName(host) }.getOrNull()
  }

  private val PRIVATE_SUFFIXES = listOf(".localhost", ".local", ".internal", ".home.arpa", ".lan")
}

/**
 * This deployment's VAPID key pair, and the subject push services may contact about it.
 *
 * Generated once and kept beside the subscriptions, because every subscription a browser holds is
 * bound to the public key it was created with: a new key pair is every subscriber silently unable
 * to receive anything until they subscribe again. That is also why a hosted deployment can pin the
 * pair through `--vapid-public-key` / `--vapid-private-key` — a rebuilt volume must not cost
 * everybody their notifications.
 */
internal class ServeVapidKeys(val keyPair: KeyPair, val subject: String) {
  val publicKey: String =
    ServeWebPush.base64Url(
      ServeWebPush.rawPublicKey(keyPair.public as java.security.interfaces.ECPublicKey)
    )

  companion object {
    const val FILE_NAME = "vapid.json"

    @Serializable private data class StoredVapid(val publicKey: String, val privateKey: String)

    private val JSON = Json { ignoreUnknownKeys = true }

    /**
     * The configured pair when both halves are given, else the one in [directory], else a new one
     * written there. A configured pair whose halves do not belong together is refused: it would
     * sign tokens no push service accepts for the key browsers subscribed with.
     */
    fun loadOrCreate(
      directory: Path,
      subject: String,
      configuredPublic: String? = null,
      configuredPrivate: String? = null,
    ): ServeVapidKeys {
      if (configuredPublic != null || configuredPrivate != null) {
        require(configuredPublic != null && configuredPrivate != null) {
          "--vapid-public-key and --vapid-private-key are given together or not at all"
        }
        return ServeVapidKeys(pairOf(configuredPublic, configuredPrivate), subject)
      }
      ServeOwnerOnlyFiles.createDirectories(directory)
      val file = directory.resolve(FILE_NAME)
      if (Files.exists(file)) {
        val stored =
          JSON.decodeFromString(
            StoredVapid.serializer(),
            Files.readString(file, StandardCharsets.UTF_8),
          )
        return ServeVapidKeys(pairOf(stored.publicKey, stored.privateKey), subject)
      }
      val generated = ServeWebPush.generateKeyPair()
      val keys = ServeVapidKeys(generated, subject)
      ServePushSubscriptionStore.writeOwnerOnly(
        file,
        JSON.encodeToString(
          StoredVapid.serializer(),
          StoredVapid(
            publicKey = keys.publicKey,
            privateKey =
              ServeWebPush.base64Url(
                ServeWebPush.rawPrivateKey(
                  generated.private as java.security.interfaces.ECPrivateKey
                )
              ),
          ),
        ),
      )
      return keys
    }

    private fun pairOf(publicKey: String, privateKey: String): KeyPair {
      val public =
        ServeWebPush.fromBase64Url(publicKey)?.let(ServeWebPush::publicKeyFromRaw)
          ?: throw IllegalArgumentException("the VAPID public key is not a base64url P-256 point")
      val private =
        ServeWebPush.fromBase64Url(privateKey)?.let(ServeWebPush::privateKeyFromRaw)
          ?: throw IllegalArgumentException("the VAPID private key is not a base64url P-256 scalar")
      val probe = "vapid".toByteArray()
      val signature =
        Signature.getInstance("SHA256withECDSA").run {
          initSign(private)
          update(probe)
          sign()
        }
      val matches =
        Signature.getInstance("SHA256withECDSA").run {
          initVerify(public)
          update(probe)
          verify(signature)
        }
      require(matches) { "the VAPID public and private keys are not a pair" }
      return KeyPair(public, private)
    }

    /**
     * The `sub` claim: the operator's own, else this deployment's https origin, else the project's
     * page. Apple refuses a token without a `mailto:` or `https:` subject, so the default is always
     * one of those — never a bare host, and never `http://localhost`.
     */
    fun subjectFor(configured: String?, origin: String?): String {
      configured
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?.let {
          return it
        }
      origin
        ?.trim()
        ?.trimEnd('/')
        ?.takeIf { it.startsWith("https://") }
        ?.let {
          return it
        }
      return DEFAULT_SUBJECT
    }

    /** Why a `--vapid-subject` is refused, or null. */
    fun subjectRejection(subject: String): String? {
      val lower = subject.lowercase()
      if (lower.startsWith("mailto:") && subject.length > "mailto:".length && '@' in subject)
        return null
      if (lower.startsWith("https://") && runCatching { URI(subject).host }.getOrNull() != null)
        return null
      return "must be a mailto: address or an https: URL"
    }

    const val DEFAULT_SUBJECT = "https://github.com/yschimke/compose-preview-server"
  }
}
