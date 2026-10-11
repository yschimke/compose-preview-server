package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.uibuilder.protocol.AnimationStateV1
import ee.schimke.composeai.uibuilder.protocol.BackgroundModifierV1
import ee.schimke.composeai.uibuilder.protocol.BooleanValueV1
import ee.schimke.composeai.uibuilder.protocol.CatalogReferenceV1
import ee.schimke.composeai.uibuilder.protocol.CodeCapabilityV1
import ee.schimke.composeai.uibuilder.protocol.ColorTokenValueV1
import ee.schimke.composeai.uibuilder.protocol.ColorValueV1
import ee.schimke.composeai.uibuilder.protocol.ComponentCapabilityV1
import ee.schimke.composeai.uibuilder.protocol.DesignDocumentV1
import ee.schimke.composeai.uibuilder.protocol.DesignEnvironmentV1
import ee.schimke.composeai.uibuilder.protocol.DesignNodeV1
import ee.schimke.composeai.uibuilder.protocol.LayoutDirectionV1
import ee.schimke.composeai.uibuilder.protocol.PropertyCapabilityV1
import ee.schimke.composeai.uibuilder.protocol.StringValueV1
import ee.schimke.composeai.uibuilder.protocol.ThemeV1
import ee.schimke.composeai.uibuilder.protocol.WasmAdapterStatusV1
import ee.schimke.composeai.uibuilder.protocol.WasmCapabilityV1
import ee.schimke.composeai.uibuilder.protocol.WindowPostureV1
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.io.TempDir

/** The pure halves of compose-preview-server#1255: the contrast arithmetic and the review store. */
class UiBuilderDesignReviewUnitTest {
  @TempDir lateinit var directory: Path

  @Test
  fun `contrast is the WCAG ratio, with alpha composited over the background`() {
    val black = UiBuilderAccessibilityCheck.parseHex("#000000")!!
    val white = UiBuilderAccessibilityCheck.parseHex("#FFFFFF")!!
    assertEquals(21.0, UiBuilderAccessibilityCheck.contrastRatio(black, white), 0.01)
    assertEquals(1.0, UiBuilderAccessibilityCheck.contrastRatio(white, white), 0.01)
    // Material's onSurfaceVariant on surface clears 4.5:1.
    assertTrue(
      UiBuilderAccessibilityCheck.contrastRatio(
        UiBuilderAccessibilityCheck.parseHex("#49454F")!!,
        UiBuilderAccessibilityCheck.parseHex("#FEF7FF")!!,
      ) > 4.5
    )
    // Fully transparent black over white is white: no contrast at all.
    assertEquals(
      1.0,
      UiBuilderAccessibilityCheck.contrastRatio(
        UiBuilderAccessibilityCheck.parseHex("#00000000")!!,
        white,
      ),
      0.01,
    )
    assertNull(UiBuilderAccessibilityCheck.parseHex("primary"))
  }

  @Test
  fun `a theme role is resolved against the baseline and reported as a warning, not an error`() {
    val document =
      document(
        "panel" to
          DesignNodeV1(
            id = "panel",
            componentId = "layout/box",
            modifiers = listOf(BackgroundModifierV1(ColorTokenValueV1("primary"), null)),
            slots = mapOf("content" to listOf("label")),
          ),
        "label" to
          DesignNodeV1(
            id = "label",
            componentId = "m3/text",
            properties =
              mapOf("text" to StringValueV1("On primary?"), "color" to ColorValueV1("#6750A4")),
          ),
      )
    val finding = UiBuilderAccessibilityCheck.check(document, emptyMap()).single()
    assertEquals(UiBuilderAccessibilityCheck.CODE_CONTRAST, finding.code)
    assertEquals(SEVERITY_WARNING, finding.severity)
    assertTrue(finding.message.contains("Material 3 baseline"), finding.message)
  }

  @Test
  fun `a hard-coded colour on a theme role is checked in both themes, naming the one that fails`() {
    // `#5F6368` on `surface` clears 4.5:1 against the light baseline (about 5.7:1) and falls to
    // about 3.1:1 against the dark one: the title an author checked in light and shipped
    // unreadable.
    fun titleOnSurface(theme: ThemeV1) =
      document(
          "panel" to
            DesignNodeV1(
              id = "panel",
              componentId = "layout/box",
              modifiers = listOf(BackgroundModifierV1(ColorTokenValueV1("surface"), null)),
              slots = mapOf("content" to listOf("title")),
            ),
          "title" to
            DesignNodeV1(
              id = "title",
              componentId = "m3/text",
              properties =
                mapOf("text" to StringValueV1("Schedule"), "color" to ColorValueV1("#5F6368")),
            ),
        )
        .let { it.copy(environment = it.environment.copy(theme = theme)) }

    for (theme in listOf(ThemeV1.LIGHT, ThemeV1.DARK)) {
      val finding = UiBuilderAccessibilityCheck.check(titleOnSurface(theme), emptyMap()).single()
      assertEquals(UiBuilderAccessibilityCheck.CODE_CONTRAST, finding.code)
      assertEquals("title", finding.nodeId)
      assertEquals("color", finding.field)
      assertEquals(SEVERITY_WARNING, finding.severity)
      assertEquals("dark", finding.measured?.theme, "$theme: ${finding.message}")
      assertTrue(finding.measured!!.ratio!! < 4.5, finding.message)
      assertTrue(finding.message.contains("in the dark theme"), finding.message)
      assertTrue(finding.message.contains("passes in the light theme"), finding.message)
    }
  }

