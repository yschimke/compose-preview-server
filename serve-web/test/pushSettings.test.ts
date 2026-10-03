// Settings → Notifications: what the group offers on each kind of browser, and the order the
// "Turn on" click does things in — permission first, from the gesture, then the worker, then the
// subscription — against a stand-in for every browser API it touches.

import "./setup.js";
import assert from "node:assert/strict";
import { resetDom } from "./setup.js";
import { installPushSettings, type PushBrowser } from "../src/push/settings.js";
import {
    applicationServerKey,
    isIos,
    pushAvailability,
    type PushEnvironment,
} from "../src/push/support.js";

const READY: PushEnvironment = {
    isSecureContext: true,
    hasServiceWorker: true,
    hasPushManager: true,
    hasNotification: true,
    userAgent: "Mozilla/5.0 (Linux; Android 14) Chrome/130",
    maxTouchPoints: 5,
    standalone: false,
};

const IPHONE_UA =
    "Mozilla/5.0 (iPhone; CPU iPhone OS 17_5 like Mac OS X) AppleWebKit/605.1.15 Version/17.5 Mobile/15E148 Safari/604.1";
const IPAD_AS_MAC_UA =
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 Version/17.5 Safari/605.1.15";

/** The group as `ServeWeb.pushNotificationSettings` renders it. */
function page(): HTMLElement {
    document.body.innerHTML = `
      <fieldset class="cp-settings-group cp-settings-notifications" data-cp-push-settings
        data-cp-push-key="/api/push/key" data-cp-push-subscribe="/api/push/subscribe"
        data-cp-push-preferences="/api/push/preferences" data-cp-push-worker="/push-sw.js">
        <legend>Notifications</legend>
        <p data-cp-push-status role="status">Get a notification…</p>
        <label><input type="checkbox" data-cp-push-kind="replies" checked disabled></label>
        <label><input type="checkbox" data-cp-push-kind="mentions" checked disabled></label>
        <label><input type="checkbox" data-cp-push-kind="reviews" checked disabled></label>
        <button type="button" data-cp-push-toggle hidden>Turn on notifications</button>
      </fieldset>`;
    return document.querySelector("[data-cp-push-settings]") as HTMLElement;
}

function status(): string {
    return document.querySelector("[data-cp-push-status]")!.textContent || "";
}

function toggle(): HTMLButtonElement {
    return document.querySelector("[data-cp-push-toggle]") as HTMLButtonElement;
}

function box(kind: string): HTMLInputElement {
    return document.querySelector(
        `[data-cp-push-kind="${kind}"]`,
    ) as HTMLInputElement;
}

const PUBLIC_KEY =
    "BP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27mlmlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A8";

interface Fake {
    browser: PushBrowser;
    log: string[];
    requests: { url: string; method: string; body?: string }[];
    permission: NotificationPermission;
    subscribed: boolean;
    unregistered: boolean;
    submitted: number;
}

function fake(options: {
    permission?: NotificationPermission;
    grant?: NotificationPermission;
    subscribed?: boolean;
    kinds?: string[];
    /** What `POST /api/push/subscribe` answers; 201 by default. */
    subscribeStatus?: number;
}): Fake {
    const state: Fake = {
        log: [],
        requests: [],
        permission: options.permission ?? "default",
        subscribed: options.subscribed ?? false,
        unregistered: false,
        submitted: 0,
        browser: undefined as unknown as PushBrowser,
    };
    const subscription = {
        endpoint: "https://fcm.googleapis.com/fcm/send/secret",
        options: {
            applicationServerKey: applicationServerKey(PUBLIC_KEY).buffer,
        },
        toJSON: () => ({
            endpoint: "https://fcm.googleapis.com/fcm/send/secret",
            keys: { p256dh: "p", auth: "a" },
        }),
        unsubscribe: async () => {
            state.log.push("unsubscribe");
            state.subscribed = false;
            return true;
        },
    };
    const registration = {
        active: {},
        pushManager: {
            getSubscription: async () =>
                state.subscribed ? subscription : null,
            subscribe: async (opts: PushSubscriptionOptionsInit) => {
                state.log.push(
                    `subscribe:${opts.userVisibleOnly}:${(opts.applicationServerKey as Uint8Array).length}`,
                );
                state.subscribed = true;
                return subscription;
            },
        },
        unregister: async () => {
            state.log.push("unregister");
            state.unregistered = true;
            return true;
        },
    } as unknown as ServiceWorkerRegistration;
    const json = (body: unknown, status = 200) =>
        new Response(JSON.stringify(body), {
            status,
            headers: { "Content-Type": "application/json" },
        });
    state.browser = {
        fetch: async (url, init) => {
            const method = init?.method ?? "GET";
            state.log.push(`${method} ${url}`);
            state.requests.push({
                url,
                method,
                body: init?.body as string | undefined,
            });
            if (url === "/api/push/key")
                return json({ publicKey: PUBLIC_KEY, kinds: [] });
            if (url === "/api/push/preferences" && method === "GET") {
                return json({
                    kinds: options.kinds ?? ["replies", "mentions", "reviews"],
                    devices: 0,
                });
            }
            if (url === "/api/push/subscribe" && method === "POST") {
                const status = options.subscribeStatus ?? 201;
                if (status >= 300) {
                    return json({ error: "refused by the server" }, status);
                }
                const sent = JSON.parse(init!.body as string) as {
                    kinds?: string[];
                };
                // Like the server: no kinds keeps the person's own choice.
                return json(
                    {
                        kinds: sent.kinds ??
                            options.kinds ?? ["replies", "mentions", "reviews"],
                        devices: 1,
                    },
                    status,
                );
            }
            return json({ kinds: [], devices: 0 });
        },
        permission: () => state.permission,
        requestPermission: async () => {
            state.log.push("requestPermission");
            state.permission = options.grant ?? "granted";
            return state.permission;
        },
        register: async (url, scope) => {
            state.log.push(`register:${url}:${scope}`);
            return registration;
        },
        getRegistration: async () =>
            state.subscribed || state.log.some((l) => l.startsWith("register"))
                ? registration
                : undefined,
        submit: () => {
            state.log.push("submit");
            state.submitted++;
        },
    };
    return state;
}

