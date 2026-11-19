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
| `GET /designs` | **optional** device token | published watch faces, newest first |
| `GET /designs/{id}/apk?v=<version>` | optional device token | the signed APK |
| `GET /designs/{id}/preview.png?v=…&sig=…` | the signed URL | the design's picture |
| `POST /installs` | device token | install telemetry; accepted with `204` and not kept |
| `GET /status` | none | whether this server can build APKs, and why not |
| `GET /devices`, `DELETE /devices/{id}` | signed-in person | their linked watches |
| `GET /link?code=…`, `POST /link` | signed-in person | the page a watch's QR code opens |
| `GET`/`PUT`/`DELETE /api/ui-builder/v1/designs/{id}/watch-push` | the design's owner | the publish flag |

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

- **No token:** every *public* published watch face on the server, from every account. Public is
  the builder's own visibility (the editor's Private / public switch, `ui_builder_share_design`).
- **A device token:** those, plus the linked account's own published faces, private ones included.
- A private design is never listed to, or downloadable by, anyone without its owner's token. Its
  APK asked for with no token is `401` (so the app offers to link); with another account's live
  token it is `404`, so that watch does not discard a token that is fine.
- A design that is not published for watches (a draft) is never listed, public or not.

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

`PUT /api/ui-builder/v1/designs/{id}/watch-push` (empty body) publishes the design's **current
revision**; MCP agents use `ui_builder_publish_watch_face` with `{"designId", "published": true}`.
Only the design's owner may (or an agent acting for them), and only for a design whose catalog
draws watch faces (native backend `android-dwf`, i.e. `wff-watchface`). The server exports the
design's Watch Face Format with the same exporter as the real-runtime preview tab, keeps that WFF
with the publication (so the APK can be rebuilt after the revision is pruned), and builds the APK
straight away: a face the packager or Google's validator refuses is a `422` with their diagnostics,
to the person who can fix it. Edits are not shipped until the owner publishes again. `DELETE`
unpublishes; `GET` reports the publication and its APK status (`ready`, `pending`, `unavailable`,
`failed`).

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

## Rate limits

Per client address (under `--trust-forwarded-for`, the forwarded one): 10 link starts and 120 polls
a minute; 60 anonymous reads (lists, APKs, pictures) and 20 anonymous APK downloads a minute. A
device token is charged per device, 240 a minute. Over the limit is `429` with `Retry-After`.

## Where this goes beyond `PUSH_API.md`

- `GET /designs` and the APK download work **without** a token, for public faces. The contract says
  auth is required; the app can therefore browse public faces with no link step (it needs a small
  change to offer that, see below).
- `visibility` on each design and `packaging` on the list; `GET /status`; `GET`/`DELETE /devices`.
- `apkUrl` carries `?v=<version>`.

A suggested change to `PUSH_API.md` (and the app) is in the pull request that added this page.
