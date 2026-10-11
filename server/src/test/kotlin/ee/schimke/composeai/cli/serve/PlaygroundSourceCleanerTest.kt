package ee.schimke.composeai.cli.serve

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the sticker → usage-code rewrite. The fixture is a verbatim extract of m3-catalog's
 * `catalog/src/main/kotlin/ee/schimke/m3catalog/sections/Buttons.kt` (a matrix-driven sticker with
 * a click tally, three knobs, a private frame and a translated label), since the question is
 * whether real catalog source comes out runnable. The rules are the subset of m3-catalog's
 * `compose-usage.json` these fixtures exercise.
 */
class PlaygroundSourceCleanerTest {

  private val rules =
    UsageRules(
      scaffoldAnnotationPackages = listOf("ee.schimke.composeai.preview", "ee.schimke.m3catalog"),
      scaffolds =
        mapOf(
          "Sticker" to
            UsageRules.Scaffold(
              kind = UsageRules.Kind.RENAME,
              renameTo = "MaterialTheme",
              addImport = "androidx.compose.material3.MaterialTheme",
            ),
          "counted" to
            UsageRules.Scaffold(
              kind = UsageRules.Kind.INLINE,
              members = mapOf("label" to "\$0", "onClick" to "{}"),
            ),
          "catalogEnabled" to UsageRules.Scaffold(kind = UsageRules.Kind.DROP),
          "catalogButtonShape" to UsageRules.Scaffold(kind = UsageRules.Kind.DROP),
          "catalogButtonSize" to UsageRules.Scaffold(kind = UsageRules.Kind.DROP),
          "ButtonFrame" to UsageRules.Scaffold(kind = UsageRules.Kind.UNWRAP),
        ),
      stringsPath = "src/main/composeResources/values/strings.xml",
    )

  private val strings = mapOf("label_filled" to "Filled", "label_tonal" to "Tonal")

  /** Verbatim from `Buttons.kt`, trimmed to the declarations the anchors below land in. */
  private val buttonsKt =
    """
    @file:CatalogGroup(name = "Buttons", section = "Actions")
    @file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

    package ee.schimke.m3catalog.sections

    import androidx.compose.foundation.layout.Box
    import androidx.compose.foundation.layout.Spacer
    import androidx.compose.foundation.layout.height
    import androidx.compose.foundation.layout.width
    import androidx.compose.material3.Button
    import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
    import androidx.compose.material3.Icon
    import androidx.compose.material3.Text
    import androidx.compose.runtime.Composable
    import androidx.compose.ui.Alignment
    import androidx.compose.ui.Modifier
    import androidx.compose.ui.unit.dp
    import ee.schimke.composeai.preview.CatalogComponent
    import ee.schimke.composeai.preview.CatalogGroup
    import ee.schimke.composeai.preview.CatalogVariant
    import ee.schimke.m3catalog.CatalogModes
    import ee.schimke.m3catalog.CatalogSize
    import ee.schimke.m3catalog.SizeShapeMatrix
    import ee.schimke.m3catalog.Sticker
    import ee.schimke.m3catalog.catalogButtonShape
    import ee.schimke.m3catalog.catalogButtonSize
    import ee.schimke.m3catalog.catalogEnabled
    import ee.schimke.m3catalog.counted
    import ee.schimke.m3catalog.generated.resources.Res
    import ee.schimke.m3catalog.generated.resources.label_filled
    import org.jetbrains.compose.resources.stringResource

    @Composable
    private fun ButtonFrame(size: CatalogSize, content: @Composable () -> Unit) {
      Box(
        modifier = Modifier.height(if (size == CatalogSize.Small) 48.dp else size.containerHeight),
        contentAlignment = Alignment.Center,
      ) {
        content()
      }
    }

    @CatalogComponent(
      id = "Button/Filled",
      reference = "figma:ocdacdEsnHipMJD3egzxKb/57994:2324",
      caption = "Highest emphasis; the primary action. Five sizes x two shapes fold in as variants.",
    )
    @CatalogModes
    @SizeShapeMatrix
    @Composable
    fun FilledButton() = Sticker {
      val c = counted(stringResource(Res.string.label_filled))
      val size = catalogButtonSize()
      ButtonFrame(size) {
        Button(
          onClick = c.onClick,
          enabled = catalogEnabled(),
          shape = catalogButtonShape(),
          contentPadding = size.contentPadding,
          modifier = Modifier.height(size.containerHeight),
        ) {
          Text(c.label)
        }
      }
    }

    @CatalogVariant(
      of = "Button/Filled",
      props = ["content=label"],
      caption = "Label only, vs the kit's icon + label default.",
    )
    @CatalogModes
    @Composable
    fun FilledButtonLabelOnly() = Sticker {
      val c = counted(stringResource(Res.string.label_filled))
      Button(onClick = c.onClick) { Text(c.label) }
    }
    """
      .trimIndent()

  private fun lineOf(needle: String): Int =
    buttonsKt
      .lines()
      .indexOfFirst { it.contains(needle) }
      .let {
        require(it >= 0) { "fixture has no line containing $needle" }
        it + 1
      }

  private fun cleanAt(needle: String) =
    PlaygroundSourceCleaner.clean(buttonsKt, lineOf(needle), rules, strings)

  /**
   * The simple variant reduces to the few lines someone would write, leaving only the component
   * call.
   */
  @Test
  fun `a variant sticker becomes plain compose`() {
    val result = assertNotNull(cleanAt("Button(onClick = c.onClick) { Text(c.label) }"))
    assertEquals(
      """
      @file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

      import androidx.compose.material3.Button
      import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
      import androidx.compose.material3.MaterialTheme
      import androidx.compose.material3.Text
      import androidx.compose.runtime.Composable
      import androidx.compose.ui.tooling.preview.Preview

      @Preview
      @Composable
      fun FilledButtonLabelOnly() = MaterialTheme {
        Button(onClick = {}) { Text("Filled") }
      }
      """
        .trimIndent(),
      result.text,
    )
    assertEquals(emptyList(), result.residue)
    assertEquals("FilledButtonLabelOnly", result.entryFunction)
  }

  /**
   * The matrix-driven case: knobs, private frame, click tally and resource lookup all resolve away,
   * leaving the default render's call.
   */
  @Test
  fun `a matrix sticker loses its knobs, its frame and its tally`() {
    val result = assertNotNull(cleanAt("""caption = "Highest emphasis"""))
    assertEquals(
      """
      @file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

      import androidx.compose.material3.Button
      import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
      import androidx.compose.material3.MaterialTheme
      import androidx.compose.material3.Text
      import androidx.compose.runtime.Composable
      import androidx.compose.ui.tooling.preview.Preview

      @Preview
      @Composable
      fun FilledButton() = MaterialTheme {
        Button(onClick = {}) {
          Text("Filled")
        }
      }
      """
        .trimIndent(),
      result.text,
    )
    assertEquals(emptyList(), result.residue)
  }

  @Test
  fun `a system Material 3 theme wrapper keeps its lambda and follows preview uiMode`() {
    val source =
      """
      package com.example.catalog

      import androidx.compose.material3.Text
      import androidx.compose.runtime.Composable
      import com.example.catalog.Sticker

      @Composable
      fun CardPreview() = Sticker {
        Text("Card")
      }
      """
        .trimIndent()
    val rules =
      UsageRules(
        scaffolds =
          mapOf(
            "Sticker" to
              UsageRules.Scaffold(
                kind = UsageRules.Kind.RENAME,
                renameTo = "MaterialTheme",
                special = UsageRules.MATERIAL3_SYSTEM_THEME,
              )
          )
      )

    val result =
      assertNotNull(
        PlaygroundSourceCleaner.clean(
          source,
          source.lines().indexOfFirst { it.contains("Text(\"Card\")") } + 1,
          rules,
        )
      )

    assertTrue(
      result.text.contains(
        "androidx.compose.material3.MaterialTheme(colorScheme = if (androidx.compose.foundation.isSystemInDarkTheme()) androidx.compose.material3.darkColorScheme() else androidx.compose.material3.lightColorScheme()) {"
      ),
      result.text,
    )
    assertTrue(result.text.contains("Text(\"Card\")"), result.text)
    assertFalse(result.text.contains("import androidx.compose.foundation.isSystemInDarkTheme"))
    assertFalse(result.text.contains("import androidx.compose.material3.MaterialTheme"))
    assertFalse(result.text.contains("import com.example.catalog.Sticker"), result.text)
    assertEquals(emptyList(), result.residue)
  }

