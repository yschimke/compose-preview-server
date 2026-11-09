# Public component previews

This experimental host serves **explicitly copied public artifacts**, independently of private UI
Builder designs. It does not enable the default-off RC exporter, change source-design access,
publish editor history, accept HTTP uploads or introduce an AT Protocol record.

The publication reader, format validation, discovery, fallback HTML and reusable Ktor routes belong
to Livery's [`livery-server` module](https://github.com/yschimke/livery/blob/main/livery-server/README.md).
`ServeComponentPublications` only translates host configuration into that library. This server owns
its deployment settings, startup, authentication boundaries and CSP/Caddy policy. It does not keep a
second descriptor parser or installation-format implementation.

The default graph resolves `ee.schimke.composeai:livery-server:0.8.0` from Maven Central,
where the library was published by [livery#49](https://github.com/yschimke/livery/pull/49).
Normal builds require no local checkout or staging override. For development against an
unpublished library change, use the opt-in [staged-publication workflow](LOCAL_DEPENDENCIES.md).
The optional browser viewer remains a separate operator-installed, reviewed distribution;
the Maven library does not contain or download it.

## Install and configure

Build a catalog with Livery's existing `livery build`, run its validation, and prepare a bundle with
the experimental `samples/component-preview/preview.json` descriptor, public example inputs and a
static `thumbnail.png`. Use Livery's `scripts/install-component-preview.mjs` installer for atomic
installation and idempotent retries; see that repository's component-preview sample instructions.
The source bundle must contain only the intended public artifacts, never editor state or credentials.

The resulting directory layout is:

```text
publications/
  quantity-stepper/r/fixture-1/
    publication-files.json     # completion inventory, not served
    preview.json
    thumbnail.png
    catalog/manifest.json
    catalog/manifest.sig.json  # when signed
    catalog/...               # referenced blobs, sidecars and assets
```

`publication-files.json` is a local installation inventory: a JSON object mapping relative filenames
to lowercase SHA-256 digests. It excludes itself. Install into a sibling staging directory and rename
the complete revision into place atomically. Never replace an existing revision: identical validated
retries reuse it, changed bytes require a new revision. The installer owns that write rule; the HTTP
server never writes the tree. Mount it read-only to the server process if possible.

In deployment `settings.json` (applied by the image entrypoint):

```json
{
  "componentPublications": {
    "directory": "/config/component-publications",
    "origin": "https://preview.example.com",
    "viewerDirectory": "/config/livery-viewer",
    "viewerConfig": "{\"keys\":[{\"keyId\":\"publisher\",\"publicKey\":\"BASE64_PUBLIC_ED25519_KEY\"}]}"
  }
}
```

All four apply on restart. The directory setting enables hosting; its absence disables the routes.
The public origin is required, rather than inferred from a request's Host header. Preview pages
must be accessed through that canonical origin: on an alias host, the viewer refuses a cross-origin
canonical URL and the server-rendered fallback links to the canonical page. The viewer directory
is optional: without it the server still returns readable fallback pages, metadata, images and the
descriptor. Install the self-contained `viewer-dist/` produced by Livery's viewer build there. Its
`viewer.js`, `viewer.css` and `livery-web/` Wasm files are mounted at `/assets/livery-preview/`.
Keep viewer updates atomic too; that unversioned mount revalidates every request.

For a locally launched server, use the corresponding environment variables (the standalone process
does not load deployment `settings.json`):

```sh
export SERVE_COMPONENT_PUBLICATIONS_DIR=/absolute/path/to/publications
export SERVE_COMPONENT_PUBLICATIONS_ORIGIN=http://127.0.0.1:8080
export SERVE_COMPONENT_VIEWER_DIR=/absolute/path/to/livery/web/core/viewer-dist
export SERVE_COMPONENT_VIEWER_CONFIG='{"unsigned":true}'
# Launch the normal server distribution with serve --host 127.0.0.1 --port 8080.
```

Unsigned acceptance is an explicit host development choice. The default `{}` does not enable it.
Use trusted public signing keys for published catalogs. A descriptor or its example inputs cannot
configure keys, URL permissions, capabilities or executable JavaScript. Livery remains responsible
for signature verification, selection, binding and action admission; the host checks structural
readiness and file integrity.

## HTTP and viewer boundary

```http
GET /p/quantity-stepper/r/fixture-1/
GET /p/quantity-stepper/r/fixture-1/embed/
GET /p/quantity-stepper/r/fixture-1/preview.json
GET /p/quantity-stepper/r/fixture-1/thumbnail.png
GET /p/quantity-stepper/r/fixture-1/catalog/manifest.json
```

These routes are anonymous even on a token-gated server; installing an artifact here is the explicit
publication act. They do not touch editor routes or grant a recipient authoring access. `HEAD` matches
`GET` headers. Only inventoried data/image/font/blob files are served; directory listings, the local
inventory, arbitrary files and executable publication content are refused. Catalog/data resources
allow anonymous cross-origin reads (`Access-Control-Allow-Origin: *`), without credentials. The viewer
still restricts descriptor references to their publication origin.

The page has server-rendered title, description, canonical URL and OpenGraph image, plus:

```html
<link rel="alternate" type="application/vnd.livery.preview+json"
      href="/p/quantity-stepper/r/fixture-1/preview.json">
<div data-livery-preview
     data-descriptor="/p/quantity-stepper/r/fixture-1/preview.json"
     data-canonical="https://preview.example.com/p/quantity-stepper/r/fixture-1/"
     data-mode="page" data-config="{&quot;keys&quot;:[...]}"></div>
```

The media type/discovery convention is experimental. `data-config` is escaped server/operator
configuration, not publication data. The static title, description and image remain outside the
viewer mount, including when JavaScript or the player fails. Embed pages use `data-mode="embed"`,
the same canonical page URL and an absolute descriptor path. Only the precise embed URL is exempted
from framing restrictions in both the server CSP and image Caddy configuration; the ordinary page
keeps `frame-ancestors 'self'`. Only publication page/embed paths gain Wasm compilation permission.

Livery owns the descriptor, inventory, path rules and size limits; consult its
[`livery-server` documentation](https://github.com/yschimke/livery/blob/main/livery-server/README.md) and
[component-preview sample](https://github.com/yschimke/livery/tree/main/samples/component-preview)
for the current experimental installation contract. The library verifies the complete bounded
inventory on each request; this initial implementation favors straightforward integrity checks over
a persistent cache and should be measured before high-volume hosting.

## Retention and withdrawal

There is no automatic expiry or garbage collection. Retain immutable revisions while they are
published. To withdraw one, create its regular `withdrawn` marker and retain the tombstone:

```sh
touch /config/component-publications/quantity-stepper/r/fixture-1/withdrawn
```

Every URL beneath that revision then returns `410 Gone`, a readable withdrawal message and
`Cache-Control: no-store`, even with an old `If-None-Match` validator. Live responses use
`public, max-age=0, must-revalidate` and content ETags, rather than long immutable caching, so a cache
must recheck withdrawal. Previously downloaded copies and screenshots cannot be recalled. Do not
reuse a withdrawn revision; publish a new one. Incomplete or hash-mismatched installs return `404`
with `no-store`, never a partially ready share page.

## Verification

```sh
UPDATE_SERVE_SETTINGS_REFERENCE=true scripts/agent-gradle.sh :server:test \
  --tests '*ServeComponentPublicationsTest*' --tests '*ServePagePolicyTest*' \
  --tests '*ServeSettingsTest*'
```

HTTP tests run the real server with an operator token and make anonymous requests. They cover SSR
discovery, GET/HEAD and validators, revision isolation, withdrawal, unlisted/private neighboring
files, environment configuration, disabled hosting and the narrow embed policy. Livery's store and
route tests own format validation, integrity, path containment and byte-limit cases; its separate
viewer browser tests exercise rendering, local actions and sharing. Deployment and
the authenticated selected-revision UI Builder publication flow remain subsequent steps.
