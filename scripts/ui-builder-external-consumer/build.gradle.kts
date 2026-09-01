import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.zip.ZipFile
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.attributes.Category
import org.gradle.api.attributes.LibraryElements
import org.gradle.api.attributes.Usage

plugins { java }

val gateVersion = providers.gradleProperty("gateVersion").get()
val gateRepository = file(providers.gradleProperty("gateRepository").get()).canonicalFile
val forbiddenSourceRoot = file(providers.gradleProperty("forbiddenSourceRoot").get()).canonicalFile

dependencies {
  implementation("ee.schimke.composeai:compose-preview-ui-builder-runtime:$gateVersion")
}

val webArchive =
  configurations.create("uiBuilderWebArchive") {
    isCanBeConsumed = false
    isCanBeResolved = true
    attributes {
      attribute(Category.CATEGORY_ATTRIBUTE, objects.named("distribution"))
      attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named("ui-builder-web"))
      attribute(Usage.USAGE_ATTRIBUTE, objects.named("ui-builder-web"))
    }
  }

dependencies {
  add(
    webArchive.name,
    "ee.schimke.composeai:compose-preview-ui-builder-web:$gateVersion",
  )
}

tasks.register<JavaExec>("runRuntimeProbe") {
  dependsOn(tasks.named("classes"))
  classpath = sourceSets.main.get().runtimeClasspath
  mainClass.set("ExternalRuntimeConsumer")
}

fun ByteArray.contains(needle: ByteArray): Boolean {
  if (needle.isEmpty()) return true
  return indices.any { start ->
    start + needle.size <= size &&
      needle.indices.all { offset -> this[start + offset] == needle[offset] }
  }
}

fun File.sha256(): String {
  val digest = MessageDigest.getInstance("SHA-256")
  inputStream().buffered().use { input ->
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    while (true) {
      val count = input.read(buffer)
      if (count < 0) break
      digest.update(buffer, 0, count)
    }
  }
  return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
}

tasks.register("verifyExtractionConsumer") {
  group = "verification"
  description = "Consumes the published UI-builder runtime and exact web distribution variant."
  dependsOn("runRuntimeProbe")

  doLast {
    check(!projectDir.canonicalFile.toPath().startsWith(forbiddenSourceRoot.toPath())) {
      "external consumer was created inside the producer source tree"
    }

    val runtimeClasspath = configurations.runtimeClasspath.get()
    val resolvedConfigurations = listOf(runtimeClasspath, webArchive)
    resolvedConfigurations.forEach { configuration ->
      val projectComponents =
        configuration.incoming.resolutionResult.allComponents
          .filter { component -> component.id != configuration.incoming.resolutionResult.root.id }
          .mapNotNull { component -> component.id as? ProjectComponentIdentifier }
      check(projectComponents.isEmpty()) {
        "external consumer resolved producer projects: ${projectComponents.joinToString()}"
      }
      configuration.resolve().forEach { artifact ->
        check(!artifact.canonicalFile.toPath().startsWith(forbiddenSourceRoot.toPath())) {
          "resolved artifact reads the producer source tree: $artifact"
        }
      }
    }

    val runtimeArtifact =
      runtimeClasspath.incoming.artifacts.artifacts.single { artifact ->
        val id = artifact.id.componentIdentifier as? ModuleComponentIdentifier
        id?.group == "ee.schimke.composeai" && id.module == "compose-preview-ui-builder-runtime"
      }
    check(runtimeArtifact.file.extension == "jar")
    ZipFile(runtimeArtifact.file).use { jar ->
      check(
        jar.getEntry("ee/schimke/composeai/uibuilder/service/UiBuilderServicePort.class") != null
      )
      check(jar.getEntry("ee/schimke/composeai/uibuilder/catalogs/m3-catalog-v1.json") != null)
      check(
        jar.getEntry(
          "ee/schimke/composeai/uibuilder/renderer/ui-builder-renderer.bundle.png"
        ) != null
      )
    }

    val webArtifact = webArchive.incoming.artifacts.artifacts.single()
    check(webArtifact.file.extension == "zip") {
      "exact UI-builder web variant resolved ${webArtifact.file.name}, not a ZIP"
    }
    val webAttributes = webArtifact.variant.attributes
    check(webAttributes.getAttribute(Category.CATEGORY_ATTRIBUTE)?.name == "distribution")
    check(
      webAttributes.getAttribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE)?.name ==
        "ui-builder-web"
    )
    check(webAttributes.getAttribute(Usage.USAGE_ATTRIBUTE)?.name == "ui-builder-web")

    ZipFile(webArtifact.file).use { zip ->
      val names = zip.entries().asSequence().map { it.name }.toSet()
      val required =
        setOf(
          "index.html",
          "uiBuilder.mjs",
          "uiBuilder.wasm",
          "skiko.mjs",
          "skiko.wasm",
          "jetcaster-discover-capabilities-v1.json",
          "jetcaster-discover-operations-v1.json",
          "fonts/fonts.json",
        )
      check(names.containsAll(required)) { "web archive is missing ${required - names}" }
    }

    val coordinateDirectory =
      gateRepository.resolve("ee/schimke/composeai/compose-preview-ui-builder-web/$gateVersion")
    val moduleMetadata =
      coordinateDirectory.listFiles().orEmpty().single { it.extension == "module" }.readText()
    val pom = coordinateDirectory.listFiles().orEmpty().single { it.extension == "pom" }.readText()
    check(moduleMetadata.contains("\"org.gradle.category\": \"distribution\""))
    check(moduleMetadata.contains("\"org.gradle.libraryelements\": \"ui-builder-web\""))
    check(moduleMetadata.contains("\"org.gradle.usage\": \"ui-builder-web-api\""))
    check(moduleMetadata.contains("\"org.gradle.usage\": \"ui-builder-web\""))
    check(moduleMetadata.contains("\"sha256\": \"${webArtifact.file.sha256()}\"")) {
      "published Gradle metadata does not authenticate the resolved web archive"
    }
    check(pom.contains("<packaging>zip</packaging>"))

    val forbiddenBytes = forbiddenSourceRoot.path.toByteArray(StandardCharsets.UTF_8)
    val leakedFiles =
      gateRepository
        .walkTopDown()
        .filter { it.isFile && it.readBytes().contains(forbiddenBytes) }
        .map { it.relativeTo(gateRepository).path }
        .toList()
    check(leakedFiles.isEmpty()) {
      "published repository leaks producer source paths: ${leakedFiles.joinToString()}"
    }
  }
}