  @Test
  fun `a system Material 3 theme wrapper accepts an empty argument list`() {
    val source =
      """
      package com.example.catalog

      import androidx.compose.runtime.Composable
      import com.example.catalog.Sticker

      @Composable
      fun CardPreview() = Sticker() { Unit }
      """
        .trimIndent()
    val rules =
      UsageRules(
        scaffolds =
          mapOf(
            "Sticker" to
              UsageRules.Scaffold(
                kind = UsageRules.Kind.RENAME,
                renameTo = "MaterialTheme",
                special = UsageRules.MATERIAL3_SYSTEM_THEME,
              )
          )
      )

    val result =
      assertNotNull(
        PlaygroundSourceCleaner.clean(
          source,
          source.lines().indexOfFirst { it.contains("Unit") } + 1,
          rules,
        )
      )

    assertFalse(result.text.contains("Sticker"), result.text)
    assertTrue(result.text.contains("androidx.compose.material3.MaterialTheme("), result.text)
    assertEquals(emptyList(), result.residue)
  }

  @Test
  fun `a system Material 3 theme wrapper treats comment-only arguments as empty`() {
    val rules =
      UsageRules(
        scaffolds =
          mapOf(
            "Sticker" to
              UsageRules.Scaffold(
                kind = UsageRules.Kind.RENAME,
                renameTo = "MaterialTheme",
                special = UsageRules.MATERIAL3_SYSTEM_THEME,
              )
          )
      )

    for (arguments in
      listOf(
        "/* default theme */",
        "/* outer /* detail */ outer */",
        "\n        // default theme\n        ",
      )) {
      val source =
        """
        package com.example.catalog

        import androidx.compose.runtime.Composable
        import com.example.catalog.Sticker

        @Composable
        fun CardPreview() = Sticker($arguments) { Unit }
        """
          .trimIndent()

      val result =
        assertNotNull(
          PlaygroundSourceCleaner.clean(
            source,
            source.lines().indexOfFirst { it.contains("fun CardPreview") } + 1,
            rules,
          ),
          "comment-only arguments were not cleaned: ${arguments.replace("\n", "\\n")}",
        )

      assertFalse(result.text.contains("Sticker"), result.text)
      assertTrue(result.text.contains("androidx.compose.material3.MaterialTheme("), result.text)
      assertEquals(emptyList(), result.residue)
    }
  }

  @Test
  fun `a system Material 3 expansion cannot collide with retained simple imports`() {
    val source =
      """
      package com.example.catalog

      import com.example.brand.MaterialTheme
      import com.example.catalog.Sticker
      import androidx.compose.runtime.Composable

      @Composable
      fun CardPreview() = Sticker { MaterialTheme { Unit } }
      """
        .trimIndent()
    val rules =
      UsageRules(
        scaffolds =
          mapOf(
            "Sticker" to
              UsageRules.Scaffold(
                kind = UsageRules.Kind.RENAME,
                renameTo = "MaterialTheme",
                special = UsageRules.MATERIAL3_SYSTEM_THEME,
                addImports =
                  listOf(
                    "androidx.compose.material3.MaterialTheme",
                    "androidx.compose.material3.darkColorScheme",
                    "androidx.compose.material3.lightColorScheme",
                    "androidx.compose.foundation.isSystemInDarkTheme",
                  ),
              )
          )
      )

    val result =
      assertNotNull(
        PlaygroundSourceCleaner.clean(
          source,
          source.lines().indexOfFirst { it.contains("MaterialTheme { Unit") } + 1,
          rules,
        )
      )

    assertTrue(result.text.contains("import com.example.brand.MaterialTheme"), result.text)
    assertFalse(
      result.text.contains("import androidx.compose.material3.MaterialTheme"),
      result.text,
    )
    assertTrue(result.text.contains("MaterialTheme { Unit }"), result.text)
    assertEquals(emptyList(), result.residue)
  }

  @Test
  fun `a system Material 3 theme rule does not discard wrapper arguments`() {
    val source =
      """
      package com.example.catalog

      import androidx.compose.runtime.Composable
      import com.example.catalog.Sticker

      @Composable
      fun CardPreview() = Sticker("brand") {
        Unit
      }
      """
        .trimIndent()
    val rules =
      UsageRules(
        scaffolds =
          mapOf(
            "Sticker" to
              UsageRules.Scaffold(
                kind = UsageRules.Kind.RENAME,
                renameTo = "MaterialTheme",
                special = UsageRules.MATERIAL3_SYSTEM_THEME,
              )
          )
      )

    val result =
      assertNotNull(
        PlaygroundSourceCleaner.clean(
          source,
          source.lines().indexOfFirst { it.contains("Unit") } + 1,
          rules,
        )
      )

    assertTrue(result.text.contains("Sticker(\"brand\")"), result.text)
    assertEquals(listOf("Sticker"), result.residue)
    assertFalse(result.text.contains("darkColorScheme"), result.text)
  }

  /** Not one annotation from either annotation package may reach the editor. */
  @Test
  fun `catalog annotations and their imports are gone`() {
    val text = assertNotNull(cleanAt("""caption = "Highest emphasis""")).text
    for (noise in
      listOf(
        "@CatalogComponent",
        "@CatalogModes",
        "@SizeShapeMatrix",
        "@CatalogVariant",
        "@file:CatalogGroup",
        "ee.schimke.m3catalog",
        "ee.schimke.composeai.preview",
        "figma:",
      )) {
      assertFalse(text.contains(noise), "cleaned source still carries $noise:\n$text")
    }
  }

  /**
   * The playground compiles a snippet and looks for a `@Preview`, so stripping `@CatalogModes` must
   * put a real one back.
   */
  @Test
  fun `a real Preview replaces the catalog's meta-annotation`() {
    val text = assertNotNull(cleanAt("""caption = "Highest emphasis""")).text
    assertTrue(text.contains("@Preview"))
    assertTrue(text.contains("import androidx.compose.ui.tooling.preview.Preview"))
    assertTrue(
      PlaygroundPreviewDiscoverer.DEFAULT_PREVIEW_ANNOTATION_FQNS.contains(rules.previewAnnotation),
      "the stamped annotation must be one the playground's discoverer recognises",
    )
  }

  /**
   * The package line is dropped: in the catalog's package the snippet could reach `internal`
   * members a consumer couldn't.
   */
  @Test
  fun `the catalog package is not carried over`() {
    val text = assertNotNull(cleanAt("""caption = "Highest emphasis""")).text
    assertFalse(text.contains("package ee.schimke.m3catalog"))
  }

  /** A helper the entry point still calls after cleaning is pulled in, so the buffer builds. */
  @Test
  fun `same-file helpers the cleaned body still needs are carried along`() {
    val source =
      """
      package demo

      import androidx.compose.material3.Text
      import androidx.compose.runtime.Composable
      import ee.schimke.m3catalog.Sticker

      @Composable
      private fun Caption(text: String) {
        Text(text)
      }

      @Composable
      fun Card() = Sticker {
        Caption("hello")
      }
      """
        .trimIndent()
    val result =
      assertNotNull(
        PlaygroundSourceCleaner.clean(source, lineIn(source, "Caption(\"hello\")"), rules)
      )
    assertTrue(result.text.contains("private fun Caption"), result.text)
    assertTrue(result.text.indexOf("fun Card") < result.text.indexOf("private fun Caption"))
  }

