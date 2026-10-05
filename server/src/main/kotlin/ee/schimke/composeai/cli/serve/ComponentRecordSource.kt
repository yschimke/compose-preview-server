package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.discovery.ComponentRecordFile
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.json.Json

/**
 * Reads each catalog's `components.json` for the Compose export path, re-reading it when it
 * changes.
 *
 * Keyed by catalog system id because a host serves several catalogs, and an id shared between them
 * must not export as another catalog's call site. An operator-named file (`--ui-builder-components
 * <catalog>=<components.json>`) wins; otherwise [served] resolves the served catalog's staged
 * record per call, since a refresh moves it to a new generation directory.
 *
 * [foundation] (the builder's own `layout/`, `shape/`, `asset/` vocabulary) is unioned onto every
 * record that exists; the catalog's own entries win collisions, and a catalog with no record
 * (Remote Compose, on purpose) stays [Lookup.Unconfigured].
 *
 * A record is not revision-pinned: a design pinned to an older catalog revision exports against the
 * file on disk now. The parse is reused while `(path, length, lastModified)` holds, a vanished file
 * reads as null again, and a bad file is reported to stderr once per identity.
 */
internal class ComponentRecordSource(
  private val files: Map<String, File>,
  /**
   * The builder's own `layout/`, `shape/` and `asset/` components, unioned onto every record this
   * source returns. Packaged rather than configured: it is the builder's vocabulary, not an
   * operator's choice. Null leaves every record exactly as its file states it.
   *
   * Declared BEFORE [served] so that stays the last parameter: every caller passes it as a trailing
   * lambda, and a parameter added after it silently re-binds that lambda to this one.
   */
  private val foundation: ComponentRecordFile? = FOUNDATION,
  /**
   * The served catalog's own record for a catalog id, or null where none is served. Consulted only
   * for a catalog [files] does not name.
   */
  private val served: (catalogSystemId: String) -> File? = { null },
) {

  private data class Parsed(val identity: Identity?, val lookup: Lookup)

  private data class Identity(val path: String, val length: Long, val lastModified: Long)

  /**
   * Concurrent, because `PersistentUiBuilderService` runs up to four exports at once on its own
   * `ui-builder-export-*` workers and every one of them reaches this cache. A plain map here loses
   * or corrupts entries under exactly the simultaneous exports the service is built to allow.
   *
   * Two exports racing on one cold key may both parse the file; that is deliberate and harmless —
   * the parse is pure, the result is equal, and holding a lock across a file read to prevent it
   * would serialise every export behind the slowest disk.
   */
  private val last = ConcurrentHashMap<String, Parsed>()

  /**
   * What this host has for [catalogSystemId].
   *
   * Three outcomes rather than a nullable record, because "no record was configured" and "the
   * record you configured will not load" are different things to be told. An export that collapses
   * them can only offer the caller advice for the first — pass `--ui-builder-components` — which is
   * useless to an operator who passed it and is looking at a typo in the path.
   */
  sealed interface Lookup {
    /** No path was configured for this catalog, and no served catalog supplies one. */
    data object Unconfigured : Lookup

    /**
     * A path was configured and did not yield a record. [reason] names the path and the failure.
     */
    data class Unusable(val reason: String) : Lookup

    data class Found(val record: ComponentRecordFile) : Lookup
  }

  /** Whether [catalogSystemId] has a record the operator named, as opposed to a served one. */
  fun isConfigured(catalogSystemId: String): Boolean = catalogSystemId in files

  /** The record for [catalogSystemId], or which of the two ways there isn't one. */
  fun record(catalogSystemId: String): Lookup {
    val path = files[catalogSystemId] ?: served(catalogSystemId) ?: return Lookup.Unconfigured
    val identity =
      path.takeIf { it.isFile }?.let { Identity(it.absolutePath, it.length(), it.lastModified()) }
    last[catalogSystemId]?.let { if (it.identity == identity) return it.lookup }
    val lookup =
      if (identity == null) {
        // Still logged as well as returned. The export names the failure to whoever asked for it;
        // the operator watching the process is a different reader with a different problem.
        System.err.println(
          "serve: UI-builder component record for $catalogSystemId not readable at $path"
        )
        Lookup.Unusable("no readable file at `$path`")
      } else {
        runCatching { JSON.decodeFromString<ComponentRecordFile>(path.readText()) }
          .fold(
            onSuccess = { Lookup.Found(it.withFoundation()) },
            onFailure = {
              System.err.println(
                "serve: UI-builder component record at $path is not readable: ${it.message}"
              )
              Lookup.Unusable("the file at `$path` did not parse as a component record")
            },
          )
      }
    last[catalogSystemId] = Parsed(identity, lookup)
    return lookup
  }

  /**
   * This record plus the foundation components it does not already carry.
   *
   * The receiver wins, and "already carries" is asked of the **component ids** as well as the
   * canonical id. The id is the load-bearing half: two entries claiming `layout/column` are two
   * components competing for one builder id, which `PublishedUiBuilderCatalog` resolves by record
   * order and reports as a collision — so a catalog that declares `Column` under a canonical id of
   * its own still keeps it, which is exactly the shape while the packaged `m3-catalog` record
   * carries its own copies of these eight.
   *
   * The header stays the catalog's: the merged file is still that catalog's record, with a
   * vocabulary nobody's catalog is expected to declare added to it.
   */
  private fun ComponentRecordFile.withFoundation(): ComponentRecordFile {
    val taken = components.map { it.canonicalId }.toSet()
    val claimed = components.flatMapTo(mutableSetOf()) { it.componentIds }
    val extra =
      foundation?.components.orEmpty().filterNot { candidate ->
        candidate.canonicalId in taken || candidate.componentIds.any { it in claimed }
      }
    return if (extra.isEmpty()) this else copy(components = components + extra)
  }

  private companion object {
    /**
     * Unknown keys are ignored so a record from a **newer** producer still parses. That is not
     * laxity: the record carries a `schemaVersion`, and `ScreenGenerator` refuses a version it does
     * not understand with a message saying so. Failing here instead would report a future catalog
     * as malformed JSON.
     */
    val JSON = Json { ignoreUnknownKeys = true }

    /** The packaged foundation record, or null where the resource is missing or unreadable. */
    val FOUNDATION: ComponentRecordFile? by
      lazy(LazyThreadSafetyMode.PUBLICATION) {
        val text =
          ComponentRecordSource::class
            .java
            .getResourceAsStream("/ui-builder/$FOUNDATION_RECORD_RESOURCE")
            ?.use { it.readBytes().decodeToString() }
        if (text == null) {
          System.err.println(
            "serve: packaged $FOUNDATION_RECORD_RESOURCE is missing — layout/, shape/ and asset/ " +
              "components will have no record and every export using one refuses by name"
          )
          return@lazy null
        }
        runCatching { JSON.decodeFromString<ComponentRecordFile>(text) }
          .onFailure {
            System.err.println(
              "serve: packaged $FOUNDATION_RECORD_RESOURCE did not parse: ${it.message}"
            )
          }
          .getOrNull()
      }

    const val FOUNDATION_RECORD_RESOURCE = "compose-foundation-components-v1.json"
  }
}
