package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.discovery.ComponentRecordFile
import java.io.File
import kotlinx.serialization.json.Json

/**
 * Reads a bundle's `components.json` for the Compose export path, per catalog, re-reading each one
 * when it changes.
 *
 * ## Per catalog, because a host serves several
 *
 * The packaged image runs `--ui-builder-catalogs m3-catalog,remote-m3`. One global record for both
 * would resolve every export against whichever record the host happened to be given: a component id
 * present in both catalogs would generate the *other* catalog's call site, and an id present in
 * neither would refuse a document that is perfectly valid against its own. Keyed by catalog system
 * id, an export either finds its own catalog's record or is told that catalog has none.
 *
 * ## What this still does not pin
 *
 * A record is **not revision-pinned**. A design pinned to an older catalog revision exports against
 * whatever `components.json` is on disk now, so regenerating the file changes what a pinned export
 * produces. Fixing that means storing a record per catalog *revision*, which needs a retention
 * story this option does not have — recorded here rather than left to be discovered.
 *
 * ## Why a source rather than a value
 *
 * The record is a build output. In a dev loop it is regenerated while the server keeps running, and
 * a host that parsed it once at startup would export against a catalog the developer has already
 * replaced — the export would look stale for no visible reason. So the file's identity is checked
 * on every call and the parse is reused only while it holds.
 *
 * The identity is `(length, lastModified)`. Not a digest: hashing the file on every export costs
 * more than it buys, and the failure mode a digest would catch — a rewrite that keeps both size and
 * timestamp — needs a deliberate `touch -r`. A path that no longer exists is null again rather than
 * the last good parse, because a caller asking "is there a record?" should get today's answer.
 *
 * ## Failures are null, once
 *
 * A missing file or unreadable JSON yields null, and the executor turns that into a refusal naming
 * the reason. It is reported to stderr **once per identity** rather than on every export: a broken
 * record is a startup-shaped problem, and a builder exporting in a loop would otherwise fill the
 * log with the same line.
 */
class ComponentRecordSource(private val files: Map<String, File>) {

  private data class Parsed(val identity: Identity?, val record: ComponentRecordFile?)

  private data class Identity(val length: Long, val lastModified: Long)

  private val last = mutableMapOf<String, Parsed>()

  /** Whether any catalog has a record configured — what the export capability is set from. */
  val configured: Boolean
    get() = files.isNotEmpty()

  /** The parsed record for [catalogSystemId], or null when there is no usable one. */
  fun record(catalogSystemId: String): ComponentRecordFile? {
    val path = files[catalogSystemId] ?: return null
    val identity = path.takeIf { it.isFile }?.let { Identity(it.length(), it.lastModified()) }
    last[catalogSystemId]?.let { if (it.identity == identity) return it.record }
    val record =
      if (identity == null) {
        System.err.println(
          "serve: UI-builder component record for $catalogSystemId not readable at $path"
        )
        null
      } else {
        runCatching { JSON.decodeFromString<ComponentRecordFile>(path.readText()) }
          .onFailure {
            System.err.println(
              "serve: UI-builder component record at $path is not readable: ${it.message}"
            )
          }
          .getOrNull()
      }
    last[catalogSystemId] = Parsed(identity, record)
    return record
  }

  private companion object {
    /**
     * Unknown keys are ignored so a record from a **newer** producer still parses. That is not
     * laxity: the record carries a `schemaVersion`, and `ScreenGenerator` refuses a version it does
     * not understand with a message saying so. Failing here instead would report a future catalog
     * as malformed JSON.
     */
    val JSON = Json { ignoreUnknownKeys = true }
  }
}
