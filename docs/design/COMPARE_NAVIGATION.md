# Comparing things: one vocabulary, one shape, one filter

> **Status: proposal + phase 1.** Written from a walk over the live `remote-m3` catalog on
> 2026-09-07 (`preview.coo.ee`). §1–§3 are the audit and the model; §4 is the change list, with
> what phase 1 delivers marked. Companion to
> [`COMPONENT_PARITY_WORKFLOW.md`](COMPONENT_PARITY_WORKFLOW.md), which settles *what* a parity
> finding is; this settles *where you look at one*.

## 1. What is actually there today

A catalog that publishes design references, a paired sibling catalog, SVG exports and Remote
Compose documents currently offers **seven** places to look at a difference, and no two of them
agree on what the two pictures are called or which side each stands on.

| # | Surface | Route | What it compares | Names it uses |
| --- | --- | --- | --- | --- |
| 1 | Catalog landing | `/remote-m3/` | — (links out) | *compare SVG*, *compare RC players*, *compare to Figma*, *design parity* |
| 2 | Viewer spec lane | `/remote-m3/p/<id>` | render ↔ one chosen source | *Figma*, *M3 Wear OS Apps Design Kit*, *Spec*, *Diff*, *Triptych*, *Slider* |
| 3 | Compare wall | `/remote-m3/compare` | all previews ↔ one of four formats | *PNG ↔ SVG*, *Remote Compose players*, *Figma ↔ PNG*, *M3 Wear OS Apps Design Kit ↔ PNG*, *Rendered PNG* |
| 4 | Focused comparison | `/remote-m3/compare/<id>?reference=…` | one preview ↔ one reference | *Reference*, *Diff*, *Actual* |
| 5 | Layer diff | `/remote-m3/parallel/<id>` | resolved layers across paired catalogs | *layer diff* |
| 6 | Parity dashboard | `/remote-m3/parity` | coverage, drift, activity, gaps | *mapped*, *design parity* — **retired, see §3.4** |
| 7 | Design pages | `/remote-m3/pages/<page>` | a Figma sheet ↔ our renders in its slots | *Our renders*, *Design spec*, *Diff %* |

Five different words for our own render (*Rendered PNG*, *Actual*, *Render*, *Our renders*,
*PNG*). Four for the thing it is compared against (*Spec*, *Reference*, *Design spec*, *Figma*).
The comparison the reader most wants — "how does the Remote Compose implementation differ from the
Wear one?" — is called *parallel* in the code, *M3 Wear OS Apps Design Kit* on the wall, and does
not appear on the landing page at all.

### 1.1 The six concrete faults

**F0 — `design parity` is a destination nobody arrives at.** Its four halves — coverage, mapping
gaps, per-component scores and an activity feed — are each a fact about a surface that already
exists, published instead on a page you have to know to open, in a vocabulary none of those
surfaces use. §3.4 takes it apart.

**F1 — the viewer's `Figma` chip is a mode switch wearing a source's name.** Pressing it reveals a
row that mixes three unrelated groups: the *source* picker (`Figma` / `M3 Wear OS Apps Design
Kit`), the *view* picker (`Spec` / `Diff` / `Triptych` / `Slider`), and — because the bar wraps —
whichever of `SVG` / `3D` / `Transparent` / `Fit width` happens to spill onto the same line. So the
button labelled with one source opens a panel of things that are not that source, and the controls
change rows depending on how long the source names are.

**F2 — the compare wall is four pages pretending to be one.** `ServeWeb.comparisonPage` bakes the
URLs for *every* format into *every* row (`data-png-*`, `data-svg-*`, `data-rc-*`,
`data-reference-*`, `data-parallel-*`) plus a per-row search haystack that inlines the full title of
every issue touching that row. On `remote-m3` that is **5.8 MB of HTML, 474 rows, 4,279 `<img>`
elements and 8.4 s to first byte** — of which ~1.9 MB is `data-hay` and `data-preview-ids` alone.
Then the client assigns 4,279 `src`es and rasterises and scores each pair, which is the "slow to
load each row" in `?format=parallel`.

**F3 — the wall's columns move.** `specLeadsColumns()` puts the target first for `reference` and
`parallel` and the render first for `svg` and `rc`, and `targetHeadLabel()` renames the column with
it. Switching format therefore swaps the pictures' sides *and* relabels both headers, which is the
one thing a comparison table must never do. `Rendered PNG` is also not a name anybody outside the
code uses for "what this catalog draws", and it is rendered into a cell sized by its own pixels, so
next to a larger design-kit export it reads as a smaller, lesser picture.