  @Test
  fun `literal colours on a literal background are checked once, in the design's own theme`() {
    val document =
      document(
        "panel" to
          DesignNodeV1(
            id = "panel",
            componentId = "layout/box",
            modifiers = listOf(BackgroundModifierV1(ColorValueV1("#FFFFFF"), null)),
            slots = mapOf("content" to listOf("title")),
          ),
        "title" to
          DesignNodeV1(
            id = "title",
            componentId = "m3/text",
            properties =
              mapOf("text" to StringValueV1("Schedule"), "color" to ColorValueV1("#5F6368")),
          ),
      )
    assertTrue(UiBuilderAccessibilityCheck.check(document, emptyMap()).isEmpty())
    val dark = document.copy(environment = document.environment.copy(theme = ThemeV1.DARK))
    assertTrue(UiBuilderAccessibilityCheck.check(dark, emptyMap()).isEmpty())
  }

  @Test
  fun `an export refusal is placed on the node and property it names`() {
    val field =
      ComponentCapabilityV1.Builder(
          "m3/outlined-text-field",
          "Outlined text field",
          "Leaf",
          WasmCapabilityV1.Builder(JsonPrimitive(false), WasmAdapterStatusV1.UNSUPPORTED).build(),
        )
        .also {
          it.properties =
            listOf(
              PropertyCapabilityV1.Builder("value", JsonPrimitive("string")).build(),
              PropertyCapabilityV1.Builder("singleLine", JsonPrimitive("boolean")).build(),
            )
          it.code = CodeCapabilityV1.Builder("androidx.compose.material3.OutlinedTextField").build()
        }
        .build()
    val document =
      document(
        "column" to
          DesignNodeV1(
            id = "column",
            componentId = "layout/column",
            slots = mapOf("children" to listOf("email", "name")),
          ),
        "email" to
          DesignNodeV1(
            id = "email",
            componentId = "m3/outlined-text-field",
            properties = mapOf("value" to StringValueV1(""), "singleLine" to BooleanValueV1(true)),
          ),
        "name" to
          DesignNodeV1(
            id = "name",
            componentId = "m3/outlined-text-field",
            properties = mapOf("value" to StringValueV1("")),
          ),
      )

    // The projection's wording names the node and the property.
    val projected =
      UiBuilderExportDiagnosticLocation.locate(
          "node `email`.singleLine: expected kotlin.Boolean",
          null,
          document,
          listOf(field),
        )
        .single()
    assertEquals("email", projected.nodeId)
    assertEquals("singleLine", projected.field)
    // The catalog offers `singleLine` and the gate refuses it: the message says they disagree.
    assertTrue(projected.message.contains("catalog disagree"), projected.message)
    assertTrue(projected.message.startsWith("node `email`.singleLine"), projected.message)

    // The generator's wording names the callable and the parameter; the place is the node of a
    // component calling it that sets the property, not every text field.
    val generated =
      UiBuilderExportDiagnosticLocation.locate(
          "`OutlinedTextField` has no parameter `singleLine`",
          null,
          document,
          listOf(field),
        )
        .single()
    assertEquals("email", generated.nodeId)
    assertEquals("singleLine", generated.field)

    // A node the diagnostic already names is kept, and a sentence about nothing in particular is
    // passed through as it was.
    assertEquals(
      "name",
      UiBuilderExportDiagnosticLocation.locate("refused", "name", document, listOf(field))
        .single()
        .nodeId,
    )
    val loose =
      UiBuilderExportDiagnosticLocation.locate(
          "the screen has no root",
          null,
          document,
          listOf(field),
        )
        .single()
    assertNull(loose.nodeId)
    assertEquals("the screen has no root", loose.message)
  }

