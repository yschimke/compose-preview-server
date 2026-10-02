// Which lane is showing, what it is called, and what each chip reports.
//
// The failure these guard against is a chip claiming something untrue — lit over a lane that is no
// longer on the stage, or naming a renderer that is not drawing. That is worse than a control that
// visibly fails, because nothing looks wrong.

import assert from "node:assert/strict";
import {
    anyInteractive,
    normalizeBakedPlayer,
    normalizeRcPlayer,
    backendRequiresRenderParam,
    bestLiveMode,
    currentLaneValue,
    laneChip,
    laneLabelText,
    liveInviteAvailable,
    liveTransportAvailable,
    restoreStaticPlayer,
    serverPlayerParam,
    type LaneFlags,
} from "../src/viewer/laneState.js";

describe("normalizeRcPlayer", () => {
    it("keeps every canonical id", () => {
        for (const id of [
            "androidx-view",
            "androidx-embedded",
            "cmp-android",
            "cmp-jvm",
            "cmp-wasm",
            "camaelon-js",
        ])
            assert.equal(normalizeRcPlayer(id), id);
    });

    it("reads a legacy ?rcPlayer= spelling as the player it always named", () => {
        assert.equal(normalizeRcPlayer("java"), "androidx-view");
        assert.equal(normalizeRcPlayer("view"), "androidx-view");
        assert.equal(normalizeRcPlayer("embedded"), "androidx-embedded");
        assert.equal(normalizeRcPlayer("js"), "camaelon-js");
        assert.equal(normalizeRcPlayer("rcplayer-jvm"), "cmp-jvm");
        assert.equal(normalizeRcPlayer("RCPLAYER-WASM"), "cmp-wasm");
        assert.equal(normalizeRcPlayer(null), "");
    });

    it("never reinterprets cmp-android in a request: it is the CMP player on Android", () => {
        assert.equal(normalizeRcPlayer("cmp-android"), "cmp-android");
    });
});

describe("normalizeBakedPlayer", () => {
    it("maps an older capture record's cmp-android / java to the AndroidX players", () => {
        assert.equal(normalizeBakedPlayer("cmp-android"), "androidx-embedded");
        assert.equal(normalizeBakedPlayer("java"), "androidx-view");
        assert.equal(
            normalizeBakedPlayer("androidx-embedded"),
            "androidx-embedded",
        );
        assert.equal(normalizeBakedPlayer(""), "");
    });
});

