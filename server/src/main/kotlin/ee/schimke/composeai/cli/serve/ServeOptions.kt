package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.bundle.TrustStore
import ee.schimke.composeai.previewdata.PreviewManifest
import ee.schimke.composeai.previewdata.PreviewModule
import ee.schimke.composeai.uibuilder.export.CatalogOwnership
import java.io.File

/**
 * The normalized configuration the preview server consumes.
 *
 * [ServeCommandOptions] owns argv syntax, defaults and normalization; this interface is the stable
 * shape [ServeRunner] and tests consume. Build operations live separately in [ServeBuildHost],
 * which deliberately names no Gradle type.
 */
public interface ServeOptions {

  public val lan: Boolean

  public val host: String

  public val requestedPort: Int

  public val tokenOverride: String?

  /**
   * Cap on concurrent daemon-backed ("live seat") stream sessions; `0` (default) is unbounded. A
   * small value bounds the JVM daemons a constrained public box will spawn, refusing over-cap
   * streams rather than risking OOM. Snapshot and Wasm tiers never take a seat.
   */
  public val liveSeats: Int

  /**
   * Background renders admitted at once, server-wide; null derives one from the seat budget via
   * [ServeBackgroundWork.renderLaneFor]. Deliberately un-clamped: the derivation is conservative,
   * while an operator naming a number knows their box, and seats still bound daemon count.
   */
  public val backgroundRenders: Int?

  /**
   * Warm Android sandbox workers kept booted for catalog daemons to adopt ([ServeSpareSandboxes]);
   * `0` (default) keeps none. Each is a resident Robolectric JVM (~450-500 MB), hence a budget
   * separate from [liveSeats].
   */
  public val spareSandboxes: Int

  public val exportPath: String?

  public val inlineBundle: Boolean

  /**
   * Project mode: also fork a daemon-backed session per git revision requested via
   * `?session=<rev>`, each in its own worktree. Off by default.
   */
  public val revisions: Boolean

  /**
   * Baseline delivery branch the viewer's render-history strip is computed from
   * ([ServeProjectHistory]). On by default and self-disabling when the branch was never fetched;
   * `--no-history` turns it off.
   */
  public val historyBranch: String?

  /**
   * Opt in to local Gradle discovery + build. Off by default so a stray `serve` at a repo root
   * hosts only fetched sources instead of triggering a full (possibly hanging) module build.
   */
  public val discover: Boolean

  /**
   * Trusted server-side re-render (SECURITY/RCE, opt-in, default off): a Trusted `--catalogs`
   * catalog that declares a `source` is served by a daemon-backed session built from that source.
   * Building runs Gradle, so it is gated three ways: the catalog must be Trusted, `source.ref` must
   * clear [revisionAllowRefs] (fail-closed), and `source.repo` must be [catalogRepo]. Never enable
   * on a box that can't build the catalog source.
   */
  public val allowRenderTrusted: Boolean

  /**
   * Git repo root for the trusted-catalog builder ([buildTrustedCatalogSource]) and its
   * [GitWorktrees], instead of the served module's project root. Lets a module-less box live-render
   * a fetched catalog from a separate checkout; the repo and ref gates still apply.
   */
  public val catalogSourceRoot: File?

  /**
   * Where read-only checkouts of pasted repositories live (`--onboard-cache`) for `POST
   * /admin/onboard/scan`. Nothing in them is ever executed; the scan reads them as text.
   */
  public val onboardCacheDir: File

  /**
   * Project mode revision policy (SECURITY/RCE): refs a requested `?session=<rev>` must be
   * reachable from to be built. Empty = nothing builds (fail closed). Also gates the
   * trusted-catalog source build ([allowRenderTrusted]).
   */
  public val revisionAllowRefs: List<String>

  /**
   * Ephemeral mode: shut down after [idleExitSeconds] with no open connections or requests. Off by
   * default.
   */
  public val exitWhenIdle: Boolean

  public val idleExitSeconds: Long

  /**
   * Seconds between re-checks of each `--catalogs` branch head; a moved head re-fetches the catalog
   * in place ([ServeCatalogRefresher]). `0` or negative disables polling.
   */
  public val catalogRefreshSeconds: Long

