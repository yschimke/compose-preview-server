// The edit→render fixture for `EditLoopIntegrationTest` (issue #1174). It deliberately does NOT
// apply the Compose Preview plugin: the compose-preview CLI's init script injects it, the way it
// does for most real projects, so the test covers the recompile path #1174 fixed.
pluginManagement {
  repositories {
    google()
    mavenCentral()
    gradlePluginPortal()
  }
}

dependencyResolutionManagement {
  repositories {
    google()
    mavenCentral()
  }
}

rootProject.name = "edit-loop-fixture"

include(":app")
