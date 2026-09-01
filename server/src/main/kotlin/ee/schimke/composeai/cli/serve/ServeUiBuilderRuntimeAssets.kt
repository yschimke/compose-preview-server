package ee.schimke.composeai.cli.serve

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
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
    internal const val MANIFEST_SCHEMA = "compose-ui-builder-runtime/v1"
    internal const val RUNTIME_MANIFEST_NAME = "runtime-manifest.json"

    private val JSON = Json { ignoreUnknownKeys = false }
    private val SAFE_RUNTIME_ID = Regex("[A-Za-z0-9._-]+")
    private val SHA256 = Regex("[a-f0-9]{64}")
    private val RESERVED_RUNTIME_IDS = setOf("current", "latest")

    internal fun load(inputs: Map<String, File>): ServeUiBuilderRuntimeAssets {
      val bundles = linkedMapOf<String, RuntimeBundle>()
      inputs.toSortedMap().forEach { (runtimeId, directory) ->
        require(runtimeId.matches(SAFE_RUNTIME_ID) && runtimeId !in RESERVED_RUNTIME_IDS) {
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
            JSON.parseToJsonElement(manifestBytes.decodeToString()).jsonObject
          } catch (failure: Exception) {
            throw IllegalArgumentException(
              "UI-builder runtime '$runtimeId' has an invalid $RUNTIME_MANIFEST_NAME",
              failure,
            )
          }
        val manifestFields =
          setOf("schema", "runtimeId", "protocolVersion", "entrypoint", "integritySha256")
        require(manifest.keys == manifestFields) {
          "UI-builder runtime '$runtimeId' manifest fields do not match $MANIFEST_SCHEMA"
        }
        require(
          manifest["schema"]?.jsonPrimitive?.takeIf { it.isString }?.content == MANIFEST_SCHEMA
        ) {
          "UI-builder runtime '$runtimeId' has an unsupported manifest schema"
        }
        require(
          manifest["runtimeId"]?.jsonPrimitive?.takeIf { it.isString }?.content == runtimeId
        ) {
          "UI-builder runtime directory '$runtimeId' does not match its manifest runtimeId"
        }
        require((manifest["protocolVersion"]?.jsonPrimitive?.intOrNull ?: 0) > 0) {
          "UI-builder runtime '$runtimeId' must declare a positive protocolVersion"
        }
        val entrypoint =
          manifest["entrypoint"]?.jsonPrimitive?.takeIf { it.isString }?.content.orEmpty()
        require(normalizeRelativePath(entrypoint) == entrypoint && entrypoint in bytes) {
          "UI-builder runtime '$runtimeId' has an unsafe or missing entrypoint"
        }
        val declaredIntegrity =
          manifest["integritySha256"]?.jsonPrimitive?.takeIf { it.isString }?.content.orEmpty()
        require(declaredIntegrity.matches(SHA256)) {
          "UI-builder runtime '$runtimeId' must declare a lowercase SHA-256 integrity digest"
        }
        val actualIntegrity = treeIntegrity(bytes - RUNTIME_MANIFEST_NAME)
        require(actualIntegrity == declaredIntegrity) {
          "UI-builder runtime '$runtimeId' integrity mismatch: expected $declaredIntegrity, " +
            "calculated $actualIntegrity"
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
      assets.toSortedMap().forEach { (path, bytes) ->
        require(normalizeRelativePath(path) == path && path != RUNTIME_MANIFEST_NAME) {
          "Unsafe runtime asset path '$path'"
        }
        digest.update(path.encodeToByteArray())
        digest.update(0)
        digest.update(bytes.size.toString().encodeToByteArray())
        digest.update(0)
        digest.update(bytes)
      }
      return digest.digest().toHex()
    }

    internal fun normalizeRelativePath(path: String): String? {
      if (path.isBlank() || path.startsWith('/') || '\\' in path || '\u0000' in path) return null
      val segments = path.split('/')
      if (segments.any { it.isBlank() || it == "." || it == ".." }) return null
      return segments.joinToString("/")
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

    private fun sha256(bytes: ByteArray): String =
      MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
  }
}
