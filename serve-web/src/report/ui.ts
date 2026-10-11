// The capture tool: three buttons, a status line, and the pile of captures so far, in the launcher
// panel on any page and again on `/report-bug`. The controls are server-rendered `hidden`
// (`ServeWeb.captureControlsHtml`) and unhidden here once capability is known, so no button offers
// a screenshot the browser can't take.

import {
    Frame,
    blobFromDataUrl,
    captureSupported,
    copyPng,
    crop,
    grabFrame,
    toDataUrl,
    whole,
} from "./capture.js";
import { elementLabel, elementMarkdown } from "./markdown.js";
import { markupEditor } from "./markup.js";
import { pickElement, pickRegion } from "./select.js";
import {
    Capture,
    addCapture,
    nextId,
    readCaptures,
    removeCapture,
    replaceCapture,
    sessionStore,
} from "./store.js";
import {
    hostedCaptureUrl,
    stillHosted,
    uploadCapture,
    withClipboardHint,
    withUploadedCaptures,
} from "./upload.js";
import { PRIVATE_SCOPE, SCOPE_ATTR, needsUploadConsent } from "./visibility.js";
import { sharedId, sharedImageUrl, withoutShared } from "./shared.js";
import { fitWithin } from "./geometry.js";

type Mode = "view" | "region" | "element";

/** Wire every capture surface on this page. Safe to call twice; the second call is a no-op. */
export function installCapture(): void {
    if (document.documentElement.hasAttribute("data-cp-capture-ready")) return;
    document.documentElement.setAttribute("data-cp-capture-ready", "1");
    // A fresh page has established nothing about this host yet, whatever the last one learned.
    hostingRuledOut = false;
    // Consent to host a capture from a signed-in-only page is given per page view and never
    // remembered: the default is always "not uploaded".
    shareOptIn = false;
    if (captureSupported()) {
        document
            .querySelectorAll<HTMLElement>(".cp-shot")
            .forEach((block) => (block.hidden = false));
        document
            .querySelectorAll<HTMLElement>("[data-cp-capture]")
            .forEach((btn) =>
                btn.addEventListener("click", () =>
                    run(
                        (btn.getAttribute("data-cp-capture") || "view") as Mode,
                    ),
                ),
            );
    }
    wireHandOff();
    render();
    if (imageUploadEnabled()) void uploadReportCaptures();
    else void discoverImageUpload();
    void importSharedCapture();
}

/** The longest side a shared image is stored at — the same budget as a capture's. */
const SHARED_MAX_SIDE = 1600;

/**
 * Pull a screenshot shared into the installed app (see `shared.ts`) into the pile, stamped as a
 * capture of this report.
 */
async function importSharedCapture(): Promise<void> {
    const id = sharedId(location.search);
    if (!id) return;
    const source = sharedImageUrl(id, location.search);
    // Before the fetch, so a reload mid-import cannot import it twice.
    try {
        history.replaceState(history.state, "", withoutShared(location.href));
    } catch {
        // A sandboxed document may refuse; importing once more on reload is the only cost.
    }
    let bitmap: ImageBitmap;
    try {
        const response = await fetch(source, { credentials: "same-origin" });
        // A text-only share parks no image, and an expired one is gone: nothing to import.
        if (!response.ok) return;
        bitmap = await createImageBitmap(await response.blob());
    } catch {
        note(
            "The shared image could not be read. Paste it into the issue instead.",
        );
        return;
    }
    const size = fitWithin(
        { width: bitmap.width, height: bitmap.height },
        SHARED_MAX_SIDE,
    );
    const canvas = document.createElement("canvas");
    canvas.width = size.width;
    canvas.height = size.height;
    canvas.getContext("2d")?.drawImage(bitmap, 0, 0, size.width, size.height);
    bitmap.close();
    const capture: Capture = {
        id: nextId(readCaptures(sessionStore())),
        label: "Shared image",
        dataUrl: toDataUrl(canvas),
        width: canvas.width,
        height: canvas.height,
        page: reportedPage() ?? location.pathname,
    };
    note(
        storeCapture(capture)
            ? "Your shared image is attached to this report."
            : "The shared image is too large to keep here. Paste it into the issue instead.",
    );
}

/**
 * Whether this page has established it cannot host a capture; set only once discovery has answered.
 */
let hostingRuledOut = false;

/**
 * Tell the reporter before they press the button that the picture will need pasting (this host
 * doesn't host captures and there is a capture for this report), rather than only at submit time on
 * a page they are leaving.
 */
