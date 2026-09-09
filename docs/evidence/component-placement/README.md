# One body, placed four times

Rendered by `UiBuilderSurface` on the JVM at 220×60dp, density 1, light theme. The design holds a
row of four `design/component-instance` nodes, one `contribution-cell` component whose body is a
single `m3/surface`, and one bound property — `containerColor` reads the key each placement passes.

| before | after |
| --- | --- |
| ![before](placement-before.png) | ![after](placement-after.png) |

**Before:** the canvas had no notion of a placement, so the instance fell through to
`UnsupportedComponentDiagnostic` and the design drew as the name of a component nothing could draw.
A designer who wanted four cells had to author four subtrees, and change what a cell *is* four
times.

**After:** four cells, four shades, from one authored body. What each placement adds is a scope —
the arguments it passes, which the body reads by key, and a path segment
(`UiBuilderInstancePath.placement()`) that makes the same body under two instances two boxes rather
than one.

The export is deliberately not part of this: the catalog declares no `design/component-instance`, so
`CapabilityComposeCodeExporter` refuses a placement by name rather than emitting a guess. That is
the next step.
