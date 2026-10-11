package com.example.designcatalogm3.shared

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/**
 * Mounts one catalog component by id inside the M3 theme. `dark`, [fontScale] and [rtl] map the
 * viewer's uiMode, font-scale and locale controls. An unknown id renders a diagnostic. The body is
 * the shared [CatalogComponent], the same composables the desktop sticker sheet bakes; controls are
 * always stateful.
 *
 * Snapshot parity is the contract: the baked PNG is `CatalogSticker` (a transparent wrap-content
 * `Surface` with 16dp padding) cropped to bounds, so this app draws the same sticker on a
 * transparent viewport and contain-fits it; the viewer sizes the iframe to the snapshot's box, so
 * the swap moves no pixels. [onFirstFrame] fires once the sticker is measured, scaled and drawn.
 * The surrounding area can't be transparent (compose-web paints an opaque base), so it paints the
 * stage's checkerboard, aligned via [checkerPhase].
 */
@Composable
fun CatalogApp(
  id: String,
  dark: Boolean = false,
  fontScale: Float = 1f,
  rtl: Boolean = false,
  checkerPhase: Offset = Offset.Zero,
  /**
   * The viewer's solid stage colour (`stageBg=#rrggbb`) painted behind the sticker; null means
   * checkerboard mode.
   */
  stageColor: Color? = null,
  /**
   * Typeface for the M3 type scale (the URL-loaded Roboto matching the Android snapshots). Null
   * falls back to the CMP bundled default.
   */
  fontFamily: FontFamily? = null,
  /**
   * Generic-family substitutes (`fonts.json` `role: "generic"`): family name → URL-loaded
   * [FontFamily], provided as `LocalGenericFonts` for `genericFontFamily`.
   */
  genericFamilies: Map<String, FontFamily> = emptyMap(),
  /**
   * Named font substitutes (`fonts.json` `role: "named"`): display name → URL-loaded [FontFamily],
   * provided as `LocalNamedFonts` for `namedFontFamily`.
   */
  namedFamilies: Map<String, FontFamily> = emptyMap(),
  onFirstFrame: (() -> Unit)? = null,
) {
  // Typeface and palette come from the override knobs (`knob.theme.*` via `LocalWasmCatalogKnobs`),
  // resolved through the shared choices so Wasm matches the desktop snapshot. No seed means Roboto
  // Flex and the M3 light/dark scheme.
  val scheme =
    catalogColorScheme(catalogOverrideString(CATALOG_COLORS_KNOB, CATALOG_PALETTE_M3), dark)
  val fontName = catalogOverrideString(CATALOG_FONT_KNOB, CATALOG_FONT_ROBOTO_FLEX)
  val resolvedFont = resolveCatalogFont(fontName, fontFamily, namedFamilies)
  // Shapes and type metrics resolve through the same shared choices.
  val shapes = catalogShapes(catalogOverrideString(CATALOG_SHAPES_KNOB, ""))
  // Type scale: the `theme.font` face, then per-group families from `theme.fonts` (resolved against
  // [namedFamilies]), then the `theme.typography` metrics. Mirrors the desktop `CatalogSticker`.
  val typography =
    catalogApplyTypography(
      catalogApplyFontFamilies(
        catalogTypography(resolvedFont),
        parseCatalogFontFamilies(catalogOverrideString(CATALOG_FONTS_KNOB, "")),
        namedFamilies,
        resolvedFont ?: FontFamily.SansSerif,
      ),
      catalogOverrideString(CATALOG_TYPOGRAPHY_KNOB, ""),
    )
  // Re-point density's fontScale (keeping pixel density) and layout direction so font-scale and
  // locale apply client-side.
  val density = LocalDensity.current
  val scaled = Density(density = density.density, fontScale = fontScale)
  val direction = if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr
  // Frame + sticker bounds, measured to contain-fit the sticker to the stage (see below).
  var frame by remember { mutableStateOf(IntSize.Zero) }
  var content by remember { mutableStateOf(IntSize.Zero) }
  var signalled by remember { mutableStateOf(false) }
  CompositionLocalProvider(
    LocalDensity provides scaled,
    LocalLayoutDirection provides direction,
    LocalGenericFonts provides genericFamilies,
    LocalNamedFonts provides namedFamilies,
  ) {
    MaterialTheme(colorScheme = scheme, typography = typography, shapes = shapes) {
      if (id in catalogComponentIds) {
        Box(
          modifier =
            Modifier.fillMaxSize()
              .stageBackdrop(stageColor, isSystemInDarkTheme(), checkerPhase)
              .onGloballyPositioned { frame = it.size },
          contentAlignment = Alignment.Center,
        ) {
          // Contain-fit with no inset or clamp: the sticker's dp geometry matches the snapshot, so
          // the exact fit reproduces it.
          val scale =
            if (frame == IntSize.Zero || content.width == 0 || content.height == 0) 1f
            else
              minOf(frame.width.toFloat() / content.width, frame.height.toFloat() / content.height)
          Box(
            modifier =
              Modifier.onGloballyPositioned { content = it.size }
                .graphicsLayer(scaleX = scale, scaleY = scale)
          ) {
            // A 1:1 port of the shared `CatalogSticker`: a transparent Surface with 16dp padding
            // (not `colorScheme.surface`, which would add a panel the snapshot doesn't have).
            Surface(color = Color.Transparent, contentColor = MaterialTheme.colorScheme.onSurface) {
              Box(Modifier.padding(16.dp)) { CatalogComponent(id) }
            }
          }
        }
        // Once both boxes are measured the scale is final; let that frame and one settle frame draw
        // before signalling the viewer.
        if (
          onFirstFrame != null && !signalled && frame != IntSize.Zero && content != IntSize.Zero
        ) {
          LaunchedEffect(Unit) {
            withFrameNanos {}
            withFrameNanos {}
            signalled = true
            onFirstFrame()
          }
        }
      } else {
        Surface(modifier = Modifier.fillMaxSize()) {
          Box(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            contentAlignment = Alignment.Center,
          ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
              Text("Unknown component id", style = MaterialTheme.typography.titleMedium)
              Text(id, style = MaterialTheme.typography.bodySmall)
            }
          }
        }
        // Still signal on the diagnostic branch — the viewer must not wait forever on a bad id.
        if (onFirstFrame != null && !signalled) {
          LaunchedEffect(Unit) {
            withFrameNanos {}
            withFrameNanos {}
            signalled = true
            onFirstFrame()
          }
        }
      }
    }
  }
}

