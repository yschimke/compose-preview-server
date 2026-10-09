package ee.schimke.composeai.cli.serve

import java.io.File

/**
 * The typefaces the server-side **cmp-jvm** Remote Compose lane shapes text with.
 *
 * compose-ai-tools' `RcJvmServerRenderer` hands its render worker a `fonts.json` manifest
 * directory, and the worker resolves every family a document names through it: the literal
 * `default` a `CoreText` with no family asks for, and `google:Roboto Flex`, which is what the Wear
 * Material 3 Remote cards name. With no manifest the worker draws in Compose's built-in face, which
 * on a Linux host is a wider fallback sans — so titles wrap an extra line, card bodies overflow and
 * the subtitle is pushed out of the card. Nothing fails, which is why it went unnoticed:
 * preview.coo.ee scored 26–31% against the published captures on every Title/App card while the
 * other players drew Roboto Flex correctly.
 *
 * The renderer looks for that directory at `-D[PROPERTY]`, else at
 * `<APP_HOME>/rc-player-wasm/fonts` — the CMP/Wasm player's own fonts. This server's distribution
 * once carried no player at all (and the image lifts only `lib-rcjvm/` and `lib-bta/` out of the
 * CLI tarball), so on a server install the lookup came back empty every time. It now ships the
 * player, and [PACKAGED_DIR] is that player's `fonts/`: the manifest and faces rc-players vendors
 * (variable faces since 2.3.0), so the server-side and in-browser players resolve every family
 * from the same files. [installPackaged] points the renderer at them explicitly rather than
 * leaning on the worker's fallback.
 *
 * Offline and deterministic by construction: the faces are files in the install, so a render never
 * fetches and two hosts on the same release draw the same glyphs.
 */
internal object ServeRcJvmFonts {
  /**
   * The system property `RcJvmServerRenderer` reads (compose-ai-tools'
   * `ee.schimke.composeai.rcjvm.FONTS_DIR_PROPERTY`). Spelled out rather than imported: the
   * constant lives in the render worker, which is a subprocess classpath and not linked here.
   */
  const val PROPERTY: String = "composeai.rcjvm.fontsDir"

  /**
   * The distribution directory holding the manifest and faces: the CMP/Wasm player's own `fonts/`
   * (`stageRcPlayerWasm` in `server/build.gradle.kts`).
   */
  const val PACKAGED_DIR: String = "rc-player-wasm/fonts"

  /** The manifest a directory must hold to count; the worker ignores a directory without one. */
  const val MANIFEST: String = "fonts.json"

  /**
   * The packaged fonts directory: `<appHome>/rc-player-wasm/fonts` when [appHome] is set, else
   * the install inferred from [installDir]. Null when neither holds a [MANIFEST] — a directory
   * without one would be passed to the worker and silently ignored, which is the failure this
   * exists to end.
   */
  fun packagedDir(
    appHome: String? = System.getProperty("composeai.cli.appHome") ?: System.getenv("APP_HOME"),
    installDir: File? = inferredInstallDir(),
  ): File? =
    listOfNotNull(appHome?.let(::File), installDir)
      .map { File(it, PACKAGED_DIR) }
      .firstOrNull { File(it, MANIFEST).isFile }

  /**
   * Point the cmp-jvm lane at [packaged] unless [PROPERTY] is already set — an operator's explicit
   * directory wins. Returns the directory now in effect, or null when there is none, in which case
   * the renderer keeps its own fallbacks.
   */
  fun installPackaged(packaged: File? = packagedDir()): File? {
    System.getProperty(PROPERTY)
      ?.takeIf { it.isNotBlank() }
      ?.let {
        return File(it)
      }
    val dir = packaged ?: return null
    System.setProperty(PROPERTY, dir.absolutePath)
    return dir
  }

  /** `<APP_HOME>`, inferred from `<APP_HOME>/lib/<server>.jar` — as [LocalUiBuilder] does. */
  private fun inferredInstallDir(): File? = runCatching {
    File(
        ServeRcJvmFonts::class.java.protectionDomain?.codeSource?.location?.toURI()
          ?: return@runCatching null
      )
      .parentFile
      ?.parentFile
  }
    .getOrNull()
}
