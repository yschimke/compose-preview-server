package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.agentgrants.AgentGrantCapability
import ee.schimke.composeai.agentgrants.AgentGrantScope
import ee.schimke.composeai.daemon.protocol.PreviewOverrideValue
import ee.schimke.composeai.daemon.protocol.RemoteNamedValue
import ee.schimke.composeai.data.overrides.PreviewOverrideDeclaration
import ee.schimke.composeai.data.overrides.PreviewOverrideType
import ee.schimke.composeai.data.remotecompose.RemoteComposeKnobDeclaration
import ee.schimke.composeai.designpages.DesignPage
import ee.schimke.composeai.designpages.PageFrame
import ee.schimke.composeai.designpages.PageImage
import ee.schimke.composeai.designpages.PageNode
import ee.schimke.composeai.designpages.PageNodeConfidence
import ee.schimke.composeai.designpages.PageNodeLink
import ee.schimke.composeai.web.WebEscaping
import java.awt.Color
import java.awt.GradientPaint
import java.awt.RenderingHints
import java.awt.geom.Ellipse2D
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * Golden generator + drift guard for the `serve` web pages. `ServeWeb` pages are rendered to
 * committed HTML fixtures under `preview-harness/fixtures/pages/`, which `pages-snapshot.spec.mjs`
 * screenshots per theme for the visual-diff bot. This test asserts the committed fixtures match the
 * current `ServeWeb`. Regenerate with:
 * ```
 * UPDATE_SERVE_WEB_FIXTURES=true ./gradlew :cli:test --tests '*ServeWebFixtureTest*'
 * ```
 *
 * (An env var because Gradle forwards the environment, not arbitrary system properties, to the test
 * JVM.) The server [version] and asset-href hashes ([stableAssetHrefs]) are held constant so diffs
 * only show markup.
 *
 * These goldens call `ServeWeb` directly, so they prove only how a page looks given these
 * arguments, not that the HTTP handler passes them. Wiring belongs in route tests against an
 * embedded server (see [ServeViewerIssueReportRouteTest], [ServeBugReportRouteTest]).
 */
class ServeWebFixtureTest {

  private fun jsonProps(vararg entries: Pair<String, String>): JsonObject = buildJsonObject {
    for ((key, value) in entries) put(key, JsonPrimitive(value))
  }

  private fun assetText(name: String): String = ServeWebAssets.load(name)!!.bytes.decodeToString()

  /**
   * One node of the design-page fixture. No geometry: the viewer measures the `data-node-id`
   * element in the SVG.
   */
  private fun pageNode(
    nodeId: String,
    name: String,
    code: String? = null,
    previewId: String? = null,
    link: PageNodeLink = PageNodeLink.MANIFEST,
    confidence: PageNodeConfidence? = if (code == null) null else PageNodeConfidence.HIGH,
    depth: Int = 3,
    type: String? = null,
  ) =
    PageNode.Builder(nodeId)
      .also {
        it.name = name
        it.depth = depth
        it.ref = "figma:ocdacdEsnHipMJD3egzxKb/$nodeId"
        it.code = code
        it.previewId = previewId
        it.link = link
        it.confidence = confidence
        it.type = type
      }
      .build()

  private val token = "demo-token-fixture"
  private val moduleLabel = ":samples:cmp"

  // A fixed server version so the committed HTML is stable across releases.
  private val version = "0.0.0-fixture"

  // Catalog provenance for the public compose-m3 landing golden — captures the provenance strip
  // (delivery branch, generation date, tool versions, regenerate link) the visual-diff bot diffs.
  private val provenance =
    ServeWeb.CatalogProvenance(
      repo = "yschimke/compose-ai-tools",
      branch = "design-artifacts/compose-m3",
      generatedAt = "2026-07-17T09:30:00.000Z",
      toolVersion = "0.16.54",
      designParityVersion = "0.1.25",
    )

  // The Figma node a preview is specified by, resolved from a Figma-backed design reference the
  // catalog published (mirrors a real meshcore-mobile design-map entry).
  private val fixtureDesignReference =
    DesignReference(
      id = "contact-chat-figma",
      previewId = "com.example.ProfileScreenPreview",
      label = "Contact chat",
      raster = DesignReferenceRaster(path = "references/contact-chat-figma.png"),
      source = DesignReferenceSource(provider = "figma", uri = "figma:gYzowY4cQ7rNr2gYoco1M6/73:6"),
    )

  private val fixtureFigmaSpec = ServeFigmaSpec.of(fixtureDesignReference)

  /** The comparison wall's page-scoped report: no preview, render or reference. */
  /**
   * The wall's page report, which is pickable: its template carries `{{locators}}` and the two
   * page-level locator facts that enable the row checkboxes.
   */
  private fun fixtureWallReportIssue(): ServeWeb.ReportIssue =
    fixturePageReportIssue(
      "https://preview.coo.ee/compose-m3/compare?format=reference",
      "these comparisons",
      pickable = true,
    )

  /**
   * The page-scoped catalog report carried by surfaces that name no single preview (wall, landing,
   * pages index, design page, motion browser).
   */
  private fun fixturePageReportIssue(
    pageUrl: String,
    subject: String,
    pickable: Boolean = false,
  ): ServeWeb.ReportIssue {
    val context =
      ServeIssueReport.Context(
        repo = "yschimke/compose-ai-tools",
        system = "compose-m3",
        catalog = "yschimke/compose-ai-tools@design-artifacts/compose-m3",
        toolVersion = provenance.toolVersion,
        pageUrl = pageUrl,
        publicRender = true,
      )
    return ServeWeb.ReportIssue(
      action = ServeIssueReport.action(context.repo),
      body = ServeIssueReport.body(context),
      bodyTemplate =
        ServeIssueReport.body(
          context,
          renderPlaceholder = true,
          locatorsPlaceholder = pickable,
        ),
      repo = context.repo,
      login = "yschimke",
      subject = subject,
      locatorSystem = if (pickable) context.system else null,
      locatorRevision = if (pickable) context.catalog else null,
    )
  }

  private fun fixtureReportIssue(
    previewId: String,
    label: String,
    sourceFile: String,
    componentId: String? = null,
    referenceId: String? = null,
    variant: String = "",
    overrides: Map<String, String> = emptyMap(),
    /**
     * Which reporting page this stands in for: the focused comparison (names the pair, fills
     * selection and score) or the viewer (names one render, fills the locator's overrides). Both
     * name the design reference.
     */
    comparison: Boolean = false,
  ) =
    ServeIssueReport.Context(
        repo = "yschimke/compose-ai-tools",
        previewId = previewId,
        previewLabel = label,
        system = "compose-m3",
        componentId = componentId,
        referenceId = referenceId,
        variant = variant,
        overrides = overrides,
        sourceUrl =
          ServeUrls.githubBlobUrl(
            "yschimke/compose-ai-tools",
            "design-artifacts-source",
            "samples/design-catalog-compose-m3",
            sourceFile,
          ),
        catalog = "yschimke/compose-ai-tools@design-artifacts/compose-m3",
        toolVersion = provenance.toolVersion,
        viewerUrl = if (!comparison) "https://preview.coo.ee/compose-m3/p/$previewId" else null,
        comparisonUrl =
          referenceId
            ?.takeIf { comparison }
            ?.let { "https://preview.coo.ee/compose-m3/compare/$previewId?reference=$it" },
        renderUrl =
          "https://preview.coo.ee/compose-m3/render/$previewId.png" +
            overrides.entries
              .sortedBy { it.key }
              .joinToString("&", prefix = if (overrides.isEmpty()) "" else "?") { (key, value) ->
                "${WebEscaping.urlEncodeSegment(key)}=${WebEscaping.urlEncodeSegment(value)}"
              },
        // The comparison's other panel, as `handleReferenceComparison` supplies it; reports from
        // that page carry the pair.
        referenceUrl =
          referenceId
            ?.takeIf { comparison }
            ?.let { "https://preview.coo.ee/compose-m3/reference/$it.png" },
        // The goldens stand in for preview.coo.ee, whose render lane is token-free — so they
        // capture the embedded-image form of the body.
        publicRender = true,
      )
      .let { ctx ->
        ServeWeb.ReportIssue(
          action = ServeIssueReport.action(ctx.repo),
          body = ServeIssueReport.body(ctx),
          bodyTemplate =
            ServeIssueReport.body(
              ctx,
              renderPlaceholder = true,
              selectionPlaceholder = comparison,
              overridesPlaceholder = !comparison,
              rawScoresPlaceholder = comparison,
            ),
          repo = ctx.repo,
          login = "yschimke",
        )
      }

  // The colour half of two real published token files
  // (`design-artifacts/<system>/tokens.dtcg.json`), trimmed to the roles the web projection reads,
  // so the themed fixtures use real palettes.
  private val wearM3Tokens =
    """
    {"color":{
      "primary":{"${'$'}type":"color","${'$'}value":"#4dd0e1ff"},
      "primaryContainer":{"${'$'}type":"color","${'$'}value":"#4d3d76ff"},
      "onPrimary":{"${'$'}type":"color","${'$'}value":"#210f48ff"},
      "onPrimaryContainer":{"${'$'}type":"color","${'$'}value":"#f6edffff"},
      "surface":{"${'$'}type":"color","${'$'}value":"#202124ff"},
      "onSurface":{"${'$'}type":"color","${'$'}value":"#f6edffff"},
      "surfaceContainerLow":{"${'$'}type":"color","${'$'}value":"#272430ff"},
      "surfaceContainer":{"${'$'}type":"color","${'$'}value":"#332e3cff"}
    }}
    """
      .trimIndent()

  private val jetNewsTokens =
    """
    {"color":{
      "primary":{"${'$'}type":"color","${'$'}value":"#bf0031ff"},
      "onPrimary":{"${'$'}type":"color","${'$'}value":"#ffffffff"},
      "primaryContainer":{"${'$'}type":"color","${'$'}value":"#ffdad9ff"},
      "surface":{"${'$'}type":"color","${'$'}value":"#fffbffff"},
      "onSurface":{"${'$'}type":"color","${'$'}value":"#201a1aff"}
    }}
    """
      .trimIndent()

  // A representative spread: a few snapshot-only previews plus two that also advertise the future
  // `live` (CMP→JS) mode, so the captured chrome exercises the mode seam.
  private val previews =
    listOf(
      ServePreview("com.example.ButtonPreview", "Button"),
      ServePreview(
        "com.example.CardPreview",
        "Card",
        listOf(PreviewMode.SNAPSHOT, PreviewMode.LIVE),
      ),
      ServePreview("com.example.DialogPreview", "Dialog"),
      ServePreview("com.example.ListScreenPreview", "List screen"),
      ServePreview(
        "com.example.ProfileScreenPreview",
        "Profile screen",
        listOf(PreviewMode.SNAPSHOT, PreviewMode.LIVE),
      ),
      ServePreview("com.example.SettingsScreenPreview", "Settings screen"),
    )

  // A design-catalog spread with a per-theme axis (`…__light` / `…__dark`) plus one theme-less
  // component, exercising the sticky light/dark toggle and its filtering.
  private val themedPreviews =
    listOf(
      ServePreview("button-filled__ideal__default__light", "Button · Filled (light)"),
      ServePreview("button-filled__ideal__default__dark", "Button · Filled (dark)"),
      ServePreview("switch-on__ideal__default__light", "Switch · On (light)"),
      ServePreview("switch-on__ideal__default__dark", "Switch · On (dark)"),
      ServePreview("badge", "Badge"),
    )

  /**
   * A published `rc-compare` manifest over [previews], hand-built so the golden pins the page, not
   * a fetch. Deliberately uneven: the JS player scores worse and cmp-wasm refuses one document, so
   * the wall shows rendered, scored and "could not decode" columns.
   */
  private fun rcCompareFixture(
    previews: List<ServePreview>,
    /**
     * Which lanes the run published (default all). Narrowing builds the partial-run wall, since
     * [ServeRcCompare.plan] drops unscored lanes and the page must say so.
     */
    lanes: Set<String>? = null,
  ): RcCompareManifest {
    val allLanes =
      listOf(
        RcCompareLane("baked", "AndroidX Embedded · baked", "baked"),
        RcCompareLane("js", "Camaelon JS", "camaelon-js"),
        RcCompareLane("embedded", "AndroidX Embedded · vendored Android", "vendored"),
        RcCompareLane("androidx-embedded", "AndroidX Embedded · androidx.dev", "androidx.dev"),
        RcCompareLane("cmp-jvm", "CMP JVM", "jvm"),
        RcCompareLane("cmp-wasm", "CMP Wasm", "cmp-wasm"),
      )
    val kept = allLanes.filter { lanes == null || it.id in lanes }
    fun cell(lane: String, slot: Int, pct: Double?, px: Long?, note: String = "") =
      if (pct == null && note.isNotEmpty()) RcCompareCell(rendered = false, note = note)
      else
        RcCompareCell(
          rendered = true,
          render = "$lane/$slot.png",
          diff = if (lane == "baked") "" else "$lane-diff/$slot.png",
          mismatchPct = pct,
          mismatchPx = px,
        )
    return RcCompareManifest(
      lanes = kept,
      rows =
        previews.mapIndexed { slot, preview ->
          val wasmRefuses = slot == 1
          RcCompareRow(
            previewId = preview.id,
            width = 400,
            height = 400,
            // Keyed by the kept lanes only, exactly as a real run's manifest is: a lane the run
            // never touched carries no cell, not an empty one.
            lanes =
              mapOf(
                  "baked" to cell("baked", slot, null, null),
                  "js" to cell("js", slot, 2.97 - slot * 0.4, 9116L - slot * 900),
                  "embedded" to cell("embedded", slot, 0.03 + slot * 0.01, 24L + slot),
                  "androidx-embedded" to
                    cell("androidx-embedded", slot, 0.4 + slot * 0.1, 640L + slot * 10),
                  "cmp-jvm" to cell("cmp-jvm", slot, 1.09 + slot * 0.2, 5151L + slot * 40),
                  "cmp-wasm" to
                    if (wasmRefuses)
                      cell(
                        "cmp-wasm",
                        slot,
                        null,
                        null,
                        "Document is not renderable by the CMP player: CoreText requires DataFont",
                      )
                    else cell("cmp-wasm", slot, 3.4 - slot * 0.3, 12040L - slot * 900),
                )
                .filterKeys { id -> kept.any { it.id == id } },
          )
        },
    )
  }

  // A catalog whose components carry baked non-default states (checked/unchecked,
  // selected/unselected) in light + dark. The landing folds each component to one card; the
  // viewer's `.cp-axes-tree` reaches the other states.
  /**
   * A Wear-shaped catalog documenting each component at the five screen sizes its kit declares. The
   * landing folds non-primary sizes onto one card and the viewer offers a size switcher.
   * `alertdialog` also varies its button arrangement, so state and size axes are crossed.
   */
  private val breakpointPreviews =
    listOf("192dp", "204dp", "216dp", "225dp", "240dp").flatMapIndexed { index, size ->
      listOf(
        ServePreview(
          "alertdialog__ideal__default__$size",
          "Alert Dialog · $size",
          componentId = "AlertDialog",
          state = "default",
          size = size,
          section = "Containment",
          group = "Dialogs",
          catalogOrder = index * 2,
          // The authored one-liner printed under the component's name.
          caption = "A decision the app needs before it can go on.",
        ),
        ServePreview(
          "alertdialog__ideal__no-buttons__$size",
          "Alert Dialog · No buttons · $size",
          componentId = "AlertDialog",
          state = "no-buttons",
          size = size,
          section = "Containment",
          group = "Dialogs",
          catalogOrder = index * 2 + 1,
        ),
        ServePreview(
          "timetext__ideal__default__$size",
          "Time Text · $size",
          componentId = "TimeText",
          state = "default",
          size = size,
          section = "Text",
          group = "Time",
          catalogOrder = 100 + index,
        ),
      )
    }

  private val statefulPreviews =
    listOf(
      ServePreview(
        "checkbox__ideal__default__light",
        "Checkbox · Checked (light)",
        state = "default",
        theme = "light",
      ),
      ServePreview(
        "checkbox__ideal__default__dark",
        "Checkbox · Checked (dark)",
        state = "default",
        theme = "dark",
      ),
      ServePreview(
        "checkbox__ideal__unchecked__light",
        "Checkbox · Unchecked (light)",
        state = "unchecked",
        theme = "light",
      ),
      ServePreview(
        "checkbox__ideal__unchecked__dark",
        "Checkbox · Unchecked (dark)",
        state = "unchecked",
        theme = "dark",
      ),
      ServePreview(
        "radiobutton__ideal__default__light",
        "Radio · Selected (light)",
        state = "default",
        theme = "light",
      ),
      ServePreview(
        "radiobutton__ideal__default__dark",
        "Radio · Selected (dark)",
        state = "default",
        theme = "dark",
      ),
      ServePreview(
        "radiobutton__ideal__unselected__light",
        "Radio · Unselected (light)",
        state = "unselected",
        theme = "light",
      ),
      ServePreview(
        "radiobutton__ideal__unselected__dark",
        "Radio · Unselected (dark)",
        state = "unselected",
        theme = "dark",
      ),
    )

  /**
   * A component with a wide state axis (22 states, like the published `iconbutton-outlined`). Past
   * [ServeWeb]'s inline threshold the rows fold behind the `State · …` toggle, so this captures the
   * collapsed disclosure; `serve-viewer-states.html` keeps the expanded case.
   */
  private val wideStatePreviews =
    listOf(
        "default",
        "disabled",
        "xs",
        "xs-narrow",
        "xs-square",
        "xs-wide",
        "s",
        "s-narrow",
        "s-square",
        "s-wide",
        "m",
        "m-narrow",
        "m-square",
        "m-wide",
        "l",
        "l-narrow",
        "l-square",
        "l-wide",
        "xl",
        "xl-narrow",
        "xl-square",
        "xl-wide",
      )
      .flatMap { state ->
        listOf("light", "dark").map { theme ->
          ServePreview(
            "iconbutton-outlined__ideal__${state}__$theme",
            "Icon Button Outlined · ${state.replace('-', ' ')} ($theme)",
            state = state,
            theme = theme,
          )
        }
      }

  /**
   * A component baking state × props as a full cross-product (every state also RTL), so each
   * subtree row must name both coordinates.
   */
  private val crossProductPreviews =
    listOf("default", "pressed", "disabled").flatMap { state ->
      listOf<String?>(null, "rtl").map { direction ->
        ServePreview(
          "button-filled__ideal__${state}__light" + if (direction == null) "" else "__$direction",
          "Button · Filled",
          state = state,
          theme = "light",
          props = direction?.let { jsonProps("direction" to it) },
        )
      }
    }

  // A trusted-catalog preview declaring author knobs (a `label` string + an accent `color`). On a
  // live catalog session these render as live controls that re-render via `/render`.
  private val knobPreview =
    ServePreview(
      "button-filled__ideal__default__light",
      "Button · Filled (light)",
      overrides =
        listOf(
          PreviewOverrideDeclaration(
            key = "label",
            type = PreviewOverrideType.STRING,
            default = PreviewOverrideValue.StringValue("Filled"),
          ),
          PreviewOverrideDeclaration(
            key = "iconColor",
            type = PreviewOverrideType.COLOR,
            default = PreviewOverrideValue.ColorValue("#FF6750A4"),
          ),
          // A font knob, rendered as a combobox seeded with the declared `@TypographyCatalog`
          // names. `googleFonts` is off here to keep the golden small; the full-list splice has its
          // own test.
          PreviewOverrideDeclaration(
            key = "theme.font",
            type = PreviewOverrideType.STRING,
            default = PreviewOverrideValue.StringValue("Roboto Flex"),
            suggestions = listOf("Roboto Flex", "Google Sans Flex", "Lobster Two"),
          ),
        ),
      // Remote Compose named-value knobs, rendered as a separate group whose edits round-trip via
      // `rc.<name>=<kind>:<value>`.
      remoteComposeKnobs =
        listOf(
          RemoteComposeKnobDeclaration("label", RemoteNamedValue.StringValue("Filled")),
          RemoteComposeKnobDeclaration("shaderColor", RemoteNamedValue.ColorValue("#FF7DE2FF")),
        ),
    )

  // An app catalog with `section` (tab) + `group` (sub-heading) + `catalogOrder`, like
  // meshcore-mobile. Three sections with sub-groups, "Device" reused across two (scoped per tab),
  // in authored order.
  private val sectionedPreviews =
    listOf(
      ServePreview(
        "theme-meshcore-light__ideal__default__compact",
        "Theme · MeshCore (light)",
        section = "Themes",
        group = "Foundation",
        catalogOrder = 0,
      ),
      ServePreview(
        "theme-material3-light__ideal__default__compact",
        "Theme · Material 3 (light)",
        section = "Themes",
        group = "Foundation",
        catalogOrder = 1,
      ),
      ServePreview(
        "devicesummarycard-populated__ideal__default__compact",
        "Device summary · Populated",
        section = "Components",
        group = "Device",
        catalogOrder = 2,
      ),
      ServePreview(
        "devicesummarycard-loading__ideal__default__compact",
        "Device summary · Loading",
        section = "Components",
        group = "Device",
        catalogOrder = 3,
      ),
      ServePreview(
        "contactrow-variants__ideal__default__compact",
        "Contact row · Variants",
        section = "Components",
        group = "Contacts",
        catalogOrder = 4,
      ),
      ServePreview(
        "contactlist-many__ideal__default__compact",
        "Contact list · Many",
        section = "Components",
        group = "Contacts",
        catalogOrder = 5,
      ),
      ServePreview(
        "scanner-savedpopulated__ideal__default__compact",
        "Scanner · Saved populated",
        section = "Screens",
        group = "Scanner",
        catalogOrder = 6,
      ),
      ServePreview(
        "scanner-blemany__ideal__default__compact",
        "Scanner · BLE many",
        section = "Screens",
        group = "Scanner",
        catalogOrder = 7,
      ),
      ServePreview(
        "device-manycontacts__ideal__default__compact",
        "Device · Many contacts",
        section = "Screens",
        group = "Device",
        catalogOrder = 8,
      ),
    )

  // A component (Button/Filled) whose default render carries baked props-axis variants (RTL, ar-XB,
  // 2× font scale) in light + dark. The landing folds to one card; the viewer's `.cp-axes-tree`
  // lists the props axis beside the state axis.
  private val variantPreviews =
    listOf(
      ServePreview(
        "button-filled__ideal__default__light",
        "Button · Filled (light)",
        state = "default",
        theme = "light",
      ),
      ServePreview(
        "button-filled__ideal__default__dark",
        "Button · Filled (dark)",
        state = "default",
        theme = "dark",
      ),
      ServePreview(
        "button-filled__ideal__default__light__direction-rtl",
        "Button · Filled · RTL (light)",
        state = "default",
        theme = "light",
        props = jsonProps("direction" to "rtl"),
      ),
      ServePreview(
        "button-filled__ideal__default__dark__direction-rtl",
        "Button · Filled · RTL (dark)",
        state = "default",
        theme = "dark",
        props = jsonProps("direction" to "rtl"),
      ),
      ServePreview(
        "button-filled__ideal__default__light__locale-ar-xb",
        "Button · Filled · ar-XB (light)",
        state = "default",
        theme = "light",
        props = jsonProps("locale" to "ar-XB"),
      ),
      ServePreview(
        "button-filled__ideal__default__dark__locale-ar-xb",
        "Button · Filled · ar-XB (dark)",
        state = "default",
        theme = "dark",
        props = jsonProps("locale" to "ar-XB"),
      ),
      ServePreview(
        "button-filled__ideal__default__light__fontscale-2.0",
        "Button · Filled · 2× font (light)",
        state = "default",
        theme = "light",
        props = jsonProps("fontScale" to "2.0"),
      ),
      ServePreview(
        "button-filled__ideal__default__dark__fontscale-2.0",
        "Button · Filled · 2× font (dark)",
        state = "default",
        theme = "dark",
        props = jsonProps("fontScale" to "2.0"),
      ),
    )

  // A section-less catalog whose components fall into families; ServeWeb synthesizes family
  // sub-group dividers. Light+dark pairs also exercise the theme toggle inside them.
  private val groupedPreviews =
    listOf("button-filled", "button-outlined", "button-tonal", "card-elevated", "card-filled")
      .flatMap { slug ->
        val name = slug.replace('-', ' ').replaceFirstChar { it.uppercaseChar() }
        listOf("light", "dark").map { theme ->
          ServePreview("${slug}__ideal__default__$theme", "$name ($theme)", theme = theme)
        }
      } +
      listOf(
        ServePreview("fab__ideal__default__light", "FAB (light)", theme = "light"),
        ServePreview("fab__ideal__default__dark", "FAB (dark)", theme = "dark"),
        ServePreview("badge__ideal__default__light", "Badge (light)", theme = "light"),
        ServePreview("badge__ideal__default__dark", "Badge (dark)", theme = "dark"),
      )

  /** One published typography row, as a catalog's `annotations/index.json` carries it. */
  private fun fixtureTypography(text: String, label: String, detail: Map<String, String>) =
    DesignAnnotation(
      kind = AnnotationKind.TYPOGRAPHY,
      bounds = AnnotationBounds(0, 0, 120, 24),
      label = label,
      role = text,
      detail = detail,
    )

  private fun fixtureLayout(role: String, label: String, detail: Map<String, String>) =
    DesignAnnotation(
      kind = AnnotationKind.LAYOUT,
      bounds = AnnotationBounds(0, 0, 200, 56),
      label = label,
      role = role,
      detail = detail,
    )

  @Test
  fun `serve web fixtures are in sync with ServeWeb`() {
    val pagesDir = File(repoRoot(), "preview-harness/fixtures/pages")
    val update =
      System.getenv("UPDATE_SERVE_WEB_FIXTURES") == "true" ||
        System.getProperty("updateServeWebFixtures") == "true"
    val parityIssues =
      listOf(
        ParityIssue(
          repository = "yschimke/m3-catalog",
          number = 40,
          title = "Glyph colour is darker than the design token",
          url = "https://github.com/yschimke/m3-catalog/issues/40",
          state = "open",
          area = "component",
          parity = "known-difference",
          component = "IconButton/Tonal",
          previewIds =
            listOf("com.example.ProfileScreenPreview", "button-filled__ideal__default__light"),
        ),
        ParityIssue(
          repository = "yschimke/m3-catalog",
          number = 41,
          title = "Verify the disabled state after the token update",
          url = "https://github.com/yschimke/m3-catalog/issues/41",
          state = "closed",
          area = "preview",
          parity = "verification-needed",
          component = "IconButton/Tonal",
          previewIds =
            listOf("com.example.ProfileScreenPreview", "button-filled__ideal__default__light"),
        ),
        // More open reports on the same component, to exercise the collapsed line's height.
        ParityIssue(
          repository = "yschimke/m3-catalog",
          number = 57,
          title = "Container radius is 12dp where the kit's Shape page says 16dp",
          url = "https://github.com/yschimke/m3-catalog/issues/57",
          state = "open",
          area = "spec",
          parity = "regression",
          component = "IconButton/Tonal",
          previewIds =
            listOf("com.example.ProfileScreenPreview", "button-filled__ideal__default__light"),
        ),
        ParityIssue(
          repository = "yschimke/m3-catalog",
          number = 63,
          title = "Upstream: the tonal container ignores the theme's secondary container role",
          url = "https://github.com/yschimke/m3-catalog/issues/63",
          state = "open",
          area = "renderer",
          parity = "upstream",
          component = "IconButton/Tonal",
          previewIds =
            listOf("com.example.ProfileScreenPreview", "button-filled__ideal__default__light"),
        ),
      )

    // Render the fixtures with a producer-trust badge so the visual-diff harness captures it: a
    // trusted (signature) landing and an unverified viewer exercise both badge styles.
    val landing =
      ServeWeb.landingPage(moduleLabel, previews, token, trust = "signature:compose-ai-tools-ci")
    // The public preview server's landing carries the "about" intro that explains the host + its
    // trust model (preview.coo.ee). Captured so the visual-diff harness covers that surface too.
    val landingPublic =
      ServeWeb.landingPage(
        moduleLabel,
        previews,
        token,
        trust = "branch:yschimke/compose-ai-tools@design-artifacts/compose-m3",
        isPublic = true,
        hasHomeIndex = true,
        version = version,
        provenance = provenance,
        refreshUrl = "/compose-m3/refresh",
        // "try in playground" on the summary line — the catalog-level half of the handoff, captured
        // so its placement in that run of actions is diffed like any other pixel.
        playgroundHref = "/playground?catalog=compose-m3",
        // Design pages on a catalog with no navigation tree, the one shape that still offers them
        // as a header chip (`landingGrouped` lists them in its tree).
        designPages =
          listOf(
            // Sections on one page and none on the other, on purpose: the pane has to render both
            // a branch and a leaf, and a golden that only ever held one shape would not say so.
            ServeWeb.PageLink(
              "shape",
              "Shape",
              listOf(
                ServeWeb.PageSection("1:20", "Corner radius"),
                ServeWeb.PageSection("1:21", "Shape scale"),
              ),
            ),
            ServeWeb.PageLink("type", "Typography"),
          ),
        parityIssues = parityIssues,
      )
    // The front door: an index of published design systems, each a card with a hero preview, title,
    // library, trust badge and link to /<system>/.
    //
    // The front-page section the operator's `catalogs.json` publishes the built-in systems under,
    // honoured only for catalogs whose bytes came from that repo.
    val designSystemsGroup =
      ServeWeb.HomeGroup(
        heading = "Design Systems",
        noun = "design system(s)",
        repos = setOf("yschimke/compose-ai-tools"),
      )
    val homeSystems =
      listOf(
        ServeWeb.HomeSystem(
          group = designSystemsGroup,
          system = "compose-m3",
          title = "Compose Material 3",
          subtitle = "androidx.compose.material3:material3",
          previewCount = 42,
          trust = "branch:yschimke/compose-ai-tools@design-artifacts/compose-m3",
          sourceRepo = "yschimke/compose-ai-tools",
          heroPreviewId = "button-filled__ideal__default__light",
          // The normal path: a prebaked, content-hashed hero on the immutable `/hero/` lane (eager
          // load, explicit box, no CSS clip).
          heroImage =
            ServeWeb.HeroImage(
              path = "/hero/compose-m3/1f0c9a4b7d2e6503.png",
              width = 168,
              height = 68,
            ),
          // Publishes Figma-backed references, so its card carries "compare to Figma"; its
          // neighbour without an action exercises the `.cp-sys-cell` grid alignment.
          hasReferenceComparison = true,
          designToolLabel = "Figma",
        ),
        ServeWeb.HomeSystem(
          group = designSystemsGroup,
          system = "wear-m3",
          title = "Wear Compose Material 3",
          subtitle = "androidx.wear.compose:compose-material3",
          previewCount = 18,
          trust = "branch:yschimke/compose-ai-tools@design-artifacts/wear-m3",
          sourceRepo = "yschimke/compose-ai-tools",
          heroPreviewId = "button-filled__ideal__default__light",
          heroImage =
            ServeWeb.HeroImage(
              path = "/hero/wear-m3/9b3d51ca08e7f264.png",
              width = 132,
              height = 132,
            ),
          // Wear is dark-first: the hero backs on the dark stage, not the default white.
          darkStage = true,
          hasReferenceComparison = true,
          designToolLabel = "Figma",
        ),
        ServeWeb.HomeSystem(
          group = designSystemsGroup,
          system = "remote-m3",
          title = "Remote Compose Material 3",
          subtitle = "androidx.wear.compose.remote:remote-material3",
          previewCount = 6,
          trust = "branch:yschimke/compose-ai-tools@design-artifacts/remote-m3",
          sourceRepo = "yschimke/compose-ai-tools",
          heroPreviewId = "Button-Filled__ideal__default__light",
          // Remote Compose draws the dark-first Wear scheme, so its catalog declares
          // `display.surface: "dark"` and the hero backs on the dark stage too.
          darkStage = true,
          // The card with both comparisons, like the live `remote-m3`: Figma-backed references and
          // a `compareWith` against the Wear catalog.
          hasReferenceComparison = true,
          designToolLabel = "Figma",
          parallelComparison = ServeWeb.ParallelComparison("wear-m3", "Wear Compose Material 3"),
        ),
        // App systems published UNLISTED from their own repos but promoted to the LISTED set
        // (`--catalogs`), so they show on the front door alongside the design systems.
        ServeWeb.HomeSystem(
          system = "meshcore-mobile",
          title = "MeshCore",
          subtitle = "ee.schimke.meshcore",
          previewCount = 33,
          trust = "branch:yschimke/meshcore-mobile@design-artifacts/meshcore-mobile",
          sourceRepo = "yschimke/meshcore-mobile",
          heroPreviewId = "device-manycontacts__ideal__default__compact",
        ),
        ServeWeb.HomeSystem(
          system = "homeassistant-remotecompose",
          title = "HomeAssistant RemoteCompose",
          subtitle = "ee.schimke.homeassistant",
          previewCount = 9,
          trust =
            "branch:yschimke/homeassistant-remotecompose@design-artifacts/homeassistant-remotecompose",
          sourceRepo = "yschimke/homeassistant-remotecompose",
          heroPreviewId = null,
          // References from no named design tool (checked-in PNGs): the card still offers the
          // comparison, with neutral wording, since availability, not vendor, gates it.
          hasReferenceComparison = true,
        ),
        // A Wear app (Confetti): dark-first stage, and its hero is a conference SCREEN — the most
        // representative view of the app — rather than a single component.
        ServeWeb.HomeSystem(
          system = "confetti-wear",
          title = "Confetti (Wear)",
          subtitle = "dev.johnoreilly.confetti",
          previewCount = 12,
          trust = "branch:joreilly/Confetti@design-artifacts/confetti-wear",
          sourceRepo = "joreilly/Confetti",
          heroPreviewId = "conference-screen__ideal__default__dark",
          darkStage = true,
        ),
      )
    val homeIndex =
      ServeWeb.homeIndexPage(
        homeSystems,
        token,
        isPublic = true,
        version = version,
        githubAuth =
          ServeWeb.GitHubAuthStatus(
            loginHref = "/auth/github/start?return=%2F",
            // Signed in, so the header carries both exits — the harness captures the sign-out form
            // and the switch-account link on every future change to the bar.
            logoutHref = "/auth/github/logout?return=%2F",
            login = "yschimke",
          ),
        // Signed in and permitted on two of three systems, so the golden holds a card with both
        // actions next to one with only the comparison. `remote-m3` is the widest card (builder
        // chip plus both destinations).
        uiBuilder =
          ServeWeb.UiBuilderInvite(
            systems = setOf("compose-m3", "remote-m3"),
            signedIn = true,
            permitted = true,
          ),
      )
    // The render-history timeline: a viewer served from a delivery branch, carrying the
    // history.json URL + repo `<cp-history-menu>` needs.
    val viewerHistory =
      ServeWeb.viewerPage(
        previews.first { it.id.endsWith("ProfileScreenPreview") },
        token,
        historyManifestUrl =
          ServeUrls.historyManifestUrl("yschimke/compose-ai-tools", "compose-preview/main"),
        historyRepo = "yschimke/compose-ai-tools",
        // Inlined so the harness renders offline. Three versions, one carried by several publishes,
        // flagged unstable to cover the badge.
        historyInlineJson =
          """
          {"formatVersion":"compose-preview-history/v1","generatedFrom":"df4aa9c00fcc8b1747e159b71d3fbc75cdc27b80",
           "previews":{"${previews.first { it.id.endsWith("ProfileScreenPreview") }.id}":{
             "path":"renders/samples:compose-m3/ProfileScreenPreview.png","observations":7,
             "unstable":true,"flapCount":4,"versions":[
               {"blob":"a","commit":"df4aa9c00fcc8b1747e159b71d3fbc75cdc27b80","date":"2026-05-22T11:08:37+00:00","sourceSha":"57ac24f3","commits":1},
               {"blob":"b","commit":"8b9f6f2bc953756edcb13963e09cd57c54866570","date":"2026-05-07T08:34:51+00:00","sourceSha":"cf69a4a0","commits":3},
               {"blob":"c","commit":"1f10ff93dcb1a0f5e6c7b8a9d0e1f2a3b4c5d6e7","date":"2026-04-19T09:12:00+00:00","sourceSha":"03ecb679","commits":1}]}}}
          """
            .trimIndent(),
      )
    // The same strip in project mode: computed from the local repo ([ServeProjectHistory]), entries
    // link to this server's content-addressed lane, the newest entry is not "current", and the head
    // carries the "published baselines" label.
    val viewerHistoryLocal =
      ServeWeb.viewerPage(
        previews.first { it.id.endsWith("ProfileScreenPreview") },
        token,
        historyLocalRenders = true,
        historyInlineJson =
          """
          {"formatVersion":"compose-preview-history/v1","generatedFrom":"df4aa9c00fcc8b1747e159b71d3fbc75cdc27b80",
           "previews":{"${previews.first { it.id.endsWith("ProfileScreenPreview") }.id}":{
             "path":"renders/samples:compose-m3/ProfileScreenPreview.png","observations":5,
             "unstable":false,"flapCount":0,"versions":[
               {"blob":"1c9a3f6b2d4e5f708192a3b4c5d6e7f809a1b2c3","commit":"df4aa9c00fcc8b1747e159b71d3fbc75cdc27b80","date":"2026-05-22T11:08:37+00:00","sourceSha":"57ac24f3","commits":1},
               {"blob":"2d8b4a7c3e5f60718293a4b5c6d7e8f90a1b2c3d","commit":"8b9f6f2bc953756edcb13963e09cd57c54866570","date":"2026-05-07T08:34:51+00:00","sourceSha":"cf69a4a0","commits":3},
               {"blob":"3e7c5b8d4f60718293a4b5c6d7e8f90a1b2c3d4e","commit":"1f10ff93dcb1a0f5e6c7b8a9d0e1f2a3b4c5d6e7","date":"2026-04-19T09:12:00+00:00","sourceSha":"03ecb679","commits":1}]}}}
          """
            .trimIndent(),
      )
    val viewer =
      ServeWeb.viewerPage(
        previews
          .first { it.id.endsWith("ProfileScreenPreview") }
          .copy(
            sourceFile = "src/main/kotlin/com/example/ProfileScreen.kt",
            section = "Screens",
            // Two published motion captures, covering the Motion chip and the (hidden at rest)
            // per-capture picker.
            motion =
              listOf(
                ServeMotion(
                  id = "screens__profile__interaction",
                  kind = "interaction",
                  caption = "Tap the avatar",
                ),
                ServeMotion(
                  id = "screens__profile__anim",
                  kind = "animation",
                  caption = "Header collapse",
                ),
              ),
          ),
        token,
        trust = "unverified",
        // Every page ends with the minimal footer, so the goldens carry the fixed server version
        // on a representative page of each kind — not just the landings.
        version = version,
        // The full preview list feeds the left-hand component nav drawer (default closed) so the
        // harness captures its chrome alongside the default-open overrides drawer.
        siblings = previews,
        // A resolved GitHub source link, captured under the title.
        sourceHref =
          ServeUrls.githubBlobUrl(
            "yschimke/compose-ai-tools",
            "design-artifacts-source",
            "samples/design-catalog-compose-m3",
            "src/main/kotlin/com/example/ProfileScreen.kt",
          ),
        // …and the prefilled "report an issue" link beside it, so the golden captures the bug-
        // reporting affordance (and a signed-in visitor's "as @login" tooltip) next to source.
        reportIssue =
          fixtureReportIssue(
            "com.example.ProfileScreenPreview",
            "Profile screen",
            "src/main/kotlin/com/example/ProfileScreen.kt",
            // With a design reference the viewer's report panel carries a
            // `compose-parity-locator/v1` block and its "Show this issue on" scope control.
            componentId = "Profile/Screen",
            referenceId = "profile-screen-figma",
          ),
        // The "open in playground" handoff, completing the provenance row.
        playgroundHref = "/playground?from=compose-m3/com.example.ProfileScreenPreview",
        parityIssues = parityIssues,
        parityIssuesGeneratedAt = "2026-09-05T20:08:06.488Z",
        // The drawer subtree's directories beside the variant rows: this component's recordings and
        // the catalog about it. Three rows for the three states: a resolved link, one with the
        // catalog's own tooltip, and a registered destination with no host (a row, not a link).
        componentDirectories =
          listOf(
            ServeWeb.ComponentDirectory(
              "related",
              "Samples",
              listOf(
                ServeWeb.ComponentDirectoryRow(
                  "ProfileScreenSample",
                  "/compose-m3-samples/p/profile-screen-sample",
                ),
                ServeWeb.ComponentDirectoryRow(
                  "ProfileHeaderSample",
                  "/compose-m3-samples/p/profile-header-sample",
                  title = "call sites",
                ),
                ServeWeb.ComponentDirectoryRow(
                  "ProfileAvatarSample",
                  "/compose-m3-samples/p/profile-avatar-sample",
                  live = false,
                ),
              ),
            )
          ),
      )
    val spatialViewer =
      ServeWeb.viewerPage(
        ServePreview(
          id = "com.example.XrMusicPreview",
          label = "XR music space",
          spatial = true,
        ),
        token,
        version = version,
        basePath = "/compose-xr",
        isPublic = true,
        spatialSceneUrl = "/compose-xr/spatial/com.example.XrMusicPreview/scene.json",
      )
    // A second viewer carrying the in-browser Wasm tier, so the harness captures the "Run in
    // browser (Wasm)" toggle + iframe seam a CMP catalog session shows.
    val wasmViewer =
      ServeWeb.viewerPage(
        previews.first { it.id.endsWith("CardPreview") },
        token,
        sessionId = "compose-m3",
        trust = "branch:yschimke/compose-ai-tools@design-artifacts/compose-m3",
        wasmSrc = "/wasm/compose-m3/?id=card-filled",
        wasmSameOrigin = true,
      )
    // A trusted catalog served live (ServeCatalogLiveHost): baked snapshots
    // (canApplyOverrides=false), Live enabled, plus the Wasm tier; the case the `staticSnapshot`
    // auto-enable signal exists for.
    val wasmViewerLive =
      ServeWeb.viewerPage(
        previews.first { it.id.endsWith("CardPreview") },
        token,
        sessionId = "compose-m3",
        canApplyOverrides = false,
        hasLiveStream = true,
        trust = "branch:yschimke/compose-ai-tools@design-artifacts/compose-m3",
        wasmSrc = "/wasm/compose-m3/?id=card-filled",
        wasmSameOrigin = true,
      )
    // The signed-out view of a catalog whose live lane is behind GitHub auth, as anonymous visitors
    // to a public box see it.
    val viewerSignIn =
      ServeWeb.viewerPage(
        previews.first { it.id.endsWith("CardPreview") },
        token,
        sessionId = "compose-m3",
        canApplyOverrides = false,
        hasLiveStream = true,
        trust = "branch:yschimke/compose-ai-tools@design-artifacts/compose-m3",
        liveAuthPrompt =
          ServeWeb.LiveAuthPrompt(loginHref = "/auth/github/start?return=%2Fp%2FCardPreview"),
      )
    // A trusted catalog served live whose preview declares knobs: snapshots stay baked but the
    // carried daemon re-renders overrides on demand (canRenderOverrides=true), so the knob controls
    // render enabled.
    val viewerCatalogKnobs =
      ServeWeb.viewerPage(
        knobPreview,
        token,
        sessionId = "compose-m3",
        canApplyOverrides = false,
        canRenderOverrides = true,
        hasSvgExport = true,
        hasScrollExport = true,
        hasLiveStream = true,
        trust = "branch:yschimke/compose-ai-tools@design-artifacts/compose-m3",
        wasmSrc = "/wasm/compose-m3/?id=button-filled",
        wasmSameOrigin = true,
        // The app's declared @ThemeCatalog themes (from the live bundle's previews.json), so the
        // App theme selector renders enabled and re-renders via the carried daemon.
        declaredThemes =
          listOf(
            ServeTheme("Brand Light", "com.example.BrandLightThemeCatalog", group = "Brand"),
            ServeTheme("Brand Dark", "com.example.BrandDarkThemeCatalog", group = "Brand"),
          ),
        // The source + "report an issue" row, on the fixture captured with the real stylesheet (see
        // STYLED_FIXTURES in pages-snapshot.spec.mjs), so painting changes move a baseline.
        sourceHref =
          ServeUrls.githubBlobUrl(
            "yschimke/compose-ai-tools",
            "design-artifacts-source",
            "samples/design-catalog-compose-m3",
            "src/main/kotlin/com/example/Button.kt",
          ),
        reportIssue =
          fixtureReportIssue(
            knobPreview.id,
            knobPreview.label,
            "src/main/kotlin/com/example/Button.kt",
          ),
        // …and the third link in the row: the Figma node this preview is specified by, which only a
        // catalog publishing Figma-backed references names.
        figmaSpec = fixtureFigmaSpec,
      )
    // A catalog served under its canonical path (/meshcore-mobile/) rather than ?session=: links
    // stay on the path and drop &session=.
    val landingPath =
      ServeWeb.landingPage(
        "meshcore-mobile",
        previews,
        token,
        sessionId = "meshcore-mobile",
        trust = "branch:yschimke/meshcore-mobile@design-artifacts/meshcore-mobile",
        isPublic = true,
        hasHomeIndex = true,
        basePath = "/meshcore-mobile",
        version = version,
        // meshcore-mobile publishes Figma-backed references, so its landing offers both the
        // reference comparison and the parity dashboard.
        hasReferenceComparison = true,
        hasParityView = true,
        designToolLabel = "Figma",
        // The footer's Changelog entry with a prefixed href (the site fixture below carries the
        // rooted one).
        changelogHref = "/meshcore-mobile/feed.xml",
      )
    // The same catalog as a top-level site ([ServeSites]) rooted on its own hostname: no back
    // button and every link rooted rather than prefixed.
    val landingSite =
      ServeWeb.landingPage(
        "meshcore-mobile",
        previews,
        token,
        sessionId = "meshcore-mobile",
        trust = "branch:yschimke/meshcore-mobile@design-artifacts/meshcore-mobile",
        isPublic = true,
        // The two lines that make it a site: no home index to link back to, no path prefix, and
        // the session carried by the ORIGIN rather than a `?session=` on every href.
        hasHomeIndex = false,
        basePath = "",
        sessionInOrigin = true,
        version = version,
        hasReferenceComparison = true,
        hasParityView = true,
        designToolLabel = "Figma",
        // A site host's landing is its front door, so it carries the sign-in.
        githubAuth = ServeWeb.GitHubAuthStatus(loginHref = "/auth/github/start?return=%2F"),
        changelogHref = "/feed.xml",
      )
    val viewerPath =
      ServeWeb.viewerPage(
        previews.first { it.id.endsWith("ProfileScreenPreview") },
        token,
        sessionId = "meshcore-mobile",
        trust = "branch:yschimke/meshcore-mobile@design-artifacts/meshcore-mobile",
        basePath = "/meshcore-mobile",
        siblings = previews,
        // Captures the provenance link and the Spec lane chip for a catalog with Figma-backed
        // references.
        figmaSpec = fixtureFigmaSpec,
        designReference = fixtureDesignReference,
        hasDesignAnnotations = true,
        referenceAnnotations =
          listOf(
            DesignAnnotation(
              kind = AnnotationKind.TYPOGRAPHY,
              bounds = AnnotationBounds(x = 20, y = 28, width = 160, height = 42),
              label = "titleLarge 22sp/28sp",
              role = "Ada Lovelace",
              detail =
                mapOf(
                  "token" to "titleLarge",
                  "fontFamily" to "Roboto Flex",
                  "fontSize" to "22sp",
                  "fontWeight" to "400",
                  "lineHeight" to "28sp",
                  "fontVariationSettings" to "'opsz' 22, 'wdth' 100, 'wght' 400",
                ),
            ),
            DesignAnnotation(
              kind = AnnotationKind.TYPOGRAPHY,
              bounds = AnnotationBounds(x = 20, y = 92, width = 116, height = 18),
              label = "bodyMedium 14sp/20sp",
              role = "Analytical engine",
              detail =
                mapOf(
                  "token" to "bodyMedium",
                  "fontFamily" to "Roboto Flex",
                  "fontSize" to "14sp",
                  "fontWeight" to "400",
                  "lineHeight" to "20sp",
                  "fontVariationSettings" to "'opsz' 14, 'wdth' 100, 'wght' 400",
                ),
            ),
          ),
        // The viewer carries the same footer entry as its landing — captured so the Changelog
        // link is diffed on the page a visitor is most often on when they want to know what moved.
        changelogHref = "/meshcore-mobile/feed.xml",
      )
    // The default-value deep link: same catalog and reference as [viewerPath], on a preview whose
    // id names its theme (`…__light`), so `?uiMode=light` spells out the default rather than
    // overriding. `pages-snapshot` navigates `?uiMode=light&mode=spec&specView=diff`; the chip and
    // readout must show the live match, not the pinned-theme "baseline-only" fallback.
    val viewerSpecDefaultTheme =
      ServeWeb.viewerPage(
        ServePreview(
          "profile-screen__ideal__default__light",
          "Profile screen",
          section = "Screens",
          componentId = "ProfileScreen",
        ),
        token,
        sessionId = "meshcore-mobile",
        trust = "branch:yschimke/meshcore-mobile@design-artifacts/meshcore-mobile",
        basePath = "/meshcore-mobile",
        siblings = previews,
        figmaSpec = fixtureFigmaSpec,
        designReference = fixtureDesignReference,
        // The compare strip under the render (`docs/design/COMPARE_NAVIGATION.md` §3.1): a scored
        // variant, a worse one, and an unmapped one, covering all three strip states.
        componentVariants =
          listOf(
            ServeWeb.ComponentVariant(
              previewId = "profile-screen__ideal__default__light",
              variant = "default · light",
              referenceId = "contact-chat-figma",
              matchPercent = 96.4,
            ),
            ServeWeb.ComponentVariant(
              previewId = "profile-screen__ideal__default__dark",
              variant = "default · dark",
              referenceId = "contact-chat-figma-dark",
              matchPercent = 88.1,
            ),
            ServeWeb.ComponentVariant(
              previewId = "profile-screen__ideal__no-avatar__light",
              variant = "no avatar",
              referenceId = "contact-chat-figma-no-avatar",
              matchPercent = 71.9,
            ),
            ServeWeb.ComponentVariant(
              previewId = "profile-screen__ideal__long-name__light",
              variant = "long name",
            ),
          ),
      )
    // A Remote Compose viewer like preview.coo.ee's `remote-m3`: one `.rc` document drawable by
    // several players, the only fixture carrying the full renderer picker (chip, combo with an
    // unavailable option, "compare players" link, SVG toggle).
    //
    // The delivery branch's publish history, newest first, each stamping its source commit.
    val catalogRevisions =
      listOf(
        ServeCatalogRevision.Revision(
          "46440dd86c24b2da6054ccab587e59fba4b15c7e",
          "2026-08-13T09:42:57Z",
          "0b0c2063",
        ),
        ServeCatalogRevision.Revision(
          "41c7a15fd21f52e7c6a959a0c441eb600ca46d4f",
          "2026-08-13T07:10:54Z",
          "b34eff53",
        ),
        ServeCatalogRevision.Revision(
          "421350e5cae04212a193cc8137be1c337a9d5396",
          "2026-08-12T15:42:44Z",
          "7b573ecc",
        ),
        ServeCatalogRevision.Revision(
          "4f7c1ae06b177603984734dc4fa3c0ea365e71e0",
          "2026-08-12T09:05:11Z",
          null,
        ),
      )
    val viewerRcPlayers =
      ServeWeb.viewerPage(
        ServePreview(
          "appcard__ideal__default__compact",
          "App card",
          section = "Cards",
          componentId = "AppCard",
        ),
        token,
        sessionId = "remote-m3",
        basePath = "/remote-m3",
        canApplyOverrides = false,
        canRenderOverrides = true,
        hasLiveStream = true,
        hasSvgExport = true,
        hasRemoteComposeDoc = true,
        // camaelon-js + cmp-wasm play in the browser, androidx-view + androidx-embedded render
        // through the daemon; cmp-android and cmp-jvm are unavailable.
        enabledRcPlayers = listOf("camaelon-js", "cmp-wasm", "androidx-view", "androidx-embedded"),
        trust = "branch:yschimke/compose-ai-tools@design-artifacts/remote-m3",
      )
    // The same Remote Compose preview behind GitHub auth (as with `--github-auth`), the one fixture
    // with both the renderer combo and the sign-in link, which must stand apart in the toolbar.
    val viewerRcSignIn =
      ServeWeb.viewerPage(
        ServePreview(
          "appcard__ideal__default__compact",
          "App card",
          section = "Cards",
          componentId = "AppCard",
        ),
        token,
        sessionId = "remote-m3",
        basePath = "/remote-m3",
        canApplyOverrides = false,
        canRenderOverrides = true,
        hasLiveStream = true,
        hasSvgExport = true,
        hasRemoteComposeDoc = true,
        enabledRcPlayers = listOf("camaelon-js", "cmp-wasm", "androidx-view", "androidx-embedded"),
        trust = "branch:yschimke/compose-ai-tools@design-artifacts/remote-m3",
        liveAuthPrompt =
          ServeWeb.LiveAuthPrompt(
            loginHref =
              "/auth/github/start?return=%2Fremote-m3%2Fp%2Fappcard__ideal__default__compact"
          ),
      )
    // A Remote Compose preview whose design target and implementation both come from its paired
    // Wear M3 catalog (the public remote-m3/Card shape).
    //
    // The compare strip with no baseline at all (no mapped reference, nothing paired): variants
    // alone, not empty reference frames.
    val viewerStripNoBaseline =
      ServeWeb.viewerPage(
        ServePreview(
          "message-list__ideal__default__narrow",
          "Message List",
          section = "Writing and messages",
          componentId = "message-list",
        ),
        token,
        sessionId = "tunjid-heron",
        basePath = "/tunjid-heron",
        catalogName = "Heron",
        componentVariants =
          listOf(
            ServeWeb.ComponentVariant(
              previewId = "message-list__ideal__default__compact",
              variant = "ideal/default/compact",
            ),
            ServeWeb.ComponentVariant(
              previewId = "message-list__ideal__default__medium",
              variant = "ideal/default/medium",
            ),
            ServeWeb.ComponentVariant(
              previewId = "message-list__ideal__default__narrow",
              variant = "ideal/default/narrow",
            ),
          ),
      )
    val viewerRcParallel =
      ServeWeb.viewerPage(
        ServePreview(
          "card__ideal__default__compact",
          "Card",
          section = "Cards",
          componentId = "Card",
        ),
        token,
        sessionId = "remote-m3",
        basePath = "/remote-m3",
        hasRemoteComposeDoc = true,
        enabledRcPlayers = listOf("camaelon-js", "cmp-wasm", "androidx-view", "androidx-embedded"),
        pairedDesignSource =
          ServeWeb.SpecSource(
            id = "kit",
            label = "Figma",
            rasterUrl = "/wear-m3-catalog/reference/card-figma.png",
            provenance = "Figma reference mapped by the paired Wear M3 card.",
          ),
        parallelSource =
          ServeWeb.SpecSource(
            id = "parallel",
            label = "Wear M3",
            rasterUrl = "/wear-m3-catalog/render/card__ideal__default.png",
            provenance = "Wear M3's own render under that catalog's theme and knobs.",
          ),
        parallelLayers = true,
        // The compare strip's second baseline, which only a paired catalog has: each row carries
        // the design reference and the sibling's render, and the source picker chooses.
        componentVariants =
          listOf(
            ServeWeb.ComponentVariant(
              previewId = "card__ideal__default__compact",
              variant = "default",
              referenceId = "card-figma",
              matchPercent = 96.4,
              parallelRenderUrl = "/wear-m3-catalog/render/card__ideal__default.png",
            ),
            ServeWeb.ComponentVariant(
              previewId = "card__ideal__outlined__compact",
              variant = "outlined",
              referenceId = "card-outlined-figma",
              matchPercent = 88.1,
              parallelRenderUrl = "/wear-m3-catalog/render/card__ideal__outlined.png",
            ),
            // A variant the sibling does not draw and one the design file does not map; both frames
            // stay empty.
            ServeWeb.ComponentVariant(
              previewId = "card__ideal__long__compact",
              variant = "long text",
            ),
          ),
        trust = "branch:yschimke/wear-m3-catalog@design-artifacts/remote-m3",
      )
    // The same rich viewer, pinned: every lane renders current code, so the pin removes all of them
    // (Live, combo, SVG, download, inspection layers) while the stage keeps the named publish.
    val viewerPinnedLanes =
      ServeWeb.viewerPage(
        ServePreview(
          "appcard__ideal__default__compact",
          "App card",
          section = "Cards",
          componentId = "AppCard",
        ),
        token,
        sessionId = "remote-m3",
        basePath = "/remote-m3",
        canApplyOverrides = false,
        canRenderOverrides = true,
        hasLiveStream = true,
        hasSvgExport = true,
        hasRemoteComposeDoc = true,
        hasA11yOverlay = true,
        hasDesignAnnotations = true,
        enabledRcPlayers = listOf("camaelon-js", "cmp-wasm", "androidx-view", "androidx-embedded"),
        trust = "branch:yschimke/compose-ai-tools@design-artifacts/remote-m3",
        revisions =
          ServeWeb.CatalogRevisions(
            pinned = catalogRevisions[1].commit,
            revisions = catalogRevisions,
            repo = "yschimke/compose-ai-tools",
          ),
      )
    // The Wear counterpart of [viewerPath]: Size offers watch shapes and Orientation is dropped.
    val viewerWearScreen =
      ServeWeb.viewerPage(
        ServePreview(
          "settings-complication",
          "Settings complication",
          section = "Screens",
          componentId = "SettingsComplication",
        ),
        token,
        sessionId = "home-assistant-wear",
        canApplyOverrides = true,
        basePath = "/home-assistant-wear",
        trust = "branch:yschimke/home-assistant-wear@design-artifacts/home-assistant-wear",
      )
    // A daemon-backed viewer whose module declares `@ThemeCatalog` themes, adding an "App theme"
    // selector grouped by `@ThemeCatalog(group=…)`.
    val viewerThemes =
      ServeWeb.viewerPage(
        previews.first { it.id.endsWith("ProfileScreenPreview") }.copy(uiMode = 0x20),
        token,
        sessionId = "compose-m3",
        canApplyOverrides = true,
        declaredThemes =
          listOf(
            ServeTheme("Brand Light", "com.example.BrandLightThemeCatalog", group = "Brand"),
            ServeTheme("Brand Dark", "com.example.BrandDarkThemeCatalog", group = "Brand"),
            ServeTheme("High Contrast", "com.example.HighContrastThemeCatalog"),
          ),
      )
    // The crowded-toolbar case: the full M3 baseline + contrast theme set (eight theme chips), as
    // published `compose-m3` has. Covers the single-row bar with shrinking, then scrolling, chips.
    val viewerThemeOverflow =
      ServeWeb.viewerPage(
        previews.first { it.id.endsWith("ProfileScreenPreview") }.copy(uiMode = 0x20),
        token,
        siblings = previews,
        sessionId = "compose-m3",
        canApplyOverrides = true,
        declaredThemes =
          listOf(
            ServeTheme("Baseline Dark", "com.example.BaselineDarkThemeCatalog"),
            ServeTheme("Baseline Light", "com.example.BaselineLightThemeCatalog"),
            ServeTheme("Dark High Contrast", "com.example.DarkHighContrastThemeCatalog"),
            ServeTheme("Dark Medium Contrast", "com.example.DarkMediumContrastThemeCatalog"),
            ServeTheme("Light High Contrast", "com.example.LightHighContrastThemeCatalog"),
            ServeTheme("Light Medium Contrast", "com.example.LightMediumContrastThemeCatalog"),
          ),
      )
    // A daemon-backed viewer for a `@FocusedPreview`, showing the "Keyboard focus" detected-feature
    // control.
    val viewerFocus =
      ServeWeb.viewerPage(
        ServePreview("com.example.FocusRingPreview", "Focus ring", supportsFocus = true),
        token,
        sessionId = "compose-m3",
        canApplyOverrides = true,
      )
    // A daemon-backed viewer with every inspection layer (a11y focus map, typography / theme
    // attributes). The harness captures it as served and with layers ticked (see
    // `serve-viewer-inspect` states).
    val viewerInspect =
      ServeWeb.viewerPage(
        ServePreview("com.example.ProfileCardPreview", "Profile card"),
        token,
        sessionId = "compose-m3",
        canApplyOverrides = true,
        hasA11yOverlay = true,
        hasDesignAnnotations = true,
      )
    // The other lane behind the Typography layer: a published catalog with no daemon, whose
    // `annotations/index.json` carries typography measured over the baked frame. Its own fixture to
    // show a page without daemon controls still offers a working layer (ticked in the
    // `serve-viewer-published-typography` `layers` state).
    val viewerPublishedTypography =
      ServeWeb.viewerPage(
        ServePreview("button-filled__ideal__default__light", "Filled button (light)"),
        token,
        sessionId = "compose-m3",
        canApplyOverrides = false,
        hasPublishedTypography = true,
      )
    // An SVG-exporting viewer opened in the exploded 3D view. The harness stubs its stage with the
    // committed `_render-placeholder-exploded.svg` (generated by `ExplodedSvgFixtureTest`), so the
    // capture shows the real projection.
    val viewerExploded =
      ServeWeb.viewerPage(
        ServePreview("com.example.ProfileCardPreview", "Profile card"),
        token,
        sessionId = "compose-m3",
        canApplyOverrides = true,
        hasSvgExport = true,
        hasScrollExport = true,
      )
    // A viewer offering the Source chip; its own fixture because the chip changes the control row
    // and the `source-panel` state needs it.
    val viewerSource =
      ServeWeb.viewerPage(
        ServePreview("com.example.ProfileCardPreview", "Profile card"),
        token,
        sessionId = "compose-m3",
        canApplyOverrides = true,
        usageHref = "/usage/com.example.ProfileCardPreview",
      )
    // The samples page role as a pair of goldens from one set of inputs:
    // `serve-viewer-samples-as-catalog.html` is the same preview at [ServeWeb.PageRole.CATALOG], so
    // the diff shows exactly what the role does. Carries a design reference because dropping
    // comparisons is part of the role.
    val samplesPreview =
      ServePreview(
        "com.example.ButtonSample",
        "Button sample",
        componentParameters =
          listOf(
            ServeComponentParameter("onClick", "() -> Unit"),
            ServeComponentParameter("enabled", "Boolean", hasDefault = true),
            ServeComponentParameter("content", "RowScope.() -> Unit", composableSlot = true),
          ),
      )
    val samplesReference =
      DesignReference(
        id = "button-sample-figma",
        previewId = samplesPreview.id,
        label = "Button",
        raster = DesignReferenceRaster(path = "references/button-sample-figma.png"),
        source = DesignReferenceSource(provider = "figma"),
      )
    fun samplesFixture(role: ServeWeb.PageRole) =
      ServeWeb.viewerPage(
        samplesPreview,
        token,
        sessionId = "compose-m3-samples",
        version = version,
        pageRole = role,
        usageHref = "/usage/com.example.ButtonSample",
        designReference = samplesReference,
        canApplyOverrides = true,
        // Several call sites, since with one preview the drawer is omitted.
        siblings =
          listOf(
            samplesPreview,
            ServePreview("com.example.ButtonWithIconSample", "Button with icon sample"),
            ServePreview("com.example.TextButtonSample", "Text button sample"),
          ),
        // The back-link to the kit component this sample explains, derived from that catalog's
        // `related` declaration ([ServeRelatedCatalogs.inverse]). On both goldens, because it
        // follows from the declaration, not the role.
        componentDirectories =
          listOf(
            ServeWeb.ComponentDirectory(
              "about",
              // The source catalog's own heading: `related` is directed, so only the catalog's name
              // reads true in both directions.
              "Compose Material 3",
              listOf(ServeWeb.ComponentDirectoryRow("Button", "/compose-m3/p/button-filled")),
            )
          ),
      )
    val samplesViewer = samplesFixture(ServeWeb.PageRole.SAMPLES)
    val samplesViewerAsCatalog = samplesFixture(ServeWeb.PageRole.CATALOG)
    // A viewer with motion captures, on its own fixture because its state leaves a lane open and
    // would otherwise contaminate `serve-viewer`'s later states. Two captures so the per-capture
    // menu appears, with realistic (long) captions.
    val viewerMotion =
      ServeWeb.viewerPage(
        ServePreview(
          "com.example.SwitchPreview",
          "Switch",
          motion =
            listOf(
              ServeMotion(
                id = "switch-on__ideal__default__light",
                kind = "interaction",
                caption =
                  "Toggle on. The thumb travels the full width of the track and the container " +
                    "recolours to the checked state as it lands.",
              ),
              ServeMotion(
                id = "switch-on__ideal__default__light__anim",
                kind = "animation",
                caption =
                  "Thumb settle. Released mid-travel, the thumb overshoots and settles back " +
                    "through the theme's spatial spring rather than snapping to the stop.",
              ),
            ),
        ),
        token,
        sessionId = "compose-m3",
      )
    // A daemon-backed `@GestureHintPreview` on an Android session (`gesturesRenderable = true`),
    // showing the "Show gesture hints" control.
    val viewerGestures =
      ServeWeb.viewerPage(
        ServePreview("com.example.OneHandedPreview", "One-handed", supportsGestures = true),
        token,
        sessionId = "wear-m3",
        canApplyOverrides = true,
        gesturesRenderable = true,
      )
    // The same preview on a desktop session (`gesturesRenderable = false`): the override is
    // ignored, so the "Detected features" group is omitted.
    val viewerGesturesDesktop =
      ServeWeb.viewerPage(
        ServePreview("com.example.OneHandedPreview", "One-handed", supportsGestures = true),
        token,
        sessionId = "compose-m3",
        canApplyOverrides = true,
      )
    // A catalog whose previews carry per-theme variants, so the landing shows the sticky light/dark
    // toggle and tags each card with its baked theme for client-side filtering.
    val landingThemed =
      ServeWeb.landingPage(
        "compose-m3",
        themedPreviews,
        token,
        sessionId = "compose-m3",
        trust = "branch:yschimke/compose-ai-tools@design-artifacts/compose-m3",
        isPublic = true,
        hasHomeIndex = true,
        // Both comparison actions, so the golden captures the split summary line ("compare SVG ·
        // compare RC players") the visual-diff bot shoots.
        hasSvgComparison = true,
        hasRcComparison = true,
        version = version,
      )
    // Catalog-theme sync: pages framed in the system's own colours (the `:root` override
    // `ServeThemeCss` projects from `tokens.dtcg.json`). A dark-first catalog on the landing and a
    // light-first one on the viewer, each shot in light and dark.
    val landingCatalogPalette =
      ServeWeb.landingPage(
        "wear-m3",
        themedPreviews,
        token,
        sessionId = "wear-m3",
        trust = "branch:yschimke/compose-ai-tools@design-artifacts/wear-m3",
        isPublic = true,
        hasHomeIndex = true,
        version = version,
        declaredSurface = "dark",
        themeCss = ServeThemeCss.fromDtcg(wearM3Tokens)!!,
      )
    val viewerCatalogPalette =
      ServeWeb.viewerPage(
        previews.first { it.id.endsWith("ProfileScreenPreview") },
        token,
        sessionId = "jetnews",
        trust = "branch:yschimke/compose-samples@design-artifacts/jetnews",
        siblings = previews,
        catalogTitle = "JetNews",
        themeCss = ServeThemeCss.fromDtcg(jetNewsTokens)!!,
      )
    // The format-comparison surface. Both targets are advertised to cover the format tabs;
    // light/dark pairs cover the theme control and same-theme URL wiring.
    val formatComparison =
      ServeWeb.comparisonPage(
        "compose-m3",
        themedPreviews,
        token,
        sessionId = "compose-m3",
        trust = "branch:yschimke/compose-ai-tools@design-artifacts/compose-m3",
        isPublic = true,
        version = version,
        hasSvgFor = { it.startsWith("button-filled") || it.startsWith("switch-on") },
        hasRemoteComposeFor = { it.startsWith("button-filled") },
        referencesFor = { id ->
          if (id.startsWith("button-filled"))
            listOf(
              DesignReference(
                id = "design-$id",
                previewId = id,
                label = "Figma filled button",
                raster =
                  DesignReferenceRaster("references/design-$id.png", width = 320, height = 160),
                source = DesignReferenceSource(provider = "figma", revision = "fixture-42"),
                // The score baked into `references/index.json`, which seeds the wall's rows and
                // order before any browser measurement.
                match =
                  DesignReferenceMatch(
                    percent = 82.4,
                    changedPercent = 3.1,
                    scoreVersion = ServeDesignReferenceStore.SCORE_VERSION,
                  ),
              )
            )
          else emptyList()
        },
        parallelSourceFor = { preview ->
          if (preview.id.startsWith("button-filled"))
            ServeWeb.SpecSource(
              id = "parallel",
              label = "Wear M3",
              rasterUrl = "/wear-m3/render/${preview.id}.png",
              provenance = "Wear M3 fixture render",
            )
          else null
        },
        reportIssue = fixtureWallReportIssue(),
        parityIssues = parityIssues,
        // The wall's Bugs column is a disclosure, and a snapshot that says so — the harness has to
        // capture the line it collapses to AND the date under the panel, or neither is diffed.
        parityIssuesGeneratedAt = "2026-09-05T20:08:06.488Z",
      )
    // The Remote Compose player wall: `?format=rc`, backed by a published `rc-compare` manifest.
    // Only rc is advertised, so the page opens on the wall.
    val rcLanesComparison =
      ServeWeb.comparisonPage(
        "remote-m3",
        themedPreviews,
        token,
        sessionId = "remote-m3",
        trust = "branch:yschimke/compose-ai-tools@design-artifacts/remote-m3",
        isPublic = true,
        rcCompare = rcCompareFixture(themedPreviews),
      )
    // The wall for a run that opted into only some players (like wear-m3-catalog); absent lanes are
    // named under the summary.
    val rcLanesPartialComparison =
      ServeWeb.comparisonPage(
        "remote-m3",
        themedPreviews,
        token,
        sessionId = "remote-m3",
        trust = "branch:yschimke/compose-ai-tools@design-artifacts/remote-m3",
        isPublic = true,
        rcCompare = rcCompareFixture(themedPreviews, lanes = setOf("baked", "js", "cmp-wasm")),
      )

    // The same partial run on a host that can draw the server-side players itself: those columns
    // are filled on request and badged.
    val rcLanesLiveComparison =
      ServeWeb.comparisonPage(
        "remote-m3",
        themedPreviews,
        token,
        sessionId = "remote-m3",
        trust = "branch:yschimke/compose-ai-tools@design-artifacts/remote-m3",
        isPublic = true,
        rcCompare = rcCompareFixture(themedPreviews, lanes = setOf("baked", "js", "cmp-wasm")),
        // The host offers both server-side players but the wall takes only cmp-jvm: on an Android
        // daemon `?rcPlayer=androidx-embedded` returns the baked capture, duplicating its
        // neighbour. `badge` has no document, so one column is partly empty.
        liveRcPlayersFor = { previewId ->
          if (previewId.startsWith("badge")) emptyList()
          else
            listOf(
              RcPlayerBackend.ANDROIDX_VIEW,
              RcPlayerBackend.ANDROIDX_EMBEDDED,
              RcPlayerBackend.CMP_JVM,
            )
        },
      )
    val comparisonReferences =
      listOf(
        DesignReference(
          id = "design-button-filled-light",
          previewId = themedPreviews.first().id,
          label = "Figma filled button",
          raster = DesignReferenceRaster("references/design-button-filled-light.png", 320, 160),
          source =
            DesignReferenceSource(
              provider = "figma",
              uri = "https://www.figma.com/file/example",
              revision = "fixture-42",
              attributes = mapOf("nodeId" to "12:34"),
            ),
        ),
        DesignReference(
          id = "design-button-filled-review",
          previewId = themedPreviews.first().id,
          label = "Review revision",
          raster = DesignReferenceRaster("references/design-button-filled-review.png", 320, 160),
          source = DesignReferenceSource(provider = "penpot", revision = "fixture-43"),
        ),
      )
    // The design-parity dashboard, built through the real [ServeParityDashboard] so the golden also
    // pins the derivation (light/dark coverage folding, lane merge by time, the "needs a look"
    // band).
    val parityDashboard =
      ServeParityDashboard.build(
        previews = themedPreviews,
        // Button is mapped; Switch and Badge are not — a realistic, partly-covered catalog.
        hasReference = { it.startsWith("button-filled") },
        referenceIdFor = {
          if (it == "button-filled__ideal__default__light") "design-button-filled-light" else null
        },
        activity =
          ParityActivity(
            generatedAt = "2026-07-17T09:30:00.000Z",
            windowDays = 30,
            code =
              CodeLane(
                repo = "yschimke/compose-ai-tools",
                ref = "main",
                events =
                  listOf(
                    CodeEvent(
                      sha = "4e73ec2b9f0a1c3d5e7f9a1b3c5d7e9f0a1b3c5d",
                      subject = "fix(button): tighten the filled button's label padding to 16dp",
                      at = "2026-07-16T14:22:00.000Z",
                      author = "yschimke",
                      previewIds = listOf("button-filled__ideal__default__light"),
                      components = listOf("Button/Filled"),
                    ),
                    CodeEvent(
                      sha = "b842ee3c1d5f7a9b1c3d5e7f9a1b3c5d7e9f0a1b",
                      subject = "feat(badge): add the small/large size axis",
                      at = "2026-07-14T08:05:00.000Z",
                      author = "yschimke",
                      previewIds = listOf("badge"),
                      components = listOf("Badge"),
                    ),
                  ),
              ),
            figma =
              FigmaLane(
                fileKey = "ocdacdEsnHipMJD3egzxKb",
                fileName = "Material 3 Design Kit",
                versions =
                  listOf(
                    FigmaVersionEvent(
                      id = "3928471",
                      at = "2026-07-15T11:40:00.000Z",
                      label = "Buttons: 16dp label padding",
                      description = "Aligns the filled/tonal pair with the spec update.",
                      author = "Dana",
                    )
                  ),
                comments =
                  listOf(
                    FigmaCommentEvent(
                      id = "9182",
                      at = "2026-07-16T09:02:00.000Z",
                      message = "The switch track reads 2dp short against the M3 spec sheet.",
                      author = "Dana",
                      nodeId = "51592:4768",
                      previewIds = listOf("switch-on__ideal__default__light"),
                      components = listOf("Switch/On"),
                    ),
                    FigmaCommentEvent(
                      id = "9165",
                      at = "2026-07-15T16:31:00.000Z",
                      message = "Padding change landed here too — matching the code side.",
                      author = "Dana",
                      resolved = true,
                      nodeId = "57994:2227",
                      previewIds = listOf("button-filled__ideal__default__light"),
                      components = listOf("Button/Filled"),
                    ),
                  ),
              ),
            gaps =
              listOf(
                MappingGap(
                  kind = MappingGap.Kind.UNMAPPED_DESIGN_NODE,
                  detail = "Published in the kit, but no design-map entry names it.",
                  ref = "figma:ocdacdEsnHipMJD3egzxKb/51827:5859",
                  component = "Bottom sheet / Modal",
                ),
                MappingGap(
                  kind = MappingGap.Kind.UNRENDERED_REFERENCE,
                  detail = "Figma render returned no image for this node; reference not published.",
                  ref = "figma:ocdacdEsnHipMJD3egzxKb/51159:5105",
                  component = "Bottom app bar",
                ),
              ),
          ),
      )
    val parity =
      ServeWeb.parityPage(
        moduleLabel = "compose-m3",
        dashboard = parityDashboard,
        token = token,
        sessionId = "compose-m3",
        basePath = "/compose-m3",
        trust = "branch:yschimke/compose-ai-tools@design-artifacts/compose-m3",
        isPublic = true,
        version = version,
        displayTitle = "Compose Material 3",
        hasReferenceFor = { it.startsWith("button-filled") },
        // The catalog names its design tool, so the page's way back out to the whole-catalog
        // comparison table is captured with the same wording as the link that leads here.
        designToolLabel = "Figma",
        parityIssues = parityIssues,
        parityIssuesGeneratedAt = "2026-09-05T20:08:06.488Z",
      )
    val referenceComparison =
      ServeWeb.referenceComparisonPage(
        moduleLabel = "compose-m3",
        preview = themedPreviews.first(),
        reference = comparisonReferences.first(),
        references = comparisonReferences,
        token = token,
        sessionId = "compose-m3",
        trust = "branch:yschimke/compose-ai-tools@design-artifacts/compose-m3",
        isPublic = true,
        version = version,
        overrides = mapOf("fontScale" to "1.5", "knob.label" to "Send;now=x"),
        reportIssue =
          fixtureReportIssue(
            previewId = themedPreviews.first().id,
            label = themedPreviews.first().label,
            sourceFile = themedPreviews.first().sourceFile.orEmpty(),
            componentId = ServeIssueReport.componentIdFor(themedPreviews.first()),
            referenceId = comparisonReferences.first().id,
            variant = ServeIssueReport.variantFor(themedPreviews.first()),
            overrides = mapOf("fontScale" to "1.5", "knob.label" to "Send;now=x"),
            comparison = true,
          ),
        // The derived semantics layers, which give the element selector something to point at on
        // the render side.
        derivedAnnotations = true,
        annotationsSelectable = true,
        // ...and the tag index: a uniquely tagged node with no typography or container tokens
        // produces no annotation box, so only the index exposes it.
        tagIndexAvailable = true,
        // A catalog with an accepted difference, so the acceptance band and its payload are in a
        // golden. The engine fills the band at runtime (collapsed in a file-opened fixture); its
        // output is covered by `serve-web/test/acceptance.test.ts`. The tag index is non-empty
        // because `tagIndexAvailable` is; the two must agree.
        knownDifferences =
          KnownDifferenceScope(
            system = "compose-m3",
            component = ServeIssueReport.componentIdFor(themedPreviews.first()),
            previewId = themedPreviews.first().id,
            referenceId = comparisonReferences.first().id,
            variant = ServeIssueReport.variantFor(themedPreviews.first()),
            overrides = mapOf("fontScale" to "1.5", "knob.label" to "Send;now=x"),
            referenceSha256 = "b7d3f1".repeat(10) + "0123",
            tagIndex =
              mapOf(
                "button-filled-label" to
                  WireTagEntry(
                    count = 1,
                    bounds = AnnotationBounds(x = 46, y = 26, width = 128, height = 20),
                    space = ServeSemanticsTags.RENDER_PIXELS,
                  )
              ),
          ),
        // Both panels annotated: layout boxes agree, type styles don't.
        referenceAnnotations =
          listOf(
            DesignAnnotation(
              kind = AnnotationKind.LAYOUT,
              bounds = AnnotationBounds(x = 12, y = 12, width = 196, height = 48),
              label = "pad 16dp · gap 8dp",
              role = "Button",
              detail = mapOf("padding" to "16", "gap" to "8", "cornerRadius" to "20"),
            ),
            DesignAnnotation(
              kind = AnnotationKind.TYPOGRAPHY,
              bounds = AnnotationBounds(x = 46, y = 26, width = 128, height = 20),
              label = "labelLarge 14sp/20",
              role = "Label",
              detail =
                mapOf(
                  "token" to "labelLarge",
                  "fontFamily" to "Roboto",
                  "fontSize" to "14",
                  "fontWeight" to "500",
                  "lineHeight" to "20",
                  "unit" to "sp",
                ),
            ),
            DesignAnnotation(
              kind = AnnotationKind.TYPOGRAPHY,
              bounds = AnnotationBounds(x = 46, y = 58, width = 128, height = 20),
              label = "labelLarge 14sp/20",
              role = "Secondary label",
              detail =
                mapOf(
                  "token" to "labelLarge",
                  "fontFamily" to "Roboto",
                  "fontSize" to "14",
                  "fontWeight" to "500",
                  "lineHeight" to "20",
                  "unit" to "sp",
                ),
            ),
          ),
        actualAnnotations =
          listOf(
            DesignAnnotation(
              kind = AnnotationKind.LAYOUT,
              bounds = AnnotationBounds(x = 12, y = 12, width = 196, height = 48),
              label = "pad 16dp · gap 8dp",
              role = "Button",
              detail = mapOf("padding" to "16", "gap" to "8", "cornerRadius" to "20"),
            ),
            DesignAnnotation(
              kind = AnnotationKind.TYPOGRAPHY,
              bounds = AnnotationBounds(x = 46, y = 26, width = 128, height = 20),
              label = "bodyMedium 14sp/20",
              role = "Label",
              detail =
                mapOf(
                  "token" to "bodyMedium",
                  "fontFamily" to "Roboto-Medium",
                  "fontSize" to "14",
                  "fontWeight" to "500",
                  "lineHeight" to "20",
                  "unit" to "sp",
                ),
            ),
            DesignAnnotation(
              kind = AnnotationKind.TYPOGRAPHY,
              bounds = AnnotationBounds(x = 46, y = 58, width = 128, height = 20),
              label = "bodyMedium 14sp/20",
              role = "Secondary label",
              detail =
                mapOf(
                  "token" to "bodyMedium",
                  "fontFamily" to "Roboto-Medium",
                  "fontSize" to "14",
                  "fontWeight" to "500",
                  "lineHeight" to "20",
                  "unit" to "sp",
                ),
            ),
            DesignAnnotation(
              kind = AnnotationKind.TYPOGRAPHY,
              bounds = AnnotationBounds(x = 46, y = 90, width = 128, height = 20),
              label = "bodyMedium 14sp/20 wght 700",
              role = "Emphasized label",
              detail =
                mapOf(
                  "token" to "bodyMedium",
                  "fontFamily" to "Roboto-Medium",
                  "fontSize" to "14",
                  "fontWeight" to "700",
                  "lineHeight" to "20",
                  "unit" to "sp",
                ),
            ),
            // The resolved-container layer, kept reachable via the THEME toggle.
            DesignAnnotation(
              kind = AnnotationKind.THEME,
              bounds = AnnotationBounds(x = 12, y = 12, width = 196, height = 48),
              label = "fill #FF6750A4 · radius 20.0dp · border 1.0dp #FF79747E",
              role = "Button",
              detail =
                mapOf(
                  "background" to "#FF6750A4",
                  "cornerRadius" to "20.0dp",
                  "borderColor" to "#FF79747E",
                  "borderWidth" to "1.0dp",
                ),
            ),
          ),
        // The parity run's own verdict for this pair: one finding per category, anchored to the
        // same boxes the redline annotates.
        parityFindings =
          listOf(
            ParityFindingSet(
              referenceId = comparisonReferences.first().id,
              status = "fail",
              reportUrl =
                "https://github.com/yschimke/compose-ai-tools/blob/design-parity/compose-m3/" +
                  "button-filled/report.html",
              findings =
                listOf(
                  ParityFinding(
                    kind = ParityFindingKind.TOKEN,
                    severity = ParityFindingSeverity.ERROR,
                    message = "spacing.padding: 24 vs spec 16 (Δ8)",
                    detail =
                      mapOf(
                        "token" to "spacing.padding",
                        "expected" to "16",
                        "actual" to "24",
                      ),
                    anchors =
                      listOf(
                        ParityAnchor(
                          side = "actual",
                          bounds = AnnotationBounds(x = 12, y = 12, width = 196, height = 48),
                          label = "Button",
                        )
                      ),
                  ),
                  ParityFinding(
                    kind = ParityFindingKind.I18N,
                    severity = ParityFindingSeverity.WARN,
                    message =
                      "\"Send\" risks truncation when localized: ≈154dp expanded vs 131dp " +
                        "available.",
                    anchors =
                      listOf(
                        ParityAnchor(
                          side = "actual",
                          bounds = AnnotationBounds(x = 46, y = 26, width = 128, height = 20),
                          label = "Label",
                        )
                      ),
                  ),
                  ParityFinding(
                    kind = ParityFindingKind.LAYOUT,
                    severity = ParityFindingSeverity.WARN,
                    message = "layout \"Send\": offset (1, -12), size Δ(41, 3) vs reference",
                    anchors =
                      listOf(
                        ParityAnchor(
                          side = "reference",
                          bounds = AnnotationBounds(x = 46, y = 26, width = 128, height = 20),
                        ),
                        ParityAnchor(
                          side = "actual",
                          bounds = AnnotationBounds(x = 46, y = 26, width = 128, height = 20),
                        ),
                      ),
                  ),
                  // The prose-only case, in the same golden: a finding with no geometry keeps its
                  // sentence and is not offered as a control.
                  ParityFinding(
                    kind = ParityFindingKind.CONTRAST,
                    severity = ParityFindingSeverity.INFO,
                    message =
                      "label on container: 4.9:1 — passes AA for 14sp text, below AAA (7:1).",
                    detail = mapOf("ratio" to "4.9", "required" to "4.5"),
                  ),
                ),
            )
          ),
        parityIssues = parityIssues,
        parityIssuesGeneratedAt = "2026-09-05T20:08:06.488Z",
      )
    // The same comparison pinned to an older publish: the banner naming the revision, the way back,
    // and the revision list.
    val referenceComparisonPinned =
      ServeWeb.referenceComparisonPage(
        moduleLabel = "compose-m3",
        preview = themedPreviews.first(),
        reference = comparisonReferences.first(),
        references = comparisonReferences,
        token = token,
        sessionId = "compose-m3",
        trust = "branch:yschimke/compose-ai-tools@design-artifacts/compose-m3",
        isPublic = true,
        version = version,
        revisions =
          ServeWeb.CatalogRevisions(
            pinned = catalogRevisions[2].commit,
            revisions = catalogRevisions,
            repo = "yschimke/compose-ai-tools",
          ),
        reportIssue =
          fixtureReportIssue(
            previewId = themedPreviews.first().id,
            label = themedPreviews.first().label,
            sourceFile = themedPreviews.first().sourceFile.orEmpty(),
            componentId = ServeIssueReport.componentIdFor(themedPreviews.first()),
            referenceId = comparisonReferences.first().id,
            variant = ServeIssueReport.variantFor(themedPreviews.first()),
            comparison = true,
          ),
        // No `tagIndexUrl`, with the reason stated: the published index describes the current
        // render, so a tag selection would use bounds from different pixels. The drag still works.
        tagSelectionNote =
          "Tag selection is off on a pinned revision: the tag index describes the current " +
            "render, not this one. Drag a region instead.",
      )
    // The same page for a dark-first catalog (yschimke/wear-m3-catalog#56). Its stickers are
    // transparent (`showBackground = false`), so white content is only visible if the stage ground
    // resolves; the light-first twin looks identical either way.
    val referenceComparisonDarkFirst =
      ServeWeb.referenceComparisonPage(
        moduleLabel = "wear-m3",
        preview = themedPreviews.first(),
        reference = comparisonReferences.first(),
        references = comparisonReferences,
        token = token,
        sessionId = "wear-m3",
        // The catalog declaring its own stage, exactly as `catalog.json`'s `display.surface` does —
        // not the system-name heuristic, so the fixture pins the declared path.
        declaredSurface = "dark",
        isPublic = true,
        version = version,
      )
    assertTrue(
      referenceComparisonDarkFirst.contains("data-bg-theme=\"dark\""),
      "the dark-first comparison fixture must actually carry the dark stage",
    )
    // The same page for a round device: a Wear capture is a circle in a square PNG, so a ground
    // painted across the panel draws the watch as a rectangle, and with Wear's near-black
    // backgrounds the device boundary vanishes.
    val referenceComparisonRoundDevice =
      ServeWeb.referenceComparisonPage(
        moduleLabel = "wear-m3",
        preview =
          themedPreviews
            .first()
            .copy(
              // Exactly what `samples/design-catalog-wear-m3` states, including the explicit dp
              // alongside the device id.
              deviceFrame = ServeDeviceFrame.from("id:wearos_large_round", 227, 227),
              showBackground = true,
              backgroundColor = 0xFF000000L,
            ),
        reference = comparisonReferences.first(),
        references = comparisonReferences,
        token = token,
        sessionId = "wear-m3",
        declaredSurface = "dark",
        isPublic = true,
        version = version,
      )
    assertTrue(
      referenceComparisonRoundDevice.contains("data-cp-stage-clip=\"1\"") &&
        referenceComparisonRoundDevice.contains("--cp-stage-clip: circle("),
      "the round-device fixture must actually carry the clip",
    )
    // The unpinned twin, on the viewer: the revision list folded away, which is all an ordinary
    // page view of a catalog with a publish history shows.
    val viewerRevisions =
      ServeWeb.viewerPage(
        previews.first { it.id.endsWith("ProfileScreenPreview") },
        token,
        revisions =
          ServeWeb.CatalogRevisions(
            revisions = catalogRevisions,
            repo = "yschimke/compose-ai-tools",
          ),
      )
    // The same page with the revision `<details>` menu forced open, so the list of publishes is
    // captured, not just its trigger.
    val viewerRevisionsOpen =
      viewerRevisions.replace(
        "<details class=\"cp-revisions\">",
        "<details class=\"cp-revisions\" open>",
      )
    assertFalse(
      viewerRevisionsOpen == viewerRevisions,
      "the revision menu's <details> tag changed shape — update this fixture's open-state rewrite",
    )
    // The same menu with `<cp-revision-runs>` answered (markers are drawn client-side, so the
    // payload is inlined). Four revisions split 2 + 2 exercise every state: first head, indented
    // follower, second head with rule, and a `×N` badge; the second run is `open` to capture "at
    // least N".
    val viewerRevisionRuns =
      ServeWeb.viewerPage(
          previews.first { it.id.endsWith("ProfileScreenPreview") },
          token,
          revisions =
            ServeWeb.CatalogRevisions(
              revisions = catalogRevisions,
              repo = "yschimke/compose-ai-tools",
            ),
          revisionRunsInlineJson =
            """
            {"schema":"compose-preview-render-runs/v1","revisions":4,"runs":[
              {"head":"46440dd86c24b2da6054ccab587e59fba4b15c7e","sourceSha":"0b0c2063","commits":2},
              {"head":"421350e5cae04212a193cc8137be1c337a9d5396","sourceSha":"7b573ecc","commits":2,
               "open":true}]}
            """
              .trimIndent(),
        )
        .replace("<details class=\"cp-revisions\">", "<details class=\"cp-revisions\" open>")
    assertTrue(
      viewerRevisionRuns.contains("id=\"cp-revision-runs-data\"") &&
        viewerRevisionRuns.contains("<cp-revision-runs "),
      "the runs fixture must carry both the element and the payload it draws from",
    )
    // The design page's inlined export, run through the real [SvgSanitizer] so the golden is what
    // the server would emit.
    //
    // Nested like a real Figma export (page > cards > slots > component), because `<cp-page-zoom>`
    // drills through that tree; cards and slots are painted since drilling uses the browser's hit
    // test. Node `1:2` is clipped: `getBoundingClientRect()` ignores clipping, so its oversized
    // sweep (twice the clip) would double the render size if the clipped measurement regressed.
    val designPageSvg =
      checkNotNull(
        SvgSanitizer.sanitize(
          """
          <svg xmlns="http://www.w3.org/2000/svg" width="1200" height="800" viewBox="0 0 1200 800" fill="none">
            <defs>
              <clipPath id="clipShimmer"><rect x="230" y="345" width="180" height="180" rx="36"/></clipPath>
            </defs>
            <rect width="1200" height="800" fill="#F7F2FA"/>
            <g data-node-id="1:0"><rect x="40" y="90" width="1140" height="690" fill="none"/></g>
            <g data-node-id="1:9"><rect x="40" y="20" width="560" height="50" rx="16" fill="#EADDFF"/></g>
            <g data-node-id="1:10"><rect x="620" y="20" width="560" height="50" rx="16" fill="#EADDFF"/></g>
            <g data-node-id="1:20">
              <rect x="40" y="90" width="560" height="690" rx="20" fill="#FFFFFF"/>
              <g data-node-id="1:30">
                <rect x="90" y="115" width="460" height="200" rx="12" fill="#F3EDF7"/>
                <g data-node-id="1:1"><circle cx="320" cy="215" r="90" fill="#6750A4"/></g>
              </g>
              <g data-node-id="1:31">
                <rect x="90" y="335" width="460" height="200" rx="12" fill="#F3EDF7"/>
                <g data-node-id="1:2">
                  <g clip-path="url(#clipShimmer)">
                    <rect x="230" y="345" width="180" height="180" rx="36" fill="#6750A4"/>
                    <path d="M470 300L560 390L280 670L190 580Z" fill="#EADDFF" fill-opacity="0.35"/>
                  </g>
                </g>
              </g>
              <g data-node-id="1:32">
                <rect x="90" y="555" width="460" height="200" rx="12" fill="#F3EDF7"/>
                <g data-node-id="1:4"><rect x="200" y="610" width="240" height="90" rx="45" fill="#6750A4"/></g>
              </g>
            </g>
            <g data-node-id="1:21">
              <rect x="620" y="90" width="560" height="690" rx="20" fill="#FFFFFF"/>
              <g data-node-id="1:33">
                <rect x="670" y="115" width="460" height="200" rx="12" fill="#F3EDF7"/>
                <g data-node-id="1:3"><path d="M900 125 L990 305 L810 305 Z" fill="#6750A4"/></g>
              </g>
              <g data-node-id="1:34">
                <rect x="670" y="335" width="460" height="200" rx="12" fill="#F3EDF7"/>
                <g data-node-id="1:5"><rect x="810" y="345" width="180" height="180" rx="90" fill="#6750A4"/></g>
              </g>
              <g data-node-id="1:35">
                <rect x="670" y="555" width="460" height="200" rx="12" fill="#F3EDF7"/>
                <g data-node-id="1:6"><rect x="810" y="565" width="180" height="180" fill="#6750A4"/></g>
              </g>
            </g>
          </svg>
          """
            .trimIndent()
        )
      )

    // A design page: one specimen sheet with the node id of every component on it. Mix: two
    // `manifest` links to published previews, one `convention` match, one `manifest` link to an
    // unpublished preview (outline, no render), one manifest node missing from the export, and four
    // `unlinked` nodes (the component-set grid, both `.Header` and `Header` spellings of the sheet
    // header, and a shape no code implements). Neither header spelling may appear in the markup;
    // each has a real box so a regression would be visible.
    val designPageFixture =
      DesignPage.Builder(
          id = "shape",
          name = "Shape",
          nodeId = "58548:7093",
          frame = PageFrame.Builder(1200.0, 800.0).build(),
          image = PageImage.Builder("shape.svg").build(),
        )
        .also {
          it.nodes =
            listOf(
              pageNode(
                "1:0",
                "Shape set",
                link = PageNodeLink.UNLINKED,
                depth = 1,
                type = "COMPONENT_SET",
              ),
              pageNode("1:9", ".Header", link = PageNodeLink.UNLINKED, depth = 2),
              pageNode(
                "1:10",
                "Header",
                link = PageNodeLink.UNLINKED,
                depth = 2,
                type = "INSTANCE",
              ),
              pageNode(
                "1:1",
                "Shape=Circle",
                code = "ui/Shapes.kt#CircleShape",
                previewId = "com.example.ProfileCardPreview",
              ),
              pageNode(
                "1:2",
                "Shape=Square",
                code = "ui/Shapes.kt#SquareShape",
                previewId = "com.example.ProfileCardPreview",
              ),
              pageNode(
                "1:3",
                "Shape=Triangle",
                code = "ui/Shapes.kt#TriangleShape",
                link = PageNodeLink.CONVENTION,
                confidence = PageNodeConfidence.LOW,
                previewId = "com.example.ProfileCardPreview",
              ),
              pageNode(
                "1:4",
                "Shape=Pill",
                code = "ui/Shapes.kt#PillShape",
                previewId = "com.example.NotInThisCatalog",
              ),
              pageNode("1:6", "Shape=Ghost-ish", link = PageNodeLink.UNLINKED),
              pageNode(
                "1:404",
                "Shape=Flattened",
                code = "ui/Shapes.kt#FlowerShape",
                previewId = "com.example.ProfileCardPreview",
              ),
            )
        }
        .build()

    val designPageHtml =
      ServeWeb.designPage(
        moduleLabel = "compose-m3",
        page = designPageFixture,
        svg = designPageSvg,
        fileKey = "ocdacdEsnHipMJD3egzxKb",
        // Everything except the pill, so the fixture covers a node the producer mapped but this
        // catalog cannot draw.
        renderablePreviewIds = setOf("com.example.ProfileCardPreview"),
        // A `compareWith` sibling's rendition of the same cells, deliberately missing the triangle:
        // that slot falls back to the design's drawing and must carry `data-cp-unpaired` and a
        // dotted outline.
        parallelRenders =
          mapOf(
            "1:1" to "/wear-m3/render/com.example.WearProfileCardPreview.png",
            "1:2" to "/wear-m3/render/com.example.WearProfileCardPreview.png",
          ),
        parallelLabel = "wear-m3",
        ownLabel = "compose-m3",
        token = token,
        sessionId = "compose-m3",
        trust = "branch:yschimke/compose-ai-tools@design-artifacts/compose-m3",
        isPublic = true,
        version = version,
        reportIssue =
          fixturePageReportIssue(
            "https://preview.coo.ee/compose-m3/pages/foundations",
            "this design page",
          ),
      )

    // A node with code behind it is an anchor (so click, middle click, modifier click and
    // status-bar preview all work), asserted here since it is server-rendered markup. Matched on
    // the specific manifest-linked node (`1:1`), not any /p/ anchor.
    val manifestNode =
      assertNotNull(
        Regex("""<(\w+) class="cp-page-node" [^>]*data-cp-node="1:1"[^>]*>""").find(designPageHtml),
        "the manifest-linked node 1:1 is emitted at all",
      )
    assertEquals(
      "a",
      manifestNode.groupValues[1],
      "a node with a renderable preview is emitted as an anchor",
    )
    // The whole final segment, not a prefix (`…PreviewLegacy` contains the id). `\shref=`, not
    // `href=`, which would also match `data-href`.
    val hrefOf = { tag: String -> Regex("""\shref="([^"]*)"""").find(tag)?.groupValues?.get(1) }
    val manifestHref =
      assertNotNull(
        hrefOf(manifestNode.value),
        "the manifest node carries a real href attribute",
      )
    // Matched through the route delimiter: `substringAfterLast` returns the whole string when it is
    // absent, so a relative `href="com.example.ProfileCardPreview"` would pass.
    assertEquals(
      "com.example.ProfileCardPreview",
      // The capture must be the FINAL segment. Unanchored, `/p/<id>/other` still yields `<id>` —
      // and the server registers only the exact `/p/{name}` routes, so that URL navigates nowhere.
      Regex("""/p/([^/?#]+)(?:[?#]|$)""").find(manifestHref)?.groupValues?.get(1),
      "…and its destination is THAT preview, on the preview route",
    )
    // A node without code still links, to the design file. Matched on that node (`1:6`) since the
    // renderable nodes satisfy a bare existence check.
    val unlinkedNode =
      assertNotNull(
        Regex("""<(\w+) class="cp-page-node" [^>]*data-cp-node="1:6"[^>]*>""").find(designPageHtml),
        "the unlinked node 1:6 is emitted at all",
      )
    assertEquals(
      "a",
      unlinkedNode.groupValues[1],
      "an unlinked node stays an anchor rather than becoming inert",
    )
    // The whole href as one string; partial checks pass for the Figma homepage or a relative URL.
    assertEquals(
      "https://www.figma.com/design/ocdacdEsnHipMJD3egzxKb?node-id=1-6",
      hrefOf(unlinkedNode.value),
      "…and its destination is THIS node in the catalog's design file",
    )

    // The catalog-wide motion browser: every recording, one page. Its resting state matters (each
    // card shows a still until pressed), so a baseline would catch autoplay. Two sections, and a
    // component with two captures (split by [MotionCaptureLabels]). The Switch and Icon Button
    // publish captures on several renders, as production does, to exercise the de-duplicating fold;
    // the Icon Button's two share a caption, which is hoisted above the cards.
    val switchCaptures =
      listOf(
        ServeMotion(
          id = "switch-on__ideal__default__light",
          kind = "interaction",
          caption =
            "Toggle on. The thumb travels the full width of the track and the container " +
              "recolours to the checked state as it lands.",
        ),
        ServeMotion(
          id = "switch-on__ideal__default__light__anim",
          kind = "animation",
          caption =
            "Thumb settle. Released mid-travel, the thumb overshoots and settles back " +
              "through the theme's spatial spring rather than snapping to the stop.",
        ),
      )
    val iconButtonCaptures =
      listOf("light", "dark").map { theme ->
        ServeMotion(
          id = "iconbutton-filled__ideal__default__$theme",
          kind = "interaction",
          caption =
            "Press and hold. Expressive animates the container into its pressed shape and " +
              "holds it there for the duration of the press; Baseline leaves it static.",
        )
      }
    val motionPreviews =
      listOf(
        ServePreview(
          "switch-on__ideal__default__light",
          "Switch · On",
          section = "Components",
          catalogOrder = 1,
          motion = switchCaptures,
        ),
        // The same two recordings again, on the switch's disabled render. One Switch block on the
        // page, not two.
        ServePreview(
          "switch-on__ideal__disabled__light",
          "Switch · On",
          section = "Components",
          catalogOrder = 1,
          state = "disabled",
          motion = switchCaptures,
        ),
        ServePreview(
          "card-filled__ideal__default__light",
          "Card · Filled",
          section = "Components",
          catalogOrder = 2,
          motion =
            listOf(
              ServeMotion(
                id = "card-filled__press",
                kind = "interaction",
                caption =
                  "Press and hold the card. The container lifts to its pressed elevation and " +
                    "the ripple expands from the contact point.",
              )
            ),
        ),
        ServePreview(
          "iconbutton-filled__ideal__default__light",
          "Icon Button · Filled",
          section = "Components",
          catalogOrder = 3,
          theme = "light",
          motion = iconButtonCaptures,
        ),
        ServePreview(
          "iconbutton-filled__ideal__default__dark",
          "Icon Button · Filled",
          section = "Components",
          catalogOrder = 3,
          theme = "dark",
          motion = iconButtonCaptures,
        ),
        // A capture with NO caption, which the annotation defaults produce and which the page has
        // to name honestly rather than leaving blank — the same fallback the viewer's picker uses.
        ServePreview(
          "profile__screen",
          "Profile screen",
          section = "Screens",
          catalogOrder = 4,
          motion = listOf(ServeMotion(id = "profile__scroll", kind = "interaction")),
        ),
        // …and a still-only component, which must NOT appear: the page is a list of recordings.
        ServePreview(
          "badge__ideal__default__light",
          "Badge",
          section = "Components",
          catalogOrder = 5,
        ),
      )
    val motionIndex =
      ServeWeb.motionIndexPage(
        moduleLabel = "compose-m3",
        previews = motionPreviews,
        token = token,
        sessionId = "compose-m3",
        trust = "branch:yschimke/compose-ai-tools@design-artifacts/compose-m3",
        isPublic = true,
        version = version,
        // `handleMotionIndex` passes a page-scoped report, so the fixture does too.
        reportIssue =
          fixturePageReportIssue("https://preview.coo.ee/compose-m3/motion", "this motion browser"),
      )

    // The cross-catalog layer diff: what two catalogs of one design system resolved for the same
    // cell (font fallback, token value, missing node), none of which shows in a pixel diff.
    val parallelLayers =
      ServeWeb.parallelLayersPage(
        moduleLabel = "remote-m3",
        preview =
          ServePreview(
            "button-child__disabled",
            "Button · Child (disabled)",
            state = "disabled",
            componentId = "Button/Child",
          ),
        siblingLabel = "wear-m3-catalog",
        siblingPreviewId = "child-button__not-enabled",
        siblingHref = "/wear-m3-catalog/p/child-button__not-enabled",
        pairedOn = ", paired on the design-kit node both catalogs map this cell to",
        cell = "state=disabled",
        diff =
          ServeParallelLayers.diff(
            here =
              listOf(
                fixtureTypography(
                  "Continue",
                  "16.0sp/24.0sp · Inter · 500",
                  mapOf(
                    "token" to "bodyLarge",
                    "fontFamily" to "Inter",
                    "fontSize" to "16.0sp",
                    "lineHeight" to "24.0sp",
                    "fontWeight" to "500",
                  ),
                ),
                fixtureTypography(
                  "Skip",
                  "14.0sp/20.0sp · Inter · 400",
                  mapOf("token" to "labelLarge", "fontFamily" to "Inter", "fontSize" to "14.0sp"),
                ),
                fixtureLayout(
                  "Row",
                  "pad 16dp · gap 8dp",
                  mapOf("padding" to "16dp", "gap" to "8dp"),
                ),
              ),
            there =
              listOf(
                fixtureTypography(
                  "Continue",
                  "16.0sp/24.0sp · Roboto · 500",
                  mapOf(
                    "token" to "bodyLarge",
                    "fontFamily" to "Roboto",
                    "fontSize" to "16.0sp",
                    "lineHeight" to "24.0sp",
                    "fontWeight" to "500",
                  ),
                ),
                fixtureLayout(
                  "Row",
                  "pad 12dp · gap 8dp",
                  mapOf("padding" to "12dp", "gap" to "8dp"),
                ),
              ),
          ),
        token = token,
        sessionId = "remote-m3",
        isPublic = true,
        version = version,
        displayTitle = "Remote Compose M3",
        reportIssue =
          fixturePageReportIssue(
            "https://preview.coo.ee/remote-m3/parallel/button-child__disabled",
            "this cross-catalog layer comparison",
          ),
      )

    val designPageIndex =
      ServeWeb.designPagesIndexPage(
        moduleLabel = "compose-m3",
        pages = listOf(designPageFixture),
        token = token,
        sessionId = "compose-m3",
        trust = "branch:yschimke/compose-ai-tools@design-artifacts/compose-m3",
        isPublic = true,
        version = version,
        reportIssue =
          fixturePageReportIssue("https://preview.coo.ee/compose-m3/pages", "these design pages"),
      )

    // The same themed catalog served live with `@ThemeCatalog` themes: the Theme control lists the
    // baked Light/Dark pair plus each declared theme, and picking one re-points daemon-twinned
    // thumbnails at a `?themeProvider=` render.
    val landingDeclaredThemes =
      ServeWeb.landingPage(
        "compose-m3",
        themedPreviews,
        token,
        sessionId = "compose-m3",
        trust = "branch:yschimke/compose-ai-tools@design-artifacts/compose-m3",
        isPublic = true,
        hasHomeIndex = true,
        version = version,
        // The motion browser entry in the `⋯` menu; the harness opens that menu on this fixture
        // (`actions-menu`).
        motionCaptureCount = 4,
        declaredThemes =
          listOf(
            ServeTheme("Brand Light", "com.example.BrandLightThemeCatalog", group = "Brand"),
            ServeTheme("Brand Dark", "com.example.BrandDarkThemeCatalog", group = "Brand"),
            ServeTheme("High Contrast", "com.example.HighContrastThemeCatalog"),
          ),
        canRenderThemeFor = { true },
        themeRenderBurstCapacity = 5,
        // Carries the presence script, which also injects the render-server badge (see
        // FIXTURE_STATES in pages-snapshot.spec.mjs).
        presenceUrl = "/compose-m3/api/presence",
      )
    // The same catalog, IR-replayed: a `themeProvider` render is refused 409, so declared theme
    // chips must not be offered; only the baked Light/Dark pair remains. Pairs with
    // [landingDeclaredThemes].
    val landingIrReplayThemes =
      ServeWeb.landingPage(
        "remote-m3",
        themedPreviews,
        token,
        sessionId = "remote-m3",
        trust = "branch:yschimke/compose-ai-tools@design-artifacts/remote-m3",
        isPublic = true,
        hasHomeIndex = true,
        version = version,
        declaredThemes =
          listOf(
            ServeTheme("Roboto Flex", "com.example.RobotoFlexThemeCatalog", group = "Typeface"),
            ServeTheme(
              "Google Sans Flex",
              "com.example.GoogleSansFlexThemeCatalog",
              group = "Typeface",
            ),
          ),
        // Fully live — a daemon twin for every card. Only the replay gate withholds the chips.
        canRenderThemeFor = { true },
        irReplayFor = { true },
        themeRenderBurstCapacity = 5,
      )
    // A live catalog where every card can be long-pressed to stream a daemon session. The committed
    // HTML holds the static half; the hover hint and streaming states are FIXTURE_STATES in
    // pages-snapshot.spec.mjs.
    val landingLive =
      ServeWeb.landingPage(
        "compose-m3",
        themedPreviews,
        token,
        sessionId = "compose-m3",
        trust = "branch:yschimke/compose-ai-tools@design-artifacts/compose-m3",
        isPublic = true,
        hasHomeIndex = true,
        version = version,
        canStreamLiveFor = { true },
      )
    // A catalog whose components carry baked non-default states: the landing folds each to ONE card
    // (the default), the non-default states reachable via the viewer switcher.
    val landingStates =
      ServeWeb.landingPage(
        "compose-m3",
        statefulPreviews,
        token,
        trust = "branch:yschimke/compose-ai-tools@design-artifacts/compose-m3",
        isPublic = true,
        hasHomeIndex = true,
        version = version,
      )
    // A breakpoint-bearing catalog: one card per component at its first declared size, the other
    // four folded away. Captured so the visual-diff bot covers the size axis on every future PR.
    val landingBreakpoints =
      ServeWeb.landingPage(
        "wear-m3-catalog",
        breakpointPreviews,
        token,
        sessionId = "wear-m3-catalog",
        trust = "branch:yschimke/wear-m3-catalog@design-artifacts/wear-m3-catalog",
        isPublic = true,
        hasHomeIndex = true,
        basePath = "/wear-m3-catalog",
        declaredSurface = "dark",
        version = version,
      )
    // …and its viewer, whose subtree lists the four folded breakpoints beside the state rows.
    val viewerBreakpoints =
      ServeWeb.viewerPage(
        breakpointPreviews.first(),
        token,
        sessionId = "wear-m3-catalog",
        catalogName = "M3 Wear OS Apps Design Kit",
        isPublic = true,
        basePath = "/wear-m3-catalog",
        siblings = breakpointPreviews,
        version = version,
      )
    // An app catalog served under its path whose previews carry sections: a tab bar over
    // per-section panels with `group` sub-headings.
    val landingSections =
      ServeWeb.landingPage(
        "meshcore-mobile",
        sectionedPreviews,
        token,
        sessionId = "meshcore-mobile",
        trust = "branch:yschimke/meshcore-mobile@design-artifacts/meshcore-mobile",
        isPublic = true,
        hasHomeIndex = true,
        basePath = "/meshcore-mobile",
        version = version,
      )
    // A tabbed declared-theme catalog exercising initial queue priority before apply() assigns
    // hidden state (for a returning visitor whose saved tab is not the first). Large enough, with
    // burst capacity, that picking a theme leaves cards held against the scroll for the
    // deferred-render contract.
    val landingDeclaredTabbedThemes =
      ServeWeb.landingPage(
        "meshcore-mobile",
        sectionedPreviews,
        token,
        sessionId = "meshcore-mobile",
        declaredThemes = listOf(ServeTheme("Brand Dark", "com.example.BrandDarkThemeCatalog")),
        canRenderThemeFor = { true },
        themeRenderBurstCapacity = 5,
      )
    // The default-state viewer for that catalog: renders the `.cp-axes-tree` subtree of
    // links to the component's other same-theme states, the current (Default) state marked active.
    val viewerStates =
      ServeWeb.viewerPage(
        statefulPreviews.first(),
        token,
        sessionId = "compose-m3",
        trust = "branch:yschimke/compose-ai-tools@design-artifacts/compose-m3",
        siblings = statefulPreviews,
      )
    // Every disclosure at once: state axis and theme set wide enough to arrive folded, plus
    // siblings for the nav toggle.
    val viewerAxesFolded =
      ServeWeb.viewerPage(
        wideStatePreviews.first(),
        token,
        sessionId = "compose-m3",
        trust = "branch:yschimke/compose-ai-tools@design-artifacts/compose-m3",
        siblings = wideStatePreviews + statefulPreviews,
        declaredThemes =
          listOf(
            ServeTheme("Baseline Light", "com.example.BaselineLightThemeCatalog"),
            ServeTheme("Baseline Dark", "com.example.BaselineDarkThemeCatalog"),
            ServeTheme("Brand Dark", "com.example.BrandDarkThemeCatalog"),
          ),
      )
    // The cross-product viewer entered on `pressed + RTL`, the render non-default on both axes, so
    // every subtree row names both coordinates.
    val viewerCrossProduct =
      ServeWeb.viewerPage(
        crossProductPreviews.first { it.state == "pressed" && it.props != null },
        token,
        sessionId = "compose-m3",
        siblings = crossProductPreviews,
      )
    // The tree at full depth. `synthesizeGroups` needs at least two families and one with more than
    // one card, so this mixes a two-card Button family (one with the props axis) with Checkbox and
    // Radio button carrying the state axis.
    val treeDepthPreviews =
      variantPreviews +
        listOf(
          ServePreview(
            "button-outlined__ideal__default__light",
            "Button · Outlined (light)",
            state = "default",
            theme = "light",
          ),
          ServePreview(
            "button-outlined__ideal__default__dark",
            "Button · Outlined (dark)",
            state = "default",
            theme = "dark",
          ),
        ) +
        statefulPreviews
    val landingTreeDepth =
      ServeWeb.landingPage(
        "compose-m3",
        treeDepthPreviews,
        token,
        sessionId = "compose-m3",
        trust = "branch:yschimke/compose-ai-tools@design-artifacts/compose-m3",
        isPublic = true,
        hasHomeIndex = true,
        version = version,
      )
    // A catalog with baked props-axis variants (RTL / pseudo-locale / large font): the landing
    // folds eight renders to one card.
    val landingVariants =
      ServeWeb.landingPage(
        "compose-m3",
        variantPreviews,
        token,
        trust = "branch:yschimke/compose-ai-tools@design-artifacts/compose-m3",
        isPublic = true,
        hasHomeIndex = true,
        version = version,
      )
    // The default-render viewer for that catalog, with the `<nav aria-label="Component variant">`
    // switcher.
    val viewerVariants =
      ServeWeb.viewerPage(
        variantPreviews.first(),
        token,
        sessionId = "compose-m3",
        trust = "branch:yschimke/compose-ai-tools@design-artifacts/compose-m3",
        siblings = variantPreviews,
      )
    // A section-less catalog rendered with synthesized family sub-groups.
    val landingGrouped =
      ServeWeb.landingPage(
        "compose-m3",
        groupedPreviews,
        token,
        trust = "branch:yschimke/compose-ai-tools@design-artifacts/compose-m3",
        isPublic = true,
        hasHomeIndex = true,
        version = version,
        // The design file's own pages, listed by name at the foot of the outline tree (as in
        // m3-catalog).
        designPages =
          listOf(
            // Sections on one page and none on the other, on purpose: the pane has to render both
            // a branch and a leaf, and a golden that only ever held one shape would not say so.
            ServeWeb.PageLink(
              "shape",
              "Shape",
              listOf(
                ServeWeb.PageSection("1:20", "Corner radius"),
                ServeWeb.PageSection("1:21", "Shape scale"),
              ),
            ),
            ServeWeb.PageLink("type", "Typography"),
          ),
      )
    // Catalog mode as first-class visual fixtures (also the feature's PR evidence): the inventory
    // and a focused component page.
    val browserPreviews =
      listOf(
        ServePreview(
          "button-filled-default",
          "Filled button",
          componentId = "Button/Filled",
          state = "default",
          section = "Components",
          group = "Buttons",
        ),
        ServePreview(
          "button-filled-pressed",
          "Filled button pressed",
          componentId = "Button/Filled",
          state = "pressed",
          section = "Components",
          group = "Buttons",
          props = jsonProps("label" to "Continue"),
          componentParameters =
            listOf(
              ServeComponentParameter("onClick", "() -> Unit"),
              ServeComponentParameter("modifier", "Modifier", hasDefault = true),
              ServeComponentParameter("spacing", "Dp", hasDefault = true),
              ServeComponentParameter(
                "content",
                "RowScope.() -> Unit",
                composableSlot = true,
              ),
            ),
        ),
        ServePreview(
          "button-outlined-default",
          "Outlined button",
          componentId = "Button/Outlined",
          state = "default",
          section = "Components",
          group = "Buttons",
        ),
        ServePreview(
          "card-elevated-default",
          "Elevated card",
          componentId = "Card/Elevated",
          section = "Components",
          group = "Cards",
        ),
        ServePreview(
          "card-filled-default",
          "Filled card",
          componentId = "Card/Filled",
          section = "Components",
          group = "Cards",
        ),
        ServePreview(
          "navigation-bar-default",
          "Navigation bar",
          componentId = "Navigation/Navigation Bar",
          section = "Components",
          group = "Navigation",
        ),
        ServePreview(
          "profile-screen-default",
          "Profile screen",
          componentId = "Screens/Profile",
          section = "Screens",
          group = "Account",
        ),
      )
    val componentBrowserCatalog =
      ServeWeb.landingPage(
        "compose-m3",
        browserPreviews,
        token,
        sessionId = "compose-m3",
        isPublic = true,
        hasHomeIndex = true,
        basePath = "/compose-m3",
        displayTitle = "Compose Material 3",
        declaredThemes = listOf(ServeTheme("Light", "com.example.LightThemeCatalog")),
        canRenderThemeFor = { true },
        componentBrowser = true,
        // Catalog mode keeps the catalog tracker: design reviewers are who file "draws the wrong
        // thing" reports.
        reportIssue = fixturePageReportIssue("https://preview.coo.ee/compose-m3/", "this catalog"),
      )
    val componentBrowserHome =
      ServeWeb.homeIndexPage(
          homeSystems,
          token,
          isPublic = true,
          version = version,
          componentBrowser = true,
        )
        .replace(Regex("[ \\t]+\\n"), "\n")
    val componentBrowserViewer =
      ServeWeb.viewerPage(
        browserPreviews.first { it.id == "button-filled-pressed" },
        token,
        sessionId = "compose-m3",
        catalogName = "Compose Material 3",
        catalogTitle = "Compose Material 3",
        basePath = "/compose-m3",
        isPublic = true,
        siblings = browserPreviews,
        canRenderOverrides = true,
        usageHref = "/compose-m3/usage/button-filled-pressed",
        hasSvgExport = true,
        hasDesignAnnotations = true,
        // Carries the presence heartbeat and render-server poller, because in Catalog mode the
        // badge has no header slot: the harness must show nothing paints with the poller actually
        // present.
        presenceUrl = "/compose-m3/api/presence",
        componentBrowser = true,
        // …and so does the component page, which is where a wrong render is actually noticed.
        reportIssue =
          fixtureReportIssue(
            "button-filled-pressed",
            "Filled button",
            "ui/buttons/FilledButton.kt",
          ),
      )
    // Catalog mode on a Remote Compose preview, the one page offering browser players there. `js`
    // and `cmp-wasm` replay published bytes in the visitor's browser, so a shared `?rcPlayer=…`
    // link must work. The switcher offers exactly those two; server-side players are absent, not
    // greyed.
    val componentBrowserRemoteCompose =
      ServeWeb.viewerPage(
        browserPreviews.first { it.id == "button-filled-pressed" },
        token,
        sessionId = "compose-m3",
        catalogName = "Compose Material 3",
        catalogTitle = "Compose Material 3",
        basePath = "/compose-m3",
        isPublic = true,
        siblings = browserPreviews,
        canRenderOverrides = true,
        hasRemoteComposeDoc = true,
        enabledRcPlayers =
          listOf("camaelon-js", "androidx-view", "androidx-embedded", "cmp-jvm", "cmp-wasm"),
        componentBrowser = true,
      )
    // A viewer whose siblings span several components each with many variants: the nav collapses to
    // one entry per component, mirroring the grid.
    val viewerNavCollapsed =
      ServeWeb.viewerPage(
        variantPreviews.first(),
        token,
        sessionId = "compose-m3",
        trust = "branch:yschimke/compose-ai-tools@design-artifacts/compose-m3",
        siblings = variantPreviews + statefulPreviews,
      )
    // The same drawer over a catalog with an outline: sections with named groups as headings over
    // the thumbnail rows. The flat golden above covers outline-less catalogs.
    val viewerNavSections =
      ServeWeb.viewerPage(
        browserPreviews.first(),
        token,
        sessionId = "compose-m3",
        trust = "branch:yschimke/compose-ai-tools@design-artifacts/compose-m3",
        siblings = browserPreviews,
      )
    // The document lane (`--accept-docs`): the upload page and each format's expiring permalink
    // page. The harness captures the chrome, not the played-back document.
    val docUpload =
      ServeWeb.docUploadPage(
        token,
        isPublic = true,
        ttlSeconds = 3600,
        urlUploadAllowed = true,
        version = version,
      )
    // The UI-builder admin screen (`GET /admin/ui-builder`). Rows are fetched by script; the
    // harness stubs the JSON route.
    val uiBuilderAdmin = ServeWeb.uiBuilderAdminPage(adminToken = token, version = version)
    val uiBuilderAdminRead =
      ServeWeb.uiBuilderAdminPage(adminToken = token, readOnly = true, version = version)
    // The actor-scoped counterpart: owned and shared rows, including the failure an unopenable
    // design shows. Static rows, so the page itself is the fixture.
    val uiBuilderDesigns =
      ServeWeb.uiBuilderDesignsPage(
        rows =
          listOf(
            ServeWeb.UiBuilderDesignRow(
              designId = "morning-player",
              title = "Morning player",
              catalogSystemId = "wear-m3-catalog",
              revision = 12,
              updatedAtEpochMillis = 1_768_214_400_000,
              ownerActorId = "github:octocat",
              requesterRole = "owner",
              requesterAllowed = "read, write, export, manage_access, delete",
              designHref = "/ui-builder/morning-player?token=fixture-token",
              shareAction = "/ui-builder/morning-player/access?token=fixture-token",
              grants =
                listOf(
                  ServeWeb.UiBuilderAccessRow(
                    actorId = "github:colleague",
                    role = "editor",
                    allowed = "read, write, export",
                  )
                ),
              unopenableReason = null,
              previewHref =
                "/api/ui-builder/v1/designs/morning-player/export.svg?token=fixture-token",
              copyAction = "/ui-builder/designs/copy?token=fixture-token",
              copySuggestedId = "shady-raccoon",
              deleteAction = "/ui-builder/morning-player/delete?token=fixture-token",
              // Filed, beside an unfiled design, so the page draws a named folder and "No folder".
              folder = "Media",
              folderAction = "/ui-builder/morning-player/folder?token=fixture-token",
            ),
            ServeWeb.UiBuilderDesignRow(
              designId = "archived-dashboard",
              title = "Archived dashboard",
              catalogSystemId = "m3-catalog",
              revision = 7,
              updatedAtEpochMillis = 1_767_955_200_000,
              ownerActorId = "github:designer",
              requesterRole = "viewer",
              requesterAllowed = "read, export",
              designHref = "/ui-builder/archived-dashboard?token=fixture-token",
              shareAction = "/ui-builder/archived-dashboard/access?token=fixture-token",
              grants = null,
              unopenableReason = "catalog unavailable for stored design archived-dashboard",
            ),
          ),
        viewerActorId = "github:octocat",
        createAction = "/ui-builder/designs?token=fixture-token",
        copyAction = "/ui-builder/designs/copy?token=fixture-token",
        catalogs =
          listOf(
            ServeWeb.UiBuilderNewDesignOption(
              systemId = "m3-catalog",
              label = "m3-catalog",
              templates =
                listOf(
                  ServeWeb.UiBuilderNewDesignTemplate("blank", "Blank"),
                  ServeWeb.UiBuilderNewDesignTemplate("jetcaster", "Jetcaster"),
                ),
            ),
            ServeWeb.UiBuilderNewDesignOption(
              systemId = "wear-m3",
              label = "wear-m3",
              templates = listOf(ServeWeb.UiBuilderNewDesignTemplate("wear-screen", "Wear screen")),
            ),
          ),
        suggestedDesignId = "cheeky-raccoon",
        navSuffix = "?token=fixture-token",
        version = version,
      )
    // The playground editor (`GET /playground`). Always token-gated (it runs user code), so the
    // fixture is the non-public form. Rendered with the catalog selector populated
    // (`--playground`); the pinned form is covered by `playgroundPage omits the catalog control…`.
    val playground =
      ServeWeb.playgroundPage(
        token,
        isPublic = false,
        version = version,
        editingLeaseEnabled = true,
        catalogs =
          listOf(
            PlaygroundCatalogInfo(
              id = "",
              label = "Server default",
              modes = PlaygroundMode.entries.toList(),
              resolved = true,
            ),
            PlaygroundCatalogInfo(
              id = "compose-m3",
              label = "compose-m3 (desktop)",
              backend = "desktop",
              modes = listOf(PlaygroundMode.CMP),
              resolved = true,
            ),
            PlaygroundCatalogInfo(
              id = "compose-wear",
              label = "compose-wear (android)",
              backend = "android",
              modes = listOf(PlaygroundMode.ANDROID, PlaygroundMode.REMOTE_COMPOSE),
              resolved = false,
            ),
          ),
      )
    // A handoff this host cannot honour: `/playground?from=horologist/…` on a desktop-only backend,
    // so no Android catalog compiles. Reached only via bookmark or shared URL; the page says so
    // rather than retargeting the buffer.
    val playgroundUncompilable =
      ServeWeb.playgroundPage(
        token,
        isPublic = false,
        version = version,
        catalogs =
          listOf(
            PlaygroundCatalogInfo(
              id = "compose-m3",
              label = "compose-m3 (desktop)",
              backend = "desktop",
              modes = listOf(PlaygroundMode.CMP),
              resolved = true,
            )
          ),
        catalogSelectorEnabled = true,
        seed =
          PlaygroundSeed(
            catalog = "horologist",
            previewId = "mediacontrolbuttonsplaying__ideal__default__compact",
            fileName = "MediaControlButtons.kt",
            text =
              """
              package com.google.android.horologist.media.ui.components

              @Preview
              @Composable
              fun MediaControlButtonsPlaying() {
                MediaControlButtons(onPlayButtonClick = {}, playing = true)
              }
              """
                .trimIndent(),
            blobUrl =
              "https://github.com/google/horologist/blob/main/media-ui/src/main/java/com/google/" +
                "android/horologist/media/ui/components/MediaControlButtons.kt",
            sliced = true,
          ),
      )
    val docLottie =
      ServeWeb.docPage(
        ServeWeb.DocView(
          id = "0YFhq8Kb2s7cVv1nQpZs3A",
          name = "loading-spinner.json",
          formatId = ServeDocFormats.LOTTIE.id,
          formatLabel = ServeDocFormats.LOTTIE.label,
          playerPath = ServeDocFormats.LOTTIE.playerPath,
          rawPath = "/d/0YFhq8Kb2s7cVv1nQpZs3A/raw",
          facts =
            listOf(
              ServeDocFact("Name", "Loading spinner"),
              ServeDocFact("Bodymovin version", "5.7.4"),
              ServeDocFact("Size", "512 × 512"),
              ServeDocFact("Frames", "90 @ 30 fps"),
              ServeDocFact("Duration", "3s"),
              ServeDocFact("Layers", "6"),
            ),
          sizeText = "48 kB",
          expiresInText = "1h",
          expiresAtText = "2026-07-28T22:15:00Z",
          width = 512,
          height = 512,
        ),
        token,
        isPublic = true,
        version = version,
      )
    val docRemoteCompose =
      ServeWeb.docPage(
        ServeWeb.DocView(
          id = "Tz3l9WcAq0Xj5RmB7dPuKw",
          name = "watchface.rc",
          formatId = ServeDocFormats.REMOTE_COMPOSE.id,
          formatLabel = ServeDocFormats.REMOTE_COMPOSE.label,
          playerPath = ServeDocFormats.REMOTE_COMPOSE.playerPath,
          rawPath = "/d/Tz3l9WcAq0Xj5RmB7dPuKw/raw",
          facts =
            listOf(
              ServeDocFact("Format version", "1.2.0"),
              ServeDocFact("Document size", "384 × 384"),
            ),
          sizeText = "12 kB",
          expiresInText = "58m",
          expiresAtText = "2026-07-28T22:13:00Z",
          width = 384,
          height = 384,
        ),
        token,
        isPublic = true,
        version = version,
      )
    // The same permalink on a host that serves the CMP/Wasm player (`--rc-player-wasm-dir`): the
    // page offers both players for the one document.
    val docRemoteComposePlayers =
      ServeWeb.docPage(
        ServeWeb.DocView(
          id = "Tz3l9WcAq0Xj5RmB7dPuKw",
          name = "watchface.rc",
          formatId = ServeDocFormats.REMOTE_COMPOSE.id,
          formatLabel = ServeDocFormats.REMOTE_COMPOSE.label,
          playerPath = ServeDocFormats.REMOTE_COMPOSE.playerPath,
          rawPath = "/d/Tz3l9WcAq0Xj5RmB7dPuKw/raw",
          facts =
            listOf(
              ServeDocFact("Format version", "1.2.0"),
              ServeDocFact("Document size", "384 × 384"),
            ),
          sizeText = "12 kB",
          expiresInText = "58m",
          expiresAtText = "2026-07-28T22:13:00Z",
          width = 384,
          height = 384,
        ),
        token,
        isPublic = true,
        version = version,
        cmpWasmPlayerPath = "/rc-player-wasm/index.html",
        serverPlayers =
          listOf(
            ServeWeb.DocServerPlayer(
              "cmp-jvm",
              "CMP (JVM)",
              "/d/Tz3l9WcAq0Xj5RmB7dPuKw/render.png",
            )
          ),
      )

    // The styled 404 for a dead catalog or preview link: site chrome with a "back to design
    // systems" link.
    //
    // The agent access-grant consent page (GET /agent-access/{id}), whose job is to make a human
    // suspicious: the verification code as the loudest element, the agent-supplied purpose escaped
    // (the label carries markup on purpose), and one scope the approver may not grant.
    val agentAccess =
      ServeWeb.agentGrantApprovalPage(
        requestId = "9c2Qk1pTf0Xb7hLm4nRzQA",
        userCode = "KX7M-9QD4",
        label = "fix wear-m3-catalog#68 <the focus ring>",
        client = "203.0.113.42",
        requestedScope = AgentGrantScope.PLAYGROUND,
        requestedTtlSeconds = 7200,
        expiresInSeconds = 540,
        approver = "@yschimke",
        selectableScopes = listOf(AgentGrantScope.PREVIEW, AgentGrantScope.LIVE),
        maxTtlSeconds = 8 * 3600,
        approveCsrf = "fixed-approve-seal",
        denyCsrf = "fixed-deny-seal",
        formAction = "/agent-access/9c2Qk1pTf0Xb7hLm4nRzQA",
        version = version,
        withheldScopes = listOf(AgentGrantScope.PLAYGROUND),
        withheldReason = "you do not hold it yourself on this server, so you cannot pass it on",
      )

    // The same page for a request via the MCP OAuth façade with an external redirect: leads with
    // that host labelled external, shows the client's self-chosen name as such, and has no "Asked
    // from" line.
    val agentAccessOAuth =
      ServeWeb.agentGrantApprovalPage(
        requestId = "9c2Qk1pTf0Xb7hLm4nRzQA",
        userCode = "KX7M-9QD4",
        label = "Claude Desktop",
        client = "203.0.113.42",
        requestedScope = AgentGrantScope.LIVE,
        requestedTtlSeconds = 3600,
        expiresInSeconds = 1740,
        approver = "@yschimke",
        selectableScopes = listOf(AgentGrantScope.PREVIEW, AgentGrantScope.LIVE),
        maxTtlSeconds = 8 * 3600,
        approveCsrf = "fixed-approve-seal",
        denyCsrf = "fixed-deny-seal",
        formAction = "/agent-access/9c2Qk1pTf0Xb7hLm4nRzQA",
        version = version,
        oauthReturn =
          ServeMcpOAuth.describeRedirect("https://mcp-client.example.net/oauth/callback"),
      )

    // The same page on a box offering a capability beside the scopes: a second fieldset of
    // independent checkboxes (scopes are radios), one of which the approver may not pass on.
    val agentAccessCapabilities =
      ServeWeb.agentGrantApprovalPage(
        requestId = "9c2Qk1pTf0Xb7hLm4nRzQA",
        userCode = "KX7M-9QD4",
        label = "embed the before/after in the PR body",
        client = "203.0.113.42",
        requestedScope = AgentGrantScope.LIVE,
        requestedTtlSeconds = 1800,
        expiresInSeconds = 540,
        approver = "@yschimke",
        selectableScopes = listOf(AgentGrantScope.PREVIEW, AgentGrantScope.LIVE),
        selectableCapabilities = listOf(AgentGrantCapability.IMAGES),
        maxTtlSeconds = 8 * 3600,
        approveCsrf = "fixed-approve-seal",
        denyCsrf = "fixed-deny-seal",
        formAction = "/agent-access/9c2Qk1pTf0Xb7hLm4nRzQA",
        version = version,
        // Capabilities the box will not offer at all: a different remedy (an operator flag), so its
        // own note naming the flag.
        storeNarrowedCapabilities =
          listOf(
            AgentGrantCapability.UI_BUILDER_READ,
            AgentGrantCapability.UI_BUILDER_WRITE,
            AgentGrantCapability.UI_BUILDER_EXPORT,
          ),
        storeNarrowedReason =
          "this server's --agent-grant-capabilities does not include it, so no tick could " +
            "grant it — the operator would have to add the capability and restart",
      )

    // What the approver lands on afterwards.
    val agentAccessGranted =
      ServeWeb.agentGrantNoticePage(
        heading = "Access granted",
        message =
          "The agent can now use this server for 2h. You can end it early from the server status " +
            "page at any time.",
        detail = "Scopes: preview, live · grant 4f2ab91c73de",
        version = version,
      )

    val notFound =
      ServeWeb.notFoundPage(
        "That preview does not exist in this catalog.",
        token,
        isPublic = true,
        version = version,
      )

    // The status page (GET /status): published catalogs with load/trust/liveness, running daemons,
    // effective config, and recent startup failures. Fixed figures, with one failure so the amber
    // "degraded" badge and failure table are captured.
    val serveStatus =
      ServeWeb.statusPage(
        token = token,
        version = version,
        view =
          ServeWeb.StatusView(
            version = version,
            public = true,
            // Exactly two hours after the fixed catalog generation time.
            nowMillis = 1_784_287_800_000,
            overallOk = false,
            healthReason = "1 daemon startup failure · 1 recent live render failure",
            healthHref = "#recent-daemon-failures",
            summary =
              listOf(
                // Derived from the catalog list by production `ServeStatusSnapshot.toView()`, so
                // they must agree with the table (the `wear-m3` entry below adds a fifth catalog
                // and 30 previews).
                ServeWeb.Stat(
                  "Catalogs",
                  "5/5 loaded",
                  ServeWeb.Meter(
                    total = 5,
                    segments = listOf(ServeWeb.MeterSegment("loaded", 5, "primary")),
                  ),
                ),
                ServeWeb.Stat(
                  "Published catalog renders",
                  "106 rendered · 1 failed · 0 deferred",
                  ServeWeb.Meter(
                    total = 107,
                    segments =
                      listOf(
                        ServeWeb.MeterSegment("rendered", 106, "primary"),
                        ServeWeb.MeterSegment("failed", 1, "warning"),
                      ),
                  ),
                ),
                ServeWeb.Stat("Live daemons running", "1"),
                ServeWeb.Stat("Active streams", "2"),
                // Live-stream throughput in `liveFrameText`'s shape: achieved fps, median gap,
                // painted/heartbeat split, per-frame wire cost. Numbers from a real m3-catalog
                // session.
                ServeWeb.Stat(
                  "Live frames",
                  "4.0 fps · p50 250ms · 1042 painted · 388 unchanged · 8 kB/frame",
                ),
                // A quiet gate held shut by a session lease, the longest wording with a holder
                // named, to cover wrapping.
                ServeWeb.Stat(
                  "Theme optimiser gate",
                  "closed · session lease held by compose-m3 · needs 60s quiet",
                ),
                ServeWeb.Stat(
                  "Live seats",
                  "3 free / 5",
                  ServeWeb.Meter(
                    total = 5,
                    segments =
                      listOf(
                        ServeWeb.MeterSegment("in use", 2, "secondary"),
                        ServeWeb.MeterSegment("free", 3, "primary"),
                      ),
                  ),
                ),
                ServeWeb.Stat("Known sessions", "4"),
                // The subprocess census showing a reaping leak against a bounded PID budget: the
                // page's longest value, and the only meter dominated by its warning segment.
                // Figures from a real incident ([ServeProcessCensusSnapshot]) against a 4096
                // ceiling (an unbounded budget draws no meter).
                ServeWeb.Stat(
                  "Processes",
                  "18 live JVMs · 2140 total · 2099 defunct (java) · 2140/4096 pids",
                  ServeWeb.Meter(
                    total = 4096,
                    segments =
                      listOf(
                        ServeWeb.MeterSegment("defunct", 2099, "warning"),
                        ServeWeb.MeterSegment("live", 41, "secondary"),
                        ServeWeb.MeterSegment("free", 1956, "primary"),
                      ),
                  ),
                ),
                ServeWeb.Stat("Uptime", "3d 4h"),
                ServeWeb.Stat(
                  "Live renders",
                  "1630 ok · 1 failed · 42 cached",
                  ServeWeb.Meter(
                    total = 1673,
                    segments =
                      listOf(
                        ServeWeb.MeterSegment("ok", 1630, "primary"),
                        ServeWeb.MeterSegment("failed", 1, "warning"),
                        ServeWeb.MeterSegment("cached", 42, "secondary"),
                      ),
                  ),
                ),
                ServeWeb.Stat("Average render latency", "741ms"),
                ServeWeb.Stat("Worst first render", "13679ms"),
              ),
            config =
              listOf(
                ServeWeb.Stat("Access", "public (open)"),
                ServeWeb.Stat("Bind", "0.0.0.0:8080"),
                ServeWeb.Stat("Trusted re-render", "on"),
                ServeWeb.Stat("Trust store", "configured"),
                ServeWeb.Stat("Catalog refresh", "600s"),
                ServeWeb.Stat("Live seats", "5"),
                ServeWeb.Stat("Render slots", "4"),
                ServeWeb.Stat("Accept uploads", "off"),
              ),
            catalogs =
              listOf(
                ServeWeb.StatusCatalog(
                  id = "compose-m3",
                  title = "Compose Material 3",
                  listed = true,
                  trust = "branch:yschimke/compose-ai-tools@design-artifacts/compose-m3",
                  previews = 42,
                  live = true,
                  running = true,
                  degradation = null,
                  provenance = provenance,
                  themeOptimization =
                    ThemeOptimizationSnapshot(
                      state = "complete",
                      total = 168,
                      cached = 168,
                      remaining = 0,
                      failed = 0,
                      cachedBytes = 8_912_384,
                      fullyOptimized = true,
                      startedAtEpochMillis = 1_721_209_800_000,
                      completedAtEpochMillis = 1_721_209_920_000,
                    ),
                  renderCache =
                    CatalogRenderCacheSnapshot(
                      entries = 1448,
                      bytes = 13L * 1024 * 1024,
                      maxBytes = 128L * 1024 * 1024,
                      evictions = 0,
                    ),
                ),
                // A catalog partway through replacing another build's renders, some refusing to
                // re-render, covering the dirty/failed optimization row.
                ServeWeb.StatusCatalog(
                  id = "wear-m3",
                  title = "Wear Material 3",
                  listed = true,
                  trust = "branch:yschimke/wear-m3-catalog@design-artifacts/wear-m3",
                  previews = 30,
                  live = true,
                  running = true,
                  degradation = null,
                  provenance =
                    provenance.copy(
                      repo = "yschimke/wear-m3-catalog",
                      branch = "design-artifacts/wear-m3",
                    ),
                  themeOptimization =
                    ThemeOptimizationSnapshot(
                      state = "degraded",
                      total = 240,
                      cached = 232,
                      remaining = 8,
                      // Non-zero only because a *dirty* entry can now be counted: these are cached,
                      // so the old "not cached" rule reported this catalog as having no failures.
                      failed = 3,
                      cachedBytes = 11_403_264,
                      fullyOptimized = false,
                      dirty = 24,
                      startedAtEpochMillis = 1_721_209_800_000,
                    ),
                  renderCache =
                    CatalogRenderCacheSnapshot(
                      entries = 240,
                      bytes = 4L * 1024 * 1024,
                      maxBytes = 128L * 1024 * 1024,
                      evictions = 0,
                    ),
                ),
                ServeWeb.StatusCatalog(
                  id = "remote-m3",
                  title = "Remote Compose Material 3",
                  listed = true,
                  trust = "branch:yschimke/compose-ai-tools@design-artifacts/remote-m3",
                  previews = 6,
                  live = false,
                  running = false,
                  degradation = "this delivery branch publishes no live bundle this server can run",
                  provenance =
                    provenance.copy(
                      branch = "design-artifacts/remote-m3",
                      generatedAt = "2026-07-15T08:05:00.000Z",
                    ),
                ),
                // A trusted catalog whose daemon is idle: the badge shows a "last known" qualifier.
                ServeWeb.StatusCatalog(
                  id = "confetti-wear",
                  title = "Confetti Wear",
                  listed = true,
                  trust = "branch:joreilly/Confetti@design-artifacts/confetti-wear",
                  previews = 18,
                  live = true,
                  running = false,
                  degradation = null,
                  provenance =
                    provenance.copy(
                      repo = "joreilly/Confetti",
                      branch = "design-artifacts/confetti-wear",
                    ),
                  stale = true,
                ),
                ServeWeb.StatusCatalog(
                  id = "cadence",
                  title = "Cadence",
                  listed = false,
                  trust = "unverified",
                  previews = 11,
                  failedRenders = 1,
                  live = true,
                  running = false,
                  degradation = null,
                  provenance = null,
                ),
              ),
            servers =
              listOf(
                ServeWeb.StatusServer(
                  id = "compose-m3",
                  label = "compose-m3 (live bundle)",
                  backend = "desktop",
                  activeStreams = 2,
                  upForText = "12m 5s",
                )
              ),
            failures =
              listOf(
                ServeWeb.StatusFailure(
                  whenText = "2026-07-17 09:41 UTC",
                  session = "wear-m3",
                  reason = "daemon launch timed out after 300s",
                )
              ),
            renderFailures =
              listOf(
                ServeWeb.StatusRenderFailure(
                  whenText = "2026-07-17 09:43 UTC",
                  session = "compose-m3 (live bundle)",
                  durationText = "120000ms (timeout)",
                  reason = "timed out waiting for renderFinished",
                )
              ),
          ),
      )

    // The server's bug-report page, captured from a viewer (catalog, preview, render thumbnail) on
    // a partly unhealthy box (a failed catalog load and a render timeout).
    val bugReportServer =
      ServeBugReport.Server(
        version = version,
        public = true,
        uptimeSeconds = 3 * 86400 + 4 * 3600,
        java = "17.0.11 (Eclipse Adoptium)",
        os = "Linux 6.8.0-generic (amd64)",
        unhealthyCatalogs = listOf("`wear-m3`: failed — daemon launch timed out after 300s"),
        recentFailures =
          listOf(
            "2026-07-17 09:43 UTC  compose-m3 (live bundle): render failed after " +
              "120000ms (timeout) — timed out waiting for renderFinished"
          ),
      )
    val bugReportPageContext =
      ServeBugReport.Page(
        path = "/compose-m3/p/button-filled?uiMode=dark",
        url = "https://preview.coo.ee/compose-m3/p/button-filled?uiMode=dark",
        system = "compose-m3",
        previewId = "button-filled",
        catalog = "yschimke/compose-ai-tools@design-artifacts/compose-m3",
        catalogToolVersion = "0.16.54",
        trust = "branch:yschimke/compose-ai-tools@design-artifacts/compose-m3",
        renderLane = "live daemon",
        renderUrl = "https://preview.coo.ee/compose-m3/render/button-filled.png?uiMode=dark",
        publicRender = true,
      )
    val bugReport =
      ServeWeb.bugReportPage(
        report =
          ServeWeb.BugReport(
            action = ServeBugReport.action(),
            body = ServeBugReport.body(bugReportServer, bugReportPageContext),
            bodyTemplate =
              ServeBugReport.body(
                bugReportServer,
                bugReportPageContext,
                clientPlaceholder = true,
              ),
            repo = ServeBugReport.REPO,
            renderUrl = "/compose-m3/render/button-filled.png",
            login = "yschimke",
          ),
        sections =
          listOf(
            ServeWeb.BugReportSection(
              "Server",
              listOf(
                "compose-preview" to version,
                "Mode" to "public (open)",
                "Uptime" to "3d 4h",
                "Server JVM" to "17.0.11 (Eclipse Adoptium)",
                "Server OS" to "Linux 6.8.0-generic (amd64)",
              ),
            ),
            ServeWeb.BugReportSection(
              "Page",
              listOf(
                "Page" to "/compose-m3/p/button-filled?uiMode=dark",
                "Design system" to "compose-m3",
                "Preview" to "button-filled",
                "Catalog" to "yschimke/compose-ai-tools@design-artifacts/compose-m3",
                "Catalog rendered by" to "compose-ai-tools 0.16.54",
                "Trust" to "branch:yschimke/compose-ai-tools@design-artifacts/compose-m3",
                "Render lane" to "live daemon",
              ),
            ),
            ServeWeb.BugReportSection(
              "Catalogs not loaded",
              listOf("" to "`wear-m3`: failed — daemon launch timed out after 300s"),
            ),
            ServeWeb.BugReportSection(
              "Recent failures",
              listOf(
                "" to
                  "2026-07-17 09:43 UTC  compose-m3 (live bundle): render failed after " +
                    "120000ms (timeout) — timed out waiting for renderFinished"
              ),
            ),
            ServeWeb.BugReportSection(
              "Browser",
              listOf("User agent, viewport, pixel ratio, colour scheme" to "added by your browser"),
            ),
          ),
        version = version,
      )
    // The same page reached from a top-level site with no preview in context: the pixel-bug
    // paragraph names the catalog and links its tracker, and there is no thumbnail.
    val bugReportSitePageContext =
      ServeBugReport.Page(
        path = "/pages/buttons",
        url = "https://wear.preview.coo.ee/pages/buttons",
        system = "wear-m3",
        catalog = "yschimke/wear-m3-catalog@design-artifacts/wear-m3",
        catalogToolVersion = "0.16.54",
        trust = "branch:yschimke/wear-m3-catalog@design-artifacts/wear-m3",
        renderLane = "baked snapshots",
        publicRender = true,
      )
    val bugReportSite =
      ServeWeb.bugReportPage(
        report =
          ServeWeb.BugReport(
            action = ServeBugReport.action(),
            body = ServeBugReport.body(bugReportServer, bugReportSitePageContext),
            bodyTemplate =
              ServeBugReport.body(
                bugReportServer,
                bugReportSitePageContext,
                clientPlaceholder = true,
              ),
            repo = ServeBugReport.REPO,
            login = "yschimke",
            catalog =
              ServeWeb.BugReportCatalog(
                system = "wear-m3",
                title = "Wear Material 3",
                repo = "yschimke/wear-m3-catalog",
                issuesUrl = "https://github.com/yschimke/wear-m3-catalog/issues/new",
                site = true,
              ),
          ),
        sections =
          listOf(
            ServeWeb.BugReportSection(
              "Server",
              listOf(
                "compose-preview" to version,
                "Mode" to "public (open)",
                "Uptime" to "3d 4h",
                "Server JVM" to "17.0.11 (Eclipse Adoptium)",
                "Server OS" to "Linux 6.8.0-generic (amd64)",
              ),
            ),
            ServeWeb.BugReportSection(
              "Page",
              listOf(
                "Page" to "/pages/buttons",
                "Design system" to "wear-m3",
                "Catalog" to "yschimke/wear-m3-catalog@design-artifacts/wear-m3",
                "Catalog rendered by" to "compose-ai-tools 0.16.54",
                "Trust" to "branch:yschimke/wear-m3-catalog@design-artifacts/wear-m3",
                "Render lane" to "baked snapshots",
              ),
            ),
            ServeWeb.BugReportSection(
              "Browser",
              listOf("User agent, viewport, pixel ratio, colour scheme" to "added by your browser"),
            ),
          ),
        version = version,
        siteName = "Wear Material 3",
      )

    // The same page reached from the focused comparison: the evidence is the pair, shown side by
    // side, and the prose notes the diff is missing (the browser composes it).
    val bugReportComparePageContext =
      bugReportPageContext.copy(
        path = "/compose-m3/compare/button-filled?reference=button-figma",
        url = "https://preview.coo.ee/compose-m3/compare/button-filled?reference=button-figma",
        renderUrl = "https://preview.coo.ee/compose-m3/render/button-filled.png",
        referenceUrl = "https://preview.coo.ee/compose-m3/reference/button-figma.png",
      )
    val bugReportCompare =
      ServeWeb.bugReportPage(
        report =
          ServeWeb.BugReport(
            action = ServeBugReport.action(),
            body = ServeBugReport.body(bugReportServer, bugReportComparePageContext),
            bodyTemplate =
              ServeBugReport.body(
                bugReportServer,
                bugReportComparePageContext,
                clientPlaceholder = true,
              ),
            repo = ServeBugReport.REPO,
            // Both thumbnails point at the harness's placeholder lane, like every other fixture's
            // stage: what this golden is about is the arrangement, not the pixels in it.
            renderUrl = "/compose-m3/render/button-filled.png",
            referenceUrl = "/compose-m3/reference/button-figma.png",
            login = "yschimke",
          ),
        sections =
          listOf(
            ServeWeb.BugReportSection(
              "Server",
              listOf(
                "compose-preview" to version,
                "Mode" to "public (open)",
                "Uptime" to "3d 4h",
                "Server JVM" to "17.0.11 (Eclipse Adoptium)",
                "Server OS" to "Linux 6.8.0-generic (amd64)",
              ),
            ),
            ServeWeb.BugReportSection(
              "Page",
              listOf(
                "Page" to "/compose-m3/compare/button-filled?reference=button-figma",
                "Design system" to "compose-m3",
                "Preview" to "button-filled",
                "Catalog" to "yschimke/compose-ai-tools@design-artifacts/compose-m3",
                "Catalog rendered by" to "compose-ai-tools 0.16.54",
                "Trust" to "branch:yschimke/compose-ai-tools@design-artifacts/compose-m3",
                "Render lane" to "live daemon",
              ),
            ),
            ServeWeb.BugReportSection(
              "Browser",
              listOf("User agent, viewport, pixel ratio, colour scheme" to "added by your browser"),
            ),
          ),
        version = version,
      )

    // The page goldens, named once, backing both regeneration and the sync assertion.
    //
    // The card rasters the fixture page below frames, drawn once for both the golden and committed
    // PNGs.
    val unfurlCards = socialCardFixtures()
    val renderedGoldens =
      listOf(
        "serve-landing.html" to landing,
        "serve-landing-public.html" to landingPublic,
        "serve-home-index.html" to homeIndex,
        "serve-home-loading.html" to
          ServeWeb.homeIndexPage(
            homeSystems.take(3).mapIndexed { index, system ->
              system.copy(
                loading = true,
                heroPreviewId = null,
                heroImage = if (index == 0) system.heroImage else null,
              )
            },
            "",
            isPublic = true,
            version = version,
          ),
        "serve-viewer.html" to viewer,
        "serve-viewer-spatial.html" to spatialViewer,
        "serve-viewer-samples.html" to samplesViewer,
        "serve-viewer-samples-as-catalog.html" to samplesViewerAsCatalog,
        "serve-viewer-history.html" to viewerHistory,
        "serve-viewer-history-local.html" to viewerHistoryLocal,
        "serve-viewer-wasm.html" to wasmViewer,
        "serve-viewer-wasm-live.html" to wasmViewerLive,
        "serve-viewer-signin.html" to viewerSignIn,
        "serve-viewer-catalog-knobs.html" to viewerCatalogKnobs,
        "serve-viewer-themes.html" to viewerThemes,
        "serve-viewer-theme-overflow.html" to viewerThemeOverflow,
        "serve-viewer-focus.html" to viewerFocus,
        "serve-viewer-inspect.html" to viewerInspect,
        "serve-viewer-published-typography.html" to viewerPublishedTypography,
        "serve-viewer-exploded.html" to viewerExploded,
        "serve-viewer-gestures.html" to viewerGestures,
        "serve-viewer-source.html" to viewerSource,
        "serve-viewer-motion.html" to viewerMotion,
        "serve-landing-path.html" to landingPath,
        "serve-landing-site.html" to landingSite,
        "serve-viewer-path.html" to viewerPath,
        "serve-viewer-spec-default-theme.html" to viewerSpecDefaultTheme,
        "serve-viewer-rc-players.html" to viewerRcPlayers,
        "serve-viewer-rc-signin.html" to viewerRcSignIn,
        "serve-viewer-rc-parallel.html" to viewerRcParallel,
        "serve-viewer-strip-no-baseline.html" to viewerStripNoBaseline,
        "serve-viewer-wear-screen.html" to viewerWearScreen,
        "serve-landing-themed.html" to landingThemed,
        "serve-landing-catalog-palette.html" to landingCatalogPalette,
        "serve-viewer-catalog-palette.html" to viewerCatalogPalette,
        "serve-format-compare.html" to formatComparison,
        "serve-rc-lanes.html" to rcLanesComparison,
        "serve-rc-lanes-partial.html" to rcLanesPartialComparison,
        "serve-rc-lanes-live.html" to rcLanesLiveComparison,
        "serve-reference-compare.html" to referenceComparison,
        "serve-reference-compare-pinned.html" to referenceComparisonPinned,
        "serve-reference-compare-dark-first.html" to referenceComparisonDarkFirst,
        "serve-reference-compare-round-device.html" to referenceComparisonRoundDevice,
        "serve-viewer-revisions.html" to viewerRevisions,
        "serve-viewer-revisions-open.html" to viewerRevisionsOpen,
        "serve-viewer-revision-runs.html" to viewerRevisionRuns,
        "serve-viewer-pinned-lanes.html" to viewerPinnedLanes,
        "serve-design-page.html" to designPageHtml,
        "serve-design-page-index.html" to designPageIndex,
        "serve-parallel-layers.html" to parallelLayers,
        "serve-motion-index.html" to motionIndex,
        "serve-parity.html" to parity,
        "serve-landing-declared-themes.html" to landingDeclaredThemes,
        "serve-landing-declared-tabbed-themes.html" to landingDeclaredTabbedThemes,
        "serve-landing-ir-replay-themes.html" to landingIrReplayThemes,
        "serve-landing-live.html" to landingLive,
        "serve-landing-states.html" to landingStates,
        "serve-landing-sections.html" to landingSections,
        "serve-landing-breakpoints.html" to landingBreakpoints,
        "serve-viewer-breakpoints.html" to viewerBreakpoints,
        "serve-viewer-states.html" to viewerStates,
        "serve-viewer-axes-folded.html" to viewerAxesFolded,
        "serve-viewer-cross-product.html" to viewerCrossProduct,
        "serve-status.html" to serveStatus,
        "serve-report-bug.html" to bugReport,
        "serve-report-bug-compare.html" to bugReportCompare,
        "serve-report-bug-site.html" to bugReportSite,
        "serve-landing-variants.html" to landingVariants,
        "serve-landing-tree-depth.html" to landingTreeDepth,
        "serve-viewer-variants.html" to viewerVariants,
        "serve-landing-grouped.html" to landingGrouped,
        "serve-component-browser-home.html" to componentBrowserHome,
        "serve-component-browser-catalog.html" to componentBrowserCatalog,
        "serve-component-browser-component.html" to componentBrowserViewer,
        "serve-component-browser-remote-compose.html" to componentBrowserRemoteCompose,
        "serve-viewer-nav-collapsed.html" to viewerNavCollapsed,
        "serve-viewer-nav-sections.html" to viewerNavSections,
        "serve-notfound.html" to notFound,
        "serve-agent-access.html" to agentAccess,
        "serve-agent-access-capabilities.html" to agentAccessCapabilities,
        "serve-agent-access-oauth.html" to agentAccessOAuth,
        "serve-agent-access-granted.html" to agentAccessGranted,
        "serve-docs-upload.html" to docUpload,
        "serve-admin-ui-builder.html" to uiBuilderAdmin,
        "serve-admin-ui-builder-read.html" to uiBuilderAdminRead,
        "serve-ui-builder-designs.html" to uiBuilderDesigns,
        "serve-playground.html" to playground,
        "serve-playground-uncompilable.html" to playgroundUncompilable,
        "serve-doc-lottie.html" to docLottie,
        "serve-doc-remotecompose.html" to docRemoteCompose,
        "serve-doc-remotecompose-players.html" to docRemoteComposePlayers,
        // Not a served page: a frame around the drawn link-unfurl cards so they are diffed. See
        // [socialCardPage].
        "serve-social-card.html" to socialCardPage(unfurlCards),
      )

    // Normalise once, here, so the regeneration below and the sync assertion further down cannot
    // disagree about what a golden is supposed to contain.
    val goldens = renderedGoldens.map { (name, html) -> name to stableAssetHrefs(html) }

    if (update) {
      pagesDir.mkdirs()
      goldens.forEach { (name, html) -> File(pagesDir, name).writeText(html) }
      writePlaceholderPng(File(pagesDir, "_render-placeholder.png"))
      File(pagesDir, "_render-placeholder.svg").writeText(renderPlaceholderSvg())
      unfurlCards.forEach { (name, card) -> File(pagesDir, name).writeBytes(card.bytes) }
      return
    }

    assertGoldensInSync(pagesDir, goldens)
    assertUnfurlCardsInSync(pagesDir, unfurlCards)
    assertFalse(
      designPageHtml.contains(
        "class=\"cp-page-node\" data-link=\"unlinked\" data-cp-node=\"1:0\""
      ) ||
        designPageHtml.contains(
          "class=\"cp-page-row\" data-link=\"unlinked\" data-cp-node=\"1:0\""
        ),
      "a component-set grid is page structure, never a giant interactive hotspot or audit row",
    )
    assertFalse(
      designPageHtml.contains("data-cp-node=\"1:9\""),
      "private sheet furniture is not presented as missing component work",
    )
    assertTrue(
      designPageHtml.contains("data-cp-gap data-cp-node=\"1:6\""),
      "a concrete unimplemented component remains a focused gap hotspot",
    )
    // The parity page's load-bearing claims: a comment on Switch (code unchanged) is one-sided
    // design movement and must reach "needs a look"; Button moved on both sides and must not.
    assertTrue(
      parity.contains("Out-of-sync activity") &&
        parity.contains("Switch on") &&
        parity.contains("design only"),
      "one-sided design movement reaches the drift band",
    )
    assertFalse(
      parity
        .substringAfter("Out-of-sync activity")
        // `<cp-parity-scores>` renders its own heading client-side, so the tag bounds the drift
        // band.
        .substringBefore("<cp-parity-scores>")
        .contains("Button"),
      "a component that moved on both sides is not drift",
    )
    assertTrue(
      parity.contains("All comparisons (3)") &&
        parity.contains("data-parity-comparison") &&
        parity.contains("<cp-parity-scores></cp-parity-scores>"),
      "the secondary inventory includes measured visual parity: $parity",
    )
    // Coverage is derived live from the previews + references, never from the published feed: 3
    // components (light/dark folded), one of them mapped.
    assertTrue(
      parity.contains("mapped</div>") && parity.contains(">1/3<"),
      "coverage tile: $parity",
    )
    // The unmapped chips link to the viewer; a mapped component's feed row links to its comparison.
    assertTrue(
      parity.contains("href=\"/compose-m3/p/switch-on__ideal__default__light\""),
      "an unmapped component opens its viewer",
    )
    assertTrue(
      parity.contains("href=\"/compose-m3/compare/button-filled__ideal__default__light\""),
      "a mapped component opens its reference comparison",
    )
    // A resolved comment is still shown (it is history) but visually stood down.
    assertTrue(parity.contains("cp-parity-entry--resolved"), "resolved comments render greyed")
    // The overrides drawer defaults closed so the preview leads.
    assertTrue(
      viewer.contains("class=\"cp-viewer\"") &&
        viewer.contains("id=\"cp-controls-toggle\" aria-expanded=\"false\""),
      "the overrides drawer defaults closed",
    )
    // The nav drawer defaults closed (toggle collapsed, no `cp-nav-open` on the viewer element)
    // while still linking siblings. Scoped to the element's class attribute because the token also
    // appears in CSS and script.
    assertTrue(
      viewer.contains("id=\"cp-nav\"") &&
        viewer.contains("id=\"cp-nav-toggle\" aria-expanded=\"false\"") &&
        viewer.contains("class=\"cp-viewer\"") &&
        !viewer.contains("class=\"cp-viewer cp-nav-open\""),
      "the component nav drawer defaults closed",
    )
    assertTrue(
      viewer.contains("class=\"cp-nav-item\" href=\"/p/com.example.ButtonPreview?token="),
      "the nav drawer links each sibling to its viewer page",
    )
    // A single-preview session shows neither the drawer nor its toggle, whether siblings are empty
    // or contain only the current preview.
    for (solo in
      listOf(
        ServeWeb.viewerPage(previews.first(), token),
        ServeWeb.viewerPage(previews.first(), token, siblings = listOf(previews.first())),
      )) {
      assertFalse(
        solo.contains("id=\"cp-nav\"") || solo.contains("id=\"cp-nav-toggle\""),
        "a single-preview session shows no component nav drawer",
      )
    }
    // Project mode addresses an old render on THIS server (by content sha) rather than on
    // raw.githubusercontent.com, and must not advertise a manifest URL it has no repo to fetch.
    assertTrue(
      viewerHistoryLocal.contains("data-history-blob-url=\"/history/render/{blob}.png?token=") &&
        !viewerHistoryLocal.contains("data-history-url=") &&
        !viewerHistoryLocal.contains("data-history-repo="),
      "the project-mode timeline links at this server's own render lane",
    )
    // Declared RC knobs render as their own "Remote Compose" group, one `.cp-rc-knob` per knob with
    // name + wire kind.
    assertTrue(
      viewerCatalogKnobs.contains("data-cp-group=\"remotecompose\"") &&
        viewerCatalogKnobs.contains(">Remote Compose</summary>"),
      "the live catalog knob viewer shows the Remote Compose control group",
    )
    assertTrue(
      viewerCatalogKnobs.contains(
        "class=\"cp-rc-knob\" data-rc-name=\"shaderColor\" " + "data-rc-kind=\"color\""
      ),
      "the declared RC colour knob renders a control tagged with its name + kind",
    )
    assertTrue(
      viewerCatalogKnobs.contains("data-rc-name=\"label\" data-rc-kind=\"string\""),
      "the declared RC string knob renders a control tagged with its name + kind",
    )
    // A preview that declares no RC knobs shows no Remote Compose group (no dead panel).
    assertFalse(
      viewerThemes.contains("data-cp-group=\"remotecompose\""),
      "a preview without RC knobs shows no Remote Compose control group",
    )
    // The detected-feature control shows for a focus-supporting preview…
    assertTrue(
      viewerFocus.contains("id=\"cp-focus\"") && viewerFocus.contains("Keyboard focus"),
      "a @FocusedPreview preview shows the Keyboard focus control",
    )
    // …and NOT for an ordinary preview (no dead control).
    assertFalse(
      viewerThemes.contains("id=\"cp-focus\""),
      "a preview without @FocusedPreview shows no Keyboard focus control",
    )
    // The gesture control shows for a gesture-supporting preview on an Android-backed session…
    assertTrue(
      viewerGestures.contains("id=\"cp-gestures\"") &&
        viewerGestures.contains("Show gesture hints"),
      "a @GestureHintPreview preview shows the Show gesture hints control on an Android session",
    )
    // …but NOT on a desktop-backed session (gesturesRenderable = false) — the row is omitted, not
    // shown dead, since the desktop daemon ignores the override.
    assertFalse(
      viewerGesturesDesktop.contains("id=\"cp-gestures\""),
      "a gesture-supporting preview shows no gesture control on a desktop session",
    )
    // Firing the gesture, labelled as the wearer's gesture and carrying the wire kind the daemon
    // invokes.
    assertTrue(
      viewerGestures.contains("cp-gesture-invoke\" data-gesture=\"primary\"") &&
        viewerGestures.contains(">Double pinch</button>"),
      "a gesture-supporting preview offers a button that fires the primary gesture",
    )
    assertTrue(
      viewerGestures.contains("cp-gesture-invoke\" data-gesture=\"dismiss\"") &&
        viewerGestures.contains(">Wrist turn</button>"),
      "a gesture-supporting preview offers a button that fires the dismiss gesture",
    )
    // The hidden holder the click writes and the render reads once — without it the buttons have
    // nowhere to put the kind, and the render would carry no `gestureInvoke` at all.
    assertTrue(
      viewerGestures.contains("id=\"cp-gesture-invoke\""),
      "the gesture-invoke buttons have the hidden field the render reads",
    )
    // Same lane rule as the hint row: a desktop session cannot invoke a handler, so the buttons are
    // absent rather than dead.
    assertFalse(
      viewerGesturesDesktop.contains("cp-gesture-invoke"),
      "a gesture-supporting preview offers no fire-a-gesture buttons on a desktop session",
    )
    // The projected palette is inlined AFTER serve.css, so it wins at equal specificity — and it
    // carries both modes, so a dark-mode visitor to a light-first catalog still gets its brand.
    assertTrue(
      landingCatalogPalette.substringAfter("serve.css").startsWith("\">\n        <style>"),
      "the catalog palette is inlined directly after the stylesheet link",
    )
    assertTrue(
      // Both modes, as one `light-dark()` pair per property — the shape the page-theme setting
      // needs, since pinning `color-scheme` can only re-resolve a pair (see ServeThemeCssTest).
      viewerCatalogPalette.contains("--cp-accent: light-dark(#bf0031, ") &&
        !viewerCatalogPalette.contains("@media (prefers-color-scheme: dark)"),
      "the viewer carries the catalog's accent in both modes",
    )
    // A plain (non-catalog) session inlines nothing at all.
    assertFalse(landingThemed.contains("<style>"), "an unthemed page carries no inline palette")
    // Every page inside a catalog carries the palette.
    val palette = ServeThemeCss.fromDtcg(jetNewsTokens)!!
    val inCatalogPages =
      mapOf(
        "landing" to ServeWeb.landingPage("jetnews", themedPreviews, token, themeCss = palette),
        "viewer" to ServeWeb.viewerPage(previews.first(), token, themeCss = palette),
        "format comparison" to
          ServeWeb.comparisonPage("jetnews", themedPreviews, token, themeCss = palette),
        "reference comparison" to
          ServeWeb.referenceComparisonPage(
            moduleLabel = "jetnews",
            preview = themedPreviews.first(),
            reference = comparisonReferences.first(),
            token = token,
            themeCss = palette,
          ),
      )
    for ((name, html) in inCatalogPages) {
      assertTrue(
        html.contains("--cp-accent: light-dark(#bf0031, "),
        "the $name page carries the palette",
      )
    }
    // One assist chip per baseline, under one heading carrying the shared verb. See
    // `docs/design/COMPARE_NAVIGATION.md` §3.3.
    assertTrue(
      landingThemed.contains("<span class=\"cp-actions-group-label\">Compare against</span>") &&
        landingThemed.contains(
          "<a class=\"cp-action-chip\" href=\"/compare?format=svg&amp;session=compose-m3\">SVG</a>"
        ) &&
        landingThemed.contains(
          "<a class=\"cp-action-chip\" href=\"/compare?format=rc&amp;session=compose-m3\">" +
            "Remote Compose players</a>"
        ),
      "a catalog with alternate formats links each one separately: $landingThemed",
    )
    // The reference comparison is one of them, named after its tool. The parity index is not; it
    // sits under `Reports`.
    assertTrue(
      landingPath.contains(
        "<a class=\"cp-action-chip\" href=\"/meshcore-mobile/compare?format=reference\">Figma</a>"
      ) &&
        landingPath.contains(
          "<div class=\"cp-actions-group\"><span class=\"cp-actions-group-label\">Reports</span>" +
            "<a class=\"cp-action-chip\" href=\"/meshcore-mobile/parity\">design parity</a></div>"
        ),
      "a Figma-specified catalog compares against Figma and links the parity index separately",
    )
    assertTrue(
      formatComparison.contains("data-compare-format=\"svg\"") &&
        formatComparison.contains("data-compare-format=\"rc\"") &&
        formatComparison.contains("data-compare-format=\"reference\"") &&
        formatComparison.contains("data-compare-format=\"parallel\"") &&
        formatComparison.contains("data-compare-theme=\"light\"") &&
        formatComparison.contains("data-compare-theme=\"dark\""),
      "the comparison page exposes only its available formats and its baked theme pair",
    )
    // The player wall's load-bearing claims, asserted rather than left to the pixel diff.
    assertTrue(
      rcLanesComparison.contains("id=\"cp-rc-lanes\"") &&
        rcLanesComparison.contains("data-rc-lanes=\"1\"") &&
        rcLanesComparison.contains(">Remote Compose players</button>") &&
        rcLanesComparison.contains("<cp-rc-lanes></cp-rc-lanes>"),
      "a catalog with a published rc-compare manifest gets the player wall, not the in-browser lane",
    )
    assertEquals(
      listOf(
        "AndroidX Embedded · baked",
        "Camaelon JS",
        "AndroidX Embedded · vendored Android",
        "AndroidX Embedded · androidx.dev",
        "CMP JVM",
        "CMP Wasm",
      ),
      Regex("<th>([^<]+)</th>")
        .findAll(rcLanesComparison.substringAfter("cp-rc-table").substringBefore("</thead>"))
        .map { it.groupValues[1] }
        .toList()
        .drop(1),
      "every player the run covered is its own column, in the published order",
    )
    assertEquals(
      listOf("none", "baked", "js", "embedded", "androidx-embedded", "cmp-jvm", "cmp-wasm"),
      Regex("data-rc-ref=\"([^\"]+)\"")
        .findAll(rcLanesComparison)
        .map { it.groupValues[1] }
        .toList(),
      "every column — including the baked reference — can itself be picked as the diff reference",
    )
    // Worst-match first by the worst-scoring player, so a preview only one player gets wrong
    // surfaces.
    assertEquals(
      listOf(
          "button-filled__ideal__default__light",
          "switch-on__ideal__default__light",
          "button-filled__ideal__default__dark",
          "switch-on__ideal__default__dark",
          "badge",
        )
        .map { "/p/$it" },
      Regex("<a href=\"(/p/[^\"?]+)")
        .findAll(rcLanesComparison.substringAfter("cp-rc-table"))
        .map { it.groupValues[1] }
        .toList(),
      "rows sort worst-match-first on the worst-scoring player",
    )
    assertTrue(
      rcLanesComparison.contains("/rc-compare/js/0.png?session=remote-m3") &&
        rcLanesComparison.contains("cp-rc-missing\">Document is not renderable by the CMP player"),
      "a rendered lane shows its published PNG; a lane that refused the document shows its reason",
    )
    // A run covering every lane says nothing about absent players.
    assertFalse(
      rcLanesComparison.contains("cp-rc-absent"),
      "a wall showing every known player carries no absent-players note",
    )
    // ...and one whose run covered only three names the other three, so the missing columns read
    // as "this run didn't include them" rather than as a page that lost its players.
    assertEquals(
      listOf(
        "AndroidX Embedded · vendored Android",
        "AndroidX Embedded · androidx.dev",
        "CMP JVM",
      ),
      Regex("<span class=\"cp-rc-absent-lane\">([^<]+)</span>")
        .findAll(rcLanesPartialComparison)
        .map { it.groupValues[1] }
        .toList(),
      "a wall with no live players names every one the run did not publish",
    )
    assertEquals(
      listOf("AndroidX Embedded · baked", "Camaelon JS", "CMP Wasm"),
      Regex("<th>([^<]+)</th>")
        .findAll(rcLanesPartialComparison.substringAfter("cp-rc-table").substringBefore("</thead>"))
        .map { it.groupValues[1] }
        .toList()
        .drop(1),
      "a partial run still gets a column per player it did publish, and no empty ones",
    )
    // The live wall: the two server-side players fill in, in the vocabulary's order, and only the
    // player nothing can draw on demand is still reported absent.
    assertEquals(
      listOf(
        "AndroidX Embedded · baked",
        "Camaelon JS",
        "CMP JVM",
        "CMP Wasm",
        // Last, and deliberately: the offline pipeline has no `androidx-view` column, so this lane
        // cannot claim a position in an order it is not part of.
        "AndroidX View",
      ),
      Regex("<th>([^<]+)</th>")
        .findAll(rcLanesLiveComparison.substringAfter("cp-rc-table").substringBefore("</thead>"))
        .map { it.groupValues[1] }
        .toList()
        .drop(1),
      "a live column appears for a renderer that cannot duplicate the baked capture, and only that",
    )
    // The one that must NOT appear, and the reason this filter exists at all.
    assertFalse(
      rcLanesLiveComparison.contains("rcPlayer=androidx-embedded") ||
        rcLanesLiveComparison.contains("rcPlayer=cmp-android"),
      "androidx-embedded is never filled live: an Android daemon answers it with the baked bytes",
    )
    assertEquals(
      listOf("AndroidX Embedded · vendored Android", "AndroidX Embedded · androidx.dev"),
      Regex("<span class=\"cp-rc-absent-lane\">([^<]+)</span>")
        .findAll(rcLanesLiveComparison)
        .map { it.groupValues[1] }
        .toList(),
      "only a player neither published nor drawable on demand is still reported absent",
    )
    // The note says which reason applies: `embedded` is drawable here but omitted as a duplicate of
    // baked.
    assertTrue(
      rcLanesLiveComparison.contains(
        "This host does draw AndroidX Embedded · vendored Android — but through the same embedded " +
          "player the baked column already went through"
      ),
      "a player withheld to avoid duplicating baked is distinguished from one the host cannot draw",
    )
    assertFalse(
      rcLanesPartialComparison.contains("This host does draw"),
      "a host with no live players has nothing withheld, and says nothing about drawing",
    )
    assertEquals(
      listOf("none", "baked", "js", "cmp-jvm", "cmp-wasm", "androidx-view"),
      Regex("data-rc-ref=\"([^\"]+)\"")
        .findAll(rcLanesLiveComparison)
        .map { it.groupValues[1] }
        .toList(),
      "a live column can be picked as the diff reference like any other",
    )
    // Each cell asks the server for that player's raster of this preview via `?rcPlayer=`.
    assertTrue(
      rcLanesLiveComparison.contains(
        "/render/button-filled__ideal__default__light.png?session=remote-m3&amp;rcPlayer=cmp-jvm"
      ),
      "a live cell points at this host's render endpoint for that player and preview",
    )
    assertEquals(
      8,
      Regex("data-live=\"1\"").findAll(rcLanesLiveComparison).count(),
      "every live cell is marked as drawn on request — two players over the four rows that carry a document",
    )
    // The androidx-view lane is named by this wall rather than by the offline vocabulary, so its
    // cells still have to point at the host's render endpoint like any other live column.
    assertTrue(
      rcLanesLiveComparison.contains(
        "/render/button-filled__ideal__default__light.png?session=remote-m3&amp;rcPlayer=androidx-view"
      ),
      "the wall-named androidx-view column renders through this host, not from staged bytes",
    )
    // The inlined client model must carry the columns, not the published lanes: `RcLanes` reads
    // lane ids from here, so a missing live column could not be diffed or shared via `?ref=`.
    assertEquals(
      listOf("baked", "js", "cmp-jvm", "cmp-wasm", "androidx-view"),
      Regex("\"id\":\"([^\"]+)\"")
        .findAll(
          rcLanesLiveComparison
            .substringAfter("id=\"cp-rc-model\">")
            .substringBefore("</script>")
            .substringBefore("\"rows\"")
        )
        .map { it.groupValues[1] }
        .toList(),
      "the client model lists every column on the wall, live ones included",
    )
    // …and it is never reported absent, because no parity run could ever have published it.
    assertFalse(
      rcLanesLiveComparison.contains("cp-rc-absent-lane\">AOSP"),
      "a player the offline pipeline has no column for is never named in the absent note",
    )
    assertTrue(
      rcLanesLiveComparison.contains(
        "cp-rc-missing\">this host cannot draw this player for this preview"
      ),
      "a row the host has no document for says so rather than showing an empty frame",
    )
    // A staged column is untouched by any of this: same published raster, same build-time score.
    assertTrue(
      rcLanesLiveComparison.contains("/rc-compare/js/0.png?session=remote-m3") &&
        !rcLanesPartialComparison.contains("data-live"),
      "a published column still replays its staged raster, and a host with no live players changes nothing",
    )
    val escapedComparison =
      ServeWeb.comparisonPage(
        moduleLabel = "compose-m3",
        previews = themedPreviews,
        token = token,
        displayTitle = "<script>alert('title')</script>",
        hasSvgFor = { true },
      )
    assertTrue(
      escapedComparison.contains("&lt;script&gt;alert(&#39;title&#39;)&lt;/script&gt;</a>") &&
        !escapedComparison.contains("<script>alert('title')</script></a>"),
      "the catalog-authored comparison breadcrumb title is HTML-escaped",
    )
    assertTrue(
      referenceComparison.contains(">Reference</h2>") &&
        referenceComparison.contains(">Diff</h2>") &&
        referenceComparison.contains(">Actual</h2>") &&
        referenceComparison.contains("Source:</strong> figma · revision fixture-42") &&
        referenceComparison.contains("aria-label=\"Design references\"") &&
        referenceComparison.contains(">Review revision</a>"),
      "the focused comparison presents the handoff triptych and provenance",
    )
    assertTrue(
      referenceComparison.contains("compose-parity-locator/v1") &&
        referenceComparison.contains(
          "overrides: {&quot;fontScale&quot;:&quot;1.5&quot;,&quot;knob.label&quot;:&quot;Send;now=x&quot;}"
        ) &&
        referenceComparison.contains("{{rawScores}}"),
      "the focused comparison pins the locator, canonical overrides and score placeholder",
    )
    // The selection placeholder rides in the TEMPLATE only. The server-rendered body is what a
    // visitor with JS off files, and it must not carry a token nothing will ever substitute.
    assertEquals(
      1,
      referenceComparison.split("{{selection}}").size - 1,
      "the selection placeholder appears once, in the template and nowhere else",
    )
    assertTrue(
      referenceComparison.substringAfter("data-report-template=\"").contains("{{selection}}"),
      "the selection placeholder rides in the report template",
    )
    assertTrue(
      referenceComparison.contains("id=\"cp-compare-actual\"") &&
        referenceComparison.contains("id=\"cp-render-inspect-layer\"") &&
        referenceComparison.contains("data-cp-host=\"#cp-compare-actual\"") &&
        // Opted in, so a box on THIS page is a target rather than only a reading aid — the brief's
        // first of two ways to choose. The viewer's mount carries no such attribute.
        referenceComparison.contains("data-cp-selectable=\"1\"") &&
        referenceComparison.contains("<cp-inspect-layers"),
      "the focused comparison mounts the derived semantics layers over its Actual panel",
    )
    assertTrue(
      referenceComparison.contains("<cp-element-selection>") &&
        referenceComparison.contains("class=\"cp-selection-tag\"") &&
        // Built through the page's own link rules, so it carries whatever credential the reader
        // presented (including header / bearer auth).
        referenceComparison.contains(
          "data-cp-tags=\"/tags/button-filled__ideal__default__light?session=compose-m3\""
        ) &&
        referenceComparison.contains("id=\"cp-selection-layer\""),
      "the focused comparison offers both a tag picker and a drag region",
    )
    // The pinned twin withholds tag selection: the published index describes the current render, so
    // bounds would be measured on different pixels. The drag stays.
    assertTrue(
      !referenceComparisonPinned.contains("data-cp-tags=") &&
        referenceComparisonPinned.contains("class=\"cp-selection-drag\""),
      "a pinned comparison withholds tag selection and keeps the drag",
    )
    // ...and withholds selecting annotation layers for the same reason (`.annotations` is fetched
    // separately from the PNG on screen). Layers still draw.
    assertTrue(
      !referenceComparisonPinned.contains("data-cp-selectable="),
      "a pinned comparison withholds annotation-box selection too",
    )
    // Baked PNG with live-daemon annotations is the same mismatch, so `annotationsSelectable` must
    // not be read off the PNG lane's flags.
    val liveAnnotationsComparison =
      ServeWeb.referenceComparisonPage(
        moduleLabel = "compose-m3",
        preview = themedPreviews.first(),
        reference = comparisonReferences.first(),
        references = comparisonReferences,
        token = token,
        sessionId = "compose-m3",
        isPublic = true,
        version = version,
        derivedAnnotations = true,
        annotationsSelectable = false,
        reportIssue =
          fixtureReportIssue(
            previewId = themedPreviews.first().id,
            label = themedPreviews.first().label,
            sourceFile = themedPreviews.first().sourceFile.orEmpty(),
            componentId = ServeIssueReport.componentIdFor(themedPreviews.first()),
            referenceId = comparisonReferences.first().id,
            variant = ServeIssueReport.variantFor(themedPreviews.first()),
            comparison = true,
          ),
      )
    assertTrue(
      liveAnnotationsComparison.contains("id=\"cp-render-inspect-layer\"") &&
        !liveAnnotationsComparison.contains("data-cp-selectable=") &&
        liveAnnotationsComparison.contains("class=\"cp-selection-drag\""),
      "live annotations draw but cannot be selected; the drag stays",
    )
    assertTrue(
      liveAnnotationsComparison.contains("data-cp-inspect=\"theme\"") &&
        liveAnnotationsComparison.contains("data-cp-inspect=\"layout\""),
      "the semantics lane carries all three layers",
    )
    // The static bundle is the one selectable host: it answers `.annotations` from what was
    // published over the PNG it serves. It has no `hasDesignAnnotationsFor`, so the mount must not
    // be gated on that alone; this asserts the combination.
    val publishedTypographyComparison =
      ServeWeb.referenceComparisonPage(
        moduleLabel = "compose-m3",
        preview = themedPreviews.first(),
        reference = comparisonReferences.first(),
        references = comparisonReferences,
        token = token,
        sessionId = "compose-m3",
        isPublic = true,
        version = version,
        derivedAnnotations = false,
        publishedTypography = true,
        annotationsSelectable = true,
        reportIssue =
          fixtureReportIssue(
            previewId = themedPreviews.first().id,
            label = themedPreviews.first().label,
            sourceFile = themedPreviews.first().sourceFile.orEmpty(),
            componentId = ServeIssueReport.componentIdFor(themedPreviews.first()),
            referenceId = comparisonReferences.first().id,
            variant = ServeIssueReport.variantFor(themedPreviews.first()),
            comparison = true,
          ),
      )
    assertTrue(
      publishedTypographyComparison.contains("id=\"cp-render-inspect-layer\"") &&
        publishedTypographyComparison.contains("data-cp-selectable=\"1\""),
      "a published-typography host mounts the layers AND may select them",
    )
    // Typography only: Theme and Layout come from a semantics tree a bundle doesn't carry.
    assertTrue(
      publishedTypographyComparison.contains("data-cp-inspect=\"typography\"") &&
        !publishedTypographyComparison.contains("data-cp-inspect=\"theme\"") &&
        !publishedTypographyComparison.contains("data-cp-inspect=\"layout\""),
      "the published lane carries Typography and no dead controls",
    )
    // And a host with neither lane draws nothing at all rather than an empty layer div.
    val noAnnotationsComparison =
      ServeWeb.referenceComparisonPage(
        moduleLabel = "compose-m3",
        preview = themedPreviews.first(),
        reference = comparisonReferences.first(),
        references = comparisonReferences,
        token = token,
        sessionId = "compose-m3",
        isPublic = true,
        version = version,
        derivedAnnotations = false,
        publishedTypography = false,
        reportIssue =
          fixtureReportIssue(
            previewId = themedPreviews.first().id,
            label = themedPreviews.first().label,
            sourceFile = themedPreviews.first().sourceFile.orEmpty(),
            componentId = ServeIssueReport.componentIdFor(themedPreviews.first()),
            referenceId = comparisonReferences.first().id,
            variant = ServeIssueReport.variantFor(themedPreviews.first()),
            comparison = true,
          ),
      )
    assertTrue(
      !noAnnotationsComparison.contains("id=\"cp-render-inspect-layer\"") &&
        !noAnnotationsComparison.contains("<cp-inspect-layers") &&
        noAnnotationsComparison.contains("class=\"cp-selection-drag\""),
      "no annotation lane means no mount, and the drag still stands alone",
    )
    // The substitution lives in `<cp-reference-compare>`'s bundle; the filled report reaches only
    // an input's `value`, never a navigation sink. Placeholders are matched in the exact shape the
    // writer emits (a markdown link destination, a whole table row) so catalog text containing the
    // literal is not rewritten; these strings are a contract between the two files.
    assertTrue(
      assetText("compare-components.js").contains(".value=") &&
        assetText("compare-components.js").contains("](" + "{{render}})") &&
        assetText("compare-components.js").contains("| Raw comparison | `{{rawScores}}` |"),
      "the components bundle substitutes the report input value after comparison",
    )
    val referencedState =
      ServePreview(
        id = "button-filled__ideal__pressed__light",
        label = "Button · Filled pressed",
        state = "pressed",
        theme = "light",
      )
    assertTrue(
      ServeWeb.comparisonPage(
          moduleLabel = "compose-m3",
          previews = listOf(referencedState),
          token = token,
          referencesFor = { id ->
            listOf(
              DesignReference(
                id = "pressed-reference",
                previewId = id,
                raster = DesignReferenceRaster("references/pressed.png"),
              )
            )
          },
        )
        .contains("data-preview-ids=\"button-filled__ideal__pressed__light\""),
      "an exactly referenced non-default state remains a comparison row",
    )
    val buttonComparison =
      formatComparison.substringAfter("data-label=\"button-filled\"").substringBefore("</tr>")
    assertTrue(
      buttonComparison.contains(
        "data-png-light=\"/render/button-filled__ideal__default__light.png?session=compose-m3\""
      ) &&
        buttonComparison.contains(
          "data-svg-light=\"/render/button-filled__ideal__default__light.svg?session=compose-m3\""
        ) &&
        buttonComparison.contains(
          "data-png-dark=\"/render/button-filled__ideal__default__dark.png?session=compose-m3\""
        ) &&
        buttonComparison.contains(
          "data-svg-dark=\"/render/button-filled__ideal__default__dark.svg?session=compose-m3\""
        ),
      "each comparison row pairs PNG and SVG from the exact same baked theme variant",
    )
    val variantComparison =
      ServeWeb.comparisonPage(
        "compose-m3",
        variantPreviews,
        token,
        sessionId = "compose-m3",
        hasSvgFor = { true },
      )
    // The fold is published once in the page's alias table (`docs/design/COMPARE_NAVIGATION.md`
    // F2); a deep link naming a folded variant still selects the row standing for it.
    val variantAliases =
      variantComparison.substringAfter("id=\"cp-compare-aliases\">").substringBefore("</script>")
    assertTrue(
      variantAliases.contains("button-filled__ideal__default__light__direction-rtl"),
      "a folded non-default variant deep-link aliases to its included component comparison row",
    )
    val sizedVariantPreviews =
      listOf("compact", "expanded").flatMap { size ->
        listOf(
          ServePreview(
            "button-filled__ideal__default__light__$size",
            "Button · Filled · $size",
            state = "default",
            theme = "light",
          ),
          ServePreview(
            "button-filled__ideal__pressed__light__$size",
            "Button · Filled · pressed · $size",
            state = "pressed",
            theme = "light",
          ),
          ServePreview(
            "button-filled__ideal__default__light__${size}__direction-rtl",
            "Button · Filled · RTL · $size",
            state = "default",
            theme = "light",
            props = jsonProps("direction" to "rtl"),
          ),
        )
      }
    val sizedVariantComparison =
      ServeWeb.comparisonPage(
        "compose-m3",
        sizedVariantPreviews,
        token,
        sessionId = "compose-m3",
        hasSvgFor = { true },
      )
    val sizedComparisonIds =
      Regex("data-preview-ids=\"([^\"]+)\"")
        .findAll(sizedVariantComparison)
        .map { it.groupValues[1] }
        .toList()
    assertEquals(2, sizedComparisonIds.size)
    // Read the fold from the alias table keyed by comparison card: the compact card folds its own
    // state and props variants and nothing from the expanded one.
    val sizedTable =
      sizedVariantComparison
        .substringAfter("id=\"cp-compare-aliases\">")
        .substringBefore("</script>")
    val compactCard =
      Regex("\"([^\"]*compact[^\"]*)\":\"([^\"]*)\"")
        .find(sizedTable)
        ?.groupValues
        ?.get(2)
        .orEmpty()
    assertTrue(
      compactCard.contains("__pressed__light__compact") &&
        compactCard.contains("__compact__direction-rtl") &&
        !compactCard.contains("__expanded"),
      "compact aliases fold state and props without selecting the expanded comparison row: " +
        sizedTable,
    )
    val expandedCard =
      Regex("\"([^\"]*expanded[^\"]*)\":\"([^\"]*)\"")
        .find(sizedTable)
        ?.groupValues
        ?.get(2)
        .orEmpty()
    assertTrue(
      expandedCard.contains("__pressed__light__expanded") &&
        expandedCard.contains("__expanded__direction-rtl") &&
        !expandedCard.contains("__compact"),
      "expanded aliases fold state and props without selecting the compact comparison row: " +
        sizedTable,
    )
    // Long-press streams a card from the daemon in place. The page carries each card's streamable
    // ids (in document order) and the header note.
    assertTrue(
      landingLive.contains("window.cpCatalogLive = {base:\"\",query:\"session=compose-m3\"") &&
        landingLive.contains("cards:[{l:\"button-filled__ideal__default__light\"") &&
        landingLive.contains("hold a card for a live session"),
      "the live catalog page wires the long-press lane",
    )
    // The header lists every configured theme: the baked pair plus one chip per declared
    // `@ThemeCatalog` theme with its provider FQN.
    assertTrue(
      landingDeclaredThemes.contains("data-theme-choice=\"light\"") &&
        landingDeclaredThemes.contains("data-theme-choice=\"dark\"") &&
        landingDeclaredThemes.contains(
          "data-theme-choice=\"theme:com.example.BrandLightThemeCatalog\""
        ) &&
        landingDeclaredThemes.contains(
          "data-theme-choice=\"theme:com.example.HighContrastThemeCatalog\""
        ),
      "the catalog Theme control offers the baked pair plus every declared theme",
    )
    // ...unless cards are replayed from a captured document: declared chips go, the baked pair
    // stays.
    assertTrue(
      landingIrReplayThemes.contains("data-theme-choice=\"light\"") &&
        landingIrReplayThemes.contains("data-theme-choice=\"dark\""),
      "an IR-replayed catalog keeps its baked light/dark axis",
    )
    assertFalse(
      landingIrReplayThemes.contains("data-theme-choice=\"theme:"),
      "…and offers no declared theme, whose render the server refuses 409",
    )
    assertFalse(
      landingIrReplayThemes.contains("var themeBase = ["),
      "…nor any themed-render URL for the script to fetch",
    )
    // Picking a declared theme re-renders through `themeProvider`. Per-card base URLs are emitted
    // by the server, never read from the DOM (CodeQL js/xss-through-dom).
    assertTrue(
      landingDeclaredThemes.contains(
        "var themeBase = [\"/render/button-filled__ideal__default__light.png?session=compose-m3\""
      ) && landingDeclaredThemes.contains("\"themeProvider=\" + encodeURIComponent(provider)"),
      "the server emits each card's themed-render URL for the script to use",
    )
    assertFalse(
      landingDeclaredThemes.contains("data-base-src"),
      "no render URL is round-tripped through a DOM attribute",
    )
    // A declared-theme selection asks the server for one short-lived page lease. A grant may run
    // five workers; denial/failure stays serial. Retries keep the same lease capability.
    assertTrue(
      landingDeclaredThemes.contains("var job = {") &&
        landingDeclaredThemes.contains(
          "var themeLeaseUrl = \"/api/theme-render-lease?session=compose-m3\""
        ) &&
        landingDeclaredThemes.contains("var themeRenderRetries = 3") &&
        landingDeclaredThemes.contains("function acquireThemeLease(gen, callback)") &&
        landingDeclaredThemes.contains("Math.max(1, Math.min(5, grant.concurrency))") &&
        landingDeclaredThemes.contains("function runThemeWorker(queue, gen, batch)") &&
        landingDeclaredThemes.contains(
          "runThemeQueue(themeQueue, themeQueueGen, lease, concurrency)"
        ) &&
        landingDeclaredThemes.contains("var workers = Math.min(concurrency, queue.length)") &&
        landingDeclaredThemes.contains("&_themeLease=\" + encodeURIComponent(lease)") &&
        landingDeclaredThemes.contains("job.src = job.baseSrc + \"&_retry=\" + job.retries") &&
        landingDeclaredThemes.contains("if (gen !== themeGen) return;") &&
        landingDeclaredThemes.contains(
          "queue.push(job);\n        runThemeWorker(queue, gen, batch)"
        ) &&
        landingDeclaredThemes.contains("1000 * Math.pow(2, job.retries)") &&
        landingDeclaredThemes.contains("releaseThemeLease(batch.lease, false)") &&
        landingDeclaredThemes.contains("navigator.sendBeacon(url, \"\")"),
      "themed renders use a leased worker burst with bounded backoff retries",
    )
    assertTrue(
      landingDeclaredThemes.contains("job.card.classList.add(\"cp-reloading\")") &&
        landingDeclaredThemes.contains("job.card.classList.remove(\"cp-reloading\")") &&
        landingDeclaredThemes.contains("job.card.setAttribute(\"aria-busy\", \"true\")"),
      "themed cards expose a busy treatment until each replacement thumbnail settles",
    )
    // On a later tab, its visible cards must lead the serial daemon queue. Hidden tabs' cards then
    // wait on the viewport rather than being appended, so they render only once their tab is
    // opened.
    assertTrue(
      landingDeclaredTabbedThemes.contains("if (themeVisible) {") &&
        landingDeclaredTabbedThemes.contains("themeQueue.push(job)") &&
        landingDeclaredTabbedThemes.contains("themeDeferredQueue.push(job)") &&
        landingDeclaredTabbedThemes.contains(
          "themeVisible = current === \"all\" || " +
            "themeSection.getAttribute(\"data-section\") === current"
        ) &&
        landingDeclaredTabbedThemes.contains("deferTheme(themeDeferredQueue, themeQueueGen)"),
      "current-tab cards are rendered first, including before hidden state is initialized",
    )
    // Re-pointing runs only when the theme itself changed, so a search keystroke (which also calls
    // apply()) never restarts an in-flight themed-render queue.
    assertTrue(
      landingDeclaredThemes.contains("if (theme === appliedTheme) return;"),
      "the card re-point runs only on an actual theme change",
    )
    // A catalog with no declared themes carries none of the theme-render machinery at all.
    assertFalse(
      landingThemed.contains("themeBase") || landingThemed.contains("runThemeQueue"),
      "a baked-only catalog emits no themed-render script",
    )
    // A catalog with no declared themes keeps exactly the baked Light/Dark axis — no dead chips, no
    // themeProvider plumbing offered where nothing could apply it.
    assertFalse(
      landingThemed.contains("data-theme-choice=\"theme:"),
      "a catalog declaring no themes shows only the baked light/dark chips",
    )
    // …and declared themes are withheld from a session that cannot re-render them (a static bundle
    // replays baked PNGs, which would ignore the theme).
    assertFalse(
      ServeWeb.landingPage(
          "compose-m3",
          themedPreviews,
          token,
          declaredThemes = listOf(ServeTheme("Brand Light", "com.example.BrandLightThemeCatalog")),
        )
        .contains("data-theme-choice=\"theme:"),
      "a static bundle offers no declared-theme chips it could not render",
    )
    // A theme-neutral module with declared themes still gets the control: a leading "Default" chip
    // plus the declared themes.
    val neutralWithThemes =
      ServeWeb.landingPage(
        moduleLabel,
        previews,
        token,
        declaredThemes =
          listOf(ServeTheme("Brand Light", "com.example.BrandLightThemeCatalog", group = "Brand")),
        canRenderThemeFor = { true },
      )
    assertTrue(
      neutralWithThemes.contains("data-theme-choice=\"default\"") &&
        neutralWithThemes.contains(
          "data-theme-choice=\"theme:com.example.BrandLightThemeCatalog\""
        ),
      "a theme-neutral module with declared themes gets a Default chip plus the declared themes",
    )
    // Header health badge, JSON link, and catalog / daemon / failure tables. A recent failure gives
    // the amber "degraded" badge; live+running reads "live · running"; baked shows its reason.
    assertTrue(
      serveStatus.contains("Server status") && serveStatus.contains("href=\"/status.json\""),
      "status page headers the status and links its JSON form",
    )
    assertTrue(
      serveStatus.contains("⚠ degraded") &&
        serveStatus.contains("daemon launch timed out after 300s"),
      "a recent failure surfaces the degraded badge and the failure row",
    )
    assertTrue(
      serveStatus.contains("live · running") && serveStatus.contains("baked PNG"),
      "the catalog table distinguishes a running live catalog from a baked one",
    )
    assertTrue(
      serveStatus.contains(
        "href=\"https://github.com/yschimke/compose-ai-tools/tree/design-artifacts/compose-m3\""
      ) &&
        serveStatus.contains("2 hours ago") &&
        serveStatus.contains("2 days ago") &&
        serveStatus.contains("compose-ai-tools <code>0.16.54</code>") &&
        serveStatus.contains("design-parity <code>0.1.25</code>"),
      "catalog status links its delivery branch and shows friendly build provenance",
    )
    // The consent page's whole job is the code and the honesty around it.
    assertTrue(
      agentAccess.contains("KX7M-9QD4") && agentAccess.contains("Verification code"),
      "the approval page shows the code the agent printed, labelled",
    )
    assertTrue(
      agentAccess.contains("fix wear-m3-catalog#68 &lt;the focus ring&gt;"),
      "the agent's label is escaped — it is attacker-controlled text on a page a human trusts",
    )
    assertTrue(
      agentAccess.contains("Not offered: playground"),
      "a scope the approver may not pass on is named rather than silently dropped",
    )
    assertTrue(
      agentAccessCapabilities.contains("--agent-grant-capabilities does not include it"),
      "a capability the box's ceiling excludes is named with its remedy, not silently dropped",
    )
    assertTrue(
      agentAccess.contains("method=\"post\"") &&
        agentAccess.contains("name=\"csrf\" value=\"fixed-approve-seal\""),
      "approval is a sealed POST, never a link a prefetcher could follow",
    )
    assertFalse(
      agentAccess.contains("cpat_"),
      "no token is ever rendered on the consent page",
    )
    // The variant landing folds the component's props-axis renders out: eight renders yield ONE
    // (default) swap card, and no RTL / locale / fontscale variant is emitted as its own card.
    assertEquals(
      1,
      Regex("class=\"cp-card\"").findAll(landingVariants).count(),
      "the component folds to a single default card despite its props variants",
    )
    assertFalse(
      landingVariants.contains("direction-rtl") ||
        landingVariants.contains("locale-ar-xb") ||
        landingVariants.contains("fontscale-2.0"),
      "props variants are folded out of the variant landing grid",
    )
    // The default-render viewer renders the component subtree, marking Default active and linking
    // the same-theme RTL sibling, never the dark render.
    val variantNav =
      viewerVariants.substringAfter("class=\"cp-tree cp-axes-tree\"").substringBefore("</nav>")
    assertTrue(
      variantNav.contains("cp-tree-component cp-tree-link\" role=\"treeitem\"") &&
        variantNav.contains("aria-current=\"page\"><span class=\"cp-tree-label\">Button") &&
        variantNav.contains("/p/button-filled__ideal__default__light__direction-rtl") &&
        variantNav.contains(">RTL</a>"),
      "the viewer subtree marks Default active and links the same-theme RTL variant",
    )
    assertFalse(
      variantNav.contains("__dark__direction-rtl"),
      "the subtree stays within the current theme",
    )
    // A sectioned catalog renders a navigation tree with one row per section in authored order,
    // each with its card count; a flat catalog shows none.
    assertTrue(
      landingSections.contains("class=\"cp-tree\"") && landingSections.contains("role=\"tree\""),
      "a sectioned catalog renders the navigation tree",
    )
    // Keyed on the row's own id, not `data-tab` (group rows carry it too).
    val tabOrder =
      Regex("id=\"cp-tab-([a-z0-9-]+)\"")
        .findAll(landingSections)
        .map { it.groupValues[1] }
        .toList()
    assertEquals(
      listOf("all", "themes", "components", "screens"),
      tabOrder,
      "All leads, then the section rows in authored catalogOrder rather than id-sorted",
    )
    // Each section is a labelled region keyed by its slug.
    assertTrue(
      landingSections.contains("id=\"cp-panel-themes\" role=\"region\"") &&
        landingSections.contains("id=\"cp-panel-components\" role=\"region\"") &&
        landingSections.contains("id=\"cp-panel-screens\" role=\"region\""),
      "each section renders a labelled region",
    )
    assertTrue(
      landingSections.contains(
        "id=\"cp-tab-themes\" href=\"#cp-panel-themes\" data-tab=\"themes\"" +
          " aria-controls=\"cp-panel-themes\" aria-selected=\"false\""
      ),
      "a section row's anchor targets its own panel",
    )
    // The catalog lands on All: every panel showing, the filter spanning the whole catalog.
    assertTrue(
      landingSections.contains(
        "<a class=\"cp-tab\" role=\"treeitem\" id=\"cp-tab-all\" href=\"#cp-grid\"" +
          " data-tab=\"all\" aria-controls=\"cp-grid\" aria-selected=\"true\">" +
          "All<span class=\"cp-tab-count\">"
      ),
      "the tree leads with a selected All row controlling the whole grid",
    )
    val sectionTree = landingSections.substringAfter("id=\"cp-tabs\"").substringBefore("</nav>")
    assertEquals(
      1,
      Regex("aria-selected=\"true\"").findAll(sectionTree).count(),
      "All is the only selected row — a section under it is expanded, not selected",
    )
    // Under All every section is expanded: the tree stands beside a grid showing everything, so it
    // has to be the outline of everything rather than of one panel.
    assertTrue(
      Regex("id=\"cp-tab-themes\"[^>]* aria-selected=\"false\" aria-expanded=\"true\"")
        .containsMatchIn(landingSections) &&
        Regex("id=\"cp-tab-components\"[^>]* aria-selected=\"false\" aria-expanded=\"true\"")
          .containsMatchIn(landingSections),
      "All expands every section rather than leaving the tree closed over a full grid",
    )
    // What All does in each owning script: no card is filtered by section, per-section <h2>s come
    // back, and a group jump scrolls without narrowing. The leading conjunct ("is a filter
    // running") is matched loosely because it also covers `uses:` queries (see
    // `ServeWeb.searchingExpr`).
    assertTrue(
      Regex("""var tabOk = .+ \|\| !sec \|\| current === "all"""").containsMatchIn(landingSections),
      "under All a card is in the current tab whatever section holds it",
    )
    assertTrue(
      landingSections.contains("classList.toggle(") &&
        landingSections.contains("\"cp-multi-section\",") &&
        landingSections.contains("showingAll || searching") &&
        assetText("serve.css")
          .contains("html.cp-js.cp-multi-section .cp-section-head { display: block; }"),
      "the section headings come back whenever several sections are on screen at once",
    )
    assertTrue(
      landingSections.contains("function selectOwningTab(row) {") &&
        landingSections.contains("if (current === \"all\") return;"),
      "jumping to a group from All stays in All",
    )
    // Reloading that URL lands on the same page: the fragment is a scroll target, not a section
    // filter, while All is selected.
    assertEquals(
      2,
      Regex("if \\(current === \"all\"\\) return;").findAll(landingSections).count(),
      "a #cp-group-… fragment scrolls within All rather than undoing it on load",
    )
    assertTrue(
      landingSections.contains("if (popped.row) markGroup(popped.row);"),
      "Back/Forward within All marks the row it lands on instead of switching section",
    )
    // Each named group is a row under its section pointing at the divider's anchor; "Device" in two
    // sections gives two distinct anchors.
    assertTrue(
      landingSections.contains("data-group=\"cp-group-themes-foundation\"") &&
        landingSections.contains("data-group=\"cp-group-components-contacts\"") &&
        landingSections.contains("data-group=\"cp-group-screens-scanner\""),
      "each named group is a tree row pointing at its sub-group anchor",
    )
    assertTrue(
      landingSections.contains("<div class=\"cp-subgroup\" id=\"cp-group-components-device\"") &&
        landingSections.contains("<div class=\"cp-subgroup\" id=\"cp-group-screens-device\""),
      "a group name reused across sections gets one anchor per section, not a shared one",
    )
    // The `group` still renders as a sub-heading inside its section — the tree navigates to those
    // headings, it does not replace them.
    assertTrue(
      landingSections.contains("<h3 class=\"cp-group-head\">Foundation</h3>") &&
        landingSections.contains("<h3 class=\"cp-group-head\">Contacts</h3>") &&
        landingSections.contains("<h3 class=\"cp-group-head\">Scanner</h3>"),
      "component groups render as sub-headings within their section",
    )
    assertEquals(
      2,
      Regex("<h3 class=\"cp-group-head\">Device</h3>").findAll(landingSections).count(),
      "a group name reused across sections stays scoped per section (one sub-heading each)",
    )
    // The tree JS is wired (adds cp-js, drives the sections, wires the group rows and the
    // scroll-spy); a flat catalog's script omits all of it.
    assertTrue(
      landingSections.contains("classList.add(\"cp-js\")") &&
        landingSections.contains("querySelectorAll(\".cp-tab\")") &&
        landingSections.contains("querySelectorAll(\".cp-tree-group\")") &&
        landingSections.contains("new IntersectionObserver"),
      "the sectioned landing wires the tree script",
    )
    assertTrue(
      landingSections.contains("localStorage.getItem(\"cp-tab:meshcore-mobile\")") &&
        landingSections.contains("localStorage.setItem(\"cp-tab:meshcore-mobile\", current)"),
      "the selected section persists per catalog and is restored when returning from a preview",
    )
    // Three things the tree has to get right around the sticky toolbar and shared links, each of
    // which fails silently — the surface still looks correct while being unusable.
    assertTrue(
      landingSections.contains("setProperty(\"--cp-sticky-tools\"") &&
        assetText("serve.css").contains("scroll-margin-top: calc(var(--cp-sticky-tools, 64px)"),
      "the toolbar's measured height offsets the sticky tree and every scroll target",
    )
    assertTrue(
      landingSections.contains("if (!stop && firstShown) firstShown.tabIndex = 0;"),
      "a filter that hides the selected section moves the tree's tab stop to a visible branch",
    )
    assertTrue(
      landingSections.contains("function hashTarget()") &&
        landingSections.contains("decodeURIComponent(id)") &&
        landingSections.contains("initialTab = current;"),
      "a shared #cp-group-… link selects the section that holds it, non-ASCII slugs included",
    )
    // Back must resolve an entry the way loading it fresh would, so the same resolver runs on pop —
    // and it has to be registered AFTER the shared `?tab=` restore to get the last word.
    assertTrue(
      landingSections.contains("var popped = hashTarget();") &&
        landingSections.indexOf("var popped = hashTarget();") >
          landingSections.indexOf("var poppedTab = urlParam(\"tab\")"),
      "Back/Forward re-applies the fragment's precedence over ?tab=",
    )
    // No roving `tabindex` in the served markup: with no JS the arrow keys never bind, so baking
    // `-1` into every row but the first would strand the rest of the navigation for a keyboard.
    assertFalse(
      Regex("<a class=\"cp-tab\"[^>]*tabindex").containsMatchIn(landingSections) ||
        Regex("<a class=\"cp-tree-group\"[^>]*tabindex").containsMatchIn(landingSections),
      "the tree's tab stops are applied by script, not baked into the markup",
    )
    assertTrue(
      landingSections.contains("panel.scrollIntoView({ block: \"start\" })"),
      "selecting a section scrolls to its panel when the toolbar would hide it",
    )
    assertTrue(
      landingSections.contains("if (expanded !== \"true\") return;"),
      "Right does nothing on a tree leaf rather than acting as a second Down",
    )
    // The fragment travels with the selection: `cpUrlState` preserves the hash, which outranks
    // `?tab=` on load.
    assertTrue(
      landingSections.contains("function setFragment(id)") &&
        landingSections.contains("setFragment(id);") &&
        landingSections.contains("setFragment(\"\");"),
      "navigating replaces the fragment, and choosing a section clears it",
    )
    // A `role="group"` has to hang off the treeitem whose `aria-expanded` governs it. The row is an
    // <a> (so it stays a real link) inside a `role="none"` <li>, so the tie is `aria-owns`.
    assertTrue(
      landingSections.contains("aria-owns=\"cp-tree-children-components\"") &&
        landingSections.contains(
          "<ul class=\"cp-tree-children\" id=\"cp-tree-children-components\""
        ),
      "a section row owns its group of sub-group rows",
    )
    // `role="tree"` and `classList.add("cp-js")` appear only when sections render (the shared CSS
    // rules are on every page).
    assertFalse(
      landingThemed.contains("role=\"tree\"") || landingThemed.contains("classList.add(\"cp-js\")"),
      "a flat (section-less) catalog renders no navigation tree and no section script",
    )
    // The state landing folds each component's non-default states out: checkbox + radio yield ONE
    // card each (two total), and no `unchecked`/`unselected` card is emitted.
    assertEquals(
      2,
      Regex("class=\"cp-card\"").findAll(landingStates).count(),
      "each component folds to a single default card",
    )
    assertFalse(
      landingStates.contains("unchecked") || landingStates.contains("unselected"),
      "non-default states are folded out of the state landing grid",
    )
    // The default-state viewer renders the component subtree, marking Default active and linking
    // the same-theme unchecked sibling.
    val statesNav =
      viewerStates.substringAfter("class=\"cp-tree cp-axes-tree\"").substringBefore("</nav>")
    assertTrue(
      statesNav.contains("aria-current=\"page\"><span class=\"cp-tree-label\">Checkbox") &&
        statesNav.contains("/p/checkbox__ideal__unchecked__light"),
      "the viewer subtree marks Default active and links the same-theme sibling",
    )
    // A section-less catalog gains synthesized family dividers over a flat grid, no tab bar.
    assertTrue(
      landingGrouped.contains("class=\"cp-grid-groups\"") &&
        landingGrouped.contains("<h2 class=\"cp-group-head\">Button</h2>") &&
        landingGrouped.contains("<h2 class=\"cp-group-head\">Card</h2>") &&
        landingGrouped.contains("<h2 class=\"cp-group-head\">FAB</h2>"),
      "a section-less catalog renders synthesized family sub-group dividers",
    )
    // A section-less catalog gets an outline tree with synthesized families at the top level; rows
    // carry no `data-tab`.
    assertTrue(
      landingGrouped.contains("role=\"tree\"") &&
        landingGrouped.contains("aria-label=\"Catalog contents\"") &&
        landingGrouped.contains("<div class=\"cp-subgroup\" id=\"cp-group-button\""),
      "a section-less catalog renders an outline tree over its synthesized families",
    )
    // Each sub-group carries its card count as `--cp-n` so CSS can size it as a cluster (CSS can't
    // count children).
    assertTrue(
      landingGrouped.contains("id=\"cp-group-button\" style=\"--cp-n:3\"") &&
        landingGrouped.contains("id=\"cp-group-card\" style=\"--cp-n:2\"") &&
        landingGrouped.contains("id=\"cp-group-fab\" style=\"--cp-n:1\"") &&
        landingGrouped.contains("id=\"cp-group-badge\" style=\"--cp-n:1\""),
      "each sub-group declares how many cards wide it is",
    )
    // The id line is two spans so it elides from the middle (split at the last `__`), keeping the
    // distinguishing suffix.
    assertTrue(
      landingGrouped.contains(
        "<div class=\"cp-id cp-id-elide\">" +
          "<span class=\"cp-id-head\">button-filled__ideal__default</span>" +
          "<span class=\"cp-id-tail\">__light</span></div>"
      ),
      "a card's id elides from the middle, keeping the mode and the scheme",
    )
    // The design file's pages are their own pane beside Components, selected by a segmented switch.
    assertTrue(
      landingGrouped.contains("<div class=\"cp-panes\" role=\"tablist\"") &&
        landingGrouped.contains("data-pane=\"components\" aria-controls=\"cp-pane-components\"") &&
        landingGrouped.contains("data-pane=\"pages\" aria-controls=\"cp-pane-pages\""),
      "a catalog with design pages switches its sidebar between Components and Pages",
    )
    // Each page is one row with `data-search` so the shared filter narrows it. A page with sections
    // is a branch; each section lands on that node's anchor.
    assertTrue(
      landingGrouped.contains(
        "<a class=\"cp-tree-page cp-tree-link\" href=\"/pages/shape\" data-search=\"Shape\"" +
          " aria-expanded=\"true\" aria-controls=\"cp-page-sections-shape\">Shape"
      ) &&
        landingGrouped.contains(
          "<a class=\"cp-tree-variant cp-tree-link\" href=\"/pages/shape\"" +
            " data-search=\"Corner radius\">Corner radius</a>"
        ) &&
        landingGrouped.contains("data-search=\"Shape scale\">Shape scale</a>"),
      "a page's major sections hang under it, each linking to that node's anchor",
    )
    // …and a page with NO sections stays a leaf: named, filterable, and carrying neither a twisty
    // nor an empty child list. Both shapes in one golden, so neither can regress unnoticed.
    assertTrue(
      landingGrouped.contains(
        "<a class=\"cp-tree-page cp-tree-link\" href=\"/pages/type\"" +
          " data-search=\"Typography\">Typography</a>"
      ) && landingGrouped.contains("<a class=\"cp-pane-all\" href=\"/pages\">All pages</a>"),
      "a page with no sections stays a plain filterable row, and the index link survives",
    )
    // Every fragment a section row links to must exist as an id on the page view it points at (two
    // goldens built by different functions).
    val sectionFragments =
      Regex("""href="[^"]*#(cp-node-[^"]*)"""").findAll(landingGrouped).map { it.groupValues[1] }
    val pageAnchors =
      Regex("""id="(cp-node-[^"]*)"""").findAll(designPageHtml).map { it.groupValues[1] }
    val pageAnchorSet = pageAnchors.toSet()
    val dangling = sectionFragments.filterNot { it in pageAnchorSet }.toList()
    assertTrue(
      pageAnchorSet.isNotEmpty() && dangling.isEmpty(),
      "every section link lands on an anchor the page view actually emits; dangling: $dangling",
    )
    // Holds trivially today (sections carry no fragment); this proves nothing dangles, not that
    // targeting works.
    assertTrue(
      sectionFragments.none(),
      "a section link carries no fragment until the page view anchors containers",
    )
    // The pane the switch reveals ships hidden, and the tree keeps the column when it is showing.
    assertTrue(
      landingGrouped.contains(
        "<div class=\"cp-pane cp-pane-pages\" id=\"cp-pane-pages\" role=\"tabpanel\""
      ) && landingGrouped.contains("aria-labelledby=\"cp-pane-tab-pages\" hidden>"),
      "the Pages pane starts hidden behind its tab",
    )
    // …and the branch it replaced is gone, so nothing lists the pages twice.
    assertFalse(
      landingGrouped.contains("cp-tree-pages-row"),
      "the pages branch does not survive alongside the pane that replaced it",
    )
    assertTrue(
      !landingGrouped.contains("class=\"cp-action-chip\" href=\"/pages\""),
      "a catalog with a tree offers its pages there, not as a header chip as well",
    )
    // The fallback: with no tree to list them in, the chip is the only route, so it stays. Label
    // matches the chip's own emission (`actionChip("$basePath/pages$q", "N design page(s)")`).
    assertTrue(
      !landingPublic.contains("cp-tree-pages") &&
        landingPublic.contains("class=\"cp-action-chip\" href=\"/pages\">2 design pages</a>"),
      "a catalog with no tree keeps the header chip, or its pages would be unreachable",
    )
    // `reflectTree` walks every expandable row on open/close; without this guard the Pages branch
    // (no target) would collapse on the first component click.
    assertTrue(
      landingGrouped.contains("if (!id) return;"),
      "the tree script leaves an always-open branch alone",
    )
    // A component row jumps to its card (`data-group`); a variant row has no card in the grid, so
    // it is a plain link to the viewer with no `data-group`.
    assertTrue(
      landingTreeDepth.contains(
        "<a class=\"cp-tree-component cp-tree-link\" role=\"treeitem\"" +
          " href=\"#cp-card-button-filled__ideal__default__light\""
      ),
      "each component is a row pointing at its own grid card",
    )
    val variantRows =
      Regex("<a class=\"cp-tree-variant cp-tree-link\"[^>]*>([^<]+)</a>")
        .findAll(landingTreeDepth)
        .map { it.groupValues[1] }
        .toList()
    assertTrue(
      variantRows.containsAll(listOf("Default", "RTL", "Locale ar-XB", "Font 2.0×", "Unchecked")),
      "primary-axis variants (props and state) each become a row: $variantRows",
    )
    assertTrue(
      landingTreeDepth.contains(
        "href=\"/p/button-filled__ideal__default__light__direction-rtl?session=compose-m3\""
      ),
      "a variant row links to the viewer rather than jumping within the page",
    )
    // Theme is SECONDARY — the card swaps it in place — so it never becomes a row, and neither the
    // dark twin of a component nor a `__dark` variant appears in the tree.
    assertFalse(
      Regex("<a class=\"cp-tree-(component|variant)[^>]*(Dark|__dark)")
        .containsMatchIn(landingTreeDepth),
      "theme stays a secondary axis and earns no tree row",
    )
    // A filter that hides a card must hide the row pointing at it, at EVERY level and in both tree
    // modes — otherwise a search leaves rows that scroll to nothing.
    assertTrue(
      landingTreeDepth.contains("treeComponents.forEach(function (c) {") &&
        landingGrouped.contains("treeComponents.forEach(function (c) {") &&
        landingGrouped.contains("treeGroups.forEach(function (g) {"),
      "the search filter follows the tree down to its component rows, outline trees included",
    )
    // A `role="tree"` is one tab stop. Nothing established that until the first arrow press, so
    // every visible row sat in the tab order until then.
    assertTrue(
      landingTreeDepth.contains("function syncTabStops()") &&
        landingTreeDepth.contains("cpTreeStops = syncTabStops;") &&
        landingTreeDepth.contains("if (cpTreeStops) cpTreeStops();"),
      "the tree keeps a single roving tab stop, re-synced whenever the filter moves rows",
    )
    // A `#cp-card-…` fragment can name a component in any group, not just the one the server
    // expanded — its own row has to open along with it.
    assertTrue(
      landingTreeDepth.contains("var owner = parentRow(row);"),
      "landing on a component's fragment opens the group that holds it",
    )
    // Cards are jump targets now, so they need the clearance sections and sub-groups already have.
    assertTrue(
      assetText("serve.css").contains(".cp-card { scroll-margin-top:"),
      "a card cleared the sticky toolbar when a component row jumps to it",
    )
    assertFalse(
      landingGrouped.contains("data-tab=") ||
        landingGrouped.contains("localStorage.getItem(\"cp-tab:"),
      "an outline tree switches no panels, so it emits no section machinery",
    )
    // The component nav collapses to ONE entry per component: button-filled's ~8 baked variants +
    // checkbox/radiobutton states yield exactly three nav items, button-filled listed once.
    val collapsedNav =
      viewerNavCollapsed.substringAfter("<aside class=\"cp-nav\"").substringBefore("</aside>")
    assertEquals(
      3,
      Regex("class=\"cp-nav-item\"|class=\"cp-tree-component cp-tree-link\"")
        .findAll(collapsedNav)
        .count(),
      "the component nav lists one entry per component, not per baked variant",
    )
    assertEquals(
      1,
      Regex(">Button · Filled[^<]*<").findAll(collapsedNav).count(),
      "the multi-variant component appears exactly once in the nav",
    )
    // Viewing a dark preview, the nav links other components to their dark render, like the state
    // and variant switchers.
    val darkNav =
      ServeWeb.viewerPage(
          statefulPreviews.first { it.id == "checkbox__ideal__default__dark" },
          token,
          sessionId = "compose-m3",
          siblings = statefulPreviews,
        )
        .substringAfter("id=\"cp-nav-list\"")
        .substringBefore("</ul>")
    assertTrue(
      darkNav.contains("/p/radiobutton__ideal__default__dark") &&
        !darkNav.contains("/p/radiobutton__ideal__default__light"),
      "the collapsed nav preserves the viewer's theme (a dark preview links dark siblings)",
    )
    // The editor page carries its three DOM hooks the run script + the Playwright e2e drive: the
    // source box, the mode selector with all three modes, and the Run button.
    assertTrue(
      playground.contains("id=\"pg-source\"") &&
        playground.contains("id=\"pg-run\"") &&
        playground.contains("value=\"compose-cmp\"") &&
        playground.contains("value=\"compose-android\"") &&
        playground.contains("value=\"remote-compose\""),
      "the playground page exposes the source box, Run button, and all three mode options",
    )
    // The run script targets the versioned compile route and follows either handoff field.
    assertTrue(
      playground.contains("/api/1/compiler/run") &&
        playground.contains("res.documentUrl || res.previewUrl"),
      "the playground script POSTs to the compile route and follows the /pg or /d handoff",
    )
    // A snippet is a list of files: the file strip is present, the run posts the whole list, and
    // the response's previewId says which @Preview was drawn.
    assertTrue(
      playground.contains("id=\"pg-files\"") &&
        playground.contains("id=\"pg-add-file\"") &&
        playground.contains("id=\"pg-remove-file\""),
      "the playground page exposes the multi-file strip",
    )
    assertTrue(
      playground.contains("files: files") && !playground.contains("files: [{ name: \"Snippet.kt\""),
      "the run body posts every open file, not just the active buffer",
    )
    assertTrue(
      playground.contains("res.previewId") &&
        playground.contains("res.previews") &&
        playground.contains("id=\"pg-preview-note\""),
      "the editor names the preview it rendered when a snippet declares several",
    )
    // A diagnostic names its file (and jumps to that tab): with several buffers open, a bare
    // "line 5" says nothing about which file to look at.
    assertTrue(
      playground.contains("d.file") && playground.contains("indexOfFile"),
      "diagnostics name their file and can jump to it",
    )
    assertTrue(
      playground.contains("editor.addLineWidget") &&
        playground.contains("editor.addLineClass") &&
        playground.contains("removeLineClass(entry.lineHandle") &&
        playground.contains("editor.markText") &&
        assetText("playground.css").contains(".cp-pg-inline-error"),
      "located compiler errors are shown inline and cleared through CodeMirror's moving line handle",
    )
    // A compiler message can span many lines (K2 overload failures). The summary keeps every line;
    // the inline widget keeps only the head.
    assertTrue(
      assetText("playground.css").contains("white-space: pre-wrap"),
      "the diagnostic summary renders a multi-line compiler message as multiple lines",
    )
    assertTrue(
      playground.contains("String(d.message).split(\"\\n\")[0]") &&
        playground.contains("message.title = d.message"),
      "the inline widget shows the head line and keeps the full text in its tooltip",
    )
    assertTrue(
      playground
        .substringAfter("removeFile.addEventListener")
        .substringBefore("renderFiles();\n      function setStatus")
        .contains("renderEditorDiags();"),
      "removing the active file repaints diagnostics for the buffer that replaces it",
    )
    // The terminal status stays exactly "Done." — the e2e polls on it, so the preview note lives
    // in its own element rather than being appended to the status text.
    assertTrue(
      playground.contains("setStatus(\"Done.\", false)"),
      "a successful run still ends on the terminal status the e2e keys on",
    )
    // The upload page names every format it accepts and states the expiry up front, so a visitor
    // knows what to drop and how long the resulting link lives before they share it.
    for (format in ServeDocFormats.ALL) {
      assertTrue(
        docUpload.contains(format.label) && docUpload.contains(format.extension),
        "the upload page lists ${format.id}",
      )
    }
    assertTrue(docUpload.contains("expires after 1h"), "the upload page states the link TTL")
    // A permalink page mounts its format's vendored player against the document's own bytes, and
    // never a different format's.
    assertTrue(
      docLottie.contains(ServeDocFormats.LOTTIE.playerPath) &&
        !docLottie.contains(ServeDocFormats.REMOTE_COMPOSE.playerPath),
      "the Lottie page loads only the Lottie player",
    )
    assertTrue(
      docRemoteCompose.contains(ServeDocFormats.REMOTE_COMPOSE.playerPath) &&
        docRemoteCompose.contains("width=\"384\" height=\"384\""),
      "the Remote Compose page loads the RC player onto a canvas sized from the document",
    )
    // The player toggle is offered only where it means something: an `.rc` document, on a host
    // serving the CMP player. Without one, the page is the TypeScript player alone, as before.
    assertTrue(
      docRemoteComposePlayers.contains("data-doc-player=\"camaelon-js\"") &&
        docRemoteComposePlayers.contains("data-doc-player=\"cmp-wasm\"") &&
        docRemoteComposePlayers.contains("id=\"cp-doc-lane-cmp-wasm\"") &&
        docRemoteComposePlayers.contains("\"/rc-player-wasm/index.html\""),
      "an .rc permalink on a CMP-serving host offers both browser players",
    )
    assertTrue(
      docRemoteComposePlayers.contains("data-doc-player=\"cmp-jvm\"") &&
        docRemoteComposePlayers.contains(
          "data-doc-lane-path=\"/d/Tz3l9WcAq0Xj5RmB7dPuKw/render.png\""
        ),
      "a server-side player is offered as a lane rendered from the document's render path",
    )
    // Each lane reports into its own status line, so the TypeScript lane finishing first cannot
    // mark a still-loading CMP frame ready; and a CMP frame that never reports times out.
    assertTrue(
      docRemoteComposePlayers.contains("id=\"cp-doc-status-cmp-wasm\" hidden") &&
        docRemoteComposePlayers.contains("didn't start") &&
        docRemoteComposePlayers.contains("20000"),
      "the CMP lane has its own status line and a start timeout",
    )
    assertFalse(
      docRemoteCompose.contains("data-doc-player") || docRemoteCompose.contains("data-doc-lane"),
      "an .rc permalink on a host without the CMP player offers no toggle",
    )
    assertFalse(
      docLottie.contains("data-doc-player"),
      "a Lottie permalink has one player and no toggle",
    )
    assertTrue(
      docLottie.contains("expires in 1h") && docLottie.contains("2026-07-28T22:15:00Z"),
      "the permalink page shows the time left and the exact expiry instant",
    )
    // The 404 is a full styled document with a heading, the message, and a link back home — not a
    // bare text/plain dead-end.
    assertTrue(
      notFound.contains("<!doctype html>") &&
        notFound.contains("<h1 class=\"cp-head\">Not found</h1>") &&
        notFound.contains("That preview does not exist in this catalog.") &&
        notFound.contains("class=\"cp-back\""),
      "the 404 page is styled chrome with a back-home link",
    )
    // Every page wraps its content in a single <main> landmark and leads with an <h1>.
    for ((name, html) in
      listOf("home" to homeIndex, "landing" to landingPublic, "viewer" to viewer)) {
      assertEquals(
        1,
        Regex("<main class=\"cp-main\">").findAll(html).count(),
        "the $name page has exactly one <main> landmark",
      )
      assertTrue(html.contains("<h1 class=\"cp-head"), "the $name page leads with an <h1>")
    }
    assertTrue(
      File(pagesDir, "_render-placeholder.png").isFile,
      "missing _render-placeholder.png — regenerate with UPDATE_SERVE_WEB_FIXTURES=true",
    )

    // The home index lists every published system as a card linking to its /<system>/ catalog —
    // including remote-m3 — each carrying a hero preview img.
    assertTrue(homeIndex.contains("Design Systems"), "home index is headed 'Design Systems'")
    assertTrue(
      homeIndex.contains("href=\"/compose-m3/\"") &&
        homeIndex.contains("href=\"/wear-m3/\"") &&
        homeIndex.contains("href=\"/remote-m3/\""),
      "home index cards link to each system's canonical /<system>/ path",
    )
    assertTrue(
      homeIndex.contains("Remote Compose Material 3"),
      "remote-m3 appears in the index with its human title",
    )
    // The normal card points at the PREBAKED hero: an immutable, content-hashed URL, loaded eagerly
    // with its box reserved up front — so the front door paints without touching the render lane.
    assertTrue(
      homeIndex.contains(
        "<img loading=\"eager\" decoding=\"async\" width=\"168\" height=\"68\"" +
          " alt=\"Compose Material 3 preview\" src=\"/hero/compose-m3/1f0c9a4b7d2e6503.png\">"
      ),
      "a system card shows its prebaked hero, sized and eager",
    )
    assertFalse(
      homeIndex.contains("src=\"/compose-m3/render/"),
      "a prebaked card puts no render request on the server",
    )
    // A catalog whose hero couldn't be prebaked still shows one — over the live /render lane.
    assertTrue(
      homeIndex.contains("src=\"/remote-m3/render/Button-Filled__ideal__default__light.png\""),
      "a card with no prebaked hero falls back to its /render endpoint",
    )
    assertTrue(
      !homeIndex.contains("cp-badge--trusted") && !homeIndex.contains("⚠ untrusted"),
      "the discovery index omits badges for trusted catalog cards",
    )
    val untrustedHomeIndex =
      ServeWeb.homeIndexPage(
        systems =
          listOf(
            ServeWeb.HomeSystem(
              system = "unverified",
              title = "Unverified catalog",
              subtitle = null,
              previewCount = 1,
              trust = "unverified",
              heroPreviewId = null,
            )
          ),
        token = token,
        isPublic = true,
      )
    assertTrue(
      untrustedHomeIndex.contains("cp-badge--unverified") &&
        untrustedHomeIndex.contains("⚠ untrusted"),
      "the discovery index calls out a genuinely unverified catalog",
    )
    // meshcore-mobile + homeassistant-remotecompose are LISTED (`--catalogs`), so they show on the
    // front door in the "Design systems" grid — served from their own repos.
    assertTrue(
      homeIndex.contains("href=\"/meshcore-mobile/\"") &&
        homeIndex.contains("href=\"/homeassistant-remotecompose/\""),
      "listed app systems appear on the front door with their /<system>/ links",
    )
    assertTrue(
      homeIndex.contains("MeshCore"),
      "a listed app shows its human title on the front door",
    )
    assertTrue(
      homeIndex.contains("<h1 class=\"cp-head\">yschimke repositories</h1>"),
      "catalogs published by yschimke have their own section",
    )
    // A catalog that claims no configured group is sectioned by its source repo's OWNER — nothing
    // in the server knows `confetti-wear`, so Confetti gets a publisher-repository section.
    assertTrue(
      homeIndex.contains("<h1 class=\"cp-head\">joreilly repositories</h1>"),
      "an unconfigured publisher still gets its own section, derived from the source repo",
    )
    assertTrue(
      homeIndex.indexOf("href=\"/remote-m3/\"") <
        homeIndex.indexOf("<h1 class=\"cp-head\">yschimke repositories</h1>") &&
        homeIndex.indexOf("href=\"/homeassistant-remotecompose/\"") <
          homeIndex.indexOf("<h1 class=\"cp-head\">joreilly repositories</h1>") &&
        homeIndex.indexOf("<h1 class=\"cp-head\">joreilly repositories</h1>") <
          homeIndex.indexOf("href=\"/confetti-wear/\""),
      "cards are split between the design system, yschimke, and joreilly sections",
    )
    // An unlisted catalog is served at /<system>/ but not advertised on the front door.
    assertFalse(
      homeIndex.contains("<p class=\"cp-head\">Apps</p>"),
      "the front door has no Apps section — unlisted catalogs are not indexed",
    )
    // Public mode opens every route, so server-rendered links carry NO ?token param.
    assertFalse(homeIndex.contains("token="), "public home index links are token-free")
    assertFalse(landingPublic.contains("token="), "public landing drops the token from its links")
    // A token-gated (non-public) landing keeps the token as the only gate.
    assertTrue(
      landing.contains("?token=$token"),
      "a token-gated landing keeps the token in its links",
    )
    // The public viewer's back-link is token-free, and its request-building JS only sends a token
    // when the page URL itself carried one (so a public page stays token-free end to end).
    val publicViewer =
      ServeWeb.viewerPage(
        previews.first(),
        token,
        sessionId = "compose-m3",
        basePath = "/compose-m3",
        isPublic = true,
      )
    assertFalse(publicViewer.contains("?token="), "public viewer back-link carries no token")
    assertTrue(
      viewerSource().contains("if (token) parts.push(\"token=\""),
      "viewer JS only appends a token when the page URL carried one",
    )
    // The representative pick prefers a default-state light hero over dark / disabled edge cases.
    assertEquals(
      "button-filled__ideal__default__light",
      ServeWeb.representativePreviewId(
        listOf(
          ServePreview("badge__ideal__default__dark", "Badge dark"),
          ServePreview("button-filled__ideal__disabled__light", "Filled disabled"),
          ServePreview("button-filled__ideal__default__light", "Filled default"),
          ServePreview("button-filled__ideal__default__dark", "Filled dark"),
        )
      ),
      "the hero pick prefers a default-state, light, filled-button render",
    )
    // A catalog with screens uses a Screens-section preview as its hero, ahead of any component.
    assertEquals(
      "conference-screen__ideal__default__dark",
      ServeWeb.representativePreviewId(
        listOf(
          ServePreview("button-filled__ideal__default__light", "Filled default"),
          ServePreview(
            "conference-screen__ideal__default__dark",
            "Conference",
            section = "Screens",
          ),
          ServePreview("bookmarks-screen__ideal__default__dark", "Bookmarks", section = "Screens"),
        )
      ),
      "a catalog with screens fronts a screen, not a component",
    )
    // The dark stage is DECLARED by the catalog (display.surface) first; the system-name heuristic
    // is only the fallback, so nothing is hardcoded per app.
    assertTrue(
      ServeWeb.SystemDisplay.resolveDarkFirst("anything", "dark"),
      "a declared dark surface wins regardless of the system name",
    )
    assertFalse(
      ServeWeb.SystemDisplay.resolveDarkFirst("wear-m3", "light"),
      "a declared light surface overrides the wear-name dark-first heuristic",
    )
    assertTrue(
      ServeWeb.SystemDisplay.resolveDarkFirst("confetti-wear", null),
      "fallback: a Wear/watch system id is dark-first when nothing is declared",
    )
    assertFalse(
      ServeWeb.SystemDisplay.resolveDarkFirst("compose-m3", null),
      "fallback: a non-Wear system stays on the light stage",
    )
    assertNull(
      ServeWeb.SystemDisplay.normalizeOverrideParams("confetti-wear", mapOf("uiMode" to "light"))[
          "uiMode"],
      "Wear ignores the generic light override",
    )
    assertEquals(
      "light",
      ServeWeb.SystemDisplay.normalizeOverrideParams("compose-m3", mapOf("uiMode" to "light"))[
          "uiMode"],
      "non-Wear systems retain day/night overrides",
    )
    assertTrue(
      landingThemed.contains("sessionStorage.getItem(\"cp-theme:compose-m3\")") &&
        landingThemed.contains("sessionStorage.setItem(\"cp-theme:compose-m3\", theme)"),
      "the catalog landing persists theme under its own catalog key, for this tab",
    )
    assertTrue(
      viewerGestures.contains("sessionStorage.getItem(\"cp-theme:wear-m3\")") &&
        !viewerGestures.contains("cp-theme:compose-m3"),
      "a viewer reads only its own catalog's sticky theme",
    )
    assertEquals(
      mapOf("fontScale" to "1.5"),
      ServeWeb.SystemDisplay.normalizeOverrideParams(
        "confetti-wear",
        mapOf("uiMode" to "light", "fontScale" to "1.5"),
      ),
      "normalizing drops only uiMode — every other override survives",
    )

    // The theme toggle appears only for a catalog with light/dark pairs, each pair collapsing into
    // one swap card.
    assertTrue(
      landingThemed.contains("id=\"cp-catalog-theme-bar\""),
      "themed catalog shows the theme toggle",
    )
    assertTrue(
      Regex("class=\"cp-card\"[^>]*data-swap=\"1\"").containsMatchIn(landingThemed) &&
        landingThemed.contains("data-l-src=") &&
        landingThemed.contains("data-d-src="),
      "a paired component renders one swap card carrying both themes' baked render",
    )
    // The light+dark pair is a single card, so the dark id no longer appears as its own card.
    assertFalse(
      landingThemed.contains(">button-filled__ideal__default__dark</div>"),
      "the dark variant is folded into the swap card, not a separate card",
    )
    // The swap re-points the image + viewer link + id + label to the chosen theme's baked render.
    assertTrue(
      landingThemed.contains(
        "if (img) { if (withSrc) setCardSrc(img, src); img.setAttribute(\"alt\", lbl); }"
      ) &&
        landingThemed.contains(
          "c.setAttribute(\"href\", c.getAttribute(\"data-\" + k + \"-href\"))"
        ),
      "the toggle swaps the card's render and viewer link in place (not a filter)",
    )
    // Dark-first system (Wear): a preview with no theme token still tags the viewer stage dark
    // (data-bg-theme, separate from data-card-theme), keeping light-on-transparent renders
    // readable; a non-dark-first viewer stays light.
    assertTrue(
      viewerGestures.contains("class=\"cp-viewer\" data-bg-theme=\"dark\""),
      "a Wear (dark-first) viewer tags the stage dark even without a __dark token",
    )
    assertFalse(
      viewerFocus.contains("class=\"cp-viewer\" data-bg-theme="),
      "a non-dark-first viewer with no theme token leaves the stage default (light)",
    )
    // The stage follows the Theme choice only when the control can re-render: on a static bundle
    // the select is disabled (but may carry a remembered value), so syncBg gates on !el.disabled.
    assertTrue(
      viewerGestures.contains("!el.disabled &&"),
      "syncBg only honors the Theme choice when the control is usable (not a disabled static select)",
    )
    assertFalse(
      landing.contains("id=\"cp-catalog-theme-bar\""),
      "a module without theme variants shows no toggle",
    )
    // The search box filters the grid and appears for every non-empty module — including the
    // plain, theme-less one that shows no theme toggle. The grid carries the id the input targets.
    assertTrue(landing.contains("id=\"cp-search\""), "landing carries the search box")
    assertTrue(
      landing.contains("id=\"cp-grid\""),
      "the grid is labelled for the search box to target",
    )
    assertTrue(
      landingThemed.contains("id=\"cp-search\""),
      "the search box shows on a themed catalog",
    )
    assertTrue(
      landingThemed
        .substringAfter("class=\"cp-catalog-menu\"")
        .substringBefore("</aside>")
        .contains("id=\"cp-search\""),
      "a catalog menu leads with its filter",
    )
    assertFalse(
      landingThemed.contains("id=\"cp-count\""),
      "the menu filter does not repeat the preview count",
    )
    // The combined filter composes search with theme: on a themed catalog the script still persists
    // the theme choice, so search didn't displace the theme half.
    assertTrue(
      landingThemed.contains("sessionStorage.setItem(\"cp-theme:compose-m3\"") &&
        landingThemed.contains("getElementById(\"cp-search\")"),
      "the themed landing's filter script drives both the theme toggle and the search box",
    )
    // The Theme select is seeded from, and written back to, a catalog-scoped `sessionStorage` key,
    // so memory is per tab and a link opened in a fresh tab is reproducible.
    assertTrue(
      viewer.contains("sessionStorage.getItem(\"cp-theme:default\""),
      "viewer seeds its Theme select from the catalog-scoped theme key on load",
    )
    assertTrue(
      viewer.contains("sessionStorage.setItem(\"cp-theme:default\""),
      "viewer Theme change writes the catalog-scoped theme key",
    )
    assertFalse(
      viewer.contains("localStorage.getItem(\"cp-theme:default\"") ||
        viewer.contains("localStorage.setItem(\"cp-theme:default\""),
      "the theme choice never reaches a store shared with the reader's other tabs",
    )
    assertTrue(
      viewerThemes.contains("stored.indexOf(\"theme:\") === 0") &&
        viewerThemes.contains("!urlOption && !el.disabled && option") &&
        viewerThemes.contains("el.setAttribute(\"data-theme-active\", \"1\")"),
      "a remembered theme is restored uniformly, baked light/dark id or not",
    )
    assertFalse(
      viewerThemes.contains("!themed"),
      "no id-shaped exemption: it made two variants of one component open in different themes",
    )
    assertTrue(
      viewerThemes.contains("!urlOption && !el.disabled && option && !option.disabled"),
      "a disabled select is a page that cannot re-render, whatever its options say: a remembered " +
        "theme applied there marks a chip the stage will not honour",
    )
    // The exclusivity rule lives in `serve-web/src/viewer/themeChoice.ts` (tested by
    // `viewerThemeChoice.test.ts`); this pins that `viewer.js` uses the shared rules rather than a
    // second copy.
    assertTrue(
      viewerSource().contains("rules.chosenUiMode(") &&
        viewerSource().contains("rules.chosenThemeProvider("),
      "the unified Theme value is mapped by the shared rules, not restated in the viewer",
    )

    // The backend-provenance badge names the active tier. The Wasm tier is always CMP-WASM; the
    // live + snapshot labels come from server metadata (a live daemon can be Android, not just
    // JVM),
    // defaulting to generic Live / Snapshot.
    // The badge is `<cp-backend-badge>` (`cli/serve-web/src/components/BackendBadge.ts`), so what
    // the page owes it is the tag carrying the live region and the lane labels. Which icon and
    // label each mode produces — ▶ live / ▪ static, and the hard-coded CMP-WASM tier — is asserted
    // against the real element in `cli/serve-web/test/backendBadge.test.ts`, which a substring
    // match on a minified bundle could not do.
    assertTrue(
      viewer.contains("<cp-backend-badge ") && viewer.contains("id=\"cp-backend\""),
      "viewer stage carries the backend badge",
    )
    assertTrue(
      viewer.contains("role=\"status\"") && viewer.contains("aria-live=\"polite\""),
      "the badge is a server-rendered live region, so lane changes are announced",
    )
    assertTrue(
      viewer.contains("data-live-backend=\"Live\"") &&
        viewer.contains("data-snapshot-backend=\"Snapshot\""),
      "live + snapshot labels default to generic, server-settable values",
    )
    // Both labels are server-settable (design catalogs render Android; a desktop daemon streams
    // JVM).
    val labelled =
      ServeWeb.viewerPage(
        previews.first { it.id.endsWith("ButtonPreview") },
        token,
        snapshotBackend = "Android",
        liveBackend = "CMP-JVM",
      )
    assertTrue(
      labelled.contains("data-snapshot-backend=\"Android\"") &&
        labelled.contains("data-live-backend=\"CMP-JVM\""),
      "snapshotBackend + liveBackend flow to the badge",
    )
  }

  @Test
  fun `a published capture is offered as a chip and never plays until it is asked for`() {
    val plain = previews.first { it.id.endsWith("CardPreview") }
    val withMotion =
      plain.copy(
        motion =
          listOf(
            ServeMotion(id = "card__filled__interaction", kind = "interaction", caption = "Press"),
            ServeMotion(
              id = "card__filled__anim",
              kind = "animation",
              caption = "Elevation settle",
              extension = ".gif",
            ),
          )
      )
    // Path-mounted, which is how a published catalog is actually served — so the assertions below
    // cover the session scoping of the route as well as its shape.
    val view =
      ServeWeb.viewerPage(withMotion, token, sessionId = "compose-m3", basePath = "/compose-m3")

    // The whole point of the axis: a still is what most readers came for, so motion is a control
    // beside it rather than the frame on the stage.
    assertTrue(view.contains("id=\"cp-motion-chip\""), "a preview with captures offers the chip")
    assertTrue(
      Regex("id=\"cp-motion-chip\"[^>]*aria-pressed=\"false\"").containsMatchIn(view),
      "the chip opens un-pressed — the page loads on the still",
    )
    assertTrue(
      Regex("<img id=\"cp-motion-img\"[^>]*hidden").containsMatchIn(view) &&
        !Regex("<img id=\"cp-motion-img\"[^>]*src=").containsMatchIn(view),
      "the capture is neither shown nor SRC-ed at page load — assigning src is what starts playback",
    )

    // The player: a canvas the decoded frames are painted on, and the transport that drives them.
    // Both start hidden — the bytes are still fetched on first entry and never at page load.
    assertTrue(
      Regex("<div class=\"cp-motion-player\" id=\"cp-motion-player\" hidden>")
        .containsMatchIn(view),
      "the player is rendered but withheld until the lane is entered",
    )
    assertTrue(view.contains("id=\"cp-motion-canvas\""), "the frames have a canvas to land on")
    // The transport is hidden separately from the player and revealed only after a successful
    // decode, so the `<img>` fallback never shows unusable controls.
    assertTrue(
      Regex("<div class=\"cp-motion-transport\" id=\"cp-motion-transport\" hidden>")
        .containsMatchIn(view),
      "the transport waits for a decode rather than promising control the page may not have",
    )
    assertTrue(
      view.contains("id=\"cp-motion-play\"") && view.contains("id=\"cp-motion-replay\""),
      "play/pause and play-again are both offered — a capture plays once, so replay is the way back",
    )
    assertTrue(
      Regex("<input type=\"range\" id=\"cp-motion-scrub\"[^>]*step=\"1\"").containsMatchIn(view),
      "the timeline is a real range input, so frame stepping and Home/End come from the platform",
    )
    assertTrue(
      view.contains("id=\"cp-motion-rate\"") && view.contains(">0.25×</option>"),
      "playback speed goes down to a quarter, which is where a 300ms spring becomes readable",
    )
    assertTrue(
      Regex("<option value=\"1\" selected>1×</option>").containsMatchIn(view),
      "…and opens at the rate the capture was recorded at",
    )

    // Each capture keeps its own extension end to end. An APNG served as a GIF renders one frame
    // and stops, so a picker that assumed one format would silently turn a recording into a still.
    assertTrue(
      view.contains("data-motion-src=\"/compose-m3/motion/card__filled__interaction.apng"),
      "the interaction capture is addressed as an APNG",
    )
    assertTrue(
      view.contains("data-motion-src=\"/compose-m3/motion/card__filled__anim.gif"),
      "the GIF capture keeps its own type rather than inheriting the first one's",
    )
    // The annotation's caption is what names the recording — without it a capture tells the reader
    // only that *something* moved.
    assertTrue(view.contains(">Press</option>"), "the declared caption labels its capture")
    assertTrue(view.contains(">Elevation settle</option>"), "…and so does the second one")
    // A caption short enough to BE a title carries no detail attribute: printing it in the menu and
    // again in the readout beside it is two controls stating one fact.
    assertFalse(
      view.contains("data-motion-detail=\"Press\""),
      "a caption the menu already shows in full is not repeated in the readout",
    )

    // A lane like every other: its own hidden mode radio, which is what buys `?mode=motion`,
    // restore-on-load and Back/Forward without a second mechanism.
    assertTrue(
      view.contains("name=\"cp-mode\" value=\"motion\" id=\"cp-motion-toggle\""),
      "motion joins the mode radio group rather than inventing its own state",
    )

    // …and a preview with nothing published is presented exactly as it was before.
    val without =
      ServeWeb.viewerPage(plain, token, sessionId = "compose-m3", basePath = "/compose-m3")
    assertFalse(without.contains("cp-motion-chip"), "no captures ⇒ no chip")
    assertFalse(without.contains("cp-motion-img"), "no captures ⇒ no stage image")
    assertFalse(without.contains("value=\"motion\""), "no captures ⇒ no mode radio")

    // Leaving the lane must DROP the src, not merely hide the image: a hidden capture with its src
    // still assigned keeps looping for the rest of the visit — invisible, and still decoding.
    assertTrue(
      viewerSource().contains("motionImg.removeAttribute(\"src\");"),
      "closing the lane stops the animation instead of hiding a still-playing one",
    )
    // Every other lane closes it, which is what stops a capture playing on behind the lane the
    // visitor actually moved to.
    assertTrue(
      viewerSource().contains("if (m !== \"motion\") closeMotion();"),
      "every transition out of motion closes the lane",
    )
    // Realistic captions: an instruction line then a paragraph. The menu shows the opening clause;
    // the rest goes to the readout.
    val prose =
      ServeWeb.viewerPage(
        plain.copy(
          motion =
            listOf(
              ServeMotion(
                id = "card__filled__interaction",
                kind = "interaction",
                caption =
                  "Toggle repeatedly. The container morphs between its unchecked and checked " +
                    "shapes through the theme's spatial animation.",
              )
            )
        ),
        token,
        sessionId = "compose-m3",
      )
    assertTrue(
      prose.contains(">Toggle repeatedly</option>"),
      "the menu shows the caption's opening clause, not the paragraph behind it",
    )
    assertTrue(
      prose.contains(
        "data-motion-detail=\"Toggle repeatedly. The container morphs between its unchecked " +
          "and checked shapes through the theme&#39;s spatial animation.\""
      ),
      "…and the words themselves are kept, on the option the readout prints from",
    )

    // Two caption-less captures of the same kind (allowed by the manifest) must still be
    // distinguishable.
    val sameKind =
      ServeWeb.viewerPage(
        plain.copy(
          motion =
            listOf(
              ServeMotion(id = "card__a", kind = "interaction"),
              ServeMotion(id = "card__b", kind = "interaction"),
            )
        ),
        token,
        sessionId = "compose-m3",
      )
    assertTrue(
      sameKind.contains(">Interaction 1</option>") && sameKind.contains(">Interaction 2</option>"),
      "a repeated fallback label is numbered so the two entries can be told apart",
    )
    // …and a lone capture is NOT numbered: "Interaction 1" on a preview with one recording is a
    // count of something nobody was choosing between.
    assertTrue(
      view.contains(">Press</option>") && !view.contains(">Press 1</option>"),
      "a label that stands alone keeps its plain form",
    )

    // A pinned page never serves current bytes, and `/motion/` reads the branch tip with no
    // revision to resolve against, so the whole axis (chip, stage image, mode radio) is withdrawn.
    val pinnedView =
      ServeWeb.viewerPage(
        withMotion,
        token,
        sessionId = "compose-m3",
        basePath = "/compose-m3",
        revisions = ServeWeb.CatalogRevisions(pinned = "df4aa9c00fcc8b1747e159b71d3fbc75cdc27b80"),
      )
    assertFalse(pinnedView.contains("cp-motion-chip"), "a pinned page offers no capture")
    assertFalse(pinnedView.contains("cp-motion-img"), "…and stages none")
    assertFalse(
      pinnedView.contains("value=\"motion\""),
      "…and carries no mode radio a URL could still name",
    )

    // The readout prints the full caption for the capture on stage, and stands in for the hidden
    // menu on a single-capture preview.
    assertTrue(
      viewerSourceFlat()
        .contains("detail || (motionOptions.length > 1 || !option ? \"\" : option.text)"),
      "the readout shows the detail, or the title when no visible menu carries it",
    )
  }

  @Test
  fun `viewer mounts the Wasm tier only when a wasm app backs the session`() {
    val card = previews.first { it.id.endsWith("CardPreview") }
    val withWasm = ServeWeb.viewerPage(card, token, wasmSrc = "/wasm/compose-m3/?id=card-filled")
    // The visible mode control is a single Static/Live toggle; hidden transport radios (png / live
    // / wasm) back it. The wasm radio exists only when a wasm app backs the session.
    assertTrue(
      withWasm.contains("id=\"cp-live-toggle\""),
      "expected the single Static⇄Live preview toggle",
    )
    assertTrue(
      withWasm.contains("name=\"cp-mode\" value=\"png\"") &&
        withWasm.contains("name=\"cp-mode\" value=\"live\"") &&
        withWasm.contains("name=\"cp-mode\" value=\"wasm\""),
      "expected the hidden png / live / wasm transport radios",
    )
    assertTrue(withWasm.contains("id=\"cp-wasm\""), "expected the Wasm iframe")
    assertTrue(withWasm.contains("data-wasm-src=\"/wasm/compose-m3/?id=card-filled\""))
    // Default (untrusted / unknown): the iframe stays opaque-origin so an unverified `/wasm/` app
    // can't reach the viewer's tokened URLs or DOM. Match the exact attribute: script comments
    // mention the phrase.
    assertTrue(
      withWasm.contains("sandbox=\"allow-scripts\"") &&
        !withWasm.contains("sandbox=\"allow-scripts allow-same-origin\""),
      "untrusted Wasm stays opaque-origin (allow-scripts only)",
    )
    // A trusted catalog's app (wasmSameOrigin=true) gets its real origin so storage/history APIs
    // work. Still no allow-forms / allow-popups / allow-top-navigation.
    val trustedWasm =
      ServeWeb.viewerPage(
        card,
        token,
        wasmSrc = "/wasm/compose-m3/?id=card-filled",
        wasmSameOrigin = true,
      )
    assertTrue(
      trustedWasm.contains("sandbox=\"allow-scripts allow-same-origin\""),
      "trusted Wasm gets same-origin",
    )
    // Flash-free switch: the snapshot stays on-stage until the app's first-frame signal, and the
    // iframe is overlaid on the snapshot's exact box (pixel parity with the baked PNG).
    assertTrue(
      viewerSource().contains("\"cp-wasm-ready\""),
      "viewer listens for the app's first-frame signal",
    )
    assertTrue(
      viewerSource().contains("function positionWasmFrame()"),
      "iframe is positioned over the snapshot's rendered box",
    )
    assertTrue(
      viewerSource().contains("loading Wasm…"),
      "load state keeps the snapshot with a status",
    )
    // No page-side font preload: the app's own index.html prefetches.
    assertFalse(
      withWasm.contains("preloadWasmFonts"),
      "no page-side font preload (the app's index.html owns the prefetch)",
    )
    // The "Component only (no background)" wasm checkbox was removed; the app always renders its
    // themed background.
    assertFalse(withWasm.contains("id=\"cp-wasm-bg\""), "the Component-only toggle is gone")
    assertFalse(
      withWasm.contains("Component only"),
      "no Component-only option (the app renders its own background)",
    )
    assertFalse(
      withWasm.contains("\"background=off\""),
      "no background=off forwarded to the app anymore",
    )

    // No wasmSrc → snapshot viewer has no Wasm mode: png + live mode inputs only, no Wasm
    // input/iframe.
    val plain = ServeWeb.viewerPage(card, token)
    assertTrue(!plain.contains("name=\"cp-mode\" value=\"wasm\""))
    assertTrue(!plain.contains("id=\"cp-wasm\""))
    assertTrue(!plain.contains("id=\"cp-wasm-bg\""))
    assertTrue(plain.contains("name=\"cp-mode\" value=\"png\""), "png mode input always present")
  }

  @Test
  fun `viewer offers sign-in in place of the live toggle when auth is what blocks the lane`() {
    val card = previews.first { it.id.endsWith("CardPreview") }
    val protectedLive =
      ServeWeb.viewerPage(
        card,
        token,
        canApplyOverrides = true,
        liveAuthPrompt =
          ServeWeb.LiveAuthPrompt(
            loginHref = "/auth/github/start?return=%2Fp%2FCardPreview%3Ftoken%3Dabc"
          ),
      )

    // The lane is one click away, so offer a working sign-in link, not a disabled button with a
    // title.
    assertTrue(
      protectedLive.contains("id=\"cp-live-signin\"") &&
        protectedLive.contains(
          "href=\"/auth/github/start?return=%2Fp%2FCardPreview%3Ftoken%3Dabc\""
        ),
      "auth-blocked live preview offers a real sign-in link, not a dead control",
    )
    assertTrue(
      protectedLive.contains("Live preview — sign in"),
      "the reason is in the visible label, not only in a hover tooltip",
    )
    // The dead `data-github-login` attribute must not come back.
    assertFalse(
      protectedLive.contains("data-github-login="),
      "the login URL is the anchor's href, not an attribute nothing reads",
    )
    // The live stream only requires sign-in; the repo check is the playground's, so
    // `--github-auth-repo` must not be named here.
    assertFalse(
      protectedLive.contains("data-github-repo="),
      "the Live sign-in must not carry the playground's gating repo",
    )
    assertTrue(
      protectedLive.contains(
        "title=\"Sign in with GitHub to enable Live preview. Any GitHub account works.\""
      ),
      "…and its tooltip states the real bar instead of naming that repo",
    )
    // ...unless `--github-auth-users` narrows sign-in, in which case "any GitHub account" would be
    // false.
    val allowlisted =
      ServeWeb.viewerPage(
        card,
        token,
        canApplyOverrides = true,
        liveAuthPrompt =
          ServeWeb.LiveAuthPrompt(
            loginHref = "/auth/github/start?return=%2Fp%2FCardPreview",
            restrictedToAllowedUsers = true,
          ),
      )
    assertTrue(
      allowlisted.contains(
        "title=\"Sign in with GitHub to enable Live preview. " +
          "This server allows named GitHub users only.\""
      ),
      "an allowlisted server does not promise that any account works",
    )
    // Must NOT be the toggle: `updateLiveToggle()` drives that element through `.disabled` and
    // `aria-pressed`, neither of which means anything on a link.
    assertFalse(
      protectedLive.contains("id=\"cp-live-toggle\""),
      "the sign-in link replaces the toggle rather than impersonating it",
    )
    assertTrue(
      protectedLive.contains(
        "name=\"cp-mode\" value=\"live\" id=\"cp-live\" tabindex=\"-1\" disabled"
      ),
      "the hidden live transport radio stays disabled — sign-in is offered, not granted",
    )

    // A bundle with no live lane at all keeps the honestly-disabled toggle: inviting a sign-in
    // that would unlock nothing is worse than a greyed chip.
    val noLane = ServeWeb.viewerPage(card, token, canApplyOverrides = false)
    assertTrue(
      noLane.contains("id=\"cp-live-toggle\"") && !noLane.contains("cp-live-signin"),
      "a session with nothing to unlock is not invited to sign in",
    )

    val openLive = ServeWeb.viewerPage(card, token, canApplyOverrides = true)
    assertTrue(
      openLive.contains(
        "id=\"cp-live-toggle\" class=\"cp-live-toggle\" aria-pressed=\"false\" " +
          "data-default-lane-label=\"Snapshot\" " +
          "title=\"Static snapshot — click for the live, interactive preview\">"
      ),
      "live preview remains an ordinary toggle when no GitHub sign-in prompt is required",
    )

    // The mode hint must not say "no live lane" beside an offer to sign in for one.
    assertTrue(
      viewerSource().contains("static snapshot — sign in for live"),
      "an auth-blocked lane is reported as needing sign-in, not as absent",
    )
    assertTrue(
      viewerSource().contains("const liveSignIn = may<HTMLAnchorElement>(\"cp-live-signin\")"),
      "the hint keys off the sign-in link, which is the only marker of the auth-blocked state",
    )

    // The sign-in case gets no stage invitation: the stage click enters via `#cp-live-toggle`,
    // which this page doesn't render.
    assertFalse(
      protectedLive.contains("id=\"cp-stage-live-hint\""),
      "no click-for-live hint over a stage whose lane is still behind sign-in",
    )
    assertFalse(
      protectedLive.contains("id=\"cp-live-toggle-verb\""),
      "the sign-in link names its own destination; it does not carry the chip's verb",
    )
  }

  @Test
  fun `the viewer invites the live lane from the stage, not only from the toolbar chip`() {
    val card = previews.first { it.id.endsWith("CardPreview") }

    // Two ways into the live lane besides the toolbar chip: a hint badge on the picture and a click
    // on the picture itself.
    val openLive = ServeWeb.viewerPage(card, token, canApplyOverrides = true)
    assertTrue(
      openLive.contains(
        "<span class=\"cp-live-hint cp-stage-live-hint\" id=\"cp-stage-live-hint\" " +
          "aria-hidden=\"true\">click for live</span>"
      ),
      "the stage carries the grid's own live-hint badge, worded for the gesture it offers here",
    )
    // Deliberately the SAME class the grid's cards use (`CatalogLive.ts`), so one badge style
    // means one thing across both surfaces rather than two lookalikes drifting apart.
    assertTrue(
      openLive.indexOf("id=\"cp-stage-live-hint\"") > openLive.indexOf("class=\"cp-stage\""),
      "the hint lives inside the stage, where the render it describes is",
    )
    // The chip reads as a switch rather than a caption: the label names the lane it is ON, the
    // verb names the lane a click goes TO.
    assertTrue(
      openLive.contains(
        "<span class=\"cp-live-toggle-verb\" id=\"cp-live-toggle-verb\" aria-hidden=\"true\">" +
          "▸ Live</span>"
      ),
      "the chip states its destination, so \"Java\" alone can't read as a readout",
    )
    // aria-hidden matters: the accessible name stays the lane's own name, and `aria-pressed` plus
    // the tooltip already carry the switch semantics. Without it the chip announces "Java ▸ Live".
    assertTrue(
      openLive.contains("id=\"cp-live-toggle\"") && openLive.contains("aria-pressed=\"false\""),
      "the verb is added beside the existing toggle semantics, not in place of them",
    )

    // A session with no live lane gets neither: a disabled chip must not promise a destination,
    // and a hint over a stage whose click is inert is worse than no hint at all.
    val noLane = ServeWeb.viewerPage(card, token, canApplyOverrides = false)
    assertTrue(
      Regex("id=\"cp-live-toggle\"[^>]* disabled>").containsMatchIn(noLane),
      "the fixture under test is the disabled-chip case",
    )
    assertFalse(
      noLane.contains("id=\"cp-stage-live-hint\"") || noLane.contains("id=\"cp-live-toggle-verb\""),
      "nothing invites a lane that does not exist",
    )

    // One predicate behind the chip's verb, the badge and the stage's click handler, so the three
    // cannot disagree about whether clicking the picture does anything.
    assertTrue(
      viewerSourceFlat().contains("function liveInvited() { return rules.liveInviteAvailable({"),
      "the invitation is derived, not duplicated per affordance",
    )
    // Single click, not double: a double-click requirement is exactly as undiscoverable as the
    // toolbar-only chip this replaces.
    assertTrue(
      viewerSource().contains("img.addEventListener(\"click\", function (event) {") &&
        !viewerSource().contains("img.addEventListener(\"dblclick\""),
      "the stage enters live on a single click",
    )
  }

  @Test
  fun `the in-browser Wasm lane is reachable from the renderer combo whenever a wasm app backs the session`() {
    val card = previews.first { it.id.endsWith("CardPreview") }
    // Case C: daemon live lane + wasm app. The chip prefers the daemon (bestLiveMode), so Wasm is
    // reachable as a renderer-combo option.
    val both =
      ServeWeb.viewerPage(
        card,
        token,
        sessionId = "compose-m3",
        hasLiveStream = true,
        wasmSrc = "/wasm/compose-m3/?id=card-filled",
        wasmSameOrigin = true,
      )
    assertTrue(
      both.contains("<option value=\"wasm\">In browser (Wasm)</option>"),
      "with a wasm app the viewer offers the in-browser lane in the renderer combo",
    )
    assertTrue(both.contains("id=\"cp-live-toggle\""), "the live toggle stays alongside it")
    // On the wasm lane, syncServerControls disables daemon-only controls the iframe can't honour.
    assertTrue(
      viewerSource().contains("var onWasm = wasmActive();") &&
        viewerSourceFlat().contains("!onWasm && !onRc && !onFixedFrame &&"),
      "server-only controls are gated off while the Wasm lane is active",
    )
    // `onFixedFrame` is one predicate covering both fixed-frame lanes (spec raster and published
    // capture), defined in `onFixedFrameLane()` and shared by `syncServerControls` and
    // `activeThemeChoice`. Asserted against the helper and its uses.
    assertTrue(
      viewerSourceFlat().contains("""return mode === "spec" || mode === "motion";"""),
      "the fixed-frame predicate still covers both the spec and the motion lane",
    )
    assertTrue(
      viewerSource().contains("var onFixedFrame = onFixedFrameLane();") &&
        !viewerSource().contains("var onSpec = specActive();"),
      "one fixed-frame predicate gates the override families, not a spec-only flag beside it",
    )
    assertTrue(
      viewerSourceFlat().contains("hasDeclaredThemes && !onWasm"),
      "declared options in the unified Theme selector are disabled on the Wasm lane",
    )

    // Case B — wasm app but NO daemon lane: the chip already drops into wasm as its only
    // interactive lane, but the combo still names both so the visitor can read what they're on.
    val wasmOnly = ServeWeb.viewerPage(card, token, wasmSrc = "/wasm/compose-m3/?id=card-filled")
    assertTrue(
      wasmOnly.contains("<option value=\"png\">Snapshot</option>") &&
        wasmOnly.contains("<option value=\"wasm\">In browser (Wasm)</option>"),
      "a wasm-only session names both of its lanes",
    )

    // Case A: daemon lane, no wasm app. One lane, so no combo; the chip names the stage's state and
    // its verb names the switch.
    val daemonOnly = ServeWeb.viewerPage(card, token, canApplyOverrides = true)
    assertFalse(
      daemonOnly.contains("id=\"cp-lane-select\""),
      "a single-lane session grows no combo box",
    )
    assertTrue(
      daemonOnly.contains("<span id=\"cp-live-toggle-label\">Snapshot</span>") &&
        daemonOnly.contains(">▸ Live</span>"),
      "the chip reads \"Snapshot ▸ Live\": a state, and the switch out of it",
    )
    // The label must not carry the destination too. It did before the verb existed — and the pair
    // then read "Live preview ▸ Live", one chip naming the same lane twice.
    assertFalse(
      daemonOnly.contains("<span id=\"cp-live-toggle-label\">Live preview</span>"),
      "the destination is the verb's job, and only the verb's",
    )
    // With no lane to enter there is no verb to pair with, so the label keeps the plain (disabled)
    // invitation — "Snapshot" alone beside a dead dot would say nothing about what the chip is for.
    val noLaneAtAll = ServeWeb.viewerPage(card, token, canApplyOverrides = false)
    assertTrue(
      noLaneAtAll.contains("<span id=\"cp-live-toggle-label\">Live preview</span>"),
      "a chip with nothing to switch to still says what it is about",
    )
  }

  @Test
  fun `live canvas fits the daemon frame aspect-preserved inside the snapshot box`() {
    // The live lane is pinned to the snapshot's box, but a <canvas> stretches its buffer; the
    // viewer contain-fits and centres the frame instead.
    val liveView =
      ServeWeb.viewerPage(
        previews.first { it.id.endsWith("CardPreview") },
        token,
        canApplyOverrides = true,
      )
    assertTrue(
      viewerSource().contains("function fitLiveCanvas()"),
      "the live canvas has a dedicated aspect-preserving fit function",
    )
    // Contain-fit math: scale by the smaller of the two box/buffer ratios, then centre.
    assertTrue(
      viewerSource().contains("Math.min(boxW / liveW, boxH / liveH)"),
      "the frame is scaled to contain (the smaller box/buffer ratio), not stretched to fill",
    )
    assertTrue(
      viewerSource().contains("(boxW - w) / 2") && viewerSource().contains("(boxH - h) / 2"),
      "the fitted frame is centred within the snapshot box",
    )
    // The painter caches buffer dims and re-fits each frame and on resize, reading the decoded
    // bitmap (`createImageBitmap`).
    assertTrue(
      viewerSource().contains("liveW = bitmap.width;") &&
        viewerSource().contains("fitLiveCanvas();"),
      "each frame caches its dims and re-fits",
    )
    val painter = viewerSource().substringAfter("paintedSeq = frame.seq;")
    assertTrue(
      painter.indexOf("liveW = bitmap.width;") in 0 until painter.indexOf("bitmap.close();"),
      "the frame dimensions are cached before ImageBitmap.close() zeros them",
    )
    assertTrue(
      viewerSource().contains("if (live && live.checked && !canvas.hidden) fitLiveCanvas();"),
      "a window resize re-fits the live canvas (not a plain box fill)",
    )
  }

  @Test
  fun `the snapshot image records the render URL its pixels came from`() {
    // The visible <img> shows a `blob:` URL, so `data-cp-src` carries the originating /render URL
    // as the displayed frame's identity (`#cp-url-png` / `#cp-url-svg` track controls, not landed
    // frames). A unit-level guard for the serve-lanes e2e.
    val js = viewerSource()
    assertTrue(
      js.contains("img.setAttribute(\"data-cp-src\", url);"),
      "the fetched URL is recorded",
    )
    assertTrue(
      js.indexOf("img.setAttribute(\"data-cp-src\", url);") >
        js.indexOf("img.setAttribute(\"data-cp-blob\", objectUrl);"),
      "recorded in the same onload that swaps the frame in, so it never describes pixels that " +
        "haven't been painted yet",
    )
  }

  @Test
  fun `the viewer spends its vertical space on the render, not on chrome above it`() {
    val view =
      ServeWeb.viewerPage(
        previews.first(),
        token,
        siblings = previews,
        sessionId = "compose-m3",
        catalogTitle = "Material 3 Design Kit",
        sourceHref = "https://github.com/yschimke/compose-ai-tools/blob/main/Profile.kt",
        engagement = ServeWeb.PreviewEngagement(1),
      )

    // 1. The breadcrumb is navigation, so it rides in the navigation bar rather than spending the
    //    body's first row on it.
    assertFalse(
      view.substringAfter("<main class=\"cp-main\">").contains("cp-breadcrumb"),
      "the trail is out of the body",
    )
    assertTrue(
      view
        .substringBefore("<main class=\"cp-main\">")
        .contains(
          "<nav class=\"cp-breadcrumb\" aria-label=\"Breadcrumb\">" +
            "<a href=\"/?token=$token&amp;session=compose-m3\">Material 3 Design Kit</a>"
        ),
      "…and in the header's lead slot, still linking the catalog it came from",
    )

    // 2. Name, trust verdict, id and the view tally are ONE row — they answer one question.
    val head = view.substringAfter("<div class=\"cp-preview-head\">").substringBefore("</div>")
    assertTrue(head.contains("<h1 class=\"cp-head cp-preview-title\">"), head)
    assertTrue(head.contains("<code class=\"cp-preview-id\""), head)
    assertTrue(
      head.contains(
        "<span class=\"cp-viewer-engage\"><span ${ServeWeb.VOLATILE_ATTR}>1 view</span></span>"
      ),
      head,
    )

    // 3. Provenance links sit with the other hand-off affordances (PNG / SVG exports) below the
    //    stage.
    assertTrue(
      view.indexOf("class=\"cp-preview-links\"") > view.indexOf("class=\"cp-viewer "),
      "the provenance row is below the stage",
    )
    assertTrue(
      view.indexOf("class=\"cp-preview-links\"") < view.indexOf("class=\"cp-export\""),
      "…and immediately above the export bar it now leads",
    )
  }

  @Test
  fun `theme choices use a dropdown and stage presentation moves into the panel`() {
    val css = assetText("serve.css")
    assertTrue(
      css.contains(".cp-theme-menu-panel { position: absolute;") &&
        css.contains(".cp-theme-menu-panel .cp-theme-bar { display: flex; flex-direction: column;"),
      "theme choices render in an anchored dropdown",
    )
    // Eight theme chips beside four fixed controls — the published compose-m3 shape, and what the
    // committed `serve-viewer-theme-overflow` golden captures for the visual-diff bot.
    val crowded =
      ServeWeb.viewerPage(
        previews.first(),
        token,
        siblings = previews,
        sessionId = "compose-m3",
        canApplyOverrides = true,
        declaredThemes =
          (1..6).map { ServeTheme("Declared theme $it", "com.example.Theme${'$'}it") },
      )
    assertEquals(
      1,
      crowded.split("class=\"cp-theme cp-theme-bar\"").size - 1,
      "the dropdown contains one theme choice group",
    )
    assertFalse(crowded.contains("class=\"cp-viewer-bar\""), "the old horizontal row is gone")
    // Transparent and Fit width moved off the renderer row (they present the stage, not choose its
    // renderer). Asserted from both ends so a page that lost them entirely also fails.
    val rendererRow =
      crowded.substringAfter("<div class=\"cp-preview-primary\"").substringBefore("</div>")
    assertFalse(
      rendererRow.contains("<cp-bg-toggle") || rendererRow.contains("Fit width"),
      "stage presentation is not on the renderer row",
    )
    val panel = crowded.substringAfter("<div class=\"cp-controls\" id=\"cp-controls\">")
    assertTrue(
      panel.contains("<cp-bg-toggle") && panel.contains("Fit width"),
      "…it is in the Overrides panel, where the drawer's own View group holds it",
    )
  }

  /**
   * The page carries the density the preview actually renders at.
   * `ServeBakedCatalogPreviewParamsTest` pins the resolution; this pins that it reaches the
   * viewer's attribute (with the documented fallback), so dp→px conversion for the size inputs
   * matches the renderer.
   */
  @Test
  fun `the viewer page carries the preview's own render density`() {
    val stated =
      ServeWeb.viewerPage(
        previews.first(),
        token,
        siblings = previews,
        sessionId = "compose-m3",
        canApplyOverrides = true,
        renderDensity = 2.75f,
      )
    assertTrue(
      stated.contains("data-render-density=\"2.75\""),
      "a preview rendering at 2.75 says so, so a dp box converts against 2.75",
    )
    val unknown =
      ServeWeb.viewerPage(
        previews.first(),
        token,
        siblings = previews,
        sessionId = "compose-m3",
        canApplyOverrides = true,
      )
    assertTrue(
      unknown.contains("data-render-density=\"2\""),
      "and a session that cannot answer falls back, exactly as every page used to",
    )
  }

  /**
   * The eyedropper readout must not change the spec lane's width. `.cp-spec-lane` is `inline-flex`
   * (shrink-to-fit) and `.cp-spec-pick` is `flex-basis: 100%`, so a reading widened the lane,
   * wrapped neighbouring toggles and moved the stage on every hover. `width: 0` keeps the readout
   * out of the lane's measurement; `min-width: 100%` then spans the settled lane and `overflow-x`
   * scrolls long readings. Pinned here because a screenshot can't show a used width.
   */
  @Test
  fun `the eyedropper's readout cannot resize the spec lane`() {
    val css = assetText("serve.css")
    assertTrue(
      css.contains(".cp-spec-pick { flex-basis: 100%; width: 0; min-width: 100%;"),
      "the readout is measured at zero width, so the lane's size does not follow the reading",
    )
    assertTrue(
      css.contains(".cp-spec-lane { display: inline-flex;"),
      "…which is only load-bearing because the lane is shrink-to-fit; if that changes, re-measure",
    )
    val pickRule = css.substringAfter(".cp-spec-pick { flex-basis: 100%;").substringBefore("}")
    assertTrue(
      pickRule.contains("overflow-x: auto;"),
      "a reading wider than the lane scrolls in its row rather than widening it",
    )
  }

  /**
   * Every visually-hidden box is anchored as well as clipped. Clipping doesn't move the box; an
   * unanchored absolute span at the end of the long `overflow-x: auto` `.cp-preview-primary` row
   * widened the document on a phone, growing the layout viewport and pushing the fixed `.cp-fab`
   * report button off screen (and `html { overflow-x: clip }` made it unreachable). See
   * `serve-web/renders/report-button-reach`.
   */
  @Test
  fun `a visually-hidden box cannot push the page wider than the device`() {
    val css = assetText("serve.css")
    for (selector in
      listOf(
        ".cp-spec-pick-live { position: absolute; left: 0; top: 0;",
        ".cp-url { position: absolute; left: 0; top: 0;",
        ".cp-modes-inputs { position: absolute; left: 0; top: 0;",
      )) {
      assertTrue(css.contains(selector), "$selector — clipped is not enough, it has to be anchored")
    }
    assertTrue(
      css.contains(".cp-fab { position: fixed; right: 16px;"),
      "…which is load-bearing only because the report launcher is anchored to the layout viewport",
    )
  }

  /**
   * Triptych frames fill their columns, with `object-fit: contain`. The base rule's `max-width` /
   * `max-height` only shrink, so small rasters sat tiny in stretched columns, leaving most of the
   * stage inert for the eyedropper. Once the width is definite, `contain` letterboxes instead of
   * squashing tall rasters (e.g. 192x192 Wear screens). `SpecCompare.drawnRect` maps through the
   * drawn rectangle; `specCompare.test.ts` pins that half.
   */
  @Test
  fun `a triptych frame fills its column without being distorted`() {
    val css = assetText("serve.css")
    val triptych =
      css
        .substringAfter(".cp-spec-compare[data-view=\"triptych\"] .cp-spec-panel canvas {")
        .substringBefore("}")
    assertTrue(
      triptych.contains("width: 100%;") && triptych.contains("height: auto;"),
      "the frame fills its stretched column instead of sitting at its intrinsic size",
    )
    assertTrue(
      triptych.contains("object-fit: contain;"),
      "…and letterboxes rather than squashing when the height budget binds",
    )
    assertTrue(
      triptych.contains("max-height: 52vh;"),
      "the height budget itself stays — a tall app screen must not push the stage past the fold",
    )
    // Nearest-neighbour is applied by class, never by the rule above: upscaled it shows the pixel
    // a reading names, but downscaling a 1000px app screen that way invents aliasing.
    assertFalse(
      triptych.contains("image-rendering"),
      "the rule itself does not pin an interpolation for every frame in the triptych",
    )
    assertTrue(
      css.contains(
        ".cp-spec-panel canvas.cp-spec-canvas--upscaled { image-rendering: pixelated; }"
      ),
      "only a frame the component marked as enlarged gets nearest-neighbour",
    )
  }

  /**
   * The Theme dropdown's rows were unreadable on dark pages, from two cascade faults:
   * 1. `color-scheme: normal` on a row resets to the UA light scheme, re-resolving `light-dark()`
   *    tokens to light text on a dark panel; `inherit` is correct.
   * 2. `.cp-theme-bar .cp-theme-btn` (the old scroller rule) is declared later at equal
   *    specificity, giving rows `inline-block` and collapsing the swatch pseudo-element. Every
   *    menu-row rule must therefore also name `.cp-theme-bar`.
   */
  @Test
  fun `theme menu rows keep the page's colour scheme and the menu's own box`() {
    val css = assetText("serve.css")
    assertFalse(
      css.contains("color-scheme: normal"),
      "no rule resets a subtree to the UA's light default; `inherit` is what follows the page",
    )
    assertTrue(
      css.contains(
        ".cp-theme-menu-panel .cp-theme-bar .cp-theme-btn { display: flex; min-width: 12em;"
      ),
      "menu rows out-specify the scroller's `inline-block`, so the swatch gets a real box",
    )
    assertTrue(
      css.contains(
        ".cp-theme-menu-panel .cp-theme-bar .cp-theme-btn[data-theme-mode] { color-scheme: inherit;"
      ),
      "…and resolve their label colour in the scheme the page is actually painted in",
    )
    // Every rule that dresses a menu row has to clear the scroller the same way, or the next one
    // added without `.cp-theme-bar` silently loses whatever the scroller also declares.
    val menuRules =
      css.lines().filter { it.trimStart().startsWith(".cp-theme-menu-panel .cp-theme-btn") }
    assertTrue(
      menuRules.isEmpty(),
      "unqualified menu-row rules lose to `.cp-theme-bar`: $menuRules",
    )
  }

  @Test
  fun `viewer defaults to fit screen and offers an explicit fit width mode`() {
    val view = ServeWeb.viewerPage(previews.first(), token)
    // One toggle, unpressed: screen fit is the default, and "Fit width" names the state the
    // button turns on rather than a second segment that re-selects what is already showing.
    assertTrue(
      view.contains("class=\"cp-bg-btn cp-zoom-toggle\" aria-pressed=\"false\"") &&
        view.contains(">Fit width</button>"),
      "the viewer offers width fit as an unpressed toggle over the default screen fit",
    )
    // The cap arithmetic lives in `serve-web/src/viewer/fit.ts` (tested by `viewerFit.test.ts`).
    // This pins that the served asset measures the stage's real position, uses the shared rule, and
    // applies a cap before the first render.
    assertTrue(
      viewerSource().contains("stage.getBoundingClientRect().top") &&
        viewerSource().contains("rules.fitCap(top, window.innerHeight)") &&
        viewerSource().contains("applyZoom(\"fit\");"),
      "screen fit bounds tall previews to the space the viewport actually has left below the " +
        "chrome, measured before the initial render rather than guessed at 72vh",
    )
    assertTrue(
      viewerSource().contains("if (live && live.checked && !canvas.hidden) fitLiveCanvas();") &&
        viewerSource().contains("if (wasmActive()) positionWasmFrame();"),
      "changing zoom re-pins active live and Wasm overlays to the snapshot geometry",
    )
  }

  @Test
  fun `screen previews keep device controls in Size while components expose only constraints`() {
    val screen =
      ServeWeb.viewerPage(
        ServePreview("speaker-details", "Speaker details", section = "Screens"),
        token,
        canApplyOverrides = true,
      )
    val component =
      ServeWeb.viewerPage(
        ServePreview("speaker-card", "Speaker card", section = "Components"),
        token,
        canApplyOverrides = true,
      )
    val sectionlessScreen =
      ServeWeb.viewerPage(
        ServePreview("com.example.ProfilePreview", "Profile screen"),
        token,
        canApplyOverrides = true,
      )
    val sectionlessComponent =
      ServeWeb.viewerPage(
        ServePreview("com.example.SpeakerCardPreview", "Speaker card"),
        token,
        canApplyOverrides = true,
      )

    assertTrue(screen.contains("<label>Device size"), "screen Size panel contains device presets")
    assertTrue(screen.contains("id=\"cp-orientation\""), "screen Size panel contains orientation")
    assertFalse(screen.contains("id=\"cp-sizeMode\""), "screen omits component constraints")
    assertFalse(screen.contains("data-cp-group=\"device\""), "screen has no duplicate Device panel")
    listOf("id:pixel_5", "id:pixel_7", "id:pixel_fold", "id:pixel_tablet").forEach {
      assertTrue(screen.contains("value=\"$it\""), "screen offers $it")
    }

    assertTrue(component.contains("id=\"cp-sizeMode\""), "component keeps size constraints")
    assertTrue(component.contains("id=\"cp-fixedW\""), "component keeps fixed sizing")
    assertTrue(component.contains("id=\"cp-minW\""), "component keeps minimum sizing")
    assertTrue(component.contains("id=\"cp-maxW\""), "component keeps maximum sizing")
    assertFalse(component.contains("id=\"cp-device\""), "component hides device overrides")
    assertFalse(
      component.contains("id=\"cp-orientation\""),
      "component hides orientation overrides",
    )
    assertFalse(component.contains("data-cp-group=\"device\""), "component has no Device panel")

    assertTrue(
      sectionlessScreen.contains("<label>Device size") &&
        sectionlessScreen.contains("id=\"cp-orientation\""),
      "sectionless screen keeps device controls in Size",
    )
    assertFalse(
      sectionlessScreen.contains("id=\"cp-sizeMode\""),
      "sectionless screen omits component constraints",
    )
    assertTrue(
      sectionlessComponent.contains("id=\"cp-sizeMode\""),
      "sectionless component keeps size constraints",
    )
    assertFalse(
      sectionlessComponent.contains("id=\"cp-device\"") ||
        sectionlessComponent.contains("id=\"cp-orientation\""),
      "sectionless component still hides device overrides",
    )
  }

  @Test
  fun `a Wear system's screens offer watch shapes instead of phones and no orientation`() {
    val wearScreen =
      ServeWeb.viewerPage(
        ServePreview("settings-complication", "Settings complication", section = "Screens"),
        token,
        canApplyOverrides = true,
        basePath = "/home-assistant-wear",
      )
    val phoneScreen =
      ServeWeb.viewerPage(
        ServePreview("settings", "Settings", section = "Screens"),
        token,
        canApplyOverrides = true,
        basePath = "/meshcore-mobile",
      )

    assertTrue(wearScreen.contains("<label>Device size"), "a Wear screen keeps the device picker")
    listOf(
        "id:wearos_small_round",
        "id:wearos_large_round",
        "id:wearos_xl_round",
        "id:wearos_square",
        "id:wearos_rect",
      )
      .forEach { assertTrue(wearScreen.contains("value=\"$it\""), "Wear screen offers $it") }
    listOf("id:pixel_5", "id:pixel_7", "id:pixel_fold", "id:pixel_tablet").forEach {
      assertFalse(wearScreen.contains("value=\"$it\""), "Wear screen must not offer $it")
      assertTrue(phoneScreen.contains("value=\"$it\""), "a handheld screen still offers $it")
    }
    // Watches don't rotate — the control is omitted rather than left as a dead knob, and neither
    // static-snapshot note may advertise it.
    assertFalse(wearScreen.contains("id=\"cp-orientation\""), "Wear screen omits orientation")
    assertTrue(phoneScreen.contains("id=\"cp-orientation\""), "a handheld screen keeps orientation")

    val staticWearScreen =
      ServeWeb.viewerPage(
        ServePreview("settings-complication", "Settings complication", section = "Screens"),
        token,
        canApplyOverrides = false,
        basePath = "/home-assistant-wear",
      )
    assertFalse(
      staticWearScreen.lowercase().contains("orientation"),
      "the Wear snapshot note must not promise an orientation override",
    )
    assertTrue(
      staticWearScreen.contains("device size, locale, font scale"),
      "the Wear snapshot note still lists the overrides it does carry",
    )
  }

  @Test
  fun `SVG is an on-screen format toggle and an export format when the session can export SVG`() {
    val card = previews.first { it.id.endsWith("CardPreview") }
    // An SVG toggle beside the Live toggle swaps the static snapshot between PNG and SVG, offered
    // only with hasSvgExport.
    val svgView = ServeWeb.viewerPage(card, token, hasSvgExport = true, hasScrollExport = true)
    assertTrue(
      svgView.contains("id=\"cp-svg-toggle\"") && svgView.contains("class=\"cp-fmt-toggle\""),
      "an SVG-exporting session offers the on-screen SVG format toggle",
    )
    // The SVG lane reuses the snapshot <img> but swaps the render extension; the viewer JS carries
    // the snapshotExt seam and stamps the backend badge with SVG.
    assertTrue(
      viewerSource().contains("var snapshotExt = \".png\";") &&
        viewerSource().contains("? \".svg\" : \".png\""),
      "the snapshot lane flips its render extension between PNG and SVG",
    )
    // The badge's "▪ SVG" label is asserted in `serve-web/test/backendBadge.test.ts`. The SVG
    // export also gets a URL row and the "Full page (scroll)" toggle.
    assertTrue(
      svgView.contains("id=\"cp-url-svg\"") && svgView.contains("id=\"cp-scroll-long\""),
      "an SVG-exporting session offers the SVG download row and its Full-page toggle",
    )

    // Scroll export is its own capability: a PNG-only daemon still offers Full page.
    val pngScrollView = ServeWeb.viewerPage(card, token, hasScrollExport = true)
    assertTrue(
      pngScrollView.contains("id=\"cp-scroll-long\"") &&
        !pngScrollView.contains("id=\"cp-url-svg\""),
      "a PNG-only scroll producer offers Full page without advertising SVG",
    )

    // No export capabilities → no SVG toggle, no SVG URL row, and no scroll toggle.
    val plain = ServeWeb.viewerPage(card, token)
    assertFalse(plain.contains("id=\"cp-svg-toggle\""), "no SVG toggle without SVG export")
    assertFalse(plain.contains("id=\"cp-url-svg\""), "no SVG export row without SVG support")
    assertFalse(
      plain.contains("id=\"cp-scroll-long\""),
      "no Full-page toggle without a scroll export",
    )
  }

  @Test
  fun `a card answers the pointer as a tile, not as an underlined hyperlink`() {
    // Every clickable tile is one `<a class="cp-card">` wrapping image and metadata, so the global
    // `a:hover` underline would underline all of it. The card must suppress that and respond as an
    // object (M3: raise an elevation level, `primary` state layer), keyboard-reachable too.
    val css = assetText("serve.css")
    assertTrue(
      css.contains(".cp-card:hover, .cp-card:focus-visible {") &&
        css.contains("transform: translateY(-2px); text-decoration: none;") &&
        css.contains("box-shadow: var(--md-sys-elevation-level3);"),
      "hovering a card lifts it to a higher elevation instead of underlining its text",
    )
    // The M3 state layer: the card's own content colour composited over it at the spec's hover /
    // focus opacities, rather than a bespoke fill or rim.
    assertTrue(
      css.contains(".cp-card:hover::after { opacity: var(--md-sys-state-hover-opacity); }") &&
        css.contains(
          ".cp-card:focus-visible::after { opacity: var(--md-sys-state-focus-opacity); }"
        ),
      "a state layer covers the card under the pointer and under keyboard focus",
    )
    assertTrue(
      css.contains(
        ".cp-card:hover .cp-imgwrap img, .cp-card:focus-visible .cp-imgwrap img { transform: scale(1.035); }"
      ),
      "the card's artwork eases in under the pointer",
    )
    assertTrue(
      css.contains(
        ".cp-card:focus-visible { outline: 3px solid var(--md-sys-color-secondary); outline-offset: 2px; }"
      ),
      "keyboard focus gets the card treatment plus M3's own focus indicator",
    )
    // Motion is an enhancement, never the affordance: a visitor who asked for less motion still
    // gets the rim, the shadow and the wipe target — just no travel.
    assertTrue(
      css.contains(".cp-card:hover, .cp-card:focus-visible { transform: none; }"),
      "the lift and zoom are dropped under prefers-reduced-motion",
    )
  }

  @Test
  fun `pages are mobile-responsive with a viewport meta and a narrow breakpoint`() {
    // Every page carries the viewport meta, and the stylesheet's narrow breakpoint stacks the
    // viewer's stage + overrides and drops flex min-widths so nothing overflows ~320px. A viewer
    // with siblings covers the nav drawer too.
    val viewer = ServeWeb.viewerPage(previews.first(), token, siblings = previews)
    assertTrue(
      viewer.contains(
        "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1, " +
          "viewport-fit=cover, interactive-widget=resizes-content\">"
      ),
      "the page declares a mobile viewport that reaches under a notch and resizes for the keyboard",
    )
    // Safe-area insets and touch-sized targets ride on that declaration.
    val css = assetText("serve.css")
    assertTrue(css.contains("env(safe-area-inset-left"), "the page pads a landscape notch")
    assertTrue(css.contains("@media (pointer: coarse)"), "touch gets larger tap targets")
    assertTrue(css.contains("max-height: 78dvh"), "bottom sheets size to the visible viewport")
    assertTrue(
      assetText("serve.css").contains("@media (max-width: 640px) {"),
      "the stylesheet has a narrow-viewport breakpoint",
    )
    assertTrue(
      assetText("serve.css")
        .contains(".cp-stage, .cp-controls, .cp-nav { flex: 1 1 100%; min-width: 0; }"),
      "stage/overrides/nav stack full-width and drop their min-width on a phone",
    )
    // On mobile the drawers become bottom sheets toggled from the sticky title row, with a scrim.
    assertTrue(
      assetText("serve.css")
        .contains(
          ".cp-preview-head { position: sticky; top: var(--site-header-height); z-index: 21;"
        ),
      "the disclosure row is sticky below the global header on mobile",
    )
    assertTrue(
      assetText("serve.css").contains(".cp-viewer.cp-controls-open .cp-controls,") &&
        assetText("serve.css").contains("position: fixed; left: 0; right: 0; bottom: 0;"),
      "open drawers render as fixed bottom sheets on mobile",
    )
    assertTrue(
      viewer.contains("id=\"cp-scrim\"") &&
        assetText("serve.css").contains(".cp-scrim.cp-scrim-on"),
      "a dismiss scrim backs the open bottom sheet",
    )
    // The overrides drawer starts closed on phones; that is `<cp-viewer-drawers>`'s job, tested in
    // `serve-web/test/viewerDrawers.test.ts`. The page only needs the tag.
    assertTrue(viewer.contains("<cp-viewer-drawers>"), "the viewer wires its drawers")
    // The breakpoint ships on the landing pages too (shared stylesheet).
    val landing = ServeWeb.landingPage(moduleLabel, previews, token)
    assertTrue(
      assetText("serve.css").contains("@media (max-width: 640px) {"),
      "landing is responsive too",
    )
    assertTrue(
      assetText("serve.css")
        .contains(".cp-card.cp-sys { display: grid; grid-template-rows: 220px 1fr; }") &&
        assetText("serve.css").contains(".cp-syslist .cp-imgwrap { min-height: 0; height: 220px;"),
      "system cards reserve one consistent hero region so metadata aligns across aspect ratios",
    )
    // Three invariants the hero break-out depends on; dropping one regresses silently.
    assertTrue(
      assetText("serve.css").contains(".cp-sys-actions") &&
        assetText("serve.css").contains("pointer-events: none; }") &&
        assetText("serve.css")
          .contains(".cp-sys-actions > a, .cp-sys-actions > details { pointer-events: auto; }"),
      "the action row passes clicks through to the tile link; only its chips take them",
    )
    // Both load-bearing: the rounding (with `overflow: visible`, nothing clips the layer's corners)
    // and `z-index: -1` (the tint goes under the content, not over the screenshot). Lowering the
    // layer rather than raising the hero keeps the overhanging hero hoverable; the harness proves
    // that in a browser.
    assertTrue(
      assetText("serve.css")
        .contains(".cp-card.cp-sys::after { border-radius: inherit; z-index: -1; }"),
      "the state layer rounds itself and paints beneath the hero instead of over it",
    )
    assertTrue(
      assetText("serve.css")
        .contains(
          ".cp-card.cp-sys:focus-within:not(:has(.cp-action-chip:focus-visible)) .cp-sys-title"
        ),
      "keyboard focus on the tile link gets the full card treatment, not just the outline",
    )
    assertTrue(
      assetText("serve.css")
        .contains(
          ".cp-card.cp-sys:focus-within:not(:has(.cp-action-chip:focus-visible)) " +
            "{ transform: none; }"
        ),
      "…and reduced motion still cancels the lift for it — the blanket reset is keyed on " +
        ":focus-visible, which the card div can never match",
    )
    assertTrue(
      landing.contains("class=\"cp-site-header\"") && landing.contains("id=\"cp-status-link\""),
      "all pages carry the shared site navigation",
    )
  }

  @Test
  fun `the header drops the home and repo links the brand and footer already carry`() {
    val landing = ServeWeb.landingPage(moduleLabel, previews, token, version = version)
    val header =
      landing.substringAfter("<header class=\"cp-site-header\">").substringBefore("</header>")
    // "Catalogs" pointed at `/` — where the brand link beside it already goes. One destination, one
    // entry.
    assertFalse(header.contains(">Catalogs</a>"), "no second link to the home page")
    assertTrue(
      header.contains("class=\"cp-site-brand\" href=\"/?token=$token\""),
      "the brand is the way home",
    )
    // The repo link is a fact about the software, so it sits with the build number in the footer.
    assertFalse(header.contains("github.com/"), "the repo link has left the header")
    val footer = landing.substringAfter("<footer class=\"cp-site-footer\">")
    assertTrue(
      footer.contains("<a href=\"https://github.com/yschimke/compose-ai-tools\">") &&
        footer.contains(" GitHub</a>"),
      "…and lands in the footer, beside /version and the build",
    )
    // Status and Settings stay put: both are about this server's own pages.
    assertTrue(
      header.contains("id=\"cp-status-link\"") && header.contains("class=\"cp-settings\""),
      "the page-scoped nav entries are untouched",
    )
  }

  @Test
  fun `a catalog landing uses the home-linked brand instead of duplicate navigation`() {
    val front = ServeWeb.landingPage(moduleLabel, previews, token, hasHomeIndex = true)
    assertFalse(front.contains("class=\"cp-systems\""), "the sideways design-systems nav is gone")
    assertFalse(front.contains("class=\"cp-back\""), "the home link is not duplicated")
    // No sideways links to the other catalogs any more.
    assertFalse(
      front.contains("href=\"/wear-m3/?token=$token\""),
      "no sideways link to sibling catalogs",
    )

    // Public mode keeps the same single route home.
    val public =
      ServeWeb.landingPage(moduleLabel, previews, token, isPublic = true, hasHomeIndex = true)
    assertFalse(public.contains("class=\"cp-back\""), "the public home link is not duplicated")

    // No home index → no back button (a plain, single-module `serve` with nothing to go back to).
    assertFalse(
      ServeWeb.landingPage(moduleLabel, previews, token).contains("class=\"cp-back\""),
      "a plain module landing shows no back button",
    )
  }

  @Test
  fun `a catalog landing shows the provenance strip with branch, date, versions and regenerate`() {
    val landing =
      ServeWeb.landingPage(
        "compose-m3",
        themedPreviews,
        token,
        isPublic = true,
        hasHomeIndex = true,
        version = version,
        provenance =
          ServeWeb.CatalogProvenance(
            repo = "yschimke/compose-ai-tools",
            branch = "design-artifacts/compose-m3",
            generatedAt = "2026-07-17T09:30:00.000Z",
            toolVersion = "0.16.54",
            designParityVersion = "0.1.25",
          ),
        refreshUrl = "/compose-m3/refresh",
      )
    assertTrue(
      landing.contains("class=\"cp-prov cp-disclosure\" open"),
      "the provenance details render, expanded",
    )
    // It lives in the site footer now, beside the build and source links.
    assertTrue(
      landing.indexOf("<footer class=\"cp-site-footer\">") <
        landing.indexOf("class=\"cp-prov cp-disclosure\"") &&
        landing.indexOf("class=\"cp-prov cp-disclosure\"") <
          landing.indexOf("class=\"cp-site-footer-links\""),
      "catalog details sit in the footer, above its links row",
    )
    // Links to the delivery branch and the regenerating workflow.
    assertTrue(
      landing.contains(
        "href=\"https://github.com/yschimke/compose-ai-tools/tree/design-artifacts/compose-m3\""
      ),
      "the strip links the delivery branch on GitHub",
    )
    assertTrue(
      landing.contains(
        "href=\"https://github.com/yschimke/compose-ai-tools/actions/workflows/design-artifacts.yml\""
      ),
      "the strip links the regenerating workflow",
    )
    // Friendly generation date + both tool versions.
    assertTrue(landing.contains("2026-07-17 09:30 UTC"), "the generation date is shown")
    assertTrue(
      landing.contains("class=\"cp-prov-refresh\"") &&
        landing.contains("data-refresh-url=\"/compose-m3/refresh\""),
      "the strip offers an immediate catalog refresh next to regenerate",
    )
    assertTrue(
      landing.contains("compose-ai-tools <code>0.16.54</code>") &&
        landing.contains("design-parity <code>0.1.25</code>"),
      "both generating tool versions are shown",
    )
    // No provenance passed → no strip (a plain bundle / non-catalog module).
    assertFalse(
      ServeWeb.landingPage(moduleLabel, previews, token, isPublic = true)
        .contains("class=\"cp-prov\""),
      "a landing without provenance shows no strip",
    )
  }

  @Test
  fun `the home footer surfaces the server version with a GitHub icon`() {
    val home =
      ServeWeb.homeIndexPage(
        listOf(
          ServeWeb.HomeSystem(
            system = "compose-m3",
            title = "Compose Material 3",
            subtitle = null,
            previewCount = 1,
            trust = null,
            heroPreviewId = null,
          )
        ),
        token,
        isPublic = true,
        version = "1.2.3",
      )
    assertTrue(home.contains(">server v1.2.3<"), "the running server version is shown")
    assertTrue(home.contains("class=\"cp-gh\""), "the source link carries the GitHub icon")
    assertTrue(
      home.indexOf("<footer class=\"cp-site-footer\">") < home.indexOf(">server v1.2.3<"),
      "the running server version is in the footer",
    )
    // The front door carries no "about this preview server" explainer at all.
    assertFalse(
      home.contains("About this preview server") ||
        home.contains("class=\"cp-about cp-disclosure\""),
      "the about box is gone from the front door",
    )
    // A null version simply omits the pill (no dangling separator crash), and the footer's own
    // links survive it.
    val noVer = ServeWeb.homeIndexPage(emptyList(), token, isPublic = true)
    assertFalse(noVer.contains("class=\"cp-about-ver\""), "no version pill when version is null")
    assertTrue(
      noVer.contains("<footer class=\"cp-site-footer\">") && noVer.contains("href=\"/version\""),
      "the footer still carries its links without a version",
    )
  }

  @Test
  fun `the home header shows GitHub login state when auth is configured`() {
    val unsigned =
      ServeWeb.homeIndexPage(
        emptyList(),
        token,
        isPublic = true,
        githubAuth = ServeWeb.GitHubAuthStatus(loginHref = "/auth/github/start?return=%2F"),
      )
    assertTrue(
      unsigned.contains("class=\"cp-gh-auth\" href=\"/auth/github/start?return=%2F\""),
      "unsigned home page links to GitHub sign-in",
    )
    assertTrue(unsigned.contains("> Sign in with GitHub</a>"), unsigned)
    assertTrue(
      unsigned.indexOf("<header class=\"cp-site-header\">") <
        unsigned.indexOf("> Sign in with GitHub</a>") &&
        unsigned.indexOf("> Sign in with GitHub</a>") < unsigned.indexOf("</header>"),
      "unsigned GitHub action is in the header",
    )

    val signed =
      ServeWeb.homeIndexPage(
        emptyList(),
        token,
        isPublic = true,
        githubAuth =
          ServeWeb.GitHubAuthStatus(
            loginHref = "/auth/github/start?return=%2F",
            login = "yschimke",
          ),
      )
    assertTrue(
      signed.contains("class=\"cp-gh-auth cp-gh-auth--signed\""),
      "signed home page shows GitHub status",
    )
    assertTrue(signed.contains("Signed in as yschimke"), signed)
    assertTrue(
      signed.indexOf("<header class=\"cp-site-header\">") <
        signed.indexOf("Signed in as yschimke") &&
        signed.indexOf("Signed in as yschimke") < signed.indexOf("</header>"),
      "signed GitHub identity is in the header",
    )
    val aboutEnd = signed.indexOf("</details>")
    assertFalse(
      signed.substring(0, aboutEnd).contains("server v") ||
        signed.substring(0, aboutEnd).contains("href=\"/version\""),
      "the home about disclosure no longer contains build metadata",
    )
  }

  /**
   * The two exits for a signed-in visitor, in the Settings menu: sign out, and re-authenticate (the
   * remedy for stale cached access).
   */
  @Test
  fun `the settings menu offers a sign-out and a way to switch account`() {
    val signed =
      ServeWeb.homeIndexPage(
        emptyList(),
        token,
        isPublic = true,
        githubAuth =
          ServeWeb.GitHubAuthStatus(
            loginHref = "/auth/github/start?return=%2F",
            logoutHref = "/auth/github/logout?return=%2F",
            login = "yschimke",
          ),
      )
    // A form, not a link: a sign-out a prefetcher can fire by looking at a URL is not one.
    assertTrue(
      signed.contains("method=\"post\" action=\"/auth/github/logout?return=%2F\""),
      signed,
    )
    assertTrue(signed.contains(">Sign out</button>"), signed)
    // …and the re-authenticate path is named, because a sign-out alone is the longer road to it.
    assertTrue(
      signed.contains("href=\"/auth/github/start?return=%2F\">Switch account</a>"),
      signed,
    )
    // Inside the Settings menu, not the bar: the header keeps the identity and nothing else.
    val settingsAt = signed.indexOf("<details class=\"cp-settings\">")
    assertTrue(settingsAt in 0 until signed.indexOf(">Sign out</button>"), signed)
    assertTrue(signed.indexOf("Signed in as yschimke") < settingsAt, signed)
    assertFalse(
      signed.substring(signed.indexOf("Signed in as yschimke"), settingsAt).contains("ign out"),
      "the header bar carries the identity, not the exits",
    )

    // A page built without a logout target — every caller that predates it — renders exactly what
    // it always did rather than a form posting nowhere.
    val noLogout =
      ServeWeb.homeIndexPage(
        emptyList(),
        token,
        isPublic = true,
        githubAuth =
          ServeWeb.GitHubAuthStatus(
            loginHref = "/auth/github/start?return=%2F",
            login = "yschimke",
          ),
      )
    assertFalse(noLogout.contains("cp-settings-session"), noLogout)
    assertFalse(
      ServeWeb.homeIndexPage(
          emptyList(),
          token,
          isPublic = true,
          githubAuth =
            ServeWeb.GitHubAuthStatus(
              loginHref = "/auth/github/start?return=%2F",
              logoutHref = "/auth/github/logout?return=%2F",
            ),
        )
        .contains("cp-settings-session"),
      "a signed-out visitor is offered a sign-in, not a sign-out",
    )
  }

  @Test
  fun `a catalog landing carries the same GitHub login control as the front door`() {
    val auth = ServeWeb.GitHubAuthStatus(loginHref = "/auth/github/start?return=%2F")
    // A top-level site's landing is its front door, so it carries the sign-in control.
    val landing =
      ServeWeb.landingPage(moduleLabel, previews, token, isPublic = true, githubAuth = auth)
    assertTrue(
      landing.contains("class=\"cp-gh-auth\" href=\"/auth/github/start?return=%2F\""),
      "an unsigned catalog landing links to GitHub sign-in",
    )
    assertTrue(
      landing.indexOf("<header class=\"cp-site-header\">") <
        landing.indexOf("> Sign in with GitHub</a>") &&
        landing.indexOf("> Sign in with GitHub</a>") < landing.indexOf("</header>"),
      "the landing's GitHub action is in the header",
    )

    val signed =
      ServeWeb.landingPage(
        moduleLabel,
        previews,
        token,
        isPublic = true,
        githubAuth = auth.copy(login = "yschimke"),
      )
    assertTrue(signed.contains("Signed in as yschimke"), "…and reports who is signed in")

    // Catalog mode drops the live lane whole — hover-live included — so there is nothing behind a
    // sign-in there, exactly as on every other page that renders this control.
    val catalogMode =
      ServeWeb.landingPage(
        moduleLabel,
        previews,
        token,
        isPublic = true,
        githubAuth = auth,
        componentBrowser = true,
      )
    assertFalse(
      catalogMode.contains("cp-gh-auth"),
      "Catalog mode offers no sign-in, because it unlocks nothing there",
    )

    // Unconfigured auth is unchanged: no control, no empty header slot.
    assertFalse(
      ServeWeb.landingPage(moduleLabel, previews, token, isPublic = true).contains("cp-gh-auth"),
      "a box with no GitHub auth renders no sign-in control",
    )
  }

  @Test
  fun `the login control names the lane the sign-in actually unlocks`() {
    val auth = ServeWeb.GitHubAuthStatus(loginHref = "/auth/github/start?return=%2F")
    // Live is the broad case — the front door's wording, and a catalog that streams. It names no
    // repository, because being signed in IS the whole gate (wear-m3-catalog#68).
    val live =
      ServeWeb.landingPage(moduleLabel, previews, token, isPublic = true, githubAuth = auth)
    assertTrue(
      live.contains("title=\"Live previews require a GitHub sign-in\""),
      "the live lane's gate is the sign-in itself",
    )
    assertFalse(
      live.substringAfter("cp-gh-auth").substringBefore("</a>").contains("access to"),
      "…so the live wording names no repository",
    )

    // A catalog with no live lane but a reachable playground: the control is still worth showing,
    // but repository access is that lane's real gate, so promising Live would be false twice over.
    val playground =
      ServeWeb.landingPage(
        moduleLabel,
        previews,
        token,
        isPublic = true,
        githubAuth =
          auth.copy(
            lane = ServeWeb.GatedLane.PLAYGROUND,
            accessRepository = "yschimke/compose-ai-tools",
          ),
      )
    assertTrue(
      playground.contains(
        "title=\"The playground requires a GitHub sign-in with access to " +
          "yschimke/compose-ai-tools\""
      ),
      "a playground-only catalog names the playground and its repository gate",
    )
    assertFalse(
      playground.contains("Live previews require"),
      "…and never promises a Live lane the catalog does not have",
    )

    // The allowlist reshapes either sentence: it narrows who may sign in at all, which neither
    // lane's own gate does.
    assertTrue(
      ServeWeb.landingPage(
          moduleLabel,
          previews,
          token,
          isPublic = true,
          githubAuth =
            auth.copy(
              lane = ServeWeb.GatedLane.PLAYGROUND,
              accessRepository = "yschimke/compose-ai-tools",
              restrictedToAllowedUsers = true,
            ),
        )
        .contains(
          "title=\"Playground access is limited to configured GitHub users with access to " +
            "yschimke/compose-ai-tools\""
        ),
      "an allowlisted box says so on the playground wording too",
    )
  }

  @Test
  fun `path-mounted pages keep links on the path and drop the session query param`() {
    // Served under /meshcore-mobile/: card, render and zip links carry the /meshcore-mobile prefix
    // and are token-only (the path, not &session=, carries the session).
    val landing =
      ServeWeb.landingPage(
        "meshcore-mobile",
        previews,
        token,
        sessionId = "meshcore-mobile",
        basePath = "/meshcore-mobile",
      )
    assertTrue(
      landing.contains("href=\"/meshcore-mobile/p/com.example.ButtonPreview?token=$token\""),
      "card link stays on the path",
    )
    assertTrue(
      landing.contains(
        "src=\"/meshcore-mobile/render/com.example.ButtonPreview.png?token=$token\""
      ),
      "render link stays on the path",
    )
    assertTrue(landing.contains("href=\"/meshcore-mobile/bundle.zip?token=$token\""), "zip on path")
    assertTrue(!landing.contains("&session="), "no &session= param in path mode")

    val viewer =
      ServeWeb.viewerPage(
        previews.first(),
        token,
        sessionId = "meshcore-mobile",
        basePath = "/meshcore-mobile",
      )
    assertTrue(
      viewer.contains("href=\"/meshcore-mobile/?token=$token\""),
      "back link stays on path",
    )
    // No same-session link carries &session= (the viewer JS still contains the literal "&session="
    // for the legacy query lane, so match the link pattern, not the bare substring).
    assertTrue(
      !viewer.contains("&session=meshcore-mobile"),
      "no &session= link param in path-mode viewer",
    )
    // The viewer JS recovers the base from the path so /render + /ws hit the same session.
    assertTrue(
      viewerSource().contains("location.pathname.replace"),
      "viewer derives its request base from the path",
    )
  }

  @Test
  fun `every page ends with the minimal footer carrying the running version`() {
    // The footer is chrome, not a per-page decision: `document` emits it for every surface, so a
    // new page cannot ship without it. One representative page per kind, public and token-gated.
    val pages =
      mapOf(
        "landing" to ServeWeb.landingPage(moduleLabel, previews, token, version = "1.2.3"),
        "landing (public)" to
          ServeWeb.landingPage(moduleLabel, previews, token, isPublic = true, version = "1.2.3"),
        "home" to ServeWeb.homeIndexPage(emptyList(), token, isPublic = true, version = "1.2.3"),
        "viewer" to ServeWeb.viewerPage(previews.first(), token, version = "1.2.3"),
        "comparison" to ServeWeb.comparisonPage(moduleLabel, previews, token, version = "1.2.3"),
        "not found" to ServeWeb.notFoundPage("gone", token, isPublic = true, version = "1.2.3"),
        "doc upload" to
          ServeWeb.docUploadPage(
            token,
            isPublic = true,
            ttlSeconds = 3600,
            urlUploadAllowed = false,
            version = "1.2.3",
          ),
      )
    for ((name, html) in pages) {
      assertTrue(html.contains("<footer class=\"cp-site-footer\">"), "$name has no footer")
      assertTrue(html.contains(">server v1.2.3<"), "$name footer omits the running version")
      assertTrue(
        html.indexOf("</main>") < html.indexOf("<footer class=\"cp-site-footer\">"),
        "$name footer must follow the page body",
      )
    }
  }

  @Test
  fun `no landing carries the about intro, public or not`() {
    for (landing in
      listOf(
        ServeWeb.landingPage(moduleLabel, previews, token, isPublic = true),
        ServeWeb.landingPage(moduleLabel, previews, token),
      )) {
      assertFalse(
        landing.contains("class=\"cp-about cp-disclosure\""),
        "the about disclosure is gone",
      )
      assertFalse(landing.contains("About this preview server"), "the about title is gone")
      assertFalse(
        landing.contains("How previews run and catalogs are trusted"),
        "the about hint is gone",
      )
      // The footer's own links survive its removal.
      assertTrue(landing.contains("href=\"/version\""), "expected a link to /version")
    }
  }

  @Test
  fun `static snapshot viewer disables server-render controls but a live session keeps them`() {
    // Catalog/bundle (canApplyOverrides defaults false), no Wasm: the controls that rebuild /render
    // can't take effect on a baked PNG, so they're disabled and a note explains why.
    val staticView = ServeWeb.viewerPage(previews.first(), token)
    assertTrue(staticView.contains("Pre-rendered snapshot"), "expected the static-snapshot note")
    // The note links out to how a viewer can enable the live overrides — run their own serve.
    assertTrue(
      staticView.contains("public-preview-server.md#running-one\">Enable a local preview server."),
      "snapshot note links to local preview server instructions",
    )
    assertTrue(staticView.contains("value=\"1.0\" disabled"), "font scale disabled")
    assertTrue(staticView.contains("id=\"cp-sizeMode\" disabled"), "component sizing disabled")
    assertFalse(staticView.contains("id=\"cp-device\""), "component device override omitted")
    assertFalse(staticView.contains("id=\"cp-orientation\""), "component orientation omitted")
    assertTrue(
      staticView.contains("id=\"cp-live\" tabindex=\"-1\" disabled"),
      "live transport radio disabled",
    )
    // With no live lane at all, the chip is itself disabled — and its tooltip says so rather than
    // inviting a click that would do nothing.
    assertTrue(
      staticView.contains("id=\"cp-live-toggle\"") &&
        Regex("id=\"cp-live-toggle\"[^>]* disabled>").containsMatchIn(staticView),
      "the live toggle is disabled on a pure static bundle",
    )
    assertTrue(
      staticView.contains("title=\"Static snapshot — this session has no live lane to switch to\""),
      "a chip with nothing to switch to does not promise a live preview",
    )
    // The tooltip is re-derived on every lane transition, since the chip's meaning inverts.
    assertTrue(
      viewerSource().contains("\"Interactive — click to return to the static snapshot\""),
      "the chip's tooltip tracks the lane rather than being written once by the server",
    )
    assertTrue(
      Regex("<select id=\"cp-theme\"[^>]*data-has-declared-themes=\"false\"[^>]* disabled>")
        .containsMatchIn(staticView),
      "Theme disabled without a renderer or Wasm app",
    )
    assertFalse(
      staticView.contains("id=\"cp-touchOverlay\""),
      "no live stream ⇒ the live-only overlay toggles are omitted entirely, not left dead",
    )
    assertTrue(
      staticView.contains(">Light (Default)</option>") &&
        staticView.contains(">Dark (Default)</option>"),
      "the unified Theme selector always carries the two default modes",
    )

    // Static + Wasm: theme, font scale, and locale go LIVE (the in-browser app honours them), while
    // component sizing stays server-only.
    val wasmView =
      ServeWeb.viewerPage(
        previews.first { it.id.endsWith("CardPreview") },
        token,
        wasmSrc = "/wasm/compose-m3/?id=card-filled",
      )
    assertTrue(
      Regex("<select id=\"cp-theme\"[^>]*>").find(wasmView)?.value?.let {
        it.contains("data-has-declared-themes=\"false\"") && !it.contains(" disabled")
      } == true,
      "Theme is enabled with a Wasm app",
    )
    assertTrue(
      wasmView.contains("step=\"0.1\" value=\"1.0\">"),
      "font scale enabled with a Wasm app",
    )
    assertTrue(wasmView.contains("autocomplete=\"off\">"), "locale enabled with a Wasm app")
    // Locale is a datalist-backed input, not a fixed <select>: presets drop down, but any BCP-47
    // tag the server accepts can still be typed (the reviewer's arbitrary-locale case, e.g. en-GB).
    assertTrue(
      wasmView.contains("id=\"cp-localeTag\" type=\"text\" list=\"cp-localeTag-list\"") &&
        wasmView.contains("<datalist id=\"cp-localeTag-list\">") &&
        wasmView.contains("value=\"en-GB\""),
      "locale keeps free BCP-47 entry (datalist input with presets)",
    )
    assertTrue(
      wasmView.contains("public-preview-server.md#running-one\">Enable a local preview server."),
      "wasm-snapshot note also links to local preview server instructions",
    )
    assertTrue(wasmView.contains("id=\"cp-sizeMode\" disabled"), "sizing stays server-only")
    assertFalse(wasmView.contains("id=\"cp-device\""), "component device override stays omitted")
    assertFalse(wasmView.contains("id=\"cp-orientation\""), "component orientation stays omitted")
    // The Wasm override-patch builder forwards the honoured params (theme/font scale/locale) to the
    // running app (via postMessage / the initial `#…` fragment), not the iframe query.
    assertTrue(viewerSource().contains("\"fontScale=\""), "font scale forwarded to Wasm")
    assertTrue(viewerSource().contains("\"localeTag=\""), "locale forwarded to Wasm")
    // On a static snapshot, a wasm-honoured control change auto-enables Wasm. The signal is
    // `staticSnapshot`, not `live.disabled` (a live catalog leaves Live enabled).
    assertTrue(
      wasmView.contains("data-static-snapshot=\"true\""),
      "static-snapshot flag on the viewer",
    )
    assertTrue(
      viewerSource().contains("setMode(\"wasm\");"),
      "static-snapshot wasm controls auto-enable the in-browser tier",
    )

    // Trusted catalog served live + Wasm: snapshots stay static but Live is enabled, the case
    // `live.disabled` can't stand in for.
    val liveCatalogWasm =
      ServeWeb.viewerPage(
        previews.first { it.id.endsWith("CardPreview") },
        token,
        canApplyOverrides = false,
        hasLiveStream = true,
        wasmSrc = "/wasm/compose-m3/?id=card-filled",
      )
    assertTrue(
      liveCatalogWasm.contains("id=\"cp-live\" tabindex=\"-1\">"),
      "live catalog leaves the live transport radio enabled (not disabled)",
    )
    assertTrue(
      liveCatalogWasm.contains("data-static-snapshot=\"true\""),
      "live catalog still marks its snapshot lane static",
    )
    assertTrue(
      liveCatalogWasm.contains("Pre-rendered snapshot"),
      "live catalog keeps the static note",
    )
    assertTrue(
      liveCatalogWasm.contains("id=\"cp-sizeMode\" disabled"),
      "server-render-only sizing stays disabled on a live catalog's static snapshot",
    )
    // A live-stream session offers the overlay toggle enabled even on the static snapshot; ticking
    // it switches into Live (onOverlayChanged).
    assertTrue(
      liveCatalogWasm.contains("cp-overlays") &&
        liveCatalogWasm.contains("id=\"cp-touchOverlay\" type=\"checkbox\">"),
      "live stream offers the overlay toggles enabled from the static lane",
    )
    // The accessibility layer is drawn client-side from daemon a11y data, so it is gated on the
    // host advertising that data, not the live stream.
    assertFalse(
      liveCatalogWasm.contains("cp-inspect"),
      "inspection layers are gated on the data products, not on the live stream",
    )
    assertTrue(
      viewerSource()
        .contains("if (anyOverlayChecked() && live && !live.disabled) setMode(\"live\");"),
      "checking an overlay off the live lane enters Live Compose",
    )
    // Overlays are URL-owned state: collected by `overrides()` and listed in the owned params, so a
    // ticked box rides the page URL, export links and the stream's connect query. The list lives in
    // `serve-web/src/viewer/ownedParams.ts` (`viewerOwnedParams.test.ts`).
    assertTrue(
      viewerSource().contains("rules.ownsUrlParam(name)"),
      "overlays are URL-owned params, decided by the shared list rather than a second copy",
    )
    // The stream replays liveOverrides() on open so an overlay checked while connecting still
    // arrives.
    assertTrue(
      viewerSource().contains("sock.onopen = function () {"),
      "the live stream seeds the daemon with the current overrides once the socket opens",
    )

    // Trusted catalog served live with author knobs (canRenderOverrides): snapshots stay baked, but
    // knob controls render enabled and route edits to /render.
    val catalogKnobs =
      ServeWeb.viewerPage(
        knobPreview,
        token,
        sessionId = "compose-m3",
        canApplyOverrides = false,
        canRenderOverrides = true,
        hasSvgExport = true,
        hasScrollExport = true,
        hasLiveStream = true,
        trust = "branch:yschimke/compose-ai-tools@design-artifacts/compose-m3",
      )
    assertTrue(catalogKnobs.contains("cp-knobs"), "declared knobs render as a control list")
    assertTrue(
      catalogKnobs.contains("data-knob-key=\"label\"") &&
        catalogKnobs.contains("data-knob-key=\"iconColor\""),
      "each declared knob gets a labelled control",
    )
    assertTrue(
      catalogKnobs.contains("data-can-render-overrides=\"true\""),
      "the viewer is flagged as override-renderable",
    )
    // The knobs are ENABLED — a live control, not the disabled/informational form. The `label` knob
    // is a text input; assert it renders enabled (no trailing ` disabled`).
    assertTrue(
      catalogKnobs.contains(
        "data-knob-key=\"label\" data-knob-kind=\"string\" data-knob-initial=\"Filled\" " +
          "data-knob-default=\"Filled\" " +
          "value=\"Filled\">"
      ),
      "declared knobs are enabled on an override-renderable session",
    )
    assertTrue(
      catalogKnobs.contains("edit a value to re-render"),
      "the knob note invites editing rather than saying values are baked in",
    )
    // A dedicated knob handler (onKnobEdited) drives whichever transport is live. Matched without
    // its parameter list: existence is the contract.
    assertTrue(
      viewerSource().contains("function onKnobEdited(") &&
        viewerSource().contains("function knobRoute()"),
      "knob edits have a dedicated, transport-aware handler",
    )
    assertTrue(
      viewerSource().contains("setSnapshotLoading(true)") &&
        viewerSource().contains("data-reloading") &&
        assetText("serve.css").contains("cp-reload-spin"),
      "snapshot overrides fade the current preview and show a spinner while re-rendering",
    )
    assertTrue(
      viewerSource()
        .contains(
          "function cancelSnapshotLoading() {\n    snapshotGen++;\n    status.textContent = \"\";"
        ),
      "cancelling a snapshot render clears its stale rendering status",
    )
    // During Live, the WebSocket override map must carry knob values (liveOverrides()), or the
    // daemon resets edited knobs.
    assertTrue(
      viewerSource().contains("function liveOverrides()") &&
        viewerSource().contains("o[\"knob.\" + key]"),
      "the live-stream override map includes the declared knob values",
    )
    assertFalse(
      viewerSource().contains("setOverrides\", overrides: overrides()"),
      "live-stream setOverrides sends liveOverrides() (knobs included), not the display-only map",
    )
    // With canRenderOverrides, display controls render enabled in the static snapshot and edits
    // re-point /render.
    assertTrue(
      viewerSource().contains("function syncServerControls()"),
      "the viewer has a syncServerControls() that keeps the display controls in sync",
    )
    assertTrue(
      viewerSource().contains("syncServerControls();"),
      "syncServerControls() is invoked on every mode transition",
    )
    assertTrue(
      viewerSource().contains("!staticSnapshot || canRenderOverrides || !!(live && live.checked)"),
      "display controls are live whenever the server can render an override (on-demand or streaming)",
    )
    // The server-render controls render ENABLED in the baked markup (canRenderOverrides), not
    // disabled-until-live: size and locale take effect immediately via on-demand /render.
    assertTrue(
      catalogKnobs.contains("id=\"cp-sizeMode\">") &&
        !catalogKnobs.contains("id=\"cp-device\"") &&
        !catalogKnobs.contains("id=\"cp-orientation\"") &&
        !catalogKnobs.contains(
          "id=\"cp-localeTag\" type=\"text\" list=\"cp-localeTag-list\" placeholder=\"e.g. en-GB, zh-Hant-TW\" autocomplete=\"off\" disabled"
        ),
      "component display controls render enabled without device overrides on an on-demand catalog",
    )
    // A plain static bundle (no daemon) still shows the knobs as DISABLED, informational controls.
    val staticKnobs = ServeWeb.viewerPage(knobPreview, token)
    assertTrue(
      staticKnobs.contains(
        "data-knob-key=\"label\" data-knob-kind=\"string\" data-knob-initial=\"Filled\" " +
          "data-knob-default=\"Filled\" " +
          "value=\"Filled\" disabled"
      ),
      "a plain static bundle leaves declared knobs disabled",
    )
    assertTrue(
      staticKnobs.contains("static bundle, values are baked in"),
      "a plain static bundle keeps the baked-in note",
    )

    // A static catalog whose only interactive lane is Wasm (no daemon re-render, wasmSrc present).
    // The wasm tier seeds `catalogOverride*` from `knob.<key>`, so knob controls are enabled and
    // drive the iframe.
    val wasmKnobs =
      ServeWeb.viewerPage(
        knobPreview,
        token,
        sessionId = "compose-m3",
        canApplyOverrides = false,
        canRenderOverrides = false,
        wasmSrc = "/wasm/compose-m3/?id=button-filled",
        wasmSameOrigin = true,
      )
    assertTrue(
      wasmKnobs.contains(
        "data-knob-key=\"label\" data-knob-kind=\"string\" data-knob-initial=\"Filled\" " +
          "data-knob-default=\"Filled\" " +
          "value=\"Filled\">"
      ),
      "a wasm-backed published catalog enables the declared knob controls (no trailing disabled)",
    )
    assertTrue(
      wasmKnobs.contains("apply it in the browser (Wasm)"),
      "the knob note invites in-browser editing when only the Wasm lane is available",
    )
    // For a wasm-only session the knob handler posts the patch to the iframe, or auto-enables Wasm.
    assertTrue(
      viewerSource().contains("function onKnobEdited(") &&
        viewerSource().contains("wasmFrame!.contentWindow.postMessage(wasmOverridePatch()"),
      "a knob edit routes to the Wasm iframe when that tier is active",
    )
    // wasmOverridePatch() carries changed knobs into the iframe fragment / postMessage.
    assertTrue(
      viewerSource().contains("function wasmOverridePatch()") &&
        viewerSourceFlat().contains("parts.push( \"knob.\" + encodeURIComponent(key)"),
      "the wasm override patch includes the author-declared knob values",
    )
    val viewerScript = viewerSource()
    assertTrue(
      viewerScript.contains("src += \"&theme=\" + encodeURIComponent(uiMode.value)") &&
        viewerScript.contains("if (rcWasmActive())") &&
        viewerScript.contains("(id === \"uiMode\" && onRcWasm)"),
      "the RC Wasm lane forwards Day/Night and keeps that control enabled while active",
    )
    // Knob controls hydrate from `knob.<key>` URL params on load, so a deep link renders the
    // override in every transport, including Wasm (whose patch is built from control state).
    assertTrue(
      viewerSource().contains("q.get(\"knob.\" + key)"),
      "the viewer hydrates declared knob controls from the URL's knob.<key> params",
    )

    // Every viewer offers a PNG URL row (copy + download); SVG-capable sessions add an SVG row.
    // URLs are built client-side from location.origin with current overrides. The bar is always
    // visible, not a disclosure.
    assertTrue(
      catalogKnobs.contains("<div class=\"cp-export\" aria-label=\"Export the current view\">") &&
        !catalogKnobs.contains("cp-export cp-disclosure"),
      "the export bar is shown open, not behind a summary",
    )
    assertTrue(
      catalogKnobs.contains("id=\"cp-url-png\"") && catalogKnobs.contains("id=\"cp-dl-png\""),
      "the PNG group has a URL field and a download link",
    )
    assertTrue(
      catalogKnobs.contains("id=\"cp-url-svg\"") && catalogKnobs.contains("id=\"cp-dl-svg\""),
      "an SVG-exporting session also offers an SVG group",
    )
    // Next to Download, a one-click "Copy PNG"/"Copy SVG" button that copies the rendered artefact
    // itself as clipboard text (PNG as a base64 data: URI, SVG markup verbatim) via .cp-copyimg.
    assertTrue(
      catalogKnobs.contains("class=\"cp-copyimg\"") &&
        catalogKnobs.contains("data-copyimg-ext=\".png\"") &&
        catalogKnobs.contains("data-copyimg-ext=\".svg\""),
      "each URL row has a Copy PNG / Copy SVG button that copies the artefact as text",
    )
    assertTrue(
      viewerSource().contains("readAsDataURL") &&
        viewerSource().contains("navigator.clipboard.writeText"),
      "the Copy PNG/SVG handler fetches the render and writes it to the clipboard as text",
    )
    // Copy PNG puts real image/png bytes on the clipboard where supported (data: URI is the
    // fallback). The blob is a promise because Safari builds the ClipboardItem synchronously in the
    // click.
    assertTrue(
      viewerSource().contains("new ClipboardItem({ \"image/png\": pngBlob })"),
      "Copy PNG writes image/png to the clipboard so it pastes as a picture",
    )
    // The prefilled report follows on-screen overrides (render URL and locator identity) and never
    // carries the session token into a public issue.
    val refreshReportLinkSource =
      viewerSource()
        .substringAfter("function refreshReportLink()")
        .substringBefore("function stripToken(")
    assertTrue(
      viewerSource().contains("function refreshReportLink()") &&
        refreshReportLinkSource.contains("render: stripToken(field.value)") &&
        refreshReportLinkSource.contains("overrides: renderOverrides()"),
      "the report body is re-substituted at the current render and the overrides that made it",
    )
    // ...via the shared body writer (a plain assignment would undo the classification and scope
    // controls), always into an input value, never an href.
    assertTrue(
      refreshReportLinkSource.contains("reportBody.set({") &&
        !refreshReportLinkSource.contains("body.value =") &&
        !refreshReportLinkSource.contains(".href = "),
      "the report prefill goes into a form input, not a navigation sink",
    )
    // ...and only while the frame on screen is the one the controls asked for; the decision lives
    // in `viewer/reportFrame.ts` (tested there). This pins that the gate is applied.
    assertTrue(
      viewerSourceFlat()
        .contains(
          "if ( mayName && !rules.reportFollowsDisplayedFrame( renderUrl(snapshotExt), " +
            "img.getAttribute(\"data-cp-src\"), ) ) return;"
        ),
      "the report is only recomposed while the landed frame is the requested one",
    )
    // ...and the locator is withheld on lanes whose pixels are not the stage image (Live, Wasm, RC
    // players).
    assertTrue(
      viewerSourceFlat()
        .contains(
          "var mayName = rules.reportMayCarryLocator( " +
            "root.getAttribute(\"data-mode\") || \"snapshot\", );"
        ) && viewerSourceFlat().contains("omitLocator: !mayName,"),
      "an interactive lane files a report with no locator in it",
    )
    // The URL is copied by a plainly named button; the field stays in the DOM, off-screen.
    assertTrue(
      catalogKnobs.contains("class=\"cp-copyurl\" data-copyurl-target=\"cp-url-png\"") &&
        catalogKnobs.contains(">Copy link</button>"),
      "each format offers a Copy link button that says what it does",
    )
    assertTrue(
      viewerSource().contains("querySelectorAll<HTMLElement>(\".cp-copyurl\")") &&
        viewerSource().contains("data-copyurl-target"),
      "the Copy link handler copies the field it targets",
    )
    assertTrue(
      assetText("serve.css").contains(".cp-url { position: absolute;"),
      "the URL field is taken out of the flow rather than given a third of the line",
    )
    assertTrue(
      // Matched without the parameter list — the helper's existence is the contract, not its
      // arity, which grew a `skipUrlSync` opt-out for the wasm auto-enable path.
      viewerSource().contains("function refreshLinks(") &&
        viewerSource().contains("location.origin"),
      "the links are rebuilt from location.origin as the controls change",
    )
    assertTrue(
      viewerSource().contains(".cp-revision, .cp-pinned-current") &&
        viewerSource().contains("destination.searchParams.set(name, value)"),
      "revision links follow a theme selected after the page was rendered",
    )
    // A plain static bundle can't export SVG, so it shows the PNG row but not the SVG one.
    assertTrue(staticKnobs.contains("id=\"cp-url-png\""), "PNG URL row shows on any viewer")
    assertFalse(
      staticKnobs.contains("id=\"cp-url-svg\""),
      "no SVG URL row when the session can't export SVG",
    )

    // Live daemon session (canApplyOverrides = true): everything enabled, no note.
    val liveView = ServeWeb.viewerPage(previews.first(), token, canApplyOverrides = true)
    assertTrue(!liveView.contains("Pre-rendered snapshot"), "no static note on a live session")
    assertTrue(!liveView.contains("value=\"1.0\" disabled"), "font scale enabled on a live session")
    assertTrue(liveView.contains("id=\"cp-sizeMode\">"), "sizing enabled on a live session")
    assertFalse(liveView.contains("id=\"cp-device\""), "device remains omitted on a live component")
    assertTrue(liveView.contains("data-static-snapshot=\"false\""), "live session is not static")
  }

  @Test
  fun `declared themes render an App theme selector routed through the daemon`() {
    val themes =
      listOf(
        ServeTheme("Brand Light", "com.example.BrandLightThemeCatalog", group = "Brand"),
        ServeTheme("Brand Dark", "com.example.BrandDarkThemeCatalog", group = "Brand"),
        ServeTheme("High Contrast", "com.example.HighContrastThemeCatalog"),
      )
    val view =
      ServeWeb.viewerPage(
        previews.first(),
        token,
        canApplyOverrides = true,
        declaredThemes = themes,
      )
    // One selector carries the default day/night modes and each provider FQN with its human name.
    assertTrue(
      view.contains("id=\"cp-theme\"") &&
        view.contains(">Light (Default)</option>") &&
        view.contains(">Dark (Default)</option>"),
      "declared themes share one selector with the two default modes",
    )
    assertTrue(
      view.contains(
        "<option value=\"theme:com.example.BrandLightThemeCatalog\" data-theme-mode=\"light\">Brand Light</option>"
      ),
      "each declared theme is an option keyed by its provider FQN",
    )
    // `@ThemeCatalog(group=…)` buckets themes into <optgroup>s; an ungrouped theme stays flat.
    assertTrue(view.contains("<optgroup label=\"Brand\">"), "grouped themes get an <optgroup>")
    assertTrue(
      view.contains(
        "<option value=\"theme:com.example.HighContrastThemeCatalog\">High Contrast</option>"
      ),
      "an ungrouped theme is a flat option",
    )
    // Enabled on a daemon host and routed like a knob (the daemon path, never the wasm
    // auto-enable).
    assertFalse(
      view.contains("data-has-declared-themes=\"true\" disabled"),
      "the theme selector is enabled on a daemon-backed host",
    )
    assertTrue(
      viewerSource().contains("if (chosenThemeProvider()) onKnobChanged();"),
      "declared choices route through the daemon (knob) path",
    )
    assertTrue(
      viewerSource().contains("if (tp) o.themeProvider = tp;"),
      "a chosen theme is appended to the /render URL as themeProvider",
    )
    // Every key is percent-encoded too: author-declared `knob.<key>` / `rc.<name>` strings
    // containing `&`, `=` or `%` would otherwise split the URL and the render would differ from the
    // locator.
    assertTrue(
      viewerSourceFlat()
        .contains("parts.push(encodeURIComponent(k) + \"=\" + encodeURIComponent(o[k]));"),
      "dynamic override keys are encoded into the /render query, not appended verbatim",
    )

    val discoveredNightPreview =
      ServeWeb.viewerPage(
        ServePreview("com.example.PlainPreview", "Plain preview", uiMode = 0x20),
        token,
        canApplyOverrides = true,
        declaredThemes = themes,
      )
    assertTrue(
      discoveredNightPreview.contains("<option value=\"dark\" selected>Dark (Default)</option>"),
      "a preview discovered with night uiMode selects its actual baked default",
    )
    assertFalse(
      discoveredNightPreview.contains("<option value=\"light\" selected>Light (Default)</option>"),
      "a night preview does not fall back to the ID-based day heuristic",
    )

    // A static bundle can't load a provider, so the selector renders disabled (informational).
    val staticThemed = ServeWeb.viewerPage(previews.first(), token, declaredThemes = themes)
    assertTrue(
      Regex("<select id=\"cp-theme\"[^>]*data-has-declared-themes=\"true\"[^>]* disabled>")
        .containsMatchIn(staticThemed) &&
        staticThemed.contains(
          "value=\"theme:com.example.BrandLightThemeCatalog\" data-theme-mode=\"light\" disabled"
        ),
      "the theme selector is disabled on a static bundle (no daemon to apply it)",
    )
  }

  @Test
  fun `trust badge renders trusted and unverified variants and is absent for a live module`() {
    val trusted = ServeWeb.landingPage(moduleLabel, previews, token, trust = "branch:repo@b")
    assertFalse(trusted.contains("class=\"cp-badge"), "trusted catalogs carry no badge")

    val unverified = ServeWeb.viewerPage(previews.first(), token, trust = "unverified")
    assertTrue(unverified.contains("cp-badge--unverified"), "expected an unverified badge")

    // A live daemon-backed module carries no trust verdict → no badge element.
    assertTrue(!ServeWeb.landingPage(moduleLabel, previews, token).contains("class=\"cp-badge"))
  }

  @Test
  fun `degrade banner explains why a session is snapshot-only and is absent when live`() {
    val degraded = listOf(ServeDegradation.catalogBakedOnly())

    // The catalog-level reason renders as a banner on both landing and viewer (checked on the
    // `class="cp-degrade"` section, since the CSS always defines the class).
    val landing = ServeWeb.landingPage(moduleLabel, previews, token, degradations = degraded)
    assertTrue(landing.contains("class=\"cp-degrade\""), "expected a degradation banner")
    assertTrue(landing.contains("publishes no live bundle"), "expected the baked-only reason text")

    val viewer = ServeWeb.viewerPage(previews.first(), token, degradations = degraded)
    assertTrue(viewer.contains("class=\"cp-degrade\""), "expected the banner on the viewer too")
    assertTrue(viewer.contains("publishes no live bundle"))

    // A fully-live session (no degradations, the default) renders no banner section.
    assertTrue(
      !ServeWeb.landingPage(moduleLabel, previews, token).contains("class=\"cp-degrade\""),
      "a live/undegraded session must not render a banner",
    )
    assertTrue(!ServeWeb.viewerPage(previews.first(), token).contains("class=\"cp-degrade\""))
  }

  @Test
  fun `theme toggle shows only when the grid has light-dark pairs to swap`() {
    // A theme-paired catalog: each light/dark pair collapses to one swap card and the toggle shows.
    val paired =
      listOf(
        ServePreview("button__ideal__default__light", "Button (light)"),
        ServePreview("button__ideal__default__dark", "Button (dark)"),
        ServePreview("switch__ideal__default__light", "Switch (light)"),
        ServePreview("switch__ideal__default__dark", "Switch (dark)"),
      )
    val pairedHtml = ServeWeb.landingPage("compose-m3", paired, token)
    assertTrue(
      pairedHtml.contains("id=\"cp-catalog-theme-bar\""),
      "a theme-paired catalog shows the Light/Dark toggle",
    )
    // Two components × two themes → two swap cards, each carrying both themes' render.
    assertEquals(
      2,
      Regex("class=\"cp-card\"[^>]*data-swap=\"1\"").findAll(pairedHtml).count(),
      "each paired component is one swap card (two components → two cards, not four)",
    )
    assertTrue(
      pairedHtml.contains("data-l-src=") && pairedHtml.contains("data-d-src="),
      "a swap card carries both the light and dark baked render",
    )

    // An app catalog whose theme showcases are distinct components: nothing pairs, so no toggle.
    // Keyed off whether any component is baked in both themes, never the system name.
    val appCatalog =
      listOf(
        ServePreview("theme-meshcore-light__ideal__default__light__compact", "MeshCore light"),
        ServePreview("theme-meshcore-dark__ideal__default__dark__compact", "MeshCore dark"),
        ServePreview("contactlist-many__ideal__default__compact", "Contacts"),
        ServePreview("scanner-blefew__ideal__default__compact", "Scanner"),
        ServePreview("device-lowbattery__ideal__default__compact", "Device"),
        ServePreview("tcpconnectpanel-idle__ideal__default__compact", "TCP connect"),
      )
    assertFalse(
      ServeWeb.landingPage("meshcore-mobile", appCatalog, token, basePath = "/meshcore-mobile")
        .contains("id=\"cp-catalog-theme-bar\""),
      "an app catalog with no light/dark pairs shows no Light/Dark toggle",
    )

    // A one-sided themed catalog (dark only) shows no toggle.
    val darkOnly =
      listOf(
        ServePreview("a__ideal__default__dark", "A"),
        ServePreview("b__ideal__default__dark", "B"),
      )
    assertFalse(
      ServeWeb.landingPage("x", darkOnly, token).contains("id=\"cp-catalog-theme-bar\""),
      "a catalog with only one theme side shows no toggle",
    )
  }

  @Test
  fun `grouping strips only the theme segment, keeping a non-theme light-dark state segment`() {
    // A flattened id can carry a `light`/`dark` state segment before the theme segment
    // (`toggle__<state>__default__<theme>`). Only the last one (the theme) may be stripped for the
    // grouping key.
    val stateful =
      listOf(
        ServePreview("toggle__dark__default__light", "Toggle · dark state (light)"),
        ServePreview("toggle__dark__default__dark", "Toggle · dark state (dark)"),
        ServePreview("toggle__light__default__light", "Toggle · light state (light)"),
        ServePreview("toggle__light__default__dark", "Toggle · light state (dark)"),
      )
    val html = ServeWeb.landingPage("compose-m3", stateful, token)
    // Two distinct components (dark-state, light-state), each a swap pair → two swap cards, not
    // one.
    assertEquals(
      2,
      Regex("class=\"cp-card\"[^>]*data-swap=\"1\"").findAll(html).count(),
      "the dark-state and light-state toggles stay separate swap cards",
    )
    // Both states survive: each state's light+dark ids appear as swap-card data (none dropped).
    for (id in
      listOf(
        "toggle__dark__default__light",
        "toggle__dark__default__dark",
        "toggle__light__default__light",
        "toggle__light__default__dark",
      )) {
      assertTrue(html.contains(id), "the $id variant must survive grouping, not be dropped")
    }
  }

  @Test
  fun `a font knob renders an autocompleting combobox, catalog names first then Google Fonts`() {
    val fontPreview =
      ServePreview(
        "button-filled__ideal__default__light",
        "Button · Filled (light)",
        overrides =
          listOf(
            PreviewOverrideDeclaration(
              key = "theme.font",
              type = PreviewOverrideType.STRING,
              default = PreviewOverrideValue.StringValue("Roboto Flex"),
              suggestions = listOf("Roboto Flex", "Google Sans Flex", "Lobster Two"),
              googleFonts = true,
            )
          ),
      )
    val view =
      ServeWeb.viewerPage(
        fontPreview,
        token,
        sessionId = "compose-m3",
        canApplyOverrides = false,
        canRenderOverrides = true,
      )
    // A font knob is a free-text `<input list>` bound to a `<datalist>` — a combobox, not a plain
    // text input — so any family is selectable while the field stays editable.
    assertTrue(
      view.contains("data-knob-key=\"theme.font\"") && view.contains("list=\"cp-dl-theme-font\""),
      "the font knob renders as an <input list> combobox",
    )
    assertTrue(
      view.contains("<datalist id=\"cp-dl-theme-font\">"),
      "the font knob emits a matching <datalist>",
    )
    val datalist =
      view.substringAfter("<datalist id=\"cp-dl-theme-font\">").substringBefore("</datalist>")
    val robotoIdx = datalist.indexOf("<option value=\"Roboto Flex\">")
    val lobsterIdx = datalist.indexOf("<option value=\"Lobster Two\">")
    val interIdx = datalist.indexOf("<option value=\"Inter\">")
    // The declared @TypographyCatalog names come first, in order ("by default show the typography
    // catalog")…
    assertTrue(
      robotoIdx in 0 until lobsterIdx,
      "the declared suggestions render first, in declaration order",
    )
    // …then `googleFonts = true` splices the full fonts.google.com list after them, so an arbitrary
    // family (Inter) is offered — de-duplicated, so Roboto Flex / Lobster Two aren't repeated.
    assertTrue(
      interIdx > lobsterIdx,
      "the Google Fonts list follows the declared suggestions (an arbitrary family is offered)",
    )
    assertEquals(
      1,
      Regex("<option value=\"Roboto Flex\">").findAll(datalist).count(),
      "a declared name that's also a Google family isn't duplicated",
    )
  }

  /**
   * Compare every page golden against what `ServeWeb` renders now, reporting all drift in one
   * failure. The message names each drifted fixture with its first differing line, which
   * distinguishes a deliberate `ServeWeb` change (drift in the touched pages) from goldens
   * regenerated before merging `main` (drift across unrelated pages).
   */
  /**
   * The committed unfurl-card rasters still match what [ServeSocialCard] draws today, within a
   * tolerance (see [meanPixelDifference]).
   */
  private fun assertUnfurlCardsInSync(
    pagesDir: File,
    cards: List<Pair<String, ServeSocialCard.Card>>,
  ) {
    val problems = cards.mapNotNull { (name, card) ->
      val file = File(pagesDir, name)
      if (!file.isFile) return@mapNotNull "$name: missing"
      val committed = ImageIO.read(file) ?: return@mapNotNull "$name: not a readable PNG"
      val drawn = ImageIO.read(java.io.ByteArrayInputStream(card.bytes))!!
      val difference = meanPixelDifference(committed, drawn)
      if (difference <= UNFURL_CARD_TOLERANCE) null
      else
        "$name: differs from the drawn card (mean channel difference " +
          "${"%.2f".format(difference)} > $UNFURL_CARD_TOLERANCE)"
    }
    if (problems.isEmpty()) return
    fail(
      "the committed link-unfurl cards are out of sync with ServeSocialCard:\n  " +
        problems.joinToString("\n  ") +
        "\n\nRegenerate with UPDATE_SERVE_WEB_FIXTURES=true — and look at the result, because " +
        "these are what a shared link shows in Slack, iMessage and search results."
    )
  }

  private fun assertGoldensInSync(pagesDir: File, goldens: List<Pair<String, String>>) {
    // Every page loads at least one hashed asset, so a golden without the placeholder means
    // `stableAssetHrefs` no longer matches what `ServeWeb` emits; say so directly.
    val unnormalised =
      goldens
        .filterNot { (name, html) -> name in ASSETLESS_GOLDENS || STABLE_ASSET_PREFIX in html }
        .map { it.first }
    if (unnormalised.isNotEmpty()) {
      fail(
        "no `$STABLE_ASSET_PREFIX` href in: ${unnormalised.joinToString(", ")}.\n" +
          "ServeWeb's asset URLs no longer match the shape ServeWebFixtureTest normalises " +
          "(`$ASSET_HREF_PATTERN`). Update stableAssetHrefs to match ServeWebAssets.href, or the " +
          "goldens will start pinning real content hashes again and every asset edit will drift " +
          "every page."
      )
    }
    val missing = goldens.filterNot { (name, _) -> File(pagesDir, name).isFile }.map { it.first }
    val drifted =
      goldens
        .filter { (name, _) -> File(pagesDir, name).isFile }
        .mapNotNull { (name, rendered) ->
          val golden = File(pagesDir, name).readText()
          if (golden == rendered) null else name to firstDifference(golden, rendered)
        }
    if (missing.isEmpty() && drifted.isEmpty()) return

    val report = buildString {
      append("serve web page fixtures are out of sync with ServeWeb")
      append(" (${drifted.size} stale, ${missing.size} missing of ${goldens.size}).")
      if (missing.isNotEmpty()) append("\n  missing: ${missing.joinToString(", ")}")
      drifted.forEach { (name, where) -> append("\n  $name: $where") }
      append(
        "\n\nRegenerate with UPDATE_SERVE_WEB_FIXTURES=true — but read the list first. Drift across " +
          "pages this branch never touched usually means the goldens were regenerated before " +
          "merging main and main has since changed the markup, not that ServeWeb is broken; " +
          "re-merge and regenerate again rather than assuming the check is flaky."
      )
    }
    fail(report)
  }

  /**
   * Replace the cache-busting content hash in every asset href with a constant, like the fixed
   * [version]: the hash is noise in the goldens (the harness matches assets by basename) and
   * changes on every asset edit. The regex matches only the versioned form, so if `ServeWeb`
   * stopped emitting it [assertGoldensInSync] fails on the missing placeholder. Production still
   * serves and checks the real hash.
   */
  private fun stableAssetHrefs(html: String): String =
    ASSET_HREF_PATTERN.replace(html, STABLE_ASSET_PREFIX)

  /** `line N: golden … | rendered …` for the first line where the two texts diverge. */
  private fun firstDifference(golden: String, rendered: String): String {
    val a = golden.lines()
    val b = rendered.lines()
    val index = (0 until maxOf(a.size, b.size)).firstOrNull { a.getOrNull(it) != b.getOrNull(it) }
    if (index == null) return "differs only in trailing newline"
    fun show(line: String?) = line?.trim()?.take(120) ?: "<end of file>"
    return "line ${index + 1}: golden ${show(a.getOrNull(index))} | rendered " +
      show(b.getOrNull(index))
  }

  /**
   * A fixed, phone-shaped placeholder the harness serves for `/render/<id>.png` (no backend in CI),
   * so tiles have a realistic size. Deterministic and font-free (font rendering varies by host),
   * using the same shapes as [renderPlaceholderSvg].
   */
  // --- The link-unfurl card as a captured visual surface ----------------------------------------

  /**
   * The two unfurl card shapes ([ServeSocialCard]): the front door's (a shelf of catalogs) and a
   * single catalog landing's. Built from committed placeholder artwork for determinism;
   * [placeholderWatchPng] gives the front door's shelf heroes of differing aspect.
   */
  private fun socialCardFixtures(): List<Pair<String, ServeSocialCard.Card>> {
    val cards = ServeSocialCard()
    val phone = ServeHeroImages.Hero(placeholderPng(), "phone.png", "\"phone\"", 200, 420)
    val watch = ServeHeroImages.Hero(placeholderWatchPng(), "watch.png", "\"watch\"", 240, 240)
    val systems =
      listOf(
        ServeWeb.HomeSystem(
          system = "compose-m3",
          title = "Compose Material 3",
          subtitle = null,
          previewCount = 42,
          trust = null,
          heroPreviewId = null,
        ),
        ServeWeb.HomeSystem(
          system = "wear-m3",
          title = "Wear Compose Material 3",
          subtitle = null,
          previewCount = 18,
          trust = null,
          heroPreviewId = null,
        ),
      )
    return listOfNotNull(
      cards
        .cardFor(
          ServeSocialCard.Spec(
            title = ServeWeb.HOME_TITLE,
            subtitle = ServeWeb.homeCardSubtitle(systems),
            heroes = listOf(phone, watch),
          )
        )
        ?.let { "_social-card-home.png" to it },
      cards
        .cardFor(
          ServeSocialCard.Spec(
            title = "Wear Compose Material 3",
            subtitle = ServeWeb.catalogCardSubtitle(18),
            heroes = listOf(watch),
          )
        )
        ?.let { "_social-card-catalog.png" to it },
    )
  }

  /**
   * A page showing those cards at 1:1 so the harness screenshots them. Hand-written because the
   * card is a raster served off `/social/`, not a page. The frame is identical in both themes since
   * the card is always dark.
   */
  private fun socialCardPage(cards: List<Pair<String, ServeSocialCard.Card>>): String {
    val figures =
      cards.joinToString("\n") { (file, card) ->
        """
        <figure>
          <img src="$file" width="${card.width}" height="${card.height}" alt="Unfurl card">
          <figcaption>$file — ${card.width}×${card.height}</figcaption>
        </figure>
        """
          .trimIndent()
          .prependIndent("    ")
      }
    return """
    <!doctype html>
    <html lang="en">
      <head>
        <meta charset="utf-8">
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <title>Link unfurl cards — compose-preview</title>
        <style>
          body { margin: 0; padding: 32px; background: #6e6a75; color: #ffffff;
            font: 14px/1.4 system-ui, sans-serif; display: grid; gap: 32px; justify-items: start; }
          figure { margin: 0; display: grid; gap: 8px; }
          img { display: block; max-width: 100%; height: auto; border-radius: 12px; }
          figcaption { font-variant-numeric: tabular-nums; opacity: 0.85; }
        </style>
      </head>
      <body>
$figures
      </body>
    </html>
    """
      .trimIndent()
  }

  /**
   * Mean absolute per-channel difference, or [Double.MAX_VALUE] when sizes differ. Card goldens are
   * rasterized text, and font hinting differs between JDK builds, so they are compared with a
   * tolerance; real layout or palette changes exceed it by orders of magnitude.
   */
  private fun meanPixelDifference(a: BufferedImage, b: BufferedImage): Double {
    if (a.width != b.width || a.height != b.height) return Double.MAX_VALUE
    var total = 0L
    for (y in 0 until a.height) {
      for (x in 0 until a.width) {
        val p = a.getRGB(x, y)
        val q = b.getRGB(x, y)
        for (shift in intArrayOf(16, 8, 0)) {
          total += Math.abs(((p shr shift) and 0xff) - ((q shr shift) and 0xff)).toLong()
        }
      }
    }
    return total.toDouble() / (a.width.toLong() * a.height * 3)
  }

  private fun placeholderPng(): ByteArray {
    val file = File.createTempFile("placeholder", ".png")
    try {
      writePlaceholderPng(file)
      return file.readBytes()
    } finally {
      file.delete()
    }
  }

  /** A square, watch-shaped companion to [writePlaceholderPng], in the same flat M3 style. */
  private fun placeholderWatchPng(): ByteArray {
    val size = 240
    val img = BufferedImage(size, size, BufferedImage.TYPE_INT_RGB)
    val g = img.createGraphics()
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
    g.color = Color(0x14, 0x12, 0x18)
    g.fillRect(0, 0, size, size)
    g.color = Color(0x21, 0x1F, 0x26)
    g.fill(Ellipse2D.Float(8f, 8f, 224f, 224f))
    g.color = Color(0xD0, 0xBC, 0xFF)
    g.fill(RoundRectangle2D.Float(70f, 60f, 100f, 18f, 18f, 18f))
    g.color = Color(0xCA, 0xC4, 0xD0)
    g.fill(RoundRectangle2D.Float(56f, 96f, 128f, 14f, 14f, 14f))
    g.fill(RoundRectangle2D.Float(66f, 122f, 108f, 14f, 14f, 14f))
    g.color = Color(0x4F, 0x37, 0x8B)
    g.fill(RoundRectangle2D.Float(78f, 154f, 84f, 32f, 32f, 32f))
    g.dispose()
    val out = java.io.ByteArrayOutputStream()
    ImageIO.write(img, "png", out)
    return out.toByteArray()
  }

  private fun writePlaceholderPng(file: File) {
    val w = 200
    val h = 420
    val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
    val g = img.createGraphics()
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
    g.paint =
      GradientPaint(0f, 0f, Color(0xCF, 0xD8, 0xFF), 0f, h.toFloat(), Color(0x9A, 0xA7, 0xE6))
    g.fillRect(0, 0, w, h)
    // App bar, title line, hero card, two list rows, FAB — mirrors the SVG placeholder's layout,
    // scaled to this tile's 200×420 (the SVG is 200×400).
    g.color = Color(0x67, 0x50, 0xA4)
    g.fill(RoundRectangle2D.Float(18f, 26f, 164f, 28f, 28f, 28f))
    g.color = Color(0x79, 0x74, 0x7E)
    g.fill(RoundRectangle2D.Float(18f, 76f, 112f, 14f, 14f, 14f))
    g.color = Color(0xE8, 0xDE, 0xF8)
    g.fill(RoundRectangle2D.Float(18f, 104f, 164f, 90f, 32f, 32f))
    g.color = Color(0xFF, 0xFF, 0xFF)
    g.fill(RoundRectangle2D.Float(18f, 212f, 164f, 60f, 32f, 32f))
    g.fill(RoundRectangle2D.Float(18f, 292f, 164f, 60f, 32f, 32f))
    g.color = Color(0x67, 0x50, 0xA4)
    g.fill(Ellipse2D.Float(88f, 374f, 24f, 24f))
    g.dispose()
    ImageIO.write(img, "png", file)
  }

  /** Deterministic vector counterpart used by the format-comparison page fixture. */
  private fun renderPlaceholderSvg(): String =
    """
    <svg xmlns="http://www.w3.org/2000/svg" width="200" height="400" viewBox="0 0 200 400">
      <rect width="200" height="400" rx="24" fill="#f2f0f7"/>
      <rect x="18" y="24" width="164" height="28" rx="14" fill="#6750a4"/>
      <rect x="18" y="72" width="112" height="14" rx="7" fill="#79747e"/>
      <rect x="18" y="98" width="164" height="86" rx="16" fill="#e8def8"/>
      <rect x="18" y="202" width="164" height="58" rx="16" fill="#ffffff"/>
      <rect x="18" y="278" width="164" height="58" rx="16" fill="#ffffff"/>
      <circle cx="100" cy="368" r="12" fill="#6750a4"/>
    </svg>
    """
      .trimIndent()

  private companion object {
    /** What the goldens carry in place of a real content hash. See [stableAssetHrefs]. */
    const val STABLE_ASSET_PREFIX = "/assets/serve/fixture/"

    /**
     * Goldens that legitimately load no hashed assets, exempt from the normalisation check.
     * Explicit so that check still catches a page that stops matching. Only the unfurl-card frame
     * (see [socialCardPage]).
     */
    val ASSETLESS_GOLDENS = setOf("serve-social-card.html")

    /**
     * Mean per-channel difference (0..255) tolerated between a committed and freshly drawn unfurl
     * card; absorbs JDK font-rasterization noise, far below a real change.
     */
    const val UNFURL_CARD_TOLERANCE = 1.5

    /**
     * The exact shape [ServeWebAssets.href] builds (`/assets/serve/<size-hex>-<16 hex>/`). Narrow
     * on purpose so a scheme change is noticed rather than silently normalised.
     */
    val ASSET_HREF_PATTERN = Regex("""/assets/serve/[0-9a-f]+-[0-9a-f]{16}/""")
  }
}
