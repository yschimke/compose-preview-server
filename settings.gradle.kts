pluginManagement {
  repositories {
    gradlePluginPortal()
    google()
    mavenCentral()
  }
}

// Development only: selected upstream publications compiled by stage-local-dependency.py.
// Normal builds still use the released catalog. The manifest includes every staged KMP variant.
val localDependencyManifest = providers.gradleProperty("localDependencies").orNull?.let { file(it) }
val localDependencyProperties = java.util.Properties()

localDependencyManifest?.let { manifest ->
  require(manifest.isFile) { "Local dependency manifest does not exist: $manifest" }
  manifest.reader().use(localDependencyProperties::load)
}

val localDependencyVersions =
  localDependencyProperties.getProperty("coordinates")?.split(",")?.associate { coordinate ->
    val parts = coordinate.split(":")
    require(parts.size == 3 && parts.all { it.isNotBlank() }) {
      "Invalid local dependency coordinate: $coordinate"
    }
    "${parts[0]}:${parts[1]}" to parts[2]
  } ?: emptyMap()

if (localDependencyManifest != null) {
  require(localDependencyVersions.isNotEmpty()) { "Local dependency manifest has no coordinates" }
  val modelVersion = localDependencyVersions["ee.schimke.composeai:screen-model"]
  val discoveryVersion = localDependencyVersions["ee.schimke.composeai:preview-discovery"]
  require(modelVersion == discoveryVersion) {
    "Stage screen-model and preview-discovery together: both contain the shared generator classes"
  }
}

dependencyResolutionManagement {
  // Kotlin/Wasm adds the Node distribution as an Ivy repository when its setup task is realized.
  // The build scripts declare no repositories; project preference exists solely for that
  // plugin-owned toolchain repository.
  repositoriesMode.set(RepositoriesMode.PREFER_PROJECT)
  repositories {
    localDependencyManifest?.let { manifest ->
      val repository =
        manifest.parentFile.resolve(
          requireNotNull(localDependencyProperties.getProperty("repository")) {
            "Local dependency manifest has no repository"
          }
        )
      require(repository.isDirectory) { "Local dependency repository does not exist: $repository" }
      exclusiveContent {
        forRepository {
          maven {
            name = "uiBuilderLocal"
            url = repository.toURI()
          }
        }
        filter {
          localDependencyVersions.keys.forEach { module ->
            val (group, artifact) = module.split(":")
            includeModule(group, artifact)
          }
        }
      }
    }
    google()
    mavenCentral()

    // ── The CMP Wear port, GROUP-FENCED ─────────────────────────────────────────────────────────
    // `ee.schimke.wearcmp:*` — Wear Compose Material 3 / Foundation compiled for Compose
    // Multiplatform, published from `yschimke/wear-m3-catalog`'s `wear-compose-cmp-maven` branch.
    // It publishes `jvm` and `wasmJs` variants, which are exactly `:ui-builder`'s two targets, and
    // it is what lets the canvas draw Wear components instead of renaming three of them to
    // Material 3 lookalikes. See `docs/design/UI_BUILDER_WEAR_SCREEN.md`.
    //
    // Fenced to the one group with `includeGroup` rather than a prefix guess, so this repository
    // can never satisfy a request for an `androidx.*` or `ee.schimke.composeai` artifact by
    // accident. The device-preview lane is unaffected: it renders through the native
    // `wear-m3-catalog` bundle against the genuine AndroidX AARs, and the port never reaches it.
    maven("https://raw.githubusercontent.com/yschimke/wear-m3-catalog/wear-compose-cmp-maven/") {
      name = "wearComposeCmpPort"
      content { includeGroup("ee.schimke.wearcmp") }
    }
  }
}

if (localDependencyVersions.isNotEmpty()) {
  logger.lifecycle("Using local dependency publications: ${localDependencyVersions.keys.sorted()}")
  gradle.beforeProject {
    configurations.configureEach {
      resolutionStrategy.eachDependency {
        localDependencyVersions["${requested.group}:${requested.name}"]?.let { version ->
          useVersion(version)
          because("Explicit localDependencies manifest")
        }
      }
    }
  }
}

rootProject.name = "compose-preview-server"

