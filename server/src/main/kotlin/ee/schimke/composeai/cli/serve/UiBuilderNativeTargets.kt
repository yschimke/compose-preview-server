package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.uibuilder.export.UiBuilderCatalogPlatform
import ee.schimke.composeai.uibuilder.export.UiBuilderPreviewSurfaces

/**
 * Where a builder catalog's designs compile and render natively, or why nowhere: the decision
 * [ServeUiBuilderNativePreview]'s `nativeTarget` makes on a live host, as a pure function.
 *
 * Two inputs can disagree about the daemon, and that is the whole difficulty (#1867). The builder
 * catalog declares one in `statusSemantics.previewSurfaces.native.backend`
 * ([UiBuilderPreviewSurfaces]); the served bundle it maps to reports the one it was built for.
 *
 * - **Both say `android`**, or the host reports no served backend (pinned hosts): the Android
 *   (Robolectric) daemon, as before.
 * - **The catalog says `android`, the bundle `desktop`, and the catalog authors
 *   [UiBuilderCatalogPlatform.MOBILE] screens**: the desktop (Skiko) daemon. A mobile catalog is
 *   Material 3 on Compose — multiplatform by declaration (see [UiBuilderCatalogPlatform.MOBILE]) —
 *   and the bundle serving it compiled those very components for the JVM, so the generated Kotlin
 *   links there too. This is the `m3-catalog` case: its policy said Robolectric while its
 *   `:catalog` module is a Compose Multiplatform desktop module.
 * - **The catalog says `android`, the bundle `desktop`, and the catalog is anything else** — Wear
 *   Compose, a Remote Compose widget body, a catalog with no known platform: refused with both
 *   backends named. Their components are Android AARs, and routing them to Skiko would fail on
 *   every import and read like a broken design.
 * - **Otherwise**: the desktop daemon on the served bundle.
 *
 * Driven by the catalog's declared platform, never by a catalog id, so the same rule covers any
 * Material 3 catalog an operator serves.
 */
internal object UiBuilderNativeTargets {

  /** The decision: a target, or the host-configuration sentence saying why there is none. */
  sealed interface Resolution {
    data class Target(val target: UiBuilderNativeTarget) : Resolution

    data class Refused(val reason: String) : Resolution
  }

  /**
   * @param builderCatalog the design's catalog (or its single pack), as the builder names it.
   * @param served the served catalog it compiles against (the `--ui-builder-native-catalog`
   *   mapping, or the same id).
   * @param declaredBackend the builder catalog's declared native backend; null when it declares
   *   none (a pack, which takes its bundle's).
   * @param servedBackend the backend the served bundle reports; null where the host reports none.
   * @param platform the builder catalog's declared platform; null when unknown.
   */
  fun resolve(
    builderCatalog: String,
    served: String,
    declaredBackend: String?,
    servedBackend: String?,
    platform: UiBuilderCatalogPlatform?,
  ): Resolution {
    val android = UiBuilderPreviewSurfaces.BACKEND_ANDROID
    return when {
      declaredBackend != android ->
        Resolution.Target(UiBuilderNativeTarget(served, UiBuilderGeneratedCompose.COMPOSE_CMP))
      servedBackend == null || servedBackend == android ->
        Resolution.Target(UiBuilderNativeTarget(served, UiBuilderGeneratedCompose.COMPOSE_ANDROID))
      acceptsDesktopBundle(platform) ->
        Resolution.Target(UiBuilderNativeTarget(served, UiBuilderGeneratedCompose.COMPOSE_CMP))
      else -> Resolution.Refused(backendMismatch(builderCatalog, served, servedBackend, platform))
    }
  }

  /**
   * Whether a catalog declaring the Android daemon may compile against a desktop bundle instead:
   * only a [UiBuilderCatalogPlatform.MOBILE] catalog, whose components are Compose Multiplatform.
   */
  fun acceptsDesktopBundle(platform: UiBuilderCatalogPlatform?): Boolean =
    platform == UiBuilderCatalogPlatform.MOBILE

  /** `NO_NATIVE_CATALOG`'s reason when a bundle is served but on the wrong daemon. */
  fun backendMismatch(
    builderCatalog: String,
    served: String,
    servedBackend: String,
    platform: UiBuilderCatalogPlatform?,
  ): String =
    "catalog `$builderCatalog` declares the `${UiBuilderPreviewSurfaces.BACKEND_ANDROID}` " +
      "native backend (`statusSemantics.previewSurfaces.native.backend`), but the bundle this " +
      "host serves for it (`$served`) is built for `$servedBackend`" +
      (platform?.let { ", and a `${it.wireValue}` catalog's components do not compile there" }
        ?: "") +
      "; serve an Android bundle of this catalog and map it with " +
      "`--ui-builder-native-catalog $builderCatalog=<served catalog>`, or, if its components " +
      "are Compose Multiplatform, have the catalog declare " +
      "`previewSurfaces.native.backend = \"$servedBackend\"`"
}