function noteClipboardFallback(): void {
    if (!hostingRuledOut) return;
    // Only where something would actually need pasting; captures already hosted earlier are
    // embedded as before.
    if (
        !capturesForThisReport().some(
            (capture) => !hostedCaptureUrl(capture.uploadedUrl),
        )
    )
        return;
    note(
        "This server can't host captures. Opening the issue puts your newest one on the " +
            "clipboard — paste it into the report's Screenshot section.",
    );
}

/** The captures taken for the report being written here — see {@link reportedPage}. */
function capturesForThisReport(): Capture[] {
    const page = reportedPage();
    return readCaptures(sessionStore()).filter(
        (capture) => !!capture.page && capture.page === page,
    );
}

/**
 * Persist a fresh capture and start hosting it if the image lane was discovered. Discovery usually
 * finishes while the screen picker is open, so the new capture triggers its own upload pass.
 * @returns whether the capture survived the store's quota/eviction rules.
 */
export function storeCapture(capture: Capture): boolean {
    const kept = addCapture(sessionStore(), capture);
    render();
    const stored = kept.some((item) => item.id === capture.id);
    if (stored && imageUploadEnabled()) void uploadReportCaptures();
    // Discovery normally finishes while the screen picker is still open, so the "you will have to
    // paste this" note had nothing to say when it ran: there were no captures yet. Now there are.
    else if (stored) noteClipboardFallback();
    return stored;
}

/** The two forms that open a prefilled issue: `/report-bug`'s and a preview's own. */
const REPORT_FORMS = ".cp-report-bug-form, .cp-report-form";

/**
 * Complete the image hand-off on submit. Hosted captures are already in the prefilled Markdown;
 * otherwise copy the newest capture to the clipboard using the submit gesture. Delegated from the
 * document because the form submitted may not exist at install time (surfaces re-render their
 * forms).
 */
function wireHandOff(): void {
    // Guarded on its own attribute: both bundles can load this file, and a duplicate delegated
    // listener would copy and note twice.
    const root = document.documentElement;
    if (root.hasAttribute("data-cp-handoff-wired")) return;
    root.setAttribute("data-cp-handoff-wired", "1");
    document.addEventListener("submit", (event) => {
        const form = event.target;
        if (form instanceof HTMLElement && form.matches(REPORT_FORMS)) {
            handOff();
        }
    });
}

/**
 * Which page the report is about: the same page for a preview's form, the `?from=` path on
 * `/report-bug`. Parsed for its pathname so a carried query can't break the match.
 */
function reportedPage(): string | null {
    const from = new URLSearchParams(location.search).get("from");
    if (!from) return location.pathname;
    try {
        return new URL(from, location.href).pathname;
    } catch {
        return null;
    }
}

function handOff(): void {
    const captures = readCaptures(sessionStore());
    if (!captures.length) return;
    // The newest capture of the page being reported. `sessionStorage` outlives a report, so an
    // older capture of another page must not be handed over; with none, say nothing.
    const page = reportedPage();
    const mine = captures.filter((c) => !!c.page && c.page === page);
    const latest = mine[mine.length - 1];
    if (!latest) return;
    const embedded = applyHostedCaptures(mine);
    // `hostedCaptureUrl`, not `needsUpload`: by submit the upload flow has confirmed or replaced
    // each URL.
    const carried = (capture: Capture) =>
        shareable(capture) && !!hostedCaptureUrl(capture.uploadedUrl);
    if (embedded && mine.every(carried)) {
        note(
            mine.length === 1
                ? "Your capture is embedded in the report."
                : `Your ${mine.length} captures are embedded in the report.`,
        );
        return;
    }
    // The newest capture the report will not carry: copying an already embedded one would duplicate
    // it and drop the edited one.
    const unhosted = mine.filter((capture) => !embedded || !carried(capture));
    const copying = unhosted[unhosted.length - 1] ?? latest;
    const rest =
        mine.length > 1
            ? ` The other ${mine.length - 1} are still here — press Copy on one to send it too.`
            : "";
    // Also note it in the body, synchronously (the form's entry list is built as the handler
    // returns), right where the paste belongs.
    applyHostedCaptures(mine, { others: mine.length - 1 });
    copyPng(blobFromDataUrl(copying.dataUrl)).then(
        () =>
            note(
                `Your capture is on the clipboard — paste it into the issue's Screenshot section.${rest}`,
            ),
        () => {
            note(
                "The clipboard refused the capture. Press Copy on it here, then paste it into the issue's Screenshot section.",
            );
            // ...and reveal the note: on a preview page it lives in the launcher panel, which the
            // submit just closed.
            reveal();
        },
    );
}

