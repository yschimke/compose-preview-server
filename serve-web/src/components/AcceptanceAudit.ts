// `<cp-acceptance-audit>` — the catalog-wide view of accepted differences on the design parity
// dashboard. `<cp-acceptance>` evaluates one comparison, so an acceptance whose target was removed
// or renamed (`orphaned-target`) never shows there; this runs `walkCatalog` against the preview
// inventory with no comparison. It is a validation-only pass (no rasters, so healthy records come
// back `out-of-scope`), so it reports:
//
// - **refusals and orphans** — document facts needing no comparison;
// - **the lifecycle join** — acceptances whose tracking issue is closed while the record remains;
// - **never a verdict** — matching is per-comparison and stays on the comparison page.

import { ControllerElement, customElement } from "../controllerElement.js";
import { Fragment, h, render, type VNode, type VNodeChild } from "../vue.js";
import { walkCatalog, type AcceptanceReport } from "../parity/acceptance.js";
import type { Catalog } from "../parity/engine.js";
import { whenParsed } from "../dom/whenParsed.js";

/** The payload the dashboard embeds. Mirrors `KnownDifferenceAuditContext` on the server. */
interface Payload {
    documentUrl: string;
    artifactBase: string;
    artifactQuery: string;
    previews: Catalog["previews"];
    issues: Array<{
        repository: string;
        number: number;
        state: "open" | "closed";
    }>;
}

/**
 * How a walk's statuses read. `out-of-scope` is omitted: in a validation-only pass it is every
 * healthy record.
 */
const STATUS_LABELS: Record<string, string> = {
    refused: "refused",
    invalidated: "no longer matches — needs review",
    resolved: "appears resolved — the acceptance can be removed",
    valid: "accepted",
};

/**
 * `orphaned-target` is a reason under `refused`, and the one this panel exists for, so it gets a
 * sentence; other tokens stay verbatim for grepping.
 */
const ORPHANED = "orphaned-target";

@customElement("cp-acceptance-audit")
export class AcceptanceAudit extends ControllerElement {
    private report: AcceptanceReport | null = null;
    private failed = false;

    private band: HTMLElement | null = null;
    private issueHref: Record<string, string> = {};
    private installed = false;
    private evaluation = 0;

    connectedCallback(): void {
        super.connectedCallback();
        if (!this.install()) void whenParsed().then(() => this.install());
    }

    private install(): boolean {
        if (!this.isConnected || this.installed) return true;
        const band = document.getElementById("cp-acceptance-audit");
        const script = document.getElementById("cp-known-difference-audit");
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
        for (const issue of payload.issues ?? []) {
            this.issueHref[
                `${issue.repository.toLowerCase()}#${issue.number}`
            ] = `https://github.com/${issue.repository}/issues/${issue.number}`;
        }
        try {
            this.report = await walkCatalog(
                {
                    documentUrl: payload.documentUrl,
                    artifactUrl: (path) =>
                        `${payload.artifactBase}${path}${payload.artifactQuery}`,
                },
                { previews: payload.previews ?? [] },
                payload.issues ?? [],
            );
        } catch {
            // An engine that threw audited nothing, so no "0 problems".
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
        this.issueHref = {};
        super.disconnectedCallback();
    }

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
            return h(Fragment, null, [
                h("h2", { class: "cp-status-sec" }, "Known differences"),
                h(
                    "p",
                    { class: "cp-muted" },
                    "This catalog's known differences could not be audited.",
                ),
            ]);
        }
        const report = this.report;
        if (!report) return null;
        // `unavailable` is not `absent`: the panel only mounts for catalogs that publish a
        // document.
        if (report.state === "unavailable") {
            return h(Fragment, null, [
                h("h2", { class: "cp-status-sec" }, "Known differences"),
                h(
                    "p",
                    { class: "cp-muted" },
                    "This catalog publishes known differences, and they could not be fetched — nothing here has been audited against them.",
                ),
            ]);
        }
        if (report.state === "absent") return null;
        if (report.documentRejected) {
            const reasons = report.validationFailures.map((failure) =>
                failure.id !== undefined
                    ? `${failure.reason} (${failure.id})`
                    : failure.index !== undefined
                      ? `${failure.reason} (#${failure.index})`
                      : failure.reason,
            );
            const reason = reasons.length > 0 ? ` (${reasons.join(", ")})` : "";
            return h(Fragment, null, [
                h("h2", { class: "cp-status-sec" }, "Known differences"),
                h(
                    "p",
                    {
                        class: "cp-acceptance-row",
                        "data-status": "refused",
                    },
                    `This catalog's known-difference document was refused${reason}, so nothing in it is being applied on any comparison.`,
                ),
            ]);
        }