  /**
   * The fail-safe: dropping the `size` knob would leave `Spacer()`, which doesn't compile, so the
   * pass abandons the rewrite and says so.
   */
  @Test
  fun `a drop that cannot complete is abandoned, not half-applied`() {
    val source =
      """
      package demo

      import androidx.compose.foundation.layout.Spacer
      import androidx.compose.foundation.layout.width
      import androidx.compose.ui.Modifier
      import androidx.compose.runtime.Composable
      import ee.schimke.m3catalog.catalogButtonSize

      @Composable
      fun Row() {
        val size = catalogButtonSize()
        Spacer(Modifier.width(size.iconSpacing))
      }
      """
        .trimIndent()
    val result =
      assertNotNull(PlaygroundSourceCleaner.clean(source, lineIn(source, "Spacer("), rules))
    assertTrue(result.text.contains("val size = catalogButtonSize()"), result.text)
    assertFalse(
      result.text.contains("Spacer()"),
      "emitted an uncompilable Spacer():\n${result.text}",
    )
    assertEquals(listOf("catalogButtonSize"), result.residue)
  }

  /** No anchor, or an anchor the file has moved out from under, means "seed it verbatim". */
  @Test
  fun `an unusable anchor declines rather than guesses`() {
    assertNull(PlaygroundSourceCleaner.clean(buttonsKt, null, rules))
    assertNull(PlaygroundSourceCleaner.clean(buttonsKt, 9_999, rules))
    assertNull(PlaygroundSourceCleaner.clean(buttonsKt, lineOf("package ee.schimke"), rules))
  }

  /** Nothing may be rewritten inside a string or a comment. */
  @Test
  fun `literals and comments are never rewritten`() {
    val source =
      """
      package demo

      import androidx.compose.material3.Text
      import androidx.compose.runtime.Composable
      import ee.schimke.m3catalog.Sticker

      // Sticker is the frame every preview gets.
      @Composable
      fun Note() = Sticker {
        Text("wrapped in a Sticker, counted by counted()")
      }
      """
        .trimIndent()
    val text =
      assertNotNull(PlaygroundSourceCleaner.clean(source, lineIn(source, "Text(\"wrapped"), rules))
        .text
    assertTrue(text.contains("\"wrapped in a Sticker, counted by counted()\""), text)
    assertTrue(text.contains("// Sticker is the frame every preview gets."), text)
    assertTrue(text.contains("= MaterialTheme {"), text)
  }

  /** Generic rules alone: a catalog declaring nothing still loses this repo's annotations. */
  @Test
  fun `generic rules still strip the preview annotations`() {
    val text = assertNotNull(cleanAt("""caption = "Highest emphasis""")).let { it }
    val generic = assertNotNull(cleanAt("""caption = "Highest emphasis"""))
    assertFalse(generic.text.contains("@CatalogComponent"))
    // GENERIC knows only about THIS repo's own API — the preview-override knobs — and nothing about
    // any catalog's scaffolding.
    assertTrue(UsageRules.GENERIC.scaffolds.keys.all { it.startsWith("previewOverride") })
    val withGeneric =
      assertNotNull(
        PlaygroundSourceCleaner.clean(
          buttonsKt,
          lineOf("""caption = "Highest emphasis"""),
          UsageRules.GENERIC,
        )
      )
    assertFalse(withGeneric.text.contains("@CatalogComponent"), withGeneric.text)
    assertTrue(withGeneric.text.contains("Sticker {"), "generic rules must not invent a rename")
    assertTrue(text.text.isNotEmpty())
  }

  @Test
  fun `malformed rules degrade to generic rather than failing the handoff`() {
    assertNull(UsageRules.parse("{ not json"))
    assertNotNull(UsageRules.parse("""{"scaffoldAnnotationPackages":["a.b"]}"""))
  }

  /**
   * One unreadable rule must not cost a catalog every other rule. A rules file may be older or
   * newer than the server; an unknown enum value throws and [UsageRules.parse] turns any throw into
   * "no rules", so a retired kind would silently drop all rules back to GENERIC.
   */
  @Test
  fun `a rule kind this build does not know costs only that rule`() {
    val rules =
      assertNotNull(
        UsageRules.parse(
          """
          {
            "scaffolds": {
              "Sticker": { "kind": "RENAME", "renameTo": "MaterialTheme" },
              "catalogButtonSize": { "kind": "DROP" },
              "toggleable": { "kind": "DESTRUCTURE", "plain": "x", "setter": "y" }
            }
          }
          """
            .trimIndent()
        )
      )
    // The rules either side of the retired one survived, which is the whole point.
    assertEquals(UsageRules.Kind.RENAME, rules.scaffolds["Sticker"]?.kind)
    assertEquals("MaterialTheme", rules.scaffolds["Sticker"]?.renameTo)
    assertEquals(UsageRules.Kind.DROP, rules.scaffolds["catalogButtonSize"]?.kind)
    assertEquals(UsageRules.Kind.UNKNOWN, rules.scaffolds["toggleable"]?.kind)
  }

  @Test
  fun `the reusable system Material 3 theme special parses without catalog-authored imports`() {
    val rules =
      assertNotNull(
        UsageRules.parse(
          """{"scaffolds":{"Sticker":{"kind":"RENAME","renameTo":"MaterialTheme","special":"MATERIAL3_SYSTEM_THEME"}}}"""
        )
      )

    val sticker = assertNotNull(rules.scaffolds["Sticker"])
    assertEquals(UsageRules.Kind.RENAME, sticker.kind)
    assertEquals(UsageRules.MATERIAL3_SYSTEM_THEME, sticker.special)
    assertEquals(
      listOf(
        "androidx.compose.foundation.isSystemInDarkTheme",
        "androidx.compose.material3.MaterialTheme",
        "androidx.compose.material3.darkColorScheme",
        "androidx.compose.material3.lightColorScheme",
      ),
      sticker.imports,
    )
  }

  @Test
  fun `an unknown rename special is residue instead of an incomplete rename`() {
    val source =
      """
      import androidx.compose.runtime.Composable
      import com.example.Sticker

      @Composable
      fun CardPreview() = Sticker { Unit }
      """
        .trimIndent()
    val rules =
      UsageRules(
        scaffolds =
          mapOf(
            "Sticker" to
              UsageRules.Scaffold(
                kind = UsageRules.Kind.RENAME,
                renameTo = "MaterialTheme",
                special = "FUTURE_THEME_POLICY",
              )
          )
      )

    val result =
      assertNotNull(
        PlaygroundSourceCleaner.clean(
          source,
          source.lines().indexOfFirst { it.contains("Unit") } + 1,
          rules,
        )
      )

    assertTrue(result.text.contains("Sticker { Unit }"), result.text)
    assertFalse(result.text.contains("MaterialTheme"), result.text)
    assertEquals(listOf("Sticker"), result.residue)
  }

  /**
   * Being lenient about unknown kinds isn't leniency about the rest: an `INLINE` rule with a null
   * member map must still take the GENERIC fallback, or `applyInline` would delete the binding and
   * orphan its references.
   */
  @Test
  fun `tolerating an unknown kind does not make the rest of the document forgiving`() {
    assertNull(UsageRules.parse("""{"scaffolds":{"counted":{"kind":"INLINE","members":null}}}"""))
    assertNull(UsageRules.parse("""{"scaffoldPackages":null}"""))
  }

