pluginManagement {
  repositories {
    // Google Maven first, scoped to its own groups, so AGP and androidx never reach the plugin
    // portal or Central: under load those answer a miss with HTTP 429, which fails the build,
    // instead of the 404 that would have moved on to Google.
    google {
      mavenContent {
        includeGroupAndSubgroups("androidx")
        includeGroupAndSubgroups("com.android")
        includeGroupAndSubgroups("com.google")
      }
    }
    gradlePluginPortal()
    mavenCentral()
  }
}

// Exercise the owning compiler's normal JVM publication without an upstream release.
val localCompilerManifest =
  providers.gradleProperty("localJsonCompilerManifest").orNull?.let(::file)
val compilerProperties = java.util.Properties()

localCompilerManifest?.reader()?.use(compilerProperties::load)

val localCompilerCoordinate =
  compilerProperties.getProperty("coordinates")?.split(",")?.single {
    it.startsWith("ee.schimke.composeai:remotecompose-json:")
  }

dependencyResolutionManagement {
  repositories {
    localCompilerManifest?.let { manifest ->
      require(localCompilerCoordinate != null) { "Manifest must stage remotecompose-json" }
      exclusiveContent {
        forRepository {
          maven {
            url = manifest.parentFile.resolve(compilerProperties.getProperty("repository")).toURI()
          }
        }
        filter { includeModule("ee.schimke.composeai", "remotecompose-json") }
      }
    }
    google()
    mavenCentral()
  }
}

localCompilerCoordinate?.let { coordinate ->
  gradle.beforeProject {
    configurations.configureEach {
      resolutionStrategy.eachDependency {
        if (requested.group == "ee.schimke.composeai" && requested.name == "remotecompose-json") {
          useVersion(coordinate.substringAfterLast(':'))
        }
      }
    }
  }
}

rootProject.name = "remote-compose-poc"

// This disposable build may consume an unpublished player without a release cycle.
// The production build's dependency policy is independent of this experiment.
providers.gradleProperty("localRcPlayers").orNull?.let { path ->
  includeBuild(file(path)) {
    dependencySubstitution {
      substitute(module("ee.schimke.composeai:rc-player-compose"))
        .using(project(":rc-player-compose"))
      substitute(module("ee.schimke.composeai:rc-player-protocol"))
        .using(project(":rc-player-protocol"))
      substitute(module("ee.schimke.composeai:rc-player-runtime"))
        .using(project(":rc-player-runtime"))
    }
  }
}
