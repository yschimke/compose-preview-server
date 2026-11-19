package ee.schimke.composeai.cli.serve

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * The Watch Face Push site API (`/api/watchface-push/v1`): the contract the Wear OS push app in
 * yschimke/watchface-tools (`:push-app`, `docs/PUSH_API.md`) talks to, so a watch can list the
 * `wff-watchface` designs drawn in this server's UI builder and install them. This file holds what
 * the routes ([installWatchFacePushRoutes]) stand on: the package naming rule, the marketplace
 * signing key, the push build, the durable state, and the device-code linking.
 *
 * The API is documented in `docs/serve/WATCHFACE_PUSH.md`.
 */
internal object WatchFacePushNaming {
  /** The push app's applicationId; the Watch Face Push API rejects any other prefix. */
  const val MARKETPLACE_PACKAGE = "ee.schimke.watchface.push"

  const val PACKAGE_PREFIX = "$MARKETPLACE_PACKAGE.watchfacepush."

  /** Longest design id kept as-is; anything longer is shortened and given a hash suffix. */
  private const val MAX_PLAIN = 48

  private val PLAIN = Regex("[A-Za-z][A-Za-z0-9_]*")

  /**
   * The shape of a hashed name (`…_` and ten hex characters). A plain id of this shape is hashed
   * too, so the plain and hashed names can never meet: `my-face` hashes to `my_face_0a48625fa2`,
   * and a design literally called `my_face_0a48625fa2` gets a hash of its own.
   */
  private val HASHED_SHAPE = Regex(".*_[0-9a-f]{10}")
  private val FACE_NAME = Regex("[A-Za-z0-9_]+")

  /**
   * Words a Java package segment cannot be. The packager does not check them, but a package that
   * `aapt2` or the platform later refuses is worse than a hash suffix now.
   */
  private val RESERVED =
    setOf(
      "abstract",
      "assert",
      "boolean",
      "break",
      "byte",
      "case",
      "catch",
      "char",
      "class",
      "const",
      "continue",
      "default",
      "do",
      "double",
      "else",
      "enum",
      "extends",
      "false",
      "final",
      "finally",
      "float",
      "for",
      "goto",
      "if",
      "implements",
      "import",
      "instanceof",
      "int",
      "interface",
      "long",
      "native",
      "new",
      "null",
      "package",
      "private",
      "protected",
      "public",
      "return",
      "short",
      "static",
      "strictfp",
      "super",
      "switch",
      "synchronized",
      "this",
      "throw",
      "throws",
      "transient",
      "true",
      "try",
      "void",
      "volatile",
      "while",
    )

  /**
   * The `faceName` for [designId]: `[A-Za-z0-9_]+`, starting with a letter so the package is a
   * valid one, and one per design id.
   *
   * A design id that already is such a name (and is not too long or a Java keyword) is used as-is,
   * so `aurora` installs as `….watchfacepush.aurora`. Anything else is sanitised — every character
   * outside `[A-Za-z0-9_]` becomes `_`, a leading non-letter gains `d_` — and given `_` plus ten
   * hex characters of the id's SHA-256, so `my-face` and `my_face` never share a package (and so
   * never replace each other's install on a watch).
   */
  fun faceName(designId: String): String {
    require(designId.isNotEmpty()) { "a design id is required" }
    if (
      PLAIN.matches(designId) &&
        designId.length <= MAX_PLAIN &&
        designId.lowercase() !in RESERVED &&
        !HASHED_SHAPE.matches(designId)
    ) {
      return designId
    }
    val sanitized =
      designId
        .map { if (it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '_') it else '_' }
        .joinToString("")
        .take(MAX_PLAIN)
        .trimEnd('_')
    val head =
      if (sanitized.firstOrNull()?.let { it in 'a'..'z' || it in 'A'..'Z' } == true) sanitized
      else "d_$sanitized".trimEnd('_')
    return head + "_" + sha256Hex(designId.toByteArray(StandardCharsets.UTF_8)).take(10)
  }

  fun packageName(faceName: String): String = PACKAGE_PREFIX + faceName

  /** Whether [packageName] is `<marketplace>.watchfacepush.<faceName>`, as the app checks. */
  fun followsRule(packageName: String): Boolean =
    packageName.startsWith(PACKAGE_PREFIX) &&
      FACE_NAME.matches(packageName.removePrefix(PACKAGE_PREFIX))
}

internal fun sha256Hex(bytes: ByteArray): String =
  MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

private fun sha256Hex(file: File): String {
  val digest = MessageDigest.getInstance("SHA-256")
  file.inputStream().use { input ->
    val buffer = ByteArray(64 * 1024)
    while (true) {
      val n = input.read(buffer)
      if (n < 0) break
      digest.update(buffer, 0, n)
    }
  }
  return digest.digest().joinToString("") { "%02x".format(it) }
}

/**
 * The marketplace signing key every push APK is signed with.
 *
 * Android installs an update only when it is signed by the certificate that signed the installed
 * APK, so a push marketplace signs **every** face with one long-lived key. It is therefore read
 * from the operator's configuration and never generated: a throwaway key would make every face
 * installed from this server un-updatable the next time the container is rebuilt. With no key
 * configured the push API stays up and says so ([Unavailable]); nothing is signed.
 *
 * Read from the environment with the names `wff-packager --keystore-from-env` itself uses, each
 * also as `NAME_FILE` ([ServeSecretEnv]):
 *
 * | Variable                        |                                                              |
 * |---------------------------------|--------------------------------------------------------------|
 * | `WFF_SIGNING_KEYSTORE_PATH`     | path to the PKCS12 keystore file, or                         |
 * | `WFF_SIGNING_KEYSTORE_B64`      | the keystore itself, base64                                  |
 * | `WFF_SIGNING_KEYSTORE_PASSWORD` | its store password (required)                                |
 * | `WFF_SIGNING_KEY_ALIAS`         | optional; defaults to the only key entry                     |
 * | `WFF_SIGNING_KEY_PASSWORD`      | optional; defaults to the store password                     |
 * | `WFF_SIGNING_CERT_SHA256`       | optional pin: refuse a key whose certificate is not this one |
 *
 * The keystore is opened here at startup, so a wrong password or pin is reported once, by name,
 * rather than on every build; the packager is handed exactly these variables and nothing else
 * signing-related ([WffPackagerProcess]).
 */
internal sealed interface WatchFacePushSigning {
  /** [environment] is what the packager's `--keystore-from-env` reads; never logged. */
  class Configured(
    val environment: Map<String, String>,
    val certificateSha256: String,
  ) : WatchFacePushSigning

