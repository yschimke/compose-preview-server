package ee.schimke.composeai.cli.serve

import java.io.File

/**
 * `compose-preview-server ui`: the UI builder pointed at the project you are in (see #301).
 *
 * A combination of existing `serve` flags plus the one fact they can't know before the build runs:
 * where the module's `components.json` is. Nothing here computes a record; the Gradle plugin's
 * discovery writes it beside `previews.json`, so the export uses the module's real record.
 *
 * The record is copied rather than pointed at because `--ui-builder-components` is read before
 * Gradle runs; the path is named up front and filled once discovery reports the module
 * ([publishRecord]). [ComponentRecordSource] re-reads by `(length, lastModified)`, so it is picked
 * up without a restart.
 *
 * The catalog stays a packaged one (`m3-catalog`, `remote-m3`): a project isn't a packaged adapter,
 * so it enters only through the component record.
 */
internal object LocalUiBuilder {

  /** The builder catalog served when the caller names none. Matches `ServeCommandOptions`. */
  const val DEFAULT_CATALOG: String = "m3-catalog"

  /** The packaged builder distribution's directory name, beside the binary's `lib/`. */
  private const val BUILDER_ASSETS = "ui-builder"

  /**
   * The packaged component record's directory, a sibling of [BUILDER_ASSETS], written by
   * `server/build.gradle.kts`.
   */
  private const val BUILDER_COMPONENTS = "ui-builder-components"

  /** The packaged record's file name, as the distribution ships it. */
  private const val PACKAGED_RECORD = "m3-catalog-components-v1.json"

  /**
   * Options that only make sense with a Gradle project and contradict [NO_PROJECT]. Refused rather
   * than forwarded (which would take `ServeRunner`'s Gradle path) or silently dropped. The first
   * five mirror `ServeRunner.needsGradle` (`explicitModule`, `discover`, `exportPath`,
   * `catalogSourceRoot`, `revisions`) and must move with it; `--variant` is meaningless without a
   * module.
   */
  private val PROJECT_FLAGS: List<String> =
    listOf(
      "--module",
      "--discover",
      "--export",
      "--catalog-source-root",
      "--revisions",
      "--variant",
    )

  /** Where the Gradle plugin's discovery task writes a module's preview outputs. */
  private const val MODULE_PREVIEW_OUTPUT = "build/compose-previews"

  private const val COMPONENT_RECORD = "components.json"

  /** This lane's own flag: print the URL instead of opening a browser, as `browse` has. */
  const val NO_OPEN: String = "--no-open"

  /**
   * The builder against the packaged design systems with no project, which needs no build host or
   * discovery. An explicit flag rather than a silent degrade, so a missing build host can't quietly
   * yield the smaller mode.
   */
  const val NO_PROJECT: String = "--no-project"

  /**
   * Catalogs offered when a projectless builder names none: both packaged adapters
   * ([ProductionUiBuilderRuntime]), so nothing is fetched.
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
   * The `serve` argv this command implies. Every addition is skipped when the caller made that
   * choice, so `ui` overrides nothing.
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
    // Without one, use the distribution's packaged M3 record so the default catalog's Compose
    // export is offered. Pinned to [DEFAULT_CATALOG], since it is M3's record.
    if (projectless && !args.hasFlag("--ui-builder-components")) {
      packagedComponentRecord()?.let {
        add("--ui-builder-components")
        add("$DEFAULT_CATALOG=${it.path}")
      }
    }
    // Offer every packaged design system, so trying another needs no relaunch.
    if (projectless && !args.hasFlag("--ui-builder-catalogs")) {
      add("--ui-builder-catalogs")
      add(DEFAULT_CATALOGS.joinToString(","))
    }
    // Set even under `--no-open`: `ServeRunner` prints it in the banner, and the root landing would
    // 404 on a projectless server.
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
   * The packaged component record beside this binary, or null. Resolved like [packagedBuilderDir],
   * since both are written by the same distribution block.
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
   * The builder distribution beside this binary, or null. Explicit app home first
   * (`composeai.cli.appHome` / `APP_HOME`), then two levels up from this class's jar, as
   * `locateBundleSidecarJars` does. Only a directory holding `index.html` counts.
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
   * Copy the discovered module's component record to [destination], returning a message to print or
   * null. Never throws or exits: a builder without export beats one that won't start. Multi-module
   * discoveries are left alone; `serve` already refuses them.
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
      --port <n>        Preferred port (default ${ServeDefaults.DEFAULT_PORT}; the next free port is used).
      --host <addr>     Bind address (default 127.0.0.1).
      --no-open         Print the URL instead of opening a browser (CI / headless shells). The
                        printed URL carries this run's ?token=; it changes on every restart.
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

    Scripting the builder. The ?token= in the printed URL is the operator credential, and it
    works on the REST routes (as ?token=, or in the X-Compose-Preview-Token header):
      PUT  /api/ui-builder/v1/designs/<id>              create a design (send If-None-Match: *)
      GET  /api/ui-builder/v1/designs/<id>              read its document back
      GET  /api/ui-builder/v1/designs/<id>/export.png   render it (or .svg)

    MCP is off by default: /mcp, the ui_builder_* tools and `compose-preview-server design` need
    it, and it is mounted only when this command is also given

        --agent-grants --catalog-mcp \
          --agent-grant-capabilities ui-builder-read,ui-builder-write,ui-builder-export
    """
      .trimIndent()
}