  /**
   * How long an RSS reader keeps a catalog's change-feed worker interested; each `feed.xml` request
   * renews the lease. `0` disables the feed lane.
   */
  public val catalogFeedIdleSeconds: Long

  /**
   * Shared mode: a directory of pre-rendered bundles (or a single bundle) hosted read-only at
   * `?session=<bundle-name>`.
   */
  public val bundlesDir: String?

  /**
   * Raw repeatable `--bundle` values, unparsed; `ServeStartupBundles.Spec` is the server's shape.
   */
  public val bundleFlags: List<String>

  /**
   * Enable `POST /bundles/{name}` uploads (zip or `?url=`). Off by default; combine with `--lan`
   * and a strong `--token`.
   */
  public val acceptBundles: Boolean

  /**
   * Public mode: serve every route without the token. Safe by construction: no server-side code
   * execution, untrusted re-render refused, uploads capped and SSRF-gated. Off by default.
   */
  public val public: Boolean

  /**
   * Streamlined, Storybook-like presentation over the same routes, exposing only catalog browsing,
   * variants, usage source and a few controls.
   */
  public val componentBrowser: Boolean

  /** Internal convenience used by [BrowseCommand]; full `serve` keeps its print-only behaviour. */
  public val openBrowser: Boolean

  /**
   * Which page [openBrowser] opens: an absolute path with no query (the opener appends the token).
   * A default member so adding it is not a source break for existing implementors; `ui` overrides
   * it with `/ui-builder/<catalog>/`.
   */
  public val openBrowserPath: String
    get() = "/"

  /** SSRF allowlist for `POST /bundles/{name}?url=` fetches. Empty = no URL fetch (fail closed). */
  public val acceptBundlesFrom: List<String>

  /**
   * Document ingestion (`--accept-docs`): `GET`/`POST /docs` turns one known document
   * ([ServeDocFormats]) into an expiring `/d/<id>` permalink. Off by default; independent of
   * `--accept-bundles`.
   */
  public val acceptDocs: Boolean

  /** How long an ingested document's permalink lives (`--doc-ttl <seconds>`). */
  public val docTtlSeconds: Long

  /** SSRF allowlist for `POST /docs?url=`. Empty = uploads only (fail closed). */
  public val acceptDocsFrom: List<String>

  /**
   * `--playground-bundle <path|system>`: enable the playground lane, resolving the CMP classpath
   * from a local `.bundle` or a served `--catalogs` system id ([PlaygroundBundleSource]). Under
   * `--public` it must still clear [PlaygroundPublicGate] (verified sandbox or GitHub repo-access
   * gating), since it compiles user-supplied code.
   */
  public val playgroundBundlePath: String?

  /**
   * `--playground-android-bundle <path|system>`: enable the Android / Remote Compose compile lane,
   * resolved like `--playground-bundle` and gated the same way under `--public`. Needs the
   * `lib-daemon-android` sidecar, `android.jar` and the `/d/` document store.
   */
  public val playgroundAndroidBundlePath: String?

  /**
   * `--playground`: enable the playground with nothing pinned, offering a runtime selector over
   * served catalogs ([PlaygroundCatalogTargets]). Pinned bundles become the preselected default.
   * Refused when there is neither a pin nor a served catalog.
   */
  public val playgroundRuntimeSelection: Boolean

  /**
   * `--compile-engine`: start the playground compile engine for this host's own consumers
   * (UI-builder native preview, Remote Compose capture) without mounting the public playground
   * surface. Add `--playground` to expose it as well.
   */
  public val compileEngine: Boolean

  /**
   * `--role playground`: this process is the public playground's own container behind the shared
   * proxy; it mints `/d/` ids with [ServeDocStore.PLAYGROUND_PREFIX] so the proxy routes them back
   * here.
   */
  public val playgroundRole: Boolean

  /**
   * `--playground-external`: a sibling `--role playground` process serves the playground at this
   * origin, so this host only renders editor handoff links. Needs `--compile-engine` to decide
   * which catalogs get one.
   */
  public val playgroundExternal: Boolean

