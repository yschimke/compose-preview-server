package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.uibuilder.protocol.AssetBindingV1
import ee.schimke.composeai.uibuilder.protocol.CatalogAssetSourceV1
import ee.schimke.composeai.uibuilder.protocol.DesignDocumentV1
import ee.schimke.composeai.uibuilder.protocol.EmbeddedAssetSourceV1
import ee.schimke.composeai.uibuilder.protocol.StringValueV1
import ee.schimke.composeai.uibuilder.protocol.UploadedAssetSourceV1
import ee.schimke.composeai.uibuilder.service.UiBuilderAssetStore
import ee.schimke.watchface.wff.WffElement
import ee.schimke.watchface.wff.WffXmlParser
import java.util.Base64

/**
 * A watch-face design's pinned assets (`ui_builder_put_asset`) as the drawables its WFF refers to,
 * shared by every lane that packages a design: the `bundle` export
 * ([WatchFaceBundleExportExecutor]), the dwf-preview session and the Watch Face Push build.
 *
 * An asset pinned under `key` is `drawable/<key>.<ext>` (relative to `res/`, as [DwfSource.assets]
 * holds it): a WFF `resource="key"` attribute names a drawable by its file name without the
 * extension, so the asset key is the resource name, and must be one Android accepts.
 */
internal object WatchFaceDrawables {
  /** A `resource` attribute naming a bitmap the face does not carry. */
  const val MISSING_RESOURCE = "WFF_BUNDLE_MISSING_RESOURCE"

  /** A pinned asset whose bytes this host cannot read, or whose key is no drawable name. */
  const val UNUSABLE_ASSET = "WFF_BUNDLE_UNUSABLE_ASSET"

  /** The drawable `watch_face_info.xml` names as the face's preview. */
  const val PREVIEW = "preview"

  /** An Android resource file name, which is what a WFF `resource` attribute refers to. */
  private val RESOURCE_NAME = Regex("[a-z][a-z0-9_]*")

  private val EXTENSIONS =
    mapOf(
      "image/png" to "png",
      "image/jpeg" to "jpg",
      "image/webp" to "webp",
      "image/gif" to "gif",
    )

  /**
   * One finding about the drawables. [required] is whether the face cannot be packaged without
   * fixing it (the asset is referenced, or is the preview), as opposed to an asset left out.
   */
  data class Problem(
    val code: String,
    val message: String,
    val required: Boolean,
    val nodeId: String? = null,
  )

  /** [files] keyed `drawable/<key>.<ext>`; [problems] are the assets that could not be used. */
  class Pinned(val files: Map<String, ByteArray>, val problems: List<Problem>)

  /**
   * [document]'s pinned assets as drawables, their bytes read from [assets] (uploads) or the
   * binding itself (embedded). [referenced] are the resource names the face draws; an unusable
   * asset among them, or the `preview`, is a [Problem.required] finding, any other a note that it
   * is left out.
   */
  fun pinned(
    document: DesignDocumentV1,
    assets: UiBuilderAssetStore?,
    referenced: Set<String>,
  ): Pinned {
    val files = LinkedHashMap<String, ByteArray>()
    val problems = mutableListOf<Problem>()
    for ((key, binding) in document.assets.toSortedMap()) {
      val required = key in referenced || key == PREVIEW
      fun unusable(why: String) {
        problems +=
          Problem(
            UNUSABLE_ASSET,
            "assets.$key: $why" + if (required) "" else "; it is left out of the package",
            required,
          )
      }
      if (!RESOURCE_NAME.matches(key)) {
        unusable(
          "an asset key becomes a drawable name, which is lowercase letters, digits and " +
            "underscores starting with a letter",
        )
        continue
      }
      val extension = EXTENSIONS[binding.mediaType.substringBefore(';').trim().lowercase()]
      if (extension == null) {
        unusable("${binding.mediaType} is not a bitmap a watch face can draw")
        continue
      }
      val bytes = bytesOf(binding, assets)
      if (bytes == null) {
        unusable("the bytes behind this asset are not on this host")
        continue
      }
      files["drawable/$key.$extension"] = bytes
    }
    return Pinned(files, problems)
  }

  /** The keys of [document]'s assets that can name a drawable, whatever their bytes. */
  fun pinnedNames(document: DesignDocumentV1): Set<String> =
    document.assets.keys.filter(RESOURCE_NAME::matches).toSet()

  /**
   * The bitmap names [xml]'s `resource` attributes refer to, in document order. An expression (a
   * complication's `[COMPLICATION.SMALL_IMAGE]`) is the watch's, not a file a package supplies.
   * Empty when [xml] does not parse: the packager reports that in its own words.
   */
  fun referenced(xml: String): List<String> {
    val root = WffXmlParser.parseOrNull(xml)?.root ?: return emptyList()
    val out = LinkedHashSet<String>()
    fun walk(element: WffElement) {
      element
        .attr("resource")
        ?.trim()
        ?.takeIf { it.isNotEmpty() && !it.startsWith("[") }
        ?.let(out::add)
      element.childElements.forEach(::walk)
    }
    walk(root)
    return out.toList()
  }

