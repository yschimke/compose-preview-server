package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.bundle.TrustStore
import ee.schimke.composeai.bundle.TrustedBranch
import ee.schimke.composeai.bundle.TrustedIdentity
import ee.schimke.composeai.bundle.TrustedKey
import ee.schimke.composeai.io.SystemFileSystem
import kotlinx.serialization.Serializable
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath

/**
 * The `producers.json` trust store as an editable document, on the same config volume as
 * `catalogs.json` so runtime-registered catalogs can be trusted without an image rebuild. Mirrors
 * [ServeCatalogsConfigFile]: staged writes, absent reads as empty, injected Okio [FileSystem].
 */
class ServeTrustStoreFile(
  private val path: Path,
  private val fileSystem: FileSystem = SystemFileSystem,
) {
  val displayPath: String
    get() = path.toString()

  fun exists(): Boolean = fileSystem.exists(path)

  /** Parse the file; an absent file is the empty fail-closed store. Throws on malformed JSON. */
  fun load(): TrustStore {
    if (!fileSystem.exists(path)) return TrustStore.EMPTY
    return TrustStore.parse(fileSystem.read(path) { readUtf8() })
  }

  /**
   * Write [store] via a temp file and [FileSystem.atomicMove]. A truncated trust store fails
   * closed, dropping every catalog to `unverified` on the next boot.
   */
  fun save(store: TrustStore) {
    val parent = path.parent
    parent?.let { fileSystem.createDirectories(it) }
    val tmp = if (parent != null) parent / "${path.name}.tmp" else "${path.name}.tmp".toPath()
    fileSystem.write(tmp) { writeUtf8(TrustStore.encode(store)) }
    fileSystem.atomicMove(tmp, path)
  }
}

/**
 * A trust store that can change while the server runs. Consumers ([ServeCatalogStore],
 * [ServeBundleStore]) call a `() -> TrustStore` per verification, so admin changes apply to the
 * next fetch or upload without a restart. Reads are lock-free via `@Volatile`; writers serialise in
 * [ServeTrustAdmin].
 */
class MutableTrustStore(
  initial: TrustStore = TrustStore.EMPTY,
  /** The backing document, if any; lets [get] also pick up direct hand-edits to producers.json. */
  private val source: ServeTrustStoreFile? = null,
  private val onLog: (String) -> Unit = { System.err.println(it) },
) {
  @Volatile private var current: TrustStore = initial

  /**
   * The trust store now, re-read from [source] on every call. Not mtime-gated (coarse timestamps
   * can miss edits); affordable because verifications are rare and accompany far costlier work.
   */
  fun get(): TrustStore {
    val file = source ?: return current
    // Only a present, parseable file replaces the store; a malformed, truncated or deleted file
    // keeps the last good one rather than un-trusting everything mid-flight. The admin API
    // separately refuses to write over an unreadable document.
    if (file.exists()) {
      runCatching { file.load() }
        .onSuccess { current = it }
        .onFailure { onLog("serve: ignoring unreadable ${file.displayPath}: ${it.message}") }
    }
    return current
  }

  fun set(store: TrustStore) {
    current = store
  }
}

/**
 * One producer entry on the admin API, flat and discriminated by `kind` across all three trust
 * bases, so request bodies are easy to write by hand and one route pair suffices.
 */
@Serializable
data class AdminTrustEntry(
  /** `branch`, `key`, or `oidc`. */
  val kind: String,
  /** `branch`: the `<owner>/<repo>` pattern. */
  val repo: String? = null,
  /** `branch`: the branch-name pattern; defaults to the [TrustedBranch] default (`*`). */
  val branch: String? = null,
  /** `key`: the pinned key's id. */
  val keyId: String? = null,
  /** `key`: PEM or base64 X.509 SPKI. */
  val publicKey: String? = null,
  /** `key`: an optional human label. */
  val name: String? = null,
  /** `oidc`: the workload-identity pattern. */
  val identity: String? = null,
)

