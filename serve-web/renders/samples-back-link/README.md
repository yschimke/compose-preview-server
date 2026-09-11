# The samples back-link

A samples page's component drawer, carrying the derived back-link: the kit component this sample is
a call site for, under a directory named after the catalog it comes from.

`related` is directed and declared at ONE end; this directory is the inverse
(`ServeRelatedCatalogs.inverse`), derived at read time. Which is why a catalog served **alone** has
no back-links: there is no other catalog on that box to have declared one, and that is the truth
rather than a gap.

The directory is named after the catalog the rows come FROM, never a fixed word, because the page it
lands on is whichever end did not declare the link — see the label's own note in
`ServeHttpServer.componentBackLinkDirectories`.

| | |
| --- | --- |
| `before.png` | the directory headed `EXPLAINS` — a fixed word, correct only while the kit catalog was expected to declare the link |
| `drawer.png` | the same directory named after the catalog the rows come from, which is true whichever end declares |

```
node shoot.mjs          # writes drawer.png
```

`before.png` is **preserved rather than shot**: it is the parent commit's own `drawer.png`, lifted
out of git (`git show <parent>:…/drawer.png`) when the label changed. Re-shooting it would need the
old label back in the fixture to photograph, and the committed image is the same bytes that were
reviewed.

The drawer itself first appeared with the back-link, so that change had no before picture at all —
the samples fixture carried a single preview until then, and a one-entry drawer is omitted. The
golden `preview-harness/fixtures/pages/serve-viewer-samples.html` is the record for that step.