  data class Unavailable(val reason: String) : WatchFacePushSigning

  companion object {
    const val KEYSTORE_PATH = "WFF_SIGNING_KEYSTORE_PATH"
    const val KEYSTORE_B64 = "WFF_SIGNING_KEYSTORE_B64"
    const val KEYSTORE_PASSWORD = "WFF_SIGNING_KEYSTORE_PASSWORD"
    const val KEY_ALIAS = "WFF_SIGNING_KEY_ALIAS"
    const val KEY_PASSWORD = "WFF_SIGNING_KEY_PASSWORD"
    const val CERT_SHA256 = "WFF_SIGNING_CERT_SHA256"

    fun fromEnvironment(
      env: Map<String, String> = System.getenv(),
      warn: (String) -> Unit = System.err::println,
    ): WatchFacePushSigning {
      val path = env[KEYSTORE_PATH]?.trim()?.takeIf { it.isNotEmpty() }
      val encoded = ServeSecretEnv.read(KEYSTORE_B64, env, warn)
      if (path == null && encoded == null) {
        return Unavailable(
          "no marketplace signing key is configured on this server: set $KEYSTORE_PATH (a " +
            "PKCS12 file) or $KEYSTORE_B64, and $KEYSTORE_PASSWORD",
        )
      }
      val keystore =
        try {
          if (path != null) {
            if (encoded != null)
              warn("serve: both $KEYSTORE_PATH and $KEYSTORE_B64 are set; using the file")
            File(path).readBytes()
          } else {
            Base64.getMimeDecoder().decode(encoded)
          }
        } catch (e: IOException) {
          return Unavailable("$KEYSTORE_PATH names $path, which could not be read")
        } catch (e: IllegalArgumentException) {
          return Unavailable("$KEYSTORE_B64 is not valid base64")
        }
      val password =
        ServeSecretEnv.read(KEYSTORE_PASSWORD, env, warn)
          ?: return Unavailable("$KEYSTORE_PASSWORD is required with the signing keystore")
      return load(
        keystore,
        password,
        alias = env[KEY_ALIAS]?.trim()?.takeIf { it.isNotEmpty() },
        keyPassword = ServeSecretEnv.read(KEY_PASSWORD, env, warn),
        pin = env[CERT_SHA256]?.trim()?.takeIf { it.isNotEmpty() },
      )
    }

    /** Open [keystore] and check its key against [pin]; public for tests. */
    fun load(
      keystore: ByteArray,
      password: String,
      alias: String? = null,
      keyPassword: String? = null,
      pin: String? = null,
    ): WatchFacePushSigning {
      val store =
        try {
          KeyStore.getInstance("PKCS12").apply {
            load(ByteArrayInputStream(keystore), password.toCharArray())
          }
        } catch (e: Exception) {
          return Unavailable(
            "the signing keystore could not be opened (${e.javaClass.simpleName}); check that it " +
              "is PKCS12 and that $KEYSTORE_PASSWORD is its password",
          )
        }
      val keyAliases = store.aliases().toList().filter(store::isKeyEntry)
      val chosen =
        alias
          ?: keyAliases.singleOrNull()
          ?: return Unavailable(
            "the signing keystore holds ${keyAliases.size} keys; set $KEY_ALIAS to choose one",
          )
      if (chosen !in keyAliases) return Unavailable("the signing keystore has no key `$chosen`")
      try {
        store.getKey(chosen, (keyPassword ?: password).toCharArray())
          ?: return Unavailable("the signing keystore has no private key `$chosen`")
      } catch (e: Exception) {
        return Unavailable("the signing key could not be read; check $KEY_PASSWORD")
      }
      val certificate =
        store.getCertificate(chosen)?.encoded
          ?: return Unavailable("the signing key `$chosen` has no certificate")
      val sha = sha256Hex(certificate)
      val wanted = pin?.replace(":", "")?.lowercase()
      if (wanted != null && wanted != sha) {
        return Unavailable(
          "the signing key's certificate SHA-256 is $sha, not the pinned $CERT_SHA256; a face " +
            "signed with it could not update one installed from this server",
        )
      }
      val environment = buildMap {
        put(KEYSTORE_B64, Base64.getEncoder().encodeToString(keystore))
        put(KEYSTORE_PASSWORD, password)
        put(KEY_ALIAS, chosen)
        keyPassword?.let { put(KEY_PASSWORD, it) }
        put(CERT_SHA256, sha)
      }
      return Configured(environment, sha)
    }
  }
}

/** A push build: a bundle in, a signed APK at `out`, the validation token out. */
internal fun interface WatchFacePushPackager {
  /**
   * Build [files] as `<marketplace>.watchfacepush.<faceName>` with [versionCode] into [out].
   *
   * @return the Watch Face Push validation token for the APK.
   * @throws DwfPackagingFailed when the packager or Google's validator refuses the face.
   */
  fun pack(files: Map<String, ByteArray>, faceName: String, versionCode: Int, out: File): String
}

/**
 * The push build through `wff-packager`'s CLI in its own JVM ([WffPackagerProcess]):
 * `--push-marketplace ee.schimke.watchface.push --face-name <faceName>` is
 * `PackageTarget.WatchFacePush(marketplacePackage, faceName)`, `--keystore-from-env` signs with
 * [signing], and `--token-out` hands back the validator's token.
 */
internal class WatchFacePushSubprocessPackager(
  private val libDir: File,
  private val sdk: DwfAndroidSdk,
  private val home: File,
  private val signing: WatchFacePushSigning.Configured,
  private val java: String = File(System.getProperty("java.home"), "bin/java").path,
  private val timeoutSeconds: Long = 180,
) : WatchFacePushPackager {
  override fun pack(
    files: Map<String, ByteArray>,
    faceName: String,
    versionCode: Int,
    out: File,
  ): String =
    WffPackagerProcess(libDir, sdk, home, java, timeoutSeconds).pack(
      files,
      arguments = { work ->
        listOf(
          "--out",
          out.path,
          "--version-code",
          versionCode.toString(),
          "--push-marketplace",
          WatchFacePushNaming.MARKETPLACE_PACKAGE,
          "--face-name",
          faceName,
          "--token-out",
          File(work, TOKEN_FILE).path,
          "--keystore-from-env",
          "true",
        )
      },
      environment = signing.environment,
    ) { work ->
      File(work, TOKEN_FILE).takeIf { it.isFile }?.readText()?.trim()?.takeIf { it.isNotEmpty() }
        ?: throw DwfPackagingFailed(
          listOf("error[push]: the packager issued no Watch Face Push validation token"),
        )
    }

  private companion object {
    const val TOKEN_FILE = "validation-token.txt"
  }
}

