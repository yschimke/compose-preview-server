package ee.schimke.composeai.cli.serve

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json

/**
 * What a catalog declares about its own scaffolding: the vocabulary [PlaygroundSourceCleaner] needs
 * to turn a sticker's source into the usage code a developer would write.
 *
 * Stickers carry machinery (`Sticker { }` frames, `counted(…)` handlers, knob helpers) that is
 * noise to a reader. Which names those are is a fact about the catalog, so the catalog declares
 * them; a handful of helpers is far cheaper to declare than hundreds of components to annotate.
 *
 * Read from `compose-usage.json` at the catalog repo's root, at the published `ref`
 * ([PlaygroundSeedResolver]). Without one a catalog gets [GENERIC]. A file for now; the intended
 * end state is `@CatalogScaffold(...)` on the helpers, projected by discovery, which changes only
 * how this is populated.
 */
@Serializable
data class UsageRules(
  /**
   * Packages whose annotations are catalog machinery (`@CatalogComponent`, `@CatalogModes`, …),
   * matched by resolving simple names through the file's imports so unrelated same-named
   * annotations are untouched.
   */
  /**
   * Gradle module paths these rules describe. Empty means every module (single-catalog repos). In a
   * multi-catalog repo the file is read once per `(repo, ref)`, so without scoping one catalog's
   * scaffolds would be applied to (and claimed for) the others. Unlisted modules fall back to
   * [GENERIC].
   */
  @SerialName("modules") val modules: List<String> = emptyList(),
  @SerialName("scaffoldAnnotationPackages")
  val scaffoldAnnotationPackages: List<String> = emptyList(),

  /**
   * Catalog annotations by bare simple name, for those no import resolves: an annotation in the
   * previews' own package (`@CatalogModes`) has no import, so [scaffoldAnnotationPackages] can't
   * see it. Blunter, so prefer the package form when imported.
   */
  @SerialName("scaffoldAnnotationNames") val scaffoldAnnotationNames: List<String> = emptyList(),

  /**
   * Repo-root-relative Kotlin files the cleaner may read, so a delegating sticker (`fun
   * FilledButton() = Sticker("button-filled")`) can be followed to its body in a shared module
   * (see #4169). Repo-root-relative because the point is to cross module boundaries; read at the
   * preview's `ref`. A name declared in more than one of these files is ambiguous and dropped.
   */
  @SerialName("scaffoldSources") val scaffoldSources: List<String> = emptyList(),

  /**
   * Extra module-relative source roots searched for imported functions as `<root>/<package
   * path>/<Name>.kt` ([PlaygroundSeedResolver]'s followed calls), for code vendored under a
   * non-conventional directory.
   */
  @SerialName("sourceRoots") val sourceRoots: List<String> = emptyList(),

  /**
   * Packages the [scaffolds] live in, so package-qualified calls are recognised. An allow-list
   * rather than a shape test, since `state.metrics.counted { }` looks like a package prefix.
   */
  @SerialName("scaffoldPackages") val scaffoldPackages: List<String> = emptyList(),

  /** Helper name → what the cleaner should do with it. */
  @SerialName("scaffolds") val scaffolds: Map<String, Scaffold> = emptyMap(),

  /**
   * Module-relative path to the English string resources, for inlining
   * `stringResource(Res.string.x)`. Null leaves lookups (and imports) in place.
   */
  @SerialName("stringsPath") val stringsPath: String? = null,

  /**
   * The `@Preview` annotation stamped on the cleaned entry point. Must be one of
   * [PlaygroundPreviewDiscoverer.DEFAULT_PREVIEW_ANNOTATION_FQNS], or the playground finds nothing
   * to render.
   */
  @SerialName("previewAnnotation")
  val previewAnnotation: String = "androidx.compose.ui.tooling.preview.Preview",
) {

  /** What to do with one scaffolding helper. */
  @Serializable
  data class Scaffold(
    /**
     * What to do with the helper. Unknown kinds decode as [Kind.UNKNOWN] ([LenientKind]) rather
     * than failing the document.
     */
    @SerialName("kind") @Serializable(with = LenientKind::class) val kind: Kind = Kind.UNKNOWN,
    /** [Kind.RENAME] only: the plain-Compose name to call instead. */
    @SerialName("renameTo") val renameTo: String? = null,
    /**
     * Optional semantics layered onto [Kind.RENAME]. A string so older servers ignore it and still
     * perform the plain [renameTo]. This build knows [MATERIAL3_SYSTEM_THEME]; unrecognised values
     * are reported as residue.
     */
    @SerialName("special") val special: String? = null,
    /** An import the replacement needs, e.g. `androidx.compose.material3.MaterialTheme`. */
    @SerialName("addImport") val addImport: String? = null,
    /**
     * Extra imports for replacements needing more than one (e.g. a state helper needs `remember`,
     * `mutableStateOf`, `getValue`, `setValue`). Used with [addImport] by RENAME, SUBSTITUTE and
     * INLINE ([imports]); DROP and UNWRAP emit no new code.
     */
    @SerialName("addImports") val addImports: List<String> = emptyList(),
    /** [Kind.SUBSTITUTE] only: what the call reads as, with `$0`, `$1`… for its arguments. */
    @SerialName("plain") val plain: String? = null,
    /**
     * [Kind.SUBSTITUTE] only: the helper's parameter names in order, so `$0`/`$1` bind named
     * arguments correctly. Without them, named-argument calls are reported as residue rather than
     * guessed.
     */
    @SerialName("params") val params: List<String> = emptyList(),
    /**
     * [Kind.INLINE] only: member → replacement with `$0`, `$1`… as arguments (e.g. `counted`:
     * `{"label": "$0", "onClick": "{}"}`).
     */
    @SerialName("members") val members: Map<String, String> = emptyMap(),
  ) {
    /**
     * Every import this rule needs: [addImport] and [addImports] together. Every rewrite reads
     * this, so either spelling works.
     */
    val imports: List<String>
      get() = buildList {
        addAll(addImports)
        addImport?.let(::add)
        if (special == MATERIAL3_SYSTEM_THEME) addAll(MATERIAL3_SYSTEM_THEME_IMPORTS)
      }
        .distinct()
  }

  enum class Kind {
    /**
     * Call the plain-Compose equivalent instead (`CatalogFrame { }` → `Box { }`). Use
     * [MATERIAL3_SYSTEM_THEME] for wrappers meaning a system-responsive Material 3 scheme.
     */
    RENAME,

    /**
     * Drop the call and keep its trailing lambda body; for render-consistency scaffolding like
     * `ButtonFrame`.
     */
    UNWRAP,

    /**
     * Knob plumbing: delete the helper's binding and every named argument mentioning it, leaving
     * the call with its defaults (what the default render shows).
     */
    DROP,

    /** Substitute the call's [Scaffold.members] at their use sites; delete the binding. */
    INLINE,

    /**
     * Replace the whole call with [Scaffold.plain], citing arguments as `$0`, `$1`…, for knobs with
     * a plain reading (`catalogChoice("style", "outlined", …)` → `"$1"`). Works on any expression,
     * unlike [DROP].
     */
    SUBSTITUTE,

    /**
     * The helper delegates: replace the call with its body from [UsageRules.scaffoldSources],
     * parameters bound, and let other passes run on the result.
     *
     * When the body is a lone `when` over a parameter bound to a string literal, only the matching
     * branch survives (shared component sets dispatch over hundreds of ids). Anything less certain
     * is left as written and reported as residue.
     */
    EXPAND,

    /**
     * A kind this build doesn't know (retired, or newer than this server). No pass matches it, so
     * the helper is left as written and reported as residue. Never written in a rules file; [parse]
     * coerces unrecognised kinds to it so one stale entry doesn't fail the whole document (rules
     * are read at old refs; see #3884).
     */
    UNKNOWN,
  }

  /**
   * Decodes an unrecognised [Kind] as [Kind.UNKNOWN]. A field serializer rather than global
   * `coerceInputValues`, which would also "repair" malformed rules (e.g. `"members": null`) into
   * ones the cleaner would misapply. Only the kind vocabulary is expected to drift.
   */
  internal object LenientKind : KSerializer<Kind> {
    override val descriptor: SerialDescriptor =
      PrimitiveSerialDescriptor(
        "ee.schimke.composeai.cli.serve.UsageRules.Kind",
        PrimitiveKind.STRING,
      )

    override fun serialize(encoder: Encoder, value: Kind) = encoder.encodeString(value.name)

    override fun deserialize(decoder: Decoder): Kind {
      val name = decoder.decodeString()
      return Kind.entries.firstOrNull { it.name == name } ?: Kind.UNKNOWN
    }
  }

  companion object {
    /**
     * A [Scaffold.special] for an argument-free wrapper meaning stock Material 3 following system
     * night mode. The cleaner keeps the trailing lambda and emits:
     * ```
     * MaterialTheme(
     *   colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme(),
     * ) { … }
     * ```
     */
    const val MATERIAL3_SYSTEM_THEME = "MATERIAL3_SYSTEM_THEME"

    private val MATERIAL3_SYSTEM_THEME_IMPORTS =
      listOf(
        "androidx.compose.foundation.isSystemInDarkTheme",
        "androidx.compose.material3.MaterialTheme",
        "androidx.compose.material3.darkColorScheme",
        "androidx.compose.material3.lightColorScheme",
      )

    /** Every override knob takes `(key, default, index)`; the default is `$1`. */
    private val OVERRIDE_KNOB_PARAMS = listOf("key", "default", "index")

    /**
     * Rules needing no catalog knowledge: strip this repo's annotations and prune unused imports.
     * Every catalog gets at least this.
     */
    val GENERIC =
      UsageRules(
        scaffoldAnnotationPackages = listOf("ee.schimke.composeai.preview"),
        scaffoldPackages = listOf("ee.schimke.composeai.overrides"),
        // The preview-override knobs are this repo's API, so no catalog should have to declare
        // them. Each takes `(key, default, …)`, so `$1` is the rendered default; `params` makes
        // named-argument calls bind correctly.
        scaffolds =
          mapOf(
              "previewOverrideString" to OVERRIDE_KNOB_PARAMS,
              "previewOverrideInt" to OVERRIDE_KNOB_PARAMS,
              "previewOverrideFloat" to OVERRIDE_KNOB_PARAMS,
              "previewOverrideBoolean" to OVERRIDE_KNOB_PARAMS,
              "previewOverrideColor" to OVERRIDE_KNOB_PARAMS,
              "previewOverrideDp" to OVERRIDE_KNOB_PARAMS,
              "previewOverrideFont" to
                listOf("key", "default", "suggestions", "googleFonts", "index"),
              // Two overloads differing in the third parameter; unknown parameter names are
              // ignored, so one entry covers both.
              "previewOverrideChoice" to listOf("key", "default", "options", "index"),
            )
            .mapValues { (_, params) ->
              Scaffold(kind = Kind.SUBSTITUTE, plain = "\$1", params = params)
            },
      )

    /**
     * Whether the catalog itself declared scaffolding (not just inherited [GENERIC]), which the
     * Source panel's stronger "usage code" note depends on.
     */
    fun UsageRules.declaresCatalogScaffolds(): Boolean = catalogScaffolds().isNotEmpty()

    /**
     * Whether these rules describe [module] ([UsageRules.modules]). Unscoped files apply
     * everywhere; a location with no module is treated as covered, to avoid regressing older
     * catalogs to [GENERIC].
     */
    fun UsageRules.appliesToModule(module: String?): Boolean =
      modules.isEmpty() || module == null || module in modules

    /**
     * The scaffolds the catalog declared itself, compared by entry (not key), so overriding a
     * generic helper counts as a declaration.
     */
    fun UsageRules.catalogScaffolds(): Map<String, Scaffold> =
      scaffolds.filter { (name, scaffold) ->
        GENERIC.scaffolds[name] != scaffold
      }

    /**
     * A catalog's rules plus this repo's own (preview-override knobs and annotation packages),
     * which a declared file would otherwise replace wholesale. The catalog's entry wins on a name
     * clash.
     */
    private fun UsageRules.withGenericDefaults(): UsageRules =
      copy(
        scaffoldAnnotationPackages =
          (scaffoldAnnotationPackages + GENERIC.scaffoldAnnotationPackages).distinct(),
        scaffoldAnnotationNames =
          (scaffoldAnnotationNames + GENERIC.scaffoldAnnotationNames).distinct(),
        scaffoldPackages = (scaffoldPackages + GENERIC.scaffoldPackages).distinct(),
        scaffolds = GENERIC.scaffolds + scaffolds,
      )

    private val json = Json {
      ignoreUnknownKeys = true
      isLenient = true
    }

    /** Parse `compose-usage.json`, or null when invalid; a malformed file degrades to [GENERIC]. */
    fun parse(text: String, onLog: (String) -> Unit = {}): UsageRules? =
      try {
        json.decodeFromString<UsageRules>(text).withGenericDefaults()
      } catch (e: Exception) {
        onLog("compose-usage.json is not valid usage rules (${e.message}); using generic rules")
        null
      }
  }
}
