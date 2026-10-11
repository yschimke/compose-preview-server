// Click-to-copy on the viewer's preview id, the `<code class="cp-preview-id">` under the title.
//
// The id is the one string a visitor carries somewhere else — a `--preview` flag, a bug report, a
// grep — and it is ellipsised to fit the title row, so selecting it by hand is fiddly and copying
// the visible text gets you the stub. Clicking it now copies the whole id instead.
//
// Wired here rather than in the server's markup so the page HTML (and its committed goldens) stays
// as it was: the bundle gives the `<code>` button semantics — role, tab stop, a name that says what
// activating it does — and handles Enter and Space the way a real `<button>` would. Confirmation is
// two-channel: the chip itself reads "Copied ✓" for a moment, and a polite live region says so for
// a screen reader, which would not otherwise re-read a label that changed under focus.

const FLASH_MS = 1400;

/**
 * Put `text` on the clipboard, resolving whether it got there.
 *
 * The async Clipboard API first; where it is missing (an insecure `http://` LAN host) the legacy
 * `execCommand("copy")`, which copies the current selection — so `text` is put in a detached-from-
 * view span and selected, the same requirement the export bar's Copy URL meets with its hidden
 * field. Never the chip itself: during the confirmation flash the chip reads "Copied ✓", and a
 * second activation in that window would copy the status message instead of the id.
 */
export function copyText(
    text: string,
    nav: Navigator = navigator,
    doc: Document = document,
): Promise<boolean> {
    if (nav.clipboard && nav.clipboard.writeText) {
        return nav.clipboard.writeText(text).then(
            () => true,
            () => false,
        );
    }
    const holder = doc.createElement("span");
    holder.textContent = text;
    holder.style.cssText = "position:fixed;left:-9999px;top:0;white-space:pre";
    try {
        doc.body.append(holder);
        const sel = doc.getSelection();
        const range = doc.createRange();
        range.selectNodeContents(holder);
        sel?.removeAllRanges();
        sel?.addRange(range);
        const ok = doc.execCommand("copy");
        sel?.removeAllRanges();
        return Promise.resolve(ok);
    } catch {
        return Promise.resolve(false);
    } finally {
        holder.remove();
    }
}

/** Make one preview-id chip copy its id on click, Enter or Space. */
export function wireCopyId(
    el: HTMLElement,
    copy: (text: string) => Promise<boolean> = (text) => copyText(text),
): void {
    // The `title` carries the full id even when the visible text has been swapped for the
    // confirmation, so it is the source of truth; the text is the fallback for markup without one.
    const id = el.getAttribute("title") || el.textContent || "";
    if (!id) return;
    el.setAttribute("role", "button");
    el.tabIndex = 0;
    el.setAttribute("aria-label", `Copy preview id ${id}`);
    el.title = `${id} — click to copy`;
    const live = el.ownerDocument.createElement("span");
    live.className = "cp-visually-hidden";
    live.setAttribute("role", "status");
    live.setAttribute("aria-live", "polite");
    el.after(live);
    let timer: ReturnType<typeof setTimeout> | undefined;
    const activate = () => {
        void copy(id).then((ok) => {
            const message = ok ? "Copied ✓" : "Copy failed";
            // Pin the width while the shorter confirmation shows, so the disclosures riding the
            // same row do not hop left and back.
            if (!timer) el.style.minWidth = `${el.offsetWidth}px`;
            el.textContent = message;
            live.textContent = ok ? "Preview id copied" : "Copy failed";
            clearTimeout(timer);
            timer = setTimeout(() => {
                el.textContent = id;
                el.style.minWidth = "";
                live.textContent = "";
                timer = undefined;
            }, FLASH_MS);
        });
    };
    el.addEventListener("click", activate);
    el.addEventListener("keydown", (e) => {
        if (e.key !== "Enter" && e.key !== " ") return;
        // Space would otherwise scroll the page, as it does not on a real button.
        e.preventDefault();
        activate();
    });
}
