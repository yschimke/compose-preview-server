# Watch faces on the real Android runtime: the `android-dwf` native surface

## Decision

A UI Builder catalog for Wear OS Watch Face Format (WFF) declares
`statusSemantics.previewSurfaces.native.backend = "android-dwf"`. For such a design the server's
**native preview is not a Compose compile**: the faithful renderer is Google's
`DeclarativeWatchFaceRuntime.apk`, run in compose-ai-tools' simulator daemon, and its pictures are
shown in **its own tab**, beside the editor and not inside it.

The editor's fast preview (the WFF → Remote Compose translation, several devices side by side)
stays in the editor tab and does not depend on anything here. This is the slow, authoritative
second rung of the fidelity ladder in [UI_BUILDER_PREVIEW_FIDELITY.md](UI_BUILDER_PREVIEW_FIDELITY.md),
and like the Wear screen's native lane it is allowed to be absent: a host without the runtime says
so, and the fast preview keeps working.

The product is **agent-first, with humans collaborating**. WFF XML has two producers: the editor
(KMP, in the browser) and an agent submitting it directly; the server imports either into the UI
Builder design model, where incremental edits continue. The real preview does not care which: it
takes the XML of one revision. Only the import (below, "Direction") is not built yet.

Implementation: `DwfPreviewModel.kt` (pure: source gate, controls, control → command mapping),
`DwfPreviewSessions.kt` (lifecycle, the seam to compose-ai-tools), `ServeUiBuilderDwfRoutes.kt`
(HTTP + socket), `ServeUiBuilderDwfPage.kt` (the tab).

## How this server reaches compose-ai-tools today

Measured on `composeai-tools` 2.95–2.96, not assumed.

- **Pins.** `render-host` is one released coordinate (`composeai-render-host`, version
  `composeai-tools` in `gradle/libs.versions.toml`); the daemons come from `composeai-preview-daemon`
  and the wire shapes from `composeai-contracts`. There is no composite include and no launch
  descriptor: the server links the library and the library finds its own sidecar jars
  (`lib-simulator/`, `lib-daemon-android/`, `lib-dex2jar/`) next to the bundle it was given.
- **The Compose android lane** (`remote-m3`, `wear-m3`) is the playground compile path:
  `ServeUiBuilderNativePreview` → `UiBuilderGeneratedPreviewAdapter` → `PlaygroundCompileService`,
  which submits generated Kotlin with `confType = COMPOSE_ANDROID` to the Robolectric daemon of a
  served *bundle*, and returns a token that `PlaygroundRedeemService` turns into a live session on
  `/{session}/ws/{preview}`. `UiBuilderNativeTargets` decides the daemon from the catalog's declared
  backend and the served bundle's.
- **The watch-face renderer already exists, but as bundle plumbing.** `render-host` ships
  `WatchFaceRendererRegistry` (`dwf-rc`, `dwf-apk`), `DwfApkWatchFaceRendererBackend`, and
  `DwfApkDaemonBridge`, which starts one `:simulator-host` `LaunchMain` process and speaks stdio JSON
  lines to it (`watchface {op: push|status|config|complication|uncomplication}`, `ambient`, `clock`,
  `health`, `battery`, `screenshot`). `ServeStreamSession` already routes a served watch-face
  preview through it (`?wfRenderer=dwf-apk`), and `GET/PUT/DELETE /admin/dwf-runtime`
  (`ServeDwfRuntimeAdmin`) installs the runtime APK. All of that is keyed on a served bundle's
  `ServeHost` + `ServePreview`.
- **A design revision is neither**, and the bridge is `internal object DwfApkDaemonBridge`. Only the
  `DwfApkSessionBridge` interface (`render(host, preview, overrides)`) is public. So this server
  cannot hand it "WFF XML + assets for revision N".

## Packaging and the launcher (implemented)

Nothing is forked here. `RenderHostDwfApkRuntime` is the production `DwfApkRuntime`, built from two
published pieces:

