// What this browser can do about Web Push, decided once and stated plainly.
//
// Push needs four things the page cannot conjure: a secure context (HTTPS, or localhost), a service
// worker, a `PushManager`, and the `Notification` API. iOS and iPadOS are the case worth naming:
// Safari there exposes `PushManager` only to a site that was added to the Home Screen and opened
// from it, so a visitor in an ordinary tab would otherwise read "not supported" on a phone that
// supports it perfectly well. Pure, so every branch is a unit test rather than a device lab.

export type PushAvailability =
    "ready" | "insecure" | "install-first" | "unsupported";

export interface PushEnvironment {
    isSecureContext: boolean;
    hasServiceWorker: boolean;
    hasPushManager: boolean;
    hasNotification: boolean;
    userAgent: string;
    maxTouchPoints: number;
    /** Opened as an installed app: `display-mode: standalone`, or iOS's `navigator.standalone`. */
    standalone: boolean;
}

/**
 * iPhone, iPod or iPad — including the iPad that, since iPadOS 13, reports itself as a Mac and
 * gives itself away only by having a touchscreen.
 */
export function isIos(userAgent: string, maxTouchPoints: number): boolean {
    if (/\b(iPhone|iPad|iPod)\b/.test(userAgent)) return true;
    return /\bMacintosh\b/.test(userAgent) && maxTouchPoints > 1;
}

export function pushAvailability(env: PushEnvironment): PushAvailability {
    if (!env.isSecureContext) return "insecure";
    if (env.hasServiceWorker && env.hasPushManager && env.hasNotification) {
        return "ready";
    }
    if (isIos(env.userAgent, env.maxTouchPoints) && !env.standalone) {
        return "install-first";
    }
    return "unsupported";
}

/** The sentence the settings group shows when push cannot be offered here. */
export function availabilityMessage(availability: PushAvailability): string {
    switch (availability) {
        case "insecure":
            return "Notifications need a secure connection. Open this site over HTTPS (browsers only allow push on HTTPS or localhost).";
        case "install-first":
            return "Add to Home Screen first: tap Share, then Add to Home Screen, open the app from there, and turn notifications on in Settings.";
        case "unsupported":
            return "This browser does not support push notifications.";
        case "ready":
            return "";
    }
}

/**
 * The live browser's answers. Every probe is guarded: a sandboxed frame throws on merely reading
 * `navigator.serviceWorker`, and a settings script must never be the thing that breaks a page.
 */
export function browserEnvironment(): PushEnvironment {
    const probe = (read: () => boolean): boolean => {
        try {
            return read();
        } catch {
            return false;
        }
    };
    return {
        isSecureContext: probe(() => window.isSecureContext === true),
        hasServiceWorker: probe(() => "serviceWorker" in navigator),
        hasPushManager: probe(() => "PushManager" in window),
        hasNotification: probe(() => "Notification" in window),
        userAgent: navigator.userAgent || "",
        maxTouchPoints: navigator.maxTouchPoints || 0,
        standalone: probe(
            () =>
                window.matchMedia?.("(display-mode: standalone)").matches ===
                    true ||
                (navigator as { standalone?: boolean }).standalone === true,
        ),
    };
}

/** A VAPID public key, base64url as the server publishes it, as `applicationServerKey` wants it. */
export function applicationServerKey(base64Url: string): Uint8Array {
    const padded = base64Url
        .replace(/-/g, "+")
        .replace(/_/g, "/")
        .padEnd(Math.ceil(base64Url.length / 4) * 4, "=");
    const binary = atob(padded);
    const bytes = new Uint8Array(binary.length);
    for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
    return bytes;
}
