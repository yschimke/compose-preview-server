package ee.schimke.composeai.cli.serve

import java.net.InetAddress
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFilePermissions
import java.security.interfaces.ECPublicKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class ServePushSubscriptionStoreTest {
  @TempDir lateinit var root: Path

  private val browserKey =
    ServeWebPush.base64Url(
      ServeWebPush.rawPublicKey(ServeWebPush.generateKeyPair().public as ECPublicKey)
    )
  private val auth = ServeWebPush.base64Url(ByteArray(16) { it.toByte() })

  private fun store(perActor: Int = 10, total: Int = 100) =
    ServePushSubscriptionStore(
      root.resolve("push"),
      maximumPerActor = perActor,
      maximumTotal = total,
    )

  @Test
  fun `endpoint validation refuses everything that is not a public https host`() {
    val ok =
      listOf(
        "https://fcm.googleapis.com/fcm/send/abc:def",
        "https://updates.push.services.mozilla.com/wpush/v2/gAAAA",
        "https://web.push.apple.com/QGuQyavXutnMH",
        "https://fcm.googleapis.com:443/fcm/send/x",
        "https://8.8.8.8/push",
      )
    for (endpoint in ok) assertNull(ServePushEndpoints.rejection(endpoint), endpoint)
    val refused =
      listOf(
        "http://fcm.googleapis.com/fcm/send/x",
        "ftp://example.com/x",
        "https://localhost/x",
        "https://push.localhost/x",
        "https://127.0.0.1/x",
        "https://10.0.0.5/x",
        "https://192.168.1.10/x",
        "https://172.16.0.1/x",
        "https://169.254.169.254/latest/meta-data",
        "https://100.64.0.1/x",
        "https://0.0.0.0/x",
        "https://[::1]/x",
        "https://[fd00::1]/x",
        "https://[fe80::1]/x",
        "https://[::ffff:127.0.0.1]/x",
        "https://printer.local/x",
        "https://metadata.google.internal/x",
        "https://intranet/x",
        "https://fcm.googleapis.com:8443/x",
        "https://user:pass@fcm.googleapis.com/x",
        "https://fcm.googleapis.com/x#frag",
        "not a url",
        "https://example.com/" + "a".repeat(3000),
      )
    for (endpoint in refused) assertNotNull(ServePushEndpoints.rejection(endpoint), endpoint)
  }

  @Test
  fun `resolution is judged on every address, so a public name pointing inward is refused`() {
    val inward = { _: String ->
      arrayOf(InetAddress.getByName("93.184.216.34"), InetAddress.getByName("10.1.2.3"))
    }
    assertFalse(ServePushEndpoints.resolvesPublic("push.example.com", inward))
    assertTrue(
      ServePushEndpoints.resolvesPublic("push.example.com") {
        arrayOf(InetAddress.getByName("93.184.216.34"))
      }
    )
    assertFalse(
      ServePushEndpoints.resolvesPublic("nowhere.invalid") { throw java.net.UnknownHostException() }
    )
  }

  @Test
  fun `key validation wants a P-256 point and a 16-byte secret`() {
    assertNull(ServePushEndpoints.keyRejection(browserKey, auth))
    assertNotNull(ServePushEndpoints.keyRejection(browserKey.dropLast(4), auth))
    assertNotNull(ServePushEndpoints.keyRejection(ServeWebPush.base64Url(ByteArray(65)), auth))
    assertNotNull(ServePushEndpoints.keyRejection("!!!", auth))
    assertNotNull(ServePushEndpoints.keyRejection(browserKey, ServeWebPush.base64Url(ByteArray(8))))
    assertNotNull(
      ServePushEndpoints.keyRejection(browserKey, ServeWebPush.base64Url(ByteArray(32)))
    )
  }

  @Test
  fun `a subscription persists, owner-only, and survives a restart`() {
    val first = store()
    val stored =
      first.subscribe("github:alice", ENDPOINT, browserKey, auth, setOf(PushKind.REPLIES))
    assertIs<PushSubscribeResult.Stored>(stored)
    val reopened = store()
    val loaded = reopened.forActor("github:alice").single()
    assertEquals(ENDPOINT, loaded.endpoint)
    assertEquals(setOf("replies"), loaded.kinds)
    assertEquals(setOf(PushKind.REPLIES), reopened.kinds("github:alice"))
    val file = root.resolve("push").resolve(ServePushSubscriptionStore.FILE_NAME)
    if (Files.getFileStore(file).supportsFileAttributeView("posix")) {
      assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)))
      assertEquals(
        "rwx------",
        PosixFilePermissions.toString(Files.getPosixFilePermissions(root.resolve("push"))),
      )
    }
  }

  @Test
  fun `invalid input is refused with a reason and nothing is stored`() {
    val store = store()
    val bad = store.subscribe("github:alice", "https://10.0.0.1/x", browserKey, auth, null)
    assertIs<PushSubscribeResult.Refused>(bad)
    assertTrue(bad.reason.startsWith("the endpoint"))
    assertIs<PushSubscribeResult.Refused>(
      store.subscribe("github:alice", ENDPOINT, browserKey, "short", null)
    )
    assertTrue(store.all().isEmpty())
  }

  @Test
  fun `a new person has every kind, and chosen kinds apply to all their devices`() {
    val store = store()
    assertEquals(PushKind.ALL, store.kinds("github:alice"))
    store.subscribe("github:alice", ENDPOINT, browserKey, auth, null)
    store.subscribe("github:alice", "$ENDPOINT-2", browserKey, auth, null)
    assertEquals(
      setOf("mentions", "replies", "reviews"),
      store.forActor("github:alice").first().kinds,
    )
    assertEquals(2, store.setKinds("github:alice", setOf(PushKind.MENTIONS)))
    assertTrue(store.forActor("github:alice").all { it.kinds == setOf("mentions") })
    // A third device starts with what the person last chose.
    store.subscribe("github:alice", "$ENDPOINT-3", browserKey, auth, null)
    assertEquals(setOf("mentions"), store.forActor("github:alice").last().kinds)
  }

  @Test
  fun `kinds sent with a subscribe are the person's choice on every device`() {
    val store = store()
    store.subscribe("github:alice", "$ENDPOINT-1", browserKey, auth, null)
    store.subscribe("github:alice", "$ENDPOINT-2", browserKey, auth, null)
    store.subscribe("github:bob", "$ENDPOINT-3", browserKey, auth, null)
    // A third browser signs up with a narrower choice: preferences are account-wide, so the two
    // devices alice already had follow it, and bob's are untouched.
    store.subscribe("github:alice", "$ENDPOINT-4", browserKey, auth, setOf(PushKind.MENTIONS))
    assertEquals(3, store.forActor("github:alice").size)
    assertTrue(store.forActor("github:alice").all { it.kinds == setOf("mentions") })
    assertEquals(setOf(PushKind.MENTIONS), store.kinds("github:alice"))
    assertEquals(PushKind.ALL.map { it.wire }.toSet(), store.forActor("github:bob").single().kinds)
    // And from disk, not just this process's copy.
    assertTrue(store().forActor("github:alice").all { it.kinds == setOf("mentions") })
  }

  @Test
  fun `re-posting the same subscription writes nothing, and keeps what the person chose`() {
    val store = store()
    val first = store.subscribe("github:alice", ENDPOINT, browserKey, auth, setOf(PushKind.REVIEWS))
    assertIs<PushSubscribeResult.Stored>(first)
    val file = root.resolve("push").resolve(ServePushSubscriptionStore.FILE_NAME)
    val before = Files.readAttributes(file, BasicFileAttributes::class.java).fileKey()
    val modified = Files.getLastModifiedTime(file)
    // What the settings page sends on every load: the subscription, without kinds.
    val again = store.subscribe("github:alice", ENDPOINT, browserKey, auth, null)
    assertIs<PushSubscribeResult.Stored>(again)
    assertEquals(first.subscription, again.subscription)
    assertEquals(before, Files.readAttributes(file, BasicFileAttributes::class.java).fileKey())
    assertEquals(modified, Files.getLastModifiedTime(file))
    assertEquals(setOf("reviews"), store.all().single().kinds)
  }

  @Test
  fun `a sign-out drops only the signing-out person's subscription named by the device cookie`() {
    val store = store()
    store.subscribe("github:alice", ENDPOINT, browserKey, auth, null)
    store.subscribe("github:alice", "$ENDPOINT-2", browserKey, auth, null)
    val device = ServePushSubscriptionStore.deviceOf(ENDPOINT)
    assertFalse(ENDPOINT in device, "the device id must not carry the endpoint")
    assertFalse(store.unsubscribeDevice("github:bob", device))
    assertTrue(store.unsubscribeDevice("github:alice", device))
    assertEquals(listOf("$ENDPOINT-2"), store.all().map { it.endpoint })
    assertFalse(store.unsubscribeDevice("github:alice", device))
  }

  @Test
  fun `two stores over one directory — a rolling deployment — lose neither one's writes`() {
    // Both replicas start, and each reads the file once, before either writes.
    val old = store()
    val new = store()
    assertTrue(old.all().isEmpty())
    assertTrue(new.all().isEmpty())

    old.subscribe("github:alice", "$ENDPOINT-1", browserKey, auth, null)
    // The new replica's write is a read-modify-write of the file, not of its stale snapshot.
    new.subscribe("github:bob", "$ENDPOINT-2", browserKey, auth, null)
    assertEquals(setOf("$ENDPOINT-1", "$ENDPOINT-2"), old.all().map { it.endpoint }.toSet())
    assertEquals(setOf("$ENDPOINT-1", "$ENDPOINT-2"), new.all().map { it.endpoint }.toSet())

    // A delivery on one replica sees a preference change and an unsubscribe made on the other.
    new.setKinds("github:alice", setOf(PushKind.REPLIES))
    assertEquals(setOf(PushKind.REPLIES), old.kinds("github:alice"))
    assertTrue(old.unsubscribe("github:bob", "$ENDPOINT-2"))
    assertTrue(new.forActor("github:bob").isEmpty())
    new.recordSuccess("$ENDPOINT-1")
    assertNotNull(old.all().single().lastSuccess)
    assertTrue(old.remove("$ENDPOINT-1"))
    assertTrue(new.all().isEmpty())
    assertTrue(store().all().isEmpty())
  }

  @Test
  fun `concurrent writers through separate stores lose no subscription`() {
    val stores = List(4) { store(perActor = 1000, total = 1000) }
    val threads = stores.mapIndexed { index, store ->
      Thread {
        repeat(25) { n ->
          store.subscribe("github:u$index", "$ENDPOINT-$index-$n", browserKey, auth, null)
        }
      }
    }
    threads.forEach(Thread::start)
    threads.forEach(Thread::join)
    assertEquals(100, store().all().size)
    stores.forEach { assertEquals(100, it.all().size) }
  }

  @Test
  fun `only the owner can unsubscribe, and the endpoint follows whoever signed in last`() {
    val store = store()
    store.subscribe("github:alice", ENDPOINT, browserKey, auth, null)
    assertFalse(store.unsubscribe("github:mallory", ENDPOINT))
    assertEquals(1, store.all().size)
    // The same browser, now signed in as bob: alice must stop receiving on it.
    store.subscribe("github:bob", ENDPOINT, browserKey, auth, null)
    assertTrue(store.forActor("github:alice").isEmpty())
    assertEquals(1, store.forActor("github:bob").size)
    assertTrue(store.unsubscribe("github:bob", ENDPOINT))
    assertTrue(store.all().isEmpty())
  }

  @Test
  fun `caps refuse rather than evict`() {
    val store = store(perActor = 2, total = 3)
    store.subscribe("github:alice", "$ENDPOINT-1", browserKey, auth, null)
    store.subscribe("github:alice", "$ENDPOINT-2", browserKey, auth, null)
    assertIs<PushSubscribeResult.Refused>(
      store.subscribe("github:alice", "$ENDPOINT-3", browserKey, auth, null)
    )
    // Re-subscribing an existing endpoint is a replacement, not a new device.
    assertIs<PushSubscribeResult.Stored>(
      store.subscribe("github:alice", "$ENDPOINT-2", browserKey, auth, null)
    )
    store.subscribe("github:bob", "$ENDPOINT-4", browserKey, auth, null)
    assertIs<PushSubscribeResult.Refused>(
      store.subscribe("github:carol", "$ENDPOINT-5", browserKey, auth, null)
    )
    assertEquals(2, store.forActor("github:alice").size)
  }

  @Test
  fun `success and removal are recorded`() {
    val store = store()
    store.subscribe("github:alice", ENDPOINT, browserKey, auth, null)
    assertNull(store.all().single().lastSuccess)
    store.recordSuccess(ENDPOINT)
    assertNotNull(store().all().single().lastSuccess)
    assertTrue(store.remove(ENDPOINT))
    assertFalse(store.remove(ENDPOINT))
    assertTrue(store().all().isEmpty())
  }

  @Test
  fun `VAPID keys are generated once, kept owner-only, and reused`() {
    val dir = root.resolve("push")
    val first = ServeVapidKeys.loadOrCreate(dir, "mailto:ops@example.com")
    val second = ServeVapidKeys.loadOrCreate(dir, "mailto:ops@example.com")
    assertEquals(first.publicKey, second.publicKey)
    assertEquals(65, ServeWebPush.fromBase64Url(first.publicKey)!!.size)
    val file = dir.resolve(ServeVapidKeys.FILE_NAME)
    if (Files.getFileStore(file).supportsFileAttributeView("posix")) {
      assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)))
    }
  }

  @Test
  fun `a configured VAPID pair wins, and halves that do not match are refused`() {
    val pair = ServeWebPush.generateKeyPair()
    val public = ServeWebPush.base64Url(ServeWebPush.rawPublicKey(pair.public as ECPublicKey))
    val private =
      ServeWebPush.base64Url(
        ServeWebPush.rawPrivateKey(pair.private as java.security.interfaces.ECPrivateKey)
      )
    val configured = ServeVapidKeys.loadOrCreate(root.resolve("a"), "mailto:x@y.z", public, private)
    assertEquals(public, configured.publicKey)
    assertFalse(Files.exists(root.resolve("a").resolve(ServeVapidKeys.FILE_NAME)))
    val other =
      ServeWebPush.base64Url(
        ServeWebPush.rawPublicKey(ServeWebPush.generateKeyPair().public as ECPublicKey)
      )
    assertFailsWith<IllegalArgumentException> {
      ServeVapidKeys.loadOrCreate(root.resolve("b"), "mailto:x@y.z", other, private)
    }
    assertFailsWith<IllegalArgumentException> {
      ServeVapidKeys.loadOrCreate(root.resolve("c"), "mailto:x@y.z", public, null)
    }
  }

  @Test
  fun `the VAPID subject defaults to an https origin and is always mailto or https`() {
    assertEquals(
      "mailto:ops@example.com",
      ServeVapidKeys.subjectFor("mailto:ops@example.com", null),
    )
    assertEquals(
      "https://preview.coo.ee",
      ServeVapidKeys.subjectFor(null, "https://preview.coo.ee/"),
    )
    assertEquals(
      ServeVapidKeys.DEFAULT_SUBJECT,
      ServeVapidKeys.subjectFor(null, "http://localhost:8080"),
    )
    assertEquals(ServeVapidKeys.DEFAULT_SUBJECT, ServeVapidKeys.subjectFor(" ", null))
    assertNull(ServeVapidKeys.subjectRejection("mailto:ops@example.com"))
    assertNull(ServeVapidKeys.subjectRejection("https://preview.coo.ee"))
    assertNotNull(ServeVapidKeys.subjectRejection("http://preview.coo.ee"))
    assertNotNull(ServeVapidKeys.subjectRejection("ops@example.com"))
    assertNotNull(ServeVapidKeys.subjectRejection("mailto:"))
  }

  private companion object {
    const val ENDPOINT = "https://fcm.googleapis.com/fcm/send/abc"
  }
}
