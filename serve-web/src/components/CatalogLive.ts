// `<cp-catalog-live>`: long-press a catalog card to start a live daemon session in place. The
// card's baked thumbnail stays as the stage: a `<canvas>` overlay seeded with its pixels (no blank
// flash while connecting) receives the daemon's frames, and pointer, wheel and key input is
// forwarded.
//
// Progressive enhancement: without script or a live lane the cards are plain links. Configuration
// comes from `window.cpCatalogLive` (an inline object literal, not `data-` attributes), so no
// preview id put into a URL originates as DOM text.
//
// Renders nothing itself (`serve.css` hides the tag). Decisions live in `live/pointerMap.ts` and
// `live/session.ts`.

import { ControllerElement, customElement } from "../controllerElement.js";
import { sameOriginNavigation } from "../dom/sameOrigin.js";
import { whenParsed } from "../dom/whenParsed.js";
import {
    FrameQueue,
    frameBlob,
    pumpFrames,
    shouldPaintDecodedFrame,
    type ServeFrame,
} from "../live/framePainter.js";
import { drifted, framePixel } from "../live/pointerMap.js";
import {
    closeReason,
    previewIdOf,
    socketUrl,
    startsHold,
    themeProviderOf,
    visibilityMessage,
    type CardEntry,
    type LiveConfig,
} from "../live/session.js";

/** How far a pointer may drift during the hold before it reads as a scroll or a drag. */
const SLOP_PX = 10;
/** How long an error stays on the card that produced it. */
const ANNOUNCE_MS = 4000;

interface Session {
    card: HTMLElement;
    canvas: HTMLCanvasElement;
    img: HTMLImageElement;
    chip: HTMLElement;
    socket: WebSocket | null;
    pointers: Map<number, { x: number; y: number; moved: boolean }>;
    /** Newest-wins frame queue + the highest `seq` actually painted. See `live/framePainter.ts`. */
    frames: FrameQueue;
    paintedSeq: number;
    painting: boolean;
    /** Whether the card is currently within the viewport (an observer-less browser: always). */
    onScreen: boolean;
    /** The visibility the SERVER believes, so a scroll only sends when it changes. */
    reportedVisible: boolean;
    /** Listeners and observers that live exactly as long as this session. */
    cleanups: Array<() => void>;
}

interface Press {
    card: HTMLElement;
    entry: CardEntry;
    x: number;
    y: number;
    timer: ReturnType<typeof setTimeout>;
}

declare global {
    interface Window {
        cpCatalogLive?: LiveConfig;
    }
}

@customElement("cp-catalog-live")
export class CatalogLive extends ControllerElement {
    private installed = false;
    private config: LiveConfig = {};
    private holdMs = 500;
    /**
     * The one live session: each is a render daemon and a grid has many cards, so starting one ends
     * the previous.
     */
    private active: Session | null = null;
    private press: Press | null = null;
    private suppressNextClick = false;
    private cleanups: Array<() => void> = [];

    connectedCallback(): void {
        super.connectedCallback();
        if (!this.install()) void whenParsed().then(() => this.install());
    }

    disconnectedCallback(): void {
        this.stopLive(null);
        this.cancelPress();
        for (const off of this.cleanups) off();
        this.cleanups = [];
        this.installed = false;
        super.disconnectedCallback();
    }

    private install(): boolean {
        if (!this.isConnected || this.installed) return true;
        const config = window.cpCatalogLive;
        if (!config?.cards?.length) return false;
        const cards = Array.from(
            document.querySelectorAll<HTMLElement>(".cp-card"),
        );
        if (!cards.length) return false;
        this.installed = true;
        this.config = config;
        this.holdMs = config.holdMs || 500;

        cards.forEach((card, index) => {
            const entry = config.cards?.[index];
            if (!entry || (!entry.l && !entry.d)) return;
            this.wireCard(card, entry);
        });

        this.on(document, "keydown", (event) => {
            if ((event as KeyboardEvent).key === "Escape") this.stopLive(null);
        });
        // A press anywhere outside the live card ends the session — the grid is for browsing, and a
        // stream nobody is looking at is a daemon nobody is using.
        this.on(document, "pointerdown", (event) => {
            const target = event.target as Node | null;
            if (this.active && target && !this.active.card.contains(target))
                this.stopLive(null);
        });
        this.on(window, "pagehide", () => this.stopLive(null));
        return true;
    }

    private on(
        target: EventTarget,
        type: string,
        handler: EventListener,
        options?: AddEventListenerOptions,
    ): void {
        target.addEventListener(type, handler, options);
        this.cleanups.push(() =>
            target.removeEventListener(type, handler, options),
        );
    }

