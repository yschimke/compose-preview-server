package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.uibuilder.protocol.DesignDocumentV1
import ee.schimke.composeai.web.WebEscaping
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.ByteBuffer
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.ExecutionException
import java.util.concurrent.Flow
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/**
 * Starting a design from a document another site prepared: `--ui-builder-import-origins`.
 *
 * A site that seeds designs for its own users — the Home Assistant card design server seeds a
 * card's content from its binding schema — publishes the seed document at a URL on its own origin
 * and sends the signed-in person to `/ui-builder/designs/import?url=<that URL>`. They confirm, and
 * the design is created **as them**, private to them, on this server. The other site holds no
 * credential here: no operator token, no agent grant, nobody else's design.
 *
 * Only the listed origins are fetched, so this is not a way to make the server fetch arbitrary
 * URLs; the fetch follows no redirects, reads at most [MAX_BYTES] and gives up after ten seconds,
 * body included. The creating POST is same-origin like every other creation form, and the document
 * is fetched again there rather than trusted from the form. Off unless an operator lists an origin.
 */
object ServeUiBuilderImport {
  const val MAX_BYTES = 1 shl 20

  private val FETCH_TIMEOUT: Duration = Duration.ofSeconds(10)

  private val client: HttpClient =
    HttpClient.newBuilder()
      .followRedirects(HttpClient.Redirect.NEVER)
      .connectTimeout(FETCH_TIMEOUT)
      .build()

  /**
   * An origin as `--ui-builder-import-origins` takes it: `https://host[:port]`, nothing else. Null
   * when [raw] is not one.
   */
  fun normalizeOrigin(raw: String): String? {
    val uri = runCatching { URI(raw.trim().removeSuffix("/")) }.getOrNull() ?: return null
    if (uri.scheme != "https" || uri.host.isNullOrBlank()) return null
    if (uri.userInfo != null || !uri.rawPath.isNullOrEmpty() || uri.rawQuery != null) return null
    if (uri.rawFragment != null) return null
    val port = if (uri.port == -1 || uri.port == 443) "" else ":${uri.port}"
    return "https://${uri.host.lowercase()}$port"
  }

  /** [url] when it is an HTTPS URL on one of [origins]; null otherwise. */
  fun allowed(url: String?, origins: List<String>): URI? {
    if (url.isNullOrBlank() || origins.isEmpty()) return null
    val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return null
    if (uri.scheme != "https" || uri.host.isNullOrBlank() || uri.userInfo != null) return null
    val port = if (uri.port == -1 || uri.port == 443) "" else ":${uri.port}"
    return uri.takeIf { "https://${uri.host.lowercase()}$port" in origins }
  }

  sealed interface Fetched {
    data class Document(val document: DesignDocumentV1) : Fetched

    data class Refused(val reason: String) : Fetched
  }

  /**
   * Fetches and decodes the seed document at [uri]. Blocking; call off the event loop.
   *
   * [timeout] bounds the whole exchange, body included: `HttpRequest.timeout` stops at the headers,
   * so a site that answers and then stalls would otherwise hold the caller.
   */
  fun fetch(uri: URI, timeout: Duration = FETCH_TIMEOUT): Fetched {
    val request =
      HttpRequest.newBuilder(uri)
        .timeout(timeout)
        .header("Accept", "application/json")
        .GET()
        .build()
    val body = AtomicReference<CappedBody?>()
    val future =
      client.sendAsync(request) { info ->
        if (info.statusCode() == 200) CappedBody(MAX_BYTES).also(body::set)
        else HttpResponse.BodySubscribers.replacing(null)
      }
    val response =
      try {
        future.get(timeout.toMillis(), TimeUnit.MILLISECONDS)
      } catch (e: TimeoutException) {
        future.cancel(true)
        body.get()?.cancel()
        return Fetched.Refused("the seed document took longer than ${timeout.toSeconds()} s")
      } catch (e: Exception) {
        val cause = (e as? ExecutionException)?.cause ?: e
        return Fetched.Refused(
          "the seed document could not be fetched: ${cause.javaClass.simpleName}"
        )
      }
    if (response.statusCode() != 200) {
      return Fetched.Refused("the seed document answered ${response.statusCode()}")
    }
    val bytes =
      response.body()
        ?: return Fetched.Refused("the seed document is larger than ${MAX_BYTES / 1024} KiB")
    return decode(bytes.toString(Charsets.UTF_8))
  }

