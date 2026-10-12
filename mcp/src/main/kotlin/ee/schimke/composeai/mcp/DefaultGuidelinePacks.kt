package ee.schimke.composeai.mcp

import ee.schimke.composeai.guidelines.GuidelineSubjectKind
import ee.schimke.composeai.guidelines.GuidelineSurfaces
import ee.schimke.composeai.guidelines.protocol.CatalogGuidelinesV1
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * The guidelines a preview is asked when its module publishes none (#149): a plain app module (a
 * Wear app, a Glance Wear widget) depends on no UI Builder catalog, so discovery writes no
 * `ui-builder.guidelines.json` and `preview_guidelines_prompt` had nothing to ask.
 *
 * The shared rule packs compose-ai-tools publishes (`guidelines/packs/`, its DESIGN_GUIDELINES.md)
 * are layered the way the catalogs that cover the same surfaces include them, chosen from what
 * [GuidelineSurfaces.of] reads off the preview's `previews.json` entry:
 *
 * |Subject                               |Packs                       |Like                              |
 * |--------------------------------------|----------------------------|----------------------------------|
 * |Wear widget (`widget`, `wear-widgets`)|remote-compose, wear-widgets|remote-m3-catalog's remote-catalog|
 * |other widget                          |launcher-widgets            |                                  |
 * |Wear screen or component              |compose-ui, wear-compose    |wear-m3-catalog                   |
 * |anything else                         |compose-ui                  |m3-catalog                        |
 *
 * Material-specific rules stay in each catalog's own file, so a catalog's guidelines, when there
 * are any, still win: these are only the fallback. Each pack is pinned by tag and sha256, exactly
 * as a catalog's `includes` pin it, and fetched by the engine's include resolver.
 */
internal object DefaultGuidelinePacks {
  /** The compose-ai-tools release the packs are read from; re-pin [Pack.sha256] when moving it. */
  const val TAG: String = "v2.52.0"

  private const val BASE =
    "https://raw.githubusercontent.com/yschimke/compose-ai-tools/$TAG/guidelines/packs/"

  enum class Pack(val id: String, val sha256: String) {
    COMPOSE_UI("compose-ui", "a323f14263b7ee460d82fdf93a229f787c583630a6d6bd2803e187b39fe52607"),
    WEAR_COMPOSE(
      "wear-compose",
      "7a33c7dbf4ff4b93338bb2ddbd781f0c8ec145ead04c6e2b5fa8746dedd303e4",
    ),
    REMOTE_COMPOSE(
      "remote-compose",
      "7d697da9eda96e78c8aab27ae58cdde9b5831552948418816a7f137992fa0d52",
    ),
    LAUNCHER_WIDGETS(
      "launcher-widgets",
      "434bc9e33d588f61a3cca043d02f2bd211f36efea6d621dccf00c268b67272ff",
    ),
    WEAR_WIDGETS(
      "wear-widgets",
      "9f252a59a2d49e176b311abb11bffd0205ce6810fcae97171fbd812dee343cb0",
    );

    val url: String
      get() = "$BASE$id.guidelines.json"
  }

  /** What a subject is asked: a catalog-shaped name and platform, and the packs layered in. */
  data class Choice(val name: String, val platform: String, val packs: List<Pack>) {
    /** One line for a note or an error: `wear-widgets (remote-compose + wear-widgets)`. */
    val description: String
      get() = "$name (${packs.joinToString(" + ") { it.id }}, compose-ai-tools $TAG)"
  }

  /** The packs for a preview whose manifest [entry] reads as [kind]; null entry: a component. */
  fun choose(kind: GuidelineSubjectKind?, entry: JsonObject?): Choice {
    val wear = isWear(kind, entry)
    return when {
      // A widget on the wear-widgets profile, or one whose manifest names the Wear host without
      // recording a profile: either way not a launcher widget.
      kind?.surface == GuidelineSurfaces.WIDGET && wear ->
        Choice("wear-widgets", "wear", listOf(Pack.REMOTE_COMPOSE, Pack.WEAR_WIDGETS))
      kind?.surface == GuidelineSurfaces.WIDGET ->
        Choice("launcher-widgets", "mobile", listOf(Pack.LAUNCHER_WIDGETS))
      wear -> Choice("wear-compose", "wear", listOf(Pack.COMPOSE_UI, Pack.WEAR_COMPOSE))
      else -> Choice("compose-ui", "mobile", listOf(Pack.COMPOSE_UI))
    }
  }

  /**
   * A `ui-builder.guidelines.json` with no rules of its own that includes [choice]'s packs: what
   * the engine's loader resolves, pins checked, into the merged guidelines.
   */
  fun catalogText(choice: Choice): String = buildJsonObject {
    put("schema", SCHEMA)
    put("catalog", "default-${choice.name}")
    put("platform", choice.platform)
    put("version", 1)
    put(
      "about",
      "The shared packs compose-ai-tools publishes, asked because the module's catalog " +
        "publishes no ${CatalogGuidelinesV1.FILE_NAME}.",
    )
    putJsonArray("rules") {}
    putJsonArray("includes") {
      choice.packs.forEach { pack ->
        addJsonObject {
          put("url", pack.url)
          put("sha256", pack.sha256)
        }
      }
    }
  }
    .toString()

  /**
   * Reads a pack over https, at most [MAX_BYTES] + 1 bytes so the resolver's own size check sees an
   * oversized pack without this buffering all of it.
   */
  fun fetch(url: String): ByteArray {
    require(url.startsWith("https://")) { "not an https URL" }
    val response =
      HTTP.send(
        HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30)).GET().build(),
        HttpResponse.BodyHandlers.ofInputStream(),
      )
    response.body().use { body ->
      check(response.statusCode() == 200) { "answered ${response.statusCode()}" }
      return body.readNBytes(MAX_BYTES + 1)
    }
  }

  /**
   * A Wear subject: a Wear widget, a widget hosted on Wear, or a preview whose device names Wear
   * (`id:wearos_small_round`, `spec:…` from a Wear preview annotation).
   */
  private fun isWear(kind: GuidelineSubjectKind?, entry: JsonObject?): Boolean {
    if (kind?.profile == GuidelineSurfaces.WEAR_WIDGETS_PROFILE) return true
    val widgetHost = ((entry?.get("widget") as? JsonObject)?.get("host") as? JsonPrimitive)
    if (widgetHost?.contentOrNull == "wear") return true
    val device =
      ((entry?.get("params") as? JsonObject)?.get("device") as? JsonPrimitive)?.contentOrNull
    return device?.contains("wear", ignoreCase = true) == true
  }

  private const val SCHEMA = "compose-ui-builder/catalog-guidelines/v1"
  private const val MAX_BYTES = 1024 * 1024

  private val HTTP: HttpClient =
    HttpClient.newBuilder()
      .connectTimeout(Duration.ofSeconds(10))
      .followRedirects(HttpClient.Redirect.NORMAL)
      .build()
}