describe("backendRequiresRenderParam", () => {
    // What the server reports for an ordinary Remote Compose preview: the baked artifact is the
    // embedded player's capture, so a bare `/render` URL already is androidx-embedded.
    const BAKED_EMBEDDED = "androidx-embedded";

    it("names every lane a bare render is not", () => {
        assert.equal(
            backendRequiresRenderParam("cmp-jvm", BAKED_EMBEDDED),
            true,
        );
        assert.equal(
            backendRequiresRenderParam("androidx-view", BAKED_EMBEDDED),
            true,
        );
    });

    it("does NOT name the lane the bare render already is", () => {
        // The regression this exists for. While this answered `true` for the embedded player, the
        // viewer seeded its pick state from it and stamped `?rcPlayer=…` onto every first click
        // from a catalog — a URL that reads as a deliberate player choice and is a no-op.
        assert.equal(
            backendRequiresRenderParam("androidx-embedded", BAKED_EMBEDDED),
            false,
        );
    });

    it("follows the server's answer rather than assuming the embedded player", () => {
        // A preview pinning `RemoteViewPreviewWrapper` bakes through the view player. There
        // androidx-embedded is a genuine re-render and must name itself, while androidx-view is the
        // silent one —
        // the exact inversion that hardcoding the default would have got backwards.
        assert.equal(
            backendRequiresRenderParam("androidx-embedded", "androidx-view"),
            true,
        );
        assert.equal(
            backendRequiresRenderParam("androidx-view", "androidx-view"),
            false,
        );
    });

    it("names everything when the server cannot say which player baked", () => {
        // A non-Remote-Compose preview, or a server predating `data-rc-baked-player`. Naming a lane
        // that turns out to be redundant costs a cache entry; NOT naming one that turns out to be a
        // real re-render serves the wrong pixels, so the unknown case names.
        for (const absent of ["", null, undefined]) {
            assert.equal(
                backendRequiresRenderParam("androidx-embedded", absent),
                true,
            );
            assert.equal(
                backendRequiresRenderParam("androidx-view", absent),
                true,
            );
            assert.equal(backendRequiresRenderParam("cmp-jvm", absent), true);
        }
    });

    it("matches an older server's legacy baked-player report", () => {
        // An older server reported the embedded capture as `cmp-android` and the view one as
        // `java`. Read as a capture record, those are still the silent lanes.
        assert.equal(
            backendRequiresRenderParam("androidx-embedded", "cmp-android"),
            false,
        );
        assert.equal(
            backendRequiresRenderParam("androidx-view", "java"),
            false,
        );
        // …and the CMP player on Android is never the baked one.
        assert.equal(
            backendRequiresRenderParam("cmp-android", "cmp-android"),
            true,
        );
    });

    it("names the CMP player on Android, a daemon lane", () => {
        assert.equal(
            backendRequiresRenderParam("cmp-android", BAKED_EMBEDDED),
            true,
        );
    });

    it("never names a browser lane, which does not use /render at all", () => {
        assert.equal(
            backendRequiresRenderParam("camaelon-js", BAKED_EMBEDDED),
            false,
        );
        assert.equal(backendRequiresRenderParam("camaelon-js", ""), false);
    });
});

describe("server-side player persistence", () => {
    it("keeps the explicit player in full live override replacements", () => {
        assert.equal(
            serverPlayerParam("androidx-embedded", true),
            "androidx-embedded",
        );
        assert.equal(serverPlayerParam("cmp-jvm", true), "cmp-jvm");
        assert.equal(serverPlayerParam("androidx-view", true), "androidx-view");
        assert.equal(serverPlayerParam("androidx-embedded", false), null);
        assert.equal(serverPlayerParam("camaelon-js", true), null);
        assert.equal(serverPlayerParam("cmp-wasm", true), null);
        // The CMP player on Android rides `/render` like the AndroidX daemon players.
        assert.equal(serverPlayerParam("cmp-android", true), "cmp-android");
        // A legacy spelling is emitted canonically.
        assert.equal(serverPlayerParam("java", true), "androidx-view");
    });

    it("restores a default that needs no parameter after a browser player", () => {
        assert.deepEqual(
            restoreStaticPlayer(
                {
                    defaultBackend: "androidx-embedded",
                    pickedBackend: "androidx-embedded",
                    picked: false,
                },
                "androidx-embedded",
            ),
            {
                defaultBackend: "androidx-embedded",
                pickedBackend: "androidx-embedded",
                // Returning to the static lane on the default needs no parameter to describe it:
                // the bare render is already this player, and the server said so.
                picked: false,
            },
        );
        assert.deepEqual(
            restoreStaticPlayer(
                {
                    defaultBackend: "androidx-view",
                    pickedBackend: "androidx-view",
                    picked: false,
                },
                "androidx-view",
            ),
            {
                defaultBackend: "androidx-view",
                pickedBackend: "androidx-view",
                picked: false,
            },
        );
    });

    it("keeps naming the restored default when the baked player is unreported", () => {
        // Without `data-rc-baked-player` nothing establishes that a bare URL is this lane, so the
        // restore names it rather than betting on it. The old code bet, and on the embedded player it
        // happened to be right — which is why the bet went unnoticed until a pinned preview.
        assert.deepEqual(
            restoreStaticPlayer({
                defaultBackend: "androidx-embedded",
                pickedBackend: "androidx-embedded",
                picked: false,
            }),
            {
                defaultBackend: "androidx-embedded",
                pickedBackend: "androidx-embedded",
                picked: true,
            },
        );
    });

    it("does not replace an explicit server-side visitor pick", () => {
        const pick = {
            defaultBackend: "androidx-embedded",
            pickedBackend: "cmp-jvm",
            picked: true,
        };
        assert.strictEqual(restoreStaticPlayer(pick), pick);
    });

    it("restores the retained visitor pick after a browser-only lane", () => {
        assert.deepEqual(
            restoreStaticPlayer({
                defaultBackend: "androidx-embedded",
                pickedBackend: "androidx-view",
                picked: false,
            }),
            {
                defaultBackend: "androidx-embedded",
                pickedBackend: "androidx-view",
                picked: true,
            },
        );
        assert.deepEqual(
            restoreStaticPlayer({
                defaultBackend: "androidx-embedded",
                pickedBackend: "cmp-jvm",
                picked: false,
            }),
            {
                defaultBackend: "androidx-embedded",
                pickedBackend: "cmp-jvm",
                picked: true,
            },
        );
    });
});

