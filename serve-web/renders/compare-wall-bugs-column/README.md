# Compare wall: the Bugs column

Before/after for the **Bugs** cell on `/{system}/compare` — what is already filed against a row,
and how much of the table it spends saying so.

| | |
| --- | --- |
| `before.png` | four issues, four full-width pills, each carrying its title. The cell is **463px** wide and the table overflows a 1280px viewport. |
| `after.png` | the same four issues as one line of numbers — `#63 #57 #40 closed ⌄ + file` — in a **257px** cell. 206px handed back to the three picture panels beside it. |
| `after-open.light.png`, `after-open.dark.png` | the disclosure open: every title, the `parity:` classification, the closed report the collapsed line only marks, and the date the whole panel is a snapshot of. |

The panel is **lifted out of flow** and opens leftward over the row's own pictures. In flow it would
set the column's width while open and shove three picture panels sideways on a click — the exact
reflow the old stacked pills were capped at 22ch to avoid, and the reason the collapsed line can now
be as narrow as it likes.

The row's height does not move: it is 257px in both shots, set by the pictures. The saving here is
horizontal and it is visual weight — a block of red sentences beside every row became a line of
numbers — not page height, and the shots are cropped to the cell for that reason.

**`index as of …` is load-bearing, not decoration.** Nothing on this page re-checks GitHub: the
rows are whatever `parity/issues.json` said when the page was rendered. The index is regenerated on
every issue event so it is rarely more than a refresh tick behind, but a `closed` with no date
invites more trust than a snapshot can carry. The line is `position: sticky` so it survives the
panel scrolling on a row with five reports.

## Reproducing

```sh
node shoot.mjs before        # HARNESS_CHROMIUM=<chrome> if playwright's own build is absent
node shoot.mjs after
node shoot.mjs after-open
```

`fixture/before-page.html` is `main`'s `ServeWeb` output, and `before` shoots it against `main`'s
stylesheet read out of git at shoot time (`BEFORE_REF`, default `origin/main`). It carries the same
four issues as the after shot: this branch adds two to the shared fixture list in
`ServeWebFixtureTest` so the row shows the run of reports the collapsed line exists for, and that
one data change was applied on top of `main` to regenerate this page. Neither shot is drawn or
edited by hand — both are the production stylesheet over server-emitted markup.

## What keeps this covered from now on

These four are a one-off. The standing coverage is the preview-harness state
`serve-format-compare-bugs-open` (`preview-harness/pages-snapshot.spec.mjs`), which opens the first
row's disclosure on the committed `serve-format-compare` fixture and shoots it in both themes — so
the panel, its classifications and its `index as of` line are diffed by the CI bot on every future
change without anyone re-running the above. The collapsed line rides along on the
`serve-format-compare` and `…-picked` captures that already existed.
