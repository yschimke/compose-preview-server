# The front door's UI Builder action, and its search

Committed evidence for three changes to the published server's front door
([`ServeWeb.homeIndexPage`](../../server/src/main/kotlin/ee/schimke/composeai/cli/serve/ServeWeb.kt)).

Before this the front door was a gallery. The UI builder ran at `/ui-builder/<catalog>/` and nothing
on the site linked it, so the whole authoring surface was reachable only by knowing the URL — and a
visitor whose account could not write there had no way to learn either that it existed or what would
let them in. The page's search, meanwhile, was a full-width sticky bar spending a band of every
visitor's screen on a control most never touch, and it matched catalog titles only: typing the name
of a component emptied the page, because nothing on a card names the components inside it.

| file | what it is |
| --- | --- |
| `front-door.before.light.png` | the front door as it shipped: the sticky "Search catalogs" bar, and one square-cornered `compare to Figma` chip per card |
| `front-door.after.light.png` | the same page: the search collapsed to a `⌕` in the bar, and a tonal **UI Builder** chip leading the card of a catalog the builder runs for |
| `front-door.before.dark.png` / `front-door.after.dark.png` | the same pair in the dark scheme |
| `search-components.after.light.png` | the search expanded from the bar, matching a **component** name across catalogs — the hits listed by name, and the catalog that publishes them kept in the grid |
| `refused-explained.after.light.png` | the refusal: a signed-in visitor without the builder's write capability gets the chip as a disclosure, and the reason inside the card |

All of them are headless-Chromium captures of the committed harness fixture
`preview-harness/fixtures/pages/serve-home-index.html`, which `ServeWebFixtureTest` generates from
the real page function — so every future change to the front door moves this evidence along with the
goldens. `before` is that fixture at `main`.

Two of the shots are stood up rather than served, because they turn on state the fixture does not
carry: the component hits stub `/api/components` with two Wear components (the real index is per
host), and the refusal substitutes the markup `ServeWeb.homeIndexPage` emits for a visitor whose
credential the create route would refuse — the page is the same either way, the visitor is not. Both
behaviours are pinned by browser contract tests in `preview-harness/pages-snapshot.spec.mjs`
("the front door's search collapses into the bar and reaches component names", "a refused UI Builder
explains itself inside the card").

The action is offered to signed-in visitors only, and only for the catalogs the builder is
configured for. Whether it is *live* is decided by asking
[`ServeUiBuilderAuthorization`](../../server/src/main/kotlin/ee/schimke/composeai/cli/serve/ServeUiBuilderAuthorization.kt)
the same `ui-builder:write` question the `POST /ui-builder/<catalog>` route will ask, off the same
call — so the card never advertises an action the route refuses, and never hides one it would have
allowed. That route's own refusal answers a browser with a styled explanation now
(`ServeWeb.accessDeniedPage`) instead of one line of `text/plain`.
