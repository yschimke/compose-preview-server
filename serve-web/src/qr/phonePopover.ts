// The "On phone" popover: the half of `viewer/phoneQr.ts` that ships in `qr-code.js`.
//
// The code is the PAGE, as the visitor sees it (overrides and all), drawn afresh whenever the URL
// has changed since it was last opened, so a changed override is never a stale code. Its `?token=`
// is removed, as Share does: a code on a screen is readable by every camera in the room, and that
// query is the operator's credential.
//
// A popover rather than a modal: it closes on Esc (focus returns to the button), on a click
// anywhere outside it, on tabbing out of it, and on the button again.

import { encodeQr, qrSvg } from "./encode.js";
import { shareableUrl } from "../viewer/webShare.js";

/**
 * Whether [hostname] names this computer itself, which a phone scanning the code cannot reach:
 * `localhost`, the loopback ranges, and the unspecified address a dev server may be bound to.
 */
export function isLoopbackHost(hostname: string): boolean {
    const host = hostname.replace(/^\[|\]$/g, "").toLowerCase();
    return (
        host === "localhost" ||
        host.endsWith(".localhost") ||
        /^127\./.test(host) ||
        host === "::1" ||
        host === "0.0.0.0"
    );
}

/** The popover's QR for [url], or the reason there is none. */
export function phoneQrMarkup(url: string): string | null {
    const code = encodeQr(url);
    return code ? qrSvg(code, "QR code of this preview's page") : null;
}

/** Build the popover beside [button] (inside its parent), take over its clicks, and open it. */
export function mountPhoneQr(button: HTMLButtonElement): void {
    const wrap = button.parentElement!;
    if (wrap.querySelector(".cp-qr-pop")) return;
    const pop = document.createElement("div");
    pop.id = "cp-qr-pop";
    pop.className = "cp-qr-pop";
    pop.hidden = true;
    pop.tabIndex = -1;
    pop.setAttribute("role", "dialog");
    pop.setAttribute("aria-label", "Open this preview on your phone");
    const art = document.createElement("div");
    art.className = "cp-qr-art";
    const caption = document.createElement("p");
    caption.className = "cp-qr-caption";
    caption.textContent = "Scan with your phone's camera to open this view.";
    const link = document.createElement("p");
    link.className = "cp-qr-url";
    const warn = document.createElement("p");
    warn.className = "cp-qr-warn";
    warn.textContent =
        "This address is this computer's own — a phone needs the server's network address.";
    pop.append(art, caption, link, warn);
    wrap.appendChild(pop);
    button.setAttribute("aria-controls", pop.id);

    let drawn = "";
    const draw = () => {
        const url = shareableUrl(location.href);
        warn.hidden = !isLoopbackHost(location.hostname);
        if (url === drawn) return;
        drawn = url;
        link.textContent = url;
        const svg = phoneQrMarkup(url);
        if (svg) art.innerHTML = svg;
        else
            art.textContent =
                "This URL is too long for a QR code — use Copy link instead.";
    };

    const isOpen = () => !pop.hidden;
    const open = () => {
        draw();
        pop.hidden = false;
        button.setAttribute("aria-expanded", "true");
        // Upward over the stage by default; downward when the sticky site header would cover the
        // top of it (a short stage, or the bar scrolled near the top of the window).
        const header = document.querySelector(".cp-site-header");
        const ceiling = header ? header.getBoundingClientRect().bottom : 0;
        pop.classList.toggle(
            "cp-qr-below",
            button.getBoundingClientRect().top - pop.offsetHeight - 8 < ceiling,
        );
        pop.scrollIntoView?.({ block: "nearest" });
        pop.focus({ preventScroll: true });
    };
    const close = (refocus: boolean) => {
        if (!isOpen()) return;
        pop.hidden = true;
        button.setAttribute("aria-expanded", "false");
        if (refocus) button.focus();
    };
    button.addEventListener("click", () => (isOpen() ? close(false) : open()));
    document.addEventListener("keydown", (event) => {
        if (event.key === "Escape" && isOpen()) close(true);
    });
    document.addEventListener("pointerdown", (event) => {
        if (isOpen() && !wrap.contains(event.target as Node)) close(false);
    });
    // Tabbing out of the popover is leaving it, the same as clicking elsewhere.
    wrap.addEventListener("focusout", (event) => {
        const next = event.relatedTarget as Node | null;
        if (next && !wrap.contains(next)) close(false);
    });
    open();
}
