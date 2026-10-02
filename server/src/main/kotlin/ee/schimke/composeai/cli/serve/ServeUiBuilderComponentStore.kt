package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.uibuilder.protocol.DesignDocumentV1
import ee.schimke.composeai.uibuilder.service.AuthenticatedUiBuilderActor
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The components this host's editors publish, kept on the host — the server half of the component
 * library, as the design service is the server half of a project's designs.
 *
 * ## Two homes, the way designs have two
 *
 * A design is worked on here and settles in the app's repository (`ui-builder/designs/`, read from
 * a checkout or a catalog's delivery branch by [ServeUiBuilderDesignLibrary]). A shared component
 * is the same loop one level down: [ServeUiBuilderComponentLibrary] already reads the settled half
 * from `ui-builder/components/`, and this is where one lives while it is still moving — published
 * from the editor with one press, no commit and no branch.
 *
 * ## Stored in the convention, so it is read by the same reader
 *
 * Under [root], one directory per catalog system, each holding exactly what a project commits:
 * ```text
 * <root>/<system>/ui-builder/components/index.json
 * <root>/<system>/ui-builder/components/<componentId>.json
 * ```
 *
 * So a host-held symbol reaches the palette, the symbol route and the drift report as one more
 * [ServeUiBuilderDesignLibrary.Source.Directory] coordinate — the same index parse, the same symbol
 * checks and the same digest — rather than through a second reader free to disagree with the first.
 * It also means moving a component into the repository is a copy: the file here is the file a
 * project would commit.
 *
 * ## What a write must satisfy
 *
 * The checks [ServeUiBuilderComponentLibrary.symbolOf] applies on every read — exactly one
 * declaration, under the id it is published as, a complete acyclic body, no nested placements — are
 * applied before anything is written, so a publish that would be dropped on its first read is
 * refused instead. Catalog membership is the caller's to check, because only the caller holds the
 * catalogs (the route asks the host's draft validator).
 *
 * And it is **referenced, not copied** on this side too: a design that imported a symbol recorded
 * its digest, so replacing one names the digest it replaces. A write against a digest that is no
 * longer current is a [PublishResult.Conflict] — somebody else published in between — never a
 * silent overwrite of their version.
 */
class ServeUiBuilderComponentStore(private val root: Path) {
  init {
    ServeOwnerOnlyFiles.createDirectories(root)
    require(Files.isDirectory(root)) { "UI-builder component store root is not a directory: $root" }
  }

  /** What one publish came to. */
  sealed interface PublishResult {
    data class Published(val symbol: ServeUiBuilderComponentLibrary.Symbol, val created: Boolean) :
      PublishResult

    /** The document is not a usable symbol; [reason] is a sentence for the person publishing. */
    data class Refused(val reason: String) : PublishResult

    /**
     * The library holds a different version than the caller thought: [currentDigest] is what it
     * holds now, null when the caller named a digest for a component that is no longer published.
     */
    data class Conflict(val currentDigest: String?, val reason: String) : PublishResult

    data class Failed(val reason: String) : PublishResult
  }

  /**
   * One coordinate per system this host holds published components for, in name order.
   *
   * Only systems with an index: a coordinate whose index is missing reads as *unreadable* to the
   * drift report, and a directory left behind by a failed first write is not a library that went
   * down.
   */
  fun coordinates(): List<ServeUiBuilderDesignLibrary.Coordinate> =
    Files.list(root)
      .use { children ->
        children
          .filter { SYSTEM.matches(it.fileName.toString()) }
          .filter { Files.isRegularFile(it.resolve(ServeUiBuilderComponentLibrary.INDEX_PATH)) }
          .map { it.fileName.toString() }
          .sorted()
          .toList()
      }
      .map(::coordinate)

  /** Where [system]'s host-held components are read from, whether or not any exist yet. */
  fun coordinate(system: String): ServeUiBuilderDesignLibrary.Coordinate =
    ServeUiBuilderDesignLibrary.Coordinate(
      system = system,
      source = ServeUiBuilderDesignLibrary.Source.Directory(root.resolve(system).toFile()),
    )

  /**
   * Publishes [document] as [system]'s [componentId], or says why not.
   *
   * [replacesDigest] null means *create*: refused when the id is already published. Non-null means
   * *replace that version*: refused unless it is the one held now. Serialised, because the index is
   * one file every publish rewrites.
   */
  @Synchronized
  fun publish(
    system: String,
    componentId: String,
    title: String,
    description: String?,
    document: DesignDocumentV1,
    replacesDigest: String?,
  ): PublishResult {
    if (!SYSTEM.matches(system)) return PublishResult.Refused("`$system` is not a catalog system")
    if (!COMPONENT_ID.matches(componentId)) {
      return PublishResult.Refused(
        "`$componentId` is not a component id: letters, digits, `.`, `_` and `-`, at most 64"
      )
    }
    if (document.catalogPin.systemId != system) {
      return PublishResult.Refused(
        "the component is drawn with ${document.catalogPin.systemId}, not $system"
      )
    }
    val cleanTitle = title.trim().ifEmpty { componentId }
    val cleanDescription = description?.trim()?.ifEmpty { null }
    if (cleanTitle.length > MAX_TEXT || (cleanDescription?.length ?: 0) > MAX_TEXT) {
      return PublishResult.Refused("a title or description is at most $MAX_TEXT characters")
    }

    // What the file will hold: the document as published, under the id it is published as, with
    // the declaration's own provenance dropped — a published symbol *is* the source, and keeping
    // the
    // design-side record in it would make the digest depend on which design published it.
    val published =
      document.copy(
        id = componentId,
        revision = 0,
        home = null,
        components =
          document.components.mapValues { (_, component) -> component.copy(source = null) },
      )
    val reasons = mutableListOf<String>()
    val checker = ServeUiBuilderComponentLibrary(fetch = { _, _ -> null }, onLog = reasons::add)
    val entry =
      ServeUiBuilderComponentLibrary.Entry(
        system = system,
        componentId = componentId,
        title = cleanTitle,
        description = cleanDescription,
        file = "$componentId.json",
      )
    val symbol =
      checker.symbolOf(entry, published)
        ?: return PublishResult.Refused(
          reasons.lastOrNull()?.removePrefix("serve: ") ?: "the component is not a usable symbol"
        )

    val coordinate = coordinate(system)
    val existing = checker.readIndex(coordinate)?.entries.orEmpty()
    val current =
      existing.firstOrNull { it.componentId == componentId }?.let { checker.symbol(coordinate, it) }
    when {
      replacesDigest == null && current != null ->
        return PublishResult.Conflict(
          current.digest,
          "$componentId is already published; publish an update to replace it",
        )
      replacesDigest != null && current == null ->
        return PublishResult.Conflict(null, "$componentId is no longer published")
      replacesDigest != null && current != null && current.digest != replacesDigest ->
        return PublishResult.Conflict(
          current.digest,
          "$componentId was published again since this design took it",
        )
    }

    val directory = root.resolve(system).resolve(ServeUiBuilderComponentLibrary.COMPONENTS_DIR)
    return try {
      ServeOwnerOnlyFiles.createDirectories(directory)
      writeAtomically(
        directory.resolve(entry.file),
        STORE_JSON.encodeToString(DesignDocumentV1.serializer(), published),
      )
      val entries = existing.filterNot { it.componentId == componentId } + entry
      writeAtomically(
        directory.resolve("index.json"),
        indexJson(entries.sortedBy { it.componentId }),
      )
      PublishResult.Published(symbol, created = current == null)
    } catch (_: IOException) {
      PublishResult.Failed("the component could not be written to disk")
    }
  }

  private fun indexJson(entries: List<ServeUiBuilderComponentLibrary.Entry>): String =
    STORE_JSON.encodeToString(
      JsonObject.serializer(),
      JsonObject(
        mapOf(
          "schema" to JsonPrimitive(ServeUiBuilderComponentLibrary.INDEX_SCHEMA),
          "components" to
            JsonArray(
              entries.map { entry ->
                JsonObject(
                  buildMap {
                    put("id", JsonPrimitive(entry.componentId))
                    put("title", JsonPrimitive(entry.title))
                    entry.description?.let { put("description", JsonPrimitive(it)) }
                    put("file", JsonPrimitive(entry.file))
                  }
                )
              }
            ),
        )
      ),
    )

  private fun writeAtomically(file: Path, text: String) {
    val temporary = Files.createTempFile(file.parent, "component", ".tmp")
    try {
      Files.writeString(temporary, text, StandardCharsets.UTF_8)
      Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING)
    } catch (failure: IOException) {
      Files.deleteIfExists(temporary)
      throw failure
    }
  }

  companion object {
    /** Where under the UI-builder state directory the host keeps published components. */
    const val DIRECTORY: String = "component-library"

    private const val MAX_TEXT = 200

    /** A system id becomes a directory name, so it is one flat name and never `.` or `..`. */
    private val SYSTEM = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")

    /** The rule the library's index reader applies, so nothing written here is refused on read. */
    private val COMPONENT_ID = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")

    private val STORE_JSON = Json {
      prettyPrint = true
      encodeDefaults = false
      explicitNulls = false
    }
  }
}

/**
 * Why the host's own service would refuse [document] as a design, as sentences — the second honest
 * rule, asked of the one thing that holds the catalogs.
 *
 * Only the refusals that stop it being *drawn*: the export's own findings are left out, because a
 * component the generator cannot yet write as Kotlin is still one a design can place, and the
 * importing design's export reports that where it applies.
 */
internal suspend fun componentPublishProblems(
  validator: UiBuilderDraftValidator,
  actorId: String,
  document: DesignDocumentV1,
): List<String> =
  validator
    .validate(AuthenticatedUiBuilderActor(actorId), document, operations = null)
    .filter { it.severity == SEVERITY_ERROR && it.source != SOURCE_EXPORT }
    .map { problem -> problem.nodeId?.let { "$it: ${problem.message}" } ?: problem.message }
