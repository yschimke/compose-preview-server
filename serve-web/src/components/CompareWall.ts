// `<cp-compare-wall>`: the `/compare` wall, every component's baked PNG beside the same render in
// another format, scored. SVG and Remote Compose lanes compare a render against an export of that
// render (shared geometry, bare percentage). The reference lane compares against independently
// drawn design artwork, so it alone carries a proportion figure and a middle column: the delta map,
// from the same normalised frames as the percentage.
//
// Scoring belongs to `format-compare.js` via `compare/api.ts`; this element owns pairing, page
// state, filtering and order. Renders nothing itself (`serve.css` hides the tag). Decisions live in
// `compare/pairing.ts`, `compare/state.ts`, `compare/wallRows.ts` and `compare/grade.ts`.

import { ControllerElement, customElement } from "../controllerElement.js";
import { compareApi } from "../compare/api.js";
import { compareImageUrls } from "../compare/detail.js";
import { grade } from "../compare/grade.js";
import {
    rowTheme,
    variantFor,
    type Available,
    type Format,
} from "../compare/pairing.js";
import { specLeadsColumns, targetHeadLabel } from "../compare/columns.js";
import {
    aliasesFor,
    readAliasTable,
    type AliasTable,
} from "../compare/aliases.js";
import {
    locatorBlocks,
    locatorForRow,
    type PickFacts,
} from "../compare/picks.js";
import { reportBody } from "../report/body.js";
import { readThemeMemory, writeThemeMemory } from "../chrome/themeMemory.js";
import { initialState, poppedState, type WallState } from "../compare/state.js";
import { GEOMETRY_REPORT_THRESHOLD } from "../compare/thresholds.js";
import {
    bakedScoreOf,
    byWorstFirst,
    byWorstKnownFirst,
    countLabel,
    keepRow,
    scoreOf,
} from "../compare/wallRows.js";
import "../chrome/pageTheme.js";
import { whenParsed } from "../dom/whenParsed.js";
// Types only: the player bundle is script-injected at runtime, never imported.

/**
 * Longest side the wall keeps a delta map at. Unlike the detail page's single map, a wall holds one
 * per row (hundreds) drawn in a ~200px column, so full-size maps would waste memory and could
 * exceed canvas limits. 440 is twice the tallest drawn size, crisp at 2× DPR.
 */
const MAP_MAX_SIDE = 440;

/**
 * How far ahead of the viewport a row is measured: two screens, enough to hide latency at a normal
 * scroll pace without measuring a whole large catalog on open.
 */
const MEASURE_MARGIN = "200% 0px";

@customElement("cp-compare-wall")
export class CompareWall extends ControllerElement {
    private installed = false;
    private root!: HTMLElement;
    private rows: HTMLElement[] = [];
    private body: HTMLElement | null = null;
    private formatButtons: HTMLElement[] = [];
    private themeButtons: HTMLElement[] = [];
    private search: HTMLInputElement | null = null;
    private count: HTMLElement | null = null;
    private empty: HTMLElement | null = null;
    /** The published Remote Compose player wall, when this catalog has one. */
    private lanesPane: HTMLElement | null = null;
    private formatsPane: HTMLElement | null = null;
    /** The two picture columns' headers, so the pair can swap sides and stay named. */
    private renderHead: HTMLElement | null = null;
    private targetHead: HTMLElement | null = null;
    /** The middle column's header, which rides between the pair wherever the pair goes. */
    private diffHead: HTMLElement | null = null;
    /** The page's alias table, read on the first filter pass. See `compare/aliases.ts`. */
    private aliases: AliasTable | null = null;
    /** The wall's own table, which carries `data-picking` for the row checkboxes' visibility. */
    private table: HTMLElement | null = null;
    /** The page-scoped report's hidden body field, when this wall can write locators into it. */
    private reportField: HTMLInputElement | null = null;
    /** The page-level halves of a locator, or null on a wall with no report to file. */
    private pickFacts: PickFacts | null = null;
    private pickedBar: HTMLElement | null = null;
    private pickedText: HTMLElement | null = null;

    private available: Available = {
        svg: false,
        rc: false,
        reference: false,
        parallel: false,
    };
    private state!: WallState;
    /** What Back falls back to on an entry naming no format or theme: what THIS load resolved to. */
    private initial!: WallState;
    /** Bumped per run, so a slow lane cannot write its scores over a newer one's. */
    private sequence = 0;
    /**
     * Watches for rows nearing the viewport. Null without `IntersectionObserver`; see {@link
     * measureAll}.
     */
    private viewport: IntersectionObserver | null = null;
    private cleanups: Array<() => void> = [];

    connectedCallback(): void {
        super.connectedCallback();
        if (!this.install()) void whenParsed().then(() => this.install());
    }

