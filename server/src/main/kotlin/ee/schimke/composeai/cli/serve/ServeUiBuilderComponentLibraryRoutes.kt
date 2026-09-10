package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.uibuilder.protocol.CatalogReferenceV1
import ee.schimke.composeai.uibuilder.protocol.DesignComponentV1
import ee.schimke.composeai.uibuilder.protocol.DesignNodeV1
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The component library, where the editor can actually reach it.
 *
 * [ServeUiBuilderComponentLibrary] already had admin routes, and they are the wrong door for this:
 * the Wasm editor authenticates the way every other `/api/ui-builder/v1` call does and holds no
 * `--admin-token`, so a palette could never have read them. The admin pair stays for the operator
 * screen; these are the ones a design imports through.
 *
 * **Authorised once, deliberately** — where a design's own routes are authorised twice. There is no
 * design here to check an actor against: a project's shared components are catalog-level facts,
 * like the catalog a design is pinned to, and the same [UiBuilderRouteCapability.READ] that lets a
 * caller open the builder at all lets them see what the projects this host serves publish. The
 * second check exists on design routes because a design belongs to somebody; a published component
 * belongs to the project, and every caller who can author against that catalog sees the same list.
 *
 * Read-only. Importing one writes a design — the body plus the `source` record that makes it a
 * reference rather than a copy — and that goes through the design service like every other write,
 * under that design's own access control.
 */
internal fun Route.installUiBuilderComponentLibraryRoutes(
  authorization: ServeUiBuilderAuthorization,
  library: ServeUiBuilderComponentLibrary,
  catalogs: () -> List<ServeUiBuilderDesignLibrary.Coordinate>,
) {
  get(UI_BUILDER_COMPONENT_LIBRARY_PATH) {
    if (!call.authorizedForComponentLibrary(authorization)) return@get
    val coordinates = catalogs()
    // On the IO dispatcher and best-effort per project: a cold index is one HTTP round trip per
    // project, and one unreachable branch must not empty the palette for the rest.
    val entries = withContext(Dispatchers.IO) { library.list(coordinates) }
    call.respondText(
      COMPONENT_LIBRARY_JSON.encodeToString(
        UiBuilderComponentLibraryResponse.serializer(),
        UiBuilderComponentLibraryResponse(
          catalogsSearched = coordinates.map { it.system },
          components =
            entries.map {
              UiBuilderComponentDto(
                system = it.system,
                componentId = it.componentId,
                paletteId = ServeUiBuilderComponentLibrary.paletteId(it.componentId),
                title = it.title,
                description = it.description,
              )
            },
        ),
      ),
      ContentType.Application.Json,
    )
  }

  get(UI_BUILDER_COMPONENT_SYMBOL_PATH) {
    if (!call.authorizedForComponentLibrary(authorization)) return@get
    val system = call.parameters["system"].orEmpty()
    val componentId = call.parameters["componentId"].orEmpty()
    if (system.isBlank() || componentId.isBlank()) {
      call.respondComponentLibraryError(
        HttpStatusCode.BadRequest,
        "a system and a component id are required",
      )
      return@get
    }
    // Every coordinate for this system, in configured order, first usable answer — the rule the
    // admin route already follows, and for the same reason: a system can have a local checkout and
    // a served branch, and the listing flattens both.
    val matching = catalogs().filter { it.system == system }
    val symbol =
      withContext(Dispatchers.IO) {
        matching.firstNotNullOfOrNull { catalog ->
          library
            .index(catalog)
            .firstOrNull { it.componentId == componentId }
            ?.let { library.symbol(catalog, it) }
        }
      }
    if (symbol == null) {
      // One answer for "no such project", "no such component" and "published but unusable": from
      // out here they are the same fact — there is no component of that name to import — and
      // telling them apart would say which projects a host serves to a caller who cannot list them.
      call.respondComponentLibraryError(
        HttpStatusCode.NotFound,
        "no usable component called $componentId is published for $system",
      )
      return@get
    }
    call.respondText(
      COMPONENT_LIBRARY_JSON.encodeToString(
        UiBuilderComponentSymbolResponse.serializer(),
        UiBuilderComponentSymbolResponse(
          system = symbol.entry.system,
          componentId = symbol.componentId,
          paletteId = ServeUiBuilderComponentLibrary.paletteId(symbol.componentId),
          title = symbol.entry.title,
          description = symbol.entry.description,
          digest = symbol.digest,
          catalogPin = symbol.catalogPin,
          component = symbol.component,
          nodes = symbol.nodes,
        ),
      ),
      ContentType.Application.Json,
    )
  }
}

