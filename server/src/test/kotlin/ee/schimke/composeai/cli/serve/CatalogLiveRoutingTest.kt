package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.daemon.protocol.GestureOverride
import ee.schimke.composeai.daemon.protocol.PreviewOverrideValue
import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import ee.schimke.composeai.daemon.protocol.RemoteComposeOverride
import ee.schimke.composeai.daemon.protocol.RemoteComposePlayerKind
import ee.schimke.composeai.daemon.protocol.RemoteComposeProfile
import ee.schimke.composeai.daemon.protocol.RemoteNamedValue
import ee.schimke.composeai.daemon.protocol.UiMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [CatalogLiveRouting.droppedOverrideNames] — the names behind a baked fallback (#3449). The
 * invariant that matters is agreement with [CatalogLiveRouting.overridesAffectRender]: anything the
 * baked PNG can't represent must come back named, so an HTTP response can say *which* override it
 * failed to apply instead of returning the snapshot under a 200.
 */
class CatalogLiveRoutingTest {

  private val lightId = "button-filled__ideal__default__light"

  private fun player(kind: RemoteComposePlayerKind) =
    PreviewOverrides(remoteCompose = RemoteComposeOverride(player = kind))

  /**
   * The player the request names is a no-op exactly when the capture went through it.
   *
   * `RemoteOverridablePreview` defaults to the embedded player, so for an ordinary preview the
   * baked PNG *is* the answer to `?rcPlayer=cmp-android` — reporting it dropped refused a request
   * the snapshot satisfies, which is why the viewer had to keep stamping the parameter onto every
   * default link. Any other player is a genuine re-render.
   */
  @Test
  fun `the baked player is a no-op and every other player is dropped`() {
    assertFalse(
      CatalogLiveRouting.overridesAffectRender(
        lightId,
        player(RemoteComposePlayerKind.EMBEDDED),
        UiMode.LIGHT,
        RemoteComposePlayerKind.EMBEDDED,
      ),
      "the embedded player drew the baked pixels, so asking for it changes nothing",
    )
    assertEquals(
      emptyList(),
      CatalogLiveRouting.droppedOverrideNames(
        lightId,
        player(RemoteComposePlayerKind.EMBEDDED),
        UiMode.LIGHT,
        RemoteComposePlayerKind.EMBEDDED,
      ),
    )
    assertTrue(
      CatalogLiveRouting.overridesAffectRender(
        lightId,
        player(RemoteComposePlayerKind.VIEW),
        UiMode.LIGHT,
        RemoteComposePlayerKind.EMBEDDED,
      ),
      "the view player is someone else's pixels",
    )
  }

  /**
   * …and the no-op follows the *session's* answer, not a constant.
   *
   * A preview pinning `RemoteViewPreviewWrapper` baked through the view player, so on it the
   * cmp-android request is the genuine re-render and `rcPlayer=java` is the one the snapshot
   * answers. Reading [ServeHost.bakedRcPlayer] rather than assuming EMBEDDED is what keeps that
   * preview from being handed the wrong player's capture under a confident 200.
   */
  @Test
  fun `a view-pinned preview inverts which player is the no-op`() {
    assertTrue(
      CatalogLiveRouting.overridesAffectRender(
        lightId,
        player(RemoteComposePlayerKind.EMBEDDED),
        UiMode.LIGHT,
        RemoteComposePlayerKind.VIEW,
      ),
      "this preview did not bake through the embedded player",
    )
    assertEquals(
      listOf("rcPlayer"),
      CatalogLiveRouting.droppedOverrideNames(
        lightId,
        player(RemoteComposePlayerKind.EMBEDDED),
        UiMode.LIGHT,
        RemoteComposePlayerKind.VIEW,
      ),
      "and it is named, so the response can say what it could not apply",
    )
    assertFalse(
      CatalogLiveRouting.overridesAffectRender(
        lightId,
        player(RemoteComposePlayerKind.VIEW),
        UiMode.LIGHT,
        RemoteComposePlayerKind.VIEW,
      ),
      "the view player is the one this preview's capture answers",
    )
  }

  @Test
  fun `an override-free request drops nothing`() {
    assertEquals(emptyList(), CatalogLiveRouting.droppedOverrideNames(lightId, PreviewOverrides()))
  }

  @Test
  fun `a uiMode matching the baked variant is a no-op, a differing one is dropped`() {
    assertEquals(
      emptyList(),
      CatalogLiveRouting.droppedOverrideNames(lightId, PreviewOverrides(uiMode = UiMode.LIGHT)),
    )
    assertEquals(
      listOf("uiMode"),
      CatalogLiveRouting.droppedOverrideNames(lightId, PreviewOverrides(uiMode = UiMode.DARK)),
    )
  }

  /**
   * `background=default` / `show` / `on` (and the raw `clearBackground=false`) ask to *preserve*
   * the preview's authored background — which is what the baked render drew. So it is honoured by
   * the snapshot, not dropped, and must not be refused. Only `clear` needs a re-render.
   */
  @Test
  fun `an explicit default background is satisfied by the baked pixels`() {
    val keepBackground = PreviewOverrides(clearBackground = false)
    assertEquals(emptyList(), CatalogLiveRouting.droppedOverrideNames(lightId, keepBackground))
    assertFalse(CatalogLiveRouting.overridesAffectRender(lightId, keepBackground))
    assertEquals(
      listOf("clearBackground"),
      CatalogLiveRouting.droppedOverrideNames(lightId, PreviewOverrides(clearBackground = true)),
    )
  }

  @Test
  fun `the display axes are named as the query string spells them`() {
    assertEquals(
      listOf("fontScale"),
      CatalogLiveRouting.droppedOverrideNames(lightId, PreviewOverrides(fontScale = 2.0f)),
    )
    assertEquals(
      listOf("widthPx", "density", "localeTag", "device", "gestures"),
      CatalogLiveRouting.droppedOverrideNames(
        lightId,
        PreviewOverrides(
          widthPx = 480,
          density = 2f,
          localeTag = "fr",
          device = "id:pixel_5",
          gestures = GestureOverride(showHints = true),
        ),
      ),
    )
  }

  @Test
  fun `knob and remote compose seeds keep their wire prefixes`() {
    assertEquals(
      listOf("knob.label", "rcProfile", "rc.stopColor"),
      CatalogLiveRouting.droppedOverrideNames(
        lightId,
        PreviewOverrides(
          namedOverrides = mapOf("label" to PreviewOverrideValue.StringValue("Tap me")),
          remoteCompose =
            RemoteComposeOverride(
              profile = RemoteComposeProfile.ANDROIDX,
              namedValues = mapOf("stopColor" to RemoteNamedValue.ColorValue("#FF8800")),
            ),
        ),
      ),
    )
  }

  /**
   * The two functions must never disagree: `overridesAffectRender` is the routing authority (it
   * compares against a defaults instance, so a newly added field is covered without touching the
   * naming code), and a request routed to the daemon that instead lands on baked pixels has to be
   * reportable. A field this file's naming block doesn't know still reports, as `overrides`.
   */
  @Test
  fun `every override that affects the render is named`() {
    val cases =
      listOf(
        PreviewOverrides(fontScale = 1.3f),
        PreviewOverrides(uiMode = UiMode.DARK),
        PreviewOverrides(themeProvider = "com.example.Brand"),
        PreviewOverrides(clearBackground = true),
        // A field the naming block deliberately doesn't spell (only the WebSocket lanes set it):
        // still reported, via the catch-all.
        PreviewOverrides(captureAdvanceMs = 500L),
      )
    for (o in cases) {
      assertTrue(CatalogLiveRouting.overridesAffectRender(lightId, o), "affects render: $o")
      assertTrue(
        CatalogLiveRouting.droppedOverrideNames(lightId, o).isNotEmpty(),
        "named as dropped: $o",
      )
    }
    assertEquals(
      listOf("overrides"),
      CatalogLiveRouting.droppedOverrideNames(lightId, PreviewOverrides(captureAdvanceMs = 500L)),
    )
  }
}