  /**
   * A helper no pass will rewrite must not be unqualified: stripping the qualifier off an
   * unimported call breaks code that resolved.
   */
  @Test
  fun `an unknown kind keeps the package qualifier that makes it resolve`() {
    val rules =
      assertNotNull(
        UsageRules.parse(
          """
          {
            "scaffoldPackages": ["ee.schimke.m3catalog"],
            "scaffolds": { "toggleable": { "kind": "DESTRUCTURE" } }
          }
          """
            .trimIndent()
        )
      )
    val source =
      """
      package ee.schimke.m3catalog.sections

      import androidx.compose.material3.Switch
      import androidx.compose.runtime.Composable

      @Composable
      fun SwitchSticker() {
        var checked by ee.schimke.m3catalog.toggleable(true)
        Switch(checked = checked, onCheckedChange = { checked = it })
      }
      """
        .trimIndent()
    val result =
      assertNotNull(
        PlaygroundSourceCleaner.clean(source, lineIn(source, "fun SwitchSticker"), rules)
      )
    assertTrue(result.text.contains("ee.schimke.m3catalog.toggleable(true)"), result.text)
    assertTrue(result.residue.contains("toggleable"), "${result.residue}")
  }

  /** An unknown kind fires no pass, so the helper survives the clean and is reported as residue. */
  @Test
  fun `a helper whose kind is unknown is left alone and reported`() {
    val rules =
      assertNotNull(UsageRules.parse("""{"scaffolds":{"toggleable":{"kind":"DESTRUCTURE"}}}"""))
    val source =
      """
      package ee.schimke.m3catalog.sections

      import androidx.compose.material3.Switch
      import androidx.compose.runtime.Composable
      import ee.schimke.m3catalog.toggleable

      @Composable
      fun SwitchSticker() {
        var checked by toggleable(true)
        Switch(checked = checked, onCheckedChange = { checked = it })
      }
      """
        .trimIndent()
    val result =
      assertNotNull(
        PlaygroundSourceCleaner.clean(source, lineIn(source, "fun SwitchSticker"), rules)
      )
    assertTrue(result.text.contains("toggleable(true)"), result.text)
    assertTrue(result.residue.contains("toggleable"), "${result.residue}")
  }

  /**
   * Preview-override knobs are this repo's API, so a catalog declaring its own scaffolding still
   * gets the generic rules (declaring `compose-usage.json` used to replace them).
   */
  @Test
  fun `declared rules inherit the generic ones`() {
    val rules = assertNotNull(UsageRules.parse("""{"scaffolds":{"Sticker":{"kind":"UNWRAP"}}}"""))
    assertTrue(rules.scaffolds.containsKey("Sticker"))
    assertTrue(rules.scaffolds.containsKey("previewOverrideString"))
    assertTrue(rules.scaffoldAnnotationPackages.contains("ee.schimke.composeai.preview"))
  }

  /**
   * `previewOverrideString` isn't in a scaffold package, so residue can't see an undeclared knob;
   * found by the corpus (`scripts/usage-corpus.sh`; see `docs/design/USAGE_SNIPPET_CORPUS.md`).
   */
  @Test
  fun `a preview override knob becomes the default the render was baked with`() {
    val source =
      """
      package ee.schimke.demo

      import androidx.compose.material3.Badge
      import androidx.compose.material3.Text
      import androidx.compose.runtime.Composable
      import ee.schimke.composeai.preview.previewOverrideString

      @Composable
      fun NumberBadge() = Badge { Text(previewOverrideString("label", "3")) }
      """
        .trimIndent()
    val cleaned =
      assertNotNull(
        PlaygroundSourceCleaner.clean(source, lineIn(source, "fun NumberBadge"), UsageRules.GENERIC)
      )
    assertTrue(cleaned.text.contains("""Text("3")"""), cleaned.text)
    assertFalse(cleaned.text.contains("previewOverrideString"), cleaned.text)
  }

  /**
   * The same knob with named arguments; a positional reading emits non-compiling `Text(default =
   * "Shopping")`.
   */
  @Test
  fun `a named-argument knob substitutes as its value, not as the argument label`() {
    val source =
      """
      package ee.schimke.demo

      import androidx.compose.material3.Text
      import androidx.compose.runtime.Composable
      import ee.schimke.composeai.preview.previewOverrideString

      @Composable
      fun Title() = Text(previewOverrideString(key = "title", default = "Shopping"))
      """
        .trimIndent()
    val cleaned =
      assertNotNull(
        PlaygroundSourceCleaner.clean(source, lineIn(source, "fun Title"), UsageRules.GENERIC)
      )
    assertTrue(cleaned.text.contains("""Text("Shopping")"""), cleaned.text)
  }

  /** A package-qualified call is the same call; it used to be neither rewritten nor reported. */
  @Test
  fun `a fully qualified knob call is rewritten like a bare one`() {
    val source =
      """
      package ee.schimke.demo

      import androidx.compose.material3.Text
      import androidx.compose.runtime.Composable

      @Composable
      fun Title() =
        Text(ee.schimke.composeai.overrides.previewOverrideString("title", "Shopping"))
      """
        .trimIndent()
    val cleaned =
      assertNotNull(
        PlaygroundSourceCleaner.clean(source, lineIn(source, "fun Title"), UsageRules.GENERIC)
      )
    assertTrue(cleaned.text.contains("""Text("Shopping")"""), cleaned.text)
    assertFalse(cleaned.text.contains("previewOverrideString"), cleaned.text)
  }

  /**
   * A receiver chain (`state.metrics.counted { }`) is not a package; only packages the rules name
   * are stripped.
   */
  @Test
  fun `a member call that shares a scaffold name is left alone`() {
    val rules =
      UsageRules(
        scaffoldPackages = listOf("ee.schimke.m3catalog"),
        scaffolds = mapOf("counted" to UsageRules.Scaffold(kind = UsageRules.Kind.UNWRAP)),
      )
    val source =
      """
      package ee.schimke.demo

      import androidx.compose.runtime.Composable

      @Composable
      fun Tally() {
        stats.counted { }
        state.metrics.counted { }
      }
      """
        .trimIndent()
    val cleaned =
      assertNotNull(PlaygroundSourceCleaner.clean(source, lineIn(source, "stats.counted"), rules))
    assertTrue(cleaned.text.contains("stats.counted"), cleaned.text)
    assertTrue(cleaned.text.contains("state.metrics.counted"), cleaned.text)
  }

  /** A qualified call the rules can't unqualify must still be reported as residue. */
  @Test
  fun `an unlisted qualified scaffold call is reported as residue`() {
    val rules =
      UsageRules(scaffolds = mapOf("counted" to UsageRules.Scaffold(kind = UsageRules.Kind.UNWRAP)))
    val source =
      """
      package ee.schimke.demo

      import androidx.compose.runtime.Composable

      @Composable
      fun Tally() = com.acme.counted { }
      """
        .trimIndent()
    val cleaned =
      assertNotNull(PlaygroundSourceCleaner.clean(source, lineIn(source, "fun Tally"), rules))
    assertTrue(cleaned.text.contains("com.acme.counted"), cleaned.text)
    assertTrue(cleaned.residue.contains("counted"), "${cleaned.residue}")
  }

