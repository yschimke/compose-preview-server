# Public component hosting evidence

Captured on 2026-11-09 using Chromium and the normal compiled server entry point, with
Livery's freshly built standalone viewer and an operator-installed, signed RC fixture.
These are local integration checks, not a production deployment.

- [Before: hosting disabled](before-disabled.png): the exact publication route returns
  HTTP 404 with publication configuration absent, matching pre-feature unavailable-route
  behavior. This is Chromium's native HTTP error page, not a reconstructed image.
- [After: signed publication](after-page.png), [mobile width](after-mobile.png), and
  [documentation embed](after-embed.png): hosting enabled with public signing keys only.
  The page shows quantity 2 after a real RC tap; immutable publication data remains 1.
- [JavaScript disabled](fallback-nojs.png): the new route still serves title, description,
  actual rendered thumbnail and canonical link. This is fallback evidence, not the before image.

The complete host browser suite passed 22 assertions for both unsigned development and
signed catalogs. A separate signed-host negative check rejected the unsigned fixture,
rendered no canvas and preserved the fallback. Server HTTP tests cover metadata, discovery,
private-design isolation, withdrawal and the exact framing exception. Full Caddy adaptation
and validation passed with Caddy 2.11.9.

See [setup and reproduction](../../development/COMPONENT_PUBLICATIONS.md). Livery's
`web/core/test/host.mjs` drives the actual browser checks; its generated Dokka example also
passed 15 assertions against this host. No authenticated editor export, Bluesky post or
native Android render is represented by these screenshots.
