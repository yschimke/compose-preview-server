// Settings → Notifications: turn Web Push on or off for this browser, and choose what it is for.
//
// The server renders the group (`ServeWeb.pushNotificationSettings`) with its kinds disabled and its
// button hidden; this decides what the browser can actually offer and enables exactly that. Three
// rules shape it:
//
// * **Permission is asked from the click and nowhere else.** `Notification.requestPermission()` is
//   the first thing the button's handler does, before any network round trip, so the browser sees
//   the user's gesture (Safari is strict about that) and nobody is ever prompted on page load.
// * **The worker is registered only when somebody turns notifications on**, at `/push-sw.js` with
//   scope `/`. It handles `push` and `notificationclick` and nothing else — no `fetch` — so it
//   cannot change how any page loads, and the UI builder's own `/ui-builder/` worker, being the
//   more specific scope, keeps controlling the editor. Turning notifications off unregisters it.
// * **Nothing about a subscription is shown.** The endpoint and keys go to the server in the
//   subscribe request, and the page only ever reads back the kinds and a device count.
//
// A subscription belongs to the browser's origin, not to whoever is signed in, so it outlives a
// change of account. Two things keep it from delivering one person's notifications to the next:
// on every load an existing subscription is posted again — idempotent on the server, and it binds
// the endpoint to the person signed in now — and is shown as on only once the server has said so;
// and Settings → Session → Sign out turns it off before the session ends.

import {
    applicationServerKey,
    availabilityMessage,
    browserEnvironment,
    pushAvailability,
    type PushEnvironment,
} from "./support.js";

/** The browser surface this needs, so a test can stand in for every piece of it. */
export interface PushBrowser {
    fetch(input: string, init?: RequestInit): Promise<Response>;
    permission(): NotificationPermission;
    requestPermission(): Promise<NotificationPermission>;
    register(url: string, scope: string): Promise<ServiceWorkerRegistration>;
    getRegistration(
        scope: string,
    ): Promise<ServiceWorkerRegistration | undefined>;
    /** Submit [form] for real — the sign-out, once this browser's subscription is gone. */
    submit(form: HTMLFormElement): void;
}

export function liveBrowser(): PushBrowser {
    return {
        fetch: (input, init) => fetch(input, init),
        permission: () => Notification.permission,
        requestPermission: () => Notification.requestPermission(),
        register: (url, scope) =>
            navigator.serviceWorker.register(url, { scope }),
        getRegistration: (scope) =>
            navigator.serviceWorker.getRegistration(scope),
        // `submit()`, not `requestSubmit()`: it does not fire `submit` again, so the handler
        // that called it is not re-entered.
        submit: (form) => form.submit(),
    };
}

const SCOPE = "/";

interface Group {
    root: HTMLElement;
    status: HTMLElement;
    toggle: HTMLButtonElement;
    kinds: HTMLInputElement[];
    keyUrl: string;
    subscribeUrl: string;
    preferencesUrl: string;
    workerUrl: string;
}

function groupOf(root: HTMLElement): Group | null {
    const status = root.querySelector<HTMLElement>("[data-cp-push-status]");
    const toggle = root.querySelector<HTMLButtonElement>(
        "[data-cp-push-toggle]",
    );
    if (!status || !toggle) return null;
    return {
        root,
        status,
        toggle,
        kinds: Array.from(
            root.querySelectorAll<HTMLInputElement>("[data-cp-push-kind]"),
        ),
        keyUrl: root.getAttribute("data-cp-push-key") || "/api/push/key",
        subscribeUrl:
            root.getAttribute("data-cp-push-subscribe") ||
            "/api/push/subscribe",
        preferencesUrl:
            root.getAttribute("data-cp-push-preferences") ||
            "/api/push/preferences",
        workerUrl: root.getAttribute("data-cp-push-worker") || "/push-sw.js",
    };
}

const JSON_HEADERS = { "Content-Type": "application/json" };

function chosenKinds(group: Group): string[] {
    return group.kinds
        .filter((box) => box.checked)
        .map((box) => box.getAttribute("data-cp-push-kind") || "")
        .filter(Boolean);
}