  /**
   * `--playground-catalog-limit`: runtime-selected catalogs that may hold a resolved classpath at
   * once. They cannot be evicted while snippet JVMs hold their jars, so this bounds disk use on a
   * public host.
   */
  public val playgroundCatalogLimit: Int

  /**
   * `--playground-rate-limit`: compiles per minute per caller (`0` disables). The per-caller
   * fairness bound; every other playground limit is host-wide.
   */
  public val playgroundRateLimit: Int

  /**
   * `--playground-caller-concurrency`: compiles one caller may hold at once (default 1), so one
   * caller cannot occupy every compile slot.
   */
  public val playgroundCallerConcurrency: Int

  /** Authenticated, explicitly acquired, single-host stateful BTA editing trial. Off by default. */
  public val playgroundEditing: Boolean

  public val playgroundEditLeaseTtlSeconds: Long

  /**
   * `--trust-forwarded-for`: key rate limits on the last `X-Forwarded-For` entry and take public
   * host/scheme from `X-Forwarded-Host`/`-Proto`. Opt-in because the headers are client-supplied;
   * enable only behind a reverse proxy you control that sets the last entry from the peer address.
   */
  public val trustForwardedFor: Boolean

  /**
   * `--playground-sandbox <profile>`: the per-session sandbox each snippet JVM runs in (`none` |
   * `unshare` | `bwrap` | `systemd` | `strict` | `custom:<argv>`). A verified sandbox, or GitHub
   * repo-access gating, is what admits the playground under `--public`. Default `none`.
   */
  public val playgroundSandboxSpec: String?

  public val playgroundSandboxMemoryMb: Int

  public val playgroundSandboxCpus: Double

  public val playgroundSandboxPids: Int

  /**
   * `--playground-compile-slots`: compiles that may hold a jailed JVM at once, so peak compile
   * memory is `slots × --playground-sandbox-memory-mb`.
   */
  public val playgroundCompileSlots: Int

  /** Hard wall-clock lifetime of one snippet JVM; the spawner kills it at the deadline. */
  public val playgroundSandboxTtlSeconds: Long

  /**
   * `--playground-sandbox-ro`: extra host paths bound read-only into the jail, for caches a
   * networkless render reads (Robolectric `android-all`, downloadable fonts).
   */
  public val playgroundSandboxReadOnlyPaths: List<String>

  /**
   * Extra Maven repository URLs the live-daemon classpath resolver may fetch from, beyond Central
   * and Google. Without them a catalog's non-default deps are skipped and it falls back to baked
   * PNGs. Operator-curated: list only repos you trust.
   */
  public val extraMavenRepos: List<String>

  /**
   * Producer-trust store path ([TrustStore]) uploaded bundles are verified against. Absent ⇒ the
   * empty, fail-closed store (every upload `unverified`).
   */
  public val trustStorePath: String?

  /**
   * Design systems served from their `design-artifacts/<system>` branches (`--catalogs
   * compose-m3,wear-m3`), each at `/<system>/`. An entry may name its own source repo as
   * `<system>@<owner>/<repo>`; otherwise `--catalog-repo` is used.
   */
  public val catalogsRaw: String?

  /** Like [catalogsRaw], but served without a landing-page nav link. */
  public val catalogsUnlistedRaw: String?

  /**
   * Catalog registry projects (`--catalog-registry owner/repo[@ref],…`): projects whose
   * `.compose-preview/catalogs.json` lists catalogs to serve as if named in [catalogsRaw]. Re-read
   * every [catalogRefreshSeconds], so listing a catalog imports it without a restart. See
   * [ServeCatalogRegistry].
   */
  public val catalogRegistryRaw: String?

  /**
   * Top-level sites (`--sites host=catalog,…`): host names on which one served catalog is presented
   * as the whole server. See [ServeSites].
   */
  public val sitesRaw: String?

  /** Optional builder entry hostname; keeps editor, API and assets on that origin. */
  public val uiBuilderHost: String?

