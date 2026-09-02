// Scope metadata inside compose-parity-locator/v1. Older locators omit it and are component-wide.

export type ReportScope = "component" | "variant";

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
