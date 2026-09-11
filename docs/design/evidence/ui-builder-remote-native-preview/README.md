# Ordinary Remote roots through native preview

The existing UI-builder native-preview route now lowers ordinary Remote catalog roots with the
same Kotlin emitter as source export, then records and plays the generated body using the Android
capture wrapper. Catalog platform metadata selects this path, including custom Remote catalog ids.
It refuses a desktop target and reports no Compose test-tag bounds for recorded Remote operations.

`RemoteNativeRenderProofTest` runs a real Kotlin compiler, Android daemon, persistent design service,
HTTP server and hosted MCP adapter. It uses the same state-selection document as the unsaved browser
proof, with initial state 20 and 24 dp padding. Its Android classpath is the locally staged daemon
3.2.0 sidecar (AndroidX Remote Compose alpha18), with the renderer fix compiled locally.

Two prerequisite defects were found by inspecting the real renders:

- [tools #5404](https://github.com/yschimke/compose-ai-tools/pull/5404) preserves the generated
  `@Preview` dimensions in the shared manifest. `before-frame.png` shows the default phone frame
  produced when those dimensions were lost.
- [daemon #74](https://github.com/yschimke/compose-preview-daemon/pull/74) hides the host action bar
  before composition records its viewport. `before-host.png` shows the 56 dp content-height loss
  that remained after restoring the square output frame.

The final HTTP and MCP PNGs are byte-identical, 720 × 720 pixels at the daemon's default 2× density,
with the selected green region at x=48…671 and y=48…671. The test checks the frame, selected state
and padding boundaries. `source.kt.txt` is the generated body; `preview.kt.txt` is its capture entry.
`browser.png` shows this result in the existing WASM editor's **2 panes** view; the browser harness
checks its real native-preview response, two separately rendered green squares, and browser errors.

Reproduce after building the existing WASM distribution and staging the local dependencies:

```shell
VERIFY_REMOTE_NATIVE_RUNTIME=/path/to/lib-daemon-android \
VERIFY_REMOTE_NATIVE_BROWSER=true \
CHROME_PATH='/Applications/Google Chrome.app/Contents/MacOS/Google Chrome' \
ANDROID_HOME=/path/to/Android/sdk \
./gradlew -PlocalDependencies=build/local-dependencies/remote-export/local-dependencies.properties \
  :server:test --tests '*RemoteNativeRenderProofTest' --rerun
```

The local profile stages render-host and its project dependencies using
`scripts/stage-local-dependency.py`; the daemon sidecar uses `classes.jar` from the locally built
`:renderer-android:bundleReleaseAar`. Neither fix needs a release for this proof.

This verifies native preview, not the Export menu's generic PNG path. It does not establish broad
catalog coverage or density-override support. The published remote-m3 bundle could not supply this
proof because its snapshot dependencies were missing or no longer matched their recorded hashes;
the test explicitly uses the pinned local Android classpath instead.