    disconnectedCallback(): void {
        for (const off of this.cleanups) off();
        this.cleanups = [];
        this.viewport?.disconnect();
        this.viewport = null;
        this.installed = false;
        this.sequence++;
        super.disconnectedCallback();
    }

    private install(): boolean {
        if (!this.isConnected || this.installed) return true;
        const root = document.getElementById("cp-compare");
        if (!root) return false;
        this.installed = true;
        this.root = root;

        this.formatButtons = Array.from(
            root.querySelectorAll<HTMLElement>("[data-compare-format]"),
        );
        this.themeButtons = Array.from(
            root.querySelectorAll<HTMLElement>("[data-compare-theme]"),
        );
        this.rows = Array.from(
            root.querySelectorAll<HTMLElement>(".cp-compare-row"),
        );
        this.body = root.querySelector("#cp-compare-formats tbody");
        this.renderHead = root.querySelector(".cp-compare-render-head");
        this.targetHead = root.querySelector(".cp-compare-target-head");
        this.diffHead = root.querySelector(".cp-compare-diff-head");
        this.lanesPane = document.getElementById("cp-rc-lanes");
        this.formatsPane = document.getElementById("cp-compare-formats");
        this.count = document.getElementById("cp-compare-count");
        this.search =
            document.querySelector<HTMLInputElement>("#cp-compare-search");
        this.empty = document.getElementById("cp-compare-empty");
        this.table = root.querySelector(".cp-compare-table");
        this.resolvePicking();

        this.available = {
            svg: root.getAttribute("data-has-svg") === "1",
            rc: root.getAttribute("data-has-rc") === "1",
            reference: root.getAttribute("data-has-reference") === "1",
            parallel: root.getAttribute("data-has-parallel") === "1",
        };

        this.state = initialState({
            defaults: {
                format: root.getAttribute("data-default-format") ?? "svg",
                theme: root.getAttribute("data-default-theme") ?? "light",
            },
            remembered: this.remembered(),
            params: new URLSearchParams(location.search),
            available: this.available,
        });
        this.initial = { ...this.state };
        if (this.search) this.search.value = this.state.query;

        this.wire();
        this.run();
        return true;
    }

    private on(
        target: EventTarget,
        type: string,
        handler: EventListener,
    ): void {
        target.addEventListener(type, handler);
        this.cleanups.push(() => target.removeEventListener(type, handler));
    }

    private themeKey(): string {
        return this.root.getAttribute("data-theme-key") ?? "";
    }

    private remembered(): string | null {
        // Per-tab, like every other theme choice on this site; blocked storage falls back to the
        // page's default.
        return readThemeMemory(this.themeKey()) || null;
    }

    private wire(): void {
        for (const button of this.formatButtons) {
            this.on(button, "click", () => {
                const picked = button.getAttribute("data-compare-format");
                if (
                    picked !== "svg" &&
                    picked !== "rc" &&
                    picked !== "reference" &&
                    picked !== "parallel"
                )
                    return;
                this.state.format = picked;
                // A discrete pick gets its own history entry, so Back returns to the format the
                // visitor was comparing before rather than out of the page entirely.
                window.cpUrlState?.push({ format: picked });
                this.run();
            });
        }
        for (const button of this.themeButtons) {
            this.on(button, "click", () => {
                const picked = button.getAttribute("data-compare-theme");
                if (picked !== "light" && picked !== "dark") return;
                this.state.theme = picked;
                // Not remembering is survivable; not comparing is not.
                writeThemeMemory(this.themeKey(), picked);
                window.cpUrlState?.push({ theme: picked });
                // Paint the page to match the theme being compared, when the visitor's Page theme
                // setting asks for that.
                window.cpPageTheme?.follow(picked);
                this.run();
            });
        }
        if (this.search) {
            // Typing REPLACES rather than pushes: the filter stays bookmarkable without one history
            // entry per keystroke.
            this.on(this.search, "input", () => {
                this.state.query = this.search?.value ?? "";
                window.cpUrlState?.replace({ q: this.state.query.trim() });
                this.applySearch();
            });
        }
        for (const box of this.pickInputs()) {
            this.on(box, "change", () => this.syncPicks());
        }
        const clear = this.pickedBar?.querySelector<HTMLElement>(
            ".cp-compare-picked-clear",
        );
        if (clear) {
            this.on(clear, "click", () => {
                for (const box of this.pickInputs()) box.checked = false;
                this.syncPicks();
            });
        }
        const off = window.cpUrlState?.onPop(() => {
            this.state = poppedState({
                initial: this.initial,
                params: new URLSearchParams(location.search),
                available: this.available,
            });
            if (this.search) this.search.value = this.state.query;
            window.cpPageTheme?.follow(this.state.theme);
            this.run();
        });
        if (off) this.cleanups.push(off);
    }

