package ee.schimke.composeai.cli.serve

import java.security.KeyPair
import java.security.Signature
import java.security.interfaces.ECPrivateKey
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/**
 * The JDK-only Web Push crypto, pinned to RFC 8291's own worked example (section 5) byte for byte,
 * and round-tripped with keys generated here. A push service cannot tell us we got it wrong — it
 * relays whatever it is given and the browser silently drops what it cannot decrypt — so the RFC's
 * vector is the only check there is short of a real phone.
 */
class ServeWebPushTest {
  private fun b64(text: String): ByteArray = assertNotNull(ServeWebPush.fromBase64Url(text))

  private fun pair(publicKey: String, privateKey: String): KeyPair =
    KeyPair(
      assertNotNull(ServeWebPush.publicKeyFromRaw(b64(publicKey))),
      assertNotNull(ServeWebPush.privateKeyFromRaw(b64(privateKey))),
    )

  @Test
  fun `encrypts the RFC 8291 section 5 example exactly`() {
    val body =
      ServeWebPush.encrypt(
        plaintext = RFC_PLAINTEXT.toByteArray(Charsets.UTF_8),
        uaPublic = b64(RFC_UA_PUBLIC),
        authSecret = b64(RFC_AUTH_SECRET),
        salt = b64(RFC_SALT),
        senderKeys = pair(RFC_AS_PUBLIC, RFC_AS_PRIVATE),
        recordSize = 4096,
      )
    assertEquals(RFC_MESSAGE, ServeWebPush.base64Url(body))
  }

  @Test
  fun `derives the RFC 8291 section 5 intermediate values`() {
    val asKeys = pair(RFC_AS_PUBLIC, RFC_AS_PRIVATE)
    val uaKeys = pair(RFC_UA_PUBLIC, RFC_UA_PRIVATE)
    val secret =
      ServeWebPush.ecdh(
        asKeys.private as ECPrivateKey,
        uaKeys.public as java.security.interfaces.ECPublicKey,
      )
    assertEquals(RFC_ECDH_SECRET, ServeWebPush.base64Url(secret))
    // Both sides agree, which is the whole point of the exchange.
    assertContentEquals(
      secret,
      ServeWebPush.ecdh(
        uaKeys.private as ECPrivateKey,
        asKeys.public as java.security.interfaces.ECPublicKey,
      ),
    )
    val keys =
      ServeWebPush.deriveKeys(
        ecdhSecret = secret,
        authSecret = b64(RFC_AUTH_SECRET),
        uaPublic = b64(RFC_UA_PUBLIC),
        asPublic = b64(RFC_AS_PUBLIC),
        salt = b64(RFC_SALT),
      )
    assertEquals(RFC_IKM, ServeWebPush.base64Url(keys.ikm))
    assertEquals(RFC_CEK, ServeWebPush.base64Url(keys.cek))
    assertEquals(RFC_NONCE, ServeWebPush.base64Url(keys.nonce))
  }

  @Test
  fun `the browser's half decrypts the RFC example`() {
    val plaintext =
      ServeWebPush.decrypt(
        b64(RFC_MESSAGE),
        pair(RFC_UA_PUBLIC, RFC_UA_PRIVATE),
        b64(RFC_AUTH_SECRET),
      )
    assertEquals(RFC_PLAINTEXT, plaintext.toString(Charsets.UTF_8))
  }

