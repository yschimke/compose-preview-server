# UI-builder compile-time features

Remote Compose authoring is disabled by default. Enable the current experimental work when building:

```sh
./gradlew -PuiBuilderRemoteCompose=true :server:installDist :mcp:installDist
```

The same property generates Kotlin `const val` selections for the shared UI-builder code (JVM and
WASM) and the independently published MCP adapter. Leaving it unset or passing `false` builds the
default configuration. Any other value is rejected. Changing a query parameter, environment variable
at runtime, catalog capability or MCP request cannot enable a disabled build. Rebuild and deploy the
server and its packaged WASM frontend together when changing the option.

The option controls:

- Screen state, event-action and state-selection authoring controls, plus modifier parameter editing.
- State selection on the canvas and the new stateful/loop/reusable source export paths.
- Ordinary Remote layout root Kotlin export and its native preview path.
- Remote JSON/RC capability advertisement, downloads and document playback preview.
- Unsaved/local document export routes, including PNG, and their service operation.
- Hosted `ui_builder_export_document` and standalone `export_document`/`export_design` MCP tools.

Existing catalog/component previews, the semantic tree, existing layout and component authoring,
Wear widget/Wear screen export, and saved PNG/SVG export remain available in default builds.
Supporting fixes and local dependency staging remain shared infrastructure. This option does not
claim the experimental work covers the full Remote Compose operation set.

For generator/player changes that have not been released, combine the option with the explicit
[local dependency manifest](LOCAL_DEPENDENCIES.md). Local artifacts are optional; they are never
selected implicitly. Main's released contracts already declare JSON/RC formats.

## Verification

Both configurations run the same build-flag tests. Experimental integration tests that need the
feature are explicitly skipped in the default configuration and exercised in the enabled build.
Default-off tests verify that catalog capabilities cannot enable the feature and that direct HTTP,
MCP and service requests cannot reach the disabled paths. The catalog goldens retain the complete
vocabulary; the default configuration checks the same golden without the state-selection property.

The independent Android compile proofs have additional test-only options:
`boundActionProof` selects the prototype and `boundActionProductionProof` selects production-generated
source. Those options do not enable any shipping editor or server feature. Generate production proof
sources with `-PuiBuilderRemoteCompose=true` first.