    /** Whether the published player wall is standing in for the client-rendered `rc` lane. */
    private lanesActive(): boolean {
        return Boolean(this.lanesPane) && this.state.format === "rc";
    }

    // ---- the multi-row picker -------------------------------------------------------------------

    /**
     * Read the page-level locator halves off the wall's report, once. `ServeWeb.reportIssueHtml`
     * writes `data-cp-locator-system` only where the template has a `{{locators}}` line, so its
     * presence is the switch; without it [pickFacts] stays null and the checkboxes stay hidden.
     */
    private resolvePicking(): void {
        this.pickedBar = document.getElementById("cp-compare-picked");
        this.pickedText =
            this.pickedBar?.querySelector(".cp-compare-picked-text") ?? null;
        const report = document.getElementById("cp-report");
        const repository = report?.getAttribute("data-cp-repo") ?? "";
        const system = report?.getAttribute("data-cp-locator-system") ?? "";
        const field =
            document.querySelector<HTMLInputElement>("#cp-report-body");
        if (!repository || !system || !field) return;
        this.reportField = field;
        this.pickFacts = {
            repository,
            system,
            revision: report?.getAttribute("data-cp-locator-revision") || null,
        };
    }

    private pickInputs(): HTMLInputElement[] {
        return this.rows.flatMap((row) => {
            const box = row.querySelector<HTMLInputElement>(
                ".cp-compare-pick-input",
            );
            return box ? [box] : [];
        });
    }

    /** Whether this wall, in the lane it is showing, can name comparisons at all. */
    private picking(): boolean {
        return Boolean(this.pickFacts) && this.state.format === "reference";
    }

    /**
     * The locator a row would contribute now, or null. Read from the row's "+ file" href, which
     * {@link dressRow} already re-pointed at the shown pair, so picks follow the lane and theme.
     */
    private locatorFor(row: HTMLElement) {
        const facts = this.pickFacts;
        if (!facts) return null;
        const report = row.querySelector<HTMLAnchorElement>(
            ".cp-compare-bug-new",
        );
        const detail = report?.getAttribute("href") ?? "";
        return locatorForRow(
            detail,
            location.href,
            row.getAttribute("data-component-id") ?? "",
            facts,
        );
    }

    /**
     * Recompute what is picked, hand it to the report, and say so. The producer refuses a whole
     * body that names one component twice, so once one variant is ticked its siblings' checkboxes
     * are disabled (with a title saying why) until it is unticked.
     */
    private syncPicks(): void {
        const field = this.reportField;
        if (!field) return;
        const picking = this.picking();
        const claimed = new Set<string>();
        const locators = [];
        for (const row of this.rows) {
            const box = row.querySelector<HTMLInputElement>(
                ".cp-compare-pick-input",
            );
            if (!box) continue;
            const locator = picking ? this.locatorFor(row) : null;
            if (!locator) {
                // Nothing this row can contribute in this lane: untick as well as disable.
                box.checked = false;
                box.disabled = true;
                box.title = picking
                    ? "This lane has no design reference for this comparison"
                    : "";
                continue;
            }
            // Clear before the second pass: the one-component-once disabling is derived, and a box
            // left disabled from the last pass would never be re-enabled.
            box.disabled = false;
            box.title = "";
            if (box.checked) {
                claimed.add(locator.componentId);
                locators.push(locator);
            }
        }
        for (const row of this.rows) {
            const box = row.querySelector<HTMLInputElement>(
                ".cp-compare-pick-input",
            );
            if (!box || box.disabled) continue;
            const component = row.getAttribute("data-component-id") ?? "";
            const taken = !box.checked && claimed.has(component);
            box.disabled = taken;
            box.title = taken
                ? "One report can name a component once — another variant of this one is already picked"
                : "";
        }
        const blocks = locatorBlocks(locators);
        if (reportBody.attach(field)) reportBody.set({ locators: blocks });
        this.showPicked(blocks.length);
    }

    /** The line above the report that says what it will name. Hidden at zero. */
    private showPicked(count: number): void {
        if (this.pickedBar) this.pickedBar.hidden = count === 0;
        if (this.pickedText) {
            this.pickedText.textContent =
                count === 1
                    ? "1 comparison will be named in the report below."
                    : `${count} comparisons will be named in the report below.`;
        }
    }

    private sourcesOf(row: HTMLElement) {
        return (kind: string, variant: string) =>
            row.getAttribute(`data-${kind}-${variant}`) ?? "";
    }

    // ---- the run -------------------------------------------------------------