const lanes = (over: Partial<LaneFlags> = {}): LaneFlags => ({
    rcWasm: false,
    rc: false,
    wasm: false,
    spec: false,
    live: false,
    ...over,
});

describe("anyInteractive", () => {
    it("counts every lane that paints a RUNNING composition", () => {
        // Picking "Camaelon JS" from the combo must light the same status dot as clicking into Live: both
        // are the claim "this is running", and reporting them differently would make the dot mean
        // two things.
        for (const key of ["live", "wasm", "rc", "rcWasm"] as const) {
            assert.equal(anyInteractive(lanes({ [key]: true })), true, key);
        }
    });

    it("does not count a finished image", () => {
        assert.equal(anyInteractive(lanes()), false);
        assert.equal(
            anyInteractive(lanes({ spec: true })),
            false,
            "the design spec is a picture",
        );
    });
});

describe("liveTransportAvailable / bestLiveMode", () => {
    it("prefers the daemon stream, and falls back to the in-browser app", () => {
        assert.equal(bestLiveMode({ daemon: true, wasm: true }), "live");
        assert.equal(bestLiveMode({ daemon: true, wasm: false }), "live");
        assert.equal(bestLiveMode({ daemon: false, wasm: true }), "wasm");
    });

    it("answers null, not a lane, when this session offers neither", () => {
        assert.equal(bestLiveMode({ daemon: false, wasm: false }), null);
        assert.equal(
            liveTransportAvailable({ daemon: false, wasm: false }),
            false,
        );
        assert.equal(
            liveTransportAvailable({ daemon: false, wasm: true }),
            true,
        );
    });
});

describe("currentLaneValue", () => {
    const pick = { defaultBackend: "", pickedBackend: "", picked: false };

    it("reports the painting lane, most specific first", () => {
        assert.equal(
            currentLaneValue(lanes({ rcWasm: true }), pick),
            "rc:cmp-wasm",
        );
        assert.equal(
            currentLaneValue(lanes({ rc: true }), pick),
            "rc:camaelon-js",
        );
        assert.equal(currentLaneValue(lanes({ wasm: true }), pick), "wasm");
        assert.equal(currentLaneValue(lanes({ spec: true }), pick), "spec");
    });

    it("falls through a daemon stream to the lane it will return to", () => {
        // A stream is not one of the offered renderers — it is the live form of whichever one is
        // picked. Reporting "live" here would make the chip rename itself on entering Live and
        // forget which renderer it came from.
        const rc = {
            defaultBackend: "androidx-view",
            pickedBackend: "",
            picked: false,
        };
        assert.equal(
            currentLaneValue(lanes({ live: true }), rc),
            "rc:androidx-view",
        );
    });

    it("prefers the visitor's pick over the server's default", () => {
        assert.equal(
            currentLaneValue(lanes(), {
                defaultBackend: "androidx-view",
                pickedBackend: "cmp-jvm",
                picked: true,
            }),
            "rc:cmp-jvm",
        );
        assert.equal(
            currentLaneValue(lanes(), {
                defaultBackend: "androidx-view",
                pickedBackend: "cmp-jvm",
                picked: false,
            }),
            "rc:androidx-view",
            "an unpicked backend stays on the server's default",
        );
    });

    it("is the plain snapshot when there is no Remote Compose at all", () => {
        assert.equal(currentLaneValue(lanes(), pick), "png");
    });
});