/** Whether push APKs can be built here, and with what. */
internal sealed interface WatchFacePushPackaging {
  class Ready(val packager: WatchFacePushPackager, val signerCertificateSha256: String?) :
    WatchFacePushPackaging

  data class Unavailable(val code: String, val reason: String) : WatchFacePushPackaging

  companion object {
    const val SIGNING_KEY_MISSING = "signing_key_unavailable"
    const val ANDROID_SDK_MISSING = "android_sdk_missing"
    const val PACKAGER_MISSING = "packager_missing"

    /**
     * The production answer, re-read per call so an SDK installed while the server runs is found.
     * The signing key is read once, at startup: the environment does not change under a process.
     */
    fun production(
      signing: WatchFacePushSigning,
      home: File,
      sdk: () -> DwfAndroidSdk? = { DwfAndroidSdk.locate() },
      libDir: () -> File? = { locateWffPackagerDir() },
    ): () -> WatchFacePushPackaging = {
      when {
        signing is WatchFacePushSigning.Unavailable ->
          Unavailable(SIGNING_KEY_MISSING, signing.reason)
        else -> {
          val located = sdk()
          val lib = libDir()
          when {
            located == null ->
              Unavailable(
                ANDROID_SDK_MISSING,
                "building a watch face needs an Android SDK on this server: " +
                  DwfAndroidSdk.INSTALL_HINT,
              )
            lib == null ->
              Unavailable(
                PACKAGER_MISSING,
                "this install has no lib-wff-packager/ (the watch-face packager); it ships in " +
                  "the server distribution",
              )
            else -> {
              val configured = signing as WatchFacePushSigning.Configured
              Ready(
                WatchFacePushSubprocessPackager(lib, located, home, configured),
                configured.certificateSha256,
              )
            }
          }
        }
      }
    }
  }
}

/**
 * What the push API keeps on disk, under the UI builder's state directory (`watchface-push/`):
 * ```text
 * devices.json                       linked watches: account, name, SHA-256 of the token
 * publications/<faceName>.json       one per design ever published to watches
 * apks/<faceName>-v<n>-<digest>.apk  built APKs, with a .json beside each
 * preview.key                        HMAC key for the signed preview URLs
 * installs.json                      the latest install report per linked watch and design
 * ```
 *
 * A device token is never stored, only its SHA-256, so a copy of this directory cannot be used to
 * read anyone's designs. A publication is never deleted, only marked unpublished, because its
 * `version` must keep increasing: a face re-published after an unpublish must still be a higher
 * `versionCode` than the one already on somebody's watch.
 */
