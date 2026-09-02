// Scope metadata inside compose-parity-locator/v1. Older locators omit it and are component-wide.

export type ReportScope = "component" | "variant";

/** Read the first valid scope carried by a fenced locator in [body]. */
export function scopeFromBody(body: string): ReportScope | null {
    const lines = body.split("\n");
    let inside = false;
    for (const line of lines) {
        const value = line.trim();
        if (value === "```compose-parity-locator/v1") {
            inside = true;
            continue;
        }
        if (inside && value === "```") {
            inside = false;
            continue;
        }
        if (!inside || !line.startsWith("scope:")) continue;
        const scope = line.slice("scope:".length).trim();
        if (scope === "component" || scope === "variant") return scope;
    }
    return null;
}

/** Add or replace `scope:` in every locator fence in a report body. */
export function withScope(body: string, scope: ReportScope): string {
    const lines = body.split("\n");
    let inside = false;
    for (let i = 0; i < lines.length; i += 1) {
        if (lines[i].trim() === "```compose-parity-locator/v1") {
            inside = true;
            continue;
        }
        if (inside && lines[i].trim() === "```") {
            inside = false;
            continue;
        }
        if (!inside || !lines[i].startsWith("component:")) continue;
        if (lines[i + 1]?.startsWith("scope:"))
            lines[i + 1] = `scope: ${scope}`;
        else lines.splice(i + 1, 0, `scope: ${scope}`);
    }
    return lines.join("\n");
}
