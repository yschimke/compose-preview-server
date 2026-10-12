package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.uibuilder.protocol.DesignDocumentV1
import ee.schimke.composeai.web.WebEscaping
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/**
 * Starting a design from a document another site prepared: `--ui-builder-import-origins`.
 *
 * A site that seeds designs for its own users — the Home Assistant card design server seeds a
 * card's content from its binding schema — publishes the seed document at a URL on its own origin
 * and sends the signed-in person to `/ui-builder/import?url=<that URL>`. They confirm, and the
 * design is created **as them**, private to them, on this server. The other site holds no
 * credential here: no operator token, no agent grant, nobody else's design.
 *
 * Only the listed origins are fetched, so this is not a way to make the server fetch arbitrary
 * URLs; the fetch follows no redirects and reads at most [MAX_BYTES]. The creating POST is
 * same-origin like every other creation form, and the document is fetched again there rather than
 * trusted from the form. Off unless an operator lists an origin.
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

  /** Fetches and decodes the seed document at [uri]. Blocking; call off the event loop. */
  fun fetch(uri: URI): Fetched {
    val response =
      try {
        client.send(
          HttpRequest.newBuilder(uri)
            .timeout(FETCH_TIMEOUT)
            .header("Accept", "application/json")
            .GET()
            .build(),
          HttpResponse.BodyHandlers.ofInputStream(),
        )
      } catch (e: Exception) {
        return Fetched.Refused("the seed document could not be fetched: ${e.javaClass.simpleName}")
      }
    response.body().use { body ->
      if (response.statusCode() != 200) {
        return Fetched.Refused("the seed document answered ${response.statusCode()}")
      }
      val bytes = ByteArrayOutputStream()
      val buffer = ByteArray(16 * 1024)
      while (true) {
        val read = body.read(buffer)
        if (read < 0) break
        bytes.write(buffer, 0, read)
        if (bytes.size() > MAX_BYTES) {
          return Fetched.Refused("the seed document is larger than ${MAX_BYTES / 1024} KiB")
        }
      }
      return decode(bytes.toString(Charsets.UTF_8))
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