  /** Optional HTTPS guide URL linked by the builder host at /start. */
  public val uiBuilderStartUrl: String?

  /**
   * Serve the UI builder at the root of [uiBuilderHost] instead of under `/ui-builder/`. Needs an
   * editor bundle that reads the `ui-builder-base-path` meta; ignored without [uiBuilderHost].
   */
  public val uiBuilderHostRoot: Boolean
    get() = false

  /** Raw `--catalogs-file` path, unopened; `ServeCatalogsConfigFile` owns what the file means. */
  public val catalogsFilePath: String?

  /** The deployment's `settings.json` ([ServeSettings]); null ⇒ none. */
  public val settingsFilePath: String?
    get() = null

  /** Durable feed cache; defaults beside catalogs.json on deployed boxes, temp for local serve. */
  public val catalogFeedCacheDir: File

  /**
   * Shared secret for `/admin/catalogs` and `/admin/trust`; absent ⇒ no admin surface. Distinct
   * from the browse token, which `--public` hands to everyone. With `--allow-render-trusted` this
   * is a code-execution credential.
   */
  public val adminToken: String?

  /**
   * Read-only credential for the UI-builder admin overview: lists design summaries but cannot read
   * documents or mutate anything.
   */
  public val adminReadToken: String?

  /** GitHub actor ids allowed to administer every shared UI-builder design on this host. */
  public val uiBuilderAdminActors: Set<String>

  /**
   * Whether a new UI-builder design starts public or private; the owner can change it per design.
   */
  public val uiBuilderDefaultVisibility: UiBuilderDefaultVisibility
    get() = UiBuilderDefaultVisibility.PRIVATE

  /**
   * Stable public origin UI-builder designs are canonical at (their `home`). Falls back to
   * `--github-auth-callback-base-url`; with neither, designs stay unhomed.
   */
  public val uiBuilderPublicOrigin: String?
    get() = null

  /** Optional durable aggregate counters. Null keeps local serve sessions in-memory only. */
  public val engagementFile: File?

  public val githubAuthClientId: String?

  public val githubAuthClientSecret: String?

  public val githubAuthCookieSecret: String?

  public val githubAuthRepo: String?

  public val githubAuthCallbackBaseUrl: String?

  /**
   * Scopes auth cookies to a parent domain so one sign-in covers every `--sites` host under it.
   * Explicit rather than derived, since a cookie domain is a session's blast radius.
   */
  public val githubAuthCookieDomain: String?

  /** Overrides the OAuth scope; unset derives it from `--github-auth-repo`'s visibility. */
  public val githubAuthScope: String?

  public val githubAuthUsers: Set<String>

  /** GitHub organizations whose members sign in as if listed in [githubAuthUsers]. */
  public val githubAuthOrgs: Set<String>
    get() = emptySet()

  /**
   * Admit other GitHub accounts as guests who can read designs shared with them and request access;
   * a guest is signed out everywhere else.
   */
  public val githubAuthGuests: Boolean
    get() = false

  /** GitHub logins who may run the `guidelines` design check on the operator's OpenRouter key. */
  public val uiBuilderGuidelinesUsers: Set<String>
    get() = emptySet()

  /** `--ui-builder-guidelines-orgs`: GitHub organizations whose members may run it too. */
  public val uiBuilderGuidelinesOrgs: Set<String>
    get() = emptySet()

  /** `--ui-builder-guidelines-model`: the OpenRouter model id; null for the default. */
  public val uiBuilderGuidelinesModel: String?
    get() = null

  /**
   * Seconds a guidelines prompt waits for uncached native renders before answering without them.
   */
  public val uiBuilderGuidelinesPictureBudgetSeconds: Long
    get() = DEFAULT_GUIDELINES_PICTURE_BUDGET_SECONDS

  /**
   * Whether a guidelines check first asks a cheap triage model which extra evidence to gather. On
   * by default.
   */
  public val uiBuilderGuidelinesTriage: Boolean
    get() = true

  /**
   * Every signed-in GitHub member may create, edit and export UI-builder designs and approve agent
   * grants, without repository access.
   */
  public val githubAuthOpenUiBuilder: Boolean
    get() = false

