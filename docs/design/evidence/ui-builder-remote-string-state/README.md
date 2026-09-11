# Independent Remote text state

The compiler's explicit `compose-preview-state-v1` profile adds independent named string
declarations to its existing integer-expression support. The shared UI-builder exporter selects
that profile for non-null text state and emits ordered literal assignments as ValueStringChange
actions. Strings starting with `@` or `$` remain literal text.

The [direct authoring JSON](mutable-strings.json) is compiled to [ordinary RC bytes](mutable-strings.rc)
and rendered by the real CMP Remote player. [Before interaction](mutable-strings-before.png), two
variables and a literal all read `Ready`. [After interaction](mutable-strings-after.png), a click has
changed only the first variable to `Changed`, and a host update only the second to `Review`.
These are two states of the fixed player proof, not captures of a new editor or an M3 text recipe.

The existing WASM editor separately edits a local text declaration from `Ready` to `@second`.
Its JSON and RC downloads match independent hosted MCP requests; the other variable remains
`Ready` and no design is saved on the server. The canvas in this fixture is a colored layout;
text declarations appear in the Screen inspector. [Before](browser-before.png) ·
[Edited state](browser-state.png) · [Verification](browser-verification.json).

Exact shared-exporter fixtures additionally exercise two ordered writes, repeated execution,
equal initial state, reserved generated-name collisions, empty text, Unicode, `@second` and
`$second`. The real player reads each resulting named value and checks that the action literal
and other variables remain unchanged. String selection, nullable text, computed text and
catalog-specific typography mappings remain outside this change.

## Reproduce

Stage the owning compiler's normal Maven publication with
`scripts/stage-local-dependency.py --checkout /path/to/compose-ai-tools --module :remotecompose-json
--output build/local-dependencies/remote-export`, alongside the local contracts/player profile.

```shell
./gradlew -PlocalDependencies=build/local-dependencies/remote-export/local-dependencies.properties :ui-builder-export:jvmTest
./gradlew -p experiments/remote-compose-poc \
  -PlocalJsonCompilerManifest="$PWD/build/local-dependencies/remote-export/local-dependencies.properties" \
  -PlocalRcPlayers=/path/to/local/rc-players \
  -PproductionStringJsonDir="$PWD/ui-builder-export/build/remote-json-strings" \
  jvmTest --tests '*MutableStringJsonTest'
VERIFY_REMOTE_STRING_EXPORTS=true VERIFY_REMOTE_STRING_BROWSER=true \
CHROME_PATH='/Applications/Google Chrome.app/Contents/MacOS/Google Chrome' \
./gradlew -PlocalDependencies=build/local-dependencies/remote-export/local-dependencies.properties \
  :ui-builder:wasmFrontendDist :server:test --tests '*RemoteStringExportProofTest'
```

The browser proof writes its current document and downloads to `server/build/remote-string-proof`.
The compiler module's 31 tests and ABI check pass; the shared exporter passes 52 tests.
All 20 targeted server tests pass, including the real browser/MCP text proof and Remote PNG
regressions. Golden regeneration changes no existing fixtures. The owning compiler follow-up is
[compose-ai-tools #5406](https://github.com/yschimke/compose-ai-tools/pull/5406).