/** Open every disclosure standing between a status note and the reporter. */
function reveal(): void {
    document.querySelectorAll<HTMLElement>(".cp-shot-note").forEach((el) => {
        let box = el.closest("details");
        while (box) {
            box.open = true;
            box = box.parentElement?.closest("details") ?? null;
        }
    });
}

/** True while a markup editor is open anywhere ({@link fill} would rebuild its row). */
function markupOpen(): boolean {
    return !!document.querySelector(".cp-markup");
}

/** A render that arrived while an editor was open, owed once it closes. */
let renderPending = false;

/**
 * Refresh every list from the store, deferred while a markup editor is open: `fill` replaces rows
 * and would discard unsaved annotations (a finishing upload could trigger it). Lists redraw on
 * {@link markupClosed}.
 */
function render(): void {
    if (markupOpen()) {
        renderPending = true;
        return;
    }
    renderPending = false;
    const captures = readCaptures(sessionStore());
    document
        .querySelectorAll<HTMLElement>(".cp-shot-list")
        .forEach((list) => fill(list, captures));
    document
        .querySelectorAll<HTMLElement>(".cp-shots-empty")
        .forEach((note) => (note.hidden = captures.length > 0));
    syncShareConsent();
}

/**
 * Whether the reporter opted in to uploading captures of a signed-in-only page. Off on every load:
 * those captures are still taken and copyable, but auto-upload to a public, anonymously readable
 * URL waits for consent.
 */
let shareOptIn = false;

/** True when this capture may be uploaded and embedded without asking first, or was allowed. */
function shareable(capture: Capture): boolean {
    return shareOptIn || !needsUploadConsent(capture.page);
}

/**
 * Show the opt-in only where it matters: the host can embed, and a capture for this report came
 * from a page needing consent.
 */
function syncShareConsent(): void {
    const wanted =
        imageUploadEnabled() &&
        capturesForThisReport().some((capture) =>
            needsUploadConsent(capture.page),
        );
    document.querySelectorAll<HTMLElement>(".cp-shot-list").forEach((list) => {
        let box = list.parentElement?.querySelector<HTMLLabelElement>(
            ":scope > .cp-shot-share",
        );
        if (!box && wanted) {
            box = shareConsentControl();
            list.before(box);
        }
        if (!box) return;
        box.hidden = !wanted;
        const input = box.querySelector<HTMLInputElement>("input");
        if (input) input.checked = shareOptIn;
    });
}

function shareConsentControl(): HTMLLabelElement {
    const label = document.createElement("label");
    label.className = "cp-shot-share";
    const input = document.createElement("input");
    input.type = "checkbox";
    input.className = "cp-shot-share-input";
    input.checked = shareOptIn;
    input.addEventListener("change", () => {
        shareOptIn = input.checked;
        syncShareConsent();
        void uploadReportCaptures();
    });
    const text = document.createElement("span");
    text.textContent =
        "Upload my captures and embed them in the issue. This page is only visible to signed-in " +
        "users, but the uploaded image can be opened by anyone with its link, and the GitHub " +
        "issue that links it is public. Left unticked, opening the issue puts your newest " +
        "capture on the clipboard instead.";
    label.append(input, text);
    return label;
}

/** Pay back a render deferred while the editor held the row. */
function markupClosed(): void {
    if (renderPending) render();
}

const originalBodies = new WeakMap<HTMLInputElement, string>();
/**
 * The exact value last written to each body field, so {@link applyHostedCaptures} can tell its own
 * re-embed from someone else's rewrite.
 */
const lastWritten = new WeakMap<HTMLInputElement, string>();
let uploadGeneration = 0;

/**
 * Ask whether this browser session may host report captures. `/report-bug` already carries
 * `data-cp-image-upload`; other pages check per request. Failure keeps the clipboard hand-off.
 */
