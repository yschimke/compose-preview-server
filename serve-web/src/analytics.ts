// This standalone bundle also runs on GitHub Pages and the hosted Wasm editor shell.
// It deliberately records semantic events only: no input contents, DOM text or replay.
import "./analyticsClient.js";

type Payload = Record<string, unknown>;
type AnalyticsWindow = Window & {
    umami?: { track(name: string, data: Payload): Promise<unknown> };
    previewAnalyticsBeforeSend?: (
        type: string,
        payload: Payload,
    ) => Payload | false;
};

export function surface(path: string): string {
    if (path.startsWith("/ui-builder")) return "builder";
    if (path.startsWith("/compose-ai-tools")) return "docs";
    if (path.startsWith("/wasm/")) return "wasm";
    return "preview";
}

export function safePath(value: unknown, origin: string): string {
    try {
        const path = new URL(String(value), origin).pathname;
        // Neither private design identifiers nor authorization paths belong in analytics.
        if (path.startsWith("/ui-builder/")) return "/ui-builder/";
        if (/^\/(auth|admin|api|agent-access|__)/.test(path)) return "/";
        return path;
    } catch {
        return "/";
    }
}

export function safeEvent(name: string, raw: unknown): Payload | null {
    if (
        ![
            "renderer_changed",
            "navigation",
            "component_added",
            "property_changed",
            "undo",
            "redo",
            "code_exported",
        ].includes(name)
    )
        return null;
    const data: Payload = {};
    const props = raw && typeof raw === "object" ? (raw as Payload) : {};
    if (
        typeof props.mode === "string" &&
        [
            "png",
            "live",
            "wasm",
            "rc-wasm",
            "spec",
            "source",
            "motion",
            "rc",
        ].includes(props.mode)
    )
        data.mode = props.mode;
    if (
        typeof props.destination === "string" &&
        ["docs", "preview", "builder", "wasm", "external"].includes(
            props.destination,
        )
    )
        data.destination = props.destination;
    return data;
}

export function installAnalytics(script: HTMLScriptElement): void {
    const host = window as AnalyticsWindow;
    const endpoint = script.dataset.umamiUrl;
    const website = script.dataset.websiteId;
    if (
        !endpoint ||
        !website ||
        host.previewTelemetry ||
        window.top !== window ||
        navigator.doNotTrack === "1" ||
        (navigator as Navigator & { globalPrivacyControl?: boolean })
            .globalPrivacyControl
    )
        return;
    const domains = script.dataset.domains?.split(",");
    if (domains && !domains.includes(location.hostname)) return;
    const pageSurface = surface(location.pathname);
    host.previewAnalyticsBeforeSend = (type, payload) => {
        if (type !== "event") return false;
        const data = payload.name
            ? safeEvent(String(payload.name), payload.data)
            : {};
        if (!data) return false;
        // Construct an allowlist, rather than forwarding future tracker fields implicitly.
        return {
            website,
            hostname: location.hostname,
            language: navigator.language,
            url: safePath(payload.url, location.origin),
            title: pageSurface,
            referrer: "",
            ...(payload.name ? { name: payload.name } : {}),
            data: { ...data, surface: pageSurface },
        };
    };
    host.previewTelemetry = {
        track(name, json) {
            try {
                const data = safeEvent(name, JSON.parse(json));
                if (data) void host.umami?.track(name, data)?.catch(() => {});
            } catch {
                /* Malformed bridge payloads must not affect the editor. */
            }
        },
    };
    document.addEventListener("click", (event) => {
        const link =
            event.target instanceof Element
                ? event.target.closest("a[href]")
                : null;
        if (!(link instanceof HTMLAnchorElement)) return;
        try {
            const url = new URL(link.href);
            if (!["http:", "https:"].includes(url.protocol)) return;
            host.previewTelemetry?.track(
                "navigation",
                JSON.stringify({
                    destination:
                        url.origin === location.origin
                            ? surface(url.pathname)
                            : "external",
                }),
            );
        } catch {
            /* Ignore malformed links. */
        }
    });
    const tracker = document.createElement("script");
    tracker.src = endpoint + "/script.js";
    tracker.async = true;
    tracker.dataset.websiteId = website;
    tracker.dataset.hostUrl = endpoint;
    tracker.dataset.excludeSearch = "true";
    tracker.dataset.excludeHash = "true";
    tracker.dataset.doNotTrack = "true";
    tracker.dataset.beforeSend = "previewAnalyticsBeforeSend";
    document.head.appendChild(tracker);
}

if (document.currentScript instanceof HTMLScriptElement)
    installAnalytics(document.currentScript);
