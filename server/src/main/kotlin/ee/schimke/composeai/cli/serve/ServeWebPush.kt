package ee.schimke.composeai.cli.serve

import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.Signature
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPrivateKeySpec
import java.security.spec.ECPublicKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The two pieces of Web Push that are cryptography rather than plumbing, written against the JDK
 * alone.
 *
 * * **RFC 8291** message encryption: the push service relays an opaque body, and only the browser
 *   that subscribed can read it. ECDH on P-256 between a throwaway key of ours and the browser's
 *   `p256dh` key, HKDF-SHA256 (via `HmacSHA256`) mixed with the browser's `auth` secret, then one
 *   AES-128-GCM record under the RFC 8188 `aes128gcm` header.
 * * **RFC 8292** VAPID: an ES256 JWT that says which application server is sending, so a push
 *   service can refuse anybody who learns an endpoint but does not hold this deployment's key.
 *
 * Why not a library: the one that exists for the JVM pulls in BouncyCastle, and every primitive
 * needed here — `EC` key pairs, `ECDH`, `HmacSHA256`, `AES/GCM`, `SHA256withECDSA` — has shipped in
 * the JDK since 8. `checkServeModuleBoundary` stays as it was. The cost is this file, and the RFC's
 * own worked example (section 5) pins it byte for byte in `ServeWebPushTest`.
 */
internal object ServeWebPush {

  /** The `aes128gcm` record size we write: one record, comfortably larger than any payload. */
  const val RECORD_SIZE: Int = 4096

  /**
   * The largest plaintext we will encrypt. Push services must accept a body of 4096 bytes (RFC 8291
   * section 4), and the body is the 86-byte header, the content, the delimiter and the tag.
   */
  const val MAX_PLAINTEXT_BYTES: Int = 4096 - 86 - 1 - 16

  private val random = SecureRandom()

  /** P-256, as the JDK names it. */
  val P256: ECParameterSpec by lazy {
    AlgorithmParameters.getInstance("EC")
      .apply { init(ECGenParameterSpec("secp256r1")) }
      .getParameterSpec(ECParameterSpec::class.java)
  }

  fun generateKeyPair(): KeyPair =
    KeyPairGenerator.getInstance("EC")
      .apply { initialize(ECGenParameterSpec("secp256r1")) }
      .generateKeyPair()

  // ------------------------------------------------------------------------------- encoding

  fun base64Url(bytes: ByteArray): String =
    Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

  /** Base64url with or without padding; null when it is not base64url at all. */
  fun fromBase64Url(text: String): ByteArray? = runCatching {
    Base64.getUrlDecoder().decode(text.trim().trimEnd('='))
  }
    .getOrNull()

  /** The 65-byte uncompressed point `04 || X || Y` a browser hands over as `p256dh`. */
  fun rawPublicKey(key: ECPublicKey): ByteArray =
    byteArrayOf(0x04) + unsigned32(key.w.affineX) + unsigned32(key.w.affineY)

  /** The 32-byte scalar `d`, which is how VAPID private keys travel in configuration. */
  fun rawPrivateKey(key: ECPrivateKey): ByteArray = unsigned32(key.s)

