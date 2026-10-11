// "On phone": a QR code of this preview's page, on the export line.
//
// A render looks right in a desktop window and wrong on the device it was made for — real pixel
// density, real font scale, a thumb's reach. The quickest way to put the page there is to point the
// phone's camera at the screen, so the export bar grows one button that opens a small popover with
// the page's URL as a QR code. Offered only to a fine pointer: on a phone the page is already where
// it needs to be, and Share link (`webShare.ts`) is the gesture there.
//
// Only the button lives in the viewer bundle. The popover and the encoder (`qr/phonePopover.ts`,
// `qr/encode.ts`) are their own bundle, `qr-code.js`, fetched on the first click: the viewer is at
// its gzip budget (`scripts/check-bundle-budgets.mjs`) and most visits never open this. The page
// names the hashed URL on the export bar's `data-cp-qr-src`, the way the report launcher finds its
// capture bundle.

/** What `qr-code.js` publishes on `window.cpQr`. */
export interface QrApi {
    /** Build the popover beside [button], take over its clicks, and open it. */
    mount(button: HTMLButtonElement): void;
}

/** Inject `qr-code.js` from [src] and resolve with the API it publishes. */
function loadScript(src: string): Promise<QrApi> {
    return new Promise((resolve, reject) => {
        // Resolved against the page and refused off-origin, rather than trusting DOM text.
        const url = new URL(src, location.href);
        if (url.origin !== location.origin) return reject();
        const script = document.createElement("script");
        script.src = url.href;
        script.onload = () => {
            const api = (window as { cpQr?: QrApi }).cpQr;
            api ? resolve(api) : reject();
        };
        script.onerror = reject;
        document.head.appendChild(script);
    });
}

/**
 * Add the On phone button to the end of the export bar. [load] fetches the popover; by default it is
 * the bundle the bar's `data-cp-qr-src` names, and tests hand in the module directly.
 */
export function installPhoneQr(load?: () => Promise<QrApi>): void {
    const bar = document.querySelector<HTMLElement>(".cp-export");
    const src = bar?.getAttribute("data-cp-qr-src");
    if (
        !bar ||
        bar.querySelector(".cp-qr") ||
        (typeof matchMedia === "function" &&
            matchMedia("(pointer: coarse)").matches)
    )
        return;
    const fetchApi = load ?? (src ? () => loadScript(src) : null);
    if (!fetchApi) return;
    const wrap = document.createElement("span");
    wrap.className = "cp-qr";
    const button = document.createElement("button");
    button.type = "button";
    button.className = "cp-qr-btn";
    button.textContent = "On phone";
    button.title = "Show a QR code to open this preview on a phone";
    button.setAttribute("aria-haspopup", "dialog");
    button.setAttribute("aria-expanded", "false");
    wrap.appendChild(button);
    bar.appendChild(wrap);
    // The first click fetches the popover and hands the button to it. A failed fetch (a flaky
    // network, a deploy mid-visit) puts this listener back, so the next click tries again.
    const first = () => {
        button.removeEventListener("click", first);
        fetchApi().then(
            (api) => api.mount(button),
            () => button.addEventListener("click", first),
        );
    };
    button.addEventListener("click", first);
}