**F4 — there is no route from a component to its own differences.** The viewer can put *one*
baseline behind *one* variant on the stage. To see the same component's other variants compared, or
to see the same variant against a second baseline, you leave for the wall — which opens on the whole
catalog and has to be filtered by hand, even though `?preview=` already exists and the viewer
already knows the answer.

**F5 — the design-page diff lane is unreadable.** The `Diff %` lane paints a red badge on every
node at once (screenshot: ~40 badges over one sheet), which hides the very drawing being judged.
The lane answers "which node is worst" and destroys "what does that node look like" in the process.

## 2. The model

One noun, one pair, one direction.

> A **comparison** is this catalog's render measured against a **baseline**.

A baseline is any of:

| Baseline | Where it comes from | Slug |
| --- | --- | --- |
| the design reference | imported from the design tool, e.g. Figma | `reference` |
| a paired catalog | a sibling implementation, e.g. `wear-m3-catalog` | `parallel` |
| our own SVG export | `/render/<id>.svg` | `svg` |
| a Remote Compose player | the published `.rc` replayed | `rc` |

Three rules follow, and they are the whole design:

**R1 — the baseline is always on the left, ours is always on the right, the diff is always in the
middle.** Every surface: the wall, the viewer's triptych, the focused comparison, the wipe seam.
The viewer's triptych and the focused page already read `Spec / Diff / Render`; the wall's `svg` and
`rc` lanes are the only dissenters, so they move.

**R2 — the two pictures always occupy the same box.** A baseline exported at a different pixel
size is fitted into the same cell as the render, so "smaller" always means *drawn smaller*, never
*exported smaller*.

**R3 — one word per thing, everywhere.** The baseline is called by the name of where it came from
(`Figma`, `M3 Wear OS Apps Design Kit`, `SVG`, `Remote Compose`). Our render is called by the
catalog's own display title (`Remote Compose Material 3`), never `Rendered PNG` / `Actual` /
`PNG` / `Our renders`.

### 2.1 The filter is inferred from where you are

Nothing about a comparison is worth choosing twice. Every surface carries the same two pieces of
state in the URL — `?baseline=<slug>` and a scope — and hands them to whatever it links to.

```
/remote-m3/                      baseline: unset       scope: catalog
/remote-m3/p/<id>                baseline: sticky      scope: this component  ← inferred
/remote-m3/compare?component=…   baseline: from link   scope: from link
/remote-m3/compare               baseline: from link   scope: catalog
```

Arriving at the wall from a component page therefore opens *on that component*, with the baseline
you were already looking at, and a single chip saying so — which you can clear to widen to the
catalog. Arriving from the landing page opens on the catalog, as it does now.

## 3. What each surface becomes

### 3.1 The viewer — a **Compare** section under the render

The `Figma` chip becomes a **Compare** chip, and what it opens is not a wrapping toolbar row but a
panel *below the stage*:

```
┌ App Card ────────────────────────────────── appcard__ideal__default__compact ┐
│                            [ the render ]                                     │
├───────────────────────────────────────────────────────────────────────────────┤
│ Compare against  (•Figma) ( M3 Wear OS Apps Design Kit ) ( SVG ) ( Remote…)   │
│ View             (•Side by side) ( Diff ) ( Wipe ) ( Baseline only )          │
│                                                                               │
│  Figma            Diff              Remote Compose Material 3      Match      │
│  ┌───────┐        ┌───────┐         ┌───────┐                                 │
│  │       │        │       │         │       │   default · compact   79.9%  ←  │
│  └───────┘        └───────┘         └───────┘                                 │
│  ┌───────┐        ┌───────┐         ┌───────┐                                 │
│  │       │        │       │         │       │   content-image       91.0%     │
│  └───────┘        └───────┘         └───────┘                                 │
│                                    … 6 more variants of App Card              │
│  every component →                                                            │
└───────────────────────────────────────────────────────────────────────────────┘
```

- The **source picker and the view picker are separate labelled groups on their own rows**, so
  neither can reflow into the other (F1).
- The rows are **this component's variants**, filtered without anyone typing (F4). The variant on
  the stage is marked; selecting another row moves the stage to it.
- `every component →` is the wall, deep-linked with `?baseline=` and no component scope.
- `SVG` / `3D` / `Transparent` / `Fit width` leave this area entirely and live in a **View** group
  on the toolbar that is always present and never reorders.

The panel is the *same rows* the wall draws, from the same builder, scoped by component id — not a
second implementation of a comparison.

### 3.2 The wall — one baseline at a time

- The format buttons become a **Baseline** group, named exactly as §2 names them, and they are
  **links** (`?baseline=<slug>`), so the server renders one baseline's rows rather than four
  baselines' data (F2).
