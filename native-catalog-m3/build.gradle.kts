plugins {
  alias(libs.plugins.ktfmt)
  alias(libs.plugins.kotlin.multiplatform)
  alias(libs.plugins.compose.multiplatform)
  alias(libs.plugins.compose.compiler)
}

ktfmt { googleStyle() }

kotlin {
  // `binaries.executable()` like every other wasmJs module here. Compose 1.12.0 added
  // `checkComposeUiTestConfigurationForWasmJs`, which fails a `wasmJs` target that declares no
  // executable: the Skiko runtime a Compose UI test needs is bundled by webpack and there is
  // nothing to bundle it into (CMP-4906). This module has no test source set at all, so the check
  // is guarding a test that does not exist — declared anyway because it costs nothing here
  // (`wasmJsTest` is NO-SOURCE, so no link is produced) and keeps the next Compose UI test added
  // to this module from inheriting a configuration that cannot load Skiko.
  @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
  wasmJs {
    browser()
    outputModuleName.set("nativeCatalogM3")
    binaries.executable()
  }

  sourceSets {
    commonMain.dependencies {
      @Suppress("DEPRECATION") implementation(compose.runtime)
      @Suppress("DEPRECATION") implementation(compose.foundation)
      @Suppress("DEPRECATION") implementation(compose.material3)
      @Suppress("DEPRECATION") implementation(compose.ui)
      @Suppress("DEPRECATION") implementation(compose.components.resources)
      implementation(libs.graphics.shapes)
      api(libs.composeai.slot.preview.runtime)
    }
  }
}

compose.resources {
  publicResClass = true
  packageOfResClass = "com.example.designcatalogm3.shared.generated.resources"
}
