plugins {
  alias(libs.plugins.ktfmt)
  alias(libs.plugins.kotlin.multiplatform)
  alias(libs.plugins.compose.multiplatform)
  alias(libs.plugins.compose.compiler)
}

ktfmt { googleStyle() }

kotlin {
  // `binaries.executable()` is required by Compose 1.12.0, not optional here: its
  // `checkComposeUiTestConfigurationForWasmJs` check fails the build when the wasmJs test
  // classpath resolves Skiko and no executable binary is declared, because the Skiko runtime
  // would have nothing to load from (CMP-4906). This module declares no wasmJs tests today, but
  // the check looks at the configuration rather than at whether tests exist.
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