- Columns are fixed: `Preview | <baseline> | Diff | <catalog title> | Match | Bugs` (F3, R1).
- Both picture cells share one box (R2).
- `?component=<id>` narrows the wall and shows a clearing chip; `?preview=<id>` keeps working.
- The per-row haystack drops the inlined issue prose; issue titles are emitted **once** as a
  component→issues map and joined client-side.

Expected effect on `remote-m3`: HTML from 5.8 MB to ≈1.4 MB, `<img>` from 4,279 to ≈1,400.

### 3.3 The landing page — every baseline it has, and a grid that scores itself

The action chips become three labelled rows instead of one run-on line:

- **Compare against** — `Figma`, `M3 Wear OS Apps Design Kit`, `SVG`, `Remote Compose players`.
  The sibling catalog is the entry that is missing today, and it is the comparison this catalog is
  most often opened for.
- **Reports** — `17 components with open issues`, `6 design pages`. No `design parity`: §3.4.
- **Explore** — `325 motion captures`, `try in playground`.

And the grid below them carries the numbers the retired dashboard used to hold: each component
card shows its worst match against the chosen baseline, so "where are we bad?" is answered by
looking at the catalog rather than by opening a report about it.

### 3.4 The parity dashboard — retired, and folded back into the app

`/remote-m3/parity` is a fifth destination that exists because nothing else had room for its
facts. Every one of those facts belongs to a surface the reader is already on, so the page goes and
its contents go with it:

| What `/parity` shows | Where it goes instead |
| --- | --- |
| Coverage — *46% of 50 components carry a design reference* | **Figma Pages.** `/pages` and each page already say "162 of 279 components implemented"; that IS coverage, said against the sheet the reader can see. The catalog-wide number joins the `/pages` index. |
| Mapping gaps — which components have no reference | **Figma Pages.** A gap is a node on a sheet with nothing behind it, and the page already has the coverage filter (*Only what we don't implement*) that draws exactly that set. |
| Per-component parity scores | **Catalog index.** Every component card carries its own worst match against the current baseline, so the grid *is* the score table and sorts itself by eye. |
| Per-variant scores | **The preview page.** §3.1's compare panel is the per-variant table, in the place where the variant can be changed. |
| Open issues per component | Already on the component card, the wall row and the viewer. The landing keeps one chip counting the components that have any. |
| The code ↔ Figma activity feed | **The changelog.** It is a list of commits with dates; the catalog already publishes one, with an RSS feed. |

`/parity` keeps answering `?format=json` for the CI checks that poll it, and the HTML route
redirects to the catalog index. Nothing is lost and there is one less place to know about.

### 3.5 The design pages — diffs on demand

`Diff %` stops being a lane that repaints the whole sheet. Instead:

- **Hover** a node to see that node's badge and its diff shading, and nothing else's.
- **Hold** the `Diff %` button (pointer down, or `Space`/`Enter` held, or the button left toggled
  on) to show them all — the current behaviour, but as a deliberate, momentary act.

The sheet stays readable at rest, and the question "what is worst here?" is one press away.

## 4. Delivery

| | Change | Fault | Where |
| --- | --- | --- | --- |
| **1** | Wall: fixed column order, baseline-left | F3 | `compare/columns.ts`, `ServeWeb.comparisonPage` |
| **2** | Wall: catalog title instead of `Rendered PNG`, equal cell boxes | F3 | `ServeWeb.comparisonPage`, `serve.css` |
| **3** | Wall: `?component=` scope + clearing chip; viewer links carry it | F4 | `compare/wallRows.ts`, `CompareWall.ts` |
| **4** | Landing: the missing `parallel` chip, chips grouped in three labelled rows | F1 | `ServeWeb` landing |
| **4b** | Retire `/parity` HTML → redirect to the index; keep `?format=json` | F0 | `ServeHttpServer.handleParity`, landing chips |
| **4c** | Catalog index cards carry their worst match against the baseline | F0 | `ServeWeb` landing grid |
| **4d** | `/pages` index carries catalog-wide coverage | F0 | `ServeWeb` pages index |
| **5** | Design pages: hover / hold diffs | F5 | `design/lanes.ts`, `DesignPage.ts`, `serve.css` |
| **6** | Viewer: Compare panel under the stage with the variant strip | F1, F4 | `SpecCompare.ts`, `ServeWeb` viewer |
| **7** | Wall: one baseline per document, haystack diet | F2 | `ServeWeb.comparisonPage` |

1–5 are self-contained and land first. 6 and 7 are the structural ones: 6 needs the wall's row
builder factored out so the panel and the wall draw the same rows, and 7 changes the page's
caching shape, so both are staged behind the vocabulary landing first.
