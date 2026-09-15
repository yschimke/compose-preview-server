# A no-JVM desktop host: what Compose Desktop Native would buy us

**Proposal / exploration.** No code, no commitment. Written against
[#825](https://github.com/yschimke/compose-preview-server/issues/825), which asks what we could do
with [bitsycore/compose-desktop-native](https://github.com/bitsycore/compose-desktop-native) — a
native inspector, a native catalog, a native UI builder.

The short answer, in the order the three questions were asked: a **native inspector is the one with a
real gap to fill**, a **native catalog is the cheapest thing to try and the right first spike**, and a
**native UI builder is the one to leave alone** — the editor is the half of that product that was
already portable, and the half that is not is JVM and Gradle all the way down.

## 1. What the port actually is

| | |
| --- | --- |
| What | Compose Multiplatform as a single native executable, no JVM: macOS arm64, Linux x64/arm64, Windows mingwX64 |
| Runtime | the official `org.jetbrains.compose.runtime` klibs, never reimplemented |
| UI / foundation / animation / material3 | **vendored verbatim** from the CMP `v1.12.0` tag, republished as `com.bitsycore.compose.*` |
| Drawing | Skia through Skiko — official Skiko on macOS (Metal) and Linux (GL), a bitsycore Skiko fork on Windows |
| Windowing, input, clipboard, dialogs | SDL3, built from source as a static lib and linked in |
| Consumption | a `com.bitsycore.compose-desktop-native.bridge` Gradle plugin that substitutes the official coordinates for the fork's klibs **on native desktop targets only** |
| Pinning | Kotlin 2.4.20, CMP 1.12.0, Material3 1.12.0-alpha03, SDL3 3.4.16, Skiko 0.150.1 |
| Licence / provenance | MIT, single maintainer, artifacts on `maven.bitsycore.com` (public, no auth) and GitHub Packages |

It is not a reimplementation and it does not claim to be: the sample apps build for stock JVM Compose
Desktop as well, and any difference against the native build is called a porting bug. That is the same
"one implementation, two hosts" shape the Wasm player has in `rc-players`, and it is why the port is
worth taking seriously rather than filing under *interesting fork*.

## 2. The constraint everything else follows from

**Substitution happens in the consumer's build, and klib module IDs are not upstream's.**

The bridge plugin rewrites `org.jetbrains.compose.ui:ui` to `com.bitsycore.compose.ui:ui` on every
configuration belonging to a native desktop target. Kotlin/Native pins module IDs into klib metadata —
the port's own troubleshooting note is an `Unknown dependent library com.bitsycore.compose.sdl:core`
after a rename — so a **prebuilt** klib of ours, compiled against the official Compose UI, records a
dependency on a library the consuming build has just substituted away.

The consequence is the whole cost model: **anything in the graph that touches Compose UI probably has
to be compiled from source against the fork**, not merely resolved. For us that means a second
publication axis for every Compose-touching artifact we own, not three extra target declarations.

*Probably*, because this is the one thing that has to be measured rather than reasoned about, and it
is cheap to measure (§6). If prebuilt klibs do link, most of this document gets much cheaper. If they
do not, every option below carries a publication cost.

## 3. Where our own artifacts stand

Measured against Maven Central, 2026-09-15:

| Artifact | Published variants | Verdict for native desktop |
| --- | --- | --- |
| `rc-player-{trace,protocol,runtime,compose}` | jvm, wasm-js, iosArm64, iosSimulatorArm64, **macosArm64** | closest of anything we own — macOS arm64 already builds; linuxX64/mingwX64 are ours to add |
| `screen-model` | jvm, wasm-js | no native klibs at all |
| `slot-preview-runtime` | jvm, wasm-js | no native klibs at all — the single blocker for the catalog |
| `ui-builder-protocol` | jvm, wasm-js | no native klibs at all |

And the third-party edges the same modules pull:

| Dependency | Native desktop story |
| --- | --- |
| `dev.snipme:highlights` (code pane) | full house — linuxX64, linuxArm64, mingwX64, macosArm64 |
| `androidx.graphics:graphics-shapes` | the port lists it as used as-is |
| `org.jetbrains.compose.material3.adaptive:adaptive{,-layout}` | macosArm64 and macosX64 only — **no linuxX64, no mingwX64**, and the port does not vendor it |
| `material-icons-extended`, `compose.components.resources` | resources are vendored by the port; icons would need checking |

That adaptive row is the sharpest single fact here. `:ui-builder` draws real
`SupportingPaneScaffold` on purpose (`UI_BUILDER_PREVIEW_FIDELITY.md`), so a native UI builder is
blocked on Linux and Windows by an artifact neither we nor the port publishes — before any of §2's
cost is counted.

## 4. The three candidates

### A. A native catalog — the cheap one, and the right spike

`native-catalog-m3` is already `commonMain`-only Compose with a `wasmJs` target: runtime, foundation,
material3, ui, resources, `graphics-shapes`, and `slot-preview-runtime`. Every one of those is either
served by the port or served as-is — **except `slot-preview-runtime`, which is ours**.

What it buys: a design-system catalog you hand somebody as one executable. No JVM, no browser tab, no
`serve` running, no network. That is a different artifact from the hosted catalog, not a better one —
the web surface stays what the product is built around.

What it does not buy: nothing in the render or serve lane changes, and the module already reaches
every designer through a URL.

Its real value is as the **measurement** for §2, which is why it comes first regardless of whether
anyone wants the binary.

### B. A native inspector — the one with a gap to fill

This is where the port supplies something that genuinely does not exist.

Compose Multiplatform has **no desktop window for Kotlin/Native**. `rc-player-compose-macosarm64` is
published today and is a library with nowhere to draw: an Apple-silicon consumer either goes through
the XCFramework and a SwiftUI host, or runs the JVM cut. There is no Linux or Windows story at all.
SDL3 plus `desktop-native-window` is exactly that missing shell, on all three desktops at once.

The product that falls out is a single binary that opens a document and lets you look at it:

- `.rc` documents — draw the document, scrub its timeline, walk the operation stream, and switch
  renderers the way `samples/macos-player` already switches between CMP and the AppKit-native path;
- portable preview bundles and UI-builder design JSON — both are **interpreted at runtime**, not
  compiled, so nothing about them needs a JVM in principle.

In `rc-players`' own vocabulary this is an eighth **host**, not an eighth implementation — the same
relationship the Wasm bundle and the XCFramework already have to the CMP player. That framing matters:
it is a distribution question, and the parity lanes stay exactly where they are.

Cost: publish `rc-player-*` for linuxX64 and mingwX64 (ours to do), a small SDL-hosted app module, and
whatever §2 turns out to cost. The macOS leg is the cheapest, because that target already builds.

### C. A native UI builder — not this

The editor frontend is the half of the UI builder that was already portable; it compiles for JVM and
`wasmJs` today. The half that is not portable is export, and export is the product: `usage-source-psi`
is IntelliJ PSI, `:ui-builder-export` and `:ui-builder-runtime` are JVM services, the render bundle is
unpacked into a JVM daemon, the `ui` command asks a Gradle build host to discover and compile a
module, and PNG/SVG export carries a Java 21 floor. A native editor shell would be a client that still
needs the JVM server for anything that generates Kotlin — the JVM does not leave the machine, it just
stops being the thing drawing the window. Add the adaptive gap from §3 and it is a lot of rope for a
second frontend.

There is one sub-case worth naming, because it is the only place a native binary could remove a JVM
rather than relocate one. `:ui-builder-renderer` is *interpretive*: it mounts a retained runtime and
draws a design document with no compile step. A native cut of that module would be a rasterizer with
no Java 21 floor — but only if Skia can render **offscreen, headless**, and the port's entire platform
layer is an SDL3 window. Whether Skiko-without-a-window works in this port is unverified and is the
first question to ask if anyone pulls this thread. Do not pull it before §6.1 answers.

## 5. What we would be taking on

- **A non-Central repository.** `maven.bitsycore.com` is a hard edge against this repository's
  deliberate "everything resolves from Maven Central, no composite, no `mavenLocal()`" policy. It is
  also **not reachable from the agent sandbox** — the proxy returns 403 on CONNECT, while Central
  resolves fine — so any such build is un-runnable in a web session and would need a CI decision.
- **A fork of Compose UI in the graph**, pinned to CMP 1.12.0 / Kotlin 2.4.20. Our version line would
  move at the port's pace on native desktop and at CMP's everywhere else.
- **Host build tooling**: cmake, Python 3, and an SDL3 build per host; Xcode CLT / gcc+X11 headers /
  mingw-w64 respectively. Three new CI lanes for something that ships nothing today.
- **Single-maintainer provenance.** MIT and openly documented, but a young project with one author, in
  the position of a Compose UI fork.

None of these is disqualifying for an experiment. All of them are disqualifying for the render lane.

## 6. If we do anything, in this order

1. **Measure §2.** Take `native-catalog-m3`, apply the bridge plugin, stub the one
   `slot-preview-runtime` seam it needs, and build `macosArm64` on a machine that can reach the port's
   repository. That single build answers whether prebuilt Compose-touching klibs link against the
   fork, which prices everything else. Nothing here should be decided before it runs.
2. **If it is cheap, do the inspector** — publish `rc-player-*` for linuxX64/mingwX64 and add a native
   host app in `rc-players`, documented as an eighth host in its lane table. That is the option with a
   gap to fill rather than a surface to duplicate.
3. **Leave the renderer alone** until somebody has answered whether this port can draw without a
   window, and leave the UI builder alone regardless.

**Non-goals**: replacing the JVM render or export lane, a second UI-builder frontend, and taking a
dependency on the port from anything that ships in `compose-preview-server`'s distribution.