  /** The resource name of a package path such as `drawable-hdpi/logo.png`, or null. */
  fun drawableName(path: String): String? {
    val normalized = DwfSource.normalizedAssetPath(path)
    val directory = normalized.substringBefore('/', "")
    if (directory != "drawable" && !directory.startsWith("drawable-")) return null
    return normalized.substringAfter('/').substringBefore('.').takeIf { it.isNotEmpty() }
  }

  /**
   * One [MISSING_RESOURCE] finding per bitmap [xml] names that [available] (resource names) does
   * not hold, with the node of [document] that names it when there is one.
   */
  fun missing(xml: String, available: Set<String>, document: DesignDocumentV1?): List<Problem> =
    referenced(xml)
      .filterNot { it in available }
      .map { name ->
        Problem(
          MISSING_RESOURCE,
          "resource \"$name\": the face draws a bitmap named $name, and the design pins no " +
            "asset under that key; upload one with ui_builder_put_asset (assetKey \"$name\")",
          required = true,
          nodeId = document?.let { nodeNaming(it, name) },
        )
      }

  /**
   * [pinned] drawables under [requested] (paths relative to `res/`). The request wins for the same
   * resource: the same directory, configuration qualifiers included, and the same name whatever the
   * extension, since `drawable/logo.png` and `drawable/logo.webp` are one resource to Android and
   * packaging both is a duplicate-resource error. A pinned file under another qualifier
   * (`drawable-hdpi/logo.png`) is a different variant and is kept. The result is still checked by
   * [DwfSource.problem] wherever it is packaged.
   */
  fun merged(
    pinned: Map<String, ByteArray>,
    requested: Map<String, ByteArray>,
  ): Map<String, ByteArray> {
    val requestedResources = requested.keys.map(::resourceIdentity).toSet()
    val out = LinkedHashMap<String, ByteArray>()
    for ((path, bytes) in pinned) {
      if (resourceIdentity(path) !in requestedResources) out[path] = bytes
    }
    out.putAll(requested)
    return out
  }

  /** A package path as Android identifies the resource: its directory and extensionless name. */
  private fun resourceIdentity(path: String): String {
    val normalized = DwfSource.normalizedAssetPath(path)
    val directory = normalized.substringBeforeLast('/', "")
    val name = normalized.substringAfterLast('/').substringBefore('.')
    return "$directory/$name"
  }

  private fun nodeNaming(document: DesignDocumentV1, resource: String): String? =
    document.nodes.entries
      .firstOrNull { (_, node) ->
        (node.properties["resource"] as? StringValueV1)?.value?.trim() == resource
      }
      ?.key

  private fun bytesOf(binding: AssetBindingV1, assets: UiBuilderAssetStore?): ByteArray? =
    when (val source = binding.source) {
      is UploadedAssetSourceV1 -> assets?.read(source.storageKey)
      is EmbeddedAssetSourceV1 ->
        runCatching { Base64.getDecoder().decode(source.base64) }.getOrNull()
      is CatalogAssetSourceV1 -> null
      else -> null
    }
}

/** [source] as it is packaged; [missing] are the reasons it cannot be, empty when it can. */
internal class PackagedWatchFace(val source: DwfSource, val missing: List<String>)

/**
 * [source] carrying [document]'s pinned drawables ([WatchFaceDrawables.pinned]) under its own
 * assets and [requested] (both of which win for the same path), and the bitmaps its XML names that
 * none of them supplies. The one place the dwf-preview session and the Watch Face Push build turn a
 * design's assets into package files, so the two cannot disagree with the `bundle` export.
 */
internal fun packagedWithPinnedDrawables(
  document: DesignDocumentV1,
  source: DwfSource,
  requested: Map<String, ByteArray>,
  assets: UiBuilderAssetStore?,
): PackagedWatchFace {
  val referenced = WatchFaceDrawables.referenced(source.wffXml).toSet()
  val pinned = WatchFaceDrawables.pinned(document, assets, referenced)
  val files = WatchFaceDrawables.merged(pinned.files, source.assets + requested)
  val available = files.keys.mapNotNull(WatchFaceDrawables::drawableName).toSet()
  val missing = WatchFaceDrawables.missing(source.wffXml, available, document)
  // Why a referenced asset the design does pin could not be used, beside the name it leaves bare.
  val unusable =
    if (missing.isEmpty()) emptyList()
    else pinned.problems.filter { it.required && it.code == WatchFaceDrawables.UNUSABLE_ASSET }
  return PackagedWatchFace(
    DwfSource(
      wffXml = source.wffXml,
      wffVersion = source.wffVersion,
      assets = files,
      label = source.label,
    ),
    (missing + unusable).map { it.message },
  )
}