        const entries = Object.entries(report.statuses);
        if (entries.length === 0) return null;
        // Two independent axes joined here: `status` from the walk, `lifecycle` from the issue
        // index. A record can be in both lists.
        const problems = entries.filter(
            ([, entry]) => entry.status !== "out-of-scope",
        );
        const closed = entries.filter(
            ([id]) => report.lifecycles[id]?.lifecycle === "closed",
        );
        return h(Fragment, null, [
            h(
                "h2",
                { class: "cp-status-sec" },
                `Known differences (${entries.length})`,
            ),
            h(
                "p",
                { class: "cp-muted" },
                "Differences this catalog has accepted against a tracking issue. Each one is still measured on its own comparison — this panel is the document, not the verdict.",
            ),
            this.problems(problems),
            this.closed(closed, report),
            problems.length === 0 && closed.length === 0
                ? this.allClear(entries, report)
                : null,
        ]);
    }

    /**
     * The nothing-to-do line, claiming only what the evidence supports: an `unknown` lifecycle (the
     * index is fail-soft, capped and can lag) is missing evidence, not an open issue.
     */
    private allClear(
        entries: Array<[string, AcceptanceReport["statuses"][string]]>,
        report: AcceptanceReport,
    ): VNode {
        const unknown = entries.filter(
            ([id]) =>
                (report.lifecycles[id]?.lifecycle ?? "unknown") !== "open",
        ).length;
        const suffix =
            unknown > 0
                ? ` The issue index says nothing about ${unknown} of them, so their issues are unknown rather than open.`
                : "";
        return h(
            "p",
            { class: "cp-muted" },
            `Every accepted difference names a component this catalog still has, and no tracking issue is reported closed.${suffix}`,
        );
    }

    /** Refusals and orphans, which a validation-only pass can stand behind. */
    private problems(
        rows: Array<[string, AcceptanceReport["statuses"][string]]>,
    ): VNode | null {
        if (rows.length === 0) return null;
        const items = rows.map(([id, entry]) => {
            const detail = [...(entry.causes ?? []), ...(entry.reasons ?? [])];
            const orphaned = detail.includes(ORPHANED);
            const rest = detail.filter((token) => token !== ORPHANED);
            const label = orphaned
                ? "names a preview, reference, component or variant this catalog no longer has"
                : (STATUS_LABELS[entry.status] ?? entry.status);
            const children: VNodeChild[] = [h("code", null, id), ` — ${label}`];
            if (rest.length > 0) children.push(` (${rest.join(", ")})`);
            return h(
                "li",
                {
                    class: "cp-acceptance-row",
                    "data-status": entry.status,
                    "data-orphaned": orphaned ? "" : undefined,
                },
                children,
            );
        });
        return h(Fragment, null, [
            h(
                "h3",
                { class: "cp-parity-sub" },
                `Needs attention (${rows.length})`,
            ),
            h("ul", { class: "cp-acceptance-list" }, items),
        ]);
    }

    /**
     * Acceptances whose tracking issue is closed while the record is still committed. Positive
     * evidence only: absence from the index stays `unknown`.
     */
    private closed(
        rows: Array<[string, AcceptanceReport["statuses"][string]]>,
        report: AcceptanceReport,
    ): VNode | null {
        if (rows.length === 0) return null;
        const items = rows.map(([id]) => {
            const issue = report.lifecycles[id]?.issue ?? null;
            const href = issue ? this.issueHref[issue] : undefined;
            const issueNode = href
                ? h("a", { href, rel: "noopener" }, issue ?? "")
                : issue
                  ? h("code", null, issue)
                  : "its tracking issue";
            return h(
                "li",
                {
                    class: "cp-acceptance-row",
                    "data-status": "stale",
                    "data-lifecycle": "closed",
                },
                [h("code", null, id), " — ", issueNode, " is closed"],
            );
        });
        return h(Fragment, null, [
            h(
                "h3",
                { class: "cp-parity-sub" },
                `Closed issue, acceptance still committed (${rows.length})`,
            ),
            h(
                "p",
                { class: "cp-muted" },
                "The loop finishes by deleting the acceptance in the same change that closes its issue. These are the halves left behind.",
            ),
            h("ul", { class: "cp-acceptance-list" }, items),
        ]);
    }
}