function showKinds(
    group: Group,
    kinds: string[] | null,
    enabled: boolean,
): void {
    for (const box of group.kinds) {
        if (kinds) {
            box.checked = kinds.includes(
                box.getAttribute("data-cp-push-kind") || "",
            );
        }
        box.disabled = !enabled;
    }
}

function setStatus(group: Group, text: string): void {
    group.status.textContent = text;
}

function setToggle(group: Group, on: boolean): void {
    group.toggle.hidden = false;
    group.toggle.disabled = false;
    group.toggle.textContent = on
        ? "Turn off notifications"
        : "Turn on notifications";
    group.root.setAttribute("data-cp-push-state", on ? "on" : "off");
}

/** Wait until [registration] has an active worker; `subscribe` refuses before that. */
async function activated(
    registration: ServiceWorkerRegistration,
): Promise<ServiceWorkerRegistration> {
    if (registration.active) return registration;
    const worker = registration.installing || registration.waiting;
    if (!worker) return registration;
    await new Promise<void>((resolve) => {
        const check = () => {
            if (worker.state === "activated" || worker.state === "redundant") {
                worker.removeEventListener("statechange", check);
                resolve();
            }
        };
        worker.addEventListener("statechange", check);
        check();
    });
    return registration;
}

async function currentSubscription(
    browser: PushBrowser,
): Promise<PushSubscription | null> {
    const registration = await browser.getRegistration(SCOPE);
    // A registration for "/" may be some other worker whose scope merely contains this page; only
    // ours carries the push subscription this group manages.
    if (!registration?.pushManager) return null;
    return registration.pushManager.getSubscription();
}

async function readPreferences(
    group: Group,
    browser: PushBrowser,
): Promise<string[] | null> {
    const response = await browser.fetch(group.preferencesUrl, {
        credentials: "same-origin",
    });
    if (!response.ok) return null;
    const body = (await response.json()) as { kinds?: string[] };
    return Array.isArray(body.kinds) ? body.kinds : null;
}

async function turnOn(group: Group, browser: PushBrowser): Promise<void> {
    // First, and synchronously from the click: the browser only shows its prompt for a gesture.
    const permission = await browser.requestPermission();
    if (permission !== "granted") {
        setStatus(
            group,
            permission === "denied"
                ? "Notifications are blocked for this site. Allow them in your browser's site settings, then try again."
                : "Notifications were not turned on.",
        );
        return;
    }
    setStatus(group, "Turning notifications on…");
    const keyResponse = await browser.fetch(group.keyUrl, {
        credentials: "same-origin",
    });
    if (!keyResponse.ok) throw new Error(`key ${keyResponse.status}`);
    const { publicKey } = (await keyResponse.json()) as { publicKey: string };
    const registration = await activated(
        await browser.register(group.workerUrl, SCOPE),
    );
    const key = applicationServerKey(publicKey);
    let subscription = await registration.pushManager.getSubscription();
    if (subscription && !sameKey(subscription, key)) {
        // Subscribed under a key this server no longer signs with: it would never deliver.
        await subscription.unsubscribe();
        subscription = null;
    }
    subscription ??= await registration.pushManager.subscribe({
        userVisibleOnly: true,
        applicationServerKey: key as BufferSource,
    });
    const response = await browser.fetch(group.subscribeUrl, {
        method: "POST",
        credentials: "same-origin",
        headers: JSON_HEADERS,
        body: JSON.stringify({
            ...subscription.toJSON(),
            kinds: chosenKinds(group),
        }),
    });
    if (!response.ok) {
        await subscription.unsubscribe().catch(() => undefined);
        const reason = await errorOf(response);
        throw new Error(reason);
    }
    const body = (await response.json()) as { kinds?: string[] };
    showKinds(group, body.kinds ?? null, true);
    setToggle(group, true);
    setStatus(
        group,
        "Notifications are on for this browser. Choose what they are for:",
    );
}

function sameKey(subscription: PushSubscription, key: Uint8Array): boolean {
    const current = subscription.options?.applicationServerKey;
    if (!current) return true;
    const bytes = new Uint8Array(current);
    return bytes.length === key.length && bytes.every((b, i) => b === key[i]);
}

