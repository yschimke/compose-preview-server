// `<cp-acceptance>` — what this catalog has accepted about the comparison on screen, and what that
// leaves unaccepted.
//
// The band is the visible half of `compose-preview-known-differences/v1`. It renders three numbers
// and a row per acceptance, and the rules behind every one of them live in the shared engine next
// door (`src/parity/acceptance.ts` → `scripts/design-artifacts/`), not here. This file decides only
// what a reader is shown.
//
// Three numbers because they answer three questions and none is the difference of the others:
//
//   - **raw** is the pair as though nothing had been accepted. It is never hidden. That is the
//     epic's requirement and the reason acceptance is not an ignore rectangle: an accepted
//     difference is *moved*, not deleted.
//   - **unaccepted** is everything outside the surviving masks — what still needs looking at.
//   - **accepted** is the accepted region's own match, on the same scale and explicitly not
//     comparable to the other two by subtraction. A reader wanting "what did acceptance buy" reads
//     `unaccepted` against `raw`, which is a signed effect and can legitimately go either way.
//
// And a row per acceptance, because a single aggregate cannot express a mixed-validity set. An
// acceptance that is `invalidated` or `refused` suppresses nothing and is the row worth reading:
// it is a known difference that has stopped matching what was recorded, which is the whole
// lifecycle signal this workflow exists to produce.

import { ControllerElement, customElement } from "../controllerElement.js";
import { Fragment, h, render, type VNode, type VNodeChild } from "../vue.js";
import {
    evaluateComparison,
    type AcceptanceReport,
    type AcceptanceStatus,
} from "../parity/acceptance.js";
import { whenParsed } from "../dom/whenParsed.js";

/** The payload the page embeds. Mirrors `KnownDifferenceContext` on the server. */
interface Payload {
    documentUrl: string;
    artifactBase: string;
    artifactQuery: string;
    referenceUrl: string;
    candidateUrl: string;
    issues: Array<{
        repository: string;
        number: number;
        state: "open" | "closed";
    }>;
    scope: {
        system: string;
        component: string;
        previewId: string;
        referenceId: string;
        variant: string;
        overrides: Record<string, string>;
        referenceSha256: string | null;
        tagIndex: Record<string, { count: number; bounds?: unknown }>;
    };
}

/**
 * How each status reads. `refused` and the four invalidation causes stay distinct because each has
 * a different fix.
 */
const STATUS_LABELS: Record<string, string> = {
    valid: "accepted",
    resolved:
        "appears resolved — the difference is gone and the acceptance can be removed",
    invalidated: "no longer matches — needs review",
    refused: "refused",
    "out-of-scope": "authored for another comparison",
};

@customElement("cp-acceptance")
export class Acceptance extends ControllerElement {
    private report: AcceptanceReport | null = null;
    private failed = false;

    private band: HTMLElement | null = null;
    private installed = false;
    private evaluation = 0;

    connectedCallback(): void {
        super.connectedCallback();
        if (!this.install()) void whenParsed().then(() => this.install());
    }

    /**
     * Find the band and payload, then evaluate. The band starts `hidden` so it never shows numbers
     * the engine has not measured.
     */
    private install(): boolean {
        if (!this.isConnected || this.installed) return true;
        const band = document.getElementById("cp-acceptance");
        const script = document.getElementById("cp-known-differences");
        if (!band || !script) return false;
        this.installed = true;
        this.band = band;
        let payload: Payload;
        try {
            payload = JSON.parse(script.textContent ?? "") as Payload;
        } catch {
            return true;
        }
        void this.evaluate(payload, ++this.evaluation);
        return true;
    }

    private async evaluate(
        payload: Payload,
        evaluation: number,
    ): Promise<void> {
        try {
            this.report = await evaluateComparison(
                {
                    documentUrl: payload.documentUrl,
                    artifactUrl: (path) =>
                        `${payload.artifactBase}${path}${payload.artifactQuery}`,
                    referenceUrl: payload.referenceUrl,
                    candidateUrl: payload.candidateUrl,
                },
                payload.scope,
                payload.scope.tagIndex,
                payload.issues ?? [],
            );
        } catch {
            // An engine that threw said nothing, so the band says nothing rather than reporting a
            // clean result.
            this.failed = true;
        }
        if (evaluation !== this.evaluation || !this.isConnected) return;
        this.paint();
    }

    override disconnectedCallback(): void {
        this.evaluation++;
        if (this.band) {
            render(null, this.band);
            this.band.hidden = true;
        }
        this.band = null;
        this.installed = false;
        this.report = null;
        this.failed = false;
        super.disconnectedCallback();
    }

    /**
     * Render into the server's band.
     *
     * Into it rather than into this element, for the reason `<cp-backend-badge>` does the same: the
     * `role="status"` live region is in the server's HTML, and a live region created by script with
     * its text already in place is not announced.
     */
    private paint(): void {
        if (!this.band) return;
        const content = this.content();
        if (content === null) {
            this.band.hidden = true;
            render(null, this.band);
            return;
        }
        this.band.hidden = false;
        render(content, this.band);
    }

