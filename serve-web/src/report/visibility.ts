// Whether a capture comes from a page that an anonymous visitor could open.
//
// A hosted capture gets an anonymous-read `/i/` URL, and the issue it is embedded in lives on a
// public tracker. On a catalog page of a public host that is fine: anyone can already open the page
// the picture shows. A UI-builder design, the admin screens, and every page of a token-gated host
// are different — they are only visible to someone signed in or holding the token — so a capture
// taken there is uploaded only when the reporter explicitly asks for it (see `ui.ts`).
//
// Two signals, either one enough:
//  - the path the capture was taken on, for the routes that are never anonymous; and
//  - `data-cp-capture-scope="private"` on the capture mount, which the server writes on
//    `/report-bug` and the image-lane probe sets on every other page when the host itself is
//    gated. The server knows its own posture; the path alone cannot.

/** Route prefixes whose pages always need a signed-in session or an operator credential. */
const PRIVATE_PREFIXES = ["/ui-builder", "/admin", "/api/ui-builder"];

/** The attribute and value that mark a page's captures as needing consent to upload. */
export const SCOPE_ATTR = "data-cp-capture-scope";
export const PRIVATE_SCOPE = "private";

/** True when [path] is one of the routes an anonymous visitor can never open. */
export function isPrivatePath(path: string | null | undefined): boolean {
    if (!path) return false;
    return PRIVATE_PREFIXES.some(
        (prefix) => path === prefix || path.startsWith(`${prefix}/`),
    );
}

/** True when the server (or the image-lane probe) marked this page's captures private. */
export function pageMarkedPrivate(): boolean {
    return !!document.querySelector(`[${SCOPE_ATTR}="${PRIVATE_SCOPE}"]`);
}

/** True when a capture of [page] should only be uploaded after the reporter opts in. */
export function needsUploadConsent(page: string | null | undefined): boolean {
    return pageMarkedPrivate() || isPrivatePath(page);
}
