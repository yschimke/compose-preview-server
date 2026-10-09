# The TypeScript Remote Compose player (`/rc-player/bundle.js`)

The viewer's `camaelon-js` lane and shared `/d/<id>` document pages draw a Remote Compose document
in a `<canvas>` with the TypeScript player, an IIFE bundle that defines the global `RC`. It is
served at `GET /rc-player/bundle.js` (and, for the `/docs` lane,
`GET /doc-player/remotecompose/bundle.js`, the same classpath resource; see `ServeDocFormats`).

## Where it comes from

Nothing here is a copy of the player. `server/build.gradle.kts` resolves
`ee.schimke.composeai:remote-compose-player-js-dist` (the `dist`-classifier zip
[yschimke/rc-players](https://github.com/yschimke/rc-players) publishes from
`third_party/remote-compose-player/dist/`) and `stageRcPlayerJs` writes it to the generated
resource `rc-player/bundle.js`. The version is `remote-compose-player-js` in
`gradle/libs.versions.toml`. It has its own ref rather than `rc-players` because rc-players
publishes only the modules a release changed; when a newer rc-players release republishes the
bundle, raise it there. Provenance of the TypeScript source itself (upstream
`camaelon/remotecompose-experiments`) is in that repository's `third_party` README.

## What this repository adds: `inert-custom-host.js`

`stageRcPlayerJs` appends [`inert-custom-host.js`](inert-custom-host.js) to the published bundle.
The player installs a `WebCustomHost` into every document it loads, and that host turns the
layout-custom operation (opcode 93) into live browser capabilities: `navigator.mediaDevices
.getUserMedia` for a `camera:` config, and same-origin `fetch`es for embed paths. This server plays
documents it did not write (anonymous `/d/<id>` uploads, catalog captures), so the shim replaces
`RC.RcdPlayer` with a subclass whose host is inert: a custom component paints as an empty box, as
it did before hosts existed. Keep it when raising the version; drop it only if the player gains an
explicit opt-in for its host.