  /**
   * Agent access grants: enable the device-grant flow at `/agent-access/…` so an agent can request
   * temporary, scoped, revocable access that a human approves. Off by default and never derived,
   * since the lane mints credentials.
   */
  public val agentGrants: Boolean

  /** Expose all catalogs through aggregate Streamable HTTP MCP at `/mcp`. */
  public val catalogMcp: Boolean

  /** Raw `--agent-grant-scopes`, unparsed; scope meaning is server policy. */
  public val agentGrantScopesFlag: String?

  /** Raw `--agent-grant-max-ttl`, unparsed; the grammar and ceiling are server policy. */
  public val agentGrantMaxTtlFlag: String?

  /**
   * Raw `--agent-grant-capabilities`, unparsed, so the CLI never needs `AgentGrantCapability` on
   * its classpath.
   */
  public val agentGrantCapabilitiesFlag: String?

  public val agentGrantMaxActive: Int

  /**
   * Per-address requests/minute on the two ungated grant routes (`0` disables): enough for a
   * polling agent, small enough that anonymous callers cannot churn the request map.
   */
  public val agentGrantRateLimit: Int

  /**
   * Image ingestion (`--accept-images`): `POST /images` returns an `/i/<id>.png` an agent can embed
   * in a PR body. Uploading always requires a GitHub token with access to [imageUploadRepository];
   * reading is open so GitHub's image proxy can fetch it. See [ServeImageStore].
   */
  public val acceptImages: Boolean

  /** How long an uploaded image's link lives (`--image-ttl <seconds>`); default 7 days. */
  public val imageTtlSeconds: Long

  /**
   * Repository an image uploader must have access to, falling back to the GitHub-auth repo. The
   * lane refuses to start without one.
   */
  public val imageUploadRepository: String?

  /** Uploads per minute per GitHub account (`--image-rate-limit`); `0` disables the budget. */
  public val imageRateLimit: Int

  /**
   * Raw `--image-upload-tokens`: accepted GitHub token kinds; null takes the default
   * ([ImageUploadTokenPolicy.parse]).
   */
  public val imageUploadTokensFlag: String?
    get() = null

  /**
   * Directory used for server-wide admission of the catalogs' background theme optimization, which
   * parks while any catalog loads and bounds concurrent renders. See [ServeBackgroundWork].
   */
  public val optimizerCoordinationDirectory: File?

  /**
   * Raw `--catalog-cache-dir`, or `none` to disable persistence; resolved during server startup.
   */
  public val catalogCacheDirFlag: String?

  /** Raw `--catalog-cache-max-bytes`; null means "use the server's default". */
  public val catalogCacheMaxBytesFlag: Long?

  /** Raw `--theme-cache-dir`, or `none` to disable; resolved during server startup. */
  public val themeCacheDirFlag: String?

  /** Raw `--theme-cache-max-bytes`; null means "use the server's default". */
  public val themeCacheMaxBytesFlag: Long?

  /** `--theme-cache-evict`: drop every cached generation once, at startup. */
  public val themeCacheEvictRequested: Boolean

  /**
   * In-browser CMP tier (`--wasm-dir <system>=<dir>,…`): maps a design system to its assembled Wasm
   * catalog app. Missing dirs are dropped with a warning.
   */
  public val wasmDirs: Map<String, File>

  /**
   * Packaged catalog browser for catalogs without their own Wasm app, projected at
   * `/wasm/<system>/` for every session.
   */
  public val wasmUiDir: File?

  /** Standalone Compose UI builder app served at `/ui-builder/`. */
  public val uiBuilderDir: File?
    get() = null

  /** Catalog ids explicitly exposed as UI-builder instances. Registration alone never opts in. */
  public val uiBuilderCatalogs: Set<String>
    get() = setOf("m3-catalog")

  /**
   * Enabled catalogs that may be served from their published `ui-builder.json` instead of the
   * built-in Kotlin definition. Null (default) allows all, empty allows none. The per-catalog,
   * reversible switch, since the two definitions can differ; check with
   * `.github/scripts/ui-builder-equivalence.sh` before flipping one.
   */
  public val uiBuilderPublishedCatalogs: Set<String>?
    get() = null

