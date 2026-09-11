# The samples back-link

A samples page's component drawer, carrying the derived **Explains** directory: the kit component
this sample is a call site for.

`related` is directed and declared in one place — the kit catalog names the samples that explain its
components, and the samples catalog, imported from upstream and regenerated on every refresh,
declares nothing. The back-link is the inverse (`ServeRelatedCatalogs.inverse`), computed from the
kit catalog's own declarations, which is why a samples catalog served **alone** has no back-links:
there is no kit catalog on that box to link back to, and that is the truth rather than a gap.

```
node shoot.mjs
```

**No before picture.** The samples fixture carried a single preview until this change, so it had no
component drawer at all to photograph — a one-entry drawer is omitted. The fixture gained realistic
siblings in the same commit, and the golden diff on
`preview-harness/fixtures/pages/serve-viewer-samples.html` is the record of what changed.
