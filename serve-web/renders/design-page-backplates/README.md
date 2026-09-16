# Design page: a sheet authored over shared plates

Before/after for `/{system}/pages/{id}` carrying a design page's shared backplates and
compositing its export over them (`DesignPage.background`, `PageAsset`, `PageBlendMode`).

| | |
| --- | --- |
| `before.png` | six buttons and a title on the pale fallback fill. Four of them are authored white or near-white, so they are invisible; the sheet reads as three stray chips. |
| `after.png` | the same six over the shared plate, the design layer compositing `screen` as the kit authored it. All six read, and so does the title. |

The case that named this is the Glimmer kit's Buttons sheet: component sets authored
`mix-blend-mode: screen` over five image backplates. Dropping the plates left that
screen-blended drawing compositing over a pale fill, washing the whole sheet toward white --
which is what `before.png` is.

Both are captured from a **real running server**, because the result is decided in three places
that only meet at runtime: the placement geometry `ServeWeb` emits, the `serve.css` rules that
read `data-design-blend` / `data-blend`, and the `/{system}/pages/assets/{id}` route that has to
actually answer for the plate to exist at all.

## The two bundles

`fixture/plates/` and `fixture/flat/` are the same sheet and differ only in their manifest:

* `plates/pages/index.json` carries `assets` and a page `background`; the plate file is stored
  content-addressed at `pages/assets/<sha256>.png`, which is the name the store verifies against.
* `flat/pages/index.json` carries neither.

So `flat/` is the before **as the code sees it**, not a different sheet: `background` and `assets`
are exactly the fields this change taught the server to read, and a server without it renders the
full manifest the way `flat/` renders here.

The plate is a synthetic three-glow stage rather than a Glimmer export, so this fixture needs no
catalog, no Figma token and no render pass -- and no kit artwork in the repository.

## Reproducing

```sh
./gradlew :server:installDist
server/build/install/*/bin/compose-preview-server serve --build-host none \
  --bundles "$PWD/serve-web/renders/design-page-backplates/fixture" --port 8791
# serve prints a ?token=... link; the page routes require it

npm i playwright                      # PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1 in this container
node shoot.mjs "http://127.0.0.1:8791/flat/pages/buttons?token=$TOKEN"   before.png
node shoot.mjs "http://127.0.0.1:8791/plates/pages/buttons?token=$TOKEN" after.png
```
