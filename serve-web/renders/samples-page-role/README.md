# The samples page role

A component page under the two page roles a catalog can declare for itself
(`catalog.json`'s `display.role` → `ServeWeb.PageRole`).

| | |
| --- | --- |
| `page-catalog.png` | `CATALOG` — the ordinary page: the design comparison on the control row (`Figma 42.4%`, `spec diff →`), the code behind the `Source` chip |
| `page-samples.png` | `SAMPLES` — the comparison lanes gone, the source standing beside the render |

Both are `ServeWeb.viewerPage` output over the production stylesheet, from two goldens
`ServeWebFixtureTest` builds out of **one** set of inputs — same preview, same usage source, same
design reference — differing only in `pageRole`. So the difference in these pictures is the role and
nothing else.

```
node shoot.mjs samples
node shoot.mjs catalog
```

`/usage/<id>` is stubbed: the panel is filled by a fetch the harness has no server for, and an empty
panel would show the layout without the thing it is a layout for. The snippet is a plausible sample
rather than a real one.
