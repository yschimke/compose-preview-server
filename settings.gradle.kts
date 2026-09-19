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

    // ── The UI-builder editor archive, from its GitHub release ──────────────────────────────────
    //
    // `compose-preview-ui-builder-web` is a ~40 MB Wasm distribution that this build unpacks into
    // the server distribution. Nothing compiles against it and nothing resolves it transitively,
    // so yschimke/compose-ui-builder ships it as a release asset rather than putting a frontend
    // distribution on Maven Central forever. Its three sibling coordinates — the runtime, the
    // export projection and the render bundle — are on Central, because those are what `:server`
    // actually compiles against.
    //
    // An ivy repository rather than a download task, so it stays an ordinary versioned dependency:
    // the version catalog names it, Gradle caches it, and the day it moves to a real Maven
    // repository this block is deleted and nothing else changes.
    //
    // `metadataSources { artifact() }` because a release asset is a bare file with no POM and no
    // Gradle module metadata. That also means the `distribution` variant attributes this build
    // matches on are NOT carried across — `:server`'s `uiBuilderWeb` configuration asks for the
    // artifact by extension instead, and its build file says so where it declares the dependency.
    //
    // The `v` in the pattern is the TAG's, not a typo: release-please cuts `v3.28.0`, while the
    // asset it attaches is `compose-preview-ui-builder-web-3.28.0.zip`. The tag segment and the
    // file name disagree about the prefix, so the layout has to spell both.
    //
    // FENCED to the single module, like the Wear port above: this repository can never satisfy a
    // request for anything else, and a typo in a coordinate fails loudly instead of reaching a
    // GitHub 404 page and being parsed as a jar.
    ivy("https://github.com/yschimke/compose-ui-builder/releases/download") {
      name = "uiBuilderWebRelease"
      patternLayout { artifact("v[revision]/[module]-[revision].[ext]") }
      content { includeModule("ee.schimke.composeai", "compose-preview-ui-builder-web") }
      metadataSources { artifact() }
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

// ── Optional composite builds against sibling checkouts ────────────────────────────────────────
//
// Everything this build resolves is a published coordinate by default -- the compose-ai-tools
// line, the preview daemon, the contracts line, and the UI builder's four seams. Naming a sibling
// swaps that coordinate for the checkout instead, so a change can be built in both repositories at
// once:
//
//     ./gradlew check -PlocalBuilds=tools
//     ./gradlew check -PlocalBuilds=tools,daemon
//     ./gradlew check -PlocalBuilds=all
//
// `-PlocalBuild.<name>=<path>` overrides the default checkout location. A named sibling whose
// directory is missing is an error rather than a silent fall back to Maven: "I asked for my local
// tools and got the released one" is exactly the confusion this exists to remove.
//
// The UI builder is deliberately NOT an entry in `localBuilds`. Gradle project properties are
// global to the invocation, so the included build reads the same value, and
// yschimke/compose-ui-builder's settings rejects a sibling name it does not know -- a UI-builder
// entry there would be a repository including itself. It takes its own property instead:
//
//     ./gradlew check -PcomposeUiBuilderDir=../compose-ui-builder
//
// Unset resolves the releases; set resolves the checkout, which is why the default build -- and
// the release -- proves the published coordinates are complete.
//
// The UI builder is the one entry with EXPLICIT substitution rules, because Gradle's automatic
// matching keys on group and project name and these four publish under artifact ids their projects
// are not named after (`:ui-builder-runtime` -> `compose-preview-ui-builder-runtime`). The other
// upstreams publish the coordinates their projects are named after, which is the case Gradle
// substitutes automatically.
//
// The web archive is the one seam that is not a Maven module -- released as a GitHub asset,
// reached through the fenced ivy repository above, requested artifact-only. Substitution still
// matches it: the included project's `runtimeElements` carries the archive, which is what the
// artifact-only request resolves to.
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

// The UI builder, as an opt-in composite build. The checkout default is the sibling directory a
// two-repository setup already has; the property is required to turn it on, so its absence is
// what makes the released coordinates the default.
providers.gradleProperty("composeUiBuilderDir").orNull?.let { path ->
  val directory = file(path).canonicalFile
  require(directory.resolve("settings.gradle.kts").isFile) {
    "-PcomposeUiBuilderDir names $directory, which is not a Gradle build. Clone " +
      "yschimke/compose-ui-builder there, or point at your checkout with " +
      "-PcomposeUiBuilderDir=<path>."
  }
  logger.lifecycle("Composite build: uiBuilder -> $directory")
  includeBuild(directory) {
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
}

include(":server")

// The MCP server — `compose-preview mcp serve`. Moved here from compose-ai-tools because the layer
// rule places a module that needs an HTTP server in this repository (compose-ai-tools#5176); it
// consumes the layer-1 daemon/render-session coordinates it used to reach as projects.
include(":mcp")

include(":usage-source-psi")

include(":wasm-ui")

include(":native-catalog-m3")
