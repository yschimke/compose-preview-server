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

  /**
   * Queries `GET /readyz`; returns null if the push-server is unreachable or returned invalid JSON.
   */
  fun readyz(): ReadyStatus? {
    val request =
      HttpRequest.newBuilder(URI("$baseUrl/readyz")).timeout(Duration.ofSeconds(10)).GET().build()
    val response =
      try {
        client.send(request, HttpResponse.BodyHandlers.ofString())
      } catch (_: Exception) {
        return null
      }
    return try {
      json.decodeFromString(ReadyStatus.serializer(), response.body())
    } catch (_: Exception) {
      null
    }
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
