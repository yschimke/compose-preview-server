package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.uibuilder.capability.CapabilityCatalog

/**
 * The capability-gated whole-document Compose export.
 *
 * It used to carry a second `export(document)` overload beside this one: the original Confetti
 * spike, ~120 lines that emitted one screen's Kotlin with that screen's content compiled in — `fun
 * ConfettiScheduleHeader()`, `ScheduleBreak(title = "Coffee Break", location = "Foyer · Level 1")`,
 * a `when` on node ids including `"coffee-break"`. It was retained "as a baseline while callers
 * migrate to capabilities"; every caller had migrated, leaving one test that asserted the hardcoded
 * strings came back out.
 *
 * `docs/design/UI_BUILDER_ON_THE_COMPONENT_RECORD.md` names it and the choice it needed — "delegate
 * it, generate it, or retire it before claiming the exporter surfaces agree" — because a fourth
 * thing that emits Compose is a fourth thing that can disagree with the other three. It is retired.
 * What it demonstrated, that a reduced document becomes Kotlin, is what this path does for every
 * document rather than for one; that the Confetti fixture still reduces correctly is asserted by
 * the reducer tests that were always the real coverage.
 */
object ComposeCodeExporter {
  fun export(document: UiBuilderDocument, catalog: CapabilityCatalog): ComposeExportResult =
    CapabilityComposeCodeExporter.export(document, catalog)
}
