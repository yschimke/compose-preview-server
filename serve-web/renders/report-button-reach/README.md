# The report button, on a phone

Before/after for [#801](https://github.com/yschimke/compose-preview-server/issues/801) on
`/remote-m3/p/appcard__ideal__default__compact` at the reporter's own 411×785, device pixel ratio
2.625.

| | |
| --- | --- |
| `before.png` | the page as served, and no report launcher anywhere on it. It is not hidden and it has not failed to render: it is at x=708 on a screen 411 wide. |
| `after.png` | the same page, the same markup, the launcher back in the bottom-right corner where every other surface has it. |

The numbers the script prints beside each shot are the whole diagnosis:

```
before  { layoutViewport: 764, screen: 411, fab: { left: 708, right: 748 }, onScreen: false }
after   { layoutViewport: 411, screen: 411, fab: { left: 355, right: 395 }, onScreen: true  }
```

## Why a phone and not a narrow window

`.cp-fab` is `position: fixed; right: 16px`, which resolves against the **layout** viewport. On a
desktop the layout viewport is the window, so the two are the same thing and the button is always
16px from the edge. On a mobile browser the layout viewport grows to the document when the document
is wider, and it had become 764px wide on a 411px device — so `right: 16px` put the button 353px
past the right-hand edge of the screen. `html { overflow-x: clip }` meant the page could not be
scrolled sideways to it either, so the affordance was not merely awkward, it was *gone*. Nothing on
a desktop, at any window width, reproduces that: shrink a desktop window to 411px and the layout
viewport shrinks with it.

## What was 764px wide

`.cp-spec-pick-live` — the visually-hidden `aria-live` span the spec lane announces a frozen
eyedropper reading into. Three properties compose into the fault, and each is unremarkable alone:

1. it is the **last child of `.cp-spec-lane`**, which is the last entry of `.cp-preview-primary` —
   a controls row that on a phone is about 1400px long and scrolls (`overflow-x: auto`). Its static
   position is therefore about x=762;
2. it is **`position: absolute` with no positioned ancestor**, so its containing block is the
   initial one rather than the scroller. An absolutely positioned box whose containing block is
   outside a scroll container is not clipped by it;
3. it was **clipped but not anchored** — `clip-path: inset(50%)` hides the paint and moves nothing.

So the document's scrollable width became 764, the layout viewport followed it, and a control on the
other side of the page left the screen. The fix is one declaration — `left: 0; top: 0` — applied to
every visually-hidden box on these pages rather than only to the one that happened to be last in a
scrolling row, because which of them is last is not a property anybody maintains.

`ServeWebFixtureTest.a visually-hidden box cannot push the page wider than the device` pins the
anchors, and pins `.cp-fab`'s `right: 16px` beside them, since that is the other half of the
mechanism and a reader of either rule needs the other one.

## Reproducing

`../spec-lane-eyedropper/fixture/` is the real page, not a mock: `/remote-m3/p/appcard__ideal__default__compact`
as preview.coo.ee served it, beside the PNGs it points at. The stylesheet is the working tree's.

```sh
npm i playwright                      # PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1 in this container
node shoot.mjs after.png
node shoot.mjs before.png --before
```

`--before` puts `.cp-spec-pick-live` back at its static position (`left: auto; top: auto`) rather
than checking out the old stylesheet. That is exactly the old rule: the span was clipped and
unanchored, which is the one thing this change altered about it.
