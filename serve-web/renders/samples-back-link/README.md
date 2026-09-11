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

```
node shoot.mjs
```

**No before picture.** The samples fixture carried a single preview until this change, so it had no
component drawer at all to photograph — a one-entry drawer is omitted. The fixture gained realistic
siblings in the same commit, and the golden diff on
`preview-harness/fixtures/pages/serve-viewer-samples.html` is the record of what changed.