async function errorOf(response: Response): Promise<string> {
    try {
        const body = (await response.json()) as { error?: string };
        if (body.error) return body.error;
    } catch {
        // Not JSON: fall back to the status.
    }
    return `the server answered ${response.status}`;
}

async function turnOff(group: Group, browser: PushBrowser): Promise<void> {
    setStatus(group, "Turning notifications off…");
    const registration = await browser.getRegistration(SCOPE);
    const subscription = await registration?.pushManager?.getSubscription();
    if (subscription) {
        await browser.fetch(group.subscribeUrl, {
            method: "DELETE",
            credentials: "same-origin",
            headers: JSON_HEADERS,
            body: JSON.stringify({ endpoint: subscription.endpoint }),
        });
        await subscription.unsubscribe();
    }
    await registration?.unregister();
    setToggle(group, false);
    setStatus(group, "Notifications are off for this browser.");
}

async function savePreferences(
    group: Group,
    browser: PushBrowser,
): Promise<void> {
    const response = await browser.fetch(group.preferencesUrl, {
        method: "PUT",
        credentials: "same-origin",
        headers: JSON_HEADERS,
        body: JSON.stringify({ kinds: chosenKinds(group) }),
    });
    setStatus(
        group,
        response.ok
            ? "Saved. These apply to every browser you have turned notifications on in."
            : `Not saved: ${await errorOf(response)}`,
    );
}

/**
 * Post this browser's existing subscription again, binding it to whoever is signed in now.
 *
 * The browser keeps a subscription across a change of account, so finding one proves only that
 * *somebody* turned notifications on here. The server's answer is what says it is now this
 * person's: the post is idempotent for the same person, and for a different one it moves the
 * endpoint to them, so the previous person's notifications stop arriving. No `kinds` are sent, so
 * the person's own choice stands. Answers the kinds on success, or the reason it was not bound.
 */
async function rebind(
    group: Group,
    browser: PushBrowser,
    subscription: PushSubscription,
): Promise<{ kinds: string[] | null } | { refused: string; status: number }> {
    const response = await browser.fetch(group.subscribeUrl, {
        method: "POST",
        credentials: "same-origin",
        headers: JSON_HEADERS,
        body: JSON.stringify(subscription.toJSON()),
    });
    if (!response.ok) {
        return { refused: await errorOf(response), status: response.status };
    }
    const body = (await response.json()) as { kinds?: string[] };
    return { kinds: Array.isArray(body.kinds) ? body.kinds : null };
}

/** Reflect what this browser already has, confirming any subscription with the server first. */
async function refresh(group: Group, browser: PushBrowser): Promise<void> {
    const subscription =
        browser.permission() === "granted"
            ? await currentSubscription(browser)
            : null;
    if (subscription) {
        const bound = await rebind(group, browser, subscription);
        if ("kinds" in bound) {
            showKinds(group, bound.kinds, true);
            setToggle(group, true);
            setStatus(group, "Notifications are on for this browser.");
            return;
        }
        // The server would not bind it to this person (too many devices, a key it no longer
        // accepts). It must not keep delivering to whoever it belonged to before, so a refusal
        // ends it here too; a server error or an outage is only reported, and the next load
        // tries again.
        if (bound.status >= 400 && bound.status < 500) {
            await subscription.unsubscribe().catch(() => undefined);
        }
        showKinds(
            group,
            await readPreferences(group, browser).catch(() => null),
            true,
        );
        setToggle(group, false);
        setStatus(
            group,
            bound.status >= 400 && bound.status < 500
                ? `Notifications were turned off for this browser: ${bound.refused}`
                : "Could not confirm notifications for this browser with the server. Reload to try again.",
        );
        return;
    }
    // The kinds are the person's, shared by all their browsers, so they are shown either way: a
    // second device starts from what the first one chose.
    showKinds(
        group,
        await readPreferences(group, browser).catch(() => null),
        true,
    );
    setToggle(group, false);
    if (browser.permission() === "denied") {
        setStatus(
            group,
            "Notifications are blocked for this site. Allow them in your browser's site settings to turn them on.",
        );
    }
}

