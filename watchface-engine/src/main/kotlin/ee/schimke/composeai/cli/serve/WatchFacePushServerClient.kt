package ee.schimke.composeai.cli.serve

import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * HTTP client for the standalone `watchface-push-server` container (`yschimke/watchface-tools`
 * `:push-server`).
 *
 * When `WATCHFACE_PUSH_SERVER_URL` is set (for example `http://watchface-push:8080`), the preview
 * server delegates WFF validation, debug preview packaging, and signed Watch Face Push packaging
 * over HTTP instead of spawning a local `lib-wff-packager/` JVM. That keeps Google's
 * `validator-push` (`xercesImpl`), Android SDK build-tools, and `WFF_SIGNING_*` marketplace keys
 * isolated in a separately released container that Watchtower can roll independently.
 */
class WatchFacePushServerClient(
  baseUrl: String,
  private val client: HttpClient =
    HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(),
  private val requestTimeout: Duration = Duration.ofSeconds(180),
) {
  val baseUrl: String = baseUrl.trimEnd('/')

  @Serializable
  data class ReadyStatus(
    val ready: Boolean = false,
    val sdkAvailable: Boolean = false,
    val signingConfigured: Boolean = false,
    val signerCertificateSha256: String? = null,
    val signingError: String? = null,
    val version: String? = null,
  )

  @Serializable
  private data class PackFailureBody(
    val diagnostics: List<String> = emptyList(),
    val code: String? = null,
    val error: String? = null,
  )

  private val readyzCache = java.util.concurrent.atomic.AtomicReference<CachedReadyz?>(null)
  private val readyzRefreshing = java.util.concurrent.atomic.AtomicBoolean(false)

  private data class CachedReadyz(val status: ReadyStatus?, val expiresAtMillis: Long)

  /**
   * Queries `GET /readyz`; returns null if the push-server is unreachable or returned invalid JSON.
   */
  fun readyz(timeout: Duration = Duration.ofSeconds(3)): ReadyStatus? {
    val request = HttpRequest.newBuilder(URI("$baseUrl/readyz")).timeout(timeout).GET().build()
    val response =
      try {
        client.send(request, HttpResponse.BodyHandlers.ofString())
      } catch (_: Exception) {
        return null
      }
    return parseReadyz(response.body())
  }

  /**
   * Non-blocking readiness lookup for request threads: returns the cached status immediately and
   * triggers a single-flight asynchronous refresh via [HttpClient.sendAsync] whenever the entry is
   * absent or expired. Never blocks a caller on a slow or unreachable push-server.
   */
  fun cachedReadyz(
    nowMillis: Long = System.currentTimeMillis(),
    successTtlMillis: Long = DEFAULT_READYZ_SUCCESS_TTL_MILLIS,
    failureTtlMillis: Long = DEFAULT_READYZ_FAILURE_TTL_MILLIS,
    probeTimeout: Duration = DEFAULT_READYZ_PROBE_TIMEOUT,
  ): ReadyStatus? {
    val cached = readyzCache.get()
    if (cached == null || nowMillis >= cached.expiresAtMillis) {
      refreshReadyzAsync(successTtlMillis, failureTtlMillis, probeTimeout)
    }
    return cached?.status
  }

  /** Starts a background `/readyz` refresh if one is not already in flight. */
  fun refreshReadyzAsync(
    successTtlMillis: Long = DEFAULT_READYZ_SUCCESS_TTL_MILLIS,
    failureTtlMillis: Long = DEFAULT_READYZ_FAILURE_TTL_MILLIS,
    probeTimeout: Duration = DEFAULT_READYZ_PROBE_TIMEOUT,
  ) {
    if (!readyzRefreshing.compareAndSet(false, true)) return
    val request = HttpRequest.newBuilder(URI("$baseUrl/readyz")).timeout(probeTimeout).GET().build()
    try {
      client.sendAsync(request, HttpResponse.BodyHandlers.ofString()).handle { response, error ->
        val status = if (error == null && response != null) parseReadyz(response.body()) else null
        val ttl = if (status != null && status.ready) successTtlMillis else failureTtlMillis
        readyzCache.set(CachedReadyz(status, System.currentTimeMillis() + ttl))
        readyzRefreshing.set(false)
      }
    } catch (_: Exception) {
      readyzCache.set(CachedReadyz(null, System.currentTimeMillis() + failureTtlMillis))
      readyzRefreshing.set(false)
    }
  }

  private fun parseReadyz(body: String): ReadyStatus? =
    try {
      json.decodeFromString(ReadyStatus.serializer(), body)
    } catch (_: Exception) {
      null
    }

  /** Calls `POST /v1/validate?pushMarketplace=<marketplacePackage>` and returns the JSON report. */
  fun validate(
    files: Map<String, ByteArray>,
    marketplacePackage: String = WatchFaceSubprocessValidator.DEFAULT_MARKETPLACE_PACKAGE,
  ): String {
    val uri = URI("$baseUrl/v1/validate?pushMarketplace=${encodeQuery(marketplacePackage)}")
    val request =
      HttpRequest.newBuilder(uri)
        .timeout(requestTimeout)
        .header("Content-Type", "application/zip")
        .POST(HttpRequest.BodyPublishers.ofByteArray(zipBundle(files)))
        .build()
    val response = send(request, HttpResponse.BodyHandlers.ofString(), "validate")
    if (response.statusCode() == 200) {
      return response.body()
    }
    throw failureException("validate", response.statusCode(), response.body())
  }

  /**
   * Calls `POST /v1/pack?package=<packageName>&versionCode=1` and writes the debug APK to [out].
   */
  fun packSideload(files: Map<String, ByteArray>, packageName: String, out: File) {
    val uri = URI("$baseUrl/v1/pack?package=${encodeQuery(packageName)}&versionCode=1")
    val request =
      HttpRequest.newBuilder(uri)
        .timeout(requestTimeout)
        .header("Content-Type", "application/zip")
        .POST(HttpRequest.BodyPublishers.ofByteArray(zipBundle(files)))
        .build()
    val response = send(request, HttpResponse.BodyHandlers.ofByteArray(), "pack")
    if (response.statusCode() == 200) {
      out.parentFile?.mkdirs()
      out.writeBytes(response.body())
      return
    }
    throw failureException(
      "pack",
      response.statusCode(),
      response.body().toString(StandardCharsets.UTF_8),
    )
  }

  /**
   * Calls `POST /v1/pack?pushMarketplace=...&faceName=...&versionCode=...&signWithEnv=true`, writes
   * the signed APK to [out], and returns the `X-Validation-Token` header.
   */
  fun packPush(
    files: Map<String, ByteArray>,
    marketplacePackage: String,
    faceName: String,
    versionCode: Int,
    out: File,
  ): String {
    val uri =
      URI(
        "$baseUrl/v1/pack" +
          "?pushMarketplace=${encodeQuery(marketplacePackage)}" +
          "&faceName=${encodeQuery(faceName)}" +
          "&versionCode=$versionCode" +
          "&signWithEnv=true",
      )
    val request =
      HttpRequest.newBuilder(uri)
        .timeout(requestTimeout)
        .header("Content-Type", "application/zip")
        .POST(HttpRequest.BodyPublishers.ofByteArray(zipBundle(files)))
        .build()
    val response = send(request, HttpResponse.BodyHandlers.ofByteArray(), "pack")
    if (response.statusCode() == 200) {
      val token =
        response.headers().firstValue("X-Validation-Token").orElse("").trim().takeIf {
          it.isNotEmpty()
        }
          ?: throw DwfPackagingFailed(
            listOf("error[push]: push-server issued no Watch Face Push validation token"),
          )
      out.parentFile?.mkdirs()
      out.writeBytes(response.body())
      return token
    }
    throw failureException(
      "pack",
      response.statusCode(),
      response.body().toString(StandardCharsets.UTF_8),
    )
  }

  fun asDwfPackager(): DwfWffPackager = DwfWffPackager { files, packageName, out ->
    packSideload(files, packageName, out)
  }

  fun asValidator(
    marketplacePackage: String = WatchFaceSubprocessValidator.DEFAULT_MARKETPLACE_PACKAGE,
  ): WatchFaceValidator = WatchFaceValidator { files -> validate(files, marketplacePackage) }

  private fun <T> send(
    request: HttpRequest,
    handler: HttpResponse.BodyHandler<T>,
    operation: String,
  ): HttpResponse<T> =
    try {
      client.send(request, handler)
    } catch (e: Exception) {
      throw DwfRuntimeException(
        "watchface push-server $operation request to $baseUrl failed: " +
          (e.message ?: e.javaClass.simpleName),
        e,
      )
    }

  private fun failureException(operation: String, status: Int, body: String): DwfRuntimeException {
    val parsed =
      try {
        json.decodeFromString(PackFailureBody.serializer(), body)
      } catch (_: Exception) {
        null
      }
    if (status == 422 && parsed != null && parsed.diagnostics.isNotEmpty()) {
      return DwfPackagingFailed(parsed.diagnostics)
    }
    val detail = parsed?.error ?: body.trim().take(400).ifEmpty { "HTTP $status" }
    return if (status == 400 || status == 422) {
      DwfPackagingFailed(listOf("error[$operation]: $detail"))
    } else {
      DwfRuntimeException("watchface push-server $operation failed (HTTP $status): $detail")
    }
  }

  companion object {
    const val ENV_URL: String = "WATCHFACE_PUSH_SERVER_URL"
    const val DEFAULT_READYZ_SUCCESS_TTL_MILLIS: Long = 15_000L
    const val DEFAULT_READYZ_FAILURE_TTL_MILLIS: Long = 3_000L
    val DEFAULT_READYZ_PROBE_TIMEOUT: Duration = Duration.ofSeconds(2)

    private val json = Json { ignoreUnknownKeys = true }

    fun configuredUrl(env: Map<String, String> = System.getenv()): String? =
      env[ENV_URL]?.trim()?.takeIf { it.isNotEmpty() }

    internal fun zipBundle(files: Map<String, ByteArray>): ByteArray {
      val out = ByteArrayOutputStream()
      ZipOutputStream(out).use { zos ->
        for ((path, bytes) in files.toSortedMap()) {
          zos.putNextEntry(ZipEntry(path))
          zos.write(bytes)
          zos.closeEntry()
        }
      }
      return out.toByteArray()
    }

    private fun encodeQuery(value: String): String =
      URLEncoder.encode(value, StandardCharsets.UTF_8)
  }
}
