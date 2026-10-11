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
  // Kotlin/Wasm adds its Node distribution as an Ivy repository; project preference exists only for
  // that.
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

    // The CMP Wear port (`ee.schimke.wearcmp:*`, jvm + wasmJs) so the canvas draws real Wear
    // components; see `docs/design/UI_BUILDER_WEAR_SCREEN.md`. Fenced with `includeGroup` so it can
    // never satisfy an `androidx.*` or `ee.schimke.composeai` request. The device-preview lane uses
    // genuine AndroidX AARs.
    maven("https://raw.githubusercontent.com/yschimke/wear-m3-catalog-out/wear-compose-cmp-maven/") {
      name = "wearComposeCmpPort"
      content { includeGroup("ee.schimke.wearcmp") }
    }

    // The UI-builder editor archive (`compose-preview-ui-builder-web`, ~40 MB Wasm), shipped as a
    // GitHub release asset rather than on Maven Central; an ivy repository keeps it a normal
    // versioned dependency. `metadataSources { artifact() }` because the asset has no POM/module
    // metadata, so `:server` requests it by extension. The pattern spells the tag's `v` prefix
    // separately from the file name's. Fenced to this one module.
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

// BuildFetch remote build cache, off unless a token resolves. Writes only from trusted CI
// (`ON_CI=true`, set on pushes to `main`); PRs read. `-Pcomposeai.remoteCache=off` disables it.
val onCi = providers.environmentVariable("ON_CI").orElse("false").get().toBoolean()

// Non-blank view of one env var or Gradle property: an unset secret that CI still exports as an
// empty string neither shadows a later fallback nor enables the cache with an empty credential.
val nonBlank = { source: Provider<String> -> source.map { it.trim() }.filter { it.isNotEmpty() } }
val cacheToken =
  nonBlank(providers.environmentVariable("BUILDFETCH_COMPOSEAI_GRADLE_REMOTE_CACHE_TOKEN"))
    .orElse(nonBlank(providers.gradleProperty("BUILDFETCH_COMPOSEAI_GRADLE_REMOTE_CACHE_TOKEN")))
    .orElse(nonBlank(providers.environmentVariable("BUILDFETCH_GRADLE_REMOTE_CACHE_TOKEN")))
    .orElse(nonBlank(providers.gradleProperty("BUILDFETCH_GRADLE_REMOTE_CACHE_TOKEN")))
    .orNull
val remoteCacheDisabled =
  providers.gradleProperty("composeai.remoteCache").orElse("on").get().trim().lowercase() == "off"

buildCache {
  local { isEnabled = true }
  remote<HttpBuildCache> {
    url = uri("https://cache.eu-central-a.buildfetch.com/8ESz2z/gradle/")
    credentials {
      username = "token-auth"
      password = cacheToken
    }
    isPush = onCi && !remoteCacheDisabled
    isEnabled = cacheToken != null && !remoteCacheDisabled
  }
}

rootProject.name = "compose-preview-server"

// Optional composite builds against sibling checkouts, replacing published coordinates:
//
//     ./gradlew check -PlocalBuilds=tools,daemon   # or =all
//     ./gradlew check -PcomposeUiBuilderDir=../compose-ui-builder
//
// `-PlocalBuild.<name>=<path>` overrides the location; a missing directory is an error, not a
// silent fallback to Maven. The UI builder takes its own property because project properties reach
// the included build too, and its settings reject unknown `localBuilds` names. It also needs
// explicit substitution rules, since its artifact ids differ from its project names. For pinning a
// fixed upstream build instead, use `scripts/stage-local-dependency.py`.
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

// The UI builder as an opt-in composite build; unset means the released coordinates.
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

// The MCP server (`compose-preview mcp serve`); it lives here because it needs an HTTP server.
include(":mcp")

include(":usage-source-psi")

include(":wasm-ui")

include(":native-catalog-m3")