async function discoverImageUpload(): Promise<void> {
    try {
        // Fixed and same-origin by construction. Hosts without the optional image lane answer 404;
        // unauthorized browser sessions answer 403 and retain the clipboard path.
        const endpoint = new URL("/images/capability", location.href);
        const token = new URLSearchParams(location.search).get("token");
        if (token) endpoint.searchParams.set("token", token);
        const response = await fetch(endpoint, {
            method: "GET",
            credentials: "same-origin",
            cache: "no-store",
        });
        if (!response.ok) {
            // 403 and 404 mean the same to a reporter: nothing will be embedded.
            hostingRuledOut = true;
            noteClipboardFallback();
            return;
        }
        const mount = document.querySelector<HTMLElement>(".cp-fab, .cp-shots");
        mount?.setAttribute("data-cp-image-upload", "true");
        // A token-gated host answers "private", so its captures wait for opt-in.
        if (
            response.headers?.get?.("X-Compose-Preview-Capture-Scope") ===
            PRIVATE_SCOPE
        )
            mount?.setAttribute(SCOPE_ATTR, PRIVATE_SCOPE);
        await uploadReportCaptures();
    } catch {
        // Hosting is optional. The submit-time clipboard hand-off remains the fallback.
        hostingRuledOut = true;
        noteClipboardFallback();
    }
}

/**
 * Image URLs this page has confirmed exist (uploaded here, or HEAD-checked). `hostedCaptureUrl`
 * checks a restored URL's shape, not existence; the store may have been restarted or expired it,
 * and trusting it would embed a 404 and skip the clipboard fallback. Checked once per page.
 */
const verifiedUrls = new Set<string>();

/** True when this capture has no hosted URL, or one this page cannot vouch for. */
function needsUpload(capture: Capture): boolean {
    const url = hostedCaptureUrl(capture.uploadedUrl);
    return !url || !verifiedUrls.has(url);
}

/** Upload this report's captures in the background while the reporter writes the summary. */
async function uploadReportCaptures(): Promise<void> {
    const generation = ++uploadGeneration;
    const page = reportedPage();
    const mine = readCaptures(sessionStore()).filter(
        (capture) => !!capture.page && capture.page === page,
    );
    // Includes captures whose restored URL is still being checked, so Submit stays disabled
    // meanwhile.
    const pending = mine.filter(
        (capture) => shareable(capture) && needsUpload(capture),
    );
    syncShareConsent();
    // An edit clears `uploadedUrl`; remove the old embed now so a failed re-upload can't leave the
    // unannotated pixels in the body.
    applyHostedCaptures(mine);
    const submits = document.querySelectorAll<HTMLButtonElement>(
        ".cp-bug-submit, .cp-report-submit",
    );
    if (!pending.length) {
        if (mine.some((capture) => !shareable(capture)))
            note(
                "Captures from this page are not uploaded unless you tick the box above. " +
                    "Opening the issue puts your newest one on the clipboard instead.",
            );
        // Nothing to wait for, so re-enable Submit (removing the last capture mid-upload lands
        // here, and the in-flight upload's `finally` won't).
        submits.forEach((submit) => (submit.disabled = false));
        return;
    }
    submits.forEach((submit) => (submit.disabled = true));
    note(
        pending.length === 1
            ? "Uploading your capture…"
            : `Uploading ${pending.length} captures…`,
    );
    try {
        for (const capture of pending) {
            const restored = hostedCaptureUrl(capture.uploadedUrl);
            if (restored && (await stillHosted(restored))) {
                // Still there: keep the URL and skip the re-upload.
                if (generation !== uploadGeneration) return;
                verifiedUrls.add(restored);
                continue;
            }
            // `uploadCapture` short-circuits on a present `uploadedUrl`, which is exactly the
            // stale one we just failed to confirm — so ask for a genuine upload.
            const uploaded = await uploadCapture({
                ...capture,
                uploadedUrl: undefined,
            });
            // An edit or removal starts a new generation. Never let this older request restore its
            // pre-edit pixels (and URL) after the replacement has reached sessionStorage.
            if (generation !== uploadGeneration) return;
            verifiedUrls.add(uploaded.url);
            replaceCapture(sessionStore(), {
                ...capture,
                uploadedUrl: uploaded.url,
            });
        }
        if (generation !== uploadGeneration) return;
        const current = readCaptures(sessionStore()).filter(
            (capture) => !!capture.page && capture.page === page,
        );
        applyHostedCaptures(current);
        note(
            current.length === 1
                ? "Capture uploaded — it will be embedded in the report."
                : `${current.length} captures uploaded — they will be embedded in the report.`,
        );
        render();
    } catch {
        if (generation !== uploadGeneration) return;
        note(
            "This server could not host the capture. It will be copied when you open the issue, so paste it into the Screenshot section.",
        );
    } finally {
        if (generation === uploadGeneration) {
            submits.forEach((submit) => (submit.disabled = false));
        }
    }
}

