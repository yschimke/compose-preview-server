// Keep the screen on while something is actually moving on the stage.
//
// A live preview stream and a playing motion capture are both things a person watches without
// touching the phone, and a phone that dims and locks halfway through is the screen-saver winning
// against the one moment the page exists for. So each of them holds a reason, and while any reason
// is held and the page is visible, the viewer holds a Screen Wake Lock. It is released the moment
// the last reason goes — the stream closed, the capture paused or finished — and the browser
// releases it on its own when the tab is hidden; coming back re-acquires it only if something is
// still running. Where the API is missing (no secure context, an older browser) this does nothing.

/** The slice of `navigator.wakeLock` this needs. */
export interface WakeLockApi {
    request(type: "screen"): Promise<{
        release(): Promise<void>;
        addEventListener?(type: "release", listener: () => void): void;
    }>;
}

type Sentinel = Awaited<ReturnType<WakeLockApi["request"]>>;

export class ScreenWakeHold {
    private readonly reasons = new Set<string>();
    private sentinel: Sentinel | null = null;
    private pending = false;

    constructor(
        private readonly api: WakeLockApi | null,
        private readonly visible: () => boolean,
    ) {}

    /** Whether [reason] currently wants the screen kept on. */
    hold(reason: string, on: boolean): void {
        if (on) this.reasons.add(reason);
        else this.reasons.delete(reason);
        this.sync();
    }

    /** Call on `visibilitychange`: a hidden page has already lost its lock. */
    visibilityChanged(): void {
        if (!this.visible()) this.sentinel = null;
        this.sync();
    }

    get held(): boolean {
        return this.sentinel !== null;
    }

    private sync(): void {
        if (!this.api) return;
        const want = this.reasons.size > 0 && this.visible();
        if (want && !this.sentinel && !this.pending) {
            this.pending = true;
            this.api.request("screen").then(
                (sentinel) => {
                    this.pending = false;
                    this.sentinel = sentinel;
                    sentinel.addEventListener?.("release", () => {
                        if (this.sentinel === sentinel) this.sentinel = null;
                    });
                    // The reason may have gone while the request was in flight.
                    this.sync();
                },
                () => {
                    // Refused (battery saver, no user activation yet): not worth a message.
                    this.pending = false;
                },
            );
        } else if (!want && this.sentinel) {
            const sentinel = this.sentinel;
            this.sentinel = null;
            sentinel.release().catch(() => {});
        }
    }
}

/** The page's hold, wired to its own visibility. */
export function pageWakeHold(): ScreenWakeHold {
    const api =
        (navigator as Navigator & { wakeLock?: WakeLockApi }).wakeLock ?? null;
    const hold = new ScreenWakeHold(api, () => !document.hidden);
    document.addEventListener("visibilitychange", () =>
        hold.visibilityChanged(),
    );
    return hold;
}