internal class WatchFacePushStore(
  private val root: Path,
  private val clock: () -> Long = System::currentTimeMillis,
) {
  @Serializable
  data class Device(
    val id: String,
    val accountActorId: String,
    val deviceName: String,
    val wearOsApi: Int? = null,
    val tokenSha256: String,
    val createdAtEpochMillis: Long,
    val lastUsedEpochMillis: Long? = null,
  )

  @Serializable private data class Devices(val devices: List<Device> = emptyList())

  /**
   * What a linked watch reported after installing [designId] at [version]: the latest one per
   * device and design ([recordInstall]), kept for the design's owner (`watch-installs`). Free text
   * from the watch is truncated; [revisionPublished] is the design revision [version] was built
   * from, when the store still knew it as the published version.
   */
  @Serializable
  data class InstallEvent(
    val deviceId: String,
    val accountActorId: String,
    val designId: String,
    val version: Long,
    val revisionPublished: Long? = null,
    val result: String,
    val errorKind: String? = null,
    val wearOsApi: Int? = null,
    val packageName: String? = null,
    val activation: String? = null,
    val activationError: String? = null,
    val deviceModel: String? = null,
    val wearOsVersion: String? = null,
    val watchFacePushApiVersion: String? = null,
    val appVersion: String? = null,
    val atEpochMillis: Long,
  )

  @Serializable private data class Installs(val installs: List<InstallEvent> = emptyList())

  /**
   * One design's watch publication. [wffXml] and [wffVersion] are the WFF exported from [revision]
   * when it was published, kept here so the APK can be rebuilt after the revision has been pruned
   * from the design's history. [version] is the APK `versionCode`.
   */
  @Serializable
  data class Publication(
    val designId: String,
    val accountActorId: String,
    val faceName: String,
    val published: Boolean,
    val version: Long,
    val revision: Long,
    val title: String,
    val wffVersion: Int,
    val wffXml: String,
    val label: String,
    val digest: String,
    val publishedAtEpochMillis: Long,
    /**
     * The design's `createdAtEpochMillis` when it was published: with [accountActorId], which
     * design this is. A design deleted and recreated under the same id (by anyone) is not it.
     */
    val designCreatedAtEpochMillis: Long? = null,
    /**
     * The face's drawables (paths relative to `res/`, as [DwfSource.assets]) by the SHA-256 of
     * their bytes, which [WatchFacePushStore] keeps beside the publications: a design's pinned
     * assets are packaged into every rebuild, as the WFF is, after its revision is pruned. Empty
     * for a publication stored before faces carried their assets.
     */
    val assets: Map<String, String> = emptyMap(),
  )

  /** A built APK's facts; the bytes are the `.apk` file beside it. */
  @Serializable
  data class Apk(
    val faceName: String,
    val version: Long,
    val digest: String,
    val packageName: String,
    val sha256: String,
    val sizeBytes: Long,
    val validationToken: String,
  )

  private val json = Json {
    ignoreUnknownKeys = true
    prettyPrint = true
  }
  private val lock = Any()
  private val devicesFile = root.resolve("devices.json")
  private val installsFile = root.resolve("installs.json")
  private val publicationsDir = root.resolve("publications")
  /** Published faces' drawables, content-addressed ([Publication.assets]). */
  private val assetsDir = root.resolve("assets")
  val apkDir: Path = root.resolve("apks")

  /** Where [WatchFacePushCatalogFaces] keeps catalog faces' versions. */
  val catalogFacesFile: Path = root.resolve("catalog-faces.json")
  private var devices: MutableList<Device>
  private var installs: MutableList<InstallEvent>
  private val publications = ConcurrentHashMap<String, Publication>()

  /** The key behind [previewSignature]; random once per server state, kept across restarts. */
  private val previewKey: ByteArray

  init {
    ServeOwnerOnlyFiles.createDirectories(root)
    ServeOwnerOnlyFiles.createDirectories(publicationsDir)
    ServeOwnerOnlyFiles.createDirectories(assetsDir)
    ServeOwnerOnlyFiles.createDirectories(apkDir)
    devices =
      readJson(devicesFile, Devices.serializer())?.devices?.toMutableList() ?: mutableListOf()
    installs =
      readJson(installsFile, Installs.serializer())?.installs?.toMutableList() ?: mutableListOf()
    Files.list(publicationsDir).use { files ->
      files
        .filter { it.fileName.toString().endsWith(".json") }
        .forEach { file ->
          readJson(file, Publication.serializer())?.let { publications[it.designId] = it }
        }
    }
    val keyFile = root.resolve("preview.key")
    previewKey =
      runCatching { Files.readAllBytes(keyFile) }.getOrNull()?.takeIf { it.size >= 32 }
        ?: ByteArray(32).also {
          SecureRandom().nextBytes(it)
          writeAtomically(keyFile, it)
        }
  }

  // ------------------------------------------------------------------ devices

  fun devices(accountActorId: String): List<Device> =
    synchronized(lock) { devices.filter { it.accountActorId == accountActorId } }

  fun deviceForToken(token: String): Device? {
    val hash = tokenHash(token)
    return synchronized(lock) {
      devices.firstOrNull { ServeUrls.tokensMatch(it.tokenSha256, hash) }
    }
  }

  /** Mint a device token for [accountActorId]; the token itself is returned and never stored. */
  fun link(accountActorId: String, deviceName: String, wearOsApi: Int?): Pair<Device, String> {
    val token = TOKEN_PREFIX + randomToken(32)
    val device =
      Device(
        id = randomToken(12),
        accountActorId = accountActorId,
        deviceName = deviceName,
        wearOsApi = wearOsApi,
        tokenSha256 = tokenHash(token),
        createdAtEpochMillis = clock(),
      )
    synchronized(lock) {
      devices.add(device)
      persistDevices()
    }
    return device to token
  }

  /** Revoke [deviceId] if it belongs to [accountActorId] (null: any account); true if it did. */
  fun revoke(deviceId: String, accountActorId: String?): Boolean =
    synchronized(lock) {
      val removed = devices.removeIf {
        it.id == deviceId && (accountActorId == null || it.accountActorId == accountActorId)
      }
      if (removed) {
        persistDevices()
        if (installs.removeIf { it.deviceId == deviceId }) persistInstalls()
      }
      removed
    }

  /** Record a use, persisting at most hourly per device: it is a hint for the person, not audit. */
  fun touch(device: Device) {
    val now = clock()
    val last = device.lastUsedEpochMillis ?: 0
    if (now - last < TOUCH_PERSIST_MILLIS) return
    synchronized(lock) {
      val index = devices.indexOfFirst { it.id == device.id }
      if (index < 0) return
      devices[index] = devices[index].copy(lastUsedEpochMillis = now)
      persistDevices()
    }
  }

  private fun persistDevices() {
    writeAtomically(
      devicesFile,
      json.encodeToString(Devices.serializer(), Devices(devices.toList())).toByteArray(),
    )
  }

  // ----------------------------------------------------------------- installs

  /**
   * Keep [event] as its device's latest report on its design, replacing the one before it, and drop
   * the oldest reports past [MAX_INSTALL_EVENTS]. Only a report on a design the device's own
   * account published is kept: nobody else reads it (`watch-installs` is the owner's), and a
   * catalog face (`c_…`) is no design at all. True when it was kept.
   */
  fun recordInstall(device: Device, event: InstallEvent): Boolean {
    val publication = publications[event.designId] ?: return false
    if (
      publication.accountActorId.isEmpty() || publication.accountActorId != device.accountActorId
    ) {
      return false
    }
    val kept =
      event.copy(
        deviceId = device.id,
        accountActorId = device.accountActorId,
        revisionPublished = publication.revision.takeIf { publication.version == event.version },
        atEpochMillis = clock(),
      )
    synchronized(lock) {
      installs.removeIf { it.deviceId == kept.deviceId && it.designId == kept.designId }
      installs.add(kept)
      while (installs.size > MAX_INSTALL_EVENTS) {
        installs.remove(installs.minBy { it.atEpochMillis })
      }
      persistInstalls()
    }
    return true
  }

  /**
   * [designId]'s latest report from each of [accountActorId]'s still-linked watches, newest first.
   */
  fun installs(designId: String, accountActorId: String): List<Pair<Device, InstallEvent>> =
    synchronized(lock) {
      val linked = devices.filter { it.accountActorId == accountActorId }.associateBy { it.id }
      installs
        .filter { it.designId == designId && it.accountActorId == accountActorId }
        .mapNotNull { event -> linked[event.deviceId]?.let { it to event } }
        // Kept in arrival order, so reversed first: a tie on the clock is the later arrival first.
        .asReversed()
        .sortedByDescending { it.second.atEpochMillis }
    }

  private fun persistInstalls() {
    writeAtomically(
      installsFile,
      json.encodeToString(Installs.serializer(), Installs(installs.toList())).toByteArray(),
    )
  }

  // ------------------------------------------------------------- publications

  fun publication(designId: String): Publication? = publications[designId]

  /** Every design currently published to watches, newest first. */
  fun published(): List<Publication> =
    publications.values.filter { it.published }.sortedByDescending { it.publishedAtEpochMillis }

  /**
   * Publish [source], exported from [revision], as [designId]'s watch face for [accountActorId].
   *
   * The version moves only when the face does: re-publishing an unchanged face keeps its version,
   * so watches are not offered an "update" to the same APK; any change to the WFF (or to an asset
   * or the WFF version) is `version + 1`. Unpublished faces keep their counter.
   */
  fun publish(
    designId: String,
    accountActorId: String,
    revision: Long,
    title: String,
    source: DwfSource,
    designCreatedAtEpochMillis: Long? = null,
  ): Publication =
    commit(candidate(designId, accountActorId, revision, title, source, designCreatedAtEpochMillis))

  /**
   * What [publish] would store, without storing it: the caller builds it first and [commit]s it
   * only once it built, so a refused face never replaces a working release.
   */
  fun candidate(
    designId: String,
    accountActorId: String,
    revision: Long,
    title: String,
    source: DwfSource,
    designCreatedAtEpochMillis: Long? = null,
  ): Publication =
    synchronized(lock) {
      val previous = publications[designId]
      val digest = source.digest()
      val version =
        when {
          previous == null -> 1L
          previous.digest == digest -> previous.version
          else -> previous.version + 1
        }
      val publication =
        Publication(
          designId = designId,
          accountActorId = accountActorId,
          faceName = WatchFacePushNaming.faceName(designId),
          published = true,
          version = version,
          revision = revision,
          title = title,
          wffVersion = source.wffVersion,
          wffXml = source.wffXml,
          label = source.label,
          digest = digest,
          publishedAtEpochMillis =
            if (previous?.published == true && previous.digest == digest) {
              previous.publishedAtEpochMillis
            } else clock(),
          designCreatedAtEpochMillis = designCreatedAtEpochMillis,
          assets = source.assets.entries.associate { (path, bytes) -> path to storeAsset(bytes) },
        )
      publication
    }

  /**
   * [publication]'s source as it was published: its WFF and its drawables. A drawable whose bytes
   * are gone is left out, and the packager then reports the resource it misses.
   */
  fun source(publication: Publication): DwfSource =
    DwfSource(
      wffXml = publication.wffXml,
      wffVersion = publication.wffVersion,
      assets =
        publication.assets.entries
          .mapNotNull { (path, digest) -> assetBytes(digest)?.let { path to it } }
          .toMap(),
      label = publication.label,
    )

  private fun storeAsset(bytes: ByteArray): String {
    val digest = sha256Hex(bytes)
    val file = assetsDir.resolve(digest)
    if (!Files.isRegularFile(file)) writeAtomically(file, bytes)
    return digest
  }

  private fun assetBytes(digest: String): ByteArray? {
    if (!ASSET_DIGEST.matches(digest)) return null
    val file = assetsDir.resolve(digest)
    return runCatching { Files.readAllBytes(file) }.getOrNull()
  }

  /**
   * Make [candidate] the design's publication. Its version is re-checked against what is stored, so
   * two publishes racing past [candidate] cannot both claim one version for different faces.
   */
  fun commit(candidate: Publication): Publication =
    synchronized(lock) {
      val current = publications[candidate.designId]
      store(candidate.copy(version = committedVersion(current, candidate)))
    }

  /**
   * The version [candidate] gets when committed over [current]: never lower than what is stored.
   * The same face keeps the stored version (a late duplicate cannot roll it back); a different face
   * is above it. Equal to `candidate.version` whenever nothing raced it.
   */
  private fun committedVersion(current: Publication?, candidate: Publication): Long =
    when {
      current == null -> candidate.version
      current.digest == candidate.digest -> maxOf(current.version, candidate.version)
      else -> maxOf(candidate.version, current.version + 1)
    }

  /**
   * [commit] [candidate] only if it lands at exactly the version it was built for; null (nothing
   * stored) when a concurrent publish moved the stored version, so the caller rebuilds first.
   */
  fun commitIfBuiltVersion(candidate: Publication): Publication? =
    synchronized(lock) {
      val current = publications[candidate.designId]
      if (committedVersion(current, candidate) != candidate.version) return null
      store(candidate)
    }

  /**
   * The design is gone for good (purged from the trash, or deleted by an administrator): take it
   * off watches and forget whose it was, keeping only its version counter, so whoever creates the
   * id next starts above every version already installed and never inherits this publication.
   */
  fun retire(designId: String): Publication? =
    synchronized(lock) {
      val previous = publications[designId] ?: return null
      if (installs.removeIf { it.designId == designId }) persistInstalls()
      val retired =
        previous.copy(
          published = false,
          accountActorId = "",
          title = "",
          wffXml = "",
          designCreatedAtEpochMillis = null,
        )
      store(retired)
      retired
    }

  /** Take [designId] off watches; its version counter is kept. Null when it was never published. */
  fun unpublish(designId: String): Publication? =
    synchronized(lock) {
      val previous = publications[designId] ?: return null
      val updated = previous.copy(published = false)
      store(updated)
      updated
    }

  private fun store(publication: Publication): Publication {
    writeAtomically(
      publicationsDir.resolve(publicationFileName(publication.designId)),
      json.encodeToString(Publication.serializer(), publication).toByteArray(),
    )
    publications[publication.designId] = publication
    return publication
  }

  // --------------------------------------------------------------------- APKs

  fun apkFile(publication: Publication): File =
    apkDir.resolve(apkBase(publication) + ".apk").toFile()

  fun apk(publication: Publication): Apk? {
    val file = apkFile(publication)
    if (!file.isFile) return null
    return readJson(apkDir.resolve(apkBase(publication) + ".json"), Apk.serializer())
  }

  /** Record [built] (already at [apkFile]) with its token; returns its facts. */
  fun recordApk(publication: Publication, token: String): Apk {
    val file = apkFile(publication)
    val apk =
      Apk(
        faceName = publication.faceName,
        version = publication.version,
        digest = publication.digest,
        packageName = WatchFacePushNaming.packageName(publication.faceName),
        sha256 = sha256Hex(file),
        sizeBytes = file.length(),
        validationToken = token,
      )
    writeAtomically(
      apkDir.resolve(apkBase(publication) + ".json"),
      json.encodeToString(Apk.serializer(), apk).toByteArray(),
    )
    return apk
  }

  @Serializable private data class Refusal(val diagnostics: List<String>)

  /**
   * The packager's diagnostics for a revision it refused, kept beside that revision's APK record so
   * a restart does not package a known-bad face again. A refusal is about exact bytes (the key
   * holds the digest), so it never needs invalidating: a fixed face is a new digest.
   */
  fun refusal(publication: Publication): List<String>? =
    readJson(apkDir.resolve(apkBase(publication) + ".refused.json"), Refusal.serializer())
      ?.diagnostics

  fun recordRefusal(publication: Publication, diagnostics: List<String>) {
    writeAtomically(
      apkDir.resolve(apkBase(publication) + ".refused.json"),
      json.encodeToString(Refusal.serializer(), Refusal(diagnostics)).toByteArray(),
    )
  }

  private fun apkBase(publication: Publication): String =
    "${publication.faceName}-v${publication.version}-${publication.digest.take(16)}"

  // ------------------------------------------------------------------ preview

  /** An unguessable, per-version signature, so the watch can fetch a picture with no header. */
  fun previewSignature(designId: String, version: Long): String {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(previewKey, "HmacSHA256"))
    return Base64.getUrlEncoder()
      .withoutPadding()
      .encodeToString(mac.doFinal("$designId|$version".toByteArray(StandardCharsets.UTF_8)))
      .take(32)
  }

  // ------------------------------------------------------------------ helpers

  private fun <T> readJson(file: Path, serializer: kotlinx.serialization.KSerializer<T>): T? {
    if (!Files.isRegularFile(file)) return null
    return try {
      json.decodeFromString(serializer, Files.readString(file, StandardCharsets.UTF_8))
    } catch (_: IOException) {
      null
    } catch (_: SerializationException) {
      System.err.println("serve: watchface-push: $file is unreadable; ignoring it")
      null
    } catch (_: IllegalArgumentException) {
      null
    }
  }

  private fun writeAtomically(target: Path, bytes: ByteArray) {
    val tmp = Files.createTempFile(target.parent, ".tmp-", ".part")
    try {
      Files.write(tmp, bytes)
      Files.move(
        tmp,
        target,
        StandardCopyOption.REPLACE_EXISTING,
        StandardCopyOption.ATOMIC_MOVE,
      )
    } finally {
      Files.deleteIfExists(tmp)
    }
  }

  companion object {
    /** Marks a device token in logs and leaked-secret scanners; carries no meaning. */
    const val TOKEN_PREFIX = "wfp_"
    private const val TOUCH_PERSIST_MILLIS = 60 * 60 * 1000L

    /** Every kept install report, across devices and designs; one per device and design. */
    const val MAX_INSTALL_EVENTS = 500
    private val ASSET_DIGEST = Regex("[0-9a-f]{64}")
    private val random = SecureRandom()

    /**
     * A publication's file, keyed by its design id (hashed, so any id is a safe file name), not by
     * its face name: the face name is derived, and the record must belong to exactly one design.
     */
    fun publicationFileName(designId: String): String =
      sha256Hex(designId.toByteArray(StandardCharsets.UTF_8)) + ".json"

    fun tokenHash(token: String): String = sha256Hex(token.toByteArray(StandardCharsets.UTF_8))

    fun randomToken(bytes: Int): String =
      Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(ByteArray(bytes).also(random::nextBytes))
  }
}