  /**
   * A matching callee name isn't a matching call: `state.previewOverrideString(…)` may be an
   * application's own member. Only bare calls or ones qualified by a named package are scaffolds.
   */
  @Test
  fun `a member call sharing a substitute rule's name keeps its receiver`() {
    val source =
      """
      package ee.schimke.demo

      import androidx.compose.material3.Text
      import androidx.compose.runtime.Composable

      @Composable
      fun Title() {
        Text(state.previewOverrideString("title", "Shopping"))
        Text(ee.schimke.composeai.overrides.previewOverrideString("subtitle", "Basket"))
      }
      """
        .trimIndent()
    val cleaned =
      assertNotNull(
        PlaygroundSourceCleaner.clean(source, lineIn(source, "fun Title"), UsageRules.GENERIC)
      )
    assertTrue(
      cleaned.text.contains("state.previewOverrideString(\"title\", \"Shopping\")"),
      "an unrelated member call was rewritten: ${cleaned.text}",
    )
    // The package-qualified one is the scaffold, and is substituted.
    assertTrue(cleaned.text.contains("Text(\"Basket\")"), cleaned.text)
  }

  /**
   * `addImports` applies to every kind that emits a replacement, e.g. SUBSTITUTE emitting
   * `remember` / `mutableStateOf` needs their imports. Caught by the corpus gate.
   */
  @Test
  fun `a substituted call contributes every import its rule declares`() {
    val rules =
      UsageRules(
        scaffolds =
          mapOf(
            "toggleable" to
              UsageRules.Scaffold(
                kind = UsageRules.Kind.SUBSTITUTE,
                params = listOf("initial"),
                plain = "remember { mutableStateOf(\$0) }",
                addImports =
                  listOf(
                    "androidx.compose.runtime.getValue",
                    "androidx.compose.runtime.mutableStateOf",
                    "androidx.compose.runtime.remember",
                    "androidx.compose.runtime.setValue",
                  ),
              )
          )
      )
    val source =
      """
      package ee.schimke.m3catalog.sections

      import androidx.compose.material3.Switch
      import androidx.compose.runtime.Composable
      import androidx.compose.runtime.getValue
      import androidx.compose.runtime.setValue
      import ee.schimke.m3catalog.toggleable

      @Composable
      fun SwitchSticker() {
        var checked by toggleable(true)
        Switch(checked = checked, onCheckedChange = { checked = it })
      }
      """
        .trimIndent()
    val result =
      assertNotNull(
        PlaygroundSourceCleaner.clean(source, lineIn(source, "fun SwitchSticker"), rules)
      )
    assertTrue(
      result.text.contains("var checked by remember { mutableStateOf(true) }"),
      result.text,
    )
    for (import in listOf("getValue", "setValue", "remember", "mutableStateOf")) {
      assertTrue(result.text.contains("import androidx.compose.runtime.$import"), result.text)
    }
    assertFalse(result.text.contains("toggleable"), result.text)
    assertEquals(emptyList(), result.residue)
  }

  /** INLINE member templates are arbitrary Kotlin and can need imports too. */
  @Test
  fun `an inlined member contributes the imports its rule declares`() {
    val rules =
      UsageRules(
        scaffolds =
          mapOf(
            "counted" to
              UsageRules.Scaffold(
                kind = UsageRules.Kind.INLINE,
                members = mapOf("label" to "\$0", "onClick" to "{ Log.d(\"tap\") }"),
                addImport = "android.util.Log",
              )
          )
      )
    val source =
      """
      package ee.schimke.m3catalog.sections

      import androidx.compose.material3.Button
      import androidx.compose.material3.Text
      import androidx.compose.runtime.Composable
      import ee.schimke.m3catalog.counted

      @Composable
      fun ButtonSticker() {
        val c = counted("Filled")
        Button(onClick = c.onClick) { Text(c.label) }
      }
      """
        .trimIndent()
    val result =
      assertNotNull(
        PlaygroundSourceCleaner.clean(source, lineIn(source, "fun ButtonSticker"), rules)
      )
    assertTrue(
      result.text.contains("""Button(onClick = { Log.d("tap") }) { Text("Filled") }"""),
      result.text,
    )
    assertTrue(result.text.contains("import android.util.Log"), result.text)
    assertEquals(emptyList(), result.residue)
  }

  /** Named arguments out of declaration order still bind by name, as Kotlin binds them. */
  @Test
  fun `a knob with reordered named arguments still resolves its default`() {
    val source =
      """
      package ee.schimke.demo

      import androidx.compose.material3.Text
      import androidx.compose.runtime.Composable
      import ee.schimke.composeai.preview.previewOverrideString

      @Composable
      fun Title() = Text(previewOverrideString(default = "Shopping", key = "title"))
      """
        .trimIndent()
    val cleaned =
      assertNotNull(
        PlaygroundSourceCleaner.clean(source, lineIn(source, "fun Title"), UsageRules.GENERIC)
      )
    assertTrue(cleaned.text.contains("""Text("Shopping")"""), cleaned.text)
  }

  /**
   * A rule without `params` can't map a named argument, so it declines (reported as residue) rather
   * than guessing.
   */
  @Test
  fun `a substitute rule without params declines a named-argument call`() {
    val rules =
      UsageRules(
        scaffolds =
          mapOf(
            "catalogChoice" to UsageRules.Scaffold(kind = UsageRules.Kind.SUBSTITUTE, plain = "\$1")
          )
      )
    val source =
      """
      package ee.schimke.demo

      import androidx.compose.material3.Text
      import androidx.compose.runtime.Composable

      @Composable
      fun Style() = Text(catalogChoice(key = "style", default = "outlined"))
      """
        .trimIndent()
    val cleaned =
      assertNotNull(PlaygroundSourceCleaner.clean(source, lineIn(source, "fun Style"), rules))
    assertTrue(cleaned.text.contains("catalogChoice("), cleaned.text)
    assertTrue(cleaned.residue.contains("catalogChoice"), "${cleaned.residue}")
  }

  /**
   * `scaffoldsDeclared` drives a strong claim in the Source panel, so only a catalog can turn it on
   * (not GENERIC's own entries).
   */
  @Test
  fun `inheriting the generic rules is not declaring scaffolding`() {
    with(UsageRules.Companion) {
      assertFalse(UsageRules.GENERIC.declaresCatalogScaffolds())
      assertFalse(assertNotNull(UsageRules.parse("{}")).declaresCatalogScaffolds())
      assertTrue(
        assertNotNull(UsageRules.parse("""{"scaffolds":{"Sticker":{"kind":"UNWRAP"}}}"""))
          .declaresCatalogScaffolds()
      )
    }
  }