/**
 * Add and remove trusted producers on a running server and persist the result, like
 * [ServeCatalogAdmin] for catalogs: validate, mutate the live store, write the file; a persistence
 * failure is a warning, since the change already serves.
 *
 * With `--allow-render-trusted`, a trusted branch's Compose is built and executed here, so the
 * admin token is effectively a code-execution credential: hence routes off without `--admin-token`,
 * a token separate from browsing, and [TrustStore.validateBranch] refusing match-everything
 * patterns.
 */
class ServeTrustAdmin(
  private val store: MutableTrustStore,
  /** The operator's producers.json; null ⇒ changes are runtime-only and don't survive a restart. */
  private val file: ServeTrustStoreFile?,
  /**
   * Called with the reduced store after trust is removed, so already-trusted catalogs (and their
   * live daemons) are retired now; otherwise they'd stay executable until their branch moved or the
   * box restarted.
   */
  private val onRevoke: (TrustStore) -> Unit = {},
  /**
   * Called with the store before and after trust is added, so catalogs already loaded as
   * `unverified` are re-verified (verdicts are baked in at load and unchanged SHAs skip reloads).
   * Matters for `--catalog-registry`, whose catalogs arrive before their trust. Both stores are
   * passed so the caller re-verifies only the delta.
   */
  private val onGrant: (before: TrustStore, updated: TrustStore) -> Unit = { _, _ -> },
  private val onLog: (String) -> Unit = { System.err.println(it) },
) {
  /**
   * Serialises the whole load-modify-save so concurrent adds can't lose updates, as in
   * [ServeCatalogAdmin].
   */
  private val lock = Any()

  /** The outcome of an admin mutation, mapped to an HTTP status by the caller. */
  sealed interface Result {
    /** Applied. [warning] flags a non-fatal persistence failure. */
    data class Ok(val summary: String, val warning: String? = null) : Result

    /** Malformed entry — a 400. */
    data class Invalid(val reason: String) : Result

    /** Already trusted (add) or not trusted (remove) — a 409 / 404. */
    data class Conflict(val reason: String) : Result
  }

  /** The producers currently trusted. */
  fun list(): TrustStore = store.get()

  /**
   * Trust [entry]. Repeating an existing entry is a conflict, not a no-op, so typos are visible.
   */
  fun add(entry: AdminTrustEntry): Result =
    when (entry.kind) {
      "branch" -> addBranch(entry)
      "key" -> addKey(entry)
      "oidc" -> addIdentity(entry)
      else -> Result.Invalid("unknown trust kind '${entry.kind}' (branch, key, or oidc)")
    }

  /** Stop trusting the producer [entry] identifies. */
  fun remove(entry: AdminTrustEntry): Result =
    when (entry.kind) {
      "branch" -> {
        val repo = entry.repo
        if (repo.isNullOrBlank()) Result.Invalid("branch removal needs a repo")
        else
          mutate("branch $repo@${entry.branch ?: "*"}") { current ->
            val match =
              current.branches.firstOrNull {
                it.repo == repo && (entry.branch == null || it.branch == entry.branch)
              } ?: return@mutate null
            current.copy(branches = current.branches - match)
          }
      }
      "key" -> {
        val keyId = entry.keyId
        if (keyId.isNullOrBlank()) Result.Invalid("key removal needs a keyId")
        else
          mutate("key $keyId") { current ->
            val match = current.keys.firstOrNull { it.keyId == keyId } ?: return@mutate null
            current.copy(keys = current.keys - match)
          }
      }
      "oidc" -> {
        val identity = entry.identity
        if (identity.isNullOrBlank()) Result.Invalid("oidc removal needs an identity")
        else
          mutate("oidc $identity") { current ->
            val match = current.oidc.firstOrNull { it.identity == identity } ?: return@mutate null
            current.copy(oidc = current.oidc - match)
          }
      }
      else -> Result.Invalid("unknown trust kind '${entry.kind}' (branch, key, or oidc)")
    }

  private fun addBranch(entry: AdminTrustEntry): Result {
    val repo = entry.repo
    if (repo.isNullOrBlank()) return Result.Invalid("branch entry needs a repo")
    val branch = TrustedBranch(repo = repo, branch = entry.branch ?: "*")
    TrustStore.validateBranch(branch)?.let {
      return Result.Invalid(it)
    }
    return mutate("branch ${branch.repo}@${branch.branch}") { current ->
      if (current.branches.any { it.repo == branch.repo && it.branch == branch.branch }) null
      else current.copy(branches = current.branches + branch)
    }
  }

  private fun addKey(entry: AdminTrustEntry): Result {
    val key =
      TrustedKey(
        keyId = entry.keyId.orEmpty(),
        publicKey = entry.publicKey.orEmpty(),
        name = entry.name,
      )
    TrustStore.validateKey(key)?.let {
      return Result.Invalid(it)
    }
    return mutate("key ${key.keyId}") { current ->
      if (current.keys.any { it.keyId == key.keyId }) null
      else current.copy(keys = current.keys + key)
    }
  }

  private fun addIdentity(entry: AdminTrustEntry): Result {
    val identity = TrustedIdentity(entry.identity.orEmpty())
    TrustStore.validateIdentity(identity)?.let {
      return Result.Invalid(it)
    }
    return mutate("oidc ${identity.identity}") { current ->
      if (current.oidc.any { it.identity == identity.identity }) null
      else current.copy(oidc = current.oidc + identity)
    }
  }

  /**
   * Apply [edit] under [lock] and publish. [edit] returns null for a no-op, reported as
   * [Result.Conflict]. Re-reads the file first so hand-edits aren't clobbered
   * ([ServeCatalogAdmin.persist]).
   */
  private fun mutate(summary: String, edit: (TrustStore) -> TrustStore?): Result =
    synchronized(lock) {
      val target = file
      // An unreadable document aborts the mutation rather than overwriting it with stale state plus
      // this edit; an absent file is fine and reads as empty.
      val current =
        if (target == null) store.get()
        else
          runCatching { target.load() }
            .getOrElse { e ->
              onLog("serve: refusing to rewrite unreadable ${target.displayPath}: ${e.message}")
              return Result.Invalid(
                "trust store ${target.displayPath} is present but unreadable " +
                  "(${e.message ?: "parse failed"}); fix or remove it before editing trust"
              )
            }
      val updated = edit(current) ?: return Result.Conflict("$summary: no change")
      store.set(updated)
      val warning =
        if (target == null) "not persisted: no trust store file is configured"
        else
          runCatching { target.save(updated) }
            .fold({ null }) { e ->
              onLog("serve: could not update ${target.displayPath}: ${e.message}")
              "not persisted: ${e.message ?: "write failed"}"
            }
      onLog("serve: trust $summary updated via admin API")
      // Retire anything trusted only under the old store now, inside the lock so a concurrent add
      // can't re-trust mid-teardown. Failures are logged, since the change already took effect.
      if (isReduction(current, updated)) {
        runCatching { onRevoke(updated) }
          .onFailure { onLog("serve: revocation cleanup after $summary failed: ${it.message}") }
      } else {
        // The grant direction: re-verify catalogs loaded under the narrower store. Failures are
        // logged for the same reason.
        runCatching { onGrant(current, updated) }
          .onFailure { onLog("serve: re-verification after $summary failed: ${it.message}") }
      }
      Result.Ok(summary, warning)
    }

  /** True when [updated] trusts strictly less than [before] — i.e. this was a revocation. */
  private fun isReduction(before: TrustStore, updated: TrustStore): Boolean =
    updated.branches.size < before.branches.size ||
      updated.keys.size < before.keys.size ||
      updated.oidc.size < before.oidc.size
}
