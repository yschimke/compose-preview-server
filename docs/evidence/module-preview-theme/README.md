# Module preview theme routing

Public deployment checked on 2026-10-04 (server 3.100.0).

Heron's `home__ideal__default?uiMode=dark` returns HTTP 503 with
`X-Compose-Preview-Dropped-Overrides: uiMode`. The published catalog identifies its
renderer as
`module_3a666561747572653a686f6d65__module_666561747572653a686f6d65__com.tunjid.heron.home.HomePreviewKt.HomePreview`.
The Home executable bundle's `bundle.json` and `previews.json` instead identify it as
`module_666561747572653a686f6d65__com.tunjid.heron.home.HomePreviewKt.HomePreview`.

## Other catalogs

All 39 hosted catalog manifests were inspected. Three declare multiple live bundles:
Heron (27 modules, 36 namespaced image records), Horologist (22 modules, 501 records),
and MeshCore Mobile (3 modules, 56 records).

| Catalog / preview | Dark request before the fix |
| --- | --- |
| Heron / home | 503, uiMode dropped |
| MeshCore Mobile / device-populated, light, compact | 503, uiMode dropped |
| Horologist / wear-prompt-app DefaultPreview, large round | 503, uiMode dropped |
| JetNews / home feed, dark | 200, published dark variant |
| Material 3 / badge number, dark | 200, published dark variant |
| Remote Compose M3 / filled button, compact | 200 |

## Verification after the fix

Controlled regression fixtures verify that the outer module identity stays unique,
while PNG, SVG, accessibility, annotations and streaming reach the local executable
preview id with the original dark override. A fixture with both local and namespaced
manifest ids verifies that an exact match takes precedence. Split-preview bundle
requests use the manifest's local id rather than the catalog namespace.

The public deployment is unchanged by this PR. These tests establish routing; they do
not establish the colors drawn by Heron's own theme implementation.

### Hosted failure

[Heron dark-mode request before the fix](heron-before.png)