function imageUploadEnabled(): boolean {
    return (
        document
            .querySelector("[data-cp-image-upload]")
            ?.getAttribute("data-cp-image-upload") === "true"
    );
}

/**
 * The hidden body field of whichever report form this page has: `#cp-bug-body` or a preview's
 * `#cp-report-body`.
 */
function reportBodyInput(): HTMLInputElement | null {
    return document.querySelector<HTMLInputElement>(
        "#cp-bug-body, #cp-report-body",
    );
}

/**
 * Write hosted captures into the report body.
 * @returns whether a body field existed, so "uploaded" isn't mistaken for "embedded".
 */
function applyHostedCaptures(
    captures: Capture[],
    clipboard?: { others: number },
): boolean {
    const input = reportBodyInput();
    if (!input) return false;
    // Re-read the base when something else wrote the field: a preview page's body is rewritten by
    // `refreshReportLink` (viewer.ts) on every knob change, so a cached base would describe the
    // first render.
    if (!originalBodies.has(input) || lastWritten.get(input) !== input.value) {
        originalBodies.set(input, input.value);
    }
    // Only embeddable captures, and those from signed-in-only pages under neutral alt text (labels
    // come from page ids and classes; the issue is public).
    const embedded = withUploadedCaptures(
        originalBodies.get(input) ?? input.value,
        captures
            .filter(shareable)
            .map((capture) =>
                needsUploadConsent(capture.page)
                    ? { ...capture, label: "screenshot" }
                    : capture,
            ),
    );
    // Rebuilt from the cached base every time, so the hint is written once however often this runs.
    const next = clipboard
        ? withClipboardHint(embedded, clipboard.others)
        : embedded;
    input.value = next;
    lastWritten.set(input, next);
    const preview = document.querySelector<HTMLElement>("#cp-bug-preview");
    if (preview) preview.textContent = input.value;
    return true;
}

function fill(list: HTMLElement, captures: Capture[]): void {
    list.replaceChildren(...captures.map(item));
}

/**
 * One capture as a row, built with `createElement`: many values are DOM-derived text going into
 * attributes, where string concatenation risks attribute injection.
 */
function item(capture: Capture): HTMLElement {
    const li = document.createElement("li");
    li.className = "cp-shot-item";

    const img = document.createElement("img");
    img.className = "cp-shot-thumb";
    img.src = capture.dataUrl;
    img.alt = `capture: ${capture.label}`;
    img.loading = "lazy";

    const meta = document.createElement("div");
    meta.className = "cp-shot-meta";
    const label = document.createElement("span");
    label.className = "cp-shot-label";
    label.textContent = capture.label;
    const size = document.createElement("span");
    size.className = "cp-shot-size";
    size.textContent = `${capture.width}×${capture.height}`;
    meta.append(label, size);

    const actions = document.createElement("div");
    actions.className = "cp-shot-actions";
    actions.append(
        action("Copy", "Copy the picture — then paste it into the issue", () =>
            // Pressed inside the click, so the gesture that authorises a clipboard write is still
            // in hand; `copyPng` takes the encode as a promise for the same reason.
            copyPng(blobFromDataUrl(capture.dataUrl)),
        ),
    );
    const markUp = document.createElement("button");
    markUp.type = "button";
    markUp.className = "cp-shot-action";
    markUp.textContent = "Mark up";
    markUp.title = "Draw a box, arrow, note, or freehand mark on this capture";
    markUp.addEventListener("click", () => {
        li.querySelector(".cp-markup")?.remove();
        const editor = markupEditor(
            capture,
            (updated) => {
                // Close the editor first: `render` defers while one is open, so the saved thumbnail
                // would never redraw.
                li.querySelector(".cp-markup")?.remove();
                replaceCapture(sessionStore(), updated);
                render();
                if (imageUploadEnabled()) {
                    void uploadReportCaptures();
                }
            },
            () => {
                li.querySelector(".cp-markup")?.remove();
                markupClosed();
            },
        );
        li.append(editor);
    });
    actions.append(markUp);
    if (capture.markdown) {
        const markdown = capture.markdown;
        actions.append(
            action(
                "Copy as text",
                "Copy the same table as markdown, so it can be read and quoted",
                () => navigator.clipboard.writeText(markdown),
            ),
        );
    }
    const download = document.createElement("a");
    download.className = "cp-shot-action";
    download.textContent = "Save";
    download.href = capture.dataUrl;
    download.download = `${capture.id}.png`;
    actions.append(download);
    actions.append(
        action("Remove", "Discard this capture", () => {
            removeCapture(sessionStore(), capture.id);
            render();
            if (imageUploadEnabled()) {
                void uploadReportCaptures();
            }
            return Promise.resolve();
        }),
    );

    li.append(img, meta, actions);
    return li;
}

