# Decimal selection in the existing WASM editor

This proof uses the existing ui-builder app, production shared JSON exporter, committed local
compiler publication and corrected CMP player. The semantic source remains a Box with authored
padding and Show by state; no flat operation tree or alternate editor is introduced.

The state compiler profile's `floatEquals` declarations lower to ordinary FloatExpression and
IntegerExpression operations, following creation-compose's exact comparison sequence. The
compiler change is [compose-ai-tools#5407](https://github.com/yschimke/compose-ai-tools/pull/5407),
commit `77d1ab39060e7e019b802d12ac887f758582a3ed`, based on main after the text-state PR merged.
The player uses [rc-players#94](https://github.com/yschimke/rc-players/pull/94), commit
`610b15f38b9b47f277d2c990545bd8276d0cd7de`. Both are staged with the local dependency manifest.

## Actual interaction and artifacts

The local design starts with `page=2.5`, cases 1.25 and 2.5, and an explicit fallback.
Physical browser clicks in the real WASM document player advance through:

- [Second case](decimal-preview-second.png)
- [Fallback](decimal-preview-fallback.png)
- [First case](decimal-preview-first.png)
- [Second case again](decimal-preview-second-again.png)

Those interactions change player state without changing the authored initial value. The harness
returns to Design mode, downloads all three formats, reopens the persisted local source, edits
the initial value to 3.75 through the existing Screen state control, and downloads them again.
Every JSON, RC and PNG download matches an independent hosted MCP request byte for byte and by
digest. The browser records no page errors, and export creates no saved server design.

| Artifact | Before: initial 2.5 | After: initial 3.75 |
| --- | --- | --- |
| Existing editor | [Export menu](local-initial-menu.png) | [Export menu](local-edited-menu.png) |
| Semantic design | [Source](local-initial.document.json) | [Source](local-edited.document.json) |
| Authoring JSON | [JSON](local-initial.json) | [JSON](local-edited.json) |
| Compiled document | [RC](local-initial.rc) | [RC](local-edited.rc) |
| Rendered PNG | [Selected case](local-initial.png) | [Fallback](local-edited.png) |

[Machine-readable verification](browser-verification.json) records all six artifact digests and
the measured playback colors. These are before/after interactions in the same implementation;
the earlier [player defect proof](../ui-builder-float-selection-player/README.md) records the
separate baseline correction.

The exact production JSON also passes nine scenarios at densities 1 and 2 in the real JVM player:
ordinary/adjacent/limit integers, Boolean state, many cases, ordinary/adjacent/subnormal/extreme
Floats. It checks ordered click actions, host-driven fallback and retained padding. The consumer
passes all 53 shared-export tests, 953 editor tests, WASM/distribution builds and the actual
server/browser proof. The main-based compiler passes 35 tests, ABI and layer checks.

This closes finite decimal selection for the existing layout/state lowering subset. String and
nullable selectors, broader catalog recipes, computed values and the wider operation census
remain outside this evidence. Decimal values use Float precision.

## Reproduce

Stage the compiler and player with [the local dependency workflow](../../../development/LOCAL_DEPENDENCIES.md).
Build the existing app, then run the browser and packaged renderer proof on Java 21:

```shell
./gradlew -PlocalDependencies=build/local-dependencies/remote-export/local-dependencies.properties \
  :ui-builder-export:jvmTest :ui-builder:jvmTest :ui-builder:wasmFrontendDist :server:installDist
VERIFY_REMOTE_PNG_EXPORTS=true \
VERIFY_REMOTE_PNG_BROWSER=true \
VERIFY_REMOTE_FLOAT_BROWSER=true \
CHROME_PATH='/Applications/Google Chrome.app/Contents/MacOS/Google Chrome' \
UI_BUILDER_REAL_RENDER_APP_HOME="$PWD/server/build/install/compose-preview-server" \
./gradlew --no-configuration-cache -I preview-harness/java21-render-proof.init.gradle \
  -PlocalDependencies=build/local-dependencies/remote-export/local-dependencies.properties \
  :server:test --tests '*RemotePngExportProofTest' --rerun
```

For the exact production-output player matrix, use `ProductionJsonExportTest` with
`-PproductionJsonDir="$PWD/ui-builder-export/build/remote-json-selection"`, the staged compiler
manifest and local player path, as described in the experiment's README.
