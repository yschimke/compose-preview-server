# Component directories in the viewer drawer

The drawer subtree's **directories** — the named groups under a component that are not more of its
renders: its recorded interactions, and the catalogs that are about it.

| | |
| --- | --- |
| `drawer-before.png` | the subtree as it was: a component with no primary-axis variants is a flat row in the list, and its two published motion captures are reachable only from the chip on the stage |
| `drawer-after.png` | the same component, same captures, same catalog — promoted to a subtree with a `Motion` directory and a `Samples` one |

Both sides are `ServeWeb.viewerPage` output over the production stylesheet, from the committed
`preview-harness/fixtures/pages/serve-viewer.html` golden. Nothing is drawn by hand: `before` reads
both the page and the stylesheet out of git, so it is what that fixture actually rendered.

```
node shoot.mjs before    # BEFORE_REF=origin/main by default
node shoot.mjs after
```

The third row in `Samples` is deliberately the not-live case — a destination this box has
registered but has no host for yet, which is a row and not a link.
