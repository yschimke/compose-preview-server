package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.uibuilder.protocol.UI_BUILDER_RUNTIME_MANIFEST_NAME_V1
import ee.schimke.composeai.uibuilder.protocol.UI_BUILDER_RUNTIME_MANIFEST_SCHEMA_V1
import ee.schimke.composeai.uibuilder.protocol.UI_BUILDER_RUNTIME_MANIFEST_SCHEMA_V2
import ee.schimke.composeai.uibuilder.protocol.UiBuilderRuntimeArtifactV1
import ee.schimke.composeai.uibuilder.protocol.UiBuilderRuntimeManifestV1
import ee.schimke.composeai.uibuilder.protocol.UiBuilderRuntimeManifestV2
import ee.schimke.composeai.uibuilder.protocol.UiBuilderRuntimeValidationIssueV1
import ee.schimke.composeai.uibuilder.protocol.frameUiBuilderRuntimeTreeIntegrityV1
import ee.schimke.composeai.uibuilder.protocol.isValidUiBuilderRuntimeIdV1
import ee.schimke.composeai.uibuilder.protocol.normalizeUiBuilderRuntimeAssetPathV1
import ee.schimke.composeai.uibuilder.protocol.validateContract
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.security.MessageDigest
import java.util.zip.ZipInputStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * An in-memory snapshot of retained, version-addressed UI-builder renderer assets.
 *
 * Input directories are read exactly once. Later changes on disk cannot change bytes behind an
 * immutable URL, and symbolic links are rejected so a bundle cannot escape its declared root.
 */