  /**
   * A knob with a plain reading is substituted with its default (what the baked render used), not
   * deleted.
   */
  @Test
  fun `a choice knob becomes the value the render was baked with`() {
    val choiceRules =
      rules.copy(
        scaffolds =
          rules.scaffolds +
            ("catalogChoice" to
              UsageRules.Scaffold(kind = UsageRules.Kind.SUBSTITUTE, plain = "\$1"))
      )
    val source =
      """
      package demo

      import androidx.compose.material3.AssistChip
      import androidx.compose.material3.Text
      import androidx.compose.runtime.Composable
      import ee.schimke.m3catalog.Sticker
      import ee.schimke.m3catalog.catalogChoice

      @Composable
      fun Chip() = Sticker {
        val style = catalogChoice("style", "outlined", "outlined", "elevated")
        Text(style)
      }
      """
        .trimIndent()
    val result =
      assertNotNull(PlaygroundSourceCleaner.clean(source, lineIn(source, "val style"), choiceRules))
    assertTrue(result.text.contains("""val style = "outlined""""), result.text)
    assertEquals(emptyList(), result.residue)
  }

  /**
   * A template citing an argument the call doesn't carry leaves the call alone rather than emitting
   * `$1`.
   */
  @Test
  fun `a substitution template that cannot be filled is declined`() {
    val badRules =
      rules.copy(
        scaffolds =
          mapOf(
            "catalogChoice" to UsageRules.Scaffold(kind = UsageRules.Kind.SUBSTITUTE, plain = "\$7")
          )
      )
    val source =
      """
      package demo

      import androidx.compose.material3.Text
      import androidx.compose.runtime.Composable
      import ee.schimke.m3catalog.catalogChoice

      @Composable
      fun Chip() {
        Text(catalogChoice("style", "outlined"))
      }
      """
        .trimIndent()
    val text =
      assertNotNull(PlaygroundSourceCleaner.clean(source, lineIn(source, "Text("), badRules)).text
    assertFalse(text.contains("\$7"), text)
    assertTrue(text.contains("catalogChoice(\"style\", \"outlined\")"), text)
  }

  // -----------------------------------------------------------------------------------------
  // Regressions found by review of the first cut. Each of these produced a seed that was
  // advertised as runnable and did not compile — the one failure mode the design says it must
  // not have. They survived the original fixture because ktfmt had wrapped its calls, putting
  // every knob on a line of its own where the buggy behaviour happened to be correct.
  // -----------------------------------------------------------------------------------------

  /**
   * Imported extensions are referenced after a dot (`padding`, `dp`), so import pruning must not
   * use the strict word test.
   */
  @Test
  fun `imports used through receiver syntax survive the prune`() {
    val source =
      """
      package demo

      import androidx.compose.foundation.layout.padding
      import androidx.compose.material3.Text
      import androidx.compose.runtime.Composable
      import androidx.compose.ui.Modifier
      import androidx.compose.ui.unit.dp
      import ee.schimke.m3catalog.Sticker

      @Composable
      fun Padded() = Sticker {
        Text("hi", modifier = Modifier.padding(16.dp))
      }
      """
        .trimIndent()
    val text =
      assertNotNull(PlaygroundSourceCleaner.clean(source, lineIn(source, "Text(\"hi\""), rules))
        .text
    assertTrue(text.contains("import androidx.compose.foundation.layout.padding"), text)
    assertTrue(text.contains("import androidx.compose.ui.unit.dp"), text)
    assertTrue(text.contains("import androidx.compose.ui.Modifier"), text)
  }

  /** An aliased import must keep both the name the body uses and its `as` clause. */
  @Test
  fun `aliased imports keep their alias`() {
    val source =
      """
      package demo

      import androidx.compose.material3.Text as Label
      import androidx.compose.runtime.Composable
      import ee.schimke.m3catalog.Sticker

      @Composable
      fun Aliased() = Sticker {
        Label("hi")
      }
      """
        .trimIndent()
    val text =
      assertNotNull(PlaygroundSourceCleaner.clean(source, lineIn(source, "Label(\"hi\")"), rules))
        .text
    assertTrue(text.contains("import androidx.compose.material3.Text as Label"), text)
  }

  /**
   * The worst of them. A ktfmt-legal one-line call with a DROP helper in a named argument was
   * deleted whole, because the unbound call reported a "binding" whose line range was the entire
   * call's line — leaving an empty themed preview, with no residue to show for it.
   */
  @Test
  fun `a drop helper on a one-line call loses only its argument`() {
    val source =
      """
      package demo

      import androidx.compose.material3.Button
      import androidx.compose.material3.Text
      import androidx.compose.runtime.Composable
      import ee.schimke.m3catalog.Sticker
      import ee.schimke.m3catalog.catalogEnabled

      @Composable
      fun One() = Sticker {
        Button(onClick = {}, enabled = catalogEnabled()) { Text("Go") }
      }
      """
        .trimIndent()
    val result =
      assertNotNull(PlaygroundSourceCleaner.clean(source, lineIn(source, "Button("), rules))
    assertTrue(result.text.contains("""Button(onClick = {}) { Text("Go") }"""), result.text)
    assertEquals(emptyList(), result.residue)
  }

  /** An UNWRAP helper as the expression body must not take `fun Card() =` with it. */
  @Test
  fun `unwrapping an expression body keeps the declaration`() {
    val source =
      """
      package demo

      import androidx.compose.material3.Text
      import androidx.compose.runtime.Composable
      import ee.schimke.m3catalog.ButtonFrame

      @Composable
      fun Card() = ButtonFrame(2) {
        Text("inside")
      }
      """
        .trimIndent()
    val text =
      assertNotNull(
          PlaygroundSourceCleaner.clean(source, lineIn(source, "Text(\"inside\")"), rules)
        )
        .text
    assertTrue(text.contains("fun Card() ="), text)
    assertFalse(text.contains("ButtonFrame"), text)
  }

  /** A same-file `data class` the body still needs must come along with it. */
  @Test
  fun `modified type declarations are recognised by the closure`() {
    val source =
      """
      package demo

      import androidx.compose.material3.Text
      import androidx.compose.runtime.Composable
      import ee.schimke.m3catalog.Sticker

      data class Model(val label: String)

      @Composable
      fun Row() = Sticker {
        Text(Model("hi").label)
      }
      """
        .trimIndent()
    val text =
      assertNotNull(PlaygroundSourceCleaner.clean(source, lineIn(source, "Text(Model"), rules)).text
    assertTrue(text.contains("data class Model(val label: String)"), text)
  }

  /** A wrapped `@file:OptIn(...)` must be emitted whole, not as its opening line. */
  @Test
  fun `a multiline file annotation survives intact`() {
    val source =
      """
      @file:OptIn(
        ExperimentalMaterial3ExpressiveApi::class,
        ExperimentalMaterial3Api::class,
      )

      package demo

      import androidx.compose.material3.ExperimentalMaterial3Api
      import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
      import androidx.compose.material3.Text
      import androidx.compose.runtime.Composable
      import ee.schimke.m3catalog.Sticker

      @Composable
      fun Opted() = Sticker {
        Text("hi")
      }
      """
        .trimIndent()
    val text =
      assertNotNull(PlaygroundSourceCleaner.clean(source, lineIn(source, "Text(\"hi\")"), rules))
        .text
    assertTrue(text.contains("ExperimentalMaterial3Api::class,\n)"), text)
    assertTrue(text.contains("import androidx.compose.material3.ExperimentalMaterial3Api"), text)
  }

  /** String-resource inlining is masked like every other pass. */
  @Test
  fun `a resource lookup quoted inside a literal is not substituted`() {
    val source =
      """
      package demo

      import androidx.compose.material3.Text
      import androidx.compose.runtime.Composable
      import ee.schimke.m3catalog.Sticker

      @Composable
      fun Doc() = Sticker {
        Text("Use stringResource(Res.string.label_filled) for the label")
      }
      """
        .trimIndent()
    val text =
      assertNotNull(
          PlaygroundSourceCleaner.clean(source, lineIn(source, "Use string"), rules, strings)
        )
        .text
    assertTrue(
      text.contains("""Text("Use stringResource(Res.string.label_filled) for the label")"""),
      text,
    )
  }

  /** An INLINE template citing an argument the call lacks must not delete the binding either. */
  @Test
  fun `an inline template that cannot be filled leaves the code alone`() {
    val badRules =
      rules.copy(
        scaffolds =
          mapOf(
            "counted" to
              UsageRules.Scaffold(kind = UsageRules.Kind.INLINE, members = mapOf("label" to "\$3"))
          )
      )
    val source =
      """
      package demo

      import androidx.compose.material3.Text
      import androidx.compose.runtime.Composable
      import ee.schimke.m3catalog.counted

      @Composable
      fun Tally() {
        val c = counted("Filled")
        Text(c.label)
      }
      """
        .trimIndent()
    val result =
      assertNotNull(PlaygroundSourceCleaner.clean(source, lineIn(source, "val c ="), badRules))
    assertFalse(result.text.contains("\$3"), result.text)
    assertTrue(result.text.contains("""val c = counted("Filled")"""), result.text)
    assertEquals(listOf("counted"), result.residue)
  }

  // Delegating catalogs: scaffold sources, EXPAND, and string-keyed `when` dispatch.

  /** A sticker sheet whose previews are one-line delegations, like `:samples:design-catalog-m3`. */
  private val delegatingPreviews =
    """
    package demo.catalog

    import androidx.compose.runtime.Composable
    import demo.catalog.shared.CatalogComponent
    import ee.schimke.composeai.preview.CatalogComponent

    @CatalogComponent(id = "Button/Filled", group = "Buttons")
    @CatalogModes
    @Composable
    fun FilledButton() = Sticker("button-filled")
    """
      .trimIndent()

  private val stickerHelper =
    """
    package demo.catalog

    import androidx.compose.runtime.Composable
    import demo.catalog.shared.CatalogComponent

    @Composable fun Sticker(id: String) = CatalogSticker { CatalogComponent(id) }
    """
      .trimIndent()

  private val sharedComponents =
    """
    package demo.catalog.shared

    import androidx.compose.material3.Button
    import androidx.compose.material3.Text
    import androidx.compose.runtime.Composable

    @Composable
    fun CatalogComponent(id: String) {
      when (id) {
        // The tally is what makes a stateless button do something visible.
        "button-filled" -> {
          val label = "Filled"
          Button(onClick = {}) { Text(label) }
        }
        "button-text" ->
          TextButton(onClick = {}) {
            Text("Text")
          }
        else -> Text("unknown")
      }
    }
    """
      .trimIndent()

  private val delegatingRules =
    UsageRules(
      scaffoldAnnotationPackages = listOf("ee.schimke.composeai.preview"),
      scaffoldAnnotationNames = listOf("CatalogModes"),
      scaffoldSources = listOf("a/Stickers.kt", "b/Components.kt"),
      scaffolds =
        mapOf(
          "Sticker" to UsageRules.Scaffold(kind = UsageRules.Kind.EXPAND),
          "CatalogComponent" to UsageRules.Scaffold(kind = UsageRules.Kind.EXPAND),
          "CatalogSticker" to UsageRules.Scaffold(kind = UsageRules.Kind.UNWRAP),
        ),
    )

  private fun cleanDelegating(
    needle: String,
    rules: UsageRules = delegatingRules,
    helpers: List<String> = listOf(stickerHelper, sharedComponents),
  ) =
    PlaygroundSourceCleaner.clean(
      source = delegatingPreviews,
      bodyLine = lineIn(delegatingPreviews, needle),
      rules = rules,
      parser = null,
      helperSources = helpers,
    )

  /**
   * A delegation-only preview has no component, so the reduction crosses into the file the
   * component lives in.
   */
  @Test
  fun `a delegating preview expands to the component the shared set dispatches to`() {
    val result = assertNotNull(cleanDelegating("fun FilledButton"))

    assertTrue(result.text.contains("""Button(onClick = {}) { Text(label) }"""), result.text)
    // Only the branch that was asked for: expanding the dispatch whole would substitute the entire
    // catalog for the one component.
    assertFalse(result.text.contains("TextButton"), result.text)
    assertFalse(result.text.contains("unknown"), result.text)
    assertFalse(result.text.contains("when ("), result.text)
    // Neither wrapper survives, and the expression body became a block one so two statements fit.
    assertFalse(result.text.contains("Sticker"), result.text)
    assertFalse(result.text.contains("CatalogComponent"), result.text)
    assertTrue(result.text.contains("fun FilledButton() {"), result.text)
    // The annotation the catalog declares in the previews' own package — no import to resolve it
    // through — comes off too, and a real `@Preview` replaces it.
    assertFalse(result.text.contains("@CatalogModes"), result.text)
    assertTrue(result.text.contains("@Preview"), result.text)
    // The imports the spliced body needs come from the file the body came from.
    assertTrue(result.text.contains("import androidx.compose.material3.Button"), result.text)
    assertEquals(emptyList(), result.residue, result.text)
  }

  /**
   * A repo publishing several catalogs may declare one name twice; picking either would mix
   * catalogs.
   */
  @Test
  fun `a name two scaffold sources both declare is not expanded`() {
    val rival =
      """
      package demo.other

      import androidx.compose.runtime.Composable

      @Composable fun Sticker(id: String) = Text("some other catalog's sticker")
      """
        .trimIndent()
    val result =
      assertNotNull(
        cleanDelegating(
          "fun FilledButton",
          helpers = listOf(stickerHelper, sharedComponents, rival),
        )
      )

    assertTrue(result.text.contains("""Sticker("button-filled")"""), result.text)
    assertFalse(result.text.contains("some other catalog"), result.text)
    assertTrue(result.residue.contains("Sticker"), result.residue.toString())
  }

  /** No scaffold sources ⇒ exactly the behaviour that shipped before EXPAND existed. */
  @Test
  fun `an EXPAND rule with nothing to read leaves the call alone`() {
    val result = assertNotNull(cleanDelegating("fun FilledButton", helpers = emptyList()))

    assertTrue(result.text.contains("""Sticker("button-filled")"""), result.text)
    assertFalse(result.text.contains("fun FilledButton() {"), result.text)
  }

  /** A dispatch key not pinned to a literal can't select one branch; don't guess. */
  @Test
  fun `a dispatch whose key is not a literal is declined and reported`() {
    val computed =
      """
      package demo.catalog

      import androidx.compose.runtime.Composable
      import demo.catalog.shared.CatalogComponent

      @Composable
      fun FilledButton(slug: String) {
        CatalogComponent(slug)
      }
      """
        .trimIndent()
    val result =
      assertNotNull(
        PlaygroundSourceCleaner.clean(
          source = computed,
          bodyLine = lineIn(computed, "CatalogComponent(slug)"),
          rules = delegatingRules,
          parser = null,
          helperSources = listOf(stickerHelper, sharedComponents),
        )
      )

    assertTrue(result.text.contains("CatalogComponent(slug)"), result.text)
    assertTrue(result.residue.contains("CatalogComponent"), result.residue.toString())
  }

  /**
   * A cross-file helper with the same name as the preview (`fun SegmentedToggle() =
   * Sticker("segmentedbutton")`, whose branch calls `SegmentedToggle()`). The entry name must not
   * satisfy the reference: after EXPAND its body is the sticker's, so the call targets the
   * component, not itself (otherwise unbounded recursion reported clean).
   */
  @Test
  fun `a helper sharing the entry preview's name is renamed rather than skipped`() {
    val previews =
      """
      package demo.catalog

      import androidx.compose.runtime.Composable

      @CatalogModes
      @Composable
      fun SegmentedToggle() = Sticker("segmentedbutton")
      """
        .trimIndent()
    val components =
      """
      package demo.catalog.shared

      import androidx.compose.material3.Text
      import androidx.compose.runtime.Composable

      @Composable
      fun CatalogComponent(id: String) {
        when (id) {
          "segmentedbutton" -> SegmentedToggle()
          else -> Text("unknown")
        }
      }

      @Composable
      fun SegmentedToggle() {
        Text("Segmented")
      }
      """
        .trimIndent()

    val result =
      assertNotNull(
        PlaygroundSourceCleaner.clean(
          source = previews,
          bodyLine = lineIn(previews, "fun SegmentedToggle"),
          rules = delegatingRules,
          parser = null,
          helperSources = listOf(stickerHelper, components),
        )
      )

    // The component came along, under a name that does not collide...
    assertTrue(result.text.contains("fun SegmentedToggleComponent"), result.text)
    assertTrue(result.text.contains("""Text("Segmented")"""), result.text)
    // ...the preview keeps the name the reader clicked...
    assertTrue(result.text.contains("fun SegmentedToggle()"), result.text)
    // ...and it calls the component rather than itself.
    assertTrue(result.text.contains("SegmentedToggleComponent()"), result.text)
    assertFalse(
      result.text.substringAfter("fun SegmentedToggle()").contains("\n  SegmentedToggle()"),
      "the preview must not call itself:\n${result.text}",
    )
    assertEquals(emptyList(), result.residue, result.text)
  }

  /** A preview merely sharing a name with an uncalled shared-module declaration is untouched. */
  @Test
  fun `a name shared with an uncalled helper is left alone`() {
    val previews =
      """
      package demo.catalog

      import androidx.compose.runtime.Composable

      @CatalogModes
      @Composable
      fun FilledButton() = Sticker("button-filled")
      """
        .trimIndent()
    val components =
      """
      package demo.catalog.shared

      import androidx.compose.material3.Button
      import androidx.compose.material3.Text
      import androidx.compose.runtime.Composable

      @Composable
      fun CatalogComponent(id: String) {
        when (id) {
          "button-filled" -> {
            val label = "Filled"
            Button(onClick = {}) { Text(label) }
          }
          else -> Text("unknown")
        }
      }

      @Composable
      fun FilledButton() {
        Text("not the one the sticker dispatches to")
      }
      """
        .trimIndent()

    val result =
      assertNotNull(
        PlaygroundSourceCleaner.clean(
          source = previews,
          bodyLine = lineIn(previews, "fun FilledButton"),
          rules = delegatingRules,
          parser = null,
          helperSources = listOf(stickerHelper, components),
        )
      )

    // The branch inlines the button directly, so nothing calls `FilledButton` and no rename
    // happens.
    assertTrue(result.text.contains("fun FilledButton() {"), result.text)
    assertFalse(result.text.contains("FilledButtonComponent"), result.text)
  }

  /**
   * A scaffold annotation on the same line as the declaration (`@CatalogModes @Composable fun
   * TextBrandedSpecimen() = …`): the line-based stripper discarded the whole function.
   */
  @Test
  fun `a scaffold annotation sharing a line with the declaration takes only itself`() {
    val source =
      """
      package demo.catalog

      import androidx.compose.runtime.Composable

      @CatalogModes @Composable fun TextBrandedSpecimen() = Sticker("text-branded")
      """
        .trimIndent()
    val components =
      """
      package demo.catalog.shared

      import androidx.compose.material3.Text
      import androidx.compose.runtime.Composable

      @Composable
      fun CatalogComponent(id: String) {
        when (id) {
          "text-branded" -> Text("Branded")
          else -> Text("unknown")
        }
      }
      """
        .trimIndent()

    val result =
      assertNotNull(
        PlaygroundSourceCleaner.clean(
          source = source,
          bodyLine = lineIn(source, "fun TextBrandedSpecimen"),
          rules = delegatingRules,
          parser = null,
          helperSources = listOf(stickerHelper, components),
        )
      )

    // The scaffold annotation goes, everything else on that line stays, and the delegation still
    // reduces to the component behind it.
    assertFalse(result.text.contains("@CatalogModes"), result.text)
    assertTrue(result.text.contains("@Composable"), result.text)
    assertTrue(result.text.contains("fun TextBrandedSpecimen()"), result.text)
    assertTrue(result.text.contains("""Text("Branded")"""), result.text)
    assertFalse(result.text.contains("Sticker("), result.text)
  }

  /** An annotation with arguments on a shared line — the span to remove is not just a word. */
  @Test
  fun `only the matched annotation span is removed when it carries arguments`() {
    val source =
      """
      package demo.catalog

      import androidx.compose.runtime.Composable
      import ee.schimke.composeai.preview.CatalogComponent

      @CatalogComponent(id = "Text/Branded", group = "Text") @Composable fun Branded() = Sticker("text-branded")
      """
        .trimIndent()
    val components =
      """
      package demo.catalog.shared

      import androidx.compose.material3.Text
      import androidx.compose.runtime.Composable

      @Composable
      fun CatalogComponent(id: String) {
        when (id) {
          "text-branded" -> Text("Branded")
          else -> Text("unknown")
        }
      }
      """
        .trimIndent()

    val result =
      assertNotNull(
        PlaygroundSourceCleaner.clean(
          source = source,
          bodyLine = lineIn(source, "fun Branded"),
          rules = delegatingRules,
          parser = null,
          helperSources = listOf(stickerHelper, components),
        )
      )

    assertFalse(result.text.contains("""id = "Text/Branded""""), result.text)
    assertTrue(result.text.contains("@Composable"), result.text)
    assertTrue(result.text.contains("fun Branded()"), result.text)
    assertTrue(result.text.contains("""Text("Branded")"""), result.text)
  }

  /**
   * An extension helper in a scaffold source: `declaredName` took the receiver (`Morph` in `fun
   * Morph.toComposePath`), and `mentionsWord` rejects names after `.`, so `toComposePath` was left
   * unresolved and unreported.
   */
  @Test
  fun `an extension helper is indexed by its callable name and followed through a receiver call`() {
    val source =
      """
      package demo.catalog

      import androidx.compose.runtime.Composable

      @CatalogModes
      @Composable
      fun ShapeMorph() = Sticker("shape-morph")
      """
        .trimIndent()
    val components =
      """
      package demo.catalog.shared

      import androidx.compose.runtime.Composable

      @Composable
      fun CatalogComponent(id: String) {
        when (id) {
          "shape-morph" -> ShapeMorphViewer()
          else -> Unit
        }
      }

      @Composable
      fun ShapeMorphViewer() {
        val morph = rememberMorph()
        val path = morph.toComposePath(0.5f)
        Draw(path)
      }

      private fun Morph.toComposePath(progress: Float): String = "path at " + progress
      """
        .trimIndent()

    val result =
      assertNotNull(
        PlaygroundSourceCleaner.clean(
          source = source,
          bodyLine = lineIn(source, "fun ShapeMorph"),
          rules = delegatingRules,
          parser = null,
          helperSources = listOf(stickerHelper, components),
        )
      )

    assertTrue(result.text.contains("fun ShapeMorphViewer"), result.text)
    assertTrue(
      result.text.contains("fun Morph.toComposePath"),
      "the extension the snippet calls was left behind:\n${result.text}",
    )
    assertEquals(emptyList(), result.residue, result.text)
  }

  /**
   * The receiver-aware check must not pull in a same-named helper that is only read, not called.
   */
  @Test
  fun `a property read on a receiver does not pull in a same-named function`() {
    assertTrue(
      PlaygroundSourceCleaner.mentionsExtensionCall("morph.toComposePath(0.5f)", "toComposePath")
    )
    assertTrue(
      PlaygroundSourceCleaner.mentionsExtensionCall("a.b().toComposePath (x)", "toComposePath")
    )
    assertFalse(
      PlaygroundSourceCleaner.mentionsExtensionCall("state.toComposePath", "toComposePath")
    )
    // A package qualifier is not a receiver call.
    assertFalse(
      PlaygroundSourceCleaner.mentionsExtensionCall("\"morph.toComposePath(1f)\"", "toComposePath")
    )
  }

  private fun lineIn(text: String, needle: String): Int =
    text.lines().indexOfFirst { it.contains(needle) } + 1

  @Test
  fun `a followed declaration left out by its limits is reported as residue`() {
    val preview =
      """
      package com.example.previews

      import androidx.compose.runtime.Composable
      import com.example.samples.HugeSample

      @Composable
      fun HugePreview() = HugeSample()
      """
        .trimIndent()
    val huge =
      "package com.example.samples\n\n" +
        "fun HugeSample() {\n  val text = \"" +
        "x".repeat(30_000) +
        "\"\n}\n"
    val result =
      assertNotNull(
        PlaygroundSourceCleaner.clean(
          source = preview,
          bodyLine = 7,
          rules = UsageRules.GENERIC,
          parser = null,
          followedSources = listOf(huge),
        )
      )
    assertFalse("fun HugeSample" in result.text, result.text)
    assertEquals(listOf("HugeSample"), result.residue)
  }
}