  /**
   * Collects a body up to [max] bytes, completing with null (and cancelling the stream) once it
   * passes that.
   */
  private class CappedBody(private val max: Int) : HttpResponse.BodySubscriber<ByteArray?> {
    private val bytes = ByteArrayOutputStream()
    private val result = CompletableFuture<ByteArray?>()
    @Volatile private var subscription: Flow.Subscription? = null

    override fun getBody(): CompletionStage<ByteArray?> = result

    override fun onSubscribe(subscription: Flow.Subscription) {
      this.subscription = subscription
      subscription.request(Long.MAX_VALUE)
    }

    override fun onNext(item: List<ByteBuffer>) {
      if (result.isDone) return
      for (buffer in item) {
        val chunk = ByteArray(buffer.remaining())
        buffer.get(chunk)
        bytes.write(chunk)
      }
      if (bytes.size() > max) {
        result.complete(null)
        cancel()
      }
    }

    override fun onError(throwable: Throwable) {
      result.completeExceptionally(throwable)
    }

    override fun onComplete() {
      result.complete(bytes.toByteArray())
    }

    fun cancel() {
      subscription?.cancel()
    }
  }

  /** A seed document from its JSON, read as the v1 API's `PUT` reads one. */
  fun decode(text: String): Fetched =
    try {
      val root = UI_BUILDER_JSON.parseToJsonElement(text).jsonObject
      val schema = (root["schema"] as? JsonPrimitive)?.contentOrNull
      if (schema == null || !schema.startsWith("compose-ui-builder")) {
        Fetched.Refused("the seed is not a UI-builder design document (schema `$schema`)")
      } else if (root["home"] != null) {
        // A seed is a new design. A document with a home belongs somewhere already, and is opened
        // there or copied, never re-imported as a second canonical design.
        Fetched.Refused("the seed already names a home; open it there instead")
      } else {
        Fetched.Document(UI_BUILDER_JSON.decodeFromJsonElement(DesignDocumentV1.serializer(), root))
      }
    } catch (e: Exception) {
      Fetched.Refused("the seed is not a valid design document: ${e.message?.take(200)}")
    }

  /** The page that asks the signed-in person to confirm the import. */
  fun confirmPage(action: String, url: URI, document: DesignDocumentV1, login: String?): String {
    fun esc(s: String) = WebEscaping.htmlEscape(s)
    val origin = "https://${url.host}${if (url.port == -1) "" else ":${url.port}"}"
    return """<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>Start a design · ${esc(document.title)}</title>
<style>
:root{color-scheme:light dark;font-family:system-ui,sans-serif}
body{max-width:36rem;margin:3rem auto;padding:0 1rem;line-height:1.5}
.muted{opacity:.7}button{font:inherit;padding:.5rem 1rem;border-radius:.4rem;cursor:pointer}
code{font-size:.9em}
</style></head><body>
<h1>Start a design</h1>
<p><b>${esc(origin)}</b> prepared <b>${esc(document.title)}</b>, a <code>${esc(document.catalogPin.systemId)}</code> design.</p>
<p class="muted">It is created as ${if (login != null) "<b>${esc(login)}</b>" else "you"}, private to you: only you, and whoever you share it with, can open it. ${esc(origin)} keeps no access to it.</p>
<form method="post" action="${esc(action)}">
<input type="hidden" name="url" value="${esc(url.toString())}">
<button type="submit">Create my design</button>
</form>
</body></html>"""
  }
}
