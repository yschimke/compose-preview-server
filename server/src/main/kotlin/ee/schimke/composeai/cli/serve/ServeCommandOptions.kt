package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.bundle.TrustStore
import ee.schimke.composeai.uibuilder.export.CatalogOwnership
import java.io.File

/**
 * The preview server's command-line configuration. Argument names, normalization, defaults and
 * usage text belong to the server; the root CLI supplies only its default Gradle timeout and the
 * shared preview-selector rule. Gradle operations stay behind [ServeBuildHost], so this module
 * depends on neither the CLI nor the Tooling API.
 */
public class ServeCommandOptions(
  private val args: List<String>,
  override val browseProject: Boolean = false,
  defaultTimeoutSeconds: Long,
  private val previewMatcher: (String, String?, String?, String?, String?, String?) -> Boolean,
) : ServeOptions {

  override val explicitModule: String? = args.flagValue("--module")

  override val filter: String? = args.flagValue("--filter")

  override val exactId: String? = args.flagValue("--id")

  override val previewRef: String? = args.flagValue("--preview")?.takeIf { it.isNotBlank() }

  override val timeoutSeconds: Long =
    args.flagValue("--timeout")?.toLongOrNull() ?: defaultTimeoutSeconds

  public val helpRequested: Boolean = "--help" in args || "-h" in args

  override val lan: Boolean = "--lan" in args

  override val host: String =
    when {
      lan -> ServeDefaults.HOST_ALL_INTERFACES
      else -> args.flagValue("--host")?.takeIf { it.isNotBlank() } ?: ServeDefaults.HOST_LOOPBACK
    }

  override val requestedPort: Int =
    args.flagValue("--port")?.toIntOrNull() ?: ServeDefaults.DEFAULT_PORT

  override val tokenOverride: String? = args.flagValue("--token")?.takeIf { it.isNotBlank() }

  override val liveSeats: Int = args.flagValue("--live-seats")?.toIntOrNull()?.coerceAtLeast(0) ?: 0

  override val backgroundRenders: Int? =
    args.flagValue("--background-renders")?.toIntOrNull()?.takeIf { it >= 1 }

  /**
   * The flag, else the `composeai.serve.spareSandboxes` system property (the prebuilt image
   * configures via `JAVA_TOOL_OPTIONS`), else none.
   */
  override val spareSandboxes: Int =
    (args.flagValue("--spare-sandboxes") ?: System.getProperty(SPARE_SANDBOXES_PROP))
      ?.toIntOrNull()
      ?.coerceAtLeast(0) ?: 0

  override val exportPath: String? = args.flagValue("--export")?.takeIf { it.isNotBlank() }

  override val inlineBundle: Boolean = "--inline" in args

  override val revisions: Boolean = "--revisions" in args

  override val historyBranch: String? =
    if ("--no-history" in args) null
    else
      args.flagValue("--history-branch")?.trim()?.takeIf { it.isNotEmpty() }
        ?: ServeDefaults.HISTORY_BRANCH

  override val discover: Boolean = "--discover" in args

  override val allowRenderTrusted: Boolean = "--allow-render-trusted" in args

  override val catalogSourceRoot: File? =
    args.flagValue("--catalog-source-root")?.takeIf { it.isNotBlank() }?.let { File(it) }

  override val onboardCacheDir: File =
    args.flagValue("--onboard-cache")?.takeIf { it.isNotBlank() }?.let(::File)
      ?: java.nio.file.Files.createTempDirectory("serve-onboard-sources").toFile().also {
        it.deleteOnExit()
      }

  override val revisionAllowRefs: List<String> =
    args.flagValue("--revisions-allow")?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
      ?: emptyList()

  override val exitWhenIdle: Boolean = args.any {
    it == "--exit-when-idle" || it.startsWith("--exit-when-idle=")
  }

  override val idleExitSeconds: Long =
    args.flagValue("--exit-when-idle")?.toLongOrNull()?.takeIf { it > 0 }
      ?: ServeDefaults.DEFAULT_IDLE_EXIT_SECONDS

  /** Wired from `SERVE_CATALOG_REFRESH` by the image entrypoint. */
  override val catalogRefreshSeconds: Long =
    args.flagValue("--catalog-refresh-interval")?.toLongOrNull()
      ?: ServeDefaults.DEFAULT_CATALOG_REFRESH_SECONDS

  override val catalogFeedIdleSeconds: Long =
    args.flagValue("--catalog-feed-idle-timeout")?.toLongOrNull()
      ?: ServeDefaults.DEFAULT_CATALOG_FEED_IDLE_SECONDS

  override val bundlesDir: String? = args.flagValue("--bundles")?.takeIf { it.isNotBlank() }

  /** Raw repeatable `--bundle` values; the server parses them into startup specs. */
  override val bundleFlags: List<String> = args.flagValuesAll("--bundle")
  override val acceptBundles: Boolean = "--accept-bundles" in args

  override val public: Boolean = "--public" in args

  override val componentBrowser: Boolean = "--component-browser" in args

  /** Internal convenience used by [BrowseCommand]; full `serve` keeps its print-only behaviour. */
  override val openBrowser: Boolean = "--open-browser" in args

  /**
   * `--open-path <path>`: rejected unless absolute and query-free, since the token is appended as a
   * query.
   */
  override val openBrowserPath: String =
    args
      .flagValue("--open-path")
      ?.takeIf { it.isNotBlank() }
      ?.also {
        require(it.startsWith("/") && '?' !in it && '#' !in it) {
          "--open-path must be an absolute path with no query or fragment, got `$it`"
        }
      } ?: "/"

  override val acceptBundlesFrom: List<String> =
    args
      .flagValue("--accept-bundles-from")
      ?.split(",")
      ?.map { it.trim() }
      ?.filter { it.isNotEmpty() } ?: emptyList()

  override val acceptDocs: Boolean = "--accept-docs" in args

  /** How long an ingested document's permalink lives (`--doc-ttl <seconds>`). */
  override val docTtlSeconds: Long =
    args.flagValue("--doc-ttl")?.toLongOrNull()?.takeIf { it > 0 } ?: ServeDefaults.DOC_TTL_SECONDS

  override val acceptDocsFrom: List<String> =
    args.flagValue("--accept-docs-from")?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
      ?: emptyList()

  override val playgroundBundlePath: String? = args.flagValue("--playground-bundle")

  override val playgroundAndroidBundlePath: String? = args.flagValue("--playground-android-bundle")

  override val playgroundRuntimeSelection: Boolean = "--playground" in args

  /** `--compile-engine`: the compile engine without the public playground; see [ServeOptions]. */
  override val compileEngine: Boolean = "--compile-engine" in args

  /** `--role playground`; see [ServeOptions]. Any other role is a startup error. */
  override val playgroundRole: Boolean =
    when (val role = args.flagValue("--role")) {
      null -> false
      "playground" -> true
      else ->
        throw IllegalArgumentException("--role '$role' is not a role — the only one is playground")
    }

  /** `--playground-external`; see [ServeOptions]. */
  override val playgroundExternal: Boolean = "--playground-external" in args

  override val playgroundCatalogLimit: Int =
    args.flagValue("--playground-catalog-limit")?.toIntOrNull()?.takeIf { it > 0 }
      ?: ServeDefaults.PLAYGROUND_CATALOG_LIMIT

  override val playgroundRateLimit: Int =
    args.flagValue("--playground-rate-limit")?.toIntOrNull()?.takeIf { it >= 0 }
      ?: ServeDefaults.DEFAULT_PLAYGROUND_RATE_LIMIT

  override val playgroundCallerConcurrency: Int =
    args.flagValue("--playground-caller-concurrency")?.toIntOrNull()?.takeIf { it > 0 } ?: 1

  /** Authenticated, explicitly acquired, single-host stateful BTA editing trial. Off by default. */
  override val playgroundEditing: Boolean = "--playground-editing" in args

  override val playgroundEditLeaseTtlSeconds: Long =
    args.flagValue("--playground-edit-lease-ttl")?.toLongOrNull()?.takeIf { it > 0 }
      ?: ServeDefaults.PLAYGROUND_EDIT_LEASE_TTL_MILLIS / 1000

  override val trustForwardedFor: Boolean = "--trust-forwarded-for" in args

  override val playgroundSandboxSpec: String? = args.flagValue("--playground-sandbox")

  override val playgroundSandboxMemoryMb: Int =
    args.flagValue("--playground-sandbox-memory-mb")?.toIntOrNull()
      ?: ServeDefaults.PLAYGROUND_SANDBOX_MEMORY_MB

  override val playgroundSandboxCpus: Double =
    args.flagValue("--playground-sandbox-cpus")?.toDoubleOrNull()
      ?: ServeDefaults.PLAYGROUND_SANDBOX_CPUS

  override val playgroundSandboxPids: Int =
    args.flagValue("--playground-sandbox-pids")?.toIntOrNull()
      ?: ServeDefaults.PLAYGROUND_SANDBOX_PIDS

  override val playgroundCompileSlots: Int =
    args.flagValue("--playground-compile-slots")?.toIntOrNull()?.takeIf { it > 0 }
      ?: ServeDefaults.PLAYGROUND_COMPILE_SLOTS

  /** Hard wall-clock lifetime of one snippet JVM; the spawner kills it at the deadline. */
  override val playgroundSandboxTtlSeconds: Long =
    args.flagValue("--playground-sandbox-ttl")?.toLongOrNull()
      ?: ServeDefaults.PLAYGROUND_SANDBOX_TTL_SECONDS

  override val playgroundSandboxReadOnlyPaths: List<String> =
    args
      .flagValue("--playground-sandbox-ro")
      ?.split(",")
      ?.map { it.trim() }
      ?.filter { it.isNotEmpty() } ?: emptyList()

  /** Env `SERVE_EXTRA_MAVEN_REPOS` in the image. */
  override val extraMavenRepos: List<String> =
    args.flagValue("--extra-maven-repos")?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
      ?: emptyList()

  override val trustStorePath: String? = args.flagValue("--trust-store")

  override val catalogsRaw: String? = args.flagValue("--catalogs")

  override val catalogsUnlistedRaw: String? = args.flagValue("--catalogs-unlisted")

  /** The image passes `SERVE_CATALOG_REGISTRY` through to it. */
  override val catalogRegistryRaw: String? =
    args.flagValue("--catalog-registry")?.takeIf { it.isNotBlank() }

  override val sitesRaw: String? = args.flagValue("--sites")

  override val uiBuilderHost: String? =
    args.flagValue("--ui-builder-host")?.let { raw ->
      requireNotNull(ServeSites.normalizeHost(raw)) { "--ui-builder-host must be a hostname" }
    }

  override val uiBuilderHostRoot: Boolean =
    ("--ui-builder-host-root" in args).also { rooted ->
      require(!rooted || uiBuilderHost != null) {
        "--ui-builder-host-root needs --ui-builder-host: it roots the builder on that host"
      }
    }

  override val uiBuilderStartUrl: String? =
    args.flagValue("--ui-builder-start-url")?.also { raw ->
      val uri = java.net.URI(raw)
      require(uri.scheme == "https" && uri.host != null && uri.userInfo == null) {
        "--ui-builder-start-url must be an HTTPS URL without credentials"
      }
    }

  /** Raw `--catalogs-file` path; the server opens it. */
  override val catalogsFilePath: String? =
    args.flagValue("--catalogs-file")?.takeIf { it.isNotBlank() }

  /**
   * `--settings-file`, else `settings.json` beside `--catalogs-file`; `none` turns it off. The
   * image entrypoint applies the file before `serve` starts.
   */
  override val settingsFilePath: String? =
    when (val flag = args.flagValue("--settings-file")?.takeIf { it.isNotBlank() }) {
      "none" -> null
      null ->
        catalogsFilePath?.let(::File)?.absoluteFile?.parentFile?.resolve("settings.json")?.path
      else -> flag
    }

  /** Durable feed cache; defaults beside catalogs.json on deployed boxes, temp for local serve. */
  override val catalogFeedCacheDir: File by lazy {
    val preferred =
      args.flagValue("--catalog-feed-cache")?.takeIf { it.isNotBlank() }?.let(::File)
        ?: catalogsFilePath?.let(::File)?.absoluteFile?.parentFile?.resolve("catalog-feeds")
    if (
      preferred != null && (preferred.isDirectory || preferred.mkdirs()) && preferred.canWrite()
    ) {
      preferred
    } else {
      java.nio.file.Files.createTempDirectory("serve-catalog-feeds").toFile().also {
        it.deleteOnExit()
        if (preferred != null) {
          System.err.println(
            "serve: catalog feed cache ${preferred.absolutePath} is not writable; using ${it.absolutePath}"
          )
        }
      }
    }
  }

  /**
   * Covers `/admin/catalogs`, `/admin/onboard` and `/admin/trust`; env `SERVE_ADMIN_TOKEN` in the
   * image.
   */
  override val adminToken: String? = args.flagValue("--admin-token")?.takeIf { it.isNotBlank() }

  override val adminReadToken: String? =
    args.flagValue("--admin-read-token")?.takeIf { it.isNotBlank() }

  override val uiBuilderAdminActors: Set<String> =
    args
      .flagValue("--ui-builder-admin-actors")
      ?.split(",")
      ?.map(String::trim)
      ?.filter(String::isNotEmpty)
      ?.also { entries ->
        require(entries.all(UI_BUILDER_ADMIN_ACTOR::matches)) {
          "--ui-builder-admin-actors accepts GitHub actor ids such as github:octocat"
        }
        require(entries.map(String::lowercase).distinct().size == entries.size) {
          "--ui-builder-admin-actors contains a duplicate actor id"
        }
      }
      ?.toSet() ?: emptySet()

  override val uiBuilderDefaultVisibility: UiBuilderDefaultVisibility =
    UiBuilderDefaultVisibility.parse(args.flagValue("--ui-builder-default-visibility"))

  override val uiBuilderPublicOrigin: String? =
    args
      .flagValue("--ui-builder-public-origin")
      ?.takeIf { it.isNotBlank() }
      ?.let { origin ->
        requireNotNull(normalizeServerHomeUrl(origin)) {
          "--ui-builder-public-origin must be an absolute http(s) URL, got '$origin'"
        }
      }

  /** Optional durable aggregate counters. Null keeps local serve sessions in-memory only. */
  override val engagementFile: File? =
    args.flagValue("--engagement-file")?.takeIf { it.isNotBlank() }?.let(::File)

  override val githubAuthClientId: String? =
    args.flagValue("--github-auth-client-id")?.takeIf { it.isNotBlank() }

  override val githubAuthClientSecret: String? =
    args.flagValue("--github-auth-client-secret")?.takeIf { it.isNotBlank() }

  override val githubAuthCookieSecret: String? =
    args.flagValue("--github-auth-cookie-secret")?.takeIf { it.isNotBlank() }

  override val githubAuthRepo: String? =
    args.flagValue("--github-auth-repo")?.takeIf { it.isNotBlank() }

  override val githubAuthCallbackBaseUrl: String? =
    args.flagValue("--github-auth-callback-base-url")?.takeIf { it.isNotBlank() }

  override val githubAuthCookieDomain: String? =
    args.flagValue("--github-auth-cookie-domain")?.takeIf { it.isNotBlank() }

  override val githubAuthScope: String? =
    args.flagValue("--github-auth-scope")?.takeIf { it.isNotBlank() }

  override val githubAuthUsers: Set<String> =
    args
      .flagValue("--github-auth-users")
      ?.split(",")
      ?.map { it.trim().lowercase() }
      ?.filter { it.isNotEmpty() }
      ?.toSet() ?: emptySet()

  override val githubAuthOrgs: Set<String> =
    args
      .flagValue("--github-auth-orgs")
      ?.split(",")
      ?.map { it.trim().lowercase() }
      ?.filter { it.isNotEmpty() }
      ?.toSet() ?: emptySet()

  override val githubAuthGuests: Boolean = "--github-auth-guests" in args

  override val uiBuilderGuidelinesUsers: Set<String> =
    args.flagValue("--ui-builder-guidelines-users").loginSet()

  override val uiBuilderGuidelinesOrgs: Set<String> =
    args.flagValue("--ui-builder-guidelines-orgs").loginSet()

  override val uiBuilderGuidelinesModel: String? =
    args.flagValue("--ui-builder-guidelines-model")?.trim()?.takeIf { it.isNotEmpty() }

  override val uiBuilderGuidelinesPictureBudgetSeconds: Long =
    args.flagValue("--ui-builder-guidelines-picture-budget")?.trim()?.toLongOrNull()?.takeIf {
      it >= 0
    } ?: DEFAULT_GUIDELINES_PICTURE_BUDGET_SECONDS

  override val uiBuilderGuidelinesTriage: Boolean =
    args.flagValue("--ui-builder-guidelines-triage")?.trim()?.lowercase() != "off"

  override val githubAuthOpenUiBuilder: Boolean = "--github-auth-open-ui-builder" in args

  override val agentGrants: Boolean = "--agent-grants" in args

  /** Serve every catalog over aggregate Streamable HTTP MCP (`/mcp`). */
  override val catalogMcp: Boolean = "--catalog-mcp" in args

  /** Raw `--agent-grant-scopes`; the server parses it (an unknown scope throws there). */
  override val agentGrantScopesFlag: String? = args.flagValue("--agent-grant-scopes")
  /** Raw `--agent-grant-max-ttl` (e.g. `2h`/`90m`/`3600`); the server parses and clamps it. */
  override val agentGrantMaxTtlFlag: String? = args.flagValue("--agent-grant-max-ttl")
  /** Raw `--agent-grant-capabilities`; the server parses it (an unknown name throws there). */
  override val agentGrantCapabilitiesFlag: String? = args.flagValue("--agent-grant-capabilities")
  /** How many grants may be live at once (`--agent-grant-max-active`). */
  override val agentGrantMaxActive: Int =
    args.flagValue("--agent-grant-max-active")?.let {
      it.toIntOrNull()?.takeIf { n -> n > 0 }
        ?: throw IllegalArgumentException(
          "--agent-grant-max-active '$it' is not a positive whole number"
        )
    } ?: ServeDefaults.AGENT_GRANT_MAX_ACTIVE

  override val agentGrantRateLimit: Int =
    args.flagValue("--agent-grant-rate-limit")?.let {
      it.toIntOrNull()?.takeIf { n -> n >= 0 }
        ?: throw IllegalArgumentException(
          "--agent-grant-rate-limit '$it' is not a whole number of requests per minute (0 disables)"
        )
    } ?: ServeDefaults.DEFAULT_AGENT_GRANT_RATE_LIMIT

  override val acceptImages: Boolean = "--accept-images" in args

  /** How long an uploaded image's link lives (`--image-ttl <seconds>`); default 7 days. */
  override val imageTtlSeconds: Long =
    args.flagValue("--image-ttl")?.toLongOrNull()?.takeIf { it > 0 }
      ?: ServeDefaults.IMAGE_TTL_SECONDS

  override val imageUploadRepository: String? =
    args.flagValue("--image-upload-repo")?.takeIf { it.isNotBlank() } ?: githubAuthRepo

  /** Uploads per minute per GitHub account (`--image-rate-limit`); `0` disables the budget. */
  override val imageRateLimit: Int =
    args.flagValue("--image-rate-limit")?.toIntOrNull()?.takeIf { it >= 0 }
      ?: ServeDefaults.DEFAULT_IMAGE_RATE_LIMIT

  /** Raw `--image-upload-tokens`; the server parses it (an unknown kind throws there). */
  override val imageUploadTokensFlag: String? =
    args.flagValue("--image-upload-tokens")?.takeIf { it.isNotBlank() }

  override val optimizerCoordinationDirectory: File? by lazy {
    val explicit =
      args.flagValue("--theme-optimizer-coordination-dir")?.takeIf { it.isNotBlank() }?.let(::File)
    explicit ?: catalogsFilePath?.let(::File)?.absoluteFile?.parentFile?.resolve("optimizer-locks")
  }

  /** Raw `--catalog-cache-dir` (`none` disables persistence); the server resolves and opens it. */
  override val catalogCacheDirFlag: String? =
    args.flagValue("--catalog-cache-dir")?.takeIf { it.isNotBlank() }

  /** Raw `--catalog-cache-max-bytes`; the server applies its own default when unset. */
  override val catalogCacheMaxBytesFlag: Long? =
    args.flagValue("--catalog-cache-max-bytes")?.toLongOrNull()?.takeIf { it > 0 }
  /** Raw `--theme-cache-dir` (`none` disables); the server resolves, creates and opens it. */
  override val themeCacheDirFlag: String? =
    args.flagValue("--theme-cache-dir")?.takeIf { it.isNotBlank() }

  /** Raw `--theme-cache-max-bytes`; the server applies its own default when unset. */
  override val themeCacheMaxBytesFlag: Long? =
    args.flagValue("--theme-cache-max-bytes")?.toLongOrNull()?.takeIf { it > 0 }

  /** `--theme-cache-evict`: drop every cached generation once, at startup. */
  override val themeCacheEvictRequested: Boolean = "--theme-cache-evict" in args
  override val wasmDirs: Map<String, File> =
    args
      .flagValue("--wasm-dir")
      ?.split(",")
      ?.mapNotNull { entry ->
        val eq = entry.indexOf('=')
        if (eq <= 0) null else entry.substring(0, eq).trim() to File(entry.substring(eq + 1).trim())
      }
      ?.toMap() ?: emptyMap()

  /** Packaged catalog-scoped Wasm browser (`/wasm/<system>/`). */
  override val wasmUiDir: File? =
    args.flagValue("--wasm-ui-dir")?.takeIf { it.isNotBlank() }?.let(::File)

  /** Standalone Compose UI builder; deliberately separate from the catalog-scoped Wasm viewer. */
  override val uiBuilderDir: File? =
    args.flagValue("--ui-builder-dir")?.takeIf { it.isNotBlank() }?.let(::File)

  override val uiBuilderCatalogs: Set<String> =
    args
      .flagValue("--ui-builder-catalogs")
      ?.split(",")
      ?.map(String::trim)
      ?.filter(String::isNotEmpty)
      ?.also { entries ->
        require(entries.isNotEmpty()) { "--ui-builder-catalogs must name at least one catalog" }
        require(entries.all(UI_BUILDER_CATALOG_ID::matches)) {
          "--ui-builder-catalogs contains an invalid catalog id"
        }
        require(entries.distinct().size == entries.size) {
          "--ui-builder-catalogs contains a duplicate catalog id"
        }
      }
      ?.toSet() ?: setOf("m3-catalog")

  /**
   * `--ui-builder-published-catalogs <all|none|<id>[,<id>]>`; absent means `all`. `none` is spelled
   * out because an empty value is what an unset environment variable looks like after substitution.
   */
  override val uiBuilderPublishedCatalogs: Set<String>? =
    args.flagValue("--ui-builder-published-catalogs")?.trim()?.let { raw ->
      when (raw.lowercase()) {
        "all" -> null
        "none" -> emptySet()
        else -> {
          val entries = raw.split(",").map(String::trim).filter(String::isNotEmpty)
          require(entries.isNotEmpty()) {
            "--ui-builder-published-catalogs is empty; use `all` or `none` to say which you meant"
          }
          require(entries.all(UI_BUILDER_CATALOG_ID::matches)) {
            "--ui-builder-published-catalogs contains an invalid catalog id"
          }
          require(entries.distinct().size == entries.size) {
            "--ui-builder-published-catalogs contains a duplicate catalog id"
          }
          // Naming a catalog that is not served is a typo with a silent failure mode: the operator
          // meant to opt something in and nothing happens. Refused rather than ignored.
          val unknown = entries.filterNot(uiBuilderCatalogs::contains)
          require(unknown.isEmpty()) {
            "--ui-builder-published-catalogs names ${unknown.joinToString()}, which " +
              "--ui-builder-catalogs does not serve"
          }
          entries.toSet()
        }
      }
    }

  override val uiBuilderPublishedDefault: Set<String> =
    args
      .flagValue("--ui-builder-published-default")
      ?.split(",")
      ?.map(String::trim)
      ?.filter(String::isNotEmpty)
      ?.also { entries ->
        require(entries.all(UI_BUILDER_CATALOG_ID::matches)) {
          "--ui-builder-published-default contains an invalid catalog id"
        }
      }
      ?.toSet() ?: emptySet()

  override val uiBuilderUnavailableCatalogs: Set<String> =
    args
      .flagValue("--ui-builder-unavailable-catalogs")
      ?.split(",")
      ?.map(String::trim)
      ?.filter(String::isNotEmpty)
      ?.also { entries ->
        require(entries.all(UI_BUILDER_CATALOG_ID::matches)) {
          "--ui-builder-unavailable-catalogs contains an invalid catalog id"
        }
      }
      ?.toSet() ?: emptySet()

  /**
   * `--ui-builder-catalog-ownership <all|none|<id>[,<id>]>`; absent or empty is `none`. An owned
   * catalog must also be allowed to read its published file; the contradiction is refused at
   * startup.
   */
  override val uiBuilderCatalogOwnership: CatalogOwnership =
    args.flagValue("--ui-builder-catalog-ownership").let { raw ->
      val ownership = CatalogOwnership.parse(raw)
      val readsPublished = uiBuilderPublishedCatalogs ?: uiBuilderCatalogs
      val contradictions = uiBuilderCatalogs.filter { ownership.owns(it) && it !in readsPublished }
      require(contradictions.isEmpty()) {
        "--ui-builder-catalog-ownership owns ${contradictions.sorted().joinToString()}, which " +
          "--ui-builder-published-catalogs does not let read a published file"
      }
      if (raw != null && raw.trim().lowercase() !in setOf("", "all", "none")) {
        val unknown =
          raw
            .split(',')
            .map(String::trim)
            .filter(String::isNotEmpty)
            .filterNot(uiBuilderCatalogs::contains)
        require(unknown.isEmpty()) {
          "--ui-builder-catalog-ownership names ${unknown.joinToString()}, which " +
            "--ui-builder-catalogs does not serve"
        }
      }
      ownership
    }

  /** `<system>=<path>` pairs, since a host serving several catalogs has several records. */
  override val uiBuilderComponents: Map<String, File> =
    args
      .flagValue("--ui-builder-components")
      ?.split(",")
      ?.map(String::trim)
      ?.filter(String::isNotEmpty)
      ?.map { entry ->
        val system = entry.substringBefore('=', missingDelimiterValue = "").trim()
        val path = entry.substringAfter('=', missingDelimiterValue = "").trim()
        require(system.isNotEmpty() && path.isNotEmpty()) {
          "--ui-builder-components entries must be <catalog>=<components.json>, got `$entry`"
        }
        require(UI_BUILDER_CATALOG_ID.matches(system)) {
          "--ui-builder-components names an invalid catalog id `$system`"
        }
        system to File(path)
      }
      ?.also { pairs ->
        require(pairs.map { it.first }.distinct().size == pairs.size) {
          "--ui-builder-components names a catalog twice"
        }
      }
      ?.toMap() ?: emptyMap()

  /** `<catalog>=<dir>` pairs; a bare `<dir>` means the default catalog. */
  override val uiBuilderDesigns: Map<String, File> =
    args
      .flagValue("--ui-builder-designs")
      ?.split(",")
      ?.map(String::trim)
      ?.filter(String::isNotEmpty)
      ?.map { entry ->
        val hasSystem = entry.contains('=')
        val system =
          if (hasSystem) entry.substringBefore('=').trim() else LocalUiBuilder.DEFAULT_CATALOG
        val path = (if (hasSystem) entry.substringAfter('=') else entry).trim()
        require(path.isNotEmpty()) {
          "--ui-builder-designs entries must be [<catalog>=]<dir>, got `$entry`"
        }
        require(UI_BUILDER_CATALOG_ID.matches(system)) {
          "--ui-builder-designs names an invalid catalog id `$system`"
        }
        system to File(path)
      }
      ?.also { pairs ->
        require(pairs.map { it.first }.distinct().size == pairs.size) {
          "--ui-builder-designs names a catalog twice"
        }
      }
      ?.toMap() ?: emptyMap()

  /**
   * Validated at startup rather than on first delivery, so a bad hook surfaces while deploying. The
   * refusal never echoes the value, which is the credential.
   */
  override val uiBuilderCommentWebhook: String? =
    args
      .flagValue("--ui-builder-comment-webhook")
      ?.trim()
      ?.takeIf { it.isNotEmpty() }
      ?.also { url ->
        val rejection = CommentWebhookConfig(url).rejection()
        require(rejection == null) { "--ui-builder-comment-webhook $rejection" }
      }

  /** Refused rather than defaulted when unrecognised. */
  override val uiBuilderCommentWebhookFormat: String? =
    args
      .flagValue("--ui-builder-comment-webhook-format")
      ?.trim()
      ?.takeIf { it.isNotEmpty() }
      ?.also {
        require(CommentWebhookFormat.parse(it) != null) {
          "--ui-builder-comment-webhook-format must be one of " +
            CommentWebhookFormat.WIRE_NAMES.joinToString(", ") +
            "; got `$it`"
        }
      }

  /**
   * Refused rather than narrowed when unrecognised, so a typo can't silently drop an event kind.
   */
  override val uiBuilderWebhookEvents: String? =
    args
      .flagValue("--ui-builder-webhook-events")
      ?.trim()
      ?.takeIf { it.isNotEmpty() }
      ?.also {
        require(DesignActivityKind.parseEvents(it) != null) {
          "--ui-builder-webhook-events takes a comma-separated list of " +
            DesignActivityKind.WIRE_NAMES.joinToString(", ") +
            "; got `$it`"
        }
      }

  override val webPush: Boolean = "--no-web-push" !in args

  /** Refused unless it is a `mailto:` or an `https:` URL, which is what Apple will accept. */
  override val vapidSubject: String? =
    args
      .flagValue("--vapid-subject")
      ?.trim()
      ?.takeIf { it.isNotEmpty() }
      ?.also { subject ->
        ServeVapidKeys.subjectRejection(subject)?.let {
          throw IllegalArgumentException("--vapid-subject $it")
        }
      }

  override val vapidPublicKey: String? =
    args.flagValue("--vapid-public-key")?.trim()?.takeIf { it.isNotEmpty() }

  /** A credential, so a malformed one is refused without echoing it. */
  override val vapidPrivateKey: String? =
    args.flagValue("--vapid-private-key")?.trim()?.takeIf { it.isNotEmpty() }

  init {
    require((vapidPublicKey == null) == (vapidPrivateKey == null)) {
      "--vapid-public-key and --vapid-private-key are given together or not at all"
    }
    vapidPublicKey?.let { key ->
      require(ServeWebPush.fromBase64Url(key)?.let(ServeWebPush::publicKeyFromRaw) != null) {
        "--vapid-public-key must be a base64url uncompressed P-256 point"
      }
    }
    vapidPrivateKey?.let { key ->
      require(ServeWebPush.fromBase64Url(key)?.let(ServeWebPush::privateKeyFromRaw) != null) {
        "--vapid-private-key must be a base64url 32-byte P-256 key"
      }
    }
  }

  /**
   * `<builder>=<served>` pairs; both are checked against [UI_BUILDER_CATALOG_ID] since the served
   * id becomes a branch name and must not contain a path separator.
   */
  override val uiBuilderNativeCatalogs: Map<String, String> =
    args
      .flagValue("--ui-builder-native-catalog")
      ?.split(",")
      ?.map(String::trim)
      ?.filter(String::isNotEmpty)
      ?.map { entry ->
        val builder = entry.substringBefore('=', missingDelimiterValue = "").trim()
        val served = entry.substringAfter('=', missingDelimiterValue = "").trim()
        require(builder.isNotEmpty() && served.isNotEmpty()) {
          "--ui-builder-native-catalog entries must be <builder catalog>=<served catalog>, got `$entry`"
        }
        require(UI_BUILDER_CATALOG_ID.matches(builder)) {
          "--ui-builder-native-catalog names an invalid builder catalog id `$builder`"
        }
        require(UI_BUILDER_CATALOG_ID.matches(served)) {
          "--ui-builder-native-catalog names an invalid served catalog id `$served`"
        }
        builder to served
      }
      ?.also { pairs ->
        require(pairs.map { it.first }.distinct().size == pairs.size) {
          "--ui-builder-native-catalog names a catalog twice"
        }
      }
      ?.toMap() ?: emptyMap()

  override val uiBuilderWidgetPlayer: UiBuilderWidgetPlayer =
    args.flagValue("--ui-builder-widget-player")?.let { value ->
      requireNotNull(UiBuilderWidgetPlayer.fromFlag(value)) {
        "--ui-builder-widget-player must be one of " +
          "${UiBuilderWidgetPlayer.entries.joinToString(", ") { it.flagValue }}, got `$value`"
      }
    } ?: UiBuilderWidgetPlayer.DEFAULT

  /**
   * `<pack>=<platform>`: a component record can't say which platform it targets, so the word is
   * given here and checked at startup.
   */
  override val uiBuilderPacks: Map<String, String> =
    args
      .flagValue("--ui-builder-packs")
      ?.split(",")
      ?.map(String::trim)
      ?.filter(String::isNotEmpty)
      ?.map { entry ->
        val pack = entry.substringBefore('=', missingDelimiterValue = "").trim()
        val platform = entry.substringAfter('=', missingDelimiterValue = "").trim().lowercase()
        require(pack.isNotEmpty() && platform.isNotEmpty()) {
          "--ui-builder-packs entries must be <served catalog>=<platform>, got `$entry`"
        }
        require(UI_BUILDER_CATALOG_ID.matches(pack)) {
          "--ui-builder-packs names an invalid catalog id `$pack`"
        }
        require(platform in UI_BUILDER_PLATFORMS) {
          "--ui-builder-packs names an unknown platform `$platform` for `$pack`; " +
            "expected one of ${UI_BUILDER_PLATFORMS.joinToString(", ")}"
        }
        pack to platform
      }
      ?.also { pairs ->
        require(pairs.map { it.first }.distinct().size == pairs.size) {
          "--ui-builder-packs names a catalog twice"
        }
      }
      ?.toMap() ?: emptyMap()

  /** Exact, retained renderer bundles; unlike the builder shell these paths are immutable pins. */
  override val uiBuilderRuntimeDirs: Map<String, File> =
    args
      .flagValue("--ui-builder-runtime-dir")
      ?.split(",")
      ?.map { entry ->
        val eq = entry.indexOf('=')
        require(eq > 0 && eq < entry.lastIndex) {
          "--ui-builder-runtime-dir entries must be <runtimeId>=<dir>"
        }
        val runtimeId = entry.substring(0, eq).trim()
        val path = entry.substring(eq + 1).trim()
        require(runtimeId.isNotEmpty() && path.isNotEmpty()) {
          "--ui-builder-runtime-dir entries must be <runtimeId>=<dir>"
        }
        runtimeId to File(path)
      }
      ?.also { entries ->
        require(entries.map { it.first }.distinct().size == entries.size) {
          "--ui-builder-runtime-dir contains a duplicate runtime id"
        }
      }
      ?.toMap() ?: emptyMap()

  /** Raw durable-state directory; `none` disables the authoritative design API. */
  override val uiBuilderStateDirFlag: String? =
    args.flagValue("--ui-builder-state-dir")?.takeIf { it.isNotBlank() }

  override val uiBuilderMigrateState: Boolean = "--ui-builder-migrate-state" in args

  /** Experimental AndroidX-conformant Remote Compose CMP/Wasm player distribution. */
  override val rcPlayerWasmDir: File? =
    args
      .flagValue("--rc-player-wasm-dir")
      ?.takeIf { it.isNotBlank() }
      ?.let(::File)
      ?.let { dir ->
        if (File(dir, "index.html").isFile) dir
        else {
          System.err.println(
            "serve: --rc-player-wasm-dir ${dir.path} has no index.html — skipping (build it with " +
              ":rc-player-wasm:wasmPlayerDist)."
          )
          null
        }
      }

  override val rcDefaultPlayer: String? =
    ServeRcPlayerIds.parsePreferredPlayer(args.flagValue("--rc-default-player")) { value ->
      System.err.println(
        "serve: --rc-default-player '$value' names no Remote Compose player (expected one of " +
          "${ServeRcPlayerIds.UNIVERSE.joinToString(", ") { it.id }}) — keeping the built-in default."
      )
    }

  override val catalogRepo: String =
    args.flagValue("--catalog-repo")?.takeIf { it.isNotBlank() } ?: ServeDefaults.CATALOG_REPO

  override val catalogBranchPrefix: String =
    args.flagValue("--catalog-branch-prefix")?.takeIf { it.isNotBlank() }
      ?: ServeDefaults.CATALOG_BRANCH_PREFIX

  override val catalogMaxImages: Int =
    args.flagValue("--catalog-max-images")?.toIntOrNull()?.takeIf { it > 0 }
      ?: ServeDefaults.CATALOG_MAX_IMAGES

  /**
   * `serve` turns `@PreviewParameter` fan-out into row ids, so module selection may keep previews
   * whose rows might match; [modulesWithMatchingPreviews] later drops modules that didn't (see
   * #3786).
   */
  override val rowAwareSelection: Boolean
    get() = true

  private val BUILD_HOST_ENV: String = BuildHostDiscovery.ENV

  public fun printUsage() {
    println(
      """
      compose-preview serve [options]

      Start a local HTTP server that renders one module's @Preview functions on demand and serves
      them as PNGs with display overrides, so you can open (or share) a link to a specific preview.
      Read-only today; bound to loopback unless you opt into LAN exposure.

      Options:
        --module <path>   Module to serve, scoping local Gradle discovery + build to it. Implies
                          --discover. Omit it (and --discover) to run module-less — see below.
        --build-host <path|none>
                          The `compose-preview` binary to run local Gradle work through. Only a
                          server that also has --module / --discover needs one; a server hosting
                          fetched bundles and catalogs has nothing to ask Gradle. Defaults to
                          $BUILD_HOST_ENV in the environment, then `compose-preview` on PATH;
                          `none` disables the search. This binary speaks a protocol to that
                          process rather than linking a Gradle driver, which is why the server
                          needs no Gradle on its own classpath.
        --discover        Opt in to local Gradle discovery + build. By default serve NEVER runs
                          Gradle: it hosts only the fetched --bundle(s) / --catalogs / uploaded
                          bundles as a pure preview server, even inside a Gradle checkout (so a
                          stray `serve` at a repo root can't trigger a full — possibly hanging —
                          module build). Pass --discover to build every module's previews and host
                          one, or --module <path> to scope to a single module.
        --bundle <url|path>[, --bundle <name>=<url|path>, …]
                          Serve one or more fetched preview bundles directly — no --module, no
                          build. A URL is fetched at startup (operator-supplied, so no SSRF gate;
                          e.g. a raw.githubusercontent.com/<owner>/<repo>/<branch>/… link); a local
                          path is read. Each is served at /<name>/. A bundle that verifies Trusted
                          (Ed25519 signature, or fetched from a trusted branch in --trust-store) is
                          served LIVE from a render daemon when --allow-render-trusted is set
                          (desktop bundles); otherwise read-only as its baked PNGs. Repeatable.
        --id <exact>      Only serve this exact preview id.
        --filter <substr> Only serve previews whose id contains this substring.
        --preview <ref>   Only serve previews the reference selects: an id, a
                          `Class.function`, a bare function name, or a case-insensitive
                          substring of an id. Combined with --id / --filter it intersects:
                          every selector you pass has to match. Selecting needs a module
                          (--module / --discover) — a bundle-backed server has no manifest.
        --host <addr>     Bind address (default 127.0.0.1 — loopback only).
        --lan             Bind all interfaces (0.0.0.0) so other devices on your network can
                          connect. Prints the token-gated network URL, a QR code for a phone
                          (interactive terminals) and a security warning. A plain-http LAN
                          origin is not a secure context: installing the app, Web Share,
                          clipboard and offline need HTTPS, or `adb reverse tcp:<port>
                          tcp:<port>` to open it as http://localhost on an Android phone.
        --port <n>        Preferred port (default ${ServeDefaults.DEFAULT_PORT}; auto-picks the next free one).
        --token <value>   Use a fixed token instead of a freshly generated one (stable links).
        --public          Serve every route WITHOUT a token (open). For a deployed public preview
                          server — browsing published catalogs / uploaded bundles is the point. Safe
                          by construction (no server-side code exec; untrusted re-render refused;
                          uploads capped + SSRF-gated). Off by default.
        --component-browser
                          Use the streamlined Storybook-like catalog and component browser. Hides
                          administration, diagnostics, comparison and renderer tooling while
                          retaining visual navigation, variants, themes, authored controls,
                          sample source, locale/font scale and PNG/SVG downloads.
        --github-auth-client-id <id>
        --github-auth-client-secret <secret>
        --github-auth-cookie-secret <secret>
        --github-auth-repo <owner/repo>
                          Add GitHub OAuth on top of the browse gate for code-running surfaces:
                          live preview WebSockets and the playground. Live preview accepts any
                          signed-in GitHub user (unless --github-auth-users narrows sign-in);
                          playground additionally requires access to <owner/repo>. After sign-in
                          the server stores only a signed, expiring login cookie plus the repo
                          access verdict. The OAuth scope is read:user only (plus read:org with
                          --github-auth-orgs): sign-in never asks for repository access, so a
                          private <owner/repo> grants nobody access. All four flags are required
                          together.
        --github-auth-callback-base-url <url>
                          External origin for the OAuth callback, e.g. https://preview.example.com.
                          Omit for local use; reverse-proxied deploys should set it explicitly.
        --github-auth-cookie-domain <domain>
                          Scope the auth cookies to a parent domain, so one sign-in covers it and
                          every --sites hostname under it (preview.example.com also signs in
                          m3.preview.example.com). Required for sign-in to work on a top-level site
                          when --github-auth-callback-base-url is pinned: without it the cookies are
                          host-only, the state cookie never reaches the callback origin, and the
                          server withholds sign-in on site hosts. Omit on a single-hostname box.
                          Every host under <domain> is inside the session's reach, so name the
                          narrowest one that covers your sites.
        --github-auth-scope <scope>
                          Override the OAuth scope (default read:user). Only read-only identity
                          scopes are accepted: read:user, user:email, read:org. Repository scopes
                          such as repo or public_repo are refused at startup.
        --github-auth-users <login>[,<login>…]
                          Optional sign-in allowlist. Empty means any signed-in GitHub user may use
                          live sessions; playground still requires access to --github-auth-repo.
        --github-auth-orgs <org>[,<org>…]
                          Admit members of these GitHub organizations as if they were listed in
                          --github-auth-users (e.g. google). Adds read:org to the requested scope;
                          a private membership counts only where the org allows this OAuth app, a
                          public one always does.
        --github-auth-guests
                          With --github-auth-users or --github-auth-orgs set, let any other GitHub
                          account sign in as a guest: it sees the UI-builder designs shared with
                          it, read-only, and can request edit access through an agent grant. A
                          guest counts as signed out
                          everywhere else — no live sessions, playground, uploads or approvals.
        --github-auth-open-ui-builder
                          Let every signed-in GitHub member create, edit and export UI-builder
                          designs — and approve agent grants for those — without access to
                          --github-auth-repo. Playground and image uploads still require it. With
                          no --github-auth-users / --github-auth-orgs, that is any GitHub account.
        --ui-builder-guidelines-users <login>[,<login>…]
        --ui-builder-guidelines-orgs <org>[,<org>…]
                          Who may run ui_builder_check_design's `guidelines` check, which asks a
                          model (via OpenRouter) to judge a design against the Android design
                          guides. It spends this server's key, read from the
                          SERVE_UI_BUILDER_GUIDELINES_OPENROUTER_KEY environment variable, so it
                          is off unless that is set and one of these names somebody. Org
                          membership is read from GitHub; set
                          SERVE_UI_BUILDER_GUIDELINES_GITHUB_TOKEN to a token of an org member so
                          private memberships count. Either secret may instead be a file named by
                          the same variable with _FILE appended (a Docker secret), which keeps it
                          out of the process environment. Everyone else can still run the check in
                          the editor with their own OpenRouter key.
        --ui-builder-guidelines-model <id>
                          The OpenRouter model for that check (default
                          deepseek/deepseek-v4.1-flash).
        --ui-builder-guidelines-picture-budget <seconds>
                          How long a guidelines prompt waits for native renders it has not
                          cached yet (default 45). The rest keep drawing into the cache and are
                          attached the next time it is asked. 0 attaches only cached pictures.
        --ui-builder-guidelines-triage <on|off>
                          Before a guidelines check, ask Jev (typesafe/jev-1.13, a fraction of a
                          cent) which extra evidence would help — a dark render, a large-font
                          render, the accessibility tree — and gather only that (default on).
        --agent-grants    Let an agent ask for temporary access it can't otherwise get. The agent
                          POSTs /agent-access/request and prints a link plus a verification code;
                          you open the link, check the code matches, and approve. It then collects a
                          short-lived bearer token scoped to what you ticked. Approving requires a
                          signed-in GitHub user (with --github-auth-*) or the --token holder; a
                          --public server with neither is refused. Revoke any time from /status.
                          Off by default.
        --catalog-mcp     Expose all catalogs at /mcp using Streamable HTTP.
                          Requires --agent-grants. Published reads need preview scope; made-to-order
                          renders and data products need live scope. The same /mcp also carries
                          the ui_builder_* tools when the UI builder is on, and is what
                          `compose-preview-server design` talks to; off by default.
        --agent-grant-scopes <list>
                          Ceiling on what a grant may carry: preview, live, playground (cumulative;
                          default preview,live). 'playground' lets an approved agent compile and run
                          Kotlin on this host, and can only be approved by someone who has access to
                          --github-auth-repo themselves.
        --agent-grant-capabilities <list>
                          Extra permissions a grant may carry beside its scope, chosen separately by
                          the approver: 'images' (upload rendered previews through the image lane,
                          needs --accept-images), and 'ui-builder-read', 'ui-builder-write' and
                          'ui-builder-export' (the ui_builder_* MCP tools: read, edit, and
                          export or natively render a design). Off by default — a scope ceiling
                          says nothing about these.
        --agent-grant-max-ttl <duration>
                          Longest grant this server will mint, e.g. 90m / 2h / 3600 (default 8h,
                          hard ceiling 24h). The approver picks the actual lifetime on the page.
        --agent-grant-max-active <n>
                          Live grants allowed at once (default ${ServeDefaults.AGENT_GRANT_MAX_ACTIVE}); over it a new
                          approval is refused. On --public each signed-in approver may also hold at
                          most ${ServeDefaults.AGENT_GRANT_MAX_ACTIVE_PER_APPROVER} live grants.
        --agent-grant-rate-limit <n>
                          Requests per minute per address on the two ungated grant routes (default
                          ${ServeDefaults.DEFAULT_AGENT_GRANT_RATE_LIMIT}; 0 disables the budget entirely).
        --export <path>   Don't serve: render every preview once and write a portable bundle (a
                          self-contained web gallery + PNGs) to <path>. A '.zip' path writes a zip;
                          any other path writes a directory. The live server also offers this at
                          GET /bundle.zip.
        --inline          With --export, bake the PNGs into the gallery for a single self-contained
                          index.html (vs. separate previews/<id>.png files).
        --revisions       Project mode: also serve other git revisions of this repo on demand. A
                          request with ?session=<rev> checks that revision out into a worktree,
                          builds it, and serves it as its own session (suspended/resumed when idle).
        --history-branch <ref>
                          Project mode: the baseline delivery branch, as fetched in this checkout,
                          whose publishes the viewer's render-history strip is built from (default
                          ${ServeDefaults.HISTORY_BRANCH}; 'origin/<ref>' is tried too). Each
                          entry opens that version's render, served from the local object store.
                          A ref this clone can't resolve simply means no strip.
        --no-history      Don't compute the render-history strip from local git.
        --revisions-allow <ref>[,<ref>…]
                          Project mode SECURITY gate: only revisions reachable from these trusted
                          refs (e.g. main,release) are checked out and built — building runs that
                          revision's own Gradle (code execution). Omitted/empty = nothing builds
                          (fail closed), so arbitrary ?session=<rev> can't run code on the server.
                          Also gates --allow-render-trusted (the catalog source ref allowlist).
        --allow-render-trusted
                          SECURITY gate, opt-in (default off): serve a --catalogs catalog that is
                          Trusted AND declares a source as a live, re-renderable session built from
                          that source (full-fidelity overrides) instead of static baked PNGs. Runs
                          the source's Gradle = code execution, so it's gated by trust + the
                          --revisions-allow ref allowlist + a same-repo check. NEVER set this on a
                          box that can't build the catalog source (e.g. the desktop-only public
                          image can't build the Android catalogs) — leave it off and let the
                          in-browser Wasm tier carry CMP.
        --catalog-source-root <dir>
                          Git repo root the trusted-catalog builder (--allow-render-trusted)
                          worktrees + builds from, instead of the served --module's own project. Use
                          when the server is module-less but the catalog's source.repo is a separate
                          checkout (e.g. a prebuilt image that clones the CMP catalog repo for live
                          render). The trust + same-repo + ref-allowlist gates are unchanged.
        --live-seats <n>  Live (daemon-backed) stream PERMIT BUDGET. Each live session charges permits
                          by backend weight — a desktop CMP daemon costs 1, a heavier Robolectric
                          Android one costs 2 — so one heavy catalog can't hog a flat seat count and
                          starve the cheap CMP lanes. On a small box bound this (e.g. 2) when the live
                          tier is on (--allow-render-trusted); an over-budget stream is refused (WS
                          1013) rather than risking the OOM killer. Default 0 = unbounded. Snapshot +
                          Wasm sessions never take a permit.
        --spare-sandboxes <n>
                          Warm Android sandbox workers kept booted ahead of demand, server-wide, for
                          catalog daemons to adopt instead of booting their own: a daemon then
                          answers `initialize` in well under a second instead of 4-9 s, and hands
                          the workers back warm when it is reaped. Each spare is a resident
                          Robolectric JVM (~500 MB). Default 0 = none. Also read from the
                          composeai.serve.spareSandboxes system property.
        --exit-when-idle[=<seconds>]
                          Ephemeral mode: shut the server down once it's been idle (no open
                          connections and no requests) for <seconds> (default ${ServeDefaults.DEFAULT_IDLE_EXIT_SECONDS}s). Use a small
                          value to exit shortly after the last client disconnects.
        --bundles <dir>   Shared mode: also host pre-rendered portable bundles (no build/daemon). A
                          bundle dir, or a directory of them, is served read-only — each reachable at
                          ?session=<bundle-name>. Bundles are what --export / GET /bundle.zip produce.
        --accept-bundles  Shared mode (public): enable POST /bundles/<name> so clients can contribute
                          bundles at runtime — upload the zip as the body, or pass ?url=<link> to a
                          build-results artifact. Pair with --lan + a strong --token for a shared
                          instance. Uploads only by default; ?url= fetches need --accept-bundles-from.
        --accept-bundles-from <host>[,<host>…]
                          SSRF allowlist for POST /bundles?url=: hostnames the server may fetch a
                          bundle from. Omitted/empty = no URL fetch is allowed (fail closed), so a
                          client can't steer the server at an arbitrary or internal address.
        --accept-docs     Enable the DOCUMENT lane: GET /docs (drop a file) + POST /docs ingest one
                          known document — Remote Compose (.rc) or Lottie (.json) — and hand back an
                          expiring permalink (/d/<id>) that plays it in the viewer's browser. Data
                          only: the server stores bytes and never renders them, so hosting an
                          anonymous document runs nothing. Off by default.
        --doc-ttl <seconds>
                          How long a /d/<id> document link lives (default ${ServeDefaults.DOC_TTL_SECONDS}s). The document is
                          held in memory and dropped when it expires.
        --accept-docs-from <host>[,<host>…]
                          SSRF allowlist for POST /docs?url=: hostnames the server may fetch a
                          document from. Omitted/empty = uploads only (fail closed).
        --accept-images   Enable the IMAGE lane: POST /images ingests a rendered preview (PNG, GIF,
                          WebP, JPEG) and answers with /i/<id>.png — a URL an agent can embed in a
                          pull-request body. Uploading is NEVER anonymous: it needs
                          "Authorization: Bearer <github-token>" from an account with access to
                          --image-upload-repo, on a --public host too. Reading is open, because
                          GitHub's image proxy fetches an embedded image anonymously; the 128-bit id
                          is the access control. Off by default.
        --image-upload-repo <owner/repo>
                          Repository an uploader must have access to. Defaults to --github-auth-repo
                          when that is set; without either, --accept-images refuses to start.
        --image-upload-tokens <kind>[,<kind>…]
                          Which GitHub tokens may upload: app (a user token issued to this server's
                          --github-auth-client-id; always accepted when that is set), personal
                          (personal access tokens), other-apps (user tokens issued to any other
                          OAuth or GitHub App, e.g. `gh auth token`), installation (GitHub App
                          installation tokens with write, e.g. a GitHub Actions GITHUB_TOKEN — any
                          app installed on the repo with write passes). Default:
                          personal,installation with GitHub OAuth configured, otherwise
                          personal,other-apps,installation.
        --image-ttl <seconds>
                          How long a /i/<id> image link lives (default ${ServeDefaults.IMAGE_TTL_SECONDS}s = 7 days). Held in
                          memory and dropped when it expires; ${ServeDefaults.IMAGE_MAX_IMAGES} images / ${ServeDefaults.IMAGE_MAX_TOTAL_BYTES / (1024 * 1024)}MB max, the
                          oldest evicted first.
        --image-rate-limit <n>
                          Uploads per minute per GitHub account (default ${ServeDefaults.DEFAULT_IMAGE_RATE_LIMIT}). 0 disables the
                          budget.
        --trust-store <file>
                          Producer-trust allowlist (JSON: signing keys / branches / CI identities).
                          Uploaded bundles are verified against it and the verdict (signature /
                          branch / provenance / unverified) is returned + badged. Omitted = trust
                          nothing (every upload unverified); the data tiers serve either way.
        --catalogs <system>[@<owner>/<repo>][,…]
                          Serve our published design systems from their design-artifacts/<system>
                          branches (e.g. compose-m3,wear-m3): each is fetched (catalog.json + images)
                          and served read-only at /<system>/ (also ?session=<system>), listed on the
                          front-page nav, trusted-by-origin when the branch is in --trust-store. Add
                          @<owner>/<repo> to fetch a system from a different repo than --catalog-repo
                          (e.g. meshcore-mobile@yschimke/meshcore-mobile).
        --catalogs-unlisted <system>[@<owner>/<repo>][,…]
                          Like --catalogs, but served WITHOUT a front-page nav link — reachable at
                          /<system>/ and ?session=<system> but hidden from the landing "Design
                          systems" row. For app design systems we publish but keep off the front door.
        --catalogs-file <path>
                          The catalog set as CONFIG, not flags: a catalogs.json listing every
                          catalog to serve ({"groups":[…],"catalogs":[{"system","repo","listed",
                          "group"}]}). Meant to live outside the container image (a mounted volume)
                          so publishing a catalog is a config edit, not an image rebuild. The
                          "group" names a front-page section from "groups" — a claim honoured only
                          when the catalog's bytes really came from the entry's repo (or one of its
                          "attributionRepos"), so an id like compose-m3 can't buy a section. Entries
                          here come first; --catalogs / --catalogs-unlisted add to them. May also
                          carry "sites" (see --sites).
        --ui-builder-host <hostname>
                          Optional builder hostname; / redirects to /ui-builder/ on that host.
                          Include it in the TLS proxy and configure a shared auth cookie domain.
        --ui-builder-start-url <https-url>
                          Optional /start guide redirect on the builder hostname.
        --sites <host>=<system>[,…]
                          Top-level sites: serve an already-published catalog on a hostname of its
                          own, where it looks like the whole server (e.g.
                          m3.preview.coo.ee=m3-catalog serves what /m3-catalog/ serves). On that
                          host the catalog's landing is /, every link stays inside the domain, the
                          front-door index and the "all design systems" back link are gone, /status
                          + /sitemap.xml cover that app only, and /<other-system>/ 404s while
                          /<this-system>/… 301s to the rooted URL. Same sessions, same baked pixels,
                          same daemons — a site is a view of the box, not a second one. The system
                          must be one this server already serves; catalogs.json's "sites" says the
                          same thing as config, and POST /admin/sites says it on a running server
                          (see --admin-token), writing it back to --catalogs-file. Two things this
                          does NOT do, because they are outside the app: DNS for the name must point
                          at this box, and the reverse proxy must match the name and hold a
                          certificate for it.
        --admin-token <value>
                          Enable the runtime admin API and gate it with this secret. Two surfaces:
                          the catalog set — GET /admin/catalogs, POST /admin/catalogs (a
                          catalogs.json entry as the body), DELETE /admin/catalogs/<system> — and
                          the producer-trust store — GET /admin/trust, POST /admin/trust
                          ({"kind":"branch"|"key"|"oidc",…}), DELETE /admin/trust?kind=&repo=…
                          (selectors ride the query string so an owner/repo needn't be escaped).
                          the front-page sections — GET /admin/groups, POST /admin/groups
                          ({"id","heading","noun"}), DELETE /admin/groups/<id>. Defining a section
                          also regroups catalogs already registered, and re-POSTing a published
                          catalog converges its listing (group / listed) in place. And the
                          top-level sites — GET /admin/sites, POST /admin/sites
                          ({"host","system"}), DELETE /admin/sites/<host> — so a hostname can be
                          published on a running box; re-POSTing one whose system changed re-points
                          it in place. The edge still has to route the name and hold a certificate
                          for it (see --sites).
                          And the UI builder's catalog settings — GET, PUT and DELETE
                          /admin/ui-builder/config — catalogs.json's `uiBuilder` block, which
                          overrides the --ui-builder-catalogs, -published-catalogs,
                          -catalog-ownership, -native-catalog, -packs and -widget-player flags
                          per catalog from the next start.
                          And onboarding — POST /admin/onboard ({"url","group","listed"}) takes a
                          GitHub project URL in any spelling, discovers the delivery branches that
                          repository already publishes, and registers each one exactly as POST
                          /admin/catalogs would, so a project is onboarded without spelling out its
                          catalog ids. Per-catalog outcomes come back in the body; re-posting the
                          same URL converges rather than erroring. A repository that has never run
                          `compose-preview publish` has nothing to serve yet and answers 404.
                          Onboarding a project that has NEVER published a catalog starts with POST
                          /admin/onboard/scan ({"url","ref"}), which shallow-clones the repository
                          and REPORTS which Gradle modules hold @Preview functions and which the
                          preview plugin can be injected into — it reads the checkout and executes
                          nothing. Building such a project is not this server's job: that runs on a
                          GitHub Actions runner in the import staging repository, which publishes an
                          ordinary design-artifacts/<system> branch this box then onboards above.
                          Mutations are applied live AND written back to --catalogs-file /
                          --trust-store, so they survive a restart. Separate from --token on purpose
                          (a --public box hands that one to every visitor); omitted = the admin
                          routes don't exist at all. NB with --allow-render-trusted this token can
                          grant server-side execution, since trusting a branch makes that
                          producer's Compose eligible for re-render here.
        --admin-read-token <value>
                          Enable a read-only diagnostic credential for GET /admin/ui-builder and
                          GET /admin/ui-builder/designs. It can see design summaries and unusable
                          reasons, but cannot download design documents, repair or delete designs,
                          or reach any other admin route. Separate from --admin-token so diagnosis
                          need not receive the code-execution-capable operator credential.
        --ui-builder-default-visibility private|public
                          Whether a new UI-builder design starts public (anyone with the link may
                          view it, read-only) or private to its owner and whoever they share it
                          with. Owners change it per design from its share page. Default private.
        --ui-builder-public-origin <url>
                          The stable public origin designs on this server are canonical at, e.g.
                          https://preview.example.com; recorded as each new design's home.
                          Defaults to --github-auth-callback-base-url. With neither, new designs
                          are left unhomed.
        --ui-builder-admin-actors <actor>[,…]
                          GitHub identities allowed to administer every shared UI-builder design,
                          for example github:octocat. A configured actor administers through its
                          own signed-in browser session; grants it approves act with ordinary
                          design access, not administration. This does not grant access to
                          catalog, trust, site, onboarding or library administration.
        --onboard-cache <dir>
                          Where POST /admin/onboard/scan checks repositories out to read them (one
                          directory per repo, reused). Nothing in them is executed. Default: a
                          temporary directory, forgotten on restart.
        --engagement-file <path>
                          Persist privacy-minimal aggregate catalog/app and per-preview view counts
                          as JSON. No IPs, cookies, user agents, or referrers are stored. Omitted =
                          counters last only for this server process.
        --catalog-repo <owner/repo>
                          Default repo the catalogs are fetched from (default
                          yschimke/compose-ai-tools); per-entry @<owner>/<repo> overrides it.
        --catalog-branch-prefix <prefix>
                          Branch prefix for --catalogs (default design-artifacts/).
        --catalog-max-images <count>
                          Maximum baked previews loaded from one published catalog (default
                          ${ServeDefaults.CATALOG_MAX_IMAGES}). Images are fetched lazily; this
                          bounds registered preview metadata and routes, not eager image downloads.
        --catalog-refresh-interval <seconds>
                          Keep a running server fresh: re-check each --catalogs branch's head every
                          <seconds> and re-fetch (catalog.json + renders + web/wasm/ + liveBundle) in
                          place when it moved — so a regenerated branch is picked up with no restart
                          (default ${ServeDefaults.DEFAULT_CATALOG_REFRESH_SECONDS}s; 0 disables, serving the boot snapshot only). Uses
                          `git ls-remote` (no API rate limit), and skips a branch it can't resolve.
        --catalog-feed-idle-timeout <seconds>
                          Publish /<catalog>/feed.xml. A request renews that feed's background
                          history-computation lease; after this many seconds without another request
                          it stops fetching while keeping the last generated feed and shallow Git
                          cache (default ${ServeDefaults.DEFAULT_CATALOG_FEED_IDLE_SECONDS}s; 0 disables).
        --settings-file <path>|none
                          The deployment's settings.json (non-secret SERVE_* settings, see
                          deploy/image/SETTINGS.md), kept by PUT /admin/settings. Defaults to
                          settings.json beside --catalogs-file. The image entrypoint applies it.
        --catalog-feed-cache <dir>
                          Durable shallow-Git + generated-XML cache for catalog feeds. Defaults to a
                          catalog-feeds directory beside --catalogs-file, or a temp dir in local mode.
        --catalog-cache-dir <dir>|none
                          Durable, content-addressed store for the heavy bytes a catalog fetches —
                          the executable liveBundle, its per-preview splits and the externalised
                          resource pool — so a reload or a restart re-reads them instead of pulling
                          ~100 MB per live catalog again. Unset (and `none`) keeps the temp-dir pool
                          this always had, which dies with the process; there is no derived default,
                          because the obvious one is the config volume. Only bytes addressed by a
                          commit-pinned URL are cached, so a load that could not resolve its
                          delivery commit populates nothing.
        --catalog-cache-max-bytes <n>
                          Ceiling for that store (default
                          ${ServeDefaults.CATALOG_BLOB_POOL_MAX_BYTES / (1024L * 1024 * 1024)} GB).
                          Reclaimed oldest-first after the startup pass and after each later
                          catalog publication; blobs newer than an hour are spared so the replicas
                          that overlap during a rolling update cannot evict each other's.
        --theme-cache-dir <dir>|none
                          Durable store for warmed theme renders, so background warming survives a
                          restart and a catalog refresh. `none` disables it. Defaults to a
                          theme-cache directory beside --catalogs-file — note that on the prebuilt
                          image that is the persistent config volume, so pass `none` to keep it off;
                          with no durable location the cache stays in memory only
                          (there is deliberately no temp-dir fallback — it would be thrown away with
                          the process). Entries are keyed by a fingerprint of the render classpath,
                          daemon variant, tool version and render config, so a new catalog revision
                          or server build never reads the previous one's pixels.
        --theme-cache-max-bytes <n>
                          Ceiling for that store across every catalog (default
                          ${ServeDefaults.THEME_CACHE_MAX_BYTES / (1024L * 1024 * 1024)} GB). Superseded
                          generations are reclaimed; generations still in use are never evicted, so
                          exceeding this is reported rather than acted on.
        --theme-cache-evict
                          Delete every persisted theme-render generation at startup, before any is
                          opened. For when the pixels on the volume are known to be wrong — a base
                          image that changed the installed fonts, say, which no fingerprint sees.
                          Ordinary renderer changes need no eviction: entries written by another
                          build are withheld until a re-rendered sample agrees with them, and the
                          whole generation is discarded when it does not.
        --background-renders <n>
                          Background (theme-optimizer) renders admitted at once, server-wide.
                          Defaults to a value derived from --live-seats, which clamps at
                          ${ServeDefaults.MAX_DERIVED_CONCURRENT_RENDERS} — a ceiling reached
                          at 8 seats, so a bigger box stops widening this lane while everything else
                          scales. Name it explicitly to go past that; the seat budget still bounds
                          how many daemons the renders can occupy.
        --theme-optimizer-coordination-dir <dir>
                          Shared directory used to coordinate background optimizer lanes across
                          server replicas. Defaults to optimizer-locks beside --catalogs-file; set
                          this explicitly when replicas do not share that directory.
        --wasm-dir <system>=<dir>[,<system>=<dir>…]
                          In-browser CMP tier: map a design system to its assembled Kotlin/Wasm
                          catalog app (./gradlew :samples:cmp-wasm-catalog:wasmCatalogDist →
                          build/wasmDist). That session's viewer then offers a "Run in browser
                          (Wasm)" toggle that mounts the M3 components client-side (no server
                          round-trip), served read-only at /wasm/<system>/. Missing dirs are skipped.
        --wasm-ui-dir <dir>
                          Catalog browser fallback served at /wasm/<system>/ for every known
                          catalog that does not publish its own Wasm app. The catalog id comes from
                          the path; /wasm/preview-ui/ redirects to this catalog-scoped form.
        --ui-builder-dir <dir>
                          Standalone Compose UI builder distribution served at /ui-builder/.
                          This is additive and does not replace or alter /wasm/<system>/.
        --ui-builder-catalogs <system>[,<system>…]
                          Explicit catalog allowlist for catalog-scoped builder instances at
                          /ui-builder/<system>/. Defaults to m3-catalog. Serving a catalog does not
                          enable its builder automatically.
        --ui-builder-components <catalog>=<components.json>[,<catalog>=<file>…]
                          Discovered component records the Compose export generates from — the
                          components.json a preview bundle carries, one per catalog. A catalog with
                          no record refuses a Compose export naming it, and reports
                          composeCode = false so the export is not offered. remote-m3 and wear-m3
                          need no record: their designs are written by their own emitters, and both
                          export without one.
        --ui-builder-packs <catalog>=<platform>[,<catalog>=<platform>…]
                          Served catalogs offered as component packs inside every builder catalog
                          of the named platform (mobile, wear or remote-compose): confetti-mobile
                          =mobile puts Confetti's own composables on a shelf of their own in every
                          Material 3 design, drawn as placeholders on the canvas and rendered
                          natively against the confetti-mobile bundle. A pack's components come
                          from the served catalog's own component record, read from its delivery
                          branch; a --ui-builder-components entry overrides it. Authors switch a
                          pack on from the editor's settings; admitting one here only makes it
                          available.
        --ui-builder-widget-player cmp-android|androidx
                          Which player draws a Wear widget design in the native preview lane and
                          its thumbnails. Both record with the AndroidX writer on the Android
                          daemon. Defaults to cmp-android, the Compose Multiplatform player,
                          wherever the widget's bundle carries rc-player-compose (else androidx);
                          androidx keeps upstream's WearWidgetPreview, whose player drops a
                          RemoteButton's container (yschimke/compose-ui-builder#511).
        --ui-builder-runtime-dir <runtimeId>=<dir>[,<runtimeId>=<dir>…]
                          Retained immutable native renderer bundles. Each directory must contain
                          runtime-manifest.json. Runtime ids are exact pins; there is no latest
                          fallback.
        --ui-builder-state-dir <dir>|none
                          Durable authoritative design store used by the UI-builder HTTP and
                          WebSocket API. Defaults to ui-builder-state beside --catalogs-file, or
                          ~/.compose-preview/ui-builder-state for a local standalone builder.
                          `none` serves static builder assets without the editable design API.
        --ui-builder-migrate-state
                          Explicitly migrate a validated v1 design store to v2 before serving.
                          Retains the exact v1 generation for rollback; never runs implicitly.
        --ui-builder-comment-webhook <url>
                          Post UI-builder comment activity to one URL: a new thread, a reply, a
                          resolve and a reopen, each carrying the thread permalink. Reactions and
                          acknowledgements are deliberately silent. Delivery is fire-and-forget, so
                          a slow endpoint never delays a comment. https only, except
                          http://127.0.0.1 and http://localhost; the URL is treated as a credential
                          and is never logged.
        --ui-builder-comment-webhook-format plain|slack|teams|google-chat
                          The body shape --ui-builder-comment-webhook posts. Defaults to plain,
                          this server's own event JSON, for a bespoke receiver or a relay. The
                          other three are the incoming-webhook bodies those chat platforms accept.
        --no-web-push
                          Do not offer Web Push notifications. They are otherwise offered on a host
                          with GitHub sign-in and a UI builder; nothing is sent until a signed-in
                          person turns them on in Settings. See docs/serve/NOTIFICATIONS.md.
        --vapid-subject mailto:<address>|https://<url>
                          Who push services may contact about this deployment. Defaults to the
                          https origin of --github-auth-callback-base-url, else the project page.
        --vapid-public-key <base64url> --vapid-private-key <base64url>
                          Pin the VAPID key pair (both or neither). Unset, one is generated on first
                          start and kept in the UI-builder state directory. The private key is a
                          credential.
        --ui-builder-webhook-events comments|fork|decision|implementation|all[,...]
                          What --ui-builder-comment-webhook posts. Defaults to comments. fork is a
                          proposed alternative, decision an approve or reject, implementation the
                          implementing pull request opening, merging or (mis)matching its design.
                          Same rate limit and private-design rule as comments.
        --open-browser    Open a browser on the served URL at startup. Used by the `browse` and
                          `ui` launchers; plain `serve` prints the link instead.
        --open-path <path>
                          Which page --open-browser opens. Absolute path, no query (the session
                          token is appended as one). Defaults to the landing page; `ui` points it
                          at /ui-builder/<catalog>/.
        --rc-player-wasm-dir <dir>
                          Experimental non-JVM Remote Compose player produced by
                          :rc-player-wasm:wasmPlayerDist. Serves it at /rc-player-wasm/ and enables
                          the "CMP Wasm" RC backend for previews carrying a captured .rc document.
        --rc-default-player <id>
                          The Remote Compose player the viewer opens on (e.g. cmp-android), used
                          only where a preview enables it; elsewhere the built-in order applies:
                          androidx-embedded, then androidx-view, then camaelon-js.

      The shareable link carries an unguessable token; requests without it get 404.
      """
        .trimIndent()
    )
  }

  internal companion object {
    val UI_BUILDER_CATALOG_ID = Regex("[A-Za-z0-9][A-Za-z0-9._-]*")
    val UI_BUILDER_ADMIN_ACTOR = Regex("github:[A-Za-z0-9](?:[A-Za-z0-9-]{0,37}[A-Za-z0-9])?")

    /**
     * The platform words `--ui-builder-packs` accepts, as `UiBuilderCatalogPlatform` spells them.
     */
    val UI_BUILDER_PLATFORMS =
      ee.schimke.composeai.uibuilder.export.UiBuilderCatalogPlatform.entries
        .map { it.wireValue }
        .toSet()
  }

  override fun previewIdMatchesRequest(
    id: String,
    exactId: String?,
    filter: String?,
    previewRef: String?,
    className: String?,
    functionName: String?,
  ): Boolean = previewMatcher(id, exactId, filter, previewRef, className, functionName)
}