    private run(): void {
        const runId = ++this.sequence;
        for (const button of this.formatButtons) {
            button.setAttribute(
                "aria-pressed",
                String(
                    button.getAttribute("data-compare-format") ===
                        this.state.format,
                ),
            );
        }
        for (const button of this.themeButtons) {
            button.setAttribute(
                "aria-pressed",
                String(
                    button.getAttribute("data-compare-theme") ===
                        this.state.theme,
                ),
            );
        }
        this.root.setAttribute("data-format", this.state.format);
        this.root.setAttribute("data-theme", this.state.theme);
        this.orderColumns();
        // Off the reference lane there is no design reference to name, so the checkboxes go and
        // `syncPicks` drops any ticks.
        if (this.table) {
            if (this.picking()) this.table.setAttribute("data-picking", "on");
            else this.table.removeAttribute("data-picking");
        }

        // The lane wall is its own view with offline-computed numbers; hand it the filter and stop,
        // rather than decode documents for an invisible table.
        if (this.lanesPane) this.lanesPane.hidden = !this.lanesActive();
        if (this.formatsPane) this.formatsPane.hidden = this.lanesActive();
        if (this.lanesActive()) {
            this.applySearch();
            return;
        }

        // Dressing (pictures, grounds, links, published scores; see {@link dressRow}) happens
        // before scoring, driven from {@link applySearch} so only visible rows fetch images; a
        // `?preview=` link showing one row shouldn't decode the whole wall. Reset per run, since a
        // format or theme switch changes each row's pair.
        this.dressedRows = new Set();
        this.applySearch();
        const visible = this.rows.filter((row) => !row.hidden);
        // Order on the published numbers before measuring. A no-op on first load (the server
        // already ordered the reference lane); matters on lane and theme switches.
        visible.sort((a, b) =>
            byWorstKnownFirst(this.bakedScore(a), this.bakedScore(b)),
        );
        for (const row of visible) this.body?.appendChild(row);
        this.measureAll(visible, runId);
    }

    /**
     * Measure [visible] viewport-first: a row is measured when it comes within {@link
     * MEASURE_MARGIN} of the viewport. Each measurement fetches and decodes two full frames and
     * scores them on the main thread, so measuring every row up front took minutes on large
     * catalogs. Results are unchanged: same serial chain, and the wall re-sorts once every visible
     * row is measured. Without `IntersectionObserver` everything is measured up front.
     */
    private measureAll(visible: HTMLElement[], runId: number): void {
        // Disconnect the previous run's observer, or every row would be enqueued once per lane.
        this.viewport?.disconnect();
        this.viewport = null;

        let measured = 0;
        const settle = (): void => {
            if (runId !== this.sequence) return;
            // Re-sort only after the whole wall is measured, so rows don't move under the reader
            // mid-pass.
            if (++measured < visible.length) return;
            visible.sort((a, b) =>
                byWorstFirst(
                    scoreOf(a.getAttribute("data-score")),
                    scoreOf(b.getAttribute("data-score")),
                ),
            );
            for (const row of visible) this.body?.appendChild(row);
            this.applySearch();
        };

        let chain: Promise<unknown> = Promise.resolve();
        const enqueue = (row: HTMLElement): void => {
            chain = chain
                .then(() => this.scoreRow(row, runId))
                .then(settle, settle);
        };

        if (typeof IntersectionObserver === "undefined") {
            for (const row of visible) enqueue(row);
            return;
        }

        // A row can be reported intersecting more than once before its callback runs; the set
        // prevents measuring it twice.
        const queued = new Set<HTMLElement>();
        const observer = new IntersectionObserver(
            (entries) => {
                for (const entry of entries) {
                    if (!entry.isIntersecting) continue;
                    const row = entry.target as HTMLElement;
                    observer.unobserve(row);
                    if (queued.has(row)) continue;
                    queued.add(row);
                    enqueue(row);
                }
            },
            { rootMargin: MEASURE_MARGIN },
        );
        this.viewport = observer;
        for (const row of visible) observer.observe(row);
    }

    /**
     * Put the design spec left of the render on the reference lane. The server orders columns for
     * its default format; moving the cells (not CSS order) keeps header, picture and DOM agreeing.
     * Idempotent.
     */
    private orderColumns(): void {
        const specFirst = specLeadsColumns(this.state.format);
        if (this.targetHead) {
            // The name node, not the whole `<th>`, which also carries the unchanging
            // `cp-compare-head-role` line.
            const name =
                this.targetHead.querySelector<HTMLElement>(
                    ".cp-compare-head-name",
                ) ?? this.targetHead;
            name.textContent = targetHeadLabel(
                this.state.format,
                this.root.getAttribute("data-reference-label") ?? "",
                this.root.getAttribute("data-parallel-label") ?? "",
            );
            lead(this.targetHead, this.renderHead, specFirst, this.diffHead);
        }
        for (const row of this.rows) {
            lead(
                cellOf(row, ".cp-compare-target-cell"),
                cellOf(row, ".cp-compare-render-cell"),
                specFirst,
                cellOf(row, ".cp-compare-diff-cell"),
            );
        }
    }

