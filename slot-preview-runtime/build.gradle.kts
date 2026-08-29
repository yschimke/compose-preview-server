plugins {
  alias(libs.plugins.ktfmt)
  alias(libs.plugins.kotlin.multiplatform)
  alias(libs.plugins.compose.multiplatform)
  alias(libs.plugins.compose.compiler)
}

ktfmt { googleStyle() }

kotlin {
  jvm {
    compilations.configureEach {
      compileTaskProvider.configure {
        compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
      }
    }
  }
  @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class) wasmJs { browser() }
  sourceSets {
    commonMain.dependencies {
      @Suppress("DEPRECATION") api(compose.runtime)
      @Suppress("DEPRECATION") api(compose.foundation)
      @Suppress("DEPRECATION") api(compose.ui)
    }
    jvmTest.dependencies {
      implementation(libs.composeai.data.layoutinspector.core)
      implementation(kotlin("test-junit5"))
    }
  }
}

tasks.withType<Test>().configureEach { useJUnitPlatform() }
