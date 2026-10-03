// The push worker's two handlers, against a stand-in scope: what a notification says, where a tap
// may go, and what the app badge counts.

import "./setup.js";
import assert from "node:assert/strict";
import {
    handleNotificationClick,
    handlePush,
    notificationFor,
    parsePayload,
    sameOriginUrl,
    type WindowClientLike,
    type WorkerScope,
} from "../src/push/worker.js";

const ORIGIN = "https://preview.example";

function scope(windows: WindowClientLike[] = []) {
    const shown: { title: string; options?: NotificationOptions }[] = [];
    const opened: string[] = [];
    const badges: (number | "clear")[] = [];
    const s: WorkerScope = {
        location: { origin: ORIGIN },
        registration: {
            showNotification: async (title, options) => {
                shown.push({ title, options });
            },
            getNotifications: async () =>
                shown.map((n) => ({ data: n.options?.data }) as Notification),
        },
        clients: {
            matchAll: async () => windows,
            openWindow: async (url) => {
                opened.push(url);
            },
        },
        navigator: {
            setAppBadge: async (count) => {
                badges.push(count ?? 0);
            },
            clearAppBadge: async () => {
                badges.push("clear");
            },
        },
    };
    return { s, shown, opened, badges };
}

const PAYLOAD = JSON.stringify({
    kind: "replies",
    designId: "d1",
    threadId: "t1",
    title: "New reply on “Checkout”",
    url: `${ORIGIN}/ui-builder/d1#thread=t1`,
    count: 3,
});

describe("the push worker", () => {
    it("reads only a well-formed payload", () => {
        assert.equal(parsePayload("not json"), null);
        assert.equal(parsePayload(JSON.stringify({ kind: "replies" })), null);
        assert.equal(parsePayload(PAYLOAD)!.count, 3);
    });

    it("never opens another origin", () => {
        assert.equal(
            sameOriginUrl("https://evil.example/x", ORIGIN),
            `${ORIGIN}/`,
        );
        assert.equal(
            sameOriginUrl("javascript:alert(1)", ORIGIN),
            `${ORIGIN}/`,
        );
        assert.equal(
            sameOriginUrl("/ui-builder/d1", ORIGIN),
            `${ORIGIN}/ui-builder/d1`,
        );
    });

    it("shows one notification per thread, with the site icon and the count", () => {
        const spec = notificationFor(parsePayload(PAYLOAD)!, ORIGIN);
        assert.equal(spec.title, "New reply on “Checkout”");
        assert.equal(spec.options.tag, "cp-push:d1:t1");
        assert.equal(spec.options.icon, "/icons/app-192.png");
        // The status bar masks the badge by alpha, so it is the monochrome glyph, not the app icon.
        assert.equal(spec.options.badge, "/icons/badge-96.png");
        assert.match(spec.options.body!, /3 updates/);
    });

    it("sets the app badge to what the visible notifications stand for", async () => {
        const { s, shown, badges } = scope();
        await handlePush(s, PAYLOAD);
        await handlePush(
            s,
            JSON.stringify({
                ...JSON.parse(PAYLOAD),
                threadId: "t2",
                count: 1,
            }),
        );
        assert.equal(shown.length, 2);
        assert.deepEqual(badges, [3, 4]);
    });

    it("still shows something for an unreadable push", async () => {
        const { s, shown } = scope();
        await handlePush(s, null);
        assert.equal(shown.length, 1);
    });

    it("focuses an open window of the site and takes it to the thread", async () => {
        const visited: string[] = [];
        let focused = 0;
        const other: WindowClientLike = {
            url: "https://elsewhere.example/",
            focus: async () => undefined,
        };
        const mine: WindowClientLike = {
            url: `${ORIGIN}/status`,
            focus: async () => {
                focused++;
            },
            navigate: async (url) => {
                visited.push(url);
            },
        };
        const { s, opened } = scope([other, mine]);
        let closed = false;
        await handleNotificationClick(s, {
            data: { url: `${ORIGIN}/ui-builder/d1#thread=t1` },
            close: () => {
                closed = true;
            },
        });
        assert.equal(closed, true);
        assert.equal(focused, 1);
        assert.deepEqual(visited, [`${ORIGIN}/ui-builder/d1#thread=t1`]);
        assert.deepEqual(opened, []);
    });

    it("opens a window when none is open, and only on this origin", async () => {
        const { s, opened } = scope();
        await handleNotificationClick(s, {
            data: { url: "https://evil.example/phish" },
            close: () => undefined,
        });
        assert.deepEqual(opened, [`${ORIGIN}/`]);
    });
});
