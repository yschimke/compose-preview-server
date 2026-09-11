# UI builder: Material Symbols, one row per icon

How the icon picker stops being a list of 11,385 near-duplicates and becomes what
[fonts.google.com/icons](https://fonts.google.com/icons) is — search a name, then customise it —
and how the icons stop being compiled into the Wasm bundle at all. Revisits
[#710](https://github.com/yschimke/compose-preview-server/pull/710)
([#675](https://github.com/yschimke/compose-preview-server/issues/675)).

## What #710 bought and what it cost

#710 answered "the picker only knows 46 icons" by generating every vector the shipped
`material-icons-extended` artifact carries into Kotlin: 11,385 style-qualified entries, each an
`ImageVector` builder, compiled into every module that draws one. Two costs came with it.

- **The bundle.** The production UI-builder Wasm went from 8,737,944 to 26,519,707 bytes; gzip from
  2,372,654 to 5,275,042. That is **~1.6 KB of Wasm per icon**, paid by every visitor on first load,
  for a catalog of which a design uses five.
- **The list.** Style is baked into identity, so `Icons.Filled.Chat`, `Icons.Outlined.Chat`,
  `Icons.Rounded.Chat`, `Icons.Sharp.Chat` and `Icons.TwoTone.Chat` are five rows. Searching `chat`
  returns near-identical pictures with the style spelled into the label, and `m3/icon`.`iconKey`
  became an enum of 11,431 allowed values — which took the MCP catalog *summary* to 241 KB and is
  the entire reason `ServeUiBuilderMcp.SUMMARY_ALLOWED_VALUES` exists.

Both are the same mistake: a variant was modelled as a separate icon, and an icon was modelled as
code.

## The shape

- **A name is the identity.** `search` is one icon. Style, fill, weight, grade and optical size are
  properties of the node, exactly as they are axes on the Material Symbols font — the four sliders
  and two dropdowns the Google Fonts page shows to the right of the grid.
- **Nothing is generated into the bundle.** The picker's grid and the canvas get their outlines from
  the **Material Symbols variable fonts**, loaded at runtime and turned into paths by the Skia that
  already ships beside the Wasm.
- **The export stops depending on `material-icons-extended`.** `builderIcon(key)` emits inline path
  data for the icons a design actually uses, so generated Kotlin is self-contained and any axis
  combination is expressible — which `Icons.*` cannot be, because the old Material Icons set has no
  weight, grade or optical-size dimension at all.

## Why a font and not a data file

The obvious runtime alternative is a data file of SVG path strings. It is the right instinct — path
data is about a third the size of the generated code that draws it — but it does not survive
contact with the axis matrix. Measured, on the upstream artefacts:

| What | Raw | On the wire | Covers |
| --- | --- | --- | --- |
| Today's generated Kotlin | 17.8 MB of Wasm | 2.90 MB gz | 11,385 fixed vectors, always downloaded |
| Path data, one style, one axis point | 3.74 MB | 735 KB gz | 6,614 glyphs, one weight, one fill |
| Path data, 3 styles × fill 0/1 × wght 100–700 | **158 MB** | **35 MB gz** | one grade, one optical size |
| **Material Symbols variable font, per style** | **10.68 MB** | **4.8 MB gz** | **every point of all four axes, continuously** |
| Static instance font, one style/fill/weight | 1.39 MB | 0.53 MB gz | one axis point |

The third row is the one that decides it: 42 axis points is a *deployment* of 158 MB of generated
JSON, and moving a weight slider costs another fetch. The fourth row is the same information in
9% of the space, because a variable font *is* the compressed form of the matrix — of the Outlined
font's 10.68 MB, `gvar` (the variation deltas) is 9.28 MB and `glyf` (the default outlines) is
1.05 MB. Buying every axis, including the two this lane was not even going to offer, costs 3× the
default instance rather than 42×.

So: per style, one file, fetched the first time someone opens the picker or a design names that
style, cached by the browser under a content-hashed URL, and never in the bundle. First load of
the editor drops back to the pre-#710 8.7 MB.

**Serve the `.ttf` with HTTP compression, not `.woff2`.** 4.8 MB gzipped and 4.0 MB as woff2 are
close enough that it is not worth caring, and Skia decodes sfnt directly — the browser's woff2
decoder is not reachable from `Typeface.makeFromData`.

## The runtime, verified rather than assumed

Every piece of this already exists in what the editor ships.

- **Skia in the browser can instance a variable font and hand back outlines.** The skiko `wasm-js`
  klib (0.150.1, the one behind Compose Multiplatform 1.11.1) declares
  `Typeface.makeFromData`, `Typeface.variationAxes`, `Typeface.makeClone(FontVariation)`,
  `Typeface.makeClone(Array<FontVariation>, Int)`, `Font.getPath(Short)` and
  `Font.getPaths(ShortArray)` — read out of the klib's own declaration table, not inferred from
  documentation. `skiko.wasm` is already served beside `uiBuilder.wasm`, so this costs no
  deployment.
- **Compose can build a vector from a path string on Wasm.** `UiBuilderRenderer.kt` already does it:
  `PathParser().addPathNodes(node.pathData).toPath()` in `commonMain`, for the structured-SVG icon
  lane. Nothing new is needed to draw one.
- **The coordinate systems line up exactly.** The font's `unitsPerEm` is 960, which is the viewBox
  upstream's own SVGs use (`viewBox="0 -960 960 960"`). Transforming glyph outlines by
  `(1, 0, 0, -1, 0, 960)` puts `search` in a 120–840 box inside a 960 × 960 viewport — checked
  against `symbols/web/search/materialsymbolsoutlined/search_24px.svg` — and the commands that come
  out are `M`, `L`, `Q` and `Z`, all of which Compose's parser accepts. An `ImageVector` with
  `viewportWidth = viewportHeight = 960f` and `defaultWidth = defaultHeight = 24.dp` needs no
  further fixing up.

## Where the fonts come from

`google/material-design-icons` ships `variablefont/MaterialSymbols{Outlined,Rounded,Sharp}[FILL,GRAD,opsz,wght].ttf`,
and `update/current_versions.json` is the index: 6,612 entries, 4,403 of them Material Symbols.

The build **fetches the three fonts at a pinned upstream commit, against a checked-in SHA-256**, into
the build cache, and the server serves them content-hashed. Not vendored: three variable fonts is
36 MB of binary in git, re-added in full on every upstream refresh. Not fetched at runtime from
`raw.githubusercontent.com` either — [`UI_BUILDER_ASSETS.md`](UI_BUILDER_ASSETS.md) settled that the
host fetches nothing on a design's behalf, and a build-time download with a pinned digest is the
same fetch moved to where it can be reviewed.

An offline build needs the fonts already in the cache, which is the same property every other
pinned artefact in this build has.

## The index the search box reads

The name list comes from the font itself — its `cmap` plus the `GSUB` ligature table is what maps
`chevron_right` to a glyph — so the bundle carries no second copy of 4,403 names.

What the font does **not** carry is tags and categories: `photo_camera` is findable under "camera"
only because `fonts.google.com/metadata/icons` says so, and that index is not in the GitHub
repository. (It was not reachable from the sandbox this was researched in; it is reachable from an
ordinary machine.) Tag search is therefore **deferred**, not designed away: if we want it, the
answer is a small vendored tags file on the same pinned-and-checksummed footing as the fonts, and
until then search matches names, which is what the picker matches today.

## What a design records

`m3/icon` gains the axes and keeps one name:

| Property | Values | Default |
| --- | --- | --- |
| `iconName` | a Material Symbols name — `search`, `chevron_right` | required |
| `iconStyle` | `outlined`, `rounded`, `sharp` | `outlined` |
| `iconFill` | `0`, `1` | `0` |
| `iconWeight` | 100–700 | `400` |
| `iconGrade` | -25, 0, 200 | `0` |
| `iconOpticalSize` | 20, 24, 40, 48 | `24` |

`iconKey` stops being an 11,431-value enum and the catalog says "a name in the Material Symbols
set", validated against the font's own name table rather than spelled out. The
`SUMMARY_ALLOWED_VALUES` guard stays — it is a good rule regardless — but nothing reaches it.

**Old keys keep resolving, and some of them change pixels.** A saved `filled/chat` maps to
`chat` at `fill 1`, `outlined/chat` to `chat` at `fill 0`, `rounded/` and `sharp/` to their own
styles, and the 46 original compatibility keys through the same table. Two cases are not clean and
must not be hidden:

- **Material Symbols is not Material Icons redrawn at a different weight.** A number of glyphs
  differ in detail. A design opened after this lane may look slightly different, and that is a
  deliberate, stated change rather than a regression to chase.
- **`twoTone` has no Material Symbols equivalent.** It maps to `fill 0` and the editor says so on
  the node. The alternative — keeping `material-icons-extended` on the classpath purely to draw
  TwoTone — reinstates the bundle cost this lane exists to remove.

## The export

`ComposeEmitter` already emits a private `builderIcon(key)` carrying only the icons a design uses.
It keeps that shape and changes what the arms return: an `ImageVector` built with
`ImageVector.Builder` and an `addPath(addPathNodes("…"))`, roughly **550 bytes of Kotlin per
distinct icon**, from the same glyph-to-path call the canvas made.

That is strictly better than what it replaces:

- generated source stops importing `androidx.compose.material.icons.*`, so a consumer needs no
  `material-icons-extended` dependency;
- an icon at `wght 300, GRAD 200` is expressible, which no `Icons.*` member can be;
- the daemon renders path data, so the Android/Robolectric lane needs no font and no Skia — which
  matters, because `ui-builder` targets `jvm` and `wasmJs` and the render bundle does not.

The expression allowlist is unchanged: `ImageVector.Builder` and `addPathNodes` are both under
`androidx.compose`.

## The picker

One row per name, with the current axis settings applied to every thumbnail, and a customise panel
below the grid that mirrors Google Fonts: Fill as a switch, Weight, Grade and Optical size as
sliders, style as a segmented control. A search for `chat` returns **one** row.

The grid composes at most a page of rows, as it does now, and the glyph-to-`ImageVector` conversion
is cached by `(name, style, fill, weight, grade, opticalSize)` — cheap, because a page is 80 paths
and a typeface clone is a Skia handle, not a re-parse of 10 MB.

## Slices

1. The font-fetch task, the serving route and the browser-side glyph → `ImageVector` seam, behind
   the existing picker, drawing the default axis point only.
2. The property change on `m3/icon`, the legacy-key mapping, and the catalog contract.
3. The picker's customise panel.
4. The export change and the removal of the generated Kotlin, `material-icons-extended` and
   `MaterialIconCatalogTasks`.

Slice 1 alone returns the 17.8 MB.

## What is deliberately not here

- **A server-side glyph service.** The browser has Skia; asking the server to instance glyphs adds
  a round trip per axis change and a font engine to a process that does not need one.
- **Drawing icons as text.** A ligature draw is fewer moving parts on the canvas, but the export
  needs outlines regardless, and a consumer's app would then need the font shipped with it.
- **The static-instance fonts** (0.53 MB gz each). They are the obvious way to make the first grid
  paint before 4.8 MB has arrived, and they are worth revisiting if that fetch is felt — but
  shipping 42 of them is the data-file deployment again under another name.
- **Tag and category search**, per above.
- **The legacy Material Icons set.** `material-icons-extended` leaves the build; Material Symbols
  is the set Google maintains.