/**
 * Device-code linking ("OAuth device flow" shaped): the watch asks for a code, shows it, and polls
 * with a secret only it knows while a signed-in person confirms the code on `/link`. In memory on
 * purpose — a code lives ten minutes, and a restart costs the person one retry — while the device
 * token an approval mints is durable ([WatchFacePushStore.link]).
 */
internal class WatchFacePushDeviceCodes(
  private val store: WatchFacePushStore,
  private val clock: () -> Long = System::currentTimeMillis,
  val lifetimeSeconds: Int = DEFAULT_LIFETIME_SECONDS,
  val pollIntervalSeconds: Int = DEFAULT_POLL_INTERVAL_SECONDS,
  private val maxPending: Int = DEFAULT_MAX_PENDING,
) {
  enum class State {
    PENDING,
    APPROVED,
    DENIED,
  }

  class Pending(
    val code: String,
    val pollSecretHash: String,
    val deviceName: String,
    val wearOsApi: Int?,
    val expiresAtEpochMillis: Long,
  ) {
    @Volatile var state: State = State.PENDING
    @Volatile var lastPollEpochMillis: Long = 0
    @Volatile var approvedAccount: String? = null
    @Volatile var approvedDeviceId: String? = null

    /** Held only from approval until the one poll that delivers it. */
    @Volatile var token: String? = null
  }

  sealed interface Poll {
    data object Pending : Poll

    data class Approved(val deviceToken: String, val accountName: String) : Poll

    data object Denied : Poll

    data object Expired : Poll

    /** Unknown, consumed, or the wrong poll secret: indistinguishable on purpose. */
    data object NotFound : Poll

    data class TooFast(val retryAfterSeconds: Long) : Poll
  }

  private val codes = ConcurrentHashMap<String, Pending>()

  /** Null when too many codes are pending (the caller answers 429). */
  fun issue(deviceName: String, wearOsApi: Int?): Pair<Pending, String>? {
    sweep()
    if (codes.size >= maxPending) return null
    val secret = WatchFacePushStore.randomToken(32)
    while (true) {
      val code = newCode()
      val pending =
        Pending(
          code = code,
          pollSecretHash = WatchFacePushStore.tokenHash(secret),
          deviceName = deviceName,
          wearOsApi = wearOsApi,
          expiresAtEpochMillis = clock() + lifetimeSeconds * 1000L,
        )
      if (codes.putIfAbsent(code, pending) == null) return pending to secret
    }
  }

  /** The pending code a person typed or scanned, normalised; null when unknown or expired. */
  fun pending(raw: String?): Pending? {
    val code = normalize(raw) ?: return null
    val pending = codes[code] ?: return null
    return pending.takeIf { it.expiresAtEpochMillis > clock() }
  }

  fun poll(raw: String, secret: String?): Poll {
    val code = normalize(raw) ?: return Poll.NotFound
    val pending = codes[code] ?: return Poll.NotFound
    if (
      secret == null ||
        !ServeUrls.tokensMatch(pending.pollSecretHash, WatchFacePushStore.tokenHash(secret))
    ) {
      return Poll.NotFound
    }
    val now = clock()
    if (now >= pending.expiresAtEpochMillis) {
      expire(pending)
      return Poll.Expired
    }
    synchronized(pending) {
      // One second of slack for a client polling on time over a slow network.
      val early = pending.lastPollEpochMillis + (pollIntervalSeconds - 1) * 1000L - now
      if (pending.lastPollEpochMillis > 0 && early > 0) {
        return Poll.TooFast(pollIntervalSeconds.toLong())
      }
      pending.lastPollEpochMillis = now
      return when (pending.state) {
        State.PENDING -> Poll.Pending
        State.DENIED -> {
          codes.remove(code, pending)
          Poll.Denied
        }
        State.APPROVED -> {
          // Delivered once: the code is consumed with this answer.
          codes.remove(code, pending)
          val token = pending.token ?: return Poll.NotFound
          pending.token = null
          Poll.Approved(token, accountName(pending.approvedAccount.orEmpty()))
        }
      }
    }
  }

  /** Approve [code] for [accountActorId]; false when it is no longer pending. */
  fun approve(code: String, accountActorId: String): Boolean {
    val pending = pending(code) ?: return false
    synchronized(pending) {
      if (pending.state != State.PENDING) return false
      val (device, token) = store.link(accountActorId, pending.deviceName, pending.wearOsApi)
      pending.approvedAccount = accountActorId
      pending.approvedDeviceId = device.id
      pending.token = token
      pending.state = State.APPROVED
      return true
    }
  }

  fun deny(code: String): Boolean {
    val pending = pending(code) ?: return false
    synchronized(pending) {
      if (pending.state != State.PENDING) return false
      pending.state = State.DENIED
      return true
    }
  }

  /**
   * Forget expired codes. An approval the watch never collected is revoked with it, so a token
   * nobody holds does not sit in the person's device list.
   */
  fun sweep() {
    val now = clock()
    codes.values.filter { now >= it.expiresAtEpochMillis + SWEEP_GRACE_MILLIS }.forEach(::expire)
  }

  private fun expire(pending: Pending) {
    codes.remove(pending.code, pending)
    synchronized(pending) {
      if (pending.token != null) {
        pending.approvedDeviceId?.let { store.revoke(it, null) }
        pending.token = null
      }
    }
  }

  private fun newCode(): String {
    val chars = CharArray(8) { ALPHABET[random.nextInt(ALPHABET.length)] }
    return String(chars, 0, 4) + "-" + String(chars, 4, 4)
  }

  companion object {
    /** No `0`/`O` or `1`/`I`: a code is read off a watch and typed. */
    const val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    const val DEFAULT_LIFETIME_SECONDS = 600
    const val DEFAULT_POLL_INTERVAL_SECONDS = 5
    const val DEFAULT_MAX_PENDING = 2000

    /** An expired code still answers `expired` (rather than 404) for this long. */
    private const val SWEEP_GRACE_MILLIS = 10 * 60 * 1000L
    private val random = SecureRandom()

    /** `ab7k 92qx`, `AB7K92QX` and `AB7K-92QX` are one code; anything else is none. */
    fun normalize(raw: String?): String? {
      val compact = raw?.uppercase()?.filter { it != '-' && !it.isWhitespace() } ?: return null
      if (compact.length != 8 || compact.any { it !in ALPHABET }) return null
      return compact.substring(0, 4) + "-" + compact.substring(4)
    }

    /** What the watch shows as "linked to": the GitHub login for a GitHub actor. */
    fun accountName(accountActorId: String): String = accountActorId.removePrefix("github:")
  }
}