    /** The declared theme the pressed chip shows, if any, so a live session opens under it. */
    private themeProvider(): string {
        const pressed = document.querySelector(
            '.cp-theme-btn[aria-pressed="true"]',
        );
        return themeProviderOf(
            pressed?.getAttribute("data-theme-choice") ?? "",
        );
    }

    // ---- the session ---------------------------------------------------------

    /**
     * Tell the server whether anyone is looking: scrolling the card away or backgrounding the tab
     * throttles the stream (kept warm) rather than rendering for nobody. Without
     * `IntersectionObserver` only the tab signal is used.
     */
    private watchVisibility(session: Session): () => void {
        const report = (): void => {
            const visible = session.onScreen && !document.hidden;
            const socket = session.socket;
            if (this.active !== session || !socket || socket.readyState !== 1)
                return;
            // Compare with what the server was told, not the last computed state: a state sampled
            // while connecting is still news once open.
            if (visible === session.reportedVisible) return;
            session.reportedVisible = visible;
            socket.send(visibilityMessage(visible));
        };

        const onTabVisibility = (): void => report();
        document.addEventListener("visibilitychange", onTabVisibility);
        session.cleanups.push(() =>
            document.removeEventListener("visibilitychange", onTabVisibility),
        );

        if (typeof IntersectionObserver !== "function") return report;
        const observer = new IntersectionObserver(
            (entries) => {
                const last = entries[entries.length - 1];
                if (!last) return;
                session.onScreen = last.isIntersecting;
                report();
            },
            // A sliver of the card counts as looking at it: the throttle is for cards that have
            // left, not for ones halfway up the fold.
            { threshold: 0 },
        );
        observer.observe(session.card);
        session.cleanups.push(() => observer.disconnect());
        return report;
    }

    private stopLive(reason: string | null): void {
        const session = this.active;
        if (!session) return;
        this.active = null;
        for (const off of session.cleanups) off();
        session.cleanups = [];
        if (session.socket) {
            session.socket.onmessage = null;
            session.socket.onclose = null;
            try {
                session.socket.close();
            } catch {
                // Already closing. Nothing left to release.
            }
            session.socket = null;
        }
        session.card.classList.remove("cp-card-live");
        session.canvas.remove();
        session.chip.remove();
        session.img.style.removeProperty("visibility");
        if (reason) this.announce(session.card, reason);
    }

    /** A failure must show on the card, or it's indistinguishable from an unregistered press. */
    private announce(card: HTMLElement, message: string): void {
        const wrap = card.querySelector(".cp-imgwrap");
        if (!wrap) return;
        wrap.querySelector(".cp-live-error")?.remove();
        const box = document.createElement("span");
        box.className = "cp-live-error";
        box.setAttribute("role", "status");
        box.textContent = message;
        wrap.appendChild(box);
        setTimeout(() => box.remove(), ANNOUNCE_MS);
    }