/**
 * A row action reporting its outcome in its label (the only feedback a clipboard write can give),
 * like the viewer's Copy buttons.
 */
function action(
    label: string,
    title: string,
    run: () => Promise<unknown>,
): HTMLButtonElement {
    const btn = document.createElement("button");
    btn.type = "button";
    btn.className = "cp-shot-action";
    btn.textContent = label;
    btn.title = title;
    btn.addEventListener("click", () => {
        run().then(
            () => flash(btn, label, "Copied"),
            () => flash(btn, label, "Failed"),
        );
    });
    return btn;
}

function flash(btn: HTMLElement, was: string, now: string): void {
    if (!btn.isConnected) return;
    btn.textContent = now;
    setTimeout(() => {
        if (btn.isConnected) btn.textContent = was;
    }, 1400);
}

/** The status line under the mode buttons, in every capture block on the page. */
function note(text: string): void {
    document
        .querySelectorAll<HTMLElement>(".cp-shot-note")
        .forEach((el) => (el.textContent = text));
}

/** The launcher, so a capture can close it before the shutter and reopen it after. */
function launcher(): HTMLDetailsElement | null {
    return document.querySelector<HTMLDetailsElement>(".cp-fab-menu");
}

/**
 * Two animation frames and a beat: closing the launcher only schedules a repaint, so capturing
 * immediately would photograph it. The timeout covers background tabs where rAF doesn't fire.
 */
function settle(): Promise<void> {
    return new Promise((resolve) => {
        let done = false;
        const finish = () => {
            if (done) return;
            done = true;
            resolve();
        };
        setTimeout(finish, 120);
        requestAnimationFrame(() => requestAnimationFrame(finish));
    });
}

async function run(mode: Mode): Promise<void> {
    const menu = launcher();
    const wasOpen = !!menu?.open;
    if (menu) menu.open = false;
    note("Waiting for you to allow the capture…");
    let frame: Frame;
    try {
        await settle();
        frame = await grabFrame();
    } catch {
        // A refused prompt and a browser that cannot do it at all land here alike, and the visitor
        // knows which of the two just happened far better than this does.
        if (menu && wasOpen) menu.open = true;
        note("No capture taken. Paste an ordinary screenshot instead.");
        return;
    }
    if (menu && wasOpen) menu.open = true;
    // A crop needs the frame to be this tab (viewport coordinates); for another window or monitor
    // take the whole surface and say so.
    const tab = frame.surface === "browser";
    if (!tab && mode !== "view") {
        note("You shared a window rather than this tab — capturing all of it.");
    }
    let label = "Whole view";
    let markdown: string | undefined;
    let canvas: HTMLCanvasElement;
    if (mode === "view" || !tab) {
        canvas = whole(frame);
        if (!tab) label = "Shared screen";
    } else {
        note(
            mode === "region"
                ? "Drag a box around the part that is wrong."
                : "Click the element to capture.",
        );
        const picked =
            mode === "region" ? await pickRegion() : await pickElement();
        if (!picked) {
            note("Cancelled.");
            return;
        }
        canvas = crop(frame, picked.rect);
        label =
            mode === "region"
                ? "Region"
                : `Element · ${elementLabel(picked.element as Element)}`;
        // A picked table is worth carrying as text as well as pixels — see `markdown.ts`.
        markdown = picked.element
            ? elementMarkdown(picked.element) || undefined
            : undefined;
    }
    const store = sessionStore();
    const capture: Capture = {
        id: nextId(readCaptures(store)),
        label,
        dataUrl: toDataUrl(canvas),
        width: canvas.width,
        height: canvas.height,
        markdown,
        // Stamped with the page it pictures, so the hand-off can match it to the report (see
        // [Capture.page]).
        page: location.pathname,
    };
    if (!storeCapture(capture)) {
        // Every eviction path failed (storage unavailable or full): the clipboard still works, but
        // it can't travel to the report page.
        note("Captured, but it can't be carried to the report — copy it now.");
        return;
    }
    // Copied immediately while the user gesture is in hand; the row's Copy button is the fallback.
    copyPng(blobFromDataUrl(capture.dataUrl)).then(
        () => note("Copied — paste it into the issue body on GitHub."),
        () => note("Captured. Press Copy, then paste it into the issue body."),
    );
}
