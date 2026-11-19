# Watch Face Push site API

The server side of the Wear OS push app in
[yschimke/watchface-tools](https://github.com/yschimke/watchface-tools) (`:push-app`). A watch links to
an account on this server (`https://ui.coo.ee`, `https://preview.coo.ee`), lists the watch faces
designed here in the UI builder's `wff-watchface` catalog, and installs them through the Watch Face
Push API.

The contract is watchface-tools [`docs/PUSH_API.md`](https://github.com/yschimke/watchface-tools/blob/main/docs/PUSH_API.md);
the client is `push-app/.../data/PushApi.kt` and its models `Models.kt`. This page says what this
server implements, where it goes beyond that contract, and how an operator sets it up. The code is
`server/.../WatchFacePush.kt` (state, signing, packaging) and `WatchFacePushRoutes.kt` (HTTP); the
tests are `WatchFacePushRoutesTest`, which speak the app's own models.

## Endpoints

All under `/api/watchface-push/v1` unless noted. JSON, UTF-8. Errors are
`{"error": "<code>", "message": "…"}`; the app relies only on the status code.

| Method and path | Auth | |
| --- | --- | --- |
| `POST /device-codes` | none, 10/min per address | start linking |
| `POST /device-codes/{code}/poll` | the `pollSecret` | `pending` / `approved` (once) / `denied` / `expired` |
| `GET /designs` | **optional** device token | published and public watch faces, newest first |
| `GET /designs/{id}/apk?v=<version>` | optional device token | the signed APK |
| `GET /designs/{id}/preview.png?v=…&sig=…` | the signed URL | the design's picture |
| `POST /installs` | device token | an install report; `204`, kept per watch and design ([Install reports](#install-reports)) |
| `GET /status` | none | whether this server can build APKs, and why not; what is listed and skipped |
| `GET /devices`, `DELETE /devices/{id}` | signed-in person | their linked watches |
| `GET /link?code=…`, `POST /link` | signed-in person | the page a watch's QR code opens |
| `GET`/`PUT`/`DELETE /api/ui-builder/v1/designs/{id}/watch-face-publication` | the design's owner, in their own session | the publication ([Publishing](#publishing)) |
| `GET /api/ui-builder/v1/designs/{id}/watch-installs` | the design's owner | each linked watch's latest install report |

### Linking

The OAuth device-code shape from the contract:

1. The watch calls `POST /device-codes` with `{"deviceName": "Pixel Watch 4", "wearOsApi": 36}`
   (both optional) and gets `code`, `pollSecret`, `verificationUrl`, `expiresInSeconds` (600) and
   `pollIntervalSeconds` (5).
2. `code` is `XXXX-XXXX` over `ABCDEFGHJKLMNPQRSTUVWXYZ23456789` (no `0`/`O`/`1`/`I`); the link page
   also accepts it in lower case or without the dash. `pollSecret` is 256 random bits; only its
   SHA-256 is held.
3. A person opens `verificationUrl` (`https://<host>/link?code=…`), signs in with the server's
   normal sign-in (GitHub on the hosted boxes), and confirms **Link <deviceName>?**. The forms carry
   a seal over (code, account, action), and a session-cookie POST is accepted only from this
   server's own page.
4. The watch polls `POST /device-codes/{code}/poll` with `{"pollSecret": …}`. A poll sooner than
   `pollIntervalSeconds` after the last one is `429` with `Retry-After`. `approved` carries
   `deviceToken` and `accountName` (the GitHub login), is returned **once**, and consumes the code.
   A wrong secret, an unknown or a consumed code is `404`; a code past its lifetime answers
   `expired` once and is then `404`. An approval the watch never collects is revoked when the code
   expires.

Codes are in memory: a restart costs a person one retry. The device token a code mints is durable.

### Device tokens

`wfp_` followed by 256 random bits, sent as `Authorization: Bearer <token>`. The server stores only
its SHA-256 (`watchface-push/devices.json`). A token reads the designs its account has published
for watches and downloads their APKs; it is not a UI-builder credential and grants nothing outside
this API. Any `Authorization` header that is not a live token is `401` (with `WWW-Authenticate:
Bearer`), so a watch whose token was revoked unlinks and shows its link screen, as the contract
says. A person lists and unlinks their watches on `/link` (or `GET`/`DELETE /devices`).

### Designs

`GET /designs` returns `{"designs": [...]}`, newest publication first, every field of the contract's
`Design`: `id`, `title`, `previewUrl`, `version`, `versionName`, `wffVersion`, `packageName`, `apkUrl`,
`sha256`, `sizeBytes`, `validationToken` (and `description` when there is one; UI-builder designs have
none yet).

**Who sees what.** Linking is optional:

- **No token:** every *public* watch face on the server, from every account: each public
  `wff-watchface` design, published or not ([Public designs](#public-designs-listed-on-their-own)),
  unless its owner unpublished it or published it as private. Public is the builder's own visibility
  (the editor's Private / public switch, `ui_builder_share_design`).
- **A device token:** those, plus the linked account's own published faces, private ones included.
- A private design is never listed to, or downloadable by, anyone without its owner's token. Its
  APK asked for with no token is `401` (so the app offers to link); with another account's live
  token it is `404`, so that watch does not discard a token that is fine.
- A private design that is not published for watches is never listed, to anyone, its owner's
  watches included.

Each entry also carries `"visibility": "public" | "private"`, which the app ignores. When the server
cannot build APKs at all the list is empty and carries `"packaging": {"available": false, "code",
"reason"}` (also ignored by the app, and the same object as `GET /status`).

`version` is the APK `versionCode`. It starts at 1, and moves (by one) only when a re-publish
changes the face itself — its exported WFF, its assets, its WFF version — so a watch is never
offered an "update" to the same APK. Unpublishing keeps the counter, so a face published again is
still a higher version than the one already on someone's watch. `versionName` is
`"<version> (revision <design revision>)"`.

`apkUrl` names the version (`…/apk?v=3`); an older version's URL is `404` rather than a different
APK. The response has `Content-Type: application/vnd.android.package-archive`, an `ETag` of the
SHA-256, `Cache-Control: private, no-cache`, and answers a matching `If-None-Match` with `304`.

`previewUrl` is signed (an HMAC over id and version, with a key kept in the state directory), so the
watch can fetch it with no header, as the contract asks. It is the design's listing thumbnail at the
published revision.

### Publishing

The design's owner publishes from the editor ("Publish to watch") through

| | |
| --- | --- |
| `GET …/designs/{id}/watch-face-publication` | where the design is on watches |
| `PUT …/designs/{id}/watch-face-publication`, body `{"visibility": "public" \| "private"}` | publish (or re-publish) the **current revision** |
| `DELETE …/designs/{id}/watch-face-publication` | unpublish |

under `/api/ui-builder/v1`. MCP agents use `ui_builder_publish_watch_face` with `{"designId",
"published", "visibility"}` instead, after `ui_builder_validate_watch_face` with `{"designId"}`,
which returns the validation report described in [the preview design](../design/UI_BUILDER_WATCH_FACE_ANDROID_PREVIEW.md#validation-report);
the HTTP routes are the person's own:

- **Only the design's owner, in their own signed-in session.** A delegated grant (an agent acting
  for the owner) and an agent's own identity are `403 owner_session_required`, exactly as `/link`
  refuses them: putting a design on every watch outlives the grant. A collaborator who is not the
  owner is `403 owner_required`; a design the caller cannot read is `404`. A session-cookie `PUT` or
  `DELETE` is accepted only from this server's own page.
- **Rate limited** per account: 30 a minute, two at once (a publish runs the packager).
- `visibility: "public"` lists the face to every watch; the design itself must be public, or it is
  `422 design_not_public` (making a design public is the editor's own switch, not this route's).
  `"private"` lists it only to the owner's linked watches, even when the design is public. Omitted,
  it follows the design's own visibility. A public publication whose design is later made private
  falls back to the owner's watches.
- Only for a design whose catalog draws watch faces (native backend `android-dwf`, i.e.
  `wff-watchface`): otherwise `422 not_a_watch_face`.

**No request waits on the packager.** `PUT` checks everything it can up front — the owner, the
catalog, the visibility, that the design exports to Watch Face Format with all its drawables — and
refuses there with a `422` (`design_not_public`, `not_a_watch_face`, `not_exportable`,
`WFF_BUNDLE_MISSING_RESOURCE`). Otherwise it queues the build and answers **`202`** at once with the publication
as it is now and its `build`. Every build on the host — owners' publishes and the automatic listing
— runs through one queue, one at a time, on one low-priority thread. The MCP tool alone waits for
its build, since an agent wants the result in its reply; it still takes its turn in the queue.

The server exports the design's Watch Face Format with the same exporter as the real-runtime preview
tab, keeps that WFF with the publication (so the APK can be rebuilt after the revision is pruned),
and builds the APK through the staged path — candidate, build, commit at the version that was built
— so a face the packager or Google's validator refuses leaves what is on watches as it was, and
ends the build `refused` with the packager's lines (for the MCP tool, a `packaging_failed` error
with the same `diagnostics`).

`build` is

```json
{
  "state": "pending | queued | building | built | refused | failed",
  "reason": "…",
  "scheduledAtEpochMillis": 0,
  "queueAhead": 1,
  "stage": "packaging",
  "startedAtEpochMillis": 0, "elapsedMillis": 0,
  "version": 7,
  "diagnostics": ["error[wff-validator]: …"],
  "nextRetryAtEpochMillis": 0,
  "automatic": false
}
```

with only the fields of its state: `pending` (an automatic build waiting for the design to settle,
`scheduledAtEpochMillis`), `queued` (`queueAhead`), `building` (`stage`, `startedAtEpochMillis`,
`elapsedMillis`), `built` (`version`), `refused` (`diagnostics`), `failed` (`reason`, and for an
automatic build `nextRetryAtEpochMillis`). `GET /status` counts them: `"builds": {"pending",
"queued", "building"}`.

A publication pins its revision: edits are not shipped until the owner publishes again. `DELETE`
takes it off every watch and keeps the version counter; for a public design it is also the owner's
opt-out of the automatic listing, until they publish again.

`GET` (and a successful `PUT`/`DELETE`) answers

```json
{
  "schema": "compose-preview/watchface-push-publication/v1",
  "designId": "aurora", "published": true, "version": 3, "revision": 41,
  "visibility": "public", "requestedVisibility": "public", "automatic": false,
  "faceName": "aurora", "packageName": "ee.schimke.watchface.push.watchfacepush.aurora",
  "apk": {"status": "ready", "sha256": "…", "sizeBytes": 18213},
  "refusal": ["error[wff-validator]: …"]
}
```

`visibility` is who it reaches now (`public`, `private`, or absent when nobody); `requestedVisibility`
is the owner's choice; `automatic` is true for a public design listed without a publish. `apk.status`
is `ready`, `pending`, `unavailable` (this host cannot build; `packaging` then says why) or `failed`.
`refusal` is the packager's lines for the latest attempt it refused — the owner's publish, or the
automatic listing of a new revision — beside whatever version is still on watches. `packageName` is
there before the first publish too.

**The editor's capability.** The `android-dwf` status the editor already reads for a watch-face
design, `GET /api/ui-builder/v1/designs/{id}/dwf-preview/status`, carries

```json
"watchFacePush": {"publicationPath": "/api/ui-builder/v1/designs/aurora/watch-face-publication", "canPublish": true}
```

only when this host can build push APKs (`GET /status` is `available`) and the design's catalog
draws watch faces. `canPublish` is whether this caller may use the route: the owner, in their own
session, with write access. Without the object the editor shows no publish action.

## Public designs, listed on their own

A public `wff-watchface` design is on every watch without an explicit publish: making a design
public is already its owner's decision that anyone may read it. `GET /designs` lists it to every
caller, with or without a token, built from a **settled revision**, with `visibility: "public"` and
the same entry shape as a published design.

**On by default, with an off switch.** `watchFacePushBuilds: false` in the deployment's
`settings.json` (`SERVE_WATCH_FACE_PUSH_BUILDS=false`, read at start; `deploy/image/SETTINGS.md`)
turns this off: no public design is built on its own and the scheduler below never starts, while an
owner's explicit publish still works, through the same one-at-a-time build queue. The server logs
which it is at start.

Building runs the packager (`aapt2` and Google's validator in their own JVM), so it happens only on
deliberate events, never on a request:

- **No request builds.** `GET /designs` and `GET /status` only read what is already built. A design
  is scheduled when a write to it succeeds through the design service — an edit, a rename, a
  revision or trash restore, a document replacement, a change of access, a create.
- **Debounced.** An edit makes the design due **ten minutes after its last edit**, so a live
  editing session builds once, when it settles; becoming public makes it due after thirty seconds.
- **One slow scheduler.** A single low-priority thread wakes every thirty seconds and handles what
  is due one design at a time, reading only that design (its admin summary and one snapshot), never
  a listing of every design. Builds share the server's one-at-a-time packager queue with explicit
  publishes. A design is exported only when its revision has moved, and built only when its face's
  content digest has.
- **Versions** share the counter of an explicit publication: they move when the digest changes, and
  are never lowered or reused. The package name is the one an explicit publish gives the same design.
- **Explicit wins.** An owner's publication — public, private, or a pinned revision — is left alone,
  and so is an owner's unpublish, even one made before anything was built. An explicit publish that
  lands while an automatic build runs is never overwritten by it.
- **Lifecycle.** A design made private, trashed or deleted is no longer readable anonymously, so it
  leaves every list at once — its owner's watches included, since it was never published to them —
  and its APK is `404`. Made public again, it comes back as it was. A purged design's publication is
  retired as any other (#1998), and a design recreated under its id is a different design: it starts
  above every version already installed and inherits nothing.
- **Progress.** The owner's `GET …/watch-face-publication` shows the automatic build's `build` too:
  `pending` with `scheduledAtEpochMillis` while it settles, then `building`, `built`, `refused`, or
  `failed` with `nextRetryAtEpochMillis`.
- **Refusals.** A face the packager refuses is not listed; the version already on watches, if any,
  stays. `/status` names it only while the design is public and is the one that was refused: a
  design since made private, trashed, deleted or recreated is not named there. The refusal is kept in `watchface-push/automatic-refusals.json`, with the revision it was
  for, so that revision is not built again, across restarts too. `GET /status` names it under
  `designs.skipped` with the packager's first line, as it does catalog faces, and the owner sees the
  whole refusal on `GET …/watch-face-publication`. A failure around the packager — a timeout, a
  full disk — is not a refusal: the design is tried again, backing off from a minute to an hour.
- **One sweep at start.** Designs that were public before the flag was on have had no event, so the
  scheduler's first job after a start is one pass over the designs on disk: it reads each design's
  header in the store (`designs/*/design.json`, not through the service, so it takes no lock to
  find them), and for each in a watch-face catalog asks the service about that one design, five
  seconds apart, building those that are public and whose current revision has no APK.
- **Not covered.** A branch merged into a design schedules nothing until the design's next edit.

### Install reports

`POST /installs` takes `{designId, version, result, errorKind?, wearOsApi?, packageName?,
activation?, activationError?, deviceModel?, wearOsVersion?, watchFacePushApiVersion?,
appVersion?}`. `result` is free text (`installed`, `failed`, `activation` for a later "Set as watch
face", …), and `wearOsVersion` and `watchFacePushApiVersion` are version strings. `activation` is one of `activated`, `not_requested`, `manual`, `failed` (any other
value is dropped). It is `204` whether or not it is kept, because the app reports fire-and-forget,
and `400` only without a `designId`, a non-negative `version` and a `result`. The server keeps a
report only on a design published by the watch's own account (so never a catalog face, `c_…`): the
latest per watch and design, at most 500 in all, in `installs.json` beside `devices.json`. Unlinking
a watch drops its reports, and a purged design's go with it.

`GET /api/ui-builder/v1/designs/{id}/watch-installs` is the owner's view:
`{"installs":[{deviceName, deviceModel?, wearOsApi?, version, revisionPublished?, result,
activation?, errorKind?, atEpochMillis}]}`, one row per still-linked watch, newest first. `atEpochMillis` is when the
server received the report, and `revisionPublished` is the revision that
`version` was published from, if that version was still the published one when the report came in.

## The APK

Built from the published WFF by `ee.schimke.watchface:wff-packager`, run in its own JVM from
`lib-wff-packager/` like the preview tab's build (its Google validator's xerces must not join this
server's classpath; see `DwfPackaging.kt`), with

```
wff-packager pack --bundle <dir> --out <apk> --version-code <version> \
  --push-marketplace ee.schimke.watchface.push --face-name <faceName> \
  --token-out <file> --keystore-from-env true
```

which is `PackageTarget.WatchFacePush(marketplacePackage = "ee.schimke.watchface.push", faceName)`.
The validation token is the packager's, from Google's push validator, for that APK. Built APKs are
cached under `watchface-push/apks/` by face name, version and content digest, so an unchanged
revision is never rebuilt, across restarts too; builds run one at a time.

### The naming rule

`packageName` is `ee.schimke.watchface.push.watchfacepush.<faceName>`, the rule the Watch Face Push
API and the app enforce. `faceName` comes from the design id:

- an id that is already `[A-Za-z][A-Za-z0-9_]*`, at most 48 characters, not a Java keyword, and
  not itself shaped like a hashed name (ending `_` and ten hex characters) is used as it is
  (`aurora` → `…watchfacepush.aurora`);
- anything else is sanitised (every other character becomes `_`, a leading non-letter gains `d_`,
  at most 48 characters) and given `_` plus the first ten hex characters of the id's SHA-256, so
  `my-face` and `my_face` are different packages and never replace each other on a watch.

## Operator setup: the signing key

Android installs an update only when it is signed with the certificate that signed the installed
APK. So a push marketplace signs **every** face with **one** long-lived key: lose or change it, and
no face installed from this server can ever be updated again. The server therefore never generates
one. Until a key is configured, the push API is up, lists nothing, and `GET /status` answers
`{"available": false, "code": "signing_key_unavailable", "reason": "…"}`.

Generate the key once, on a trusted machine, and keep a backup somewhere other than the server:

```
keytool -genkeypair -keystore market.p12 -storetype PKCS12 -alias market \
  -keyalg RSA -keysize 4096 -validity 10950 -dname "CN=<your marketplace>"
keytool -list -v -keystore market.p12 | grep SHA256     # the certificate pin
```

Then give the server, as **secrets** (the deployment's `.env`, never `settings.json`, a compose file
or a commit):

| Variable | |
| --- | --- |
| `WFF_SIGNING_KEYSTORE_B64` | the keystore, `base64 -w0 market.p12`; or |
| `WFF_SIGNING_KEYSTORE_PATH` | a path to the `.p12`, for a keystore mounted into the container |
| `WFF_SIGNING_KEYSTORE_PASSWORD` | its store password (required) |
| `WFF_SIGNING_CERT_SHA256` | recommended: the pin. A key whose certificate is not this one is refused, so a wrong secret cannot ship APKs that installed faces would reject as updates. Colons and case are ignored |
| `WFF_SIGNING_KEY_ALIAS` | optional: the key's alias, when the keystore holds more than one |
| `WFF_SIGNING_KEY_PASSWORD` | optional: the key's own password, when it differs from the store's |

The secret ones also work as `NAME_FILE` (Docker secrets). The names are `wff-packager`'s own
(`--keystore-from-env`). The compose deployment passes them through (`deploy/image/docker-compose.yml`)
and `.env.example` lists them.

The server opens the keystore at startup and logs either `watch-face push signs with the certificate
SHA-256 <sha>` or why it cannot build. It hands the key to each packager run in that run's own
environment and nothing else gets it: render daemons and builds start from an allowlisted
environment, and the debug-signed preview packager strips every `WFF_SIGNING_*` variable.

Building also needs the Android SDK (`aapt2`, `zipalign`, a platform `android.jar`) and
`lib-wff-packager/`; the hosted image has both. `GET /status` names whichever is missing.

State lives beside the UI builder's, in `watchface-push/` under its state directory: linked devices
(token hashes), publications, built APKs, and the preview-URL key. Back it up with the rest of the
builder's state; losing it unlinks every watch and restarts versions at 1 (which is why the key,
not this directory, is the one thing that must never be lost: a reinstall at version 1 still works
when it is signed by the same key, an update signed by another never does).

## Catalog faces

Beside UI-builder designs, `GET /designs` lists the watch faces of the **served catalogs**
(preview.coo.ee's `wear-os-samples-watchfaces`, for instance), after the designs. They are public:
every caller, with or without a token, sees the same ones.

A catalog face is listed when all of these hold:

- its catalog is served and **listed** (an unlisted catalog, `--catalogs-unlisted`, never is) and
  resident (the listing never wakes a suspended catalog);
- its bundle carries the face's Watch Face Format source, `previews/<id>.wff.zip`, and its
  provenance manifest, `previews/<id>.wff.json` (compose-ai-tools#6647,
  `ee.schimke.composeai.bundle.WffSourceManifest`, mirrored here as `WffSourceManifestV1` until a
  release of `:bundle-format` is pinned). A trusted catalog's live bundle delivers both, beside
  the `.watchface.json` sidecar, and an uploaded bundle keeps them too;
- the manifest's `license` is one this server redistributes: `Apache-2.0`, `MIT`, `BSD-2-Clause`,
  `BSD-3-Clause`, `CC0-1.0`, `Unlicense` or `CC-BY-4.0`, or an SPDX `OR` of alternatives one of
  which is all on that list. No licence (`null`) means no permission;
- the source is usable: `res/raw/watchface.xml` is there, the WFF version is one the packager
  takes, and the zip is within 512 entries and 16 MB.

`GET /status` counts the listed ones and names each skipped face with why
(`"catalogFaces": {"listed": 1, "skipped": [{"id": "…", "reason": "licence unknown …"}]}`), and does
the same for UI-builder designs under `"designs"`: how many are on the anonymous list, and each public
watch-face design the automatic listing could not build.

Each entry is a normal design entry plus:

```json
{
  "id": "catalog:wear-os-samples-watchfaces/simple_digital",
  "source": "catalog",
  "catalog": { "system": "wear-os-samples-watchfaces", "title": "…" },
  "attribution": {
    "repoUrl": "https://github.com/android/wear-os-samples",
    "sourceUrl": "https://github.com/android/wear-os-samples/blob/<commit>/…",
    "license": "Apache-2.0"
  },
  "visibility": "public",
  "packageName": "ee.schimke.watchface.push.watchfacepush.c_wear_os_samples_watchfaces_simple_digital_0a1b2c3d4e",
  "apkUrl": "https://…/api/watchface-push/v1/catalog-faces/apk?system=…&preview=…&v=1",
  "previewUrl": "https://…/api/watchface-push/v1/catalog-faces/preview.png?system=…&preview=…&v=1"
}
```

Design entries carry `"source": "design"`. `id` is for display and matching only; the app uses
`apkUrl` and `previewUrl` as given (a preview id may hold a `/`, so the face is named by query).

The APK is the same push build as a design's, signed with the same marketplace key: the zip's
`res/**` (its drawables, fonts and values) with the server's own manifest and face info, so the
upstream module's package, `hasCode` or permissions never reach it, and its `LICENSE` stays out of
the APK. The face name is `c_`, the sanitised system and preview, and `_` plus ten hex characters
of the SHA-256 of the face's id: always hashed, from an input no design shares, so it is never a
design's package, and under this marketplace's prefix, so never the upstream app's.
`version` starts at 1 and moves by one whenever the face's source changes; it is kept in
`watchface-push/catalog-faces.json` and only ever grows. The picture is the catalog's baked PNG.

## Rate limits

Per client address (under `--trust-forwarded-for`, the forwarded one): 10 link starts and 120 polls
a minute; 60 anonymous reads (lists, APKs, pictures) and 20 anonymous APK downloads a minute. A
device token is charged per device, 240 a minute. The owner's publication route is charged per
account, 30 a minute and two at once. Over the limit is `429` with `Retry-After`.

## Where this goes beyond `PUSH_API.md`

- `GET /designs` and the APK download work **without** a token, for public faces. The contract says
  auth is required; the app can therefore browse public faces with no link step (it needs a small
  change to offer that, see below).
- `visibility` on each design and `packaging` on the list; `GET /status`; `GET`/`DELETE /devices`.
- `apkUrl` carries `?v=<version>`.

A suggested change to `PUSH_API.md` (and the app) is in the pull request that added this page.
