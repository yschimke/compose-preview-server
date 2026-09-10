package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.daemon.protocol.CompileErrorDetail
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The pure BTA-diagnostic → PlaygroundDiagnostic mapping (positions, basename, severity). */
class PlaygroundBtaCompilerTest {

  @Test
  fun `kotlinc 1-based positions map to codemirror 0-based, keyed by basename`() {
    val details =
      listOf(
        CompileErrorDetail(
          file = "/tmp/pg/abc/src/Snippet.kt",
          line = 3,
          column = 5,
          message = "unresolved reference: Bttn",
        ),
        CompileErrorDetail(
          file = "/tmp/pg/abc/src/Snippet.kt",
          line = 1,
          column = 1,
          message = "expecting a top level declaration",
        ),
      )

    val diags = PlaygroundBtaCompiler.mapDiagnostics(details)

    assertEquals(2, diags.size)
    assertEquals(PlaygroundSeverity.ERROR, diags[0].severity)
    assertEquals(
      "Snippet.kt",
      diags[0].file,
      "the editor sees the snippet basename, not the temp path",
    )
    assertEquals(2, diags[0].line, "line 3 (1-based) → 2 (0-based)")
    assertEquals(4, diags[0].ch, "column 5 (1-based) → 4 (0-based)")
    assertEquals("unresolved reference: Bttn", diags[0].message)
    assertEquals(0, diags[1].line)
    assertEquals(0, diags[1].ch)
  }

  /**
   * The regression behind yschimke/compose-preview-server#699: `Slider(state = …, valueRange = …)`
   * is K2's most ordinary overload failure and it is reported as SIX lines. `DiagnosticCollector`
   * matches its single-line regex against the whole message, so every one of those parsed to null
   * and the playground fell back to one unanchored `compilation failed: …` line.
   */
  @Test
  fun `a multi-line diagnostic keeps its position and every line`() {
    val raw =
      "file:///tmp/pg/abc/src/Snippet.kt:31:3 none of the following candidates is applicable:\n" +
        "\n" +
        "fun Slider(state: SliderState, enabled: Boolean = ...): Unit:\n" +
        "  No parameter with name 'valueRange' found.\n"

    val diag = PlaygroundBtaCompiler.diagnosticsFrom(listOf(raw)).single()

    assertEquals("Snippet.kt", diag.file)
    assertEquals(30, diag.line, "line 31 (1-based) → 30 (0-based)")
    assertEquals(2, diag.ch)
    assertTrue(
      diag.message.startsWith("none of the following candidates is applicable:"),
      "the head line's text survives the position parse: ${diag.message}",
    )
    assertTrue(
      diag.message.contains("No parameter with name 'valueRange' found."),
      "the candidate block is the half that says what is wrong: ${diag.message}",
    )
  }

  @Test
  fun `a single-line diagnostic is unchanged`() {
    val diag =
      PlaygroundBtaCompiler.diagnosticsFrom(
          listOf("file:///tmp/pg/abc/src/Snippet.kt:3:5 unresolved reference: Bttn")
        )
        .single()

    assertEquals("Snippet.kt", diag.file)
    assertEquals(2, diag.line)
    assertEquals(4, diag.ch)
    assertEquals("unresolved reference: Bttn", diag.message)
  }

  /**
   * Kept unanchored rather than dropped, and kept file-less: `compileWithCollector` treats "no
   * diagnostic carried a position" as the infrastructure shape the compile service resets a lease
   * on, so this is the signal that decides between "your snippet is wrong" and "the compiler fell
   * over".
   */
  @Test
  fun `an unanchored message is reported rather than dropped`() {
    val diag = PlaygroundBtaCompiler.diagnosticsFrom(listOf("Compilation error.")).single()

    assertNull(diag.file, "nothing to anchor it to")
    assertNull(diag.line)
    assertEquals("Compilation error.", diag.message)
    assertEquals(PlaygroundSeverity.ERROR, diag.severity)
  }

  @Test
  fun `blank callbacks contribute nothing`() {
    assertEquals(
      emptyList<PlaygroundDiagnostic>(),
      PlaygroundBtaCompiler.diagnosticsFrom(listOf("", "   ", "\n")),
    )
  }

  @Test
  fun `a head that is only a position still shows the raw text`() {
    val diag =
      PlaygroundBtaCompiler.diagnosticsFrom(listOf("file:///tmp/x/Snippet.kt:7:1 \n  see above"))
        .single()

    assertEquals("Snippet.kt", diag.file)
    assertEquals(6, diag.line)
    assertEquals(
      "  see above",
      diag.message,
      "leading indentation is kept — it is what separates a candidate's own lines from the head",
    )
  }

  @Test
  fun `the error log records every callback in order`() {
    val log = PlaygroundBtaCompiler.ErrorLog()
    log.error("first", null)
    log.warn("a warning is not a diagnostic here")
    log.info("nor is progress output")
    log.error("second", RuntimeException("boom"))

    assertEquals(listOf("first", "second"), log.messages)
  }

  @Test
  fun `positions never go negative`() {
    val diags =
      PlaygroundBtaCompiler.mapDiagnostics(
        listOf(CompileErrorDetail(file = "X.kt", line = 0, column = 0, message = "m"))
      )
    assertEquals(0, diags.single().line)
    assertEquals(0, diags.single().ch)
  }
}
