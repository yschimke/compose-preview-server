package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.daemon.protocol.PreviewOverrideValue
import ee.schimke.composeai.data.overrides.PreviewOverrideDeclaration
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Pins the knob attributes the viewer's Wasm patch reads. An `@OverrideVariant` opens with its knob
 * seeded away from the default; the Wasm tier mounts the component without the variant axis, so the
 * page must publish both the opening value and the declared default.
 */
class ServeWebKnobSeedTest {

  private fun declaration(key: String, default: Boolean, current: Boolean) =
    PreviewOverrideDeclaration(
      key = key,
      type = "bool",
      label = key,
      default = PreviewOverrideValue.BooleanValue(default),
      current = PreviewOverrideValue.BooleanValue(current),
    )

  private fun viewer(
    vararg declarations: PreviewOverrideDeclaration,
    requestOverrides: Map<String, String> = emptyMap(),
  ): String {
    val preview =
      ServePreview(id = "button-filled", label = "Filled", overrides = declarations.toList())
    return ServeWeb.viewerPage(
      preview,
      token = "t",
      basePath = "/compose-m3",
      siblings = listOf(preview),
      wasmSrc = "/wasm/compose-m3/?id=button-filled",
      requestOverrides = requestOverrides,
    )
  }

  /** The checkbox row for [key], so an assertion reads one control rather than the whole page. */
  private fun knobRow(html: String, key: String): String =
    html.lineSequence().first { it.contains("""data-knob-key="$key"""") }

  @Test
  fun `a seeded variant publishes both the opening value and the author default`() {
    val html = viewer(declaration("enabled", default = true, current = false))
    // The control opens on the seed…
    assertTrue(html.contains("""data-knob-initial="false""""), html.substringAfter("cp-knob"))
    // …and still says what the author declared, which is the only way the Wasm patch can tell that
    // this sticker is a variant rather than an untouched primary.
    assertTrue(html.contains("""data-knob-default="true""""))
  }

  @Test
  fun `an ordinary sticker opens on its author default, and says so`() {
    val html = viewer(declaration("enabled", default = true, current = true))
    assertTrue(html.contains("""data-knob-initial="true""""))
    assertTrue(html.contains("""data-knob-default="true""""))
  }

  /**
   * A deep link's knob value reaches the control, not only the snapshot `<img>`, so live
   * `setOverrides`, export links and the next `/render` agree with the address.
   */
  @Test
  fun `a request override seeds the control`() {
    val html =
      viewer(
        declaration("secondary", default = false, current = false),
        requestOverrides = mapOf("knob.secondary" to "true"),
      )
    assertTrue(knobRow(html, "secondary").contains(" checked"), knobRow(html, "secondary"))
  }

  /**
   * …while `data-knob-initial` keeps naming the declaration: the viewer omits knobs equal to
   * `initial`, so a plain visit replays the baked PNG while a seeded value still counts as an
   * override.
   */
  @Test
  fun `a request override leaves the declared initial alone, so it still rides into the render`() {
    val row =
      knobRow(
        viewer(
          declaration("secondary", default = false, current = false),
          requestOverrides = mapOf("knob.secondary" to "true"),
        ),
        "secondary",
      )
    assertTrue(row.contains("""data-knob-initial="false""""), row)
    assertTrue(row.contains("""data-knob-default="false""""), row)
  }

  /** A request override displaces a variant's seed too — the link is the more specific answer. */
  @Test
  fun `a request override wins over an OverrideVariant seed`() {
    val row =
      knobRow(
        viewer(
          declaration("enabled", default = true, current = false),
          requestOverrides = mapOf("knob.enabled" to "true"),
        ),
        "enabled",
      )
    assertTrue(row.contains(" checked"), row)
    // Still the seed, so the control's value differs from it and the render carries `knob.enabled`.
    assertTrue(row.contains("""data-knob-initial="false""""), row)
  }

  /**
   * A legacy `<kind>:` wire tag is stripped exactly as `ServeOverrides.parse` does; seeded
   * verbatim, `bool:true` reads unchecked and `int:3` blanks a number input.
   */
  @Test
  fun `a legacy kind prefix is stripped before the control is seeded`() {
    val row =
      knobRow(
        viewer(
          declaration("enabled", default = false, current = false),
          requestOverrides = mapOf("knob.enabled" to "bool:true"),
        ),
        "enabled",
      )
    assertTrue(row.contains(" checked"), row)
  }

  /**
   * …but only when it matches the declared kind: a string knob may legitimately start with `int:`.
   */
  @Test
  fun `a mismatched kind prefix is left in a string knob's value`() {
    val preview =
      ServePreview(
        id = "button-filled",
        label = "Filled",
        overrides =
          listOf(
            PreviewOverrideDeclaration(
              key = "label",
              type = "string",
              label = "label",
              default = PreviewOverrideValue.StringValue(""),
            )
          ),
      )
    val html =
      ServeWeb.viewerPage(
        preview,
        token = "t",
        basePath = "/compose-m3",
        siblings = listOf(preview),
        requestOverrides = mapOf("knob.label" to "int:3"),
      )
    assertTrue(knobRow(html, "label").contains("""value="int:3""""), knobRow(html, "label"))
  }

  private fun rcViewer(
    declaration: ee.schimke.composeai.data.remotecompose.RemoteComposeKnobDeclaration,
    requestOverrides: Map<String, String>,
  ): String {
    val preview =
      ServePreview(
        id = "button-filled",
        label = "Filled",
        remoteComposeKnobs = listOf(declaration),
      )
    return ServeWeb.viewerPage(
      preview,
      token = "t",
      basePath = "/compose-m3",
      siblings = listOf(preview),
      canApplyOverrides = true,
      requestOverrides = requestOverrides,
    )
  }

  private fun rcRow(html: String, name: String): String =
    html.lineSequence().first { it.contains("""data-rc-name="$name"""") }

  /** An RC bool reads `1` as true, like `hydrateFromUrl` does. */
  @Test
  fun `an rc bool seeded as 1 is checked`() {
    val row =
      rcRow(
        rcViewer(
          ee.schimke.composeai.data.remotecompose.RemoteComposeKnobDeclaration(
            "enabled",
            ee.schimke.composeai.daemon.protocol.RemoteNamedValue.BooleanValue(false),
          ),
          mapOf("rc.enabled" to "bool:1"),
        ),
        "enabled",
      )
    assertTrue(row.contains(" checked"), row)
  }

  /**
   * An RC seed whose kind won't parse as the declared one leaves the control alone: RC params type
   * themselves (default `string`), so the renderer ignores `?rc.count=3` on a declared int, and
   * seeding it would turn an ignored request into an obeyed one.
   */
  @Test
  fun `an rc seed that would not parse as the declared kind is ignored`() {
    val row =
      rcRow(
        rcViewer(
          ee.schimke.composeai.data.remotecompose.RemoteComposeKnobDeclaration(
            "count",
            ee.schimke.composeai.daemon.protocol.RemoteNamedValue.IntValue(5),
          ),
          mapOf("rc.count" to "3"),
        ),
        "count",
      )
    assertTrue(row.contains("""value="5""""), row)
  }

  /** …and one that agrees with the declared kind is taken, with its wire tag stripped. */
  @Test
  fun `an rc seed tagged with the declared kind seeds the control`() {
    val row =
      rcRow(
        rcViewer(
          ee.schimke.composeai.data.remotecompose.RemoteComposeKnobDeclaration(
            "count",
            ee.schimke.composeai.daemon.protocol.RemoteNamedValue.IntValue(5),
          ),
          mapOf("rc.count" to "int:3"),
        ),
        "count",
      )
    assertTrue(row.contains("""value="3""""), row)
  }

  /** `bool:TRUE` ticks the box, since `parse` ignores case. */
  @Test
  fun `a mixed-case bool seed is checked`() {
    val row =
      knobRow(
        viewer(
          declaration("enabled", default = false, current = false),
          requestOverrides = mapOf("knob.enabled" to "bool:TRUE"),
        ),
        "enabled",
      )
    assertTrue(row.contains(" checked"), row)
  }

  /** An empty non-string seed leaves the control on the declaration, because `parse` skips it. */
  @Test
  fun `an empty non-string seed keeps the declaration`() {
    val preview =
      ServePreview(
        id = "button-filled",
        label = "Filled",
        overrides =
          listOf(
            PreviewOverrideDeclaration(
              key = "count",
              type = "int",
              label = "count",
              default = PreviewOverrideValue.IntValue(5),
            )
          ),
      )
    fun rowFor(seed: String): String {
      val html =
        ServeWeb.viewerPage(
          preview,
          token = "t",
          basePath = "/compose-m3",
          siblings = listOf(preview),
          requestOverrides = mapOf("knob.count" to seed),
        )
      return knobRow(html, "count")
    }
    assertTrue(rowFor("").contains("""value="5""""), rowFor(""))
    assertTrue(rowFor("int:").contains("""value="5""""), rowFor("int:"))
    // …while a real value still seeds, so this is a skip rather than a blanket refusal.
    assertTrue(rowFor("3").contains("""value="3""""), rowFor("3"))
  }

  /** An empty STRING seed is a real value — a cleared label — and reaches the control. */
  @Test
  fun `an empty string seed clears the control`() {
    val preview =
      ServePreview(
        id = "button-filled",
        label = "Filled",
        overrides =
          listOf(
            PreviewOverrideDeclaration(
              key = "label",
              type = "string",
              label = "label",
              default = PreviewOverrideValue.StringValue("Tap me"),
            )
          ),
      )
    val html =
      ServeWeb.viewerPage(
        preview,
        token = "t",
        basePath = "/compose-m3",
        siblings = listOf(preview),
        requestOverrides = mapOf("knob.label" to ""),
      )
    assertTrue(knobRow(html, "label").contains("""value="""""), knobRow(html, "label"))
  }

  /** A knob the request doesn't name is untouched — a plain visit renders exactly as before. */
  @Test
  fun `an unnamed knob keeps its declared value`() {
    val row =
      knobRow(
        viewer(
          declaration("enabled", default = true, current = true),
          declaration("secondary", default = false, current = false),
          requestOverrides = mapOf("knob.secondary" to "true"),
        ),
        "enabled",
      )
    assertTrue(row.contains(" checked"), row)
    assertTrue(row.contains("""data-knob-initial="true""""), row)
  }
}
