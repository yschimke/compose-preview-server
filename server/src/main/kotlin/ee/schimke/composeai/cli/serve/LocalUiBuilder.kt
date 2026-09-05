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

  /** Where the Gradle plugin's discovery task writes a module's preview outputs. */
  private const val MODULE_PREVIEW_OUTPUT = "build/compose-previews"

  private const val COMPONENT_RECORD = "components.json"

  /** This lane's own flag: print the URL instead of opening a browser, as `browse` has. */
  const val NO_OPEN: String = "--no-open"

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
    // `--no-open` is this lane's flag, not a server flag; forwarding it would leave an argument the
    // server does not know in its argv.
    addAll(args.filterNot { it == NO_OPEN })
    // The builder is being pointed at a project, so the previews have to be discovered and built.
    // `--module` implies it already; `--discover` alone means every module in the build.
    if (!args.hasFlag("--module") && !args.hasFlag("--discover")) add("--discover")
    if (builderDir != null && !args.hasFlag("--ui-builder-dir")) {
      add("--ui-builder-dir")
      add(builderDir.path)
    }
    if (!args.hasFlag("--ui-builder-components")) {
      add("--ui-builder-components")
      add("$catalog=${componentRecord.path}")
    }
    if (NO_OPEN !in args) {
      if (!args.hasFlag("--open-browser")) add("--open-browser")
      if (!args.hasFlag("--open-path")) {
        add("--open-path")
        add("/ui-builder/$catalog/")
      }
    }
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

  private fun inferredInstallAssets(): File? = runCatching {
    val jar =
      File(
        LocalUiBuilder::class.java.protectionDomain?.codeSource?.location?.toURI()
          ?: return@runCatching null
      )
    jar.parentFile?.parentFile?.resolve(BUILDER_ASSETS)
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

    Options:
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
