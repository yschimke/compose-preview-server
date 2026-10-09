# `rc-player/bundle.js` — vendored Remote Compose browser player (build output)

`bundle.js` is the **built** IIFE bundle (global name `RC`) of the TypeScript
Remote Compose player. It is served over `GET /rc-player/bundle.js` (and, for the
`/docs` lane, `GET /doc-player/remotecompose/bundle.js` — the same classpath
resource, see `ServeDocFormats`) so the viewer's `camaelon-js` lane and the shared
`/d/<id>` document pages can render a captured Remote Compose document in a
`<canvas>`, without a Robolectric daemon.

## Provenance

This file is a **byte-for-byte copy** of
[`third_party/remote-compose-player/dist/bundle.js`](https://github.com/yschimke/rc-players/blob/d1d8adca2c64109251fd36344df924d6d3cd34cd/third_party/remote-compose-player/dist/bundle.js)
in [yschimke/rc-players](https://github.com/yschimke/rc-players), which owns the
vendored TypeScript source (upstream `camaelon/remotecompose-experiments`,
Apache-2.0 — see that repository's `third_party/remote-compose-player/PROVENANCE.md`)
and is the only place it can be rebuilt.

| | |
| --- | --- |
| Copied from | rc-players `d1d8adc` (v2.2.1 + 5) |
| Last bundle change there | `44dcd85`, rc-players#548 |
| SHA-256 | `76981e1e64bb1170b8efd50663b8e1de50d336922ad1930bd3ae284b5d4129b2` |

The same bytes are published to Maven Central as
`ee.schimke.composeai:remote-compose-player-js-dist:2.1.2` (`dist` classifier, zip).
rc-players publishes only the modules a release changed, so that coordinate does not
exist at the rc-players version this build pins (`rc-players` in
`gradle/libs.versions.toml`); resolving it would need a version of its own, which is
why this is still a committed copy.

## Refreshing

Do not edit or rebuild it here. Copy rc-players' `dist/bundle.js` over this file,
update the table above, and check a fixture paints (no `Unknown operation opcode` in
the console). A stale copy renders an older document format than the connector packs:
the copy this replaced stopped at opcode 171 (copy handle) and painted nothing for
documents that use it.