  /**
   * [raw] as a P-256 public key, or null when it is not an uncompressed point **on the curve**.
   *
   * The curve check is not left to `KeyAgreement`: a point off the curve is the classic invalid-
   * curve attack on ECDH, and a subscription is refused at the door rather than at the first push.
   */
  fun publicKeyFromRaw(raw: ByteArray): ECPublicKey? {
    if (raw.size != 65 || raw[0] != 0x04.toByte()) return null
    val x = BigInteger(1, raw.copyOfRange(1, 33))
    val y = BigInteger(1, raw.copyOfRange(33, 65))
    if (!isOnCurve(x, y)) return null
    return runCatching {
      KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(ECPoint(x, y), P256))
        as ECPublicKey
    }
      .getOrNull()
  }

  fun privateKeyFromRaw(raw: ByteArray): ECPrivateKey? {
    if (raw.size != 32) return null
    val s = BigInteger(1, raw)
    if (s.signum() == 0 || s >= P256.order) return null
    return runCatching {
      KeyFactory.getInstance("EC").generatePrivate(ECPrivateKeySpec(s, P256)) as ECPrivateKey
    }
      .getOrNull()
  }

  private fun isOnCurve(x: BigInteger, y: BigInteger): Boolean {
    val field = P256.curve.field as java.security.spec.ECFieldFp
    val p = field.p
    if (x.signum() < 0 || x >= p || y.signum() < 0 || y >= p) return false
    val left = y.modPow(BigInteger.TWO, p)
    val right =
      x.modPow(BigInteger.valueOf(3), p).add(P256.curve.a.multiply(x)).add(P256.curve.b).mod(p)
    return left == right
  }

  private fun unsigned32(value: BigInteger): ByteArray {
    val bytes = value.toByteArray()
    return when {
      bytes.size == 32 -> bytes
      bytes.size == 33 && bytes[0] == 0.toByte() -> bytes.copyOfRange(1, 33)
      bytes.size < 32 -> ByteArray(32 - bytes.size) + bytes
      else -> error("not a P-256 field element")
    }
  }

  // ---------------------------------------------------------------------------- RFC 8291

  /** What [deriveKeys] produces, kept apart so the RFC's intermediate values can be asserted. */
  data class DerivedKeys(val ikm: ByteArray, val cek: ByteArray, val nonce: ByteArray)

  /**
   * Encrypt [plaintext] for one subscription: the complete `aes128gcm` body, header included.
   *
   * [salt] and [senderKeys] are parameters only so the RFC's example can be reproduced; every real
   * message takes a fresh random salt and a fresh key pair, which is what makes two identical
   * payloads indistinguishable on the wire.
   */
  fun encrypt(
    plaintext: ByteArray,
    uaPublic: ByteArray,
    authSecret: ByteArray,
    salt: ByteArray = ByteArray(16).also(random::nextBytes),
    senderKeys: KeyPair = generateKeyPair(),
    recordSize: Int = RECORD_SIZE,
  ): ByteArray {
    require(salt.size == 16) { "salt must be 16 bytes" }
    require(authSecret.size == 16) { "auth secret must be 16 bytes" }
    require(plaintext.size + 17 <= recordSize) { "payload does not fit one record" }
    val receiver = requireNotNull(publicKeyFromRaw(uaPublic)) { "not a P-256 public key" }
    val asPublic = rawPublicKey(senderKeys.public as ECPublicKey)
    val keys =
      deriveKeys(
        ecdhSecret = ecdh(senderKeys.private as ECPrivateKey, receiver),
        authSecret = authSecret,
        uaPublic = uaPublic,
        asPublic = asPublic,
        salt = salt,
      )
    // One record, so it is also the last: content, then the 0x02 delimiter, no padding.
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(
      Cipher.ENCRYPT_MODE,
      SecretKeySpec(keys.cek, "AES"),
      GCMParameterSpec(128, keys.nonce),
    )
    val sealed = cipher.doFinal(plaintext + byteArrayOf(0x02))
    val out = ByteArrayOutputStream(86 + sealed.size)
    out.write(salt)
    out.write(ByteBuffer.allocate(4).putInt(recordSize).array())
    out.write(asPublic.size)
    out.write(asPublic)
    out.write(sealed)
    return out.toByteArray()
  }

  /**
   * The receiving half, for tests only: what a browser does with [body]. Single-record bodies,
   * which is all [encrypt] writes.
   */
  fun decrypt(body: ByteArray, uaKeys: KeyPair, authSecret: ByteArray): ByteArray {
    val salt = body.copyOfRange(0, 16)
    val idLength = body[20].toInt() and 0xff
    val asPublic = body.copyOfRange(21, 21 + idLength)
    val sender = requireNotNull(publicKeyFromRaw(asPublic)) { "bad sender key" }
    val keys =
      deriveKeys(
        ecdhSecret = ecdh(uaKeys.private as ECPrivateKey, sender),
        authSecret = authSecret,
        uaPublic = rawPublicKey(uaKeys.public as ECPublicKey),
        asPublic = asPublic,
        salt = salt,
      )
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(
      Cipher.DECRYPT_MODE,
      SecretKeySpec(keys.cek, "AES"),
      GCMParameterSpec(128, keys.nonce),
    )
    val padded = cipher.doFinal(body.copyOfRange(21 + idLength, body.size))
    val end = padded.indexOfLast { it != 0.toByte() }
    require(end >= 0 && padded[end] == 0x02.toByte()) { "missing last-record delimiter" }
    return padded.copyOfRange(0, end)
  }

  fun ecdh(privateKey: ECPrivateKey, publicKey: ECPublicKey): ByteArray =
    KeyAgreement.getInstance("ECDH").run {
      init(privateKey)
      doPhase(publicKey, true)
      generateSecret()
    }

  /** RFC 8291 section 3.4, then RFC 8188 section 2.2. */
  fun deriveKeys(
    ecdhSecret: ByteArray,
    authSecret: ByteArray,
    uaPublic: ByteArray,
    asPublic: ByteArray,
    salt: ByteArray,
  ): DerivedKeys {
    val keyInfo = "WebPush: info".toByteArray(StandardCharsets.US_ASCII) + 0 + uaPublic + asPublic
    val ikm = hkdfExpand(hmac(authSecret, ecdhSecret), keyInfo, 32)
    val prk = hmac(salt, ikm)
    val cek =
      hkdfExpand(prk, "Content-Encoding: aes128gcm".toByteArray(StandardCharsets.US_ASCII) + 0, 16)
    val nonce =
      hkdfExpand(prk, "Content-Encoding: nonce".toByteArray(StandardCharsets.US_ASCII) + 0, 12)
    return DerivedKeys(ikm = ikm, cek = cek, nonce = nonce)
  }

  private operator fun ByteArray.plus(byte: Int): ByteArray = this + byteArrayOf(byte.toByte())

  private fun hmac(key: ByteArray, data: ByteArray): ByteArray =
    Mac.getInstance("HmacSHA256").run {
      init(SecretKeySpec(key, "HmacSHA256"))
      doFinal(data)
    }

  /** HKDF-Expand for lengths up to one hash block, which is every length used here. */
  private fun hkdfExpand(prk: ByteArray, info: ByteArray, length: Int): ByteArray {
    require(length <= 32)
    return hmac(prk, info + 0x01).copyOf(length)
  }

  // ---------------------------------------------------------------------------- RFC 8292

  /**
   * The `Authorization` header for one push request: `vapid t=<jwt>, k=<public key>`.
   *
   * [audience] is the push service's origin (`https://fcm.googleapis.com`), never the full
   * endpoint: the token is scoped to a service, not to a subscription, so it carries nothing about
   * whom we are pushing to.
   */
  fun vapidAuthorization(
    audience: String,
    subject: String,
    keys: KeyPair,
    expiresAtEpochSeconds: Long,
  ): String {
    val header = base64Url("""{"typ":"JWT","alg":"ES256"}""".toByteArray(StandardCharsets.UTF_8))
    val claims = buildString {
      append("{\"aud\":").append(jsonString(audience))
      append(",\"exp\":").append(expiresAtEpochSeconds)
      append(",\"sub\":").append(jsonString(subject))
      append('}')
    }
      .toByteArray(StandardCharsets.UTF_8)
    val signingInput = "$header.${base64Url(claims)}"
    val der =
      Signature.getInstance("SHA256withECDSA").run {
        initSign(keys.private)
        update(signingInput.toByteArray(StandardCharsets.US_ASCII))
        sign()
      }
    val jwt = "$signingInput.${base64Url(derToRaw(der))}"
    return "vapid t=$jwt, k=${base64Url(rawPublicKey(keys.public as ECPublicKey))}"
  }

  /**
   * An ASN.1 `SEQUENCE { INTEGER r, INTEGER s }` as the 64-byte `r || s` JWS wants (RFC 7518
   * section 3.4). The JDK signs in DER; a push service that receives DER rejects the token.
   */
  fun derToRaw(der: ByteArray): ByteArray {
    var index = 0
    fun next(): Int = der[index++].toInt() and 0xff
    fun length(): Int {
      val first = next()
      if (first < 0x80) return first
      var value = 0
      repeat(first and 0x7f) { value = (value shl 8) or next() }
      return value
    }
    require(next() == 0x30) { "not a DER sequence" }
    length()
    fun integer(): ByteArray {
      require(next() == 0x02) { "not a DER integer" }
      val size = length()
      val bytes = der.copyOfRange(index, index + size)
      index += size
      return unsigned32(BigInteger(1, bytes))
    }
    return integer() + integer()
  }

  private fun jsonString(value: String): String = buildString {
    append('"')
    for (c in value) {
      when {
        c == '"' -> append("\\\"")
        c == '\\' -> append("\\\\")
        c < ' ' -> append("\\u%04x".format(c.code))
        else -> append(c)
      }
    }
    append('"')
  }
}