1. **Packaging: `ee.schimke.watchface:wff-packager`** (yschimke/watchface-tools, pinned in
   `gradle/libs.versions.toml`; 0.3.2 is the first release that targets Java 17, which this build's
   floor needs, watchface-tools#28). `DwfPackaging` lays a `DwfSource` out as a WFF source bundle:
   `AndroidManifest.xml` from `WffBundleLayout.manifestDocument` (package
   `ee.schimke.composeai.dwfpreview.f<digest16>`, the WFF version as
   `com.google.wear.watchface.format.version`, `minSdk` from `WffBundleLayout.minSdkFor`),
   `xml` -> `res/raw/watchface.xml`, `assets` under `res/`, a default `res/xml/watch_face_info.xml`,
   and a 64x64 transparent `drawable/preview.png` unless the design supplies a `drawable*/preview.*`
   (the resource link fails on a face info that names a missing preview). It runs the packager's CLI
   (`pack --bundle ... --package ...`, sideload target, the generated debug key kept under the cache
   directory because the runtime does not need a publishable signature) **in its own JVM**, from
   `lib-wff-packager/` in the distribution (`DwfSubprocessPackager`). Not in-process, and not on any
   classpath of the server: Google's `validator-push` carries `xercesImpl` (the XSD 1.1 build, which
   the WFF schema needs), and a second JAXP implementation on the server's classpath replaces the
   JDK's for every DOM user in the process; it made `ExplodedSvg` return its input unexploded. The
   server compiles only against `wff-core` for the bundle layout. `design assets` named
   `raw/watchface.xml`, `xml/watch_face_info.xml` or `AndroidManifest.xml` are refused
   (`DwfSource.problem()`), so the validated document is the one packaged. The APK is cached by `DwfSource.digest()` in memory and on
   disk (`composeAiCacheDir("dwf-preview-apks")`), so a re-push, a second session on the revision and
   a restart all reuse it.
   Packaging runs in `DwfPreviewSessions.start`, off the event loop, not on the first frame: a
   `PackagingException` becomes `DwfPackagingFailed` and the start request answers
   **422 `DWF_PACKAGING_FAILED`** with the packager's own diagnostics
   (`error[wff-validator]: ...`, `error[aapt2]: ...`) as the reasons, which is what the tab shows.