describe("laneLabelText", () => {
    const laneOptions = new Map([
        ["rc:androidx-view", "AndroidX View"],
        ["rc:camaelon-js", "Camaelon JS"],
        ["png", "Snapshot"],
    ]);

    it("says Live while the stream is up, whatever is picked underneath", () => {
        assert.equal(
            laneLabelText({
                live: true,
                laneOptions,
                wanted: "rc:androidx-view",
                defaultLabel: "Live preview",
            }),
            "Live",
        );
    });

    it("uses the combo's OWN label, so the two cannot disagree", () => {
        assert.equal(
            laneLabelText({
                live: false,
                laneOptions,
                wanted: "rc:camaelon-js",
                defaultLabel: "Live preview",
            }),
            "Camaelon JS",
        );
    });

    it("keeps naming the render lane on the spec lane", () => {
        // There is no `spec` option, deliberately: the spec chip beside this one is already lit and
        // names it, and two adjacent chips both reading "Figma" would be two controls arguing about
        // the same fact. So this one names where clicking it goes back TO.
        assert.equal(
            laneLabelText({
                live: false,
                laneOptions,
                wanted: "spec",
                defaultLabel: "Live preview",
            }),
            "Live preview",
        );
    });

    it("falls back to the server-rendered label on a preview with no combo", () => {
        assert.equal(
            laneLabelText({
                live: false,
                laneOptions: null,
                wanted: "png",
                defaultLabel: "Live preview",
            }),
            "Live preview",
        );
    });
});

describe("laneChip", () => {
    it("presses when its lane is on the stage", () => {
        assert.deepEqual(laneChip({ onLane: true, available: true }), {
            pressed: true,
            disabled: false,
        });
    });

    it("stays ENABLED on its own lane even when unavailable", () => {
        // The only way back out. A chip that disabled itself on entry would strand the visitor on
        // the lane it just entered.
        assert.deepEqual(laneChip({ onLane: true, available: false }), {
            pressed: true,
            disabled: false,
        });
    });

    it("disables only when there is nothing to enter and nothing to leave", () => {
        assert.deepEqual(laneChip({ onLane: false, available: false }), {
            pressed: false,
            disabled: true,
        });
        assert.deepEqual(laneChip({ onLane: false, available: true }), {
            pressed: false,
            disabled: false,
        });
    });
});

describe("liveInviteAvailable", () => {
    it("offers the lane only from a static render of this preview", () => {
        assert.equal(
            liveInviteAvailable({
                interactive: false,
                transport: true,
                mode: "png",
            }),
            true,
        );
    });

    it("withdraws the offer once an interactive lane is already painting", () => {
        // Otherwise the stage would keep inviting a visitor into the lane they are standing in,
        // and the hint would sit over a live canvas as a permanent badge.
        assert.equal(
            liveInviteAvailable({
                interactive: true,
                transport: true,
                mode: "live",
            }),
            false,
        );
    });

    it("withdraws it when there is no live lane to enter", () => {
        assert.equal(
            liveInviteAvailable({
                interactive: false,
                transport: false,
                mode: "png",
            }),
            false,
        );
    });

    it("withdraws it on the fixed-frame lanes", () => {
        // The spec, the usage source and a recorded motion clip are not this preview's render.
        // Clicking through from one of them would discard what the visitor asked to look at.
        for (const mode of ["spec", "source", "motion"]) {
            assert.equal(
                liveInviteAvailable({
                    interactive: false,
                    transport: true,
                    mode,
                }),
                false,
                mode,
            );
        }
    });
});
