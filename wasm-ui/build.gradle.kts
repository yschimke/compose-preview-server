plugins {
  // Apply formatting first, matching the source repository's base convention plugin. It installs
  // Gradle's lifecycle before Kotlin/Wasm's Node root plugin configures the shared `check` task.
  alias(libs.plugins.ktfmt)
  alias(libs.plugins.kotlin.multiplatform)
  alias(libs.plugins.compose.multiplatform)
  alias(libs.plugins.compose.compiler)
}

ktfmt { googleStyle() }

// Version of the compose-ai-tools sources compiled into :native-catalog-m3. The public catalog
// publishes the renderer version that produced its snapshots; the frontend substitutes native
// composables only when these values agree exactly (#4821).
val generateNativeCatalogVersion =
  tasks.register("generateNativeCatalogVersion") {
    val outputDir = layout.buildDirectory.dir("generated/nativeCatalogVersion/kotlin")
    val nativeVersion = libs.versions.composeai.tools.get()
    inputs.property("nativeCatalogVersion", nativeVersion)
    outputs.dir(outputDir)
    doLast {
      val source =
        outputDir.get().file("ee/schimke/composeai/servewasm/NativeCatalogVersion.kt").asFile
      source.parentFile.mkdirs()
      source.writeText(
        """
      package ee.schimke.composeai.servewasm

      internal const val NATIVE_CATALOG_VERSION = "$nativeVersion"
      """
          .trimIndent() + "\n"
      )
    }
  }

kotlin {
  @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
  wasmJs {
    outputModuleName.set("previewServer")
    browser()
    binaries.executable()
  }

  sourceSets {
    commonMain { kotlin.srcDir(generateNativeCatalogVersion) }
    commonMain.dependencies {
      implementation(project(":native-catalog-m3"))
      @Suppress("DEPRECATION") implementation(compose.runtime)
      @Suppress("DEPRECATION") implementation(compose.foundation)
      @Suppress("DEPRECATION") implementation(compose.material3)
      @Suppress("DEPRECATION") implementation(compose.ui)
      implementation(libs.kotlinx.coroutines.core)
      implementation(libs.kotlinx.serialization.json)
    }
    wasmJsTest.dependencies { implementation(kotlin("test")) }
  }
}

// Keep the prototype webpack-free, matching the catalog and Remote Compose Wasm apps. This makes
// the output a plain static directory that `serve --wasm-ui-dir <dir>` can project per catalog
// server's existing same-origin Wasm asset lane.
tasks.register<Sync>("wasmFrontendDist") {
  description = "Assemble the experimental preview-server Wasm frontend."
  group = "distribution"
  dependsOn("wasmJsDevelopmentExecutableCompileSync", "processSkikoRuntimeForKWasm")
  dependsOn("wasmJsProcessResources")
  from(layout.buildDirectory.dir("compileSync/wasmJs/main/developmentExecutable/kotlin"))
  from(layout.buildDirectory.dir("compose/skiko-runtime-processed-wasmjs")) {
    include("skiko.mjs", "skiko.wasm")
  }
  from(layout.projectDirectory.dir("src/wasmJsMain/resources")) {
    include("index.html", "js-joda.esm.js")
  }
  from(layout.buildDirectory.dir("processedResources/wasmJs/main")) {
    include("composeResources/**")
  }
  // The typefaces the native catalog lane composes with (#4821). Taken from the repository's own
  // `assets/rc-fonts` — the same files the server serves to the client-side Remote Compose lanes
  // and the offline parity harness registers — rather than a second vendored copy under
  // `src/wasmJsMain/resources`, so the two can never drift onto different outlines.
  //
  // Without these the lane fell back to the CMP bundled font while claiming to reproduce snapshots
  // the Android renderer rasterized with Roboto, so text metrics and wrapping differed in the
  // default lane.
  from(rootProject.layout.projectDirectory.dir("assets/rc-fonts")) {
    include("*.ttf", "fonts.json", "*OFL.txt", "LICENSE.txt")
    into("fonts")
  }
  into(layout.buildDirectory.dir("wasmDist"))
}
