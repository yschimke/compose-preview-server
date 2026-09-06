package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.discovery.ComponentCode
import ee.schimke.composeai.discovery.ComponentOrigin
import ee.schimke.composeai.discovery.ComponentRecord
import ee.schimke.composeai.discovery.ComponentRecordFile
import ee.schimke.composeai.discovery.ComponentSlot
import ee.schimke.composeai.discovery.ComponentSymbol
import ee.schimke.composeai.discovery.TargetParameter
import ee.schimke.composeai.uibuilder.UiBuilderCatalogPlatform
import ee.schimke.composeai.uibuilder.protocol.WasmAdapterStatusV1
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.jsonPrimitive

/** The record → pack projection: what it offers, what it leaves out, and that both agree on ids. */
class ComponentRecordPacksTest {

  @Test
  fun `only the project's own proven composables become components`() {
    val derived =
      ComponentRecordPacks.derive("confetti-mobile", UiBuilderCatalogPlatform.MOBILE, record())

    assertEquals(
      listOf("confetti-mobile/session-card", "confetti-mobile/speaker-row"),
      derived.source.components.map { it.componentId },
    )
    assertEquals("Confetti Mobile", derived.source.label)
    assertEquals("mobile", derived.source.platform)
    assertEquals("confetti-mobile", derived.source.nativeCatalog)
    // Each exclusion is named, with its reason, so the startup log says why a shelf is shorter
    // than the record.
    assertEquals(
      listOf(
        "confetti-mobile/text — a library symbol, not the project's own",
        "confetti-mobile/broken — no call site: `state: SessionState` has no placeholder",
        "confetti-mobile/session-card — a component of the same name was already taken from this record",
      ),
      derived.skipped,
    )
  }

  @Test
  fun `a component's literal parameters are its properties and its lambdas are its slots`() {
    val derived =
      ComponentRecordPacks.derive("confetti-mobile", UiBuilderCatalogPlatform.MOBILE, record())
    val card = derived.source.components.first { it.componentId == "confetti-mobile/session-card" }

    assertEquals("Session Card", card.displayName)
    assertEquals("Container", card.role)
    assertEquals(listOf("content"), card.slots.map { it.name })
    assertTrue(card.slots.single().acceptedRoles.isEmpty())
    assertTrue(card.slots.single().acceptedTraits.isEmpty())
    assertEquals(
      mapOf("title" to true, "count" to false, "highlighted" to false),
      card.properties.associate { it.name to it.required },
    )
    assertEquals(
      listOf("string", "integer", "boolean"),
      card.properties.map { it.jsonType.jsonPrimitive.content },
      "a Modifier, a callback and a domain type are not properties",
    )
    assertEquals(WasmAdapterStatusV1.UNSUPPORTED, card.wasm.adapterStatus)
    assertEquals("dev.confetti.ui.SessionCard", card.code?.symbol)
    assertTrue("fillMaxSize" in card.modifierCapabilities)

    val row = derived.source.components.first { it.componentId == "confetti-mobile/speaker-row" }
    assertEquals("Leaf", row.role)
    assertTrue("fillMaxSize" !in row.modifierCapabilities)
  }

  @Test
  fun `the aliased record names exactly the offered components, under the same ids`() {
    val aliased = ComponentRecordPacks.aliasedRecord("confetti-mobile", record())
    val derived =
      ComponentRecordPacks.derive("confetti-mobile", UiBuilderCatalogPlatform.MOBILE, record())

    assertEquals(
      derived.source.components.map { it.componentId },
      aliased.components.map { it.componentIds.single() },
    )
    assertEquals(
      listOf(
        "confetti/dev.confetti.ui.SessionCardKt.SessionCard",
        "confetti/dev.confetti.ui.SpeakerRowKt.SpeakerRow",
      ),
      aliased.components.map { it.canonicalId },
    )
  }

  @Test
  fun `a label is spelled from the pack id`() {
    assertEquals("Confetti Wear", ComponentRecordPacks.labelFor("confetti-wear"))
    assertEquals("Jetnews", ComponentRecordPacks.labelFor("jetnews"))
    assertEquals("Home Assistant Rc", ComponentRecordPacks.labelFor("home_assistant.rc"))
  }

  companion object {
    fun record(): ComponentRecordFile =
      ComponentRecordFile(
        module = "confetti",
        variant = "debug",
        components =
          listOf(
            project(
              "SessionCard",
              parameters =
                listOf(
                  TargetParameter(name = "title", type = "String", typeFqn = "kotlin.String"),
                  TargetParameter(
                    name = "count",
                    type = "Int",
                    typeFqn = "kotlin.Int",
                    hasDefault = true,
                  ),
                  TargetParameter(
                    name = "highlighted",
                    type = "Boolean?",
                    typeFqn = "kotlin.Boolean",
                    nullable = true,
                  ),
                  TargetParameter(
                    name = "modifier",
                    type = "Modifier",
                    typeFqn = "androidx.compose.ui.Modifier",
                    hasDefault = true,
                  ),
                  TargetParameter(name = "onClick", type = "() -> Unit", hasDefault = true),
                  TargetParameter(
                    name = "speaker",
                    type = "Speaker?",
                    typeFqn = "dev.confetti.model.Speaker",
                    hasDefault = true,
                    nullable = true,
                  ),
                  TargetParameter(
                    name = "content",
                    type = "@Composable () -> Unit",
                    composableSlot = true,
                    hasDefault = true,
                  ),
                ),
              slots = listOf(ComponentSlot(name = "content", required = false)),
              call = "SessionCard(title = \"\")",
            ),
            project(
              "SpeakerRow",
              parameters =
                listOf(TargetParameter(name = "name", type = "String", typeFqn = "kotlin.String")),
              call = "SpeakerRow(name = \"\")",
            ),
            ComponentRecord(
              canonicalId = "confetti/androidx.compose.material3.TextKt.Text",
              componentIds = emptyList(),
              symbol =
                ComponentSymbol(
                  jvmOwner = "androidx.compose.material3.TextKt",
                  callable = "androidx.compose.material3.Text",
                  name = "Text",
                  origin = ComponentOrigin.LIBRARY,
                ),
              code =
                ComponentCode(
                  call = "Text(text = \"\")",
                  imports = listOf("androidx.compose.material3.Text"),
                ),
              signatureKnown = true,
            ),
            project(
              "Broken",
              call = null,
              refusedReason = "`state: SessionState` has no placeholder",
            ),
            // A second `SessionCard` in another file: same name, another owner. First one wins.
            project(
              "SessionCard",
              owner = "dev.confetti.ui.legacy.SessionCardKt",
              call = "SessionCard()",
            ),
          ),
      )

    private fun project(
      name: String,
      owner: String = "dev.confetti.ui.${name}Kt",
      parameters: List<TargetParameter> = emptyList(),
      slots: List<ComponentSlot> = emptyList(),
      call: String?,
      refusedReason: String? = null,
    ): ComponentRecord =
      ComponentRecord(
        canonicalId = "confetti/$owner.$name",
        componentIds = emptyList(),
        symbol =
          ComponentSymbol(
            jvmOwner = owner,
            callable = "${owner.substringBeforeLast('.')}.$name",
            name = name,
            origin = ComponentOrigin.PROJECT,
          ),
        parameters = parameters,
        slots = slots,
        code =
          ComponentCode(
            call = call,
            imports =
              if (call == null) emptyList() else listOf("${owner.substringBeforeLast('.')}.$name"),
            refusedReason = refusedReason,
          ),
        signatureKnown = true,
      )
  }
}