internal class ServeUiBuilderRuntimeAssets
private constructor(private val runtimes: Map<String, RuntimeBundle>) {
  internal data class Asset(val bytes: ByteArray, val etag: String)

  internal val runtimeIds: Set<String>
    get() = runtimes.keys

  internal fun asset(runtimeId: String, segments: List<String>): Asset? {
    val bundle = runtimes[runtimeId] ?: return null
    val path =
      if (segments.isEmpty()) RUNTIME_MANIFEST_NAME
      else normalizeRelativePath(segments.joinToString("/")) ?: return null
    return bundle.assets[path]
  }

  private data class RuntimeBundle(val assets: Map<String, Asset>)

  internal companion object {
    internal const val MANIFEST_SCHEMA = UI_BUILDER_RUNTIME_MANIFEST_SCHEMA_V1
    internal const val MANIFEST_SCHEMA_V2 = UI_BUILDER_RUNTIME_MANIFEST_SCHEMA_V2
    internal const val RUNTIME_MANIFEST_NAME = UI_BUILDER_RUNTIME_MANIFEST_NAME_V1

    private val JSON = Json { ignoreUnknownKeys = false }

    internal fun load(inputs: Map<String, File>): ServeUiBuilderRuntimeAssets {
      val bundles = linkedMapOf<String, RuntimeBundle>()
      inputs.toSortedMap().forEach { (runtimeId, directory) ->
        require(isValidUiBuilderRuntimeIdV1(runtimeId)) {
          "UI-builder runtime id '$runtimeId' is unsafe or reserved"
        }
        val root = directory.toPath().toAbsolutePath().normalize()
        require(Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
          "UI-builder runtime '$runtimeId' is not a directory: ${directory.path}"
        }
        val bytes = readSnapshot(root)
        val manifestBytes =
          requireNotNull(bytes[RUNTIME_MANIFEST_NAME]) {
            "UI-builder runtime '$runtimeId' has no $RUNTIME_MANIFEST_NAME"
          }
        val manifest = decodeManifest(runtimeId, manifestBytes)
        require(manifest.runtimeId == runtimeId) {
          "UI-builder runtime directory '$runtimeId' does not match its manifest runtimeId"
        }
        val actualIntegrity = treeIntegrity(bytes - RUNTIME_MANIFEST_NAME)
        val issues = manifest.validate(bytes.keys, actualIntegrity)
        require(issues.isEmpty()) {
          "UI-builder runtime '$runtimeId' manifest is invalid: " +
            issues.joinToString { issue -> "${issue.field}:${issue.code}" }
        }
        bundles[runtimeId] =
          RuntimeBundle(
            bytes.mapValues { (_, content) ->
              val digest = sha256(content)
              Asset(content, "\"sha256-$digest\"")
            }
          )
      }
      return ServeUiBuilderRuntimeAssets(bundles)
    }

    /**
     * Verify and expand one catalog-delivered runtime into its generation staging tree.
     *
     * Nothing is written until the complete archive, manifest identity and tree digest have passed.
     * The caller then activates the generation directory atomically with its catalog metadata.
     */
    internal fun stageArchive(
      descriptor: UiBuilderRuntimeArtifactV1,
      archive: ByteArray,
      runtimeRoot: File,
      supportedProtocolVersions: Set<Int> = SUPPORTED_PROTOCOL_VERSIONS,
    ) {
      val descriptorIssues = descriptor.validateContract()
      require(descriptorIssues.isEmpty()) {
        "UI-builder runtime descriptor is invalid: " +
          descriptorIssues.joinToString { issue -> "${issue.field}:${issue.code}" }
      }
      require(descriptor.protocolVersion in supportedProtocolVersions) {
        "UI-builder runtime protocol ${descriptor.protocolVersion} is not supported"
      }
      val bytes = readArchive(archive)
      val manifestBytes =
        requireNotNull(bytes[RUNTIME_MANIFEST_NAME]) {
          "UI-builder runtime '${descriptor.runtimeId}' has no $RUNTIME_MANIFEST_NAME"
        }
      val manifest = decodeManifest(descriptor.runtimeId, manifestBytes)
      require(manifest.runtimeId == descriptor.runtimeId) {
        "UI-builder runtime descriptor id does not match its manifest"
      }
      require(manifest.protocolVersion == descriptor.protocolVersion) {
        "UI-builder runtime descriptor protocol does not match its manifest"
      }
      require(manifest.integritySha256 == descriptor.integritySha256) {
        "UI-builder runtime descriptor integrity does not match its manifest"
      }
      val actualIntegrity = treeIntegrity(bytes - RUNTIME_MANIFEST_NAME)
      val manifestIssues = manifest.validate(bytes.keys, actualIntegrity)
      require(manifestIssues.isEmpty()) {
        "UI-builder runtime '${descriptor.runtimeId}' manifest is invalid: " +
          manifestIssues.joinToString { issue -> "${issue.field}:${issue.code}" }
      }

      val target = File(runtimeRoot, descriptor.runtimeId)
      require(!target.exists()) {
        "UI-builder runtime target already exists: ${descriptor.runtimeId}"
      }
      bytes.forEach { (path, content) ->
        val file = File(target, path)
        require(file.canonicalFile.toPath().startsWith(target.canonicalFile.toPath())) {
          "UI-builder runtime contains an unsafe asset path: $path"
        }
        file.parentFile.mkdirs()
        file.writeBytes(content)
      }
    }

    /** Read one already-verified generation asset without mutating its immutable bytes. */
    internal fun assetFromDirectory(
      runtimeRoot: File,
      runtimeId: String,
      segments: List<String>,
    ): Asset? {
      if (!isValidUiBuilderRuntimeIdV1(runtimeId)) return null
      val relative =
        if (segments.isEmpty()) RUNTIME_MANIFEST_NAME
        else normalizeRelativePath(segments.joinToString("/")) ?: return null
      val root = File(runtimeRoot, runtimeId).canonicalFile
      val file = File(root, relative).canonicalFile
      if (
        !file.toPath().startsWith(root.toPath()) ||
          !file.isFile ||
          Files.isSymbolicLink(file.toPath())
      ) {
        return null
      }
      val bytes = file.readBytes()
      return Asset(bytes, "\"sha256-${sha256(bytes)}\"")
    }

    /** Canonical digest used by retained runtime manifests and their packaging tools. */
    internal fun treeIntegrity(assets: Map<String, ByteArray>): String {
      val digest = MessageDigest.getInstance("SHA-256")
      frameUiBuilderRuntimeTreeIntegrityV1(assets, digest::update)
      return digest.digest().toHex()
    }

    internal fun normalizeRelativePath(path: String): String? =
      normalizeUiBuilderRuntimeAssetPathV1(path)

    private data class DecodedRuntimeManifest(
      val runtimeId: String,
      val protocolVersion: Int,
      val integritySha256: String,
      val validate: (Set<String>, String?) -> List<UiBuilderRuntimeValidationIssueV1>,
    )

    private fun decodeManifest(runtimeId: String, bytes: ByteArray): DecodedRuntimeManifest =
      try {
        val document = JSON.parseToJsonElement(bytes.decodeToString()).jsonObject
        when (document["schema"]?.jsonPrimitive?.content) {
          UI_BUILDER_RUNTIME_MANIFEST_SCHEMA_V1 -> {
            val manifest =
              JSON.decodeFromJsonElement(UiBuilderRuntimeManifestV1.serializer(), document)
            DecodedRuntimeManifest(
              manifest.runtimeId,
              manifest.protocolVersion,
              manifest.integritySha256,
              manifest::validateContract,
            )
          }
          UI_BUILDER_RUNTIME_MANIFEST_SCHEMA_V2 -> {
            val manifest =
              JSON.decodeFromJsonElement(UiBuilderRuntimeManifestV2.serializer(), document)
            DecodedRuntimeManifest(
              manifest.runtimeId,
              manifest.protocolVersion,
              manifest.integritySha256,
              manifest::validateContract,
            )
          }
          else -> error("unsupported runtime manifest schema")
        }
      } catch (failure: Exception) {
        throw IllegalArgumentException(
          "UI-builder runtime '$runtimeId' has an invalid $RUNTIME_MANIFEST_NAME",
          failure,
        )
      }

    private fun readSnapshot(root: Path): Map<String, ByteArray> {
      val result = linkedMapOf<String, ByteArray>()
      Files.walk(root).use { paths ->
        paths.sorted().forEach { path ->
          if (path == root) return@forEach
          require(!Files.isSymbolicLink(path)) {
            "UI-builder runtime contains a symbolic link: ${root.relativize(path)}"
          }
          if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) return@forEach
          require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            "UI-builder runtime contains a non-regular asset: ${root.relativize(path)}"
          }
          val relative = root.relativize(path).joinToString("/") { it.toString() }
          require(normalizeRelativePath(relative) == relative) {
            "UI-builder runtime contains an unsafe asset path: $relative"
          }
          result[relative] = Files.readAllBytes(path)
        }
      }
      return result
    }

    private fun readArchive(archive: ByteArray): Map<String, ByteArray> {
      require(archive.size <= MAX_ARCHIVE_BYTES) { "UI-builder runtime archive exceeds the cap" }
      val result = linkedMapOf<String, ByteArray>()
      var total = 0L
      ZipInputStream(ByteArrayInputStream(archive)).use { zip ->
        var entry = zip.nextEntry
        while (entry != null) {
          if (!entry.isDirectory) {
            require(result.size < MAX_ARCHIVE_ENTRIES) {
              "UI-builder runtime archive has too many entries"
            }
            val path = entry.name.replace('\\', '/')
            require(normalizeRelativePath(path) == path) {
              "UI-builder runtime contains an unsafe asset path: $path"
            }
            require(path !in result) { "UI-builder runtime contains a duplicate asset: $path" }
            val content = zip.readBounded(MAX_ARCHIVE_ENTRY_BYTES)
            total += content.size
            require(total <= MAX_ARCHIVE_EXPANDED_BYTES) {
              "UI-builder runtime expanded bytes exceed the cap"
            }
            result[path] = content
          }
          zip.closeEntry()
          entry = zip.nextEntry
        }
      }
      return result
    }

    private fun ZipInputStream.readBounded(maxBytes: Long): ByteArray {
      val output = java.io.ByteArrayOutputStream()
      val buffer = ByteArray(64 * 1024)
      var total = 0L
      while (true) {
        val count = read(buffer)
        if (count < 0) break
        total += count
        require(total <= maxBytes) { "UI-builder runtime archive entry exceeds the cap" }
        output.write(buffer, 0, count)
      }
      return output.toByteArray()
    }

    private fun sha256(bytes: ByteArray): String =
      MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private const val MAX_ARCHIVE_BYTES = 64L * 1024 * 1024
    private const val MAX_ARCHIVE_ENTRIES = 512
    private const val MAX_ARCHIVE_ENTRY_BYTES = 128L * 1024 * 1024
    private const val MAX_ARCHIVE_EXPANDED_BYTES = 256L * 1024 * 1024
    private val SUPPORTED_PROTOCOL_VERSIONS = setOf(1, 2)
  }
}