  @Test
  fun `round-trips a payload to a locally generated subscription`() {
    val browser = ServeWebPush.generateKeyPair()
    val auth = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }
    val payload = """{"kind":"replies","designId":"d1","title":"New reply on “Card”"}"""
    val body =
      ServeWebPush.encrypt(
        payload.toByteArray(Charsets.UTF_8),
        ServeWebPush.rawPublicKey(browser.public as java.security.interfaces.ECPublicKey),
        auth,
      )
    // salt(16) + rs(4) + idlen(1) + key(65) + content + delimiter(1) + tag(16)
    assertEquals(86 + payload.toByteArray().size + 1 + 16, body.size)
    assertEquals(4096, java.nio.ByteBuffer.wrap(body, 16, 4).int)
    assertEquals(payload, ServeWebPush.decrypt(body, browser, auth).toString(Charsets.UTF_8))
    // A fresh salt and sender key every time: the same payload twice is two different bodies.
    val again =
      ServeWebPush.encrypt(
        payload.toByteArray(Charsets.UTF_8),
        ServeWebPush.rawPublicKey(browser.public as java.security.interfaces.ECPublicKey),
        auth,
      )
    assertTrue(!body.contentEquals(again))
  }

  @Test
  fun `refuses a point that is not on the curve`() {
    val point = b64(RFC_UA_PUBLIC)
    point[64] = (point[64].toInt() xor 1).toByte()
    assertNull(ServeWebPush.publicKeyFromRaw(point))
    assertNull(ServeWebPush.publicKeyFromRaw(ByteArray(65)))
    assertNull(ServeWebPush.publicKeyFromRaw(b64(RFC_UA_PUBLIC).copyOf(64)))
    assertNull(ServeWebPush.privateKeyFromRaw(ByteArray(32)))
    assertFailsWith<IllegalArgumentException> {
      ServeWebPush.encrypt(ByteArray(4), point, ByteArray(16))
    }
  }

  @Test
  fun `raw key encodings round-trip`() {
    val keys = ServeWebPush.generateKeyPair()
    val raw = ServeWebPush.rawPublicKey(keys.public as java.security.interfaces.ECPublicKey)
    assertEquals(65, raw.size)
    assertEquals(keys.public, ServeWebPush.publicKeyFromRaw(raw))
    val d = ServeWebPush.rawPrivateKey(keys.private as ECPrivateKey)
    assertEquals(32, d.size)
    assertEquals((keys.private as ECPrivateKey).s, ServeWebPush.privateKeyFromRaw(d)!!.s)
  }

  @Test
  fun `the VAPID header is an ES256 JWT with a raw signature that verifies`() {
    val keys = ServeWebPush.generateKeyPair()
    val header =
      ServeWebPush.vapidAuthorization(
        audience = "https://fcm.googleapis.com",
        subject = "mailto:ops@example.com",
        keys = keys,
        expiresAtEpochSeconds = 1_700_000_000,
      )
    val match = Regex("^vapid t=([^,]+), k=(.+)$").matchEntire(header)
    assertNotNull(match, header)
    val (jwt, k) = match.destructured
    assertEquals(
      ServeWebPush.base64Url(
        ServeWebPush.rawPublicKey(keys.public as java.security.interfaces.ECPublicKey)
      ),
      k,
    )
    val parts = jwt.split('.')
    assertEquals(3, parts.size)
    val head = Json.parseToJsonElement(String(Base64.getUrlDecoder().decode(parts[0]))).jsonObject
    assertEquals("ES256", head["alg"]!!.jsonPrimitive.content)
    val claims = Json.parseToJsonElement(String(Base64.getUrlDecoder().decode(parts[1]))).jsonObject
    assertEquals("https://fcm.googleapis.com", claims["aud"]!!.jsonPrimitive.content)
    assertEquals("mailto:ops@example.com", claims["sub"]!!.jsonPrimitive.content)
    assertEquals(1_700_000_000, claims["exp"]!!.jsonPrimitive.long)
    val signature = Base64.getUrlDecoder().decode(parts[2])
    assertEquals(64, signature.size, "JWS ES256 wants r || s, not DER")
    val verified =
      Signature.getInstance("SHA256withECDSAinP1363Format").run {
        initVerify(keys.public)
        update("${parts[0]}.${parts[1]}".toByteArray(Charsets.US_ASCII))
        verify(signature)
      }
    assertTrue(verified)
  }

  @Test
  fun `DER signatures with short and padded integers convert to 64 bytes`() {
    // r has a leading 0x00 (high bit set), s is short (31 bytes): both shapes the JDK emits.
    val r = ByteArray(32) { 0x80.toByte() }
    val s = ByteArray(31) { 0x11 }
    val der =
      byteArrayOf(0x30, (2 + 33 + 2 + 31).toByte(), 0x02, 33, 0x00) + r + byteArrayOf(0x02, 31) + s
    val raw = ServeWebPush.derToRaw(der)
    assertEquals(64, raw.size)
    assertContentEquals(r, raw.copyOfRange(0, 32))
    assertContentEquals(byteArrayOf(0) + s, raw.copyOfRange(32, 64))
  }

  private companion object {
    // RFC 8291 section 5, "Push Message Encryption Example".
    const val RFC_PLAINTEXT = "When I grow up, I want to be a watermelon"
    const val RFC_AS_PRIVATE = "yfWPiYE-n46HLnH0KqZOF1fJJU3MYrct3AELtAQ-oRw"
    const val RFC_AS_PUBLIC =
      "BP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27mlmlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A8"
    const val RFC_UA_PRIVATE = "q1dXpw3UpT5VOmu_cf_v6ih07Aems3njxI-JWgLcM94"
    const val RFC_UA_PUBLIC =
      "BCVxsr7N_eNgVRqvHtD0zTZsEc6-VV-JvLexhqUzORcxaOzi6-AYWXvTBHm4bjyPjs7Vd8pZGH6SRpkNtoIAiw4"
    const val RFC_SALT = "DGv6ra1nlYgDCS1FRnbzlw"
    const val RFC_AUTH_SECRET = "BTBZMqHH6r4Tts7J_aSIgg"
    const val RFC_ECDH_SECRET = "kyrL1jIIOHEzg3sM2ZWRHDRB62YACZhhSlknJ672kSs"
    const val RFC_IKM = "S4lYMb_L0FxCeq0WhDx813KgSYqU26kOyzWUdsXYyrg"
    const val RFC_CEK = "oIhVW04MRdy2XN9CiKLxTg"
    const val RFC_NONCE = "4h_95klXJ5E_qnoN"
    const val RFC_MESSAGE =
      "DGv6ra1nlYgDCS1FRnbzlwAAEABBBP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27mlmlMoZIIgDll6e3vCYLocInmYWAmS" +
        "6TlzAC8wEqKK6PBru3jl7A_yl95bQpu6cVPTpK4Mqgkf1CXztLVBSt2Ks3oZwbuwXPXLWyouBWLVWGNWQexSgSxsj_Q" +
        "ulcy4a-fN"
  }
}