    /**
     * The published score for the pair this row currently shows, if any. Reference lane only: the
     * number describes render-vs-design, and using it on the SVG lane would seed and order by an
     * unrelated comparison.
     */
    private bakedScore(row: HTMLElement): number | null {
        if (this.state.format !== "reference") return null;
        const variant = variantFor(
            this.sourcesOf(row),
            "reference",
            this.state.theme,
        );
        if (!variant) return null;
        return bakedScoreOf(row.getAttribute(`data-match-${variant}`));
    }

    /**
     * Put the published score on a row before measuring, so the wall is readable and ordered at
     * first paint instead of "waiting…" in catalog order. The delivery branch measured these pairs
     * with the same scorer (`design-reference-score.mjs`). `data-score-source` records the origin
     * (for `serve.css`, the failure path in {@link scoreRow}, and tests).
     */
    private seedScore(row: HTMLElement, score: HTMLElement): void {
        const baked = this.bakedScore(row);
        if (baked === null) {
            row.removeAttribute("data-score");
            score.removeAttribute("data-score-source");
            return;
        }
        row.setAttribute("data-score", String(baked));
        score.textContent = `${baked.toFixed(1)}%`;
        score.className = `cp-compare-score cp-compare-score--${grade(baked)}`;
        score.setAttribute("data-score-source", "published");
        score.title =
            "Measured when this catalog was published — re-measured here as the row loads";
    }

    private applySearch(): void {
        const query = this.search?.value ?? "";
        if (this.lanesActive()) {
            window.cpRcLanes?.filter(query);
            return;
        }
        // Re-read per pass rather than resolved once at load: the viewer links in with `?preview=`,
        // and a Back to such an entry has to re-narrow.
        const params = new URLSearchParams(location.search);
        const preview = params.get("preview") ?? "";
        const component = params.get("component") ?? "";
        // Parsed once per filter pass, not once per row: it is one JSON island for the whole page.
        const aliases = (this.aliases ??= readAliasTable(document));
        let visible = 0;
        for (const row of this.rows) {
            const hasFormat = Boolean(
                variantFor(
                    this.sourcesOf(row),
                    this.state.format,
                    this.state.theme,
                ),
            );
            // A row this lane can't pair is unscoreable, so its cell must stop saying "waiting…"
            // (it would never change, and would be wrong once revealed). It also unblocks anything
            // waiting for every cell to settle, like the capture harness.
            if (!hasFormat) this.markUnpairable(row);
            const keep = keepRow(
                {
                    hay: row.getAttribute("data-hay") ?? "",
                    // The wall subtracts the ids that have rows of their own — see
                    // `compare/aliases.ts` for why that rule lives on this side of the pair.
                    previewIds: aliasesFor(
                        aliases,
                        row.getAttribute("data-preview-ids") ?? "",
                        row.getAttribute("data-alias-card"),
                        true,
                    ).join(" "),
                    componentId: row.getAttribute("data-component-id") ?? "",
                    hasFormat,
                },
                query,
                preview,
                component,
            );
            // Dressed here, only when visible (see {@link run}); also covers rows revealed by a
            // later filter change.
            const show = keep && this.ensureDressed(row);
            row.hidden = !show;
            if (show) visible++;
        }
        if (this.count) this.count.textContent = countLabel(visible);
        if (this.empty) this.empty.hidden = visible !== 0;
        this.showScope(component || preview, visible);
        // After dressing: a row's locator reads the "+ file" href {@link dressRow} just re-pointed.
        this.syncPicks();
    }

    /**
     * Blank the score of a row the current lane has no pair for: `—`, a third state distinct from
     * "being measured" and "measured badly", using the scorer's `--na` band.
     */
    private markUnpairable(row: HTMLElement): void {
        // Unconditional and idempotent; a "done" flag would need clearing on every path that writes
        // a score.
        const score = row.querySelector<HTMLElement>(".cp-compare-score");
        if (!score) return;
        score.textContent = "—";
        score.className = "cp-compare-score cp-compare-score--na";
        score.removeAttribute("data-score-source");
        score.title = "This lane has nothing to compare this preview against";
    }

    /**
     * Show that the wall is scoped (`?component=` / `?preview=`), naming the scope and linking to
     * the unscoped view. Server-rendered `hidden` and revealed here, so it never claims a filter no
     * script applied.
     */
    private showScope(scope: string, visible: number): void {
        const bar = this.root.querySelector<HTMLElement>("#cp-compare-scope");
        if (!bar) return;
        if (!scope) {
            bar.hidden = true;
            return;
        }
        const kept = this.rows.find((row) => !row.hidden);
        const name =
            kept?.getAttribute("data-component-label")?.trim() ||
            kept?.getAttribute("data-label")?.trim() ||
            scope;
        const text = bar.querySelector<HTMLElement>(".cp-compare-scope-text");
        if (text) {
            text.textContent = `${name} · ${countLabel(visible)} of ${this.rows.length}`;
        }
        const clear = bar.querySelector<HTMLAnchorElement>(
            ".cp-compare-scope-clear",
        );
        if (clear) {
            const params = new URLSearchParams(location.search);
            params.delete("component");
            params.delete("preview");
            const query = params.toString();
            clear.href = location.pathname + (query ? `?${query}` : "");
        }
        bar.hidden = false;
    }

