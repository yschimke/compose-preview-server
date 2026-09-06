# The UI-builder admin screen

Committed evidence for `GET /admin/ui-builder` — `ServeWeb.uiBuilderAdminPage` in
[`ServeWeb.kt`](../../server/src/main/kotlin/ee/schimke/composeai/cli/serve/ServeWeb.kt) — the
operator's list of every design on the host, with a download and a delete per row and a repair on
any row the host cannot serve.

| file | what it is |
| --- | --- |
| `designs.light.png` / `designs.dark.png` | the screen with three designs loaded, one per catalog |
| `no-token.light.png` | the same page opened without `?token=`: the hint instead of an empty host |

All three are `preview-harness` captures of the committed fixture
`preview-harness/fixtures/pages/serve-admin-ui-builder.html` (written by `ServeWebFixtureTest`),
with the `designs` state in `pages-snapshot.spec.mjs` stubbing `/admin/ui-builder/designs`, so the
diff bot re-shoots them on every change to the page.