// ── The UI builder, as a composite build ───────────────────────────────────────────────────────
//
// The nine UI-builder modules were extracted to `yschimke/compose-ui-builder`, where the boundary
// `docs/design/UI_BUILDER_PROJECT_BOUNDARY.md` drew inside this repository became the repository
// boundary. This build consumes the four modules that document's table names as seams, and nothing
// else of that project.
//
// They are NOT on Maven Central yet -- publishing is the step after this one -- so the coordinates
// below are resolved by substituting the included build's projects for them. The dependency
// declarations in `server/build.gradle.kts` are already spelled as coordinates, which is the whole
// point of doing it this way round: when the publishing lane lands, this block is deleted and
// nothing else changes.
//
// The rules are EXPLICIT because Gradle's automatic substitution matches on group and project
// name, and these four publish under artifact ids their projects are not named after
// (`:ui-builder-runtime` -> `compose-preview-ui-builder-runtime`). Automatic matching would find
// nothing and the build would fail asking Maven for an artifact that does not exist yet.
//
// A checkout beside this one is the default. CI sets the property instead, because a GitHub
// Actions workspace cannot hold a sibling directory above itself.
val uiBuilderCheckout =
  file(providers.gradleProperty("composeUiBuilderDir").orNull ?: "../compose-ui-builder")
    .canonicalFile

require(uiBuilderCheckout.resolve("settings.gradle.kts").isFile) {
  """
  The UI builder is a separate repository and this build needs a checkout of it:

      git clone https://github.com/yschimke/compose-ui-builder ${uiBuilderCheckout}

  Or point at an existing one with -PcomposeUiBuilderDir=<path>.
  """
    .trimIndent()
}

includeBuild(uiBuilderCheckout) {
  dependencySubstitution {
    substitute(module("ee.schimke.composeai:compose-preview-ui-builder-runtime"))
      .using(project(":ui-builder-runtime"))
    substitute(module("ee.schimke.composeai:compose-preview-ui-builder-export"))
      .using(project(":ui-builder-export"))
    substitute(module("ee.schimke.composeai:compose-preview-ui-builder-web"))
      .using(project(":ui-builder-web"))
    substitute(module("ee.schimke.composeai:compose-preview-ui-builder-render-bundle"))
      .using(project(":ui-builder-render-bundle"))
  }
}

// ── Optional composite builds against sibling checkouts ────────────────────────────────────────
//
// The same mechanism for the upstream this build resolves as published coordinates -- the
// compose-ai-tools line, the preview daemon, the contracts line. OPT-IN: naming no sibling
// resolves everything from Maven exactly as before.
//
//     ./gradlew check -PlocalBuilds=tools
//     ./gradlew check -PlocalBuilds=tools,daemon
//     ./gradlew check -PlocalBuilds=all
//
// `-PlocalBuild.<name>=<path>` overrides the default checkout location. A named sibling whose
// directory is missing is an error rather than a silent fall back to Maven: "I asked for my local
// tools and got the released one" is exactly the confusion this exists to remove.
//
// No substitution rules here, unlike the UI builder above: these upstreams publish the coordinates
// their projects are named after, which is the case Gradle substitutes automatically.
//
// `scripts/stage-local-dependency.py` still exists and is the right tool for a different job --
// pinning one FIXED upstream build into a workspace-local Maven repository, rather than following
// a checkout as it changes.
val localBuildRoots =
  mapOf(
    "tools" to "../compose-ai-tools",
    "daemon" to "../compose-preview-daemon",
    "contracts" to "../compose-preview-contracts",
  )

providers
  .gradleProperty("localBuilds")
  .orNull
  ?.split(",")
  ?.map(String::trim)
  ?.filter(String::isNotEmpty)
  .orEmpty()
  .flatMap { requested -> if (requested == "all") localBuildRoots.keys else listOf(requested) }
  .distinct()
  .forEach { name ->
    val default =
      requireNotNull(localBuildRoots[name]) {
        "Unknown local build '$name'. Known siblings: ${localBuildRoots.keys.sorted()}, or 'all'."
      }
    val directory =
      file(providers.gradleProperty("localBuild.$name").orNull ?: default).canonicalFile
    require(directory.resolve("settings.gradle.kts").isFile) {
      "-PlocalBuilds names '$name' but $directory is not a Gradle build. Clone it there, or " +
        "point at your checkout with -PlocalBuild.$name=<path>."
    }
    logger.lifecycle("Composite build: $name -> $directory")
    includeBuild(directory)
  }

include(":server")

// The MCP server — `compose-preview mcp serve`. Moved here from compose-ai-tools because the layer
// rule places a module that needs an HTTP server in this repository (compose-ai-tools#5176); it
// consumes the layer-1 daemon/render-session coordinates it used to reach as projects.
include(":mcp")

include(":usage-source-psi")

include(":wasm-ui")

include(":native-catalog-m3")