    // ---- one row -------------------------------------------------------------

    /**
     * Rows already dressed for the current format and theme, so {@link applySearch} can dress newly
     * revealed ones. Reset by {@link run}.
     */
    private dressedRows = new Set<HTMLElement>();

    /**
     * {@link dressRow} once per row per run. False when the row can't be paired in this format (the
     * caller hides it). Failures aren't remembered; retrying costs a cheap sweep.
     */
    private ensureDressed(row: HTMLElement): boolean {
        if (this.dressedRows.has(row)) return true;
        if (!this.dressRow(row)) return false;
        this.dressedRows.add(row);
        return true;
    }

    /**
     * Everything a row shows without measuring: its pair, ground, published score and links. Run
     * before the measuring chain rather than inside it, so a wall isn't blank until each row is
     * scored. False for a row this format can't pair or whose markup is incomplete.
     */
    private dressRow(row: HTMLElement): boolean {
        const sources = this.sourcesOf(row);
        const variant = variantFor(
            sources,
            this.state.format,
            this.state.theme,
        );
        const pngUrl = sources("png", variant);
        const candidateUrl = sources(this.state.format, variant);
        const score = row.querySelector<HTMLElement>(".cp-compare-score");
        const png = row.querySelector<HTMLImageElement>(".cp-compare-png");
        const vector =
            row.querySelector<HTMLImageElement>(".cp-compare-vector");
        // Two canvases per row (delta map and RC lane), so select by name; a bare `canvas` query
        // would get the diff.
        const canvas = row.querySelector<HTMLCanvasElement>(".cp-compare-rc");
        const diff = row.querySelector<HTMLCanvasElement>(".cp-compare-diff");
        if (!pngUrl || !candidateUrl || !score || !png || !vector || !canvas)
            return false;
        row.setAttribute(
            "data-bg-theme",
            rowTheme(
                variant,
                this.root.getAttribute("data-default-theme") ?? "",
                row.getAttribute(`data-declared-bg-${variant}`),
            ),
        );
        png.src = pngUrl;
        png.alt = `${row.getAttribute("data-label")} rendered PNG`;
        stampSize(row, "png", png);
        this.seedScore(row, score);

        const format = this.state.format;
        const detail = () => {
            location.href = sources("reference-detail", variant);
        };
        // The Bugs column's "+ file" follows the shown pair, since the focused page files against
        // that preview and reference. Off the reference lane it falls back to the viewer's report.
        const report = row.querySelector<HTMLAnchorElement>(
            ".cp-compare-bug-new",
        );
        if (report) {
            const focused =
                format === "reference"
                    ? sources("reference-detail", variant)
                    : "";
            report.href = focused || report.dataset.bugFallback || report.href;
        }
        // Exact issue pills follow the shown preview variant (the server serializes every theme
        // variant); component-wide pills stay visible.
        const activePreview = sources("preview", variant);
        for (const issue of row.querySelectorAll<HTMLElement>(
            '[data-bug-scope="variant"]',
        )) {
            const ids = (issue.dataset.bugPreviewIds ?? "")
                .split(/\s+/)
                .filter(Boolean);
            issue.hidden = !activePreview || !ids.includes(activePreview);
        }
        if (
            format === "svg" ||
            format === "reference" ||
            format === "parallel"
        ) {
            vector.hidden = false;
            canvas.hidden = true;
            vector.src = candidateUrl;
            stampSize(row, "target", vector);
            vector.alt = `${row.getAttribute("data-label")}${
                format === "svg"
                    ? " SVG"
                    : format === "parallel"
                      ? " parallel implementation"
                      : " design reference"
            }`;
            vector.title =
                format === "reference" ? "Open Reference / Diff / Actual" : "";
            vector.onclick = format === "reference" ? detail : null;
        } else {
            vector.hidden = true;
            canvas.hidden = false;
            // The RC lane paints a canvas with no file size, so clear the caption.
            stampSize(row, "target", null);
        }
        if (diff) {
            // Blank the map before the run: it's only redrawn on success, so a failed or switched
            // row would keep the previous pair's magenta.
            diff.width = 0;
            diff.height = 0;
            diff.setAttribute(
                "aria-label",
                `${row.getAttribute("data-label")} difference from the design reference`,
            );
            diff.title =
                format === "reference" ? "Open Reference / Diff / Actual" : "";
            diff.onclick = format === "reference" ? detail : null;
        }
        return true;
    }

