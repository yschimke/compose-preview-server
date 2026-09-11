# Compile-time authoring flag

These captures render the existing editor's common Compose UI on the JVM at 1600 × 1050.
They are test captures, not browser screenshots. Both use
`experiments/remote-state-selection/bound-actions.document.json` and select `indicator`.
The deliberately small color fixture tests state selection and typed callbacks rather than product styling.

- `disabled.png`: default build; state selection controls are absent and the original read-only modifier inspector remains.
- `enabled.png`: `-PuiBuilderRemoteCompose=true`; the same editor exposes **Show by state** and editable layout modifiers.

Reproduce independently (sequentially, since they use the same build outputs):

```sh
./gradlew :ui-builder:jvmTest --tests '*EditorBuildFeatureFlagTest'
./gradlew -PuiBuilderRemoteCompose=true :ui-builder:jvmTest --tests '*EditorBuildFeatureFlagTest'
```

The test asserts catalog metadata and control visibility and writes captures to
`ui-builder/build/feature-flag-evidence/`. The accompanying export, route, service and MCP tests
verify that external capability flags and direct calls cannot enable disabled features.
See [feature documentation](../../../development/UI_BUILDER_FEATURE_FLAGS.md) for the build switch
and [existing WASM browser evidence](../ui-builder-scoped-remote-export/README.md) for browser/MCP export proof.
