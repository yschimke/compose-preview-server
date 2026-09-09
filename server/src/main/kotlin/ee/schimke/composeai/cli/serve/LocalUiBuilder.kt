package ee.schimke.composeai.cli.serve

import java.io.File

/**
 * `compose-preview-server ui` — the UI builder, pointed at the project you are sitting in.
 *
 * The builder has always been reachable from `serve`, as `/ui-builder/`, `--ui-builder-catalogs`,
 * `--ui-builder-components <catalog>=<components.json>` and friends. What it was not was
 * *launchable*: aiming it at a local project meant knowing which of those flags to combine and
 * producing the component record yourself
 * ([#301](https://github.com/yschimke/compose-preview-server/issues/301)).
 *
 * So this is a combination of flags that already exist, plus one fact the flags cannot know until
 * the build has run: **where the local module's `components.json` is**. Nothing here computes a
 * component record. The Gradle plugin's discovery task already writes one beside `previews.json`
 * (`build/compose-previews/components.json`) from the same scan that produced the manifest, so the
 * record the builder exports against is the module's real, derived record — not a fixture, and not
 * a second projection that could disagree with the one bundles carry.
 *
 * ## Why the record is copied rather than pointed at
 *
 * `--ui-builder-components` is read when the server's options are constructed, which is before any
 * Gradle work has happened and therefore before the module — and so its project directory — is
 * known. The lane names a path up front and fills it in once discovery reports the module
 * ([publishRecord]). [ComponentRecordSource] re-reads by `(length, lastModified)` on every export,
 * so a file that appears after startup is picked up with no restart and no special case.
 *
 * ## The catalog stays a packaged one
 *
 * `--ui-builder-catalogs` names catalogs the builder has a packaged adapter for (`m3-catalog`,
 * `remote-m3`); a project is not one of them and inventing an id here would only produce "catalog
 * <id> has no packaged adapter" at startup. The palette is the design system; the local project
 * enters through the record the export generates call sites from. That is exactly the seam
 * `--ui-builder-components` was built for — this command just fills it in for you.
 */
internal object LocalUiBuilder {

  /** The builder catalog served when the caller names none. Matches `ServeCommandOptions`. */
  const val DEFAULT_CATALOG: String = "m3-catalog"

  /** The packaged builder distribution's directory name, beside the binary's `lib/`. */
  private const val BUILDER_ASSETS = "ui-builder"

  /**
   * The packaged component record's directory, a sibling of [BUILDER_ASSETS] in the distribution.
   *
   * Written by `server/build.gradle.kts`, whose own comment says what it is for: without the record
   * "a packaged host advertises no Compose export at all".
   */
  private const val BUILDER_COMPONENTS = "ui-builder-components"

  /** The packaged record's file name, as the distribution ships it. */
  private const val PACKAGED_RECORD = "m3-catalog-components-v1.json"

  /**
   * The options that only mean something with a Gradle project, and so contradict [NO_PROJECT].
   *
   * Refused rather than dropped. Forwarding them sent `ServeRunner` down its Gradle path — which
   * with no build host exits on a render-build failure, and with one builds a project the caller
   * asked not to have — and dropping them silently would make a typed flag vanish. The help text
   * said these "stop applying"; a usage error is the only reading of that which does not lie.
   */
  private val PROJECT_FLAGS: List<String> =
    listOf("--module", "--discover", "--export", "--revisions", "--variant")

  /** Where the Gradle plugin's discovery task writes a module's preview outputs. */
  private const val MODULE_PREVIEW_OUTPUT = "build/compose-previews"

  private const val COMPONENT_RECORD = "components.json"

  /** This lane's own flag: print the URL instead of opening a browser, as `browse` has. */
  const val NO_OPEN: String = "--no-open"

  /**
   * This lane's other flag: the builder against the packaged design systems, with no project.
   *
   * `ui` exists to point the builder at *your* module, and everything that makes it worth using —
   * the discovery, the component record, the export that calls your own composables — needs a build
   * host and a Gradle project. But the other reason to open the builder is to draw against a design
   * system that is already packaged in it, which needs none of that, and until now the only way to
   * do it was to work out the `serve` flags by hand.
   *
   * A flag rather than a silent degrade. The two modes differ in what the export can do, and a `ui`
   * that quietly became the smaller one whenever a build host happened to be missing would look
   * like it had worked — which is exactly the failure the hard exit in `StandaloneServerMain` was
   * added to avoid.
   */
  const val NO_PROJECT: String = "--no-project"

  /**
   * The catalogs offered when a projectless builder names none.
   *
   * Both are packaged adapters ([ProductionUiBuilderRuntime]), which is the whole reason this mode
   * needs nothing fetched: `--catalogs` serves the browsable preview *sites*, a different feature,
   * and a builder catalog with no packaged adapter is refused at startup rather than fetched.
   */
  val DEFAULT_CATALOGS: List<String> = listOf(DEFAULT_CATALOG, "remote-m3")

