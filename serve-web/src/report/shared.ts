// A screenshot shared into the installed app from the OS share sheet.
//
// The manifest's `share_target` POSTs to `/report-bug/share`, and the server cannot reach this
// tab's `sessionStorage` — which is where the capture pile lives — so it parks the image under an
// unguessable id and redirects here with `?shared=<id>`. This module is the other half: fetch the
// parked bytes, normalise them to the same PNG data URL a capture taken on the page would be, and
// hand them to the pile. From there everything that already happens to a capture (hosting, copy,
// the hand-off into the issue) happens to this one too.

/** The query parameter the server's share redirect names the parked share with. */
export const SHARED_PARAM = "shared";

/** Where the parked image is served from. */
export const SHARED_PATH = "/report-bug/shared/";

const ID = /^[0-9a-f]{32}$/;

/** The parked share's id from a page query, or null when it is absent or malformed. */
export function sharedId(search: string): string | null {
    const id = new URLSearchParams(search).get(SHARED_PARAM);
    return id && ID.test(id) ? id : null;
}

/**
 * The URL to fetch a parked share from, keeping the page's `?token=` so a token-gated box answers
 * it — the share redirect carried the token onto this page for exactly that reason.
 */
export function sharedImageUrl(id: string, search: string): string {
    const token = new URLSearchParams(search).get("token");
    return `${SHARED_PATH}${id}${token ? `?token=${encodeURIComponent(token)}` : ""}`;
}

/**
 * The page's own URL without `?shared=`, so a reload does not import the same picture twice. The
 * rest of the query (the token, `from`) is left exactly as it was.
 */
export function withoutShared(href: string): string {
    const url = new URL(href);
    url.searchParams.delete(SHARED_PARAM);
    return url.pathname + url.search + url.hash;
}
