# Layout controls in the existing WASM inspector

This is the production ui-builder WASM app with a local Remote catalog design. Its tree contains
a Box with state selection and three child layouts. The inspector edits the Box's authored
modifier chain; export lowers that hierarchy into actual Remote Compose operations.

The [earlier inspector](../ui-builder-remote-png-export/local-initial-menu.png) displayed modifier
JSON beneath the state-selection controls. The new [Layout section before editing](modifier-before.png)
shows numeric padding fields. [After editing](modifier-after.png), top padding is 40 dp and the
other edges remain 24 dp. These two new captures show an interaction in the same implementation,
not two different application builds.

The browser harness physically edits the Top field and presses Enter. It downloads PNG before and
after the edit and independently requests the same supplied document through hosted MCP. Both PNGs
match their MCP counterparts byte for byte and match the HTTP response digest. Pixel assertions
check the content boundary at y=24 before and y=40 after, with the left and bottom padding preserved.
The source keeps state `page=20`, its cases and the fallback; export creates no saved server design.

- [Initial PNG](local-initial.png) and [edited PNG](local-edited.png)
- [Initial source](local-initial.document.json) and [edited source](local-edited.document.json)
- [Browser verification](browser-verification.json)

Reducer tests also address repeated modifier types by their chain index, preserve earlier entries,
and reject stale indexes, unknown fields and non-finite numeric drafts without changing the source.
The inspector reuses the existing catalog/scope rules and the authoritative modifier mutation.
This evidence establishes layout editing and precise export for this fixture, not general catalog
coverage or completion of the Remote Compose operation census.

Verification: all 953 editor JVM tests pass, the existing WASM app and server distribution build,
and the real browser/render/MCP proof completes with no browser errors.

## Reproduce

Use the staged dependencies described in [local dependency development](../../../development/LOCAL_DEPENDENCIES.md).

```shell
./gradlew -PlocalDependencies=build/local-dependencies/remote-export/local-dependencies.properties \
  :ui-builder:jvmTest :ui-builder:wasmFrontendDist :server:installDist
VERIFY_REMOTE_PNG_EXPORTS=true \
VERIFY_REMOTE_PNG_BROWSER=true \
VERIFY_REMOTE_MODIFIER_BROWSER=true \
CHROME_PATH='/Applications/Google Chrome.app/Contents/MacOS/Google Chrome' \
UI_BUILDER_REAL_RENDER_APP_HOME="$PWD/server/build/install/compose-preview-server" \
./gradlew --no-configuration-cache -I preview-harness/java21-render-proof.init.gradle \
  -PlocalDependencies=build/local-dependencies/remote-export/local-dependencies.properties \
  :server:test --tests '*RemotePngExportProofTest' --rerun
```

The proof writes artifacts under `server/build/remote-png-proof`. The Java 21 test launcher runs the
real packaged renderer without changing the server's published Java 17 floor.