/**
 * Builds and caches push APKs, one at a time: the packager runs `aapt2` and Google's validator,
 * which are memory-hungry, and a listing that finds several faces unbuilt should not start them all
 * at once. A build is keyed by `(faceName, version, digest)`, so an unchanged revision is never
 * rebuilt — across requests and across restarts.
 *
 * Builds are deliberate. An owner's publish builds inline (so a refusal reaches the person who can
 * fix it), and everything else is queued onto one background worker ([enqueue]) by the reconcile
 * pass — a catalog face whose digest has no APK yet, a publication whose APK was lost. Listings
 * read [cached] and [refusal] only and never build. The APK download routes still call [build] on a
 * miss, as a fallback.
 *
 * A refusal is remembered on disk ([WatchFacePushStore.recordRefusal]), so a restart does not
 * repackage a known-bad face. Any other failure (a timeout, a full disk) may well succeed later, so
 * it is retried, but only after a backoff that doubles from [transientBackoffMillis] up to
 * [maxTransientBackoffMillis], not once per request.
 */
internal class WatchFacePushBuilds(
  private val store: WatchFacePushStore,
  private val packaging: () -> WatchFacePushPackaging,
  private val clock: () -> Long = System::currentTimeMillis,
  private val transientBackoffMillis: Long = DEFAULT_TRANSIENT_BACKOFF_MILLIS,
  private val maxTransientBackoffMillis: Long = MAX_TRANSIENT_BACKOFF_MILLIS,
  private val log: (String) -> Unit = System.err::println,
) {
  sealed interface Result {
    data class Built(val apk: WatchFacePushStore.Apk, val file: File) : Result

    data class Unavailable(val code: String, val reason: String) : Result

    data class Failed(val diagnostics: List<String>) : Result
  }

  private val lock = Any()

  /** Refusals by build key, mirrored from disk on first read so a listing stays cheap. */
  private val failures = ConcurrentHashMap<String, List<String>>()

  /**
   * Keys whose disk refusal was looked up and found absent. Cleared by a refusal being recorded.
   */
  private val noRefusal = ConcurrentHashMap.newKeySet<String>()

  private class Backoff(
    val attempts: Int,
    val retryAtEpochMillis: Long,
    val diagnostics: List<String>,
  )

  /** Transient failures by build key: the last diagnostics and when a retry is allowed again. */
  private val transient = ConcurrentHashMap<String, Backoff>()

  /** Keys queued on, or being built by, [worker]. */
  private val queued = ConcurrentHashMap.newKeySet<String>()

  private val worker =
    java.util.concurrent.Executors.newSingleThreadExecutor { runnable ->
      Thread(runnable, "watchface-push-builds").apply {
        isDaemon = true
        priority = Thread.MIN_PRIORITY
      }
    }

  fun cached(publication: WatchFacePushStore.Publication): Result.Built? =
    store.apk(publication)?.let { Result.Built(it, store.apkFile(publication)) }

  /**
   * The packager's diagnostics for [publication] if a build of this exact revision was refused, in
   * this process or an earlier one, without building it: `/status` reports such a face as skipped
   * rather than listed.
   */
  fun refusal(publication: WatchFacePushStore.Publication): List<String>? {
    val key = key(publication)
    failures[key]?.let {
      return it
    }
    if (key in noRefusal) return null
    val stored = store.refusal(publication)
    if (stored != null) failures[key] = stored else noRefusal += key
    return stored
  }

  /** Wait until the background worker has nothing queued; false on timeout. For tests. */
  fun awaitIdle(timeoutMillis: Long = 60_000L): Boolean {
    val deadline = System.currentTimeMillis() + timeoutMillis
    while (queued.isNotEmpty()) {
      if (System.currentTimeMillis() > deadline) return false
      Thread.sleep(20)
    }
    return true
  }

  /** True while [publication] is queued for, or being built by, the background worker. */
  fun isPending(publication: WatchFacePushStore.Publication): Boolean = key(publication) in queued

  /**
   * Queue a background build of [publication] unless it is built, refused, already queued, or
   * waiting out a transient failure's backoff. Never blocks on the packager. True when queued.
   */
  fun enqueue(
    publication: WatchFacePushStore.Publication,
    source: () -> DwfSource = { store.source(publication) },
    cause: String,
  ): Boolean {
    if (cached(publication) != null || refusal(publication) != null) return false
    val key = key(publication)
    if (transient[key]?.let { clock() < it.retryAtEpochMillis } == true) return false
    if (packaging() is WatchFacePushPackaging.Unavailable) return false
    if (!queued.add(key)) return false
    try {
      worker.execute {
        try {
          when (val result = build(publication, source(), cause = cause)) {
            is Result.Failed ->
              log(
                "serve: watchface-push: ${publication.designId} v${publication.version} is not " +
                  "listed: ${result.diagnostics.firstOrNull()}",
              )
            else -> Unit
          }
        } catch (e: Exception) {
          log("serve: watchface-push: background build of ${publication.designId} failed: $e")
        } finally {
          queued.remove(key)
        }
      }
    } catch (e: java.util.concurrent.RejectedExecutionException) {
      queued.remove(key)
      return false
    }
    return true
  }

  /**
   * Build [publication] now, on the caller's thread. [source] defaults to the publication's own
   * WFF; a catalog face passes its full source. [retryTransient] lets a deliberate caller (an
   * owner's publish) skip a transient failure's backoff; a refusal is never retried.
   */
  fun build(
    publication: WatchFacePushStore.Publication,
    source: DwfSource = store.source(publication),
    retryTransient: Boolean = false,
    cause: String = "download",
  ): Result {
    cached(publication)?.let {
      return it
    }
    val key = key(publication)
    refusal(publication)?.let {
      return Result.Failed(it)
    }
    if (!retryTransient) {
      transient[key]?.let { backoff ->
        if (clock() < backoff.retryAtEpochMillis) return Result.Failed(backoff.diagnostics)
      }
    }
    val ready =
      when (val available = packaging()) {
        is WatchFacePushPackaging.Unavailable ->
          return Result.Unavailable(available.code, available.reason)
        is WatchFacePushPackaging.Ready -> available
      }
    val waitStarted = System.nanoTime()
    synchronized(lock) {
      cached(publication)?.let {
        return it
      }
      // Again under the lock: a build that held it may have just been refused or failed, and a
      // caller that waited for it must not run the packager straight back into the same outcome.
      refusal(publication)?.let {
        return Result.Failed(it)
      }
      if (!retryTransient) {
        transient[key]?.let { backoff ->
          if (clock() < backoff.retryAtEpochMillis) return Result.Failed(backoff.diagnostics)
        }
      }
      val started = System.nanoTime()
      val waitedMillis = (started - waitStarted) / 1_000_000
      val out = store.apkFile(publication)
      val tmp = File(out.parentFile, out.name + ".tmp")
      fun elapsed() =
        "${(System.nanoTime() - started) / 1_000_000} ms" +
          if (waitedMillis >= 1000) " after waiting $waitedMillis ms for the packager" else ""
      return try {
        source.problem()?.let { throw DwfPackagingFailed(listOf("error[bundle]: $it")) }
        val files =
          DwfPackaging.bundleFiles(
            source,
            WatchFacePushNaming.packageName(publication.faceName),
          )
        val token =
          ready.packager.pack(
            files,
            publication.faceName,
            publication.version.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            tmp,
          )
        check(tmp.isFile && tmp.length() > 0L) { "the packager wrote no APK" }
        Files.move(tmp.toPath(), out.toPath(), StandardCopyOption.REPLACE_EXISTING)
        transient.remove(key)
        log(
          "serve: watchface-push: packaged ${publication.designId} v${publication.version} " +
            "($cause) in ${elapsed()}",
        )
        Result.Built(store.recordApk(publication, token), out)
      } catch (e: DwfPackagingFailed) {
        if (!isSourceRefusal(e.diagnostics)) {
          // A nonzero exit without the packager's own diagnostics (a JVM killed under memory
          // pressure, a full disk): about the host, not these bytes, so never remembered as a
          // refusal.
          return transientFailure(publication, key, e.diagnostics, cause, elapsed())
        }
        failures[key] = e.diagnostics
        noRefusal -= key
        transient.remove(key)
        runCatching { store.recordRefusal(publication, e.diagnostics) }
          .onFailure { log("serve: watchface-push: could not record a refusal: ${it.message}") }
        log(
          "serve: watchface-push: the packager refused ${publication.designId} " +
            "v${publication.version} ($cause) in ${elapsed()}",
        )
        Result.Failed(e.diagnostics)
      } catch (e: Exception) {
        // Not a refusal: a timeout or a full disk may well succeed later, so back off and retry.
        transientFailure(
          publication,
          key,
          listOf("error[push]: ${e.message ?: e.javaClass.simpleName}"),
          cause,
          elapsed(),
        )
      } finally {
        tmp.delete()
      }
    }
  }

  private fun transientFailure(
    publication: WatchFacePushStore.Publication,
    key: String,
    diagnostics: List<String>,
    cause: String,
    elapsed: String,
  ): Result.Failed {
    val attempts = (transient[key]?.attempts ?: 0) + 1
    val delay =
      (transientBackoffMillis shl (attempts - 1).coerceAtMost(20)).coerceAtMost(
        maxTransientBackoffMillis,
      )
    transient[key] = Backoff(attempts, clock() + delay, diagnostics)
    log(
      "serve: watchface-push: packaging ${publication.designId} v${publication.version} " +
        "($cause) failed after $elapsed; retrying no sooner than ${delay / 1000} s: " +
        diagnostics.firstOrNull(),
    )
    return Result.Failed(diagnostics)
  }

  /** Stop the background worker; a build already running finishes, nothing queued starts. */
  fun close() {
    worker.shutdownNow()
  }

  private fun key(publication: WatchFacePushStore.Publication): String =
    "${publication.faceName}|${publication.version}|${publication.digest}"

  companion object {
    /**
     * Whether [diagnostics] are the packager refusing the source: every line one of its own
     * `error[…]`/`warning[…]`/`info[…]` diagnostics, with at least one error. Anything else (the
     * last line of a crashed JVM, say) is about the host.
     */
    internal fun isSourceRefusal(diagnostics: List<String>): Boolean =
      diagnostics.isNotEmpty() &&
        diagnostics.all { REFUSAL_LINE.matches(it) } &&
        diagnostics.any { it.startsWith("error[") }

    private val REFUSAL_LINE = Regex("^(error|warning|info)\\[[^\\]]+\\]: .*")

    const val DEFAULT_TRANSIENT_BACKOFF_MILLIS = 60_000L
    const val MAX_TRANSIENT_BACKOFF_MILLIS = 60 * 60_000L
  }
}
