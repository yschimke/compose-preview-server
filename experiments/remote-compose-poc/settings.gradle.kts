pluginManagement {
  repositories {
    gradlePluginPortal()
    google()
    mavenCentral()
  }
}

dependencyResolutionManagement {
  repositories {
    google()
    mavenCentral()
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
