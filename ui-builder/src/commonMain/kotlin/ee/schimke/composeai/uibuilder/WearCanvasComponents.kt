package ee.schimke.composeai.uibuilder

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.ListSubHeader
import androidx.wear.compose.material3.Slider
import androidx.wear.compose.material3.SwitchButton
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight

/**
 * Wear components drawn by Wear Compose, on the Wasm canvas.
 *
 * ## What changed, and why this file exists
 *
 * For most of this module's life the canvas drew a Wear design with Material 3 lookalikes: a Wear
 * card was literally a mobile Material card, a `ListHeader` was a `Box` with a `Text` centred in
 * 48dp, and a `TransformingLazyColumn` was a plain `Column`. The reason given — in
 * [wearScreenStandIn]'s KDoc, in `WearCanvasStandInTest` and in
 * `docs/design/UI_BUILDER_WEAR_SCREEN.md` — was that `androidx.wear.compose:compose-material3` is
 * an Android AAR that Compose Multiplatform for Wasm cannot link, so a Wear component was either
 * drawn as something else or not drawn at all.
 *
 * The first half of that is still true and checkable: that artifact's Gradle module metadata
 * carries exactly two variants, `releaseVariantReleaseApiPublication` and its runtime twin, and one
 * artifact, an `.aar`. It is not a multiplatform publication and there is no `wasmJs` variant to
 * resolve.
 *
 * The conclusion did not follow. The canvas does not have to link *that* artifact. The Wear kit
 * repository maintains a Compose Multiplatform port of the same source — `ee.schimke.wearcmp:*` —
 * which keeps the `androidx.wear.compose.material3` package names and publishes `jvm` and `wasmJs`
 * variants. Those are exactly this module's two targets, and it already renders its whole kit
 * through that port on a desktop lane. `settings.gradle.kts` names where the port is published and
 * `gradle/libs.versions.toml` pins the version.
 *
 * So every import above is the real component. `SwitchButton` here is Wear's `SwitchButton`,
 * measured and drawn by its own code, not an impression of it assembled from Material 3 pieces at
 * sizes read off a screenshot. That was the specific thing the old rule existed to prevent, and it
 * is no longer the price of drawing a Wear screen.
 *
 * ## What this is still not
 *
 * The port is not the kit rendition. The published Wear renders — and the device-preview lane —
 * draw with the genuine AndroidX library under Robolectric through that platform's own native
 * bundle, and that stays the thing this repository measures against the kit. This canvas is the
 * editing surface and the first-line preview; the two lanes disagreeing about a component is a
 * finding about the port, not about the design.
 */
@Composable
internal fun WearCanvasListHeader(text: String, modifier: Modifier = Modifier) {
  ListHeader(modifier = modifier) { Text(text) }
}

/** Wear's own sub-header, replacing a `Text` that was styled to look like one. */
@Composable
internal fun WearCanvasListSubHeader(text: String, modifier: Modifier = Modifier) {
  ListSubHeader(modifier = modifier) { Text(text) }
}

/**
 * Wear's `SwitchButton` — a component with no Material 3 counterpart, and therefore one the canvas
 * previously could not draw at any fidelity. `google-home-wear` uses eleven of them.
 *
 * `onCheckedChange` is empty rather than wired to the document: the canvas draws a design, it does
 * not run it, so the toggle reflects the authored `checked` and nothing moves when it is clicked.
 * That matches how every other stateful component behaves here.
 */
@Composable
internal fun WearCanvasSwitchButton(
  label: String,
  secondaryLabel: String,
  checked: Boolean,
  enabled: Boolean,
  modifier: Modifier = Modifier,
) {
  SwitchButton(
    checked = checked,
    onCheckedChange = {},
    modifier = modifier.fillMaxWidth(),
    enabled = enabled,
    label = { Text(label) },
    secondaryLabel = secondaryLabel.takeIf { it.isNotEmpty() }?.let { { Text(it) } },
  )
}

/**
 * Wear's `Slider`. Also previously undrawable, and also in `google-home-wear` three times.
 *
 * `valueRange` is the authored `valueFrom..valueTo` rather than a range derived from `steps`,
 * because the document carries both and the two can legitimately differ — a brightness row running
 * 0..100 in 10 steps is not the same component as one running 0..11.
 */
@Composable
internal fun WearCanvasSlider(
  value: Float,
  valueFrom: Float,
  valueTo: Float,
  steps: Int,
  segmented: Boolean,
  enabled: Boolean,
  modifier: Modifier = Modifier,
) {
  Slider(
    value = value,
    onValueChange = {},
    modifier = modifier.fillMaxWidth(),
    enabled = enabled,
    // `steps` is the count BETWEEN the ends, so it cannot be negative however the document reads.
    steps = steps.coerceAtLeast(0),
    // Guarded rather than trusted: `Slider` requires a non-empty range and the canvas must not
    // crash on a half-authored document — a canvas that throws cannot draw the Issues panel that
    // would explain why.
    valueRange = if (valueTo > valueFrom) valueFrom..valueTo else 0f..1f,
    segmented = segmented,
  )
}

/**
 * The real `TransformingLazyColumn`, with the transformation the canvas used to leave out.
 *
 * The old branch was a `Column`, and its comment argued the case honestly: `TransformingLazyColumn`
 * scales and fades its rows against the round display through `SurfaceTransformation` and
 * `Modifier.transformedHeight`, "neither of which exists off Android", so approximating the curve
 * by hand would draw a different wrong picture and imply it was right. Both now exist here —
 * `androidx.wear.compose.material3.lazy.transformedHeight` is imported above — so the rows scale
 * and fade because the library does it, which is the only version of this worth drawing.
 *
 * Items are passed as a count plus an index lambda rather than a list of composables so the lazy
 * column stays lazy: `TransformingLazyColumnScope.items` composes what is on screen.
 */
@Composable
internal fun WearCanvasTransformingLazyColumn(
  itemCount: Int,
  verticalSpacingDp: Float,
  modifier: Modifier = Modifier,
  item: @Composable (Int, Modifier) -> Unit,
) {
  val state = rememberTransformingLazyColumnState()
  val spec = rememberTransformationSpec()
  TransformingLazyColumn(
    state = state,
    modifier = modifier.fillMaxWidth(),
    verticalArrangement = Arrangement.spacedBy(verticalSpacingDp.dp),
  ) {
    items(itemCount) { index -> item(index, Modifier.fillMaxWidth().transformedHeight(this, spec)) }
  }
}