    private content(): VNode | null {
        if (this.failed) {
            return h(
                "span",
                { class: "cp-acceptance-note" },
                "Known differences could not be evaluated.",
            );
        }
        const report = this.report;
        if (!report) return null;
        // `unavailable` is not `absent`: hiding the band would read as a clean bill of health.
        if (report.state === "unavailable") {
            return h(
                "span",
                { class: "cp-acceptance-note" },
                "This catalog publishes known differences, and they could not be fetched — nothing on this comparison has been evaluated against them.",
            );
        }
        if (report.state === "absent") return null;

        const rows = Object.entries(report.statuses).filter(
            ([, entry]) => entry.status !== "out-of-scope",
        );
        // A stalled comparison is not an empty one: with no pair, every acceptance comes back
        // `out-of-scope` (e.g. a transient 503 or a digest mismatch), which would otherwise hide
        // the band as if there were nothing to say.
        const stalled =
            report.pair === "unavailable" &&
            Object.keys(report.statuses).length > 0;
        // Every acceptance in the document belongs to some other comparison: this page has nothing
        // to say, and saying "0 accepted" on it would be noise on every comparison in the catalog.
        if (
            rows.length === 0 &&
            report.validationFailures.length === 0 &&
            !stalled
        ) {
            return null;
        }

        return h(Fragment, null, [
            this.scores(report),
            this.documentFailures(report),
            h(
                "ul",
                { class: "cp-acceptance-list" },
                rows.map(([id, entry]) => this.row(id, entry)),
            ),
        ]);
    }

    /**
     * Failures that belong to the document rather than any acceptance (malformed, duplicate id,
     * over the size ceiling). The engine marks these by omitting `statuses`; that, not the presence
     * of an `id`, is the selector, since `duplicate-id` carries an id like a per-record refusal.
     */
    private documentFailures(report: AcceptanceReport): VNode | null {
        if (!report.documentRejected) return null;
        // Attributed where the engine attributed it: `duplicate-id (glyph)` names the spelling to go
        // and look at, and `id-missing (#2)` the record that has no name to be called by.
        const reasons = report.validationFailures.map((failure) => {
            if (failure.id !== undefined)
                return `${failure.reason} (${failure.id})`;
            if (failure.index !== undefined)
                return `${failure.reason} (#${failure.index})`;
            return failure.reason;
        });
        if (reasons.length === 0) return null;
        return h(
            "span",
            { class: "cp-acceptance-row", "data-status": "refused" },
            `This catalog's known-difference document was refused (${reasons.join(", ")}), so nothing in it is being applied.`,
        );
    }

    private scores(report: AcceptanceReport): VNode | null {
        const scores = report.scores;
        if (!scores) {
            // No scores means nothing was measured against the catalog's known differences, not
            // that there was nothing to measure.
            return h(
                "span",
                { class: "cp-acceptance-note" },
                report.pair === "unavailable"
                    ? "The rendered pair could not be read as this page describes it, so nothing on this comparison has been evaluated against this catalog's known differences. Reloading usually resolves it."
                    : "This pair could not be scored, so only the acceptance verdicts are shown.",
            );
        }
        // `raw` first and always: it must never be hidden. Acceptance and the result line now share
        // the portable scoring path (D3), so the numbers agree.
        return h("span", { class: "cp-acceptance-scores" }, [
            h("strong", null, `${scores.raw.toFixed(1)}%`),
            " raw · ",
            h("strong", null, `${scores.unaccepted.toFixed(1)}%`),
            " unaccepted · ",
            h("strong", null, `${scores.accepted.toFixed(1)}%`),
            " over the accepted region",
        ]);
    }

    private row(id: string, entry: AcceptanceStatus): VNode {
        const label = STATUS_LABELS[entry.status] ?? entry.status;
        // The engine's own tokens, untranslated, because they are what an author greps for.
        const detail = [...(entry.causes ?? []), ...(entry.reasons ?? [])];
        const lifecycle = this.report?.lifecycles[id];
        const lifecycleLabel = lifecycle?.stale
            ? "stale configuration — the issue is closed while this acceptance is still live"
            : lifecycle?.lifecycle === "closed" && entry.status === "resolved"
              ? "verified; issue closed; remove the acceptance"
              : lifecycle?.lifecycle === "closed"
                ? "issue closed"
                : lifecycle?.lifecycle === "open"
                  ? "issue open"
                  : "issue state unknown";
        const children: VNodeChild[] = [h("code", null, id), ` — ${label}`];
        if (detail.length > 0) children.push(` (${detail.join(", ")})`);
        children.push(` · ${lifecycleLabel}`);
        return h(
            "li",
            {
                class: "cp-acceptance-row",
                "data-status": entry.status,
                "data-lifecycle": lifecycle?.lifecycle ?? "unknown",
                "data-stale": lifecycle?.stale ? "" : undefined,
            },
            children,
        );
    }
}
