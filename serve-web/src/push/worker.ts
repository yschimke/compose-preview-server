// The push service worker's logic, kept free of `self` so it is a unit test rather than a phone.
//
// It does exactly two things, and deliberately nothing else:
//
// * `push` — decrypting is the browser's job; this reads the small JSON the server sent
//   (`{kind, designId, threadId?, title, url, count}`), shows one notification per design thread
//   (the `tag` replaces an earlier one for the same thread), and sets the installed app's badge.
// * `notificationclick` — focus a window of this site and take it to the thread, or open one.
//
// There is NO `fetch` handler. This worker is registered at the root scope, so a fetch handler
// would sit in front of every page, every API call and every socket on the site; without one it
// cannot change how anything loads, and the UI builder's own `/ui-builder/` worker keeps
// controlling the editor because its scope is more specific.

/** The site icons the notification wears; `ServeSiteIcon`'s paths, pinned by a server test. */
export const ICON_PATH = "/icons/app-192.png";
export const BADGE_PATH = "/icons/app-192.png";

export interface PushPayload {
    kind: string;
    designId: string;
    threadId?: string;
    title: string;
    url: string;
    count: number;
}

/** The payload, or null when it is not one this worker understands. */
export function parsePayload(
    text: string | null | undefined,
): PushPayload | null {
    if (!text) return null;
    let value: unknown;
    try {
        value = JSON.parse(text);
    } catch {
        return null;
    }
    if (typeof value !== "object" || value === null) return null;
    const v = value as Record<string, unknown>;
    if (
        typeof v.kind !== "string" ||
        typeof v.designId !== "string" ||
        typeof v.title !== "string" ||
        typeof v.url !== "string"
    ) {
        return null;
    }
    return {
        kind: v.kind,
        designId: v.designId,
        threadId: typeof v.threadId === "string" ? v.threadId : undefined,
        title: v.title.slice(0, 120),
        url: v.url,
        count:
            typeof v.count === "number" && v.count >= 1
                ? Math.floor(v.count)
                : 1,
    };
}

/**
 * [url] when it is on this worker's own origin, else the site's front door. A notification may only
 * ever open this site: the payload is the server's, but a worker that would navigate wherever a
 * message told it to is one compromised push away from a phishing link on the lock screen.
 */
export function sameOriginUrl(url: string, origin: string): string {
    try {
        const resolved = new URL(url, origin);
        if (resolved.origin === origin) return resolved.href;
    } catch {
        // Unparseable: fall through to the front door.
    }
    return new URL("/", origin).href;
}

const BODIES: Record<string, string> = {
    replies: "Tap to open the thread.",
    mentions: "Tap to open the thread.",
    reviews: "Tap to open the design.",
};

export interface NotificationSpec {
    title: string;
    options: NotificationOptions & { renotify?: boolean };
}

export function notificationFor(
    payload: PushPayload,
    origin: string,
): NotificationSpec {
    const more = payload.count > 1 ? ` (${payload.count} updates)` : "";
    return {
        title: payload.title,
        options: {
            body: (BODIES[payload.kind] ?? "Tap to open.") + more,
            tag: `cp-push:${payload.designId}:${payload.threadId ?? ""}`,
            renotify: true,
            icon: ICON_PATH,
            badge: BADGE_PATH,
            data: {
                url: sameOriginUrl(payload.url, origin),
                count: payload.count,
            },
        },
    };
}

/** The parts of `ServiceWorkerGlobalScope` this uses, so a test can supply them. */
export interface WorkerScope {
    location: { origin: string };
    registration: {
        showNotification(
            title: string,
            options?: NotificationOptions,
        ): Promise<void>;
        getNotifications(filter?: { tag?: string }): Promise<Notification[]>;
    };
    clients: {
        matchAll(options: {
            type: "window";
            includeUncontrolled: boolean;
        }): Promise<ReadonlyArray<WindowClientLike>>;
        openWindow(url: string): Promise<unknown>;
    };
    navigator: {
        setAppBadge?: (count?: number) => Promise<void>;
        clearAppBadge?: () => Promise<void>;
    };
}

export interface WindowClientLike {
    url: string;
    focus(): Promise<unknown>;
    navigate?(url: string): Promise<unknown>;
}

/**
 * The installed app's badge: how many updates the notifications still showing stand for. Read
 * back from the notifications themselves rather than counted here, because a worker's memory does
 * not survive between events and the notification list is the one thing that does.
 */
export async function updateBadge(scope: WorkerScope): Promise<void> {
    const setBadge = scope.navigator.setAppBadge;
    if (!setBadge) return;
    const showing = await scope.registration.getNotifications();
    const count = showing.reduce((sum, n) => {
        const data = n.data as { count?: unknown } | null;
        return sum + (typeof data?.count === "number" ? data.count : 1);
    }, 0);
    if (count > 0) await setBadge.call(scope.navigator, count);
    else await scope.navigator.clearAppBadge?.call(scope.navigator);
}

export async function handlePush(
    scope: WorkerScope,
    text: string | null,
): Promise<void> {
    const payload = parsePayload(text);
    // A push with nothing readable still has to show something — browsers penalise a site whose
    // pushes are invisible — so it is a plain pointer at the site rather than silence.
    const spec = payload
        ? notificationFor(payload, scope.location.origin)
        : {
              title: "Compose Preview",
              options: {
                  body: "Something changed. Tap to open.",
                  icon: ICON_PATH,
                  badge: BADGE_PATH,
                  data: {
                      url: new URL("/", scope.location.origin).href,
                      count: 1,
                  },
              },
          };
    await scope.registration.showNotification(spec.title, spec.options);
    await updateBadge(scope).catch(() => undefined);
}

export async function handleNotificationClick(
    scope: WorkerScope,
    notification: { data: unknown; close(): void },
): Promise<void> {
    notification.close();
    const data = notification.data as { url?: unknown } | null;
    const url = sameOriginUrl(
        typeof data?.url === "string" ? data.url : "/",
        scope.location.origin,
    );
    const windows = await scope.clients.matchAll({
        type: "window",
        includeUncontrolled: true,
    });
    const own = windows.filter((client) => {
        try {
            return new URL(client.url).origin === scope.location.origin;
        } catch {
            return false;
        }
    });
    // The manifest says `launch_handler: focus-existing`; a click honours the same intent. Prefer a
    // window already on this exact page, then any window of the site, taken to the thread.
    const exact = own.find((client) => client.url === url);
    const target = exact ?? own[0];
    if (target) {
        await target.focus();
        if (!exact && target.navigate) await target.navigate(url);
    } else {
        await scope.clients.openWindow(url);
    }
    await updateBadge(scope).catch(() => undefined);
}
