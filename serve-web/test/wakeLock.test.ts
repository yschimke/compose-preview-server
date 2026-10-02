// The viewer's screen wake lock: held while a live stream or motion capture runs, released when the
// last one stops, dropped while hidden and re-acquired on return only if something still runs.

import "./setup.js";
import assert from "node:assert/strict";
import { ScreenWakeHold, type WakeLockApi } from "../src/viewer/wakeLock.js";

function fakeApi() {
    const log: string[] = [];
    const api: WakeLockApi = {
        request: () => {
            log.push("request");
            return Promise.resolve({
                release: () => {
                    log.push("release");
                    return Promise.resolve();
                },
            });
        },
    };
    return { api, log };
}

const tick = () => new Promise((r) => setTimeout(r, 0));

describe("the viewer's screen wake lock", () => {
    it("holds while any reason is held and releases with the last", async () => {
        const { api, log } = fakeApi();
        const hold = new ScreenWakeHold(api, () => true);
        hold.hold("live", true);
        hold.hold("motion", true);
        await tick();
        assert.equal(hold.held, true);
        hold.hold("live", false);
        assert.equal(hold.held, true, "motion still running");
        hold.hold("motion", false);
        assert.equal(hold.held, false);
        assert.deepEqual(log, ["request", "release"]);
    });

    it("re-acquires on return only while something still runs", async () => {
        const { api, log } = fakeApi();
        let visible = true;
        const hold = new ScreenWakeHold(api, () => visible);
        hold.hold("live", true);
        await tick();
        visible = false;
        hold.visibilityChanged();
        assert.equal(hold.held, false);
        visible = true;
        hold.visibilityChanged();
        await tick();
        assert.equal(hold.held, true);
        hold.hold("live", false);
        visible = false;
        hold.visibilityChanged();
        visible = true;
        hold.visibilityChanged();
        await tick();
        assert.deepEqual(log, ["request", "request", "release"]);
    });

    it("does nothing without the API", () => {
        const hold = new ScreenWakeHold(null, () => true);
        hold.hold("live", true);
        assert.equal(hold.held, false);
    });
});
