plugins {
  alias(libs.plugins.ktfmt)
  alias(libs.plugins.kotlin.multiplatform)
  alias(libs.plugins.compose.multiplatform)
  alias(libs.plugins.compose.compiler)
}

ktfmt { googleStyle() }

kotlin {
  // `binaries.executable()` with no application to run: compose-multiplatform 1.12.0 added
  // `checkComposeUiTestConfigurationForWasmJs`, which fails a wasmJs target whose test classpath
  // resolves Skiko — as this one's does, transitively through `compose.ui` — but declares no
  // executable for webpack to bundle that runtime into. No link is produced while `wasmJsTest` is
  // NO-SOURCE, and it matches the six sibling targets that already declare one. See CMP-4906.
  @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
  wasmJs {
    browser()
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
