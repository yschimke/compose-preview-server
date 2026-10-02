package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.uibuilder.protocol.CatalogReferenceV1
import ee.schimke.composeai.uibuilder.protocol.DesignComponentV1
import ee.schimke.composeai.uibuilder.protocol.DesignDocumentV1
import ee.schimke.composeai.uibuilder.protocol.DesignNodeV1
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.put
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
 * Importing one writes a design — the body plus the `source` record that makes it a reference
 * rather than a copy — and that goes through the design service like every other write, under that
 * design's own access control.
 *
 * **Publishing** is the one write here, and it lands in [store], never in a project: the project's
 * own `ui-builder/components/` is its repository's to change, the way its designs are. [catalogs]
 * are the project coordinates, and they come first in every read, so a component that has been
 * committed to the project shadows the copy this host still holds — and publishing over a committed
 * one is refused rather than quietly shadowed. A host with no [store] serves the read routes alone.
 */
internal fun Route.installUiBuilderComponentLibraryRoutes(
  authorization: ServeUiBuilderAuthorization,
  library: ServeUiBuilderComponentLibrary,
  catalogs: () -> List<ServeUiBuilderDesignLibrary.Coordinate>,
  store: ServeUiBuilderComponentStore? = null,
  /**
   * Why the host's own service would refuse [DesignDocumentV1] as a design, as sentences; empty
   * when it would accept it. The second honest rule — a symbol uses only its pinned catalog's
   * components — needs a catalog, and this is where one is asked. Null checks nothing further.
   */
  validate: (suspend (actorId: String, document: DesignDocumentV1) -> List<String>)? = null,
) {
  val everywhere = { catalogs() + store?.coordinates().orEmpty() }
  get(UI_BUILDER_COMPONENT_LIBRARY_PATH) {
    if (call.authorizedForComponentLibrary(authorization) == null) return@get
    val coordinates = everywhere()
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
    if (call.authorizedForComponentLibrary(authorization) == null) return@get
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
    val matching = everywhere().filter { it.system == system }
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
    call.respondSymbol(symbol, HttpStatusCode.OK)
  }

  if (store == null) return
  put(UI_BUILDER_COMPONENT_SYMBOL_PATH) {
    val actorId =
      call.authorizedForComponentLibrary(authorization, UiBuilderRouteCapability.WRITE)
        ?: return@put
    val system = call.parameters["system"].orEmpty()
    val componentId = call.parameters["componentId"].orEmpty()
    val request = runCatching {
      PUBLISH_JSON.decodeFromString(
        UiBuilderComponentPublishRequest.serializer(),
        call.receiveText(),
      )
    }
      .getOrElse {
        call.respondComponentLibraryError(
          HttpStatusCode.BadRequest,
          "the body is not a component to publish (${it.message})",
        )
        return@put
      }
    // A project that already publishes this id owns it: its file is in a repository, and a host
    // copy under the same name would be shadowed on every read — published, and never seen.
    val committed =
      withContext(Dispatchers.IO) {
        catalogs()
          .filter { it.system == system }
          .any { catalog -> library.index(catalog).any { it.componentId == componentId } }
      }
    if (committed) {
      call.respondComponentLibraryError(
        HttpStatusCode.Conflict,
        "$componentId is committed to the project's own library; change it there",
      )
      return@put
    }
    val problems = validate?.invoke(actorId, request.document).orEmpty()
    if (problems.isNotEmpty()) {
      call.respondComponentLibraryError(
        HttpStatusCode.UnprocessableEntity,
        "this host would not draw the component: ${problems.joinToString("; ")}",
      )
      return@put
    }
    val result =
      withContext(Dispatchers.IO) {
        store.publish(
          system = system,
          componentId = componentId,
          title = request.title,
          description = request.description,
          document = request.document,
          replacesDigest = request.replacesDigest,
        )
      }
    when (result) {
      is ServeUiBuilderComponentStore.PublishResult.Published ->
        call.respondSymbol(
          result.symbol,
          if (result.created) HttpStatusCode.Created else HttpStatusCode.OK,
        )
      is ServeUiBuilderComponentStore.PublishResult.Refused ->
        call.respondComponentLibraryError(HttpStatusCode.UnprocessableEntity, result.reason)
      is ServeUiBuilderComponentStore.PublishResult.Conflict ->
        call.respondComponentLibraryError(
          HttpStatusCode.Conflict,
          result.reason,
          digest = result.currentDigest,
        )
      is ServeUiBuilderComponentStore.PublishResult.Failed ->
        call.respondComponentLibraryError(HttpStatusCode.InternalServerError, result.reason)
    }
  }
}

private suspend fun ApplicationCall.respondSymbol(
  symbol: ServeUiBuilderComponentLibrary.Symbol,
  status: HttpStatusCode,
) {
  respondText(
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
    status,
  )
}

/**
 * What the editor sends to publish one of its components: the one-component document a project
 * would commit, and — to replace a version rather than create one — the digest it replaces.
 */
@Serializable
internal data class UiBuilderComponentPublishRequest(
  val title: String,
  val description: String? = null,
  /** Null publishes a new component; otherwise the version this one replaces, as recorded. */
  val replacesDigest: String? = null,
  val document: DesignDocumentV1,
)

/** The caller's actor id when authorised for [capability]; null once a refusal has been sent. */
private suspend fun ApplicationCall.authorizedForComponentLibrary(
  authorization: ServeUiBuilderAuthorization,
  capability: UiBuilderRouteCapability = UiBuilderRouteCapability.READ,
): String? {
  // Never cached: a project's local checkout is the half that changes under you, and a palette
  // showing yesterday's components is the failure this whole convention exists to avoid.
  response.headers.append(HttpHeaders.CacheControl, "no-store")
  return when (val decision = authorization.authorize(this, capability)) {
    is UiBuilderAuthorizationDecision.Authorized -> decision.actorId
    UiBuilderAuthorizationDecision.Missing -> {
      response.headers.append(HttpHeaders.WWWAuthenticate, "Bearer")
      respondComponentLibraryError(HttpStatusCode.Unauthorized, "authentication is required")
      null
    }
    UiBuilderAuthorizationDecision.Forbidden -> {
      respondComponentLibraryError(
        HttpStatusCode.Forbidden,
        if (capability == UiBuilderRouteCapability.READ) "UI-builder access is required"
        else "UI-builder write access is required to publish a component",
      )
      null
    }
  }
}

private suspend fun ApplicationCall.respondComponentLibraryError(
  status: HttpStatusCode,
  message: String,
  digest: String? = null,
) {
  respondText(
    COMPONENT_LIBRARY_JSON.encodeToString(
      ComponentLibraryErrorResponse.serializer(),
      ComponentLibraryErrorResponse(message, digest),
    ),
    ContentType.Application.Json,
    status,
  )
}

/** [digest] is set on a publish conflict: the version the library holds now. */
@Serializable
private data class ComponentLibraryErrorResponse(val error: String, val digest: String? = null)

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

private val PUBLISH_JSON = Json { ignoreUnknownKeys = true }