    private startLive(card: HTMLElement, entry: CardEntry): void {
        const previewId = previewIdOf(
            entry,
            card.getAttribute("data-swap") === "1",
            card.getAttribute("data-bg-theme"),
        );
        if (!previewId) return;
        // On a GitHub-gated box the press follows the sign-in link (the card is itself an `<a>`, so
        // it can't contain one).
        if (this.config.signInHref) {
            const href = sameOriginNavigation(
                this.config.signInHref,
                location.origin,
            );
            if (href) location.href = href;
            else
                this.announce(
                    card,
                    "Sign in with GitHub to start a live session.",
                );
            return;
        }
        this.stopLive(null);
        const img = card.querySelector("img");
        const wrap = card.querySelector(".cp-imgwrap");
        if (!img || !wrap) return;

        const canvas = document.createElement("canvas");
        canvas.className = "cp-card-canvas";
        // Seed from the thumbnail so the card shows real pixels for the whole connect window; the
        // first daemon frame overwrites the buffer.
        if (img.naturalWidth && img.naturalHeight) {
            canvas.width = img.naturalWidth;
            canvas.height = img.naturalHeight;
            try {
                canvas.getContext("2d")?.drawImage(img, 0, 0);
            } catch {
                // A tainted thumbnail cannot be read back. The connect window just starts blank.
            }
        }
        const chip = document.createElement("span");
        chip.className = "cp-live-chip";
        chip.setAttribute("role", "status");
        chip.textContent = "connecting…";
        wrap.appendChild(canvas);
        wrap.appendChild(chip);
        img.style.visibility = "hidden";
        card.classList.add("cp-card-live");

        const session: Session = {
            card,
            canvas,
            img,
            chip,
            socket: null,
            pointers: new Map(),
            frames: new FrameQueue(),
            paintedSeq: -1,
            painting: false,
            onScreen: true,
            reportedVisible: true,
            cleanups: [],
        };
        this.active = session;
        this.runFrameLoop(session);

        let socket: WebSocket;
        try {
            socket = new WebSocket(
                socketUrl(
                    this.config,
                    previewId,
                    location,
                    this.themeProvider(),
                ),
            );
        } catch {
            this.stopLive(closeReason(null));
            return;
        }
        session.socket = socket;
        const reportVisibility = this.watchVisibility(session);

        let gotFrame = false;
        socket.onopen = () => {
            // State visibility once the socket opens: a card in an already-hidden tab or starting
            // off-screen gets no further event to trigger it, and streams start visible
            // server-side.
            reportVisibility();
        };
        socket.onmessage = (event: MessageEvent) => {
            if (this.active !== session) return;
            let message: {
                type?: string;
                seq?: number;
                dataBase64?: string;
                codec?: string;
                message?: string;
            };
            try {
                message = JSON.parse(String(event.data));
            } catch {
                return;
            }
            if (message.type === "frame") {
                gotFrame = true;
                session.chip.textContent = "live";
                session.frames.submit(message as ServeFrame);
            } else if (message.type === "error") {
                session.chip.textContent = message.message || "error";
            }
        };
        socket.onclose = (event: CloseEvent) => {
            if (this.active !== session) return;
            // Closed before any frame ⇒ the lane never activated. Drop the seeded thumbnail from
            // the canvas rather than letting it pass for a live render, and say why.
            this.stopLive(gotFrame ? null : closeReason(event));
        };
        this.wireInput(session);
    }

    /**
     * Drain one queued frame per animation frame while this session is live. Two watermarks keep it
     * moving forward: the queue drops frames at or below the last released, and `paintedSeq` drops
     * out-of-order decodes (see `live/framePainter.ts`).
     */
    private runFrameLoop(session: Session): void {
        if (session.painting) return;
        session.painting = true;
        pumpFrames({
            alive: () => {
                if (this.active === session) return true;
                session.painting = false;
                return false;
            },
            next: () => session.frames.dispatch(),
            paint: (frame) => this.decodeAndPaint(session, frame),
        });
    }

    private decodeAndPaint(session: Session, frame: ServeFrame): void {
        const blob = frameBlob(frame);
        if (!blob) return; // heartbeat — the daemon says the pixels are unchanged
        createImageBitmap(blob).then(
            (bitmap) => {
                // The decode may have resolved after a newer frame already painted, and the card
                // may have stopped streaming entirely while it was in flight.
                if (
                    this.active !== session ||
                    !shouldPaintDecodedFrame(session.paintedSeq, frame.seq)
                ) {
                    bitmap.close();
                    return;
                }
                session.paintedSeq = frame.seq;
                session.canvas.width = bitmap.width;
                session.canvas.height = bitmap.height;
                session.canvas.getContext("2d")?.drawImage(bitmap, 0, 0);
                bitmap.close();
            },
            () => {
                // A frame that won't decode is dropped; the next one repaints the card.
            },
        );
    }

    // ---- input forwarding ----------------------------------------------------

