// "Share" beside the viewer's Copy actions, on a phone.
//
// On a touchscreen the OS share sheet is how a link or a picture leaves the browser — Copy and then
// switching apps is the desktop gesture. So where `navigator.share` exists AND the primary pointer
// is a finger, the export bar's PNG group grows two buttons: Share link (this preview's page, as
// the visitor sees it) and, where the browser can share files, Share PNG (the same bytes Copy PNG
// would put on the clipboard). A desktop browser that also implements `navigator.share` keeps the
// bar exactly as it was: the Copy actions are the right tools there.
//
// The link shared is the PAGE, not the `/render` URL: a person receiving it wants the viewer. Its
// `?token=` is removed first — on a token-gated box that is the operator's own credential, and a
// share sheet sends it to whoever the visitor picks.

/** The page URL to share: the current one, minus any credential in its query. */
export function shareableUrl(href: string): string {
    const url = new URL(href);
    url.searchParams.delete("token");
    return url.toString();
}

/** A PNG file name for a preview, from the last path segment of its viewer URL. */
export function pngFileName(href: string): string {
    const last = new URL(href).pathname.split("/").filter(Boolean).pop();
    const base = (last ? decodeURIComponent(last) : "preview").replace(
        /[^A-Za-z0-9._-]+/g,
        "_",
    );
    return `${base || "preview"}.png`;
}

/** Whether this browser, on this device, should be offered Share at all. */
export function shareOffered(nav: Navigator, coarse: boolean): boolean {
    return coarse && typeof nav.share === "function";
}

/** Whether this browser can put a PNG file on the share sheet. */
export function canSharePng(nav: Navigator): boolean {
    if (typeof nav.canShare !== "function") return false;
    try {
        return nav.canShare({
            files: [
                new File([new Uint8Array(0)], "x.png", { type: "image/png" }),
            ],
        });
    } catch {
        return false;
    }
}

/**
 * Add the Share buttons to the export bar's PNG group. [pngSource] answers the URL Copy PNG would
 * fetch right now (stage applied or not), so the shared picture is the one on screen.
 */
export function installWebShare(pngSource: () => string | null): void {
    const coarse =
        typeof matchMedia === "function" &&
        matchMedia("(pointer: coarse)").matches;
    if (!shareOffered(navigator, coarse)) return;
    const group = document
        .querySelector<HTMLElement>('.cp-copyimg[data-copyimg-ext=".png"]')
        ?.closest<HTMLElement>(".cp-link-group");
    if (!group || group.querySelector(".cp-share")) return;
    const title = document.title;
    const flash = (btn: HTMLButtonElement, label: string) => {
        const was =
            btn.getAttribute("data-share-label") || btn.textContent || "";
        btn.setAttribute("data-share-label", was);
        btn.textContent = label;
        setTimeout(() => (btn.textContent = was), 1400);
    };
    // A dismissed sheet rejects with AbortError: the visitor changed their mind, nothing failed.
    const settled = (btn: HTMLButtonElement) => (error: unknown) => {
        if ((error as DOMException)?.name !== "AbortError")
            flash(btn, "Share failed");
    };
    const button = (label: string, tip: string) => {
        const btn = document.createElement("button");
        btn.type = "button";
        btn.className = "cp-share";
        btn.textContent = label;
        btn.title = tip;
        group.insertBefore(btn, group.querySelector(".cp-dl"));
        return btn;
    };
    const link = button("Share link", "Share this preview's page");
    link.addEventListener("click", () => {
        navigator
            .share({ title, url: shareableUrl(location.href) })
            .catch(settled(link));
    });
    if (!canSharePng(navigator)) return;
    const png = button("Share PNG", "Share the PNG of the current view");
    png.addEventListener("click", () => {
        const src = pngSource();
        if (!src) return;
        png.textContent = "Preparing…";
        fetch(src)
            .then((r) => {
                if (!r.ok) throw new Error(`render ${r.status}`);
                return r.blob();
            })
            .then((blob) => {
                png.textContent = "Share PNG";
                const file = new File([blob], pngFileName(location.href), {
                    type: "image/png",
                });
                return navigator.share({ files: [file], title });
            })
            .catch((error) => {
                png.textContent = "Share PNG";
                settled(png)(error);
            });
    });
}