  /**
   * Builder catalogs served from their published `ui-builder.json` unless told otherwise; the image
   * entrypoint passes the list it derives `--ui-builder-published-catalogs` from.
   */
  public val uiBuilderPublishedDefault: Set<String>
    get() = emptySet()

  /**
   * Builder catalogs this machine cannot serve, which `catalogs.json` cannot turn back on (e.g.
   * `wear-m3` under `SERVE_UI_BUILDER_WEAR=0`).
   */
  public val uiBuilderUnavailableCatalogs: Set<String>
    get() = emptySet()

  /**
   * Builder catalogs reported at startup as if catalog-owned while still served as-is: the shadow
   * step before [uiBuilderCatalogOwnership].
   */
  public val uiBuilderShadowCatalogs: Set<String>
    get() = emptySet()

  /**
   * Which UI-builder catalogs answer for themselves: seed templates, chooser card and export route
   * come from what they publish, and their synthesised Kotlin definition is never built.
   * [CatalogOwnership.NONE] (default) changes nothing.
   */
  public val uiBuilderCatalogOwnership: CatalogOwnership
    get() = CatalogOwnership.NONE

  /**
   * Discovered component records per UI-builder catalog (`--ui-builder-components
   * <system>=<components.json>,…`). Compose export generates from the record matching the design's
   * pinned catalog; a catalog with none refuses export
   * ([ScreenGeneratorComposeExportExecutor.NO_COMPONENT_RECORD]). `remote-m3` and `wear-m3` need no
   * record: `RecordFreeExport` writes them.
   */
  public val uiBuilderComponents: Map<String, File>

  /**
   * Where each project keeps the designs it is working on: `[<catalog>=]<dir>`. Designs exported
   * into that checkout appear immediately, with no publish step.
   */
  public val uiBuilderDesigns: Map<String, File>
    get() = emptyMap()

  /**
   * URL a comment board's activity is posted to (see [ServeUiBuilderCommentWebhook]); null keeps it
   * in-product. Treat it as a credential: it is never logged, and only `https` (or loopback) is
   * accepted.
   */
  public val uiBuilderCommentWebhook: String?
    get() = null

  /**
   * Webhook body format: `plain`, `slack`, `teams` or `google-chat`; defaults to the server's own
   * event JSON. Named explicitly rather than sniffed from the host, which says nothing behind a
   * relay.
   */
  public val uiBuilderCommentWebhookFormat: String?
    get() = null

  /**
   * Webhook event kinds: comma-separated `comments`, `fork`, `decision`, `implementation`, or
   * `all`. Defaults to `comments` so existing hooks get nothing new.
   */
  public val uiBuilderWebhookEvents: String?
    get() = null

  /**
   * Web Push notifications, on by default where they can work (GitHub sign-in plus a UI builder);
   * users still opt in per browser. See [ServePushNotifier].
   */
  public val webPush: Boolean
    get() = true

  /** The VAPID `sub` claim; defaults to the deployment's https origin, else the project page. */
  public val vapidSubject: String?
    get() = null

  /**
   * Pinned VAPID key pair (base64url); unset generates one on first start. The private key is a
   * credential: it can push to every subscriber.
   */
  public val vapidPublicKey: String?
    get() = null

  public val vapidPrivateKey: String?
    get() = null

  /**
   * Served catalog each UI-builder catalog is compiled against for the native preview lane
   * (`<builder catalog>=<served catalog>`). Absent entries use a served catalog of the same name;
   * an Android (e.g. Wear) target also selects the Robolectric daemon. Unresolvable designs are
   * refused with [ServeUiBuilderNativePreview.NO_NATIVE_CATALOG].
   */
  public val uiBuilderNativeCatalogs: Map<String, String>
    get() = emptyMap()