function wire(group: Group, browser: PushBrowser): void {
    let busy = false;
    group.toggle.addEventListener("click", () => {
        if (busy) return;
        busy = true;
        group.toggle.disabled = true;
        const on = group.root.getAttribute("data-cp-push-state") === "on";
        // Called straight from the click — `turnOn` asks for permission before awaiting anything.
        const work = on ? turnOff(group, browser) : turnOn(group, browser);
        void work
            .catch((error: unknown) => {
                setStatus(
                    group,
                    `Could not change notifications: ${error instanceof Error ? error.message : String(error)}`,
                );
            })
            .finally(() => {
                busy = false;
                group.toggle.disabled = false;
            });
    });
    for (const box of group.kinds) {
        box.addEventListener("change", () => {
            // While off, a choice is simply what the next "Turn on" subscribes with.
            if (group.root.getAttribute("data-cp-push-state") !== "on") return;
            void savePreferences(group, browser).catch(() =>
                setStatus(group, "Not saved: the server could not be reached."),
            );
        });
    }
}

/** How long a sign-out waits on the push service before signing out anyway. */
const SIGN_OUT_GRACE_MS = 3000;

/**
 * Turn this browser's notifications off before the Session group's Sign out submits.
 *
 * Signing out ends the session, not the subscription, and the next person to sign in here — or
 * nobody — would otherwise go on receiving the person's notifications. The server drops the
 * subscription on sign-out too, from the device cookie it set at subscribe; this is the half that
 * also removes it from the browser. Never the thing that stops a sign-out: whatever happens, or
 * after [SIGN_OUT_GRACE_MS], the form is submitted.
 */
function wireSignOut(
    form: HTMLFormElement,
    subscribeUrl: string,
    browser: PushBrowser,
): void {
    let leaving = false;
    form.addEventListener("submit", (event) => {
        if (leaving) return;
        event.preventDefault();
        leaving = true;
        const forget = (async () => {
            const registration = await browser.getRegistration(SCOPE);
            const subscription =
                await registration?.pushManager?.getSubscription();
            if (!subscription) return;
            await browser
                .fetch(subscribeUrl, {
                    method: "DELETE",
                    credentials: "same-origin",
                    headers: JSON_HEADERS,
                    body: JSON.stringify({ endpoint: subscription.endpoint }),
                })
                .catch(() => undefined);
            await subscription.unsubscribe();
            await registration?.unregister();
        })().catch(() => undefined);
        let timer: ReturnType<typeof setTimeout> | undefined;
        const grace = new Promise<void>((resolve) => {
            timer = setTimeout(resolve, SIGN_OUT_GRACE_MS);
        });
        void Promise.race([forget, grace]).then(() => {
            clearTimeout(timer);
            browser.submit(form);
        });
    });
}

/**
 * Enhance every Notifications group on the page. Answers a promise that settles once each group
 * shows this browser's current state, which is what a test waits on.
 */
export function installPushSettings(
    root: ParentNode = document,
    env: PushEnvironment = browserEnvironment(),
    browser: PushBrowser | null = null,
): Promise<void> {
    const groups = Array.from(
        root.querySelectorAll<HTMLElement>("[data-cp-push-settings]"),
    )
        .filter((el) => !el.hasAttribute("data-cp-push-wired"))
        .map(groupOf)
        .filter((g): g is Group => g !== null);
    const availability = pushAvailability(env);
    const work: Promise<void>[] = [];
    if (availability === "ready" && groups.length > 0) {
        const live = browser ?? liveBrowser();
        for (const form of Array.from(
            root.querySelectorAll<HTMLFormElement>(
                "form[data-cp-push-signout]",
            ),
        )) {
            if (form.hasAttribute("data-cp-push-wired")) continue;
            form.setAttribute("data-cp-push-wired", "");
            wireSignOut(form, groups[0].subscribeUrl, live);
        }
    }
    for (const group of groups) {
        group.root.setAttribute("data-cp-push-wired", "");
        group.root.setAttribute("data-cp-push-availability", availability);
        if (availability !== "ready") {
            setStatus(group, availabilityMessage(availability));
            group.toggle.hidden = true;
            showKinds(group, null, false);
            continue;
        }
        const live = browser ?? liveBrowser();
        wire(group, live);
        work.push(
            refresh(group, live).catch(() => {
                setToggle(group, false);
            }),
        );
    }
    return Promise.all(work).then(() => undefined);
}
