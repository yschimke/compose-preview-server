package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.uibuilder.protocol.UI_BUILDER_RUNTIME_MANIFEST_NAME_V1
import ee.schimke.composeai.uibuilder.protocol.UI_BUILDER_RUNTIME_MANIFEST_SCHEMA_V1
import ee.schimke.composeai.uibuilder.protocol.UiBuilderRuntimeManifestV1
import ee.schimke.composeai.uibuilder.protocol.frameUiBuilderRuntimeTreeIntegrityV1
import ee.schimke.composeai.uibuilder.protocol.isValidUiBuilderRuntimeIdV1
import ee.schimke.composeai.uibuilder.protocol.normalizeUiBuilderRuntimeAssetPathV1
import ee.schimke.composeai.uibuilder.protocol.validateContract
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.security.MessageDigest
import kotlinx.serialization.json.Json

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
        val manifest =
          try {
            JSON.decodeFromString(
              UiBuilderRuntimeManifestV1.serializer(),
              manifestBytes.decodeToString(),
            )
          } catch (failure: Exception) {
            throw IllegalArgumentException(
              "UI-builder runtime '$runtimeId' has an invalid $RUNTIME_MANIFEST_NAME",
              failure,
            )
          }
        require(manifest.runtimeId == runtimeId) {
          "UI-builder runtime directory '$runtimeId' does not match its manifest runtimeId"
        }
        val actualIntegrity = treeIntegrity(bytes - RUNTIME_MANIFEST_NAME)
        val issues = manifest.validateContract(bytes.keys, actualIntegrity)
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

    /** Canonical digest used by retained runtime manifests and their packaging tools. */
    internal fun treeIntegrity(assets: Map<String, ByteArray>): String {
      val digest = MessageDigest.getInstance("SHA-256")
      frameUiBuilderRuntimeTreeIntegrityV1(assets, digest::update)
      return digest.digest().toHex()
    }

    internal fun normalizeRelativePath(path: String): String? =
      normalizeUiBuilderRuntimeAssetPathV1(path)

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

    private fun sha256(bytes: ByteArray): String =
      MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
  }
}