    private async scoreRow(row: HTMLElement, runId: number): Promise<void> {
        // Touch nothing unless this chain is current. Bumping `sequence` stops an abandoned run's
        // results, but its chain keeps walking and would otherwise blank rows the visitor is
        // reading.
        if (runId !== this.sequence) return;
        const sources = this.sourcesOf(row);
        const variant = variantFor(
            sources,
            this.state.format,
            this.state.theme,
        );
        const pngUrl = sources("png", variant);
        const candidateUrl = sources(this.state.format, variant);
        const score = row.querySelector<HTMLElement>(".cp-compare-score");
        const canvas = row.querySelector<HTMLCanvasElement>(".cp-compare-rc");
        const diff = row.querySelector<HTMLCanvasElement>(".cp-compare-diff");
        if (!pngUrl || !candidateUrl || !score || !canvas) return;
        // A row with a published score keeps showing it while being measured.
        if (!row.hasAttribute("data-score")) {
            score.textContent = "comparing…";
            score.className = "cp-compare-score";
        }
        const format = this.state.format;

        try {
            const measured = await this.measure(
                format,
                pngUrl,
                candidateUrl,
                canvas,
                Boolean(diff),
            );
            if (runId !== this.sequence) return;
            // Only now does the map reach the row: `measure` drew it into its own canvas, so an
            // abandoned chain's late result can't paint stale magenta after the current check.
            if (diff && measured.map) this.paintMap(measured.map, diff);
            row.setAttribute("data-score", String(measured.percent));
            score.textContent = `${measured.percent.toFixed(1)}%`;
            score.className = `cp-compare-score cp-compare-score--${grade(measured.percent)}`;
            // Measured now, so the published marking and its note go.
            score.removeAttribute("data-score-source");
            score.title = "";
            if (typeof measured.geometry === "number") {
                row.setAttribute(
                    "data-geometry-delta",
                    measured.geometry.toFixed(2),
                );
                score.title =
                    measured.geometry >= GEOMETRY_REPORT_THRESHOLD
                        ? `${measured.geometry.toFixed(1)}% proportion difference between the two content boxes`
                        : "";
            } else {
                row.removeAttribute("data-geometry-delta");
            }
        } catch {
            if (runId !== this.sequence) return;
            // A published score survives a failed measurement: the failure is about this browser
            // (fetch, canvas), not the pair.
            if (score.getAttribute("data-score-source") === "published") return;
            // `-1` rather than dropping the row: an unmeasurable pair sorts to the top, because it
            // is the one nobody is looking at.
            row.setAttribute("data-score", "-1");
            row.removeAttribute("data-geometry-delta");
            score.textContent = "unavailable";
            score.className = "cp-compare-score cp-compare-score--na";
        }
    }

    /**
     * The comparison handle, read here rather than at install: `format-compare.js` loads after the
     * components bundle, so a handle cached at upgrade would be `null`.
     */
    private async measure(
        format: Format,
        pngUrl: string,
        candidateUrl: string,
        canvas: HTMLCanvasElement,
        withMap: boolean,
    ): Promise<{
        percent: number;
        geometry?: number;
        map?: HTMLCanvasElement;
    }> {
        const compare = compareApi();
        if (!compare) throw new Error("no scorer");
        // Vector lanes share the render's geometry by construction and report a bare percentage;
        // only the reference lane carries a geometry figure.
        if (format === "svg") {
            return {
                percent: await compare.scoreSvgUrls(pngUrl, candidateUrl),
            };
        }
        if (format === "reference" || format === "parallel") {
            if (!withMap) return compare.scoreImageUrls(candidateUrl, pngUrl);
            // Normalise the pair once, then diff and score those frames, so the map and percentage
            // describe the same pixels. Painted into a canvas owned by this call and copied in once
            // the run is validated, then discarded.
            const map = document.createElement("canvas");
            const result = await compareImageUrls(
                compare,
                candidateUrl,
                pngUrl,
                map,
                MAP_MAX_SIDE,
            );
            return { percent: result.score, geometry: result.geometry, map };
        }
        return {
            percent: await this.renderRc(pngUrl, candidateUrl, canvas, compare),
        };
    }

    /**
     * Copy a measured delta map into the row's canvas, bounded to {@link MAP_MAX_SIDE}. Dimensions
     * are set before the context is requested, so a row reports a painted map even without a 2D
     * context.
     */
    private paintMap(
        source: HTMLCanvasElement,
        target: HTMLCanvasElement,
    ): void {
        const scale = Math.min(
            1,
            MAP_MAX_SIDE / Math.max(source.width, source.height, 1),
        );
        target.width = Math.max(1, Math.round(source.width * scale));
        target.height = Math.max(1, Math.round(source.height * scale));
        const context = target.getContext("2d");
        if (!context) return;
        context.clearRect(0, 0, target.width, target.height);
        context.drawImage(source, 0, 0, target.width, target.height);
    }

