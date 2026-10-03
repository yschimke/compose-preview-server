import { strict as assert } from "node:assert";
import { safeEvent, safePath, installAnalytics } from "../src/analytics.js";

describe("analytics", () => {
    it("removes credentials, searches and private design names", () => {
        assert.equal(
            safePath(
                "/m3/?token=secret&q=private#secret",
                "https://preview.test",
            ),
            "/m3/",
        );
        assert.equal(
            safePath(
                "/ui-builder/private-project?token=secret",
                "https://preview.test",
            ),
            "/ui-builder/",
        );
        assert.equal(
            safePath("/auth/device/secret", "https://preview.test"),
            "/",
        );
        assert.deepEqual(
            safeEvent("property_changed", {
                value: "private text",
                source: "code",
                token: "secret",
            }),
            {},
        );
        assert.equal(safeEvent("private-event-name", {}), null);
        assert.deepEqual(
            safeEvent("renderer_changed", {
                mode: "live",
                url: "?token=secret",
            }),
            { mode: "live" },
        );
    });
    it("does nothing without explicit deployment configuration", () => {
        const count = document.scripts.length;
        installAnalytics(document.createElement("script"));
        assert.equal(document.scripts.length, count);
        assert.equal(window.previewTelemetry, undefined);
    });
    it("installs the bridge, sanitizes every payload and ignores broken events", () => {
        const script = document.createElement("script");
        script.dataset.umamiUrl = "https://preview.test/__analytics";
        script.dataset.websiteId = "e676c9b4-11e4-4ef1-a4d7-87001773e9f2";
        const append = document.head.appendChild.bind(document.head);
        let tracker: HTMLScriptElement;
        document.head.appendChild = ((node: HTMLScriptElement) => {
            tracker = node;
            return node;
        }) as typeof document.head.appendChild;
        try {
            installAnalytics(script);
        } finally {
            document.head.appendChild = append;
        }
        const host = window as any;
        assert.ok(host.previewTelemetry);
        assert.doesNotThrow(() =>
            host.previewTelemetry.track("undo", "bad json"),
        );
        const payload = host.previewAnalyticsBeforeSend("event", {
            url: "/ui-builder/secret?token=abc",
            title: "Private project",
            referrer: "https://private.test?token=abc",
            name: "undo",
            data: { source: "secret" },
        });
        assert.equal(payload.url, "/ui-builder/");
        assert.equal(payload.referrer, "");
        assert.equal(JSON.stringify(payload).includes("secret"), false);
        assert.equal(host.previewAnalyticsBeforeSend("identify", {}), false);
        assert.equal(tracker!.dataset.hostUrl, script.dataset.umamiUrl);
        delete host.previewTelemetry;
        delete host.previewAnalyticsBeforeSend;
    });
});
