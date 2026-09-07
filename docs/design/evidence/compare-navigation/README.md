# Compare-navigation mocks

The shapes [`COMPARE_NAVIGATION.md`](../../COMPARE_NAVIGATION.md) argues for, drawn against the
server's own `serve.css` and this catalog's own published renders and Figma references, with the
chrome that is not under discussion blurred out. They are **mocks, not screenshots of the built
server** — a page whose data comes from a live catalog cannot be captured from a checkout — so read
them as the target, and the code as what has landed of it.

| File | What it shows | §
| --- | --- | --- |
| `mock-wall.png` | The comparison wall: one baseline group, fixed `baseline · diff · ours` columns, both pictures in one box with their own sizes said underneath, and the scope chip a `?component=` link arrives with | 3.2 |
| `mock-viewer.png` | The preview page's Compare panel under the stage: source and view as separate labelled groups, and this component's variants compared without anyone typing a filter | 3.1 |
| `mock-landing.png` | The catalog's actions in named groups, including the paired-catalog baseline that was missing entirely | 3.3 |
| `mock-pages.png` | The design page's diff lane at rest and while `Diff %` is held | 3.5 |

`mock-viewer.png` is the one that has **not** landed yet — see the delivery table in the design
note. The rest are drawn from the shipped markup.