  /** Whether this invocation is the projectless one. */
  fun isProjectless(args: List<String>): Boolean = NO_PROJECT in args

  /** The catalog the builder is opened at — the caller's first, else the packaged default. */
  fun catalog(args: List<String>): String =
    args.flagValue("--ui-builder-catalogs")?.split(",")?.firstOrNull()?.trim()?.takeIf {
      it.isNotEmpty()
    } ?: DEFAULT_CATALOG

  /**
   * The `serve` argv this command implies.
   *
   * Every addition is skipped when the caller made the choice themselves, so `ui` narrows nothing:
   * it is the set of decisions someone launching the builder for their own project should not have
   * to make, and no more.
   */
  fun serveArgs(
    args: List<String>,
    catalog: String,
    componentRecord: File,
    builderDir: File?,
  ): List<String> = buildList {
    val projectless = isProjectless(args)
    // `--no-open` and `--no-project` are this lane's flags, not server flags; forwarding one would
    // leave an argument the server does not know in its argv.
    addAll(args.filterNot { it == NO_OPEN || it == NO_PROJECT })
    // The builder is being pointed at a project, so the previews have to be discovered and built.
    // `--module` implies it already; `--discover` alone means every module in the build.
    if (!projectless && !args.hasFlag("--module") && !args.hasFlag("--discover")) add("--discover")
    if (builderDir != null && !args.hasFlag("--ui-builder-dir")) {
      add("--ui-builder-dir")
      add(builderDir.path)
    }
    // With a project, the record is the one discovery is about to write — named up front and
    // filled in by [publishRecord], because the module is not known until Gradle has run.
    if (!projectless && !args.hasFlag("--ui-builder-components")) {
      add("--ui-builder-components")
      add("$catalog=${componentRecord.path}")
    }
    // Without one, the DISTRIBUTION's record. `--no-project` is the documented stock-distribution
    // command and the distribution ships `ui-builder-components/m3-catalog-components-v1.json`, so
    // omitting it left `uiBuilderComponents` empty, `composeExportFor` false for the default
    // `m3-catalog`, and its Compose export action withdrawn — the packaged record sitting unread
    // beside the binary. Only `remote-m3` worked, and only because its exporter needs no record.
    //
    // Pinned to [DEFAULT_CATALOG] rather than to `catalog`: it is M3's record, and naming it for a
    // catalog it does not describe would make the export generate call sites for the wrong system.
    if (projectless && !args.hasFlag("--ui-builder-components")) {
      packagedComponentRecord()?.let {
        add("--ui-builder-components")
        add("$DEFAULT_CATALOG=${it.path}")
      }
    }
    // Offer every packaged design system rather than only the one being opened: the reason to run
    // this mode is to draw against them, and picking one at launch would mean relaunching to try
    // the other.
    if (projectless && !args.hasFlag("--ui-builder-catalogs")) {
      add("--ui-builder-catalogs")
      add(DEFAULT_CATALOGS.joinToString(","))
    }
    // `--open-path` is set even under `--no-open`, because it is the page this command is ABOUT,
    // not only the page a browser is pointed at: `ServeRunner` prints it in the banner and names it
    // when a desktop browse fails. Without it the only URL a headless caller saw was the generic
    // root landing page, which on a projectless server has no session and no served catalog behind
    // it — a 404 offered as the way in.
    if (!args.hasFlag("--open-path")) {
      add("--open-path")
      add("/ui-builder/$catalog/")
    }
    if (NO_OPEN !in args && !args.hasFlag("--open-browser")) add("--open-browser")
  }

  /**
   * The project options [NO_PROJECT] contradicts, in the order given, or empty when there are none.
   */
  fun conflictingProjectFlags(args: List<String>): List<String> =
    if (!isProjectless(args)) emptyList() else PROJECT_FLAGS.filter { args.hasFlag(it) }

  /**
   * The packaged component record shipped beside this binary, or null when it is not there.
   *
   * Resolved exactly as [packagedBuilderDir] resolves the assets — explicit app home first, then
   * the install inferred from this class's own jar — because the two are siblings written by the
   * same distribution block, and a build that moves one moves the other.
   */
  fun packagedComponentRecord(): File? {
    val appHome = System.getProperty("composeai.cli.appHome") ?: System.getenv("APP_HOME")
    return listOfNotNull(
        appHome?.let { File(it, BUILDER_COMPONENTS) },
        inferredInstallDir(BUILDER_COMPONENTS),
      )
      .map { File(it, PACKAGED_RECORD) }
      .firstOrNull { it.isFile }
  }

