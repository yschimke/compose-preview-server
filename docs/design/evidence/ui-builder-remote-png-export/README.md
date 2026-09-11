# PNG export from authored Remote layouts

This proof uses the existing ui-builder WASM app, the actual JSON compiler and the packaged
Compose Remote player. Its semantic source selects among three colored layouts using integer
state. These images prove layout/state export plumbing; they do not establish broad catalog
component coverage.

The renderer plays the exact compiled `.rc` bytes at densities 1 and 2. Assertions check image
dimensions (360 × 360 and 720 × 720), selected branch color and retained 24 dp padding. HTTP export
of saved and supplied documents matches hosted MCP byte for byte. Unsupported RTL lowering returns
HTTP 422; artifact responses preserve the located compiler diagnostic and contain no PNG.

The browser starts with a local design at state 20, downloads PNG, reopens the local source, changes
state to 30 through the existing Screen state editor, and downloads the fallback. Each file matches
an independently requested hosted MCP export and the HTTP response digest. Export creates no saved
design. The standalone MCP transport tests additionally exercise PNG request paths, binary encoding
and error diagnostics through a real HTTP connection.

[Before: local Export menu](before-menu.png) · [After: PNG enabled](local-initial-menu.png) ·
[After editing state to 30](local-edited-menu.png).
The downloaded [initial PNG](local-initial.png) and [edited PNG](local-edited.png) correspond to
the [machine-readable browser verification](browser-verification.json).

Final staged verification passes 19 server tests, all 189 runtime tests, 14 MCP tests, the runtime
ABI check and the existing WASM/distribution builds. One separate standalone live-server MCP test
is opt-in and was not run here; PNG transport is covered by its real-HTTP transport test and the
actual hosted server proof above. Browser verification reports no page errors.

## Reproduce

Stage the local contracts, JSON compiler and player as described in
[local dependency development](../../../development/LOCAL_DEPENDENCIES.md). Build the existing
distribution and WASM frontend with that manifest, then run the proof on Java 21:

```shell
./gradlew -PlocalDependencies=build/local-dependencies/remote-export/local-dependencies.properties :server:installDist :ui-builder:wasmFrontendDist
VERIFY_REMOTE_PNG_EXPORTS=true \
VERIFY_REMOTE_PNG_BROWSER=true \
CHROME_PATH='/Applications/Google Chrome.app/Contents/MacOS/Google Chrome' \
UI_BUILDER_REAL_RENDER_APP_HOME="$PWD/server/build/install/compose-preview-server" \
./gradlew --no-configuration-cache -I preview-harness/java21-render-proof.init.gradle \
  -PlocalDependencies=build/local-dependencies/remote-export/local-dependencies.properties \
  :server:test --tests '*RemotePngExport*Test' \
  :mcp:test --tests '*UiBuilderSuppliedDocumentTest'
```

The opt-in test writes captures and verification data under `server/build/remote-png-proof`.
The test launcher override leaves the published server's Java 17 floor unchanged.