private suspend fun ApplicationCall.authorizedForComponentLibrary(
  authorization: ServeUiBuilderAuthorization
): Boolean {
  // Never cached: a project's local checkout is the half that changes under you, and a palette
  // showing yesterday's components is the failure this whole convention exists to avoid.
  response.headers.append(HttpHeaders.CacheControl, "no-store")
  return when (authorization.authorize(this, UiBuilderRouteCapability.READ)) {
    is UiBuilderAuthorizationDecision.Authorized -> true
    UiBuilderAuthorizationDecision.Missing -> {
      response.headers.append(HttpHeaders.WWWAuthenticate, "Bearer")
      respondComponentLibraryError(HttpStatusCode.Unauthorized, "authentication is required")
      false
    }
    UiBuilderAuthorizationDecision.Forbidden -> {
      respondComponentLibraryError(HttpStatusCode.Forbidden, "UI-builder access is required")
      false
    }
  }
}

private suspend fun ApplicationCall.respondComponentLibraryError(
  status: HttpStatusCode,
  message: String,
) {
  respondText(
    COMPONENT_LIBRARY_JSON.encodeToString(
      ComponentLibraryErrorResponse.serializer(),
      ComponentLibraryErrorResponse(message),
    ),
    ContentType.Application.Json,
    status,
  )
}

@Serializable private data class ComponentLibraryErrorResponse(val error: String)

/**
 * One shared component in a listing.
 *
 * Shared by the editor's routes and the operator's, so the two cannot describe the same library
 * differently — the admin screen and the palette are two views of one answer.
 */
@Serializable
internal data class UiBuilderComponentDto(
  val system: String,
  val componentId: String,
  /** What the symbol is called once it reaches a palette: `project/<id>`. */
  val paletteId: String,
  val title: String,
  val description: String? = null,
)

@Serializable
internal data class UiBuilderComponentLibraryResponse(
  val schema: String = "compose-preview-serve/ui-builder-component-library/v1",
  /** Which projects were looked in, so an empty list separates "none served" from "none shared". */
  val catalogsSearched: List<String> = emptyList(),
  val components: List<UiBuilderComponentDto> = emptyList(),
)

/**
 * One checked symbol: what an importing design copies in, and what it records beside it.
 *
 * [digest] is the half that makes this reuse rather than copying — a design writes it into the
 * component's `source`, and a later read computing a different one has found drift to report rather
 * than a redraw to perform silently.
 */
@Serializable
internal data class UiBuilderComponentSymbolResponse(
  val schema: String = "compose-preview-serve/ui-builder-component-symbol/v1",
  val system: String,
  val componentId: String,
  val paletteId: String,
  val title: String,
  val description: String? = null,
  val digest: String,
  val catalogPin: CatalogReferenceV1,
  val component: DesignComponentV1,
  val nodes: Map<String, DesignNodeV1>,
)

internal const val UI_BUILDER_COMPONENT_LIBRARY_PATH = "/api/ui-builder/v1/component-library"

internal const val UI_BUILDER_COMPONENT_SYMBOL_PATH =
  "/api/ui-builder/v1/component-library/{system}/{componentId}"

private val COMPONENT_LIBRARY_JSON = Json { encodeDefaults = true }