  @Test
  fun `a rendered box replaces the declared size`() {
    val document =
      document(
        "go" to
          DesignNodeV1(
            id = "go",
            componentId = "m3/button",
            properties = mapOf("text" to StringValueV1("Go")),
          )
      )
    // No declared size: nothing to say from the document alone.
    assertTrue(UiBuilderAccessibilityCheck.check(document, emptyMap()).isEmpty())
    // Rendered at 2px per dp, 80×60px is 40×30dp.
    val rendered =
      UiBuilderAccessibilityCheck.Rendered(2.0, mapOf("go" to ServeUiBuilderView.Box(0, 0, 80, 60)))
    val finding = UiBuilderAccessibilityCheck.check(document, emptyMap(), rendered).single()
    assertEquals(UiBuilderAccessibilityCheck.CODE_TOUCH_TARGET, finding.code)
    assertEquals(SEVERITY_ERROR, finding.severity)
    assertEquals(30.0, finding.measured?.height)
  }

  @Test
  fun `decisions are validated, bounded and idempotent by id`() {
    val store = ServeUiBuilderReviewStore(directory)
    assertIs<ReviewWriteResult.Refused>(
      store.decide("d", "alice", "human", DecisionRequest(revision = 1, verdict = "lgtm"))
    )
    assertIs<ReviewWriteResult.Refused>(
      store.decide("d", "alice", "human", DecisionRequest(revision = -1, verdict = "approve"))
    )
    val first =
      store.decide("d", "alice", "human", DecisionRequest(1, "APPROVE", decisionId = "x"))
        as ReviewWriteResult.Stored
    assertEquals("approve", first.decision?.verdict)
    val replay =
      store.decide("d", "alice", "human", DecisionRequest(1, "approve", decisionId = "x"))
        as ReviewWriteResult.Stored
    assertTrue(replay.replay)
    assertEquals(first.review.sequence, replay.review.sequence)
    // Somebody else may not reuse the id.
    assertIs<ReviewWriteResult.Refused>(
      store.decide("d", "bob", "human", DecisionRequest(1, "approve", decisionId = "x"))
    )
    repeat(ServeUiBuilderReviewStore.MAX_DECISIONS + 5) {
      store.decide("d", "alice", "human", DecisionRequest(1, "reject"))
    }
    assertEquals(ServeUiBuilderReviewStore.MAX_DECISIONS, store.read("d")!!.decisions.size)
  }

  @Test
  fun `a wait filters by revision and decider, and returns at once when it is already answered`() =
    runBlocking {
      val store = ServeUiBuilderReviewStore(directory)
      store.decide("d", "bot", "agent", DecisionRequest(2, "approve"))
      assertNull(store.awaitDecisionsAfter("d", 0, revision = null, "human", 0))
      assertNull(store.awaitDecisionsAfter("d", 0, revision = 3, null, 0))
      assertNotNull(store.awaitDecisionsAfter("d", 0, revision = 2, null, 0))
      store.decide("d", "alice", "human", DecisionRequest(2, "reject"))
      val answered = assertNotNull(store.awaitDecisionsAfter("d", 1, revision = null, "human", 50))
      assertEquals("reject", answered.decisions.last().verdict)
    }

  @Test
  fun `an unchanged implementation record does not move the cursor, and a PR finds its design`() {
    val store = ServeUiBuilderReviewStore(directory)
    val pr = "https://github.com/example/app/pull/7"
    val first =
      store.setImplementation("d", "alice", ImplementationRequest(pr = pr, revision = 3))
        as ReviewWriteResult.Stored
    val again =
      store.setImplementation("d", "bob", ImplementationRequest(pr = pr, revision = 3))
        as ReviewWriteResult.Stored
    assertEquals(first.review.sequence, again.review.sequence)
    assertEquals("open", first.review.implementation?.status)
    assertEquals(listOf("d"), store.implementedBy(pr))
    assertIs<ReviewWriteResult.Refused>(
      store.setImplementation("d", "alice", ImplementationRequest(pr = "ftp://x/y"))
    )
    val cleared = store.setImplementation("d", "alice", null) as ReviewWriteResult.Stored
    assertNull(cleared.review.implementation)
    assertTrue(store.implementedBy(pr).isEmpty())
    assertTrue(store.delete("d"))
    assertNull(store.read("d"))
  }

  private fun document(vararg nodes: Pair<String, DesignNodeV1>): DesignDocumentV1 =
    DesignDocumentV1(
      schema = "compose-ui-builder-document/v1-candidate",
      id = "unit",
      title = "Unit",
      revision = 1,
      catalogPin = CatalogReferenceV1("m3-catalog", "c", "c", "c"),
      environment =
        DesignEnvironmentV1(
          widthDp = 400,
          heightDp = 800,
          density = 1.0,
          theme = ThemeV1.LIGHT,
          locale = "en-US",
          fontScale = 1.0,
          layoutDirection = LayoutDirectionV1.LTR,
          windowPosture = WindowPostureV1.FLAT,
          animations = AnimationStateV1.SETTLED,
          networkAccess = false,
        ),
      roots = listOf(nodes.first().first),
      nodes = nodes.toMap(),
    )
}
