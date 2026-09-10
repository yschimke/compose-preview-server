# One template, placed once, drawn per row

Rendered by `UiBuilderSurface` on the JVM at 120×120dp, density 1, light theme. The design holds one
`layout/for-each` over three rows, whose template is a single `design/component-instance` placing a
`cell` component. The placement passes one bound argument — `containerColor` reads `shade`, a key
each row answers.

| before | after |
| --- | --- |
| ![before](rows-before.png) | ![after](rows-after.png) |

**Before:** the canvas substituted the row into a template node's own `properties`, but handed a
placement its `component.arguments` untouched. The body therefore read the binding *wrapper* rather
than the row's value, `containerColor` resolved to no colour token, and every row drew the
component's default. Meanwhile the generated Kotlin varied correctly per row — the two lanes
disagreed about the same document, which is the one thing this builder exists not to do.

**After:** three rows, three shades, matching what `CapabilityComposeCodeExporter` emits for the
same design.

The composition is a loop over a placement, so both readers of the open-keyed dictionary are in play
at once: the loop supplies the row, the placement supplies the arguments, and one substitution in
`RenderNode` serves both. Pinned in pixels by `ForEachRowsTest`, which asserts the three row centres
carry the three shades rather than the fallback.