    private wireInput(session: Session): void {
        const canvas = session.canvas;
        const send = (message: Record<string, unknown>): void => {
            const socket = session.socket;
            if (this.active !== session || !socket || socket.readyState !== 1)
                return;
            socket.send(JSON.stringify({ type: "input", ...message }));
        };
        const pixel = (event: PointerEvent | WheelEvent) =>
            framePixel(
                canvas.getBoundingClientRect(),
                { x: canvas.width, y: canvas.height },
                { x: event.clientX, y: event.clientY },
            );

        canvas.addEventListener("pointerdown", (event) => {
            const point = pixel(event);
            if (!point) return;
            event.preventDefault();
            event.stopPropagation();
            try {
                canvas.setPointerCapture?.(event.pointerId);
            } catch {
                // Capture is a nicety; the pointer still tracks without it.
            }
            session.pointers.set(event.pointerId, { ...point, moved: false });
        });
        canvas.addEventListener("pointermove", (event) => {
            const state = session.pointers.get(event.pointerId);
            if (!state) return;
            const point = pixel(event);
            if (!point) return;
            event.preventDefault();
            // Withhold the down until the first move so a tap is sent as a single `click` (the
            // daemon's fast path; a batched down+up can race).
            if (!state.moved) {
                state.moved = true;
                send({
                    kind: "pointerDown",
                    pixelX: state.x,
                    pixelY: state.y,
                    pointerId: event.pointerId,
                });
            }
            send({
                kind: "pointerMove",
                pixelX: point.x,
                pixelY: point.y,
                pointerId: event.pointerId,
            });
        });
        canvas.addEventListener("pointerup", (event) => {
            const state = session.pointers.get(event.pointerId);
            if (!state) return;
            session.pointers.delete(event.pointerId);
            const point = pixel(event) ?? { x: state.x, y: state.y };
            event.preventDefault();
            event.stopPropagation();
            send({
                kind: state.moved ? "pointerUp" : "click",
                pixelX: point.x,
                pixelY: point.y,
                pointerId: event.pointerId,
            });
        });
        canvas.addEventListener("pointercancel", (event) => {
            session.pointers.delete(event.pointerId);
        });
        // The card is a link: a click that reached it after driving the composition must not
        // navigate.
        canvas.addEventListener("click", (event) => {
            event.preventDefault();
            event.stopPropagation();
        });
        canvas.addEventListener(
            "wheel",
            (event) => {
                const point = pixel(event);
                if (!point) return;
                event.preventDefault();
                send({
                    kind: "rotaryScroll",
                    pixelX: point.x,
                    pixelY: point.y,
                    scrollDeltaY: event.deltaY,
                });
            },
            { passive: false },
        );
    }

    // The long press. A card is a link: a press past the threshold goes live and doesn't follow the
    // link, while a tap, drag/scroll or right-click behaves as before.

    private cancelPress(): void {
        if (!this.press) return;
        clearTimeout(this.press.timer);
        this.press.card.classList.remove("cp-card-pressing");
        this.press = null;
    }

    private wireCard(card: HTMLElement, entry: CardEntry): void {
        card.classList.add("cp-card-livable");
        // Discoverability: the affordance is invisible otherwise. A plain span (not a button) — a
        // card is an `<a>`, and interactive content may not nest inside one.
        const wrap = card.querySelector(".cp-imgwrap");
        if (wrap && !wrap.querySelector(".cp-live-hint")) {
            const hint = document.createElement("span");
            hint.className = "cp-live-hint";
            hint.setAttribute("aria-hidden", "true");
            hint.textContent = "hold for live";
            wrap.appendChild(hint);
        }

        this.on(card, "pointerdown", (event) => {
            const pointer = event as PointerEvent;
            if (!startsHold(pointer)) return;
            // Already live — the canvas owns the pointer.
            if (this.active?.card === card) return;
            this.cancelPress();
            card.classList.add("cp-card-pressing");
            this.press = {
                card,
                entry,
                x: pointer.clientX,
                y: pointer.clientY,
                timer: setTimeout(() => {
                    const target = this.press;
                    this.cancelPress();
                    if (!target) return;
                    // The press became a gesture, so the click it will produce is not a navigation.
                    this.suppressNextClick = true;
                    this.startLive(target.card, target.entry);
                }, this.holdMs),
            };
        });
        this.on(card, "pointermove", (event) => {
            const pointer = event as PointerEvent;
            if (this.press?.card !== card) return;
            if (
                drifted(
                    this.press,
                    { x: pointer.clientX, y: pointer.clientY },
                    SLOP_PX,
                )
            )
                this.cancelPress();
        });
        for (const type of ["pointerup", "pointercancel", "pointerleave"]) {
            this.on(card, type, () => this.cancelPress());
        }
        // Touch platforms pop a context menu / selection callout on a long press; that is exactly
        // the gesture being claimed here.
        this.on(card, "contextmenu", (event) => {
            if (this.press?.card === card) event.preventDefault();
        });
        this.on(card, "click", (event) => {
            if (this.suppressNextClick || this.active?.card === card) {
                this.suppressNextClick = false;
                event.preventDefault();
                event.stopPropagation();
            }
        });
        // Keyboard equivalent: a long press is a pointer gesture, so `L` on a focused card is how
        // the same lane is reached without one. Escape leaves it (as does clicking off the card).
        this.on(card, "keydown", (event) => {
            const key = event as KeyboardEvent;
            if (key.ctrlKey || key.metaKey || key.altKey) return;
            if (key.key === "l" || key.key === "L") {
                key.preventDefault();
                if (this.active?.card === card) this.stopLive(null);
                else this.startLive(card, entry);
            } else if (key.key === "Escape" && this.active?.card === card) {
                key.preventDefault();
                this.stopLive(null);
            }
        });
    }
}

declare global {
    interface HTMLElementTagNameMap {
        "cp-catalog-live": CatalogLive;
    }
}