async function settle(): Promise<void> {
    for (let i = 0; i < 20; i++) await new Promise((r) => setTimeout(r, 0));
}

describe("push availability", () => {
    it("names iOS, including the iPad that says it is a Mac", () => {
        assert.equal(isIos(IPHONE_UA, 5), true);
        assert.equal(isIos(IPAD_AS_MAC_UA, 5), true);
        assert.equal(isIos(IPAD_AS_MAC_UA, 0), false);
        assert.equal(isIos(READY.userAgent, 5), false);
    });

    it("asks an iPhone in a Safari tab to add to the Home Screen first", () => {
        const tab = { ...READY, hasPushManager: false, userAgent: IPHONE_UA };
        assert.equal(pushAvailability(tab), "install-first");
        // Installed, with PushManager, it is an ordinary browser.
        assert.equal(
            pushAvailability({
                ...tab,
                hasPushManager: true,
                standalone: true,
            }),
            "ready",
        );
        // Installed and still without it (iOS before 16.4) is simply unsupported.
        assert.equal(
            pushAvailability({ ...tab, standalone: true }),
            "unsupported",
        );
    });

    it("explains HTTPS before anything else", () => {
        assert.equal(
            pushAvailability({ ...READY, isSecureContext: false }),
            "insecure",
        );
    });

    it("decodes a base64url VAPID key", () => {
        const key = applicationServerKey(PUBLIC_KEY);
        assert.equal(key.length, 65);
        assert.equal(key[0], 4);
    });
});

