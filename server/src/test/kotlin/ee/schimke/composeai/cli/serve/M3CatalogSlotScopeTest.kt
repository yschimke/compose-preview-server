package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.discovery.ComponentRecordFile
import ee.schimke.composeai.uibuilder.export.ScreenDocumentProjection
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json

/**
 * The projection and the record must agree about which receiver a slot's children get.
 *
 * `ScreenGenerator` accepts a scoped modifier — `Modifier.weight`, `Modifier.matchParentSize` —
 * only when the link's `receiverScopeFqn` equals the `composableSlotReceiver` of the slot it is
 * emitting into. The projection states that scope from `SLOT_SCOPES` and the generator checks it
 * against the record, so the two are one claim split across two files. Disagree, and every weight
 * in every design refuses with a message about scopes that both sides believe they got right.
 *
 * They are keyed differently on purpose, which is the drift risk this closes. The projection keys
 * by the **catalog's** slot name, because that is what a document holds — `layout/column` has
 * `children`. The record keys by the **parameter** name the signature declares — `content`. The
 * mapping between them is `SLOT_PARAMETERS`, a third authored table, so this walks all three and
 * compares the resolved result in both directions at once.
 */
class M3CatalogSlotScopeTest {

  private val record: ComponentRecordFile =
    Json { ignoreUnknownKeys = true }.decodeFromString(File(RECORD).readText())

  /** Every receiver-scoped slot the shipped record attests, by capability id and parameter name. */
  private fun attested(): Map<Pair<String, String>, String> =
    record.components
      .flatMap { component ->
        component.componentIds.flatMap { id ->
          component.slots.mapNotNull { slot ->
            slot.receiverScope?.let { (id to slot.name) to it }
          }
        }
      }
      .toMap()

  /** Every scope the projection claims, resolved through the same slot alias it applies. */
  private fun claimed(): Map<Pair<String, String>, String> =
    ScreenDocumentProjection.SLOT_SCOPES
      .flatMap { (id, slots) ->
        slots.map { (slot, scope) ->
          (id to ScreenDocumentProjection.parameterForSlotName(id, slot)) to scope
        }
      }
      .toMap()

  @Test
  fun `the projection claims exactly the receiver scopes the record attests`() {
    // Both directions in one comparison, because each has its own failure. A scope the record does
    // not attest emits a scoped modifier the generator refuses; a scoped slot the projection does
    // not carry composes its children under a receiver it never learned about, so every `weight`
    // inside refuses as though the node were at the root — which reads like a missing feature.
    assertEquals(attested(), claimed())
  }

  private companion object {
    const val RECORD = "../docs/design/fixtures/ui-builder/m3-catalog-components-v1.json"
  }
}