  /**
   * Which player draws a Wear widget design in the native preview lane (`cmp-android` or
   * `androidx`). `cmp-android` applies only where the bundle carries `rc-player-compose`;
   * `androidx`'s `WearWidgetPreview` currently drops a `RemoteButton`'s container
   * (yschimke/compose-ui-builder#511).
   */
  public val uiBuilderWidgetPlayer: UiBuilderWidgetPlayer
    get() = UiBuilderWidgetPlayer.DEFAULT

  /**
   * Served catalogs admitted as UI-builder component packs (`<served
   * catalog>=<mobile|wear|remote-compose>,…`), projected from each catalog's discovered component
   * record. A catalog with no record is logged and skipped. Empty by default: admitting a pack is
   * an operator decision.
   */
  public val uiBuilderPacks: Map<String, String>
    get() = emptyMap()

  /**
   * Retained native renderer bundles (`runtimeId` to directory), each with a verified
   * `runtime-manifest.json`; ids are exact pins, never aliases.
   */
  public val uiBuilderRuntimeDirs: Map<String, File>
    get() = emptyMap()

  /**
   * Durable UI-builder state directory, or `none` to keep the API off. Null derives a stable
   * location when the builder is enabled.
   */
  public val uiBuilderStateDirFlag: String?
    get() = null

  /** Explicitly migrate a validated v1 UI-builder store to the latest recoverable format. */
  public val uiBuilderMigrateState: Boolean
    get() = false

  /** Experimental AndroidX-conformant Remote Compose CMP/Wasm player distribution. */
  public val rcPlayerWasmDir: File?

  /**
   * Default Remote Compose player id ([ServeRcPlayerIds]), or null for the built-in order. A
   * preview that does not enable it falls back via [ServeRcPlayerIds.defaultPlayer].
   */
  public val rcDefaultPlayer: String?
    get() = null

  public val catalogRepo: String

  public val catalogBranchPrefix: String

  public val catalogMaxImages: Int

  // Shared selectors and timeouts every command parses; the server reads them too, so they are part
  // of this contract.

  /** `--module`, the Gradle path a run is scoped to, or null for "every module". */
  public val explicitModule: String?

  /** `--filter`, a substring match over preview ids. */
  public val filter: String?

  /** `--id`, an exact preview id. */
  public val exactId: String?

  /** `--preview`, a loose reference (`Class.method`, a file, a fully-qualified id). */
  public val previewRef: String?

  /** Whether selection keeps a `@PreviewParameter` preview whose *rows* might match. */
  public val rowAwareSelection: Boolean

  /** Per-invocation Gradle timeout. */
  public val timeoutSeconds: Long

  /** True when `serve` was launched by `browse`, which gets a print-free, auto-opening variant. */
  public val browseProject: Boolean

  // Preview-reference matching is tool-wide policy, injected by the root CLI so `serve`, `render`
  // and `show` agree.

  /** The shared preview-id selector rule, so `serve` and the other commands agree on a match. */
  public fun previewIdMatchesRequest(
    id: String,
    exactId: String?,
    filter: String?,
    previewRef: String? = null,
    className: String? = null,
    functionName: String? = null,
  ): Boolean
}

/**
 * What the server needs back from a discovery build. Narrower than `Command.RenderModulesOutcome`
 * to keep the Tooling API off this module.
 */
public class ServeDiscovery(
  public val buildOk: Boolean,
  public val manifests: List<Pair<PreviewModule, PreviewManifest>>,
)

/**
 * [discovered] narrowed to the module [requested] names, or null when absent. Guards against an
 * older build host that ignores `--module` and reports every module. Both sides are normalised,
 * since `:app` and `app` are the same module.
 */
internal fun selectRequestedModule(
  discovered: ServeDiscovery,
  requested: String,
): ServeDiscovery? {
  val normalized = requested.removePrefix(":")
  val selected = discovered.manifests.filter { it.first.gradlePath.removePrefix(":") == normalized }
  return if (selected.isEmpty()) null else ServeDiscovery(discovered.buildOk, selected)
}

/**
 * Default `--ui-builder-guidelines-picture-budget`: below every timeout in front of an MCP call.
 */
public const val DEFAULT_GUIDELINES_PICTURE_BUDGET_SECONDS: Long = 45L