/**
 * Resolves typeface [name] to a URL-loaded family, mirroring the desktop `catalogFont`:
 * * Roboto Flex → the default [family].
 * * Google Sans Flex → its named family if vendored, else `FontFamily.SansSerif` (the desktop's
 *   fallback).
 * * other named faces → their named family, else the default.
 */
internal fun resolveCatalogFont(
  name: String,
  family: FontFamily?,
  named: Map<String, FontFamily>,
): FontFamily? =
  when (name) {
    CATALOG_FONT_ROBOTO_FLEX -> family
    CATALOG_FONT_GOOGLE_SANS_FLEX -> named[name] ?: FontFamily.SansSerif
    else -> named[name] ?: family
  }

/**
 * The viewer's stage checkerboard, pixel-for-pixel: `repeating-conic-gradient(<odd> 0% 25%, <even>
 * 0% 50%) / 16px 16px`. [dark] follows the page's `prefers-color-scheme`, not the component theme;
 * [phase] is the tile origin in this frame's CSS px.
 */
/**
 * The viewer's stage backdrop behind the snapshot, so the transparent sticker sits on the same
 * ground here: [stageColor] when solid, else the checkerboard. Something is always painted (the
 * surface can't be transparent), so it must match the page.
 */
private fun Modifier.stageBackdrop(stageColor: Color?, dark: Boolean, phase: Offset): Modifier =
  if (stageColor != null) drawBehind { drawRect(color = stageColor) }
  else stageCheckerboard(dark, phase)

private fun Modifier.stageCheckerboard(dark: Boolean, phase: Offset): Modifier = drawBehind {
  val even = if (dark) Color(0xFF1D1D20) else Color(0xFFFFFFFF)
  val odd = if (dark) Color(0xFF26262B) else Color(0xFFF4F4F6)
  val cell = 8.dp.toPx()
  val tile = cell * 2
  // First cell at or left of 0, congruent with the tile origin (so parity is origin-anchored).
  val ox = phase.x.dp.toPx().mod(tile) - tile
  val oy = phase.y.dp.toPx().mod(tile) - tile
  var row = 0
  var y = oy
  while (y < size.height) {
    var col = 0
    var x = ox
    while (x < size.width) {
      drawRect(
        color = if ((row + col) % 2 == 0) even else odd,
        topLeft = Offset(x, y),
        size = Size(cell, cell),
      )
      x += cell
      col++
    }
    y += cell
    row++
  }
}
