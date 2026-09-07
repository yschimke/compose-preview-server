// Hosting browser captures long enough for a GitHub issue to retain them.
//
// The image lane was originally a headless API. The report page uses the same POST with its signed
// OAuth cookie, then inserts the returned anonymous-read URL into the prefilled body. The URL is
// accepted only when it points back at this origin's `/i/` lane: server text never becomes arbitrary
// markdown or an off-origin tracking pixel in the reporter's issue.

import { blobFromDataUrl } from "./capture.js";
import type { Capture } from "./store.js";

export interface UploadedCapture {
    url: string;
}

/** Insert hosted captures immediately below the report's Screenshot heading. */
export function withUploadedCaptures(
    body: string,
    captures: Capture[],
): string {
    const lines = captures
        .map((capture) => ({
            capture,
            url: hostedCaptureUrl(capture.uploadedUrl),
        }))
        .filter((item): item is { capture: Capture; url: string } => !!item.url)
        .map(({ capture, url }) => `![${markdownAlt(capture.label)}](${url})`);
    if (!lines.length) return body;
    const evidence = lines.join("\n\n");
    const heading = "### Screenshot";
    const at = body.indexOf(heading);
    if (at < 0) return `${body.trimEnd()}\n\n${heading}\n\n${evidence}\n`;
    const end = at + heading.length;
    return `${body.slice(0, end)}\n\n${evidence}${body.slice(end)}`;
}

/**
 * The placeholder the server writes into the Screenshot section of a bug report.
 *
 * Matched so the clipboard hint can take its place rather than sit beside it, which would leave two
 * comments a line apart telling the reporter to paste in the same spot.
 */
const PASTE_PLACEHOLDER = "<!-- Paste your capture of the page here. -->";

/**
 * Say, in the Screenshot section itself, that the capture is already on the clipboard.
 *
 * A report that cannot be hosted (this server has no image lane, or this visitor is not admitted to
 * it — see `handleImageUploadCapability`) reaches GitHub with an empty Screenshot section, and the
 * only thing that ever said otherwise was a status line on `/report-bug` — a page the reporter left
 * in the same gesture, because the issue form opens in a new tab. So the issue opened with no
 * picture, no image on screen suggesting there should be one, and a clipboard the reporter had no
 * reason to think was holding anything: issue #556.
 *
 * A markdown COMMENT, for two reasons. It is invisible in the filed issue, so a reporter who
 * ignores it leaves no boilerplate behind; and it is plainly visible in GitHub's editor, at the
 * exact insertion point, which is the one place the reporter is certainly looking. Pasting over it
 * is the gesture it asks for.
 */
export function withClipboardHint(body: string, others: number): string {
    // Worded to hold whichever way the clipboard write goes. It is started as this hint is
    // written and can still be refused (Safari's private mode, a denied permission), and by the
    // time that is known the reporter is in this editor and the body cannot be taken back — so the
    // sentence has to be true for a paste that comes up empty too.
    const rest = others > 0 ? ` The other ${others} are there as well.` : "";
    const hint =
        "<!-- Paste your capture here (Ctrl+V, or Cmd+V on a Mac): opening this issue put your " +
        "newest one on the clipboard. If the paste comes up empty, the report page still has it " +
        `— press Copy on it there, then paste again.${rest} -->`;
    if (body.includes(PASTE_PLACEHOLDER))
        return body.replace(PASTE_PLACEHOLDER, () => hint);
    const heading = "### Screenshot";
    const at = body.indexOf(heading);
    if (at < 0) return `${body.trimEnd()}\n\n${heading}\n\n${hint}\n`;
    const end = at + heading.length;
    return `${body.slice(0, end)}\n\n${hint}${body.slice(end)}`;
}

/** Upload one capture and return the checked anonymous-read URL. */
export async function uploadCapture(
    capture: Capture,
): Promise<UploadedCapture> {
    const existing = hostedCaptureUrl(capture.uploadedUrl);
    if (existing) return { url: existing };
    const endpoint = new URL("/images", location.href);
    endpoint.searchParams.set("name", `bug-report-${capture.id}.png`);
    // Private preview hosts carry their browse secret in the query, not a cookie. It stays on this
    // origin and is never part of the returned public image URL.
    const token = new URLSearchParams(location.search).get("token");
    if (token) endpoint.searchParams.set("token", token);
    const response = await fetch(endpoint, {
        method: "POST",
        credentials: "same-origin",
        headers: { Accept: "application/json", "Content-Type": "image/png" },
        body: await blobFromDataUrl(capture.dataUrl),
    });
    if (!response.ok)
        throw new Error(`image upload answered ${response.status}`);
    const payload = (await response.json()) as { url?: unknown };
    const url = hostedCaptureUrl(payload.url);
    if (!url) throw new Error("image upload returned an unsafe URL");
    return { url };
}

/**
 * Does a previously-returned image URL still resolve?
 *
 * `uploadedUrl` rides in `sessionStorage`, which outlives the server: the pile survives a restart
 * of the image store and the lane's own retention TTL, and `hostedCaptureUrl` only says the string
 * is shaped like one of our `/i/` URLs — it cannot say the bytes are still there. Embedding an
 * unchecked one puts a 404 in the filed issue, which is worse than no screenshot, because the
 * clipboard fallback is skipped on the strength of the URL being present.
 *
 * A HEAD, so the check costs headers rather than the picture. Anything but a 2xx — gone, expired,
 * or a network error — is "cannot vouch for it", and the caller re-uploads. A needless re-upload
 * costs one request; a wrongly-trusted URL costs the report its evidence.
 */
export async function stillHosted(url: string): Promise<boolean> {
    try {
        const response = await fetch(url, {
            method: "HEAD",
            credentials: "same-origin",
            cache: "no-store",
        });
        return response.ok;
    } catch {
        return false;
    }
}

/** Return a canonical URL only for an image hosted by this page's image lane. */
export function hostedCaptureUrl(value: unknown): string | null {
    if (typeof value !== "string") return null;
    try {
        const url = new URL(value, location.href);
        if (url.origin !== location.origin) return null;
        if (!/^\/i\/[A-Za-z0-9_-]+\.[A-Za-z0-9]+$/.test(url.pathname))
            return null;
        return url.href;
    } catch {
        return null;
    }
}

function markdownAlt(value: string): string {
    return value.replace(/[\\[\]\r\n]/g, " ").trim() || "bug report capture";
}