/**
 * `--flag value` or `--flag=value`. Internal because [ServerCommands] and [LocalUiBuilder] parse
 * the same argv before this object exists.
 */
/** System-property spelling of `--spare-sandboxes`; see [ServeCommandOptions.spareSandboxes]. */
internal const val SPARE_SANDBOXES_PROP: String = "composeai.serve.spareSandboxes"

internal fun List<String>.flagValue(flag: String): String? {
  firstOrNull { it.startsWith("$flag=") }
    ?.let {
      return it.substringAfter("=")
    }
  val index = indexOf(flag)
  return if (index >= 0 && index + 1 < size) this[index + 1] else null
}

/** Whether [flag] was passed at all, in either spelling. */
internal fun List<String>.hasFlag(flag: String): Boolean = any {
  it == flag || it.startsWith("$flag=")
}

private fun List<String>.flagValuesAll(flag: String): List<String> = buildList {
  var index = 0
  while (index < this@flagValuesAll.size) {
    val argument = this@flagValuesAll[index]
    when {
      argument == flag && index + 1 < this@flagValuesAll.size -> {
        add(this@flagValuesAll[index + 1])
        index += 2
      }
      argument.startsWith("$flag=") -> {
        add(argument.substringAfter("="))
        index++
      }
      else -> index++
    }
  }
}

/** A comma-separated list of GitHub logins or orgs, lowercased, blanks dropped. */
private fun String?.loginSet(): Set<String> =
  this?.split(",")?.map { it.trim().lowercase() }?.filter { it.isNotEmpty() }?.toSet() ?: emptySet()
