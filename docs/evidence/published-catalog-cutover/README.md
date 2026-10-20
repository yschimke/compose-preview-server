# Production published-template cutover verification

Verified on 2026-10-20 against server **3.146.0**, bundled editor **3.122.0**, editor/server API **2**.

A new Chromium context loaded the actual published `m3-catalog` capability and templates, waited
for IndexedDB and the controlling service worker, closed its online tab, disabled networking, then
opened a fresh tab and created **Hello**. No catalog, template or identity responses were mocked.
The saved document has a new id, revision zero, and the publication's nodes, roots, environment and
complete served catalog pin. No server design writes occurred.

- [Root host online chooser](root/online-chooser.png), [offline chooser](root/offline-chooser.png),
  [offline-created document](root/offline-created.png), [asserted result](root/result.json).
- [Path host online chooser](path/online-chooser.png), [offline chooser](path/offline-chooser.png),
  [offline-created document](path/offline-created.png), [asserted result](path/result.json).

The root host registers `/push-sw.js`; the path host registers `/ui-builder/ui-builder-sw.js`.
Production intentionally redirects HTML browser navigations from `preview.coo.ee/ui-builder/` to
`ui.coo.ee/`. The path check changes only the outgoing `Accept` header to `*/*`, allowing the actual
path-layout server responses to be exercised without changing deployment settings. Normal root-host
navigation is unmodified. The network is disabled before the fresh offline tab opens.

This proves offline **document creation**. The screenshots also show that a catalog renderer which
has never been opened online is unavailable offline (`Pinned catalog runtime unavailable: Failed to
fetch`). Renderer-runtime caching is separate from the app shell and template store; these results
do not claim an offline-rendering pass. Tracked as
[SCH-46](https://linear.app/yschimke/issue/SCH-46/fresh-offline-template-creation-cannot-draw-a-renderer-never-opened).

## Compatibility retirement

The follow-up server cleanup requires editor API 2, stops emitting the ownership meta tag, and
removes `owned` from the catalog settings model. Existing files still load through the tolerant
file reader and drop the retired key on the next save. The strict admin publication endpoint
rejects `owned` as an unknown key; config publishers must omit it. The deployment catalog file
already omits it. Design creation now uses the served catalog pin and published templates without
reading the Jetcaster benchmark operations file. The benchmark fixture remains an explicit test
and visual-harness input.
