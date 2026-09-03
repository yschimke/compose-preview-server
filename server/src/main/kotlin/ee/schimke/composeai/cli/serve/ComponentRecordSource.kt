package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.discovery.ComponentRecordFile
import java.io.File
import kotlinx.serialization.json.Json

/**
 * Reads a bundle's `components.json` for the Compose export path, re-reading it when it changes.
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
class ComponentRecordSource(private val file: File?) {

  private data class Parsed(val identity: Identity?, val record: ComponentRecordFile?)

  private data class Identity(val length: Long, val lastModified: Long)

  private var last: Parsed? = null

  /** The parsed record, or null when there is no usable one. */
  fun record(): ComponentRecordFile? {
    val path = file ?: return null
    val identity = path.takeIf { it.isFile }?.let { Identity(it.length(), it.lastModified()) }
    last?.let { if (it.identity == identity) return it.record }
    val record =
      if (identity == null) {
        System.err.println("serve: UI-builder component record not readable at $path")
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
    last = Parsed(identity, record)
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
