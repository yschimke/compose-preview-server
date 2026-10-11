package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.agentgrants.AgentGrantCapability
import ee.schimke.composeai.agentgrants.AgentGrantProtocol
import ee.schimke.composeai.agentgrants.AgentGrantScope
import ee.schimke.composeai.bundle.BundleVerifier
import ee.schimke.composeai.cli.serve.icons.IconNamesResponse
import ee.schimke.composeai.cli.serve.icons.IconOutlinesResponse
import ee.schimke.composeai.cli.serve.icons.IconRequestFailure
import ee.schimke.composeai.cli.serve.icons.IconResult
import ee.schimke.composeai.cli.serve.icons.MaterialSymbolsIcons
import ee.schimke.composeai.cli.serve.icons.MaterialSymbolsSource
import ee.schimke.composeai.daemon.client.SandboxSparePool
import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import ee.schimke.composeai.daemon.protocol.RemoteComposeOverride
import ee.schimke.composeai.daemon.protocol.RemoteNamedValue
import ee.schimke.composeai.daemon.protocol.StreamCodec
import ee.schimke.composeai.daemon.protocol.UiMode
import ee.schimke.composeai.data.layoutinspector.ComposeFigmaSvgProduct
import ee.schimke.composeai.data.layoutinspector.ExplodedSvg
import ee.schimke.composeai.data.overrides.PreviewOverrideDeclaration
import ee.schimke.composeai.data.remotecompose.RemoteComposeKnobDeclaration
import ee.schimke.composeai.data.render.PreviewClip
import ee.schimke.composeai.designpages.DesignPage
import ee.schimke.composeai.discovery.ComponentRecordFile
import ee.schimke.composeai.imagecrop.ContentCrop
import ee.schimke.composeai.remotecompose.json.RemoteComposeJson
import ee.schimke.composeai.remotecompose.json.RemoteComposeJsonException
import ee.schimke.composeai.uibuilder.export.NewDesignNames
import ee.schimke.composeai.uibuilder.export.UiBuilderNewDesignSeed
import ee.schimke.composeai.uibuilder.export.decodeNewDesignStates
import ee.schimke.composeai.uibuilder.protocol.DesignAccessActionV1
import ee.schimke.composeai.uibuilder.protocol.DesignAccessControlV1
import ee.schimke.composeai.uibuilder.protocol.DesignAccessRoleV1
import ee.schimke.composeai.uibuilder.protocol.GetDesignAccessRequestV1
import ee.schimke.composeai.uibuilder.protocol.GrantActorAccessMutationV1
import ee.schimke.composeai.uibuilder.protocol.ListDesignsRequestV1
import ee.schimke.composeai.uibuilder.protocol.OpenDesignRequestV1
import ee.schimke.composeai.uibuilder.protocol.RevokeActorAccessMutationV1
import ee.schimke.composeai.uibuilder.protocol.UpdateDesignAccessRequestV1
import ee.schimke.composeai.uibuilder.service.AuthenticatedUiBuilderActor
import ee.schimke.composeai.uibuilder.service.UiBuilderAssetPort
import ee.schimke.composeai.uibuilder.service.UiBuilderBranchPort
import ee.schimke.composeai.uibuilder.service.UiBuilderServiceCall
import ee.schimke.composeai.uibuilder.service.UiBuilderServiceDiagnosticsSource
import ee.schimke.composeai.uibuilder.service.UiBuilderServicePort
import ee.schimke.composeai.uibuilder.service.UiBuilderServiceRequest
import ee.schimke.composeai.uibuilder.service.UiBuilderServiceResponse
import ee.schimke.composeai.web.WebEscaping
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.http.content.OutgoingContent
import io.ktor.http.content.TextContent
import io.ktor.http.decodeURLQueryComponent
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.hooks.ResponseBodyReadyForSend
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.http.content.LocalFileContent
import io.ktor.server.plugins.autohead.AutoHeadResponse
import io.ktor.server.plugins.compression.Compression
import io.ktor.server.plugins.compression.gzip
import io.ktor.server.plugins.compression.matchContentType
import io.ktor.server.plugins.compression.minimumSize
import io.ktor.server.plugins.origin
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.request.queryString
import io.ktor.server.request.receiveParameters
import io.ktor.server.request.receiveStream
import io.ktor.server.request.receiveText
import io.ktor.server.response.ApplicationSendPipeline
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.routing
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.util.AttributeKey
import io.ktor.util.pipeline.PipelinePhase
import io.ktor.utils.io.writeStringUtf8
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/** The query values a create link carries into the design's permalink: identity, never content. */
private val UI_BUILDER_IDENTITY_QUERY =
  listOf("token", "actor", "clientId", "displayName", "color", "endpoint", "updatesEndpoint")

/** A `/ui-builder/<designId>` design segment: the New design dialog's own id shape. */
private val UI_BUILDER_DESIGN_SEGMENT = Regex("[A-Za-z0-9][A-Za-z0-9._-]*")

/** Everything a design id may hold that a `Content-Disposition` filename may not. */
private val UNSAFE_FILENAME_CHARACTER = Regex("[^A-Za-z0-9._-]")

/** Extensions that belong to the builder's static distribution rather than to a design id. */
private val UI_BUILDER_ASSET_EXTENSIONS =
  setOf(
    "css",
    "html",
    "ico",
    "js",
    "json",
    "map",
    "mjs",
    "otf",
    "png",
    "svg",
    "ttf",
    "txt",
    "wasm",
    "webp",
    "woff",
    "woff2",
  )

/**
 * The embedded Ktor (CIO) HTTP server fronting a [ServeSessionRegistry]. A thin IO shell: token
 * gate, routes, and query-param → [ServeOverrides] → render → PNG glue. All shared,
 * concurrency-safe state lives in the per-tenant [ServeRenderHost]s; this class keeps no
 * per-request state.
 *
 * **Multi-tenant:** every route resolves a [ServeRenderHost] by the request's `?session=` (falling
 * back to [defaultSessionId]); the registry forks the tenant on first use. Unknown sessions 404
 * like a bad token.
 *
 * Endpoints (all token-gated except `/healthz`, `/readyz`, `/version`, and the `/wasm/` and
 * `/ui-builder/` static assets):
 * - `GET /` landing page, `GET /p/{id}` viewer page,
 * - `GET /{system}/feed.xml` demand-activated catalog change feed,
 * - `GET /render/{id}.png` PNG bytes (`POST` with the parameters in the body for oversized knob
 *   values), `GET /{system}/a2ui` the A2UI document playground,
 * - `GET /api/previews` JSON, `GET /healthz` liveness,
 * - `GET /hero/{system}/{hash}.png` a prebaked, immutable front-door thumbnail ([ServeHeroImages]),
 * - `GET /social/{hash}.png` the link-unfurl card ([ServeSocialCard]), and `GET /favicon.svg` /
 *   `/favicon.ico` / `/apple-touch-icon.png` ([ServeSiteIcon]) — ungated, since unfurlers and icon
 *   fetchers present no token,
 * - `GET /readyz` readiness (green only after every configured design system renders),
 * - `GET /index.json` Storybook stories index, `GET /iframe.html?id=` isolated story render
 *   (`&format=svg` serves an inert SVG) ([StorybookCompat]),
 * - `GET /version` host identity (CLI version, serve schema, public flag),
 * - `GET /bundle.zip` portable bundle, `WS /ws/{id}` streamed-frame lane.
 *
 * A bad/missing token returns **404** (not 401) so the server's existence isn't confirmed to a
 * scanner; tokens are compared in constant time ([ServeUrls.tokensMatch]).
 */
class ServeHttpServer(
  private val host: String,
  requestedPort: Int,
  /** Public origin an imported or newly created design should retain as its canonical home. */
  private val canonicalOrigin: String? = null,
  /** The operator's own browse token (`--token`). Read through [serverToken]. */
  token: String,
  private val sessions: ServeSessionRegistry,
  private val defaultSessionId: String,
  /** When non-null, enables `POST /bundles/{name}` for clients to contribute bundles at runtime. */
  private val bundleStore: ServeBundleStore? = null,
  /**
   * Public mode: serve without requiring the token. For a deployed public preview server. Safe by
   * construction: rendering a bundle/catalog executes no code, re-rendering untrusted Compose is
   * refused, uploads are size-capped and `?url=` fetches are SSRF-gated. Off by default.
   */
  private val isPublic: Boolean = false,
  /** Render the streamlined Storybook-like catalog/component browsing presentation. */
  private val componentBrowser: Boolean = false,
  /**
   * Largest `ir/<id>.rc` `GET /render/<id>.rc.json` will inflate (see [projectDocument]). A
   * parameter so tests can use a small fixture.
   */
  private val maxProjectableDocumentBytes: Int = DEFAULT_MAX_PROJECTABLE_DOCUMENT_BYTES,
  /**
   * In-browser CMP tier: system id → assembled Wasm app directory
   * (`:samples:cmp-wasm-catalog:wasmCatalogDist`). A catalog session listed here offers "Run in
   * browser (Wasm)", mounting `/wasm/<system>/?id=<component>` in a sandboxed iframe. The assets
   * are generic static client code with no session data, so `/wasm/` is ungated and relative
   * fetches need no token.
   */
  private val wasmCatalogs: Map<String, File> = emptyMap(),
  /** Shared browser fallback projected at `/wasm/<system>/` for known catalog sessions. */
  private val wasmUiDir: File? = null,
  /** Independent Compose UI builder app. It is never projected as a catalog Wasm viewer. */
  private val uiBuilderDir: File? = null,
  /** Explicit builder-instance allowlist. A served catalog is not authoring-enabled by default. */
  private val uiBuilderCatalogs: Set<String> = setOf("m3-catalog"),
  /** What a new design can start as; built in unless a catalog owns its seeds. */
  private val uiBuilderSeeds: UiBuilderCatalogSeeds = UiBuilderCatalogSeeds.BUILT_IN,
  /** Retained native renderer directories, snapshotted before this server accepts requests. */
  uiBuilderRuntimeDirs: Map<String, File> = emptyMap(),
  /** Runtime assets activated atomically with a refreshed catalog generation. */
  private val catalogUiBuilderRuntimeAsset:
    (runtimeId: String, segments: List<String>) -> Pair<ByteArray, String>? =
    { _, _ ->
      null
    },
  /** Local auto-discovered apps that must use the credential-carrying `/wasm-private/` route. */
  private val privateWasmCatalogs: Set<String> = emptySet(),
  /**
   * Experimental non-JVM Remote Compose player distribution. When present, its static files are
   * served from `/rc-player-wasm/` and RC previews advertise the `cmp-wasm` browser backend.
   */
  private val rcPlayerWasmDir: File? = null,
  /**
   * The operator's preferred default Remote Compose player (`--rc-default-player`), or null for the
   * built-in order. Reaches the viewer as `data-rc-default` only on a preview that enables it
   * ([ServeRcPlayerIds.defaultPlayer]).
   */
  private val preferredRcPlayer: String? = null,
  /**
   * Registered design-system catalog sessions (`--catalogs`), e.g. `["compose-m3","wear-m3"]`,
   * listed on the front door. Empty ⇒ no nav row.
   */
  private val catalogSessions: List<String> = emptyList(),
  /**
   * App catalogs registered unlisted (`--catalogs-unlisted`): served at `/<system>/` like
   * [catalogSessions] but not listed on the front door or nav — reachable by direct link only. They
   * still count toward whether a home index exists.
   */
  private val appCatalogSessions: List<String> = emptyList(),
  /**
   * **Top-level sites** (`--sites m3.preview.coo.ee=m3-catalog`, or `catalogs.json`'s `sites`):
   * hostnames that serve one published catalog as the whole server. A routing/presentation view
   * over the same session ([ServeSites]); costs a map lookup per request. The live map
   * ([ServeSiteRegistry]) so `/admin/sites` can publish hostnames at runtime ([ServeSiteAdmin]).
   */
  private val sites: ServeSiteRegistry = ServeSiteRegistry.empty(),
  private val uiBuilderHost: String? = null,
  private val uiBuilderStartUrl: String? = null,
  /** `--ui-builder-host-root`; see [uiBuilderRootMode]. */
  private val uiBuilderHostRoot: Boolean = false,
  /**
   * Configured catalog availability shared with startup + refresh, so `/status` includes
   * failed/pending catalogs. `/readyz` validates usable sessions; this makes partial service
   * explicit. Null for plain/test servers.
   */
  private val catalogLoads: CatalogLoadTracker? = null,
  /** Persistent last-successful thumbnails, independent of catalog initialization. */
  private val heroCacheDir: File? = null,
  /** Immediate catalog branch check exposed by `POST /{system}/refresh`. */
  private val catalogRefresh: ((system: String, force: Boolean) -> CatalogRefreshResult)? = null,
  /** Demand-activated, expiring background RSS generator for published catalog history. */
  private val catalogFeed: ServeCatalogChangeFeed? = null,
  portRange: Int = DEFAULT_PORT_RANGE,
  /**
   * Max renders in flight across the HTTP `/render` lane, defaulting to the CPU count. Excess
   * requests wait briefly, then get `503 + Retry-After`. Renders also serialise inside
   * [ServeRenderHost], so this is load shedding, not a parallelism knob.
   */
  maxConcurrentRenders: Int = Runtime.getRuntime().availableProcessors().coerceAtLeast(1),
  /**
   * Permit budget for concurrent live (daemon-backed) stream sessions. Each session charges its
   * backend weight ([ServeSessionState.liveSeatWeight]) — desktop CMP 1, Android/Robolectric more —
   * and one that can't get permits is refused with WebSocket close 1013 rather than risking the OOM
   * killer. `0` (default) is unbounded. Static sessions never consume permits. See
   * [LiveSeatLimiter].
   */
  maxLiveSeats: Int = 0,
  /**
   * A seat budget shared with something built earlier: `serve` passes the limiter it gives the
   * catalog daemon pools, so pooled render daemons and live streams draw on one budget. Null builds
   * a private limiter from [maxLiveSeats].
   */
  liveSeatLimiter: LiveSeatLimiter? = null,
  /**
   * The server's spare Android sandbox workers ([ServeSpareSandboxes]) for `/status.json`; null
   * when none. A supplier, read per request.
   */
  private val spareSandboxSnapshot: () -> SandboxSparePool.Snapshot? = { null },
  /**
   * Recent daemon startup failures, recorded by [ServeCommand.openHost] (every registry relaunch
   * passes through it) and shown on `/status` + `/status.json`. Null ⇒ empty failure list.
   */
  private val daemonLog: DaemonStartupLog? = null,
  /**
   * Whether `--allow-render-trusted` is set (trusted catalogs get a live server-side render lane).
   */
  private val allowRenderTrusted: Boolean = false,
  /**
   * Whether a producer-trust store was configured (`--trust-store`); shown in the status config.
   */
  private val trustStoreConfigured: Boolean = false,
  /** Catalog auto-refresh interval in seconds (`--catalog-refresh-interval`); `0` ⇒ disabled. */
  private val catalogRefreshSeconds: Long = 0,
  /**
   * What `--catalog-registry` nominated and what each nomination gave at boot, shown in the status
   * config so a missing flag and a failed registry read are distinguishable. Empty ⇒ off.
   */
  /**
   * Read per request: the registry sync publishes and retires catalogs at runtime
   * ([catalogRegistryStatus]).
   */
  private val catalogRegistries: () -> List<CatalogRegistryStatus> = { emptyList() },
  /**
   * Catalog-owned UI-builder catalogs this process cannot fully serve, with why; read per request
   * since a refresh can recover one. Reported on `/status` and the designs page.
   */
  private val uiBuilderCatalogProblems: () -> Map<String, String> = { emptyMap() },
  /** Whether `POST /bundles` runtime uploads are accepted (`--accept-bundles`). */
  private val acceptBundlesEnabled: Boolean = false,
  /**
   * Runtime catalog administration ([ServeCatalogAdmin]), persisted to `catalogs.json`. Null ⇒
   * `/admin/catalogs` is not registered and 404s.
   */
  private val catalogAdmin: ServeCatalogAdmin? = null,
  /**
   * GitHub project onboarding ([ServeOnboarding]): `POST /admin/onboard` takes a repository URL,
   * discovers its delivery branches and registers each through [catalogAdmin]. Gated by
   * [adminToken]; null ⇒ not registered. Separate from [catalogAdmin] so tests can build one
   * without the other.
   */
  private val onboarding: ServeOnboarding? = null,
  /**
   * Onboarding a project with nothing published yet ([ServeSourceOnboarding]): `POST
   * /admin/onboard/scan` reports a repository's Compose modules from a shallow clone. Gated by
   * [adminToken]; null ⇒ not registered. Builds nothing; that happens in the import staging
   * repository.
   */
  private val sourceOnboarding: ServeSourceOnboarding? = null,
  /**
   * Runtime producer-trust administration ([ServeTrustAdmin]): trusted branches / keys / CI
   * identities without an image rebuild. Gated by [adminToken]; null ⇒ not registered.
   *
   * With `--allow-render-trusted`, trusting a branch makes that producer's Compose eligible for
   * server-side execution, so this token is powerful.
   */
  private val trustAdmin: ServeTrustAdmin? = null,
  /**
   * Runtime site administration ([ServeSiteAdmin]) for the hostnames in [sites], persisted to
   * `catalogs.json`. Gated by [adminToken]; null ⇒ `/admin/sites` not registered.
   */
  private val siteAdmin: ServeSiteAdmin? = null,
  /**
   * The instance's UI-builder editor pin ([ServeUiBuilderEditorAdmin]): which editor release
   * `catalogs.json` pins, verified before writing. Gated by [adminToken]; null ⇒ `/admin/editor`
   * not registered.
   */
  private val editorAdmin: ServeUiBuilderEditorAdmin? = null,
  /**
   * The UI builder's catalog settings ([ServeUiBuilderSettingsAdmin]): `catalogs.json`'s
   * `uiBuilder` block, replacing the `SERVE_UI_BUILDER_*` variables. Gated by [adminToken]; null ⇒
   * not registered.
   */
  private val uiBuilderSettingsAdmin: ServeUiBuilderSettingsAdmin? = null,
  /**
   * Deployment settings ([ServeSettingsAdmin]): `settings.json`, home of every non-secret `SERVE_*`
   * setting, with each value's source. Gated by [adminToken]; null ⇒ not registered.
   */
  private val settingsAdmin: ServeSettingsAdmin? = null,
  /**
   * Runtime UI-builder administration ([ServeUiBuilderAdmin]): list every design and delete any.
   * Gated by [adminToken], [adminReadToken], or a configured [uiBuilderAdministrators] identity;
   * null ⇒ not registered. The actor allowlist is deliberately narrower than the admin token.
   */
  private val uiBuilderAdmin: ServeUiBuilderAdmin? = null,
  /**
   * The designs catalog projects publish ([ServeUiBuilderDesignLibrary]), browsable from the admin
   * screen. Same gate and null rule as [uiBuilderAdmin]; opening one writes a design.
   */
  private val uiBuilderDesignLibrary: ServeUiBuilderDesignLibrary? = null,
  /**
   * Which catalogs to look in, read per request since catalogs are registered and branch heads move
   * at runtime.
   */
  private val uiBuilderDesignCatalogs: () -> List<ServeUiBuilderDesignLibrary.Coordinate> = {
    emptyList()
  },
  /**
   * The components those projects publish ([ServeUiBuilderComponentLibrary]). Read-only, but behind
   * the admin token because it exposes the same projects.
   */
  private val uiBuilderComponentLibrary: ServeUiBuilderComponentLibrary? = null,
  /**
   * Components this host's editors publish ([ServeUiBuilderComponentStore]), read after the
   * projects' so a committed component shadows the host copy. Null serves the library read-only.
   */
  private val uiBuilderComponentStore: ServeUiBuilderComponentStore? = null,
  /**
   * The record the Compose export generates a catalog's designs from, so the browser's code pane
   * reads the same one ([installUiBuilderCatalogRecordRoutes]). A function because records can
   * arrive after construction. The default serves none.
   */
  private val uiBuilderCatalogRecord: (catalogSystemId: String) -> ComponentRecordFile? = { null },
  /**
   * Shared secret for the admin routes (`--admin-token`), separate from the browse [token] and
   * gated even when [isPublic]. Null/blank ⇒ no admin routes.
   */
  private val adminToken: String? = null,
  /**
   * Narrow diagnostic credential for the UI-builder design list; never admits a document body or
   * mutation. See [rejectBadAdminToken].
   */
  private val adminReadToken: String? = null,
  /** GitHub identities that administer UI-builder designs, without reaching other admin lanes. */
  uiBuilderAdminActors: Set<String> = emptySet(),
  /**
   * When non-null, enables the **document** lane: `GET /docs` (upload page), `POST /docs` (ingest a
   * known format), `GET /d/{id}` (expiring permalink). Supplied by `--accept-docs`; independent of
   * [bundleStore].
   */
  private val docStore: ServeDocStore? = null,
  /**
   * When non-null, enables the **image** lane: `POST /images` ingests a rendered preview PNG and
   * `GET /i/{id}.png` serves it at an embeddable URL. Supplied by `--accept-images`.
   *
   * Uploading requires a GitHub account with access to the operator's repository
   * ([imageUploadAuth]); `ServeCommand` refuses to start the lane without it. Reads are open so
   * GitHub's image proxy can fetch them; the unguessable id is the access control. See
   * [ServeImageStore].
   */
  private val imageStore: ServeImageStore? = null,
  /** Who may upload to [imageStore]. Non-null exactly when that store is; see its KDoc. */
  private val imageUploadAuth: ServeImageUploadAuth? = null,
  /**
   * Per-login budget on `POST /images`, bounding store filling and GitHub API calls per upload.
   * Null ⇒ unlimited.
   */
  private val imageUploadLimiter: ServeRateLimiter? = null,
  /**
   * When non-null, enables the **playground** lane: `POST /api/{version}/compiler/run` compiles a
   * snippet against a catalog classpath and returns diagnostics + an expiring preview token.
   * Supplied by `--playground-bundle`. It runs user code, so under `--public` it is only wired
   * behind a sandbox that passed the startup containment probe ([PlaygroundPublicGate]). See
   * [docs/design/PLAYGROUND.md](../../../../../../../../docs/design/PLAYGROUND.md).
   */
  private val playgroundService: PlaygroundCompileService? = null,
  /**
   * Set when a sibling process serves the public playground at this origin
   * (`--playground-external`): handoff links render against this engine's catalogs, but
   * [playgroundService]'s routes aren't mounted.
   */
  private val externalPlaygroundLinks: PlaygroundCompileService? = null,
  /**
   * When non-null, enables Stage-2 redemption: `GET /pg/<token>` redeems a preview token into a
   * live session and redirects to its viewer. Shares a [PlaygroundTokenStore] with
   * [playgroundService].
   *
   * Present without [playgroundService] on a `--compile-engine` host: the UI builder's native pane
   * redeems tokens in process, but `/pg/` is mounted only with the public surface.
   */
  private val playgroundRedeem: PlaygroundRedeemService? = null,
  /**
   * Optional GitHub auth: public browsing stays open while code-running surfaces (playground, live
   * WebSocket) require sign-in.
   */
  private val githubAuth: ServeGithubAuth? = null,
  /**
   * `ui_builder_check_design`'s `guidelines` check on the operator's OpenRouter key, open only to
   * `--ui-builder-guidelines-users` / `--ui-builder-guidelines-orgs`. Null reports it skipped.
   */
  private val uiBuilderGuidelines: ServeUiBuilderGuidelines? = null,
  /** Each builder catalog's own guidelines; null where the host keeps none. */
  private val uiBuilderCatalogGuidelines: ServeCatalogGuidelines? = null,
  /** `--ui-builder-guidelines-picture-budget`, in seconds. */
  private val uiBuilderGuidelinesPictureBudgetSeconds: Long =
    DEFAULT_GUIDELINES_PICTURE_BUDGET_SECONDS,
  /** The picture budget as `settings.json` now sets it; null ⇒ the startup value, fixed. */
  private val uiBuilderGuidelinesPictureBudgetSecondsLive: (() -> Long)? = null,
  /**
   * Resolve a browser session into an image-uploader login for [ServeImageUploadAuth.repository].
   *
   * For the bug-report page, whose signed OAuth cookie never carries the OAuth token. [ServeRunner]
   * wires this only when the cookie proves access to exactly the gated repository. A function so
   * the HTTP boundary is testable without a signed cookie.
   */
  private val imageBrowserLogin: ((ApplicationCall, String) -> String?)? = null,
  /**
   * When non-null, enables **agent access grants**: `POST /agent-access/request` opens a request,
   * `GET /agent-access/{id}` is the human approval page, and `POST /agent-access/poll` hands the
   * bearer to the agent. Supplied by `--agent-grants`. See
   * [docs/design/AGENT_ACCESS_GRANTS.md](../../../../../../../../docs/design/AGENT_ACCESS_GRANTS.md).
   *
   * A grant satisfies the same gates a human does — [rejectBadToken], [rejectMissingGithubAuth],
   * [rejectMissingGithubRepoAccess] — adding no new surface.
   */
  private val agentGrants: ServeAgentGrantStore? = null,
  /** Durable public OAuth client metadata; grants and authorization codes remain ephemeral. */
  private val mcpOAuthClientsFile: File? = null,
  /**
   * Per-address budget on the two ungated grant routes (`request`, `poll`) — the only bound on
   * anonymous callers filling the request map. Null ⇒ unlimited.
   */
  private val agentGrantLimiter: ServeRateLimiter? = null,
  /** Register Streamable HTTP MCP endpoints for served catalogs. */
  private val catalogMcpEnabled: Boolean = false,
  /**
   * How long a request-scoped MCP elicitation may wait for a person before falling back to its text
   * decision. A test seam; production uses two minutes.
   */
  private val catalogMcpInteractionTimeoutMillis: Long =
    ServeMcpRequestScopes.DEFAULT_INTERACTION_TIMEOUT_MILLIS,
  /** Shared bearer/session resolver used by catalog MCP and UI-builder authorization. */
  private val machineAuthorization: ServeMachineAuthorization? = null,
  /** Authoritative editable-design service. Null keeps the design API unregistered. */
  uiBuilderService: UiBuilderServicePort? = null,
  /** Independent human/operator/agent authorization for [uiBuilderService]. */
  private val uiBuilderAuthorization: ServeUiBuilderAuthorization? = null,
  /** The design listing's cached card pictures; null draws cards from the live export. */
  private val uiBuilderThumbnails: ServeUiBuilderThumbnails? = null,
  /**
   * Compiles and renders a design with real Compose. Non-null only when both the builder and the
   * playground compile lane are configured.
   */
  private val uiBuilderNativePreview: UiBuilderNativePreviewLane? = null,
  /**
   * Captures a design's inline Remote Compose content into its document. Non-null on the same hosts
   * as [uiBuilderNativePreview].
   */
  private val uiBuilderInlineCapture: UiBuilderInlineCaptureLane? = null,
  /**
   * Per-design reference overlays. Null leaves the routes unregistered on hosts without durable
   * UI-builder state.
   */
  private val uiBuilderReferenceStore: ServeUiBuilderReferenceStore? = null,
  /** Per-design comment threads. Null leaves the routes unregistered (no durable state). */
  private val uiBuilderCommentStore: ServeUiBuilderCommentStore? = null,
  /**
   * Per-design back-links (issue, frame, PR, thread, predecessor design). Null leaves the routes
   * unregistered (no durable state).
   */
  private val uiBuilderLinksStore: ServeUiBuilderLinksStore? = null,
  /**
   * Per-design review verdicts and the implementing PR. Null leaves the review routes and
   * decision/implementation tools unregistered.
   */
  private val uiBuilderReviewStore: ServeUiBuilderReviewStore? = null,
  /**
   * Each design's latest guidelines result. Null leaves the result routes and get/record tools
   * unregistered; the prompt route and tool stay.
   */
  private val uiBuilderGuidelineStore: ServeUiBuilderGuidelineStore? = null,
  /** Shared server-side folders for designs; null leaves folder organization unavailable. */
  private val uiBuilderFolderStore: ServeUiBuilderFolderStore? = null,
  private val uiBuilderProjectStore: ServeUiBuilderProjectStore? = null,
  /**
   * The asset lane of [uiBuilderService] (the bytes behind a design's `assets` map). Null leaves
   * the asset routes and `ui_builder_put_asset` unregistered (no durable state).
   */
  uiBuilderAssets: UiBuilderAssetPort? = null,
  /**
   * The branch lane of [uiBuilderService]: fork, list, merge and archive design branches. Null
   * leaves the branch tools unregistered. It is the service itself, since the runtime records a
   * branch's parent.
   */
  uiBuilderBranches: UiBuilderBranchPort? = null,
  /**
   * Checks a design or mutation batch without saving (`ui_builder_validate`). Null leaves the tool
   * unadvertised.
   */
  private val uiBuilderValidator: UiBuilderDraftValidator? = null,
  /**
   * Playground observability for `/status.json`: admitting posture, whether the jail contains
   * anything, and per-mode classpath resolution. Null when the lane isn't wired. See
   * [PlaygroundHealth].
   */
  private val playgroundHealth: (() -> PlaygroundHealth)? = null,
  /**
   * Delivery-branch read counters for `/status.json`, from the catalog store. A provider since the
   * numbers move; null without a catalog store.
   */
  private val branchFetchStats: (() -> BranchFetchSnapshot?)? = null,
  /** Cross-catalog optimizer admission for `/status.json`, read from the shared background-work. */
  private val themeOptimizerStats: (() -> ThemeOptimizerAdmissionSnapshot?)? = null,
  /** Disk tier for warmed theme renders, for `/status.json`. Null when persistence is off. */
  private val themeCacheStats: (() -> ThemeCacheStoreSnapshot?)? = null,
  /**
   * Catalog blob cache occupancy and read outcomes for `/status.json`. Null on a server with no
   * catalogs.
   */
  private val catalogCacheStats: (() -> CatalogBlobPoolSnapshot?)? = null,
  /**
   * Drop the catalog blob cache, for `DELETE /admin/catalog-cache`. Separate from
   * [catalogCacheStats] because discarding needs the admin token.
   */
  private val catalogCacheClear: (() -> CatalogBlobPoolSnapshot)? = null,
  /**
   * The shared background-work handle for the admin pause/resume routes. Separate from
   * [themeOptimizerStats] because pausing needs the admin token.
   */
  private val themeOptimizerAdmin: ServeBackgroundWork? = null,
  /**
   * Per-caller budget on the compile lane, or null for unmetered; otherwise two busy callers can
   * hold every slot.
   */
  private val playgroundRateLimiter: ServeRateLimiter? = null,
  /**
   * Trust the **last** `X-Forwarded-For` entry as the client address for anonymous rate limiting,
   * instead of the socket peer.
   *
   * Opt-in because the header is client-supplied: on a directly exposed host it would let callers
   * forge identities. The last entry is the one a single reverse proxy appended from the peer it
   * saw (nginx `$proxy_add_x_forwarded_for`; Caddy without `trusted_proxies` replaces the header).
   * Exactly one hop of trust; behind two proxies it names the inner one.
   */
  private val trustForwardedFor: Boolean = false,
  /**
   * Reads a preview's Kotlin off GitHub for the `/playground?from=…` handoff. Null disables the
   * handoff. Injected so tests never reach the network.
   */
  private val playgroundSourceFetch: ((String) -> ByteArray?)? = null,
  /** Aggregate view counts; pass a file-backed store to keep them across server restarts. */
  private val engagementStore: ServeEngagementStore = ServeEngagementStore(),
  /**
   * Project mode's local render history. When non-null, a viewer whose session has no delivery
   * provenance inlines the timeline and `GET /history/render/{blob}.png` serves old renders from
   * the local object store. Null leaves both out.
   */
  private val projectHistory: ServeProjectHistory? = null,
  /** Trusted module roots for local browse sessions, keyed by their session ids. */
  private val localSourceRoots: Map<String, File> = emptyMap(),
  /**
   * This machine's site-local addresses for the "Open on your phone" card on a `--lan` server. A
   * parameter for tests.
   */
  private val lanAddresses: () -> List<String> = ServeUrls::siteLocalIpv4Addresses,
  /**
   * Web Push ([installPushRoutes]): subscriptions and the VAPID key. Null without GitHub sign-in or
   * a UI builder; the routes then 404.
   */
  private val push: ServePushLane? = null,
) {

  /**
   * Which design a branch was forked from, so a grant naming a design also reaches its branches
   * ([ServeUiBuilderGrantScope]). Null when branches aren't wired.
   */
  private val projectBranches: UiBuilderBranchPort? = uiBuilderBranches?.let { raw ->
    uiBuilderProjectStore?.let { ServeUiBuilderProjectBranches(raw, it) } ?: raw
  }
  private val branchParents: ServeUiBuilderGrantScope.Parents? =
    projectBranches?.let(ServeUiBuilderGrantScope::parentsOf)

  /**
   * The design service every route, sidecar, stream and MCP tool uses, with grants held to the
   * designs they name ([ServeUiBuilderGrantScope]). Nothing here reaches the unwrapped port, which
   * is why the constructor parameter is not a property.
   */
  private val designService: UiBuilderServicePort? = uiBuilderService?.let { raw ->
    val service =
      uiBuilderProjectStore?.let { ServeUiBuilderProjectService(raw, it, uiBuilderBranches) } ?: raw
    agentGrants?.let {
      ServeUiBuilderGrantScope.limit(service, ServeUiBuilderGrantScope.lookupOf(it), branchParents)
    } ?: service
  }

  /** The asset lane of [designService], under the same limit. */
  private val designAssets: UiBuilderAssetPort? = uiBuilderAssets?.let { raw ->
    val assets =
      uiBuilderProjectStore?.let { ServeUiBuilderProjectAssets(raw, it, uiBuilderBranches) } ?: raw
    agentGrants?.let {
      ServeUiBuilderGrantScope.limit(assets, ServeUiBuilderGrantScope.lookupOf(it), branchParents)
    } ?: assets
  }

  /** The branch lane of [designService], under the same limit. */
  private val designBranches: UiBuilderBranchPort? = projectBranches?.let { branches ->
    agentGrants?.let {
      ServeUiBuilderGrantScope.limit(branches, ServeUiBuilderGrantScope.lookupOf(it), branchParents)
    } ?: branches
  }

  /** Read off the service itself: the limit above is a decorator and reports nothing. */
  private val uiBuilderDiagnostics = uiBuilderService as? UiBuilderServiceDiagnosticsSource

  private val uiBuilderAdministrators = ServeUiBuilderAdministrators(uiBuilderAdminActors)

  private val uiBuilderRuntimeAssets = ServeUiBuilderRuntimeAssets.load(uiBuilderRuntimeDirs)

  /**
   * Resolves `/playground?from=<system>/<previewId>` to the preview's source, using this server's
   * own registry metadata for the fetch URL. Null without a fetcher.
   */
  private val playgroundSeeds: PlaygroundSeedResolver? = playgroundSourceFetch
  // Not gated on `playgroundService`: the viewer's Source panel needs cleaned source on every host
  // that can browse a catalog, including ones that can't compile it. `playgroundLinkFor` checks for
  // a compiler itself.
  ?.let { fetch ->
    PlaygroundSeedResolver(
      locate = ::sourceLocationFor,
      fetch = fetch,
      onLog = { System.err.println("serve: playground seed: $it") },
    )
  }

  /**
   * Answers the Dev-mode `uses:` filter (which previews call a composable), built on the same
   * metadata and fetcher as the seed resolver. Null without a fetcher.
   */
  private val previewUsage: PreviewUsageIndex? = playgroundSourceFetch?.let { fetch ->
    PreviewUsageIndex(
      locate = ::sourceLocationFor,
      fetch = fetch,
      onLog = { System.err.println("serve: usage index: $it") },
    )
  }

  /**
   * Where a preview's source lives across resident / suspended / retired sessions, shared by the
   * seed resolver and usage index so they can't drift.
   */
  private fun sourceLocationFor(
    system: String,
    previewId: String,
  ): PlaygroundSeedResolver.Location? {
    // peekHost, not lease: this is a metadata read, and leasing would stand a daemon up just
    // to answer where a file lives.
    val host = sessions.peekHost(system)
    return when {
      // Resident: authoritative both ways. A live host that doesn't list the preview answers "no"
      // and drops any stale remembered entry.
      host != null -> sourceLocationOf(host, previewId)
      // Suspended but still served: answer from the snapshot taken at suspension (of the
      // replacement, if the catalog was refreshed).
      sessions.isKnownSession(system) -> catalogSourceLocationsSeen[system]?.get(previewId)
      // Retired: the catalog is gone, so answer nothing rather than keep serving (and re-fetching)
      // a remembered location.
      else -> null
    }
  }

  /** Where a preview's source lives according to [host], or null when it cannot say. */
  private fun sourceLocationOf(
    host: ServeHost,
    previewId: String,
  ): PlaygroundSeedResolver.Location? {
    val bundleHost = catalogBundleHost(host) ?: return null
    val source = bundleHost.catalogSource ?: return null
    val preview = bundleHost.previews.firstOrNull { it.id == previewId } ?: return null
    val sourceFile = preview.sourceFile?.takeIf { it.isNotBlank() } ?: return null
    return PlaygroundSeedResolver.Location(
      repo = source.repo,
      ref = source.ref,
      module = preview.sourceModule ?: source.module,
      sourceFile = sourceFile,
      // Absent on a catalog published before discovery recorded it, which is exactly the case the
      // resolver falls back to whole-file seeding for.
      bodyLine = preview.bodyLine,
    )
  }

  /** The actual bound port — may differ from the requested one if it was taken (auto-picked). */
  val port: Int = pickPort(host, requestedPort, portRange)

  /**
   * The server home stamped on created or imported designs, or null. Only an operator-stated origin
   * counts (`--ui-builder-public-origin`, else `--github-auth-callback-base-url`); a bind address
   * is not an identity.
   */
  private val canonicalServerOriginValue: String? = canonicalOrigin?.let { configured ->
    requireNotNull(normalizeServerHomeUrl(configured)) {
      "the configured public origin '$configured' is not an absolute HTTP(S) URL"
    }
  }

  private fun canonicalServerOrigin(): String? = canonicalServerOriginValue

  /** Concurrent-render slot count (the `/render` load-shed bound), surfaced on `/status`. */
  private val renderSlots: Int = maxConcurrentRenders.coerceAtLeast(1)

  /** Wall-clock the server was constructed — the basis for the `/status` uptime figure. */
  private val startedAtMillis: Long = System.currentTimeMillis()

  /**
   * Frame counters for the live socket lane (`liveFrames` on `/status.json`). Owned here because a
   * socket outlives its streams and follows the client. See [LiveFramePerfStats].
   */
  private val liveFrameStats = LiveFramePerfStats()

  private val renderSemaphore = Semaphore(renderSlots)
  private val uiBuilderAgentPresence = ServeUiBuilderAgentPresence()
  /**
   * The UI-builder MCP tools, built once and shared by the MCP endpoint and the guidelines prompt
   * route.
   */
  private val uiBuilderMcp: ServeUiBuilderMcp? by lazy {
    designService?.let {
      ServeUiBuilderMcp(
        it,
        ::canonicalServerOrigin,
        uiBuilderNativePreview,
        uiBuilderCommentStore,
        references = uiBuilderReferenceStore,
        links = uiBuilderLinksStore,
        assets = designAssets,
        validator = uiBuilderValidator,
        reviews = uiBuilderReviewStore,
        branches = designBranches,
        agentPresence = uiBuilderAgentPresence,
        guidelines = uiBuilderGuidelines,
        guidelineRecords = uiBuilderGuidelineStore,
        guidelineFrames = uiBuilderThumbnails?.guidelineFrames,
        widgetThumbnail = { designId, revision ->
          uiBuilderThumbnails?.nativeWidgetThumbnail(designId, revision)
        },
        guidelinePictureBudgetMillis = {
          (uiBuilderGuidelinesPictureBudgetSecondsLive?.invoke()
            ?: uiBuilderGuidelinesPictureBudgetSeconds) * 1_000
        },
        catalogGuidelines = uiBuilderCatalogGuidelines,
      )
    }
  }

  private val catalogMcp =
    if (catalogMcpEnabled && machineAuthorization != null)
      ServeCatalogMcp(
        sessions,
        renderSemaphore,
        projectHistory = projectHistory,
        uiBuilder = uiBuilderMcp,
        uiBuilderNative = uiBuilderNativePreview != null,
        publicOrigin = ::canonicalServerOrigin,
        pendingCatalogs = {
          catalogLoads
            ?.snapshot()
            ?.filter { it.loadState == "pending" }
            ?.map { it.config.system }
            .orEmpty()
        },
      )
    else null
  /**
   * Ephemeral request/response rendezvous only; authoritative application state stays elsewhere.
   */
  private val catalogMcpRequestScopes = catalogMcp?.let {
    ServeMcpRequestScopes(maxInteractionTimeoutMillis = catalogMcpInteractionTimeoutMillis)
  }
  private val unleasedThemeSemaphore = Semaphore(1)
  private val themeRenderLeases = ThemeRenderLeaseManager(renderSlots)

  /** Catalog ids with a manual branch check in flight; public callers coalesce at this boundary. */
  private val catalogRefreshesInFlight = ConcurrentHashMap.newKeySet<String>()

  /**
   * Readiness latch for `/readyz`, the rolling-update gate. Unlike `/healthz` (listener up), this
   * is true only once every configured design system has actually rendered here, so a broken new
   * replica isn't promoted.
   *
   * Every design system, not one representative preview, which let a catalog with no renders go
   * live. [ServeCatalogsConfig.DESIGN_SYSTEMS_GROUP] explains why only that group. Latches on first
   * success; the probe render runs at most once ([readinessProber]).
   *
   * Set by the server-owned [readinessProber] thread, never in a request coroutine, so `/readyz`
   * stays instant and a healthcheck timeout can't cancel a first render.
   */
  private val ready = AtomicBoolean(false)

  /** Starts [readinessProber] exactly once, on the first `/readyz` poll (idempotent). */
  private val readinessProbeStarted = AtomicBoolean(false)

  /**
   * The background thread that renders the design systems until all succeed, then latches [ready].
   * Started lazily by the first `/readyz` poll, interrupted on [stop], and retries on failure.
   */
  @Volatile private var readinessProber: Thread? = null

  /**
   * Live-seat limiter: a permit budget ([maxLiveSeats]) charged per session by backend weight. `<=
   * 0` ⇒ unbounded. See [LiveSeatLimiter].
   */
  private val liveSeats: LiveSeatLimiter = liveSeatLimiter ?: LiveSeatLimiter(maxLiveSeats)

  /**
   * Prebaked front-door hero thumbnails for `/hero/`, baked once per catalog host
   * ([rememberCatalogMeta]). See [ServeHeroImages].
   */
  private val heroImages =
    ServeHeroImages(heroCacheDir).also { images ->
      catalogLoads?.snapshot()?.forEach { images.cached(it.config) }
    }

  /**
   * Bakes the PNGs a page build missed, off the request thread, so the next build can thumbnail
   * them ([ServeThumbWarmer]).
   */
  private val thumbWarmer =
    ServeThumbWarmer(onLog = { System.err.println("serve: thumbnail warm: $it") })

  /**
   * Drawn link-unfurl cards for `/social/`, composed from the hero thumbnails and memoised by
   * inputs. See [ServeSocialCard].
   */
  private val socialCards = ServeSocialCard()

  /**
   * Whether `/admin/catalogs` exists: requires both [catalogAdmin] and [adminToken]. Fail-closed.
   */
  private val adminEnabled: Boolean = catalogAdmin != null && !adminToken.isNullOrBlank()

  /** As [adminEnabled], for the `/admin/trust` routes. Same token, separately supplied admin. */
  private val trustAdminEnabled: Boolean = trustAdmin != null && !adminToken.isNullOrBlank()

  /** As [adminEnabled], for the `/admin/sites` routes. Same token, separately supplied admin. */
  private val siteAdminEnabled: Boolean = siteAdmin != null && !adminToken.isNullOrBlank()

  /** As [adminEnabled], for the `/admin/editor` routes. */
  private val editorAdminEnabled: Boolean = editorAdmin != null && !adminToken.isNullOrBlank()

  /** As [adminEnabled], for the `/admin/ui-builder/config` routes. */
  private val uiBuilderSettingsAdminEnabled: Boolean =
    uiBuilderSettingsAdmin != null && !adminToken.isNullOrBlank()

  /** As [adminEnabled], for the `/admin/settings` routes. */
  private val settingsAdminEnabled: Boolean = settingsAdmin != null && !adminToken.isNullOrBlank()

  /** As [adminEnabled], for `/admin/ui-builder`, with the additional UI-builder-only actor gate. */
  private val uiBuilderAdminEnabled: Boolean =
    uiBuilderAdmin != null &&
      (!adminToken.isNullOrBlank() ||
        !adminReadToken.isNullOrBlank() ||
        uiBuilderAdministrators.configured)

  /** As [uiBuilderAdminEnabled], for the `/admin/ui-builder/library` routes. */
  private val uiBuilderDesignLibraryEnabled: Boolean =
    uiBuilderDesignLibrary != null &&
      designService != null &&
      uiBuilderDir != null &&
      !adminToken.isNullOrBlank()

  /**
   * As [uiBuilderDesignLibraryEnabled], for `/admin/ui-builder/component-library`. Needs no service
   * of its own, so a read-only editor host can still offer it.
   */
  private val uiBuilderComponentLibraryEnabled: Boolean =
    uiBuilderComponentLibrary != null && !adminToken.isNullOrBlank()

  /** As [adminEnabled], for `POST /admin/onboard`. Same token, separately supplied onboarder. */
  private val onboardingEnabled: Boolean = onboarding != null && !adminToken.isNullOrBlank()

  /** As [onboardingEnabled], for the `/admin/onboard/scan` route. */
  private val sourceOnboardingEnabled: Boolean =
    sourceOnboarding != null && !adminToken.isNullOrBlank()

  /**
   * As [adminEnabled], for `DELETE /admin/catalog-cache`; requires the token so an unconfigured box
   * exposes nothing.
   */
  private val catalogCacheAdminEnabled: Boolean =
    catalogCacheClear != null && !adminToken.isNullOrBlank()

  /** Explicit hosts admitted to the existing OAuth return and browser-origin checks. */
  private val browserHosts: Set<String>
    get() = sites.hosts + listOfNotNull(uiBuilderHost)

  private fun isUiBuilderHost(call: ApplicationCall): Boolean =
    uiBuilderHost != null && requestHost(call, trustForwardedFor) == uiBuilderHost

  /**
   * The builder is served at the root of [uiBuilderHost] (`ServeUiBuilderHostRoot.kt`); decided
   * once at bind time. An editor that can't read its base path ([uiBuilderRootEditorProblem])
   * keeps the host on `/ui-builder/` and logs why.
   */
  private val uiBuilderRootMode: Boolean =
    uiBuilderHost != null &&
      uiBuilderHostRoot &&
      uiBuilderRootEditorProblem(uiBuilderDir).let { problem ->
        if (problem != null) {
          System.err.println(
            "serve: ERROR --ui-builder-host-root ignored, $uiBuilderHost stays on /ui-builder/: " +
              problem
          )
        }
        problem == null
      }

  /** Whether [call] is on the rooted builder host, where the editor's pages live at `/`. */
  private fun isUiBuilderRootCall(call: ApplicationCall): Boolean =
    uiBuilderRootMode && isUiBuilderHost(call)

  /**
   * A builder page's path as [call]'s host spells it: `/<rest>` on the rooted builder host,
   * `/ui-builder/<rest>` elsewhere, so server-written redirects land in one hop instead of via
   * [uiBuilderRootRedirect].
   */
  private fun uiBuilderPagePath(call: ApplicationCall, rest: String): String =
    if (isUiBuilderRootCall(call)) "/$rest" else "/ui-builder/$rest"

  private val server: EmbeddedServer<*, *> =
    embeddedServer(CIO, host = host, port = port) {
      install(WebSockets)
      // A socket opened with the GitHub session cookie is accepted only from a page this server
      // served ([ServeSameOriginRequests]). Checked before the upgrade so refusal is a plain 403.
      // Covers every socket route; header/query-token clients send no cookie and are unaffected.
      intercept(ApplicationCallPipeline.Plugins) {
        val current: ApplicationCall = context
        if (
          ServeSameOriginRequests.isWebSocketUpgrade(current) &&
            ServeSameOriginRequests.isForeignSessionRequest(current, browserHosts)
        ) {
          current.respondText("socket origin not accepted", status = HttpStatusCode.Forbidden)
          finish()
        }
      }
      // A top-level page load carrying exactly the operator token in its query trades it for the
      // browse cookie ([ServeBrowseCookie]) and is redirected without it, keeping the token out of
      // the address bar, history and links. Only on a gated box; anything else falls through.
      if (!isPublic && serverToken.isNotBlank()) {
        intercept(ApplicationCallPipeline.Plugins) {
          val current: ApplicationCall = context
          val target = ServeBrowseCookie.exchangeTarget(current, serverToken) ?: return@intercept
          current.response.cookies.append(
            ServeBrowseCookie.cookie(serverToken, secure = isSecure(current))
          )
          current.response.headers.append(HttpHeaders.CacheControl, "no-store")
          current.response.headers.append(HttpHeaders.Location, target)
          current.respond(HttpStatusCode.Found)
          finish()
        }
      }
      // A browser arriving with a short-lived agent grant exchanges it for a derived HttpOnly
      // credential backed by the same grant, then the URL is cleaned. Installed on public boxes
      // too, since UI-builder write/export still needs a grant.
      agentGrants?.let { store ->
        intercept(ApplicationCallPipeline.Plugins) {
          val current: ApplicationCall = context
          val exchange = ServeAgentGrantCookie.exchange(current, store)
          // A human identity wins over an ambient grant. Still strip a cpat URL they opened, but
          // clear any previous grant cookie instead of silently switching their browser identity.
          val humanPresent =
            current.presentsOperatorCredential() ||
              githubAuth?.currentSignedInLogin(current) != null
          if (humanPresent) {
            if (
              exchange != null ||
                current.request.cookies.rawCookies[ServeAgentGrantCookie.NAME] != null
            ) {
              current.response.cookies.append(
                ServeAgentGrantCookie.clearedCookie(secure = isSecure(current))
              )
            }
            if (exchange == null) return@intercept
            current.response.headers.append(HttpHeaders.CacheControl, "no-store")
            current.response.headers.append(HttpHeaders.Location, exchange.target)
            current.respond(HttpStatusCode.Found)
            finish()
            return@intercept
          }
          if (exchange == null) return@intercept
          // A browser already acting as a different live grant isn't switched by a link alone (it
          // could be someone else's); ask on our own page, submitted by same-origin POST.
          val conflicting = ServeAgentGrantCookie.conflictingGrant(current, store, exchange)
          if (conflicting != null) {
            respondAgentGrantSwitchConfirmation(current, conflicting, exchange)
            finish()
            return@intercept
          }
          current.response.cookies.append(
            ServeAgentGrantCookie.cookie(exchange.credential, secure = isSecure(current))
          )
          current.response.headers.append(HttpHeaders.CacheControl, "no-store")
          current.response.headers.append(HttpHeaders.Location, exchange.target)
          current.respond(HttpStatusCode.Found)
          finish()
        }
      }
      // Top-level sites ([ServeSites]): on a site host, make `/<system>/…` behave as though this
      // box served only that catalog. Registered before routing because it must answer instead of
      // the `/{system}/…` handlers.
      // - the site's own system — 301 to the rooted URL, so crawlers see one spelling;
      // - another served catalog — 404, since a site must not expose neighbours. Only served ids
      //   count, so constant routes fall through.
      // Installed when a site is configured or could be published at runtime via `/admin/sites`;
      // otherwise there is no interceptor.
      // The rooted builder host (`ServeUiBuilderHostRoot.kt`): a browser opening a `/ui-builder/…`
      // page is redirected to its rooted URL so old links converge. Navigations only; see
      // [uiBuilderRootRedirect].
      if (uiBuilderRootMode) {
        intercept(ApplicationCallPipeline.Plugins) {
          val current: ApplicationCall = context
          if (!isUiBuilderHost(current)) return@intercept
          // A rooted builder page carries the headers of its `/ui-builder/` form — the CSP above
          // all, whose `'wasm-unsafe-eval'` the editor cannot start without.
          uiBuilderRootCanonicalPath(current.request.path())?.let {
            ServePagePolicy.servesAs(current, it)
          }
          val target =
            uiBuilderRootRedirect(
              method = current.request.httpMethod.value,
              path = current.request.path(),
              query = current.request.queryString(),
              accept = current.request.headers[HttpHeaders.Accept],
            ) ?: return@intercept
          current.response.headers.append(HttpHeaders.Location, target)
          current.respond(HttpStatusCode.Found)
          finish()
        }
      }
      if (!sites.isEmpty || siteAdminEnabled) {
        intercept(ApplicationCallPipeline.Plugins) {
          val current: ApplicationCall = context
          val system = current.siteSystem() ?: return@intercept
          val path = current.request.path()
          val first = decodeSegment(path.trimStart('/').substringBefore('/'))
          if (first.isEmpty()) return@intercept
          if (first == system) {
            // `trimStart` on the remainder too: `/<system>//evil.example` would otherwise build a
            // protocol-relative open redirect. The target must have exactly one leading slash.
            val rest = path.trimStart('/').substringAfter('/', "").trimStart('/')
            val query = current.request.queryString().prefixedQuery()
            // 308, not 301: the canonical prefix carries POST routes, and most clients re-issue a
            // 301 as GET.
            current.response.headers.append(HttpHeaders.Location, "/$rest$query")
            current.respond(HttpStatusCode.PermanentRedirect)
            finish()
          } else if (!isRootedRoute(first)) {
            // The site's own styled 404, but only for callers already allowed to see this server:
            // this runs before `rejectBadToken`, and `notFoundPage` threads the token through its
            // links. Unauthorized callers get the bare 404.
            if (!current.isAuthorizedCall()) {
              current.respondText("not found", status = HttpStatusCode.NotFound)
              finish()
              return@intercept
            }
            val skin = current.siteSkin()
            current.markGeneration("static-page", current.pageCacheControl())
            current.respondText(
              ServeWeb.notFoundPage(
                "That page was not found on this site.",
                current.linkToken(),
                isPublic,
                version = SERVE_VERSION,
                siteName = skin.first,
                themeCss = skin.second,
                themeStorageKey = skin.third,
                componentBrowser = current.componentBrowserMode(),
                githubAuth =
                  githubAuth?.let { auth ->
                    ServeWeb.GitHubAuthStatus(
                      loginHref = auth.loginPath(current),
                      logoutHref = auth.logoutPath(current),
                      login = auth.currentLogin(current),
                      restrictedToAllowedUsers = auth.isRestrictedToAllowedUsers(),
                    )
                  },
              ),
              ContentType.Text.Html,
              HttpStatusCode.NotFound,
            )
            finish()
          }
        }
      }
      // Answer HEAD wherever GET is answered (every route is registered with `get`). Unfurlers,
      // link checkers, uptime monitors and `curl -I` probe with HEAD and treat 4xx as dead. The
      // plugin runs the GET pipeline and drops the body, so headers match exactly.
      install(AutoHeadResponse)
      // Sliding sessions: a session past its half-life gets a freshly signed cookie. Runs when the
      // response is ready, so it covers every response and can see the route's `Cache-Control` (a
      // `public` response gets no session cookie). No-op for young or absent sessions. See
      // [ServeGithubAuth.refreshSession].
      githubAuth?.let { auth ->
        install(
          createApplicationPlugin("github-session-refresh") {
            on(ResponseBodyReadyForSend) { call, content ->
              auth.refreshSession(call, content.headers.getAll(HttpHeaders.CacheControl).orEmpty())
            }
          }
        )
      }
      // The Content-Security-Policy on every HTML response ([ServePagePolicy]). Installed before
      // the entity-tag phase, so a page's header is staged before a `304` can short-circuit it.
      ServePagePolicy.install(this) { pagePolicyFormActions() }
      // A strong `ETag` on every cacheable HTML page, so the revalidation [ANON_PAGE_CACHE_CONTROL]
      // (`max-age=0, … must-revalidate`) and [STATIC_PAGE_CACHE_CONTROL] demand can end in `304`
      // instead of a full re-render.
      //
      // Privacy is unchanged: `Vary: Cookie` and `private`/`public` still decide who may store.
      // Skipped for `no-store`, HTML without a lifetime, and non-200 responses.
      //
      // The validator is [pageEntityTag] (the markup minus the per-visit element), one SHA-256 per
      // page. Its own phase before `ContentEncoding`, so the hash is encoding-independent and
      // [AutoHeadResponse] (in `After`) still sees the `ETag`.
      sendPipeline.insertPhaseBefore(ApplicationSendPipeline.ContentEncoding, HTML_ENTITY_TAG_PHASE)
      sendPipeline.intercept(HTML_ENTITY_TAG_PHASE) { message ->
        val html = message as? TextContent
        if (html == null || html.contentType.withoutParameters() != ContentType.Text.Html) {
          return@intercept
        }
        val status = html.status ?: call.response.status() ?: HttpStatusCode.OK
        val cacheControl = call.response.headers[HttpHeaders.CacheControl]
        if (status != HttpStatusCode.OK || cacheControl == null || "no-store" in cacheControl) {
          return@intercept
        }
        val etag = pageEntityTag(html.text)
        call.response.headers.append(HttpHeaders.ETag, etag)
        if (ifNoneMatchHits(call.request.headers[HttpHeaders.IfNoneMatch], etag)) {
          proceedWith(NotModifiedResponse)
        }
      }
      // Compress text-ish lanes only (pages, `/status.json`, figma-svg exports, baked CSS/JS). An
      // allowlist because the heavy lanes are already compressed (PNGs, `.bundle` images, multi-MB
      // Wasm) and re-encoding them would burn CPU on a box running render daemons. `minimumSize`
      // skips tiny bodies like `/healthz` and `/readyz`, which are polled frequently.
      install(Compression) {
        gzip {
          matchContentType(
            ContentType.Text.Html,
            ContentType.Text.CSS,
            ContentType.Text.Plain,
            ContentType.Text.JavaScript,
            ContentType.Application.JavaScript,
            ContentType.Application.Json,
            ContentType.Image.SVG,
          )
          minimumSize(1024)
        }
      }
      routing {
        if (designService != null && uiBuilderAuthorization != null) {
          // The REST and protocol routes below read the same credentials the form routes do, and a
          // write carried by the session cookie is accepted only from a page this server served.
          val sameOriginUiBuilderAuthorization =
            uiBuilderAuthorization.acceptingSessionWritesFromSameOrigin {
              browserHosts
            }
          installUiBuilderRoutes(
            designService,
            sameOriginUiBuilderAuthorization,
            ::canonicalServerOrigin,
            uiBuilderNativePreview,
            uiBuilderInlineCapture,
            identityDetails = ::uiBuilderIdentityDetails,
            agentPresence = uiBuilderAgentPresence,
            // The native pane's live lane on hosts with Stage-2 redemption: the compile's token is
            // redeemed into a registered session and the editor opens the same
            // `/{session}/ws/{preview}` socket as the viewer's Live toggle. Otherwise the response
            // omits the live fields.
            liveNativeSession =
              playgroundRedeem?.let { redeem ->
                { token, preview ->
                  (redeem.redeem(token, preview) as? PlaygroundRedeemService.Outcome.Live)?.let {
                    UiBuilderNativeLiveSession(sessionId = it.sessionId, previewId = it.previewId)
                  }
                }
              },
          )
          uiBuilderThumbnails?.let { thumbnails ->
            installUiBuilderThumbnailRoute(
              designService,
              sameOriginUiBuilderAuthorization,
              thumbnails,
            )
          }
          if (uiBuilderReferenceStore != null) {
            installUiBuilderReferenceRoutes(
              designService,
              sameOriginUiBuilderAuthorization,
              uiBuilderReferenceStore,
            )
          }
          if (uiBuilderCommentStore != null) {
            installUiBuilderCommentRoutes(
              designService,
              sameOriginUiBuilderAuthorization,
              uiBuilderCommentStore,
            )
          }
          if (uiBuilderLinksStore != null) {
            installUiBuilderLinksRoutes(
              designService,
              sameOriginUiBuilderAuthorization,
              uiBuilderLinksStore,
            )
          }
          if (uiBuilderReviewStore != null) {
            installUiBuilderReviewRoutes(
              designService,
              sameOriginUiBuilderAuthorization,
              uiBuilderReviewStore,
            )
          }
          val guidelinesMcp = uiBuilderMcp
          // The prompt and access routes are stateless: a host whose result store could not be
          // opened still shows the prompt; only reading, recording and running checks need it.
          if (guidelinesMcp != null) {
            installUiBuilderGuidelineRoutes(
              designService,
              sameOriginUiBuilderAuthorization,
              uiBuilderGuidelineStore,
              guidelinesMcp::guidelinesPromptFor,
              guidelinesMcp::guidelinesAccess,
              guidelinesMcp::runGuidelinesCheck,
              guidelinesMcp::guidelinesRuleSet,
              guidelinesMcp::currentDesignRevision,
            )
          }
          if (uiBuilderCatalogGuidelines != null) {
            installUiBuilderCatalogGuidelinesRoute(
              sameOriginUiBuilderAuthorization,
              uiBuilderCatalogGuidelines,
            )
          }
          if (uiBuilderProjectStore != null) {
            installUiBuilderProjectRoutes(
              ServeUiBuilderProjects(
                uiBuilderProjectStore,
                designService,
                serverOrigin = ::canonicalServerOrigin,
              ),
              sameOriginUiBuilderAuthorization,
              agentGrants?.let(ServeUiBuilderGrantScope::lookupOf),
            )
          }
          if (uiBuilderFolderStore != null) {
            installUiBuilderFolderRoutes(
              designService,
              sameOriginUiBuilderAuthorization,
              uiBuilderFolderStore,
              uiBuilderAdministrators,
              uiBuilderAdmin,
            )
          }
          if (designAssets != null) {
            installUiBuilderAssetRoutes(sameOriginUiBuilderAuthorization, designAssets)
          }
          // Whether a design's imported components still match their library. Inside this block
          // because it reads a design, needing the service and the design's own access control.
          if (uiBuilderComponentLibrary != null) {
            installUiBuilderComponentDriftRoutes(
              designService,
              sameOriginUiBuilderAuthorization,
              ServeUiBuilderComponentDrift(uiBuilderComponentLibrary),
              ::uiBuilderComponentCatalogs,
            )
          }
        }
        // The components the served projects share, behind the builder's own credential (the editor
        // holds no admin token) and outside the block above, so read-only builder hosts still have
        // a palette.
        if (uiBuilderAuthorization != null && uiBuilderComponentLibrary != null) {
          installUiBuilderComponentLibraryRoutes(
            uiBuilderAuthorization,
            uiBuilderComponentLibrary,
            uiBuilderDesignCatalogs,
            store = uiBuilderComponentStore,
            validate =
              uiBuilderValidator?.let { validator ->
                { actorId, document -> componentPublishProblems(validator, actorId, document) }
              },
          )
        }
        // What the export generates from, for the editor's code pane; outside the design-service
        // block so read-only hosts have it.
        if (uiBuilderAuthorization != null) {
          installUiBuilderCatalogRecordRoutes(uiBuilderAuthorization, uiBuilderCatalogRecord)
        }

        // `/healthz`: ungated liveness, "ok" once the listener is up. Not the rolling-update gate;
        // see `/readyz`.
        get("/healthz") { call.respondText("ok") }

        // `/readyz`: ungated readiness, "ready" only once every design system has rendered here
        // ([ready]). docker-rollout should wait on this before promoting a replica. 503 ("warming")
        // until then; latches green.
        get("/readyz") { handleReadyz() }

        // `/version`: ungated host identity (CLI version, serve API schema, public or gated), so
        // deployers and checks can confirm the live build without a token. Keeps the version out of
        // the HTML goldens.
        get("/version") {
          call.respondText(
            JSON.encodeToString(
              VersionResponse.serializer(),
              VersionResponse(version = SERVE_VERSION, public = isPublic),
            ),
            ContentType.Application.Json,
          )
        }

        githubAuth?.let { auth ->
          // Configured browser hosts are the only hostnames a sign-in started elsewhere may return
          // to. Passed in so host config stays with the server.
          get(ServeGithubAuth.START_PATH) { with(auth) { handleStart(browserHosts) } }
          get(ServeGithubAuth.CALLBACK_PATH) { with(auth) { handleCallback(browserHosts) } }
          // POST only ([ServeGithubAuth.handleLogout]): a prefetcher or unfurler following the URL
          // gets 405 rather than signing the visitor out.
          post(ServeGithubAuth.LOGOUT_PATH) {
            // Before the session cookie is cleared, while it still names who is signing out.
            push?.forgetSignedOutBrowser(call)
            with(auth) { handleLogout() }
          }
        }

        // The agent-grant lane (`--agent-grants`): an agent asks, a human approves in a browser,
        // and the agent collects a short-lived bearer. See
        // [docs/design/AGENT_ACCESS_GRANTS.md](../../../../../../../../docs/design/AGENT_ACCESS_GRANTS.md).
        //
        // `request` and `poll` are ungated (the agent has no credential yet), so both are
        // per-address rate limited and grant nothing alone: `request` parks an entry for a human,
        // and `poll` answers only to the device secret it minted. The approval routes require a
        // real operator identity and are the only place a grant is created. Constant first segments
        // outscore the `/{system}` catch-all.
        agentGrants?.let { store ->
          post(ServeAgentGrants.REQUEST_PATH) { handleAgentGrantRequest(store) }
          post(ServeAgentGrants.POLL_PATH) { handleAgentGrantPoll(store) }
          post(ServeAgentGrants.REVOKE_PATH) { handleAgentGrantRevoke(store) }
          get(ServeAgentGrants.WHOAMI_PATH) { handleAgentGrantWhoami(store) }
          post(ServeAgentGrants.SWITCH_PATH) { handleAgentGrantSwitch(store) }
          post(ServeAgentGrants.LEAVE_PATH) {
            // Same-origin only: a foreign page must not be able to sign this browser out of its
            // grant any more than into one.
            if (!ServeSameOriginRequests.isSameOrigin(call, browserHosts)) {
              call.response.headers.append(HttpHeaders.CacheControl, "no-store")
              call.respond(HttpStatusCode.Forbidden)
              return@post
            }
            call.response.cookies.append(
              ServeAgentGrantCookie.clearedCookie(secure = isSecure(call))
            )
            call.response.headers.append(HttpHeaders.CacheControl, "no-store")
            call.respond(HttpStatusCode.NoContent)
          }
          get("${ServeAgentGrants.BASE_PATH}/{requestId}") { handleAgentGrantPage(store) }
          post("${ServeAgentGrants.BASE_PATH}/{requestId}") { handleAgentGrantDecision(store) }
          post("${ServeAgentGrants.BASE_PATH}/{grantId}/revoke") {
            handleAgentGrantRevokeFromStatus(store)
          }
          // A person asking for UI-builder edit access for themselves, from their own session.
          if (githubAuth != null && designService != null) {
            get(UI_BUILDER_REQUEST_ACCESS_PATH) {
              handleUiBuilderRequestAccess(store, submit = false)
            }
            post(UI_BUILDER_REQUEST_ACCESS_PATH) {
              handleUiBuilderRequestAccess(store, submit = true)
            }
          }

          // The OAuth 2.1 façade over the same flow, for MCP clients that follow the 401 → resource
          // metadata → authorization-server metadata → dynamic registration → authorization code +
          // PKCE script. Nothing here mints anything: `/oauth/authorize` opens an ordinary grant
          // request and `/oauth/token` returns the grant the approval page created. See
          // [ServeMcpOAuth]. The metadata documents are necessarily unauthenticated and disclose
          // nothing about content.
          get(ServeMcpOAuth.PROTECTED_RESOURCE_METADATA_PATH) {
            respondProtectedResourceMetadata(store)
          }
          get(ServeMcpOAuth.PROTECTED_RESOURCE_METADATA_MCP_PATH) {
            respondProtectedResourceMetadata(store)
          }
          get(ServeMcpOAuth.AUTHORIZATION_SERVER_METADATA_PATH) {
            respondAuthorizationServerMetadata(store)
          }
          get(ServeMcpOAuth.AUTHORIZATION_SERVER_METADATA_MCP_PATH) {
            respondAuthorizationServerMetadata(store)
          }
          get(ServeMcpOAuth.OPENID_CONFIGURATION_PATH) { respondAuthorizationServerMetadata(store) }
          post(ServeMcpOAuth.REGISTER_PATH) { handleOAuthRegister() }
          get(ServeMcpOAuth.AUTHORIZE_PATH) { handleOAuthAuthorize(store) }
          post(ServeMcpOAuth.TOKEN_PATH) { handleOAuthToken(store) }
        }

        // Aggregate Streamable HTTP MCP. Callers stay independent JSON requests; a 2025 client
        // advertising form elicitation may get a bounded session id and request-scoped SSE while
        // its POST waits. No long-lived GET stream or duplicated state.
        if (catalogMcp != null) {
          post("/mcp") { handleCatalogMcp() }
          get("/mcp") { rejectCatalogMcpListen() }
          delete("/mcp") { handleCatalogMcpDelete() }
          get(ServeCatalogMcp.IMAGE_URL_PATH) { handleSignedRenderPng() }
        }

        // The UI-builder document and mutation JSON Schemas (the same bytes as the
        // `compose-preview://schemas/…` MCP resources) for non-MCP tools. Ungated like `/version`.
        if (designService != null) {
          get("${UiBuilderJsonSchemas.HTTP_PREFIX}{name}") {
            val schema =
              call.parameters["name"]?.let(UiBuilderJsonSchemas::byName)
                ?: return@get call.respond(HttpStatusCode.NotFound)
            call.response.headers.append(HttpHeaders.CacheControl, "public, max-age=3600")
            call.respondText(
              schema.text,
              ContentType.parse(UiBuilderJsonSchemas.MEDIA_TYPE),
              HttpStatusCode.OK,
            )
          }
        }

        // `/status`: the operator view of this host — published catalogs with
        // trust/liveness/errors, running daemons, effective config, recent startup failures. HTML
        // by default (`?format=json`); `/status.json` is the canonical JSON for monitors. Gated
        // like everything else, since it is more sensitive than `/version`.
        get("/status") { handleStatus(json = false) }
        get("/status.json") { handleStatus(json = true) }

        // `/report-bug`: file a bug against the server's own repo, prefilled with diagnostics.
        // Distinct from the per-preview report ([ServeBugReport] explains why). Gated like
        // `/status`, since it reports the same detail.
        get(ServeBugReport.PATH) { handleBugReport() }
        // The installed app's share target ([ServeShareTarget]), gated like the report page it
        // leads to; the installed app's browse cookie is the credential.
        post(ServeShareTarget.ACTION_PATH) { handleShareTarget() }
        get("${ServeShareTarget.SHARED_PATH}/{id}") { handleSharedImage() }

        // The crawler-facing pair ([ServeSiteIndex]), ungated even on a gated host: a private
        // server's `robots.txt` disallows everything and its sitemap is absent.
        get("/robots.txt") { handleRobotsTxt() }
        get("/sitemap.xml") { handleSitemapXml() }

        // Shared frontend assets for ServeWeb pages. These are generic CSS/JS bytes baked into the
        // CLI jar, so they are ungated like the Wasm/RC players and cacheable with an ETag.
        get("/assets/serve/{name}") { handleServeWebAsset(versioned = false) }
        get("/assets/serve/{version}/{name}") { handleServeWebAsset(versioned = true) }

        // Serve the static Wasm app for a registered system at `/wasm/<system>/<file>`, ungated
        // (generic client code) so the sandboxed iframe's relative fetches work.
        //
        // Registered unconditionally: routes are installed at bind time, before catalogs load,
        // while [wasmCatalogs] is a live view. An unknown system 404s in the handler.
        get("/wasm/{system}/{path...}") { handleWasmAsset(privateRoute = false) }
        // Auto-discovered local apps are project output, so the token goes in the path (relative
        // requests keep it) and the ordinary route is refused for that system to prevent a
        // token-free bypass.
        get("/wasm-private/{access}/{system}/{path...}") { handleWasmAsset(privateRoute = true) }

        // The builder's page routes are registered by [uiBuilderPageRoutes] under `/ui-builder` on
        // every host, and with `--ui-builder-host-root` again at the root of the builder host
        // behind [UiBuilderHostRootSelector], which yields to server routes
        // ([ServeSites.RESERVED_SYSTEMS]) and other hosts.
        uiBuilderPageRoutes("/ui-builder")
        if (uiBuilderRootMode) {
          createChild(UiBuilderHostRootSelector(::isUiBuilderHost)).uiBuilderPageRoutes("")
        }

        // The shared CMP/Wasm Remote Compose player, opt-in while operation coverage is incomplete;
        // unset ⇒ 404 and a disabled selector chip.
        get("/rc-player-wasm/{path...}") {
          val dir = rcPlayerWasmDir
          if (dir == null) {
            call.respondText("not found", status = HttpStatusCode.NotFound)
            return@get
          }
          val segments = call.parameters.getAll("path").orEmpty().filter { it.isNotEmpty() }
          val rel = if (segments.isEmpty()) "index.html" else segments.joinToString("/")
          val base = dir.toPath().toAbsolutePath().normalize()
          val resolved = base.resolve(rel).normalize()
          if (!resolved.startsWith(base) || !resolved.toFile().isFile) {
            call.respondText("not found", status = HttpStatusCode.NotFound)
            return@get
          }
          val file = resolved.toFile()
          // Opened top-level, the player shell runs under `sandbox allow-scripts`
          // ([ServePagePolicy.sandboxFor]), an opaque origin whose module and Wasm requests need
          // CORS (as `/wasm/`).
          call.response.headers.append(HttpHeaders.AccessControlAllowOrigin, "*")
          val etag = "\"${file.length().toString(16)}-${file.lastModified().toString(16)}\""
          // Filenames are stable across releases, so revalidate every use to avoid running a stale
          // decoder; unchanged assets still get a cheap ETag/304.
          call.response.headers.append(HttpHeaders.CacheControl, "no-cache")
          call.response.headers.append(HttpHeaders.ETag, etag)
          if (call.request.headers[HttpHeaders.IfNoneMatch] == etag) {
            call.respond(HttpStatusCode.NotModified)
            return@get
          }
          val bytes = withContext(Dispatchers.IO) { file.readBytes() }
          call.respondBytes(bytes, wasmContentType(file.name))
        }

        // Prebaked front-door hero thumbnails ([ServeHeroImages]). Not the `/render` lane: bytes
        // are resident, so no session lease, render permit or daemon wake. The name is the content
        // hash, so `immutable`. A constant first segment outscores the `/{system}` catch-all.
        get("/hero/{system}/{name}") { handleHeroImage() }

        // The link-unfurl card a page advertises as `og:image` ([ServeSocialCard]), content-hashed
        // and `immutable` like `/hero/` — important because unfurlers key caches by URL and some
        // never revalidate.
        get("/social/{name}") { handleSocialCard() }

        // The site icon in its three web forms ([ServeSiteIcon]), shown by unfurlers beside the
        // card. Ungated: icons carry no session data and favicon fetches never carry the token.
        get(ServeSiteIcon.SVG_PATH) { respondSiteIcon(ServeSiteIcon.svg) }
        get(ServeSiteIcon.ICO_PATH) { respondSiteIcon(ServeSiteIcon.ico) }
        get(ServeSiteIcon.APPLE_TOUCH_PATH) { respondSiteIcon(ServeSiteIcon.appleTouchIcon) }
        get(ServeSiteIcon.APP_ICON_192_PATH) { respondSiteIcon(ServeSiteIcon.appIcon192) }
        get(ServeSiteIcon.APP_ICON_512_PATH) { respondSiteIcon(ServeSiteIcon.appIcon512) }
        get(ServeSiteIcon.MASKABLE_ICON_PATH) { respondSiteIcon(ServeSiteIcon.maskableIcon) }
        // The push notification's status-bar badge, also the manifest's `monochrome` icon.
        get(ServeSiteIcon.BADGE_PATH) { respondSiteIcon(ServeSiteIcon.badgeIcon) }
        // The web app manifest ([ServeSiteIcon.manifest]), ungated like the icons. Per host, so a
        // top-level site installs as its own app with its catalog's name and colour.
        get(ServeSiteIcon.MANIFEST_PATH) { respondSiteIcon(manifestFor(call)) }
        // The push service worker: root-scoped, push and notificationclick only
        // (`serve-web/src/push/pushWorker.ts`). Ungated because the browser fetches worker updates
        // without credentials, and served even with push off so existing subscribers update.
        get(PUSH_SERVICE_WORKER_PATH) { respondPushServiceWorker() }
        push?.let { lane -> installPushRoutes(lane) { browserHosts } }
        // The manifest's install-dialog screenshots: committed captures, ungated like the icons.
        get(ServeSiteIcon.SCREENSHOT_NARROW_PATH) { respondScreenshot() }
        get(ServeSiteIcon.SCREENSHOT_WIDE_PATH) { respondScreenshot() }

        // The in-browser Remote Compose player: a shared IIFE bundle (global `RC`) baked into the
        // CLI jar, for the viewer's `<canvas>` lane (`/render/<id>.rc` → `RC.RcdPlayer`). Always
        // available, ungated and CORS-open like the Wasm assets. A constant first segment outscores
        // the `/{system}` catch-all.
        get("/rc-player/bundle.js") { respondPlayerAsset(playerAsset(RC_PLAYER_RESOURCE)) }

        // The typefaces the player draws generic families in ([ServeRcFonts]): the generated
        // `@font-face` stylesheet and vendored faces, so the browser lane matches the baked PNG.
        // Ungated and CORS-open like the player bundle.
        get("${ServeRcFonts.URL_BASE}/{name}") { handleRcFont() }
        // A Google Fonts family at one weight for a UI-builder design whose typeface the editor
        // doesn't vendor ([ServeGoogleFonts]). Ungated and CORS-open: public font bytes, requested
        // by sandboxed credential-less frames.
        get("${ServeGoogleFonts.ROUTE}/{family}/{weight}") { handleGoogleFont() }
        // A Noto slice Compose's web text fallback asks for when no loaded font has a glyph
        // ([ServeNotoFallbackFonts]); gstatic itself is outside the page's `connect-src`.
        get("${ServeNotoFallbackFonts.ROUTE}/{path...}") { handleNotoFallbackFont() }

        // The document lane (`--accept-docs`): ingest a known format (Remote Compose or Lottie,
        // [ServeDocFormats]) and return an expiring permalink that plays it. Opt-in; constant first
        // segments.
        // The image lane (`--accept-images`): ingest a preview PNG from an authenticated GitHub
        // collaborator and serve it at an embeddable URL, so agents can put before/after pixels in
        // a PR body. Two routes and no browse page, since an unguessable-link store must not list
        // uploads. Opt-in.
        if (imageStore != null && imageUploadAuth != null) {
          get("/images/capability") { handleImageUploadCapability(imageUploadAuth) }
          post("/images") { handleImageUpload(imageStore, imageUploadAuth) }
          get("/i/{id}") { handleImage(imageStore) }
        }

        docStore?.let { store ->
          get("/docs") { handleDocUploadPage(store) }
          post("/docs") { handleDocUpload(store) }
          get("/d/{id}") { handleDocPage(store) }
          get("/d/{id}/raw") { handleDocRaw(store) }
          get("/d/{id}/render.png") { handleDocRender(store) }
          // Each format's vendored browser player, looked up in the registry. Ungated + CORS-open
          // like `/rc-player/bundle.js`.
          get("/doc-player/{format}/bundle.js") { handleDocPlayer() }
        }

        // The playground lane (`--playground-bundle`): compile a snippet against a catalog
        // classpath, returning diagnostics + an expiring preview token. The `{version}` segment
        // (e.g. `/api/1/compiler/run`) is captured and ignored. Never registered under `--public`.
        if (playgroundService != null) {
          val svc = playgroundService
          post("/api/{version}/compiler/run") { handlePlaygroundRun(svc) }
          if (svc.editLeasesEnabled) {
            post("/api/{version}/compiler/edit-lease") { handlePlaygroundEditLeaseAcquire(svc) }
            post("/api/{version}/compiler/edit-lease/release") {
              handlePlaygroundEditLeaseRelease(svc)
            }
          }
          // The catalog selector's list, fetched by the editor on load because catalogs load in the
          // background after startup.
          get("/api/{version}/compiler/catalogs") { handlePlaygroundCatalogs(svc) }
          // The Stage-1 editor page (`GET /playground`), mounted only with the lane and never under
          // `--public`.
          get("/playground") { handlePlaygroundPage(svc) }
        } else {
          // Reserve `/playground` even without the compile lane, so it isn't routed through the
          // `/{system}` catch-all as a missing design system.
          get("/playground") { handlePlaygroundDisabledPage() }
        }

        // Stage-2 redemption (`GET /pg/<token>`): redeem a preview token into a live session and
        // redirect to its viewer.
        // The segment is `{pgToken}`, not `{token}`: `call.parameters` merges path and query, so
        // `{token}` would collide with the `?token=` access token.
        // Only beside the public surface; a `--compile-engine` host's redeem service is for the
        // native pane, which redeems in process.
        if (playgroundService != null) {
          playgroundRedeem?.let { redeem ->
            get("/pg/{pgToken}") { handlePlaygroundRedeem(redeem) }
          }
        }

        // Shared/public mode ingestion: upload a pre-rendered bundle (zip body, or `?url=` to a
        // build artifact) and get a `?session=` link. Only registered when a bundle store is
        // supplied.
        bundleStore?.let { store ->
          post("/bundles/{name}") {
            if (rejectBadTokenForIngest()) return@post
            val name = call.parameters["name"]
            if (name.isNullOrBlank()) {
              call.respondText("missing bundle name", status = HttpStatusCode.BadRequest)
              return@post
            }
            val url = call.request.queryParameters["url"]
            // Cap the uploaded body as it streams in — receiving it whole into memory first would
            // let a client OOM the server regardless of the store's later extraction cap.
            val body =
              if (url == null) {
                withContext(Dispatchers.IO) {
                  call.receiveStream().use { readCapped(it, MAX_UPLOAD_BYTES) }
                }
                  ?: run {
                    call.respondText(
                      "bundle exceeds ${MAX_UPLOAD_BYTES / (1024 * 1024)}MB",
                      status = HttpStatusCode.PayloadTooLarge,
                    )
                    return@post
                  }
              } else {
                null
              }
            val result =
              withContext(Dispatchers.IO) {
                // isSecurityChecked = true: the route is token-gated (rejectBadToken above) and the
                // store defends in depth (name sanitisation, zip-slip, size cap, SSRF host
                // allowlist).
                if (url != null) store.addFromUrl(name, url, isSecurityChecked = true)
                else store.add(name, body!!, isSecurityChecked = true)
              }
            when (result) {
              is ServeBundleStore.Result.Ok ->
                call.respondText(
                  JSON.encodeToString(
                    BundleAcceptedResponse.serializer(),
                    BundleAcceptedResponse(
                      session = result.name,
                      previews = result.previewCount,
                      path = "/?session=${result.name}",
                      trust = result.trust,
                    ),
                  ),
                  ContentType.Application.Json,
                  HttpStatusCode.Created,
                )
              is ServeBundleStore.Result.Failed ->
                call.respondText(result.reason, status = HttpStatusCode.BadRequest)
            }
          }
        }

        // Runtime catalog administration: `POST /admin/catalogs` publishes, `DELETE
        // /admin/catalogs/{system}` retires, `GET /admin/catalogs` lists. Mutations are written
        // back to `catalogs.json`. Registered only with both an admin implementation and
        // `--admin-token`.
        if (themeOptimizerAdmin != null && !adminToken.isNullOrBlank()) {
          val optimizer = themeOptimizerAdmin
          // Pause the optimizer without a restart (which would discard warm daemons and redo
          // catalog loads). `minutes` is bounded.
          post("/admin/theme-optimization/pause") {
            if (rejectBadAdminToken()) return@post
            val minutesText = call.request.queryParameters["minutes"]
            val minutes =
              if (minutesText == null) DEFAULT_OPTIMIZER_PAUSE_MINUTES
              else
                minutesText.toLongOrNull()
                  ?: run {
                    call.respondText(
                      "minutes must be an integer in 1..$MAX_OPTIMIZER_PAUSE_MINUTES",
                      status = HttpStatusCode.BadRequest,
                    )
                    return@post
                  }
            if (minutes <= 0 || minutes > MAX_OPTIMIZER_PAUSE_MINUTES) {
              call.respondText(
                "minutes must be 1..$MAX_OPTIMIZER_PAUSE_MINUTES",
                status = HttpStatusCode.BadRequest,
              )
              return@post
            }
            val reason = call.request.queryParameters["reason"].orEmpty().ifBlank { "admin" }
            val until = optimizer.pauseOptimizers(minutes * 60_000L, reason)
            call.respondText(
              Json.encodeToString(
                OptimizerPauseDto.serializer(),
                OptimizerPauseDto(paused = true, pausedUntilEpochMillis = until, reason = reason),
              ),
              ContentType.Application.Json,
            )
          }
          // Per-catalog cache control. `regenerate` marks warmed renders for re-render and deletes
          // nothing, so previews keep serving while the background pass replaces them (for pixels
          // suspected wrong, e.g. after a font change in the base image). `drop` deletes them,
          // making every preview cold.
          post("/admin/catalogs/{system}/theme-cache/regenerate") {
            if (rejectBadAdminToken()) return@post
            val system = call.parameters["system"].orEmpty()
            // Use the retained state, not the live host: `peekHost` is null for suspended sessions
            // (most catalogs), and the cache lives on the state, so no daemon is needed or woken.
            val cache = sessions.peekState(system)?.catalogThemeCache
            if (cache == null) {
              call.respondText("no such catalog: $system", status = HttpStatusCode.NotFound)
              return@post
            }
            val queued = withContext(Dispatchers.IO) { cache.markPersistedDirty() }
            // Wake the pass that works the queue: a converged catalog's optimizer has exited and
            // its host is usually suspended, so marking alone would leave nobody working.
            // Best-effort; the mark persists for the resume rotation.
            if (queued > 0) withContext(Dispatchers.IO) { sessions.wakeOptimizer(system) }
            call.response.headers.append(HttpHeaders.CacheControl, "no-store")
            if (queued < 0) {
              // Two refusals that must not read as queued: no targets (theme optimization off) or
              // the mark couldn't be persisted (full or read-only volume).
              call.respondText(
                Json.encodeToString(
                  ThemeCacheActionDto.serializer(),
                  ThemeCacheActionDto(
                    system = system,
                    action = "regenerate",
                    entries = 0,
                    queued = false,
                  ),
                ),
                ContentType.Application.Json,
                status = HttpStatusCode.Conflict,
              )
              return@post
            }
            call.respondText(
              Json.encodeToString(
                ThemeCacheActionDto.serializer(),
                ThemeCacheActionDto(
                  system = system,
                  action = "regenerate",
                  entries = queued,
                  queued = true,
                ),
              ),
              ContentType.Application.Json,
            )
          }
          post("/admin/catalogs/{system}/theme-cache/drop") {
            if (rejectBadAdminToken()) return@post
            val system = call.parameters["system"].orEmpty()
            // The state, for the reason the regenerate route above gives.
            val cache = sessions.peekState(system)?.catalogThemeCache
            if (cache == null) {
              call.respondText("no such catalog: $system", status = HttpStatusCode.NotFound)
              return@post
            }
            val dropped = withContext(Dispatchers.IO) { cache.dropPersisted() }
            // Wake the pass as regenerate does, since a drop leaves the catalog cold. But only when
            // there is a pass: with `-Dcomposeai.serve.themeOptimization=false` there are no
            // targets (`markPersistedDirty` answers -1), and waking would cold-start a daemon and
            // take a live seat for nothing. The drop itself still succeeds.
            if (dropped && cache.hasOptimizationTargets) {
              withContext(Dispatchers.IO) { sessions.wakeOptimizer(system) }
            }
            call.response.headers.append(HttpHeaders.CacheControl, "no-store")
            call.respondText(
              Json.encodeToString(
                ThemeCacheActionDto.serializer(),
                // `dropped = false` means a render holds the generation write lock right now; the
                // caller should retry.
                ThemeCacheActionDto(
                  system = system,
                  action = "drop",
                  entries = 0,
                  dropped = dropped,
                ),
              ),
              ContentType.Application.Json,
              status = if (dropped) HttpStatusCode.OK else HttpStatusCode.Conflict,
            )
          }
          post("/admin/theme-optimization/resume") {
            if (rejectBadAdminToken()) return@post
            optimizer.resumeOptimizers()
            call.respondText(
              Json.encodeToString(
                OptimizerPauseDto.serializer(),
                OptimizerPauseDto(paused = false, pausedUntilEpochMillis = null, reason = null),
              ),
              ContentType.Application.Json,
            )
          }
        }

        // Discard the catalog blob cache. Whole-pool: blobs are content-named and shared between
        // systems, so none has an owning catalog ([CatalogBlobPool.clear]). Everything is
        // re-fetchable. Responds with the post-clear pool state in the `/status.json` shape.
        if (catalogCacheAdminEnabled) {
          delete("/admin/catalog-cache") {
            if (rejectBadAdminToken()) return@delete
            val after = withContext(Dispatchers.IO) { catalogCacheClear!!.invoke() }
            call.response.headers.append(HttpHeaders.CacheControl, "no-store")
            call.respondText(
              // The configured `Json` (with `encodeDefaults`), same as `/status.json`; the bare
              // companion would drop fields like `persistenceConfigured` whose default is the
              // alarming value.
              JSON.encodeToString(CatalogBlobPoolSnapshot.serializer(), after),
              ContentType.Application.Json,
            )
          }
        }
        if (adminEnabled) {
          val admin = catalogAdmin!!
          get("/admin/catalogs") {
            if (rejectBadAdminToken()) return@get
            respondAdminCatalogs(admin)
          }
          post("/admin/catalogs") {
            if (rejectBadAdminToken()) return@post
            handleAdminRegister(admin)
          }
          delete("/admin/catalogs/{system}") {
            if (rejectBadAdminToken()) return@delete
            val system = call.parameters["system"].orEmpty()
            val result = withContext(Dispatchers.IO) { admin.unregister(system) }
            respondAdminResult(result)
          }
          // Front-page sections at runtime. Defining a section also re-resolves the claims of
          // already-registered catalogs.
          get("/admin/groups") {
            if (rejectBadAdminToken()) return@get
            respondAdminGroups(admin)
          }
          post("/admin/groups") {
            if (rejectBadAdminToken()) return@post
            handleAdminGroupUpsert(admin)
          }
          delete("/admin/groups/{id}") {
            if (rejectBadAdminToken()) return@delete
            val id = call.parameters["id"].orEmpty()
            respondAdminResult(withContext(Dispatchers.IO) { admin.removeGroup(id) })
          }
        }

        // One-step onboarding: `POST /admin/onboard` with a GitHub URL publishes every
        // `design-artifacts/` branch the repository delivers. Equivalent to `POST /admin/catalogs`
        // per catalog, without needing to know each id.
        if (onboardingEnabled) {
          post("/admin/onboard") {
            if (rejectBadAdminToken()) return@post
            handleAdminOnboard(onboarding!!)
          }
        }

        // Onboarding a project that publishes nothing yet: report the Compose previews in a
        // repository from a shallow clone. Building happens on a runner in the import staging
        // repository, arriving as a `design-artifacts/` branch via the route above.
        if (sourceOnboardingEnabled) {
          post("/admin/onboard/scan") {
            if (rejectBadAdminToken()) return@post
            handleAdminOnboardScan(sourceOnboarding!!)
          }
        }

        // Runtime site administration, so a hostname can reach a running box without editing `.env`
        // and recreating the container. Registered separately from the catalog routes.
        if (siteAdminEnabled) {
          val admin = siteAdmin!!
          get("/admin/sites") {
            if (rejectBadAdminToken()) return@get
            respondAdminSites(admin)
          }
          post("/admin/sites") {
            if (rejectBadAdminToken()) return@post
            handleAdminSiteAdd(admin)
          }
          delete("/admin/sites/{host}") {
            if (rejectBadAdminToken()) return@delete
            val host = call.parameters["host"].orEmpty()
            respondAdminSiteResult(withContext(Dispatchers.IO) { admin.remove(host) })
          }
        }

        // The instance's editor pin. A PUT fetches and verifies the archive before writing the pin,
        // so the reply can be slow; the pin applies at the next start.
        if (editorAdminEnabled) {
          val admin = editorAdmin!!
          get("/admin/editor") {
            if (rejectBadAdminToken(allowReadToken = true)) return@get
            respondAdminEditor(admin)
          }
          put("/admin/editor") {
            if (rejectBadAdminToken()) return@put
            handleAdminEditorSet(admin)
          }
          delete("/admin/editor") {
            if (rejectBadAdminToken()) return@delete
            respondAdminEditorResult(withContext(Dispatchers.IO) { admin.clear() })
          }
        }

        // The UI builder's catalog settings (catalogs.json `uiBuilder`), applied at the next start.
        // Operator token only: unlike the design list, this decides what the whole host offers.
        if (uiBuilderSettingsAdminEnabled) {
          val admin = uiBuilderSettingsAdmin!!
          get("/admin/ui-builder/config") {
            if (rejectBadAdminToken(allowReadToken = true)) return@get
            respondAdminUiBuilderSettings(admin)
          }
          put("/admin/ui-builder/config") {
            if (rejectBadAdminToken()) return@put
            handleAdminUiBuilderSettingsSet(admin)
          }
          delete("/admin/ui-builder/config") {
            if (rejectBadAdminToken()) return@delete
            respondAdminUiBuilderSettingsResult(withContext(Dispatchers.IO) { admin.clear() })
          }
        }

        // The deployment's settings.json: live settings apply on PUT, the rest at the next start.
        // Operator token to change, read token to look: every value here is non-secret.
        if (settingsAdminEnabled) {
          val admin = settingsAdmin!!
          get("/admin/settings") {
            if (rejectBadAdminToken(allowReadToken = true)) return@get
            respondAdminSettings(admin)
          }
          put("/admin/settings") {
            if (rejectBadAdminToken()) return@put
            handleAdminSettingsSet(admin)
          }
          delete("/admin/settings") {
            if (rejectBadAdminToken()) return@delete
            respondAdminSettingsResult(withContext(Dispatchers.IO) { admin.clear() })
          }
        }

        // Runtime UI-builder administration: list every design on this host and delete one.
        // `/admin/ui-builder` is the screen; the JSON routes under `/admin/ui-builder/designs`
        // drive it. Fail-closed like the other admin surfaces; actor administrators are scoped to
        // this surface only.
        if (uiBuilderAdminEnabled) {
          val admin = uiBuilderAdmin!!
          get("/admin/ui-builder") {
            val access =
              uiBuilderAdminAccess(allowReadToken = true, allowPageQueryToken = true) ?: return@get
            // Only a token that opened the page is handed to its script; a header-authenticated
            // or signed-in caller gets a page that relies on its own credentials.
            val pageToken =
              call.request.queryParameters["token"]?.takeIf {
                !access.browserSession && call.request.headers[ADMIN_TOKEN_HEADER] == null
              }
            call.response.headers.append(HttpHeaders.CacheControl, "no-store")
            call.respondText(
              ServeWeb.uiBuilderAdminPage(
                adminToken = pageToken,
                readOnly = access.readOnly,
                version = SERVE_VERSION,
              ),
              ContentType.Text.Html,
            )
          }
          get("/admin/ui-builder/designs") {
            if (uiBuilderAdminAccess(allowReadToken = true) == null) return@get
            respondAdminUiBuilderDesigns(admin)
          }
          // Copy a design out — the one route meant to work on a design the host cannot serve,
          // since an operator needs its document to repair it.
          get("/admin/ui-builder/designs/{designId}/document") {
            if (uiBuilderAdminAccess() == null) return@get
            respondAdminUiBuilderDocument(admin, call.parameters["designId"].orEmpty())
          }
          // The return leg of the download above: a repaired document goes back in place of the
          // one the host cannot serve, and the design is served again without a restart.
          put("/admin/ui-builder/designs/{designId}/document") {
            val access = uiBuilderAdminAccess() ?: return@put
            if (rejectCrossOriginUiBuilderAdminMutation(access)) return@put
            val designId = call.parameters["designId"].orEmpty()
            val body = call.receiveText()
            respondAdminUiBuilderResult(
              withContext(Dispatchers.IO) { admin.repair(designId, body) }
            )
          }
          delete("/admin/ui-builder/designs/{designId}") {
            val access = uiBuilderAdminAccess() ?: return@delete
            if (rejectCrossOriginUiBuilderAdminMutation(access)) return@delete
            val designId = call.parameters["designId"].orEmpty()
            respondAdminUiBuilderResult(withContext(Dispatchers.IO) { admin.delete(designId) })
          }
          // Remove one person from every design's access list and anonymise what they said on the
          // comment boards. Revision history keeps the id; see [ServeUiBuilderAdmin.eraseActor].
          delete("/admin/ui-builder/actors/{actorId}") {
            val access = uiBuilderAdminAccess() ?: return@delete
            if (rejectCrossOriginUiBuilderAdminMutation(access)) return@delete
            val erased =
              withContext(Dispatchers.IO) { admin.eraseActor(call.parameters["actorId"].orEmpty()) }
            if (erased == null) {
              call.respondText("an actor id is required", status = HttpStatusCode.BadRequest)
              return@delete
            }
            call.respondText(
              JSON.encodeToString(
                AdminUiBuilderActorErasureResult.serializer(),
                AdminUiBuilderActorErasureResult(
                  actorId = erased.actorId,
                  revokedFrom = erased.revokedFrom,
                  ownedDesigns = erased.ownedDesigns,
                  commentBoards = erased.commentBoards,
                  replacedWith = ERASED_ACTOR_ID,
                ),
              ),
              ContentType.Application.Json,
            )
          }
        }

        // The designs catalog projects publish. Opening one is an ordinary create against the
        // editor's service, so the same admin token gates both.
        if (uiBuilderDesignLibraryEnabled) {
          val library = uiBuilderDesignLibrary!!
          get("/admin/ui-builder/library") {
            if (rejectBadAdminToken()) return@get
            respondAdminUiBuilderLibrary(library)
          }
          post("/admin/ui-builder/library/{system}/{designId}") {
            if (rejectBadAdminToken()) return@post
            respondAdminUiBuilderLibraryOpen(
              library = library,
              system = call.parameters["system"].orEmpty(),
              designId = call.parameters["designId"].orEmpty(),
            )
          }
        }

        // The components those projects share. Listing only; importing one is a design write done
        // by the editor.
        if (uiBuilderComponentLibraryEnabled) {
          val components = uiBuilderComponentLibrary!!
          get("/admin/ui-builder/component-library") {
            if (rejectBadAdminToken()) return@get
            respondAdminUiBuilderComponentLibrary(components)
          }
          get("/admin/ui-builder/component-library/{system}/{componentId}") {
            if (rejectBadAdminToken()) return@get
            respondAdminUiBuilderComponentSymbol(
              library = components,
              system = call.parameters["system"].orEmpty(),
              componentId = call.parameters["componentId"].orEmpty(),
            )
          }
        }

        // Runtime producer-trust administration, on the same volume as catalogs.json, so a catalog
        // published via `POST /admin/catalogs` can be trusted without an image rebuild. Removal
        // uses the same entry shape, with selector fields (`repo`, `keyId`, `identity`) as query
        // parameters so `<owner>/<repo>` needn't be path-escaped.
        if (trustAdminEnabled) {
          val admin = trustAdmin!!
          get("/admin/trust") {
            if (rejectBadAdminToken()) return@get
            respondAdminTrust(admin)
          }
          post("/admin/trust") {
            if (rejectBadAdminToken()) return@post
            handleAdminTrustAdd(admin)
          }
          delete("/admin/trust") {
            if (rejectBadAdminToken()) return@delete
            val q = call.request.queryParameters
            val entry =
              AdminTrustEntry(
                kind = q["kind"] ?: "branch",
                repo = q["repo"],
                branch = q["branch"],
                keyId = q["keyId"],
                identity = q["identity"],
              )
            respondAdminTrustResult(withContext(Dispatchers.IO) { admin.remove(entry) })
          }
        }

        // A persistent frame lane: the browser receives JSON frames ([ServeStreamProtocol]) and
        // sends override / switch / input messages. The token is checked post-handshake (a 404 is
        // impossible after upgrade), closing immediately if bad. Two routes share one handler:
        // `?session=` and `/{system}/ws/{name}`.
        webSocket("/ws/{name}") { serveStreamLane() }
        webSocket("/{system}/ws/{name}") { serveStreamLane() }

        // Session-selecting routes come in two forms sharing a handler: `?session=` (back-compat)
        // and `/{system}/…` (canonical; the segment is the session), chosen by `sessionInPath`.
        // Constant first segments outscore `/{system}` in Ktor routing, so only unknown single
        // segments fall through to a session lookup.
        get("/") {
          if (isUiBuilderRootCall(call)) {
            // The editor's home IS this host's root page.
            handleUiBuilderAsset()
          } else if (isUiBuilderHost(call)) {
            call.respondRedirect("/ui-builder/" + call.request.queryString().prefixedQuery())
          } else {
            handleLanding(sessionInPath = false)
          }
        }
        if (uiBuilderHost != null && uiBuilderStartUrl != null) {
          get("/start") {
            if (isUiBuilderHost(call)) call.respondRedirect(uiBuilderStartUrl)
            else handleLanding(sessionInPath = true)
          }
        }
        get("/{system}") { handleLanding(sessionInPath = true) }
        get("/{system}/") { handleLanding(sessionInPath = true) }
        get("/compare") { handleFormatComparison(sessionInPath = false) }
        get("/{system}/compare") { handleFormatComparison(sessionInPath = true) }
        get("/compare/{name}") { handleReferenceComparison(sessionInPath = false) }
        get("/{system}/compare/{name}") { handleReferenceComparison(sessionInPath = true) }
        get("/reference/{name}") { handleDesignReferenceAsset(sessionInPath = false) }
        get("/{system}/reference/{name}") { handleDesignReferenceAsset(sessionInPath = true) }

        // The published element tag index ([ServeTagIndex]), one per render like `/reference` and
        // `/pages`.
        get("/tags/{name}") { handleTagIndex(sessionInPath = false) }
        get("/{system}/tags/{name}") { handleTagIndex(sessionInPath = true) }

        // Portable spatial scenes. Textures deliberately share the scene's same-origin route;
        // uploaded bundles never get to inject scripts or point the WebXR viewer at local files.
        get("/spatial/{name}/{path...}") { handleSpatialAsset(sessionInPath = false) }
        get("/{system}/spatial/{name}/{path...}") { handleSpatialAsset(sessionInPath = true) }

        // Design pages ([ServeDesignPages]). One route per level: `{name}` ending in `.png` is the
        // backdrop, `.svg` and `.json` (the node → code join) are data, anything else is the view —
        // the `/reference/{name}` suffix convention. Both ids reserve the suffixes
        // ([ServeDesignPageStore.isDrawable]).
        get("/pages") { handleDesignPageIndex(sessionInPath = false) }
        get("/{system}/pages") { handleDesignPageIndex(sessionInPath = true) }
        get("/pages.json") { handleDesignPageIndex(sessionInPath = false, json = true) }
        get("/{system}/pages.json") { handleDesignPageIndex(sessionInPath = true, json = true) }
        // Shared backplates for design pages, registered before the single-segment page routes and
        // two segments deep so `/pages/assets/<hash>` can't be read as a page named `assets`.
        // Content-hash ids, so `immutable`; served through the store, never a manifest path
        // ([handleDesignPageAsset]).
        get("/pages/assets/{id}") { handleDesignPageAsset(sessionInPath = false) }
        get("/{system}/pages/assets/{id}") { handleDesignPageAsset(sessionInPath = true) }
        get("/pages/{name}") { handleDesignPage(sessionInPath = false) }
        get("/{system}/pages/{name}") { handleDesignPage(sessionInPath = true) }

        // The published Remote Compose player renders + build-time diffs the compare page replays
        // (see [ServeRcCompare]). Content-addressed by lane and slot, never by a published id.
        get("/rc-compare/{lane}/{name}") { handleRcCompareAsset(sessionInPath = false) }
        get("/{system}/rc-compare/{lane}/{name}") { handleRcCompareAsset(sessionInPath = true) }

        // The design-parity dashboard. `?format=json` mirrors the `/status` convention; `.json` is
        // the canonical machine path a CI check polls.
        get("/parity") { handleParity(sessionInPath = false, json = false) }
        get("/{system}/parity") { handleParity(sessionInPath = true, json = false) }
        get("/parity.json") { handleParity(sessionInPath = false, json = true) }
        get("/{system}/parity.json") { handleParity(sessionInPath = true, json = true) }

        // The committed known differences ([ServeKnownDifferences]). The document as raw text (so
        // `document-unreadable` and the byte cap stay with its consumer) and each artifact as bytes
        // (so the browser decodes the same PNG the offline run does).
        get("/parity/known-differences.json") { handleKnownDifferences(sessionInPath = false) }
        get("/{system}/parity/known-differences.json") {
          handleKnownDifferences(sessionInPath = true)
        }
        get("/parity/known-differences/{path...}") {
          handleKnownDifferenceArtifact(sessionInPath = false)
        }
        get("/{system}/parity/known-differences/{path...}") {
          handleKnownDifferenceArtifact(sessionInPath = true)
        }

        post("/refresh") { handleCatalogRefresh(sessionInPath = false) }
        post("/{system}/refresh") { handleCatalogRefresh(sessionInPath = true) }

        if (catalogFeed != null) {
          get("/feed.xml") { handleCatalogFeed(sessionInPath = false) }
          get("/{system}/feed.xml") { handleCatalogFeed(sessionInPath = true) }
        }

        get("/api/previews") { handleApiPreviews(sessionInPath = false) }
        get("/{system}/api/previews") { handleApiPreviews(sessionInPath = true) }
        get("/api/uses") { handleUsesSearch(sessionInPath = false) }
        get("/{system}/api/uses") { handleUsesSearch(sessionInPath = true) }
        // Which of a preview's published revisions actually differ. Fetched lazily by
        // `<cp-revision-runs>` when the revision menu is opened, never during page render.
        get("/api/render-runs/{name}") { handleRenderRuns(sessionInPath = false) }
        get("/{system}/api/render-runs/{name}") { handleRenderRuns(sessionInPath = true) }
        get("/api/components") { handleGlobalComponents() }
        // Outlines for the icons a client is about to draw, rather than the whole font (~45 KB vs
        // 4.8 MB). See docs/design/UI_BUILDER_MATERIAL_SYMBOLS.md.
        get("/api/icons/{style}/names") { handleIconNames() }
        get("/api/icons/{style}") { handleIconOutlines() }
        get("/api/daemons") { handleDaemonStatus(sessionInPath = false) }
        get("/{system}/api/daemons") { handleDaemonStatus(sessionInPath = true) }
        post("/api/presence") { handlePresence(sessionInPath = false) }
        post("/{system}/api/presence") { handlePresence(sessionInPath = true) }
        post("/api/theme-render-lease") { handleThemeRenderLease(sessionInPath = false) }
        post("/{system}/api/theme-render-lease") { handleThemeRenderLease(sessionInPath = true) }
        post("/api/theme-render-lease/release") { handleThemeRenderLeaseRelease() }
        post("/{system}/api/theme-render-lease/release") { handleThemeRenderLeaseRelease() }

        // Storybook-compatibility surface ([StorybookCompat]): `/index.json` is the stories index
        // visual tools crawl; `iframe.html?id=<storyId>` renders one story in isolation. Both in
        // `?session=` and `/{system}/…` forms.
        get("/index.json") { handleStorybookIndex(sessionInPath = false) }
        get("/{system}/index.json") { handleStorybookIndex(sessionInPath = true) }

        get("/iframe.html") { handleStorybookIframe(sessionInPath = false) }
        get("/{system}/iframe.html") { handleStorybookIframe(sessionInPath = true) }

        get("/bundle.zip") { handleBundleZip(sessionInPath = false) }
        get("/{system}/bundle.zip") { handleBundleZip(sessionInPath = true) }
        get("/bundle/{name}") { handleExecutableBundle(sessionInPath = false) }
        get("/{system}/bundle/{name}") { handleExecutableBundle(sessionInPath = true) }

        get("/p/{name}") { handleViewer(sessionInPath = false) }
        get("/{system}/p/{name}") { handleViewer(sessionInPath = true) }
        // The cross-catalog layer diff for one render: what this catalog and its `compareWith`
        // sibling resolved for the same cell. Its own page because it compares catalogs;
        // `?format=json` lets CI gate on it.
        get("/parallel/{name}") { handleParallelLayers(sessionInPath = false) }
        get("/{system}/parallel/{name}") { handleParallelLayers(sessionInPath = true) }

        get("/usage/{name}") { handleUsage(sessionInPath = false) }
        get("/{system}/usage/{name}") { handleUsage(sessionInPath = true) }

        get("/render/{name}") { handleRender(sessionInPath = false) }
        get("/{system}/render/{name}") { handleRender(sessionInPath = true) }
        // The same render with parameters in the body, for knob values too large for a URL (an A2UI
        // document). [handleRenderPost] merges them and hands off to [handleRender], so every gate
        // is the GET's.
        post("/render/{name}") { handleRenderPost(sessionInPath = false) }
        post("/{system}/render/{name}") { handleRenderPost(sessionInPath = true) }
        // The A2UI playground: a textarea bound to the catalog's `document` string knob, POSTed to
        // the route above. 404 on a catalog that declares no such preview.
        get("/{system}/a2ui") { handleA2uiPlayground(sessionInPath = true) }
        // The root-mounted form, for a viewer at `/p/{name}` whose playground link has no system
        // segment.
        get("/a2ui") { handleA2uiPlayground(sessionInPath = false) }

        // The motion lane, beside `/render` rather than inside it: a capture is a separate
        // artifact, and folding it in would let a fetched path decide the content type.
        // The motion browser, a constant first segment like `/pages`; Ktor scores `/motion` and
        // `/motion/{name}` separately.
        get("/motion") { handleMotionIndex(sessionInPath = false) }
        get("/{system}/motion") { handleMotionIndex(sessionInPath = true) }
        get("/motion/{name}") { handleMotion(sessionInPath = false) }
        get("/{system}/motion/{name}") { handleMotion(sessionInPath = true) }

        // Project mode only ([projectHistory]): one version of a render by content sha, read from
        // the local repository. Registered conditionally.
        if (projectHistory != null) {
          get("/history/render/{name}") { handleHistoryRender() }
          get("/{system}/history/render/{name}") { handleHistoryRender() }
        }
      }
    }

  /** Start listening. Non-blocking; the caller keeps the process alive separately. */
  fun start() {
    server.start(wait = false)
  }

  /** Stop with a short grace period. Idempotent enough for a shutdown hook. */
  fun stop() {
    readinessProber?.interrupt()
    thumbWarmer.stop()
    server.stop(gracePeriodMillis = 500, timeoutMillis = 2000)
    uiBuilderPrecompressed.close()
  }

  /**
   * The session id this request selects: the `{system}` segment in path mode ([sessionInPath]),
   * else `?session=`, falling back to [defaultSessionId]. Returned even when unknown; the lease
   * then 404s.
   */
  private fun RoutingContext.selectedSessionId(sessionInPath: Boolean): String =
    if (sessionInPath) call.parameters["system"] ?: defaultSessionId
    // A top-level site's host outranks `?session=`, or `/api/previews?session=wear-m3` would expose
    // a neighbour through the site.
    else siteSystem() ?: call.request.queryParameters["session"] ?: defaultSessionId

  /** `?format=json` — the spelling `/status` established and every page-with-data route reuses. */
  private fun RoutingContext.wantsJson(): Boolean =
    call.request.queryParameters["format"].equals("json", ignoreCase = true)

  /**
   * Refuses an unknown `?format=` instead of silently serving HTML that a consumer would parse as
   * data. Allowed: absent, `html`, `json`. Returns true when it has answered.
   */
  private suspend fun RoutingContext.rejectUnknownFormat(): Boolean {
    val format = call.request.queryParameters["format"] ?: return false
    if (format.equals("json", ignoreCase = true) || format.equals("html", ignoreCase = true)) {
      return false
    }
    call.respondText(
      "unsupported format: expected `json` or `html`",
      status = HttpStatusCode.BadRequest,
    )
    return true
  }

  /**
   * A catalog's RSS document. The request renews [catalogFeed]'s interest lease and queues
   * background catch-up; the handler returns the last completed document (or a valid empty one on
   * first cold request).
   */
  private suspend fun RoutingContext.handleCatalogFeed(sessionInPath: Boolean) {
    if (rejectBadToken()) return
    val feed =
      catalogFeed
        ?: run {
          call.respondText("not found", status = HttpStatusCode.NotFound)
          return
        }
    val system = selectedSessionId(sessionInPath)
    val (webSessionId, basePath) = webSessionAndBase(sessionInPath)
    val linkQuery = feedLinkQuery(basePath, webSessionId)
    val result =
      withContext(Dispatchers.IO) { feed.request(system, externalOrigin() + basePath, linkQuery) }
        ?: run {
          call.respondText("not found", status = HttpStatusCode.NotFound)
          return
        }
    if (result.building) call.response.headers.append(HttpHeaders.RetryAfter, "30")
    markGeneration("catalog-feed", "no-cache")
    call.respondText(
      result.xml,
      ContentType.parse("application/rss+xml; charset=utf-8"),
    )
  }

  /**
   * The routing/authentication values a feed URL carries. Readers can't replay headers for URLs
   * embedded in RSS, so links carry server-controlled values only, never arbitrary request
   * parameters.
   */
  private fun RoutingContext.feedLinkQuery(basePath: String, webSessionId: String?): String =
    buildList {
      if (basePath.isEmpty() && siteSystem() == null && webSessionId != null) {
        add("session=${WebEscaping.urlEncodeSegment(webSessionId)}")
      }
      if (linksCarryToken()) add("token=${WebEscaping.urlEncodeSegment(linkToken())}")
    }
    .joinToString("&")

  /**
   * The **Changelog** link for a catalog page's footer: this catalog's `/feed.xml`. Empty when the
   * feed lane is off or the session has no delivery branch.
   */
  private fun RoutingContext.changelogHref(
    system: String,
    basePath: String,
    webSessionId: String?,
  ): String {
    if (catalogFeed?.serves(system) != true) return ""
    val query = feedLinkQuery(basePath, webSessionId)
    return "$basePath/feed.xml" + query.takeIf { it.isNotBlank() }?.let { "?$it" }.orEmpty()
  }

  /**
   * The catalog this request's host publishes as a top-level site, or null (fast path when no
   * sites). See [ServeSites]. Reads `X-Forwarded-Host` only under [trustForwardedFor], like
   * [externalOrigin], else the request's `Host`.
   */
  private fun ApplicationCall.siteSystem(): String? {
    if (sites.isEmpty) return null
    return sites.systemFor(
      forwardedHeader(this, "X-Forwarded-Host", trustForwardedFor)
        ?: request.headers[HttpHeaders.Host]
    )
  }

  private fun RoutingContext.siteSystem(): String? = call.siteSystem()

  /**
   * The catalog identity (name, palette, theme `localStorage` key) a top-level site's non-catalog
   * pages should wear, so `/status` and 404s match the hostname. Empty on the main host or before
   * the catalog loads. Read via [ServeSessionRegistry.peekHost], which never resumes a daemon.
   */
  private fun RoutingContext.siteSkin(): Triple<String, String, String> = call.siteSkin()

  /** As [siteSkin], from the call alone — the site interceptor has no [RoutingContext]. */
  private fun ApplicationCall.siteSkin(): Triple<String, String, String> {
    val system = siteSystem() ?: return Triple("", "", "")
    val host = sessions.peekHost(system)
    val bundle = host?.let { catalogBundleHost(it) }
    val name =
      bundle?.title?.takeIf { it.isNotBlank() }
        ?: catalogMetaSeen[system]?.title
        ?: host?.label
        ?: system
    // Same key as the catalog's own pages, so one theme choice follows the visitor across the
    // hostname.
    // Palette from the resident bundle, else the last-known snapshot, so a suspended site keeps its
    // colours.
    val themeCss =
      bundle?.webThemeCss?.takeIf { it.isNotBlank() }
        ?: catalogMetaSeen[system]?.webThemeCss.orEmpty()
    return Triple(name, themeCss, "cp-theme:$system")
  }

  /**
   * Whether a GitHub sign-in started from this request's origin can come back to it.
   *
   * With [ServeGithubAuthConfig.cookieDomain] the state and session cookies are written for the
   * parent domain, so the pinned callback host and every site host under it share them; the state
   * carries the originating host for the return redirect. False for hosts outside that domain,
   * where a sign-in would land the visitor back signed out.
   */
  private fun RoutingContext.oauthCanRoundTrip(): Boolean =
    githubAuth?.canRoundTrip(requestHost(call, trustForwardedFor), browserHosts) ?: true

  /**
   * The session id for [ServeWeb] nav-marking and links, and the [basePath] for same-session links.
   * Path mode → the `{system}` segment and `/<system>`; query mode → raw `?session=` (null for
   * default) and empty base. Separate from [selectedSessionId] so the default session keeps
   * token-only links (byte-identical goldens).
   */
  private fun RoutingContext.webSessionAndBase(sessionInPath: Boolean): Pair<String?, String> {
    val system = if (sessionInPath) call.parameters["system"] else null
    if (system != null) return system to "/" + WebEscaping.urlEncodeSegment(system)
    // A top-level site: the session is this host's catalog with an empty base path, so links stay
    // on the custom domain. The session id still drives nav-marking and engagement counters.
    siteSystem()?.let {
      return it to ""
    }
    return call.request.queryParameters["session"] to ""
  }

  /**
   * The catalogs the playground may offer this request: all on the main host, only its own on a
   * top-level site. The playground's constant paths bypass the canonical-path interceptor, so this
   * enforces one catalog per host. The pinned "Server default" entry is kept.
   */
  private fun RoutingContext.siteScopedCatalogChoices(
    service: PlaygroundCompileService
  ): List<PlaygroundCatalogInfo> {
    val choices = service.catalogChoices()
    val site = siteSystem() ?: return choices
    return choices.filter { it.id.isEmpty() && sitePinIsOwn(service) || it.system == site }
  }

  /**
   * Whether the host's pinned playground default compiles against this site's own catalog. A pin to
   * a neighbour's bundle would let a site compile against another design system under "Server
   * default"; a pin to local files is nobody's neighbour.
   */
  private fun RoutingContext.sitePinIsOwn(service: PlaygroundCompileService): Boolean {
    val site = siteSystem() ?: return true
    val pinned = service.pinnedCatalogSystems
    return pinned.isEmpty() || pinned == setOf(site)
  }

  /** `POST /{system}/refresh`: check the published catalog branch and reload it when newer. */
  private suspend fun RoutingContext.handleCatalogRefresh(sessionInPath: Boolean) {
    if (rejectBadToken()) return
    val system = selectedSessionId(sessionInPath)
    val refresh = catalogRefresh
    if (system.isEmpty() || refresh == null) {
      call.respondText(
        "{\"status\":\"unavailable\"}",
        ContentType.Application.Json,
        HttpStatusCode.NotFound,
      )
      return
    }
    // Authorize before reserving the per-catalog refresh lane: the response can reach the client
    // before this coroutine resumes, so admitting first could make a following request see 202
    // instead of 404.
    val force = call.request.queryParameters["force"] == "1"
    if (force && rejectBadAdminToken()) return
    if (!catalogRefreshesInFlight.add(system)) {
      call.response.headers.append(HttpHeaders.CacheControl, "no-store")
      call.response.headers.append(HttpHeaders.RetryAfter, "2")
      call.respondText(
        "{\"status\":\"checking\"}",
        ContentType.Application.Json,
        HttpStatusCode.Accepted,
      )
      return
    }
    // `?force=1` re-fetches even when the branch hasn't moved (e.g. after clearing the blob cache).
    // Gated by the admin token, not the browse token: an ordinary refresh short-circuits on an
    // unchanged head (one `git ls-remote`), but forcing doesn't, so an anonymous caller on
    // `--public` could loop full re-stages. Without an admin token, forcing is impossible.
    val result =
      try {
        withContext(Dispatchers.IO) { refresh(system, force) }
      } finally {
        catalogRefreshesInFlight.remove(system)
      }
    val (status, code) =
      when (result) {
        CatalogRefreshResult.UPDATED -> "updated" to HttpStatusCode.OK
        CatalogRefreshResult.CURRENT -> "current" to HttpStatusCode.OK
        CatalogRefreshResult.UNAVAILABLE -> "unavailable" to HttpStatusCode.ServiceUnavailable
        CatalogRefreshResult.FAILED -> "failed" to HttpStatusCode.BadGateway
        CatalogRefreshResult.NOT_FOUND -> "not-found" to HttpStatusCode.NotFound
      }
    call.response.headers.append(HttpHeaders.CacheControl, "no-store")
    call.respondText("{\"status\":\"$status\"}", ContentType.Application.Json, code)
  }

  /**
   * Hosts a form may end up at after GitHub sign-in (`/auth/github/start`, GitHub, the pinned
   * callback host, back to the site), for `form-action` ([ServePagePolicy]). Empty without GitHub
   * sign-in.
   */
  private fun pagePolicyFormActions(): List<String> {
    val auth = githubAuth ?: return emptyList()
    return listOfNotNull(auth.callbackOrigin()) + browserHosts.sorted().map { "https://$it" }
  }

  /**
   * Browser-visible origin for absolute Open Graph image URLs. Behind Caddy, `Host` is preserved
   * and `X-Forwarded-Proto` supplied; direct requests use the connection scheme and `Host`. Only
   * the first proxy value matters. `X-Forwarded-*` is read only with [trustForwardedFor].
   */
  private fun RoutingContext.externalOrigin(): String {
    val forwardedScheme = forwardedHeader(call, "X-Forwarded-Proto", trustForwardedFor)
    val scheme =
      forwardedScheme?.takeIf { it.equals("http", true) || it.equals("https", true) }
        ?: call.request.origin.scheme
    val authority =
      forwardedHeader(call, "X-Forwarded-Host", trustForwardedFor)
        ?: call.request.headers[HttpHeaders.Host]?.substringBefore(',')?.trim()?.takeIf {
          it.isNotEmpty()
        }
        ?: "${call.request.origin.serverHost}:${call.request.origin.serverPort}"
    return "${scheme.lowercase()}://$authority"
  }

  /** Raw request query, including its leading `?` only when non-empty. */
  private fun RoutingContext.requestQuerySuffix(): String =
    call.request.queryString().let { if (it.isEmpty()) "" else "?$it" }

  /** A pinned render accepts routing state and the pin itself, never viewer render overrides. */
  private fun RoutingContext.pinnedRenderQuerySuffix(): String =
    call.request.queryParameters
      .entries()
      .filter { (key, _) ->
        key == "token" || key == "session" || key == ServeCatalogRevision.PARAM
      }
      .flatMap { (key, values) -> values.map { key to it } }
      .joinToString("&") { (key, value) ->
        "${WebEscaping.urlEncodeSegment(key)}=${WebEscaping.urlEncodeSegment(value)}"
      }
      .let { if (it.isEmpty()) "" else "?$it" }

  /**
   * The global presentation mode. The command sets the initial mode (`browse` → Catalog, `serve` →
   * Dev); the visitor's [ServeWeb.INTERFACE_MODE_COOKIE] wins over that; an explicit `?chrome=`
   * wins over both.
   *
   * The cookie travels with every request, so no link needs to carry the choice. `?chrome=` is a
   * per-request permalink and deliberately doesn't write the cookie.
   */
  private fun ApplicationCall.componentBrowserMode(): Boolean {
    interfaceMode(request.queryParameters[CHROME_PARAM])?.let {
      return it
    }
    // Only on this branch: the body now depends on the cookie, so shared caches must vary on it. A
    // pinned `?chrome=` never reads the cookie.
    varyOnCookie()
    return interfaceMode(request.cookies[ServeWeb.INTERFACE_MODE_COOKIE]) ?: componentBrowser
  }

  private fun RoutingContext.componentBrowserMode(): Boolean = call.componentBrowserMode()

  /**
   * The header's GitHub control, or null when the sign-in can't return to this origin
   * ([oauthCanRoundTrip]): the CSRF state would be unreadable at the callback and the visitor would
   * land back signed out. A signed-in identity is never hidden by this in practice.
   */
  /**
   * [lane] is what the sign-in unlocks on the requesting page, for the tooltip. Defaults to Live; a
   * landing whose only gated lane is the playground passes [ServeWeb.GatedLane.PLAYGROUND], the
   * only case `--github-auth-repo` is named.
   */
  private fun RoutingContext.githubAuthStatus(
    lane: ServeWeb.GatedLane = ServeWeb.GatedLane.LIVE
  ): ServeWeb.GitHubAuthStatus? =
    githubAuth
      ?.takeIf { oauthCanRoundTrip() }
      ?.let { auth ->
        ServeWeb.GitHubAuthStatus(
          loginHref = auth.loginPath(call),
          logoutHref = auth.logoutPath(call),
          // Who is signed in, guests included: the chip is an identity, not a permission, and a
          // guest shown "Sign in" would sign in again and land back here none the wiser.
          login = auth.currentSignedInLogin(call),
          restrictedToAllowedUsers = auth.isRestrictedToAllowedUsers(),
          lane = lane,
          accessRepository =
            auth.accessRepository().takeIf { lane == ServeWeb.GatedLane.PLAYGROUND },
          notifications = push != null,
        )
      }

  /**
   * What the front door may offer this visitor about the UI builder ([ServeWeb.UiBuilderInvite]).
   * Null when the builder isn't deployed.
   *
   * Asks [uiBuilderAuthorization] with the same capability the create route demands
   * ([UiBuilderRouteCapability.WRITE]), so card and POST agree whatever the credential (operator
   * token, GitHub session, agent grant). [ServeMachineAuthorization] answers `Missing` for a
   * signed-in visitor lacking repository access, so the reason is assembled here where repo and
   * login are known.
   */
  private fun RoutingContext.uiBuilderInvite(): ServeWeb.UiBuilderInvite? {
    val authorization = uiBuilderAuthorization ?: return null
    if (uiBuilderDir == null || uiBuilderCatalogs.isEmpty()) return null
    // Guests included: a guest is signed in, and told why it cannot create, rather than offered a
    // sign-in it has already done.
    val login = githubAuth?.currentSignedInLogin(call)
    val decision = authorization.authorize(call, UiBuilderRouteCapability.WRITE)
    val permitted = decision is UiBuilderAuthorizationDecision.Authorized
    return ServeWeb.UiBuilderInvite(
      systems = uiBuilderCatalogs,
      editorHref = uiBuilderHost?.let { "https://$it/" } ?: "/ui-builder/",
      // An operator token is a sign-in for this purpose: it carries the capability, and hiding the
      // action from the one credential that always has it would be a strange kind of security.
      signedIn = login != null || permitted,
      permitted = permitted,
      deniedReason =
        if (permitted) "" else uiBuilderDeniedReason(login, githubAuth?.isGuest(call) == true),
    )
  }

  /**
   * The identity endpoint's account of this caller beyond its actor id: whether anyone is signed
   * in, why a write would be refused, and where to sign in. [canWrite] comes from the route (the
   * same WRITE question as [uiBuilderInvite]). The sign-in link returns to the editor page.
   */
  private fun uiBuilderIdentityDetails(
    call: ApplicationCall,
    canWrite: Boolean,
  ): UiBuilderIdentityDetails {
    val auth = githubAuth
    val login = auth?.currentSignedInLogin(call)
    return UiBuilderIdentityDetails(
      // An operator token is a sign-in for this purpose, as it is for the invite card.
      signedIn = login != null || canWrite,
      writeDeniedReason =
        if (canWrite) null else uiBuilderDeniedReason(login, auth?.isGuest(call) == true),
      signInUrl =
        if (auth == null || login != null) null
        else
          ServeGithubAuth.START_PATH +
            "?return=" +
            java.net.URLEncoder.encode(uiBuilderReturnPath(call), Charsets.UTF_8),
    )
  }

  /**
   * The same-host page the identity request came from, or the builder home. Only a path is kept;
   * the sign-in route sanitizes it again.
   */
  private fun uiBuilderReturnPath(call: ApplicationCall): String {
    val fallback = "/ui-builder"
    val referer = call.request.headers[HttpHeaders.Referrer] ?: return fallback
    val uri = runCatching { java.net.URI(referer) }.getOrNull() ?: return fallback
    val sameHost =
      uri.host == null ||
        uri.host.equals(call.request.local.serverHost, ignoreCase = true) ||
        uri.host.equals(
          call.request.headers[HttpHeaders.Host]?.substringBefore(':'),
          ignoreCase = true,
        )
    val path = uri.rawPath?.takeIf { it.startsWith("/") && sameHost } ?: return fallback
    return ServeGithubAuth.safeReturnTo(path + (uri.rawQuery?.let { "?$it" } ?: ""))
  }

  /** Why this visitor may not create a design, in the terms they can act on. */
  private fun uiBuilderDeniedReason(login: String?, guest: Boolean = false): String {
    val auth = githubAuth
    // Nobody signed in on a host that offers GitHub sign-in: the next step is to sign in, not to
    // ask an operator for a permission nobody has been asked for yet.
    if (login == null && auth != null) return "Sign in with GitHub to create and edit designs."
    // A guest on a box that names its members: repository access is not the bar there — being
    // one of the named accounts or orgs is — so say that, and name the orgs.
    if (guest && auth != null && auth.isRestrictedToAllowedUsers()) {
      val orgs = auth.allowedOrgs().sorted()
      val account = "the account you are signed in with ($login)"
      return if (orgs.isNotEmpty()) {
        val named = orgs.joinToString(" or ")
        val noun = if (orgs.size == 1) "organization" else "organizations"
        "Creating and editing designs here is limited to members of the $named GitHub $noun, " +
          "and $account is not one. If you joined recently, sign out and sign in again."
      } else {
        "Creating and editing designs here is limited to accounts the operator has listed, and " +
          "$account is not one of them. Ask an operator for access."
      }
    }
    val repository = githubAuth?.accessRepository()?.takeIf { it.isNotBlank() }
    val account = login?.let { "the account you are signed in with ($it)" } ?: "your session"
    return if (repository != null) {
      "Creating a design needs write access to $repository, which $account does not have. " +
        "Ask an operator to add you, or sign in with an account that has it."
    } else {
      "Creating a design needs UI-builder write access, which $account does not carry. " +
        "Ask an operator for access."
    }
  }

  /** The two wire values of the Catalog / Dev switch; null for absent, empty, or anything else. */
  private fun interfaceMode(value: String?): Boolean? =
    when (value?.lowercase()) {
      "catalog" -> true
      "dev" -> false
      else -> null
    }

  /** Add `Vary: Cookie`, at most once, since several things on a response may depend on cookies. */
  private fun ApplicationCall.varyOnCookie() {
    val already =
      response.headers.values(HttpHeaders.Vary).any {
        it.splitToSequence(',').any { part -> part.trim().equals(HttpHeaders.Cookie, true) }
      }
    if (!already) response.headers.append(HttpHeaders.Vary, HttpHeaders.Cookie)
  }

  private fun RoutingContext.varyOnCookie() = call.varyOnCookie()

  /** Absolute externally visible URL for the current page (including its query). */
  private fun RoutingContext.externalPageUrl(): String = externalOrigin() + call.request.origin.uri

  /**
   * Resolve the tenant for [sessionId] and run [block] holding a [ServeSessionRegistry.Lease] for
   * the whole request, so the reaper can't suspend the daemon mid-request. Responds 404 when the
   * session can't be opened. The lease is always released.
   */
  private suspend fun RoutingContext.withLeasedSession(
    sessionId: String,
    /**
     * How to respond when the session can't be opened: bare `text/plain` 404 by default; HTML page
     * routes pass [respondNotFoundHtml].
     */
    onMissing: (suspend RoutingContext.() -> Unit)? = null,
    block: suspend (ServeHost) -> Unit,
  ) {
    val lease = withContext(Dispatchers.IO) { sessions.lease(sessionId) }
    if (lease == null) {
      if (onMissing != null) onMissing()
      else call.respondText("not found", status = HttpStatusCode.NotFound)
      return
    }
    try {
      block(lease.host)
    } finally {
      // Called directly, not through `withContext`: a `withContext` in a `finally` throws
      // immediately once the job is cancelled — exactly when a visitor navigates away or a socket
      // drops. A skipped release leaks the lease, keeping the session resident forever and stopping
      // `--exit-when-idle` (`connectionIdleMillis`) from firing. `Lease.close` is non-suspending
      // and idempotent.
      lease.close()
    }
  }

  /**
   * [withLeasedSession] for a lane that reads a value off the host, so the caller picks the status
   * after the lease is released. Null covers both "no session" and "nothing there". [block] runs on
   * [Dispatchers.IO], since reading an asset may hit the delivery branch.
   *
   * Resumes but never creates: [ServeSessionRegistry.lease] would fall through to the session
   * factory, which in project mode with `--revisions` checks out a ref and runs Gradle. Gating on
   * [isKnownSession] keeps strangers from triggering arbitrary builds via a lane that can only 404.
   */
  private suspend fun <T> RoutingContext.withLeasedSessionOrNull(
    sessionId: String,
    block: (ServeHost) -> T?,
  ): T? {
    if (!sessions.isKnownSession(sessionId)) return null
    val lease = withContext(Dispatchers.IO) { sessions.lease(sessionId) } ?: return null
    return try {
      withContext(Dispatchers.IO) { block(lease.host) }
    } finally {
      // Called directly, not through `withContext`, which is skipped once the job is cancelled
      // (e.g. a client disconnecting during the branch fetch); a leaked lease pins the daemon
      // resident forever.
      lease.close()
    }
  }

  /**
   * A styled HTML 404 for browser-facing page routes ([ServeWeb.notFoundPage]). Asset/API lanes
   * keep the bare `text/plain` 404.
   */
  private suspend fun RoutingContext.respondNotFoundHtml(message: String) {
    val skin = siteSkin()
    // Not immutable: refresh, admin registration or parity staging can make the URL valid later, so
    // the 404 must not be cached.
    markGeneration("static-page", DYNAMIC_RESOURCE_CACHE_CONTROL)
    call.respondText(
      ServeWeb.notFoundPage(
        message,
        linkToken(),
        isPublic,
        unfurl = ServeWeb.UnfurlMetadata(pageUrl = externalPageUrl()),
        version = SERVE_VERSION,
        siteName = skin.first,
        themeCss = skin.second,
        themeStorageKey = skin.third,
        componentBrowser = componentBrowserMode(),
        githubAuth = githubAuthStatus(),
      ),
      ContentType.Text.Html,
      HttpStatusCode.NotFound,
    )
  }

  // ---- The document lane (`--accept-docs`) ---------------------------------------------------

  /** `GET /docs`: the upload surface — drop a known document, get an expiring permalink back. */
  private suspend fun RoutingContext.handleDocUploadPage(store: ServeDocStore) {
    if (rejectBadToken()) return
    markGeneration("static-page", pageCacheControl())
    call.respondText(
      ServeWeb.docUploadPage(
        token = linkToken(),
        isPublic = isPublic,
        ttlSeconds = store.ttlSeconds,
        urlUploadAllowed = store.urlFetchAllowed,
        unfurl = ServeWeb.UnfurlMetadata(pageUrl = externalPageUrl()),
        version = SERVE_VERSION,
      ),
      ContentType.Text.Html,
    )
  }

  /**
   * `POST /docs` (body = document, or `?url=`): ingest and answer with its expiring permalink.
   * `?name=` is a display label only; the store content-sniffs the format.
   */
  private suspend fun RoutingContext.handleDocUpload(store: ServeDocStore) {
    if (rejectBadTokenForIngest()) return
    val name = call.request.queryParameters["name"]
    val url = call.request.queryParameters["url"]
    // Cap the body as it streams in — receiving it whole first would let a client OOM the server
    // regardless of the store's own cap.
    val body =
      if (url == null) {
        withContext(Dispatchers.IO) { call.receiveStream().use { readCapped(it, MAX_DOC_BYTES) } }
          ?: run {
            call.respondText(
              "document exceeds ${MAX_DOC_BYTES / (1024 * 1024)}MB",
              status = HttpStatusCode.PayloadTooLarge,
            )
            return
          }
      } else {
        null
      }
    // isSecurityChecked = true: token-gated (rejectBadToken above); the store defends in depth
    // (format sniff, size + count caps, TTL, SSRF allowlist).
    val result =
      withContext(Dispatchers.IO) {
        if (url != null) store.addFromUrl(name, url, isSecurityChecked = true)
        else store.add(name, body!!, isSecurityChecked = true)
      }
    when (result) {
      is ServeDocStore.Result.Ok -> {
        val doc = result.doc
        call.respondText(
          JSON.encodeToString(
            DocAcceptedResponse.serializer(),
            DocAcceptedResponse(
              id = doc.id,
              name = doc.name,
              format = doc.format.label,
              formatId = doc.format.id,
              bytes = doc.sizeBytes,
              url = doc.path,
              expiresIn = ServeWeb.humanDuration(store.remainingSeconds(doc)),
              expiresAtEpochSeconds = doc.expiresAtMillis / 1000,
            ),
          ),
          ContentType.Application.Json,
          HttpStatusCode.Created,
        )
      }
      is ServeDocStore.Result.Failed ->
        call.respondText(result.reason, status = HttpStatusCode.BadRequest)
    }
  }

  /**
   * `POST /api/{version}/compiler/run`: compile a playground snippet and return diagnostics (our
   * shape and the stock `errors` map) plus, on success, an expiring preview token. Runs user code
   * in-process, so the CLI refuses this lane under `--public`. `isSecurityChecked = true`: the
   * token gate passed; the service bounds the work.
   */
  /**
   * `GET /playground`: the Stage-1 editor page. Token-gated (never public); its script POSTs to
   * `/api/{v}/compiler/run` and follows the `/pg/` or `/d/` handoff.
   */
  private suspend fun RoutingContext.handlePlaygroundPage(service: PlaygroundCompileService) {
    if (rejectBadToken()) return
    if (rejectMissingGithubAuth()) return
    if (rejectMissingGithubRepoAccess()) return
    // `?from=<system>/<previewId>` opens that preview's Kotlin with its catalog preselected;
    // `?catalog=<system>` just preselects the catalog. Both resolve through this server's registry,
    // so a request never names a URL to fetch. An unresolvable seed opens the sample; the log says
    // why.
    val seed =
      call.request.queryParameters["from"]?.let { raw ->
        val system = raw.substringBefore('/')
        val previewId = raw.substringAfter('/', "")
        // `?from=` is caller-supplied, so on a site host a neighbour's `from` is ignored (not
        // refused) to keep one catalog per host.
        val siteSystem = siteSystem()
        if (system.isBlank() || previewId.isBlank()) null
        else if (siteSystem != null && system != siteSystem) null
        // On the IO dispatcher: an uncached seed is a synchronous GitHub GET (10 s connect + 10 s
        // read) that would stall the routing dispatcher.
        else withContext(Dispatchers.IO) { playgroundSeeds?.seed(system, previewId) }
      }
    markGeneration("static-page", pageCacheControl())
    call.respondText(
      ServeWeb.playgroundPage(
        token = linkToken(),
        isPublic = isPublic,
        catalogs = siteScopedCatalogChoices(service),
        catalogSelectorEnabled = service.catalogSelectorEnabled,
        seed = seed,
        preselectCatalog = call.request.queryParameters["catalog"],
        pinnedCatalogSystems = service.pinnedCatalogSystems,
        unfurl = ServeWeb.UnfurlMetadata(pageUrl = externalPageUrl()),
        version = SERVE_VERSION,
        editingLeaseEnabled = service.editLeasesEnabled && githubAuth?.currentLogin(call) != null,
      ),
      ContentType.Text.Html,
    )
  }

  /**
   * `GET /api/{version}/compiler/catalogs`: what the editor's catalog selector may offer — the
   * pinned default plus every catalog that can back a compile, with their modes. Gated like the run
   * route.
   */
  private suspend fun RoutingContext.handlePlaygroundCatalogs(service: PlaygroundCompileService) {
    if (rejectBadToken()) return
    if (rejectMissingGithubAuth(api = true)) return
    if (rejectMissingGithubRepoAccess(api = true)) return
    markGeneration("playground-catalogs", DYNAMIC_RESOURCE_CACHE_CONTROL)
    call.respondText(
      JSON.encodeToString(
        PlaygroundCatalogsResponse.serializer(),
        PlaygroundCatalogsResponse(siteScopedCatalogChoices(service)),
      ),
      ContentType.Application.Json,
    )
  }

  private suspend fun RoutingContext.handlePlaygroundDisabledPage() {
    markGeneration("static-page", DYNAMIC_RESOURCE_CACHE_CONTROL)
    call.respondText(
      ServeWeb.playgroundDisabledPage(
        token = linkToken(),
        isPublic = isPublic,
        unfurl = ServeWeb.UnfurlMetadata(pageUrl = externalPageUrl()),
        version = SERVE_VERSION,
      ),
      ContentType.Text.Html,
      HttpStatusCode.ServiceUnavailable,
    )
  }

  /**
   * `GET /pg/<token>`: redeem a preview token into a live session and redirect to its viewer. An
   * unknown/expired token, or one without a live backend here, gets a styled 404 that discloses
   * neither. Token-gated.
   */
  private suspend fun RoutingContext.handlePlaygroundRedeem(redeem: PlaygroundRedeemService) {
    if (rejectBadToken()) return
    if (rejectMissingGithubAuth()) return
    if (rejectMissingGithubRepoAccess()) return
    // Read the PATH segment explicitly (see the route mount): it's named `{pgToken}` so it can't be
    // shadowed by the `?token=` access token that `call.parameters` also carries on a gated host.
    val id = call.parameters["pgToken"].orEmpty()
    val gone = "That preview link has expired, or never existed."
    if (!PlaygroundTokenStore.isWellFormedId(id)) {
      respondNotFoundHtml(gone)
      return
    }
    // `?preview=<id>` opens a specific preview of the snippet. Read from the query (not the path)
    // to avoid the access token; validated by the service, falling back to the first.
    val preview = call.request.queryParameters["preview"]?.takeIf { it.isNotBlank() }
    when (val outcome = redeem.redeem(id, preview)) {
      PlaygroundRedeemService.Outcome.NotFound -> respondNotFoundHtml(gone)
      PlaygroundRedeemService.Outcome.Unavailable ->
        respondNotFoundHtml("Live preview isn't available on this host.")
      is PlaygroundRedeemService.Outcome.Live -> {
        // Hand off to the path-form viewer for the new session, whose `/{session}/ws/{preview}`
        // lane enforces the live-seat budget. Carry the token for the gated follow-on requests.
        val suffix =
          if (!linksCarryToken()) ""
          else "?token=" + java.net.URLEncoder.encode(linkToken(), Charsets.UTF_8)
        call.respondRedirect("/${outcome.sessionId}/p/${outcome.previewId}$suffix")
      }
    }
  }

  /**
   * Who to charge for rate limiting: the authenticated GitHub login if any (stable across
   * addresses), else the client address. Prefixed so the two spaces never collide and signing in
   * doesn't inherit a NAT neighbour's spent budget.
   */
  private fun RoutingContext.rateLimitKey(): String {
    githubAuth
      ?.currentLogin(call)
      ?.takeIf { it.isNotBlank() }
      ?.let {
        return "gh:$it"
      }
    return clientAddressKey()
  }

  /**
   * Who to charge by address only, for work before an identity exists or deliberately per-address.
   * Separate from [rateLimitKey] so a lane charging twice doesn't put both charges in one bucket.
   */
  private fun RoutingContext.clientAddressKey(): String = "ip:" + clientAddress()

  /**
   * The caller's address under the forwarding policy: the trusted final `X-Forwarded-For` hop with
   * `--trust-forwarded-for`, else the socket peer. Shared by the rate limiter and any display of
   * the address.
   */
  private fun RoutingContext.clientAddress(): String {
    val forwarded =
      if (!trustForwardedFor) null
      else
        call.request.headers["X-Forwarded-For"]
          ?.split(',')
          ?.map { it.trim() }
          ?.lastOrNull { it.isNotEmpty() }
    return forwarded ?: call.request.origin.remoteHost
  }

  /**
   * Charge this request against its caller's budget, answering `429` + `Retry-After` and returning
   * null when over. A non-null result must be released.
   */
  private suspend fun RoutingContext.acquirePlaygroundPermit():
    ServeRateLimiter.Decision.Admitted? {
    val limiter = playgroundRateLimiter ?: return ServeRateLimiter.Decision.Admitted {}
    return when (val decision = limiter.tryAcquire(rateLimitKey())) {
      is ServeRateLimiter.Decision.Admitted -> decision
      is ServeRateLimiter.Decision.Throttled -> {
        call.response.headers.append(HttpHeaders.RetryAfter, decision.retryAfterSeconds.toString())
        call.respondText(
          JSON.encodeToString(
            PlaygroundRunResponse.serializer(),
            PlaygroundRunResponse(exception = "Too many requests — ${decision.reason}."),
          ),
          ContentType.Application.Json,
          HttpStatusCode.TooManyRequests,
        )
        null
      }
    }
  }

  private suspend fun RoutingContext.handlePlaygroundRun(service: PlaygroundCompileService) {
    if (rejectBadToken()) return
    if (rejectMissingGithubAuth(api = true)) return
    if (rejectMissingGithubRepoAccess(api = true)) return
    if (rejectForeignSessionRequest(json = true)) return
    // After the gates, before the body read: a throttled caller should cost this host a 429 and
    // nothing else — not 256 KB of buffered upload, and certainly not a compile slot.
    val permit = acquirePlaygroundPermit() ?: return
    try {
      handlePlaygroundRunAdmitted(service)
    } finally {
      permit.release()
    }
  }

  private suspend fun RoutingContext.handlePlaygroundRunAdmitted(
    service: PlaygroundCompileService
  ) {
    val body = receivePlaygroundBody() ?: return
    val request =
      try {
        JSON.decodeFromString(PlaygroundRunRequest.serializer(), body.decodeToString())
      } catch (e: Exception) {
        call.respondText(
          "invalid playground request: ${e.message}",
          status = HttpStatusCode.BadRequest,
        )
        return
      }
    // A site host must reject a neighbour's catalog id outright, since it is a request field. Empty
    // (the pinned default) stays legal.
    val site = siteSystem()
    val foreignCatalog = request.catalog.isNotEmpty() && request.catalog != site
    // An EMPTY catalog is the pinned default, which is only legitimate here when the pin is this
    // site's own — otherwise it is a neighbour's classpath wearing the name "Server default".
    val foreignPin = request.catalog.isEmpty() && !sitePinIsOwn(service)
    if (site != null && (foreignCatalog || foreignPin)) {
      call.respondText(
        "{\"error\":\"unknown catalog\"}",
        ContentType.Application.Json,
        HttpStatusCode.NotFound,
      )
      return
    }
    val response =
      withContext(Dispatchers.IO) {
        service.run(
          request,
          isSecurityChecked = true,
          authenticatedOwner = githubAuth?.currentLogin(call),
        )
      }
    call.respondText(
      JSON.encodeToString(PlaygroundRunResponse.serializer(), response),
      ContentType.Application.Json,
    )
  }

  private suspend fun RoutingContext.handlePlaygroundEditLeaseAcquire(
    service: PlaygroundCompileService
  ) {
    if (rejectBadToken()) return
    if (rejectMissingGithubAuth(api = true)) return
    if (rejectMissingGithubRepoAccess(api = true)) return
    if (rejectForeignSessionRequest(json = true)) return
    val owner = githubAuth?.currentLogin(call)
    if (owner == null) {
      call.respondText(
        "GitHub sign-in is required for live editing.",
        status = HttpStatusCode.Unauthorized,
      )
      return
    }
    val body = receivePlaygroundBody() ?: return
    val request =
      if (body.isEmpty()) PlaygroundEditLeaseAcquireRequest()
      else
        runCatching {
          JSON.decodeFromString(
            PlaygroundEditLeaseAcquireRequest.serializer(),
            body.decodeToString(),
          )
        }
          .getOrNull()
    if (request == null) {
      call.respondText("Invalid live-edit lease request.", status = HttpStatusCode.BadRequest)
      return
    }
    val result = withContext(Dispatchers.IO) { service.acquireEditLease(owner, request.client) }
    call.respondText(
      JSON.encodeToString(PlaygroundEditLeaseResponse.serializer(), result),
      ContentType.Application.Json,
      if (result.acquired) HttpStatusCode.OK else HttpStatusCode.Conflict,
    )
  }

  private suspend fun RoutingContext.handlePlaygroundEditLeaseRelease(
    service: PlaygroundCompileService
  ) {
    if (rejectBadToken()) return
    if (rejectMissingGithubAuth(api = true)) return
    if (rejectMissingGithubRepoAccess(api = true)) return
    if (rejectForeignSessionRequest(json = true)) return
    val owner = githubAuth?.currentLogin(call)
    if (owner == null) {
      call.respondText(
        "GitHub sign-in is required for live editing.",
        status = HttpStatusCode.Unauthorized,
      )
      return
    }
    val body = receivePlaygroundBody() ?: return
    val request = runCatching {
      JSON.decodeFromString(
        PlaygroundEditLeaseReleaseRequest.serializer(),
        body.decodeToString(),
      )
    }
      .getOrNull()
    if (
      request == null || !service.releaseEditLease(owner, request.lease, client = request.client)
    ) {
      call.respondText("Live-edit lease not found.", status = HttpStatusCode.NotFound)
      return
    }
    call.respondText("{\"released\":true}", ContentType.Application.Json)
  }

  /**
   * Refuse a request authenticated only by the GitHub session cookie that didn't come from this
   * server's page, and, for a [json] route, one whose body isn't declared JSON. Header and bearer
   * credentials pass ([ServeSameOriginRequests]).
   */
  private suspend fun RoutingContext.rejectForeignSessionRequest(json: Boolean = false): Boolean {
    if (ServeSameOriginRequests.isForeignSessionRequest(call, browserHosts)) {
      call.respondText("request origin not accepted", status = HttpStatusCode.Forbidden)
      return true
    }
    if (json && ServeSameOriginRequests.isNonJsonSessionRequest(call)) {
      call.respondText(
        "Content-Type: application/json is required",
        status = HttpStatusCode.UnsupportedMediaType,
      )
      return true
    }
    return false
  }

  /** Reject declared oversized bodies before the handler reads their content. */
  private suspend fun RoutingContext.receivePlaygroundBody(): ByteArray? {
    val declaredBytes = call.request.headers[HttpHeaders.ContentLength]?.toLongOrNull()
    val body =
      if (declaredBytes != null && declaredBytes > MAX_PLAYGROUND_BYTES) null
      else
        withContext(Dispatchers.IO) {
          call.receiveStream().use { readCapped(it, MAX_PLAYGROUND_BYTES) }
        }
    if (body == null) {
      call.respondText(
        "playground request exceeds ${MAX_PLAYGROUND_BYTES / 1024}KB",
        status = HttpStatusCode.PayloadTooLarge,
      )
    }
    return body
  }

  /** `GET /d/{id}`: the permalink page. An expired (or unknown) id is a styled 404, not a hint. */
  private suspend fun RoutingContext.handleDocPage(store: ServeDocStore) {
    if (rejectBadToken()) return
    val doc = leaseDoc(store)
    if (doc == null) {
      respondNotFoundHtml("That document link has expired, or never existed.")
      return
    }
    val size = doc.format.size(doc.bytes)
    // An expiring capability URL must never be stored by a shared cache — no-store, always.
    markGeneration("document", "private, no-store")
    call.respondText(
      ServeWeb.docPage(
        ServeWeb.DocView(
          id = doc.id,
          name = doc.name,
          formatId = doc.format.id,
          formatLabel = doc.format.label,
          playerPath = doc.format.playerPath,
          rawPath = "${doc.path}/raw",
          facts = doc.format.describe(doc.bytes),
          sizeText = humanBytes(doc.sizeBytes),
          expiresInText = ServeWeb.humanDuration(store.remainingSeconds(doc)),
          expiresAtText = Instant.ofEpochMilli(doc.expiresAtMillis).toString(),
          width = size?.width,
          height = size?.height,
        ),
        token = linkToken(),
        isPublic = isPublic,
        unfurl = ServeWeb.UnfurlMetadata(pageUrl = externalPageUrl()),
        version = SERVE_VERSION,
        // The same `/rc-player-wasm/` the viewer's cmp-wasm lane frames; absent, the page offers
        // only the TypeScript player, as it always did.
        cmpWasmPlayerPath = rcPlayerWasmDir?.let { "/rc-player-wasm/index.html" },
        serverPlayers = docServerPlayers(doc),
      ),
      ContentType.Text.Html,
    )
  }

  /** `GET /d/{id}/raw`: the document bytes the browser player fetches (and the download link). */
  private suspend fun RoutingContext.handleDocRaw(store: ServeDocStore) {
    if (rejectBadToken()) return
    val doc = leaseDoc(store)
    if (doc == null) {
      call.respondText("not found", status = HttpStatusCode.NotFound)
      return
    }
    markGeneration("document", "private, no-store")
    // Client-supplied bytes: pin the declared type so no browser can sniff them into something
    // active (e.g. HTML) served from this origin.
    call.response.headers.append("X-Content-Type-Options", "nosniff")
    call.respondBytes(doc.bytes, ContentType.parse(doc.format.contentType))
  }

  // ---- The image lane (`--accept-images`) -----------------------------------------------------

  /**
   * Whether this browser session may use the image lane for report captures. Catalog reports are
   * filed in place, so the capture bundle probes this route. Admits only the signed browser
   * session, not bearer tokens or grants.
   */
  private suspend fun RoutingContext.handleImageUploadCapability(auth: ServeImageUploadAuth) {
    if (rejectBadToken()) return
    val login = imageBrowserLogin?.invoke(call, auth.repository)
    if (login == null) {
      call.respondText("image uploads unavailable", status = HttpStatusCode.Forbidden)
      return
    }
    // Whether anonymous visitors can open this host's pages; on a token-gated host captures need
    // explicit consent since image URLs are anonymous-read.
    call.response.headers.append(CAPTURE_SCOPE_HEADER, if (isPublic) "public" else "private")
    call.respondText("", status = HttpStatusCode.NoContent)
  }

  /**
   * `POST /images?name=<label>` (body = image bytes): ingest a rendered preview and answer with the
   * URL to embed.
   *
   * Always authenticated, even on `--public`: the caller presents `Authorization: Bearer
   * <github-token>` and must be a collaborator on the gating repository ([ServeImageUploadAuth]),
   * since this hosts content under the operator's name. `?name=` is a display label only; the store
   * content-sniffs.
   */
  private suspend fun RoutingContext.handleImageUpload(
    store: ServeImageStore,
    auth: ServeImageUploadAuth,
  ) {
    if (rejectBadToken()) return
    // Charged before the identity check, by address: verifying a token is a synchronous GitHub
    // call, and nothing else bounds an anonymous caller spraying invalid tokens.
    // Address-only, not [rateLimitKey], which may return the same login key as the
    // post-verification charge and halve the budget.
    // A grant with `images` is checked before the GitHub round trip: the operator already decided,
    // and the agent shouldn't need a GitHub credential.
    grantedImageIdentity()?.let { granted ->
      val permit = acquireImagePermit(granted.budgetKey) ?: return
      try {
        acceptImageUpload(store, granted.login)
      } finally {
        permit.release()
      }
      return
    }
    // A bug report comes from a browser holding a signed OAuth session, admitted through the
    // runner's repository-matching resolver. Before the anonymous budget, since no GitHub call is
    // made and charging the IP would halve a one-upload budget.
    imageBrowserLogin?.invoke(call, auth.repository)?.let { login ->
      // Admitted by the session cookie alone, so only from a page this server served. A caller
      // presenting a bearer, a grant or a token header is judged by its own gate as before.
      if (rejectForeignSessionRequest()) return
      val permit = acquireImagePermit("browser:$login") ?: return
      try {
        acceptImageUpload(store, login)
      } finally {
        permit.release()
      }
      return
    }
    val verifyPermit = acquireImagePermit(clientAddressKey()) ?: return
    val identity =
      try {
        authorizeImageUpload(auth)
      } finally {
        // Released as soon as the GitHub round-trip is done rather than at the end of the request:
        // it exists to bound *verification*, and the accepted upload below has its own budget.
        verifyPermit.release()
      } ?: return
    // Per-caller budget charged to the verified identity (CI addresses are shared or ephemeral).
    // The key comes from the gate ([Identity.Ok.budgetKey]).
    val permit = acquireImagePermit(identity.budgetKey) ?: return
    try {
      acceptImageUpload(store, identity.login)
    } finally {
      permit.release()
    }
  }

  /**
   * Store the posted image and answer `201` with the line to paste. Shared by both admission paths
   * (verified GitHub credential or `images` grant); [login] is the attribution reported as
   * `uploadedBy`.
   */
  private suspend fun RoutingContext.acceptImageUpload(store: ServeImageStore, login: String) {
    val name = call.request.queryParameters["name"]
    // Cap the body as it streams in — receiving it whole first would let a client OOM the server
    // regardless of the store's own cap.
    val body =
      withContext(Dispatchers.IO) { call.receiveStream().use { readCapped(it, MAX_IMAGE_BYTES) } }
        ?: run {
          call.respondText(
            "image exceeds ${MAX_IMAGE_BYTES / (1024 * 1024)}MB",
            status = HttpStatusCode.PayloadTooLarge,
          )
          return
        }
    // isSecurityChecked = true: the identity gate above cleared this caller. The store still
    // defends in depth (format sniff, size + count caps, TTL).
    when (
      val result =
        withContext(Dispatchers.IO) {
          store.add(name, body, uploadedBy = login, isSecurityChecked = true)
        }
    ) {
      is ServeImageStore.Result.Ok -> {
        val image = result.image
        // Absolute, built from the forwarded origin, since it will be pasted into a PR body on
        // github.com.
        val url = externalOrigin() + image.path
        val size = image.dimensions
        call.respondText(
          JSON.encodeToString(
            ImageAcceptedResponse.serializer(),
            ImageAcceptedResponse(
              id = image.id,
              name = image.name,
              format = image.format.label,
              formatId = image.format.id,
              bytes = image.sizeBytes,
              width = size?.width,
              height = size?.height,
              path = image.path,
              url = url,
              // Return finished markdown so callers don't produce the `![alt](`url`)` shape that
              // renders as literal text.
              markdown = "![${image.name}]($url)",
              uploadedBy = image.uploadedBy,
              expiresIn = ServeWeb.humanDuration(store.remainingSeconds(image)),
              expiresAtEpochSeconds = image.expiresAtMillis / 1000,
            ),
          ),
          ContentType.Application.Json,
          HttpStatusCode.Created,
        )
      }
      is ServeImageStore.Result.Failed ->
        call.respondText(result.reason, status = HttpStatusCode.BadRequest)
    }
  }

  /**
   * Charge one unit of image-lane work to [key], answering `429` + `Retry-After` and returning null
   * when over budget. A non-null result must be released. A no-op permit when the budget is
   * disabled.
   */
  private suspend fun RoutingContext.acquireImagePermit(
    key: String
  ): ServeRateLimiter.Decision.Admitted? {
    val limiter = imageUploadLimiter ?: return ServeRateLimiter.Decision.Admitted {}
    return when (val decision = limiter.tryAcquire(key)) {
      is ServeRateLimiter.Decision.Admitted -> decision
      is ServeRateLimiter.Decision.Throttled -> {
        call.response.headers.append(HttpHeaders.RetryAfter, decision.retryAfterSeconds.toString())
        call.respondText(decision.reason, status = HttpStatusCode.TooManyRequests)
        null
      }
    }
  }

  /**
   * The upload identity of a live agent grant with [AgentGrantCapability.IMAGES], or null when the
   * call presents none (the GitHub gate then decides).
   *
   * Attribution names the grant and its approver (`agent grant 682daf65 (approved by @yschimke)`)
   * rather than borrowing a login. The budget key is the grant itself: grants are already bounded
   * (expiry, revocation, live cap), so grants don't throttle each other.
   */
  private fun RoutingContext.grantedImageIdentity(): ServeImageUploadAuth.Identity.Ok? {
    val grant = agentGrantFor(call) ?: return null
    if (!grant.allows(AgentGrantCapability.IMAGES)) return null
    return ServeImageUploadAuth.Identity.Ok(
      login = "agent grant ${grant.fingerprint} (approved by ${grant.approvedBy})",
      budgetKey = "grant:${grant.id}",
    )
  }

  /**
   * The verified identity behind this upload, or null once a refusal has been written; keeps both
   * refusal shapes in one place.
   */
  private suspend fun RoutingContext.authorizeImageUpload(
    auth: ServeImageUploadAuth
  ): ServeImageUploadAuth.Identity.Ok? {
    val header = call.request.headers[HttpHeaders.Authorization]
    val bearer = header?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }?.substring(7)
    return when (val identity = auth.identify(bearer)) {
      is ServeImageUploadAuth.Identity.Ok -> identity
      is ServeImageUploadAuth.Identity.Missing -> {
        call.response.headers.append(
          HttpHeaders.WWWAuthenticate,
          "Bearer realm=\"compose-preview\"",
        )
        call.respondText(
          "Uploading preview images requires a GitHub token with access to ${auth.repository}. " +
            "Send it as: Authorization: Bearer <token>  (e.g. a GitHub Actions job's " +
            "\$GITHUB_TOKEN, or a personal access token).",
          status = HttpStatusCode.Unauthorized,
        )
        null
      }
      is ServeImageUploadAuth.Identity.Refused -> {
        call.respondText(
          identity.reason,
          status = HttpStatusCode.fromValue(identity.status),
        )
        null
      }
    }
  }

  /**
   * `GET /i/{id}.png`: the image itself.
   *
   * Deliberately ungated, even on a token-gated host: these URLs go into PR bodies, so appending
   * the browse token would publish it, and GitHub's image proxy fetches anonymously. The 128-bit id
   * is the access control, as for `/d/<id>`.
   */
  private suspend fun RoutingContext.handleImage(store: ServeImageStore) {
    val raw = call.parameters["id"] ?: ""
    // `<id>.png` is one path segment. Split the suffix off and hand it to the store, which decides
    // whether it is the right one for what it holds.
    val dot = raw.lastIndexOf('.')
    val id = if (dot > 0) raw.substring(0, dot) else raw
    val extension = if (dot > 0) raw.substring(dot) else null
    val image = if (ServeCapabilityId.isWellFormed(id)) store.get(id, extension) else null
    if (image == null) {
      call.respondText("not found", status = HttpStatusCode.NotFound)
      return
    }
    // The id is content-addressed enough to be immutable *while it lives*: the bytes behind it
    // never change, so a proxy may keep them — but only up to the link's own expiry, never past it.
    markGeneration("image", "public, max-age=${store.remainingSeconds(image).coerceAtLeast(1)}")
    // Client-supplied bytes: pin the declared type so no browser can sniff them into something
    // active (e.g. HTML) served from this origin.
    call.response.headers.append("X-Content-Type-Options", "nosniff")
    call.respondBytes(
      image.bytes,
      ContentType.parse(ServeImageFormats.contentTypeOf(image.format, image.bytes)),
    )
  }

  /** The live document this request addresses, or null when the id is malformed/expired/unknown. */
  private fun RoutingContext.leaseDoc(store: ServeDocStore): ServeDocStore.Doc? {
    val id = call.parameters["id"] ?: return null
    return if (ServeDocStore.isWellFormedId(id)) store.get(id) else null
  }

  /**
   * Serve a vendored player bundle: ungated (generic client code), CORS-open for sandboxed iframes,
   * with a content ETag for cheap 304s.
   */
  private suspend fun RoutingContext.respondPlayerAsset(asset: PlayerAsset) {
    if (asset.bytes.isEmpty()) {
      call.respondText("not found", status = HttpStatusCode.NotFound)
      return
    }
    call.response.headers.append(HttpHeaders.AccessControlAllowOrigin, "*")
    call.response.headers.append(HttpHeaders.CacheControl, "public, max-age=3600")
    call.response.headers.append(HttpHeaders.ETag, asset.etag)
    if (call.request.headers[HttpHeaders.IfNoneMatch] == asset.etag) {
      call.respond(HttpStatusCode.NotModified)
      return
    }
    call.respondBytes(asset.bytes, ContentType.parse("text/javascript"))
  }

  /**
   * `GET /doc-player/{format}/bundle.js`: a format's vendored browser player, from the registry.
   */
  private suspend fun RoutingContext.handleDocPlayer() {
    val format = call.parameters["format"]?.let { ServeDocFormats.byId(it) }
    if (format == null) {
      call.respondText("not found", status = HttpStatusCode.NotFound)
      return
    }
    respondPlayerAsset(playerAsset(format.playerResource))
  }

  /**
   * `GET /rc-fonts/{name}`: the generated `@font-face` stylesheet ([ServeRcFonts.STYLESHEET]) or
   * one of its vendored faces; anything else 404s. Cached like the player bundles (ETag + short
   * `max-age`).
   */
  private suspend fun RoutingContext.handleRcFont() {
    val name = call.parameters["name"] ?: ""
    if (name == ServeRcFonts.STYLESHEET) {
      val css = ServeRcFonts.css()
      call.response.headers.append(HttpHeaders.AccessControlAllowOrigin, "*")
      call.response.headers.append(HttpHeaders.CacheControl, "public, max-age=3600")
      call.respondText(css, ContentType.parse("text/css; charset=utf-8"))
      return
    }
    val resource = ServeRcFonts.resourceFor(name)
    if (resource == null) {
      call.respondText("not found", status = HttpStatusCode.NotFound)
      return
    }
    val asset = playerAsset(resource)
    if (asset.bytes.isEmpty()) {
      call.respondText("not found", status = HttpStatusCode.NotFound)
      return
    }
    call.response.headers.append(HttpHeaders.AccessControlAllowOrigin, "*")
    call.response.headers.append(HttpHeaders.CacheControl, "public, max-age=3600")
    call.response.headers.append(HttpHeaders.ETag, asset.etag)
    if (call.request.headers[HttpHeaders.IfNoneMatch] == asset.etag) {
      call.respond(HttpStatusCode.NotModified)
      return
    }
    call.respondBytes(asset.bytes, ContentType.parse("font/ttf"))
  }

  /** The Google Fonts cache, present exactly when this host serves the UI builder. */
  private val googleFonts: ServeGoogleFonts? by lazy {
    val dir = uiBuilderDir ?: return@lazy null
    ServeGoogleFonts.overHttp(dir, materialSymbolsHttpClient)
  }

  /**
   * `GET /api/fonts/google/{family}/{weight}`: the family's TrueType file. 404 for an unknown
   * family or non-hundred weight, 502 when Google is unreachable; the editor then uses the default
   * face.
   */
  private suspend fun RoutingContext.handleGoogleFont() {
    // Every answer is CORS-open: the asker is a sandboxed opaque-origin frame, and a 404 without
    // the header would surface as a CORS error.
    call.response.headers.append(HttpHeaders.AccessControlAllowOrigin, "*")
    val fonts = googleFonts
    val family = call.parameters["family"].orEmpty()
    val weight = call.parameters["weight"]?.removeSuffix(".ttf")?.toIntOrNull()
    if (fonts == null || weight == null || fonts.canonical(family) == null) {
      call.respondText("not found", status = HttpStatusCode.NotFound)
      return
    }
    val bytes =
      try {
        withContext(Dispatchers.IO) { fonts.font(family, weight) }
      } catch (e: Exception) {
        call.respondText(
          "could not fetch $family: ${e.message}",
          status = HttpStatusCode.BadGateway,
        )
        return
      }
    if (bytes == null) {
      call.respondText("not found", status = HttpStatusCode.NotFound)
      return
    }
    call.response.headers.append(HttpHeaders.CacheControl, "public, max-age=604800")
    call.respondBytes(bytes, ContentType.parse("font/ttf"))
  }

  /** The Noto fallback cache, present exactly when this host serves the UI builder. */
  private val notoFallbackFonts: ServeNotoFallbackFonts? by lazy {
    val dir = uiBuilderDir ?: return@lazy null
    ServeNotoFallbackFonts.overHttp(dir, materialSymbolsHttpClient)
  }

  /**
   * `GET /api/fonts/noto/{family}/v{n}/{file}.woff2`: the Noto slice Compose's text fallback would
   * fetch from `fonts.gstatic.com/s/` at that path. 404 for paths not in Compose's list, 502 when
   * Google is unreachable.
   */
  private suspend fun RoutingContext.handleNotoFallbackFont() {
    // CORS-open on every answer, for the same sandboxed runtime frames as [handleGoogleFont].
    call.response.headers.append(HttpHeaders.AccessControlAllowOrigin, "*")
    val fonts = notoFallbackFonts
    val path = call.parameters.getAll("path").orEmpty().joinToString("/")
    if (fonts == null || !fonts.knows(path)) {
      call.respondText("not found", status = HttpStatusCode.NotFound)
      return
    }
    val bytes =
      try {
        withContext(Dispatchers.IO) { fonts.font(path) }
      } catch (e: Exception) {
        call.respondText("could not fetch $path: ${e.message}", status = HttpStatusCode.BadGateway)
        return
      }
    if (bytes == null) {
      call.respondText("not found", status = HttpStatusCode.NotFound)
      return
    }
    // A slice's path names its version, so its bytes never change.
    call.response.headers.append(HttpHeaders.CacheControl, "public, max-age=31536000, immutable")
    call.respondBytes(bytes, ContentType.parse("font/woff2"))
  }

  /** `GET /assets/serve/{version}/{name}`: static ServeWeb CSS/JS extracted from raw strings. */
  private suspend fun RoutingContext.handleServeWebAsset(versioned: Boolean) {
    val name = call.parameters["name"] ?: ""
    val asset = ServeWebAssets.load(name)
    val version = call.parameters["version"]
    if (asset == null || (versioned && version != asset.version)) {
      call.respondText("not found", status = HttpStatusCode.NotFound)
      return
    }
    call.response.headers.append(HttpHeaders.AccessControlAllowOrigin, "*")
    call.response.headers.append(
      HttpHeaders.CacheControl,
      if (versioned) "public, max-age=31536000, immutable" else "no-cache",
    )
    call.response.headers.append(HttpHeaders.ETag, asset.etag)
    if (call.request.headers[HttpHeaders.IfNoneMatch] == asset.etag) {
      call.respond(HttpStatusCode.NotModified)
      return
    }
    call.respondBytes(asset.bytes, ContentType.parse(asset.contentType))
  }

  /**
   * Describe the work behind a response in proxy-surviving headers, so clients can tell published
   * bytes from a daemon render. Static pages are cacheable only in public mode; token-bearing
   * private URLs must never reach a shared cache.
   */
  private fun ApplicationCall.markGeneration(generation: String, cacheControl: String? = null) {
    response.headers.append(GENERATION_HEADER, generation)
    cacheControl?.let { response.headers.append(HttpHeaders.CacheControl, it) }
    // Backup for `private, no-store`: an intermediary that under-honours it still learns the body
    // depends on the session cookie.
    // Load-bearing for ANON_PAGE_CACHE_CONTROL, which invites shared caching: without `Vary:
    // Cookie` a cache would serve an anonymous rendering to a signed-in visitor.
    if (cacheControl == SIGNED_IN_PAGE_CACHE_CONTROL || cacheControl == ANON_PAGE_CACHE_CONTROL) {
      varyOnCookie()
    }
  }

  private fun RoutingContext.markGeneration(generation: String, cacheControl: String? = null) =
    call.markGeneration(generation, cacheControl)

  private fun incrementPreviewViews(
    sessionId: String,
    previewId: String,
  ): ServeWeb.PreviewEngagement =
    ServeWeb.PreviewEngagement(engagementStore.incrementPreview(sessionId, previewId))

  private fun previewEngagement(sessionId: String, previews: List<ServePreview>) =
    engagementStore.previewViews(sessionId, previews.map { it.id }).mapValues {
      ServeWeb.PreviewEngagement(it.value)
    }

  /** `GET /` (query) and `GET /{system}[/]` (path): the session's preview-list landing page. */
  private suspend fun RoutingContext.handleLanding(sessionInPath: Boolean) {
    if (rejectBadToken()) return
    // Front door: with design-system catalogs, the bare `/` is an index of the systems; a plain
    // `serve` keeps the module landing. `?session=` or a `/<system>` path selects a session's
    // landing below.
    // …except on a top-level site host, whose `/` is its catalog's landing.
    if (
      !sessionInPath &&
        siteSystem() == null &&
        (listedCatalogs().isNotEmpty() || unlistedCatalogs().isNotEmpty()) &&
        call.request.queryParameters["session"] == null
    ) {
      handleHomeIndex()
      return
    }
    val (webSessionId, basePath) = webSessionAndBase(sessionInPath)
    val selectedSessionId = selectedSessionId(sessionInPath)
    loadingHomeSystem(selectedSessionId)?.let { pending ->
      catalogLoads?.prioritize(selectedSessionId)
      call.response.headers.append(HttpHeaders.CacheControl, "no-store")
      call.response.headers.append(HttpHeaders.RetryAfter, "3")
      call.response.headers.append("Refresh", "3")
      call.respondText(
        ServeWeb.homeIndexPage(
          listOf(pending),
          linkToken(),
          isPublic,
          version = SERVE_VERSION,
          componentBrowser = componentBrowserMode(),
        ),
        ContentType.Text.Html,
        HttpStatusCode.ServiceUnavailable,
      )
      return
    }
    withLeasedSession(
      selectedSessionId,
      onMissing = { respondNotFoundHtml("That design system was not found on this server.") },
    ) { renderHost ->
      val systemViews =
        if (isViewRequest()) engagementStore.incrementSystem(selectedSessionId)
        else engagementStore.systemViews(listOf(selectedSessionId)).getValue(selectedSessionId)
      val gridPreviews = landingPreviews(renderHost)
      val heroId =
        catalogBundleHost(renderHost)?.declaredHeroPreviewId
          ?: ServeWeb.representativePreviewId(gridPreviews)
      val heroUrl = heroId?.let {
        externalOrigin() +
          basePath +
          "/render/${WebEscaping.urlEncodeSegment(it)}.png" +
          requestQuerySuffix()
      }
      // Read from the PNG header so the unfurl can declare `og:image:width`/`height`. Skipped with
      // overrides, since the image would then be a re-render at an unknown size.
      val heroSize =
        if (requestCarriesOverrides()) null else heroId?.let { renderHost.bakedRenderSize(it) }
      // Prefer a drawn 1200×630 card ([ServeSocialCard]) of this catalog's hero with its name and
      // count, as [handleHomeIndex] does. Skipped with overrides. Baked here (memoised per host via
      // [ServeHeroImages.heroFor]) so a shared `/<system>/` URL gets a card without visiting the
      // front door first.
      val bundle = catalogBundleHost(renderHost)
      val heading = ServeWeb.catalogHeading(bundle?.title, renderHost.label)
      // Hoisted out of the argument list because the header's sign-in control reads it too: a
      // catalog with neither a live lane nor a reachable playground has nothing behind a login.
      val catalogPlaygroundHref = playgroundLinkForCatalog(selectedSessionId)
      val card =
        if (requestCarriesOverrides() || bundle == null || heroId == null) null
        else
          withContext(Dispatchers.IO) {
            heroImages.heroFor(bundle, heroId, bundle.contentCrop(heroId))?.let { hero ->
              socialCards.cardFor(
                ServeSocialCard.Spec(
                  title = heading,
                  subtitle = ServeWeb.catalogCardSubtitle(gridPreviews.size),
                  heroes = listOf(hero),
                  system = selectedSessionId,
                )
              )
            }
          }
      val unfurl =
        if (card != null)
          ServeWeb.UnfurlMetadata(
            pageUrl = externalPageUrl(),
            imageUrl = externalOrigin() + ServeSocialCard.PATH_PREFIX + "/" + card.fileName,
            imageWidth = card.width,
            imageHeight = card.height,
          )
        else
        // No baked hero yet, or overrides present: use the render; `twitterCard` demotes it to the
        // card its shape fits.
        ServeWeb.UnfurlMetadata(
            pageUrl = externalPageUrl(),
            imageUrl = heroUrl,
            imageWidth = heroSize?.first,
            imageHeight = heroSize?.second,
          )
      val lanCard = operatorLanCard()
      markGeneration("static-page", if (lanCard != null) "no-store" else pageCacheControl())
      respondWithLanCard(
        lanCard,
        ServeWeb.landingPage(
          renderHost.label,
          gridPreviews,
          linkToken(),
          webSessionId,
          trust = catalogBundleHost(renderHost)?.let { BundleVerifier.summary(it.trust) },
          isPublic = isPublic,
          componentBrowser = componentBrowserMode(),
          // A back-to-home button whenever this server has a front-door index (listed or unlisted
          // catalogs, as in handleLanding).
          // …but never on a top-level site, which has no front door.
          hasHomeIndex =
            siteSystem() == null &&
              (listedCatalogs().isNotEmpty() || unlistedCatalogs().isNotEmpty()),
          basePath = basePath,
          changelogHref = changelogHref(selectedSessionId, basePath, webSessionId),
          // One action per comparable format, gated on the same condition `comparisonPage` uses.
          // …without waking a daemon; see [ServeCatalogLiveHost.hasSvgExportWithoutWaking].
          hasSvgComparison =
            renderHost.previews.any {
              (renderHost as? ServeCatalogLiveHost)?.hasSvgExportWithoutWaking(it.id)
                ?: renderHost.hasSvgExportFor(it.id)
            },
          hasRcComparison =
            renderHost.rcCompare() != null ||
              renderHost.previews.any { renderHost.hasRemoteComposeDoc(it.id) },
          // Same condition `comparisonPage` enables the `reference` format on.
          hasReferenceComparison =
            renderHost.previews.any { renderHost.designReferencesFor(it.id).isNotEmpty() },
          // Same condition `handleParity` serves on.
          hasParityView =
            renderHost.parityActivity() != null ||
              renderHost.parityIssues() != null ||
              renderHost.knownDifferences() != null ||
              renderHost.previews.any { renderHost.designReferencesFor(it.id).isNotEmpty() },
          // The paired implementation from the same source the wall reads (`parallelSpecSource`),
          // so chip and format agree.
          parallelComparisonLabel =
            renderHost.previews.firstNotNullOfOrNull { parallelSpecSource(renderHost, it)?.label },
          // Scoped to the served system; the index is identical across a repository's catalogs. See
          // [ServeWeb.issuesForSystem].
          parityIssues =
            ServeWeb.issuesForSystem(
              renderHost.parityIssues()?.issues.orEmpty(),
              selectedSessionId,
            ),
          // Same count `handleMotionIndex` gates on, so the chip never leads to that route's 404.
          motionCaptureCount = renderHost.previews.sumOf { it.motion.size },
          // Same condition `handleDesignPageIndex` serves on; names are needed for the navigation
          // tree.
          designPages =
            renderHost.designPages().pages.map { page ->
              ServeWeb.PageLink(page.id, page.name, designPageSections(page))
            },
          // Name that action after the design tool, read from the published references (or the
          // parity feed's Figma lane for provider-less rasters). Null ⇒ "design parity".
          designToolLabel =
            renderHost.previews.firstNotNullOfOrNull { preview ->
              renderHost.designReferencesFor(preview.id).firstNotNullOfOrNull {
                ServeWeb.designToolLabel(it.source.provider)
              }
            } ?: renderHost.parityActivity()?.figma?.let { "Figma" },
          version = SERVE_VERSION,
          // Catalog provenance (delivery branch, generation date, tool versions) for the strip
          // under the header; null for a plain (non-catalog) module session.
          provenance = catalogBundleHost(renderHost)?.provenance,
          refreshUrl =
            if (catalogRefresh != null) "$basePath/refresh${requestQuerySuffix()}" else null,
          // "try in playground": opens the editor with this design system preselected. Omitted
          // without the lane; per-preview handoff is the viewer's `playgroundHref`.
          playgroundHref = catalogPlaygroundHref,
          // Crop each card's thumbnail to the component's figma-svg content box (cheap baked
          // reads).
          thumbCrop = { id -> catalogBundleHost(renderHost)?.contentCrop(id) },
          // Point each card at a prebaked, downscaled copy where one can be baked from local pixels
          // ([ServeHeroImages.gridThumbFor]). Runs per card on the request thread, so it must never
          // fetch.
          thumbHash = { id ->
            heroImages
              .gridThumbFor(renderHost, id, catalogBundleHost(renderHost)?.contentCrop(id))
              ?.hash
              // On a miss the card uses the full-resolution URL; request the fetch off-thread so
              // the next page build can thumbnail it.
              .also { if (it == null) thumbWarmer.enqueue(renderHost, id) }
          },
          // A heartbeat keeps the session and daemon alive while the tab is open; the cached cards
          // may make no requests at all.
          presenceUrl = "$basePath/api/presence${requestQuerySuffix()}",
          // The catalog's declared stage surface (`display.surface`), so a dark-first system's
          // unthemed cards sit on the dark stage instead of the default white.
          declaredSurface = catalogBundleHost(renderHost)?.stageSurface,
          // …and its own colour palette, so this system's pages are framed in its colours.
          themeCss = catalogBundleHost(renderHost)?.webThemeCss.orEmpty(),
          // Why the catalog is snapshot-only, if it is, shown as a banner under the header.
          degradations = renderHost.degradations,
          // Declared @ThemeCatalog themes join the Theme control; only daemon-twinned cards can
          // re-render, hence the per-preview predicate.
          declaredThemes = applicableThemes(renderHost),
          canRenderThemeFor = { id -> renderHost.canRenderOverridesFor(id) },
          // A twin that replays a captured document can't honour a theme provider either (409);
          // same predicate as the refusal and the viewer's `irReplay`.
          // A replayed card is theme-overridable only when it can apply every theme this page
          // offers. In a mixed catalog the chips are the union, so a card mapped for only some
          // would 409.
          irReplayFor = { id ->
            isReplayedPreview(renderHost, id) && !everyThemeApplies(renderHost, id)
          },
          // Long-press to open a live session in a card: the session offers the stream and the
          // preview has a daemon twin, as for the viewer's Live toggle.
          canStreamLiveFor = { id ->
            renderHost.hasLiveStream && renderHost.canRenderOverridesFor(id)
          },
          // …and when the box gates its live lanes on GitHub, the press answers with the sign-in
          // rather than a socket that would close 1008.
          liveSignInHref =
            githubAuth
              ?.takeIf { renderHost.hasLiveStream }
              ?.takeUnless { it.isAuthenticated(call) }
              ?.takeIf { oauthCanRoundTrip() }
              ?.loginPath(call),
          themeRenderBurstCapacity = renderHost.themeRenderBurstCapacity,
          engagement = previewEngagement(selectedSessionId, renderHost.previews),
          systemViews = systemViews,
          unfurl = unfurl,
          displayTitle = bundle?.title,
          // A top-level site's pages carry their session in the ORIGIN, so same-session links
          // drop the `?session=` the rooted legacy form would add. See [ServeSites].
          sessionInOrigin = siteSystem() != null,
          // The header's sign-in control: on a site host this landing is the front door, and the
          // long-press route to sign-in (`liveSignInHref`) is undiscoverable.
          //
          // Only where a login unlocks something here (a live lane, or a playground compiling
          // against this catalog); otherwise it would be a dead affordance. The lane it names is
          // the one this catalog has: with no live stream it is the playground, whose gate is
          // repository access. The front door keeps its unconditional control.
          githubAuth =
            when {
              renderHost.hasLiveStream -> githubAuthStatus()
              catalogPlaygroundHref != null -> githubAuthStatus(ServeWeb.GatedLane.PLAYGROUND)
              else -> null
            },
          // The page-scoped catalog report on the landing — the launcher's catalog half in Dev and
          // Catalog mode's only reporting affordance.
          reportIssue = pageScopedReportIssue(renderHost, selectedSessionId, "this catalog"),
        ),
        ContentType.Text.Html,
      )
    }
  }

  /** Join this page to its catalog's short-lived themed-thumbnail burst allocation. */
  private suspend fun RoutingContext.handleThemeRenderLease(sessionInPath: Boolean) {
    if (rejectBadToken()) return
    // This route only grants permission for a burst of live theme renders, so refuse a `preview`
    // grant up front rather than lease and then refuse every render. Release is deliberately
    // ungated.
    if (rejectGrantBelowScope(AgentGrantScope.LIVE, api = true)) return
    if (rejectForeignSessionRequest()) return
    val sessionId = selectedSessionId(sessionInPath)
    withLeasedSession(sessionId) { renderHost ->
      val grant =
        themeRenderLeases.acquire(
          sessionId = sessionId,
          hostIdentity = renderHost,
          requestedCapacity = renderHost.themeRenderBurstCapacity,
        )
      if (grant == null) {
        call.response.headers.append(HttpHeaders.RetryAfter, "2")
        call.respondText("theme render burst unavailable", status = HttpStatusCode.Conflict)
      } else {
        call.respondText(
          """{"lease":"${grant.token}","concurrency":${grant.concurrency},"expiresAt":${grant.expiresAtMillis}}""",
          ContentType.Application.Json,
        )
      }
    }
  }

  /**
   * `POST /api/presence` (and its `/{system}/` form): a heartbeat from an open catalog tab.
   *
   * Sessions are reaped after an idle window ([ServeSessionRegistry.DEFAULT_IDLE_TIMEOUT_MILLIS])
   * measured in requests, and a reader of cached pages makes none. Leasing the session keeps it
   * (and its daemon) resident, resuming it if suspended; [ServeHost.keepLiveWarm] then readies the
   * live lane for a visitor who has only seen baked pixels.
   *
   * Silent: 204 with no body; a failed heartbeat just retries next time.
   */
  private suspend fun RoutingContext.handlePresence(sessionInPath: Boolean) {
    if (rejectBadToken()) return
    // `keepLiveWarm()` below starts or retains a daemon — the definition of `live`, however small
    // each individual ping looks.
    if (rejectGrantBelowScope(AgentGrantScope.LIVE, api = true)) return
    if (rejectForeignSessionRequest()) return
    withLeasedSession(
      selectedSessionId(sessionInPath),
      onMissing = { call.respond(HttpStatusCode.NoContent) },
    ) { renderHost ->
      // Only the warm is gated on the live-seat budget (a browsing convenience ranks below real
      // requests); it takes no seat itself. The lease above is unconditional.
      if (liveSeats.availablePermits() > 0) renderHost.keepLiveWarm()
      call.respond(HttpStatusCode.NoContent)
    }
  }

  /** Best-effort page/queue release; fixed lease expiry remains authoritative. */
  private suspend fun RoutingContext.handleThemeRenderLeaseRelease() {
    if (rejectBadToken()) return
    val lease = call.request.queryParameters["lease"]
    if (lease.isNullOrBlank()) {
      call.respondText("missing lease", status = HttpStatusCode.BadRequest)
      return
    }
    themeRenderLeases.release(lease)
    call.respond(HttpStatusCode.NoContent)
  }

  /** `GET /compare` and `GET /{system}/compare`: native format-fidelity comparison gallery. */
  private suspend fun RoutingContext.handleFormatComparison(sessionInPath: Boolean) {
    if (rejectBadToken()) return
    val sessionId = selectedSessionId(sessionInPath)
    val (webSessionId, basePath) = webSessionAndBase(sessionInPath)
    withLeasedSession(
      sessionId,
      onMissing = { respondNotFoundHtml("That design system was not found on this server.") },
    ) { renderHost ->
      // Resolve each cross-catalog source once; the wall asks repeatedly, and a sibling
      // disappearing mid-response could otherwise leave a button whose rows lack a source.
      val pairedDesignSources =
        renderHost.previews.associateWith { preview ->
          if (renderHost.designReferencesFor(preview.id).isEmpty())
            pairedDesignSpecSource(renderHost, preview)
          else null
        }
      val parallelSources =
        renderHost.previews.associateWith { preview -> parallelSpecSource(renderHost, preview) }
      // The published player comparison is itself a comparable format: a catalog can carry it even
      // where the per-preview `.rc` sidecars didn't make it into the served staging dir.
      val rcCompare = renderHost.rcCompare()
      val comparable =
        rcCompare != null ||
          renderHost.previews.any { preview ->
            renderHost.hasSvgExportFor(preview.id) ||
              renderHost.hasRemoteComposeDoc(preview.id) ||
              renderHost.designReferencesFor(preview.id).isNotEmpty() ||
              pairedDesignSources[preview] != null ||
              parallelSources[preview] != null
          }
      if (!comparable) {
        respondNotFoundHtml("This session has no native formats or design references to compare.")
        return@withLeasedSession
      }
      // Uncacheable while the player comparison is still staging, since the page's shape depends on
      // a manifest that lands asynchronously.
      markGeneration(
        "static-page",
        if (renderHost.rcComparePending()) DYNAMIC_RESOURCE_CACHE_CONTROL else pageCacheControl(),
      )
      // The wall's page-scoped "report a catalog issue", so the launcher has a catalog half here.
      // It carries the page (with its lane), catalog build and tool version, without preview rows;
      // a single row's defect is better reported from its focused comparison.
      val reportIssue =
        pageScopedReportIssue(renderHost, sessionId, "these comparisons", pickable = true)
      call.respondText(
        ServeWeb.comparisonPage(
          moduleLabel = renderHost.label,
          previews = renderHost.previews,
          token = linkToken(),
          sessionId = webSessionId,
          basePath = basePath,
          changelogHref = changelogHref(sessionId, basePath, webSessionId),
          isPublic = isPublic,
          trust = catalogBundleHost(renderHost)?.let { BundleVerifier.summary(it.trust) },
          declaredSurface = catalogBundleHost(renderHost)?.stageSurface,
          // …and its own colour palette, so this system's pages are framed in its colours.
          themeCss = catalogBundleHost(renderHost)?.webThemeCss.orEmpty(),
          hasSvgFor = renderHost::hasSvgExportFor,
          hasRemoteComposeFor = renderHost::hasRemoteComposeDoc,
          rcCompare = rcCompare,
          // …and the players this host can draw itself, for the columns that run did not publish.
          liveRcPlayersFor = renderHost::enabledRcPlayersFor,
          referencesFor = renderHost::designReferencesFor,
          pairedDesignSourceFor = { pairedDesignSources[it] },
          parallelSourceFor = { parallelSources[it] },
          unfurl = ServeWeb.UnfurlMetadata(pageUrl = externalPageUrl()),
          reportIssue = reportIssue,
          generation = catalogGeneration(renderHost),
          // This catalog's whole index, unfiltered except by system: the wall joins per row itself,
          // but cannot tell sibling catalogs from one repository apart. See
          // [ServeWeb.issuesForSystem].
          parityIssues =
            ServeWeb.issuesForSystem(renderHost.parityIssues()?.issues.orEmpty(), sessionId),
          // The index's own stamp, so an opened Bugs panel can say what its `closed` is as of. The
          // scope filter above does not touch it: `generatedAt` describes the publish, not a row.
          parityIssuesGeneratedAt = renderHost.parityIssues()?.generatedAt,
          version = SERVE_VERSION,
          displayTitle = catalogBundleHost(renderHost)?.title,
          // A top-level site's pages carry their session in the ORIGIN, so same-session links
          // drop the `?session=` the rooted legacy form would add. See [ServeSites].
          sessionInOrigin = siteSystem() != null,
        ),
        ContentType.Text.Html,
      )
    }
  }

  /**
   * `GET /<system>/parity`: the catalog's design-parity dashboard — coverage, the merged code ↔
   * Figma activity feed, and mapping gaps.
   *
   * Coverage is computed live, so any session with references or a feed gets a page; only a session
   * with neither 404s. `?format=json` returns the same dashboard as data (computed in
   * [ServeParityDashboard]).
   */
  private suspend fun RoutingContext.handleParity(sessionInPath: Boolean, json: Boolean) {
    if (rejectBadToken()) return
    @Suppress("NAME_SHADOWING") val json = json || wantsJson()
    if (rejectUnknownFormat()) return
    val sessionId = selectedSessionId(sessionInPath)
    val (webSessionId, basePath) = webSessionAndBase(sessionInPath)
    withLeasedSession(
      sessionId,
      onMissing = {
        if (json) call.respond(HttpStatusCode.NotFound)
        else respondNotFoundHtml("That design system was not found on this server.")
      },
    ) { renderHost ->
      val activity = renderHost.parityActivity()
      val hasReference = { id: String -> renderHost.designReferencesFor(id).isNotEmpty() }
      val mapped = renderHost.previews.any { hasReference(it.id) }
      val issues = renderHost.parityIssues()?.issues.orEmpty()
      // The bands this catalog draws, scoped to the system on the mount; `issues` stays whole for
      // the acceptance walk's lifecycle join. See [ServeWeb.issuesForSystem].
      val systemIssues = ServeWeb.issuesForSystem(issues, sessionId)
      // A published known-difference document keeps the page reachable on its own, since a catalog
      // whose acceptances are all `orphaned-target` is exactly the finding.
      // HTML only: `ParityResponse` can't represent acceptances, so a JSON answer would be a
      // dashboard of zeroes that reads as "fine" to CI.
      val accepts = renderHost.knownDifferences() != null
      // The dashboard is an index: each component links into the comparison wall scoped to it, the
      // activity feed is folded behind a disclosure, and the landing lists it under `Reports`
      // (`docs/design/COMPARE_NAVIGATION.md`, §3.4).
      //
      // The gate reads the system-scoped list, so a catalog whose only rows belong to a sibling has
      // nothing here.
      if (activity == null && !mapped && systemIssues.isEmpty() && (json || !accepts)) {
        if (json) call.respond(HttpStatusCode.NotFound)
        else
          respondNotFoundHtml(
            "This session publishes no design references and no parity activity feed."
          )
        return@withLeasedSession
      }
      val dashboard =
        ServeParityDashboard.build(
          previews = renderHost.previews,
          hasReference = hasReference,
          activity = activity,
          referenceIdFor = { id -> renderHost.designReferencesFor(id).firstOrNull()?.id },
        )
      if (json) {
        markGeneration("parity", pageCacheControl())
        call.respondText(
          JSON.encodeToString(
            ParityResponse.serializer(),
            ParityResponse.of(dashboard, systemIssues),
          ),
          ContentType.Application.Json,
        )
        return@withLeasedSession
      }
      // The audit-bearing page is not cacheable: the inventory and issue rows are baked into the
      // HTML while the document is fetched live (`no-store`), so a cached page after a refresh
      // would report false `orphaned-target` findings. There is no generation anchor for a
      // catalog-wide walk.
      markGeneration(
        "static-page",
        if (accepts) DYNAMIC_RESOURCE_CACHE_CONTROL else pageCacheControl(),
      )
      call.respondText(
        ServeWeb.parityPage(
          moduleLabel = renderHost.label,
          dashboard = dashboard,
          token = linkToken(),
          sessionId = webSessionId,
          basePath = basePath,
          changelogHref = changelogHref(sessionId, basePath, webSessionId),
          isPublic = isPublic,
          trust = catalogBundleHost(renderHost)?.let { BundleVerifier.summary(it.trust) },
          themeCss = catalogBundleHost(renderHost)?.webThemeCss.orEmpty(),
          unfurl = ServeWeb.UnfurlMetadata(pageUrl = externalPageUrl()),
          version = SERVE_VERSION,
          displayTitle = catalogBundleHost(renderHost)?.title,
          hasReferenceFor = hasReference,
          parityIssues = systemIssues,
          parityIssuesGeneratedAt = renderHost.parityIssues()?.generatedAt,
          // Unscoped: an acceptance may cite an issue filed against a sibling system, and the join
          // reads state by URL. See [ServeWeb.issuesForSystem].
          acceptanceIssues = issues,
          generation = catalogGeneration(renderHost),
          // The catalog-wide acceptance walk, only for a catalog publishing a known-difference
          // document. Fields are spelled as the comparison page's locator does (`system` from the
          // mount, `component` and `variant` from [ServeIssueReport]), since acceptances match on
          // all of them. The page builds the URLs ([KnownDifferenceScope]).
          acceptanceAudit =
            renderHost.knownDifferences()?.let {
              renderHost.previews.map { preview ->
                KnownDifferenceCatalogPreview(
                  // The resolved session id, not the base path segment: they differ for names with
                  // escaped characters (`@` → `%40`), and the identity must not change with the
                  // route form.
                  system = sessionId,
                  id = preview.id,
                  component = ServeIssueReport.componentIdFor(preview),
                  variant = ServeIssueReport.variantFor(preview),
                  referenceIds = renderHost.designReferencesFor(preview.id).map { it.id },
                )
              }
            },
          // Same derivation the landing uses to label its "compare to Figma" action, so the page a
          // visitor arrives on names the tool the same way the link that brought them here did.
          designToolLabel =
            renderHost.previews.firstNotNullOfOrNull { preview ->
              renderHost.designReferencesFor(preview.id).firstNotNullOfOrNull {
                ServeWeb.designToolLabel(it.source.provider)
              }
            } ?: activity?.figma?.let { "Figma" },
          // A top-level site's pages carry their session in the ORIGIN, so same-session links
          // drop the `?session=` the rooted legacy form would add. See [ServeSites].
          sessionInOrigin = siteSystem() != null,
        ),
        ContentType.Text.Html,
      )
    }
  }

  /**
   * Refuse a request whose `at=` is present but not a commit sha. Ignoring it would answer a
   * request for a specific publish with current bytes. A ref is not a pin
   * ([ServeCatalogRevision.normalize]).
   */
  private suspend fun RoutingContext.rejectMalformedPin(): Boolean {
    val raw = call.request.queryParameters[ServeCatalogRevision.PARAM] ?: return false
    if (ServeCatalogRevision.normalize(raw) != null) return false
    call.respondText(
      "'${ServeCatalogRevision.PARAM}' must be a commit sha (7-40 hex), not a branch or tag",
      status = HttpStatusCode.BadRequest,
    )
    return true
  }

  /**
   * Refuse a request whose `gen=` is present but not a commit sha, as [rejectMalformedPin] does; a
   * 400 shows up in logs.
   */
  private suspend fun RoutingContext.rejectMalformedGeneration(): Boolean {
    val raw = call.request.queryParameters[ServeCacheGeneration.PARAM] ?: return false
    if (ServeCacheGeneration.normalize(raw) != null) return false
    call.respondText(
      "'${ServeCacheGeneration.PARAM}' must be a commit sha (7-40 hex), not a branch or tag",
      status = HttpStatusCode.BadRequest,
    )
    return true
  }

  /**
   * The delivery-branch commit this session serves — the generation its pages' frame URLs are
   * scoped to ([ServeCacheGeneration]) — or null (uploaded bundle, local project, daemon module).
   * [ServeBundleHost.supportsPinnedRevisions] is load-bearing: scoping commits the asset lane to
   * serving older generations from the branch, which an unpinnable host cannot do.
   */
  private fun catalogGeneration(renderHost: ServeHost): String? =
    catalogBundleHost(renderHost)
      ?.takeIf { it.supportsPinnedRevisions }
      ?.provenance
      ?.commit
      ?.let(ServeCacheGeneration::normalize)

  /**
   * The generation a request names when it is not the one this host serves (the page is a publish
   * behind); null otherwise. Returning the sha lets the caller reuse the pin path, so stale
   * generations follow the same fetch rules.
   */
  /**
   * Whether this request named the generation the host is serving, so the response may be marked
   * immutable. Absent `gen=` doesn't count: an unscoped URL is a moving target.
   */
  private fun RoutingContext.carriesCurrentGeneration(renderHost: ServeHost): Boolean {
    val asked =
      ServeCacheGeneration.normalize(call.request.queryParameters[ServeCacheGeneration.PARAM])
        ?: return false
    return asked == catalogGeneration(renderHost)
  }

  private fun RoutingContext.staleGeneration(renderHost: ServeHost): String? {
    val asked =
      ServeCacheGeneration.normalize(call.request.queryParameters[ServeCacheGeneration.PARAM])
        ?: return null
    return asked.takeIf { it != catalogGeneration(renderHost) }
  }

  /**
   * The revision state for a catalog page: the request's pin and the branch's recent publishes
   * ([ServeWeb.CatalogRevisions]).
   *
   * A pin is honoured whether or not it is in that list (the feed only covers about a dozen
   * publishes); the asset lanes decide by asking the branch. Sessions without a delivery branch get
   * no revision surface.
   */
  private fun RoutingContext.catalogRevisions(
    renderHost: ServeHost,
    previewId: String? = null,
  ): ServeWeb.CatalogRevisions {
    val host = catalogBundleHost(renderHost) ?: return ServeWeb.CatalogRevisions.NONE
    if (!host.supportsPinnedRevisions) return ServeWeb.CatalogRevisions.NONE
    return ServeWeb.CatalogRevisions(
      pinned =
        ServeCatalogRevision.normalize(call.request.queryParameters[ServeCatalogRevision.PARAM]),
      revisions = if (previewId == null) host.revisions else availableRevisions(host, previewId),
      repo = host.provenance?.repo,
      // The publish this page is assembled from: pinned pages' frames take the pin, unpinned ones
      // take this ([ServeWeb.assetQuery]).
      generation = catalogGeneration(renderHost),
    )
  }

  /**
   * Catalog publishes as they apply to one preview, newest first.
   *
   * The feed is catalog-wide, so commits from before the preview existed are dropped using the
   * compact preview index the publisher rolls forward (an in-memory lookup). A missing index fails
   * open for older publishers; once one exists, an unindexed commit (e.g. a parity-issue refresh)
   * is omitted.
   */
  private fun availableRevisions(
    host: ServeBundleHost,
    previewId: String,
  ): List<ServeCatalogRevision.Revision> {
    // The feed head is the immutable tree this host loaded. Without it there is no trustworthy
    // "current" revision to lead the menu, so preserve the pre-index behaviour of drawing none.
    val current = host.revisions.firstOrNull() ?: return emptyList()
    val fromFeed =
      host.revisions.filterIndexed { index, revision ->
        index == 0 || host.revisionContainsPreview(revision.commit, previewId) != false
      }
    // Merge history.json's distinct image versions with the branch feed's rows, sort by ISO publish
    // date, and prefer the feed's record when a commit appears in both.
    val tail =
      (host.indexedPreviewRevisions(previewId) + fromFeed)
        .associateBy { it.commit }
        .values
        .filterNot { it.commit == current.commit }
        .sortedByDescending { it.date }
        .take(ServeCatalogRevision.MAX_REVISIONS - 1)
    return listOf(current) + tail
  }

  /**
   * Answer one pinned image request: the published bytes at a delivery-branch commit, or a 404
   * saying why there are none. `(commit, path)` is immutable, so the response is too, under the
   * same public/private split as other content-addressed lanes ([prebakedImageCacheControl]); on a
   * token-gated box the URL carries the token, so shared proxies must not keep it.
   */
  private suspend fun RoutingContext.respondPinnedAsset(
    outcome: ServeBundleHost.PinnedOutcome?,
    missing: String,
    /** Applied to the bytes on the way out, for the render lane's `?bg=`; identity elsewhere. */
    transform: suspend (ByteArray) -> ByteArray = { it },
  ) {
    when (outcome) {
      is ServeBundleHost.PinnedOutcome.Ok -> {
        markGeneration("pinned-asset", prebakedImageCacheControl(isPublic))
        call.respondBytes(transform(outcome.bytes), ContentType.Image.PNG)
      }
      // A shed request answers 503 + Retry-After so link checkers and visitors don't conclude the
      // revision is gone.
      ServeBundleHost.PinnedOutcome.Busy -> {
        call.response.headers.append(HttpHeaders.RetryAfter, "5")
        call.respondText(
          "busy reading that revision from the delivery branch; try again shortly",
          status = HttpStatusCode.ServiceUnavailable,
        )
      }
      else -> call.respondText(missing, status = HttpStatusCode.NotFound)
    }
  }

  /** Canonical, inert PNG for a design reference. Original HTML/Figma sources are never served. */
  private suspend fun RoutingContext.handleDesignReferenceAsset(sessionInPath: Boolean) {
    if (rejectBadToken() || rejectMalformedPin() || rejectMalformedGeneration()) return
    val sessionId = selectedSessionId(sessionInPath)
    val name = call.parameters["name"].orEmpty()
    val uid = name.endsWith(".uid") || name.endsWith(".html")
    val referenceId = name.substringBeforeLast('.')
    val requestedPin =
      ServeCatalogRevision.normalize(call.request.queryParameters[ServeCatalogRevision.PARAM])
    withLeasedSession(sessionId, onMissing = { call.respond(HttpStatusCode.NotFound) }) { renderHost
      ->
      if (uid) {
        // Never silently open today's design under a historical comparison. The source-commit
        // link remains available; serving historical documents needs the historical manifest too.
        if (requestedPin != null || staleGeneration(renderHost) != null) {
          call.respondText(
            "This design snapshot is no longer the current publish. Open its commit-pinned .uid source.",
            status = HttpStatusCode.Conflict,
          )
          return@withLeasedSession
        }
        val snapshot = catalogBundleHost(renderHost)?.uidReference(referenceId)
        if (snapshot == null) {
          call.respond(HttpStatusCode.NotFound)
          return@withLeasedSession
        }
        val (reference, bytes) = snapshot
        val expected = call.request.queryParameters["sha"]
        if (
          (name.endsWith(".html") || expected != null) &&
            expected != reference.source.attributes["documentSha256"]
        ) {
          call.respondText(
            "This design reference is unpinned or has changed. Reopen the comparison to review the current publish.",
            status = HttpStatusCode.Conflict,
          )
          return@withLeasedSession
        }
        call.response.headers.append(HttpHeaders.CacheControl, "no-store")
        if (name.endsWith(".uid")) call.respondBytes(bytes, ContentType.Application.Json)
        else
          call.respondText(
            ServeUidReference.page(
              reference,
              bytes,
              if (sessionInPath) "/${WebEscaping.urlEncodeSegment(sessionId.orEmpty())}" else "",
              catalogBundleHost(renderHost)?.catalogSource?.let { source ->
                val preview = renderHost.previews.firstOrNull { it.id == reference.previewId }
                ServeUrls.githubBlobUrl(
                  source.repo,
                  source.ref,
                  preview?.sourceModule ?: source.module,
                  preview?.sourceFile,
                )
              },
            ),
            ContentType.Text.Html,
          )
        return@withLeasedSession
      }
      // References are republished with the catalog, so this lane reads the branch for an explicit
      // `at=` pin or a `gen=` this host no longer serves ([ServeCacheGeneration]).
      val pinnedCommit = requestedPin ?: staleGeneration(renderHost)
      // A pinned comparison must pin both panels, or it compares two moments.
      if (pinnedCommit != null) {
        respondPinnedAsset(
          outcome =
            catalogBundleHost(renderHost)?.let {
              withContext(Dispatchers.IO) { it.pinnedReference(pinnedCommit, referenceId) }
            },
          missing = "no published design reference at that revision",
        )
        return@withLeasedSession
      }
      val bytes = renderHost.designReferenceRaster(referenceId)
      if (bytes == null) {
        call.respond(HttpStatusCode.NotFound)
      } else {
        // A `gen=` here names the generation on disk, so the response is content-addressed and
        // cacheable; without one it keeps the short private lifetime.
        markGeneration(
          "design-reference",
          if (carriesCurrentGeneration(renderHost)) prebakedImageCacheControl(isPublic)
          else "private, max-age=300",
        )
        call.respondBytes(bytes, ContentType.Image.PNG)
      }
    }
  }

  /** The catalog's published design pages, or a 404 when it publishes none. */
  /**
   * The catalog-wide motion browser ([ServeWeb.motionIndexPage]). 404s when the catalog records
   * nothing, matching the landing chip's gate.
   */
  private suspend fun RoutingContext.handleMotionIndex(sessionInPath: Boolean) {
    if (rejectBadToken()) return
    val sessionId = selectedSessionId(sessionInPath)
    val (webSessionId, basePath) = webSessionAndBase(sessionInPath)
    withLeasedSession(
      sessionId,
      onMissing = { respondNotFoundHtml("That design system was not found on this server.") },
    ) { renderHost ->
      val previews = renderHost.previews
      if (previews.none { it.motion.isNotEmpty() }) {
        respondNotFoundHtml("This design system publishes no motion captures.")
        return@withLeasedSession
      }
      markGeneration("static-page", pageCacheControl())
      call.respondText(
        ServeWeb.motionIndexPage(
          moduleLabel = renderHost.label,
          previews = previews,
          token = linkToken(),
          sessionId = webSessionId,
          basePath = basePath,
          changelogHref = changelogHref(sessionId, basePath, webSessionId),
          isPublic = isPublic,
          trust = catalogBundleHost(renderHost)?.let { BundleVerifier.summary(it.trust) },
          themeCss = catalogBundleHost(renderHost)?.webThemeCss.orEmpty(),
          unfurl = ServeWeb.UnfurlMetadata(pageUrl = externalPageUrl()),
          version = SERVE_VERSION,
          displayTitle = catalogBundleHost(renderHost)?.title,
          sessionInOrigin = siteSystem() != null,
          reportIssue = pageScopedReportIssue(renderHost, sessionId, "this motion browser"),
        ),
        ContentType.Text.Html,
      )
    }
  }

  private suspend fun RoutingContext.handleDesignPageIndex(
    sessionInPath: Boolean,
    json: Boolean = false,
  ) {
    if (rejectBadToken()) return
    @Suppress("NAME_SHADOWING") val json = json || wantsJson()
    if (rejectUnknownFormat()) return
    val sessionId = selectedSessionId(sessionInPath)
    val (webSessionId, basePath) = webSessionAndBase(sessionInPath)
    withLeasedSession(
      sessionId,
      onMissing = {
        if (json) call.respond(HttpStatusCode.NotFound)
        else respondNotFoundHtml("That design system was not found on this server.")
      },
    ) { renderHost ->
      val pages = renderHost.designPages().pages
      if (pages.isEmpty()) {
        // 404 in both spellings: a catalog with no sheets has no coverage, and `{"pages":[]}` would
        // read as "measured, nothing there".
        if (json) call.respond(HttpStatusCode.NotFound)
        else respondNotFoundHtml("This design system publishes no design pages.")
        return@withLeasedSession
      }
      if (json) {
        markGeneration("design-page", pageCacheControl())
        call.respondText(
          ServeDesignPagesPayload.index(
            system = sessionId,
            module = renderHost.label,
            pages = pages,
          ),
          ContentType.Application.Json,
        )
        return@withLeasedSession
      }
      markGeneration("static-page", pageCacheControl())
      call.respondText(
        ServeWeb.designPagesIndexPage(
          moduleLabel = renderHost.label,
          pages = pages,
          token = linkToken(),
          sessionId = webSessionId,
          basePath = basePath,
          changelogHref = changelogHref(sessionId, basePath, webSessionId),
          isPublic = isPublic,
          trust = catalogBundleHost(renderHost)?.let { BundleVerifier.summary(it.trust) },
          themeCss = catalogBundleHost(renderHost)?.webThemeCss.orEmpty(),
          unfurl = ServeWeb.UnfurlMetadata(pageUrl = externalPageUrl()),
          version = SERVE_VERSION,
          displayTitle = catalogBundleHost(renderHost)?.title,
          // A top-level site's pages carry their session in the ORIGIN, so same-session links
          // drop the `?session=` the rooted legacy form would add. See [ServeSites].
          sessionInOrigin = siteSystem() != null,
          reportIssue = pageScopedReportIssue(renderHost, sessionId, "these design pages"),
        ),
        ContentType.Text.Html,
      )
    }
  }

  /**
   * One published design page: its view, or with a `.svg` suffix the cached export. The export is
   * staged at catalog load and sanitized once by [ServeDesignPageStore]; no Figma credential or
   * per-request fetch. The asset route serves the same sanitized markup the view inlines, never the
   * raw branch bytes.
   */
  /**
   * `GET /{system}/pages/assets/{id}`: one shared backplate's bytes. Served only via
   * [ServeDesignPageStore.asset], which has checked declaration, path containment, signature and
   * size; reading the manifest's `uri` directly would be an arbitrary file read. `immutable`: the
   * id is the content hash.
   */
  private suspend fun RoutingContext.handleDesignPageAsset(sessionInPath: Boolean) {
    if (rejectBadToken()) return
    val sessionId = selectedSessionId(sessionInPath)
    val id = call.parameters["id"].orEmpty()
    withLeasedSession(sessionId, onMissing = { call.respond(HttpStatusCode.NotFound) }) { renderHost
      ->
      val asset = renderHost.designPages().asset(id)
      val bundle = catalogBundleHost(renderHost)
      val bytes =
        if (asset == null || bundle == null) null
        else withContext(Dispatchers.IO) { bundle.designPageAssetBytes(id) }
      if (asset == null || bytes == null) {
        call.respond(HttpStatusCode.NotFound)
        return@withLeasedSession
      }
      markGeneration("design-page-asset", "public, max-age=31536000, immutable")
      call.respondBytes(bytes, ContentType.parse("image/" + asset.format))
    }
  }

  private suspend fun RoutingContext.handleDesignPage(sessionInPath: Boolean) {
    if (rejectBadToken() || rejectUnknownFormat()) return
    val sessionId = selectedSessionId(sessionInPath)
    val (webSessionId, basePath) = webSessionAndBase(sessionInPath)
    val name = call.parameters["name"].orEmpty()
    val isImage = name.endsWith(".svg")
    // `.json` and `?format=json` are the third and fourth spellings of the page (the `/status`
    // convention); neither applies to the `.svg` export.
    val isJson = !isImage && (name.endsWith(".json") || wantsJson())
    val pageId = name.removeSuffix(".svg").removeSuffix(".json")
    // A machine caller gets machine answers all the way down, misses included.
    val respondMissing: suspend (String) -> Unit = { message ->
      if (isImage || isJson) call.respond(HttpStatusCode.NotFound) else respondNotFoundHtml(message)
    }
    withLeasedSession(
      sessionId,
      onMissing = { respondMissing("That design system was not found on this server.") },
    ) { renderHost ->
      val store = renderHost.designPages()
      val page = store.page(pageId)
      val svg = page?.let { renderHost.designPageSvg(pageId) }
      if (page == null || svg == null) {
        respondMissing("That page was not found in this design system.")
        return@withLeasedSession
      }
      if (isImage) {
        markGeneration("design-page", "private, max-age=300")
        call.respondText(svg, ContentType.Image.SVG)
        return@withLeasedSession
      }
      if (isJson) {
        markGeneration("design-page", pageCacheControl())
        call.respondText(
          ServeDesignPagesPayload.page(
            system = sessionId,
            module = renderHost.label,
            page = page,
            refFor = store::refFor,
            // Resolved against what this session actually publishes, exactly as the view resolves
            // it before drawing a render — see [PageNodeDto.renderable].
            renderablePreviewIds = renderHost.previews.mapTo(HashSet()) { it.id },
            // The plates the view actually draws — the store's verified set, not the manifest's
            // wish list, so this document and the sheet describe the same scene.
            background = store.background(page),
          ),
          ContentType.Application.Json,
        )
        return@withLeasedSession
      }
      // The sibling catalog's rendition of this sheet's cells, resolved per node through the
      // `compareWith` + `parallel` pairing. Skipped on a top-level site ([parallelSpecSource]: the
      // sibling's `/render/` 404s there) and cheap for catalogs with no `compareWith`.
      val previewsById = renderHost.previews.associateBy { it.id }
      val parallelRenders = LinkedHashMap<String, String>()
      var parallelLabel: String? = null
      if (siteSystem() == null) {
        for (node in page.nodes) {
          val preview = previewsById[node.renderablePreviewId ?: continue] ?: continue
          val resolved = resolveParallel(renderHost, preview) ?: continue
          parallelLabel = parallelLabel ?: resolved.label
          parallelRenders[node.nodeId] = parallelRenderUrl(resolved.system, resolved.preview.id)
        }
      }
      markGeneration("static-page", pageCacheControl())
      call.respondText(
        ServeWeb.designPage(
          moduleLabel = renderHost.label,
          page = page,
          svg = svg,
          // The scene beneath the sheet: `background` is the store's verified set, and `assetHref`
          // resolves through the store again, so unverified placements are dropped.
          background = store.background(page),
          assetHref = { id -> store.asset(id)?.let { pageAssetUrl(sessionId, id) } },
          fileKey = store.fileKey,
          parallelRenders = parallelRenders,
          parallelLabel = parallelLabel,
          // Named only when there is a second catalog to distinguish from; alone, "Ours" is
          // unambiguous.
          ownLabel =
            if (parallelRenders.isEmpty()) null
            else
              catalogBundleHost(renderHost)?.title?.takeIf { it.isNotBlank() } ?: renderHost.label,
          // Resolved against what this session actually publishes, so a node mapped to a preview
          // the catalog dropped renders as a plain outline instead of a broken image.
          renderablePreviewIds = renderHost.previews.mapTo(HashSet()) { it.id },
          token = linkToken(),
          sessionId = webSessionId,
          basePath = basePath,
          changelogHref = changelogHref(sessionId, basePath, webSessionId),
          isPublic = isPublic,
          trust = catalogBundleHost(renderHost)?.let { BundleVerifier.summary(it.trust) },
          themeCss = catalogBundleHost(renderHost)?.webThemeCss.orEmpty(),
          unfurl = ServeWeb.UnfurlMetadata(pageUrl = externalPageUrl()),
          version = SERVE_VERSION,
          displayTitle = catalogBundleHost(renderHost)?.title,
          // A top-level site's pages carry their session in the ORIGIN, so same-session links
          // drop the `?session=` the rooted legacy form would add. See [ServeSites].
          sessionInOrigin = siteSystem() != null,
          reportIssue = pageScopedReportIssue(renderHost, sessionId, "this design page"),
        ),
        ContentType.Text.Html,
      )
    }
  }

  /**
   * One staged rc-compare lane image (a player's render of `ir/<id>.rc`, or its build-time diff
   * against the baked PNG). Immutable for a catalog generation, cached like the baked PNGs.
   */
  private suspend fun RoutingContext.handleRcCompareAsset(sessionInPath: Boolean) {
    if (rejectBadToken() || rejectMalformedGeneration()) return
    val sessionId = selectedSessionId(sessionInPath)
    val name = "${call.parameters["lane"].orEmpty()}/${call.parameters["name"].orEmpty()}"
    withLeasedSession(sessionId, onMissing = { call.respond(HttpStatusCode.NotFound) }) { renderHost
      ->
      // Only the current staging is kept, so a wall from an earlier publish gets a refusal rather
      // than today's raster under that publish's mismatch number ([ServeCacheGeneration]).
      val stale = staleGeneration(renderHost)
      if (stale != null) {
        call.respondText(
          "this catalog has restaged its player comparison since " +
            "'${ServeCacheGeneration.PARAM}=${ServeCacheGeneration.short(stale)}' — reload the page",
          status = HttpStatusCode.Conflict,
        )
        return@withLeasedSession
      }
      val bytes = renderHost.rcCompareImage(name)
      if (bytes == null) {
        call.respond(HttpStatusCode.NotFound)
      } else {
        markGeneration("rc-compare", "private, max-age=300")
        call.respondBytes(bytes, ContentType.Image.PNG)
      }
    }
  }

  /** Focused Reference / Diff / Actual comparison for one exact preview-reference mapping. */
  private suspend fun RoutingContext.handleReferenceComparison(sessionInPath: Boolean) {
    if (rejectBadToken() || rejectMalformedPin()) return
    val sessionId = selectedSessionId(sessionInPath)
    val (webSessionId, basePath) = webSessionAndBase(sessionInPath)
    val previewId = call.parameters["name"].orEmpty()
    val requestedReference = call.request.queryParameters["reference"]
    withLeasedSession(
      sessionId,
      onMissing = { respondNotFoundHtml("That design system was not found on this server.") },
    ) { renderHost ->
      val preview = renderHost.previews.firstOrNull { it.id == previewId }
      val references = renderHost.designReferencesFor(previewId)
      val reference =
        if (requestedReference != null) references.firstOrNull { it.id == requestedReference }
        else references.firstOrNull()
      if (preview == null || reference == null) {
        respondNotFoundHtml("That preview has no matching design reference.")
        return@withLeasedSession
      }
      val overrideParams = requestOverrideParams(sessionId)
      val bundleHost = catalogBundleHost(renderHost)
      val sourceHref =
        bundleHost?.catalogSource?.let { source ->
          ServeUrls.githubBlobUrl(
            source.repo,
            source.ref,
            preview.sourceModule ?: source.module,
            preview.sourceFile,
          )
        }
      // The frame this page drew: an override-free, unpinned comparison carries its generation, so
      // a report embeds the pixels the verdict was measured on ([ServeCacheGeneration]). Both
      // panels take it, like the page's `assetQuery`.
      val assetQuerySuffix =
        if (
          overrideParams.isEmpty() &&
            ServeCatalogRevision.normalize(
              call.request.queryParameters[ServeCatalogRevision.PARAM]
            ) == null
        )
          ServeCacheGeneration.scope(requestQuerySuffix(), catalogGeneration(renderHost))
        else requestQuerySuffix()
      val reportContext =
        ServeIssueReport.Context(
          repo = ServeIssueReport.repoFor(bundleHost?.catalogSource, bundleHost?.provenance),
          previewId = preview.id,
          previewLabel = preview.label,
          system = sessionId,
          componentId = ServeIssueReport.componentIdFor(preview),
          referenceId = reference.id,
          variant = ServeIssueReport.variantFor(preview),
          overrides = overrideParams,
          sourceUrl = sourceHref,
          catalog = bundleHost?.provenance?.let { "${it.repo}@${it.branch}" },
          toolVersion = bundleHost?.provenance?.toolVersion,
          comparisonUrl = ServeIssueReport.withoutToken(externalPageUrl()),
          renderUrl =
            ServeIssueReport.withoutToken(
              "${externalOrigin()}$basePath/render/${WebEscaping.urlEncodeSegment(preview.id)}" +
                ".png$assetQuerySuffix"
            ),
          // …and the reference panel, so the issue shows both sides. The reference the page
          // resolved, since `?reference=` may be absent.
          referenceUrl =
            ServeIssueReport.withoutToken(
              "${externalOrigin()}$basePath/reference/" +
                "${WebEscaping.urlEncodeSegment(reference.id)}.png$assetQuerySuffix"
            ),
          publicRender = isPublic,
        )
      val reportIssue =
        ServeWeb.ReportIssue(
          action = ServeIssueReport.action(reportContext.repo),
          body = ServeIssueReport.body(reportContext),
          // The template the page's JS fills, including the selection placeholder, so picks land in
          // the same locator block the server wrote.
          bodyTemplate =
            ServeIssueReport.body(
              reportContext,
              renderPlaceholder = true,
              selectionPlaceholder = true,
              rawScoresPlaceholder = true,
            ),
          repo = reportContext.repo,
          login = githubAuth?.currentLogin(call),
        )
      val revisions = catalogRevisions(renderHost, preview.id)
      val pinned = revisions.pinned != null
      // Whether the frame on screen is the catalog's baked render replayed, rather than produced
      // for this request — the condition under which a separately fetched product (`.png` vs
      // `.annotations`) describes the same frame. `canApplyOverrides` is false exactly for hosts
      // replaying baked pixels; a pin or override re-renders on any host; `tagIndexForPreview` is
      // measured in CI over the baked render.
      //
      // Caching is handled by [ServeCacheGeneration]: the frame URL and `/tags/<id>` URL are scoped
      // to the same publish, and the tag lane refuses a generation the catalog has moved past, so
      // the pair is one publish or the picker fails closed.
      val frameIsReplayedBaked =
        !pinned && overrideParams.isEmpty() && !renderHost.canApplyOverrides
      val tagIndex = renderHost.tagIndexForPreview(preview.id)
      val tagsDescribeFrame = frameIsReplayedBaked && tagIndex.isNotEmpty()
      val tagSelectionNote =
        when {
          // A pin or override means the frame was produced for this request, so neither the
          // published tag index nor the separately fetched annotations describe it; both selectors
          // are withheld with one note.
          pinned ->
            "Element selection is off on a pinned revision: the tag index and the semantics " +
              "layers describe the current render, not this one. Drag a region instead."
          !frameIsReplayedBaked ->
            "Element selection is off while this frame is rendered for you: the tag index and " +
              "the semantics layers are fetched separately and may describe a different render. " +
              "Drag a region instead."
          tagIndex.isEmpty() -> "This catalog publishes no element tag index for this preview."
          else -> null
        }
      val allParityIssues = renderHost.parityIssues()?.issues.orEmpty()
      // The display half, scoped to the system on the mount. `allParityIssues` stays whole for the
      // acceptance lifecycle join below. See [ServeWeb.issuesForSystem].
      val systemParityIssues = ServeWeb.issuesForSystem(allParityIssues, sessionId)
      markGeneration("static-page", pageCacheControl())
      call.respondText(
        ServeWeb.referenceComparisonPage(
          moduleLabel = renderHost.label,
          preview = preview,
          reference = reference,
          references = references,
          token = linkToken(),
          sessionId = webSessionId,
          basePath = basePath,
          changelogHref = changelogHref(sessionId, basePath, webSessionId),
          isPublic = isPublic,
          trust = catalogBundleHost(renderHost)?.let { BundleVerifier.summary(it.trust) },
          // …nor the catalog's stage: a dark-first sticker needs its dark ground.
          declaredSurface = catalogBundleHost(renderHost)?.stageSurface,
          // Stepping from the themed comparison table into its focused Reference/Diff/Actual view
          // must not drop back to the built-in chrome mid-journey.
          themeCss = catalogBundleHost(renderHost)?.webThemeCss.orEmpty(),
          unfurl = ServeWeb.UnfurlMetadata(pageUrl = externalPageUrl()),
          version = SERVE_VERSION,
          displayTitle = catalogBundleHost(renderHost)?.title,
          // Annotation layers describe the current catalog's layout; on a pinned pair they would
          // label historical pixels with today's spec, and nothing per-revision exists.
          referenceAnnotations =
            if (pinned) emptyList() else renderHost.annotationsForReference(reference.id),
          actualAnnotations =
            if (pinned) emptyList() else renderHost.annotationsForPreview(previewId),
          // Withheld on a pin (anchors are bounds in the published render's pixel space) and under
          // an override (the verdict was measured on the published frame, so its claims would be
          // false for what is on screen). The redline survives overrides as a reading aid; a
          // verdict is a claim.
          //
          // Not `frameIsReplayedBaked`, which would also hide the panel on every
          // per-request-rendering host where an override-free browse draws the same frame.
          parityFindings =
            if (pinned || overrideParams.isNotEmpty()) emptyList()
            else renderHost.parityFindingsFor(previewId, reference.id),
          // Same rule as the authored layers: derived ones come from today's render.
          derivedAnnotations = !pinned && renderHost.hasDesignAnnotationsFor(preview.id),
          // The baked Typography lane, as in the viewer: published typography measured off the
          // published frame works without a daemon, which is what makes the annotation pick below
          // offerable at all.
          publishedTypography = !pinned && renderHost.hasPublishedTypographyFor(preview.id),
          // Layers still draw on a re-rendered frame, but clicking one records a baseline, and on a
          // per-request host `.annotations` and the decoded PNG may differ. Drags read displayed
          // pixels and are unaffected.
          //
          // `frameIsReplayedBaked` alone isn't enough: live wrappers keep the PNG baked but ask the
          // daemon for annotations, so the host states which lane its annotations follow.
          annotationsSelectable = frameIsReplayedBaked && renderHost.annotationsFollowBakedFrame,
          tagIndexAvailable = tagsDescribeFrame,
          tagSelectionNote = tagSelectionNote,
          // The acceptance band, only for a catalog that published a document (absent rather than
          // empty, saving the engine bundle).
          //
          // Never on a pinned revision: the pixels are historical but the document and
          // `referenceSha256` are current, so the fingerprint gate could pass and suppress pixels
          // with an acceptance never published for that revision.
          //
          // Scope fields come from the same `reportContext` as the locator, so a record always
          // matches the comparison it was authored on.
          knownDifferences =
            renderHost
              .knownDifferences()
              ?.takeIf { !pinned }
              ?.let {
                val system = reportContext.system
                val component = reportContext.componentId
                // Both are optional on a report but required by an acceptance's scope; without them
                // nothing can match, so the band and bundle are omitted.
                if (system == null || component == null) return@let null
                KnownDifferenceScope(
                  system = system,
                  component = component,
                  previewId = preview.id,
                  referenceId = reference.id,
                  variant = reportContext.variant,
                  overrides = overrideParams,
                  // Null when the reference publishes no digest (`reference-hash-missing`): a
                  // refusal, since a gate that couldn't run must not report a pass.
                  referenceSha256 = reference.raster.sha256,
                  // Empty unless the published index describes the frame on screen (the picker's
                  // gate); otherwise an element-scoped acceptance would report a false move.
                  tagIndex =
                    if (!tagsDescribeFrame) emptyMap()
                    else
                      tagIndex.mapValues { (_, entry) ->
                        WireTagEntry(
                          count = entry.count,
                          bounds = entry.bounds,
                          space = entry.space,
                        )
                      },
                )
              },
          parityIssues =
            systemParityIssues.filter { issue ->
              preview.id in issue.previewIds ||
                reference.id in issue.referenceIds ||
                (issue.scope == "component" && issue.component == reportContext.componentId)
            },
          parityIssuesGeneratedAt = renderHost.parityIssues()?.generatedAt,
          acceptanceIssues = allParityIssues,
          revisions = revisions,
          overrides = overrideParams,
          reportIssue = reportIssue,
          // A top-level site's pages carry their session in the ORIGIN, so same-session links
          // drop the `?session=` the rooted legacy form would add. See [ServeSites].
          sessionInOrigin = siteSystem() != null,
        ),
        ContentType.Text.Html,
      )
    }
  }

  /**
   * Admin gate: the admin routes require [adminToken] — never the browse token, never open under
   * `--public`. Responds 404 like the browse gate and compares in constant time.
   *
   * Read from the [ADMIN_TOKEN_HEADER] header only, never `?token=`, which leaks into proxy logs,
   * history and `Referer`. All in-repo callers send the header.
   */
  private suspend fun RoutingContext.rejectBadAdminToken(allowReadToken: Boolean = false): Boolean {
    val provided = call.request.headers[ADMIN_TOKEN_HEADER]
    // A blank configured token must not match an empty header; this keeps any route that forgets
    // its `*Enabled` registration gate fail-closed.
    if (adminToken.isNullOrBlank() && (!allowReadToken || adminReadToken.isNullOrBlank())) {
      call.respondText("not found", status = HttpStatusCode.NotFound)
      return true
    }
    if (!adminToken.isNullOrBlank() && ServeUrls.tokensMatch(adminToken, provided.orEmpty())) {
      return false
    }
    if (
      allowReadToken &&
        !adminReadToken.isNullOrBlank() &&
        ServeUrls.tokensMatch(adminReadToken, provided.orEmpty())
    ) {
      return false
    }
    call.respondText("not found", status = HttpStatusCode.NotFound)
    return true
  }

  private data class UiBuilderAdminAccess(
    val readOnly: Boolean,
    val browserSession: Boolean,
  )

  /**
   * UI-builder-only admin gate; never used by catalog, trust, site, or onboarding routes. Reads
   * [ADMIN_TOKEN_HEADER] only, except with [allowPageQueryToken] (set by `GET /admin/ui-builder`
   * alone): a browser opens pages by URL, so that data-free shell accepts `?token=`, strips it on
   * load, and uses the header for every JSON route.
   */
  private suspend fun RoutingContext.uiBuilderAdminAccess(
    allowReadToken: Boolean = false,
    allowPageQueryToken: Boolean = false,
  ): UiBuilderAdminAccess? {
    val provided =
      call.request.headers[ADMIN_TOKEN_HEADER]
        ?: call.request.queryParameters["token"]?.takeIf { allowPageQueryToken }
    if (!adminToken.isNullOrBlank() && ServeUrls.tokensMatch(adminToken, provided.orEmpty())) {
      return UiBuilderAdminAccess(readOnly = false, browserSession = false)
    }
    if (
      allowReadToken &&
        !adminReadToken.isNullOrBlank() &&
        ServeUrls.tokensMatch(adminReadToken, provided.orEmpty())
    ) {
      return UiBuilderAdminAccess(readOnly = true, browserSession = false)
    }

    if (uiBuilderAdministrators.containsGithubLogin(githubAuth?.currentLogin(call))) {
      return UiBuilderAdminAccess(readOnly = false, browserSession = true)
    }

    val capability =
      if (allowReadToken) UiBuilderRouteCapability.READ else UiBuilderRouteCapability.WRITE
    val actor =
      (uiBuilderAuthorization?.authorize(call, capability)
          as? UiBuilderAuthorizationDecision.Authorized)
        ?.actor
    if (actor != null && uiBuilderAdministrators.contains(actor)) {
      return UiBuilderAdminAccess(readOnly = false, browserSession = false)
    }

    call.respondText("not found", status = HttpStatusCode.NotFound)
    return null
  }

  private suspend fun RoutingContext.rejectCrossOriginUiBuilderAdminMutation(
    access: UiBuilderAdminAccess
  ): Boolean {
    if (!access.browserSession || isSameOriginFormSubmission()) return false
    call.respondText(
      "cross-origin UI-builder administration is not allowed",
      status = HttpStatusCode.Forbidden,
    )
    return true
  }

  /** `GET /admin/catalogs`: the configured catalog set and each entry's latest load state. */
  private suspend fun RoutingContext.respondAdminCatalogs(admin: ServeCatalogAdmin) {
    val catalogs =
      withContext(Dispatchers.IO) { admin.list() }
        .map { state ->
          AdminCatalogDto(
            system = state.config.system,
            repo = state.config.repo,
            branch = state.config.branch,
            listed = state.config.listed,
            group = state.config.group?.heading,
            loadPriority = state.config.loadPriority,
            state = state.loadState,
            error = state.error,
          )
        }
    call.respondText(
      JSON.encodeToString(
        AdminCatalogsResponse.serializer(),
        AdminCatalogsResponse(catalogs = catalogs),
      ),
      ContentType.Application.Json,
    )
  }

  /** `POST /admin/catalogs`: publish one catalog from a [ServeCatalogsConfig.Entry] JSON body. */
  private suspend fun RoutingContext.handleAdminRegister(admin: ServeCatalogAdmin) {
    val body =
      withContext(Dispatchers.IO) {
        call.receiveStream().use { readCapped(it, MAX_ADMIN_BODY_BYTES) }
      }
    if (body == null) {
      call.respondText("request body too large", status = HttpStatusCode.PayloadTooLarge)
      return
    }
    val entry = runCatching {
      JSON.decodeFromString(ServeCatalogsConfig.Entry.serializer(), body.decodeToString())
    }
      .getOrElse {
        call.respondText(
          "invalid catalog entry: ${it.message}",
          status = HttpStatusCode.BadRequest,
        )
        return
      }
    // The fetch runs off the request dispatcher: publishing a catalog clones a delivery branch.
    respondAdminResult(withContext(Dispatchers.IO) { admin.register(entry) })
  }

  /** Map a [ServeCatalogAdmin.Result] onto its HTTP status + JSON body. */
  private suspend fun RoutingContext.respondAdminResult(result: ServeCatalogAdmin.Result) {
    when (result) {
      is ServeCatalogAdmin.Result.Ok ->
        call.respondText(
          JSON.encodeToString(
            AdminCatalogResult.serializer(),
            AdminCatalogResult(system = result.system, status = "ok", warning = result.warning),
          ),
          ContentType.Application.Json,
        )
      is ServeCatalogAdmin.Result.Invalid ->
        call.respondText(result.reason, status = HttpStatusCode.BadRequest)
      is ServeCatalogAdmin.Result.Conflict ->
        call.respondText(result.reason, status = HttpStatusCode.Conflict)
      // The entry was well-formed but its branch wouldn't fetch — an upstream failure, not the
      // caller's mistake, so it reads as a bad gateway rather than a bad request.
      is ServeCatalogAdmin.Result.Failed ->
        call.respondText(
          "catalog ${result.system} not published: ${result.reason}",
          status = HttpStatusCode.BadGateway,
        )
    }
  }

  /** `GET /admin/groups`: the front-page sections a catalog entry may claim. */
  private suspend fun RoutingContext.respondAdminGroups(admin: ServeCatalogAdmin) {
    val groups = withContext(Dispatchers.IO) { admin.listGroups() }
    call.respondText(
      JSON.encodeToString(
        AdminGroupsResponse.serializer(),
        AdminGroupsResponse(
          groups = groups.map { AdminGroupDto(it.id, it.heading, it.noun, it.priority) }
        ),
      ),
      ContentType.Application.Json,
    )
  }

  /** `POST /admin/groups`: define a section, or restyle one that exists. */
  private suspend fun RoutingContext.handleAdminGroupUpsert(admin: ServeCatalogAdmin) {
    val body =
      withContext(Dispatchers.IO) {
        call.receiveStream().use { readCapped(it, MAX_ADMIN_BODY_BYTES) }
      }
    if (body == null) {
      call.respondText("request body too large", status = HttpStatusCode.PayloadTooLarge)
      return
    }
    val group = runCatching {
      JSON.decodeFromString(ServeCatalogsConfig.Group.serializer(), body.decodeToString())
    }
      .getOrElse {
        call.respondText("invalid group: ${it.message}", status = HttpStatusCode.BadRequest)
        return
      }
    respondAdminResult(withContext(Dispatchers.IO) { admin.upsertGroup(group) })
  }

  /**
   * `POST /admin/onboard`: publish every catalog a GitHub repository delivers, from its URL.
   * Answers 200 if anything ends up serving (including idempotent re-posts), with per-catalog
   * outcomes; only total failure is an error status.
   */
  private suspend fun RoutingContext.handleAdminOnboard(onboarding: ServeOnboarding) {
    val body =
      withContext(Dispatchers.IO) {
        call.receiveStream().use { readCapped(it, MAX_ADMIN_BODY_BYTES) }
      }
    if (body == null) {
      call.respondText("request body too large", status = HttpStatusCode.PayloadTooLarge)
      return
    }
    val request = runCatching {
      JSON.decodeFromString(AdminOnboardRequest.serializer(), body.decodeToString())
    }
      .getOrElse {
        call.respondText(
          "invalid onboarding request: ${it.message}",
          status = HttpStatusCode.BadRequest,
        )
        return
      }
    // Discovery is a `git ls-remote` and each publish clones a delivery branch, so the whole thing
    // runs off the request dispatcher exactly as `POST /admin/catalogs` does.
    val result =
      withContext(Dispatchers.IO) {
        onboarding.onboard(request.url, group = request.group, listed = request.listed)
      }
    when (result) {
      is ServeOnboarding.Result.Invalid ->
        call.respondText(result.reason, status = HttpStatusCode.BadRequest)
      is ServeOnboarding.Result.Unreachable ->
        call.respondText(
          "could not onboard ${result.repo}: ${result.reason}",
          status = HttpStatusCode.BadGateway,
        )
      is ServeOnboarding.Result.Empty ->
        call.respondText(
          "${result.repo} publishes no ${result.branchPrefix}* branches — run " +
            "`compose-preview publish` in that project first",
          status = HttpStatusCode.NotFound,
        )
      is ServeOnboarding.Result.Ok -> {
        val payload =
          AdminOnboardResponse(
            repo = result.repo,
            catalogs =
              result.catalogs.map { AdminOnboardCatalogDto(it.system, it.status, it.detail) },
          )
        call.respondText(
          JSON.encodeToString(AdminOnboardResponse.serializer(), payload),
          ContentType.Application.Json,
          // Every discovered branch failed to publish: an upstream fault, so bad gateway, as for
          // `POST /admin/catalogs`.
          status = if (result.served.isEmpty()) HttpStatusCode.BadGateway else HttpStatusCode.OK,
        )
      }
    }
  }

  /**
   * `POST /admin/onboard/scan`: what Compose previews are in a pasted repository. 200 even with
   * none (a finding, with per-module reasons); 400 for a bad URL, 502 if cloning fails.
   */
  private suspend fun RoutingContext.handleAdminOnboardScan(onboarding: ServeSourceOnboarding) {
    val request = receiveOnboardSourceRequest() ?: return
    // A shallow clone of an arbitrary repository, so off the request dispatcher — but still
    // in-request: a scan is bounded work and the caller wants the answer, not a job to poll.
    val result = withContext(Dispatchers.IO) { onboarding.scan(request.url, request.ref) }
    respondOnboardScan(result)
  }

  /** Read and parse the scan route's body; null once it has already answered the call. */
  private suspend fun RoutingContext.receiveOnboardSourceRequest(): AdminOnboardSourceRequest? {
    val body =
      withContext(Dispatchers.IO) {
        call.receiveStream().use { readCapped(it, MAX_ADMIN_BODY_BYTES) }
      }
    if (body == null) {
      call.respondText("request body too large", status = HttpStatusCode.PayloadTooLarge)
      return null
    }
    return runCatching {
      JSON.decodeFromString(AdminOnboardSourceRequest.serializer(), body.decodeToString())
    }
      .getOrElse {
        call.respondText(
          "invalid onboarding request: ${it.message}",
          status = HttpStatusCode.BadRequest,
        )
        null
      }
  }

  /** The one mapping of a scan verdict to a status, shared by both routes that can produce one. */
  private suspend fun RoutingContext.respondOnboardScan(result: ServeSourceOnboarding.ScanResult) {
    when (result) {
      is ServeSourceOnboarding.ScanResult.Invalid ->
        call.respondText(result.reason, status = HttpStatusCode.BadRequest)
      is ServeSourceOnboarding.ScanResult.Unreachable ->
        call.respondText(
          "could not read ${result.repo}: ${result.reason}",
          status = HttpStatusCode.BadGateway,
        )
      is ServeSourceOnboarding.ScanResult.Ok ->
        call.respondText(
          JSON.encodeToString(
            AdminOnboardScanResponse.serializer(),
            AdminOnboardScanResponse(
              repo = result.repo,
              ref = result.ref,
              sha = result.sha,
              modules = result.modules.map { it.toDto() },
              notes = result.notes,
            ),
          ),
          ContentType.Application.Json,
        )
    }
  }

  /** `GET /admin/sites`: the hostnames this server serves a single catalog on. */
  private suspend fun RoutingContext.respondAdminSites(admin: ServeSiteAdmin) {
    val sites = withContext(Dispatchers.IO) { admin.list() }
    call.respondText(
      JSON.encodeToString(
        AdminSitesResponse.serializer(),
        AdminSitesResponse(sites = sites.map { AdminSiteDto(it.host, it.system) }),
      ),
      ContentType.Application.Json,
    )
  }

  /** `GET /admin/editor`: what is serving, what is bundled, and what the next start will pin. */
  private suspend fun RoutingContext.respondAdminEditor(admin: ServeUiBuilderEditorAdmin) {
    val configured = withContext(Dispatchers.IO) { admin.configuredPin() }
    val state = admin.state()
    call.respondText(
      JSON.encodeToString(
        AdminEditorResponse.serializer(),
        AdminEditorResponse(
          serving = state.servingVersion,
          servingPinned = state.servingPin != null,
          bundled = state.bundledVersion,
          pinned = configured,
          restartRequired = configured != state.servingPin,
          supportedServerApi = ServeUiBuilderEditor.SUPPORTED_SERVER_API.sorted(),
        ),
      ),
      ContentType.Application.Json,
    )
  }

  /**
   * `GET /admin/ui-builder/config`: the `uiBuilder` block, what the environment alone gives, what
   * is serving, and what the next start will serve.
   */
  private suspend fun RoutingContext.respondAdminUiBuilderSettings(
    admin: ServeUiBuilderSettingsAdmin
  ) {
    val configured = withContext(Dispatchers.IO) { admin.configured() }
    val next = withContext(Dispatchers.IO) { admin.next() }
    call.respondText(
      JSON.encodeToString(
        AdminUiBuilderSettingsResponse.serializer(),
        AdminUiBuilderSettingsResponse(
          configured = configured,
          environment = admin.environment().describe(),
          serving = admin.serving().describe(),
          next = next.effective.describe(),
          restartRequired = next.effective != admin.serving(),
          problems = next.problems,
          shadow = admin.shadowReports().toSortedMap(),
          unavailable = admin.unavailable().toSortedMap(),
        ),
      ),
      ContentType.Application.Json,
    )
  }

  /** `PUT /admin/ui-builder/config`: replace the `uiBuilder` block with the JSON body. */
  private suspend fun RoutingContext.handleAdminUiBuilderSettingsSet(
    admin: ServeUiBuilderSettingsAdmin
  ) {
    val body =
      withContext(Dispatchers.IO) {
        call.receiveStream().use { readCapped(it, MAX_ADMIN_BODY_BYTES) }
      }
    if (body == null) {
      call.respondText("request body too large", status = HttpStatusCode.PayloadTooLarge)
      return
    }
    val settings = runCatching {
      JSON.decodeFromString(
        ServeCatalogsConfig.UiBuilderSettings.serializer(),
        body.decodeToString(),
      )
    }
      .getOrElse {
        call.respondText(
          "invalid UI-builder settings: ${it.message}",
          status = HttpStatusCode.BadRequest,
        )
        return
      }
    respondAdminUiBuilderSettingsResult(withContext(Dispatchers.IO) { admin.set(settings) })
  }

  private suspend fun RoutingContext.respondAdminUiBuilderSettingsResult(
    result: ServeUiBuilderSettingsAdmin.Result
  ) {
    when (result) {
      is ServeUiBuilderSettingsAdmin.Result.Ok ->
        call.respondText(
          JSON.encodeToString(
            AdminUiBuilderSettingsResult.serializer(),
            AdminUiBuilderSettingsResult(
              status = "ok",
              configured = result.settings,
              next = result.effective.describe(),
              restartRequired = result.restartRequired,
              problems = result.problems,
            ),
          ),
          ContentType.Application.Json,
        )
      is ServeUiBuilderSettingsAdmin.Result.Invalid ->
        call.respondText(result.reason, status = HttpStatusCode.BadRequest)
      is ServeUiBuilderSettingsAdmin.Result.Unavailable ->
        call.respondText(result.reason, status = HttpStatusCode.ServiceUnavailable)
    }
  }

  /**
   * `GET /admin/settings`: `settings.json` as stored, plus each setting's serving value, its
   * source, and what changes at next start.
   */
  private suspend fun RoutingContext.respondAdminSettings(admin: ServeSettingsAdmin) {
    val (configured, problem) =
      withContext(Dispatchers.IO) {
        runCatching { admin.configured() }.fold({ it to null }, { null to it.message })
      }
    val entries = withContext(Dispatchers.IO) { admin.entries(configured) }
    call.respondText(
      JSON.encodeToString(
        AdminSettingsResponse.serializer(),
        AdminSettingsResponse(
          file = admin.displayPath,
          configured = configured,
          settings = entries,
          restartRequired = entries.any { it.pending },
          problems = listOfNotNull(problem?.let { "settings.json could not be read: $it" }),
        ),
      ),
      ContentType.Application.Json,
    )
  }

  /** `PUT /admin/settings`: replace `settings.json` with the JSON body. */
  private suspend fun RoutingContext.handleAdminSettingsSet(admin: ServeSettingsAdmin) {
    val body =
      withContext(Dispatchers.IO) {
        call.receiveStream().use { readCapped(it, MAX_ADMIN_BODY_BYTES) }
      }
    if (body == null) {
      call.respondText("request body too large", status = HttpStatusCode.PayloadTooLarge)
      return
    }
    val document = runCatching {
      ServeSettings.parse(body.decodeToString())
    }
      .getOrElse {
        call.respondText("invalid settings: ${it.message}", status = HttpStatusCode.BadRequest)
        return
      }
    respondAdminSettingsResult(withContext(Dispatchers.IO) { admin.set(document) })
  }

  private suspend fun RoutingContext.respondAdminSettingsResult(result: ServeSettingsAdmin.Result) {
    when (result) {
      is ServeSettingsAdmin.Result.Ok ->
        call.respondText(
          JSON.encodeToString(
            AdminSettingsResult.serializer(),
            AdminSettingsResult(
              status = "ok",
              applied = result.applied,
              pending = result.entries.filter { it.pending }.map { it.key },
              overridden = result.entries.filter { it.overridden }.map { it.key },
              restartRequired = result.entries.any { it.pending },
              problems = result.problems,
            ),
          ),
          ContentType.Application.Json,
        )
      is ServeSettingsAdmin.Result.Invalid ->
        call.respondText(
          result.problems.joinToString("\n"),
          status = HttpStatusCode.BadRequest,
        )
      is ServeSettingsAdmin.Result.Unavailable ->
        call.respondText(result.reason, status = HttpStatusCode.ServiceUnavailable)
    }
  }

  /** `PUT /admin/editor`: pin an editor from a [ServeCatalogsConfig.EditorPin] JSON body. */
  private suspend fun RoutingContext.handleAdminEditorSet(admin: ServeUiBuilderEditorAdmin) {
    val body =
      withContext(Dispatchers.IO) {
        call.receiveStream().use { readCapped(it, MAX_ADMIN_BODY_BYTES) }
      }
    if (body == null) {
      call.respondText("request body too large", status = HttpStatusCode.PayloadTooLarge)
      return
    }
    val pin = runCatching {
      JSON.decodeFromString(ServeCatalogsConfig.EditorPin.serializer(), body.decodeToString())
    }
      .getOrElse {
        call.respondText("invalid editor pin: ${it.message}", status = HttpStatusCode.BadRequest)
        return
      }
    respondAdminEditorResult(withContext(Dispatchers.IO) { admin.set(pin) })
  }

  private suspend fun RoutingContext.respondAdminEditorResult(
    result: ServeUiBuilderEditorAdmin.Result
  ) {
    when (result) {
      is ServeUiBuilderEditorAdmin.Result.Ok ->
        call.respondText(
          JSON.encodeToString(
            AdminEditorResult.serializer(),
            AdminEditorResult(
              status = "ok",
              pinned = result.pin,
              restartRequired = result.restartRequired,
              warning = result.warning,
            ),
          ),
          ContentType.Application.Json,
        )
      is ServeUiBuilderEditorAdmin.Result.Invalid ->
        call.respondText(result.reason, status = HttpStatusCode.BadRequest)
      is ServeUiBuilderEditorAdmin.Result.Conflict ->
        call.respondText(result.reason, status = HttpStatusCode.Conflict)
      is ServeUiBuilderEditorAdmin.Result.Unavailable ->
        call.respondText(result.reason, status = HttpStatusCode.ServiceUnavailable)
    }
  }

  /** `POST /admin/sites`: publish a hostname from a [ServeCatalogsConfig.Site] JSON body. */
  private suspend fun RoutingContext.handleAdminSiteAdd(admin: ServeSiteAdmin) {
    val body =
      withContext(Dispatchers.IO) {
        call.receiveStream().use { readCapped(it, MAX_ADMIN_BODY_BYTES) }
      }
    if (body == null) {
      call.respondText("request body too large", status = HttpStatusCode.PayloadTooLarge)
      return
    }
    val site = runCatching {
      JSON.decodeFromString(ServeCatalogsConfig.Site.serializer(), body.decodeToString())
    }
      .getOrElse {
        call.respondText("invalid site: ${it.message}", status = HttpStatusCode.BadRequest)
        return
      }
    respondAdminSiteResult(withContext(Dispatchers.IO) { admin.add(site) })
  }

  /**
   * Map a [ServeSiteAdmin.Result] onto an HTTP status + JSON body. A 409 for "already exactly this"
   * lets the publish script treat re-runs as success.
   */
  private suspend fun RoutingContext.respondAdminSiteResult(result: ServeSiteAdmin.Result) {
    when (result) {
      is ServeSiteAdmin.Result.Ok ->
        call.respondText(
          JSON.encodeToString(
            AdminSiteResult.serializer(),
            AdminSiteResult(host = result.host, status = "ok", warning = result.warning),
          ),
          ContentType.Application.Json,
        )
      is ServeSiteAdmin.Result.Invalid ->
        call.respondText(result.reason, status = HttpStatusCode.BadRequest)
      is ServeSiteAdmin.Result.Conflict ->
        call.respondText(result.reason, status = HttpStatusCode.Conflict)
    }
  }

  /**
   * `GET /admin/ui-builder/library`: every design the served catalogs publish. On IO (a cold index
   * is an HTTP round trip per catalog), best-effort per catalog.
   */
  private suspend fun RoutingContext.respondAdminUiBuilderLibrary(
    library: ServeUiBuilderDesignLibrary
  ) {
    val catalogs = uiBuilderDesignCatalogs()
    val entries = withContext(Dispatchers.IO) { library.list(catalogs) }
    call.response.headers.append(HttpHeaders.CacheControl, "no-store")
    call.respondText(
      JSON.encodeToString(
        AdminUiBuilderLibraryResponse.serializer(),
        AdminUiBuilderLibraryResponse(
          catalogsSearched = catalogs.map { it.system },
          designs =
            entries.map {
              AdminUiBuilderLibraryDto(
                system = it.system,
                designId = it.designId,
                title = it.title,
                description = it.description,
              )
            },
        ),
      ),
      ContentType.Application.Json,
    )
  }

  /**
   * `GET /admin/ui-builder/component-library`: every component the served projects share. On IO and
   * best-effort per project, like the design listing.
   */
  /**
   * Every place a shared component is read from: projects first, then this host's editors, so a
   * committed component shadows the host copy.
   */
  private fun uiBuilderComponentCatalogs(): List<ServeUiBuilderDesignLibrary.Coordinate> =
    uiBuilderDesignCatalogs() + uiBuilderComponentStore?.coordinates().orEmpty()

  private suspend fun RoutingContext.respondAdminUiBuilderComponentLibrary(
    library: ServeUiBuilderComponentLibrary
  ) {
    val catalogs = uiBuilderComponentCatalogs()
    val entries = withContext(Dispatchers.IO) { library.list(catalogs) }
    call.response.headers.append(HttpHeaders.CacheControl, "no-store")
    call.respondText(
      JSON.encodeToString(
        UiBuilderComponentLibraryResponse.serializer(),
        UiBuilderComponentLibraryResponse(
          catalogsSearched = catalogs.map { it.system },
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

  /**
   * `GET /admin/ui-builder/component-library/{system}/{componentId}`: one symbol, checked. Answers
   * the body a design would import and the digest it would record. A symbol that doesn't check out
   * is a 404 with the library's logged reason.
   */
  private suspend fun RoutingContext.respondAdminUiBuilderComponentSymbol(
    library: ServeUiBuilderComponentLibrary,
    system: String,
    componentId: String,
  ) {
    // Every coordinate for this system, not just the first: `--ui-builder-designs` can name a local
    // checkout for a system a served catalog also covers.
    val catalogs = uiBuilderComponentCatalogs().filter { it.system == system }
    if (catalogs.isEmpty()) {
      call.respondText(
        "$system is not a catalog this host serves",
        status = HttpStatusCode.NotFound,
      )
      return
    }
    // In configured order (a local export shadows the branch), taking the first usable one; failing
    // sources were already logged.
    // One index read per coordinate feeds both metadata and body, so they describe the same symbol
    // even mid-export.
    val symbol =
      withContext(Dispatchers.IO) {
        catalogs.firstNotNullOfOrNull { catalog ->
          library
            .index(catalog)
            .firstOrNull { it.componentId == componentId }
            ?.let { library.symbol(catalog, it) }
        }
      }
    if (symbol == null) {
      call.respondText(
        "$system publishes no usable component called $componentId",
        status = HttpStatusCode.NotFound,
      )
      return
    }
    call.response.headers.append(HttpHeaders.CacheControl, "no-store")
    call.respondText(
      JSON.encodeToString(
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

  /** `POST /admin/ui-builder/library/{system}/{designId}`: open one published design here. */
  private suspend fun RoutingContext.respondAdminUiBuilderLibraryOpen(
    library: ServeUiBuilderDesignLibrary,
    system: String,
    designId: String,
  ) {
    val catalog = uiBuilderDesignCatalogs().firstOrNull { it.system == system }
    if (catalog == null) {
      call.respondText(
        "$system is not a catalog this host serves",
        status = HttpStatusCode.NotFound,
      )
      return
    }
    // One index read for both, so the links written below belong to the document created above.
    val entry =
      withContext(Dispatchers.IO) { library.index(catalog).firstOrNull { it.designId == designId } }
    val document = entry?.let { withContext(Dispatchers.IO) { library.document(catalog, it) } }
    if (document == null) {
      call.respondText(
        "$system publishes no design called $designId",
        status = HttpStatusCode.NotFound,
      )
      return
    }
    val outcome =
      withContext(Dispatchers.IO) {
        ServeUiBuilderCreate(
            designService!!,
            uiBuilderDir!!,
            canonicalServerOrigin(),
            uiBuilderSeeds,
          )
          .installPublished(
            actor = AuthenticatedUiBuilderActor(ADMIN_LIBRARY_ACTOR),
            document = document,
          )
      }
    call.response.headers.append(HttpHeaders.CacheControl, "no-store")
    if (outcome is ServeUiBuilderCreate.Outcome.Created) {
      // Carry the project's back-links across, but only for the design this call opened (existing
      // links records aren't overwritten), and only when the document's id matches the entry's.
      entry
        ?.takeIf { it.designId == document.id }
        ?.links
        ?.let { links ->
          val written =
            withContext(Dispatchers.IO) { uiBuilderLinksStore?.replace(document.id, links) }
          // The design opened; only its sidecar did not. Saying so is the difference between an
          // operator knowing why the reverse lookup omits this design and being left to guess.
          val why =
            when (written) {
              is LinksWriteResult.Refused -> written.reason
              is LinksWriteResult.Failed -> written.reason
              else -> null
            }
          if (why != null) {
            System.err.println("serve: links for library design ${document.id} not stored ($why)")
          }
        }
      if (entry != null && entry.designId != document.id) {
        System.err.println(
          "serve: ${catalog.system} publishes design ${entry.designId} as a document with id " +
            "${document.id}; its links were not carried across"
        )
      }
    }
    when (outcome) {
      is ServeUiBuilderCreate.Outcome.Created,
      is ServeUiBuilderCreate.Outcome.AlreadyExists ->
        call.respondText(
          JSON.encodeToString(
            AdminUiBuilderLibraryOpenResult.serializer(),
            AdminUiBuilderLibraryOpenResult(
              designId = document.id,
              status =
                if (outcome is ServeUiBuilderCreate.Outcome.Created) "opened" else "alreadyOpen",
            ),
          ),
          ContentType.Application.Json,
        )
      is ServeUiBuilderCreate.Outcome.Refused ->
        call.respondText(outcome.reason, status = HttpStatusCode.fromValue(outcome.status))
    }
  }

  /** `GET /admin/ui-builder/designs`: every UI-builder design on this host, oldest first. */
  private suspend fun RoutingContext.respondAdminUiBuilderDesigns(admin: ServeUiBuilderAdmin) {
    val designs = withContext(Dispatchers.IO) { admin.list() }
    val unusable = withContext(Dispatchers.IO) { admin.unusable() }
    val degraded = withContext(Dispatchers.IO) { admin.degraded() }
    val unreadable = withContext(Dispatchers.IO) { admin.unreadable() }
    val listed = designs.map {
      AdminUiBuilderDesignDto(
        designId = it.designId,
        title = it.title,
        revision = it.revision,
        catalogSystemId = it.catalogPin.systemId,
        ownerActorId = it.ownerActorId,
        collaborators = it.collaborators,
        createdAtEpochMillis = it.createdAtEpochMillis,
        updatedAtEpochMillis = it.updatedAtEpochMillis,
        activeSubscribers = it.activeSubscribers,
        unusableReason = unusable[it.designId],
        degradedReason = degraded[it.designId],
      )
    }
    // A design whose stored files won't read isn't in the map above, so give it its own row (id and
    // reason) on the page the startup warning points to.
    val quarantined =
      (unusable.keys - designs.map { it.designId }.toSet()).sorted().map { designId ->
        AdminUiBuilderDesignDto(
          designId = designId,
          title = designId,
          revision = 0,
          catalogSystemId = "",
          ownerActorId = "",
          collaborators = 0,
          createdAtEpochMillis = 0,
          updatedAtEpochMillis = 0,
          activeSubscribers = 0,
          unusableReason = unusable.getValue(designId),
          documentAvailable = designId !in unreadable,
        )
      }
    call.response.headers.append(HttpHeaders.CacheControl, "no-store")
    call.respondText(
      JSON.encodeToString(
        AdminUiBuilderDesignsResponse.serializer(),
        AdminUiBuilderDesignsResponse(designs = listed + quarantined),
      ),
      ContentType.Application.Json,
    )
  }

  /**
   * `GET /admin/ui-builder/designs/{designId}/document`: one stored design document as a JSON
   * attachment.
   */
  private suspend fun RoutingContext.respondAdminUiBuilderDocument(
    admin: ServeUiBuilderAdmin,
    rawDesignId: String,
  ) {
    val designId = rawDesignId.trim()
    if (designId.isEmpty()) {
      call.respondText("design id is required", status = HttpStatusCode.BadRequest)
      return
    }
    val document = withContext(Dispatchers.IO) { admin.document(designId) }
    if (document == null) {
      call.respondText("no such design: $designId", status = HttpStatusCode.NotFound)
      return
    }
    call.response.headers.append(HttpHeaders.CacheControl, "no-store")
    // The id came off the wire, so it is reduced to a safe filename for the header.
    val filename = designId.replace(UNSAFE_FILENAME_CHARACTER, "_")
    call.response.headers.append(
      HttpHeaders.ContentDisposition,
      "attachment; filename=\"$filename.json\"",
    )
    call.respondText(document, ContentType.Application.Json)
  }

  /** Map a [ServeUiBuilderAdmin.Result] onto its HTTP status + JSON body. */
  private suspend fun RoutingContext.respondAdminUiBuilderResult(
    result: ServeUiBuilderAdmin.Result
  ) {
    when (result) {
      is ServeUiBuilderAdmin.Result.Repaired ->
        call.respondText(
          JSON.encodeToString(
            AdminUiBuilderRepairResult.serializer(),
            AdminUiBuilderRepairResult(
              designId = result.designId,
              status = "repaired",
              revision = result.revision,
            ),
          ),
          ContentType.Application.Json,
        )
      is ServeUiBuilderAdmin.Result.Deleted ->
        call.respondText(
          JSON.encodeToString(
            AdminUiBuilderDesignResult.serializer(),
            AdminUiBuilderDesignResult(designId = result.designId, status = "deleted"),
          ),
          ContentType.Application.Json,
        )
      is ServeUiBuilderAdmin.Result.NotFound ->
        call.respondText("no such design: ${result.designId}", status = HttpStatusCode.NotFound)
      is ServeUiBuilderAdmin.Result.Invalid ->
        call.respondText(result.reason, status = HttpStatusCode.BadRequest)
    }
  }

  /**
   * `GET /admin/trust`: the trusted producers. Pinned public keys are listed by id and name only,
   * never key material.
   */
  private suspend fun RoutingContext.respondAdminTrust(admin: ServeTrustAdmin) {
    val store = withContext(Dispatchers.IO) { admin.list() }
    call.respondText(
      JSON.encodeToString(
        AdminTrustResponse.serializer(),
        AdminTrustResponse(
          branches = store.branches.map { AdminTrustBranchDto(it.repo, it.branch) },
          keys = store.keys.map { AdminTrustKeyDto(it.keyId, it.name) },
          oidc = store.oidc.map { it.identity },
        ),
      ),
      ContentType.Application.Json,
    )
  }

  /** `POST /admin/trust`: trust one producer from an [AdminTrustEntry] JSON body. */
  private suspend fun RoutingContext.handleAdminTrustAdd(admin: ServeTrustAdmin) {
    val body =
      withContext(Dispatchers.IO) {
        call.receiveStream().use { readCapped(it, MAX_ADMIN_BODY_BYTES) }
      }
    if (body == null) {
      call.respondText("request body too large", status = HttpStatusCode.PayloadTooLarge)
      return
    }
    val entry = runCatching {
      JSON.decodeFromString(AdminTrustEntry.serializer(), body.decodeToString())
    }
      .getOrElse {
        call.respondText("invalid trust entry: ${it.message}", status = HttpStatusCode.BadRequest)
        return
      }
    respondAdminTrustResult(withContext(Dispatchers.IO) { admin.add(entry) })
  }

  /** Map a [ServeTrustAdmin.Result] onto its HTTP status + JSON body. */
  private suspend fun RoutingContext.respondAdminTrustResult(result: ServeTrustAdmin.Result) {
    when (result) {
      is ServeTrustAdmin.Result.Ok ->
        call.respondText(
          JSON.encodeToString(
            AdminTrustResult.serializer(),
            AdminTrustResult(producer = result.summary, status = "ok", warning = result.warning),
          ),
          ContentType.Application.Json,
        )
      is ServeTrustAdmin.Result.Invalid ->
        call.respondText(result.reason, status = HttpStatusCode.BadRequest)
      is ServeTrustAdmin.Result.Conflict ->
        call.respondText(result.reason, status = HttpStatusCode.Conflict)
    }
  }

  /**
   * The [ServeBundleHost] carrying a catalog's browse metadata: the host itself when static, or the
   * baked host behind a [ServeCatalogLiveHost]. Null for a plain daemon module.
   */
  /**
   * The catalogs on the front-page index now: from [catalogLoads] when wired (the admin API changes
   * the set at runtime), else the constructor's [catalogSessions].
   */
  private fun listedCatalogs(): List<String> =
    catalogLoads?.snapshot()?.filter { it.config.listed }?.map { it.config.system }
      ?: catalogSessions

  /** The served-but-unlisted catalogs right now; the [listedCatalogs] counterpart. */
  private fun unlistedCatalogs(): List<String> =
    catalogLoads?.snapshot()?.filterNot { it.config.listed }?.map { it.config.system }
      ?: appCatalogSessions

  /**
   * Whether [first], a request's first path segment on a site host, names one of the server's own
   * routes. Everything else 404s there.
   *
   * An allowlist ([ServeSites.RESERVED_SYSTEMS]) because enumerating foreign sessions can't keep up
   * with every way a session can exist (uploads, suspended entries, `--revisions` refs the factory
   * would build). A missing top-level route then fails visibly rather than leaking.
   *
   * Letting a reserved segment through is only safe while no session can be named one (Ktor matches
   * whole paths, this a prefix), so [ServeBundleStore.sanitizeName] refuses reserved upload names
   * and [ServeSites] refuses reserved catalog ids.
   */
  private fun isRootedRoute(first: String): Boolean {
    // The visitor's own just-redeemed playground session (unguessable token id) must be allowed
    // through.
    if (playgroundRedeem?.isRedeemedSession(first) == true) return true
    return first in ServeSites.RESERVED_SYSTEMS
  }

  /**
   * The UI builder's page routes (shell, assets, designs index, create/copy, access and history
   * pages) under [base]: `/ui-builder`, or `""` at the builder host root with
   * `--ui-builder-host-root`. Handlers read only route parameters, so one handler serves both.
   */
  private fun Route.uiBuilderPageRoutes(base: String) {
    // The builder's static shell is public like the Wasm assets; design data and mutations are
    // separately authenticated.
    if (base.isNotEmpty())
      get(base) {
        if (uiBuilderDir == null) call.respondText("not found", status = HttpStatusCode.NotFound)
        else {
          // With the query: on a token-gated host the Wasm client reads `?token=` from
          // `location.search`.
          val query = call.request.queryString()
          call.respondRedirect(if (query.isEmpty()) "$base/" else "$base/?$query")
        }
      }
    // A person's index over only the designs the service lets this actor read; no delete or
    // document replacement (unlike `/admin/ui-builder`).
    get("${base}/designs") { handleUiBuilderDesigns() }
    // Create is a POST answered with a `303` to the design's permalink, so the browser lands on a
    // URL safe to reload and share.
    post("${base}/designs") { handleUiBuilderCreate() }
    // Copy from an existing design. Registered before the compatibility route below, whose
    // `{catalog}` would otherwise swallow `designs`.
    post("${base}/designs/copy") { handleUiBuilderCopy() }
    // Compatibility for creation forms emitted by older builder bundles.
    post("${base}/{catalog}") { handleUiBuilderCreate() }
    // Sharing one design as a page. A literal `access` segment outranks `{path...}`, so other paths
    // still serve the editor shell.
    get("${base}/{designId}/access") { handleUiBuilderAccess() }
    post("${base}/{designId}/access") { handleUiBuilderAccessUpdate() }
    // A design's history: its retained revisions as pictures, each one openable, restorable
    // (forward, as a new revision) and forkable into a design of its own.
    get("${base}/{designId}/history") { handleUiBuilderHistory() }
    post("${base}/{designId}/history/{revision}/restore") { handleUiBuilderRestore() }
    post("${base}/{designId}/history/{revision}/fork") { handleUiBuilderFork() }
    // Delete one's own design. Owner-only, enforced by the service.
    post("${base}/{designId}/delete") { handleUiBuilderDelete() }
    // Shared file-manager metadata. A move changes no design revision, but it is visible to
    // every collaborator, so the design's WRITE action gates the form.
    post("${base}/{designId}/folder") { handleUiBuilderFolderMove() }
    // Compatibility for bookmarks emitted before the catalog became document-only state.
    get("${base}/{catalog}/{designId}/access") { handleUiBuilderAccess() }
    post("${base}/{catalog}/{designId}/access") { handleUiBuilderAccessUpdate() }
    // A runtime id is an exact immutable pin. There is deliberately no unversioned or
    // `latest` route: an unavailable pin has to surface as an explicit migration decision.
    get("${base}/runtime/{runtimeId}/{path...}") { handleUiBuilderRuntimeAsset() }
    // The bundle under a content-addressed prefix. Registered before the catch-all so the
    // prefix is matched as a version rather than as the first path segment of a bundle file.
    get("${base}/$UI_BUILDER_VERSION_SEGMENT/{version}/{path...}") {
      handleUiBuilderVersionedAsset()
    }
    get("${base}/{path...}") { handleUiBuilderAsset() }
  }

  /** `?a=b` for a non-empty query string, else `""` — for rebuilding a URL we are redirecting. */
  private fun String.prefixedQuery(): String = if (isEmpty()) "" else "?$this"

  /**
   * One percent-decoded path segment, or the segment verbatim if the encoding is invalid. Used to
   * compare against catalog ids.
   */
  private fun decodeSegment(raw: String): String = runCatching {
    java.net.URLDecoder.decode(raw, Charsets.UTF_8)
  }
    .getOrDefault(raw)

  /**
   * `/playground?from=<system>/<previewId>` for a preview, or null when this host wouldn't honour
   * it (no lane, no fetcher, no recorded source path, or a catalog it can't compile). Checked here
   * so dead links are never rendered. Carries the access token.
   */
  private fun RoutingContext.playgroundLinkFor(
    host: ServeHost,
    system: String,
    previewId: String,
    sourceFile: String?,
  ): String? {
    val playgroundService = playgroundService ?: externalPlaygroundLinks
    if (playgroundService == null || playgroundSeeds == null) return null
    // Same dead end the catalog-level handoff is withheld for: a site host whose OAuth cannot round
    // trip would offer an editor that ends in a 401.
    if (!playgroundReachable()) return null
    if (sourceFile.isNullOrBlank()) return null
    // Only offer the handoff when this catalog is a compile target here; otherwise the editor would
    // compile against whichever catalog is first and report unresolved references.
    if (!playgroundService.compilesCatalog(system)) return null
    // The same condition the resolver applies: plain sessions or uploads can carry a `sourceFile`
    // with no catalog source to resolve it.
    if (catalogBundleHost(host)?.catalogSource == null) return null
    val from = WebEscaping.urlEncodeSegment(system) + "/" + WebEscaping.urlEncodeSegment(previewId)
    val token =
      if (!linksCarryToken()) "" else "&token=" + WebEscaping.urlEncodeSegment(linkToken())
    return "/playground?from=$from$token"
  }

  /**
   * `/playground?catalog=<system>` for a catalog landing: preselect the design system with the
   * starter snippet. Null without a lane or when this host can't compile the catalog (as
   * [playgroundLinkFor]).
   */
  /**
   * Whether a playground handoff can complete from this request's origin. The playground is
   * GitHub-gated, so where the OAuth round trip can't return to this origin the links are withheld,
   * like the live prompts.
   */
  private fun RoutingContext.playgroundReachable(): Boolean =
    githubAuth == null || oauthCanRoundTrip()

  private fun RoutingContext.playgroundLinkForCatalog(system: String): String? {
    if (!playgroundReachable()) return null
    val playgroundService = playgroundService ?: externalPlaygroundLinks ?: return null
    if (!playgroundService.compilesCatalog(system)) return null
    val token =
      if (!linksCarryToken()) "" else "&token=" + WebEscaping.urlEncodeSegment(linkToken())
    return "/playground?catalog=${WebEscaping.urlEncodeSegment(system)}$token"
  }

  /**
   * The counterpart's render in the `compareWith` sibling, as a second source for the viewer's spec
   * lane.
   *
   * Both halves must resolve: the catalog's `compareWith` names the sibling system and the
   * component's `parallel` names the counterpart. Any failure means no second source:
   * 1. this hostname serves the whole box, not one catalog (a top-level site [ServeSites] 404s
   *    neighbours);
   * 2. this catalog declares `compareWith`;
   * 3. this preview's component declares `parallel`;
   * 4. the sibling is served on this host;
   * 5. the sibling has a preview for that component.
   *
   * Uses [ServeSessionRegistry.peekHost], never `lease`, so building a page never wakes a suspended
   * sibling.
   */
  /**
   * The counterpart render in the `compareWith` sibling, or null. Split from [parallelSpecSource]
   * because the layer diff ([handleParallelLayers]) needs the walk without a URL; the site-host
   * guard lives in the caller.
   */
  private data class ResolvedParallel(
    val system: String,
    val host: ServeHost,
    val preview: ServePreview,
    /** The counterpart component the pairing named, for naming the pair where a render doesn't. */
    val componentId: String,
    val label: String,
    val basis: ServeParallelPairing.Basis,
    /** This render's own cell, as a reader is told it — empty for the component's default. */
    val cell: String,
  ) {
    /**
     * How the pair was formed, for a provenance line. A cell the sibling doesn't draw is stated,
     * never silently paired with the default.
     */
    val pairedOn: String =
      when {
        basis == ServeParallelPairing.Basis.KIT_CELL ->
          ", paired on the design-kit node both catalogs map this cell to"
        basis == ServeParallelPairing.Basis.VARIANT_CELL && cell.isNotEmpty() ->
          ", paired on the $cell cell"
        basis == ServeParallelPairing.Basis.CANONICAL && cell.isNotEmpty() ->
          " — its default render, because that catalog publishes no $cell cell for this component"
        else -> ""
      }
  }

  /**
   * The cross-catalog pairing for one preview, memoised for this request. The viewer and compare
   * wall ask repeatedly (kit reference [pairedDesignSpecSource], sibling render
   * [parallelSpecSource], layer diff). Caches the resolved pairing per preview and the sibling's
   * previews grouped by component per sibling. Scoped to one `ApplicationCall` like
   * [agentGrantFor], so refreshes are never answered from stale indexes.
   */
  private fun RoutingContext.resolveParallel(
    host: ServeHost,
    preview: ServePreview,
  ): ResolvedParallel? {
    val memo = call.attributes.computeIfAbsent(RESOLVED_PARALLELS) { ParallelPairings() }
    val key = ParallelPairingKey(host, preview.id)
    // `containsKey`, not `?:` — "resolved to nothing" is the commonest answer here (every preview
    // of a catalog with no sibling), and a plain null read would recompute it every time.
    if (memo.pairings.containsKey(key)) return memo.pairings[key]
    val resolved = computeParallel(memo, host, preview)
    memo.pairings[key] = resolved
    return resolved
  }

  /** [resolveParallel] without the memo — call that, not this. */
  private fun computeParallel(
    memo: ParallelPairings,
    host: ServeHost,
    preview: ServePreview,
  ): ResolvedParallel? {
    val bundle = catalogBundleHost(host) ?: return null
    val siblingSystem = bundle.compareWithSystem?.takeIf { it.isNotBlank() } ?: return null
    val componentId = preview.componentId?.takeIf { it.isNotBlank() } ?: return null
    val parallelId = bundle.parallelByComponentId[componentId] ?: return null
    val siblingHost = sessions.peekHost(siblingSystem) ?: return null
    // The sibling's render of this cell (matched by design-kit node, else state/props/size), then
    // its canonical sticker; see [ServeParallelPairing].
    val pairing =
      ServeParallelPairing.pair(
        preview = preview,
        kitNodes = ServeParallelPairing.kitNodesOf(host.designReferencesFor(preview.id)),
        candidates = memo.candidatesOf(siblingSystem, siblingHost)[parallelId].orEmpty(),
        kitNodesFor = { ServeParallelPairing.kitNodesOf(siblingHost.designReferencesFor(it.id)) },
      ) ?: return null
    val siblingBundle = catalogBundleHost(siblingHost)
    return ResolvedParallel(
      system = siblingSystem,
      host = siblingHost,
      preview = pairing.preview,
      componentId = parallelId,
      label =
        siblingBundle?.title?.takeIf { it.isNotBlank() }
          ?: siblingHost.label.ifBlank { siblingSystem },
      basis = pairing.basis,
      cell = ServeParallelPairing.cellLabel(preview),
    )
  }

  /**
   * The sibling catalog's render route for [previewId] on this same server, with this page's
   * credential. Uses [linkToken], not the server's own token, so a grant holder's page never leaks
   * the operator's.
   */
  /**
   * URL for one verified backplate with the page's credential; mirrors [parallelRenderUrl]. The id
   * is a content hash.
   */
  private fun RoutingContext.pageAssetUrl(system: String, assetId: String): String =
    "/" +
      WebEscaping.urlEncodeSegment(system) +
      "/pages/assets/" +
      WebEscaping.urlEncodeSegment(assetId) +
      if (!linksCarryToken()) "" else "?token=" + WebEscaping.urlEncodeSegment(linkToken())

  private fun RoutingContext.parallelRenderUrl(system: String, previewId: String): String =
    "/" +
      WebEscaping.urlEncodeSegment(system) +
      "/render/" +
      WebEscaping.urlEncodeSegment(previewId) +
      ".png" +
      if (!linksCarryToken()) "" else "?token=" + WebEscaping.urlEncodeSegment(linkToken())

  private fun RoutingContext.parallelSpecSource(
    host: ServeHost,
    preview: ServePreview,
  ): ServeWeb.SpecSource? {
    // On a site host the sibling's render is unreachable (the interceptor 404s other systems), so
    // don't offer the source.
    if (siteSystem() != null) return null
    val parallel = resolveParallel(host, preview) ?: return null
    val siblingSystem = parallel.system
    val siblingPreview = parallel.preview
    val siblingLabel = parallel.label
    val pairedOn = parallel.pairedOn
    return ServeWeb.SpecSource(
      id = "parallel",
      label = siblingLabel,
      // Same origin, which satisfies the lane's guard in `viewer.ts` `specRasterSrc()`.
      // …with the page's credential, since `/render/` is token-gated off `--public`. See
      // [parallelRenderUrl].
      rasterUrl = parallelRenderUrl(siblingSystem, siblingPreview.id),
      // The provenance caveat: this panel is another catalog's render under its own theme, knobs
      // and overrides.
      provenance =
        "$siblingLabel's own render of ${siblingPreview.componentId ?: parallel.componentId}" +
          "$pairedOn, " +
          "under that catalog's theme and knobs — not this page's.",
    )
  }

  /**
   * The design reference attached to this preview's paired sibling, used as the design target for a
   * Remote Compose preview that doesn't duplicate the reference.
   */
  private fun RoutingContext.pairedDesignSpecSource(
    host: ServeHost,
    preview: ServePreview,
  ): ServeWeb.SpecSource? {
    // As with [parallelSpecSource], a top-level site cannot fetch a neighbouring system's route.
    if (siteSystem() != null) return null
    val parallel = resolveParallel(host, preview) ?: return null
    val reference =
      parallel.host.designReferencesFor(parallel.preview.id).firstOrNull() ?: return null
    val label = ServeWeb.designToolLabel(reference.source.provider) ?: "Design spec"
    return ServeWeb.SpecSource(
      id = "kit",
      label = label,
      rasterUrl =
        "/" +
          WebEscaping.urlEncodeSegment(parallel.system) +
          "/reference/" +
          WebEscaping.urlEncodeSegment(reference.id) +
          ".png" +
          if (!linksCarryToken()) "" else "?token=" + WebEscaping.urlEncodeSegment(linkToken()),
      provenance =
        "$label reference mapped by ${parallel.label}'s paired " +
          "${parallel.preview.componentId ?: parallel.componentId}${parallel.pairedOn}.",
    )
  }

  /**
   * `GET /{system}/parallel/{preview}`: the cross-catalog layer diff for one render, as a page or
   * `?format=json`.
   *
   * Answers what rasters can't: the resolved font family, token values and insets on each side
   * ([ServeParallelLayers]). Read-only and cheap: both sides come from `annotations/index.json`,
   * via [ServeSessionRegistry.peekHost], with nothing rendered. Works on a top-level site too,
   * since it's joined server-side; only the link to the counterpart's viewer is withheld there.
   */
  private suspend fun RoutingContext.handleParallelLayers(sessionInPath: Boolean) {
    if (rejectBadToken()) return
    val json = wantsJson()
    if (rejectUnknownFormat()) return
    val sessionId = selectedSessionId(sessionInPath)
    val (webSessionId, basePath) = webSessionAndBase(sessionInPath)
    suspend fun missing(message: String) {
      if (json) call.respond(HttpStatusCode.NotFound) else respondNotFoundHtml(message)
    }
    withLeasedSession(
      sessionId,
      onMissing = { missing("That design system was not found on this server.") },
    ) { renderHost ->
      val previewId = call.parameters["name"].orEmpty()
      val preview = renderHost.previews.firstOrNull { it.id == previewId }
      if (preview == null) {
        missing("That preview was not found in this design system.")
        return@withLeasedSession
      }
      val parallel = resolveParallel(renderHost, preview)
      if (parallel == null) {
        // No `compareWith`, no `parallel`, or no served sibling: all mean there is no counterpart
        // to diff against.
        missing("This render has no counterpart in a sibling design system on this server.")
        return@withLeasedSession
      }
      val diff =
        ServeParallelLayers.diff(
          here = renderHost.annotationsForPreview(preview.id),
          there = parallel.host.annotationsForPreview(parallel.preview.id),
        )
      if (diff.isEmpty) {
        // Neither catalog publishes a layer for this cell: 404 in both spellings, since an empty
        // document would read as agreement.
        missing(
          "Neither this catalog nor its sibling publishes annotation layers for this render, " +
            "so there is nothing to compare."
        )
        return@withLeasedSession
      }
      if (json) {
        markGeneration("static-page", pageCacheControl())
        call.respondText(
          ServeParallelLayersPayload.encode(
            system = sessionId,
            previewId = preview.id,
            componentId = preview.componentId,
            cell = parallel.cell,
            sibling =
              ServeParallelLayersPayload.Sibling(
                system = parallel.system,
                label = parallel.label,
                previewId = parallel.preview.id,
                componentId = parallel.preview.componentId ?: parallel.componentId,
                pairedBy = ServeParallelLayersPayload.pairedByWire(parallel.basis),
              ),
            diff = diff,
          ),
          ContentType.Application.Json,
        )
        return@withLeasedSession
      }
      markGeneration("static-page", pageCacheControl())
      call.respondText(
        ServeWeb.parallelLayersPage(
          moduleLabel = renderHost.label,
          preview = preview,
          siblingLabel = parallel.label,
          siblingPreviewId = parallel.preview.id,
          // Withheld on a site host, where a neighbour's `/{system}/…` is this site's own 404.
          siblingHref =
            if (siteSystem() != null) ""
            else
              "/" +
                WebEscaping.urlEncodeSegment(parallel.system) +
                "/p/" +
                WebEscaping.urlEncodeSegment(parallel.preview.id) +
                if (!linksCarryToken()) ""
                else "?token=" + WebEscaping.urlEncodeSegment(linkToken()),
          pairedOn = parallel.pairedOn,
          cell = parallel.cell,
          diff = diff,
          token = linkToken(),
          sessionId = webSessionId,
          basePath = basePath,
          isPublic = isPublic,
          themeCss = catalogBundleHost(renderHost)?.webThemeCss.orEmpty(),
          version = SERVE_VERSION,
          displayTitle = catalogBundleHost(renderHost)?.title,
          sessionInOrigin = siteSystem() != null,
          changelogHref = changelogHref(sessionId, basePath, webSessionId),
          reportIssue =
            pageScopedReportIssue(renderHost, sessionId, "this cross-catalog layer comparison"),
          unfurl = ServeWeb.UnfurlMetadata(pageUrl = externalPageUrl()),
        ),
        ContentType.Text.Html,
      )
    }
  }

  /**
   * The page-scoped "report a catalog issue" for surfaces that name no single preview (landing
   * grid, pages index, design page, motion browser), so the floating launcher's catalog half
   * appears there. Names the page (URL with query), the catalog build and tool version, and drops
   * preview rows as [ServeIssueReport.body] drops absent facts. [subject] is the affordance's
   * wording. Previews keep their own per-preview reports.
   */
  /**
   * The page-scoped catalog report, or null when there is no catalog to file against.
   *
   * Gated on a catalog existing ([ServeBundleHost.isCatalog]), not on a named repo: a catalog
   * without source or provenance still falls back to [ServeIssueReport.repoFor]'s default
   * (`ServeDesignPageRoutingTest` and `ServeHttpRoutingTest` assert this). A plain module, a
   * `--bundles` directory or an uploaded bundle is not a catalog.
   */
  private fun RoutingContext.pageScopedReportIssue(
    renderHost: ServeHost,
    sessionId: String,
    subject: String,
    /**
     * Whether this page can name visitor-picked comparisons (only the comparison wall). True adds
     * [ServeIssueReport.LOCATORS_PLACEHOLDER] and the facts a browser-written locator can't derive
     * (system, delivery revision); elsewhere the placeholder would be filed verbatim.
     */
    pickable: Boolean = false,
  ): ServeWeb.ReportIssue? {
    val bundleHost = catalogBundleHost(renderHost)?.takeIf { it.isCatalog } ?: return null
    val context =
      ServeIssueReport.Context(
        repo = ServeIssueReport.repoFor(bundleHost.catalogSource, bundleHost.provenance),
        system = sessionId,
        catalog = bundleHost.provenance?.let { "${it.repo}@${it.branch}" },
        toolVersion = bundleHost.provenance?.toolVersion,
        pageUrl = ServeIssueReport.withoutToken(pageUrlPinningChrome()),
        publicRender = isPublic,
      )
    return ServeWeb.ReportIssue(
      action = ServeIssueReport.action(context.repo),
      body = ServeIssueReport.body(context),
      bodyTemplate =
        ServeIssueReport.body(
          context,
          renderPlaceholder = true,
          locatorsPlaceholder = pickable,
        ),
      repo = context.repo,
      login = githubAuth?.currentLogin(call),
      subject = subject,
      locatorSystem = if (pickable) context.system else null,
      locatorRevision = if (pickable) context.catalog else null,
    )
  }

  /**
   * This page's URL with the resolved presentation mode pinned via `?chrome=`, so a triager with a
   * different cookie sees the reported surface. Both modes are pinned.
   */
  private fun RoutingContext.pageUrlPinningChrome(): String {
    val url = externalPageUrl()
    // A recognised pin is kept; an unrecognised `chrome=` (which `interfaceMode` ignores) is
    // replaced rather than appended to, so the URL names the mode actually served.
    if (interfaceMode(call.request.queryParameters[CHROME_PARAM]) != null) return url
    val mode = if (componentBrowserMode()) "catalog" else "dev"
    val base = url.substringBefore('?')
    val kept =
      url.substringAfter('?', "").split('&').filter {
        it.isNotEmpty() && queryParamName(it) != CHROME_PARAM
      }
    return "$base?" + (kept + "$CHROME_PARAM=$mode").joinToString("&")
  }

  /**
   * The decoded name of a raw `k=v` query pair. Ktor decodes parameter names, so `?%63hrome=x` is
   * `chrome` to [interfaceMode]; comparing raw text would keep that pair and append a second
   * `chrome=`. `plusIsSpace` matches Ktor's query decoding. Invalid percent-encoding keeps its raw
   * text, so only pairs the server reads as the chrome pin are removed.
   */
  private fun queryParamName(pair: String): String {
    val raw = pair.substringBefore('=')
    return runCatching { raw.decodeURLQueryComponent(plusIsSpace = true) }.getOrDefault(raw)
  }

  /**
   * The ground and shape a `?bg=` request should composite for [previewId], or null when unknown.
   * Resolved via [ServeWeb.backdropFor] and [ServeWeb.effectiveDeviceFrame], the same functions the
   * viewer, grid and wall use for CSS, so a matted PNG matches its page.
   */
  private fun renderStage(
    renderHost: ServeHost,
    sessionId: String?,
    previewId: String,
    overrides: Map<String, String>,
  ): ServeRenderMatte.Stage? {
    val preview = renderHost.previews.firstOrNull { it.id == previewId } ?: return null
    val darkFirst =
      ServeWeb.SystemDisplay.resolveDarkFirst(
        // The system name as the cmp-jvm lane resolves it: on a mounted catalog the session id is
        // the system.
        sessionId.orEmpty(),
        catalogBundleHost(renderHost)?.stageSurface,
      )
    val frame = ServeWeb.effectiveDeviceFrame(preview, overrides)
    return ServeRenderMatte.Stage(
      backdrop = ServeWeb.backdropFor(preview, darkFirst, overrides["uiMode"]),
      clip = frame?.let { PreviewClip.resolve(it.isRound, it.widthDp, it.heightDp) },
      frameWidthDp = frame?.widthDp,
      frameHeightDp = frame?.heightDp,
    )
  }

  /**
   * The component's related directories for the viewer's drawer: other catalogs about the
   * component, one per catalog, a row per destination. Resolved here because it needs the registry
   * ([ServeRelatedCatalogs] holds the policy).
   *
   * Keyed on the component: declarations from every render are unioned and shown on all of them, so
   * samples declared on one cell are findable from any.
   *
   * Fails soft: unregistered systems are dropped by [ServeRelatedCatalogs.resolve], registered ones
   * without a host are marked not live, and unpublished component ids are dropped here.
   */
  /**
   * The back-links for one component: catalogs whose `related` links point at it, and which of
   * their components points.
   *
   * `related` is declared in one place (the samples catalog is regenerated from upstream and
   * declares nothing), so the reverse is derived here. On a box with only the samples catalog there
   * are no back-links, which is correct. Resident catalogs only ([ServeSessionRegistry.peekHost]),
   * so nothing is woken.
   */
  private fun RoutingContext.componentBackLinkDirectories(
    preview: ServePreview,
    selfSystem: String,
  ): List<ServeWeb.ComponentDirectory> {
    val componentId = ServeIssueReport.componentIdFor(preview)
    if (componentId.isBlank()) return emptyList()
    val memo = call.attributes.computeIfAbsent(RELATED_INVERSES) { RelatedInverses() }
    return sessions
      .knownSessionIds()
      .filter { it != selfSystem }
      .mapNotNull { system ->
        val source = relatedCatalogOf(system) ?: return@mapNotNull null
        // The cheap guard first: most catalogs declare no `related` at all, and one that does not
        // cannot be pointing at anything.
        if (source.relatedByComponentId.isEmpty()) return@mapNotNull null
        val sourceComponentIds =
          memo.of(system, source.relatedByComponentId, selfSystem)[componentId].orEmpty().ifEmpty {
            return@mapNotNull null
          }
        val rows = sourceComponentIds.mapNotNull { sourceComponentId ->
          val target = source.componentsById[sourceComponentId] ?: return@mapNotNull null
          ServeWeb.ComponentDirectoryRow(
            label = target.label,
            href =
              "/" +
                WebEscaping.urlEncodeSegment(system) +
                "/p/" +
                WebEscaping.urlEncodeSegment(target.previewId) +
                requestQuerySuffix(),
          )
        }
        if (rows.isEmpty()) null
        else
          ServeWeb.ComponentDirectory(
            "about",
            // Named for what the rows are, from the source catalog's declared role, since `related`
            // is directed and a fixed word would read backwards from one end. Without a declared
            // role, the catalog's heading is used.
            source.heading,
            rows,
          )
      }
  }

  /**
   * The related-link facts for [system] without waking an idle daemon: the resident host is
   * authoritative (including empty), else the snapshot taken at suspension — the same ladder as
   * [sourceLocationFor].
   */
  private fun relatedCatalogOf(system: String): RelatedCatalog? {
    val host = sessions.peekHost(system)
    return when {
      host != null -> relatedCatalogOf(host)
      sessions.isKnownSession(system) -> relatedCatalogsSeen[system]
      else -> null
    }
  }

  private fun RoutingContext.componentRelatedDirectories(
    renderHost: ServeHost,
    preview: ServePreview,
    selfSystem: String,
  ): List<ServeWeb.ComponentDirectory> {
    val bundle = catalogBundleHost(renderHost) ?: return emptyList()
    if (bundle.relatedByComponentId.isEmpty()) return emptyList()
    val componentId = ServeIssueReport.componentIdFor(preview)
    // Every render of this component, so a link declared on one cell is found from all of them.
    val declared =
      renderHost.previews
        .filter { ServeIssueReport.componentIdFor(it) == componentId }
        .flatMap { bundle.relatedByComponentId[ServeIssueReport.componentIdFor(it)].orEmpty() }
    if (declared.isEmpty()) return emptyList()
    // The registration check is asked per DECLARED system rather than off the whole session list:
    // a catalog declares a handful of links and the registry holds every session this box serves.
    val registered = declared.map { it.system }.filter { sessions.isKnownSession(it) }.toSet()
    val links =
      ServeRelatedCatalogs.resolve(
        entries = declared,
        componentId = componentId,
        selfSystem = selfSystem,
        registered = registered,
        // peekHost, never lease: drawing a drawer row must not wake a daemon.
        isLive = { sessions.peekHost(it) != null },
      )
    if (links.isEmpty()) return emptyList()
    // One directory per destination catalog in declared order, named by that catalog's own heading.
    return links
      .groupBy { it.system }
      .mapNotNull { (system, systemLinks) ->
        val host = sessions.peekHost(system)
        val heading =
          host?.let { ServeWeb.catalogHeading(catalogBundleHost(it)?.title, it.label) } ?: system
        val rows = systemLinks.mapNotNull { link ->
          // A live destination is resolved to a real preview (and its own name for the component);
          // without a host the row uses what the link declared.
          val target =
            host?.previews?.firstOrNull { ServeIssueReport.componentIdFor(it) == link.componentId }
          if (host != null && target == null) return@mapNotNull null
          ServeWeb.ComponentDirectoryRow(
            label = link.label ?: target?.let { ServeWeb.rowDisplayName(it) } ?: link.componentId,
            href =
              "/" +
                WebEscaping.urlEncodeSegment(system) +
                "/p/" +
                WebEscaping.urlEncodeSegment(target?.id ?: link.componentId) +
                requestQuerySuffix(),
            live = link.live && target != null,
            // The catalog's own wording for the relationship, where the row is already showing
            // the destination's name instead.
            title =
              link.label?.takeIf {
                it != (target?.let { p -> ServeWeb.rowDisplayName(p) } ?: link.componentId)
              },
          )
        }
        if (rows.isEmpty()) null else ServeWeb.ComponentDirectory("related", heading, rows)
      }
  }

  /**
   * The previews a system's landing grid shows: all, less a catalog's A2UI playground preview,
   * which the host lists for `/{system}/a2ui` but isn't a card
   * ([ServeCatalogLiveHost.playgroundPreviewIds]).
   */
  private fun landingPreviews(host: ServeHost): List<ServePreview> {
    val playgrounds = (host as? ServeCatalogLiveHost)?.playgroundPreviewIds.orEmpty()
    return if (playgrounds.isEmpty()) host.previews
    else host.previews.filterNot { it.id in playgrounds }
  }

  private fun catalogBundleHost(host: ServeHost): ServeBundleHost? =
    when (host) {
      is ServeBundleHost -> host
      is ServeCatalogLiveHost -> host.bakedHost as? ServeBundleHost
      is ServePerPreviewLiveHost -> host.bakedHost as? ServeBundleHost
      else -> null
    }

  /**
   * The front-page index: published design systems ([catalogSessions]) as cards linking to
   * `/<system>/`. Unlisted app catalogs ([appCatalogSessions]) are not indexed. See
   * [homeSystemsFor].
   */
  private suspend fun RoutingContext.handleHomeIndex() {
    val systems = withContext(Dispatchers.IO) { homeSystemsFor(listedCatalogs()) }
    // Match the first card a visitor actually sees after the homepage's publisher grouping, not
    // merely the operator's input order.
    val featured =
      ServeWeb.homeSections(systems)
        .asSequence()
        .flatMap { it.systems.asSequence() }
        .firstOrNull { it.heroImage != null || it.heroPreviewId != null }
    val featuredPath =
      featured?.heroImage?.path
        ?: featured?.heroPreviewId?.let {
          "/${WebEscaping.urlEncodeSegment(featured.system)}/render/" +
            "${WebEscaping.urlEncodeSegment(it)}.png"
        }
    val featuredUrl = featuredPath?.let { externalOrigin() + it + requestQuerySuffix() }
    // The full render behind the featured card, as the fallback unfurl image (the `/hero/`
    // thumbnail is below unfurlers' large-card floor). Read from remembered metadata, since
    // `peekHost` is null for an idle catalog.
    val featuredRender =
      featured?.heroPreviewId?.let { id ->
        // Same rule as the viewer and the catalog landing: the URL below inherits the request's
        // query, so a shared `/?widthPx=1200` names a re-render the baked size does not describe.
        if (requestCarriesOverrides()) return@let null
        val size = catalogMetaSeen[featured.system]?.heroRenderSize ?: return@let null
        val path =
          "/${WebEscaping.urlEncodeSegment(featured.system)}/render/" +
            "${WebEscaping.urlEncodeSegment(id)}.png"
        (externalOrigin() + path + requestQuerySuffix()) to size
      }
    // What the front door advertises is a drawn 1200×630 card ([ServeSocialCard]), since no catalog
    // render has that shape. Composed from the already-baked hero thumbnails; on `Dispatchers.IO`
    // like `homeSystemsFor`, since the first visit after a change rasterizes.
    val card =
      withContext(Dispatchers.IO) {
        socialCards.cardFor(
          ServeSocialCard.Spec(
            title = ServeWeb.HOME_TITLE,
            subtitle = ServeWeb.homeCardSubtitle(systems),
            heroes =
              ServeWeb.homeSections(systems)
                .asSequence()
                .flatMap { it.systems.asSequence() }
                .mapNotNull { catalogMetaSeen[it.system]?.heroImage }
                .take(ServeSocialCard.MAX_HEROES)
                .toList(),
          )
        )
      }
    val unfurl =
      if (card != null)
        ServeWeb.UnfurlMetadata(
          pageUrl = externalPageUrl(),
          imageUrl = externalOrigin() + ServeSocialCard.PATH_PREFIX + "/" + card.fileName,
          imageWidth = card.width,
          imageHeight = card.height,
        )
      else
      // Only when the card couldn't be encoded at all. The featured render is a worse picture but
      // a real one, and `twitterCard` now demotes it to the small card its shape can fill.
      ServeWeb.UnfurlMetadata(
          pageUrl = externalPageUrl(),
          imageUrl = featuredRender?.first ?: featuredUrl,
          imageWidth = featuredRender?.second?.first,
          imageHeight = featuredRender?.second?.second,
        )
    val lanCard = operatorLanCard()
    markGeneration("static-page", if (lanCard != null) "no-store" else pageCacheControl())
    respondWithLanCard(
      lanCard,
      ServeWeb.homeIndexPage(
        systems,
        linkToken(),
        isPublic = isPublic,
        componentBrowser = componentBrowserMode(),
        version = SERVE_VERSION,
        unfurl = unfurl,
        githubAuth = githubAuthStatus(),
        uiBuilder = uiBuilderInvite(),
      ),
      ContentType.Text.Html,
    )
  }

  /**
   * The catalogs a crawler may enumerate: the listed ones (unlisted app catalogs excluded, as on
   * the front door), with preview ids and generation dates.
   *
   * Reads via [ServeSessionRegistry.peekHost] + [catalogMetaSeen] so a sitemap fetch never resumes
   * daemons; never-resident catalogs appear once seen. Doesn't call [rememberCatalogMeta], which
   * bakes hero thumbnails and would put that work on the request coroutine; it reads the two fields
   * directly.
   */
  private fun crawlableCatalogs(onlySystem: String? = null): List<ServeSiteIndex.CatalogEntry> =
    (if (onlySystem != null) listOf(onlySystem) else listedCatalogs()).mapNotNull { system ->
      val host = sessions.peekHost(system)
      val meta = catalogMetaSeen[system]
      val previewIds = host?.previews?.map { it.id } ?: meta?.previewIds ?: return@mapNotNull null
      val generatedAt =
        host?.let { catalogBundleHost(it)?.provenance?.generatedAt }
          ?: meta?.provenance?.generatedAt
      ServeSiteIndex.CatalogEntry(
        system = system,
        previewIds = previewIds,
        lastModified = generatedAt,
      )
    }

  /** `GET /robots.txt`: what a crawler may ask this server for. See [ServeSiteIndex]. */
  private suspend fun RoutingContext.handleRobotsTxt() {
    // Advertised only when non-empty; a token-gated host has no crawlable pages.
    // On a top-level site, gated on the same scoped set the sitemap uses.
    val sitemapUrl =
      if (isPublic && crawlableCatalogs(siteSystem()).isNotEmpty())
        externalOrigin() + "/sitemap.xml"
      else null
    markGeneration("robots", STATIC_RESOURCE_CACHE_CONTROL)
    call.respondText(ServeSiteIndex.robotsTxt(isPublic, sitemapUrl), ContentType.Text.Plain)
  }

  /**
   * `GET /sitemap.xml`: every catalog landing and preview viewer, dated by catalog generation. 404
   * on a token-gated host, whose URLs a crawler couldn't open.
   */
  private suspend fun RoutingContext.handleSitemapXml() {
    if (!isPublic) {
      call.respondText("not found", status = HttpStatusCode.NotFound)
      return
    }
    markGeneration("sitemap", STATIC_RESOURCE_CACHE_CONTROL)
    // A site host publishes ONE catalog, rooted: its landing is `/`, its viewers are `/p/<id>`, and
    // its neighbours are none of a crawler's business here.
    val site = siteSystem()
    call.respondText(
      ServeSiteIndex.sitemapXml(externalOrigin(), crawlableCatalogs(site), rootedSystem = site),
      ContentType.Application.Xml,
    )
  }

  /**
   * `GET /hero/{system}/{name}`: a prebaked front-door hero thumbnail ([ServeHeroImages]).
   *
   * No session lease, render permit, disk read or daemon wake: the bytes are in memory. `{name}` is
   * the content hash, so responses are `immutable` with a year-long `max-age`; `{system}` is
   * cosmetic, so URLs survive refreshes. Gated like the rest.
   */
  private suspend fun RoutingContext.handleHeroImage() {
    if (rejectBadToken()) return
    // Scoped like `/wasm/<system>/…`: `{system}` isn't the first segment, so the canonical-path
    // interceptor doesn't see it, and a site host must not serve a neighbour's hero.
    val site = call.siteSystem()
    val hero =
      if (site != null && call.parameters["system"] != site) null
      else call.parameters["name"]?.let { heroImages.byFileName(it) }
    if (hero == null) {
      call.respondText("no such hero image", status = HttpStatusCode.NotFound)
      return
    }
    call.response.headers.append(HttpHeaders.CacheControl, prebakedImageCacheControl())
    call.response.headers.append(HttpHeaders.ETag, hero.etag)
    if (call.request.headers[HttpHeaders.IfNoneMatch] == hero.etag) {
      call.respond(HttpStatusCode.NotModified)
      return
    }
    call.respondBytes(hero.bytes, ContentType.Image.PNG)
  }

  /**
   * `GET /social/{name}`: a drawn link-unfurl card ([ServeSocialCard]). Ungated, unlike `/hero/`:
   * unfurlers don't replay a page's token, and the card is drawn from public chrome and front-door
   * thumbnails.
   */
  private suspend fun RoutingContext.handleSocialCard() {
    val site = call.siteSystem()
    val card =
      call.parameters["name"]
        ?.let { socialCards.byFileName(it) }
        // A site hostname answers for one catalog, so cards for neighbours (and the front door's
        // own, `system == null`) are refused, even though names are content hashes.
        ?.takeIf { site == null || site in it.systems }
    if (card == null) {
      call.respondText("no such card", status = HttpStatusCode.NotFound)
      return
    }
    call.response.headers.append(HttpHeaders.CacheControl, prebakedImageCacheControl())
    call.response.headers.append(HttpHeaders.ETag, card.etag)
    if (call.request.headers[HttpHeaders.IfNoneMatch] == card.etag) {
      call.respond(HttpStatusCode.NotModified)
      return
    }
    call.respondBytes(card.bytes, ContentType.Image.PNG)
  }

  /**
   * Respond one of the site icons ([ServeSiteIcon]). Not `immutable`: they live at guessed
   * well-known paths and change across deploys, so a day of caching plus ETag.
   */
  /**
   * This box's web app manifest, built once from whether a UI builder is served (its launcher
   * shortcuts) and the fixed brand.
   */
  private val siteManifest: ServeSiteIcon.Icon by lazy {
    ServeSiteIcon.manifest(
      name = "Compose Preview",
      shortName = "Compose Preview",
      startUrl = "/",
      shortcuts = manifestShortcuts,
    )
  }

  /** Launcher shortcuts: the UI builder and its designs, where this box has one. */
  private val manifestShortcuts: List<ServeSiteIcon.Shortcut>
    get() =
      if (designService == null) emptyList()
      else
        listOf(
          ServeSiteIcon.Shortcut(
            "UI builder",
            "/ui-builder/",
            "Start a design or carry on with one",
          ),
          ServeSiteIcon.Shortcut(
            "My designs",
            "/ui-builder/designs",
            "Every design you own or that was shared with you",
          ),
        )

  /** Site-host manifests, keyed by what they are built from so a re-themed catalog rebuilds. */
  private val siteManifests =
    java.util.concurrent.ConcurrentHashMap<List<String>, ServeSiteIcon.Icon>()

  /**
   * The manifest for [call]'s host: [siteManifest] on the main host, or for a top-level site
   * ([ServeSites]) one named and coloured for its catalog. Members resolve against each origin, so
   * each site is a separate installable app.
   */
  private fun manifestFor(call: ApplicationCall): ServeSiteIcon.Icon {
    val system = call.siteSystem() ?: return siteManifest
    val (name, themeCss, _) = call.siteSkin()
    val title = name.ifBlank { system }
    val themeColor = ServeSiteIcon.themeColors(themeCss).first
    return siteManifests.computeIfAbsent(listOf(system, title, themeColor)) {
      ServeSiteIcon.manifest(
        name = title,
        // A launcher truncates past about a dozen characters; the catalog id is the short form
        // its own URLs already use, so prefer it to a name cut mid-word.
        shortName =
          listOf(title, system).firstOrNull { it.length <= SHORT_NAME_MAX }
            ?: title.take(SHORT_NAME_MAX),
        startUrl = "/",
        shortcuts = manifestShortcuts,
        description = "$title — Compose previews from this catalog.",
        themeColor = themeColor,
      )
    }
  }

  /**
   * `push-sw.js` from the serve-web bundle. Uncached, since a worker is identified by URL and must
   * pick up each release. `Service-Worker-Allowed: /` allows whole-origin scope.
   */
  private suspend fun RoutingContext.respondPushServiceWorker() {
    val asset = ServeWebAssets.load(PUSH_SERVICE_WORKER_ASSET)
    if (asset == null) {
      call.respondText("not found", status = HttpStatusCode.NotFound)
      return
    }
    call.response.headers.append(HttpHeaders.CacheControl, "no-cache")
    call.response.headers.append(HttpHeaders.ETag, asset.etag)
    call.response.headers.append(SERVICE_WORKER_ALLOWED, "/")
    if (call.request.headers[HttpHeaders.IfNoneMatch] == asset.etag) {
      call.respond(HttpStatusCode.NotModified)
      return
    }
    call.respondBytes(asset.bytes, ContentType.parse("text/javascript"))
  }

  private suspend fun RoutingContext.respondScreenshot() {
    val icon = ServeSiteIcon.screenshot(call.request.path())
    if (icon == null) call.respondText("not found", status = HttpStatusCode.NotFound)
    else respondSiteIcon(icon)
  }

  private suspend fun RoutingContext.respondSiteIcon(icon: ServeSiteIcon.Icon) {
    if (icon.bytes.isEmpty()) {
      call.respondText("icon unavailable", status = HttpStatusCode.NotFound)
      return
    }
    call.response.headers.append(HttpHeaders.CacheControl, SITE_ICON_CACHE_CONTROL)
    call.response.headers.append(HttpHeaders.ETag, icon.etag)
    if (call.request.headers[HttpHeaders.IfNoneMatch] == icon.etag) {
      call.respond(HttpStatusCode.NotModified)
      return
    }
    call.respondBytes(icon.bytes, ContentType.parse(icon.contentType))
  }

  /**
   * Whether this render request asks for the base preview and nothing else, the only shape a
   * prebaked grid thumbnail can answer (see `?thumb=` in [handleRender]).
   */
  private fun RoutingContext.plainThumbRequest(): Boolean =
    renderParams().entries().none { (key, _) ->
      ServeOverrides.isOverrideParam(key) ||
        key == "scroll" ||
        key == "rcPlayer" ||
        key == "mode" ||
        // A `?bg=` stage changes the bytes, and the thumbnail URL is the hash of un-matted pixels,
        // so take the ordinary render path.
        key == ServeRenderMatte.PARAM ||
        // A pin asks for a different version than the baked thumbnail, so leave this lane.
        key == ServeCatalogRevision.PARAM ||
        key in ServeExplodedSvg.PARAMS
    }

  /**
   * Respond a prebaked grid thumbnail ([ServeHeroImages.gridThumbFor]), cached like `/hero/`: the
   * URL carries the content hash.
   */
  private suspend fun RoutingContext.respondGridThumb(thumb: ServeHeroImages.Thumb) {
    call.response.headers.append(HttpHeaders.CacheControl, prebakedImageCacheControl())
    call.response.headers.append(HttpHeaders.ETag, thumb.etag)
    if (call.request.headers[HttpHeaders.IfNoneMatch] == thumb.etag) {
      call.respond(HttpStatusCode.NotModified)
      return
    }
    call.respondBytes(thumb.bytes, ContentType.Image.PNG)
  }

  /**
   * `GET /status` (HTML, or `?format=json`) and `GET /status.json`: a live snapshot of this host —
   * catalogs with availability/trust/liveness, running daemons, effective config, recent startup
   * failures. Gated like the API routes. JSON is the canonical machine surface.
   */
  private suspend fun RoutingContext.handleStatus(json: Boolean) {
    if (rejectBadToken() || rejectUnknownFormat()) return
    val wantJson = json || wantsJson()
    // Operational state must reach browsers and monitors immediately in both directions: neither
    // a healthy snapshot after failure nor a stale failure after recovery is useful status.
    markGeneration("status", DYNAMIC_RESOURCE_CACHE_CONTROL)
    // On a top-level site, `/status` reports only that app (its catalog row, daemons and failures),
    // filtered from the same snapshot.
    val data = withContext(Dispatchers.IO) { buildStatusData(onlySystem = siteSystem()) }
    val skin = siteSkin()
    if (wantJson) {
      call.respondText(
        JSON.encodeToString(StatusResponse.serializer(), data.toResponse()),
        ContentType.Application.Json,
      )
    } else {
      val grantRows = agentGrantStatusRows()
      call.respondText(
        ServeWeb.statusPage(
          data.toView(
            agentGrants = grantRows,
            agentGrantRequests = agentGrantRequestRows(),
            hiddenAgentGrants = agentGrantHiddenCount(shown = grantRows.size),
          ),
          linkToken(),
          unfurl = ServeWeb.UnfurlMetadata(pageUrl = externalPageUrl()),
          version = SERVE_VERSION,
          siteName = skin.first,
          themeCss = skin.second,
          themeStorageKey = skin.third,
          componentBrowser = componentBrowserMode(),
          githubAuth = githubAuthStatus(),
        ),
        ContentType.Text.Html,
      )
    }
  }

  /**
   * `GET /report-bug`: the server's own bug-report page.
   *
   * Collects what a triager needs about this deployment (build, posture, uptime, JVM/OS, unhealthy
   * catalogs, recent daemon/render failures) plus what the visitor was viewing, from the footer
   * form's `from` path. [ServeWeb.bugReportPage] shows it before filing; [ServeBugReport] builds
   * the body.
   *
   * `from` is browser-supplied and reaches HTML, links and a public issue, so it is accepted only
   * as a same-origin path with the token stripped ([ServeBugReport.sanitizeFrom]), and its preview
   * is resolved by matching an existing id. An unresolvable path just loses the page section.
   */
  private suspend fun RoutingContext.handleBugReport() {
    if (rejectBadToken()) return
    val from = ServeBugReport.sanitizeFrom(call.request.queryParameters[ServeBugReport.FROM_PARAM])
    val ref = ServeBugReport.parsePath(from)
    val pathSystem = ref.system?.let(::decodeQueryValue)
    // Which session the reported page showed, most specific first:
    //  - a top-level site's host;
    //  - a `/{system}/…` path;
    //  - a root-form viewer (`/p/Red`) with `?session=`, else the default session — the common
    // plain `serve` case.
    val system =
      siteSystem()
        ?: pathSystem?.takeIf { sessions.isKnownSession(it) }
        // An explicit `?session=` is honoured anywhere, since query-mode catalog routes have no
        // system in their path.
        ?: explicitSessionId(from)?.takeIf { sessions.isKnownSession(it) }
        // The default session is only assumed for root-form viewers: a path naming an unknown
        // system must not be re-attributed, and server pages (`/`, `/status`, `/docs/…`, a 404)
        // belong to no catalog.
        ?: if (ref.system == null && ref.previewSegment != null) {
          defaultSessionId.takeIf { it.isNotBlank() && sessions.isKnownSession(it) }
        } else null
    // Resident host when there is one; `peekHost` never resumes, so the last-known snapshot below
    // keeps a suspended catalog's provenance and trust.
    val host = system?.let { sessions.peekHost(it) }
    val bundle = host?.let { catalogBundleHost(it) }
    val seen = if (host == null) system?.let { catalogMetaSeen[it] } else null
    // Match, don't trust: the segment names a preview only if this session has one encoding to it
    // (a suspended session uses its snapshot's ids).
    val previewIds = host?.previews?.map { it.id } ?: seen?.previewIds.orEmpty()
    val previewId =
      ref.previewSegment?.let { segment ->
        previewIds.firstOrNull { it == segment || WebEscaping.urlEncodeSegment(it) == segment }
      }
    val preview = previewId?.let { id -> host?.previews?.firstOrNull { it.id == id } }
    val basePath =
      if (system == null || siteSystem() != null) "" else "/${WebEscaping.urlEncodeSegment(system)}"
    // The overrides the reporter had on screen (from `from`'s query), so the report embeds the
    // render that prompted it.
    val overrideSuffix = renderOverrideSuffix(from, system)
    // Which design reference was on stage beside the render, only where the reporter's path and
    // query settle it. The focused comparison names its pair exactly; the viewer's spec lane
    // (`?mode=spec`) only when it has no second source to switch to. Otherwise null, keeping the
    // base render alone.
    val stageReference = previewId?.let { id ->
      val references = host?.designReferencesFor(id).orEmpty()
      when {
        ref.previewRoute == ServeBugReport.COMPARE_ROUTE -> {
          val named = ServeBugReport.referenceSegment(from)
          // Match, don't trust, mirroring `handleReferenceComparison`: an unknown `?reference=` is
          // that page's 404, so don't fall back to the first.
          if (named == null) references.firstOrNull()
          else
            references.firstOrNull {
              it.id == named || WebEscaping.urlEncodeSegment(it.id) == named
            }
        }
        ref.previewRoute == ServeBugReport.VIEWER_ROUTE && ServeBugReport.onSpecLane(from) ->
          // The lane defaults to the first reference ([ServeWeb.SpecSource]); with a second source
          // the URL can't say which was shown, so keep the render alone.
          if (host != null && preview != null && parallelSpecSource(host, preview) != null) null
          else references.firstOrNull()
        else -> null
      }
    }
    val status = withContext(Dispatchers.IO) { buildStatusData(onlySystem = siteSystem()) }
    val server =
      ServeBugReport.Server(
        version = SERVE_VERSION,
        public = isPublic,
        uptimeSeconds = status.uptimeSeconds,
        java = "${System.getProperty("java.version")} (${System.getProperty("java.vendor")})",
        os =
          "${System.getProperty("os.name")} ${System.getProperty("os.version")} " +
            "(${System.getProperty("os.arch")})",
        unhealthyCatalogs =
          status.catalogs
            .filter { it.loadState != "loaded" }
            .map { catalog ->
              val why = catalog.loadError?.takeIf { it.isNotBlank() }?.let { " — $it" } ?: ""
              "`${catalog.id}`: ${catalog.loadState}$why"
            },
        recentFailures = recentFailureLines(status),
      )
    val page =
      ServeBugReport.Page(
        path = from,
        url = from?.let { ServeIssueReport.withoutToken(externalOrigin() + it) },
        system = system,
        previewId = previewId,
        catalog = (bundle?.provenance ?: seen?.provenance)?.let { "${it.repo}@${it.branch}" },
        catalogToolVersion = (bundle?.provenance ?: seen?.provenance)?.toolVersion,
        trust = bundle?.let { BundleVerifier.summary(it.trust) } ?: seen?.trust,
        // A suspended session is not a lane verdict — it is an idle daemon that resumes on the
        // next render — so it reports what it was rather than being called "baked".
        renderLane =
          when {
            host != null -> if (host.hasLiveStream) "live daemon" else "baked snapshots"
            seen != null -> "suspended (idle)"
            else -> null
          },
        // Read from the reporter's query, since the viewer rewrites `?mode=`/`?specView=` as the
        // visitor moves between lanes.
        view = ServeBugReport.viewLabel(from),
        degradations = host?.degradations.orEmpty().map { "${it.code} — ${it.detail}" },
        renderUrl =
          previewId?.let {
            ServeIssueReport.withoutToken(
              "${externalOrigin()}$basePath/render/${WebEscaping.urlEncodeSegment(it)}.png" +
                overrideSuffix
            )
          },
        // The same suffix as the render: the reference lane ignores overrides but honours the `at=`
        // pin.
        referenceUrl =
          stageReference?.let {
            ServeIssueReport.withoutToken(
              "${externalOrigin()}$basePath/reference/${WebEscaping.urlEncodeSegment(it.id)}.png" +
                overrideSuffix
            )
          },
        publicRender = isPublic,
      )
    val skin = siteSkin()
    // Which catalog's tracker a pixel bug belongs in, so the page can name and link it; always
    // answerable on a top-level site and for `/{system}/…` pages. Skipped when it is
    // [ServeBugReport.REPO] itself (compared against that, not [ServeIssueReport.FALLBACK_REPO],
    // which is now a different tracker).
    val catalogTarget = system?.let { id ->
      val provenance = bundle?.provenance ?: seen?.provenance
      val repo = ServeIssueReport.repoFor(bundle?.catalogSource, provenance)
      if (repo == ServeBugReport.REPO) null
      else
        ServeWeb.BugReportCatalog(
          system = id,
          title =
            bundle?.title?.takeIf { it.isNotBlank() }
              ?: catalogMetaSeen[id]?.title?.takeIf { it.isNotBlank() }
              ?: host?.label?.takeIf { it.isNotBlank() }
              ?: id,
          repo = repo,
          issuesUrl = ServeIssueReport.action(repo),
          site = siteSystem() != null,
        )
    }
    // Text shared into the installed app ([ServeShareTarget]) answers "what went wrong".
    val shared = sharedReportText()
    fun withShared(body: String) = ServeBugReport.withSharedText(body, shared)
    val report =
      ServeWeb.BugReport(
        action = ServeBugReport.action(),
        body = withShared(ServeBugReport.body(server, page)),
        bodyTemplate = withShared(ServeBugReport.body(server, page, clientPlaceholder = true)),
        repo = ServeBugReport.REPO,
        // The on-page thumbnail is fetched by the visitor's browser, so it keeps the token the
        // report body strips.
        renderUrl =
          previewId?.let {
            val gate =
              if (!linksCarryToken()) ""
              else
                (if (overrideSuffix.isEmpty()) "?" else "&") +
                  "token=${WebEscaping.urlEncodeSegment(linkToken())}"
            "$basePath/render/${WebEscaping.urlEncodeSegment(it)}.png$overrideSuffix$gate"
          },
        // The other panel, on the same terms: the page shows what the body carries, so a
        // comparison-sourced report previews the pair rather than half of it.
        referenceUrl =
          stageReference?.let {
            val gate =
              if (!linksCarryToken()) ""
              else
                (if (overrideSuffix.isEmpty()) "?" else "&") +
                  "token=${WebEscaping.urlEncodeSegment(linkToken())}"
            "$basePath/reference/${WebEscaping.urlEncodeSegment(it.id)}.png$overrideSuffix$gate"
          },
        login = githubAuth?.currentLogin(call),
        catalog = catalogTarget,
      )
    markGeneration("static-page", "no-store")
    call.respondText(
      ServeWeb.bugReportPage(
        report = report,
        sections = bugReportSections(server, page),
        // The bare route, not `externalPageUrl()`: the query is browser-supplied `from`, and
        // `og:url` would echo it unvalidated.
        unfurl = ServeWeb.UnfurlMetadata(pageUrl = externalOrigin() + ServeBugReport.PATH),
        version = SERVE_VERSION,
        siteName = skin.first,
        themeCss = skin.second,
        themeStorageKey = skin.third,
        navSuffix =
          if (!linksCarryToken()) "" else "?token=${WebEscaping.urlEncodeSegment(linkToken())}",
        // Resolve this caller, not just whether a resolver exists: the lane admits only an OAuth
        // session for the image repository, and advertising it otherwise makes every report attempt
        // a doomed upload. A cookie read, not a GitHub call.
        canUploadCaptures =
          imageStore != null &&
            imageUploadAuth != null &&
            imageBrowserLogin?.invoke(call, imageUploadAuth.repository) != null,
        // Only a public host's catalog pages are open to anyone; captures of anything else wait
        // for the reporter's opt-in before reaching the anonymous-read image lane.
        privateCaptures = !isPublic || ServeBugReport.isPrivatePath(from),
      ),
      ContentType.Text.Html,
    )
  }

  /** Shares waiting for the report page to pick them up. See [ServeShareTarget]. */
  private val sharedItems = ServeShareTarget.Store()

  /**
   * `POST /report-bug/share`: what the OS share sheet sends the installed app. Answered with a
   * `303`: images or text land in the bug report (parked in [sharedItems]); links to this server
   * open that page. Cross-site posts are refused; the share sheet's own POST is `Sec-Fetch-Site:
   * none`.
   */
  private suspend fun RoutingContext.handleShareTarget() {
    if (rejectBadToken()) return
    if (call.request.headers["Sec-Fetch-Site"] == "cross-site") {
      call.respondText("cross-site share refused", status = HttpStatusCode.Forbidden)
      return
    }
    val contentType = call.request.headers[HttpHeaders.ContentType].orEmpty()
    if (!contentType.startsWith("multipart/form-data", ignoreCase = true)) {
      call.respondText("expected multipart/form-data", status = HttpStatusCode.UnsupportedMediaType)
      return
    }
    val body =
      withContext(Dispatchers.IO) {
        call.receiveStream().use { readCapped(it, ServeShareTarget.MAX_BODY_BYTES) }
      }
    if (body == null) {
      call.respondText("share too large", status = HttpStatusCode.PayloadTooLarge)
      return
    }
    val fields = ServeShareTarget.parseMultipart(body, contentType)
    if (fields == null) {
      call.respondText("malformed share", status = HttpStatusCode.BadRequest)
      return
    }
    val self = runCatching { java.net.URI(externalOrigin()) }.getOrNull()
    fun port(uri: java.net.URI) =
      if (uri.port >= 0) uri.port else if (uri.scheme.equals("https", true)) 443 else 80
    val outcome =
      ServeShareTarget.outcome(fields, System.currentTimeMillis()) { uri ->
        self != null && uri.host.equals(self.host, ignoreCase = true) && port(uri) == port(self)
      }
    val gate =
      if (!linksCarryToken()) null else "token=${WebEscaping.urlEncodeSegment(linkToken())}"
    fun withGate(path: String) =
      if (gate == null || Regex("[?&]token=").containsMatchIn(path)) path
      else path + (if ('?' in path) "&" else "?") + gate
    val target =
      when (outcome) {
        is ServeShareTarget.Outcome.Open -> outcome.path
        is ServeShareTarget.Outcome.Report -> {
          val id = sharedItems.put(outcome.shared)
          "${ServeBugReport.PATH}?${ServeShareTarget.SHARED_PARAM}=$id"
        }
        ServeShareTarget.Outcome.Empty -> ServeBugReport.PATH
      }
    call.response.headers.append(HttpHeaders.Location, withGate(target))
    call.respond(HttpStatusCode.SeeOther)
  }

  /** `GET /report-bug/shared/<id>`: a parked shared image, for the report page to import. */
  private suspend fun RoutingContext.handleSharedImage() {
    if (rejectBadToken()) return
    val shared = call.parameters["id"]?.let(sharedItems::get)
    val image = shared?.image
    val type = shared?.imageType
    if (image == null || type == null) {
      call.respondText("not found", status = HttpStatusCode.NotFound)
      return
    }
    call.response.headers.append(HttpHeaders.CacheControl, "private, no-store")
    call.response.headers.append("X-Content-Type-Options", "nosniff")
    call.respondBytes(image, ContentType.parse(type))
  }

  /** The text a share parked for the report page named by `?shared=`, if it is still parked. */
  private fun RoutingContext.sharedReportText(): String? =
    call.request.queryParameters[ServeShareTarget.SHARED_PARAM]
      ?.let(sharedItems::get)
      ?.text
      ?.takeIf { it.isNotBlank() }

  /**
   * "Open on your phone" for the operator of a `--lan` server: network URLs, a QR code, and the
   * secure-context caveat.
   *
   * Shown only when the server binds every interface, the request came directly from this machine
   * (loopback peer and `Host`, no forwarding headers), and it carries the operator's token or
   * browse cookie (not a grant), since the card embeds the token. The response is `no-store`.
   */
  private fun RoutingContext.operatorLanCard(): String? {
    if (!ServeUrls.isExposed(host)) return null
    val headers = call.request.headers
    if (
      listOf("X-Forwarded-For", "X-Forwarded-Host", "Forwarded", "X-Real-IP").any {
        headers[it] != null
      }
    )
      return null
    val peer = call.request.local.remoteAddress
    if (peer !in LOOPBACK_PEERS) return null
    val requestHost = headers[HttpHeaders.Host]?.substringBeforeLast(':')?.trim('[', ']')
    if (requestHost !in LOOPBACK_HOSTS) return null
    val provided = call.request.queryParameters["token"] ?: headers[TOKEN_HEADER]
    val operator =
      isPublic || ServeUrls.tokensMatch(serverToken, provided) || call.browsesByCookie()
    if (!operator) return null
    val origins = lanAddresses().map { ServeUrls.origin(it, port) }
    if (origins.isEmpty()) return null
    val tokenQuery = if (isPublic) "" else "?token=${WebEscaping.urlEncodeSegment(serverToken)}"
    val urls = origins.map { "$it/$tokenQuery" }
    val qr =
      ServeQrCode.encode(urls.first())?.svg(moduleSize = 4, label = "QR code for ${urls.first()}")
    return buildString {
      append("<section class=\"cp-lan-card\" aria-labelledby=\"cp-lan-card-title\">")
      append("<h2 id=\"cp-lan-card-title\">Open on your phone</h2>")
      if (qr != null) append("<div class=\"cp-lan-qr\">").append(qr).append("</div>")
      append("<div class=\"cp-lan-text\"><p>Scan the code, or open")
      urls.forEach { url ->
        val escaped = WebEscaping.htmlEscape(url)
        append(" <a href=\"$escaped\"><code>$escaped</code></a>")
      }
      append(" on a device on the same network. Only you see this card: it carries your token.</p>")
      append(
        "<p class=\"cp-lan-caveat\">A plain-http LAN address is not a secure context, so " +
          "installing the app, sharing, the clipboard and offline use need HTTPS — or, on an " +
          "Android phone over USB, <code>adb reverse tcp:$port tcp:$port</code> and open " +
          "<code>http://localhost:$port/</code>.</p></div></section>"
      )
    }
  }

  /** Respond [html] with [card] as the first thing in its `<main>`, when there is one. */
  private suspend fun RoutingContext.respondWithLanCard(
    card: String?,
    html: String,
    contentType: ContentType,
  ) = call.respondText(withOperatorLanCard(card, html), contentType)

  /** [html] with [card] as the first thing in its `<main>`, or unchanged when there is none. */
  private fun withOperatorLanCard(card: String?, html: String): String =
    if (card == null) html
    else html.replaceFirst("<main class=\"cp-main\">", "<main class=\"cp-main\">\n$card")

  /**
   * The session a root-form viewer path showed: its `?session=`, else the default. The caller
   * checks the session is known.
   */
  private fun explicitSessionId(from: String?): String? {
    val query = from?.substringAfter('?', missingDelimiterValue = "").orEmpty()
    val raw =
      query
        .split('&')
        .firstOrNull { it.startsWith("session=") }
        ?.substringAfter('=')
        ?.takeIf { it.isNotBlank() } ?: return null
    // Decoded, since `ServeWeb.queryString` percent-encodes it (`feature/foo` → `feature%2Ffoo`)
    // while the registry stores the raw key.
    return decodeQueryValue(raw)
  }

  /**
   * Percent-decode a query value without the `+`-means-space rule, since
   * `WebEscaping.urlEncodeSegment` leaves `+` literal.
   */
  private fun decodeQueryValue(value: String): String? = runCatching {
    URLDecoder.decode(value.replace("+", "%2B"), StandardCharsets.UTF_8)
  }
    .getOrNull()

  /**
   * This request's override query, filtered to what the render lane consumes
   * ([ServeOverrides.isOverrideParam]) and normalised like the page's links, so routing params and
   * the token never ride along. Shared by the viewer and focused comparison so their reports agree.
   */
  /**
   * [requestOverrideParams], minus whatever this page's picture cannot be showing, so controls
   * never claim a state the pixels lack.
   *
   * A pinned revision (`?at=<sha>`) drops every seed, matching `pinnedRenderQuerySuffix`. Otherwise
   * only an accepted baked fallback (`?fallback=baked`, via `respondDroppedOverrides`) can ignore
   * an override, and per axis:
   * - no lane can apply it at all (no server render path and no Wasm app [wasmSrc]);
   * - the preview is replayed from a Remote Compose document, so `knob.*` and string `rc.*` can't
   *   apply; [CatalogLiveRouting.irReplayDroppedOverrideNames] decides, matching
   *   [droppedOverridesFor].
   *
   * Everything the render honours still seeds.
   */
  private fun RoutingContext.seedableOverrideParams(
    renderHost: ServeHost,
    preview: ServePreview,
    sessionId: String,
    pinnedRevision: String?,
    wasmSrc: String?,
  ): OverrideSeeds {
    val params = requestOverrideParams(sessionId)
    fun seeds(seeded: Map<String, String>) = OverrideSeeds.of(params, seeded)
    if (pinnedRevision != null) return seeds(emptyMap())
    if (params.isEmpty() || !acceptsBakedFallback()) return seeds(params)
    val overrideCanReachThePixels =
      renderHost.canApplyOverrides ||
        renderHost.canRenderOverridesFor(preview.id) ||
        wasmSrc != null
    if (!overrideCanReachThePixels) return seeds(emptyMap())
    if (!isReplayedPreview(renderHost, preview.id)) return seeds(params)
    val parsed =
      ServeRcPlayerIds.parseOverrides(params, ServeOverrides.declaredKnobKinds(preview))
        as? OverrideParse.Ok ?: return seeds(params)
    val dropped =
      CatalogLiveRouting.irReplayDroppedOverrideNames(
          preview.id,
          parsed.overrides,
          renderHost.bakedTheme(preview.id),
          renderHost.bakedRcPlayer(preview.id),
        )
        .toSet()
    return seeds(if (dropped.isEmpty()) params else params.filterKeys { it !in dropped })
  }

  /**
   * A page's request overrides split into those its controls may open on and those they may not.
   * The withheld set must be told to the viewer, since `hydrateFromUrl` reads `location.search`
   * itself.
   */
  internal data class OverrideSeeds(
    val seeded: Map<String, String>,
    val withheld: Set<String>,
  ) {
    companion object {
      /**
       * [seeded] plus whatever of [all] it left out, narrowed to the per-axis prefixes the viewer's
       * controls hold (display axes like `fontScale` and `device` hydrate separately).
       */
      fun of(all: Map<String, String>, seeded: Map<String, String>): OverrideSeeds =
        OverrideSeeds(
          seeded = seeded,
          withheld =
            all.keys
              .filter { it !in seeded }
              .filter {
                it.startsWith(ServeOverrides.KNOB_PREFIX) ||
                  it.startsWith(ServeOverrides.RC_NAMED_PREFIX)
              }
              .toSet(),
        )
    }
  }

  private fun RoutingContext.requestOverrideParams(sessionId: String): Map<String, String> =
    call.request.queryParameters
      .entries()
      .mapNotNull { (key, values) ->
        val value = values.firstOrNull() ?: return@mapNotNull null
        if (ServeOverrides.isOverrideParam(key)) key to value else null
      }
      .toMap()
      .let { ServeWeb.SystemDisplay.normalizeOverrideParams(sessionId, it) }

  /**
   * The reported page's override query as a `?…` suffix for a `/render` URL, taken from `from` (the
   * reported page). Filtered ([ServeOverrides.isOverrideParam]) and normalised like the viewer's
   * links; the token is added separately for the on-page thumbnail only. Empty when there are no
   * overrides.
   */
  private fun renderOverrideSuffix(from: String?, system: String?): String {
    val query = from?.substringAfter('?', missingDelimiterValue = "").orEmpty()
    if (query.isEmpty()) return ""
    val pairs = query.split('&').filter { it.isNotEmpty() }
    val revision = pairs.firstNotNullOfOrNull { pair ->
      val key = pair.substringBefore('=')
      if (key != ServeCatalogRevision.PARAM) return@firstNotNullOfOrNull null
      ServeCatalogRevision.normalize(
        decodeQueryValue(pair.substringAfter('=', missingDelimiterValue = ""))
      )
    }
    val params =
      pairs
        .mapNotNull { pair ->
          val key = pair.substringBefore('=')
          val value = pair.substringAfter('=', missingDelimiterValue = "")
          if (ServeOverrides.isOverrideParam(key)) key to value else null
        }
        .toMap()
        .let { ServeWeb.SystemDisplay.normalizeOverrideParams(system ?: defaultSessionId, it) }
        .toMutableMap()
        .apply { revision?.let { put(ServeCatalogRevision.PARAM, it) } }
    if (params.isEmpty()) return ""
    return "?" + params.entries.joinToString("&") { "${it.key}=${it.value}" }
  }

  /**
   * The most recent failures for a bug report, newest first: daemon startups and render
   * failures/timeouts, merged by timestamp before capping so the newest always survive. Capped for
   * readability; full history is on `/status`.
   */
  private fun recentFailureLines(status: StatusData): List<String> {
    val startup =
      status.failures.map { failure ->
        failure.atEpochMillis to
          "${formatInstant(failure.atEpochMillis)}  ${failure.session}: ${failure.reason}"
      }
    val renders =
      status.running.flatMap { daemon ->
        daemon.renderStats?.recentFailures.orEmpty().map { failure ->
          failure.atEpochMillis to
            "${formatInstant(failure.atEpochMillis)}  ${daemon.label}: render failed after " +
              "${failure.durationMs}ms${if (failure.timedOut) " (timeout)" else ""} — " +
              failure.reason
        }
      }
    return (startup + renders)
      .sortedByDescending { it.first }
      .take(BUG_REPORT_FAILURE_LIMIT)
      .map { it.second }
  }

  /** The same facts [ServeBugReport.body] files, grouped for the page that shows them first. */
  private fun bugReportSections(
    server: ServeBugReport.Server,
    page: ServeBugReport.Page,
  ): List<ServeWeb.BugReportSection> = buildList {
    add(
      ServeWeb.BugReportSection(
        "Server",
        buildList {
          server.version?.let { add("compose-preview" to it) }
          add("Mode" to if (server.public) "public (open)" else "token-gated")
          server.uptimeSeconds?.let { add("Uptime" to ServeBugReport.duration(it)) }
          server.java?.let { add("Server JVM" to it) }
          server.os?.let { add("Server OS" to it) }
        },
      )
    )
    add(
      ServeWeb.BugReportSection(
        "Page",
        buildList {
          page.path?.let { add("Page" to it) }
          page.system?.let { add("Design system" to it) }
          page.previewId?.let { add("Preview" to it) }
          page.catalog?.let { add("Catalog" to it) }
          page.catalogToolVersion?.let { add("Catalog rendered by" to "compose-ai-tools $it") }
          page.trust?.let { add("Trust" to it) }
          page.renderLane?.let { add("Render lane" to it) }
          page.view?.let { add("View" to it) }
          page.degradations.forEach { add("Degraded" to it) }
        },
      )
    )
    add(
      ServeWeb.BugReportSection(
        "Catalogs not loaded",
        server.unhealthyCatalogs.map { "" to it },
      )
    )
    add(ServeWeb.BugReportSection("Recent failures", server.recentFailures.map { "" to it }))
    add(
      ServeWeb.BugReportSection(
        "Browser",
        listOf("User agent, viewport, pixel ratio, colour scheme" to "added by your browser"),
      )
    )
  }

  /**
   * `GET /readyz`: the rolling-update readiness gate. Instant: it reads the [ready] latch (`200
   * "ready"` / `503 "warming"`). The render runs on [readinessProber], so a healthcheck timeout
   * (Docker allows 5s) can't cancel it; the first poll starts the prober.
   */
  private suspend fun RoutingContext.handleReadyz() {
    if (ready.get()) {
      call.respondText("ready")
      return
    }
    // Upload-only server (`--accept-bundles`, no landing session or catalogs): nothing to render,
    // so ready once listening. Catalog-only starts still wait below.
    if (defaultSessionId.isBlank() && catalogLoads?.snapshot().isNullOrEmpty()) {
      ready.set(true)
      call.respondText("ready")
      return
    }
    ensureReadinessProbe()
    call.respondText("warming", status = HttpStatusCode.ServiceUnavailable)
  }

  /**
   * Start the readiness prober on the first `/readyz` poll (idempotent via
   * [readinessProbeStarted]). It renders the design systems off the request path, retrying until a
   * fully successful pass latches [ready]. A daemon thread, interrupted on [stop].
   */
  private fun ensureReadinessProbe() {
    if (!readinessProbeStarted.compareAndSet(false, true)) return
    val prober =
      Thread(
          {
            while (!ready.get() && !Thread.currentThread().isInterrupted) {
              if (probeReadiness()) {
                ready.set(true)
                return@Thread
              }
              try {
                Thread.sleep(READINESS_PROBE_RETRY_MILLIS)
              } catch (e: InterruptedException) {
                return@Thread
              }
            }
          },
          "serve-readiness-probe",
        )
        .apply { isDaemon = true }
    readinessProber = prober
    prober.start()
  }

  /**
   * One readiness attempt: render one preview from every configured design system
   * ([CatalogLoadTracker.designSystemSystems]); success only if all answer. Systems still warming
   * are logged by name.
   *
   * With no design systems configured (plain `serve`, dev box, upload-only), falls back to the
   * representative-session probe, since a server that renders nothing isn't ready.
   *
   * Runs on [readinessProber] (blocking). Never throws.
   */
  private fun probeReadiness(): Boolean {
    val designSystems = catalogLoads?.designSystemSystems().orEmpty()
    if (designSystems.isNotEmpty()) {
      val notYet = designSystems.filterNot { renderableNow(it) }
      if (notYet.isNotEmpty()) {
        System.err.println(
          "[serve] readiness: waiting for ${notYet.size} of ${designSystems.size} design " +
            "system(s) to render: ${notYet.joinToString(", ")}"
        )
        return false
      }
      return true
    }
    return readinessSessionIds().any { renderableNow(it) }
  }

  /**
   * Whether [system] can render right now: lease its session and render one preview override-free.
   * False on any failure. A catalog counts as `loaded` before it can produce bytes, so only an
   * actual render answers this.
   */
  private fun renderableNow(system: String): Boolean {
    val lease = sessions.lease(system) ?: return false
    return try {
      val preview = lease.host.previews.firstOrNull() ?: return false
      lease.host.render(preview.id, PreviewOverrides()) is RenderOutcome.Ok
    } catch (e: Exception) {
      System.err.println("[serve] readiness probe failed for '$system': ${e.message}")
      false
    } finally {
      lease.close()
    }
  }

  private fun readinessSessionIds(): List<String> =
    listOfNotNull(defaultSessionId.takeIf { it.isNotBlank() }, catalogLoads?.firstAvailableSystem())
      .distinct()

  /**
   * Raw catalog metadata for the status snapshot — projected to HTML rows and JSON by [StatusData].
   */
  private data class CatalogStat(
    val id: String,
    val listed: Boolean,
    val title: String?,
    val trust: String?,
    val previews: Int?,
    val failedRenders: Int,
    val deferredPreviews: Int,
    /** Has a live (daemon-backed) render lane — a running daemon, or a suspended live catalog. */
    val live: Boolean,
    /** A live daemon for this catalog is up right now. */
    val running: Boolean,
    val degradation: String?,
    val provenance: ServeWeb.CatalogProvenance?,
    /** A usable catalog session is currently registered (possibly the last good refresh copy). */
    val available: Boolean,
    /**
     * Latest startup/refresh error; may coexist with [available] when the old copy was retained.
     */
    val loadError: String?,
    val lastLoadAttemptEpochMillis: Long?,
    val themeOptimization: ThemeOptimizationSnapshot?,
    val renderCache: CatalogRenderCacheSnapshot?,
    /**
     * The metadata above is a last-known snapshot of a suspended catalog ([catalogMetaSeen]);
     * trust, provenance and preview count can't change by suspension, so they're reported but
     * marked not-live.
     */
    val stale: Boolean = false,
  ) {
    val loadState: String
      get() =
        when {
          available && loadError == null -> "loaded"
          available -> "stale"
          loadError != null -> "failed"
          else -> "pending"
        }
  }

  /**
   * Last-known catalog metadata per session, captured while resident. `peekHost` never resumes, so
   * without this a suspended catalog would render as a blank row whose empty trust cell reads as
   * untrusted. These facts come from the delivery branch, so suspension doesn't invalidate them.
   *
   * Written on suspension (the [sessions] listener below) and refreshed on every resident read.
   */
  private val catalogMetaSeen = ConcurrentHashMap<String, CatalogMeta>()

  /**
   * Where each preview's source lives, per system, so `/usage/<id>` can answer for a suspended
   * catalog.
   *
   * Written and removed only by [ServeSessionRegistry.SessionSnapshots] under the registry's lock
   * during detach and retire, so readers see either the live host or a snapshot, never a gap, and a
   * retirement can't be overtaken by a slower writer. Nothing is written while resident.
   */
  private val catalogSourceLocationsSeen =
    ConcurrentHashMap<String, Map<String, PlaygroundSeedResolver.Location>>()

  /**
   * The related-link index and component labels for each suspended catalog, since back-links walk
   * other catalogs that are usually suspended. Captured and retired atomically beside
   * [catalogSourceLocationsSeen].
   */
  private val relatedCatalogsSeen = ConcurrentHashMap<String, RelatedCatalog>()

  private data class RelatedCatalog(
    val relatedByComponentId: Map<String, List<ServeRelatedCatalogs.Declared>>,
    val componentsById: Map<String, RelatedComponent>,
    val heading: String,
  )

  private data class RelatedComponent(val previewId: String, val label: String)

  /** A resident-time snapshot of one catalog's status facts. See [catalogMetaSeen]. */
  private data class CatalogMeta(
    val title: String?,
    val subtitle: String?,
    val trust: String?,
    val previews: Int?,
    /**
     * The preview ids, for the sitemap's per-viewer URLs; remembered so the sitemap doesn't depend
     * on which catalogs are warm.
     */
    val previewIds: List<String>,
    /** Component-card projection used by the home page's cross-catalog command palette. */
    val components: List<ServeWeb.ComponentSearchEntry>,
    val failedRenders: Int,
    val deferredPreviews: Int,
    val heroPreviewId: String?,
    val heroCrop: ContentCrop?,
    /**
     * The prebaked front-door thumbnail for [heroPreviewId], served on `/hero/`. Captured here
     * where the owning bundle host is in hand, so a suspended catalog keeps its hero. Null when
     * there's no hero or it couldn't be decoded; the card then uses `/render`.
     */
    val heroImage: ServeHeroImages.Hero?,
    /**
     * The pixel size of the hero's full render, advertised to unfurlers. Remembered because
     * `peekHost` is null for an idle catalog, which would otherwise fall back to the undersized
     * thumbnail.
     */
    val heroRenderSize: Pair<Int, Int>?,
    val darkStage: Boolean,
    /**
     * The catalog's web palette ([ServeThemeCss]), remembered so a site's `/status` and 404 keep
     * its skin while idle. It comes from the delivery branch.
     */
    val webThemeCss: String,
    val degradation: String?,
    val provenance: ServeWeb.CatalogProvenance?,
    /**
     * The upstream project the catalog's `catalog.json` declares ([ServeBundleHost.catalogSource]),
     * for attributing imports with no `importedFrom`. See [ServeWeb.HomeSystem.catalogSourceRepo].
     */
    val catalogSourceRepo: String?,
    val themeOptimization: ThemeOptimizationSnapshot?,
    val renderCache: CatalogRenderCacheSnapshot?,
    /**
     * Whether this catalog publishes design references
     * ([ServeWeb.HomeSystem.hasReferenceComparison]); remembered so idle catalogs keep their
     * compare action.
     */
    val hasReferenceComparison: Boolean,
    /**
     * The sibling system this catalog pairs with when both halves of `compareWith` + `parallel`
     * resolve ([CatalogFacts.compareWithSystem]). Usually null.
     */
    val compareWithSystem: String? = null,
    /**
     * The counterpart components named in [compareWithSystem], in that catalog's vocabulary
     * ([CatalogFacts.parallelComponentIds]); [homeSystemsFor] resolves them.
     */
    val parallelComponentIds: Set<String> = emptySet(),
    /**
     * The design tool the references name ("Figma", …), or null. Label only;
     * [hasReferenceComparison] decides whether there is an action.
     */
    val designToolLabel: String?,
  )

  init {
    // Widget thumbnails are drawn in their host container wherever this server can render one.
    uiBuilderThumbnails?.nativePreview = uiBuilderNativePreview
    // Snapshot a catalog's facts as its daemon goes idle — the last moment they're readable.
    sessions.addSuspendListener { id, host -> rememberCatalogMeta(id, host) }
    // A retired catalog's status snapshot goes with it. A listener rather than a registry
    // transition because [catalogMetaSeen] also has resident-read writers.
    sessions.addUnregisterListener { id -> catalogMetaSeen.remove(id) }
    // Source locations ride the registry's own transitions ([catalogSourceLocationsSeen]). Pure map
    // operations, as the under-lock contract requires.
    sessions.setSessionSnapshots(
      object : ServeSessionRegistry.SessionSnapshots {
        override fun capture(sessionId: String, host: ServeHost) {
          // Nothing stored for a host without a catalog source.
          val locations = sourceLocationsOf(host)
          if (locations.isEmpty()) catalogSourceLocationsSeen.remove(sessionId)
          else catalogSourceLocationsSeen[sessionId] = locations

          val related = relatedCatalogOf(host)
          if (related == null) relatedCatalogsSeen.remove(sessionId)
          else relatedCatalogsSeen[sessionId] = related
        }

        override fun discard(sessionId: String) {
          catalogSourceLocationsSeen.remove(sessionId)
          relatedCatalogsSeen.remove(sessionId)
        }
      }
    )
  }

  /** The smallest projection of [host] from which another catalog can derive its back-links. */
  private fun relatedCatalogOf(host: ServeHost): RelatedCatalog? {
    val bundle = catalogBundleHost(host) ?: return null
    if (bundle.relatedByComponentId.isEmpty()) return null
    val components = buildMap {
      host.previews.forEach { preview ->
        val componentId = ServeIssueReport.componentIdFor(preview)
        if (componentId.isNotBlank()) {
          // Match the resident lookup's firstOrNull: variants share a component id, and the first
          // published render is its canonical link destination.
          putIfAbsent(componentId, RelatedComponent(preview.id, ServeWeb.rowDisplayName(preview)))
        }
      }
    }
    val heading =
      when (ServeWeb.PageRole.of(bundle.catalogRole)) {
        ServeWeb.PageRole.SAMPLES -> "Samples"
        else -> ServeWeb.catalogHeading(bundle.title, host.label)
      }
    return RelatedCatalog(bundle.relatedByComponentId, components, heading)
  }

  /**
   * Every preview's source location on [host] for the suspended snapshot. Runs under the registry
   * lock ([ServeSessionRegistry.SessionSnapshots]), so: walk, build a map, nothing else.
   */
  private fun sourceLocationsOf(host: ServeHost): Map<String, PlaygroundSeedResolver.Location> {
    val bundle = catalogBundleHost(host) ?: return emptyMap()
    val source = bundle.catalogSource ?: return emptyMap()
    return host.previews
      .mapNotNull { preview ->
        val file = preview.sourceFile?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        preview.id to
          PlaygroundSeedResolver.Location(
            repo = source.repo,
            ref = source.ref,
            module = preview.sourceModule ?: source.module,
            sourceFile = file,
            bodyLine = preview.bodyLine,
          )
      }
      .toMap()
  }

  /**
   * Record [host]'s browse-card facts under [id]. Bundle hosts contribute richer publishing
   * metadata; local module sessions still contribute title, preview count and a representative
   * render.
   *
   * [progress] controls the two progress counters (theme optimization and render cache), read only
   * by `/status`. They are expensive (`themeOptimizationSnapshot` builds a cache file name with
   * `String.format` per themed render), so `homeSystemsFor` and the component index pass `false`
   * and keep remembered values. The status path and the suspend listener pass true (the last chance
   * to capture final counters). `buildStatusData` re-reads live values anyway.
   */
  private fun rememberCatalogMeta(id: String, host: ServeHost, progress: Boolean = true) {
    val bundle = catalogBundleHost(host)
    val facts = catalogFactsFor(id, host)
    val remembered = catalogMetaSeen[id]
    // A live read, not part of [CatalogFacts]: `ServeBundleHost.contentCrop` returns null or a
    // provisional answer without memoising while files are still landing, so freezing it would
    // leave the card uncropped. It caches settled decisions itself.
    val heroCrop = facts.heroPreviewId?.let { bundle?.contentCrop(it) }
    catalogMetaSeen[id] =
      CatalogMeta(
        title = bundle?.title?.takeIf { it.isNotBlank() } ?: host.label,
        subtitle = bundle?.subtitle,
        trust = bundle?.let { BundleVerifier.summary(it.trust) },
        previews = host.previews.size,
        previewIds = facts.previewIds,
        components = facts.components,
        failedRenders = facts.failedRenders,
        deferredPreviews = host.liveOnlyPreviewIds.size,
        heroPreviewId = facts.heroPreviewId,
        heroCrop = heroCrop,
        // Memoised per (host instance, preview): the decode + scale runs once per catalog, and a
        // refresh — which installs a fresh host — re-bakes under a new hash.
        heroImage =
          bundle?.let { owner ->
            facts.heroPreviewId?.let {
              heroImages.heroFor(owner, it, heroCrop)?.also { hero ->
                catalogLoads?.configFor(id)?.let { config -> heroImages.remember(config, hero) }
              }
            }
          },
        heroRenderSize = facts.heroPreviewId?.let { host.bakedRenderSize(it) },
        darkStage = facts.darkStage,
        webThemeCss = bundle?.webThemeCss.orEmpty(),
        degradation = host.degradations.firstOrNull()?.detail,
        provenance = bundle?.provenance,
        catalogSourceRepo = bundle?.catalogSource?.repo?.takeIf { it.isNotBlank() },
        themeOptimization =
          if (progress) host.themeOptimizationSnapshot() else remembered?.themeOptimization,
        renderCache = if (progress) host.catalogRenderCacheSnapshot() else remembered?.renderCache,
        hasReferenceComparison = facts.hasReferenceComparison,
        compareWithSystem =
          facts.compareWithSystem?.takeIf { facts.parallelComponentIds.isNotEmpty() },
        parallelComponentIds = facts.parallelComponentIds,
        designToolLabel = facts.designToolLabel,
      )
  }

  /**
   * The part of [CatalogMeta] derived by walking [ServeHost.previews], memoised on host identity.
   *
   * Fixed for a host's life: `previews` is immutable and references, declared hero and stage
   * surface come from the delivery branch; a refresh installs a fresh host (the same key as
   * [ServeHeroImages.heroFor]), and the weak map drops retired ones.
   *
   * Split out because [rememberCatalogMeta] runs per listed catalog on every home-index request
   * ([homeSystemsFor]), and these members are expensive ([ServeWeb.componentSearchEntries],
   * `designToolLabel`).
   *
   * Excludes what moves while resident: progress counters, the hero's render size, content crop and
   * thumbnail (images fill in after load, which is why [ServeHeroImages] and
   * [ServeBundleHost.contentCrop] memoise only settled answers).
   */
  private class CatalogFacts(
    /** The id this was built for: [darkStage] is resolved per system, so a reuse must match. */
    val system: String,
    val previewIds: List<String>,
    val components: List<ServeWeb.ComponentSearchEntry>,
    val failedRenders: Int,
    val heroPreviewId: String?,
    val darkStage: Boolean,
    val hasReferenceComparison: Boolean,
    val designToolLabel: String?,
    /**
     * The sibling system this catalog declares itself a parallel rendition of, and whether any
     * component names a counterpart — the host-fixed halves of `compareWith` + `parallel`. The
     * sibling's residency and title change independently and are resolved at render by
     * [homeSystemsFor].
     */
    val compareWithSystem: String?,
    val parallelComponentIds: Set<String>,
  )

  private val catalogFactsByHost = WeakHashMap<ServeHost, CatalogFacts>()

  private val catalogFactsLock = Any()

  private fun catalogFactsFor(id: String, host: ServeHost): CatalogFacts {
    synchronized(catalogFactsLock) { catalogFactsByHost[host] }
      ?.takeIf { it.system == id }
      ?.let {
        return it
      }
    // Built outside the lock, like the hero bake: two callers racing a cold catalog build it twice
    // and agree, because every input is immutable for this host.
    val built = buildCatalogFacts(id, host)
    synchronized(catalogFactsLock) { catalogFactsByHost[host] = built }
    return built
  }

  private fun buildCatalogFacts(id: String, host: ServeHost): CatalogFacts {
    val bundle = catalogBundleHost(host)
    val heroId = bundle?.declaredHeroPreviewId ?: ServeWeb.representativePreviewId(host.previews)
    val darkStage = ServeWeb.SystemDisplay.resolveDarkFirst(id, bundle?.stageSurface)
    return CatalogFacts(
      system = id,
      previewIds = host.previews.map { it.id },
      components = ServeWeb.componentSearchEntries(host.previews, darkStage),
      failedRenders = host.previews.count { it.renderFailure != null },
      heroPreviewId = heroId,
      darkStage = darkStage,
      // The same two reads the landing uses for its compare chip: availability (as `comparisonPage`
      // enables `reference`) and the tool label, kept separate because provider-less references
      // still compare. The parity feed's Figma lane is not a fallback for either.
      hasReferenceComparison = host.previews.any { host.designReferencesFor(it.id).isNotEmpty() },
      designToolLabel =
        host.previews.firstNotNullOfOrNull { preview ->
          host.designReferencesFor(preview.id).firstNotNullOfOrNull {
            ServeWeb.designToolLabel(it.source.provider)
          }
        },
      // Both halves of the pairing; a `compareWith` no component pairs against would put a chip
      // over an empty wall.
      compareWithSystem = bundle?.compareWithSystem?.takeIf { it.isNotBlank() },
      // Only components this catalog publishes, held as the sibling's component ids for lookup
      // against the sibling's previews ([homeSystemsFor]).
      parallelComponentIds =
        bundle?.parallelByComponentId.orEmpty().let { mapped ->
          val published = host.previews.mapNotNullTo(HashSet()) { it.componentId }
          mapped.filterKeys { it in published }.values.toSet()
        },
    )
  }

  /**
   * One process census for the server, resampled on a short interval ([ServeProcessCensus]), since
   * `/status` is unauthenticated and uncached on the public deployment.
   */
  private val processCensusSampler = ServeProcessCensus()

  /** Raw status snapshot; the single source for both HTML and JSON. */
  private inner class StatusData(
    val nowMillis: Long,
    val catalogs: List<CatalogStat>,
    val running: List<ServeSessionRegistry.RunningDaemon>,
    val failures: List<DaemonStartupLog.Failure>,
    /**
     * The one system this snapshot is about ([ServeSites]), or null for the whole box. Carried
     * because box-wide reads (session count, playground selector) must also be scoped for a
     * per-site monitor.
     */
    val onlySystem: String? = null,
  ) {
    val uptimeSeconds: Long = ((nowMillis - startedAtMillis) / 1000).coerceAtLeast(0)

    /** Sessions known to this view: every registered one, or this site's (0 or 1). */
    val knownSessions: Int =
      if (onlySystem == null) sessions.activeCount()
      // Registration, not residency: `peekHost` is null for a suspended catalog, which would make
      // the session vanish whenever it idles.
      else if (sessions.isKnownSession(onlySystem)) 1 else 0

    /** The playground's offered catalogs, narrowed to what this view is allowed to name. */
    fun offeredCatalogs(offered: List<String>): List<String> =
      if (onlySystem == null) offered else offered.filter { it == onlySystem }

    /** Live daemons (a render daemon is up), excluding pinned static baked hosts. */
    val liveDaemons: List<ServeSessionRegistry.RunningDaemon> = running.filter { it.hasLiveStream }
    val activeStreams: Int = liveDaemons.sumOf { it.activeStreams }

    /**
     * Cross-catalog optimizer admission, taken once for both projections. Whole-box only; omitted
     * for a site.
     */
    val optimizerAdmission: ThemeOptimizerAdmissionSnapshot? =
      if (onlySystem == null) themeOptimizerStats?.invoke() else null

    /**
     * The catalog blob pool, scoped like [optimizerAdmission]. On the page too because a pool
     * pinned at its cap costs render time everywhere while each catalog looks healthy.
     */
    val catalogCache: CatalogBlobPoolSnapshot? =
      if (onlySystem == null) catalogCacheStats?.invoke() else null

    /** Sessions holding an open lease (what keeps them resident), scoped like `knownSessions`. */
    val leasedSessions: List<String> =
      sessions.leasedSessions().let { held ->
        if (onlySystem == null) held else held.filter { it == onlySystem }
      }

    /**
     * The subset of [leasedSessions] whose holder was recently active, i.e. keeping the idle clock
     * busy. Leases with none busy are idle tabs keeping a daemon warm.
     */
    val busyLeasedSessions: List<String> =
      sessions.busyLeasedSessions().let { held ->
        if (onlySystem == null) held else held.filter { it == onlySystem }
      }
    val openRenderBreakerCount: Int = running.count { it.renderStats?.breaker?.open == true }
    val currentLiveRenderFailureCount: Int = running.count {
      it.renderStats?.let { stats -> stats.breaker?.open != true && stats.lastRenderFailed } == true
    }
    val catalogLoadFailureCount: Int = catalogs.count { it.loadError != null }
    /**
     * This container's subprocesses ([ServeProcessCensusSnapshot]), shared by page and JSON.
     * Box-wide, so omitted for a site like [branchFetch], [themeCache] and [catalogCache]: a census
     * can't attribute a process to a catalog.
     */
    val processCensus: ServeProcessCensusSnapshot? =
      if (onlySystem == null) processCensusSampler.sample() else null
    val overallOk: Boolean =
      failures.isEmpty() &&
        catalogLoadFailureCount == 0 &&
        openRenderBreakerCount == 0 &&
        currentLiveRenderFailureCount == 0

    private fun backendOf(weight: Int): String = if (weight >= 2) "android" else "desktop"

    /**
     * One line of live-lane cadence: delivered fps, median gap, per-frame wire cost, and heartbeats
     * (which distinguish quiet from stalled).
     */
    private fun liveFrameText(stats: LiveFramePerfSnapshot): String = buildString {
      append(stats.achievedFps?.let { "$it fps" } ?: "no frames yet")
      stats.p50IntervalMs?.let { append(" · p50 ${it}ms") }
      append(" · ${stats.frames} painted")
      if (stats.heartbeats > 0) append(" · ${stats.heartbeats} unchanged")
      stats.avgPayloadBytes?.let { append(" · ${humanBytes(it.toInt())}/frame") }
    }

    private fun countLabel(count: Int, singular: String): String =
      "$count $singular${if (count == 1) "" else "s"}"

    /**
     * Whether the theme optimizer's quiet gate is open, and what holds it shut — otherwise
     * indistinguishable from idle. The threshold is printed beside the reading.
     */
    /**
     * The effective stop/resume thresholds as pairs per limb: the gap is the tuning (a narrow band
     * the optimizer's own load crosses will flap). `quiet` closes it.
     */
    private fun optimizerThresholdText(t: OptimizerPressureThresholds): String =
      listOf(
          "load ${trimZeros(t.stopLoadPerCpu)}→${trimZeros(t.resumeLoadPerCpu)}/cpu",
          "cpu ${formatPercent(t.stopCpuUtilization)}→${formatPercent(t.resumeCpuUtilization)}",
          "mem ${formatPercent(t.stopMemoryAvailableFraction)}→" +
            formatPercent(t.resumeMemoryAvailableFraction),
          "quiet ${t.resumeQuietMillis / 1000}s",
        )
        .joinToString(" · ")

    /**
     * Host and cgroup headroom shown apart, naming the governing one; only when both are known and
     * differ materially.
     */
    private fun memoryCeilingText(pressure: OptimizerPressureSnapshot): String? {
      val host = pressure.memoryHostAvailableFraction ?: return null
      val cgroup = pressure.memoryCgroupAvailableFraction ?: return null
      val governing = if (cgroup <= host) "container limit" else "host"
      return "host ${formatPercent(host)} · container ${formatPercent(cgroup)} · " +
        "$governing governs"
    }

    /**
     * `8.0 / 8.0 GB · 100% · 171 evicted` — the cap is always shown. Hit rate only once there have
     * been reads.
     */
    private fun catalogCacheText(c: CatalogBlobPoolSnapshot): String {
      val fill = if (c.maxBytes > 0) " · ${formatPercent(c.bytes.toDouble() / c.maxBytes)}" else ""
      val reads = c.hits + c.misses
      val hitRate = if (reads > 0) " · ${formatPercent(c.hits.toDouble() / reads)} hits" else ""
      val evicted = if (c.evicted > 0) " · ${c.evicted} evicted" else ""
      return "${gigabytes(c.bytes)} / ${gigabytes(c.maxBytes)}$fill$hitRate$evicted"
    }

    /** GB rather than [humanBytes]' MB ceiling: this pool is sized in gigabytes. */
    private fun gigabytes(bytes: Long): String =
      String.format(java.util.Locale.ROOT, "%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0))

    /** Matches the phrasing the gate's own `reason` strings use, so the two rows agree. */
    private fun formatPercent(value: Double): String =
      "%.0f%%".format(java.util.Locale.ROOT, value * 100.0)

    /** `2.0` reads better than `2.0000001`, and `0.85` must not become `1`. */
    private fun trimZeros(value: Double): String =
      if (value == value.toLong().toDouble()) value.toLong().toString()
      else value.toString().trimEnd('0').trimEnd('.')

    private fun optimizerGateText(admission: ThemeOptimizerAdmissionSnapshot): String {
      val needs = "needs ${admission.idleThresholdMillis / 1000}s quiet"
      // A host sitting on a stop threshold runs on the starvation cap's bounded windows; say so,
      // since it otherwise looks like a healthy but slow gate. Appended because the quiet gate
      // still applies.
      val pressure = admission.pressure
      val cycles = pressure?.dutyCycles ?: 0
      val dutyCycles =
        when {
          pressure?.dutyCycleUntilEpochMillis != null ->
            " · duty cycle $cycles" + (pressure.reason?.let { " · $it" } ?: "")
          cycles > 0 -> " · ${countLabel(cycles, "duty cycle")}"
          else -> ""
        } +
          // Residency: more unfinished catalogs than lanes with `0 parked` means daemons are pinned
          // by their own backlog.
          if (admission.hostSuspensions > 0 || admission.hostResumes > 0)
            " · ${admission.hostSuspensions} parked / ${admission.hostResumes} resumed"
          else ""
      if (admission.paused) {
        return "paused" + (admission.pauseReason?.let { " · $it" } ?: "") + dutyCycles
      }
      val idle =
        admission.serverIdleMillis
          ?: run {
            val why =
              when (admission.idleBlockedBy) {
                ServeBackgroundWork.IDLE_BLOCKED_BY_SESSION_LEASE ->
                  // Only busy holders: an idle tab's lease keeps its session resident without
                  // shutting this gate.
                  if (busyLeasedSessions.isEmpty()) "session lease held"
                  else "session lease held by ${busyLeasedSessions.joinToString(", ")}"
                ServeBackgroundWork.IDLE_BLOCKED_BY_CATALOG_LOAD -> "catalogs loading"
                else -> "server busy"
              }
            return "closed · $why · $needs$dutyCycles"
          }
      val open = idle >= admission.idleThresholdMillis
      return "${if (open) "open" else "closed"} · idle ${idle / 1000}s · $needs$dutyCycles"
    }

    fun toResponse(): StatusResponse {
      // One pass over the image store: it sweeps expired entries as it counts, so reading it twice
      // is both wasted work and two answers that can disagree.
      val imageOccupancy = imageStore?.occupancy()
      return StatusResponse(
        version = SERVE_VERSION,
        public = isPublic,
        status = if (overallOk) "ok" else "degraded",
        uptimeSeconds = uptimeSeconds,
        catalogs =
          CatalogSummaryDto(
            total = catalogs.size,
            listed = catalogs.count { it.listed },
            unlisted = catalogs.count { !it.listed },
            trusted = catalogs.count { it.trust != null && it.trust != "unverified" },
            degraded =
              catalogs.count {
                it.degradation != null || it.loadError != null || it.failedRenders > 0
              },
            loaded = catalogs.count { it.available },
            failed = catalogs.count { !it.available && it.loadError != null },
            pending = catalogs.count { !it.available && it.loadError == null },
          ),
        daemons =
          DaemonSummaryDto(
            known = knownSessions,
            running = liveDaemons.size,
            activeStreams = activeStreams,
            liveSeatsTotal = if (liveSeats.unbounded) 0 else liveSeats.totalPermits,
            liveSeatsAvailable = if (liveSeats.unbounded) -1 else liveSeats.availablePermits(),
            liveSeatsUnbounded = liveSeats.unbounded,
            perPreviewSeatsTotal = if (liveSeats.unbounded) 0 else liveSeats.perPreviewPermits,
            perPreviewSeatsAvailable =
              if (liveSeats.unbounded) -1 else liveSeats.perPreviewPermitsAvailable(),
            liveSeatRefusals = liveSeats.refusalCount(),
            liveSeatRefusalsUnverified = liveSeats.unverifiedRefusalCount(),
            leasedSessions = leasedSessions,
            busyLeasedSessions = busyLeasedSessions,
            spareSandboxes =
              spareSandboxSnapshot()?.let {
                SpareSandboxesDto(
                  warm = it.warm,
                  booting = it.booting,
                  signatures = it.signatures,
                  adopted = it.adopted,
                  returned = it.returned,
                  coldLaunches = it.coldLaunches,
                )
              },
          ),
        config =
          ConfigDto(
            host = host,
            port = port,
            allowRenderTrusted = allowRenderTrusted,
            trustStore = trustStoreConfigured,
            acceptBundles = acceptBundlesEnabled,
            acceptDocs = docStore != null,
            docTtlSeconds = docStore?.ttlSeconds ?: 0,
            acceptImages = imageStore != null,
            imageTtlSeconds = imageStore?.ttlSeconds ?: 0,
            imageUploadRepository = imageUploadAuth?.repository,
            imagesHeld = imageOccupancy?.count ?: 0,
            imageBytesHeld = imageOccupancy?.totalBytes ?: 0,
            catalogRefreshSeconds = catalogRefreshSeconds,
            catalogRegistries = catalogRegistries(),
            uiBuilderCatalogProblems = uiBuilderCatalogProblems(),
            maxConcurrentRenders = renderSlots,
            liveSeats = liveSeats.totalPermits,
          ),
        catalogList =
          catalogs.map { c ->
            CatalogDto(
              id = c.id,
              listed = c.listed,
              title = c.title,
              trust = c.trust,
              previews = c.previews,
              failedRenders = c.failedRenders,
              deferredPreviews = c.deferredPreviews,
              live = c.live,
              running = c.running,
              degradation = c.degradation,
              repo = c.provenance?.repo,
              branch = c.provenance?.branch,
              generatedAt = c.provenance?.generatedAt,
              composeAiToolsVersion = c.provenance?.toolVersion,
              designParityVersion = c.provenance?.designParityVersion,
              path = "/${c.id}/",
              metaStale = c.stale,
              loadState = c.loadState,
              loadError = c.loadError,
              lastLoadAttemptEpochMillis = c.lastLoadAttemptEpochMillis,
              themeOptimization = c.themeOptimization,
              renderCache = c.renderCache,
            )
          },
        runningServers =
          running.map { d ->
            val static = d.pinned
            RunningServerDto(
              id = d.id,
              label = d.label,
              backend = if (static) "static" else backendOf(d.liveSeatWeight),
              seatWeight = if (static) 0 else d.liveSeatWeight,
              activeStreams = if (static) 0 else d.activeStreams,
              uptimeSeconds = d.startedAt?.let { ((nowMillis - it) / 1000).coerceAtLeast(0) },
              renderStats = d.renderStats,
              liveFrames = liveFrameStats.snapshot(d.id),
              daemonPools = d.daemonPools,
            )
          },
        recentDaemonFailures = failures.map { FailureDto(it.atEpochMillis, it.session, it.reason) },
        // Omitted on a site host: these counters are box-wide, and including them would fire a
        // site's monitor on a neighbour and reveal the neighbour.
        branchFetch = if (onlySystem == null) branchFetchStats?.invoke() else null,
        // Box-wide and unattributed per system, so scoped out on a site host for the same reason
        // the branch counters are.
        themeOptimizer = optimizerAdmission,
        themeCache = if (onlySystem == null) themeCacheStats?.invoke() else null,
        // Whole-box like themeCache, and for the same reason: the pool is shared across every
        // catalog, so a site host scoped to one of them has nothing of its own to report here.
        catalogCache = if (onlySystem == null) catalogCacheStats?.invoke() else null,
        renderStats =
          RenderPerfSnapshot.aggregate(
            // A fresh daemon reports an all-zero snapshot; keep the roll-up null until something
            // has actually rendered so a quiet server doesn't advertise a block of zeros.
            running.mapNotNull { it.renderStats }.filter { it.renders + it.cacheHits + it.busy > 0 }
          ),
        // Scoped like everything else on a site host: a per-site monitor is told about its own
        // live lane, never a neighbour's.
        liveFrames = liveFrameStats.snapshot(onlySystem),
        // Omitted entirely on a site-scoped snapshot, like `branchFetch` above and for the same
        // reason: a site host answers for one app, and grants belong to the box.
        agentAccess =
          agentGrants
            ?.takeIf { onlySystem == null }
            ?.let { store ->
              val now = System.currentTimeMillis()
              val live = store.activeGrants()
              AgentAccessDto(
                activeGrants = live.size,
                pendingRequests = store.pendingRequests().size,
                maxScope = store.maxScope.wire,
                maxTtlSeconds = store.maxGrantTtlSeconds,
                maxCapabilities = AgentGrantCapability.wireNames(store.maxCapabilities),
                grants =
                  live.map { grant ->
                    AgentGrantDto(
                      fingerprint = grant.fingerprint,
                      scopes = grant.scopes.map { it.wire },
                      capabilities = AgentGrantCapability.wireNames(grant.capabilities),
                      approvedBy = grant.approvedBy,
                      expiresInSeconds = grant.secondsUntilExpiry(now),
                      label = grant.label,
                    )
                  },
              )
            },
        uiBuilder =
          uiBuilderDiagnostics
            ?.takeIf { onlySystem == null }
            ?.diagnostics()
            ?.let { diagnostics ->
              // Zero is how a storage that bounds nothing reports, and it must not read as a
              // measured 0%: the storage rows go null together rather than being carried as zeroes.
              val ceiling = diagnostics.storageMaximumBytes.takeIf { it > 0 }
              UiBuilderDto(
                activeSubscribers = diagnostics.activeSubscribers,
                peakSubscribers = diagnostics.peakSubscribers,
                rejectedBatchLimit = diagnostics.rejectedBatchLimit,
                rejectedSubscriberLimit = diagnostics.rejectedSubscriberLimit,
                slowSubscribersClosed = diagnostics.slowSubscribersClosed,
                rejectedPresenceLimit = diagnostics.rejectedPresenceLimit,
                activeExports = diagnostics.activeExports,
                peakExports = diagnostics.peakExports,
                rejectedExportLimit = diagnostics.rejectedExportLimit,
                rejectedMutationRate = diagnostics.rejectedMutationRate,
                rejectedDocumentBytes = diagnostics.rejectedDocumentBytes,
                rejectedAssetBytes = diagnostics.rejectedAssetBytes,
                timedOutExports = diagnostics.timedOutExports,
                activeMutationBuckets = diagnostics.activeMutationBuckets,
                persistenceMigrations = diagnostics.persistenceMigrations,
                unusableDesigns = diagnostics.unusableDesigns,
                degradedDesigns = diagnostics.degradedDesigns,
                rePinnedDesigns = diagnostics.rePinnedDesigns,
                rePinPersistenceFailure = diagnostics.rePinPersistenceFailure,
                storageBytes = ceiling?.let { diagnostics.storageBytes },
                storageMaximumBytes = ceiling,
                storageUsedPercent =
                  ceiling?.let { storageUsedPercent(diagnostics.storageBytes, it) },
              )
            },
        playground =
          playgroundHealth?.invoke()?.let { h ->
            PlaygroundDto(
              admittedBy = h.admittedBy,
              publicSurface = h.publicSurface,
              sandbox =
                SandboxDto(
                  profile = h.sandboxProfile,
                  active = h.sandboxActive,
                  jailDropped = h.jailDropped,
                  memoryMb = h.sandboxMemoryMb,
                  cpus = h.sandboxCpus,
                  ttlSeconds = h.sandboxTtlSeconds,
                  probe =
                    h.probe?.let { p ->
                      ProbeDto(
                        ran = p.ran,
                        detail = p.detail,
                        failedChecks = p.failedChecks(),
                        egressBlocked = p.egressBlocked,
                        filesystemContained = p.filesystemContained,
                        processIsolated = p.processIsolated,
                        workDirWritable = p.workDirWritable,
                      )
                    },
                ),
              compilerJailed = h.compilerJailed,
              compileSlots = h.compileSlots,
              modes =
                h.modes().map {
                  ModeDto(mode = it.mode, source = it.source, resolved = it.resolved)
                },
              catalogSelector =
                h.catalogSelector?.invoke()?.let {
                  // Scoped like everything else on a site's status: the selector must not name a
                  // neighbouring catalog through a hostname that publishes one app.
                  CatalogSelectorDto(
                    offered = offeredCatalogs(it.offered),
                    // Omitted when scoped: `resolved` counts box-wide catalogs with no per-catalog
                    // breakdown, so it would be inconsistent and leak the neighbour count.
                    resolved = if (onlySystem == null) it.resolved else null,
                    limit = it.limit,
                  )
                },
              rateLimit =
                playgroundRateLimiter?.let {
                  RateLimitDto(
                    activeCallers = it.activeCallers(),
                    trackedCallers = it.trackedCallers(),
                  )
                },
              editing =
                h.editing?.invoke()?.let {
                  EditingDto(
                    enabled = it.enabled,
                    active = it.active,
                    expiresAtEpochMs = it.expiresAtEpochMs,
                    lastRevision = it.lastRevision,
                    acquisitions = it.acquisitions,
                    compileAttempts = it.compileAttempts,
                    incrementalCompiles = it.incrementalCompiles,
                    fullFallbacks = it.fullFallbacks,
                    lastCompileMillis = it.lastCompileMillis,
                  )
                },
            )
          },
        processes = processCensus,
      )
    }

    /**
     * [agentGrants] / [agentGrantRequests] are passed in because revoke buttons depend on who is
     * reading the page.
     */
    fun toView(
      agentGrants: List<ServeWeb.StatusAgentGrant> = emptyList(),
      agentGrantRequests: List<ServeWeb.StatusAgentRequest> = emptyList(),
      hiddenAgentGrants: Int = 0,
    ): ServeWeb.StatusView {
      val seatsText =
        if (liveSeats.unbounded) "unbounded"
        else "${liveSeats.availablePermits()} free / ${liveSeats.totalPermits}"
      val renderAgg =
        RenderPerfSnapshot.aggregate(
          running.mapNotNull { it.renderStats }.filter { it.renders + it.cacheHits + it.busy > 0 }
        )
      val summary = buildList {
        val loadedCatalogs = catalogs.count { it.available }
        add(
          ServeWeb.Stat(
            "Catalogs",
            "$loadedCatalogs/${catalogs.size} loaded",
            ServeWeb.Meter(
              catalogs.size.toLong(),
              listOf(
                ServeWeb.MeterSegment("loaded", loadedCatalogs.toLong(), "primary"),
                ServeWeb.MeterSegment(
                  "unavailable",
                  (catalogs.size - loadedCatalogs).toLong(),
                  "warning",
                ),
              ),
            ),
          )
        )
        val published = catalogs.sumOf { it.previews ?: 0 }
        val publishedFailures = catalogs.sumOf { it.failedRenders }
        val publishedDeferred = catalogs.sumOf { it.deferredPreviews }
        val publishedRendered = (published - publishedFailures - publishedDeferred).coerceAtLeast(0)
        add(
          ServeWeb.Stat(
            "Published catalog renders",
            "$publishedRendered rendered · " +
              "$publishedFailures failed · $publishedDeferred deferred",
            ServeWeb.Meter(
              published.toLong(),
              listOf(
                ServeWeb.MeterSegment("rendered", publishedRendered.toLong(), "primary"),
                ServeWeb.MeterSegment("failed", publishedFailures.toLong(), "warning"),
                ServeWeb.MeterSegment("deferred", publishedDeferred.toLong(), "muted"),
              ),
            ),
          )
        )
        add(ServeWeb.Stat("Live daemons running", liveDaemons.size.toString()))
        add(ServeWeb.Stat("Active streams", activeStreams.toString()))
        // What those streams achieve (fps), which the stream count alone doesn't say.
        liveFrameStats.snapshot(onlySystem)?.let {
          add(ServeWeb.Stat("Live frames", liveFrameText(it)))
        }
        // The optimizer's input beside its output counters, distinguishing a polite pause from a
        // gate that will never open.
        optimizerAdmission?.let {
          add(ServeWeb.Stat("Theme optimiser gate", optimizerGateText(it)))
          // The thresholds the gate was judged against (set by system property outside the image),
          // giving the reading a scale.
          it.pressure?.thresholds?.let { t ->
            add(ServeWeb.Stat("Theme optimiser limits", optimizerThresholdText(t)))
          }
          // Which ceiling the memory limb reads: a container at its cap and a full machine both
          // show 0% but need different fixes.
          it.pressure?.let { p ->
            memoryCeilingText(p)?.let { text ->
              add(ServeWeb.Stat("Optimiser memory headroom", text))
            }
          }
        }
        // Fill against the cap with a meter, plus evictions.
        catalogCache?.let {
          add(
            ServeWeb.Stat(
              "Catalog blob cache",
              catalogCacheText(it),
              if (it.maxBytes <= 0) null
              else
                ServeWeb.Meter(
                  it.maxBytes,
                  listOf(
                    ServeWeb.MeterSegment(
                      "used",
                      it.bytes.coerceIn(0, it.maxBytes),
                      if (it.bytes * 10 >= it.maxBytes * 9) "warning" else "secondary",
                    ),
                    ServeWeb.MeterSegment(
                      "free",
                      (it.maxBytes - it.bytes).coerceAtLeast(0),
                      "primary",
                    ),
                  ),
                ),
            )
          )
        }
        add(
          ServeWeb.Stat(
            "Live seats",
            seatsText,
            if (liveSeats.unbounded) null
            else {
              val free = liveSeats.availablePermits().toLong()
              val total = liveSeats.totalPermits.toLong()
              ServeWeb.Meter(
                total,
                listOf(
                  ServeWeb.MeterSegment("in use", (total - free).coerceAtLeast(0), "secondary"),
                  ServeWeb.MeterSegment("free", free, "primary"),
                ),
              )
            },
          )
        )
        add(ServeWeb.Stat("Known sessions", knownSessions.toString()))
        // Subprocesses, which session counts miss. Zombies are shown separately from live JVMs
        // (they hold a PID, no memory). See [ServeProcessCensusSnapshot].
        processCensus?.let { census ->
          add(
            ServeWeb.Stat(
              "Processes",
              buildString {
                append("${census.liveJava} live JVMs · ${census.total} total")
                if (census.zombies > 0) {
                  append(" · ${census.zombies} defunct")
                  census.zombieCommands.entries.firstOrNull()?.let { append(" (${it.key})") }
                }
                census.pidsMax?.let { max -> append(" · ${census.pidsCurrent ?: 0}/$max pids") }
              },
              // The meter is the PID budget; an unbounded budget (null `pidsMax`) draws none.
              census.pidsMax?.let { max ->
                val used = census.pidsCurrent ?: (census.total.toLong())
                ServeWeb.Meter(
                  max,
                  listOf(
                    ServeWeb.MeterSegment("defunct", census.zombies.toLong(), "warning"),
                    ServeWeb.MeterSegment(
                      "live",
                      (used - census.zombies).coerceAtLeast(0),
                      "secondary",
                    ),
                    ServeWeb.MeterSegment("free", (max - used).coerceAtLeast(0), "primary"),
                  ),
                )
              },
            )
          )
        }
        add(ServeWeb.Stat("Uptime", formatDuration(uptimeSeconds)))
        if (renderAgg != null) {
          add(
            ServeWeb.Stat(
              "Live renders",
              "${renderAgg.ok} ok · ${renderAgg.failed} failed · " +
                "${renderAgg.cacheHits} cached",
              ServeWeb.Meter(
                renderAgg.ok + renderAgg.failed + renderAgg.cacheHits,
                listOf(
                  ServeWeb.MeterSegment("ok", renderAgg.ok, "primary"),
                  ServeWeb.MeterSegment("failed", renderAgg.failed, "warning"),
                  ServeWeb.MeterSegment("cached", renderAgg.cacheHits, "secondary"),
                ),
              ),
            )
          )
          renderAgg.avgMs?.let { add(ServeWeb.Stat("Average render latency", "${it}ms")) }
          renderAgg.firstRenderMs?.let { add(ServeWeb.Stat("Worst first render", "${it}ms")) }
        }
      }
      val config =
        listOf(
          ServeWeb.Stat("Access", if (isPublic) "public (open)" else "token-gated"),
          ServeWeb.Stat("Bind", "$host:$port"),
          ServeWeb.Stat("Trusted re-render", if (allowRenderTrusted) "on" else "off"),
          ServeWeb.Stat("Trust store", if (trustStoreConfigured) "configured" else "none"),
          ServeWeb.Stat(
            "Catalog refresh",
            if (catalogRefreshSeconds > 0) "${catalogRefreshSeconds}s" else "disabled",
          ),
          // "none" distinguishes no `--catalog-registry` from a failed registry read.
          ServeWeb.Stat(
            "Catalog registry",
            catalogRegistries().let { registries ->
              if (registries.isEmpty()) "none"
              else
                registries.joinToString(" · ") { r ->
                  val where = r.ref?.let { "${r.repo}@$it" } ?: r.repo
                  when {
                    // A failed re-read after a clean one: the last document still stands (the sync
                    // retires nothing on a pass that could not read), so say both halves.
                    r.error != null && r.catalogs > 0 ->
                      "$where — ${r.catalogs} catalog(s), last read failed"
                    r.error != null -> "$where — unreadable"
                    r.catalogs == 0 -> "$where — 0 catalogs"
                    else -> "$where — ${r.catalogs} catalog(s)"
                  }
                }
            },
          ),
          ServeWeb.Stat(
            "Live seats",
            if (liveSeats.unbounded) "unbounded" else liveSeats.totalPermits.toString(),
          ),
          ServeWeb.Stat("Render slots", renderSlots.toString()),
          ServeWeb.Stat("Accept uploads", if (acceptBundlesEnabled) "on" else "off"),
          ServeWeb.Stat(
            "Accept documents",
            if (docStore == null) "off"
            else "on (${ServeWeb.humanDuration(docStore.ttlSeconds)} links)",
          ),
          // Short because the column is narrow; who may upload is on `/status.json`, in the startup
          // log, and in the refusal.
          ServeWeb.Stat(
            "Accept images",
            if (imageStore == null || imageUploadAuth == null) "off"
            else "on (${ServeWeb.humanDuration(imageStore.ttlSeconds)} links)",
          ),
        )
      return ServeWeb.StatusView(
        agentGrants = agentGrants,
        agentGrantRequests = agentGrantRequests,
        hiddenAgentGrants = hiddenAgentGrants,
        version = SERVE_VERSION,
        public = isPublic,
        nowMillis = nowMillis,
        overallOk = overallOk,
        healthReason =
          buildList {
              if (catalogLoadFailureCount > 0)
                add(countLabel(catalogLoadFailureCount, "catalog load failure"))
              if (failures.isNotEmpty()) add(countLabel(failures.size, "daemon startup failure"))
              if (openRenderBreakerCount > 0)
                add(countLabel(openRenderBreakerCount, "open live render breaker"))
              if (currentLiveRenderFailureCount > 0)
                add(countLabel(currentLiveRenderFailureCount, "current live render failure"))
            }
            .takeIf { it.isNotEmpty() }
            ?.joinToString(" · "),
        healthHref =
          when {
            catalogLoadFailureCount > 0 -> "#catalogs"
            failures.isNotEmpty() -> "#recent-daemon-failures"
            openRenderBreakerCount > 0 -> "#recent-render-failures"
            currentLiveRenderFailureCount > 0 -> "#recent-render-failures"
            else -> null
          },
        summary = summary,
        config = config,
        catalogs =
          catalogs.map { c ->
            ServeWeb.StatusCatalog(
              id = c.id,
              title = c.title ?: c.id,
              listed = c.listed,
              trust = c.trust,
              previews = c.previews ?: 0,
              failedRenders = c.failedRenders,
              deferredPreviews = c.deferredPreviews,
              live = c.live,
              running = c.running,
              degradation = c.degradation,
              stale = c.stale,
              provenance = c.provenance,
              loadState = c.loadState,
              loadError = c.loadError,
              themeOptimization = c.themeOptimization,
              renderCache = c.renderCache,
            )
          },
        servers =
          running.map { d ->
            ServeWeb.StatusServer(
              id = d.id,
              label = d.label,
              backend = backendOf(d.liveSeatWeight),
              activeStreams = d.activeStreams,
              upForText =
                d.startedAt?.let { formatDuration(((nowMillis - it) / 1000).coerceAtLeast(0)) }
                  ?: "—",
            )
          },
        failures =
          failures.map { f ->
            ServeWeb.StatusFailure(
              whenText = formatInstant(f.atEpochMillis),
              session = f.session,
              reason = f.reason,
            )
          },
        renderFailures =
          running
            .flatMap { daemon ->
              daemon.renderStats?.recentFailures.orEmpty().map { failure -> daemon to failure }
            }
            .sortedByDescending { (_, failure) -> failure.atEpochMillis }
            .take(RenderPerfStats.FAILURE_WINDOW_SIZE)
            .map { (daemon, failure) ->
              ServeWeb.StatusRenderFailure(
                whenText = formatInstant(failure.atEpochMillis),
                session = daemon.label,
                durationText =
                  "${failure.durationMs}ms" + if (failure.timedOut) " (timeout)" else "",
                reason = failure.reason,
              )
            },
      )
    }
  }

  /**
   * Assemble the status snapshot. Liveness comes from [ServeSessionRegistry.runningDaemons]
   * (non-resuming): a pinned baked host is always present; a live catalog is present-with-stream
   * when its daemon is up and absent when suspended. Metadata comes from
   * [ServeSessionRegistry.peekHost], falling back to [catalogMetaSeen] (flagged
   * [CatalogStat.stale]) rather than resuming or reporting blank (which would read as untrusted).
   */
  private fun buildStatusData(onlySystem: String? = null): StatusData {
    val allRunning = sessions.runningDaemons()
    val running = if (onlySystem == null) allRunning else allRunning.filter { it.id == onlySystem }
    val byId = allRunning.associateBy { it.id }
    val tracked = catalogLoads?.snapshot()?.associateBy { it.config.system }.orEmpty()
    val allEntries =
      if (tracked.isNotEmpty()) tracked.values.map { it.config.system to it.config.listed }
      else listedCatalogs().map { it to true } + unlistedCatalogs().map { it to false }
    val entries =
      if (onlySystem == null) allEntries else allEntries.filter { it.first == onlySystem }
    val catalogs = entries.map { (id, listed) ->
      val load = tracked[id]
      val daemon = byId[id]
      val host = sessions.peekHost(id)
      val bundle = host?.let { catalogBundleHost(it) }
      // Liveness from the resident snapshot only (never resume): absent ⇒ a suspended live
      // catalog; present-with-live-stream ⇒ its daemon is up; present-without ⇒ static baked host.
      val running = daemon?.hasLiveStream == true
      val available = load?.available ?: true
      val live = available && (daemon == null || daemon.hasLiveStream)
      // Resident: read live and refresh the snapshot (a catalog refresh can change provenance).
      if (host != null) rememberCatalogMeta(id, host)
      val seen = if (host == null) catalogMetaSeen[id] else null
      CatalogStat(
        id = id,
        listed = listed,
        title = bundle?.title?.takeIf { it.isNotBlank() } ?: host?.label ?: seen?.title,
        trust = bundle?.let { BundleVerifier.summary(it.trust) } ?: seen?.trust,
        previews = host?.previews?.size ?: seen?.previews,
        failedRenders =
          host?.previews?.count { it.renderFailure != null }
            ?: seen?.failedRenders
            ?: load?.failedRenders
            ?: 0,
        deferredPreviews = host?.liveOnlyPreviewIds?.size ?: seen?.deferredPreviews ?: 0,
        live = live,
        running = running,
        degradation = host?.degradations?.firstOrNull()?.detail ?: seen?.degradation,
        provenance =
          bundle?.provenance
            ?: seen?.provenance
            ?: load?.config?.let { ServeWeb.CatalogProvenance(it.repo, it.branch) },
        available = available,
        loadError = load?.error,
        lastLoadAttemptEpochMillis = load?.lastAttemptEpochMillis,
        themeOptimization = host?.themeOptimizationSnapshot() ?: seen?.themeOptimization,
        renderCache = host?.catalogRenderCacheSnapshot() ?: seen?.renderCache,
        stale = seen != null,
      )
    }
    return StatusData(
      nowMillis = System.currentTimeMillis(),
      catalogs = catalogs,
      running = running,
      failures =
        daemonLog?.recent().orEmpty().let { failures ->
          if (onlySystem == null) failures else failures.filter { it.session == onlySystem }
        },
      onlySystem = onlySystem,
    )
  }

  /**
   * Resolve catalog [ids] into [ServeWeb.HomeSystem] cards without leasing (resident host, else the
   * snapshot from before suspension), so the front page never resumes idle daemons. Catalogs with
   * neither are skipped.
   */
  /**
   * The front-page card for a catalog configured but not loaded yet, or null. Startup loads
   * catalogs one at a time, so this keeps each card in its section until the load lands. A failed
   * load is skipped.
   */
  private fun loadingHomeSystem(system: String): ServeWeb.HomeSystem? {
    val state = catalogLoads?.snapshot()?.firstOrNull { it.config.system == system } ?: return null
    if (state.loadState != "pending") return null
    return ServeWeb.HomeSystem(
      group = state.config.group,
      system = system,
      title = system,
      subtitle = null,
      previewCount = 0,
      trust = null,
      sourceRepo = state.config.repo,
      importedFrom = state.config.importedFrom,
      heroPreviewId = null,
      heroImage =
        heroImages.cached(state.config)?.let {
          ServeWeb.HeroImage(
            "${ServeHeroImages.PATH_PREFIX}/$system/${it.fileName}",
            it.cssWidth,
            it.cssHeight,
          )
        },
      loading = true,
    )
  }

  private fun homeSystemsFor(ids: List<String>): List<ServeWeb.HomeSystem> {
    val views = engagementStore.systemViews(ids)
    // The front door's own set, for the sibling gate below. A list here and a membership test per
    // paired catalog would be quadratic in the number of catalogs on the box.
    val listed = ids.toSet()
    return ids.mapNotNull { system ->
      sessions.peekHost(system)?.let { rememberCatalogMeta(system, it, progress = false) }
      val meta = catalogMetaSeen[system] ?: return@mapNotNull loadingHomeSystem(system)
      ServeWeb.HomeSystem(
        // The front-page section this catalog was published under, straight from the operator's
        // config — the page then checks the claim against the catalog's actual provenance.
        group = catalogLoads?.configFor(system)?.group,
        system = system,
        title = meta.title ?: system,
        subtitle = meta.subtitle,
        previewCount = meta.previews ?: 0,
        views = views.getValue(system),
        trust = meta.trust,
        sourceRepo = meta.provenance?.repo,
        // The catalog's own declared source, so an import whose registration lost `importedFrom` is
        // still filed under the upstream owner.
        catalogSourceRepo = meta.catalogSourceRepo,
        // Attribution for an imported catalog: the project it was rendered from, which is neither
        // the serving repo nor anything the catalog's own provenance records.
        importedFrom = catalogLoads?.configFor(system)?.importedFrom,
        heroPreviewId = meta.heroPreviewId,
        heroCrop = meta.heroCrop,
        // The prebaked thumbnail, when the catalog has one: the card then points at the static
        // `/hero/` lane (crop already in the pixels) instead of the live `/render` endpoint.
        heroImage =
          meta.heroImage?.let {
            ServeWeb.HeroImage(
              path = "${ServeHeroImages.PATH_PREFIX}/$system/${it.fileName}",
              width = it.cssWidth,
              height = it.cssHeight,
            )
          },
        darkStage = meta.darkStage,
        // Whether the card offers the compare action, and — separately — what it calls it.
        hasReferenceComparison = meta.hasReferenceComparison,
        designToolLabel = meta.designToolLabel,
        // The paired catalog named by its current title, resolved here because the sibling's
        // residency and title change independently of this catalog.
        //
        // Gated on the destination's own condition: `parallelSpecSource` (which builds the wall's
        // `format=parallel` rows) needs the sibling resident (`peekHost`, never `lease`) and
        // publishing a counterpart component; anything weaker deep-links an empty format. Matches
        // the landing's chip. No daemon is woken.
        parallelComparison =
          meta.compareWithSystem?.let { sibling ->
            // Listed first: an unlisted catalog must not appear on the front door via a pairing.
            if (sibling !in listed) return@let null
            val siblingHost = sessions.peekHost(sibling) ?: return@let null
            if (siblingHost.previews.none { it.componentId in meta.parallelComponentIds }) {
              return@let null
            }
            ServeWeb.ParallelComparison(
              system = sibling,
              title =
                catalogBundleHost(siblingHost)?.title?.takeIf { it.isNotBlank() }
                  ?: catalogMetaSeen[sibling]?.title
                  ?: siblingHost.label.ifBlank { sibling },
            )
          },
      )
    }
  }

  /**
   * `GET /api/daemons` and `GET /{system}/api/daemons`: whether this catalog is backed by a live
   * render server, and how many processes. Reads via [ServeSessionRegistry.peekHost] so the probe
   * never creates the daemon it reports on.
   */
  private suspend fun RoutingContext.handleDaemonStatus(sessionInPath: Boolean) {
    if (rejectBadToken()) return
    val host = sessions.peekHost(selectedSessionId(sessionInPath))
    val pools =
      host?.let { runCatching { it.daemonPoolStats() }.getOrDefault(emptyList()) }.orEmpty()
    val allRunning = sessions.runningDaemons().filter { it.hasLiveStream }
    val dto =
      DaemonStatusDto(
        // Counted from real subprocesses, not `daemonStarted`, which a baked bundle inherits as
        // true and which is true for a pooled child.
        running = (host?.daemonProcessCount ?: 0) > 0,
        instances = host?.daemonProcessCount ?: 0,
        pooled = pools.sumOf { it.open },
        poolCapacity = pools.sumOf { it.maxOpen },
        activeStreams = host?.let { runCatching { it.activeStreamCount() }.getOrDefault(0) } ?: 0,
        overallRunning = allRunning.size,
        overallActiveStreams = allRunning.sumOf { it.activeStreams },
        liveSeatsTotal = if (liveSeats.unbounded) 0 else liveSeats.totalPermits,
        liveSeatsAvailable = if (liveSeats.unbounded) -1 else liveSeats.availablePermits(),
      )
    call.response.headers.append(HttpHeaders.CacheControl, DYNAMIC_RESOURCE_CACHE_CONTROL)
    call.respondText(
      JSON.encodeToString(DaemonStatusDto.serializer(), dto),
      ContentType.Application.Json,
    )
  }

  /**
   * `GET /api/render-runs/{name}`: one preview's publishes collapsed into runs of identical pixels
   * ([ServeCatalogRevision.renderRuns]), over [availableRevisions] — the list the menu draws — so
   * every `head` is a visible row.
   *
   * A branch that can't be asked is a `404`, not an empty list (which would claim all rows are
   * identical); likewise no delivery branch, an unknown preview, or no revisions.
   */
  private suspend fun RoutingContext.handleRenderRuns(sessionInPath: Boolean) {
    if (rejectBadToken()) return
    val previewId = call.parameters["name"].orEmpty()
    withLeasedSession(selectedSessionId(sessionInPath)) { renderHost ->
      val host = catalogBundleHost(renderHost)
      val revisions = host?.let { availableRevisions(it, previewId) }.orEmpty()
      // Every publish below the tip must be confirmed to carry this preview by a generation-time
      // index. [availableRevisions] fails open for branches with neither `preview-index.json` nor
      // image history (right for the menu); here that would create a phantom trailing run before
      // the preview existed. Legacy branches get no markers.
      val bounded =
        host != null &&
          revisions.drop(1).all { host.revisionContainsPreview(it.commit, previewId) == true }
      // On [Dispatchers.IO]: `renderChangeCommits` may hit the delivery branch (10s connect / 30s
      // read), and `withLeasedSession` runs its block on the request coroutine.
      val changed =
        withContext(Dispatchers.IO) {
          host?.renderChangeCommits(previewId, revisions.mapTo(mutableSetOf()) { it.commit })
        }
      if (changed == null || revisions.isEmpty() || !bounded) {
        call.respondText("not found", status = HttpStatusCode.NotFound)
        return@withLeasedSession
      }
      val sourceShas = revisions.associate { it.commit to it.sourceSha }
      val dto =
        RenderRunsResponse(
          runs =
            ServeCatalogRevision.renderRuns(revisions, changed).map { run ->
              RenderRunDto(
                head = run.head,
                sourceSha = sourceShas[run.head],
                commits = run.commits,
                open = run.open,
              )
            },
          revisions = revisions.size,
        )
      call.response.headers.append(HttpHeaders.CacheControl, DYNAMIC_RESOURCE_CACHE_CONTROL)
      call.respondText(
        JSON.encodeToString(RenderRunsResponse.serializer(), dto),
        ContentType.Application.Json,
      )
    }
  }

  /**
   * `GET /tags/{name}` and `GET /{system}/tags/{name}`: one preview's published element tag index
   * (`testTag → {count, bounds, space}`, as [ServeAnnotationsPayload.encodeTags] writes it).
   *
   * `.json` is accepted as an alias; the name is tried verbatim first, since an id may itself end
   * in `.json`. Reads only the catalog's `tags/index.json` via [ServeHost.tagIndexForPreview], so
   * it works for static bundles without a live-scope gate. An empty index is `{}`; an unserved
   * preview is 404.
   *
   * It doesn't establish that the index describes the caller's frame: it was measured in CI over
   * the baked render, so tag selection must be gated on the frame being baked
   * ([ServeWeb.ReferenceComparison.tagSelection]). Paired with a generation check
   * ([ServeCacheGeneration]): a publish the catalog has moved past is refused rather than answered
   * with today's bounds.
   */
  private suspend fun RoutingContext.handleTagIndex(sessionInPath: Boolean) {
    if (rejectBadToken() || rejectMalformedGeneration()) return
    val requested = call.parameters["name"].orEmpty()
    withLeasedSession(selectedSessionId(sessionInPath)) { renderHost ->
      // Once the catalog has moved past the requested publish, only today's bounds exist; refusing
      // lets the picker fail closed and the page reload ([ServeCacheGeneration]).
      val stale = staleGeneration(renderHost)
      if (stale != null) {
        call.respondText(
          "this catalog has moved on from " +
            "'${ServeCacheGeneration.PARAM}=${ServeCacheGeneration.short(stale)}'; the published " +
            "tag index describes the current render, not that one — reload the page",
          status = HttpStatusCode.Conflict,
        )
        return@withLeasedSession
      }
      // Verbatim wins over the alias, so a preview whose id really ends in `.json` keeps its own
      // index instead of being answered with another preview's.
      val previewId =
        when {
          renderHost.previews.any { it.id == requested } -> requested
          else ->
            requested.removeSuffix(".json").takeIf { alias ->
              renderHost.previews.any { it.id == alias }
            }
        }
      if (previewId == null) {
        call.respondText("not found", status = HttpStatusCode.NotFound)
        return@withLeasedSession
      }
      call.response.headers.append(HttpHeaders.CacheControl, DYNAMIC_RESOURCE_CACHE_CONTROL)
      call.respondBytes(
        ServeAnnotationsPayload.encodeTags(previewId, renderHost.tagIndexForPreview(previewId)),
        ContentType.Application.Json,
      )
    }
  }

  /**
   * `GET /parity/known-differences.json` (and `/{system}/…`): the catalog's committed
   * known-difference document, verbatim.
   *
   * Text, not re-serialised: `compose-preview-known-differences/v1` verdicts
   * (`document-unreadable`, `document-too-large`, duplicate ids, future schema tokens) belong to
   * the engine, which needs the bytes intact. See [ServeKnownDifferences].
   *
   * A catalog with none answers 404 (unlike `/tags/{name}`), since any invented body would be
   * judged as a document. `no-store`, since the verdict is resolved against a specific frame.
   */
  private suspend fun RoutingContext.handleKnownDifferences(sessionInPath: Boolean) {
    if (rejectBadToken()) return
    withLeasedSession(selectedSessionId(sessionInPath)) { renderHost ->
      call.response.headers.append(HttpHeaders.CacheControl, DYNAMIC_RESOURCE_CACHE_CONTROL)
      when (val document = renderHost.knownDifferences()) {
        null -> call.respondText("not found", status = HttpStatusCode.NotFound)
        is ServeKnownDifferences.Document.Text ->
          call.respondText(document.text, ContentType.Application.Json)
        // Refused by file length before reading; 413 so the consumer reaches `document-too-large`
        // without a truncated body.
        ServeKnownDifferences.Document.TooLarge ->
          call.respondText(
            "known-differences.json is over the ${ServeKnownDifferences.MAX_DOCUMENT_BYTES}-byte ceiling",
            status = HttpStatusCode.PayloadTooLarge,
          )
      }
    }
  }

  /**
   * `GET /parity/known-differences/{path...}` (and `/{system}/…`): one acceptance artifact as
   * bytes.
   *
   * Three distinct failure statuses, since the engine maps them to `path-not-contained`,
   * `artifact-too-large` and `artifact-unreadable` (a traversal should not look like a typo). Raw
   * bytes, so the browser decodes with the same PNG reader as the offline run rather than a canvas
   * that normalises to 8-bit RGBA.
   */
  private suspend fun RoutingContext.handleKnownDifferenceArtifact(sessionInPath: Boolean) {
    if (rejectBadToken()) return
    val requested = call.parameters.getAll("path").orEmpty().joinToString("/")
    withLeasedSession(selectedSessionId(sessionInPath)) { renderHost ->
      call.response.headers.append(HttpHeaders.CacheControl, DYNAMIC_RESOURCE_CACHE_CONTROL)
      when (val artifact = renderHost.knownDifferenceArtifact(requested)) {
        is ServeKnownDifferences.Artifact.Bytes ->
          call.respondBytes(artifact.bytes, ContentType.Image.PNG)
        ServeKnownDifferences.Artifact.NotContained ->
          call.respondText("not contained", status = HttpStatusCode.Forbidden)
        ServeKnownDifferences.Artifact.TooLarge ->
          call.respondText("too large", status = HttpStatusCode.PayloadTooLarge)
        ServeKnownDifferences.Artifact.Unreadable ->
          call.respondText("not found", status = HttpStatusCode.NotFound)
      }
    }
  }

  /** One scene document or texture for the WebGL/WebXR preview surface. */
  private suspend fun RoutingContext.handleSpatialAsset(sessionInPath: Boolean) {
    if (rejectBadToken() || rejectMalformedGeneration()) return
    val previewId = call.parameters["name"].orEmpty()
    val requested = call.parameters.getAll("path").orEmpty().joinToString("/")
    withLeasedSession(selectedSessionId(sessionInPath)) { renderHost ->
      if (staleGeneration(renderHost) != null) {
        call.respondText(
          "this catalog has moved on; reload the spatial preview",
          status = HttpStatusCode.Conflict,
        )
        return@withLeasedSession
      }
      val asset = renderHost.spatialAsset(previewId, requested)
      if (asset == null) {
        call.respondText("not found", status = HttpStatusCode.NotFound)
      } else {
        call.response.headers.append(HttpHeaders.CacheControl, DYNAMIC_RESOURCE_CACHE_CONTROL)
        call.respondBytes(asset.bytes, ContentType.parse(asset.contentType))
      }
    }
  }

  /** `GET /api/previews` and `GET /{system}/api/previews`: the session's preview JSON. */
  private suspend fun RoutingContext.handleApiPreviews(sessionInPath: Boolean) {
    if (rejectBadToken()) return
    val sessionId = selectedSessionId(sessionInPath)
    withLeasedSession(sessionId) { renderHost ->
      val previewEngagement = previewEngagement(sessionId, renderHost.previews)
      val dto =
        PreviewsResponse(
          module = renderHost.label,
          // The compose-ai-tools version that produced the snapshots. Native clients must match it
          // before substituting compiled composables; missing provenance fails closed to the
          // snapshots.
          catalogVersion = catalogBundleHost(renderHost)?.provenance?.toolVersion,
          // Producer-trust verdict for a bundle/catalog session (signature / branch / provenance /
          // unverified); null for a live daemon-backed module session.
          trust = catalogBundleHost(renderHost)?.let { BundleVerifier.summary(it.trust) },
          // Why the session is snapshot-only (no live lane), when it is — read off the host so a
          // programmatic client sees the same reason the viewer banner shows.
          degradations = renderHost.degradations.map { DegradationDto(it.code, it.detail) },
          views = engagementStore.systemViews(sessionId),
          previews =
            renderHost.previews.map { p ->
              PreviewDto(
                id = p.id,
                label = p.label,
                modes = p.modes.map { it.wire },
                overrides = p.overrides,
                remoteComposeKnobs = p.remoteComposeKnobs,
                spatial = p.spatial,
                liveOnly = p.id in renderHost.liveOnlyPreviewIds,
                views = previewEngagement.getValue(p.id).views,
                remoteCompose = renderHost.hasRemoteComposeDoc(p.id),
              )
            },
        )
      call.respondText(
        JSON.encodeToString(PreviewsResponse.serializer(), dto),
        ContentType.Application.Json,
      )
    }
  }

  /** One MCP message, using JSON response mode unless a negotiated request scope needs SSE. */
  private suspend fun RoutingContext.handleCatalogMcp() {
    val mcp = catalogMcp ?: return call.respond(HttpStatusCode.NotFound)
    val requestScopes = catalogMcpRequestScopes ?: return call.respond(HttpStatusCode.NotFound)
    val authorization = machineAuthorization ?: return call.respond(HttpStatusCode.NotFound)
    if (rejectCatalogMcpOrigin()) return

    val requestContentType =
      call.request.headers[HttpHeaders.ContentType]?.substringBefore(';')?.trim()
    if (!requestContentType.equals(ContentType.Application.Json.toString(), ignoreCase = true)) {
      call.respondText(
        "MCP POST requests require Content-Type: application/json",
        status = HttpStatusCode.UnsupportedMediaType,
      )
      return
    }

    val protocolVersion = call.request.headers[MCP_PROTOCOL_VERSION_HEADER]
    if (
      protocolVersion != null && protocolVersion !in ServeCatalogMcp.SUPPORTED_PROTOCOL_VERSIONS
    ) {
      call.respondText(
        "unsupported MCP protocol version '$protocolVersion'",
        status = HttpStatusCode.BadRequest,
      )
      return
    }

    val bytes =
      withContext(Dispatchers.IO) {
        call.receiveStream().use { readCapped(it, MAX_CATALOG_MCP_BYTES) }
      }
    if (bytes == null) {
      call.respondText("MCP request exceeds 1 MiB", status = HttpStatusCode.PayloadTooLarge)
      return
    }
    val request =
      try {
        JSON.parseToJsonElement(bytes.decodeToString()).jsonObject
      } catch (e: Exception) {
        call.respondText("invalid JSON-RPC request", status = HttpStatusCode.BadRequest)
        return
      }

    // A session id only hints that elicitation may work. Unknown, expired, evicted or
    // protocol-mismatched ids are served on the stateless JSON path, not 404/400, so restarts and
    // eviction never break clients; at worst they lose elicitation.
    val sessionId = call.request.headers[MCP_SESSION_ID_HEADER]
    val requestScope =
      sessionId?.let(requestScopes::find)?.takeIf { protocolVersion == it.protocolVersion }

    // A JSON-RPC response answers a server request emitted on another in-flight POST. It skips the
    // grant gate: it carries only a choice among the eliciting call's options, which acts under its
    // own authorization. It's correlated by the unguessable session id and must present the same
    // transport credential as the asking POST.
    if (request["method"] == null && request["id"] != null) {
      call.response.headers.append(HttpHeaders.CacheControl, "no-store")
      when (requestScopes.acceptResponse(sessionId, request, catalogMcpCredential())) {
        ServeMcpRequestScopes.ResponseDisposition.ACCEPTED -> call.respond(HttpStatusCode.Accepted)
        ServeMcpRequestScopes.ResponseDisposition.UNKNOWN_SESSION ->
          call.respondText("unknown or expired MCP session", status = HttpStatusCode.NotFound)
        ServeMcpRequestScopes.ResponseDisposition.UNKNOWN_REQUEST ->
          call.respondText("unknown MCP server request", status = HttpStatusCode.BadRequest)
        ServeMcpRequestScopes.ResponseDisposition.INVALID_RESPONSE ->
          call.respondText("invalid JSON-RPC response", status = HttpStatusCode.BadRequest)
      }
      return
    }

    // The gate sits below the parse so it can judge each message: discovery and the two access
    // tools are open (listed by [ServeCatalogMcp.requiresGrant]) so a credential-less client can
    // `initialize` and ask for one; catalog reads are gated, and unknown methods by default.
    // A grant may also ride the message; see [ServeCatalogMcp.TOKEN_ARGUMENT].
    // Read once so the endpoint gate and the tool judge the same credential.
    val presentedToken = ServeCatalogMcp.presentedToken(request)

    if (ServeCatalogMcp.requiresGrant(request)) {
      when (
        val decision =
          authorization.authorizeScope(
            call,
            AgentGrantScope.PREVIEW,
            presentedToken,
            allowBrowserGrantCookie = false,
          )
      ) {
        is ServeMachineAuthorization.Decision.Authorized -> Unit
        ServeMachineAuthorization.Decision.Missing -> {
          respondCatalogMcpAuthorization(
            HttpStatusCode.Unauthorized,
            "A short-lived preview grant is required.",
          )
          return
        }
        is ServeMachineAuthorization.Decision.Forbidden -> {
          respondCatalogMcpAuthorization(HttpStatusCode.Forbidden, decision.message)
          return
        }
      }
    }

    suspend fun dispatch(
      interaction: ServeCatalogMcp.ClientInteraction = ServeCatalogMcp.ClientInteraction.Unsupported
    ): ServeCatalogMcp.Reply =
      mcp.handle(
        request,
        access = agentGrants?.let { catalogMcpAgentAccess(it) },
        clientInteraction = interaction,
        // Asked per capability, off the same call the credential arrived on, so an MCP client
        // reaches the builder through exactly the door the browser does.
        uiBuilderAuthorization = { capability, presented ->
          uiBuilderAuthorization?.authorize(call, capability, presented)
            ?: UiBuilderAuthorizationDecision.Missing
        },
        liveAuthorization = { presented ->
          authorization.authorizeScope(
            call,
            AgentGrantScope.LIVE,
            presented,
            allowBrowserGrantCookie = false,
          )
        },
      )

    val isInitialize = (request["method"] as? JsonPrimitive)?.contentOrNull == "initialize"
    val acceptsRequestScope = acceptsCatalogMcpRequestScope()
    if (isInitialize) {
      val reply = dispatch()
      if (reply.accepted) {
        call.respond(HttpStatusCode.Accepted)
        return
      }
      val formVersion = formElicitationProtocol(request)
      // OpenAI form elicitation (`extensions["openai/elicitation"].form`) also needs the request
      // scope, whether or not plain form elicitation was declared beside it.
      val openAiVersion =
        (request["params"] as? JsonObject)
          ?.takeIf(ServeOpenAiForms::declaredIn)
          ?.let { (it["protocolVersion"] as? JsonPrimitive)?.contentOrNull }
          ?.takeIf { it in ELICITING_PROTOCOL_VERSIONS }
      val elicitingVersion = formVersion ?: openAiVersion
      if (acceptsRequestScope && elicitingVersion != null) {
        val scope =
          requestScopes.open(
            protocolVersion = elicitingVersion,
            formElicitationSupported = formVersion != null,
            openAiFormsSupported = openAiVersion != null,
          )
        // Capacity exhausted by pending interactions: stay stateless rather than refuse.
        scope?.let { call.response.headers.append(MCP_SESSION_ID_HEADER, it.id) }
      }
      call.response.headers.append(HttpHeaders.CacheControl, "no-store")
      call.respondText(reply.body.toString(), ContentType.Application.Json, HttpStatusCode.OK)
      return
    }

    suspend fun respondReply(reply: ServeCatalogMcp.Reply) {
      if (reply.accepted) {
        call.respond(HttpStatusCode.Accepted)
      } else {
        call.response.headers.append(HttpHeaders.CacheControl, "no-store")
        call.respondText(
          reply.body.toString(),
          ContentType.Application.Json,
          HttpStatusCode.OK,
        )
      }
    }

    // A negotiated session's response becomes SSE only once the call actually sends the client a
    // message; otherwise it is identical to the stateless path.
    if (requestScope != null && request["id"] != null && acceptsRequestScope) {
      requestScopes.dispatchLazily(
        requestScope,
        credential = catalogMcpCredential(),
        dispatch = { interaction -> dispatch(interaction) },
        onReply = { reply -> respondReply(reply) },
        onStream = { first, rest, reply ->
          call.response.headers.append(HttpHeaders.CacheControl, "no-store")
          call.respondBytesWriter(contentType = ContentType.Text.EventStream) {
            suspend fun emit(message: JsonObject) {
              writeStringUtf8("event: message\ndata: $message\n\n")
              flush()
            }
            emit(first)
            for (message in rest) emit(message)
            reply.await().body?.let { emit(it) }
          }
        },
      )
      return
    }

    respondReply(dispatch())
  }

  /**
   * The fetchable PNG a catalog `render_preview` result links to. Public (an `<img>` sends no
   * credential); the query's HMAC over one resource URI and expiry is the whole authorization, and
   * anything else is 404.
   */
  private suspend fun RoutingContext.handleSignedRenderPng() {
    val mcp = catalogMcp ?: return call.respond(HttpStatusCode.NotFound)
    val params = call.request.queryParameters
    val uri = params["uri"]
    val expiry = params["exp"]?.toLongOrNull()
    val signature = params["sig"]
    if (uri == null || expiry == null || signature == null) {
      return call.respond(HttpStatusCode.NotFound)
    }
    val png =
      try {
        mcp.signedImagePng(uri, expiry, signature)
      } catch (e: Exception) {
        null
      } ?: return call.respond(HttpStatusCode.NotFound)
    call.response.headers.append(HttpHeaders.CacheControl, "private, max-age=300")
    call.respondBytes(png, ContentType.Image.PNG)
  }

  /** This implementation needs only per-POST streams, not a long-lived notification channel. */
  private suspend fun RoutingContext.rejectCatalogMcpListen() {
    call.response.headers.append(HttpHeaders.Allow, HttpMethod.Post.value)
    call.respondText(
      "This catalog MCP endpoint uses request-scoped POST streams; GET is not available.",
      status = HttpStatusCode.MethodNotAllowed,
    )
  }

  /** Explicitly terminate a negotiated request scope; expiry remains the crash-safe fallback. */
  private suspend fun RoutingContext.handleCatalogMcpDelete() {
    if (rejectCatalogMcpOrigin()) return
    call.response.headers.append(HttpHeaders.CacheControl, "no-store")
    val sessionId = call.request.headers[MCP_SESSION_ID_HEADER]
    if (sessionId.isNullOrBlank()) {
      call.respondText("MCP-Session-Id is required", status = HttpStatusCode.BadRequest)
      return
    }
    val scope = catalogMcpRequestScopes?.find(sessionId)
    if (scope == null) {
      call.respondText("unknown or expired MCP session", status = HttpStatusCode.NotFound)
      return
    }
    if (call.request.headers[MCP_PROTOCOL_VERSION_HEADER] != scope.protocolVersion) {
      call.respondText(
        "MCP-Protocol-Version must match the negotiated session version",
        status = HttpStatusCode.BadRequest,
      )
      return
    }
    if (catalogMcpRequestScopes.close(sessionId)) {
      call.respond(HttpStatusCode.NoContent)
    } else {
      call.respondText("unknown or expired MCP session", status = HttpStatusCode.NotFound)
    }
  }

  /**
   * The credential material this POST carries on the transport, as one opaque string, or null. An
   * elicitation's answer must present the same material ([ServeMcpRequestScopes.acceptResponse]);
   * only a fingerprint is kept. In-band grants ([ServeCatalogMcp.TOKEN_ARGUMENT]) can't ride a
   * response, so such calls bind by session id alone.
   */
  private fun RoutingContext.catalogMcpCredential(): String? {
    val parts =
      listOf(
        call.request.headers[TOKEN_HEADER],
        call.request.headers[HttpHeaders.Authorization],
        call.request.queryParameters["token"],
        call.request.headers[HttpHeaders.Cookie],
      )
    if (parts.all { it == null }) return null
    return parts.joinToString("\u0000") { it ?: "" }
  }

  private fun RoutingContext.acceptsCatalogMcpRequestScope(): Boolean {
    val accepted =
      call.request.headers[HttpHeaders.Accept]
        .orEmpty()
        .split(',')
        .map { it.substringBefore(';').trim().lowercase() }
        .toSet()
    return ContentType.Application.Json.toString() in accepted &&
      ContentType.Text.EventStream.toString() in accepted
  }

  /**
   * The protocol version a request scope is negotiated under when `initialize` asks for form
   * elicitation on 2025-06-18 or 2025-11-25 (same wire), else null. 2025-03-26 has no elicitation.
   */
  private fun formElicitationProtocol(request: JsonObject): String? {
    val params = request["params"] as? JsonObject ?: return null
    val version =
      (params["protocolVersion"] as? JsonPrimitive)?.contentOrNull?.takeIf {
        it in ELICITING_PROTOCOL_VERSIONS
      } ?: return null
    val capabilities = params["capabilities"] as? JsonObject ?: return null
    val elicitation = capabilities["elicitation"] as? JsonObject ?: return null
    // Form support is `form` declared, or a bare `{}` (the shape before form/url split). A
    // URL-only client never gets a form, so it gets no request scope either.
    return version.takeIf { elicitation["form"] != null || elicitation["url"] == null }
  }

  /** MCP's DNS-rebinding guard: browser-originated calls may only come from this request's host. */
  private suspend fun RoutingContext.rejectCatalogMcpOrigin(): Boolean {
    val raw = call.request.headers[HttpHeaders.Origin] ?: return false
    val originHost = runCatching { URI(raw).host }.getOrNull()
    val requestHost =
      call.request.headers[HttpHeaders.Host]?.let { authority ->
        runCatching { URI("http://$authority").host }.getOrNull()
      }
    if (originHost != null && requestHost != null && originHost.equals(requestHost, true)) {
      return false
    }
    call.respondText("untrusted Origin", status = HttpStatusCode.Forbidden)
    return true
  }

  private suspend fun RoutingContext.respondCatalogMcpAuthorization(
    status: HttpStatusCode,
    message: String,
  ) {
    // `resource_metadata` tells an MCP client where discovery starts; without it clients guess with
    // an unprompted registration POST and report a spurious 404.
    call.response.headers.append(
      HttpHeaders.WWWAuthenticate,
      ServeMcpOAuth.challenge(externalOrigin()),
    )
    call.response.headers.append(
      CATALOG_MCP_AGENT_ACCESS_HEADER,
      externalOrigin() + ServeAgentGrants.REQUEST_PATH,
    )
    call.respondText(
      JSON.encodeToString(
        CatalogMcpAuthorizationResponse.serializer(),
        CatalogMcpAuthorizationResponse(
          error =
            if (status == HttpStatusCode.Unauthorized) "authorization_required" else "forbidden",
          message = message,
          agentAccessRequestUrl = externalOrigin() + ServeAgentGrants.REQUEST_PATH,
          requiredScope = AgentGrantScope.PREVIEW.wire,
        ),
      ),
      ContentType.Application.Json,
      status,
    )
  }

  /**
   * `GET /api/uses?q=<token>` (and `/{system}/…`): previews whose declaration calls something
   * matching the token, for the landing's `uses:` filter.
   *
   * Dev mode only ([componentBrowserMode]), and 404 rather than empty in Catalog mode, since an
   * empty list would claim nothing matched. Never resumes: uses [ServeSessionRegistry.peekHost] or
   * the suspended location snapshot, as [sourceLocationFor] does.
   */
  private suspend fun RoutingContext.handleUsesSearch(sessionInPath: Boolean) {
    if (rejectBadToken()) return
    if (componentBrowserMode()) {
      call.respondText("not found", status = HttpStatusCode.NotFound)
      return
    }
    val index = previewUsage
    if (index == null) {
      // No source fetcher on this host: the Source panel is absent for the same reason. Honest
      // "unavailable" so the filter can say so instead of showing an empty grid.
      call.respondText(
        JSON.encodeToString(UsesResponse.serializer(), UsesResponse(available = false)),
        ContentType.Application.Json,
      )
      return
    }
    val system = selectedSessionId(sessionInPath)
    val previewIds =
      sessions.peekHost(system)?.previews?.map { it.id }
        ?: catalogSourceLocationsSeen[system]?.keys?.toList()
        ?: run {
          call.respondText("not found", status = HttpStatusCode.NotFound)
          return
        }
    val token = call.request.queryParameters["q"].orEmpty()
    // Off the request thread: a cold search can be up to `maxFiles` network reads.
    val match = withContext(Dispatchers.IO) { index.match(system, previewIds, token) }
    // Depends on the interface-mode cookie, so it must not be cached across modes.
    call.response.headers.append(HttpHeaders.CacheControl, DYNAMIC_RESOURCE_CACHE_CONTROL)
    call.respondText(
      JSON.encodeToString(
        UsesResponse.serializer(),
        UsesResponse(
          available = match.available,
          truncated = match.truncated,
          ids = match.ids.toList().sorted(),
        ),
      ),
      ContentType.Application.Json,
    )
  }

  /**
   * `GET /api/components`: the listed catalogs' component cards for home-page keyboard search.
   * Reads only resident or remembered metadata, so no daemon is resumed. Fetched lazily and kept
   * for the page's life.
   */
  /**
   * The icon service, present exactly when this host authors designs; its cache lives in
   * `uiBuilderDir`.
   */
  private val materialSymbolsHttpClient: okhttp3.OkHttpClient by lazy {
    okhttp3.OkHttpClient.Builder()
      .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
      // Generous beside the 10 s page-load fetches elsewhere, because this transfers 9-15 MB once
      // per host; still finite, which is the whole point.
      .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
      .build()
  }

  private val materialSymbolsIcons: MaterialSymbolsIcons? by lazy {
    val dir = uiBuilderDir ?: return@lazy null
    MaterialSymbolsIcons(
      MaterialSymbolsSource(
        cacheDirectory = File(dir, "material-symbols"),
        // Bounded in time, since this runs under a lock every icon request shares and a stalled
        // host would block them forever; and in size, since the digest can only reject bytes
        // already allocated. Every file has a known exact size.
        fetch = { url, expectedBytes ->
          materialSymbolsHttpClient
            .newCall(okhttp3.Request.Builder().url(url).build())
            .execute()
            .use { response ->
              check(response.isSuccessful) { "$url answered ${response.code}" }
              val body = checkNotNull(response.body) { "$url answered no body" }
              val declared = body.contentLength()
              check(declared <= expectedBytes) {
                "$url declared $declared bytes, expected $expectedBytes; refusing to read it"
              }
              MaterialSymbolsSource.readAtMost(body.byteStream(), expectedBytes)
            }
        },
      )
    )
  }

  /**
   * The icon names a face carries, filtered locally as the user types. One ~79 KB fetch per face,
   * cached, rather than a request per keystroke; only visible rows' outlines are fetched.
   */
  private suspend fun RoutingContext.handleIconNames() {
    if (rejectBadToken()) return
    val icons = materialSymbolsIcons ?: return respondIconsUnavailable()
    val style = call.parameters["style"].orEmpty()
    when (val result = iconResultOrNull { icons.names(style) } ?: return) {
      is IconResult.Refused -> respondIconRefusal(result.failure)
      is IconResult.Answered ->
        respondIconJson(
          style,
          JSON.encodeToString(IconNamesResponse.serializer(), result.value),
        )
    }
  }

  private suspend fun RoutingContext.handleIconOutlines() {
    if (rejectBadToken()) return
    val icons = materialSymbolsIcons ?: return respondIconsUnavailable()
    val style = call.parameters["style"].orEmpty()
    val names = MaterialSymbolsIcons.parseNames(call.request.queryParameters.getAll("names"))
    val axes =
      when (val parsed = MaterialSymbolsIcons.parseAxes { call.request.queryParameters[it] }) {
        is IconResult.Refused -> return respondIconRefusal(parsed.failure)
        is IconResult.Answered -> parsed.value
      }
    when (val result = iconResultOrNull { icons.outlines(style, names, axes) } ?: return) {
      is IconResult.Refused -> respondIconRefusal(result.failure)
      is IconResult.Answered ->
        respondIconJson(
          style,
          JSON.encodeToString(IconOutlinesResponse.serializer(), result.value),
        )
    }
  }

  /**
   * Turns a font that won't load into a 503 rather than a stack trace. The expected cause is an
   * offline host with a cold cache; the message names the wanted file so it can be warmed by hand.
   */
  private suspend fun <T> RoutingContext.iconResultOrNull(
    block: () -> IconResult<T>
  ): IconResult<T>? =
    try {
      // On [Dispatchers.IO]: a cold call downloads 9-15 MB and parses a font under one monitor.
      withContext(Dispatchers.IO) { block() }
    } catch (failure: IllegalStateException) {
      respondIconsFailed(failure)
    } catch (failure: java.io.IOException) {
      // The documented offline cold-cache case: `openStream` throws `UnknownHostException`; without
      // this arm it would be a 500.
      respondIconsFailed(failure)
    }

  private suspend fun <T> RoutingContext.respondIconsFailed(failure: Throwable): IconResult<T>? {
    call.respondText(
      "material symbols unavailable: ${failure.message ?: failure::class.simpleName}",
      status = HttpStatusCode.ServiceUnavailable,
    )
    return null
  }

  private suspend fun RoutingContext.respondIconRefusal(failure: IconRequestFailure) {
    val status =
      if (failure is IconRequestFailure.UnknownStyle) HttpStatusCode.NotFound
      else HttpStatusCode.BadRequest
    call.respondText(MaterialSymbolsIcons.describe(failure), status = status)
  }

  /**
   * Outlines and names are immutable per pin, so cacheable: the browser transport uses
   * `force-cache`, which `no-store` would defeat. A token-gated host keeps them out of shared
   * caches.
   */
  /**
   * Answers an icon route with the pin as a strong `ETag`, since the names route can't carry `v` on
   * a fresh page load and would otherwise refetch ~79 KB every visit.
   */
  private suspend fun RoutingContext.respondIconJson(style: String, body: String) {
    val pin = materialSymbolsIcons?.pin(style)
    call.response.headers.append(HttpHeaders.CacheControl, iconCacheControl(style))
    if (pin.isNullOrEmpty()) {
      call.respondText(body, ContentType.Application.Json)
      return
    }
    val etag = "\"$pin\""
    call.response.headers.append(HttpHeaders.ETag, etag)
    if (ifNoneMatchHits(call.request.headers[HttpHeaders.IfNoneMatch], etag)) {
      call.respond(HttpStatusCode.NotModified)
      return
    }
    call.respondText(body, ContentType.Application.Json)
  }

  private fun RoutingContext.iconCacheControl(style: String): String {
    // Only a caller naming the current pin gets an immutable answer; others are told to revalidate.
    val presented = call.request.queryParameters["v"]
    val current = materialSymbolsIcons?.pin(style)
    if (presented.isNullOrEmpty() || current.isNullOrEmpty() || presented != current)
      return "no-cache"
    return if (isPublic) UI_BUILDER_IMMUTABLE_CACHE_CONTROL
    else "private, max-age=31536000, immutable"
  }

  private suspend fun RoutingContext.respondIconsUnavailable() {
    call.respondText("not found", status = HttpStatusCode.NotFound)
  }

  private suspend fun RoutingContext.handleGlobalComponents() {
    if (rejectBadToken()) return
    // Front-door-only: on a top-level site this would reveal neighbours and return links the site's
    // isolation rejects.
    if (siteSystem() != null) {
      call.respondText("not found", status = HttpStatusCode.NotFound)
      return
    }
    val suffix =
      if (!linksCarryToken()) "" else "?token=" + WebEscaping.urlEncodeSegment(linkToken())
    val components =
      listedCatalogs().flatMap { system ->
        sessions.peekHost(system)?.let { rememberCatalogMeta(system, it, progress = false) }
        val meta = catalogMetaSeen[system] ?: return@flatMap emptyList()
        val systemSegment = WebEscaping.urlEncodeSegment(system)
        meta.components.map { component ->
          GlobalComponentDto(
            label = component.label,
            catalog = system,
            catalogTitle = meta.title ?: system,
            href = "/$systemSegment/p/${WebEscaping.urlEncodeSegment(component.previewId)}$suffix",
            keywords = component.keywords,
          )
        }
      }
    call.response.headers.append(HttpHeaders.CacheControl, DYNAMIC_RESOURCE_CACHE_CONTROL)
    call.respondText(
      JSON.encodeToString(
        GlobalComponentsResponse.serializer(),
        GlobalComponentsResponse(components = components),
      ),
      ContentType.Application.Json,
    )
  }

  /**
   * `GET /index.json` and `GET /{system}/index.json`: the session's previews as a Storybook stories
   * index ([StorybookCompat.Index]) for visual tools to enumerate stories.
   */
  private suspend fun RoutingContext.handleStorybookIndex(sessionInPath: Boolean) {
    if (rejectBadToken()) return
    withLeasedSession(selectedSessionId(sessionInPath)) { renderHost ->
      call.respondText(
        JSON.encodeToString(
          StorybookCompat.Index.serializer(),
          StorybookCompat.index(renderHost.previews),
        ),
        ContentType.Application.Json,
      )
    }
  }

  /**
   * `GET /iframe.html?id=<storyId>` (and `/{system}/…`): render one story in isolation as a
   * chrome-free HTML page — a PNG `data:` URI by default ([StorybookCompat.iframePage]), or with
   * `&format=svg` the figma-svg export as an inert `<img src="data:image/svg+xml">`
   * ([StorybookCompat.iframeSvgPage]) so untrusted SVG can't script. SVG is daemon-only (404 for
   * static bundles). Honours `/render`'s override params and the shared render semaphore.
   */
  private suspend fun RoutingContext.handleStorybookIframe(sessionInPath: Boolean) {
    if (rejectBadToken()) return
    // Renders unconditionally — there is no baked lane here at all, so every request is live work.
    if (rejectGrantBelowScope(AgentGrantScope.LIVE, api = true)) return
    // Always renders (no baked lane), so a bodyless HEAD probe is not worth answering.
    if (rejectHeadProbe()) return
    val sessionId = selectedSessionId(sessionInPath)
    withLeasedSession(sessionId) { renderHost ->
      val storyId = call.request.queryParameters["id"]
      if (storyId.isNullOrBlank()) {
        call.respondText("missing story id", status = HttpStatusCode.BadRequest)
        return@withLeasedSession
      }
      val previewId = StorybookCompat.resolvePreviewId(storyId, renderHost.previews)
      if (previewId == null) {
        call.respondText("no such story", status = HttpStatusCode.NotFound)
        return@withLeasedSession
      }
      val overrideParams =
        call.request.queryParameters
          .entries()
          .mapNotNull { (key, values) ->
            val value = values.firstOrNull() ?: return@mapNotNull null
            if (ServeOverrides.isOverrideParam(key)) key to value else null
          }
          .toMap()
      val themeSeeding = expandThemeProvider(renderHost, previewId, overrideParams)
      val normalizedOverrideParams =
        ServeWeb.SystemDisplay.normalizeOverrideParams(sessionId, themeSeeding.params)
      val knobKinds =
        ServeOverrides.declaredKnobKinds(renderHost.previews.firstOrNull { it.id == previewId })
      // Reject a themeProvider this catalog never declared instead of quietly rendering the
      // default theme under its name (see ServeOverrides.parse).
      val declaredThemeFqns = renderHost.declaredThemes.map { it.providerFqn }.toSet()
      // `?format=svg` serves the figma-svg export as an inert `<img>`; default inlines the PNG. SVG
      // is daemon-only.
      val wantSvg = call.request.queryParameters["format"]?.lowercase() == "svg"
      when (
        val parsed =
          ServeRcPlayerIds.parseOverrides(normalizedOverrideParams, knobKinds, declaredThemeFqns)
      ) {
        is OverrideParse.Invalid ->
          call.respondText(parsed.message, status = HttpStatusCode.BadRequest)
        is OverrideParse.Ok ->
          if (wantSvg) {
            storybookIframeSvg(renderHost, storyId, previewId, parsed.overrides)
          } else {
            storybookIframePng(renderHost, storyId, previewId, parsed.overrides)
          }
      }
    }
  }

  /** PNG lane of [handleStorybookIframe]: render, then inline the raster in the isolation page. */
  private suspend fun RoutingContext.storybookIframePng(
    renderHost: ServeHost,
    storyId: String,
    previewId: String,
    overrides: PreviewOverrides,
  ) {
    val outcome =
      withContext(Dispatchers.IO) {
        if (!renderSemaphore.tryAcquire(RENDER_QUEUE_WAIT_SECONDS, TimeUnit.SECONDS)) {
          null
        } else {
          try {
            renderHost.render(previewId, overrides)
          } finally {
            renderSemaphore.release()
          }
        }
      }
    when (outcome) {
      null -> {
        call.response.headers.append(HttpHeaders.RetryAfter, "2")
        call.respondText(
          "render queue saturated; retry shortly",
          status = HttpStatusCode.ServiceUnavailable,
        )
      }
      RenderOutcome.Busy -> {
        // The daemon was busy and the request backed off (~DAEMON_BUSY_WAIT): fast 503 +
        // Retry-After (a bare bundle host has no baked fallback).
        call.response.headers.append(HttpHeaders.RetryAfter, "2")
        call.respondText("render busy; retry shortly", status = HttpStatusCode.ServiceUnavailable)
      }
      is RenderOutcome.Ok -> {
        // Story args ride the override params, and this lane serves arg-diffing tools (BackstopJS /
        // reg-suit), so baked pixels would wrongly show args as no-ops.
        val dropped = droppedOverridesFor(renderHost, outcome.generation, previewId, overrides)
        if (dropped.isNotEmpty() && !acceptsBakedFallback()) {
          refuseDroppedOverrides(renderHost, previewId, dropped, overrides)
        } else {
          markDroppedOverrides(dropped)
          call.respondText(StorybookCompat.iframePage(storyId, outcome.png), ContentType.Text.Html)
        }
      }
      RenderOutcome.NotFound -> call.respondText("no such story", status = HttpStatusCode.NotFound)
      is RenderOutcome.Failed ->
        call.respondText(outcome.reason, status = HttpStatusCode.InternalServerError)
    }
  }

  /**
   * SVG lane of [handleStorybookIframe]: render the figma-svg export as an inert `<img>`
   * ([StorybookCompat.iframeSvgPage]). Daemon-only, like `/render.svg`.
   */
  private suspend fun RoutingContext.storybookIframeSvg(
    renderHost: ServeHost,
    storyId: String,
    previewId: String,
    overrides: PreviewOverrides,
  ) {
    val outcome =
      withContext(Dispatchers.IO) {
        if (!renderSemaphore.tryAcquire(RENDER_QUEUE_WAIT_SECONDS, TimeUnit.SECONDS)) {
          null
        } else {
          try {
            renderHost.renderSvg(previewId, overrides)
          } finally {
            renderSemaphore.release()
          }
        }
      }
    when (outcome) {
      null -> {
        call.response.headers.append(HttpHeaders.RetryAfter, "2")
        call.respondText(
          "render queue saturated; retry shortly",
          status = HttpStatusCode.ServiceUnavailable,
        )
      }
      is SvgOutcome.Ok -> {
        val dropped = droppedOverridesFor(renderHost, outcome.generation, previewId, overrides)
        if (dropped.isNotEmpty() && !acceptsBakedFallback()) {
          refuseDroppedOverrides(renderHost, previewId, dropped, overrides)
        } else {
          markDroppedOverrides(dropped)
          call.respondText(
            StorybookCompat.iframeSvgPage(storyId, outcome.svg),
            ContentType.Text.Html,
          )
        }
      }
      SvgOutcome.NotFound ->
        call.respondText(
          "svg unavailable for this story (no daemon-backed SVG export)",
          status = HttpStatusCode.NotFound,
        )
      is SvgOutcome.Failed ->
        call.respondText(outcome.reason, status = HttpStatusCode.InternalServerError)
    }
  }

  /** `GET /bundle.zip` and `GET /{system}/bundle.zip`: the session as a portable zip. */
  private suspend fun RoutingContext.handleBundleZip(sessionInPath: Boolean) {
    if (rejectBadToken()) return
    // Renders every preview in the catalog and packs a zip. Never probed for an unfurl, and the
    // most expensive thing a HEAD could otherwise trigger anonymously.
    if (rejectHeadProbe()) return
    // The most expensive thing a grant can trigger, so it requires `live`, like override renders.
    if (rejectGrantBelowScope(AgentGrantScope.LIVE, api = true)) return
    withLeasedSession(selectedSessionId(sessionInPath)) { renderHost ->
      // Render the whole module once (cache-backed) into the portable WebEmbed gallery and stream
      // it as a zip.
      val zip =
        withContext(Dispatchers.IO) {
          val built =
            ServeBundle.build(
              previews = renderHost.previews,
              title = renderHost.label,
              modulePath = renderHost.label,
            ) { preview ->
              (renderHost.render(preview.id, PreviewOverrides()) as? RenderOutcome.Ok)?.png
            }
          ServeBundle.zip(built.files)
        }
      call.respondBytes(zip, ContentType.Application.Zip)
    }
  }

  /** Return one server-hydrated, self-contained executable PNG+ZIP preview bundle. */
  private suspend fun RoutingContext.handleExecutableBundle(sessionInPath: Boolean) {
    if (rejectBadToken()) return
    // Materialises a per-preview bundle, which can reach the network.
    if (rejectHeadProbe()) return
    withLeasedSession(selectedSessionId(sessionInPath)) { renderHost ->
      val previewId = call.parameters["name"]
      val available =
        previewId?.let {
          withContext(Dispatchers.IO) { renderHost.canDownloadExecutableBundle(it) }
        } == true
      if (!available) {
        call.respondText("executable bundle unavailable", status = HttpStatusCode.NotFound)
        return@withLeasedSession
      }
      val bytes = withContext(Dispatchers.IO) { renderHost.executableBundle(previewId) }
      if (bytes == null) {
        call.respondText("executable bundle unavailable", status = HttpStatusCode.NotFound)
        return@withLeasedSession
      }
      val filename =
        previewId.map { if (it.isLetterOrDigit() || it in "._-") it else '_' }.joinToString("") +
          ".png"
      call.response.headers.append(
        HttpHeaders.ContentDisposition,
        "attachment; filename=\"$filename\"",
      )
      call.respondBytes(bytes, ContentType.Image.PNG)
    }
  }

  /**
   * `GET /usage/{name}` and `GET /{system}/usage/{name}`: the plain-Compose usage code behind one
   * preview, as JSON, for the viewer's Source panel. Fetched on first open, since it may cost a
   * GitHub read.
   *
   * No session lease: it's a source read from the registry/cache or a trusted local root (like the
   * resolver's `locate`). 404 for every "nothing to show" case, so the panel has one branch.
   */
  /**
   * `/usage/<previewId>` for a preview, or null when this host has nothing to serve (no fetcher, no
   * recorded source path, no catalog source). Unlike [playgroundLinkFor], needs no playground.
   */
  private fun RoutingContext.usageLinkFor(
    system: String,
    previewId: String,
    sourceFile: String?,
    basePath: String,
  ): String? {
    if (sourceFile.isNullOrBlank()) return null
    if (localSourceFile(system, previewId) != null) {
      return "$basePath/usage/${WebEscaping.urlEncodeSegment(previewId)}${requestQuerySuffix()}"
    }
    if (playgroundSeeds == null) return null
    // The resolver's own condition: plain sessions or uploads may carry a `sourceFile` with no
    // catalog source.
    if (sessions.peekHost(system)?.let { catalogBundleHost(it) }?.catalogSource == null) return null
    return "$basePath/usage/${WebEscaping.urlEncodeSegment(previewId)}${requestQuerySuffix()}"
  }

  private suspend fun RoutingContext.respondNoUsage() {
    markGeneration("usage-snippet", DYNAMIC_RESOURCE_CACHE_CONTROL)
    call.respondText(
      "{\"status\":\"no-usage\"}",
      ContentType.Application.Json,
      HttpStatusCode.NotFound,
    )
  }

  private suspend fun RoutingContext.handleUsage(sessionInPath: Boolean) {
    if (rejectBadToken()) return
    val sessionId = selectedSessionId(sessionInPath)
    // Ktor already decodes route parameters; decoding again would mangle `%2B` and `%2F` in escaped
    // ids.
    val previewId = call.parameters["name"]
    if (previewId.isNullOrBlank()) {
      respondNoUsage()
      return
    }
    // Off the request dispatcher: this is either a local file read or an uncached synchronous
    // GitHub GET with 10 s connect + 10 s read. Neither should hold Ktor's request threads.
    val localSeed = withContext(Dispatchers.IO) { localUsageSeed(sessionId, previewId) }
    val seed =
      localSeed ?: withContext(Dispatchers.IO) { playgroundSeeds?.seed(sessionId, previewId) }
    // Hosted catalogs fall back to their GitHub source link when cleaning declines; a local session
    // has no blob URL, so its authored file is the fallback.
    if (seed == null || (!seed.cleaned && localSeed == null)) {
      respondNoUsage()
      return
    }
    val host = sessions.peekHost(sessionId)
    val sourceFile = host?.previews?.firstOrNull { it.id == previewId }?.sourceFile
    markGeneration("usage-snippet", DYNAMIC_RESOURCE_CACHE_CONTROL)
    call.respondText(
      JSON.encodeToString(
        UsageSnippetResponse.serializer(),
        UsageSnippetResponse(
          text = seed.text,
          entryFunction = seed.previewId.substringAfterLast('.').takeIf { it.isNotBlank() },
          scaffoldsDeclared = seed.scaffoldsDeclared,
          residue = seed.residue,
          blobUrl = seed.blobUrl,
          playgroundHref = host?.let { playgroundLinkFor(it, sessionId, previewId, sourceFile) },
          // Links are derived here as a projection of the cleaned snippet; other seed consumers
          // want the code without them.
          apiDocs =
            ApiDocLinks.of(seed.text).map {
              ApiDocLink(
                name = it.name,
                fqn = it.fqn,
                composable = it.composable,
                url = it.url,
              )
            },
        ),
      ),
      ContentType.Application.Json,
    )
  }

  /**
   * Resolve a manifest source path inside its trusted local module root; never follow it outside.
   */
  private fun localSourceFile(system: String, previewId: String): Pair<ServePreview, File>? {
    val root = localSourceRoots[system] ?: return null
    val preview =
      sessions.peekHost(system)?.previews?.firstOrNull { it.id == previewId } ?: return null
    val relative = preview.sourceFile?.takeIf { it.isNotBlank() } ?: return null
    if (File(relative).isAbsolute) return null
    return try {
      val canonicalRoot = root.canonicalFile
      val source = File(canonicalRoot, relative).canonicalFile
      if (!source.toPath().startsWith(canonicalRoot.toPath()) || !source.isFile) null
      else preview to source
    } catch (_: Exception) {
      null
    }
  }

  /** Read and clean a local browse preview, falling back to its authored source when needed. */
  private fun localUsageSeed(system: String, previewId: String): PlaygroundSeed? {
    val (preview, source) = localSourceFile(system, previewId) ?: return null
    if (source.length() > LOCAL_SOURCE_MAX_BYTES) return null
    val text = source.readBytes().decodeToString()
    if (text.contains('�')) return null
    val cleaned =
      try {
        PlaygroundSourceCleaner.clean(text, preview.bodyLine, UsageRules.GENERIC)
      } catch (_: Exception) {
        null
      }
    return PlaygroundSeed(
      catalog = system,
      previewId = previewId,
      fileName = source.name,
      text = cleaned?.text ?: text,
      blobUrl = null,
      sliced = cleaned != null,
      cleaned = cleaned != null,
      residue = cleaned?.residue.orEmpty(),
    )
  }

  /** `GET /p/{name}` (query) and `GET /{system}/p/{name}` (path): one preview's viewer page. */
  private suspend fun RoutingContext.handleViewer(sessionInPath: Boolean) {
    if (rejectBadToken() || rejectMalformedPin()) return
    val sessionId = selectedSessionId(sessionInPath)
    val (webSessionId, basePath) = webSessionAndBase(sessionInPath)
    withLeasedSession(
      sessionId,
      onMissing = { respondNotFoundHtml("That design system was not found on this server.") },
    ) { renderHost ->
      val previewId = call.parameters["name"]
      val revisions = catalogRevisions(renderHost, previewId)
      // Which catalog decides what this page is about: the session's list unpinned, or under a pin
      // the revision's own catalog first (as the asset lanes do), so a historical page never
      // borrows today's metadata or component name.
      //
      // Off the request dispatcher, since a cold lookup fetches that revision's manifests and any
      // valid sha is accepted.
      val preview = previewId?.let { id ->
        val host = catalogBundleHost(renderHost)
        val currentPreview = renderHost.previews.firstOrNull { it.id == id }
        val pinnedPreview =
          revisions.pinned?.let { pin ->
            withContext(Dispatchers.IO) { host?.pinnedPreview(pin, id) }
          }
        // Fall back only when the revision's catalog couldn't be read, never when it was read and
        // lacks this id ([ServeBundleHost.pinnedCatalogIsAuthoritative]).
        val revisionAnswers =
          revisions.pinned?.let { pin ->
            withContext(Dispatchers.IO) { host?.pinnedCatalogIsAuthoritative(pin) }
          } == true
        when {
          // The revision owns the route. While the id survives, enrich it with the tip's
          // state/props/source metadata (absent from catalog.json), keeping the revision's
          // componentId when it had one. A retired id uses the historical placeholder alone.
          pinnedPreview != null ->
            currentPreview?.copy(
              componentId = pinnedPreview.componentId ?: currentPreview.componentId,
              // The caption takes no tip fallback: `catalog.json` carries it, so the revision's
              // answer (including absence) is authoritative.
              caption = pinnedPreview.caption,
              theme = pinnedPreview.theme ?: currentPreview.theme,
            ) ?: pinnedPreview
          revisionAnswers -> null
          else -> currentPreview
        }
      }
      if (preview == null) {
        if (revisions.pinned != null && previewId != null) {
          // The pinned publish authoritatively lacks this id: keep the 404 but retain the revision
          // menu.
          val skin = siteSkin()
          call.respondText(
            ServeWeb.unavailablePreviewRevisionPage(
              previewId = previewId,
              token = linkToken(),
              sessionId = webSessionId,
              basePath = basePath,
              isPublic = isPublic,
              revisions = revisions,
              unfurl = ServeWeb.UnfurlMetadata(pageUrl = externalPageUrl()),
              version = SERVE_VERSION,
              siteName = skin.first,
              themeCss = skin.second,
              themeStorageKey = skin.third,
              sessionInOrigin = siteSystem() != null,
              changelogHref = changelogHref(sessionId, basePath, webSessionId),
            ),
            ContentType.Text.Html,
            HttpStatusCode.NotFound,
          )
        } else {
          respondNotFoundHtml("That preview does not exist in this catalog.")
        }
        return@withLeasedSession
      }
      // Offer the Wasm tier when this catalog has a Wasm app. ServeUrls.wasmAppSrc strips the
      // variant to the registry's component slug and bakes its theme into `uiMode` so the live
      // render matches the snapshot.
      val wasmSrc =
        if (!wasmCatalogs.containsKey(sessionId)) null
        else if (!isPublic && sessionId in privateWasmCatalogs)
          ServeUrls.privateWasmAppSrc(sessionId, preview.id, wasmPrivateAccess())
        else ServeUrls.wasmAppSrc(sessionId, preview.id)
      // Real origin only for a trusted catalog's Wasm app; unverified ones stay opaque-origin.
      // Fail-closed.
      val wasmSameOrigin =
        catalogBundleHost(renderHost)?.let { it.trust is BundleVerifier.Verdict.Trusted } ?: false
      val origin = externalOrigin()
      // A pinned page's render URL keeps only the pin; an unpinned one carries the page's query
      // plus its generation, so unfurl and report point at the frame this page drew
      // ([ServeCacheGeneration]). Not scoped under an override (`no-store`, belongs to no publish).
      //
      // `requestQuerySuffix()` is the raw query; that's intended for overrides and harmless
      // otherwise, since the raster lane ignores other params.
      val imageQuerySuffix =
        if (revisions.pinned != null) pinnedRenderQuerySuffix()
        else if (requestCarriesOverrides()) requestQuerySuffix()
        else ServeCacheGeneration.scope(requestQuerySuffix(), catalogGeneration(renderHost))
      val imageUrl =
        "$origin$basePath/render/${WebEscaping.urlEncodeSegment(preview.id)}.png$imageQuerySuffix"
      // PNG-header read so the unfurl carries real dimensions (and small components don't claim a
      // large card; see [ServeWeb.twitterCard]). Only without overrides, since `imageUrl` then
      // names a re-render of unknown size.
      val imageSize =
        if (requestCarriesOverrides()) null else renderHost.bakedRenderSize(preview.id)
      val engagement =
        if (isViewRequest()) incrementPreviewViews(sessionId, preview.id)
        else previewEngagement(sessionId, listOf(preview)).getValue(preview.id)
      val bundleHost = catalogBundleHost(renderHost)
      // Link the preview to its source file via the catalog's source (Kotlin repo/ref/module, not
      // the delivery branch) plus `sourceFile`. Null without either.
      val sourceHref =
        bundleHost
          ?.catalogSource
          ?.takeIf { revisions.pinned == null }
          ?.let { src ->
            ServeUrls.githubBlobUrl(
              src.repo,
              src.ref,
              preview.sourceModule ?: src.module,
              preview.sourceFile,
            )
          }
      // The request's overrides split into seeds and withheld ([seedableOverrideParams]); both
      // reach the page. Computed here because the report below needs `seeded` as "what this picture
      // can be showing".
      val overrideSeeds =
        seedableOverrideParams(renderHost, preview, sessionId, revisions.pinned, wasmSrc)
      // The prefilled "report an issue" for the preview on screen, filed against the repo owning
      // its Kotlin. Built here because the viewer is where problems are noticed and has every fact
      // a preview bug needs.
      //
      // Includes the preview's first design reference: `ServeIssueReport.locator` returns null
      // without a `referenceId`, which would leave viewer-filed issues out of `parity/issues.json`.
      // It identifies the comparison without asserting pixels; the score stays exclusive to the
      // comparison page (`rawScoresPlaceholder`), and `referenceUrl` stays null with no reference
      // on stage.
      val reportContext =
        ServeIssueReport.Context(
          repo = ServeIssueReport.repoFor(bundleHost?.catalogSource, bundleHost?.provenance),
          previewId = preview.id,
          previewLabel = preview.label,
          system = sessionId,
          componentId = ServeIssueReport.componentIdFor(preview),
          // …but not on a pinned viewer: the stage is historical while the reference mapping and
          // `revision:` describe today, so the locator would name a different comparison (as with
          // `sourceHref`, `referenceAnnotations`, seeds and the playground link).
          referenceId =
            renderHost.designReferencesFor(preview.id).firstOrNull()?.id.takeIf {
              revisions.pinned == null
            },
          variant = ServeIssueReport.variantFor(preview),
          // The seeded map, not the raw query: under `?fallback=baked` the pixels may ignore an
          // axis (`respondDroppedOverrides`), and a locator must not claim it. The client-side
          // `{{overrides}}` pass collects the same set.
          overrides = overrideSeeds.seeded,
          sourceUrl = sourceHref,
          catalog = bundleHost?.provenance?.let { "${it.repo}@${it.branch}" },
          toolVersion = bundleHost?.provenance?.toolVersion,
          viewerUrl = ServeIssueReport.withoutToken(externalPageUrl()),
          // `imageUrl` carries this page's override suffix; token-stripped like every URL in an
          // issue body.
          renderUrl = ServeIssueReport.withoutToken(imageUrl),
          publicRender = isPublic,
        )
      val reportIssue =
        ServeWeb.ReportIssue(
          action = ServeIssueReport.action(reportContext.repo),
          body = ServeIssueReport.body(reportContext),
          // The template the page's script fills, with both overrides and render placeholders
          // substituted in one pass so they agree. No `{{rawScores}}` (nothing measured here) and
          // no `{{selection}}` (no element selector).
          bodyTemplate =
            ServeIssueReport.body(
              reportContext,
              renderPlaceholder = true,
              overridesPlaceholder = true,
            ),
          repo = reportContext.repo,
          login = githubAuth?.currentLogin(call),
        )
      val liveAuthPrompt =
        githubAuth
          ?.takeIf { renderHost.hasLiveStream }
          ?.takeUnless { it.isAuthenticated(call) }
          ?.takeIf { oauthCanRoundTrip() }
          ?.let {
            ServeWeb.LiveAuthPrompt(
              loginHref = it.loginPath(call),
              restrictedToAllowedUsers = it.isRestrictedToAllowedUsers(),
            )
          }
      // Project mode's local timeline, only for sessions without delivery provenance (which ship
      // the published manifest instead). Off the event loop: the first call per refresh window
      // shells out to git.
      val localHistoryJson =
        projectHistory
          ?.takeIf { catalogBundleHost(renderHost)?.provenance == null }
          ?.let { history -> withContext(Dispatchers.IO) { history.timelineJsonFor(preview.id) } }
      // The publication-aware HEAD probe is network I/O; keep it off Ktor's request dispatcher.
      val executableBundleAvailable =
        withContext(Dispatchers.IO) { renderHost.canDownloadExecutableBundle(preview.id) }
      markGeneration(
        "static-page",
        viewerCacheControl(
          githubAuthConfigured = githubAuth != null,
          isPublic = isPublic,
          signedIn = requestIsSignedIn(),
          stagedCapabilitiesPending = renderHost.rcComparePending(),
        ),
      )
      call.respondText(
        ServeWeb.viewerPage(
          preview,
          linkToken(),
          webSessionId,
          canApplyOverrides = renderHost.canApplyOverrides,
          // Per-preview: a catalog-live host can only re-render overrides on daemon-twinned
          // previews, so others get disabled controls.
          canRenderOverrides = renderHost.canRenderOverridesFor(preview.id),
          // Per-preview: dp size overrides are converted against this density.
          renderDensity = catalogBundleHost(renderHost)?.renderDensityFor(preview.id),
          // The knob values this request asked for, unless the picture can't be showing them
          // ([seedableOverrideParams]).
          requestOverrides = overrideSeeds.seeded,
          // …and the axes it declined, so `hydrateFromUrl` defers on them instead of putting them
          // straight back a frame after load.
          unseededOverrides = overrideSeeds.withheld,
          // Per-preview: a catalog advertises SVG once it has `figma/`, but a preview without
          // `figma/<slug>.svg` still 404s.
          hasSvgExport = renderHost.hasSvgExportFor(preview.id),
          hasScrollExport = renderHost.hasScrollExportFor(preview.id),
          executableBundleHref =
            if (executableBundleAvailable)
              "$basePath/bundle/${WebEscaping.urlEncodeSegment(preview.id)}${requestQuerySuffix()}"
            else null,
          // Inspection layers per preview: a11y needs an a11y-capable daemon, typography/theme a
          // semantics-capturing one, and only daemon-twinned ids qualify.
          hasA11yOverlay = renderHost.hasA11yOverlayFor(preview.id),
          hasDesignAnnotations = renderHost.hasDesignAnnotationsFor(preview.id),
          // The baked half of the same Typography layer: a published catalog measured it off the
          // frame it also published, so the overlay works on a host with no daemon at all.
          hasPublishedTypography = renderHost.hasPublishedTypographyFor(preview.id),
          hasLiveStream = renderHost.hasLiveStream,
          trust = catalogBundleHost(renderHost)?.let { BundleVerifier.summary(it.trust) },
          // Per-preview: the Remote Compose canvas lane needs a captured `.rc` (fetched from
          // `/render/<id>.rc`).
          hasRemoteComposeDoc = renderHost.hasRemoteComposeDoc(preview.id),
          // Per-preview: a replayed document can't recompose, so the viewer greys controls
          // [droppedOverridesFor] would 409. Read from the same host question to avoid drift.
          irReplay = isReplayedPreview(renderHost, preview.id),
          // …but a replayed preview can still take a declared theme when the session publishes its
          // colours, so the viewer greys the recomposition-only controls without greying this one.
          replayThemes = applicableThemes(renderHost, preview.id).isNotEmpty(),
          // Per-preview Remote Compose backend lanes: the host's server/client lanes plus the
          // opt-in CMP/Wasm browser lane when the preview has an RC document. Empty ⇒ no selector.
          enabledRcPlayers =
            buildList {
              // In this server's player vocabulary ([ServeRcPlayerIds]), not compose-ai-tools'
              // wire spelling: the pinned release still spells the embedded player `cmp-android`.
              addAll(renderHost.enabledRcPlayersFor(preview.id).map(ServeRcPlayerIds::of))
              if (rcPlayerWasmDir != null && renderHost.hasRemoteComposeDoc(preview.id)) {
                add(ServeRcPlayerIds.CMP_WASM)
              }
            },
          // Which lane a bare `/render` already is, so the viewer can stop naming it; empty when
          // unknown.
          bakedRcPlayer =
            renderHost.bakedRcPlayer(preview.id)?.let(ServeRcPlayerIds::ofKind).orEmpty(),
          preferredRcPlayer = preferredRcPlayer,
          wasmSrc = wasmSrc,
          wasmSameOrigin = wasmSameOrigin,
          basePath = basePath,
          spatialSceneUrl =
            if (preview.spatial)
              "$basePath/spatial/${WebEscaping.urlEncodeSegment(preview.id)}/scene.json${requestQuerySuffix()}"
            else null,
          changelogHref = changelogHref(sessionId, basePath, webSessionId),
          isPublic = isPublic,
          componentBrowser = componentBrowserMode(),
          declaredThemes = applicableThemes(renderHost, preview.id),
          // Android-daemon-only: gates the "Show gesture hints" row so a `@GestureHintPreview`
          // doesn't show a toggle that would do nothing on a desktop-backed session.
          gesturesRenderable = renderHost.gesturesRenderable,
          // The session's full preview list feeds the left-hand component nav drawer.
          siblings = renderHost.previews,
          // …and one component's worth feeds the compare strip: every variant in catalog order,
          // with its reference and published score. Resolved here because `componentIdFor` and
          // reference lookups are the host's, and the strip's `?component=` must match the wall's
          // ids. See `docs/design/COMPARE_NAVIGATION.md`, §3.1.
          componentVariants =
            renderHost.previews
              .filter {
                ServeIssueReport.componentIdFor(it) == ServeIssueReport.componentIdFor(preview)
              }
              .map { variant ->
                val reference = renderHost.designReferencesFor(variant.id).firstOrNull()
                ServeWeb.ComponentVariant(
                  previewId = variant.id,
                  variant = ServeIssueReport.variantFor(variant),
                  referenceId = reference?.id,
                  matchPercent = reference?.match?.percent,
                  // The sibling's render of this variant through the same resolver and per-request
                  // memo as the lane's `parallel` source.
                  parallelRenderUrl = parallelSpecSource(renderHost, variant)?.rasterUrl,
                )
              },
          // …and the catalogs that are ABOUT this component — its samples, a rendition of it
          // elsewhere — as named directories in the same drawer subtree the variants live in.
          componentDirectories =
            componentRelatedDirectories(renderHost, preview, sessionId) +
              // …and the components pointing at this one, derived so an imported samples catalog
              // needs no mapping.
              componentBackLinkDirectories(preview, sessionId),
          // What KIND of catalog this is, as the catalog itself declared it — which decides the
          // shape of the page rather than any detail of it. See [ServeWeb.PageRole].
          pageRole = ServeWeb.PageRole.of(catalogBundleHost(renderHost)?.catalogRole),
          // The catalog's declared stage surface (`display.surface`), so an unthemed preview backs
          // on the dark stage for a dark-first system instead of the default white.
          declaredSurface = catalogBundleHost(renderHost)?.stageSurface,
          // …and its own colour palette, so this system's pages are framed in its colours.
          themeCss = catalogBundleHost(renderHost)?.webThemeCss.orEmpty(),
          // The header bar names the catalog this preview belongs to — the viewer's own <h1>
          // is the preview, so without this the page never says which system it is from.
          catalogName =
            ServeWeb.catalogHeading(catalogBundleHost(renderHost)?.title, renderHost.label),
          // Why this session is snapshot-only, if it is, for the header banner.
          degradations = renderHost.degradations,
          engagement = engagement,
          unfurl =
            ServeWeb.UnfurlMetadata(
              pageUrl = externalPageUrl(),
              imageUrl = imageUrl,
              imageWidth = imageSize?.first,
              imageHeight = imageSize?.second,
            ),
          version = SERVE_VERSION,
          sourceHref = sourceHref,
          reportIssue = reportIssue,
          // The Figma node this preview is specified by, from catalog data; nothing is fetched from
          // Figma.
          figmaSpec =
            ServeUidReference.spec(renderHost.designReferencesFor(preview.id), basePath).takeIf {
              revisions.pinned == null
            } ?: ServeFigmaSpec.of(renderHost.designReferencesFor(preview.id)),
          // …and the spec as a lane: the first reference, as in [ServeFigmaSpec]. Absent without
          // references.
          designReference = renderHost.designReferencesFor(preview.id).firstOrNull(),
          // Without a local reference, reuse the paired sibling's imported spec so Remote Compose
          // still compares against Figma.
          pairedDesignSource = pairedDesignSpecSource(renderHost, preview),
          // …and the `compareWith` counterpart, as a second source for the same lane.
          parallelSource = parallelSpecSource(renderHost, preview),
          // Whether this render has a counterpart, weaker than having its raster: the layer diff
          // works on a top-level site too.
          // A pairing isn't yet a layer diff: `handleParallelLayers` 404s when
          // `ServeParallelLayers.diff` is empty, so only link when there are layers.
          parallelLayers =
            resolveParallel(renderHost, preview)?.let { parallel ->
              renderHost.annotationsForPreview(preview.id).isNotEmpty() ||
                parallel.host.annotationsForPreview(parallel.preview.id).isNotEmpty()
            } == true,
          referenceAnnotations =
            if (revisions.pinned != null) emptyList()
            else
              renderHost
                .designReferencesFor(preview.id)
                .firstOrNull()
                ?.let { renderHost.annotationsForReference(it.id) }
                .orEmpty(),
          // "open in playground", only with the lane and a recorded source path.
          playgroundHref =
            if (revisions.pinned == null)
              playgroundLinkFor(renderHost, sessionId, preview.id, preview.sourceFile)
            else null,
          usageHref = usageLinkFor(sessionId, preview.id, preview.sourceFile, basePath),
          liveAuthPrompt = liveAuthPrompt,
          catalogTitle = catalogBundleHost(renderHost)?.title,
          // The same heartbeat the grid sends; the viewer is where visitors settle and use
          // warm-daemon actions.
          presenceUrl = "$basePath/api/presence${requestQuerySuffix()}",
          // Delivery-branch provenance names the repo/branch carrying history.json; without it
          // (uploaded bundle) the strip is omitted.
          historyManifestUrl =
            catalogBundleHost(renderHost)?.provenance?.let {
              ServeUrls.historyManifestUrl(it.repo, it.branch)
            },
          historyRepo = catalogBundleHost(renderHost)?.provenance?.repo,
          // …and its project-mode twin, inlined and linking to `/history/render/`; mutually
          // exclusive with the pair above.
          historyInlineJson = localHistoryJson,
          historyLocalRenders = localHistoryJson != null,
          revisions = revisions,
          revisionQuery =
            requestOverrideParams(sessionId)
              .filterKeys { it == "themeProvider" || it == "uiMode" }
              .entries
              .joinToString("&") { (key, value) ->
                "${WebEscaping.urlEncodeSegment(key)}=${WebEscaping.urlEncodeSegment(value)}"
              },
          parityIssues =
            ServeWeb.issuesForSystem(renderHost.parityIssues()?.issues.orEmpty(), sessionId)
              .filter { issue ->
                preview.id in issue.previewIds ||
                  (issue.scope == "component" &&
                    preview.componentId != null &&
                    issue.component == preview.componentId)
              },
          parityIssuesGeneratedAt = renderHost.parityIssues()?.generatedAt,
          // A top-level site's pages carry their session in the ORIGIN, so same-session links
          // drop the `?session=` the rooted legacy form would add. See [ServeSites].
          sessionInOrigin = siteSystem() != null,
          // Drawer rows use the prebaked-thumbnail lane like the grid; bakes from local pixels
          // only, never fetches.
          navThumbHash = { id ->
            heroImages
              .gridThumbFor(renderHost, id, catalogBundleHost(renderHost)?.contentCrop(id))
              ?.hash
              // Same warm as the grid's cards, for the same reason: the drawer's rows are the other
              // surface that falls back to a full-resolution render when the pixels are not local.
              .also { if (it == null) thumbWarmer.enqueue(renderHost, id) }
          },
        ),
        ContentType.Text.Html,
      )
    }
  }

  /**
   * `GET /history/render/{blob}.png`: one historical render from the local repository by content
   * sha ([ServeProjectHistory]). Content-addressed, so no path traversal or ref steering.
   * Session-independent, but registered in both URL forms for relative links.
   */
  private suspend fun RoutingContext.handleHistoryRender() {
    if (rejectBadToken()) return
    // Reads an old render out of the local git object store, per request.
    if (rejectHeadProbe()) return
    val history = projectHistory
    val name = call.parameters["name"].orEmpty().removeSuffix(".png")
    val bytes =
      if (history == null) null else withContext(Dispatchers.IO) { history.renderBytes(name) }
    if (bytes == null) {
      call.respondText("no such render", status = HttpStatusCode.NotFound)
      return
    }
    // Immutable by construction — the URL *is* the content hash — but this is a token-gated
    // response on a private box, so it follows the same no-store rule as every other one.
    markGeneration("history-render", DYNAMIC_RESOURCE_CACHE_CONTROL)
    call.respondBytes(bytes, ContentType.Image.PNG)
  }

  /**
   * The parameters [handleRender] reads: the query, or for [handleRenderPost] the query merged with
   * the body. URL-identity parameters (`at=`, `gen=`, `thumb=`, the token) stay on the query, since
   * a body isn't part of a cacheable address.
   */
  private fun RoutingContext.renderParams(): Parameters =
    call.attributes.getOrNull(RENDER_BODY_PARAMS) ?: call.request.queryParameters

  /**
   * `POST /render/{name}` and `POST /{system}/render/{name}`: [handleRender] with parameters in the
   * body, for knob values too large for a URL.
   *
   * Body is `application/json` (string/number/boolean values keyed like the query, e.g.
   * `{"knob.document": "…", "fontScale": 1.5}`) or form-urlencoded, merged over the query, so every
   * gate and lane is the GET's. Capped at [MAX_RENDER_BODY_BYTES] (413), matching
   * `catalog_render_preview`.
   */
  private suspend fun RoutingContext.handleRenderPost(sessionInPath: Boolean) {
    // The credential first, so an unauthenticated caller cannot make this server buffer a body.
    if (rejectBadToken()) return
    val bytes =
      withContext(Dispatchers.IO) {
        call.receiveStream().use { readCapped(it, MAX_RENDER_BODY_BYTES) }
      }
    if (bytes == null) {
      call.respondText(
        "render parameters exceed ${MAX_RENDER_BODY_BYTES / 1024} KiB",
        status = HttpStatusCode.PayloadTooLarge,
      )
      return
    }
    val contentType =
      call.request.headers[HttpHeaders.ContentType]
        ?.let { runCatching { ContentType.parse(it) }.getOrNull() }
        ?.withoutParameters()
    val body =
      when {
        bytes.isEmpty() -> emptyMap()
        contentType == null || contentType.match(ContentType.Application.Json) ->
          renderBodyJson(bytes.decodeToString())
            ?: run {
              call.respondText(
                "render body must be a JSON object of string, number or boolean values",
                status = HttpStatusCode.BadRequest,
              )
              return
            }
        contentType.match(ContentType.Application.FormUrlEncoded) ->
          io.ktor.http.parseQueryString(bytes.decodeToString()).entries().associate { (k, v) ->
            k to v.firstOrNull().orEmpty()
          }
        else -> {
          call.respondText(
            "render body must be application/json or application/x-www-form-urlencoded",
            status = HttpStatusCode.UnsupportedMediaType,
          )
          return
        }
      }
    val merged = Parameters.build {
      call.request.queryParameters.entries().forEach { (key, values) ->
        if (key !in body) appendAll(key, values)
      }
      body.forEach { (key, value) -> append(key, value) }
    }
    call.attributes.put(RENDER_BODY_PARAMS, merged)
    handleRender(sessionInPath)
  }

  /**
   * `GET /{system}/a2ui`: the A2UI playground, a document editor POSTing to the render route. 404
   * unless the catalog has a [ServeWeb.a2uiDocumentPreview].
   */
  private suspend fun RoutingContext.handleA2uiPlayground(sessionInPath: Boolean) {
    if (rejectBadToken()) return
    val sessionId = selectedSessionId(sessionInPath)
    val (webSessionId, basePath) = webSessionAndBase(sessionInPath)
    withLeasedSession(
      sessionId,
      onMissing = { respondNotFoundHtml("That design system was not found on this server.") },
    ) { renderHost ->
      val requested = call.request.queryParameters["preview"]
      val preview = ServeWeb.a2uiDocumentPreview(renderHost.previews, requested)
      if (preview == null) {
        respondNotFoundHtml(
          if (requested.isNullOrBlank()) "This design system declares no A2UI document preview."
          else "That preview is not an A2UI document preview."
        )
        return@withLeasedSession
      }
      markGeneration("static-page", DYNAMIC_RESOURCE_CACHE_CONTROL)
      call.respondText(
        ServeWeb.a2uiPlaygroundPage(
          moduleLabel = catalogBundleHost(renderHost)?.title ?: renderHost.label,
          preview = preview,
          token = linkToken(),
          sessionId = webSessionId,
          basePath = basePath,
          isPublic = isPublic,
          liveAvailable = renderHost.canRenderOverridesFor(preview.id),
          unfurl = ServeWeb.UnfurlMetadata(pageUrl = externalPageUrl()),
          version = SERVE_VERSION,
        ),
        ContentType.Text.Html,
      )
    }
  }

  /**
   * `GET /render/{name}` and `GET /{system}/render/{name}`: a preview's rendered bytes — PNG for
   * `<id>.png` (or no suffix), figma-svg for `<id>.svg`, slots JSON for `<id>.slots`, merged
   * accessibility JSON for `<id>.a11y`, inspection layers for `<id>.annotations`, or the captured
   * Remote Compose document for `<id>.rc`. All but `.rc` take the override params; SVG and slots
   * need a daemon, `.rc` a bundle host with `ir/` sidecars (each 404s otherwise).
   */
  private suspend fun RoutingContext.handleRender(sessionInPath: Boolean) {
    if (rejectBadToken() || rejectMalformedPin() || rejectMalformedGeneration()) return
    // An override or daemon-only product makes this a live render, which a `preview` grant doesn't
    // cover. Keyed on the request (override param or non-PNG suffix), not on whether bytes happen
    // to be baked, so ordinary browsing of a non-resident session still works.
    //
    // A bare `?rcPlayer=` is not refused here: it selects an already-published capture, which the
    // compare wall uses for every preview. If it would become a live render,
    // [renderCmpJvmResponse]'s subprocess and a null `cached` below refuse there instead.
    if (
      ((requestCarriesOverrides() && !bareRcPlayerRequest()) || wantsDaemonOnlyRenderProduct()) &&
        rejectGrantBelowScope(AgentGrantScope.LIVE, api = true)
    )
      return
    // The deferred half of that decision, settled here: non-null only for a request admitted as a
    // bare player selection. `bakedRender` is local-only, so a cold catalog's ordinary browse
    // returns null from `cached` and must still be served. Not re-asked after the lease, since an
    // expired grant would then read as "no grant" and be admitted.
    val bareRcPlayerBelowLive =
      if (requestCarriesOverrides() && bareRcPlayerRequest()) {
        grantBelowScope(AgentGrantScope.LIVE)
      } else {
        null
      }
    // A bare `/render/<id>.png` replays a baked file and is what unfurlers HEAD for `og:image`, so
    // it keeps answering HEAD. Overrides and [DAEMON_ONLY_RENDER_SUFFIXES] would turn a bodyless
    // probe into real work, so those refuse; the override check is by param name to stay cheap.
    if (
      (requestCarriesOverrides() ||
        wantsDaemonOnlyRenderProduct() ||
        !renderWouldReplayBakedBytes(sessionInPath)) && rejectHeadProbe()
    )
      return
    val sessionId = selectedSessionId(sessionInPath)
    withLeasedSession(sessionId) { renderHost ->
      val rawName = call.parameters["name"]
      if (rawName.isNullOrBlank()) {
        call.respondText("missing preview id", status = HttpStatusCode.BadRequest)
        return@withLeasedSession
      }
      val wantSvg = rawName.endsWith(".svg")
      val wantSlots = rawName.endsWith(".slots")
      val wantA11y = rawName.endsWith(".a11y")
      val wantAnnotations = rawName.endsWith(".annotations")
      val wantRcDoc = rawName.endsWith(".rc")
      // `.rc.json` is `.rc` projected to text for people and tools. Its suffix must be stripped
      // before `.rc`.
      val wantRcJson = rawName.endsWith(".rc.json")
      val previewId =
        rawName
          .removeSuffix(".png")
          .removeSuffix(".svg")
          .removeSuffix(".slots")
          .removeSuffix(".a11y")
          .removeSuffix(".annotations")
          .removeSuffix(".rc.json")
          .removeSuffix(".rc")
      // `?bg=`: composite the preview's resolved stage into the PNG ([ServeRenderMatte]).
      //
      // Not an override param: it paints under bytes a lane already chose, so it doesn't make a
      // live render, escalate grant scope, or change what a pin or generation means, and each lane
      // keeps its cache lifetime. A blank value is treated as absent.
      val requestedStage = renderParams()[ServeRenderMatte.PARAM]?.takeIf { it.isNotBlank() }
      val stageMode = ServeRenderMatte.Mode.parse(requestedStage)
      if (requestedStage != null && stageMode == null) {
        call.respondText(
          "unknown '${ServeRenderMatte.PARAM}' value '$requestedStage'; expected one of " +
            ServeRenderMatte.Mode.wires(),
          status = HttpStatusCode.BadRequest,
        )
        return@withLeasedSession
      }
      // Applied on the way out of every raster lane. A no-op without `bg=`, and
      // [ServeRenderMatte.apply] is itself a no-op on anything it can't stage, so no lane fails
      // because of it. On [Dispatchers.Default] because it is CPU work (a pass over the frame plus
      // a PNG re-encode).
      val staged: suspend (ByteArray, Map<String, String>) -> ByteArray = { bytes, overrides ->
        if (stageMode == null) bytes
        else {
          val stage = renderStage(renderHost, sessionId, previewId, overrides)
          if (stage == null) bytes
          else withContext(Dispatchers.Default) { ServeRenderMatte.apply(bytes, stageMode, stage) }
        }
      }
      // The prebaked grid-thumbnail lane: `?thumb=<hash>` returns a downscaled copy from memory —
      // no override parse, admission, disk read or daemon wake.
      //
      // The hash must match today's bake; a stale URL falls through to a normal render, so `thumb=`
      // bytes never change and the response is `immutable`. Only a request asking for nothing else
      // qualifies; overrides (e.g. the grid's `themeProvider=`), `scroll=` and the cmp-jvm lane go
      // the normal way.
      //
      // A stale generation also leaves the lane (checked here, ahead of the routing that would
      // fetch that publish), since the thumbnail is today's. A `gen=` naming the generation on disk
      // stays on the fast path.
      val thumbHash = call.request.queryParameters[ServeHeroImages.THUMB_PARAM]
      if (
        thumbHash != null &&
          !wantSvg &&
          !wantSlots &&
          !wantA11y &&
          !wantAnnotations &&
          !wantRcDoc &&
          !wantRcJson &&
          plainThumbRequest() &&
          staleGeneration(renderHost) == null
      ) {
        val thumb =
          heroImages.gridThumbFor(
            renderHost,
            previewId,
            catalogBundleHost(renderHost)?.contentCrop(previewId),
          )
        if (thumb != null && thumb.hash == thumbHash) {
          respondGridThumb(thumb)
          return@withLeasedSession
        }
      }
      // Products selected by query (`?scroll=long`, `?rcPlayer=cmp-jvm`, `mode=`, any override) are
      // made to order, so a pin naming one is contradictory and refused below. The generation lane
      // asks a narrower question ([madeToOrder]); the rules are stated separately because they
      // differ.
      val onDemand =
        requestCarriesOverrides() ||
          renderParams()["scroll"] != null ||
          renderParams()["rcPlayer"] != null ||
          renderParams()["mode"] != null ||
          ServeExplodedSvg.PARAMS.any { renderParams()[it] != null }
      // A pinned render (`?at=<sha>`): this preview's bytes at that delivery-branch commit, read
      // from the branch — what makes a published URL a permalink ([ServeCatalogRevision]).
      //
      // Short-circuits everything below: a pin and a live render are mutually exclusive. Only
      // rasters are pinnable, and an unanswerable pin is a 404, never today's bytes.
      val requestedPin =
        ServeCatalogRevision.normalize(call.request.queryParameters[ServeCatalogRevision.PARAM])
      // The same lane answers a stale generation ([ServeCacheGeneration]): a page from an earlier
      // publish needs that publish's frame. Reconciled here because it is a revision read with the
      // same fetch rules. A `gen=` matching disk falls through.
      //
      // A stale generation steps aside only for a render made to order (override, scroll capture,
      // player selection, exploded projection), which is `no-store` anyway, so turning a knob on an
      // overtaken page still works.
      //
      // Narrower than [onDemand]: `mode=` only affects the SVG export, and treating it as
      // made-to-order would opt the commonest shared viewer link out of the coupling.
      val madeToOrder =
        requestCarriesOverrides() ||
          renderParams()["scroll"] != null ||
          renderParams()["rcPlayer"] != null ||
          (wantSvg && renderParams()["mode"] != null) ||
          ServeExplodedSvg.PARAMS.any { renderParams()[it] != null }
      val staleGeneration = if (madeToOrder) null else staleGeneration(renderHost)
      // A stale generation on a non-raster product refuses, as a pin does: these products describe
      // the frame (semantics, a11y, annotations used as acceptance baselines), and only today's can
      // be described.
      if (
        staleGeneration != null &&
          (wantSvg || wantSlots || wantA11y || wantAnnotations || wantRcDoc || wantRcJson)
      ) {
        call.respondText(
          "only the baked render is published per generation; this catalog has moved on from " +
            "'${ServeCacheGeneration.PARAM}=${ServeCacheGeneration.short(staleGeneration)}', so " +
            "there is no answer for that frame — reload the page",
          status = HttpStatusCode.Conflict,
        )
        return@withLeasedSession
      }
      val pinnedCommit = requestedPin ?: staleGeneration
      if (pinnedCommit != null) {
        // Non-raster products are made on demand and never published per revision, so a pin on them
        // refuses rather than serving today's export under an old publish's URL.
        if (wantSvg || wantSlots || wantA11y || wantAnnotations || wantRcDoc || wantRcJson) {
          call.respondText(
            "only the baked render is published per revision; drop " +
              "'${ServeCatalogRevision.PARAM}' to ask the daemon for this product",
            status = HttpStatusCode.NotFound,
          )
          return@withLeasedSession
        }
        if (onDemand) {
          call.respondText(
            "'${ServeCatalogRevision.PARAM}' pins the published render, which cannot be " +
              "re-rendered to order; drop the pin or drop the render parameters",
            status = HttpStatusCode.BadRequest,
          )
          return@withLeasedSession
        }
        respondPinnedAsset(
          outcome =
            catalogBundleHost(renderHost)?.let {
              withContext(Dispatchers.IO) { it.pinnedRender(pinnedCommit, previewId) }
            },
          missing = "no published render for that preview at that revision",
          // A pin is override-free (`onDemand` refuses otherwise), so the stage resolves against
          // the preview's own published frame.
          transform = { staged(it, emptyMap()) },
        )
        return@withLeasedSession
      }
      // The `.rc` lane serves captured document bytes verbatim (the browser player applies knob
      // edits), so it short-circuits ahead of the override parse. No `ir/<id>.rc` sidecar → 404.
      if (wantRcDoc || wantRcJson) {
        val bytes = renderHost.remoteComposeDoc(previewId)
        // `no-store` on both lanes: the token can arrive in the `X-Compose-Preview-Token` header,
        // which no cache keys on, so a shared cache could otherwise serve one caller's document to
        // another or after revocation (see `DYNAMIC_RESOURCE_CACHE_CONTROL`).
        val documentCacheControl =
          if (isPublic) STATIC_RESOURCE_CACHE_CONTROL else DYNAMIC_RESOURCE_CACHE_CONTROL
        if (bytes == null) {
          call.respondText("no such remote compose document", status = HttpStatusCode.NotFound)
        } else if (wantRcJson) {
          // Projected on demand (a pure function costing one inflate) rather than cached beside a
          // document the catalog may rewrite.
          //
          // A bundle may be baked on a newer Remote Compose alpha than this server's `remote-core`
          // (the split-family case `RemoteComposePairing` names), so an uninflatable document is a
          // 422 naming the document and reason, not a 500.
          try {
            val projected = projectDocument(bytes)
            call.response.headers.append(HttpHeaders.CacheControl, documentCacheControl)
            call.respondText(projected, ContentType.Application.Json)
          } catch (e: RemoteComposeJsonException) {
            // The refusal is cached no more freely than the document: its message names the
            // document's size, which is not something to hand a later caller from a shared cache.
            call.response.headers.append(HttpHeaders.CacheControl, DYNAMIC_RESOURCE_CACHE_CONTROL)
            call.respondText(
              "cannot project that remote compose document: ${e.message}",
              status = HttpStatusCode.UnprocessableEntity,
            )
          }
        } else {
          call.response.headers.append(HttpHeaders.CacheControl, documentCacheControl)
          call.respondBytes(bytes, ContentType.Application.OctetStream)
        }
        return@withLeasedSession
      }
      // A bare cmp-jvm raster the parity run already staged, answered before the subprocess (a
      // ~4.3s one-shot JVM) is considered. Caught here because the short-circuit below returns
      // before the override parse the `cached` chain reads. "Bare" is read off the raw query: no
      // override besides `rcPlayer`. `.svg` is excluded (the staged artifact is a raster).
      if (!wantSvg && !wantSlots && !wantA11y && !wantAnnotations && bareRcPlayerRequest()) {
        val stagedRaster =
          renderHost.publishedRcPlayerRender(previewId, RcPlayerBackend.CMP_JVM).takeIf {
            ServeRcPlayerIds.isCmpJvm(renderParams()["rcPlayer"])
          }
        if (stagedRaster != null) {
          // Cached like the daemon player lanes, since these are the published bytes.
          markGeneration(
            RenderOutcome.Generation.RC_PUBLISHED.wire,
            if (isPublic) STATIC_RESOURCE_CACHE_CONTROL else PRIVATE_REPLAY_CACHE_CONTROL,
          )
          call.respondBytes(staged(stagedRaster, emptyMap()), ContentType.Image.PNG)
          return@withLeasedSession
        }
      }
      // The cmp-jvm lane renders the captured document with the embedded desktop player in an
      // isolated subprocess, for `.png` and `.svg`; slots stay a daemon product.
      if (
        !wantSlots &&
          !wantA11y &&
          !wantAnnotations &&
          ServeRcPlayerIds.isCmpJvm(renderParams()["rcPlayer"])
      ) {
        // Past the staged shortcut this spawns the desktop player, so it is a commission — the
        // first place the up-front gate defers to.
        bareRcPlayerBelowLive?.let {
          respondBelowScope(it, AgentGrantScope.LIVE, api = true)
          return@withLeasedSession
        }
        val format = if (wantSvg) RcJvmServerRenderer.Format.SVG else RcJvmServerRenderer.Format.PNG
        val webMode = wantSvg && renderParams()["mode"]?.lowercase() == "web"
        // A bare `?rcPlayer=cmp-jvm` raster is a fixed answer to a fixed URL (`uiMode` and
        // `rc.<name>=` are override params), so cache it; the compare wall requests it for every
        // unstaged row. `.svg` stays `no-store`, since `?mode=web` rewrites it without being an
        // override.
        val bareRaster = !wantSvg && bareRcPlayerRequest()
        renderCmpJvmResponse(
          renderHost,
          previewId,
          format,
          webMode,
          sessionId,
          // PNG only; an SVG export has no alpha to composite and is handed through untouched.
          stage = { bytes -> staged(bytes, emptyMap()) },
          cacheControl =
            if (!bareRaster) DYNAMIC_RESOURCE_CACHE_CONTROL
            else if (isPublic) STATIC_RESOURCE_CACHE_CONTROL else PRIVATE_REPLAY_CACHE_CONTROL,
        )
        return@withLeasedSession
      }
      // Forward the fixed render axes plus dynamic params (`knob.<key>=…`, `rc.<name>=…`, not in
      // SUPPORTED_KEYS) so live edits reach ServeOverrides.parse.
      val overrideParams =
        renderParams()
          .entries()
          .mapNotNull { (key, values) ->
            val value = values.firstOrNull() ?: return@mapNotNull null
            if (ServeOverrides.isOverrideParam(key)) key to value else null
          }
          .toMap()
      val themeSeeding = expandThemeProvider(renderHost, previewId, overrideParams)
      val normalizedOverrideParams =
        ServeWeb.SystemDisplay.normalizeOverrideParams(sessionId, themeSeeding.params)
      // Type a bare `knob.<key>=<value>` from the preview's declared knobs (an explicit
      // `<kind>:<value>` still wins) so the viewer never has to spell the type in the URL.
      val knobKinds =
        ServeOverrides.declaredKnobKinds(renderHost.previews.firstOrNull { it.id == previewId })
      // Reject a themeProvider this catalog never declared instead of quietly rendering the
      // default theme under its name (see ServeOverrides.parse).
      val declaredThemeFqns = renderHost.declaredThemes.map { it.providerFqn }.toSet()
      when (
        val parsed =
          ServeRcPlayerIds.parseOverrides(normalizedOverrideParams, knobKinds, declaredThemeFqns)
      ) {
        is OverrideParse.Invalid ->
          call.respondText(parsed.message, status = HttpStatusCode.BadRequest)
        is OverrideParse.Ok -> {
          // Wear/watch surfaces are always dark. Ignore a generic or hand-authored uiMode query so
          // it cannot wake the live daemon and produce another render for an unsupported mode.
          val overrides = parsed.overrides
          val scroll = renderParams()["scroll"]?.lowercase() in setOf("long", "full", "page")
          if (wantSvg) {
            // `?scroll=long` (or `full`/`page`) requests the full-page export
            // (compose/figma-svg-long).
            // `?mode=web` swaps the base64 `@font-face` blocks for a Google Fonts `@import` for
            // direct browser viewing; the default (or `mode=figma`) stays self-contained for
            // `<img>`/Figma import.
            // `?exploded=1` (with tilt / spin / gap / depth) pulls the layered export into one
            // sheet per nesting level. All three are post-processing over one render and compose.
            val webMode = renderParams()["mode"]?.lowercase() == "web"
            renderSvgResponse(
              renderHost,
              previewId,
              overrides,
              scroll = scroll,
              webMode = webMode,
              exploded = explodedOptions(),
            )
            return@withLeasedSession
          }
          if (wantSlots) {
            renderSlotsResponse(renderHost, previewId, overrides)
            return@withLeasedSession
          }
          if (wantA11y) {
            renderA11yResponse(renderHost, previewId, overrides)
            return@withLeasedSession
          }
          if (wantAnnotations) {
            renderAnnotationsResponse(renderHost, previewId, overrides, requestedInspectLayers())
            return@withLeasedSession
          }
          // The render blocks (renderNow + await), so keep it off the request dispatcher and cap
          // concurrent renders (default CPU count): wait briefly for a slot, else 503 +
          // Retry-After. Null means the wait timed out.
          // Catalog theme cache hits are memory reads and must not be refused because live renders
          // hold every slot; [render] rechecks after admission.
          // A full-page request is a distinct product a cached viewport PNG can't satisfy.
          // Answer without admission from a completed theme-cache entry or baked pixels on disk, so
          // readers aren't head-of-line blocked behind cold daemon renders.
          // A `?scroll=` request can't be satisfied by baked pixels.
          val cached =
            if (scroll) null
            else
              renderHost.cachedRender(previewId, overrides)
                // Before the baked snapshot: `bakedRender` ignores overrides, so a bare
                // `?rcPlayer=…` would otherwise get baked bytes and then be refused for dropping
                // `rcPlayer`. Baked is the androidx-embedded capture, so [publishedRcPlayerRender]
                // declines that backend and lets it fall through to baked; for every other backend
                // the ordering holds.
                ?: publishedRcPlayerRender(renderHost, previewId, overrides)
                ?: renderHost.bakedRender(previewId, overrides)
          // The second place the up-front gate defers to: a null `cached` for a bare player
          // selection means a render must be commissioned, so refuse with the door's decision. A
          // null `cached` alone isn't a commission — `bakedRender` reads only local files, so cold
          // catalogs answer null and `render` serves them after fetching.
          if (cached == null) {
            bareRcPlayerBelowLive?.let {
              respondBelowScope(it, AgentGrantScope.LIVE, api = true)
              return@withLeasedSession
            }
          }
          // A pure declared-theme render, the classification the burst lease admits on. Read from
          // the request, since the expanded provider is no longer in the parsed overrides.
          val pureThemeProvider =
            overrides.themeProvider?.takeIf {
              overrides == PreviewOverrides(themeProvider = it) &&
                renderHost.declaredThemes.any { theme -> theme.providerFqn == it }
            }
              ?: themeSeeding.provider?.takeIf {
                overrideParams.keys.all { key -> key == "themeProvider" } &&
                  renderHost.declaredThemes.any { theme -> theme.providerFqn == it }
              }
          // A preview this catalog has permanently failed to render is answered 409 before any
          // lease or slot is taken, so the page stops retrying.
          val latchedFailure =
            if (cached == null) renderHost.renderFailureLatch(previewId, overrides) else null
          if (latchedFailure != null) {
            call.respondText(latchedFailure, status = HttpStatusCode.Conflict)
            return@withLeasedSession
          }
          val leaseToken = call.request.queryParameters["_themeLease"]
          val admission =
            if (cached == null && pureThemeProvider != null && leaseToken != null) {
              themeRenderLeases.admission(leaseToken, sessionId, renderHost)
            } else {
              null
            }
          // Saturated is the only refusal. The claim is alive and its width is momentarily full, so
          // `Retry-After` is a true statement and the page's backoff is the right response.
          if (admission is ThemeRenderLeaseManager.Admission.Saturated) {
            call.response.headers.append(HttpHeaders.RetryAfter, "2")
            call.respondText(
              "theme render lease saturated",
              status = HttpStatusCode.TooManyRequests,
            )
            return@withLeasedSession
          }
          val leasePermit = (admission as? ThemeRenderLeaseManager.Admission.Admitted)?.permit
          // An unknown lease token (released, expired, another catalog's) is treated like none: the
          // render runs on the serial unleased lane. Refusing would be unrecoverable for the
          // caller.
          val needsSerialThemePermit =
            cached == null && pureThemeProvider != null && leasePermit == null
          val outcome =
            try {
              cached
                ?: withContext(Dispatchers.IO) {
                  val serialAcquired =
                    !needsSerialThemePermit ||
                      unleasedThemeSemaphore.tryAcquire(RENDER_QUEUE_WAIT_SECONDS, TimeUnit.SECONDS)
                  if (!serialAcquired) {
                    null
                  } else if (
                    !renderSemaphore.tryAcquire(RENDER_QUEUE_WAIT_SECONDS, TimeUnit.SECONDS)
                  ) {
                    if (needsSerialThemePermit) unleasedThemeSemaphore.release()
                    null
                  } else {
                    try {
                      if (scroll) renderHost.renderScrollPng(previewId, overrides)
                      else if (leasePermit != null) renderHost.renderLeased(previewId, overrides)
                      else renderHost.render(previewId, overrides)
                    } finally {
                      renderSemaphore.release()
                      if (needsSerialThemePermit) unleasedThemeSemaphore.release()
                    }
                  }
                }
            } finally {
              leasePermit?.close()
            }
          when (outcome) {
            null -> {
              call.response.headers.append(HttpHeaders.RetryAfter, "2")
              call.respondText(
                "render queue saturated; retry shortly",
                status = HttpStatusCode.ServiceUnavailable,
              )
            }
            RenderOutcome.Busy -> {
              // Daemon busy; backed off after ~DAEMON_BUSY_WAIT. Pure catalog-theme requests also
              // get this retry signal, since serving baked pixels with a success status would leave
              // the thumbnail on the wrong theme.
              call.response.headers.append(HttpHeaders.RetryAfter, "2")
              call.respondText(
                "render busy; retry shortly",
                status = HttpStatusCode.ServiceUnavailable,
              )
            }
            is RenderOutcome.Ok -> {
              // Baked pixels answering an override-bearing request don't reflect the override.
              // Every other generation is a real render keyed by these overrides.
              val dropped =
                droppedOverridesFor(renderHost, outcome.generation, previewId, overrides)
              if (dropped.isNotEmpty()) {
                respondDroppedOverrides(
                  renderHost,
                  previewId,
                  dropped,
                  overrides,
                  outcome.png,
                  ContentType.Image.PNG,
                  outcome.generation,
                )
              } else {
                // A bare `?rcPlayer=` is a fixed answer to a fixed URL: it replays a published
                // `ir/<id>.rc` through a named player at the preview's own spec, and every
                // request-dependent axis is another override param (excluded by `singleOrNull`).
                // [RenderOutcome.Generation.RC_PUBLISHED] is published bytes, and the compare wall
                // requests these per cell, so it takes the baked lane's lifetime and staleness
                // bound (`max-age` + `stale-while-revalidate`).
                // `!scroll`, as in [bareRcPlayerRequest]: a full-page capture is made to order
                // (`cached = if (scroll) null`).
                val bareRcPlayer = overrideParams.keys.singleOrNull() == "rcPlayer" && !scroll
                val bakedBrowse =
                  outcome.generation == RenderOutcome.Generation.BAKED && overrideParams.isEmpty()
                // A pure declared-theme selection is likewise fixed: the theme is defined by the
                // catalog ([ServeHost.declaredThemes]), and [pureThemeProvider] excludes every
                // caller-dependent axis. Cacheable so toggling theme chips doesn't refetch the PNG
                // each time.
                // `!scroll`, as in [bareRcPlayerRequest].
                val pureThemeRender = pureThemeProvider != null && !scroll
                markGeneration(
                  outcome.generation.wire,
                  // A bare player replay is cacheable on a private box too
                  // ([PRIVATE_REPLAY_CACHE_CONTROL]), but `private` since the URL carries the
                  // token. Everything else on a gated box stays `no-store`.
                  if (!isPublic) {
                    if (bareRcPlayer) PRIVATE_REPLAY_CACHE_CONTROL
                    else DYNAMIC_RESOURCE_CACHE_CONTROL
                  }
                  // A player selection never takes `immutable`, even generation-scoped: a redeploy
                  // can change the player without moving the generation.
                  else if (bareRcPlayer) STATIC_RESOURCE_CACHE_CONTROL
                  else if (bakedBrowse) {
                    // A generation-named frame URL is content-addressed (a republish moves the
                    // URL), so it takes `immutable`. Unscoped URLs keep the short public lifetime.
                    if (carriesCurrentGeneration(renderHost)) prebakedImageCacheControl(isPublic)
                    else STATIC_RESOURCE_CACHE_CONTROL
                  } else if (pureThemeRender) {
                    // Generation-scoped themed renders are content-addressed too; unscoped ones
                    // keep the short lifetime like the player lane.
                    if (carriesCurrentGeneration(renderHost)) prebakedImageCacheControl(isPublic)
                    else STATIC_RESOURCE_CACHE_CONTROL
                  } else DYNAMIC_RESOURCE_CACHE_CONTROL,
                )
                call.respondBytes(staged(outcome.png, overrideParams), ContentType.Image.PNG)
              }
            }
            RenderOutcome.NotFound ->
              call.respondText("no such preview", status = HttpStatusCode.NotFound)
            is RenderOutcome.Failed ->
              call.respondText(outcome.reason, status = HttpStatusCode.InternalServerError)
          }
        }
      }
    }
  }

  /**
   * The validated overrides a [generation] artifact for [previewId] does not reflect: empty unless
   * the bytes came straight off a published bundle ([RenderOutcome.Generation.BAKED]). Every other
   * generation is a real render keyed by these overrides.
   */
  /**
   * [previewId]'s published render by the player a bare `?rcPlayer=` names, as a [RenderOutcome]
   * for the pre-admission `cached` chain, or null. The offline parity run drew every document with
   * every player, so these are published bytes.
   *
   * The backend matching [ServeHost.bakedRcPlayer] is excluded and answered from baked instead
   * (androidx-embedded for an ordinary preview, the `RemoteOverridablePreview` default).
   *
   * "Bare" is the safety condition: with any other override the player selection is stripped and
   * what remains must be satisfiable by the baked snapshot
   * ([CatalogLiveRouting.overridesAffectRender]); otherwise null and the request goes to the
   * renderer.
   */
  private fun publishedRcPlayerRender(
    renderHost: ServeHost,
    previewId: String,
    overrides: PreviewOverrides,
  ): RenderOutcome.Ok? {
    val rc = overrides.remoteCompose ?: return null
    val player = rc.player ?: return null
    val backend = RcPlayerBackend.entries.firstOrNull { it.playerKind == player } ?: return null
    // The player the catalog baked with ([ServeHost.bakedRcPlayer], usually
    // [RcPlayerBackend.ANDROIDX_EMBEDDED]) is served from the baked artifact, not its staged
    // rc-compare column: the staged `embedded` column is a different render (the vendored player
    // under this repo's Robolectric harness) and differs slightly, so a bare browse and an explicit
    // pick would disagree. Baked is also faster. Same reasoning as
    // [RcPlayerBackend.ANDROIDX_VIEW]'s null `rcCompareLane`. Other backends keep the shortcut,
    // including androidx-embedded on a view-pinned preview.
    if (player == renderHost.bakedRcPlayer(previewId)) return null
    // Everything the request asks for beyond "draw it with this player".
    val withoutPlayer =
      overrides.copy(
        remoteCompose =
          rc
            .newBuilder()
            .also { it.player = null }
            .build()
            .takeIf { it.profile != null || it.namedValues.isNotEmpty() }
      )
    if (
      CatalogLiveRouting.overridesAffectRender(
        previewId,
        withoutPlayer,
        renderHost.bakedTheme(previewId),
        renderHost.bakedRcPlayer(previewId),
      )
    )
      return null
    val bytes = renderHost.publishedRcPlayerRender(previewId, backend) ?: return null
    return RenderOutcome.Ok(bytes, RenderOutcome.Generation.RC_PUBLISHED)
  }

  private fun droppedOverridesFor(
    renderHost: ServeHost,
    generation: RenderOutcome.Generation,
    previewId: String,
    overrides: PreviewOverrides,
  ): List<String> =
    if (generation == RenderOutcome.Generation.BAKED) {
      CatalogLiveRouting.droppedOverrideNames(
        previewId,
        overrides,
        renderHost.bakedTheme(previewId),
        renderHost.bakedRcPlayer(previewId),
      )
    } else if (renderHost.hasRemoteComposeDoc(previewId)) {
      // A real render happened but still couldn't apply everything, because this preview is
      // replayed rather than recomposed; see [CatalogLiveRouting.irReplayDroppedOverrideNames].
      CatalogLiveRouting.irReplayDroppedOverrideNames(
        previewId,
        overrides,
        renderHost.bakedTheme(previewId),
        renderHost.bakedRcPlayer(previewId),
      )
    } else {
      emptyList()
    }

  /**
   * The dropped overrides a refusal for [previewId] must call terminal: axes a replay can never
   * apply. Exactly [CatalogLiveRouting.irReplayDroppedOverrideNames]; empty for a recomposing
   * preview.
   *
   * Terminality is per axis, not per preview: a transient baked fallback on e.g. `?rcPlayer=java`
   * must be retryable (503), since the warm daemon would answer 200 and the viewer treats 409 as
   * final. `rcPlayer` is never terminal (`IrReplayDroppedOverridesTest`).
   */
  private fun terminalDroppedOverrides(
    renderHost: ServeHost,
    previewId: String,
    overrides: PreviewOverrides,
  ): List<String> =
    if (isReplayedPreview(renderHost, previewId)) {
      CatalogLiveRouting.irReplayDroppedOverrideNames(
        previewId,
        overrides,
        renderHost.bakedTheme(previewId),
        renderHost.bakedRcPlayer(previewId),
      )
    } else {
      emptyList()
    }

  /**
   * The declared themes [previewId] can render under: all when it recomposes, else only those with
   * a published replay mapping, so no offered theme 409s.
   */
  private fun applicableThemes(renderHost: ServeHost, previewId: String): List<ServeTheme> =
    if (isReplayedPreview(renderHost, previewId)) renderHost.replayableThemes()
    else renderHost.declaredThemes

  /**
   * The declared themes a multi-preview page can offer: the union over its previews. Honest only
   * with the per-card gate [everyThemeApplies]: a replayed card that can't take the whole offered
   * set is gated out of the control.
   */
  private fun applicableThemes(renderHost: ServeHost): List<ServeTheme> =
    if (renderHost.previews.any { !isReplayedPreview(renderHost, it.id) }) {
      renderHost.declaredThemes
    } else {
      renderHost.replayableThemes()
    }

  /** Whether [previewId] can be rendered under every theme [applicableThemes] offers this page. */
  private fun everyThemeApplies(renderHost: ServeHost, previewId: String): Boolean =
    applicableThemes(renderHost, previewId)
      .map { it.providerFqn }
      .containsAll(applicableThemes(renderHost).map { it.providerFqn })

  /**
   * Whether [previewId] is redrawn by replaying its captured document rather than recomposing. Not
   * a statement that a refusal is final ([terminalDroppedOverrides] decides that).
   */
  private fun isReplayedPreview(renderHost: ServeHost, previewId: String): Boolean =
    ServeThemeReplay.isReplayed(renderHost, previewId)

  /**
   * [ServeThemeReplay.expand] as a member; the logic lives in [ServeThemeReplay] because the
   * WebSocket lanes need it too.
   */
  private fun expandThemeProvider(
    renderHost: ServeHost,
    previewId: String,
    params: Map<String, String>,
  ): ServeThemeReplay.Seeding = ServeThemeReplay.expand(renderHost, previewId, params)

  /**
   * Answer a render whose validated overrides were not applied: [bytes] are the baked artifact
   * while the request asked for [dropped]. Shared by the PNG and SVG lanes.
   *
   * A plain `200` would be indistinguishable from an override that changed nothing, misleading diff
   * bots, parity checks and agents. Three outcomes, each naming the dropped params in
   * [DROPPED_OVERRIDES_HEADER]:
   * - `?fallback=baked`: the caller accepted the snapshot. 200 plus `X-Compose-Preview-Render:
   *   baked-fallback`.
   * - a live lane exists but not right now (daemon down, cold, no seat): 503 + `Retry-After`,
   *   including replayed previews for axes their document can answer ([refuseDroppedOverrides]).
   * - no live lane at all, or an axis no replay can honour: 409, which the viewer treats as
   *   terminal.
   */
  private suspend fun RoutingContext.respondDroppedOverrides(
    renderHost: ServeHost,
    previewId: String,
    dropped: List<String>,
    overrides: PreviewOverrides,
    bytes: ByteArray,
    contentType: ContentType,
    generation: RenderOutcome.Generation,
  ) {
    if (acceptsBakedFallback()) {
      markDroppedOverrides(dropped)
      markGeneration(generation.wire, DYNAMIC_RESOURCE_CACHE_CONTROL)
      call.respondBytes(bytes, contentType)
      return
    }
    refuseDroppedOverrides(renderHost, previewId, dropped, overrides)
  }

  /**
   * Whether this request selects a Remote Compose player and nothing else, read off the raw query
   * for the caller that runs before [ServeOverrides.parse]. Anything more needs the renderer.
   * `scroll=` is excluded like `.svg` (a different product), matching `cached = if (scroll) null`.
   */
  private fun RoutingContext.bareRcPlayerRequest(): Boolean =
    renderParams().entries().none { (key, _) ->
      (ServeOverrides.isOverrideParam(key) && key != "rcPlayer") || key == "scroll"
    }

  /** Whether the caller passed `?fallback=baked` — an explicit "serve the snapshot anyway". */
  private fun RoutingContext.acceptsBakedFallback(): Boolean =
    renderParams()[FALLBACK_PARAM]?.lowercase() == FALLBACK_BAKED

  /**
   * Name the un-applied overrides on a response that carries the baked artifact anyway (accepted
   * `?fallback=baked`, and Storybook isolation pages). No-op when nothing was dropped.
   */
  private fun RoutingContext.markDroppedOverrides(dropped: List<String>) {
    if (dropped.isEmpty()) return
    call.response.headers.append(DROPPED_OVERRIDES_HEADER, dropped.joinToString(","))
    call.response.headers.append(RENDER_HEADER, RENDER_BAKED_FALLBACK)
  }

  /**
   * The refusal half of [respondDroppedOverrides]: 409 when any dropped axis is terminal
   * ([terminalDroppedOverrides], per axis), else 503 when a live lane exists, else 409 for no lane.
   * One terminal axis decides the whole response (retrying can't satisfy it), and the message names
   * only the terminal axes.
   */
  private suspend fun RoutingContext.refuseDroppedOverrides(
    renderHost: ServeHost,
    previewId: String,
    dropped: List<String>,
    overrides: PreviewOverrides,
  ) {
    call.response.headers.append(DROPPED_OVERRIDES_HEADER, dropped.joinToString(","))
    val params = dropped.joinToString(", ")
    val terminal = terminalDroppedOverrides(renderHost, previewId, overrides)
    if (terminal.isNotEmpty()) {
      call.respondText(
        "override not applied: ${terminal.joinToString(", ")} — this preview is replayed from its " +
          "captured document, which cannot be recomposed, so the override can never apply; add " +
          "&$FALLBACK_PARAM=$FALLBACK_BAKED to accept the published snapshot (which ignores it)",
        status = HttpStatusCode.Conflict,
      )
    } else if (renderHost.canRenderOverridesFor(previewId)) {
      call.response.headers.append(HttpHeaders.RetryAfter, "2")
      call.respondText(
        "override not applied: $params — this preview's live render lane is unavailable; " +
          "retry shortly, or add &$FALLBACK_PARAM=$FALLBACK_BAKED to accept the baked snapshot " +
          "(which ignores the override)",
        status = HttpStatusCode.ServiceUnavailable,
      )
    } else {
      call.respondText(
        "override not applied: $params — this preview has no live render lane, so only its baked " +
          "snapshot can be served; add &$FALLBACK_PARAM=$FALLBACK_BAKED to accept it (which " +
          "ignores the override)",
        status = HttpStatusCode.Conflict,
      )
    }
  }

  /**
   * cmp-jvm lane of [handleRender]: render the captured document with the embedded desktop player
   * in an isolated subprocess and respond with [format]. Load-shed via [renderSemaphore]. 404
   * without a captured doc / render spec; 503 when the sidecar isn't installed or the queue is
   * full; 500 when the player fails.
   */
  private suspend fun RoutingContext.renderCmpJvmResponse(
    renderHost: ServeHost,
    previewId: String,
    format: RcJvmServerRenderer.Format,
    webMode: Boolean,
    sessionId: String,
    /** The `?bg=` stage for a raster result; identity without `bg=`, never applied to SVG. */
    stage: suspend (ByteArray) -> ByteArray = { it },
    /** Lifetime for a successful render — see the call site for which requests earn one. */
    cacheControl: String,
  ) {
    val doc = renderHost.remoteComposeDoc(previewId)
    val spec = renderHost.remoteComposeRenderSpec(previewId)
    if (doc == null || spec == null) {
      call.respondText("no cmp-jvm render for this preview", status = HttpStatusCode.NotFound)
      return
    }
    // Apply live `rc.<name>=…` knob edits, leniently parsed (malformed seeds fall back to the
    // authored default) — the server counterpart of the JS lane's in-browser knobs.
    val seeds =
      ServeOverrides.rcNamedValueSeeds(
        expandThemeProvider(
            renderHost,
            previewId,
            renderParams().entries().associate { (key, values) ->
              key to (values.firstOrNull() ?: "")
            },
          )
          .params
      )
    // `?uiMode=dark` selects the document's dark `ColorTheme` branch; for a replayed document it is
    // a player setting.
    val theme =
      cmpJvmRenderTheme(
        renderParams()["uiMode"],
        renderHost.previews.firstOrNull { it.id == previewId }?.uiMode ?: 0,
        ServeWeb.SystemDisplay.resolveDarkFirst(
          sessionId,
          catalogBundleHost(renderHost)?.stageSurface,
        ),
      )
    respondCmpJvmRender(doc, spec, seeds, format, theme, webMode, stage, cacheControl)
  }

  /**
   * Render [doc] with the embedded desktop player under the render semaphore — the shared tail of
   * the catalog cmp-jvm lane and shared documents.
   */
  private suspend fun RoutingContext.respondCmpJvmRender(
    doc: ByteArray,
    spec: RcJvmRenderSpec,
    seeds: Map<String, RemoteNamedValue>,
    format: RcJvmServerRenderer.Format,
    theme: RcJvmServerRenderer.RenderTheme,
    webMode: Boolean,
    stage: suspend (ByteArray) -> ByteArray,
    cacheControl: String,
  ) {
    val result =
      withContext(Dispatchers.IO) {
        if (!renderSemaphore.tryAcquire(RENDER_QUEUE_WAIT_SECONDS, TimeUnit.SECONDS)) {
          null
        } else {
          try {
            RcJvmServerRenderer.render(doc, spec, seeds, format, theme)
          } finally {
            renderSemaphore.release()
          }
        }
      }
    when (result) {
      null -> {
        call.response.headers.append(HttpHeaders.RetryAfter, "2")
        call.respondText(
          "render queue saturated; retry shortly",
          status = HttpStatusCode.ServiceUnavailable,
        )
      }
      is RcJvmServerRenderer.RenderResult.Ok -> {
        call.response.headers.append(HttpHeaders.CacheControl, cacheControl)
        val bytes =
          if (format == RcJvmServerRenderer.Format.SVG && webMode) {
            webModeSvg(result.bytes.toString(Charsets.UTF_8)).toByteArray(Charsets.UTF_8)
          } else {
            result.bytes
          }
        val contentType =
          if (format == RcJvmServerRenderer.Format.SVG) {
            ContentType.parse(ComposeFigmaSvgProduct.MEDIA_TYPE_SVG)
          } else {
            ContentType.Image.PNG
          }
        call.respondBytes(
          if (format == RcJvmServerRenderer.Format.SVG) bytes else stage(bytes),
          contentType,
        )
      }
      is RcJvmServerRenderer.RenderResult.Unavailable -> {
        // The chip is only offered when the sidecar is present, so this is a torn-down install
        // rather than a user error; a retryable 503 with the search paths beats a hard 500.
        call.response.headers.append(HttpHeaders.RetryAfter, "5")
        call.respondText(result.reason, status = HttpStatusCode.ServiceUnavailable)
      }
      is RcJvmServerRenderer.RenderResult.Failed ->
        call.respondText(result.reason, status = HttpStatusCode.InternalServerError)
    }
  }

  /**
   * The server-side players a `/d/<id>` page may offer: the embedded desktop player (cmp-jvm) when
   * installed, and each Android player ([DOC_DAEMON_PLAYERS]) a resident catalog daemon can draw
   * with ([docRcDonor]). Behind the same gate as [handleDocRender].
   */
  private fun RoutingContext.docServerPlayers(
    doc: ServeDocStore.Doc
  ): List<ServeWeb.DocServerPlayer> {
    if (doc.format.id != ServeDocFormats.REMOTE_COMPOSE.id) return emptyList()
    if (!mayRenderDocServerSide(call)) return emptyList()
    val renderPath = "${doc.path}/render.png"
    val jvm =
      if (RcJvmServerRenderer.isAvailable())
        listOf(ServeWeb.DocServerPlayer(ServeRcPlayerIds.CMP_JVM, "CMP (JVM)", renderPath))
      else emptyList()
    val android =
      DOC_DAEMON_PLAYERS.filter { docRcDonor(it) != null }
        .map { ServeWeb.DocServerPlayer(ServeRcPlayerIds.of(it), it.label, renderPath) }
    return jvm + android
  }

  /** A catalog preview whose live daemon replays a carried Remote Compose document. */
  private data class DocRcDonor(val sessionId: String, val previewId: String)

  /**
   * A preview in an already-resident session whose daemon can draw a carried document with
   * [backend]: it replays captured Remote Compose documents, renders overrides live, and has
   * [backend] enabled.
   *
   * The donor only lends its daemon: the daemon replays `overrides.remoteCompose.documentBase64` in
   * place of the preview's content, and the render cache is keyed on the document. Resident running
   * daemons only ([ServeSessionRegistry.peekHost], [ServeHost.daemonStarted]), so a shared document
   * never boots one; [respondDocDaemonRender] re-checks under its lease.
   */
  private fun docRcDonor(backend: RcPlayerBackend): DocRcDonor? {
    for (sessionId in sessions.knownSessionIds()) {
      val host = sessions.peekHost(sessionId) ?: continue
      if (!host.daemonStarted) continue
      val previewId =
        runCatching {
          host.previews
            .firstOrNull {
              host.hasRemoteComposeDoc(it.id) &&
                host.canRenderOverridesFor(it.id) &&
                backend in host.enabledRcPlayersFor(it.id)
            }
            ?.id
        }
          .getOrNull() ?: continue
      return DocRcDonor(sessionId, previewId)
    }
    return null
  }

  /**
   * Whether [call] may spend a render worker on a shared document. Stricter than the live-render
   * gate: anonymous `--accept-docs` uploads are only stored and played in the browser, so a `live`
   * grant, GitHub sign-in, or the host token is required.
   */
  private fun mayRenderDocServerSide(call: ApplicationCall): Boolean {
    agentGrantFor(call)?.let {
      return it.allows(AgentGrantScope.LIVE)
    }
    githubAuth?.let {
      return it.isAuthenticated(call)
    }
    return !isPublic
  }

  /**
   * `GET /d/{id}/render.png?rcPlayer=cmp-jvm`: a shared Remote Compose document drawn server-side.
   * Needs a real credential ([mayRenderDocServerSide]) even on a public host. Sized from the
   * document header at `?density=` (default 1); `?uiMode=` and `rc.<name>=` as on `/render`.
   */
  private suspend fun RoutingContext.handleDocRender(store: ServeDocStore) {
    // A bodyless probe would still take a render worker: Ktor's AutoHeadResponse runs this handler.
    if (rejectHeadProbe()) return
    if (rejectBadToken()) return
    if (rejectMissingGithubAuth(api = true)) return
    if (!mayRenderDocServerSide(call)) {
      call.respondText(
        "server-side rendering of shared documents needs a credential on this host",
        status = HttpStatusCode.Forbidden,
      )
      return
    }
    val doc = leaseDoc(store)
    if (doc == null || doc.format.id != ServeDocFormats.REMOTE_COMPOSE.id) {
      call.respondText("no such Remote Compose document", status = HttpStatusCode.NotFound)
      return
    }
    val params = call.request.queryParameters
    val size = doc.format.size(doc.bytes)
    val density = params["density"]?.toFloatOrNull()?.takeIf { it in 0.5f..4f } ?: 1f
    val daemonPlayer =
      params["rcPlayer"]
        ?.let { RcPlayerBackend.fromWire(ServeRcPlayerIds.normalizeRequest(it)) }
        ?.takeIf { it in DOC_DAEMON_PLAYERS }
    if (daemonPlayer != null) {
      respondDocDaemonRender(doc, daemonPlayer, size, density, params)
      return
    }
    if (!ServeRcPlayerIds.isCmpJvm(params["rcPlayer"])) {
      call.respondText(
        "a shared document renders server-side with rcPlayer=" +
          (listOf(ServeRcPlayerIds.CMP_JVM) + DOC_DAEMON_PLAYERS.map { ServeRcPlayerIds.of(it) })
            .joinToString("|"),
        status = HttpStatusCode.BadRequest,
      )
      return
    }
    val spec =
      RcJvmRenderSpec(
        (size?.width ?: DOC_RENDER_DEFAULT_PX).coerceIn(1, DOC_RENDER_MAX_PX),
        (size?.height ?: DOC_RENDER_DEFAULT_PX).coerceIn(1, DOC_RENDER_MAX_PX),
        density,
        1f,
      )
    val seeds =
      ServeOverrides.rcNamedValueSeeds(
        params.entries().associate { (key, values) -> key to (values.firstOrNull() ?: "") }
      )
    val theme = cmpJvmRenderTheme(params["uiMode"], bakedUiMode = 0)
    // An expiring capability URL must never be stored by a shared cache — no-store, as the page.
    markGeneration("document", "private, no-store")
    respondCmpJvmRender(
      doc.bytes,
      spec,
      seeds,
      RcJvmServerRenderer.Format.PNG,
      theme,
      webMode = false,
      stage = { it },
      cacheControl = "private, no-store",
    )
  }

  /**
   * The Android half of [handleDocRender]: [doc] drawn by [player] on a resident catalog's daemon
   * ([docRcDonor]) via `overrides.remoteCompose.documentBase64`. Only a fresh render answers; baked
   * or published images are the donor's pixels, so they are refused.
   */
  private suspend fun RoutingContext.respondDocDaemonRender(
    doc: ServeDocStore.Doc,
    player: RcPlayerBackend,
    size: ServeDocSize?,
    density: Float,
    params: Parameters,
  ) {
    val donor = docRcDonor(player)
    if (donor == null) {
      call.response.headers.append(HttpHeaders.RetryAfter, "30")
      call.respondText(
        "no running catalog here can draw ${ServeRcPlayerIds.of(player)}",
        status = HttpStatusCode.ServiceUnavailable,
      )
      return
    }
    val widthPx = ((size?.width ?: DOC_RENDER_DEFAULT_PX) * density).toInt()
    val heightPx = ((size?.height ?: DOC_RENDER_DEFAULT_PX) * density).toInt()
    val overrides =
      PreviewOverrides(
        widthPx = widthPx.coerceIn(1, DOC_RENDER_MAX_PX),
        heightPx = heightPx.coerceIn(1, DOC_RENDER_MAX_PX),
        density = density,
        // The page asks for the viewer's theme on every server lane, and a document whose colours
        // defer to the host configuration must follow it rather than the donor preview's mode.
        uiMode =
          when (params["uiMode"]?.lowercase()) {
            "dark" -> UiMode.DARK
            "light" -> UiMode.LIGHT
            else -> null
          },
        remoteCompose =
          RemoteComposeOverride.Builder()
            .also {
              it.player = player.playerKind
              it.playerId = player.daemonPlayerId
              it.documentBase64 = java.util.Base64.getEncoder().encodeToString(doc.bytes)
              it.namedValues =
                ServeOverrides.rcNamedValueSeeds(
                  params.entries().associate { (key, values) ->
                    key to (values.firstOrNull() ?: "")
                  }
                )
            }
            .build(),
      )
    // An expiring capability URL must never be stored by a shared cache — no-store, as the page.
    markGeneration("document", "private, no-store")
    call.response.headers.append(HttpHeaders.CacheControl, "private, no-store")
    withLeasedSession(donor.sessionId) { host ->
      // The lease resumes a session suspended since [docRcDonor] peeked at it, and a resumed host
      // has no daemon yet: rendering now would boot one for a shared document. Ask again instead.
      if (!host.daemonStarted) {
        call.response.headers.append(HttpHeaders.RetryAfter, "30")
        call.respondText(
          "no running catalog here can draw ${ServeRcPlayerIds.of(player)}",
          status = HttpStatusCode.ServiceUnavailable,
        )
        return@withLeasedSession
      }
      val outcome =
        withContext(Dispatchers.IO) {
          if (!renderSemaphore.tryAcquire(RENDER_QUEUE_WAIT_SECONDS, TimeUnit.SECONDS)) null
          else
            try {
              host.render(donor.previewId, overrides)
            } finally {
              renderSemaphore.release()
            }
        }
      when (outcome) {
        null,
        RenderOutcome.Busy -> {
          call.response.headers.append(HttpHeaders.RetryAfter, "2")
          call.respondText(
            "render busy; retry shortly",
            status = HttpStatusCode.ServiceUnavailable,
          )
        }
        is RenderOutcome.Ok ->
          if (
            outcome.generation == RenderOutcome.Generation.BAKED ||
              outcome.generation == RenderOutcome.Generation.RC_PUBLISHED
          ) {
            call.response.headers.append(HttpHeaders.RetryAfter, "2")
            call.respondText(
              "the ${ServeRcPlayerIds.of(player)} daemon is not warm yet; retry shortly",
              status = HttpStatusCode.ServiceUnavailable,
            )
          } else {
            call.respondBytes(outcome.png, ContentType.Image.PNG)
          }
        is RenderOutcome.Failed ->
          call.respondText(outcome.reason, status = HttpStatusCode.InternalServerError)
        RenderOutcome.NotFound ->
          call.respondText("the donor preview is gone", status = HttpStatusCode.ServiceUnavailable)
        else ->
          call.respondText("unexpected render outcome", status = HttpStatusCode.InternalServerError)
      }
    }
  }

  /**
   * Whether this request presented a valid session cookie, choosing between
   * [SIGNED_IN_PAGE_CACHE_CONTROL] and [ANON_PAGE_CACHE_CONTROL]. Request-scoped because only a
   * personal response justifies refusing to store it.
   */
  /**
   * Whether this request counts as a view. False for HEAD:
   * [io.ktor.server.plugins.autohead.AutoHeadResponse] runs the GET pipeline, so unfurler probes
   * would double-count.
   */
  private fun RoutingContext.isViewRequest(): Boolean = call.request.local.method != HttpMethod.Head

  /**
   * Refuse `HEAD` on a lane whose GET does real work, answering `405` + `Allow: GET`; true when
   * refused.
   *
   * [io.ktor.server.plugins.autohead.AutoHeadResponse] runs the whole GET handler, so `HEAD
   * /bundle.zip` would render every preview and discard the zip, letting anonymous probes burn
   * render capacity. Applied only to work lanes; pages and baked PNGs keep answering HEAD for
   * unfurlers.
   */
  /**
   * Whether the query names any render override (`fontScale`, `device`, `themeProvider`,
   * `knob.<key>`, …). A cheap key-level test rather than [ServeOverrides.parse]; both callers fail
   * safe on over-reporting.
   */
  private fun RoutingContext.requestCarriesOverrides(): Boolean =
    renderParams().entries().any { (key, _) -> ServeOverrides.isOverrideParam(key) }

  /**
   * `/render/{name}` suffixes that are never a baked replay: figma-svg, the slot / accessibility /
   * annotation products, and the captured Remote Compose document. Only `<id>.png` (or no suffix)
   * serves published bytes.
   */
  /**
   * Project [bytes], refusing a document too large to be one. The projection holds the parsed graph
   * and expanded JSON at once, and an uploaded bundle may carry up to 100 MB.
   * [MAX_PROJECTABLE_DOCUMENT_BYTES] (8 MB) is far above real captured documents. Reported as the
   * same 422 as an uninflatable document.
   */
  private fun projectDocument(bytes: ByteArray): String {
    if (bytes.size > maxProjectableDocumentBytes) {
      throw RemoteComposeJsonException(
        "document is ${bytes.size} bytes, above the ${maxProjectableDocumentBytes}-byte " +
          "projection limit; fetch the .rc lane for the bytes themselves"
      )
    }
    return RemoteComposeJson.dump(bytes)
  }

  private val DAEMON_ONLY_RENDER_SUFFIXES =
    // `.rc.json` doesn't end with `.rc`, so it must be listed explicitly.
    listOf(".svg", ".slots", ".a11y", ".annotations", ".rc", ".rc.json")

  /**
   * Whether `/render/{name}` names one of [DAEMON_ONLY_RENDER_SUFFIXES], a product this route must
   * make even without a query, so an override-free HEAD doesn't take the semaphore or start a
   * daemon.
   */
  private fun RoutingContext.wantsDaemonOnlyRenderProduct(): Boolean {
    val name = call.parameters["name"] ?: return false
    return DAEMON_ONLY_RENDER_SUFFIXES.any { name.endsWith(it) }
  }

  /**
   * Whether a bare `/render/{name}` can be answered from published bytes alone, so a HEAD costs
   * nothing. A `.png` suffix isn't enough: a plain [ServeRenderHost] has no bake and deferred
   * previews ([ServeHost.liveOnlyPreviewIds]) are published without one.
   * [ServeHost.bakedRenderSize] answers this from a PNG header and is null for both. Also requires
   * a resident session ([ServeSessionRegistry.peekHost]). Refusals are safe: unfurlers fetch images
   * with GET.
   */
  private fun RoutingContext.renderWouldReplayBakedBytes(sessionInPath: Boolean): Boolean {
    // Only a HEAD pays for this lookup; a GET is going to do the work regardless.
    if (call.request.local.method != HttpMethod.Head) return true
    // A pinned render answers HEAD: the lane is admission-bounded, so a probe costs at most one
    // permitted branch read, which the following GET reuses from cache; unfurlers probe `og:image`
    // first.
    // Free when the bytes are resident.
    if (call.request.queryParameters[ServeCatalogRevision.PARAM] != null) return true
    val name = call.parameters["name"]?.removeSuffix(".png") ?: return false
    val host = sessions.peekHost(selectedSessionId(sessionInPath)) ?: return false
    return host.bakedRenderSize(name) != null
  }

  /**
   * Serve one published animated capture.
   *
   * The extension is matched against the host's closed set and passed through, so lookup and
   * `Content-Type` agree; anything else (unknown suffix or undeclared id) is a 404, so the
   * requester can't choose the served type.
   *
   * Leased, not peeked: [ServeSessionRegistry.peekHost] never resumes, which would 404 every idle
   * catalog. The lease is the same `/render` takes and costs no render seat; the capture is read
   * off the staged branch asset.
   */
  private suspend fun RoutingContext.handleMotion(sessionInPath: Boolean) {
    // Token-gated like every sibling asset lane.
    if (rejectBadToken()) return
    if (rejectHeadProbe()) return
    val name = call.parameters["name"].orEmpty()
    val extension = MOTION_CONTENT_TYPES.keys.firstOrNull { name.endsWith(it) }
    if (extension == null) {
      call.respondText("", status = HttpStatusCode.NotFound)
      return
    }
    val motionId = name.removeSuffix(extension)
    val outcome =
      withLeasedSessionOrNull(selectedSessionId(sessionInPath)) { host ->
        host.motionRead(motionId, extension)
      } ?: BranchFetch.NotFound
    val bytes = outcome.bytesOrNull
    if (bytes == null) {
      // An unpublished capture is 404. One the delivery branch is currently refusing is 503 with
      // `Retry-After`, not 404. One past the transport's size envelope (`TooLarge`) is 413, as for
      // any body past a ceiling.
      if (outcome is BranchFetch.TooLarge) {
        call.respondText(outcome.summary, status = HttpStatusCode.PayloadTooLarge)
        return
      }
      if (outcome.isTransient) {
        call.response.headers.append(
          HttpHeaders.RetryAfter,
          motionRetryAfterSeconds(outcome).toString(),
        )
        call.respondText(outcome.summary, status = HttpStatusCode.ServiceUnavailable)
      } else {
        call.respondText("", status = HttpStatusCode.NotFound)
      }
      return
    }
    // Revalidated, not `immutable`: unlike other users of [prebakedImageCacheControl], a capture's
    // URL derives from its sticker path, so a re-publish replaces bytes at the same URL. The ETag
    // makes revalidation cheap (304s save a lot on many-frame captures).
    val etag = "\"" + motionEtag(bytes) + "\""
    call.response.headers.append(
      HttpHeaders.CacheControl,
      if (isPublic) MOTION_CACHE_CONTROL else DYNAMIC_RESOURCE_CACHE_CONTROL,
    )
    call.response.headers.append(HttpHeaders.ETag, etag)
    if (call.request.headers[HttpHeaders.IfNoneMatch] == etag) {
      call.respond(HttpStatusCode.NotModified)
      return
    }
    call.respondBytes(bytes, ContentType.parse(MOTION_CONTENT_TYPES.getValue(extension)))
  }

  /**
   * The `Retry-After` for a refused capture: the branch host's own value, else a short default,
   * clamped to the fetch policy's ceiling.
   */
  private fun motionRetryAfterSeconds(outcome: BranchFetch): Long {
    val asked =
      when (outcome) {
        is BranchFetch.Throttled -> outcome.retryAfterSeconds
        is BranchFetch.Unavailable -> outcome.retryAfterSeconds
        else -> null
      }
    return (asked ?: MOTION_DEFAULT_RETRY_AFTER_SECONDS).coerceIn(
      1L,
      BranchFetch.MAX_RETRY_AFTER_SECONDS,
    )
  }

  private suspend fun RoutingContext.rejectHeadProbe(): Boolean {
    if (call.request.local.method != HttpMethod.Head) return false
    call.response.headers.append(HttpHeaders.Allow, HttpMethod.Get.value)
    call.respondText("", status = HttpStatusCode.MethodNotAllowed)
    return true
  }

  private fun ApplicationCall.requestIsSignedIn(): Boolean = githubAuth?.currentLogin(this) != null

  private fun RoutingContext.requestIsSignedIn(): Boolean = call.requestIsSignedIn()

  private fun ApplicationCall.pageCacheControl(): String =
    pageCacheControl(
      githubAuthConfigured = githubAuth != null,
      isPublic = isPublic,
      signedIn = requestIsSignedIn(),
    )

  private fun RoutingContext.pageCacheControl(): String = call.pageCacheControl()

  /**
   * The major sections of a design page for the sidebar's Pages tree: its Figma `COMPONENT_SET`s,
   * not the hundreds of concrete components.
   *
   * Third-party data, so:
   * - unnamed sets are dropped (blank `name` can't be chosen);
   * - capped, since a manifest may carry up to `MAX_NODES_PER_PAGE` nodes; past the cap the page
   *   row still leads to the whole sheet.
   */
  private fun designPageSections(page: DesignPage): List<ServeWeb.PageSection> =
    page.nodes
      .asSequence()
      .filter { it.isContainer && it.name.isNotBlank() }
      .map { ServeWeb.PageSection(it.nodeId, it.name) }
      .take(MAX_PAGE_SECTIONS)
      .toList()

  private fun prebakedImageCacheControl(): String = prebakedImageCacheControl(isPublic)

  /**
   * SVG lane of [handleRender]: load-shed like the PNG lane, then respond the figma-svg bytes; with
   * [scroll], the full-page (`compose/figma-svg-long`) export.
   */
  /**
   * The exploded-view options this request asks for, or null. Read off the raw query because, like
   * `mode=web`, they describe presentation, so they must not affect override cache identity or be
   * reported as dropped.
   */
  private fun RoutingContext.explodedOptions(): ExplodedSvg.Options? {
    val params = { key: String -> renderParams()[key] }
    return if (ServeExplodedSvg.enabled(params)) ServeExplodedSvg.optionsFrom(params) else null
  }

  private suspend fun RoutingContext.renderSvgResponse(
    renderHost: ServeHost,
    previewId: String,
    overrides: PreviewOverrides,
    scroll: Boolean = false,
    webMode: Boolean = false,
    exploded: ExplodedSvg.Options? = null,
  ) {
    val outcome =
      withContext(Dispatchers.IO) {
        if (!renderSemaphore.tryAcquire(RENDER_QUEUE_WAIT_SECONDS, TimeUnit.SECONDS)) {
          null
        } else {
          try {
            val produced =
              when {
                scroll -> renderHost.renderScrollSvg(previewId, overrides)
                // Web mode uses the host's web variant: a catalog host links raster crops to
                // published branch files rather than embedding them. The font `@import` rewrite
                // applies either way.
                webMode -> renderHost.renderSvgForWeb(previewId, overrides)
                else -> renderHost.renderSvg(previewId, overrides)
              }
            // Both rewrites run with the permit held: the exploded projection parses, copies and
            // re-serializes the SVG, comparable to a render, so it must queue and shed like one.
            if (produced is SvgOutcome.Ok && (webMode || exploded != null)) {
              var text = produced.svg.toString(Charsets.UTF_8)
              if (webMode) text = webModeSvg(text)
              if (exploded != null) text = ExplodedSvg.render(text, exploded)
              produced.copy(svg = text.toByteArray(Charsets.UTF_8))
            } else produced
          } finally {
            renderSemaphore.release()
          }
        }
      }
    when (outcome) {
      null -> {
        call.response.headers.append(HttpHeaders.RetryAfter, "2")
        call.respondText(
          "render queue saturated; retry shortly",
          status = HttpStatusCode.ServiceUnavailable,
        )
      }
      is SvgOutcome.Ok -> {
        // The host renders and caches the self-contained SVG; web and exploded variants are
        // per-response rewrites, already applied above in that order (web first, since it rewrites
        // the `@font-face` block the exploded view carries through).
        val svg = outcome.svg
        val contentType = ContentType.parse(ComposeFigmaSvgProduct.MEDIA_TYPE_SVG)
        // The vector lane drops overrides like the PNG one: a branch `figma/<slug>.svg` was drawn
        // at discovery-time axes. `?scroll=long` is daemon-only, so never baked here.
        val dropped = droppedOverridesFor(renderHost, outcome.generation, previewId, overrides)
        if (dropped.isNotEmpty()) {
          respondDroppedOverrides(
            renderHost,
            previewId,
            dropped,
            overrides,
            svg,
            contentType,
            outcome.generation,
          )
        } else {
          markGeneration(outcome.generation.wire)
          call.respondBytes(svg, contentType)
        }
      }
      SvgOutcome.NotFound -> call.respondText("no such preview", status = HttpStatusCode.NotFound)
      is SvgOutcome.Failed ->
        call.respondText(outcome.reason, status = HttpStatusCode.InternalServerError)
    }
  }

  /** Slots lane of [handleRender]: load-shed like the PNG lane, then respond the slots JSON. */
  private suspend fun RoutingContext.renderSlotsResponse(
    renderHost: ServeHost,
    previewId: String,
    overrides: PreviewOverrides,
  ) {
    val outcome =
      withContext(Dispatchers.IO) {
        if (!renderSemaphore.tryAcquire(RENDER_QUEUE_WAIT_SECONDS, TimeUnit.SECONDS)) {
          null
        } else {
          try {
            renderHost.renderSlots(previewId, overrides)
          } finally {
            renderSemaphore.release()
          }
        }
      }
    when (outcome) {
      null -> {
        call.response.headers.append(HttpHeaders.RetryAfter, "2")
        call.respondText(
          "render queue saturated; retry shortly",
          status = HttpStatusCode.ServiceUnavailable,
        )
      }
      is SlotsOutcome.Ok -> call.respondBytes(outcome.json, ContentType.Application.Json)
      SlotsOutcome.NotFound -> call.respondText("no such preview", status = HttpStatusCode.NotFound)
      is SlotsOutcome.Failed ->
        call.respondText(outcome.reason, status = HttpStatusCode.InternalServerError)
    }
  }

  /**
   * Accessibility lane of [handleRender]: respond the merged `a11y/hierarchy` + `a11y/atf` +
   * `a11y/touchTargets` JSON. Like slots it may force an `a11y`-mode daemon render, so it uses
   * render admission.
   */
  private suspend fun RoutingContext.renderA11yResponse(
    renderHost: ServeHost,
    previewId: String,
    overrides: PreviewOverrides,
  ) {
    val outcome =
      withContext(Dispatchers.IO) {
        if (!renderSemaphore.tryAcquire(RENDER_QUEUE_WAIT_SECONDS, TimeUnit.SECONDS)) {
          null
        } else {
          try {
            renderHost.renderA11y(previewId, overrides)
          } finally {
            renderSemaphore.release()
          }
        }
      }
    when (outcome) {
      null -> {
        call.response.headers.append(HttpHeaders.RetryAfter, "2")
        call.respondText(
          "render queue saturated; retry shortly",
          status = HttpStatusCode.ServiceUnavailable,
        )
      }
      is A11yOutcome.Ok -> respondInspectionJson(renderHost, outcome.json)
      A11yOutcome.NotFound -> call.respondText("no such preview", status = HttpStatusCode.NotFound)
      is A11yOutcome.Failed ->
        call.respondText(outcome.reason, status = HttpStatusCode.InternalServerError)
    }
  }

  /**
   * Respond one inspection payload (`<id>.a11y` or `<id>.annotations`) with the route's validators
   * and lifetime, since `cp-inspect-layers` only caches per page.
   *
   * A strong `ETag` always (payloads are small and deterministic). Lifetime follows the raster
   * lanes:
   * - overrides: made-to-order, [DYNAMIC_RESOURCE_CACHE_CONTROL];
   * - naming the generation on disk: content-addressed, `immutable` ([carriesCurrentGeneration],
   *   [prebakedImageCacheControl]);
   * - otherwise the short public lifetime with `stale-while-revalidate`
   *   ([STATIC_RESOURCE_CACHE_CONTROL]).
   *
   * A token-gated box never caches any of it.
   */
  private suspend fun RoutingContext.respondInspectionJson(renderHost: ServeHost, json: ByteArray) {
    val etag = contentEtag(json)
    markGeneration(
      "inspection",
      when {
        !isPublic || requestCarriesOverrides() -> DYNAMIC_RESOURCE_CACHE_CONTROL
        carriesCurrentGeneration(renderHost) -> prebakedImageCacheControl(isPublic)
        else -> STATIC_RESOURCE_CACHE_CONTROL
      },
    )
    call.response.headers.append(HttpHeaders.ETag, etag)
    if (call.request.headers[HttpHeaders.IfNoneMatch] == etag) {
      call.respond(HttpStatusCode.NotModified)
      return
    }
    call.respondBytes(json, ContentType.Application.Json)
  }

  /**
   * Design-annotation lane of [handleRender]: load-shed like the PNG lane, then respond the
   * typography + theme layers.
   */
  /**
   * The inspect layers this `.annotations` request draws (`?layers=typography,theme`), or null for
   * all. Not an override param ([ServeOverrides.isOverrideParam] is an allowlist): it selects
   * projections of one frame, so it must not make a published replay `no-store`; the content ETag
   * covers the body change.
   */
  private fun RoutingContext.requestedInspectLayers(): Set<String>? =
    AnnotationKind.parseLayers(renderParams()["layers"])

  private suspend fun RoutingContext.renderAnnotationsResponse(
    renderHost: ServeHost,
    previewId: String,
    overrides: PreviewOverrides,
    layers: Set<String>?,
  ) {
    val outcome =
      withContext(Dispatchers.IO) {
        if (!renderSemaphore.tryAcquire(RENDER_QUEUE_WAIT_SECONDS, TimeUnit.SECONDS)) {
          null
        } else {
          try {
            renderHost.renderAnnotations(previewId, overrides, layers)
          } finally {
            renderSemaphore.release()
          }
        }
      }
    when (outcome) {
      null -> {
        call.response.headers.append(HttpHeaders.RetryAfter, "2")
        call.respondText(
          "render queue saturated; retry shortly",
          status = HttpStatusCode.ServiceUnavailable,
        )
      }
      is AnnotationsOutcome.Ok -> respondInspectionJson(renderHost, outcome.json)
      AnnotationsOutcome.NotFound ->
        call.respondText("no such preview", status = HttpStatusCode.NotFound)
      is AnnotationsOutcome.Failed ->
        call.respondText(outcome.reason, status = HttpStatusCode.InternalServerError)
    }
  }

  /**
   * The persistent frame lane for `WS /ws/{name}` (`?session=`) and `WS /{system}/ws/{name}`. The
   * token is checked post-handshake; a bad one closes immediately.
   */
  private suspend fun DefaultWebSocketServerSession.serveStreamLane() {
    if (!call.isAuthorizedCall()) {
      close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "unauthorized"))
      return
    }
    // Same rule as [rejectMissingGithubAuth], restated because a socket can't redirect to sign-in;
    // a presented grant is judged on its scope regardless of GitHub auth.
    val socketGrant = agentGrantFor(call)
    if (socketGrant != null) {
      if (!socketGrant.allows(AgentGrantScope.LIVE)) {
        close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "live not approved for this grant"))
        return
      }
    } else if (githubAuth?.isAuthenticated(call) == false) {
      close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "github auth required"))
      return
    }
    val sessionId =
      call.parameters["system"]
        // Same precedence as [selectedSessionId]: on a site host the origin decides, so a
        // `?session=` on the socket URL can't stream a catalog the site doesn't publish.
        ?: call.siteSystem()
        ?: call.request.queryParameters["session"]
        ?: defaultSessionId
    // Reserve live-seat permits before leasing, since leasing resumes the host and spawns the
    // daemon the budget bounds. Static sessions take weight 0; daemon-backed ones their backend
    // weight from session state; lazily-forked unregistered sessions (e.g. --revisions) default to
    // 1.
    val weight = if (sessions.isKnownStatic(sessionId)) 0 else sessions.liveSeatWeight(sessionId)
    // A refusal for an unknown session is counted separately: not evidence of demand, but
    // `--revisions` sessions are legitimately unknown until built.
    val seatTicket = liveSeats.acquire(weight, verified = sessions.isKnownSession(sessionId))
    if (seatTicket == null) {
      close(CloseReason(1013.toShort(), "live preview at capacity — try again shortly"))
      return
    }
    try {
      // Lease the tenant for the socket's life, since a fallback-lane socket opens no stream and
      // the reaper could close its host. `connection = true` makes this lease count as busy only on
      // activity; request-scoped leases count as busy until released.
      val lease = withContext(Dispatchers.IO) { sessions.lease(sessionId, connection = true) }
      if (lease == null) {
        close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "no such session"))
        return
      }
      try {
        val renderHost = lease.host
        val previewId = call.parameters["name"]
        if (previewId == null || renderHost.previews.none { it.id == previewId }) {
          close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "no such preview"))
          return
        }
        val initialOverrides =
          call.request.queryParameters
            .entries()
            .mapNotNull { (key, values) ->
              val value = values.firstOrNull() ?: return@mapNotNull null
              if (ServeOverrides.isOverrideParam(key)) key to value else null
            }
            .toMap()
            .let { ServeWeb.SystemDisplay.normalizeOverrideParams(sessionId, it) }
        // Non-suspending hand-off to the socket; drop frames a slow client can't keep up with.
        val send: (String) -> Unit = { text -> outgoing.trySend(Frame.Text(text)) }
        // Optional stream tuning: codec (WebP is ~30–60% smaller; the daemon falls back to PNG) and
        // an fps cap.
        val codec =
          when (call.request.queryParameters["codec"]?.lowercase()) {
            "webp" -> StreamCodec.WEBP
            "png" -> StreamCodec.PNG
            else -> null
          }
        val maxFps = call.request.queryParameters["maxFps"]?.toIntOrNull()?.takeIf { it > 0 }
        // Prefer the daemon's live stream lane; fall back to the snapshot re-render lane when
        // streaming isn't supported, keeping the live lane's failure to explain why input isn't
        // live. The callback fires synchronously inside tryStart, so a plain var is safe.
        var liveUnavailableReason: String? = null
        val live =
          withContext(Dispatchers.IO) {
            ServeLiveSession.tryStart(
              renderHost,
              previewId,
              initialOverrides,
              codec,
              maxFps,
              send,
              system = sessionId,
              frameStats = liveFrameStats,
            ) { reason ->
              if (liveUnavailableReason == null) liveUnavailableReason = reason
            }
          }
        if (live != null) {
          try {
            for (frame in incoming) {
              if (frame is Frame.Text) {
                val text = frame.readText()
                // The lease keeps the session resident; this marks it as in use, so an untouched
                // tab doesn't hold the optimizer's quiet gate shut forever.
                lease.touch()
                withContext(Dispatchers.IO) { live.onClientMessage(text) }
              }
            }
          } finally {
            // `NonCancellable`: the socket dying cancels this coroutine, and a plain `withContext`
            // would skip the close. A leaked stream keeps `activeStreamCount()` above zero, pinning
            // the daemon and its live seat. Unlike `Lease.close` this blocks, so it stays on IO.
            withContext(Dispatchers.IO + NonCancellable) { live.close() }
          }
        } else {
          val session =
            ServeStreamSession(
              renderHost,
              previewId,
              initialOverrides,
              send,
              system = sessionId,
              liveUnavailableReason = liveUnavailableReason,
            )
          // Renders block (renderNow + await); keep them off the socket's event-loop thread.
          withContext(Dispatchers.IO) { session.onOpen() }
          for (frame in incoming) {
            if (frame is Frame.Text) {
              val text = frame.readText()
              lease.touch() // see the live lane above
              withContext(Dispatchers.IO) { session.onClientMessage(text) }
            }
          }
        }
      } finally {
        lease.close()
      }
    } finally {
      seatTicket.close()
    }
  }

  /**
   * Token gate: respond 404 (not 401, to avoid confirming the server) and return true when neither
   * `?token=` / `X-Compose-Preview-Token` matches nor a browse cookie ([ServeBrowseCookie]) minted
   * from the token is present. Constant-time compare.
   *
   * A live agent grant ([ServeAgentGrantStore]) presented in the same place also passes, at
   * [AgentGrantScope.PREVIEW].
   */
  private suspend fun RoutingContext.rejectBadToken(): Boolean {
    if (callIsAuthorized()) return false
    call.respondText("not found", status = HttpStatusCode.NotFound)
    return true
  }

  /**
   * Whether this call may see the server at all (operator token, public box, or live grant). Shared
   * with the site-404 interceptor.
   */
  private fun RoutingContext.callIsAuthorized(): Boolean = call.isAuthorizedCall()

  private fun ApplicationCall.isAuthorizedCall(): Boolean {
    val provided = request.queryParameters["token"] ?: request.headers[TOKEN_HEADER]
    if (isAuthorized(serverToken, provided, isPublic)) return true
    if (browsesByCookie()) return true
    return agentGrantFor(this)?.allows(AgentGrantScope.PREVIEW) == true
  }

  /**
   * The operator's browse token, named for its two legitimate uses: an authorisation compare, or
   * deliberately embedding the operator's credential. Pages almost always want [linkToken] instead.
   */
  private val serverToken: String = token

  /**
   * The credential to thread into the links, form actions and asset `src`s of this request's page.
   *
   * On a gated server links carry `?token=`, so a grant holder loading any page would otherwise
   * read the operator's permanent credential from the markup. A live-grant caller gets links with
   * their own grant token; once exchanged for [ServeAgentGrantCookie], clean links. Everyone else
   * gets [serverToken]. `--public` puts no operator token in links.
   *
   * See [isAuthorizedAccessParam] for the path-segment form, which accepts the same two answers.
   */
  private fun RoutingContext.linkToken(): String = call.linkToken()

  private fun ApplicationCall.linkToken(): String {
    val grant = agentGrantFor(this)
    return when {
      grant != null && browsesByAgentGrantCookie(grant) -> ""
      grant != null -> grant.token
      browsesByCookie() -> ""
      else -> serverToken
    }
  }

  /**
   * Whether this call presents the browse cookie ([ServeBrowseCookie]); such a browser needs no
   * token in links, so [linkToken] answers empty.
   */
  private fun ApplicationCall.browsesByCookie(): Boolean =
    !isPublic && ServeBrowseCookie.presents(this, serverToken)

  /** Whether this call already speaks with the operator's standing authority. */
  private fun ApplicationCall.presentsOperatorCredential(): Boolean {
    if (serverToken.isBlank()) return false
    if (browsesByCookie()) return true
    return sequenceOf(
        request.headers[TOKEN_HEADER],
        request.queryParameters["token"],
      )
      .filterNotNull()
      .any { ServeUrls.tokensMatch(serverToken, it) }
  }

  /** Whether this call's resolved grant came from the short-lived browser cookie. */
  private fun ApplicationCall.browsesByAgentGrantCookie(
    grant: ServeAgentGrantStore.Grant
  ): Boolean =
    agentGrants?.browserCredentialMatches(
      request.cookies.rawCookies[ServeAgentGrantCookie.NAME],
      grant,
    ) == true

  /** Whether the links of the page being built carry `token=` — see [linkToken]. */
  private fun RoutingContext.linksCarryToken(): Boolean = !isPublic && linkToken().isNotEmpty()

  /**
   * The `{access}` segment of a `/wasm-private/…` URL on this call's page: the caller's grant token
   * as [linkToken] gives it, else a value derived from (never equal to) the operator token.
   */
  private fun RoutingContext.wasmPrivateAccess(): String {
    val grant = agentGrantFor(call)
    return when {
      grant != null && call.browsesByAgentGrantCookie(grant) ->
        agentGrants?.wasmCredentialFor(grant) ?: grant.token
      grant != null -> grant.token
      else -> ServeBrowseCookie.wasmAccess(serverToken)
    }
  }

  /**
   * Whether a path-segment credential (`/wasm-private/{access}/…`) is accepted: the operator's
   * token or a live grant reaching [AgentGrantScope.PREVIEW], matching [linkToken].
   */
  private fun isAuthorizedAccessParam(call: ApplicationCall, value: String?): Boolean =
    ServeUrls.tokensMatch(ServeBrowseCookie.wasmAccess(serverToken), value) ||
      ServeUrls.tokensMatch(serverToken, value) ||
      agentGrants?.grantForToken(value)?.allows(AgentGrantScope.PREVIEW) == true ||
      agentGrants?.grantForWasmCredential(value)?.allows(AgentGrantScope.PREVIEW) == true

  /**
   * The live grant this call presents, or null — resolved once per request and remembered.
   *
   * Every gate asks independently, and they fail in opposite directions: the token gate refuses an
   * absent grant, while a scope gate reads absence as "nothing to say". Resolving per gate let a
   * grant expiring between gates widen the request. Resolving once means a grant revoked
   * mid-request is honoured for that request (the usual contract); the live socket resolves once at
   * setup ([socketGrant]).
   *
   * Reads the token's two locations plus `Authorization: Bearer` and the derived HttpOnly browser
   * cookie, tried last so an explicit grant decides.
   */
  private fun agentGrantFor(call: ApplicationCall): ServeAgentGrantStore.Grant? =
    call.attributes
      .computeIfAbsent(RESOLVED_AGENT_GRANT) { ResolvedAgentGrant(resolveAgentGrant(call)) }
      .grant

  /** Boxed so the memo can remember "resolved to nothing" as distinct from "not yet resolved". */
  private class ResolvedAgentGrant(val grant: ServeAgentGrantStore.Grant?)

  /**
   * One request's cross-catalog pairings ([resolveParallel]), keyed by host identity since a
   * request may hold several hosts.
   */
  private data class ParallelPairingKey(val host: ServeHost, val previewId: String)

  private class ParallelPairings {
    val pairings = HashMap<ParallelPairingKey, ResolvedParallel?>()

    /** [host]'s previews by component id, built once per sibling catalog per request. */
    fun candidatesOf(system: String, host: ServeHost): Map<String, List<ServePreview>> =
      candidates.getOrPut(system) {
        host.previews.groupBy { it.componentId.orEmpty() }.filterKeys { it.isNotEmpty() }
      }

    private val candidates = HashMap<String, Map<String, List<ServePreview>>>()
  }

  /**
   * One request's inverse `related` indexes (which catalog points at this component, and from which
   * of its components), memoised per `ApplicationCall` since building one walks a whole catalog's
   * declarations.
   */
  private class RelatedInverses {
    private val indexes = HashMap<Pair<String, String>, Map<String, List<String>>>()

    /** [source]'s declarations, inverted onto [targetSystem]'s component ids. */
    fun of(
      sourceSystem: String,
      entries: Map<String, List<ServeRelatedCatalogs.Declared>>,
      targetSystem: String,
    ): Map<String, List<String>> =
      indexes.getOrPut(sourceSystem to targetSystem) {
        ServeRelatedCatalogs.inverse(entries, targetSystem)
      }
  }

  /**
   * The page a grant link gets when this browser already acts as a different live grant. `Keep` is
   * the clean URL; `Switch` is a same-origin POST to [ServeAgentGrants.SWITCH_PATH]. The bearer
   * goes only into this `no-store` page's form, never a link.
   */
  private suspend fun respondAgentGrantSwitchConfirmation(
    call: ApplicationCall,
    current: ServeAgentGrantStore.Grant,
    exchange: ServeAgentGrantCookie.Exchange,
  ) {
    val skin = call.siteSkin()
    call.response.headers.append(HttpHeaders.CacheControl, "no-store")
    call.response.headers.append("Referrer-Policy", "no-referrer")
    call.respondText(
      ServeWeb.agentGrantSwitchPage(
        currentLabel = current.label,
        currentFingerprint = current.fingerprint,
        nextLabel = exchange.grant.label,
        nextFingerprint = exchange.grant.fingerprint,
        formAction = ServeAgentGrants.SWITCH_PATH,
        token = exchange.grant.token,
        target = exchange.target,
        version = SERVE_VERSION,
        siteName = skin.first,
        themeCss = skin.second,
      ),
      ContentType.Text.Html,
      HttpStatusCode.OK,
    )
  }

  /**
   * `POST /agent-access/switch`: replace this browser's grant. Only from this server's page, only
   * for a live grant, never over a human identity.
   */
  private suspend fun RoutingContext.handleAgentGrantSwitch(store: ServeAgentGrantStore) {
    call.response.headers.append(HttpHeaders.CacheControl, "no-store")
    val sameOrigin =
      call.request.headers["Sec-Fetch-Site"] == "same-origin" ||
        (call.request.headers[HttpHeaders.Origin] != null &&
          ServeSameOriginRequests.isSameOrigin(call, browserHosts))
    if (!sameOrigin) {
      call.respond(HttpStatusCode.Forbidden)
      return
    }
    val form = call.receiveParameters()
    val target = ServeAgentGrantCookie.safeLocalTarget(form["next"])
    val humanPresent =
      call.presentsOperatorCredential() || githubAuth?.currentSignedInLogin(call) != null
    val grant = store.grantForToken(form["token"])
    val credential = grant?.let(store::browserCredentialFor)
    if (humanPresent || credential == null) {
      call.respond(HttpStatusCode.Forbidden)
      return
    }
    call.response.cookies.append(ServeAgentGrantCookie.cookie(credential, secure = isSecure(call)))
    call.response.headers.append(HttpHeaders.Location, target)
    call.respond(HttpStatusCode.SeeOther)
  }

  private fun resolveAgentGrant(call: ApplicationCall): ServeAgentGrantStore.Grant? {
    val store = agentGrants ?: return null
    // An ambient browser grant must not reduce a request that also carries the operator's
    // credential, or scope gates would narrow operator authority to an old grant.
    if (call.presentsOperatorCredential()) return null
    val bearer =
      call.request.headers[HttpHeaders.Authorization]
        ?.takeIf { it.startsWith(BEARER_PREFIX, ignoreCase = true) }
        ?.substring(BEARER_PREFIX.length)
        ?.trim()
    // Each source is resolved independently rather than by precedence, so an unrelated
    // `Authorization: Bearer` (e.g. `share-preview --mechanism serve`'s GitHub token) can't shadow
    // a grant in `?token=`. The first that resolves to a live grant wins.
    val explicitlyPresented =
      sequenceOf(
          call.request.headers[TOKEN_HEADER],
          bearer,
          call.request.queryParameters["token"],
        )
        .firstNotNullOfOrNull { store.grantForToken(it) }
    if (explicitlyPresented != null) return explicitlyPresented
    // A signed-in browser identity outranks an ambient grant cookie; an explicit cpat above still
    // wins.
    if (githubAuth?.currentSignedInLogin(call) != null) return null
    return ServeAgentGrantCookie.grant(call, store, browserHosts)
  }

  /**
   * The token gate for the ingest lanes (`POST /bundles/{name}`, `POST /docs`): [rejectBadToken]
   * except that agent grants never satisfy it. A `preview` grant means browsing, not publishing
   * content, so these lanes stay outside the grant system (the image lane requires a GitHub
   * credential for its own reasons).
   */
  private suspend fun RoutingContext.rejectBadTokenForIngest(): Boolean {
    val provided = call.request.queryParameters["token"] ?: call.request.headers[TOKEN_HEADER]
    val operator = isAuthorized(serverToken, provided, isPublic) || call.browsesByCookie()
    if (operator && agentGrantFor(call) == null) return false
    call.respondText("not found", status = HttpStatusCode.NotFound)
    return true
  }

  private suspend fun RoutingContext.handleWasmAsset(privateRoute: Boolean) {
    val system = call.parameters["system"]

    // The first packaged frontend was registered as a fake `preview-ui` catalog; keep old URLs
    // working but redirect to the canonical catalog-in-path form.
    if (!privateRoute && system == LEGACY_WASM_UI_SYSTEM) {
      val targetSystem =
        call.request.queryParameters["session"]?.takeIf(sessions::isKnownSession)
          ?: defaultSessionId
      val query =
        call.request.queryParameters
          .entries()
          .flatMap { (key, values) ->
            if (key == "session") emptyList()
            else
              values.map { value ->
                "${WebEscaping.urlEncodeSegment(key)}=${WebEscaping.urlEncodeSegment(value)}"
              }
          }
          .joinToString("&")
      val target =
        "/wasm/${WebEscaping.urlEncodeSegment(targetSystem)}/" +
          query.takeIf { it.isNotEmpty() }?.let { "?$it" }.orEmpty()
      call.respondRedirect(target)
      return
    }
    val privateSystem = system in privateWasmCatalogs
    if (
      (privateRoute &&
        (!privateSystem || !isAuthorizedAccessParam(call, call.parameters["access"]))) ||
        (!privateRoute && privateSystem && !isPublic)
    ) {
      call.respondText("not found", status = HttpStatusCode.NotFound)
      return
    }
    // A site hostname serves ONE catalog's app. These constant-prefix routes bypass canonical-path
    // isolation, so enforce the same one-catalog projection here.
    val site = call.siteSystem()
    val catalogApp = if (site != null && system != site) null else system?.let(wasmCatalogs::get)
    val dir =
      if (site != null && system != site) null
      else
        catalogApp
          ?: system?.let { selected -> wasmUiDir?.takeIf { sessions.isKnownSession(selected) } }
    if (dir == null) {
      call.respondText("not found", status = HttpStatusCode.NotFound)
      return
    }
    val segments = call.parameters.getAll("path").orEmpty().filter { it.isNotEmpty() }
    val rel = if (segments.isEmpty()) "index.html" else segments.joinToString("/")
    val base = dir.toPath().toAbsolutePath().normalize()
    val resolved = base.resolve(rel).normalize()
    if (!resolved.startsWith(base) || !resolved.toFile().isFile) {
      call.respondText("not found", status = HttpStatusCode.NotFound)
      return
    }
    val file = resolved.toFile()
    // A catalog's own app is that producer's code and is sandboxed when opened top-level; the
    // packaged frontend this distribution ships is not ([ServePagePolicy.sandboxFor]).
    if (catalogApp == null) ServePagePolicy.markServerOwned(call)
    // The sandboxed iframe has an opaque origin, so its ES-module and Wasm requests require CORS.
    call.response.headers.append(HttpHeaders.AccessControlAllowOrigin, "*")
    val etag = "\"${file.length().toString(16)}-${file.lastModified().toString(16)}\""
    call.response.headers.append(
      HttpHeaders.CacheControl,
      // The access segment can expire or be revoked while the bytes stay the same. A private cache
      // may retain them, but it must revalidate through the authorization gate before reuse.
      if (privateRoute) "private, no-cache" else "public, max-age=3600",
    )
    call.response.headers.append(HttpHeaders.ETag, etag)
    if (call.request.headers[HttpHeaders.IfNoneMatch] == etag) {
      call.respond(HttpStatusCode.NotModified)
      return
    }
    val bytes = withContext(Dispatchers.IO) { file.readBytes() }
    call.respondBytes(bytes, wasmContentType(file.name))
  }

  /**
   * Whether a path segment can name a design rather than an asset: the New design dialog's
   * path-safe shape (which allows `.`), minus static-asset extensions, so `uiBuilder.mjs` typos 404
   * instead of returning HTML. Leading alphanumeric, so `..` never reaches the shell.
   */
  private fun isUiBuilderDesignSegment(segment: String): Boolean =
    UI_BUILDER_DESIGN_SEGMENT.matches(segment) &&
      (!segment.contains('.') ||
        segment.substringAfterLast('.').lowercase() !in UI_BUILDER_ASSET_EXTENSIONS)

  private suspend fun RoutingContext.handleUiBuilderAsset() {
    val dir = uiBuilderDir
    if (dir == null) {
      call.respondText("not found", status = HttpStatusCode.NotFound)
      return
    }
    serveUiBuilderPath(dir, call.parameters.getAll("path").orEmpty().filter { it.isNotEmpty() })
  }

  /** `GET /ui-builder/v/{version}/{path...}`: the same bundle under an immutable prefix. */
  private suspend fun RoutingContext.handleUiBuilderVersionedAsset() {
    val dir = uiBuilderDir
    if (dir == null) {
      call.respondText("not found", status = HttpStatusCode.NotFound)
      return
    }
    val version = call.parameters["version"].orEmpty()
    val rest = call.parameters.getAll("path").orEmpty().filter { it.isNotEmpty() }
    // A catalog with id `v` would be shadowed by this route, so the catalog wins and just gets no
    // versioned URLs.
    if (UI_BUILDER_VERSION_SEGMENT in uiBuilderCatalogs) {
      serveUiBuilderPath(dir, listOf(UI_BUILDER_VERSION_SEGMENT, version) + rest)
      return
    }
    serveUiBuilderPath(dir, rest, version = version)
  }

  /**
   * One bundle, reachable unversioned or under a content prefix.
   *
   * The prefix goes on the directory because the Kotlin/Wasm build's relative references
   * (`index.html` → `uiBuilder.mjs` → `skiko.wasm`, `uiBuilder.wasm`) can't be rewritten;
   * `/ui-builder/v/<digest>/` carries them all.
   *
   * Null [version] means the unversioned path: the entry document redirects to the current prefix
   * (the one revalidated response a rollout changes), while other files are served uncached so held
   * URLs keep working.
   */
  private suspend fun RoutingContext.serveUiBuilderPath(
    dir: File,
    segments: List<String>,
    version: String? = null,
  ) {
    val scopedCatalog = segments.firstOrNull()?.takeIf(uiBuilderCatalogs::contains)
    if (scopedCatalog != null && segments.size == 1 && !call.request.path().endsWith("/")) {
      call.respondRedirect(uiBuilderPagePath(call, "$scopedCatalog/"))
      return
    }
    val assetSegments = if (scopedCatalog == null) segments else segments.drop(1)
    if (version != null) {
      // The versioned prefix carries assets only: the app reads the design id from
      // `location.pathname`, so the document keeps its URL.
      // Only one bundle is on disk, so a prefix naming another version 404s rather than making an
      // immutable URL serve two different files.
      if (assetSegments.isEmpty() || version != uiBuilderBundleVersion) {
        call.respondText("not found", status = HttpStatusCode.NotFound)
        return
      }
    } else if (
      scopedCatalog != null && assetSegments.size == 1 && isUiBuilderDesignSegment(assetSegments[0])
    ) {
      // An old catalog-prefixed permalink. The document now owns its catalog pin, so keep the old
      // address only as a temporary redirect to the catalog-free identity.
      val designId = assetSegments[0]
      if (!File(dir, designId).isFile) {
        val suffix = call.request.queryString().let { if (it.isEmpty()) "" else "?$it" }
        call.respondRedirect(uiBuilderPagePath(call, "$designId$suffix"))
        return
      }
    } else if (assetSegments.size == 1 && isUiBuilderDesignSegment(assetSegments[0])) {
      // The canonical design URL. The shell is not design data, so it is served for every
      // path-shaped id; this keeps the create POST/303/GET handoff independent of how the
      // credential is presented and avoids an existence oracle. The design API decides readability.
      if (!File(dir, assetSegments[0]).isFile) {
        if (call.request.path().endsWith("/")) {
          val suffix = call.request.queryString().let { if (it.isEmpty()) "" else "?$it" }
          call.respondRedirect(uiBuilderPagePath(call, "${assetSegments[0]}$suffix"))
        } else {
          respondUiBuilderShell(dir, File(dir, "index.html"), designId = assetSegments[0])
        }
        return
      }
    }
    val rel = if (assetSegments.isEmpty()) "index.html" else assetSegments.joinToString("/")
    val base = dir.canonicalFile.toPath()
    val file = File(dir, rel).canonicalFile
    if (!file.toPath().startsWith(base) || !file.isFile) {
      call.respondText("not found", status = HttpStatusCode.NotFound)
      return
    }
    // The builder's service worker, with its own headers: revalidated on every navigation, never
    // immutable, and allowed the whole `/ui-builder/` scope only if the server says so.
    if (assetSegments == listOf(UI_BUILDER_SERVICE_WORKER)) {
      // Only at `/ui-builder/ui-builder-sw.js`: the worker's URL is its identity across releases,
      // so a copy elsewhere would register a second worker.
      if (version != null || scopedCatalog != null) {
        call.respondText("not found", status = HttpStatusCode.NotFound)
      } else {
        respondUiBuilderServiceWorker(file)
      }
      return
    }
    // One door to the shell, so the reference rewrite cannot be reached around.
    if (version == null && rel == "index.html") {
      respondUiBuilderShell(dir, file)
      return
    }
    // The gzip copy when accepted and ready, else the file itself. See
    // [UiBuilderPrecompressedAssets].
    val gzipped =
      if (
        UiBuilderPrecompressedAssets.acceptsGzip(call.request.headers[HttpHeaders.AcceptEncoding])
      )
        uiBuilderPrecompressed.ready(file)
      else null
    val identity = "${file.length().toString(16)}-${file.lastModified().toString(16)}"
    // Each representation its own validator: a cache holding the gzip bytes must never have them
    // confirmed as current by a 304 meant for the plain ones.
    val etag = if (gzipped == null) "\"$identity\"" else "\"$identity-gzip\""
    call.response.headers.append(
      HttpHeaders.CacheControl,
      if (version == null) "no-cache" else UI_BUILDER_IMMUTABLE_CACHE_CONTROL,
    )
    call.response.headers.append(HttpHeaders.ETag, etag)
    if (uiBuilderPrecompressed.compressible(file)) {
      call.response.headers.append(HttpHeaders.Vary, HttpHeaders.AcceptEncoding)
    }
    if (call.request.headers[HttpHeaders.IfNoneMatch] == etag) {
      call.respond(HttpStatusCode.NotModified)
      return
    }
    // Streamed from disk rather than read into a heap array: `uiBuilder.wasm` alone is tens of
    // megabytes, and cold-load bursts would hold one copy per request.
    if (gzipped != null) {
      call.respond(GzipEncodedContent(LocalFileContent(gzipped, wasmContentType(file.name))))
    } else {
      call.respond(LocalFileContent(file, wasmContentType(file.name)))
    }
  }

  /**
   * `ui-builder-sw.js` at the bundle root: the builder's offline shell cache (older bundles lack it
   * and 404).
   *
   * Served only at the unversioned `/ui-builder/ui-builder-sw.js`, uncached: a worker is identified
   * by its URL and its freshness decides whether a rollout reaches anyone. Scope is `/ui-builder/`
   * (also stated via `Service-Worker-Allowed`), so catalog pages, `/api/` and WebSockets are
   * outside it. Ungated, since workers update without the page's credential.
   */
  private suspend fun RoutingContext.respondUiBuilderServiceWorker(file: File) {
    val etag = "\"${file.length().toString(16)}-${file.lastModified().toString(16)}\""
    call.response.headers.append(HttpHeaders.CacheControl, "no-cache")
    call.response.headers.append(HttpHeaders.ETag, etag)
    call.response.headers.append(SERVICE_WORKER_ALLOWED, UI_BUILDER_SERVICE_WORKER_SCOPE)
    if (call.request.headers[HttpHeaders.IfNoneMatch] == etag) {
      call.respond(HttpStatusCode.NotModified)
      return
    }
    call.respond(LocalFileContent(file, ContentType.parse("text/javascript")))
  }

  /** The bundle's gzip copies. Per server, because the bundle directory is. */
  private val uiBuilderPrecompressed = UiBuilderPrecompressedAssets()

  /**
   * A content digest of the builder bundle, used as its immutable URL prefix. Content rather than
   * `lastModified`, since image rebuilds rewrite timestamps. Computed lazily on first request. Path
   * and length are mixed in (so renames and swaps change it) and files are visited in sorted order
   * for host independence.
   */
  private val uiBuilderBundleVersion: String by lazy {
    val dir = uiBuilderDir ?: return@lazy "none"
    val base = dir.canonicalFile.toPath()
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(64 * 1024)
    dir
      .walkTopDown()
      // A symlink out of the bundle is not part of it, and the serving path refuses to follow one
      // anyway; hashing its target would let something outside the tree name the version.
      .filter { it.isFile && it.canonicalFile.toPath().startsWith(base) }
      .sortedBy { it.toPath().toAbsolutePath().normalize().toString() }
      .forEach { file ->
        digest.update(base.relativize(file.canonicalFile.toPath()).toString().toByteArray())
        digest.update(file.length().toString().toByteArray())
        file.inputStream().use { stream ->
          while (true) {
            val read = stream.read(buffer)
            if (read <= 0) break
            digest.update(buffer, 0, read)
          }
        }
      }
    digest.digest().take(8).joinToString("") { "%02x".format(it.toInt() and 0xff) }
  }

  /**
   * The builder's app shell with asset references pointed at the versioned prefix. `no-cache`,
   * since it is the document a rollout must change; the ETag folds in the bundle version because
   * the body depends on it.
   */
  private suspend fun RoutingContext.respondUiBuilderShell(
    dir: File,
    index: File,
    designId: String? = null,
  ) {
    if (!index.isFile) {
      call.respondText("not found", status = HttpStatusCode.NotFound)
      return
    }
    val version = uiBuilderBundleVersion
    // The shell is one file for every design, so what makes a pasted link unfurl as *this* design
    // is written into its head here. The ETag folds it in, because the body now depends on it.
    val shellHead = uiBuilderShellHead(designId)
    val head =
      shellHead.first to
        (shellHead.second +
          uiBuilderCatalogOwnershipTag() +
          uiBuilderBasePathTag(call) +
          ServeAnalytics.scriptTag())
    val headTag = Integer.toHexString(head.second.hashCode())
    val etag =
      "\"${index.length().toString(16)}-${index.lastModified().toString(16)}-$version-$headTag\""
    call.response.headers.append(HttpHeaders.CacheControl, "no-cache")
    call.response.headers.append(HttpHeaders.ETag, etag)
    if (call.request.headers[HttpHeaders.IfNoneMatch] == etag) {
      call.respond(HttpStatusCode.NotModified)
      return
    }
    // Start the gzip copies on the first shell request, just before the browser asks for the Wasm.
    // Only the first call does anything.
    uiBuilderPrecompressed.warm(dir)
    val html = withContext(Dispatchers.IO) { index.readText() }
    call.respondBytes(
      withUiBuilderShellHead(versionUiBuilderShellReferences(dir, html, version), head)
        .toByteArray(),
      wasmContentType(index.name),
    )
  }

  /**
   * Where the editor's pages live, as a `<meta>`: `/` on the rooted builder host, nothing elsewhere
   * (the editor keeps its `/ui-builder/` default). See [UI_BUILDER_BASE_PATH_META].
   */
  private fun uiBuilderBasePathTag(call: ApplicationCall): String =
    if (isUiBuilderRootCall(call)) "<meta name=\"$UI_BUILDER_BASE_PATH_META\" content=\"/\">"
    else ""

  /**
   * The catalog-owned cutover flag for the editor's new-design chooser, as a `<meta>`; nothing at
   * `none`.
   */
  private fun uiBuilderCatalogOwnershipTag(): String =
    uiBuilderSeeds.ownership
      .takeUnless { it.isNone }
      ?.let {
        "<meta name=\"ui-builder-catalog-ownership\" content=\"${
          WebEscaping.htmlEscape(it.wireValue)
        }\">"
      }
      .orEmpty()

  /**
   * The `<title>` and unfurl tags for the builder shell: `(title, meta tags)`. A public design
   * unfurls as itself (title and thumbnail), judged as the anonymous reader an unfurler is;
   * everything else unfurls as the UI builder with a drawn card.
   */
  private suspend fun RoutingContext.uiBuilderShellHead(designId: String?): Pair<String?, String> {
    val origin = externalOrigin()
    val pageUrl = origin + call.request.path()
    // Only a `--public` box has anonymous readers, so only there can a design be public.
    val public =
      designId
        ?.takeIf { isPublic }
        ?.let { id ->
          designService?.let { service ->
            val anonymous = AuthenticatedUiBuilderActor(ServeUiBuilderVisibility.ANONYMOUS_ACTOR_ID)
            (runCatching {
                service.execute(
                  UiBuilderServiceCall(anonymous, UiBuilderServiceRequest.OpenDesign(id))
                )
              }
                .getOrNull() as? UiBuilderServiceResponse.Snapshot)
              ?.snapshot
              ?.state
              ?.document
              ?.also { uiBuilderThumbnails?.warm(id, anonymous, knownRevision = it.revision) }
          }
        }
    if (public != null) {
      val title = public.title.ifBlank { public.id }
      val picture = uiBuilderThumbnails?.cached(public.id)
      val size = picture?.png?.let(::pngSize)
      val unfurl =
        ServeWeb.UnfurlMetadata(
          pageUrl = pageUrl,
          imageUrl =
            "$origin/api/ui-builder/v1/designs/${WebEscaping.urlEncodeSegment(public.id)}" +
              "/thumbnail.png?revision=${public.revision}",
          imageWidth = size?.first,
          imageHeight = size?.second,
        )
      val description =
        "A ${public.catalogPin.systemId} screen designed in the Compose UI builder, at revision " +
          "${public.revision}. Open it to see it, or fork it to make it your own."
      return title to ServeWeb.unfurlHeadHtml(title, description, unfurl)
    }
    val card =
      withContext(Dispatchers.IO) {
        socialCards.cardFor(
          ServeSocialCard.Spec(
            title = UI_BUILDER_UNFURL_TITLE,
            subtitle = UI_BUILDER_UNFURL_SUBTITLE,
          )
        )
      }
    val unfurl =
      ServeWeb.UnfurlMetadata(
        pageUrl = pageUrl,
        imageUrl = card?.let { origin + ServeSocialCard.PATH_PREFIX + "/" + it.fileName },
        imageWidth = card?.width,
        imageHeight = card?.height,
      )
    val description =
      if (designId == null) UI_BUILDER_UNFURL_SUBTITLE
      else "A private Compose UI builder design. Sign in to open it if it was shared with you."
    return null to ServeWeb.unfurlHeadHtml(UI_BUILDER_UNFURL_TITLE, description, unfurl)
  }

  /** [html] with [head]'s unfurl tags after its `<title>`. */
  private fun withUiBuilderShellHead(html: String, head: Pair<String?, String>): String {
    // A public design's own title; otherwise the shell keeps the title it was built with.
    val existing = Regex("<title>.*?</title>", RegexOption.DOT_MATCHES_ALL)
    val match = existing.find(html)
    val title =
      head.first?.let { "<title>${WebEscaping.htmlEscape(it)}</title>" } ?: match?.value.orEmpty()
    // The shell is the builder's own page, so it carries the site's icons and manifest too:
    // installing from inside the editor installs the same app as installing from anywhere else.
    val block = title + "\n" + ServeSiteIcon.linkTags() + "\n" + head.second
    return when {
      match != null -> html.replaceRange(match.range, block)
      "</head>" in html -> html.replaceFirst("</head>", "$block\n</head>")
      else -> html
    }
  }

  /** A PNG's pixel size from its IHDR chunk, or null for anything that is not one. */
  private fun pngSize(bytes: ByteArray): Pair<Int, Int>? {
    if (bytes.size < 24 || bytes[12] != 'I'.code.toByte() || bytes[15] != 'R'.code.toByte()) {
      return null
    }
    fun int(at: Int) =
      ((bytes[at].toInt() and 0xff) shl 24) or
        ((bytes[at + 1].toInt() and 0xff) shl 16) or
        ((bytes[at + 2].toInt() and 0xff) shl 8) or
        (bytes[at + 3].toInt() and 0xff)
    return int(16) to int(20)
  }

  /**
   * Rewrites the shell's relative references to `/ui-builder/v/<version>/…`. Making the shell's one
   * reference absolute carries the whole relative chain (`uiBuilder.mjs` → `uiBuilder.wasm` →
   * `skiko.mjs` → `skiko.wasm`) into the immutable prefix without touching build output.
   *
   * Two forms appear in the generated shell: attribute values (`src="uiBuilder.mjs"`) and
   * import-map targets (`"./js-joda.esm.js"`). Only references resolving to a real bundle file are
   * rewritten.
   */
  private fun versionUiBuilderShellReferences(dir: File, html: String, version: String): String {
    val base = dir.canonicalFile.toPath()
    val prefix = "/ui-builder/$UI_BUILDER_VERSION_SEGMENT/$version/"
    fun versioned(reference: String): String? {
      val relative = reference.removePrefix("./")
      if (relative.isEmpty() || relative.startsWith("/") || "://" in relative) return null
      val file = File(dir, relative).canonicalFile
      if (!file.toPath().startsWith(base) || !file.isFile) return null
      return prefix + relative
    }
    return html
      .replace(Regex("""\b(src|href)="([^"]+)"""")) { match ->
        val replacement = versioned(match.groupValues[2]) ?: return@replace match.value
        "${match.groupValues[1]}=\"$replacement\""
      }
      // Unquoted attribute values too, or such assets would silently stay unversioned. Runs second,
      // and quoted values are excluded, so nothing is rewritten twice.
      .replace(Regex("""\b(src|href)=([^\s>"']+)""")) { match ->
        val replacement = versioned(match.groupValues[2]) ?: return@replace match.value
        "${match.groupValues[1]}=$replacement"
      }
      .replace(Regex(""""(\./[^"]+)"""")) { match ->
        val replacement = versioned(match.groupValues[1]) ?: return@replace match.value
        "\"$replacement\""
      }
  }

  /**
   * Refuse a design creation in a shape the caller can read: [ServeWeb.accessDeniedPage] for a
   * browser (`Accept: text/html`), plain text [plain] otherwise. Same status either way.
   */
  private suspend fun RoutingContext.respondUiBuilderDenied(
    status: HttpStatusCode,
    plain: String,
    reason: String,
  ) {
    val wantsHtml =
      call.request.headers[HttpHeaders.Accept]?.contains(ContentType.Text.Html.toString()) == true
    if (!wantsHtml) {
      call.respondText(plain, status = status)
      return
    }
    markGeneration("static-page", DYNAMIC_RESOURCE_CACHE_CONTROL)
    call.respondText(
      ServeWeb.accessDeniedPage(
        reason,
        linkToken(),
        isPublic,
        // Only offer the round trip when it can actually complete and is not already done.
        signInHref =
          githubAuth
            ?.takeIf { oauthCanRoundTrip() && it.currentLogin(call) == null }
            ?.loginPath(call),
        version = SERVE_VERSION,
        githubAuth = githubAuthStatus(),
        componentBrowser = componentBrowserMode(),
      ),
      ContentType.Text.Html,
      status,
    )
  }

  /**
   * `POST /ui-builder/designs`: create one design, then `303` to its permalink.
   *
   * Form-urlencoded so a browser can submit it without script (POST/Redirect/GET). The server seeds
   * the document ([UiBuilderNewDesignSeed]) from intent (id, template, optional state variables)
   * rather than accepting a payload; whole documents go through `PUT
   * /api/ui-builder/v1/designs/{id}`. Never overwrites: an existing id gets the same `303`.
   */
  private suspend fun RoutingContext.handleUiBuilderCreate() {
    val dir = uiBuilderDir
    val service = designService
    val authorization = uiBuilderAuthorization
    if (dir == null || service == null || authorization == null) {
      call.respondText("not found", status = HttpStatusCode.NotFound)
      return
    }
    val form = call.receiveParameters()
    val creationVisibility =
      try {
        UiBuilderDefaultVisibility.parseRequested(form["visibility"])
      } catch (e: IllegalArgumentException) {
        call.respondText(e.message.orEmpty(), status = HttpStatusCode.BadRequest)
        return
      }
    // `start` carries catalog and template as one choice, since the page has no script to
    // repopulate a second select. `catalog` and `template` still work for other callers.
    val start = form["start"].orEmpty().trim().takeIf { it.contains('|') }
    val catalog =
      start?.substringBefore('|')
        ?: form["catalog"]?.trim().orEmpty().ifEmpty { call.parameters["catalog"].orEmpty() }
    if (catalog !in uiBuilderCatalogs) {
      call.respondText("not found", status = HttpStatusCode.NotFound)
      return
    }
    if (!isSameOriginFormSubmission()) {
      call.respondText("cross-site design creation is refused", status = HttpStatusCode.Forbidden)
      return
    }
    val actor =
      when (val decision = authorization.authorize(call, UiBuilderRouteCapability.WRITE)) {
        is UiBuilderAuthorizationDecision.Authorized -> decision.actor
        UiBuilderAuthorizationDecision.Missing -> {
          // The header is for scripts; the body for a browser that submitted the form
          // ([ServeWeb.accessDeniedPage]).
          call.response.headers.append(HttpHeaders.WWWAuthenticate, "Bearer")
          respondUiBuilderDenied(
            HttpStatusCode.Unauthorized,
            "authentication is required",
            uiBuilderDeniedReason(githubAuth?.currentLogin(call)),
          )
          return
        }
        UiBuilderAuthorizationDecision.Forbidden -> {
          respondUiBuilderDenied(
            HttpStatusCode.Forbidden,
            "UI-builder write access required",
            uiBuilderDeniedReason(githubAuth?.currentLogin(call)),
          )
          return
        }
      }
    val designId = form["designId"].orEmpty().trim()
    if (!isUiBuilderDesignSegment(designId)) {
      call.respondText(
        "a design id must start with a letter or number and use only path-safe characters",
        status = HttpStatusCode.BadRequest,
      )
      return
    }
    val template =
      start?.substringAfter('|')?.takeIf { it.isNotBlank() }
        ?: form["template"]?.takeIf { it.isNotBlank() }
        ?: uiBuilderSeeds.defaultTemplate(catalog)
    if (template !in uiBuilderSeeds.templateIds(catalog)) {
      call.respondText(
        "$template is not a template $catalog can start from",
        status = HttpStatusCode.BadRequest,
      )
      return
    }
    val state =
      try {
        decodeNewDesignStates(form["state"]?.takeIf { it.isNotBlank() } ?: "[]")
      } catch (e: IllegalArgumentException) {
        call.respondText(
          e.message ?: "the state variables are not valid",
          status = HttpStatusCode.BadRequest,
        )
        return
      }
    val outcome =
      withContext(Dispatchers.IO) {
        withDesignCreationVisibility(creationVisibility) {
          ServeUiBuilderCreate(service, dir, canonicalServerOrigin(), uiBuilderSeeds)
            .create(
              actor = actor,
              catalogSystemId = catalog,
              designId = designId,
              templateId = template,
              state = state,
            )
        }
      }
    when (outcome) {
      is ServeUiBuilderCreate.Outcome.Created,
      is ServeUiBuilderCreate.Outcome.AlreadyExists -> {
        // 303, not 302, so the browser follows with GET and a reload never re-POSTs.
        call.response.headers.append(
          HttpHeaders.Location,
          uiBuilderPermalink(designId, call.request.queryParameters),
        )
        call.respond(HttpStatusCode.SeeOther)
      }
      is ServeUiBuilderCreate.Outcome.Refused ->
        call.respondText(outcome.reason, status = HttpStatusCode.fromValue(outcome.status))
    }
  }

  /**
   * `POST /ui-builder/designs/copy`: start from an existing design, then `303` to the copy.
   *
   * The copy is the source's current committed document under a new id at revision zero, with its
   * own history, access list and comments; the source is only read. Read as the caller, so it
   * grants nothing. Installed via [ServeUiBuilderCreate.install] (like opening a library design):
   * taken ids are left alone, and catalogs this host doesn't author are refused before storing.
   */
  private suspend fun RoutingContext.handleUiBuilderCopy() {
    val dir = uiBuilderDir
    val service = designService
    val authorization = uiBuilderAuthorization
    if (dir == null || service == null || authorization == null) {
      call.respondText("not found", status = HttpStatusCode.NotFound)
      return
    }
    if (!isSameOriginFormSubmission()) {
      call.respondText("cross-site design creation is refused", status = HttpStatusCode.Forbidden)
      return
    }
    val actor = authorizeUiBuilderPage(authorization, UiBuilderRouteCapability.WRITE) ?: return
    val form = call.receiveParameters()
    val creationVisibility =
      try {
        UiBuilderDefaultVisibility.parseRequested(form["visibility"])
      } catch (e: IllegalArgumentException) {
        call.respondText(e.message.orEmpty(), status = HttpStatusCode.BadRequest)
        return
      }
    val sourceDesignId = form["sourceDesignId"].orEmpty().trim()
    val designId = form["designId"].orEmpty().trim()
    if (!isUiBuilderDesignSegment(sourceDesignId) || !isUiBuilderDesignSegment(designId)) {
      call.respondText(
        "a design id must start with a letter or number and use only path-safe characters",
        status = HttpStatusCode.BadRequest,
      )
      return
    }
    if (sourceDesignId == designId) {
      call.respondText(
        "a copy needs an id of its own",
        status = HttpStatusCode.BadRequest,
      )
      return
    }
    val source =
      when (val opened = service.executeMapped(OpenDesignRequestV1(sourceDesignId), actor)) {
        is UiBuilderServiceResponse.Snapshot -> opened.snapshot.state.document
        is UiBuilderServiceResponse.Error -> {
          call.respondText(
            opened.error.message,
            status = HttpStatusCode.fromValue(opened.httpStatusValue()),
          )
          return
        }
        else -> {
          call.respondText(
            "the design service did not answer with a document",
            status = HttpStatusCode.InternalServerError,
          )
          return
        }
      }
    // A new design: revision zero, timestamps cleared, and a title saying where it came from.
    val copy =
      source.copy(
        id = designId,
        revision = 0,
        title =
          form["title"]?.trim()?.takeIf { it.isNotBlank() }
            ?: "${source.title.ifBlank { sourceDesignId }} copy",
        createdAtEpochMillis = null,
        updatedAtEpochMillis = null,
        home = null,
      )
    val outcome =
      withContext(Dispatchers.IO) {
        withDesignCreationVisibility(creationVisibility) {
          ServeUiBuilderCreate(service, dir, canonicalServerOrigin(), uiBuilderSeeds)
            .install(actor, copy)
        }
      }
    when (outcome) {
      is ServeUiBuilderCreate.Outcome.Created,
      is ServeUiBuilderCreate.Outcome.AlreadyExists -> {
        call.response.headers.append(
          HttpHeaders.Location,
          uiBuilderPermalink(designId, call.request.queryParameters),
        )
        call.respond(HttpStatusCode.SeeOther)
      }
      is ServeUiBuilderCreate.Outcome.Refused ->
        call.respondText(outcome.reason, status = HttpStatusCode.fromValue(outcome.status))
    }
  }

  /**
   * `GET /ui-builder/{designId}/history`: retained revisions newest first, with picture, author,
   * time, and what this reader may do. Read access suffices to look; restore needs the design's
   * WRITE action, fork the host's write capability. Both POST and redirect back.
   */
  private suspend fun RoutingContext.handleUiBuilderHistory() {
    val (actor, designId) = uiBuilderAccessTarget(UiBuilderRouteCapability.READ) ?: return
    val service = designService ?: return
    val listed =
      when (
        val response =
          service.shapeForReader(
            actor,
            service.execute(
              UiBuilderServiceCall(actor, UiBuilderServiceRequest.ListRevisions(designId))
            ),
          )
      ) {
        is UiBuilderServiceResponse.Revisions -> response
        else -> {
          call.respondText("not found", status = HttpStatusCode.NotFound)
          return
        }
      }
    val title =
      (service.execute(UiBuilderServiceCall(actor, UiBuilderServiceRequest.OpenDesign(designId)))
          as? UiBuilderServiceResponse.Snapshot)
        ?.snapshot
        ?.state
        ?.document
        ?.title
        ?.takeIf { it.isNotBlank() } ?: designId
    val actions = service.designActions(actor, designId).orEmpty()
    val mayFork =
      uiBuilderAuthorization?.authorize(call, UiBuilderRouteCapability.WRITE) is
        UiBuilderAuthorizationDecision.Authorized &&
        actor.actorId != ServeUiBuilderVisibility.ANONYMOUS_ACTOR_ID
    val permalink = uiBuilderPermalink(designId, call.request.queryParameters)
    val (path, query) = permalink.substringBefore("?") to permalink.substringAfter("?", "")
    fun withQuery(target: String, extra: String? = null): String {
      val parts = listOfNotNull(query.takeIf { it.isNotEmpty() }, extra)
      return if (parts.isEmpty()) target else target + "?" + parts.joinToString("&")
    }
    val restored = call.request.queryParameters["restored"]?.toLongOrNull()
    val notice =
      when {
        restored != null ->
          "Restored revision $restored as revision ${listed.currentRevision}. The revisions " +
            "in between are still here, so this can be undone by restoring again."
        call.request.queryParameters["stale"] != null ->
          "Somebody changed the design while you were looking, so nothing was restored. " +
            "Here is the history as it is now."
        else -> ""
      }
    val skin = call.siteSkin()
    markGeneration("static-page", "no-store")
    call.respondText(
      ServeWeb.uiBuilderHistoryPage(
        designId = designId,
        title = title,
        currentRevision = listed.currentRevision,
        rows =
          listed.revisions.take(UI_BUILDER_HISTORY_LIMIT).map { revision ->
            ServeWeb.UiBuilderHistoryRow(
              revision = revision.revision,
              updatedAt =
                revision.updatedAtEpochMillis?.let {
                  java.time.Instant.ofEpochMilli(it).toString()
                },
              actorId = revision.actorId,
              thumbnailSrc =
                "/api/ui-builder/v1/designs/${WebEscaping.urlEncodeSegment(designId)}" +
                  "/revisions/${revision.revision}/thumbnail.png" +
                  agentGrantTokenQuery(),
              openHref = withQuery(path, "revision=${revision.revision}"),
              restoreAction =
                withQuery("$path/history/${revision.revision}/restore").takeIf {
                  DesignAccessActionV1.WRITE in actions &&
                    revision.revision != listed.currentRevision
                },
              forkAction = withQuery("$path/history/${revision.revision}/fork").takeIf { mayFork },
            )
          },
        omitted = (listed.revisions.size - UI_BUILDER_HISTORY_LIMIT).coerceAtLeast(0),
        designHref = permalink,
        notice = notice,
        designsHref = "/ui-builder/designs${agentGrantTokenQuery()}",
        navSuffix = agentGrantTokenQuery(),
        version = SERVE_VERSION,
        siteName = skin.first,
        themeCss = skin.second,
      ),
      ContentType.Text.Html,
    )
  }

  /** `POST /ui-builder/{designId}/history/{revision}/restore` — see [handleUiBuilderHistory]. */
  private suspend fun RoutingContext.handleUiBuilderRestore() {
    if (!isSameOriginFormSubmission()) {
      call.respondText("cross-site restore is refused", status = HttpStatusCode.Forbidden)
      return
    }
    val (actor, designId) = uiBuilderAccessTarget(UiBuilderRouteCapability.WRITE) ?: return
    val service = designService ?: return
    val revision = call.parameters["revision"]?.toLongOrNull()
    val base = call.receiveParameters()["baseRevision"]?.toLongOrNull()
    if (revision == null || base == null) {
      call.respondText(
        "a revision and a base revision are required",
        status = HttpStatusCode.BadRequest,
      )
      return
    }
    val response =
      service.execute(
        UiBuilderServiceCall(
          actor,
          UiBuilderServiceRequest.RestoreRevision(
            designId,
            revision,
            base,
            operationId = "restore-" + java.util.UUID.randomUUID(),
          ),
        )
      )
    val historyPath = call.request.path().substringBefore("/history/") + "/history"
    val outcome = (response as? UiBuilderServiceResponse.OperationOutcome)?.outcome
    val flag =
      when {
        outcome is ee.schimke.composeai.uibuilder.protocol.AcceptedOutcomeV1 -> "restored=$revision"
        outcome is ee.schimke.composeai.uibuilder.protocol.RejectedOutcomeV1 &&
          outcome.code ==
            ee.schimke.composeai.uibuilder.protocol.RejectionCodeV1.REVISION_MISMATCH -> "stale=1"
        response is UiBuilderServiceResponse.Error -> {
          call.respondText(
            response.error.message,
            status = HttpStatusCode.fromValue(response.httpStatusValue()),
          )
          return
        }
        else -> {
          call.respondText(
            (outcome as? ee.schimke.composeai.uibuilder.protocol.RejectedOutcomeV1)?.message
              ?: "nothing was restored",
            status = HttpStatusCode.Conflict,
          )
          return
        }
      }
    val token = agentGrantTokenQuery().removePrefix("?")
    call.response.headers.append(
      HttpHeaders.Location,
      "$historyPath?$flag" + if (token.isEmpty()) "" else "&$token",
    )
    call.respond(HttpStatusCode.SeeOther)
  }

  /**
   * `POST /ui-builder/{designId}/history/{revision}/fork`: a new design from one revision, owned by
   * the forker, then `303` to it. Read as the caller, so it grants nothing.
   */
  private suspend fun RoutingContext.handleUiBuilderFork() {
    val dir = uiBuilderDir
    if (dir == null) {
      call.respondText("not found", status = HttpStatusCode.NotFound)
      return
    }
    if (!isSameOriginFormSubmission()) {
      call.respondText("cross-site design creation is refused", status = HttpStatusCode.Forbidden)
      return
    }
    val (actor, designId) = uiBuilderAccessTarget(UiBuilderRouteCapability.WRITE) ?: return
    val service = designService ?: return
    val revision = call.parameters["revision"]?.toLongOrNull()
    if (revision == null) {
      call.respondText("a revision is required", status = HttpStatusCode.BadRequest)
      return
    }
    val source =
      when (
        val opened =
          service.execute(
            UiBuilderServiceCall(actor, UiBuilderServiceRequest.GetSnapshot(designId, revision))
          )
      ) {
        is UiBuilderServiceResponse.Snapshot -> opened.snapshot.state.document
        is UiBuilderServiceResponse.Error -> {
          call.respondText(
            opened.error.message,
            status = HttpStatusCode.fromValue(opened.httpStatusValue()),
          )
          return
        }
        else -> {
          call.respondText(
            "the design service did not answer with a document",
            status = HttpStatusCode.InternalServerError,
          )
          return
        }
      }
    // Its own id — a fork is never an existing design — made from where it came from.
    val forkId = defaultForkId(designId, revision)
    val fork = forkedDesignDocument(source, designId, forkId)
    when (
      val outcome =
        withContext(Dispatchers.IO) {
          ServeUiBuilderCreate(service, dir, canonicalServerOrigin(), uiBuilderSeeds)
            .install(actor, fork)
        }
    ) {
      is ServeUiBuilderCreate.Outcome.Created -> {
        // The same ancestry `ui_builder_fork_design` records, so either fork shows its parent.
        withContext(Dispatchers.IO) {
          recordDesignFork(uiBuilderLinksStore, source, designId, forkId)
        }
        call.response.headers.append(
          HttpHeaders.Location,
          uiBuilderPermalink(forkId, call.request.queryParameters),
        )
        call.respond(HttpStatusCode.SeeOther)
      }
      is ServeUiBuilderCreate.Outcome.AlreadyExists ->
        call.respondText("that fork id is taken; try again", status = HttpStatusCode.Conflict)
      is ServeUiBuilderCreate.Outcome.Refused ->
        call.respondText(outcome.reason, status = HttpStatusCode.fromValue(outcome.status))
    }
  }

  /**
   * `POST /ui-builder/{designId}/delete`: remove one design, then `303` back to the index. The page
   * route to the owner-only [UiBuilderServiceRequest.DeleteDesign] (no grantee may delete).
   *
   * Sidecars (overlay, comments, links) are swept as the admin path and MCP tool do, so the next
   * holder of the id inherits nothing; failures there are logged, since the design is already gone.
   * `confirm=delete` is required to stop a stray re-POST.
   */
  private suspend fun RoutingContext.handleUiBuilderDelete() {
    if (!isSameOriginFormSubmission()) {
      call.respondText("cross-site deletion is refused", status = HttpStatusCode.Forbidden)
      return
    }
    val (actor, designId) = uiBuilderAccessTarget(UiBuilderRouteCapability.WRITE) ?: return
    val service = designService ?: return
    if (call.receiveParameters()["confirm"] != "delete") {
      call.respondText("deletion was not confirmed", status = HttpStatusCode.BadRequest)
      return
    }
    val response =
      service.execute(UiBuilderServiceCall(actor, UiBuilderServiceRequest.DeleteDesign(designId)))
    when (response) {
      is UiBuilderServiceResponse.DesignDeleted -> {
        withContext(Dispatchers.IO) {
          runCatching { uiBuilderReferenceStore?.delete(designId) }
            .onFailure {
              System.err.println(
                "serve: reference overlay for $designId not removed (${it.message})"
              )
            }
          runCatching { uiBuilderCommentStore?.delete(designId) }
            .onFailure {
              System.err.println("serve: comment board for $designId not removed (${it.message})")
            }
          runCatching {
            if (uiBuilderLinksStore?.delete(designId) == LinksDeleteResult.FAILED) {
              System.err.println("serve: links record for $designId not removed")
            }
          }
            .onFailure {
              System.err.println("serve: links record for $designId not removed (${it.message})")
            }
          runCatching {
            if (uiBuilderReviewStore?.delete(designId) == false) {
              System.err.println("serve: review record for $designId not removed")
            }
          }
            .onFailure {
              System.err.println("serve: review record for $designId not removed (${it.message})")
            }
          runCatching {
            if (uiBuilderGuidelineStore?.delete(designId) == false) {
              System.err.println("serve: guidelines record for $designId not removed")
            }
          }
            .onFailure {
              System.err.println(
                "serve: guidelines record for $designId not removed (${it.message})"
              )
            }
          runCatching {
            if (uiBuilderFolderStore?.move(designId, null) is FolderWriteResult.Failed) {
              System.err.println("serve: folder record for $designId not removed")
            }
          }
            .onFailure {
              System.err.println("serve: folder record for $designId not removed (${it.message})")
            }
        }
        call.response.headers.append(
          HttpHeaders.Location,
          "/ui-builder/designs${agentGrantTokenQuery()}",
        )
        call.respond(HttpStatusCode.SeeOther)
      }
      is UiBuilderServiceResponse.Error ->
        respondUiBuilderDenied(
          HttpStatusCode.fromValue(response.httpStatusValue()),
          response.error.message,
          response.error.message,
        )
      else ->
        call.respondText(
          "the design service answered a delete with something else",
          status = HttpStatusCode.InternalServerError,
        )
    }
  }

  /** `POST /ui-builder/{designId}/folder` — move a design in the shared server file manager. */
  private suspend fun RoutingContext.handleUiBuilderFolderMove() {
    if (!isSameOriginFormSubmission()) {
      call.respondText("cross-site folder changes are refused", status = HttpStatusCode.Forbidden)
      return
    }
    val (actor, designId) = uiBuilderAccessTarget(UiBuilderRouteCapability.WRITE) ?: return
    val service = designService ?: return
    val store = uiBuilderFolderStore
    if (store == null) {
      call.respondText("folder storage is unavailable", status = HttpStatusCode.NotFound)
      return
    }
    val actions = service.designActions(actor, designId)
    if (actions == null) {
      call.respondText("not found", status = HttpStatusCode.NotFound)
      return
    }
    if (!actions.contains(DesignAccessActionV1.WRITE)) {
      call.respondText(
        "the design's own access control does not permit moving it",
        status = HttpStatusCode.Forbidden,
      )
      return
    }
    val folder = call.receiveParameters()["folder"]
    when (val result = withContext(Dispatchers.IO) { store.move(designId, folder) }) {
      FolderWriteResult.Stored -> {
        call.response.headers.append(
          HttpHeaders.Location,
          "/ui-builder/designs${agentGrantTokenQuery()}",
        )
        call.respond(HttpStatusCode.SeeOther)
      }
      is FolderWriteResult.Refused ->
        call.respondText(result.reason, status = HttpStatusCode.UnprocessableEntity)
      is FolderWriteResult.Failed ->
        call.respondText(result.reason, status = HttpStatusCode.InternalServerError)
    }
  }

  /**
   * `GET /ui-builder/{designId}/access`: the sharing page for one design. Owner-only by the
   * service's decision (`GetDesignAccess`); the route adds the reader's own actor id and a form.
   */
  private suspend fun RoutingContext.handleUiBuilderAccess() {
    val (actor, designId) = uiBuilderAccessTarget(UiBuilderRouteCapability.READ) ?: return
    val service = designService ?: return
    val access =
      when (val response = service.executeMapped(GetDesignAccessRequestV1(designId), actor)) {
        is UiBuilderServiceResponse.DesignAccess -> response.access
        else -> {
          respondUiBuilderDenied(
            HttpStatusCode.Forbidden,
            "only a design's owner can see or change who else may open it",
            "Only the owner of $designId can share it. Ask them to add " +
              "${actor.actorId} from the design's own share page.",
          )
          return
        }
      }
    val query = call.request.queryParameters
    val target = query["actor"].orEmpty()
    val notice =
      when {
        query["changed"] == "public" ->
          "This design is now public: anyone with its link can open it, read-only."
        query["changed"] == "private" -> "This design is now private."
        target.isBlank() -> ""
        query["changed"] == "removed" -> "$target can no longer open this design."
        query["changed"] == "shared" && query["role"] in setOf("editor", "viewer") ->
          "$target can now open this design as ${query["role"]}."
        else -> ""
      }
    respondUiBuilderAccessPage(designId, actor, access, notice = notice)
  }

  /** `GET /ui-builder/designs` — owned and shared designs for the authenticated actor. */
  private suspend fun RoutingContext.handleUiBuilderDesigns() {
    val service = designService
    val authorization = uiBuilderAuthorization
    if (service == null || authorization == null || uiBuilderDir == null) {
      call.respondText("not found", status = HttpStatusCode.NotFound)
      return
    }
    val actor = authorizeUiBuilderPage(authorization, UiBuilderRouteCapability.READ) ?: return
    // A signed-out visitor may open a public design by its link, but has no designs of their own:
    // this page is "mine", so it asks them who they are, as it did before public designs existed.
    if (actor.actorId == ServeUiBuilderVisibility.ANONYMOUS_ACTOR_ID) {
      githubAuth?.let {
        call.respondRedirect(it.loginPath(call))
        return
      }
      call.response.headers.append(HttpHeaders.WWWAuthenticate, "Bearer")
      respondUiBuilderDenied(
        HttpStatusCode.Unauthorized,
        "authentication is required",
        uiBuilderDeniedReason(null),
      )
      return
    }
    val listed = mutableListOf<ee.schimke.composeai.uibuilder.protocol.DesignListItemV1>()
    var cursor: String? = null
    do {
      when (val response = service.executeMapped(ListDesignsRequestV1(cursor, 200), actor)) {
        is UiBuilderServiceResponse.Designs -> {
          listed += response.designs
          cursor = response.nextCursor
        }
        is UiBuilderServiceResponse.Error -> {
          respondUiBuilderDenied(
            HttpStatusCode.fromValue(response.httpStatusValue()),
            response.error.message,
            response.error.message,
          )
          return
        }
        else -> {
          call.respondText(
            "UI-builder service returned an unexpected design list",
            status = HttpStatusCode.InternalServerError,
          )
          return
        }
      }
    } while (cursor != null)

    val tokenQuery = agentGrantTokenQuery()
    // Whether this reader may create at all, asked once: the create/copy forms and every Duplicate
    // control share the capability.
    val mayCreate =
      authorization.authorize(call, UiBuilderRouteCapability.WRITE) is
        UiBuilderAuthorizationDecision.Authorized
    val createAction = if (mayCreate) "/ui-builder/designs$tokenQuery" else ""
    // A reader who may not create, but is signed in on a box that can grant access, is offered the
    // way to ask for it rather than a dead end.
    val requestAccessHref =
      if (
        !mayCreate &&
          agentGrants != null &&
          designService != null &&
          githubAuth?.currentSignedInLogin(call) != null
      )
        UI_BUILDER_REQUEST_ACCESS_PATH + tokenQuery
      else ""
    val copyAction = if (mayCreate) "/ui-builder/designs/copy$tokenQuery" else ""
    // Whether this viewer may have widget cards compiled natively; see ServeUiBuilderThumbnails.
    val mayCompileThumbnails =
      authorization.authorize(call, UiBuilderRouteCapability.EXPORT) is
        UiBuilderAuthorizationDecision.Authorized
    val folders = withContext(Dispatchers.IO) { uiBuilderFolderStore?.readAll().orEmpty() }
    val rows = listed.map { item ->
      val openFailure =
        (service.executeMapped(OpenDesignRequestV1(item.designId), actor)
            as? UiBuilderServiceResponse.Error)
          ?.error
          ?.message
      val access =
        if (item.requesterAccess.role == DesignAccessRoleV1.OWNER)
          (service.executeMapped(GetDesignAccessRequestV1(item.designId), actor)
              as? UiBuilderServiceResponse.DesignAccess)
            ?.access
        else null
      val href = uiBuilderPermalink(item.designId, call.request.queryParameters)
      ServeWeb.UiBuilderDesignRow(
        designId = item.designId,
        title = item.title,
        catalogSystemId = item.catalogPin.systemId,
        revision = item.revision,
        updatedAtEpochMillis = item.updatedAtEpochMillis,
        ownerActorId = item.ownerActorId,
        requesterRole = item.requesterAccess.role.name.lowercase(),
        requesterAllowed =
          item.requesterAccess.allowedActions.joinToString(", ") { it.name.lowercase() },
        designHref = href,
        shareAction =
          href.let { permalink ->
            val (path, query) = permalink.substringBefore("?") to permalink.substringAfter("?", "")
            "$path/access" + if (query.isEmpty()) "" else "?$query"
          },
        grants =
          access?.actorGrants?.map {
            ServeWeb.UiBuilderAccessRow(
              actorId = it.actorId,
              role = it.role.name.lowercase(),
              allowed = it.allowedActions.joinToString(", ") { action -> action.name.lowercase() },
            )
          },
        unopenableReason = openFailure,
        publicRead =
          access?.let(ServeUiBuilderVisibility::isPublic)
            ?: service.canRead(
              AuthenticatedUiBuilderActor(ServeUiBuilderVisibility.ANONYMOUS_ACTOR_ID),
              item.designId,
            ),
        // No thumbnail for a design that could not be opened: the export would answer with the
        // same refusal the card already prints in words, and an `<img>` cannot say it.
        previewHref =
          if (openFailure != null) ""
          else if (uiBuilderThumbnails != null) {
            // Queued now so a card this page shows out of date is current on the next view.
            uiBuilderThumbnails.warm(
              item.designId,
              actor,
              knownRevision = item.revision,
              native = mayCompileThumbnails,
            )
            "/api/ui-builder/v1/designs/" +
              WebEscaping.urlEncodeSegment(item.designId) +
              "/thumbnail.png" +
              (if (tokenQuery.isEmpty()) "?" else "$tokenQuery&") +
              "revision=${item.revision}"
          } else
            "/api/ui-builder/v1/designs/" +
              WebEscaping.urlEncodeSegment(item.designId) +
              "/export.svg$tokenQuery",
        copyAction = if (openFailure == null) copyAction else "",
        copySuggestedId = NewDesignNames.random(),
        // Owner-only, and it is the service that decides so — `DeleteDesign` refuses anybody else,
        // however wide their grant. The page asks the same question the request would.
        deleteAction =
          if (item.requesterAccess.role == DesignAccessRoleV1.OWNER)
            "/ui-builder/${WebEscaping.urlEncodeSegment(item.designId)}/delete$tokenQuery"
          else "",
        folder = folders[item.designId],
        folderAction =
          if (
            uiBuilderFolderStore != null &&
              item.requesterAccess.allowedActions.contains(DesignAccessActionV1.WRITE)
          )
            "/ui-builder/${WebEscaping.urlEncodeSegment(item.designId)}/folder$tokenQuery"
          else "",
        // The same way in as the page's own link, naming this design, so what the owner is asked
        // to approve is edit access to this design rather than to everything they can edit.
        requestAccessHref =
          if (requestAccessHref.isEmpty() || item.requesterAccess.role == DesignAccessRoleV1.OWNER)
            ""
          else
            "$UI_BUILDER_REQUEST_ACCESS_PATH?design=" +
              WebEscaping.urlEncodeSegment(item.designId) +
              tokenQuery.replaceFirst("?", "&"),
      )
    }
    val skin = call.siteSkin()
    markGeneration("static-page", "no-store")
    call.respondText(
      ServeWeb.uiBuilderDesignsPage(
        // Newest first. A file manager's default order is "what I was last working on", and this
        // list was oldest-first, which put the design you just made at the bottom of the page.
        rows = rows.sortedByDescending { it.updatedAtEpochMillis ?: 0L },
        viewerActorId = actor.actorId,
        createAction = createAction,
        copyAction = copyAction,
        catalogs = if (mayCreate) uiBuilderNewDesignOptions() else emptyList(),
        suggestedDesignId = NewDesignNames.random(),
        notice = call.request.queryParameters["notice"].orEmpty().take(200),
        catalogProblems = if (mayCreate) uiBuilderCatalogProblems() else emptyMap(),
        navSuffix = tokenQuery,
        version = SERVE_VERSION,
        siteName = skin.first,
        themeCss = skin.second,
        requestAccessHref = requestAccessHref,
      ),
      ContentType.Text.Html,
    )
  }

  /**
   * The catalogs the New design form offers and their starting points, read from
   * [UiBuilderNewDesignSeed] so the form can't offer a template the create route refuses.
   */
  private fun uiBuilderNewDesignOptions(): List<ServeWeb.UiBuilderNewDesignOption> =
    uiBuilderCatalogs.sorted().map { catalog ->
      ServeWeb.UiBuilderNewDesignOption(
        systemId = catalog,
        label = catalog,
        templates =
          uiBuilderSeeds
            .templateIds(catalog)
            // A catalog-owned catalog's order is its own, and its first is the default the
            // create route falls back to; the built-in set has no order, so it stays sorted.
            .let { if (uiBuilderSeeds.ownership.owns(catalog)) it.toList() else it.sorted() }
            .map { template ->
              ServeWeb.UiBuilderNewDesignTemplate(
                id = template,
                label = template.replace('-', ' ').replaceFirstChar { it.uppercase() },
              )
            },
      )
    }

  /**
   * `POST /ui-builder/{designId}/access`: share, unshare, or make public/private. A change answers
   * `303` to the access page with a fixed notice (POST/redirect/GET); a refusal renders the page
   * directly.
   */
  private suspend fun RoutingContext.handleUiBuilderAccessUpdate() {
    if (!isSameOriginFormSubmission()) {
      call.respondText("cross-site sharing is refused", status = HttpStatusCode.Forbidden)
      return
    }
    val (actor, designId) = uiBuilderAccessTarget(UiBuilderRouteCapability.WRITE) ?: return
    val service = designService ?: return
    val form = call.receiveParameters()
    val target = form["actorId"].orEmpty().trim()
    val revoking = form["action"] == "revoke"
    val visibility = form["visibility"]?.takeIf { it == "public" || it == "private" }
    val current =
      when (val response = service.executeMapped(GetDesignAccessRequestV1(designId), actor)) {
        is UiBuilderServiceResponse.DesignAccess -> response.access
        else -> {
          respondUiBuilderDenied(
            HttpStatusCode.Forbidden,
            "only a design's owner can see or change who else may open it",
            "Only the owner of $designId can share it.",
          )
          return
        }
      }
    if (visibility != null) {
      val mutation =
        if (visibility == "public") ServeUiBuilderVisibility.makePublic()
        else ServeUiBuilderVisibility.makePrivate()
      when (
        val updated =
          service.executeMapped(
            UpdateDesignAccessRequestV1(designId, current.accessRevision, listOf(mutation)),
            actor,
          )
      ) {
        is UiBuilderServiceResponse.DesignAccess ->
          if (form["returnTo"] == "designs") {
            call.response.headers.append(
              HttpHeaders.Location,
              "/ui-builder/designs${agentGrantTokenQuery()}",
            )
            call.respond(HttpStatusCode.SeeOther)
          } else {
            redirectToAccessPage(listOf("changed" to visibility))
          }
        is UiBuilderServiceResponse.Error ->
          respondUiBuilderAccessPage(designId, actor, current, notice = updated.error.message)
        else -> respondUiBuilderAccessPage(designId, actor, current, notice = "nothing changed")
      }
      return
    }
    if (ServeUiBuilderVisibility.isReservedActor(target)) {
      respondUiBuilderAccessPage(
        designId,
        actor,
        current,
        notice = "$target stands for everyone; use Make public or Make private instead.",
      )
      return
    }
    if (target.isBlank() || target == current.ownerActorId) {
      respondUiBuilderAccessPage(
        designId,
        actor,
        current,
        notice =
          if (target.isBlank()) "Nothing was shared: no actor id was given."
          else "That is the owner's own id, and an owner's access is not a grant to add or remove.",
      )
      return
    }
    val role =
      if (form["role"] == "editor") DesignAccessRoleV1.EDITOR else DesignAccessRoleV1.VIEWER
    val mutation =
      if (revoking) RevokeActorAccessMutationV1(target)
      else
        GrantActorAccessMutationV1(
          target,
          role,
          if (role == DesignAccessRoleV1.EDITOR)
            listOf(
              DesignAccessActionV1.READ,
              DesignAccessActionV1.WRITE,
              DesignAccessActionV1.EXPORT,
            )
          // Viewers can export too (the Kotlin is a rendering of what they already see). Neither
          // role carries `manageAccess` or `delete`, so being shared with never lets one share on.
          else listOf(DesignAccessActionV1.READ, DesignAccessActionV1.EXPORT),
        )
    val updated =
      service.executeMapped(
        UpdateDesignAccessRequestV1(designId, current.accessRevision, listOf(mutation)),
        actor,
      )
    when (updated) {
      is UiBuilderServiceResponse.DesignAccess -> {
        if (form["returnTo"] == "designs") {
          call.response.headers.append(
            HttpHeaders.Location,
            "/ui-builder/designs${agentGrantTokenQuery()}",
          )
          call.respond(HttpStatusCode.SeeOther)
          return
        }
        // POST, redirect, GET: the access page re-reads the list, and the notice travels as a
        // fixed template plus its two values, so a refresh repeats the message and not the change.
        redirectToAccessPage(
          if (revoking) listOf("changed" to "removed", "actor" to target)
          else listOf("changed" to "shared", "actor" to target, "role" to role.name.lowercase())
        )
      }
      is UiBuilderServiceResponse.Error ->
        respondUiBuilderAccessPage(designId, actor, current, notice = updated.error.message)
      else -> respondUiBuilderAccessPage(designId, actor, current, notice = "nothing changed")
    }
  }

  /**
   * `303` back to this design's access page with [notice] in the query, keeping `?token=` and
   * catalog.
   */
  private suspend fun RoutingContext.redirectToAccessPage(notice: List<Pair<String, String>>) {
    val kept =
      call.request.queryParameters
        .entries()
        .filter { (name, _) -> name !in ACCESS_NOTICE_PARAMS }
        .flatMap { (name, values) -> values.map { name to it } }
    call.response.headers.append(
      HttpHeaders.Location,
      call.request.path() +
        "?" +
        (kept + notice).joinToString("&") { (name, value) ->
          "${WebEscaping.urlEncodeSegment(name)}=${WebEscaping.urlEncodeSegment(value)}"
        },
    )
    call.respond(HttpStatusCode.SeeOther)
  }

  /**
   * Who is asking and which design, for both access routes; null once a refusal has been written.
   */
  private suspend fun RoutingContext.uiBuilderAccessTarget(
    capability: UiBuilderRouteCapability
  ): Pair<AuthenticatedUiBuilderActor, String>? {
    val service = designService
    val authorization = uiBuilderAuthorization
    if (service == null || authorization == null) {
      call.respondText("not found", status = HttpStatusCode.NotFound)
      return null
    }
    // The catalog-prefixed route is compatibility-only. If it is used, retain its old rule that
    // an unknown catalog is a 404; the canonical route has no catalog parameter at all.
    call.parameters["catalog"]?.let { catalog ->
      if (catalog !in uiBuilderCatalogs) {
        call.respondText("not found", status = HttpStatusCode.NotFound)
        return null
      }
    }
    val designId = call.parameters["designId"].orEmpty()
    if (!isUiBuilderDesignSegment(designId)) {
      call.respondText("not found", status = HttpStatusCode.NotFound)
      return null
    }
    val actor =
      when (val decision = authorization.authorize(call, capability)) {
        is UiBuilderAuthorizationDecision.Authorized -> decision.actor
        UiBuilderAuthorizationDecision.Missing -> {
          call.response.headers.append(HttpHeaders.WWWAuthenticate, "Bearer")
          respondUiBuilderDenied(
            HttpStatusCode.Unauthorized,
            "authentication is required",
            uiBuilderDeniedReason(githubAuth?.currentLogin(call)),
          )
          return null
        }
        UiBuilderAuthorizationDecision.Forbidden -> {
          respondUiBuilderDenied(
            HttpStatusCode.Forbidden,
            "UI-builder access required",
            uiBuilderDeniedReason(githubAuth?.currentLogin(call)),
          )
          return null
        }
      }
    return actor to designId
  }

  /** Authenticate one server-rendered UI-builder page without trusting an actor from its URL. */
  private suspend fun RoutingContext.authorizeUiBuilderPage(
    authorization: ServeUiBuilderAuthorization,
    capability: UiBuilderRouteCapability,
  ): AuthenticatedUiBuilderActor? =
    when (val decision = authorization.authorize(call, capability)) {
      is UiBuilderAuthorizationDecision.Authorized -> decision.actor
      UiBuilderAuthorizationDecision.Missing -> {
        call.response.headers.append(HttpHeaders.WWWAuthenticate, "Bearer")
        respondUiBuilderDenied(
          HttpStatusCode.Unauthorized,
          "authentication is required",
          uiBuilderDeniedReason(githubAuth?.currentLogin(call)),
        )
        null
      }
      UiBuilderAuthorizationDecision.Forbidden -> {
        respondUiBuilderDenied(
          HttpStatusCode.Forbidden,
          "UI-builder access required",
          uiBuilderDeniedReason(githubAuth?.currentLogin(call)),
        )
        null
      }
    }

  private suspend fun RoutingContext.respondUiBuilderAccessPage(
    designId: String,
    actor: AuthenticatedUiBuilderActor,
    access: DesignAccessControlV1,
    notice: String,
  ) {
    val skin = call.siteSkin()
    markGeneration("static-page", "no-store")
    call.respondText(
      ServeWeb.uiBuilderAccessPage(
        designId = designId,
        designHref = uiBuilderPermalink(designId, call.request.queryParameters),
        formAction =
          uiBuilderPermalink(designId, call.request.queryParameters).let { permalink ->
            val (path, query) = permalink.substringBefore("?") to permalink.substringAfter("?", "")
            "$path/access" + if (query.isEmpty()) "" else "?$query"
          },
        ownerActorId = access.ownerActorId,
        grants =
          access.actorGrants.map { grant ->
            ServeWeb.UiBuilderAccessRow(
              actorId = grant.actorId,
              role = grant.role.name.lowercase(),
              allowed = grant.allowedActions.joinToString(", ") { it.name.lowercase() },
            )
          },
        viewerActorId = actor.actorId,
        notice = notice,
        isPublic = ServeUiBuilderVisibility.isPublic(access),
        historyHref =
          uiBuilderPermalink(designId, call.request.queryParameters).let { permalink ->
            val (path, query) = permalink.substringBefore("?") to permalink.substringAfter("?", "")
            "$path/history" + if (query.isEmpty()) "" else "?$query"
          },
        designsHref = "/ui-builder/designs${agentGrantTokenQuery()}",
        navSuffix = agentGrantTokenQuery(),
        version = SERVE_VERSION,
        siteName = skin.first,
        themeCss = skin.second,
      ),
      ContentType.Text.Html,
    )
  }

  /**
   * Whether a form POST came from a page this server served. Mutating form routes refuse otherwise,
   * since a hostile page can aim a form here with the reader's credentials. Non-browser clients
   * send neither header and are left alone; a same-origin POST without `Origin` still sends
   * `Sec-Fetch-Site: same-origin`.
   */
  private fun RoutingContext.isSameOriginFormSubmission(): Boolean {
    val fetchSite = call.request.headers["Sec-Fetch-Site"]
    val origin = call.request.headers[HttpHeaders.Origin]
    return when {
      fetchSite != null -> fetchSite == "same-origin" || fetchSite == "none"
      origin != null ->
        // Compare authorities (`Origin`'s host[:port] vs `Host`), since schemes differ behind a
        // TLS-terminating proxy.
        call.request.headers[HttpHeaders.Host]?.let {
          origin.substringAfter("://").equals(it, ignoreCase = true)
        } == true
      else -> true
    }
  }

  /** The design's own URL, carrying forward only the identity a create link was opened with. */
  private fun RoutingContext.uiBuilderPermalink(
    designId: String,
    query: io.ktor.http.Parameters,
  ): String {
    val carried = UI_BUILDER_IDENTITY_QUERY.mapNotNull { name ->
      val value = query[name] ?: return@mapNotNull null
      "$name=" + java.net.URLEncoder.encode(value, "UTF-8")
    }
    val suffix = if (carried.isEmpty()) "" else carried.joinToString("&", prefix = "?")
    return uiBuilderPagePath(call, "$designId$suffix")
  }

  private suspend fun RoutingContext.handleUiBuilderRuntimeAsset() {
    val runtimeId = call.parameters["runtimeId"].orEmpty()
    val segments = call.parameters.getAll("path").orEmpty().filter { it.isNotEmpty() }
    val configuredManifest = uiBuilderRuntimeAssets.asset(runtimeId, emptyList())
    val catalogManifest =
      catalogUiBuilderRuntimeAsset(runtimeId, emptyList())?.let { (bytes, etag) ->
        ServeUiBuilderRuntimeAssets.Asset(bytes, etag)
      }
    // Runtime ids are global immutable identities: configured and catalog runtimes may share one
    // only with identical verified manifests, or a catalog descriptor's integrity would point at
    // different bytes.
    val collision =
      configuredManifest != null &&
        catalogManifest != null &&
        configuredManifest.etag != catalogManifest.etag
    val asset =
      if (collision) {
        null
      } else {
        uiBuilderRuntimeAssets.asset(runtimeId, segments)
          ?: catalogUiBuilderRuntimeAsset(runtimeId, segments)?.let { (bytes, etag) ->
            ServeUiBuilderRuntimeAssets.Asset(bytes, etag)
          }
      }
    if (asset == null) {
      call.respondText("not found", status = HttpStatusCode.NotFound)
      return
    }
    call.response.headers.append(HttpHeaders.AccessControlAllowOrigin, "*")
    call.response.headers.append(
      HttpHeaders.CacheControl,
      "public, max-age=31536000, immutable",
    )
    call.response.headers.append(HttpHeaders.ETag, asset.etag)
    if (call.request.headers[HttpHeaders.IfNoneMatch] == asset.etag) {
      call.respond(HttpStatusCode.NotModified)
      return
    }
    val name = segments.lastOrNull() ?: ServeUiBuilderRuntimeAssets.RUNTIME_MANIFEST_NAME
    call.respondBytes(asset.bytes, wasmContentType(name))
  }

  // ------------------------------------------------------------ agent grants

  /**
   * The live-grant rows for `/status` with their revoke seals, shown only to an approver and only
   * for grants they manage ([ServeAgentGrants.Approver.manages]). Rows name logins, so others
   * (including grant-bearing agents) see only [agentGrantHiddenCount]. Never a token; only
   * [ServeAgentGrantStore.Grant.fingerprint].
   */
  private fun RoutingContext.agentGrantStatusRows(): List<ServeWeb.StatusAgentGrant> {
    // On a top-level site grants are omitted (they belong to the box, not a catalog).
    if (siteSystem() != null) return emptyList()
    val store = agentGrants ?: return emptyList()
    val approver = agentGrantApprover(store) ?: return emptyList()
    val now = System.currentTimeMillis()
    return store
      .activeGrants()
      .filter { approver.manages(it) }
      .map { grant ->
        ServeWeb.StatusAgentGrant(
          id = grant.id,
          fingerprint = grant.fingerprint,
          scopes = grant.scopes.joinToString(", ") { it.wire },
          capabilities = AgentGrantCapability.wireNames(grant.capabilities).joinToString(", "),
          label = grant.label,
          approvedBy = grant.approvedBy,
          expiresInText = AgentGrantProtocol.formatDuration(grant.secondsUntilExpiry(now)),
          revokeCsrf =
            agentGrantCsrf.seal(grant.id, approver.name, ServeAgentGrants.Csrf.ACTION_DENY),
        )
      }
  }

  /**
   * How many live grants this reader isn't shown — the same number `/status.json` publishes as
   * `agentAccess.activeGrants`.
   */
  private fun RoutingContext.agentGrantHiddenCount(shown: Int): Int {
    if (siteSystem() != null) return 0
    val store = agentGrants ?: return 0
    return (store.activeGrants().size - shown).coerceAtLeast(0)
  }

  /**
   * Requests waiting on a human, shown only to an approver (a signed-in `--public` visitor sees
   * only their own; [ServeAgentGrants.Approver.sees]). On a token-gated box this also lets the
   * operator reach a request from their tokened `/status`, since the agent's printed link lacks
   * `?token=`.
   */
  private fun RoutingContext.agentGrantRequestRows(): List<ServeWeb.StatusAgentRequest> {
    if (siteSystem() != null) return emptyList()
    val store = agentGrants ?: return emptyList()
    val approver = agentGrantApprover(store) ?: return emptyList()
    val now = System.currentTimeMillis()
    return store
      .pendingRequests()
      .filter { approver.sees(it) }
      .map { request ->
        ServeWeb.StatusAgentRequest(
          id = request.id,
          userCode = request.userCode,
          label = request.label,
          client = request.client,
          requestedScope = request.requestedScope.wire,
          expiresInText = AgentGrantProtocol.formatDuration(request.secondsUntilExpiry(now)),
        )
      }
  }

  /**
   * `POST /agent-access/request`: an agent holding nothing asks for access. Ungated and so the most
   * exposed route: it parks one bounded entry and returns two random strings, touching no session
   * or daemon and conferring nothing.
   */
  /**
   * The per-process seal on the approval form. No configuration; a restart drops every request, so
   * older seals protect nothing.
   */
  private val agentGrantCsrf = ServeAgentGrants.Csrf()

  /** Only public client registrations persist; approval and token state dies with the grants. */
  private val mcpOAuth = ServeMcpOAuth.Store(mcpOAuthClientsFile?.toPath())

  private suspend fun RoutingContext.handleAgentGrantRequest(store: ServeAgentGrantStore) {
    val permit = acquireAgentGrantPermit() ?: return
    try {
      val body =
        withContext(Dispatchers.IO) {
          call.receiveStream().use { readCapped(it, MAX_AGENT_GRANT_BYTES) }
        }
      if (body == null) {
        call.respondText("request too large", status = HttpStatusCode.PayloadTooLarge)
        return
      }
      // An empty body is the honest minimum ask — `curl -X POST .../request` should work — so it
      // means "the defaults", not "malformed".
      val text = body.decodeToString().trim()
      val parsed =
        if (text.isEmpty()) ServeAgentGrants.OpenRequest()
        else
          try {
            JSON.decodeFromString(ServeAgentGrants.OpenRequest.serializer(), text)
          } catch (e: Exception) {
            call.respondText("invalid request: ${e.message}", status = HttpStatusCode.BadRequest)
            return
          }
      val response = openAgentGrantRequest(store, parsed)
      if (response == null) {
        call.response.headers.append(HttpHeaders.RetryAfter, "60")
        call.respondText(
          "too many pending access requests on this server; try again shortly",
          status = HttpStatusCode.TooManyRequests,
        )
        return
      }
      call.respondText(
        JSON.encodeToString(ServeAgentGrants.OpenResponse.serializer(), response),
        ContentType.Application.Json,
      )
    } finally {
      permit.release()
    }
  }

  /**
   * Open one access request and describe it, shared by the `/agent-access` route and the catalog
   * MCP's `request_access` tool. The caller owns rate limiting and refusal reporting. Null at the
   * pending-request ceiling.
   */
  private fun RoutingContext.openAgentGrantRequest(
    store: ServeAgentGrantStore,
    parsed: ServeAgentGrants.OpenRequest,
  ): ServeAgentGrants.OpenResponse? {
    val scope = AgentGrantScope.parse(parsed.scope) ?: AgentGrantScope.DEFAULT_REQUEST
    // Unknown capability names are dropped, not refused ([OpenRequest.capabilities]). The approval
    // page decides what is offered and approval clamps what is minted; the request keeps the whole
    // ask.
    val capabilities = parsed.capabilities.mapNotNull { AgentGrantCapability.parse(it) }.toSet()
    val ttl = parsed.ttlSeconds.takeIf { it > 0 } ?: ServeAgentGrantStore.DEFAULT_GRANT_TTL_SECONDS
    val request =
      store.openRequest(
        label = parsed.label,
        // The address, not a caller-chosen name, for "who is asking"; the label above is the
        // caller's.
        // The same trusted-forwarding policy as the rate limiter, not the raw peer (which is the
        // proxy behind Caddy).
        client = clientAddress(),
        requestedScope = scope,
        requestedTtlSeconds = ttl,
        requestedCapabilities = capabilities,
      ) ?: return null
    val origin = externalOrigin()
    return ServeAgentGrants.OpenResponse(
      requestId = request.id,
      deviceSecret = request.deviceSecret,
      userCode = request.userCode,
      approveUrl = origin + ServeAgentGrants.approvalPath(request.id),
      pollUrl = origin + ServeAgentGrants.POLL_PATH,
      expiresInSeconds = request.secondsUntilExpiry(System.currentTimeMillis()),
      pollIntervalSeconds = ServeAgentGrantStore.POLL_INTERVAL_SECONDS,
      requestedScope = request.requestedScope.wire,
      requestedTtlSeconds = request.requestedTtlSeconds,
      maxScope = store.maxScope.wire,
      maxTtlSeconds = store.maxGrantTtlSeconds,
      requestedCapabilities = AgentGrantCapability.wireNames(request.requestedCapabilities),
      maxCapabilities = AgentGrantCapability.wireNames(store.maxCapabilities),
    )
  }

  /**
   * `GET`/`POST /ui-builder/request-access`: a signed-in reader asks for UI-builder edit access for
   * themselves.
   *
   * Opened here from the verified session rather than via the ungated, cookie-blind
   * [ServeAgentGrants.REQUEST_PATH], so nobody can request in another's name; the form carries a
   * seal minted for that login, which a cross-site POST also fails. Approval is carried by the
   * requester's session ([ServeAgentGrantStore.activeGrantForRequester]); no device secret is
   * collected.
   */
  private suspend fun RoutingContext.handleUiBuilderRequestAccess(
    store: ServeAgentGrantStore,
    submit: Boolean,
  ) {
    val auth = githubAuth ?: return
    val login = auth.currentSignedInLogin(call)
    if (login == null) {
      call.respondRedirect(auth.loginPath(call))
      return
    }
    val requester = ServeAgentGrants.githubActorId(login)
    val skin = call.siteSkin()
    val seal = agentGrantCsrf.seal(REQUEST_ACCESS_SEAL_ID, requester, REQUEST_ACCESS_SEAL_ACTION)
    val ttlChoices =
      ServeWeb.ttlChoices(store.maxGrantTtlSeconds, store.maxGrantTtlSeconds).filter {
        it >= 60 * 60
      }
    val active = store.activeGrantForRequester(requester)
    val form = if (submit) call.receiveFormParameters() else null
    // The design the request is for (`?design=`, carried as a hidden field). Malformed values are
    // refused, since dropping one would widen the request to all designs.
    val designParam =
      (if (form != null) form["design"]?.firstOrNull() else call.request.queryParameters["design"])
        ?.takeIf { it.isNotEmpty() }
    if (designParam != null && !ServeAgentGrantStore.isWellFormedDesignId(designParam)) {
      call.respondText("not a design id", status = HttpStatusCode.BadRequest)
      return
    }
    val designIds = setOfNotNull(designParam)
    if (form != null) {
      if (
        !agentGrantCsrf.verify(
          REQUEST_ACCESS_SEAL_ID,
          requester,
          REQUEST_ACCESS_SEAL_ACTION,
          form["csrf"]?.firstOrNull(),
        )
      ) {
        call.respondText(
          "stale or forged form; reload and try again",
          status = HttpStatusCode.Forbidden,
        )
        return
      }
      // One waiting request per person and design. The seal is deterministic, so without this
      // every refresh or double-click opened another request against the server-wide pending cap.
      val waiting =
        store.pendingRequests().firstOrNull {
          it.requesterActorId == requester && it.designIds == designIds
        }
      if (waiting != null) {
        redirectToRequestedAccess(waiting.id)
        return
      }
      val permit = acquireAgentGrantPermit() ?: return
      try {
        val ttl =
          form["ttl"]?.firstOrNull()?.toLongOrNull()?.takeIf { it > 0 } ?: store.maxGrantTtlSeconds
        val request =
          store.openRequest(
            label =
              "UI-builder edit access for @$login" +
                designParam?.let { " to design $it" }.orEmpty(),
            // Written by this server from the verified session, which is what "Asked from" on the
            // approval page is for: the one line there the asker cannot write.
            client = "@$login, signed in with GitHub (from ${clientAddress()})",
            requestedScope = AgentGrantScope.PREVIEW,
            requestedTtlSeconds = ttl,
            requestedCapabilities =
              setOf(
                AgentGrantCapability.UI_BUILDER_READ,
                AgentGrantCapability.UI_BUILDER_WRITE,
                AgentGrantCapability.UI_BUILDER_EXPORT,
              ),
            requesterActorId = requester,
            designIds = designIds,
          )
        if (request == null) {
          call.response.headers.append(HttpHeaders.RetryAfter, "60")
          call.respondText(
            "too many pending access requests on this server; try again shortly",
            status = HttpStatusCode.TooManyRequests,
          )
          return
        }
        // POST, redirect, GET: the page showing the link to send is drawn by the GET below, so a
        // refresh re-reads the same request instead of opening another.
        redirectToRequestedAccess(request.id)
        return
      } finally {
        permit.release()
      }
    }
    // The landing of that redirect. Only the requester's own, still-waiting request is shown: the
    // id is in the URL, and a URL is not a credential.
    val requested =
      call.request.queryParameters[REQUEST_ACCESS_ID_PARAM]
        ?.let { store.request(it) }
        ?.takeIf {
          it.requesterActorId == requester && it.state == ServeAgentGrantStore.Request.State.PENDING
        }
        ?.let { request ->
          ServeWeb.RequestedAccess(
            approveUrl = externalOrigin() + ServeAgentGrants.approvalPath(request.id),
            userCode = request.userCode,
            expiresInSeconds = request.secondsUntilExpiry(System.currentTimeMillis()),
            designIds = request.designIds.sorted(),
          )
        }
    markGeneration("static-page", "no-store")
    call.respondText(
      ServeWeb.uiBuilderRequestAccessPage(
        login = login,
        formAction = UI_BUILDER_REQUEST_ACCESS_PATH + agentGrantTokenQuery(),
        csrf = seal,
        ttlChoicesSeconds = ttlChoices.ifEmpty { listOf(store.maxGrantTtlSeconds) },
        activeUntil = active?.let { java.time.Instant.ofEpochMilli(it.expiresAtMillis).toString() },
        requested = requested,
        designId = designParam,
        activeDesignIds = active?.designIds?.sorted().orEmpty(),
        navSuffix = agentGrantTokenQuery(),
        version = SERVE_VERSION,
        siteName = skin.first,
        themeCss = skin.second,
      ),
      ContentType.Text.Html,
    )
  }

  private suspend fun RoutingContext.redirectToRequestedAccess(requestId: String) {
    val token = agentGrantTokenQuery().removePrefix("?")
    call.response.headers.append(
      HttpHeaders.Location,
      "$UI_BUILDER_REQUEST_ACCESS_PATH?$REQUEST_ACCESS_ID_PARAM=" +
        WebEscaping.urlEncodeSegment(requestId) +
        (if (token.isEmpty()) "" else "&$token"),
    )
    call.respond(HttpStatusCode.SeeOther)
  }

  /**
   * `POST /agent-access/poll`: the agent collects the outcome with its device secret. Negative
   * answers are 200 with a status field ("not yet" is expected). Unknown id and wrong secret answer
   * identically.
   */
  private suspend fun RoutingContext.handleAgentGrantPoll(store: ServeAgentGrantStore) {
    val permit = acquireAgentGrantPermit() ?: return
    try {
      val body =
        withContext(Dispatchers.IO) {
          call.receiveStream().use { readCapped(it, MAX_AGENT_GRANT_BYTES) }
        }
      val parsed =
        try {
          JSON.decodeFromString(
            ServeAgentGrants.PollRequest.serializer(),
            body?.decodeToString()?.trim().orEmpty().ifEmpty { "{}" },
          )
        } catch (e: Exception) {
          call.respondText("invalid poll: ${e.message}", status = HttpStatusCode.BadRequest)
          return
        }
      val response =
        pollAgentGrantAwaiting(store, parsed.requestId, parsed.deviceSecret, parsed.waitSeconds)
      call.respondText(
        JSON.encodeToString(ServeAgentGrants.PollResponse.serializer(), response),
        ContentType.Application.Json,
      )
    } finally {
      permit.release()
    }
  }

  /**
   * One poll outcome, shared by `/agent-access/poll` and the catalog MCP's `poll_access`, so both
   * speak one vocabulary.
   */
  /**
   * [pollAgentGrant], held open up to [waitSeconds] (clamped) while `pending`. A quarter-second
   * re-read loop over the synchronized store rather than a per-request `CompletableDeferred`;
   * switch only if it shows in a profile. Waiting holds one rate-limit permit rather than
   * re-charging.
   */
  private suspend fun pollAgentGrantAwaiting(
    store: ServeAgentGrantStore,
    requestId: String,
    deviceSecret: String,
    waitSeconds: Long,
  ): ServeAgentGrants.PollResponse {
    val immediate = pollAgentGrant(store, requestId, deviceSecret)
    val wait = waitSeconds.coerceIn(0, ServeAgentGrants.MAX_POLL_WAIT_SECONDS)
    // Only `pending` is worth waiting on; `unknown` (also a wrong secret) answers at once so
    // guesses can't occupy connections.
    if (wait <= 0 || immediate.status != ServeAgentGrants.PollResponse.PENDING) return immediate
    val deadline = System.currentTimeMillis() + wait * 1000
    while (System.currentTimeMillis() < deadline) {
      delay(ServeAgentGrants.POLL_WAIT_TICK_MILLIS)
      val next = pollAgentGrant(store, requestId, deviceSecret)
      if (next.status != ServeAgentGrants.PollResponse.PENDING) return next
    }
    // Reached the deadline still pending: answer what an immediate poll would have, freshly read
    // so `expiresInSeconds` reflects the time actually spent waiting.
    return pollAgentGrant(store, requestId, deviceSecret)
  }

  private fun pollAgentGrant(
    store: ServeAgentGrantStore,
    requestId: String,
    deviceSecret: String,
  ): ServeAgentGrants.PollResponse =
    when (val outcome = store.poll(requestId, deviceSecret)) {
      is ServeAgentGrantStore.Poll.Pending ->
        ServeAgentGrants.PollResponse(
          status = ServeAgentGrants.PollResponse.PENDING,
          retryAfterSeconds = ServeAgentGrantStore.POLL_INTERVAL_SECONDS,
          expiresInSeconds = outcome.secondsUntilExpiry,
          message = "waiting for a human to approve this request",
        )
      is ServeAgentGrantStore.Poll.Approved ->
        ServeAgentGrants.PollResponse(
          status = ServeAgentGrants.PollResponse.APPROVED,
          token = outcome.grant.token,
          tokenHeader = TOKEN_HEADER,
          scopes = outcome.grant.scopes.map { it.wire },
          capabilities = AgentGrantCapability.wireNames(outcome.grant.capabilities),
          expiresInSeconds = outcome.grant.secondsUntilExpiry(System.currentTimeMillis()),
          approvedBy = outcome.grant.approvedBy,
          message = "approved by ${outcome.grant.approvedBy}",
        )
      is ServeAgentGrantStore.Poll.Denied ->
        ServeAgentGrants.PollResponse(
          status = ServeAgentGrants.PollResponse.DENIED,
          approvedBy = outcome.by,
          message = "the request was declined",
        )
      ServeAgentGrantStore.Poll.Expired ->
        ServeAgentGrants.PollResponse(
          status = ServeAgentGrants.PollResponse.EXPIRED,
          message = "the request expired before it was approved",
        )
      ServeAgentGrantStore.Poll.Unknown ->
        ServeAgentGrants.PollResponse(
          status = ServeAgentGrants.PollResponse.UNKNOWN,
          message = "no such access request",
        )
    }

  /**
   * The grant flow as the catalog MCP's access tools see it, charged to the same per-address budget
   * as the `/agent-access/…` routes. A throttled call answers null, which MCP turns into a tool
   * error.
   */
  private fun RoutingContext.catalogMcpAgentAccess(
    store: ServeAgentGrantStore
  ): ServeCatalogMcp.AgentAccess =
    object : ServeCatalogMcp.AgentAccess {
      override suspend fun open(
        label: String,
        scope: String,
        ttlSeconds: Long,
        capabilities: List<String>,
      ): String? {
        val permit = tryAgentGrantPermit() ?: return null
        try {
          val response =
            openAgentGrantRequest(
              store,
              ServeAgentGrants.OpenRequest(
                label = label,
                scope = scope,
                ttlSeconds = ttlSeconds,
                capabilities = capabilities,
              ),
            ) ?: return null
          return JSON.encodeToString(ServeAgentGrants.OpenResponse.serializer(), response)
        } finally {
          permit.release()
        }
      }

      override suspend fun poll(
        requestId: String,
        deviceSecret: String,
        waitSeconds: Long,
      ): String? {
        val permit = tryAgentGrantPermit() ?: return null
        try {
          return JSON.encodeToString(
            ServeAgentGrants.PollResponse.serializer(),
            pollAgentGrantAwaiting(store, requestId, deviceSecret, waitSeconds),
          )
        } finally {
          permit.release()
        }
      }

      override fun approvalUrl(requestId: String): String =
        externalOrigin() + ServeAgentGrants.approvalPath(requestId)
    }

  /**
   * `GET /agent-access/whoami`: what the presented bearer is, without echoing it. No (or a dead)
   * grant gets 200 with `active: false`.
   */
  private suspend fun RoutingContext.handleAgentGrantWhoami(store: ServeAgentGrantStore) {
    val grant = agentGrantFor(call)
    val response =
      if (grant == null) {
        val state = presentedTokenState(store)
        ServeAgentGrants.WhoamiResponse(
          active = false,
          reason = state.wire,
          message = whoamiReasonMessage(state),
        )
      } else
        ServeAgentGrants.WhoamiResponse(
          active = true,
          scopes = grant.scopes.map { it.wire },
          capabilities = AgentGrantCapability.wireNames(grant.capabilities),
          expiresInSeconds = grant.secondsUntilExpiry(System.currentTimeMillis()),
          approvedBy = grant.approvedBy,
          label = grant.label,
          fingerprint = grant.fingerprint,
          actorId = ServeAgentGrants.agentActorId(grant.fingerprint),
          onBehalfOfActorId = grant.approvedByActorId.takeIf { it.isNotBlank() },
          designIds = grant.designIds.sorted(),
        )
    call.respondText(
      JSON.encodeToString(ServeAgentGrants.WhoamiResponse.serializer(), response),
      ContentType.Application.Json,
    )
  }

  /**
   * The best classification across the three sources [resolveAgentGrant] reads; they are
   * independent, so report the most informative state.
   */
  private fun RoutingContext.presentedTokenState(
    store: ServeAgentGrantStore
  ): ServeAgentGrantStore.TokenState {
    val bearer =
      call.request.headers[HttpHeaders.Authorization]
        ?.takeIf { it.startsWith(BEARER_PREFIX, ignoreCase = true) }
        ?.substring(BEARER_PREFIX.length)
        ?.trim()
    val states =
      sequenceOf(
          call.request.headers[TOKEN_HEADER],
          bearer,
          call.request.queryParameters["token"],
        )
        .map(store::describeToken)
        .toList()
    // Ordered by how much it tells the caller: a recognised-but-dead token outranks silence.
    return listOf(
        ServeAgentGrantStore.TokenState.LIVE,
        ServeAgentGrantStore.TokenState.EXPIRED,
        ServeAgentGrantStore.TokenState.UNKNOWN,
        ServeAgentGrantStore.TokenState.MALFORMED,
      )
      .firstOrNull { it in states } ?: ServeAgentGrantStore.TokenState.ABSENT
  }

  private fun whoamiReasonMessage(state: ServeAgentGrantStore.TokenState): String =
    when (state) {
      ServeAgentGrantStore.TokenState.LIVE -> "this grant is live"
      ServeAgentGrantStore.TokenState.ABSENT ->
        "no credential was presented; request one at ${ServeAgentGrants.REQUEST_PATH}"
      ServeAgentGrantStore.TokenState.MALFORMED ->
        "the presented credential is not shaped like a grant token"
      ServeAgentGrantStore.TokenState.EXPIRED ->
        "this grant has expired; request a new one at ${ServeAgentGrants.REQUEST_PATH}"
      ServeAgentGrantStore.TokenState.UNKNOWN ->
        "this server does not know that token: it was revoked, or it was issued by a previous " +
          "run of this server — grants are held in memory and do not survive a restart. " +
          "Request a new one at ${ServeAgentGrants.REQUEST_PATH}"
    }

  /** `POST /agent-access/revoke` — an agent hands its own access back early. */
  private suspend fun RoutingContext.handleAgentGrantRevoke(store: ServeAgentGrantStore) {
    val grant = agentGrantFor(call)
    val revoked = grant != null && store.revoke(grant.id, "the agent itself")
    if (grant != null) mcpOAuth.forgetRefreshFor(grant.id)
    call.respondText(
      JSON.encodeToString(
        ServeAgentGrants.RevokeResponse.serializer(),
        ServeAgentGrants.RevokeResponse(
          revoked = revoked,
          message = if (revoked) "access revoked" else "no live grant was presented",
        ),
      ),
      ContentType.Application.Json,
    )
  }

  /**
   * `POST /agent-access/{grantId}/revoke`: the `/status` revoke button. Requires an approver
   * identity; a grant can't revoke another. On `--public` a signed-in visitor may revoke only
   * grants they approved ([agentGrantStatusRows]); anything else answers like an unknown id.
   */
  private suspend fun RoutingContext.handleAgentGrantRevokeFromStatus(store: ServeAgentGrantStore) {
    val approver = agentGrantApprover(store)
    if (approver == null) {
      call.respondText("not found", status = HttpStatusCode.NotFound)
      return
    }
    val grantId = call.parameters["grantId"].orEmpty()
    val csrf = call.receiveFormField("csrf")
    if (!agentGrantCsrf.verify(grantId, approver.name, ServeAgentGrants.Csrf.ACTION_DENY, csrf)) {
      call.respondText("not found", status = HttpStatusCode.NotFound)
      return
    }
    val grant = store.grant(grantId)
    if (grant != null && !approver.manages(grant)) {
      call.respondText("not found", status = HttpStatusCode.NotFound)
      return
    }
    store.revoke(grantId, approver.name)
    // Its refresh tokens go with it now rather than sitting in the bounded map until someone
    // presents one.
    mcpOAuth.forgetRefreshFor(grantId)
    call.respondRedirect("/status" + agentGrantTokenQuery())
  }

  /**
   * `GET /agent-access/{requestId}`: the approval page. Authenticate before resolving, so an
   * anonymous caller learns nothing about whether the id exists.
   */
  private suspend fun RoutingContext.handleAgentGrantPage(store: ServeAgentGrantStore) {
    val approver = agentGrantApprover(store)
    if (approver == null) {
      respondAgentGrantSignIn()
      return
    }
    val request = store.request(call.parameters["requestId"])
    // Where the decision form lands (POST/redirect/GET); the outcome is read back from the store,
    // so a refresh doesn't resubmit.
    if (request != null && respondAgentGrantOutcome(store, request)) return
    if (request == null || request.state != ServeAgentGrantStore.Request.State.PENDING) {
      respondAgentGrantNotice(
        heading = "Nothing to approve",
        message =
          "That access request is not waiting for a decision — it expired, or it has already " +
            "been approved or declined. Ask the agent to request access again.",
        status = HttpStatusCode.NotFound,
      )
      return
    }
    val selectable =
      ServeAgentGrants.selectableScopes(request.requestedScope, approver, store.maxScope)
    val withheld = AgentGrantScope.upTo(request.requestedScope).filterNot { it in selectable }
    val selectableCapabilities =
      ServeAgentGrants.selectableCapabilities(
        request.requestedCapabilities,
        approver,
        store.maxCapabilities,
      )
    // Capabilities withheld by the approver's own holdings vs by this box's ceiling (an operator
    // flag) are named separately, since the remedies differ.
    val withheldCapabilities =
      request.requestedCapabilities.filterNot { it in selectableCapabilities }
    val storeNarrowedCapabilities = withheldCapabilities.filterNot { it in store.maxCapabilities }
    val requestedDesigns = requestedDesigns(request, approver)
    val skin = call.siteSkin()
    markGeneration("static-page", "no-store")
    // Approving an OAuth authorization answers with a redirect to the client's own URI, and the
    // page's `form-action` has to admit that destination for the browser to follow it.
    mcpOAuth.forRequest(request.id)?.let { ServePagePolicy.allowFormAction(call, it.redirectUri) }
    call.respondText(
      ServeWeb.agentGrantApprovalPage(
        requestId = request.id,
        userCode = request.userCode,
        label = request.label,
        client = request.client,
        requestedScope = request.requestedScope,
        requestedTtlSeconds = request.requestedTtlSeconds,
        expiresInSeconds = request.secondsUntilExpiry(System.currentTimeMillis()),
        approver = approver.name,
        selectableScopes = selectable,
        maxTtlSeconds = minOf(store.maxGrantTtlSeconds, request.requestedTtlSeconds),
        approveCsrf =
          agentGrantCsrf.seal(request.id, approver.name, ServeAgentGrants.Csrf.ACTION_APPROVE),
        denyCsrf =
          agentGrantCsrf.seal(request.id, approver.name, ServeAgentGrants.Csrf.ACTION_DENY),
        formAction = ServeAgentGrants.approvalPath(request.id) + agentGrantTokenQuery(),
        navSuffix = agentGrantTokenQuery(),
        version = SERVE_VERSION,
        siteName = skin.first,
        themeCss = skin.second,
        selectableCapabilities =
          AgentGrantCapability.entries.filter { it in selectableCapabilities },
        withheldScopes = withheld,
        withheldCapabilities = withheldCapabilities - storeNarrowedCapabilities.toSet(),
        withheldReason = "you do not hold it yourself on this server, so you cannot pass it on",
        storeNarrowedCapabilities = storeNarrowedCapabilities,
        storeNarrowedReason =
          "this server's --agent-grant-capabilities does not include it, so no tick could " +
            "grant it — the operator would have to add the capability and restart",
        oauthReturn =
          mcpOAuth.forRequest(request.id)?.let { ServeMcpOAuth.describeRedirect(it.redirectUri) },
        requestedDesigns = requestedDesigns,
      ),
      ContentType.Text.Html,
    )
  }

  /**
   * The designs [request] names, titled as [approver] sees them; designs they can't see show only
   * the id.
   */
  private suspend fun requestedDesigns(
    request: ServeAgentGrantStore.Request,
    approver: ServeAgentGrants.Approver,
  ): List<ServeWeb.RequestedDesign> {
    if (request.designIds.isEmpty()) return emptyList()
    val service = designService
    val titles =
      if (service == null || approver.actorId.isBlank()) emptyMap()
      else
        with(ServeUiBuilderGrantScope) {
            runCatching {
                service.listed(AuthenticatedUiBuilderActor(approver.actorId), request.designIds)
              }
              .getOrDefault(emptyList())
          }
          .associate { it.designId to it.title }
    return request.designIds.sorted().map { ServeWeb.RequestedDesign(it, titles[it].orEmpty()) }
  }

  /**
   * `POST /agent-access/{requestId}`: the decision. Three locks, each covering another's hole:
   * `SameSite=Lax` blocks cross-site cookie posts but not URL tokens; `?token=` stops strangers but
   * not a tricked operator; the CSRF seal binds the POST to this request, approver and action.
   */
  private suspend fun RoutingContext.handleAgentGrantDecision(store: ServeAgentGrantStore) {
    val approver = agentGrantApprover(store)
    if (approver == null) {
      respondAgentGrantSignIn()
      return
    }
    val requestId = call.parameters["requestId"].orEmpty()
    val form = call.receiveFormParameters()
    val action = form["action"]?.firstOrNull().orEmpty()
    val deny = action == ServeAgentGrants.Csrf.ACTION_DENY
    val seal = if (deny) form["denyCsrf"]?.firstOrNull() else form["csrf"]?.firstOrNull()
    val expected =
      if (deny) ServeAgentGrants.Csrf.ACTION_DENY else ServeAgentGrants.Csrf.ACTION_APPROVE
    if (!agentGrantCsrf.verify(requestId, approver.name, expected, seal)) {
      respondAgentGrantNotice(
        heading = "That form went stale",
        message =
          "This approval form was not issued to you, or the server restarted since it was drawn. " +
            "Open the link again and re-check the verification code.",
        status = HttpStatusCode.Forbidden,
      )
      return
    }
    if (deny) {
      // Check the return value: if another operator approved first, saying "nothing was granted"
      // would be false.
      if (store.deny(requestId, approver.name)) {
        // An OAuth client is owed the refusal on its own redirect URI — RFC 6749 §4.1.2.1 — rather
        // than being left to time out while the human reads a page it will never see.
        mcpOAuth.forRequest(requestId)?.let { authorization ->
          call.respondRedirect(
            ServeMcpOAuth.redirectWithError(
              authorization.redirectUri,
              "access_denied",
              "The request was declined.",
              authorization.state,
            )
          )
          return
        }
        redirectToAgentGrantOutcome(requestId)
      } else {
        respondAgentGrantNotice(
          heading = "Already decided",
          message =
            "This request was resolved before your decision arrived — most likely approved by " +
              "someone else holding the same page. Nothing was declined. If a grant is live and " +
              "you want it stopped, revoke it from the server status page.",
          status = HttpStatusCode.Conflict,
        )
      }
      return
    }
    // The approver's ticks are capped again here: the store clamps regardless, but this keeps the
    // audit line honest.
    // The page posts one radio value ([ServeWeb.agentGrantApprovalPage]), but `maxOrNull` handles
    // several, clamped by the ceilings.
    val ticked =
      form["scope"].orEmpty().mapNotNull { AgentGrantScope.parse(it) }.maxOrNull()
        ?: AgentGrantScope.PREVIEW
    val chosen = minOf(ticked, approver.ceiling)
    // Capabilities are independent checkboxes; absent means not granted, so nothing defaults to the
    // request.
    val tickedCapabilities =
      form["capability"].orEmpty().mapNotNull { AgentGrantCapability.parse(it) }.toSet()
    val chosenCapabilities = tickedCapabilities intersect approver.capabilityCeiling
    val ttl =
      form["ttl"]?.firstOrNull()?.let { AgentGrantProtocol.parseDurationSeconds(it) }
        ?: ServeAgentGrantStore.DEFAULT_GRANT_TTL_SECONDS
    // A request that names a design is approved for it alone unless the approver chose every
    // design they can edit instead. Anything but that explicit choice keeps the request's designs.
    val limitToRequestedDesigns = form["designScope"]?.firstOrNull() != ServeWeb.DESIGN_SCOPE_ALL
    val grant =
      store.approve(
        requestId,
        approver.name,
        chosen,
        ttl,
        chosenCapabilities,
        approver.actorId,
        enforceApproverCap = !approver.administers,
        limitToRequestedDesigns = limitToRequestedDesigns,
      )
    if (
      grant == null && store.request(requestId)?.state == ServeAgentGrantStore.Request.State.PENDING
    ) {
      // Still pending, so the refusal was a limit on live grants, not a stale request. The request
      // stays open: revoking a grant and pressing Approve again completes it.
      val full =
        store.capacityFor(approver.name, approver.actorId, !approver.administers) ==
          ServeAgentGrantStore.Capacity.APPROVER_FULL
      respondAgentGrantNotice(
        heading = "No room for another grant",
        message =
          if (full)
            "You already have as many live grants on this server as one approver may hold. " +
              "Revoke one you no longer need from the server status page, then open this link " +
              "again and approve."
          else
            "This server already holds as many live grants as it allows. Revoke one from the " +
              "server status page, or wait for one to expire, then open this link again and approve.",
        status = HttpStatusCode.Conflict,
      )
      return
    }
    if (grant == null) {
      // Retrying a submitted OAuth approval must resume its outstanding callback too. The
      // original CSRF seal was verified above; no new grant is minted by this return leg.
      if (mcpOAuth.forRequest(requestId) != null) {
        store.request(requestId)?.let { request ->
          if (respondAgentGrantOutcome(store, request)) return
        }
      }
      respondAgentGrantNotice(
        heading = "Nothing to approve",
        message =
          "That access request is no longer waiting for a decision — it expired, or it was " +
            "already resolved.",
        status = HttpStatusCode.NotFound,
      )
      return
    }
    // The OAuth return leg: send the browser back with the code so the client can redeem the same
    // grant.
    mcpOAuth.forRequest(requestId)?.let { authorization ->
      call.respondRedirect(
        ServeMcpOAuth.redirectWithCode(
          authorization.redirectUri,
          authorization.code,
          authorization.state,
        )
      )
      return
    }
    redirectToAgentGrantOutcome(requestId)
  }

  /** `303` back to the approval link, which now shows the outcome ([respondAgentGrantOutcome]). */
  private suspend fun RoutingContext.redirectToAgentGrantOutcome(requestId: String) {
    call.response.headers.append(
      HttpHeaders.Location,
      ServeAgentGrants.approvalPath(requestId) + agentGrantTokenQuery(),
    )
    call.respond(HttpStatusCode.SeeOther)
  }

  /**
   * The approval link once decided: what was granted (while live) or that it was declined. False
   * when pending or the grant has since expired/been revoked.
   */
  private suspend fun RoutingContext.respondAgentGrantOutcome(
    store: ServeAgentGrantStore,
    request: ServeAgentGrantStore.Request,
  ): Boolean {
    // Revisiting after approval (another tab, interrupted callback) completes the OAuth return leg
    // while its code is outstanding. Redeeming removes the binding, so it never replays.
    mcpOAuth.forRequest(request.id)?.let { authorization ->
      when (request.state) {
        ServeAgentGrantStore.Request.State.PENDING -> Unit
        ServeAgentGrantStore.Request.State.DENIED -> {
          call.respondRedirect(
            ServeMcpOAuth.redirectWithError(
              authorization.redirectUri,
              "access_denied",
              "The request was declined.",
              authorization.state,
            )
          )
          return true
        }
        ServeAgentGrantStore.Request.State.APPROVED -> {
          if (store.grant(request.grantId) != null) {
            call.respondRedirect(
              ServeMcpOAuth.redirectWithCode(
                authorization.redirectUri,
                authorization.code,
                authorization.state,
              )
            )
            return true
          }
        }
      }
    }
    when (request.state) {
      ServeAgentGrantStore.Request.State.PENDING -> return false
      ServeAgentGrantStore.Request.State.DENIED -> {
        respondAgentGrantNotice(
          heading = "Access declined",
          message =
            "Nothing was granted" +
              (request.resolvedBy?.let { " — declined by $it" } ?: "") +
              ". The agent has been told its request was declined.",
        )
        return true
      }
      ServeAgentGrantStore.Request.State.APPROVED -> {
        val grant = store.grant(request.grantId) ?: return false
        respondAgentGrantNotice(
          heading = "Access granted",
          message =
            "The agent can now use this server for " +
              AgentGrantProtocol.formatDuration(
                grant.secondsUntilExpiry(System.currentTimeMillis())
              ) +
              ". You can end it early from the server status page at any time.",
          detail =
            buildString {
              append("Scopes: ${grant.scopes.joinToString(", ") { it.wire }}")
              if (grant.capabilities.isNotEmpty()) {
                val names = AgentGrantCapability.wireNames(grant.capabilities).joinToString(", ")
                append(" · also: $names")
              }
              if (grant.designIds.isNotEmpty()) {
                append(" · designs: ${grant.designIds.sorted().joinToString(", ")}")
              }
              append(" · grant ${grant.fingerprint}")
              append(" · approved by ${grant.approvedBy}")
            },
        )
        return true
      }
    }
  }

  // OAuth façade: seven routes adding no authority of their own, so MCP clients following the fixed
  // 401 script can complete it ([ServeMcpOAuth]).

  /** RFC 9728: what protects `/mcp`, and which server issues tokens for it. */
  private suspend fun RoutingContext.respondProtectedResourceMetadata(store: ServeAgentGrantStore) {
    val origin = externalOrigin()
    markGeneration("static-page", "no-store")
    call.respondText(
      JSON.encodeToString(
        ServeMcpOAuth.ProtectedResourceMetadata.serializer(),
        ServeMcpOAuth.ProtectedResourceMetadata(
          resource = origin + ServeMcpOAuth.MCP_RESOURCE_PATH,
          authorizationServers = listOf(origin),
          scopesSupported = ServeMcpOAuth.scopesSupported(store.maxScope, store.maxCapabilities),
          resourceDocumentation = origin + ServeAgentGrants.BASE_PATH,
        ),
      ),
      ContentType.Application.Json,
    )
  }

  /** RFC 8414: where to register, where to send the human, where to redeem the code. */
  private suspend fun RoutingContext.respondAuthorizationServerMetadata(
    store: ServeAgentGrantStore
  ) {
    val origin = externalOrigin()
    markGeneration("static-page", "no-store")
    call.respondText(
      JSON.encodeToString(
        ServeMcpOAuth.AuthorizationServerMetadata.serializer(),
        ServeMcpOAuth.AuthorizationServerMetadata(
          issuer = origin,
          authorizationEndpoint = origin + ServeMcpOAuth.AUTHORIZE_PATH,
          tokenEndpoint = origin + ServeMcpOAuth.TOKEN_PATH,
          registrationEndpoint = origin + ServeMcpOAuth.REGISTER_PATH,
          scopesSupported = ServeMcpOAuth.scopesSupported(store.maxScope, store.maxCapabilities),
        ),
      ),
      ContentType.Application.Json,
    )
  }

  /**
   * RFC 7591 dynamic client registration. Ungated and rate limited like `POST
   * /agent-access/request`: it confers nothing, and a `client_id` only correlates an authorization
   * with its redirect URIs.
   */
  private suspend fun RoutingContext.handleOAuthRegister() {
    val permit = acquireAgentGrantPermit() ?: return
    try {
      val body =
        withContext(Dispatchers.IO) {
          call.receiveStream().use { readCapped(it, MAX_AGENT_GRANT_BYTES) }
        }
      if (body == null) {
        respondOAuthError(
          HttpStatusCode.PayloadTooLarge,
          "invalid_client_metadata",
          "Registration body too large.",
        )
        return
      }
      val parsed =
        try {
          JSON.decodeFromString(
            ServeMcpOAuth.ClientRegistrationRequest.serializer(),
            body.decodeToString().trim().ifEmpty { "{}" },
          )
        } catch (e: Exception) {
          respondOAuthError(
            HttpStatusCode.BadRequest,
            "invalid_client_metadata",
            "Could not parse registration body: ${e.message}",
          )
          return
        }
      // A public client with no redirect URI can never complete an authorization, so refuse now
      // with a clear reason.
      if (parsed.redirectUris.isEmpty()) {
        respondOAuthError(
          HttpStatusCode.BadRequest,
          "invalid_redirect_uri",
          "At least one redirect_uri is required.",
        )
        return
      }
      if (parsed.redirectUris.any { runCatching { URI(it) }.getOrNull()?.scheme == null }) {
        respondOAuthError(
          HttpStatusCode.BadRequest,
          "invalid_redirect_uri",
          "Every redirect_uri must be an absolute URI.",
        )
        return
      }
      val client = mcpOAuth.register(parsed.clientName, parsed.redirectUris)
      if (client == null) {
        call.response.headers.append(HttpHeaders.RetryAfter, "60")
        respondOAuthError(
          HttpStatusCode.TooManyRequests,
          "invalid_client_metadata",
          "Too many registered clients on this server; try again shortly.",
        )
        return
      }
      call.respondText(
        JSON.encodeToString(
          ServeMcpOAuth.ClientRegistrationResponse.serializer(),
          ServeMcpOAuth.ClientRegistrationResponse(
            clientId = client.clientId,
            clientIdIssuedAt = client.issuedAtMillis / 1000,
            redirectUris = client.redirectUris,
            clientName = client.clientName,
          ),
        ),
        ContentType.Application.Json,
        HttpStatusCode.Created,
      )
    } finally {
      permit.release()
    }
  }

  /**
   * `GET /oauth/authorize`: the human's leg. Opens an ordinary grant request and redirects to the
   * existing approval page, so there is one place access is granted, with one set of ceilings and
   * one audit line. OAuth only adds where to return.
   */
  private suspend fun RoutingContext.handleOAuthAuthorize(store: ServeAgentGrantStore) {
    // Charged to the same per-address budget as its siblings: it creates the most state (a grant
    // request and an OAuth pending entry) with no credential.
    val permit = acquireAgentGrantPermit() ?: return
    try {
      authorizeThroughApprovalPage(store)
    } finally {
      permit.release()
    }
  }

  private suspend fun RoutingContext.authorizeThroughApprovalPage(store: ServeAgentGrantStore) {
    val query = call.request.queryParameters
    val clientId = query["client_id"]
    val redirectUri = query["redirect_uri"]
    val state = query["state"].orEmpty()
    val client = mcpOAuth.client(clientId)
    when (
      val rejection =
        ServeMcpOAuth.validateAuthorize(
          client = client,
          redirectUri = redirectUri,
          responseType = query["response_type"],
          codeChallenge = query["code_challenge"],
          codeChallengeMethod = query["code_challenge_method"],
        )
    ) {
      // Never redirected: the target is exactly what could not be trusted, and bouncing an error
      // to an unvalidated URI is an open redirector (RFC 6749 §4.1.2.1).
      is ServeMcpOAuth.AuthorizeRejection.Unredirectable -> {
        respondAgentGrantNotice(
          heading = "That authorization request cannot be completed",
          message = rejection.description,
          status = HttpStatusCode.BadRequest,
        )
        return
      }
      is ServeMcpOAuth.AuthorizeRejection.Redirectable -> {
        call.respondRedirect(
          ServeMcpOAuth.redirectWithError(
            redirectUri!!,
            rejection.error,
            rejection.description,
            state,
          )
        )
        return
      }
      null -> Unit
    }
    val wanted = ServeMcpOAuth.parseScope(query["scope"])
    val request =
      store.openRequest(
        // The client's registered name, which it wrote. The approval page shows it as the client's
        // own choice, below the redirect host it leads with; see [ServeWeb.agentGrantApprovalPage].
        label = client!!.clientName.ifBlank { "MCP client" },
        client = clientAddress(),
        requestedScope = wanted.scope,
        requestedTtlSeconds = ServeAgentGrantStore.DEFAULT_GRANT_TTL_SECONDS,
        requestedCapabilities = wanted.capabilities,
      )
    if (request == null) {
      call.respondRedirect(
        ServeMcpOAuth.redirectWithError(
          redirectUri!!,
          "temporarily_unavailable",
          "Too many pending access requests on this server; try again shortly.",
          state,
        )
      )
      return
    }
    val authorization =
      mcpOAuth.open(
        requestId = request.id,
        clientId = client.clientId,
        redirectUri = redirectUri!!,
        codeChallenge = query["code_challenge"]!!,
        state = state,
        resource = query["resource"].orEmpty(),
      )
    if (authorization == null) {
      call.respondRedirect(
        ServeMcpOAuth.redirectWithError(
          redirectUri,
          "temporarily_unavailable",
          "Too many authorizations in flight on this server; try again shortly.",
          state,
        )
      )
      return
    }
    // The approval page carries the operator token forward the same way every other link to it
    // does, so a private box does not bounce the human to a sign-in they cannot satisfy from here.
    call.respondRedirect(ServeAgentGrants.approvalPath(request.id) + agentGrantTokenQuery())
  }

  /**
   * `POST /oauth/token`: the client's leg. Public clients (`token_endpoint_auth_method=none`),
   * proven by the PKCE verifier. Returns the grant's own token — same string, expiry and revocation
   * as the device poll.
   */
  private suspend fun RoutingContext.handleOAuthToken(store: ServeAgentGrantStore) {
    val permit = acquireAgentGrantPermit() ?: return
    try {
      val form = call.receiveFormParameters()
      when (form["grant_type"]?.firstOrNull()) {
        "authorization_code" -> Unit
        "refresh_token" -> {
          refreshOAuthToken(store, form)
          return
        }
        else -> {
          respondOAuthError(
            HttpStatusCode.BadRequest,
            "unsupported_grant_type",
            "Only grant_type=authorization_code and grant_type=refresh_token are supported.",
          )
          return
        }
      }
      // Redeemed before anything is checked, so a replay cannot find the entry twice even while
      // the first attempt is in flight (RFC 6749 §10.5).
      val authorization = mcpOAuth.redeem(form["code"]?.firstOrNull())
      if (authorization == null) {
        respondOAuthError(
          HttpStatusCode.BadRequest,
          "invalid_grant",
          "Unknown, expired, or already-redeemed authorization code.",
        )
        return
      }
      if (form["client_id"]?.firstOrNull() != authorization.clientId) {
        respondOAuthError(
          HttpStatusCode.BadRequest,
          "invalid_grant",
          "This code was issued to a different client.",
        )
        return
      }
      // RFC 6749 §4.1.3: when the authorization carried a redirect_uri, the token request must
      // repeat it identically.
      val presentedRedirect = form["redirect_uri"]?.firstOrNull()
      if (presentedRedirect != null && presentedRedirect != authorization.redirectUri) {
        respondOAuthError(
          HttpStatusCode.BadRequest,
          "invalid_grant",
          "redirect_uri does not match the one this code was issued for.",
        )
        return
      }
      if (
        !ServeMcpOAuth.verifyPkce(
          authorization.codeChallenge,
          form["code_verifier"]?.firstOrNull(),
        )
      ) {
        respondOAuthError(
          HttpStatusCode.BadRequest,
          "invalid_grant",
          "code_verifier does not match the code_challenge this authorization was opened with.",
        )
        return
      }
      // Still pending: the code was consumed by redeeming, so tell the client to start over rather
      // than retry.
      val request = store.request(authorization.requestId)
      val grant =
        when (request?.state) {
          ServeAgentGrantStore.Request.State.APPROVED -> store.grant(request.grantId)
          else -> null
        }
      if (grant == null) {
        respondOAuthError(
          HttpStatusCode.BadRequest,
          "invalid_grant",
          "That authorization was declined, expired, or has not been approved. Start a new " +
            "authorization request.",
        )
        return
      }
      call.respondText(
        JSON.encodeToString(
          ServeMcpOAuth.TokenResponse.serializer(),
          ServeMcpOAuth.TokenResponse(
            accessToken = grant.token,
            expiresIn = grant.secondsUntilExpiry(System.currentTimeMillis()),
            scope = ServeMcpOAuth.formatScope(grant),
            refreshToken =
              mcpOAuth.issueRefresh(grant.id, authorization.clientId) { store.grant(it) != null },
          ),
        ),
        ContentType.Application.Json,
      )
    } finally {
      permit.release()
    }
  }

  /**
   * `grant_type=refresh_token`: renew within the human-approved session. Mints and extends nothing:
   * if the grant is still live, return the same bearer with its remaining life; a revoked or lapsed
   * grant refuses and its refresh tokens die with it. Lets a client that lost its token mid-session
   * recover without re-asking a person.
   */
  private suspend fun RoutingContext.refreshOAuthToken(
    store: ServeAgentGrantStore,
    form: Map<String, List<String>>,
  ) {
    val clientId = form["client_id"]?.firstOrNull()
    // Consumed before anything is checked, so a replay finds nothing even if this attempt fails.
    val binding = mcpOAuth.redeemRefresh(form["refresh_token"]?.firstOrNull(), clientId)
    if (binding == null) {
      respondOAuthError(
        HttpStatusCode.BadRequest,
        "invalid_grant",
        "Unknown, already-used, or wrong-client refresh token.",
      )
      return
    }
    val grant = store.grant(binding.grantId)
    if (grant == null) {
      // The grant is gone, so every token bound to it is too — including any sibling this
      // rotation has not seen.
      mcpOAuth.forgetRefreshFor(binding.grantId)
      respondOAuthError(
        HttpStatusCode.BadRequest,
        "invalid_grant",
        "The session this refresh token belonged to has expired or been revoked. Start a new " +
          "authorization request.",
      )
      return
    }
    call.respondText(
      JSON.encodeToString(
        ServeMcpOAuth.TokenResponse.serializer(),
        ServeMcpOAuth.TokenResponse(
          accessToken = grant.token,
          expiresIn = grant.secondsUntilExpiry(System.currentTimeMillis()),
          scope = ServeMcpOAuth.formatScope(grant),
          refreshToken =
            mcpOAuth.issueRefresh(grant.id, binding.clientId) { store.grant(it) != null },
        ),
      ),
      ContentType.Application.Json,
    )
  }

  private suspend fun RoutingContext.respondOAuthError(
    status: HttpStatusCode,
    error: String,
    description: String,
  ) {
    call.respondText(
      JSON.encodeToString(
        ServeMcpOAuth.ErrorResponse.serializer(),
        ServeMcpOAuth.ErrorResponse(error, description),
      ),
      ContentType.Application.Json,
      status,
    )
  }

  /**
   * Who is approving, or null without an operator identity. An agent grant is never an approver:
   * GitHub sessions are cookies agents can't hold, and the token branch compares against `--token`
   * only.
   */
  private fun RoutingContext.agentGrantApprover(
    store: ServeAgentGrantStore
  ): ServeAgentGrants.Approver? {
    // The server's front door first: on a private box with OAuth, a GitHub session alone would let
    // any allowed account approve its own request without the browse token. A `--public` box has no
    // such door.
    val provided = call.request.queryParameters["token"] ?: call.request.headers[TOKEN_HEADER]
    if (!isPublic && !ServeUrls.tokensMatch(serverToken, provided) && !call.browsesByCookie()) {
      return null
    }
    // …then the identity. Neither branch can be satisfied by an agent grant, so a grant can never
    // approve or revoke another.
    val auth = githubAuth
    if (auth != null) {
      val login = auth.currentLogin(call) ?: return null
      return ServeAgentGrants.Approver.github(
        login,
        auth.hasRepositoryAccess(call),
        auth.hasImageRepositoryAccess(call),
        store.maxScope,
        store.maxCapabilities,
        // On `--public` any signed-in visitor approves but only for their own; the `--token` holder
        // and configured UI-builder administrators answer for the whole box.
        administers =
          !isPublic ||
            (serverToken.isNotBlank() && ServeUrls.tokensMatch(serverToken, provided)) ||
            uiBuilderAdministrators.containsGithubLogin(login),
        opensUiBuilder = auth.opensUiBuilder(),
      )
    }
    return ServeAgentGrants.Approver.operator(store.maxScope, store.maxCapabilities)
  }

  /**
   * What to tell someone reaching an approval route without an operator identity: the GitHub
   * sign-in if available, else how to present the token (they are the operator, so this is help,
   * not disclosure).
   */
  private suspend fun RoutingContext.respondAgentGrantSignIn() {
    // A private box needs both token and identity; sending a tokenless visitor to OAuth would loop,
    // so a missing token gets instructions and only a missing session gets sign-in.
    val provided = call.request.queryParameters["token"] ?: call.request.headers[TOKEN_HEADER]
    val hasFrontDoor =
      isPublic || ServeUrls.tokensMatch(serverToken, provided) || call.browsesByCookie()
    if (hasFrontDoor) {
      githubAuth?.let { auth ->
        call.respondRedirect(auth.loginPath(call))
        return
      }
    }
    respondAgentGrantNotice(
      heading = "Sign in to approve",
      message =
        "Only this server's operator can approve an access request. Open this same link from a " +
          "browser that carries the server's access token — append ?token=… to the URL — and the " +
          "approval page will appear" +
          (if (githubAuth != null) ", after signing in with GitHub." else ".") +
          " The server status page lists every waiting request with a link that already carries " +
          "the token.",
      status = HttpStatusCode.Unauthorized,
    )
  }

  private suspend fun RoutingContext.respondAgentGrantNotice(
    heading: String,
    message: String,
    detail: String = "",
    status: HttpStatusCode = HttpStatusCode.OK,
  ) {
    val skin = call.siteSkin()
    markGeneration("static-page", "no-store")
    call.respondText(
      ServeWeb.agentGrantNoticePage(
        heading = heading,
        message = message,
        detail = detail,
        navSuffix = agentGrantTokenQuery(),
        version = SERVE_VERSION,
        siteName = skin.first,
        themeCss = skin.second,
      ),
      ContentType.Text.Html,
      status,
    )
  }

  /**
   * The `?token=…` an approval page's links and form carry forward on a gated box: exactly what
   * this request presented, never the configured value.
   */
  private fun RoutingContext.agentGrantTokenQuery(): String {
    if (isPublic) return ""
    val provided = call.request.queryParameters["token"] ?: return ""
    if (!ServeUrls.tokensMatch(serverToken, provided)) return ""
    return "?token=" + WebEscaping.urlEncodeSegment(provided)
  }

  /** Charge an ungated grant route against its caller's address budget (no identity exists yet). */
  /**
   * The same budget as [acquireAgentGrantPermit] without answering the call, for MCP tools where
   * "too fast" is a tool error.
   */
  private fun RoutingContext.tryAgentGrantPermit(): ServeRateLimiter.Decision.Admitted? {
    val limiter = agentGrantLimiter ?: return ServeRateLimiter.Decision.Admitted {}
    return limiter.tryAcquire(clientAddressKey()) as? ServeRateLimiter.Decision.Admitted
  }

  private suspend fun RoutingContext.acquireAgentGrantPermit():
    ServeRateLimiter.Decision.Admitted? {
    val limiter = agentGrantLimiter ?: return ServeRateLimiter.Decision.Admitted {}
    return when (val decision = limiter.tryAcquire(clientAddressKey())) {
      is ServeRateLimiter.Decision.Admitted -> decision
      is ServeRateLimiter.Decision.Throttled -> {
        call.response.headers.append(HttpHeaders.RetryAfter, decision.retryAfterSeconds.toString())
        call.respondText(
          "Too many requests — ${decision.reason}.",
          status = HttpStatusCode.TooManyRequests,
        )
        null
      }
    }
  }

  /**
   * Read one form-urlencoded field. Parsed by hand because these forms are tiny and server-drawn,
   * and the body cap matters for unauthenticated callers.
   */
  private suspend fun ApplicationCall.receiveFormField(name: String): String? =
    receiveFormParameters()[name]?.firstOrNull()

  private suspend fun ApplicationCall.receiveFormParameters(): Map<String, List<String>> {
    val body =
      withContext(Dispatchers.IO) { receiveStream().use { readCapped(it, MAX_AGENT_GRANT_BYTES) } }
        ?: return emptyMap()
    return parseFormBody(body.decodeToString())
  }

  private suspend fun RoutingContext.rejectMissingGithubAuth(api: Boolean = false): Boolean {
    // A presented grant is judged on its own scope first, regardless of GitHub auth: checking
    // `githubAuth` first would let every grant through on a box without OAuth, letting `preview`
    // grants open live sessions.
    if (rejectGrantBelowScope(AgentGrantScope.LIVE, api)) return true
    if (agentGrantFor(call) != null) return false
    val auth = githubAuth ?: return false
    if (auth.isAuthenticated(call)) return false
    if (api) {
      call.respondText("GitHub sign-in required.", status = HttpStatusCode.Unauthorized)
    } else {
      call.respondRedirect(auth.loginPath(call))
    }
    return true
  }

  /**
   * Refuse a presented grant that doesn't reach [required]. Returns false both when no grant was
   * presented (fall through to the human gate) and when it suffices. A too-small grant gets 403,
   * not a sign-in redirect: an agent's remedy is a wider grant.
   */
  private suspend fun RoutingContext.rejectGrantBelowScope(
    required: AgentGrantScope,
    api: Boolean,
  ): Boolean {
    val grant = grantBelowScope(required) ?: return false
    respondBelowScope(grant, required, api)
    return true
  }

  /**
   * The presented grant when it doesn't reach [required]; null when absent or sufficient. Split out
   * so deferred refusals decide once, since re-asking later would read an expired grant as "no
   * grant" and admit the request.
   */
  private fun RoutingContext.grantBelowScope(
    required: AgentGrantScope
  ): ServeAgentGrantStore.Grant? = agentGrantFor(call)?.takeIf { !it.allows(required) }

  private suspend fun RoutingContext.respondBelowScope(
    grant: ServeAgentGrantStore.Grant,
    required: AgentGrantScope,
    api: Boolean,
  ) {
    val message =
      "This agent grant covers ${grant.scopes.joinToString(", ") { it.wire }}; " +
        "'${required.wire}' was not approved for it. Ask for a wider grant " +
        "(compose-preview auth request --scope ${required.wire})."
    if (api) {
      call.respondText(message, status = HttpStatusCode.Forbidden)
    } else {
      call.respondText(message, ContentType.Text.Plain, HttpStatusCode.Forbidden)
    }
  }

  private suspend fun RoutingContext.rejectMissingGithubRepoAccess(api: Boolean = false): Boolean {
    // Scope first, independent of GitHub auth ([rejectMissingGithubAuth]). `playground` is never
    // default and only approvable by someone with repository access themselves.
    if (rejectGrantBelowScope(AgentGrantScope.PLAYGROUND, api)) return true
    if (agentGrantFor(call) != null) return false
    val auth = githubAuth ?: return false
    if (auth.hasRepositoryAccess(call)) return false
    val message =
      "Playground requires access to ${auth.accessRepository()}. Live preview is available to any " +
        "signed-in GitHub user."
    if (api) {
      call.respondText(message, status = HttpStatusCode.Forbidden)
    } else {
      call.respondText(
        ServeWeb.notFoundPage(
          message,
          linkToken(),
          isPublic,
          unfurl = ServeWeb.UnfurlMetadata(pageUrl = externalPageUrl()),
          version = SERVE_VERSION,
          componentBrowser = componentBrowserMode(),
          githubAuth = githubAuthStatus(),
        ),
        ContentType.Text.Html,
        HttpStatusCode.Forbidden,
      )
    }
    return true
  }

  companion object {
    /** Per-call memo for [agentGrantFor], scoped to one `ApplicationCall`. */
    private val RESOLVED_AGENT_GRANT = AttributeKey<ResolvedAgentGrant>("composeai.agentGrant")

    /** A `POST /render/{name}`'s query merged with its body; see [renderParams]. */
    private val RENDER_BODY_PARAMS = AttributeKey<Parameters>("composeai.renderBodyParams")

    /** The `POST /render/{name}` body bound — the catalog MCP endpoint's, for the same document. */
    internal const val MAX_RENDER_BODY_BYTES: Long = 1024L * 1024

    /**
     * A render body's JSON object as query-shaped params, or null unless it is an object of
     * scalars. Numbers and booleans are spelled as the query would, so [ServeOverrides.parse] types
     * them identically.
     */
    internal fun renderBodyJson(text: String): Map<String, String>? {
      val obj =
        runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return null
      return obj.mapValues { (_, value) ->
        val primitive = value as? JsonPrimitive ?: return null
        if (primitive is kotlinx.serialization.json.JsonNull) return null
        primitive.content
      }
    }

    /** Per-call memo for [resolveParallel], scoped like [RESOLVED_AGENT_GRANT]. */
    private val RESOLVED_PARALLELS = AttributeKey<ParallelPairings>("composeai.parallelPairings")
    private val RELATED_INVERSES = AttributeKey<RelatedInverses>("composeai.relatedInverses")

    private const val MCP_PROTOCOL_VERSION_HEADER = "MCP-Protocol-Version"
    private const val MCP_SESSION_ID_HEADER = "MCP-Session-Id"

    /** Where a signed-in reader asks for UI-builder edit access for themselves. */
    const val UI_BUILDER_REQUEST_ACCESS_PATH = "/ui-builder/request-access"

    private const val UI_BUILDER_UNFURL_TITLE = "Compose UI builder"

    private const val UI_BUILDER_UNFURL_SUBTITLE =
      "Design Compose screens in the browser, together, and export real Kotlin."

    /** How many revisions the history page draws; older retained ones are counted, not shown. */
    private const val UI_BUILDER_HISTORY_LIMIT = 48

    /** Query parameters the share page's redirect adds to say what just changed. */
    private val ACCESS_NOTICE_PARAMS = setOf("changed", "actor", "role")

    /** The request-access page's query parameter naming the request its POST just opened. */
    private const val REQUEST_ACCESS_ID_PARAM = "request"

    /** The seal's fixed `requestId` and action for that form; the login is the per-reader part. */
    private const val REQUEST_ACCESS_SEAL_ID = "ui-builder-request-access"
    private const val REQUEST_ACCESS_SEAL_ACTION = "request"
    private const val CATALOG_MCP_AGENT_ACCESS_HEADER = "X-Compose-Preview-Agent-Access"
    private const val MAX_CATALOG_MCP_BYTES = 1024L * 1024
    private const val UI_MODE_NIGHT_MASK = 0x30
    private const val UI_MODE_NIGHT_NO = 0x10
    private const val UI_MODE_NIGHT_YES = 0x20

    /** Resolve an explicit cmp-jvm override, falling back to the preview's baked mode. */
    internal fun cmpJvmRenderTheme(
      requestedUiMode: String?,
      bakedUiMode: Int,
      darkFirst: Boolean = false,
    ): RcJvmServerRenderer.RenderTheme =
      when (requestedUiMode?.lowercase()) {
        "dark" -> RcJvmServerRenderer.RenderTheme.DARK
        "light" -> RcJvmServerRenderer.RenderTheme.LIGHT
        else -> {
          val bakedNight = bakedUiMode and UI_MODE_NIGHT_MASK
          when (bakedNight) {
            UI_MODE_NIGHT_YES -> RcJvmServerRenderer.RenderTheme.DARK
            UI_MODE_NIGHT_NO -> RcJvmServerRenderer.RenderTheme.LIGHT
            else ->
              if (darkFirst) RcJvmServerRenderer.RenderTheme.DARK
              else RcJvmServerRenderer.RenderTheme.LIGHT
          }
        }
      }

    /**
     * Max sections per page in the sidebar tree: above the kit's densest sheet, well below a wall
     * of rows.
     */
    const val MAX_PAGE_SECTIONS = 24

    /**
     * Extensions the motion route serves and their types. The key set is the allowlist, so "may
     * this be served" and "as what" can't drift. APNG is `image/apng` (not `image/png`) to signal
     * more than one frame.
     */
    val MOTION_CONTENT_TYPES: Map<String, String> =
      linkedMapOf(".apng" to "image/apng", ".gif" to "image/gif")

    const val TOKEN_HEADER: String = "X-Compose-Preview-Token"

    /** The negotiated MCP versions whose clients can answer `elicitation/create` forms. */
    private val ELICITING_PROTOCOL_VERSIONS =
      setOf(ServeCatalogMcp.MCP_PROTOCOL_VERSION, ServeCatalogMcp.MCP_PROTOCOL_VERSION_2025_11)

    /**
     * On `GET /images/capability`: `public` when anyone can open this host's pages, `private` when
     * token-gated. Read by `serve-web/src/report/ui.ts`.
     */
    const val CAPTURE_SCOPE_HEADER: String = "X-Compose-Preview-Capture-Scope"

    /** `Authorization: Bearer <grant>` — the other place an agent's HTTP client puts a token. */
    private const val BEARER_PREFIX: String = "Bearer "

    /**
     * Body cap for the agent-grant routes (two are ungated); every legitimate body is a small JSON
     * object or three-field form.
     */
    private const val MAX_AGENT_GRANT_BYTES = 8L * 1024

    /**
     * Form-urlencoded → name → values, `+` as space. Hand-rolled because the body was already read
     * under [readCapped]; Ktor's `receiveParameters` would buffer an uncapped body. Malformed pairs
     * are skipped; callers treat absent fields as invalid.
     */
    internal fun parseFormBody(body: String): Map<String, List<String>> {
      val out = LinkedHashMap<String, MutableList<String>>()
      for (pair in body.split('&')) {
        if (pair.isEmpty()) continue
        val index = pair.indexOf('=')
        val rawName = if (index < 0) pair else pair.substring(0, index)
        val rawValue = if (index < 0) "" else pair.substring(index + 1)
        val name = runCatching { URLDecoder.decode(rawName, StandardCharsets.UTF_8) }.getOrNull()
        val value = runCatching { URLDecoder.decode(rawValue, StandardCharsets.UTF_8) }.getOrNull()
        if (name.isNullOrEmpty() || value == null) continue
        out.getOrPut(name) { mutableListOf() }.add(value)
      }
      return out
    }

    /** Header carrying the `--admin-token` for the `/admin/catalogs` routes. */
    const val ADMIN_TOKEN_HEADER: String = "X-Compose-Preview-Admin-Token"

    /** A catalog registration is a few hundred bytes of JSON; cap it well short of a payload. */
    private const val MAX_ADMIN_BODY_BYTES = 64L * 1024

    /** Local preview source is display-only and should never make an HTTP request buffer huge. */
    private const val LOCAL_SOURCE_MAX_BYTES = 1024L * 1024

    const val GENERATION_HEADER: String = "X-Compose-Preview-Generation"

    /**
     * How a `/render` response relates to the request, when that needs saying. Only value:
     * [RENDER_BAKED_FALLBACK] (an accepted `?fallback=baked` snapshot not reflecting the override).
     * Absent on ordinary renders; see [GENERATION_HEADER].
     */
    const val RENDER_HEADER: String = "X-Compose-Preview-Render"

    /** [RENDER_HEADER] value: baked pixels standing in for a render that could not be made. */
    const val RENDER_BAKED_FALLBACK: String = "baked-fallback"

    /**
     * Comma-separated validated override params a `/render` response does not reflect
     * (`fontScale,uiMode`, `knob.label`, …). Present on refusals and on an accepted
     * `?fallback=baked` 200.
     */
    const val DROPPED_OVERRIDES_HEADER: String = "X-Compose-Preview-Dropped-Overrides"

    /**
     * `?fallback=baked`: accept the un-overridden snapshot instead of a refusal when the live lane
     * can't honour the request. Not an override param.
     */
    const val FALLBACK_PARAM: String = "fallback"

    const val FALLBACK_BAKED: String = "baked"

    /**
     * `?chrome=catalog|dev`: a permalink pinning the presentation for one request, outranking
     * [ServeWeb.INTERFACE_MODE_COOKIE]. See `componentBrowserMode`.
     */
    const val CHROME_PARAM: String = "chrome"

    private const val DEFAULT_PORT_RANGE = 32

    /** Short edge/browser caching for HTML assembled entirely from published catalog metadata. */
    private const val STATIC_PAGE_CACHE_CONTROL = "public, max-age=60, stale-while-revalidate=300"

    /** Published preview paths are stable but may change when a catalog refreshes in place. */
    private const val STATIC_RESOURCE_CACHE_CONTROL =
      "public, max-age=300, stale-while-revalidate=3600"

    /** Variant renders and all token-gated responses stay out of shared and browser caches. */
    private const val DYNAMIC_RESOURCE_CACHE_CONTROL = "no-store"

    /** Default for [maxProjectableDocumentBytes]. See `projectDocument`. */
    private const val DEFAULT_MAX_PROJECTABLE_DOCUMENT_BYTES = 8 * 1024 * 1024

    /** The path segment that introduces a UI-builder bundle version. */
    private const val UI_BUILDER_VERSION_SEGMENT = "v"

    /**
     * The service worker the UI-builder bundle ships at its root, from the release that adds it.
     */
    internal const val UI_BUILDER_SERVICE_WORKER = "ui-builder-sw.js"

    /** The widest scope that worker may claim: the editor, and nothing else on the server. */
    internal const val UI_BUILDER_SERVICE_WORKER_SCOPE = "/ui-builder/"

    private const val SERVICE_WORKER_ALLOWED = "Service-Worker-Allowed"

    /** The serve-web bundle served at [PUSH_SERVICE_WORKER_PATH]. */
    internal const val PUSH_SERVICE_WORKER_ASSET = "push-sw.js"

    /** A request's peer address when it comes from this machine. */
    private val LOOPBACK_PEERS = setOf("127.0.0.1", "::1", "0:0:0:0:0:0:0:1", "localhost")

    /** A `Host` naming this machine (port and IPv6 brackets already removed). */
    private val LOOPBACK_HOSTS = setOf("127.0.0.1", "localhost", "::1")

    /** Launchers truncate a `short_name` past about a dozen characters. */
    private const val SHORT_NAME_MAX = 12

    /** A year, `immutable`: safe only because the URL carries the bundle's content digest. */
    private const val UI_BUILDER_IMMUTABLE_CACHE_CONTROL = "public, max-age=31536000, immutable"

    /**
     * [STATIC_RESOURCE_CACHE_CONTROL]'s lifetime for a bare player replay on a non-`--public` box.
     * A bare `?rcPlayer=` is a fixed answer to a fixed URL (see the public lane), but these URLs
     * carry `?token=`, so `private` keeps them out of shared caches while the visitor's browser can
     * reuse them. Same staleness bound as public: the deployed player can change without moving the
     * generation, so never `immutable`.
     */
    private const val PRIVATE_REPLAY_CACHE_CONTROL =
      "private, max-age=300, stale-while-revalidate=3600"

    /**
     * Caching for HTML whose body depends on who is asking — every page on a `--github-auth-*`
     * server, since all render the sign-in chip.
     *
     * Not [STATIC_PAGE_CACHE_CONTROL]: `public` would let a CDN serve one visitor's login to
     * others, and even browser-only `max-age=60, stale-while-revalidate=300` would show a
     * signed-out page after returning from the OAuth callback. `no-store` rather than `no-cache`:
     * no ETag means revalidation costs a full render anyway, and it also keeps signed-out HTML out
     * of the back/forward cache.
     */
    internal const val SIGNED_IN_PAGE_CACHE_CONTROL = "private, no-store"

    /**
     * Caching for a page on a GitHub-auth server that the request proves is not personal (no
     * session cookie).
     *
     * `max-age=0` makes the browser revalidate every visit, so the chip never replays stale.
     * `s-maxage` licenses shared caches only together with the `Vary: Cookie` [markGeneration]
     * appends, so sessioned requests never reach these bytes. No `stale-while-revalidate`, which
     * would show pre-sign-in HTML. Unlike `no-store`, back/forward cache may briefly show
     * signed-out chrome after sign-in — accepted so shared links are storable.
     */
    internal const val ANON_PAGE_CACHE_CONTROL = "public, max-age=0, s-maxage=300, must-revalidate"

    /**
     * Caching for an assembled HTML page: public and auth-free ⇒ short edge caching; token-gated or
     * signed-in ⇒ not stored; anonymous on an auth server ⇒ [ANON_PAGE_CACHE_CONTROL]. [signedIn]
     * matters only when auth is configured and the server is public; a gated host's URLs carry a
     * credential, so it stays `no-store`.
     */
    internal fun pageCacheControl(
      githubAuthConfigured: Boolean,
      isPublic: Boolean,
      signedIn: Boolean = true,
    ): String =
      when {
        !githubAuthConfigured ->
          if (isPublic) STATIC_PAGE_CACHE_CONTROL else DYNAMIC_RESOURCE_CACHE_CONTROL
        !isPublic || signedIn -> SIGNED_IN_PAGE_CACHE_CONTROL
        else -> ANON_PAGE_CACHE_CONTROL
      }

    /**
     * The viewer page follows [pageCacheControl]: the sign-in chip is on every viewer, live or not.
     */
    internal fun viewerCacheControl(
      githubAuthConfigured: Boolean,
      isPublic: Boolean,
      signedIn: Boolean = true,
      stagedCapabilitiesPending: Boolean = false,
    ): String =
      if (stagedCapabilitiesPending) DYNAMIC_RESOURCE_CACHE_CONTROL
      else pageCacheControl(githubAuthConfigured, isPublic, signedIn)

    /** Classpath location of the vendored Remote Compose player IIFE bundle (global `RC`). */
    private const val RC_PLAYER_RESOURCE = "/rc-player/bundle.js"

    /**
     * The Android players a shared `/d/<id>` document can be drawn with on a resident catalog's
     * daemon ([docRcDonor]), default first, in viewer order.
     */
    private val DOC_DAEMON_PLAYERS: List<RcPlayerBackend> =
      listOf(
        RcPlayerBackend.ANDROIDX_EMBEDDED,
        RcPlayerBackend.ANDROIDX_VIEW,
        RcPlayerBackend.CMP_ANDROID,
      )

    /** A shared document with no declared size renders at this many pixels a side. */
    private const val DOC_RENDER_DEFAULT_PX = 512

    /** The largest side a shared document's server-side render is drawn at. */
    private const val DOC_RENDER_MAX_PX = 4096

    /**
     * A vendored browser player bundle from the CLI jar: bytes plus a content-hash ETag (stable
     * across restarts and replicas, cheap 304s). Empty [bytes] (a broken jar) makes the route 404.
     */
    internal class PlayerAsset(val bytes: ByteArray, val etag: String)

    private val playerAssets = java.util.concurrent.ConcurrentHashMap<String, PlayerAsset>()

    /**
     * The send-pipeline phase that stamps an `ETag` on assembled HTML, before `ContentEncoding` so
     * it hashes the page rather than gzip.
     */
    private val HTML_ENTITY_TAG_PHASE = PipelinePhase("HtmlEntityTag")

    /**
     * The body of a `304`: no bytes, with the staged `Cache-Control`, `Vary` and `ETag` headers
     * still delivered.
     */
    private object NotModifiedResponse : OutgoingContent.NoContent() {
      override val status: HttpStatusCode = HttpStatusCode.NotModified
    }

    /** The element an assembled page's `ETag` excludes; see [ServeWeb.VOLATILE_ATTR]. */
    private val VOLATILE_MARKUP = Regex("<span ${ServeWeb.VOLATILE_ATTR}>[^<]*</span>")

    /**
     * A strong ETag for an assembled page: [contentEtag] over the markup with volatile elements
     * elided. Hashing raw bytes would change on every visit (the visit tally), so no page would
     * ever 304; the cost is a count up to one `max-age` stale. Everything meaningful is still
     * hashed.
     */
    internal fun pageEntityTag(html: String): String =
      contentEtag(VOLATILE_MARKUP.replace(html, "").encodeToByteArray())

    /**
     * Whether an `If-None-Match` names [etag]: a list, `*`, and a `W/"…"`-weakened tag all count.
     */
    internal fun ifNoneMatchHits(header: String?, etag: String): Boolean {
      val value = header?.trim() ?: return false
      if (value == "*") return true
      return value.split(',').any { it.trim().removePrefix("W/") == etag }
    }

    /**
     * A strong ETag over exactly [bytes] (size and SHA-256 prefix, as [playerAsset] builds), for
     * per-request bodies.
     */
    internal fun contentEtag(bytes: ByteArray): String {
      val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
      return "\"" +
        bytes.size.toString(16) +
        "-" +
        digest.take(8).joinToString("") { "%02x".format(it.toInt() and 0xff) } +
        "\""
    }

    /** Load (once per classpath resource) a vendored player bundle. */
    internal fun playerAsset(resource: String): PlayerAsset =
      playerAssets.computeIfAbsent(resource) { path ->
        val bytes =
          ServeHttpServer::class.java.getResourceAsStream(path)?.use { it.readBytes() }
            ?: ByteArray(0)
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
        val etag =
          "\"" +
            bytes.size.toString(16) +
            "-" +
            digest.take(8).joinToString("") { "%02x".format(it.toInt() and 0xff) } +
            "\""
        PlayerAsset(bytes, etag)
      }

    /** The Remote Compose player, kept as a named handle for the preview viewer's canvas lane. */
    internal val rcPlayerBundle: ByteArray
      get() = playerAsset(RC_PLAYER_RESOURCE).bytes

    internal val rcPlayerEtag: String
      get() = playerAsset(RC_PLAYER_RESOURCE).etag

    /** Max accepted upload-body size for `POST /docs` (matches the document store's own cap). */
    private val MAX_DOC_BYTES: Long = ServeDocStore.DEFAULT_MAX_DOC_BYTES.toLong()

    /**
     * Request-body ceiling on `POST /images`, enforced while streaming; the store's per-image cap
     * only sees buffered bytes.
     */
    private val MAX_IMAGE_BYTES: Long = ServeImageStore.DEFAULT_MAX_IMAGE_BYTES.toLong()

    /** Max accepted body size for `POST /api/{v}/compiler/run` — a snippet is small. */
    private val MAX_PLAYGROUND_BYTES: Long = 256L * 1024

    /** `1234567` → `1.2 MB`; the size line on a document page. */
    internal fun humanBytes(bytes: Int): String =
      when {
        bytes >= 1024 * 1024 -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
        bytes >= 1024 -> "${bytes / 1024} kB"
        else -> "$bytes B"
      }

    /**
     * Delay between failed readiness render attempts; short so a warming daemon latches promptly.
     */
    private const val READINESS_PROBE_RETRY_MILLIS = 2000L

    /** How many recent failures a bug report carries; `/status` keeps the full window. */
    private const val BUG_REPORT_FAILURE_LIMIT = 8

    /**
     * Authorisation decision for a request: open when [isPublic], otherwise [provided] must match
     * [token] (constant-time). Pure for unit testing. Rejection means the caller 404s.
     */
    fun isAuthorized(token: String, provided: String?, isPublic: Boolean): Boolean =
      isPublic || ServeUrls.tokensMatch(token, provided)

    /**
     * How long a `/render` request waits for a concurrency slot before getting 503 + Retry-After.
     */
    private const val RENDER_QUEUE_WAIT_SECONDS = 30L

    /** Max accepted upload-body size for `POST /bundles` (matches the store's extraction cap). */
    private const val MAX_UPLOAD_BYTES = 100L * 1024 * 1024

    /** Compatibility-only name used by the first packaged Wasm browser deployment. */
    private const val LEGACY_WASM_UI_SYSTEM = "preview-ui"

    /**
     * Caching for `/hero/`: the file name is the content hash, so `immutable` and repeat visits
     * make no requests. A republish changes the URL.
     */
    private const val HERO_CACHE_CONTROL = "public, max-age=31536000, immutable"

    /**
     * A published capture: shared-cacheable for a few minutes but always revalidated, since the URL
     * derives from the sticker, not the bytes ([handleMotion]). Token-gated catalogs use
     * `no-store`.
     */
    internal const val MOTION_CACHE_CONTROL = "public, max-age=0, s-maxage=300, must-revalidate"

    /** The bytes' own hash, so a re-published capture under the same id revalidates to a miss. */
    internal fun motionEtag(bytes: ByteArray): String =
      MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }
        .take(16)

    /**
     * Caching for site icons ([ServeSiteIcon]): public on every server (no protected content, and
     * favicon requests carry no token). A day, not `immutable`, since well-known paths change
     * across deploys.
     */
    private const val SITE_ICON_CACHE_CONTROL = "public, max-age=86400"

    /** `Retry-After` for a refused capture whose branch host named no interval of its own. */
    private const val MOTION_DEFAULT_RETRY_AFTER_SECONDS = 5L

    /** Pause length when the caller names none. Long enough to ride out a burst of traffic. */
    private const val DEFAULT_OPTIMIZER_PAUSE_MINUTES = 30L

    /**
     * Longest pause allowed. A pause defers; disabling for good is `--no-theme-optimization`, which
     * is visible in config.
     */
    private const val MAX_OPTIMIZER_PAUSE_MINUTES = 24L * 60L

    /**
     * Caching for the prebaked image lanes (`/hero/` and `?thumb=`): content-addressed and
     * `immutable`, but only on a public server. On a token-gated one the URLs carry the token, so
     * `no-store` like other private responses ([pageCacheControl]); the ETag is still sent. Pure,
     * like [isAuthorized].
     */
    internal fun prebakedImageCacheControl(isPublic: Boolean): String =
      if (isPublic) HERO_CACHE_CONTROL else DYNAMIC_RESOURCE_CACHE_CONTROL

    private val JSON = Json { encodeDefaults = true }

    /** A compact human duration (`3d 4h`, `12m 5s`, `42s`); deterministic for stable goldens. */
    internal fun formatDuration(seconds: Long): String {
      val s = seconds.coerceAtLeast(0)
      val d = s / 86_400
      val h = (s % 86_400) / 3_600
      val m = (s % 3_600) / 60
      val sec = s % 60
      return when {
        d > 0 -> "${d}d ${h}h"
        h > 0 -> "${h}h ${m}m"
        m > 0 -> "${m}m ${sec}s"
        else -> "${sec}s"
      }
    }

    /** An epoch-millis instant as `YYYY-MM-DD HH:MM UTC` for the status page's failure table. */
    internal fun formatInstant(epochMillis: Long): String {
      val dt =
        java.time.OffsetDateTime.ofInstant(
          java.time.Instant.ofEpochMilli(epochMillis),
          java.time.ZoneOffset.UTC,
        )
      return java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'").format(dt)
    }

    /**
     * Content type for a Wasm-app asset. `application/wasm` is required by
     * `WebAssembly.instantiateStreaming`; `.mjs`/`.js` need a JS type for the module loader.
     */
    internal fun wasmContentType(name: String): ContentType =
      when {
        name.endsWith(".html") -> ContentType.Text.Html
        name.endsWith(".mjs") || name.endsWith(".js") -> ContentType.parse("text/javascript")
        name.endsWith(".wasm") -> ContentType.parse("application/wasm")
        name.endsWith(".json") || name.endsWith(".map") -> ContentType.Application.Json
        name.endsWith(".ttf") -> ContentType.parse("font/ttf")
        name.endsWith(".woff2") -> ContentType.parse("font/woff2")
        name.endsWith(".css") -> ContentType.Text.CSS
        name.endsWith(".svg") -> ContentType.Image.SVG
        name.endsWith(".png") -> ContentType.Image.PNG
        else -> ContentType.Application.OctetStream
      }

    /**
     * Read [input] fully, or `null` once it exceeds [max] bytes (without buffering past the cap).
     */
    private fun readCapped(input: InputStream, max: Long): ByteArray? {
      val out = ByteArrayOutputStream()
      val buffer = ByteArray(64 * 1024)
      var total = 0L
      while (true) {
        val n = input.read(buffer)
        if (n < 0) break
        total += n
        if (total > max) return null
        out.write(buffer, 0, n)
      }
      return out.toByteArray()
    }

    /**
     * Pick a bindable port: try [requested], then increment up to [range] times. Probes with a
     * short-lived [ServerSocket]; the TOCTOU window before Ktor binds is acceptable for a local dev
     * server. Falls back to an ephemeral port.
     */
    private fun pickPort(host: String, requested: Int, range: Int): Int {
      val bindAddr = if (host == ServeUrls.ALL_INTERFACES) null else InetAddress.getByName(host)
      for (candidate in requested until (requested + range)) {
        try {
          ServerSocket(candidate, 0, bindAddr).use {
            return it.localPort
          }
        } catch (_: Exception) {
          // taken — try the next
        }
      }
      ServerSocket(0, 0, bindAddr).use {
        return it.localPort
      }
    }
  }
}

@Serializable
private data class VersionResponse(
  val schema: String = "compose-preview-serve/version/v1",
  /** The host CLI's released version ([SERVE_VERSION]). */
  val version: String,
  /**
   * The schema id the `/api/previews` + page surface speaks, for feature detection. `v3` adds the
   * catalog snapshot provenance that gates native substitution.
   */
  val serveSchema: String = "compose-preview-serve/v3",
  /** True when the box serves token-free (public preview server); false for a token-gated serve. */
  val public: Boolean,
)

@Serializable
private data class CatalogMcpAuthorizationResponse(
  val schema: String = "compose-preview/catalog-mcp-auth/v1",
  val error: String,
  val message: String,
  val agentAccessRequestUrl: String,
  val requiredScope: String,
)

/**
 * `GET /status.json` (and `?format=json`): the machine-readable status snapshot for monitors / Home
 * Assistant. Flat-ish so `status` and the grouped counts (`catalogs`, `daemons`) map to sensor
 * states; detail is in `catalogList` / `runningServers` / `recentDaemonFailures`.
 */
@Serializable
private data class StatusResponse(
  val schema: String = "compose-preview-serve/status/v1",
  /** The host CLI's released version ([SERVE_VERSION]). */
  val version: String,
  /** True when the box serves token-free (public preview server). */
  val public: Boolean,
  /** `ok` when there are no catalog load or recent daemon startup failures, else `degraded`. */
  val status: String,
  /** Seconds since the server started. */
  val uptimeSeconds: Long,
  val catalogs: CatalogSummaryDto,
  val daemons: DaemonSummaryDto,
  val config: ConfigDto,
  val catalogList: List<CatalogDto>,
  val runningServers: List<RunningServerDto>,
  val recentDaemonFailures: List<FailureDto>,
  /**
   * Delivery-branch read counters ([BranchFetchSnapshot]); null until a branch has been read.
   * Additive on `compose-preview-serve/status/v1`. `throttled` climbing while `notFound` holds is a
   * rate limit; `notFound` alone is normal.
   */
  val branchFetch: BranchFetchSnapshot? = null,
  /**
   * Cross-catalog optimizer admission ([ThemeOptimizerAdmissionSnapshot]): how many passes are
   * inside the door versus parked — invisible from per-catalog rows.
   */
  val themeOptimizer: ThemeOptimizerAdmissionSnapshot? = null,
  /**
   * The theme cache's disk tier ([ThemeCacheStoreSnapshot]), or null when memory-only. Shows
   * whether warming accumulates or restarts from zero.
   */
  val themeCache: ThemeCacheStoreSnapshot? = null,
  /**
   * The catalog blob cache ([CatalogBlobPoolSnapshot]), or null without catalogs.
   *
   * `persistenceConfigured` only means a directory was named (it may not be a volume); `adopted`
   * (blobs found at open) is the evidence the pool survived a restart.
   *
   * `hits` aggregates all three lanes (small assets, executable bundles, the resource pool), while
   * `branchFetch.cached` counts only small assets, so `hits` is normally larger. Success shows as
   * either climbing while `branchFetch.attempted` flattens across a restart.
   *
   * `blobs`/`bytes` vs `maxBytes` shows whether the sweeper keeps up (published per sweep).
   * `corrupt` above zero means a volume is losing bytes.
   */
  val catalogCache: CatalogBlobPoolSnapshot? = null,
  /**
   * Server-wide render-latency roll-up across running live daemons ([RenderPerfSnapshot.aggregate]:
   * counts sum, `firstRenderMs` is the worst, percentiles per daemon). Null without stats. Additive
   * on `compose-preview-serve/status/v1`; per-daemon detail in `runningServers[].renderStats`.
   */
  val renderStats: RenderPerfSnapshot? = null,
  /**
   * Live-lane frame counters across open stream sockets ([LiveFramePerfSnapshot]): fps,
   * painted/heartbeat split, payload bytes. Null until a socket opens. Additive; `renderStats`
   * can't see streamed frames.
   */
  val liveFrames: LiveFramePerfSnapshot? = null,
  /**
   * Playground lane health, or null when not wired. Additive on `compose-preview-serve/status/v1`.
   * See [PlaygroundHealth].
   */
  val playground: PlaygroundDto? = null,
  /**
   * Agent access grants, or null when disabled. Additive. Counts and fingerprints only, so alerting
   * pipelines never store credentials.
   */
  val agentAccess: AgentAccessDto? = null,
  /** Aggregate UI-builder pressure counters. Owner and document identifiers are never included. */
  val uiBuilder: UiBuilderDto? = null,
  /**
   * This container's process census ([ServeProcessCensusSnapshot]), or null off Linux. `zombies` is
   * the alert: render daemons, compile jails and UI-builder renderers are subprocesses, and every
   * other count is session-level. Additive on `compose-preview-serve/status/v1`.
   */
  val processes: ServeProcessCensusSnapshot? = null,
)

@Serializable
private data class UiBuilderDto(
  val activeSubscribers: Int,
  val peakSubscribers: Long,
  val rejectedBatchLimit: Long,
  val rejectedSubscriberLimit: Long,
  val slowSubscribersClosed: Long,
  val rejectedPresenceLimit: Long,
  val activeExports: Int,
  val peakExports: Long,
  val rejectedExportLimit: Long,
  val rejectedMutationRate: Long,
  val rejectedDocumentBytes: Long,
  val rejectedAssetBytes: Long,
  val timedOutExports: Long,
  val activeMutationBuckets: Int,
  val persistenceMigrations: Long,
  val unusableDesigns: Int = 0,
  val degradedDesigns: Int = 0,
  /**
   * Stored designs re-pinned to the served catalog reference on load, and the class of the
   * exception that stopped a rewrite being written, if any. Shows whether a source flip has
   * converged on disk (non-zero once after the flip, then zero). Only the class, not the message:
   * this is unauthenticated on `--public`, and messages name designs and state paths.
   */
  val rePinnedDesigns: Int = 0,
  val rePinPersistenceFailure: String? = null,
  /**
   * Durable state bytes against the save-refusal ceiling, and the percentage. Null when unbounded,
   * so "not measured" differs from 0%. The percentage is carried so alert thresholds don't drift
   * between readers.
   */
  val storageBytes: Long? = null,
  val storageMaximumBytes: Long? = null,
  val storageUsedPercent: Double? = null,
)

@Serializable
private data class AgentAccessDto(
  /** Live grants right now. */
  val activeGrants: Int,
  /** Requests waiting for a human. */
  val pendingRequests: Int,
  /** The operator's ceiling — the most privileged scope this box will ever grant. */
  val maxScope: String,
  val maxTtlSeconds: Long,
  /** Every capability this box will grant at all. Empty unless the operator opted in. */
  val maxCapabilities: List<String> = emptyList(),
  /** One entry per live grant: fingerprint, scopes, approver, seconds left. Never a token. */
  val grants: List<AgentGrantDto> = emptyList(),
)

@Serializable
private data class AgentGrantDto(
  val fingerprint: String,
  val scopes: List<String>,
  val capabilities: List<String> = emptyList(),
  val approvedBy: String,
  val expiresInSeconds: Long,
  val label: String,
)

@Serializable
private data class PlaygroundDto(
  /** Which admission posture let the lane serve (the gate's own words). */
  val admittedBy: String,
  /**
   * False when only the compile engine is up (`--compile-engine`): `/playground`, the run route and
   * `/pg/` aren't mounted.
   */
  val publicSurface: Boolean = true,
  val sandbox: SandboxDto,
  /** True when compiles run in a jailed child rather than in the serve JVM. */
  val compilerJailed: Boolean,
  /** Concurrent jailed compiles allowed; inert unless [compilerJailed]. */
  val compileSlots: Int,
  val modes: List<ModeDto>,
  /** The runtime catalog selector (`--playground`), or null when this host pins its bundles. */
  val catalogSelector: CatalogSelectorDto? = null,
  /** The per-caller compile budget, or null when the lane is unmetered. */
  val rateLimit: RateLimitDto? = null,
  /** Authenticated single-lease incremental editing trial, or null on older/unwired lanes. */
  val editing: EditingDto? = null,
)

@Serializable
private data class EditingDto(
  val enabled: Boolean,
  val active: Boolean,
  val expiresAtEpochMs: Long? = null,
  val lastRevision: Long? = null,
  val acquisitions: Long,
  val compileAttempts: Long,
  val incrementalCompiles: Long,
  val fullFallbacks: Long,
  val lastCompileMillis: Long? = null,
)

@Serializable
private data class RateLimitDto(
  /** Callers holding a compile permit right now. */
  val activeCallers: Int,
  /**
   * Distinct callers the limiter tracks; pinned near its cap on a public host suggests a key-space
   * spray.
   */
  val trackedCallers: Int,
)

@Serializable
private data class CatalogSelectorDto(
  /**
   * Catalogs the selector offers now. Empty on a fresh host or when every catalog needs a backend
   * this host can't render; `modes` and the startup log distinguish them.
   */
  val offered: List<String>,
  /**
   * How many hold a resolved compile classpath, against [limit]. Null on a [ServeSites]-scoped
   * status, since the count is box-wide.
   */
  val resolved: Int? = null,
  /** `--playground-catalog-limit`; at [resolved] == this, a run naming a new catalog is refused. */
  val limit: Int,
)

@Serializable
private data class SandboxDto(
  val profile: String,
  /**
   * False ⇒ `none`: no jail, and no `-Xmx`, CPU cap or hard TTL on snippet JVMs — an uncapped JVM
   * takes a quarter of a large cgroup limit as max heap.
   */
  val active: Boolean,
  /**
   * True ⇒ the configured jail couldn't launch and was dropped; JVM caps still apply but the
   * snippet isn't contained. See `probe.detail`.
   */
  val jailDropped: Boolean = false,
  val memoryMb: Int,
  val cpus: Double,
  val ttlSeconds: Long,
  /** Null when no preflight ran (token-gated host, or no sandbox configured). */
  val probe: ProbeDto? = null,
)

@Serializable
private data class ProbeDto(
  /** False ⇒ the jail could not even launch here; [detail] says why. */
  val ran: Boolean,
  val detail: String,
  /** Empty ⇒ the jail contained the preflight on every measured axis. */
  val failedChecks: List<String>,
  val egressBlocked: Boolean,
  val filesystemContained: Boolean,
  val processIsolated: Boolean,
  val workDirWritable: Boolean,
)

@Serializable
private data class ModeDto(val mode: String, val source: String, val resolved: Boolean)

@Serializable
private data class CatalogSummaryDto(
  val total: Int,
  val listed: Int,
  val unlisted: Int,
  val trusted: Int,
  val degraded: Int,
  /** Configured catalogs with a usable registered copy. */
  val loaded: Int,
  /** Configured catalogs whose latest attempt failed before any usable copy registered. */
  val failed: Int,
  /** Configured catalogs not attempted yet (normally only visible during concurrent startup). */
  val pending: Int,
)

/**
 * The spare Android sandbox workers ([ServeSpareSandboxes]): warm, booting, and whether the pool
 * earns its memory (spares adopted, returned by reaped daemons, and cold launches with no match).
 */
@Serializable
private data class SpareSandboxesDto(
  val warm: Int,
  val booting: Int,
  val signatures: Int,
  val adopted: Long,
  val returned: Long,
  val coldLaunches: Long,
)

@Serializable
private data class DaemonSummaryDto(
  /** Total known sessions (resident + suspended). */
  val known: Int,
  /** Live (daemon-backed) render sessions up right now. */
  val running: Int,
  val activeStreams: Int,
  /** Live-seat permit budget; `0` ⇒ unbounded. */
  val liveSeatsTotal: Int,
  /** Free permits; `-1` ⇒ unbounded. */
  val liveSeatsAvailable: Int,
  val liveSeatsUnbounded: Boolean,
  /**
   * The slice of [liveSeatsTotal] reserved for the per-preview daemon lane, and how much is free.
   * Published because without it, resident catalog daemons holding every general permit starved
   * that lane invisibly.
   */
  val perPreviewSeatsTotal: Int = 0,
  val perPreviewSeatsAvailable: Int = 0,
  /**
   * Live sessions refused for want of seats since startup (monotonic). A counter because refusals
   * are events a sampled gauge misses; zero over long uptime means the budget is comfortable.
   */
  val liveSeatRefusals: Long = 0,
  /**
   * Refusals for a session id the registry didn't have ([LiveSeatLimiter.unverifiedRefusalCount]);
   * separate because anyone can generate them on a public box, though on `--revisions` they are
   * real demand.
   */
  val liveSeatRefusalsUnverified: Long = 0,
  /**
   * Sessions holding an open lease ([ServeSessionRegistry.leasedSessions]), which keeps them
   * resident and blocks `--exit-when-idle`. Usually short-lived; one persisting on an idle box is
   * an open tab or a leaked lease, which [busyLeasedSessions] distinguishes.
   */
  val leasedSessions: List<String> = emptyList(),
  /**
   * The recently active holders among [leasedSessions] ([ServeSessionRegistry.busyLeasedSessions]);
   * non-empty makes the idle clock read busy and stands the theme optimizer down.
   */
  val busyLeasedSessions: List<String> = emptyList(),
  /** Null when the server keeps no spare sandbox workers (`--spare-sandboxes 0`). */
  val spareSandboxes: SpareSandboxesDto? = null,
)

/**
 * One `--catalog-registry` nomination as status reports it. Public because it reaches
 * [ServeHttpServer]'s constructor. Carries the latest read's outcome so an unreachable registry and
 * an empty one are distinguishable.
 */
@Serializable
public data class CatalogRegistryStatus(
  /** `owner/repo`, as nominated. */
  val repo: String,
  /** The explicitly nominated `@ref`. Null ⇒ the default ref candidates were tried in order. */
  val ref: String? = null,
  /** Systems this registry contributes now, from its last clean document. */
  val catalogs: Int = 0,
  /** The contributed system ids. */
  val systems: List<String> = emptyList(),
  /** Why the read produced nothing, when it did. Null on a successful read. */
  val error: String? = null,
)

@Serializable
private data class ConfigDto(
  val host: String,
  val port: Int,
  val allowRenderTrusted: Boolean,
  val trustStore: Boolean,
  val acceptBundles: Boolean,
  /** Whether the document lane (`POST /docs` → `/d/<id>`) is enabled on this host. */
  val acceptDocs: Boolean = false,
  /** TTL of a document permalink in seconds; `0` when the document lane is off. */
  val docTtlSeconds: Long = 0,
  /** Whether `POST /images` exists on this host at all (`--accept-images`). */
  val acceptImages: Boolean = false,
  /** TTL of an uploaded image link in seconds; `0` when the image lane is off. */
  val imageTtlSeconds: Long = 0,
  /** The repository uploaders must access; non-null exactly when [acceptImages] is set. */
  val imageUploadRepository: String? = null,
  /** Live uploaded images, and what they occupy — the lane's whole footprint is heap. */
  val imagesHeld: Int = 0,
  val imageBytesHeld: Long = 0,
  /** Catalog auto-refresh interval; `0` ⇒ disabled. */
  val catalogRefreshSeconds: Long,
  /**
   * The `--catalog-registry` nominations and their contributions. Empty ⇒ none nominated;
   * `catalogs: 0` with an `error` ⇒ nominated but unreadable.
   */
  val catalogRegistries: List<CatalogRegistryStatus> = emptyList(),
  /**
   * Catalog-owned UI-builder catalogs this box can't fully serve, with why. Empty when all compose.
   */
  val uiBuilderCatalogProblems: Map<String, String> = emptyMap(),
  val maxConcurrentRenders: Int,
  /** Live-seat permit budget; `0` ⇒ unbounded. */
  val liveSeats: Int,
)

@Serializable
private data class CatalogDto(
  val id: String,
  val listed: Boolean,
  val title: String? = null,
  /**
   * [BundleVerifier.summary] verdict. For a suspended live catalog, the last-known verdict (with
   * [metaStale]). Null means unknown (non-catalog session, or never resident) — never "untrusted";
   * `unverified` says that.
   */
  val trust: String? = null,
  val previews: Int? = null,
  /** Number of catalogued previews whose published render failed. */
  val failedRenders: Int = 0,
  /** Number of catalogued previews deliberately deferred to the live render lane. */
  val deferredPreviews: Int = 0,
  /** Has a live daemon-backed render lane (running now, or a suspended live catalog). */
  val live: Boolean,
  /** A live daemon for this catalog is up right now. */
  val running: Boolean,
  val degradation: String? = null,
  val repo: String? = null,
  val branch: String? = null,
  val generatedAt: String? = null,
  /** compose-ai-tools / compose-preview version that rendered this catalog. */
  val composeAiToolsVersion: String? = null,
  /** @design-parity/catalog-export version, when that producer recorded one. */
  val designParityVersion: String? = null,
  /** Canonical catalog path (`/<id>/`). */
  val path: String,
  /**
   * This row's `title`/`trust`/`previews`/`degradation`/provenance are a last-known snapshot (the
   * daemon is idle and `/status` never resumes it). Branch-derived, so still valid; monitors
   * wanting live rows can filter on this. Additive on `compose-preview-serve/status/v1`.
   */
  val metaStale: Boolean = false,
  /** `pending`, `loaded`, `failed`, or `stale` (last good copy + latest refresh error). */
  val loadState: String = "loaded",
  /** Latest catalog fetch/parse/image error, null after a successful latest attempt. */
  val loadError: String? = null,
  val lastLoadAttemptEpochMillis: Long? = null,
  /** Server-side idle theme-cache fill progress for this catalog generation. */
  val themeOptimization: ThemeOptimizationSnapshot? = null,
  /** Bounded rendered-preview cache occupancy for this catalog generation. */
  val renderCache: CatalogRenderCacheSnapshot? = null,
)

@Serializable
private data class RunningServerDto(
  val id: String,
  val label: String,
  /** `static`, or `desktop` / `android` derived from the live-seat weight. */
  val backend: String,
  val seatWeight: Int,
  val activeStreams: Int,
  val uptimeSeconds: Long? = null,
  /**
   * Serve-side render-latency counters for this daemon's live lane ([RenderPerfSnapshot]): cold vs
   * warm counts, first-render latency, recent p50/p95. Null before any render or without a
   * measurable lane.
   */
  val renderStats: RenderPerfSnapshot? = null,
  /**
   * This catalog's live-lane frame counters, complementing [activeStreams]. Null until a socket has
   * streamed.
   */
  val liveFrames: LiveFramePerfSnapshot? = null,
  /** Child daemon pools owned by this server, e.g. per-preview bundles for trusted catalogs. */
  val daemonPools: List<DaemonPoolSnapshot> = emptyList(),
)

@Serializable
private data class FailureDto(val atEpochMillis: Long, val session: String, val reason: String)

@Serializable
private data class UsesResponse(
  /**
   * False when the catalog couldn't be indexed (no parser sidecar, source metadata or fetcher).
   * Distinct from an empty [ids].
   */
  val available: Boolean,
  /**
   * Whether the index stopped short of every source file, so absence is not evidence of absence.
   */
  val truncated: Boolean = false,
  val ids: List<String> = emptyList(),
)

@Serializable
private data class PreviewsResponse(
  val schema: String = "compose-preview-serve/v3",
  val module: String,
  /**
   * The compose-ai-tools version that produced this catalog's snapshots (`catalog.json`'s
   * `renderer`). A client may substitute a compiled native catalog only on an exact match; null
   * means the snapshot stays authoritative. Added in `compose-preview-serve/v3`.
   */
  val catalogVersion: String? = null,
  /**
   * Producer-trust verdict for this session ([BundleVerifier.summary]): `signature:<keyId>`,
   * `branch:<repo>@<branch>`, `provenance:<id>`, or `unverified`. Null for a live daemon-backed
   * module.
   */
  val trust: String? = null,
  /**
   * Why this session is snapshot-only, if it is (e.g. no `liveBundle`); empty when fully live. Each
   * entry has a stable [code] and a human [detail]. Additive since `compose-preview-serve/v2`. See
   * [ServeDegradation].
   */
  val degradations: List<DegradationDto> = emptyList(),
  /** Aggregate landing-page visits for this catalog/app. */
  val views: Long = 0,
  val previews: List<PreviewDto>,
)

@Serializable private data class DegradationDto(val code: String, val detail: String)

/**
 * `GET /<system>/parity?format=json`: the design-parity dashboard as data, the same numbers as the
 * HTML, so CI can gate on `coverage.percent` or empty `drift`/`gaps`. The derived view, not
 * `activity.json`: coverage is computed live and preview ids are filtered to those served.
 */
@Serializable
private data class ParityResponse(
  val schema: String = "compose-preview-serve/parity/v1",
  val generatedAt: String? = null,
  val windowDays: Int? = null,
  val coverage: ParityCoverageDto,
  /** Components that moved on one side only — the actionable subset of the correlation. */
  val drift: List<ParityDriftDto> = emptyList(),
  val activity: List<ParityEventDto> = emptyList(),
  val gaps: List<ParityGapDto> = emptyList(),
  /** Validated GitHub issue rows published by the catalog, including closed rows. */
  val issues: List<ParityIssue> = emptyList(),
) {
  companion object {
    fun of(
      dashboard: ServeParityDashboard.Dashboard,
      issues: List<ParityIssue> = emptyList(),
    ): ParityResponse =
      ParityResponse(
        generatedAt = dashboard.generatedAt,
        windowDays = dashboard.windowDays,
        coverage =
          ParityCoverageDto(
            components = dashboard.coverage.components,
            mapped = dashboard.coverage.mapped,
            unmapped = dashboard.coverage.unmappedCount,
            percent = dashboard.coverage.percent,
          ),
        drift =
          dashboard.components
            .filter { it.correlation != ServeParityDashboard.Correlation.BOTH }
            .map {
              ParityDriftDto(
                component = it.name,
                side =
                  if (it.correlation == ServeParityDashboard.Correlation.CODE_ONLY) "code"
                  else "design",
                lastChangeAt = it.lastAt,
                previewId = it.previewId,
              )
            },
        activity =
          dashboard.feed.map {
            ParityEventDto(
              lane =
                when (it.lane) {
                  ServeParityDashboard.Lane.CODE -> "code"
                  ServeParityDashboard.Lane.FIGMA_VERSION -> "figma-version"
                  ServeParityDashboard.Lane.FIGMA_COMMENT -> "figma-comment"
                },
              at = it.at,
              title = it.title,
              author = it.author,
              url = it.href,
              previewIds = it.previewIds,
              components = it.components,
              resolved = it.resolved,
            )
          },
        gaps =
          dashboard.gaps.map {
            ParityGapDto(
              kind = it.kind,
              detail = it.detail,
              code = it.code,
              ref = it.ref,
              previewId = it.previewId,
              component = it.component,
            )
          },
        issues = issues,
      )
  }
}

@Serializable
private data class ParityCoverageDto(
  val components: Int,
  val mapped: Int,
  val unmapped: Int,
  /** 0–100, rounded. */
  val percent: Int,
)

@Serializable
private data class ParityDriftDto(
  val component: String,
  /** `code` (render moved, reference didn't) or `design` (the reverse). */
  val side: String,
  val lastChangeAt: String,
  val previewId: String? = null,
)

@Serializable
private data class ParityEventDto(
  /** `code`, `figma-version`, or `figma-comment`. */
  val lane: String,
  val at: String,
  val title: String,
  val author: String? = null,
  val url: String? = null,
  val previewIds: List<String> = emptyList(),
  val components: List<String> = emptyList(),
  val resolved: Boolean = false,
)

@Serializable
private data class ParityGapDto(
  val kind: String,
  val detail: String,
  val code: String? = null,
  val ref: String? = null,
  val previewId: String? = null,
  val component: String? = null,
)

/** What `/api/daemons` reports: is a render server up for this catalog, and how many processes. */
@Serializable
private data class DaemonStatusDto(
  val running: Boolean,
  val instances: Int,
  val pooled: Int,
  val poolCapacity: Int,
  val activeStreams: Int,
  val overallRunning: Int,
  val overallActiveStreams: Int,
  val liveSeatsTotal: Int,
  val liveSeatsAvailable: Int,
)

@Serializable
private data class PreviewDto(
  val id: String,
  val label: String,
  val modes: List<String>,
  /**
   * The author-declared editable knobs (`compose/overrides`): key, type, label, default/current
   * value, repeat index, for programmatic clients like the Figma plugin. Empty when none. Additive
   * since `compose-preview-serve/v2`.
   */
  val overrides: List<PreviewOverrideDeclaration> = emptyList(),
  /**
   * The Remote Compose named-value knobs (`compose/remotecompose`): name and typed author default.
   * Clients write edits back via `rc.<name>=<kind>:<value>`. Empty when none are bound through
   * `rememberOverridableRemote*`. Additive since `compose-preview-serve/v2`.
   */
  val remoteComposeKnobs: List<RemoteComposeKnobDeclaration> = emptyList(),
  /** True when `/spatial/<id>/scene.json` is available for WebGL/WebXR presentation. */
  val spatial: Boolean = false,
  /**
   * True when this preview is live-only: declared (`deferred[]`) without a baked PNG, so every
   * render is on demand. Additive since `compose-preview-serve/v2`.
   */
  val liveOnly: Boolean = false,
  /** Number of viewer page opens for this preview since this server process started. */
  val views: Long = 0,
  /**
   * True when this preview publishes a Remote Compose document at `GET /{system}/render/{id}.rc`.
   * Not derivable from `modes` (which describes pixel production), and saves clients a probe per
   * preview. Additive since `compose-preview-serve/v3`; false for a daemon-only host.
   */
  val remoteCompose: Boolean = false,
)

/**
 * `GET /{system}/api/render-runs/{previewId}`: this preview's published revisions collapsed into
 * stretches sharing pixels. A separate lane because it costs a delivery-branch read, needed only
 * when the revision menu opens.
 */
@Serializable
private data class RenderRunsResponse(
  val schema: String = "compose-preview-render-runs/v1",
  /** Newest first, aligned with the revision menu's own order. */
  val runs: List<RenderRunDto>,
  /** Publishes considered — the same window the menu lists. */
  val revisions: Int,
)

@Serializable
private data class RenderRunDto(
  /** Delivery sha of the newest publish in this run; the row the viewer marks. */
  val head: String,
  /** Source sha for that publish when its subject recorded one — what the menu row shows. */
  val sourceSha: String? = null,
  val commits: Int,
  /** True when the run runs off the end of the window and may be longer than [commits]. */
  val open: Boolean = false,
)

@Serializable
private data class GlobalComponentsResponse(
  val schema: String = "compose-preview-components/v1",
  val components: List<GlobalComponentDto>,
)

@Serializable
private data class GlobalComponentDto(
  val label: String,
  val catalog: String,
  val catalogTitle: String,
  val href: String,
  val keywords: String,
)

/** One configured catalog on `GET /admin/catalogs`: its config plus its latest load outcome. */
@Serializable
private data class AdminCatalogDto(
  val system: String,
  val repo: String,
  /** The delivery branch watched for this catalog (`design-artifacts/<system>`). */
  val branch: String,
  /** On the front-page index (vs. served-but-unlisted). */
  val listed: Boolean,
  /** The front-page section heading this catalog is published under; null ⇒ grouped by owner. */
  val group: String? = null,
  /**
   * Startup fetch order, highest first ([ServeCatalogsConfig.Entry.loadPriority]), so a reconcile
   * can see the next boot's load order.
   */
  val loadPriority: Int = 0,
  /** `pending` / `loaded` / `failed` / `stale` ([CatalogLoadTracker.State.loadState]). */
  val state: String,
  val error: String? = null,
)

@Serializable
private data class AdminCatalogsResponse(
  val schema: String = "compose-preview-serve/admin-catalogs/v1",
  val catalogs: List<AdminCatalogDto>,
)

/**
 * The result of an admin mutation. [warning] is set when the catalog serves but the change couldn't
 * be written to `catalogs.json` (it won't survive a restart).
 */
@Serializable
private data class AdminCatalogResult(
  val schema: String = "compose-preview-serve/admin-catalog/v1",
  val system: String,
  val status: String,
  val warning: String? = null,
)

/** One front-page section on `GET /admin/groups`. */
@Serializable
private data class AdminGroupDto(
  val id: String,
  val heading: String,
  val noun: String,
  /** Section order on the front page, highest first ([ServeCatalogsConfig.Group.priority]). */
  val priority: Int = 0,
)

@Serializable
private data class AdminGroupsResponse(
  val schema: String = "compose-preview-serve/admin-groups/v1",
  val groups: List<AdminGroupDto> = emptyList(),
)

/**
 * `POST /admin/onboard`'s body: the GitHub project URL plus presentation choices applied to every
 * delivered catalog.
 */
@Serializable
private data class AdminOnboardRequest(
  /** Anything that names a GitHub repository — see [GithubProject.parse]. */
  val url: String,
  /** Front-page section for the discovered catalogs; null ⇒ grouped by the source repo's owner. */
  val group: String? = null,
  /** On the front-page index (vs. served-but-unlisted). */
  val listed: Boolean = true,
)

/** What became of one discovered delivery branch. */
@Serializable
private data class AdminOnboardCatalogDto(
  val system: String,
  /**
   * `published` / `already-published` / `invalid` / `failed` ([ServeOnboarding.Catalog.status]).
   */
  val status: String,
  val detail: String? = null,
)

@Serializable
private data class AdminOnboardResponse(
  val schema: String = "compose-preview-serve/admin-onboard/v1",
  val repo: String,
  val catalogs: List<AdminOnboardCatalogDto> = emptyList(),
)

/** `POST /admin/onboard/scan`'s body: which repository, at which ref. */
@Serializable
private data class AdminOnboardSourceRequest(
  /** Anything that names a GitHub repository — see [GithubProject.parse]. */
  val url: String,
  /** Branch or tag; null takes the repository's default branch. */
  val ref: String? = null,
)

/** One Gradle module a scan looked at. */
@Serializable
private data class AdminOnboardModuleDto(
  val gradlePath: String,
  val previewCount: Int,
  /** Whether a build of this module is worth attempting — not a promise that it succeeds. */
  val buildable: Boolean,
  /** Plugin ids the preview plugin would be injected beside. */
  val hostPlugins: List<String> = emptyList(),
  val pluginPreApplied: Boolean = false,
  /** Why the module was passed over, when it was. */
  val skipReason: String? = null,
  /** A bounded sample of the preview function names, so the report is readable. */
  val previewFunctions: List<String> = emptyList(),
)

@Serializable
private data class AdminOnboardScanResponse(
  val schema: String = "compose-preview-serve/admin-onboard-scan/v1",
  val repo: String,
  val ref: String,
  val sha: String,
  val modules: List<AdminOnboardModuleDto> = emptyList(),
  /** Human-readable remarks, chiefly why an apparently-Compose repository yielded nothing. */
  val notes: List<String> = emptyList(),
)

private fun ServeSourceModule.toDto() =
  AdminOnboardModuleDto(
    gradlePath = gradlePath,
    previewCount = previewCount,
    buildable = buildable,
    hostPlugins = hostPlugins,
    pluginPreApplied = pluginPreApplied,
    skipReason = skipReason,
    previewFunctions = previewFunctions,
  )

/** One design on `GET /admin/ui-builder/designs`. */
@Serializable
private data class AdminUiBuilderDesignDto(
  val designId: String,
  val title: String,
  val revision: Long,
  val catalogSystemId: String,
  val ownerActorId: String,
  val collaborators: Int,
  val createdAtEpochMillis: Long,
  val updatedAtEpochMillis: Long,
  val activeSubscribers: Int,
  /** Why the host can't serve this design, or null. Additive to v1. */
  val unusableReason: String? = null,
  /** Why this still-editable design has lost catalog vocabulary, if it has. */
  val degradedReason: String? = null,
  /**
   * Whether this design's document can still be produced (download and repair offered). False only
   * when the stored files won't read, leaving retirement. Additive; defaults true.
   */
  val documentAvailable: Boolean = true,
)

@Serializable
private data class AdminUiBuilderDesignsResponse(
  val schema: String = "compose-preview-serve/admin-ui-builder-designs/v1",
  val designs: List<AdminUiBuilderDesignDto> = emptyList(),
)

/**
 * The actor a design opened from a catalog library is created as: a fixed host identity, so the
 * owner reads as the host rather than whichever operator clicked.
 */
private const val ADMIN_LIBRARY_ACTOR: String = "operator:library"

/** One published design on `GET /admin/ui-builder/library`. */
@Serializable
private data class AdminUiBuilderLibraryDto(
  val system: String,
  val designId: String,
  val title: String,
  val description: String? = null,
)

@Serializable
private data class AdminUiBuilderLibraryResponse(
  val schema: String = "compose-preview-serve/admin-ui-builder-library/v1",
  /** Which catalogs were searched, so "none searched" and "none found" are distinguishable. */
  val catalogsSearched: List<String> = emptyList(),
  val designs: List<AdminUiBuilderLibraryDto> = emptyList(),
)

/** The result of `POST /admin/ui-builder/library/{system}/{designId}`. */
@Serializable
private data class AdminUiBuilderLibraryOpenResult(val designId: String, val status: String)

/**
 * The result of `DELETE /admin/ui-builder/actors/{actorId}`. [ownedDesigns] are left alone;
 * reassigning them is the operator's call.
 */
@Serializable
private data class AdminUiBuilderActorErasureResult(
  val actorId: String,
  val revokedFrom: List<String>,
  val ownedDesigns: List<String>,
  val commentBoards: Int,
  val replacedWith: String,
)

/** The result of `DELETE /admin/ui-builder/designs/{designId}`. */
@Serializable
private data class AdminUiBuilderDesignResult(val designId: String, val status: String)

/**
 * The result of a repairing `PUT /admin/ui-builder/designs/{designId}/document`. Its own shape so
 * delete responses don't gain a `"revision": null` field.
 */
@Serializable
private data class AdminUiBuilderRepairResult(
  val designId: String,
  val status: String,
  val revision: Long,
)

/** One configured hostname on `GET /admin/sites`. */
@Serializable private data class AdminSiteDto(val host: String, val system: String)

/** `GET /admin/editor`. [pinned] is what `catalogs.json` holds, i.e. what the next start serves. */
@Serializable
private data class AdminEditorResponse(
  val schema: String = "compose-preview-serve/admin-editor/v1",
  val serving: String?,
  val servingPinned: Boolean,
  val bundled: String?,
  val pinned: ServeCatalogsConfig.EditorPin?,
  val restartRequired: Boolean,
  val supportedServerApi: List<Int>,
)

@Serializable
private data class AdminEditorResult(
  val schema: String = "compose-preview-serve/admin-editor-result/v1",
  val status: String,
  val pinned: ServeCatalogsConfig.EditorPin?,
  val restartRequired: Boolean,
  val warning: String? = null,
)

/**
 * `GET /admin/ui-builder/config`: [environment] (from `SERVE_UI_BUILDER_*` alone), [serving], and
 * [next] (from [configured]).
 */
@Serializable
private data class AdminUiBuilderSettingsResponse(
  val schema: String = "compose-preview-serve/admin-ui-builder-config/v1",
  val configured: ServeCatalogsConfig.UiBuilderSettings?,
  val environment: ServeUiBuilderSettingsDto,
  val serving: ServeUiBuilderSettingsDto,
  val next: ServeUiBuilderSettingsDto,
  val restartRequired: Boolean,
  val problems: List<String>,
  /** Each shadowed catalog's report, as this process composed it. */
  val shadow: Map<String, ServeUiBuilderShadowReportDto> = emptyMap(),
  /**
   * Each catalog-owned catalog this process can't fully serve, with why. Owned catalogs have no
   * fallback, so a non-composing publish is left out until republished.
   */
  val unavailable: Map<String, String> = emptyMap(),
)

@Serializable
private data class AdminUiBuilderSettingsResult(
  val schema: String = "compose-preview-serve/admin-ui-builder-config-result/v1",
  val status: String,
  val configured: ServeCatalogsConfig.UiBuilderSettings?,
  val next: ServeUiBuilderSettingsDto,
  val restartRequired: Boolean,
  val problems: List<String>,
)

/**
 * `GET /admin/settings`: [configured] is `settings.json` as stored; each of [settings] says what's
 * serving, its source, and what the next start changes.
 */
@Serializable
private data class AdminSettingsResponse(
  val schema: String = "compose-preview-serve/admin-settings/v1",
  val file: String?,
  val configured: JsonObject?,
  val settings: List<ServeSettingsAdmin.Entry>,
  val restartRequired: Boolean,
  val problems: List<String> = emptyList(),
)

/**
 * The result of `PUT`/`DELETE /admin/settings`: settings [applied] live, [pending] the next start,
 * and [overridden] by the environment until the `.env` line goes.
 */
@Serializable
private data class AdminSettingsResult(
  val schema: String = "compose-preview-serve/admin-settings-result/v1",
  val status: String,
  val applied: List<String>,
  val pending: List<String>,
  val overridden: List<String>,
  val restartRequired: Boolean,
  val problems: List<String>,
)

@Serializable
private data class AdminSitesResponse(
  val schema: String = "compose-preview-serve/admin-sites/v1",
  val sites: List<AdminSiteDto> = emptyList(),
)

/**
 * The result of a site mutation. [warning] is set when the hostname is live but couldn't be written
 * to catalogs.json (it won't survive a restart).
 */
@Serializable
private data class AdminSiteResult(
  val schema: String = "compose-preview-serve/admin-site-result/v1",
  val host: String,
  val status: String,
  val warning: String? = null,
)

/** One trusted branch on `GET /admin/trust`. */
@Serializable private data class AdminTrustBranchDto(val repo: String, val branch: String)

/** One pinned key on `GET /admin/trust`: id and label only, never key material. */
@Serializable private data class AdminTrustKeyDto(val keyId: String, val name: String? = null)

@Serializable
private data class AdminTrustResponse(
  val schema: String = "compose-preview-serve/admin-trust/v1",
  val branches: List<AdminTrustBranchDto> = emptyList(),
  val keys: List<AdminTrustKeyDto> = emptyList(),
  val oidc: List<String> = emptyList(),
)

/**
 * The result of a trust mutation. [warning] is set when the change is live but couldn't be written
 * to producers.json (it won't survive a restart).
 */
@Serializable
private data class AdminTrustResult(
  val schema: String = "compose-preview-serve/admin-trust-result/v1",
  val producer: String,
  val status: String,
  val warning: String? = null,
)

@Serializable
private data class DocAcceptedResponse(
  val schema: String = "compose-preview-serve/doc/v1",
  /** The permalink id — the capability. */
  val id: String,
  /** The display label the page shows (the sanitised upload name). */
  val name: String,
  /** Human format name ([ServeDocFormat.label]). */
  val format: String,
  /** Wire format id ([ServeDocFormat.id]) for a programmatic client. */
  val formatId: String,
  val bytes: Int,
  /** Relative permalink (`/d/<id>`) — absolute-ise against the host you posted to. */
  val url: String,
  /** Human time left on the link, e.g. `1h`. */
  val expiresIn: String,
  val expiresAtEpochSeconds: Long,
)

/**
 * What `POST /images` answers: [path] for addressing this host, and [url] (absolute, from the
 * forwarded origin) for pasting elsewhere.
 */
@Serializable
private data class ImageAcceptedResponse(
  val schema: String = "compose-preview-serve/image/v1",
  /** The link id — the capability. */
  val id: String,
  /** The display label (the sanitised upload name), also the alt text in [markdown]. */
  val name: String,
  /** Human format name ([ServeImageFormat.label]). */
  val format: String,
  /** Wire format id ([ServeImageFormat.id]) for a programmatic client. */
  val formatId: String,
  val bytes: Int,
  /** Intrinsic pixel size when the image's header declared one. */
  val width: Int? = null,
  val height: Int? = null,
  /** Relative link (`/i/<id>.png`). */
  val path: String,
  /** Absolute link — what goes in a PR body. */
  val url: String,
  /** The finished embed line, ready to paste: `![name](url)`. */
  val markdown: String,
  /** The GitHub login this upload was attributed to. */
  val uploadedBy: String,
  /** Human time left on the link, e.g. `7d`. */
  val expiresIn: String,
  val expiresAtEpochSeconds: Long,
)

@Serializable
private data class BundleAcceptedResponse(
  val schema: String = "compose-preview-serve/bundle/v1",
  val session: String,
  val previews: Int,
  /** Relative viewer link for the new session (append your token). */
  val path: String,
  /**
   * Producer-trust verdict for the upload ([BundleVerifier.summary]): `signature:<keyId>`,
   * `branch:<repo>@<branch>`, `provenance:<id>`, or `unverified`. Data tiers serve either way.
   */
  val trust: String,
)

/**
 * Reply from the per-catalog theme-cache admin routes. [entries] is what `regenerate` queued (zero
 * is legitimate). [dropped] false means a render held the generation write lock; retry.
 */
@Serializable
private data class ThemeCacheActionDto(
  val system: String,
  val action: String,
  val entries: Int = 0,
  val dropped: Boolean? = null,
  /**
   * Whether `regenerate` queued anything. False with a 409: theme optimization is off, or the mark
   * couldn't be persisted.
   */
  val queued: Boolean? = null,
)

/** Reply from the optimizer pause/resume admin routes. */
@Serializable
private data class OptimizerPauseDto(
  val paused: Boolean,
  val pausedUntilEpochMillis: Long? = null,
  val reason: String? = null,
)
