/**
 * The request a viewer snapshot is fetched with.
 *
 * A render is a GET whose query carries every override, which is right for almost every preview
 * and wrong for one kind: a string knob holding a document. An A2UI document is kilobytes of JSON
 * Lines, and percent-encoded into a URL it outgrows what the server (and any proxy in front of it)
 * will accept, so the render fails before it reaches the renderer. Past [MAX_GET_URL] the same
 * render goes out as `POST` with the override parameters (`knob.*`, `rc.*`) in a form body, which
 * `POST /render/{name}` merges over the query and hands to the GET's own handler.
 *
 * Only the overrides move. Everything that addresses the render rather than shaping its pixels —
 * `token=`, `session=`, `at=`, `gen=`, the display axes — stays in the query, where the server
 * reads it.
 */
export const MAX_GET_URL = 2000;

export interface RenderRequest {
    url: string;
    init: RequestInit;
}

function isBodyParam(part: string): boolean {
    return part.startsWith("knob.") || part.startsWith("rc.");
}

export function renderRequest(path: string, qs: string): RenderRequest {
    const url = path + (qs ? "?" + qs : "");
    if (url.length <= MAX_GET_URL) {
        return { url, init: { credentials: "same-origin" } };
    }
    const parts = qs ? qs.split("&") : [];
    const query = parts.filter((p) => !isBodyParam(p)).join("&");
    const body = parts.filter(isBodyParam).join("&");
    return {
        url: path + (query ? "?" + query : ""),
        init: {
            method: "POST",
            credentials: "same-origin",
            headers: { "Content-Type": "application/x-www-form-urlencoded" },
            body,
        },
    };
}
