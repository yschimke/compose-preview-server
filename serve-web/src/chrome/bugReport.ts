// Browser-side halves of "report a bug". They live in the page shell because the footer form is on
// every page (front door, `/status`, 404s), none of which load `main.js`; each no-ops when its
// elements are absent. Neither writes an `href` or navigates: they only set input values on
// server-rendered forms with literal actions (see `ServeIssueReport.action`).

import { whenReady } from "../dom/whenReady.js";

/** Params the report page needs from the visitor's current URL, and nothing else. */
const CARRIED = ["token"];

/**
 * Fill the footer form's hidden inputs so `/report-bug` knows where the visitor came from. `from`
 * is the address bar's path + query, not a server-baked value, because `installUrlState` rewrites
 * the query as knobs change. The token is copied separately because `/report-bug` is gated like
 * `/status`; the server strips it from `from` (`ServeBugReport.sanitizeFrom`), which is quoted into
 * a public issue.
 */
export function installBugReportLink(): void {
    whenReady(() => {
        fillBugReportLink();
        // …and again at submit: `installUrlState` rewrites the address bar on every
        // knob/device/theme change, so a value frozen at load would report the wrong overrides.
        document
            .querySelectorAll<HTMLFormElement>(".cp-report-bug")
            .forEach((form) =>
                form.addEventListener("submit", fillBugReportLink),
            );
    });
}

/**
 * The fill itself, separate from scheduling so tests can drive it. Fills every copy of the form
 * (the footer link and the floating launcher), not just the first match.
 */
export function fillBugReportLink(): void {
    document
        .querySelectorAll<HTMLFormElement>(".cp-report-bug")
        .forEach(fillOne);
}

function fillOne(form: HTMLFormElement): void {
    const from = form.querySelector<HTMLInputElement>('input[name="from"]');
    const token = form.querySelector<HTMLInputElement>('input[name="token"]');
    const scheme = form.querySelector<HTMLInputElement>('input[name="scheme"]');
    if (from) {
        const current = new URLSearchParams(location.search);
        CARRIED.forEach(function (name) {
            current.delete(name);
        });
        const query = current.toString();
        from.value = location.pathname + (query ? `?${query}` : "");
    }
    if (token)
        token.value = new URLSearchParams(location.search).get("token") ?? "";
    // Captured here because `/report-bug` cannot recover it: the OS preference alone mislabels
    // "dark preview on a light OS".
    if (scheme) scheme.value = pageScheme();
}

/**
 * The scheme this page is actually painted in: the `cp-scheme-light`/`cp-scheme-dark` class on
 * `<html>` (see `pageTheme`), falling back to `prefers-color-scheme` only when nothing is pinned.
 */
export function pageScheme(): string {
    const root = document.documentElement;
    if (root.classList.contains("cp-scheme-dark")) return "dark";
    if (root.classList.contains("cp-scheme-light")) return "light";
    return osScheme();
}

function osScheme(): string {
    return typeof window.matchMedia === "function" &&
        window.matchMedia("(prefers-color-scheme: dark)").matches
        ? "dark"
        : "light";
}

/**
 * The carried `?scheme=`, allowlisted to its two valid values: it comes from a URL and lands in a
 * markdown table cell, so `dark|forged` would shear the row. Anything else falls back to this
 * page's own scheme.
 */
function knownScheme(value: string | null): string | undefined {
    return value === "light" || value === "dark" ? value : undefined;
}

/**
 * On `/report-bug`, splice the browser's own facts into the `{{client}}` slot of the server-filled
 * body (so a JS-off visitor still files the server part). Pixel ratio and scheme are what the
 * server cannot observe. The visible `<pre>` is rewritten from the same string so what is shown is
 * what is filed.
 */
export function installBugReportBody(): void {
    whenReady(fillBugReportBody);
}

/** The fill itself, separated from the scheduling so tests can drive it against a built DOM. */
export function fillBugReportBody(): void {
    const body = document.querySelector<HTMLInputElement>("#cp-bug-body");
    if (!body) return;
    const template = body.getAttribute("data-report-template");
    if (!template) return;
    // The reported page's scheme from the footer form; absent when `/report-bug` was opened
    // directly.
    const reported = knownScheme(
        new URLSearchParams(location.search).get("scheme"),
    );
    // A function replacement, because a string replacement honours `$&`, `$'` and `$1`.
    const filled = template.replace("{{client}}", () => clientBlock(reported));
    body.value = filled;
    const preview = document.querySelector<HTMLElement>("#cp-bug-preview");
    if (preview) preview.textContent = filled;
}

/**
 * The browser section as a two-column markdown table. [reportedScheme] defaults to this page's own.
 */
export function clientBlock(reportedScheme?: string): string {
    const rows = clientRows(reportedScheme);
    if (!rows.length) return "";
    return (
        "### Browser\n\n| | |\n| --- | --- |\n" +
        rows.map((row) => `| ${row[0]} | ${row[1]} |`).join("\n") +
        "\n"
    );
}

/**
 * Make free text safe in a markdown table cell inside a code span. Backslash is escaped first so
 * the later escapes are not double-escaped; `|` would shear the row and a backtick close the span.
 */
function cell(text: string): string {
    return text
        .replace(/\\/g, "\\\\")
        .replace(/\|/g, "\\|")
        .replace(/`/g, "\\`");
}

function clientRows(reportedScheme?: string): string[][] {
    const rows: string[][] = [];
    const ua = navigator.userAgent;
    if (ua) rows.push(["User agent", "`" + cell(ua) + "`"]);
    if (window.innerWidth && window.innerHeight) {
        rows.push([
            "Viewport",
            `${window.innerWidth}×${window.innerHeight} CSS px`,
        ]);
    }
    if (window.devicePixelRatio) {
        rows.push(["Device pixel ratio", String(window.devicePixelRatio)]);
    }
    // Both, labelled apart: the page's scheme produced the pixels, the OS preference is what a
    // triager would otherwise assume.
    rows.push(["Page colour scheme", reportedScheme || pageScheme()]);
    rows.push(["OS colour scheme", osScheme()]);
    return rows;
}