  /**
   * The builder distribution shipped beside this binary, or null when it is not there.
   *
   * Same ordering as `locateBundleSidecarJars` uses for the daemon sidecars: an explicit app home
   * first (`composeai.cli.appHome` / `APP_HOME`), then the install inferred from where this class
   * was loaded from — `<APP_HOME>/lib/compose-preview-serve.jar` puts the distribution two levels
   * up. A directory only counts when it actually holds `index.html`, because the failure worth
   * avoiding is a builder route that 404s every asset.
   */
  fun packagedBuilderDir(): File? {
    val appHome = System.getProperty("composeai.cli.appHome") ?: System.getenv("APP_HOME")
    return listOfNotNull(appHome?.let { File(it, BUILDER_ASSETS) }, inferredInstallAssets())
      .firstOrNull { File(it, "index.html").isFile }
  }

  private fun inferredInstallAssets(): File? = inferredInstallDir(BUILDER_ASSETS)

  /** `<APP_HOME>/[name]`, inferred from `<APP_HOME>/lib/compose-preview-serve.jar`. */
  private fun inferredInstallDir(name: String): File? = runCatching {
    val jar =
      File(
        LocalUiBuilder::class.java.protectionDomain?.codeSource?.location?.toURI()
          ?: return@runCatching null
      )
    jar.parentFile?.parentFile?.resolve(name)
  }
    .getOrNull()

  /** Where the plugin's discovery task wrote [module]'s component record. */
  fun recordFor(module: ee.schimke.composeai.previewdata.PreviewModule): File =
    File(module.projectDir, "$MODULE_PREVIEW_OUTPUT/$COMPONENT_RECORD")

  /**
   * Copy the discovered module's component record to [destination], reporting what happened.
   *
   * Returns the message to print, or null when there was nothing to say. Never throws and never
   * exits: a builder that opens with no export is worth more than a command that refuses to start,
   * and the export itself already refuses per request with a message naming the file and reason.
   *
   * A discovery carrying several modules is left alone — `serve` refuses to host more than one and
   * says which, and guessing here would only put a different module's record behind that error.
   */
  fun publishRecord(discovery: ServeDiscovery, destination: File): String? {
    val (module, _) = discovery.manifests.singleOrNull() ?: return null
    val record = recordFor(module)
    if (!record.isFile) {
      return "ui: ${module.gradlePath} has no ${record.path} — the Compose export will refuse " +
        "until the preview plugin writes one (it is produced by preview discovery)."
    }
    return runCatching {
      destination.parentFile?.mkdirs()
      record.copyTo(destination, overwrite = true)
      "ui: Compose export resolves against ${module.gradlePath}'s $COMPONENT_RECORD " +
        "(${record.path})."
    }
      .getOrElse { failure ->
        "ui: could not read ${record.path} (${failure.message ?: failure.javaClass.name}); " +
          "the Compose export will refuse."
      }
  }

  fun usage(): String =
    """
    compose-preview-server ui [options]

    Build this project's @Preview functions and open the Compose UI builder against them.

    The builder's palette is a packaged design-system catalog ($DEFAULT_CATALOG by default). What
    this command adds is the project: the module's discovered $COMPONENT_RECORD becomes the record
    the Compose export generates call sites from, so exported code calls your composables.

    Needs a build host — the `compose-preview` binary — because discovering and building a local
    Gradle project is work this server asks for over a pipe rather than doing itself. Without one
    there is nothing to point the builder at, and this command says so instead of serving an empty
    builder.

    Or, with no project at all:

        compose-preview-server ui --no-project

    which opens the builder against the packaged design systems
    (${DEFAULT_CATALOGS.joinToString(", ")}) and needs nothing else — no build host, no Gradle
    project, no catalog to fetch. Designs are saved under ~/.compose-preview/ui-builder-state and
    survive a restart. The Compose export still writes code; what it cannot do is call your
    project's own composables, because there is no project to have discovered them from.

    Options:
      --no-project      Open the builder against the packaged design systems, with no Gradle project
                        and no build host. The options below that need a project — ${PROJECT_FLAGS.joinToString(", ")} —
                        are refused alongside it rather than ignored.
      --module <path>   Build and serve one Gradle module. Omit it to discover every module in the
                        build (a build with more than one module of previews then asks you to pick).
      --variant <name>  Android build variant used for previews.
      --port <n>        Preferred port (default 8791; the next free port is used).
      --host <addr>     Bind address (default 127.0.0.1).
      --no-open         Print the URL instead of opening a browser (CI / headless shells).
      --build-host <path|none>
                        The `compose-preview` binary to run Gradle through. Defaults to
                        ${BuildHostDiscovery.ENV} in the environment, then `compose-preview` on PATH.
      --ui-builder-dir <dir>
                        Builder distribution to serve. Defaults to the one packaged beside this
                        binary.
      --ui-builder-catalogs <system>[,<system>…]
                        Catalogs to open the builder for. The first is the one opened.
      --ui-builder-state-dir <dir>|none
                        Where saved designs live. Defaults to ~/.compose-preview/ui-builder-state.
      --help, -h        Show this help.

    Every `serve` flag is accepted as well; see `compose-preview-server help serve`.
    """
      .trimIndent()
}
