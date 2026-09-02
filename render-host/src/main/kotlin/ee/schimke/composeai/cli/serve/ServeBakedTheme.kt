package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.daemon.protocol.UiMode

/**
 * The light/dark mode a published catalog render was **baked in**, from the catalog's own naming.
 *
 * The whole "browsing is instant" property of a trusted catalog rests on this one question: a
 * `?uiMode=` that names the theme the sticker already shows is a no-op, so the request replays the
 * baked PNG (`CatalogLiveRouting.withoutBakedNoOps`); one that names the other theme has to reach a
 * daemon. Answer it too narrowly and every browse of a render whose theme cannot be named pays for
 * a live render of pixels that are already on disk.
 *
 * Which is exactly what an **untagged** id used to cost. The catalog export names a sticker
 * `<variant>__<state>[__theme][__size][…]` and omits the theme segment for the mode it draws by
 * default — so `button-filled__ideal__l-square` (published alongside
 * `button-filled__ideal__l-square__dark`) carries no token at all even though it is the light half
 * of that pair, and its `previewId` says so outright:
 * `…ButtonsKt.FilledButton_Light_VARIANT_l-square`. On `m3-catalog` that is 1874 of 4120 published
 * renders whose ordinary light browse — the viewer writes `uiMode` into every render URL — routed
 * to the daemon instead of the sticker, at seconds per image against a sub-second baked replay, and
 * under `Cache-Control: no-store` so the next visit paid again (compose-ai-tools#4997).
 *
 * The pairing is the evidence, not a guess about light-first catalogs: the export refuses to emit
 * two images of one component that share `{variant, state, theme, size, props}`, so an untagged id
 * whose `__dark` twin is published differs from that twin in the theme axis alone. It is therefore
 * the non-dark member of a folded pair. An untagged id with no such twin stays unnamed and keeps
 * routing to the daemon — a catalog that bakes only one theme has said nothing about which.
 *
 * Deliberately **light-only** in the derived direction, and that asymmetry is not an oversight: a
 * dark-first system never reaches this question, because `ServeWeb.SystemDisplay`'s
 * `normalizeOverrideParams` strips `uiMode` from the raw parameter map before it is ever parsed. A
 * dark-first catalog that bakes a light pair still names its light renders with an explicit
 * `__light` token, which [token] reads.
 */
object ServeBakedTheme {

  /**
   * The explicit `light` / `dark` token a flattened catalog id carries, or null when it carries
   * none.
   *
   * The **last** such segment past the component slug wins, matching `ServeUrls.wasmAppSrc` and
   * `ServeWeb.cardTheme`. Scanning for `dark` first would misread a non-theme segment named `dark`
   * in an otherwise-light variant, wrongly treating `uiMode=dark` as a no-op and dropping the
   * override.
   */
  fun token(previewId: String): UiMode? =
    when (previewId.split("__").drop(1).lastOrNull { it == "light" || it == "dark" }) {
      "dark" -> UiMode.DARK
      "light" -> UiMode.LIGHT
      else -> null
    }

  /**
   * The theme [previewId]'s baked pixels are drawn in, or null when the catalog names none.
   *
   * Three rungs, narrowing: the catalog's own [declaredTheme] for the record (`image.theme` in
   * `catalog.json`), the id's explicit [token], then the folded-pair rule above — an untagged id is
   * light when [publishesId] reports its `__dark` twin published.
   *
   * Null is the honest answer, not a default: it leaves a `uiMode` request routed to a real render,
   * which is what a session that cannot name the sticker's theme owes the caller.
   */
  fun resolve(
    previewId: String,
    declaredTheme: String? = null,
    publishesId: (String) -> Boolean = { false },
  ): UiMode? =
    when (declaredTheme?.trim()?.lowercase()) {
      "dark" -> UiMode.DARK
      "light" -> UiMode.LIGHT
      else -> token(previewId) ?: UiMode.LIGHT.takeIf { publishesId(darkTwinOf(previewId)) }
    }

  /** The id of [previewId]'s dark twin — the same sticker with the theme segment appended. */
  fun darkTwinOf(previewId: String): String = "${previewId}__dark"
}
