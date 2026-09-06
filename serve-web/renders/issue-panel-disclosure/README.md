# The filed-issue panel: viewer and parity dashboard

The same disclosure the compare wall's Bugs column gets
([`../compare-wall-bugs-column`](../compare-wall-bugs-column/README.md)), on the two pages that
carry this list beside something else.

## Viewer

| | |
| --- | --- |
| `viewer-before.png` | a **158px** panel between the preview's title and the preview, four issues tall. The picture the page exists for starts below it. |
| `viewer-after.png` | **47px**: `Issues #40 #57 #63 closed ⌄`. The preview moves up by the difference. |
| `viewer-open.png` | the same rows as before, plus the date they are a snapshot of, when the reader asks for them. |

## Parity dashboard

| | |
| --- | --- |
| `parity-before.png` | per component: a heading, then a panel captioned "Issues" under it — two elements for one fact, **167px** each, and this page's body is one per mapped component. |
| `parity-after.png` | **47px** each. The component's name *is* the disclosure's label, and the closed band's summary is the closed numbers themselves rather than the word, since its own `<h2>` already says what it is. |

The bands stay in flow here rather than floating as the wall's panel does: these pages have the
width, and there are no picture columns beside them to shove sideways.

## Reproducing

```sh
node shoot.mjs viewer-before   # HARNESS_CHROMIUM=<chrome> if playwright's own build is absent
node shoot.mjs viewer-after
node shoot.mjs viewer-open
node shoot.mjs parity-before
node shoot.mjs parity-after
```

`fixture/before-*.html` are `main`'s `ServeWeb` output for the same four issues the after side
shows, and `*-before` shoots them against `main`'s stylesheet read out of git at shoot time
(`BEFORE_REF`, default `origin/main`). The four issues come from this branch's addition of two
reports to the shared fixture list in `ServeWebFixtureTest`; that one data change was applied on top
of `main` to regenerate these pages, so both halves show the same data.

## What keeps this covered from now on

The preview-harness states `serve-viewer-issues-open` and `serve-parity-issues-open`
(`preview-harness/pages-snapshot.spec.mjs`) open a panel on each page's committed fixture and shoot
it, so the rows, their classifications and the `index as of` line are diffed by the CI bot from now
on. The collapsed lines ride along on the `serve-viewer`, `serve-parity` and
`serve-reference-compare` captures that already existed.