describe("Settings → Notifications", () => {
    beforeEach(resetDom);

    it("explains the HTTPS requirement on an insecure page and offers nothing", async () => {
        page();
        await installPushSettings(
            document,
            { ...READY, isSecureContext: false },
            fake({}).browser,
        );
        assert.match(status(), /HTTPS/);
        assert.equal(toggle().hidden, true);
        assert.equal(box("replies").disabled, true);
    });

    it("tells an iPhone in a tab to add to the Home Screen first", async () => {
        page();
        await installPushSettings(
            document,
            { ...READY, hasPushManager: false, userAgent: IPHONE_UA },
            fake({}).browser,
        );
        assert.match(status(), /Add to Home Screen first/);
        assert.equal(toggle().hidden, true);
    });

    it("offers the button without asking for permission on load", async () => {
        page();
        const f = fake({ kinds: ["replies"] });
        await installPushSettings(document, READY, f.browser);
        assert.equal(toggle().hidden, false);
        assert.equal(toggle().textContent, "Turn on notifications");
        assert.equal(f.log.includes("requestPermission"), false);
        assert.equal(
            f.log.some((l) => l.startsWith("register")),
            false,
        );
        // The person's kinds, from their other devices, are shown and may be changed before turning on.
        assert.equal(box("replies").checked, true);
        assert.equal(box("mentions").checked, false);
        assert.equal(box("mentions").disabled, false);
    });

    it("asks permission first, from the click, then registers the root worker and subscribes", async () => {
        page();
        const f = fake({ kinds: ["replies", "reviews"] });
        await installPushSettings(document, READY, f.browser);
        f.log.length = 0;
        toggle().click();
        // Synchronously inside the click: the permission prompt is the first thing that happens.
        assert.equal(f.log[0], "requestPermission");
        await settle();
        assert.deepEqual(f.log, [
            "requestPermission",
            "GET /api/push/key",
            "register:/push-sw.js:/",
            "subscribe:true:65",
            "POST /api/push/subscribe",
        ]);
        const post = f.requests.find((r) => r.method === "POST")!;
        const body = JSON.parse(post.body!) as {
            endpoint: string;
            kinds: string[];
        };
        assert.equal(
            body.endpoint,
            "https://fcm.googleapis.com/fcm/send/secret",
        );
        assert.deepEqual(body.kinds, ["replies", "reviews"]);
        assert.equal(toggle().textContent, "Turn off notifications");
        // The endpoint is a secret: the page never shows it.
        assert.equal(
            document.body.innerHTML.includes("fcm.googleapis.com"),
            false,
        );
    });

    it("does nothing more when permission is refused", async () => {
        page();
        const f = fake({ grant: "denied" });
        await installPushSettings(document, READY, f.browser);
        toggle().click();
        await settle();
        assert.match(status(), /blocked/);
        assert.equal(
            f.log.some((l) => l.startsWith("register")),
            false,
        );
        assert.equal(
            f.requests.some((r) => r.method === "POST"),
            false,
        );
    });

    it("saves a kind change while on, and turning off unsubscribes and unregisters", async () => {
        page();
        const f = fake({ permission: "granted", subscribed: true });
        await installPushSettings(document, READY, f.browser);
        assert.equal(toggle().textContent, "Turn off notifications");
        box("mentions").checked = false;
        box("mentions").dispatchEvent(new Event("change"));
        await settle();
        const put = f.requests.find((r) => r.method === "PUT")!;
        assert.deepEqual(JSON.parse(put.body!), {
            kinds: ["replies", "reviews"],
        });

        toggle().click();
        await settle();
        const del = f.requests.find((r) => r.method === "DELETE")!;
        assert.equal(del.url, "/api/push/subscribe");
        assert.ok(f.log.includes("unsubscribe"));
        assert.equal(f.unregistered, true);
        assert.equal(toggle().textContent, "Turn on notifications");
    });

    it("re-posts an existing subscription on load, and shows it on only once the server binds it", async () => {
        page();
        // Alice turned notifications on in this browser; Bob has since signed in. His choice is
        // "mentions" only, and that is what the server answers for him.
        const f = fake({
            permission: "granted",
            subscribed: true,
            kinds: ["mentions"],
        });
        await installPushSettings(document, READY, f.browser);
        const post = f.requests.find((r) => r.method === "POST")!;
        assert.equal(post.url, "/api/push/subscribe");
        const body = JSON.parse(post.body!) as {
            endpoint: string;
            kinds?: string[];
        };
        assert.equal(
            body.endpoint,
            "https://fcm.googleapis.com/fcm/send/secret",
        );
        // No kinds: re-binding must not overwrite the signed-in person's choice with the boxes.
        assert.equal("kinds" in body, false);
        assert.equal(toggle().textContent, "Turn off notifications");
        assert.equal(box("mentions").checked, true);
        assert.equal(box("replies").checked, false);
        assert.equal(f.log.includes("requestPermission"), false);
    });

    it("turns a subscription the server will not bind to this person off, here too", async () => {
        page();
        const f = fake({
            permission: "granted",
            subscribed: true,
            subscribeStatus: 422,
        });
        await installPushSettings(document, READY, f.browser);
        assert.equal(toggle().textContent, "Turn on notifications");
        assert.ok(f.log.includes("unsubscribe"));
        assert.equal(f.subscribed, false);
        assert.match(status(), /refused by the server/);
    });

    it("shows a subscription it could not confirm as off, without throwing it away", async () => {
        page();
        const f = fake({
            permission: "granted",
            subscribed: true,
            subscribeStatus: 503,
        });
        await installPushSettings(document, READY, f.browser);
        assert.equal(toggle().textContent, "Turn on notifications");
        assert.equal(f.subscribed, true);
        assert.match(status(), /Could not confirm/);
    });

    it("turns notifications off before Sign out submits", async () => {
        page();
        document.body.insertAdjacentHTML(
            "beforeend",
            `<form method="post" action="/auth/github/logout?return=%2F" data-cp-push-signout>
               <button type="submit">Sign out</button>
             </form>`,
        );
        const f = fake({ permission: "granted", subscribed: true });
        await installPushSettings(document, READY, f.browser);
        f.log.length = 0;
        const form = document.querySelector("form") as HTMLFormElement;
        const submit = new Event("submit", { cancelable: true });
        form.dispatchEvent(submit);
        assert.equal(submit.defaultPrevented, true);
        await settle();
        assert.deepEqual(f.log, [
            "DELETE /api/push/subscribe",
            "unsubscribe",
            "unregister",
            "submit",
        ]);
        const del = f.requests.find((r) => r.method === "DELETE")!;
        assert.deepEqual(JSON.parse(del.body!), {
            endpoint: "https://fcm.googleapis.com/fcm/send/secret",
        });
    });

    it("signs out straight away when this browser has no subscription", async () => {
        page();
        document.body.insertAdjacentHTML(
            "beforeend",
            `<form method="post" action="/auth/github/logout" data-cp-push-signout></form>`,
        );
        const f = fake({});
        await installPushSettings(document, READY, f.browser);
        f.log.length = 0;
        (document.querySelector("form") as HTMLFormElement).dispatchEvent(
            new Event("submit", { cancelable: true }),
        );
        await settle();
        assert.deepEqual(f.log, ["submit"]);
    });
});