2. **The daemon launcher: compose-ai-tools `DwfSimulatorLauncher`** (render-host, compose-ai-tools#6643).
   `connect(initialApk)` starts the shared `:simulator-host` daemon and returns a
   `DwfSimulatorConnection { command(cmd, payload), alive, close() }`; `unavailableReason()` says why
   it cannot start. It shares the bridge's environment lookup and runtime-conversion cache, and one
   JVM-wide lock around the conversion. `RenderHostDwfApkRuntime.connect` adapts it to
   `DwfDaemonConnection` (an `ok:false` answer becomes a `DwfRuntimeException`).

`availability()` names the first thing missing, in the order an operator fixes it:

| Code | HTTP | Meaning |
| --- | --- | --- |
| `DWF_RUNTIME_MISSING` | 503 | Google's proprietary `DeclarativeWatchFaceRuntime.apk` is not installed (the common hosted case). |
| `DWF_ANDROID_SDK_MISSING` | 503 | No Android SDK for the packager: `aapt2` + `zipalign` (build-tools) and a platform `android.jar`, via `ANDROID_HOME` / `ANDROID_SDK_ROOT`. The message carries the `sdkmanager` line. |
| `DWF_SIMULATOR_UNAVAILABLE` | 503 | The launcher's own prerequisites are missing (`lib-simulator/`, `lib-daemon-android/`, an `android.jar`). |
| `DWF_PACKAGING_UNAVAILABLE` | 503 | Packaging could not run (a tool failed to start). |
| `DWF_PACKAGING_FAILED` | 422 | The packager refused this face; the reasons are its diagnostics. |

Still owed: **the override builder** (optional, compose-ai-tools): `buildOverrideCommands` is
`internal` and keyed on a bundle's `ServePreview`.
[`DwfDaemonCommands`](../../server/src/main/kotlin/ee/schimke/composeai/cli/serve/DwfPreviewModel.kt)
restates the vocabulary, small and under test; once exported it becomes a call and the restatement
goes.

The runtime APK is Google's, never redistributed: it is found through the `dwf-runtime pull` cache,
`$COMPOSE_PREVIEW_DWF_RUNTIME_APK`, or `PUT /admin/dwf-runtime`. The preview deployment
(`preview.coo.ee`) carries the Android build-tools and the `lib-simulator/` + `lib-dex2jar/`
sidecars (preview-coo-ee#54) but not the APK, so it answers `DWF_RUNTIME_MISSING` until an operator
installs one.

## Flow

```
editor (KMP) or agent                server                                   daemon (shared)
  produces WFF XML + assets
  POST …/dwf-preview?revision=N ───▶ source gate → DwfPreviewSessions.start
        ◀── 201 {sessionId, socketPath, pagePath}
  window.open(pagePath)
tab ── GET …/dwf-preview/status ───▶ available? else banner with the reason
tab ══ WS …/{sessionId}/ws ═════════▶ first frame:
                                       packageApk (cached) ── connect/boot ──▶ push apk
                                       full(controls) ───────────────────────▶ ambient/clock/…
                                       screenshot + watchface status ────────▶ PNG, kind=app
        ◀═ {"type":"frame",…}
tab ══ {"type":"controls",…} ═══════▶ diff(applied, wanted) ────────────────▶ only what changed
        ◀═ {"type":"frame",…}
```

### Input

`POST /api/ui-builder/v1/designs/{designId}/dwf-preview[?revision=N]`, gated on
`ui-builder-export` (it feeds a design's content to `aapt2` and a sandboxed runtime, the same trust
as exporting it). Body, all optional:

```json
{ "wffXml": "<WatchFace …>", "wffVersion": 2, "assets": { "drawable/bg.png": "<base64>" }, "label": "My face" }
```

- The design must be readable by the actor (same snapshot read as the native render) and its catalog
  must declare `android-dwf`, or the answer is 422.
- `wffXml` comes from the caller: the editor, which turns the design into WFF in the browser, or an
  agent that authored WFF directly. The request does not import it into the design (see
  "Direction"); it only previews it against a revision the actor can read. If the server later
  derives WFF from the revision itself (`UiBuilderDwfPreview.sourceFor`, for a design that has an
  exporter) the body becomes `{}`. Today `{}` answers `DWF_SOURCE_UNAVAILABLE` rather than guessing.
- `wffVersion` is the design's target, 1–4, default 2.
- The source is gated (`DwfSource.problem`) before anything is packaged: size limits; **no
  `DOCTYPE`/`ENTITY`**; asset paths are plain relative paths under `res/` (no `..`, no absolute, no
  empty segment); a digest of the whole package keys the packaged-APK cache, so re-opening an
  unchanged revision is free.
- Refusals: `DWF_SOURCE_INVALID` / `DWF_SOURCE_UNAVAILABLE` (422), `DWF_RUNTIME_MISSING` /
  `DWF_PACKAGING_UNAVAILABLE` (503), `DWF_TOO_MANY_SESSIONS` (429).

The existing `…/native-preview` route answers a `android-dwf` design with `422 NATIVE_BACKEND_DWF`
naming this route, instead of compiling it on Skiko. `UiBuilderNativeTargets.resolve` returns
`Resolution.WatchFace` for the backend whatever bundle is served.

### Session lifecycle

One session per tab. A session owns its revision's `DwfSource`, its `DwfControls`, and lazily its
packaged APK; it belongs to the actor that started it (another actor gets 404/closed socket).

**One daemon, many sessions.** The runtime holds exactly one face and the host has one daemon
(compose-ai-tools keeps a single `activeDaemon`). Sessions therefore *borrow* it under a lock: when
the daemon last served another session — or another revision — the next frame pushes this session's
APK and replays its whole state; when it last served this session, only the diff is sent. A failing
command drops the daemon (its state is unknown) and the next frame starts a fresh one and replays.
Sessions are bounded (4) and expire after 15 idle minutes, checked on use — no thread. The last
session out stops the daemon. `DELETE …/dwf-preview/{sessionId}` closes explicitly.

### The tab and its socket

The page is `/ui-builder/{designId}/watch-face[?session=…]`: a static shell served like the editor's,
whose every data call is separately authorized. The editor opens it with `?session=` after its own
`POST`; opened bare, it starts a session itself (and so reports `DWF_SOURCE_UNAVAILABLE` until the
exporter exists).

The socket is `…/dwf-preview/{sessionId}/ws`. Server → client is the **server half of
`composeai-stream/1`** (`lane`, `frame` with `codec`/`widthPx`/`heightPx`/`dataBase64`/`seq`,
`error`; see [SESSION-VIEWER-PROTOCOL.md](../serve/SESSION-VIEWER-PROTOCOL.md)), so a frame handler
written for the viewer works. It is a sibling route rather than the viewer's `/{session}/ws/{preview}`
because that socket leases a `ServeHost` from the session registry and streams a *served preview*;
making a design revision look like one would mean faking `ServeHost`, and the viewer's
`setOverrides` is a flat string map where these controls are typed. Client → server:

| Message | Effect |
| --- | --- |
| `{"type":"controls","controls":{…}}` | partial update, then a frame |
| `{"type":"requestFrame"}` | redraw |
| `{"type":"visibility","visible":false}` | a hidden tab stops ticking |

`controls` keys (absent = unchanged, `null` = clear; the whole patch is refused if any key is bad):

| Control | Key | Daemon command |
| --- | --- | --- |
| Time | `timeMillis` | `clock {timeMillis}` |
| Live ticking | `ticking` | none — the server redraws each second and sends the advanced `clock`; counts from the last set time |
| Ambient | `ambient`, `burnInProtection`, `lowBitAmbient` | `ambient {state: AMBIENT\|INTERACTIVE, burnIn, lowBit}` |
| Heart rate, steps | `heartRate` (0–300), `steps` | `health {heartRate, steps}` |
| Battery | `batteryPercent` (0–100) | `battery {level}` |
| Flavor, user configuration | `flavor`, `config {id: value}` | `watchface {op: config, reset: true, flavor, values}` (absolute, so it resets first) |
| Complication slot data | `complications {slot: {type, text, title, value, min, max, colors, weights, …Image}}`, `null` clears | `watchface {op: complication, slot, type, …}` / `{op: uncomplication, slot}` (`EMPTY` also clears) |

Complication images are accepted **only as inline `data:image/…`**. The daemon would also take
`file:` (read a file on this host) and `http(s):` (fetch for the client); this server does not pass
either on.

Each frame is `screenshot` followed by `watchface status`: the simulator draws its own built-in demo
face whenever the app's face failed to load, and a screenshot alone cannot tell the two, so
`kind: "built-in"`, an `error`, or zero frames is an `error` message, never a picture.

### When the runtime is missing

`GET …/dwf-preview/status` (read-gated, design-scoped) answers `{available, code, reason}`. The tab
asks first and, if the runtime is not available, shows the reason in a banner — "The real watch-face
runtime is not available on this server", the sentence naming how to install it, and that the fast
preview is unaffected — and offers no controls. The same reason is the body of a start request's 503.

### Validation report

`POST /api/ui-builder/v1/designs/{designId}/watchface-validation[?revision=N]` takes the same
EXPORT gate, request body and source resolution as a session start, pinned design assets included,
and runs `wff-packager validate --report-out` in its own JVM instead of `pack`
(`WatchFaceValidation`). Up to two runs happen at once, off the event loop, and reports are cached
by `DwfSource.digest`. The answer is the packager's report
`{schemaVersion, wffVersion, minimumWffVersion, passed, checks:[{id, title, status,
findings:[{severity, message, line?, column?, path?, suggestion?}], memory?, contributors?,
passedChecks?, tokenIssued?}], mode?}` plus `designId`, `revision`, `digest` and `ranAtEpochMillis`.
The run passes `--push-marketplace`, so the push validator runs as well. It is decoded field by field,
so a validation token the packager writes is never passed on. A failing face is a `200` with
`passed: false`. So is a bitmap that nothing supplies: one failed check, `bundle-resources`, with
the `WFF_BUNDLE_MISSING_RESOURCE` finding for each name, and the packager is not run. A packager
refusal that comes before any report is one failed `packager` check carrying its diagnostics. A
`422` means only that the request names no usable face, a `503` that this host cannot validate (with
`watch_face_validation_disabled` while the deployment's `watchFaceValidation` flag is off, which is
the production default; the editor then keeps to its in-browser checks), and
a `502` that the validator ran and returned nothing readable. `GET` on the same path returns the
last report on the current revision (or `?revision=`), and `404` when there is none.

## What this does not do

- It does not implement the fast Remote Compose path or the WFF → RC translator (compose-ai-tools),
  the WFF exporter (compose-ui-builder) or `ExportFormatV1` (compose-preview-contracts).
- It does not add a Gradle driver or any dependency: the Tooling API floor in `AGENTS.md` is
  untouched, and `checkServeModuleBoundary` is unaffected — the surface compiles nothing.
- Export of the WFF XML / DWF bundle / resource-only APK is a later phase; `DwfApkRuntime.packageApk`
  (and so `:wff-packager`) is the seam it would reuse, including the Watch Face Push token.

## Direction (not built)

- **Import of agent-submitted WFF.** An agent posts WFF XML; the server parses it with `wff-core`
  into the UI Builder design model (a new revision), after which humans and agents continue with
  incremental edits and the preview is just `POST …/dwf-preview` on that revision with `{}`.
  Parsing, the catalog mapping and the lossiness of the import (what WFF the model cannot hold, and
  whether such a face round-trips) belong to the exporter/importer work in compose-ui-builder and
  `wff-core`; this server would host the route and the revision write. Until it exists the preview
  trusts the XML it is sent and does not reconcile it with the design.
- **Consuming `:wff-packager`** when published: replace `RenderHostDwfApkRuntime.packageApk` and delete
  `DWF_PACKAGING_UNAVAILABLE`'s packaging half; surface packager diagnostics in the 422.