    // ---- the client-rendered Remote Compose lane -----------------------------

    private ensureRcPlayer(): Promise<void> {
        if (window.RC) return Promise.resolve();
        return new Promise((resolve, reject) => {
            const existing = document.querySelector(
                "script[data-cp-rc-compare]",
            );
            if (existing) {
                existing.addEventListener("load", () => resolve(), {
                    once: true,
                });
                existing.addEventListener(
                    "error",
                    () => reject(new Error("rc")),
                    {
                        once: true,
                    },
                );
                return;
            }
            const script = document.createElement("script");
            script.src = "/rc-player/bundle.js";
            script.setAttribute("data-cp-rc-compare", "1");
            script.onload = () => resolve();
            script.onerror = () => reject(new Error("rc"));
            document.head.appendChild(script);
        });
    }

    private nextFrame(): Promise<void> {
        return new Promise((resolve) => requestAnimationFrame(() => resolve()));
    }

    private async renderRc(
        pngUrl: string,
        documentUrl: string,
        canvas: HTMLCanvasElement,
        compare: NonNullable<ReturnType<typeof compareApi>>,
    ): Promise<number> {
        // Await `cpRcFonts.ready()`: a canvas neither triggers lazy `@font-face` loads nor
        // repaints, so the document would be scored in the visitor's own sans-serif against a
        // Roboto PNG.
        const [, png, response] = await Promise.all([
            this.ensureRcPlayer(),
            compare.loadImage(pngUrl),
            fetch(documentUrl),
            window.cpRcFonts?.ready() ?? Promise.resolve(),
        ]);
        if (!response.ok) throw new Error(`RC ${response.status}`);
        canvas.width = png.naturalWidth || png.width;
        canvas.height = png.naturalHeight || png.height;
        const buffer = await response.arrayBuffer();

        const player = new window.RC!.RcdPlayer(canvas);
        // Artifact theme is an explicit comparison input; it must not inherit the site's or the OS's
        // `prefers-color-scheme`, or a light PNG gets scored against a dark RC canvas.
        player.setTheme(this.state.theme);
        await player.loadFromArrayBuffer(buffer);
        player.repaint?.();
        // The first paint is what DISCOVERS the named font families. Wait for those faces, repaint
        // with the resolved glyphs, and only then take the single-shot measurement.
        await player.fontsReady();
        player.repaint?.();
        await this.nextFrame();
        await this.nextFrame();
        return compare.scoreCanvas(pngUrl, canvas);
    }
}

declare global {
    interface HTMLElementTagNameMap {
        "cp-compare-wall": CompareWall;
    }
}

/** A row's picture cell, by its own class — position is what we are about to change. */
/**
 * Caption a picture's own pixel size under its fixed frame (panels are a fixed box, so scale
 * differences aren't visible). Read from the decoded raster since the wall chooses the theme
 * variant. `null` clears it (canvas lanes have no file size).
 */
function stampSize(
    row: HTMLElement,
    which: "png" | "target",
    image: HTMLImageElement | null,
): void {
    const cell = row.querySelector<HTMLElement>(
        `.cp-compare-dim[data-dim-for="${which}"]`,
    );
    if (!cell) return;
    if (!image) {
        cell.textContent = "";
        return;
    }
    const write = () => {
        const w = image.naturalWidth;
        const h = image.naturalHeight;
        cell.textContent = w && h ? `${w} × ${h}` : "";
    };
    cell.textContent = "";
    if (image.complete) write();
    else image.addEventListener("load", write, { once: true });
}

function cellOf(row: HTMLElement, selector: string): HTMLElement | null {
    return row.querySelector<HTMLElement>(selector);
}

/**
 * Ensure [spec] precedes [render] when [specFirst], else follows it, with [middle] (the delta map)
 * between them. Both must be present siblings, or the row is left untouched. [middle] is ignored if
 * absent or elsewhere.
 */
function lead(
    spec: HTMLElement | null,
    render: HTMLElement | null,
    specFirst: boolean,
    middle: HTMLElement | null = null,
): void {
    if (!spec || !render || spec === render) return;
    const parent = spec.parentElement;
    if (!parent || render.parentElement !== parent) return;
    const [first, second] = specFirst ? [spec, render] : [render, spec];
    const seat = middle?.parentElement === parent ? middle : null;
    if (!seat) {
        if (first.nextElementSibling === second) return;
        parent.insertBefore(first, second);
        return;
    }
    if (first.nextElementSibling === seat && seat.nextElementSibling === second)
        return;
    parent.insertBefore(first, second);
    parent.insertBefore(seat, second);
}
